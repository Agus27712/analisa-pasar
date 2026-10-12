#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Saringan pasar Tokocrypto: pair mana yang punya MFE cukup besar relatif biaya?

Logika: biaya round-trip 0,9688% sama untuk semua pair USDT. Yang berbeda
adalah seberapa jauh harga bergerak. Jadi kita cari pair dengan
  capture_wajib = biaya / MFE_median  <-- sekecil mungkin.
"""
import argparse
import csv
import json
import os
import statistics as st
import time

import requests

BASE = "https://www.tokocrypto.site/api/v3"
UA = {"User-Agent": "Mozilla/5.0 TokoClient/3.5"}
FEE_BUY = 0.4044
FEE_SELL = 0.4044
SLIP = 0.08
COST = FEE_BUY + FEE_SELL + 2.0 * SLIP  # 0.9688 %


def get(path, params=None, tries=4):
    for k in range(tries):
        try:
            r = requests.get(f"{BASE}/{path}", params=params, headers=UA, timeout=25)
            if r.status_code == 200:
                return r.json()
        except Exception:
            pass
        time.sleep(0.8 * (k + 1))
    return None


def quant(v, q):
    v = sorted(v)
    if not v:
        return float("nan")
    i = q * (len(v) - 1)
    lo, hi = int(i), min(int(i) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (i - lo)


def klines(symbol, interval, limit=1000):
    d = get("klines", {"symbol": symbol, "interval": interval, "limit": limit})
    if not d:
        return []
    return [{"t": int(x[0]), "o": float(x[1]), "h": float(x[2]),
             "l": float(x[3]), "c": float(x[4]), "v": float(x[5])} for x in d]


def mfe_mae(bars, T):
    mfe, mae = [], []
    n = len(bars)
    for i in range(0, n - T):
        e = bars[i]["c"]
        if e <= 0:
            continue
        hi = max(b["h"] for b in bars[i + 1:i + 1 + T])
        lo = min(b["l"] for b in bars[i + 1:i + 1 + T])
        mfe.append(100.0 * (hi - e) / e)
        mae.append(100.0 * (lo - e) / e)
    return mfe, mae


def blind(bars, T, p):
    nets = []
    for i in range(0, len(bars) - T):
        e = bars[i]["c"]
        if e <= 0:
            continue
        tp, sl = e * (1 + p / 100.0), e * (1 - p / 100.0)
        res = None
        for b in bars[i + 1:i + 1 + T]:
            if b["l"] <= sl:
                res = -p
                break
            if b["h"] >= tp:
                res = p
                break
        if res is None:
            res = 100.0 * (bars[i + T]["c"] - e) / e
        nets.append(res - COST)
    return st.mean(nets) if nets else float("nan")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=60, help="Ambil N pair terlikuid")
    ap.add_argument("--min-vol", type=float, default=300_000,
                    help="Volume 24 jam minimum (USDT) agar slippage 0,08% realistis")
    ap.add_argument("--out-json", default="data/hasil/p6_pair_screen.json")
    ap.add_argument("--out-md", default="data/hasil/p6_pair_screen.md")
    a = ap.parse_args()

    info = get("exchangeInfo")
    tick = {}
    tradable = set()
    for s in info["symbols"]:
        if s.get("quoteAsset") == "USDT" and s.get("status") == "TRADING":
            tradable.add(s["symbol"])
            for f in s.get("filters", []):
                if f["filterType"] == "PRICE_FILTER":
                    tick[s["symbol"]] = float(f["tickSize"])

    tk = get("ticker/24hr")
    rows = []
    for t in tk:
        sym = t["symbol"]
        if sym not in tradable:
            continue
        try:
            qv = float(t["quoteVolume"])
            last = float(t["lastPrice"])
        except Exception:
            continue
        if qv < a.min_vol or last <= 0:
            continue
        rows.append({"symbol": sym, "quoteVolume": qv, "price": last,
                     "chg24": float(t.get("priceChangePercent", 0) or 0)})
    rows.sort(key=lambda x: -x["quoteVolume"])
    cand = rows[:a.top]
    print(f"Kandidat: {len(cand)} pair (dari {len(tradable)} pair USDT tradable, "
          f"vol >= {a.min_vol:,.0f} USDT)")

    results = []
    for i, c in enumerate(cand, 1):
        sym = c["symbol"]
        d1 = klines(sym, "1d", 1000)
        time.sleep(0.12)
        h1 = klines(sym, "1h", 1000)
        time.sleep(0.12)
        if len(d1) < 120 or len(h1) < 300:
            print(f"  [{i}/{len(cand)}] {sym}: data kurang, dilewati")
            continue

        tk_sz = tick.get(sym, 0.0)
        tick_pct = 100.0 * tk_sz / c["price"] if c["price"] > 0 else float("nan")

        ranges = [100.0 * (b["h"] - b["l"]) / b["o"] for b in d1 if b["o"] > 0]
        rng_med = st.median(ranges)
        # rezim 42 hari terakhir (sebanding dengan jendela data 1 jam)
        rng_42 = st.median([100.0 * (b["h"] - b["l"]) / b["o"] for b in d1[-42:] if b["o"] > 0])
        regime_heat = rng_42 / rng_med if rng_med else float("nan")

        m24, _ = mfe_mae(h1, 24)
        m7d, ma7 = mfe_mae(d1, 7)
        m1d, _ = mfe_mae(d1, 1)
        m30d, _ = mfe_mae(d1, 30)

        med24 = st.median(m24) if m24 else float("nan")
        med7 = st.median(m7d) if m7d else float("nan")
        med1d = st.median(m1d) if m1d else float("nan")
        med30 = st.median(m30d) if m30d else float("nan")

        # total gerakan absolut 1000 hari (untuk menilai apakah pair ini trending/choppy)
        tot = 100.0 * abs(d1[-1]["c"] - d1[0]["c"]) / d1[0]["o"]

        results.append({
            "symbol": sym,
            "price": c["price"],
            "quoteVolume": c["quoteVolume"],
            "chg24_pct": c["chg24"],
            "tick_pct": tick_pct,
            "range_med_pct": rng_med,
            "range_med_42d_pct": rng_42,
            "regime_heat": regime_heat,
            "range_over_cost": rng_med / COST,
            "mfe_1d_pct": med1d,
            "mfe_24h_pct": med24,
            "mfe_7d_pct": med7,
            "mfe_30d_pct": med30,
            "mae_7d_med_pct": st.median(ma7) if ma7 else float("nan"),
            "capture_1d_pct": 100.0 * COST / med1d if med1d else float("inf"),
            "capture_30d_pct": 100.0 * COST / med30 if med30 else float("inf"),
            "capture_24h_pct": 100.0 * COST / med24 if med24 else float("inf"),
            "capture_7d_pct": 100.0 * COST / med7 if med7 else float("inf"),
            "pct_mfe_gt_cost_24h": 100.0 * sum(1 for x in m24 if x > COST) / len(m24) if m24 else 0,
            "pct_mfe_gt_cost_7d": 100.0 * sum(1 for x in m7d if x > COST) / len(m7d) if m7d else 0,
            "blind_7d_10pct_net": blind(d1, 7, 10.0),
            "total_move_1000d_pct": tot,
            "n_d1": len(d1), "n_h1": len(h1),
        })
        print(f"  [{i}/{len(cand)}] {sym:>12} vol {c['quoteVolume']/1e6:8.2f}M  "
              f"MFE24j {med24:6.3f}%  capture {100.0*COST/med24 if med24 else 0:6.0f}%  "
              f"MFE7h {med7:6.3f}%  capture {100.0*COST/med7 if med7 else 0:5.0f}%")

    # buang pair dengan tick terlalu kasar (seperti kasus TRX)
    for r in results:
        r["tick_ok"] = (r["tick_pct"] < 0.05) if r["tick_pct"] == r["tick_pct"] else False
        r["not_pegged"] = r["range_med_pct"] >= 0.35
        r["history_ok"] = r["n_d1"] >= 200

    good = [r for r in results if r["tick_ok"] and r["not_pegged"]]
    good.sort(key=lambda x: x["capture_7d_pct"])

    os.makedirs(os.path.dirname(a.out_json), exist_ok=True)
    with open(a.out_json, "w") as f:
        json.dump({"cost_roundtrip_pct": COST, "screened": len(results),
                   "results": results, "ranking_7d": good}, f, indent=2)

    # ---------- markdown ----------
    L = []
    A = L.append
    A("# Saringan Pair Tokocrypto — MFE vs Biaya Bursa")
    A("")
    A(f"Biaya round-trip semua pair USDT: **{COST:.4f}%** (sama untuk semua).")
    A(f"Disaring **{len(results)} pair** terlikuid (volume 24 jam ≥ {a.min_vol:,.0f} USDT).")
    A("")
    A("> `capture wajib` = biaya ÷ MFE median. **Makin kecil makin baik.** "
      ">100% = mustahil profit walau exit sempurna.")
    A("")
    A("## Peringkat: peluang terbaik (diukur dari 1.000 bar harian)")
    A("")
    A("> **Penting:** kolom MFE di sini diukur dari **candle harian** (riwayat panjang), "
      "bukan dari data 1 jam yang hanya mencakup 42 hari. `heat` = perbandingan volatilitas "
      "42 hari terakhir terhadap rata-rata — nilai >1,3 berarti pair sedang jauh lebih panas "
      "dari biasanya, jadi angka MFE-nya bisa menyesatkan.")
    A("")
    A("| # | Pair | Harga | Vol 24j | Tick/harga | Range harian med | heat | **MFE 1h** | cap | **MFE 7h** | **cap** | MFE 30h | MAE 7h med |")
    A("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for i, r in enumerate(good[:40], 1):
        heat = f"{r['regime_heat']:.2f}" if r['regime_heat'] == r['regime_heat'] else "-"
        A(f"| {i} | **{r['symbol']}** | {r['price']:,.6g} | {r['quoteVolume']/1e6:,.2f}M | "
          f"{r['tick_pct']:.4f}% | {r['range_med_pct']:.2f}% | {heat} | "
          f"{r['mfe_1d_pct']:.2f}% | {r['capture_1d_pct']:.0f}% | "
          f"**{r['mfe_7d_pct']:.2f}%** | **{r['capture_7d_pct']:.0f}%** | "
          f"{r['mfe_30d_pct']:.2f}% | {r['mae_7d_med_pct']:.2f}% |")
    A("")
    A("## Pair dengan tick terlalu kasar (struktural tidak bisa ditradingkan)")
    A("")
    A("| Pair | Harga | Tick/harga | MFE 7h | Catatan |")
    A("|---|---:|---:|---:|---|")
    bad = [r for r in results if not r["tick_ok"]]
    bad.sort(key=lambda x: -x["tick_pct"])
    for r in bad[:20]:
        A(f"| {r['symbol']} | {r['price']:,.6g} | {r['tick_pct']:.3f}% | {r['mfe_7d_pct']:.2f}% | "
          f"1 tick = {r['tick_pct']/COST*100:.1f}% dari biaya |")
    A("")
    A("---")
    A("")
    A("Reproduksi:")
    A("```")
    A(f"python3 tools/screen_pairs.py --top {a.top} --min-vol {a.min_vol:.0f}")
    A("```")

    with open(a.out_md, "w") as f:
        f.write("\n".join(L) + "\n")

    print("")
    print(f"Tersimpan: {a.out_json}")
    print(f"Tersimpan: {a.out_md}")
    print("")
    print("TOP 15 (capture 7 hari terkecil):")
    for i, r in enumerate(good[:15], 1):
        print(f"  {i:2}. {r['symbol']:>12}  MFE7h {r['mfe_7d_pct']:6.2f}%  capture {r['capture_7d_pct']:5.0f}%  "
              f"MFE24j {r['mfe_24h_pct']:5.2f}%  capture24j {r['capture_24h_pct']:5.0f}%  vol {r['quoteVolume']/1e6:7.2f}M")


if __name__ == "__main__":
    main()
