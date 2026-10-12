#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ambil data NYATA Tokocrypto untuk SEMUA pair layak uji (bukan hanya 8 major).

Tujuan: memberi harness replay universe yang cukup luas agar bisa menjawab
"pair mana yang benar-benar punya gerakan > friksi?" dengan data, bukan asumsi.

Keluaran: <out>/<PAIR>_1m.csv, _15m.csv, _1h.csv
Kolom   : open_time_ms,open,high,low,close,volume   (hanya candle CLOSED)

Contoh:
    python3 tools/fetch_universe.py --quote USDT --days 30 --top 40
    python3 tools/fetch_universe.py --quote IDR  --days 30 --top 30
"""
import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = "https://www.tokocrypto.site/api/v3"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TokoClient/3.5"
INTERVALS = {"1m": 60_000, "15m": 15 * 60_000, "1h": 60 * 60_000, "4h": 4 * 60 * 60_000, "1d": 24 * 60 * 60_000}


def http_get(url, tries=4):
    last = None
    for k in range(tries):
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=25) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(0.7 * (k + 1))
    raise RuntimeError(f"gagal: {last}")


def parse_rows(data):
    if isinstance(data, dict):
        data = data.get("data", [])
    out = []
    for r in data:
        try:
            out.append((int(r[0]), float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5])))
        except (ValueError, IndexError, TypeError):
            continue
    return out


def fetch(symbol, interval, pages):
    by_time = {}
    end_time = None
    step = INTERVALS[interval]
    for _ in range(pages):
        url = f"{BASE}/klines?symbol={symbol}&interval={interval}&limit=1000"
        if end_time is not None:
            url += f"&endTime={end_time}"
        rows = parse_rows(http_get(url))
        if not rows:
            break
        before = len(by_time)
        for r in rows:
            by_time[r[0]] = r
        if len(by_time) == before:
            break
        end_time = min(r[0] for r in rows) - 1
        time.sleep(0.12)
    now_ms = int(time.time() * 1000)
    return [by_time[k] for k in sorted(by_time) if k + step <= now_ms]


def write_csv(path, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["open_time_ms", "open", "high", "low", "close", "volume"])
        w.writerows(rows)


def universe(quote, top, min_vol):
    info = http_get(f"{BASE}/exchangeInfo")
    tradable, tick = set(), {}
    for s in info.get("symbols", []):
        if s.get("quoteAsset") == quote and s.get("status") == "TRADING":
            tradable.add(s["symbol"])
            for flt in s.get("filters", []):
                if flt.get("filterType") == "PRICE_FILTER":
                    tick[s["symbol"]] = float(flt.get("tickSize", 0) or 0)
    tk = http_get(f"{BASE}/ticker/24hr")
    rows = []
    for t in tk:
        sym = t.get("symbol")
        if sym not in tradable:
            continue
        try:
            qv = float(t["quoteVolume"])
            last = float(t["lastPrice"])
        except (KeyError, ValueError, TypeError):
            continue
        if last <= 0:
            continue
        rows.append({"symbol": sym, "quoteVolume": qv, "price": last, "tick": tick.get(sym, 0.0)})
    rows.sort(key=lambda x: -x["quoteVolume"])
    # buang pair dengan tick terlalu kasar (>=0,05% per tick = struktural tak bisa ditradingkan)
    filtered = [r for r in rows if r["price"] > 0 and 100.0 * r["tick"] / r["price"] < 0.05]
    return [r for r in filtered if r["quoteVolume"] >= min_vol][:top]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quote", default="USDT", help="USDT atau IDR")
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--min-vol", type=float, default=100_000)
    ap.add_argument("--days", type=float, default=30.0, help="Panjang riwayat M1 yang diambil")
    ap.add_argument("--out", default="")
    ap.add_argument("--pairs", default="", help="Override daftar pair (dipisah koma)")
    ap.add_argument("--skip-1m", action="store_true")
    a = ap.parse_args()

    out = a.out or f"data/tokocrypto_live_{a.quote.lower()}"
    os.makedirs(out, exist_ok=True)

    if a.pairs:
        cand = [{"symbol": p.strip().upper(), "quoteVolume": 0.0, "price": 0.0, "tick": 0.0}
                for p in a.pairs.split(",") if p.strip()]
    else:
        cand = universe(a.quote, a.top, a.min_vol)

    print(f"Universe {a.quote}: {len(cand)} pair -> {out}", flush=True)
    m1_pages = max(1, int(a.days * 1440 / 1000) + 2)
    m15_pages = max(1, int(a.days * 96 / 1000) + 2)
    h1_pages = max(1, int(a.days * 24 / 1000) + 2)

    manifest = []
    ok = 0
    for i, c in enumerate(cand, 1):
        sym = c["symbol"]
        try:
            got = {}
            if not a.skip_1m:
                rows = fetch(sym, "1m", m1_pages)
                write_csv(os.path.join(out, f"{sym}_1m.csv"), rows)
                got["m1"] = len(rows)
            rows15 = fetch(sym, "15m", m15_pages)
            write_csv(os.path.join(out, f"{sym}_15m.csv"), rows15)
            got["m15"] = len(rows15)
            rows1h = fetch(sym, "1h", h1_pages)
            write_csv(os.path.join(out, f"{sym}_1h.csv"), rows1h)
            got["h1"] = len(rows1h)
            manifest.append({"symbol": sym, "quoteVolume": c["quoteVolume"], "price": c["price"],
                             "tick_pct": 100.0 * c["tick"] / c["price"] if c["price"] else None, **got})
            print(f"  [{i}/{len(cand)}] {sym:>14} m1={got.get('m1', 0):>6} m15={got['m15']:>5} h1={got['h1']:>5}", flush=True)
            ok += 1
        except Exception as e:  # noqa: BLE001
            print(f"  [{i}/{len(cand)}] {sym:>14} GAGAL: {e}", file=sys.stderr, flush=True)

    with open(os.path.join(out, "_manifest.json"), "w") as f:
        json.dump({"quote": a.quote, "days": a.days, "fetched_at": int(time.time() * 1000),
                   "pairs": manifest}, f, indent=2)
    print(f"Selesai: {ok}/{len(cand)} pair -> {out}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
