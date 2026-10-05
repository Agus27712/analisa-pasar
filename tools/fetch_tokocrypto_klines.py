#!/usr/bin/env python3
"""
Ambil candle NYATA dari API Tokocrypto (type 1 market data) lalu simpan sebagai CSV.

Jalankan dari jaringan di Indonesia (Tokocrypto memblokir IP luar negeri dengan HTTP 451,
termasuk runner GitHub Actions). Hanya butuh Python 3 (library standar), tanpa pip.

Contoh:
    python tools/fetch_tokocrypto_klines.py
    python tools/fetch_tokocrypto_klines.py --pairs BTCUSDT,ETHUSDT --pages 5 --out data/tokocrypto

Hasil: <out>/<PAIR>_1m.csv, <PAIR>_15m.csv, <PAIR>_1h.csv
Kolom : open_time_ms,open,high,low,close,volume   (hanya candle yang sudah CLOSED)
"""
import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request

DEFAULT_BASE = "https://www.tokocrypto.site/api/v3"
DEFAULT_PAIRS = "BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT,DOGEUSDT,ADAUSDT,TRXUSDT"
INTERVALS = {"1m": 60_000, "15m": 15 * 60_000, "1h": 60 * 60_000}
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TokoClient/3.5"


def http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "ignore")[:120]
        raise RuntimeError(f"HTTP {e.code} {body}")


def parse_rows(text):
    data = json.loads(text)
    if isinstance(data, dict):
        data = data.get("data", [])
    rows = []
    for r in data:
        try:
            rows.append((int(r[0]), float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5])))
        except (ValueError, IndexError, TypeError):
            continue
    return rows


def fetch(base, symbol, interval, pages):
    by_time = {}
    end_time = None
    for _ in range(pages):
        url = f"{base}/klines?symbol={symbol}&interval={interval}&limit=1000"
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
        time.sleep(0.15)
    now_ms = int(time.time() * 1000)
    step = INTERVALS[interval]
    return [by_time[k] for k in sorted(by_time) if k + step <= now_ms]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", default=DEFAULT_PAIRS, help="Dipisah koma, format BTCUSDT")
    ap.add_argument("--pages", type=int, default=5, help="Halaman M1 @1000 candle (5 = sekitar 3,5 hari)")
    ap.add_argument("--out", default="data/tokocrypto")
    ap.add_argument("--base", default=DEFAULT_BASE)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    ok = 0
    for sym in [p.strip().upper() for p in args.pairs.split(",") if p.strip()]:
        try:
            for interval, pages in (("1m", args.pages), ("15m", 1), ("1h", 1)):
                rows = fetch(args.base, sym, interval, pages)
                path = os.path.join(args.out, f"{sym}_{interval}.csv")
                with open(path, "w", newline="") as f:
                    w = csv.writer(f)
                    w.writerow(["open_time_ms", "open", "high", "low", "close", "volume"])
                    w.writerows(rows)
                print(f"{sym} {interval}: {len(rows)} candle -> {path}")
            ok += 1
        except Exception as e:  # noqa: BLE001
            print(f"{sym}: GAGAL - {e}", file=sys.stderr)
    print(f"Selesai: {ok} pair berhasil.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
