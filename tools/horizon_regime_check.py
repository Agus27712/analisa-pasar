#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P4c -- Uji Ketahanan Rezim: apakah ekspektasi positif di horizon harian itu
EDGE atau sekadar DRIFT pasar (beta)?

Cara uji
--------
Jalankan barrier test buta (tanpa sinyal) pada horizon panjang, lalu pecah
hasilnya per tahun kalender, dan bandingkan sisi LONG vs SHORT.

- Jika LONG untung dan SHORT rugi secara konsisten -> itu DRIFT/beta, bukan edge.
- Jika keduanya mendekati nol, atau tidak konsisten antar tahun -> tidak ada
  keunggulan struktural; strategi harus punya sinyal arah sendiri.

Biaya: FEE_TRUTH.md, round-trip all-in USDT.
"""

import argparse
import csv
import json
import os
import statistics as st
import datetime as dt

FEE_BUY = 0.4044
FEE_SELL = 0.4044
SLIP = 0.08
COST = FEE_BUY + FEE_SELL + 2.0 * SLIP  # 0.9688 %


def load_bars(path):
    ts, o, h, l, c = [], [], [], [], []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            ts.append(int(row["open_time_ms"]))
            o.append(float(row["open"]))
            h.append(float(row["high"]))
            l.append(float(row["low"]))
            c.append(float(row["close"]))
    return ts, o, h, l, c


def resolve(h, l, c, i, T, p, direction):
    """Return gross % (belum dikurangi biaya)."""
    e = c[i]
    if direction == "long":
        tp, sl = e * (1 + p / 100.0), e * (1 - p / 100.0)
        for j in range(i + 1, i + 1 + T):
            if l[j] <= sl:
                return -p
            if h[j] >= tp:
                return p
    else:
        tp, sl = e * (1 - p / 100.0), e * (1 + p / 100.0)
        for j in range(i + 1, i + 1 + T):
            if h[j] >= sl:
                return -p
            if l[j] <= tp:
                return p
    return (c[i + T] - e) / e * 100.0 * (1 if direction == "long" else -1)


def collect(data, T, p, direction):
    """Return list of (year, gross_pct)."""
    out = []
    for sym, (ts, o, h, l, c) in data.items():
        n = len(c)
        i = 0
        while i + T < n:
            yr = dt.datetime.utcfromtimestamp(ts[i] / 1000).year
            out.append((yr, resolve(h, l, c, i, T, p, direction)))
            i += T
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/tokocrypto_v5")
    ap.add_argument("--interval", default="1d")
    ap.add_argument("--t-days", default="7,30")
    ap.add_argument("--p-list", default="8,12,20")
    ap.add_argument("--out-json", default="data/hasil/p4c_regime_check.json")
    args = ap.parse_args()

    suffix = f"_{args.interval}.csv"
    symbols = sorted(f.replace(suffix, "") for f in os.listdir(args.data) if f.endswith(suffix))
    data = {s: load_bars(os.path.join(args.data, f"{s}{suffix}")) for s in symbols}
    print(f"Simbol: {', '.join(symbols)}")
    print(f"Biaya : {COST:.4f}% round-trip\n")

    result = {"cost_roundtrip_pct": COST, "cases": []}
    for T in [int(x) for x in args.t_days.split(",")]:
        for p in [float(x) for x in args.p_list.split(",")]:
            longv = collect(data, T, p, "long")
            shortv = collect(data, T, p, "short")
            el = st.mean([g - COST for _, g in longv])
            es = st.mean([g - COST for _, g in shortv])
            print(f"=== T={T}d  TP=SL=±{p:g}%  (n={len(longv):,}) ===")
            print(f"  LONG  E[net]={el:+.3f}%   SHORT E[net]={es:+.3f}%   "
                  f"selisih(L-S)={el-es:+.3f}%")
            years = sorted({y for y, _ in longv})
            rows = []
            print(f"  {'tahun':<6} {'n':>5} {'LONG':>9} {'SHORT':>9} {'selisih':>9}")
            for y in years:
                lv = [g - COST for yy, g in longv if yy == y]
                sv = [g - COST for yy, g in shortv if yy == y]
                if not lv:
                    continue
                ml, ms = st.mean(lv), st.mean(sv)
                rows.append({"year": y, "n": len(lv), "long_net_pct": ml,
                             "short_net_pct": ms, "diff_pct": ml - ms})
                print(f"  {y:<6} {len(lv):>5} {ml:>+8.2f}% {ms:>+8.2f}% {ml-ms:>+8.2f}%")
            pos = sum(1 for r in rows if r["long_net_pct"] > 0)
            print(f"  -> tahun LONG positif: {pos}/{len(rows)}\n")
            result["cases"].append({
                "T_days": T, "tp_sl_pct": p, "n": len(longv),
                "long_net_pct": el, "short_net_pct": es, "long_minus_short_pct": el - es,
                "years_long_positive": pos, "years_total": len(rows), "per_year": rows,
            })

    os.makedirs(os.path.dirname(args.out_json), exist_ok=True)
    with open(args.out_json, "w") as f:
        json.dump(result, f, indent=2)
    print(f"JSON -> {args.out_json}")


if __name__ == "__main__":
    main()
