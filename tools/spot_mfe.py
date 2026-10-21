#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Hitung MFE/MAE median pada dataset 15m Tokocrypto (Spec Scalping Spot v2).

Definisi (sama dengan tools/universe_replay.py, hanya beda timeframe):
  untuk setiap candle sampel k, entry = close[k]
  MFE = max(high[k+1 .. k+T]) / close[k] - 1
  MAE = min(low[k+1 .. k+T]) / close[k] - 1
  T   = horizon dalam bar (7 jam = 28 bar 15m)

Median MFE 7 jam dibandingkan dengan friksi round-trip F (0,9688%). Aturan gerbang
PairEdgeGate tetap: layak bila median MFE >= 2,0 x F.

Contoh:
  python3 tools/spot_mfe.py --data data/tokocrypto_spot \
      --out data/hasil/mfe_m15_spot.json
"""
import argparse
import json
import os
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import spot_lib as lib  # noqa: E402

HORIZONS = [("1h", 4), ("4h", 16), ("7h", 28)]
MIN_RATIO = 2.0


def measure(rows, horizon_bars, step):
    n = len(rows)
    mfe = []
    mae = []
    for k in range(0, n - horizon_bars, step):
        e0 = rows[k][4]
        if e0 <= 0:
            continue
        window = rows[k + 1:k + 1 + horizon_bars]
        hi = max(r[2] for r in window)
        lo = min(r[3] for r in window)
        mfe.append(100.0 * (hi - e0) / e0)
        mae.append(100.0 * (lo - e0) / e0)
    if not mfe:
        return None, None, 0
    return st.median(mfe), st.median(mae), len(mfe)


def load_old_gate(path):
    if not os.path.exists(path):
        return {}
    try:
        doc = json.load(open(path))
    except (OSError, ValueError):
        return {}
    block = (doc.get("quotes") or {}).get("USDT") or {}
    return {p["pair"]: p for p in block.get("pairs", [])}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/tokocrypto_spot")
    ap.add_argument("--out", default="data/hasil/mfe_m15_spot.json")
    ap.add_argument("--gate-out", default="",
                    help="Tulis gate JSON (skema pair_gate) dari hasil jendela ini; untuk E3")
    ap.add_argument("--pair-gate", default="data/config/pair_gate.json")
    ap.add_argument("--min-ratio", type=float, default=MIN_RATIO)
    ap.add_argument("--start-ms", type=int, default=0, help="Awal jendela MFE (open_time_ms).")
    ap.add_argument("--end-ms", type=int, default=0, help="Akhir jendela MFE (open_time_ms).")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()

    pairs = lib.list_pairs(a.data)
    if not pairs:
        print(f"Tidak ada berkas *_15m.csv di {a.data}", file=sys.stderr)
        return 1

    old = load_old_gate(a.pair_gate)
    rows_out = []
    for i, pair in enumerate(pairs, 1):
        rows = lib.load_pair(a.data, pair, "15m")
        if a.start_ms:
            rows = [r for r in rows if r[0] >= a.start_ms]
        if a.end_ms:
            rows = [r for r in rows if r[0] <= a.end_ms]
        if not rows or len(rows) < 200:
            print(f"  [{i}/{len(pairs)}] {pair:<14} dilewati (data kurang)", flush=True)
            continue
        step = max(1, len(rows) // 4000)
        res = {}
        for label, bars in HORIZONS:
            mfe, mae, samples = measure(rows, bars, step)
            res[label] = (mfe, mae, samples)
        mfe7, mae7, samples7 = res["7h"]
        ratio = mfe7 / lib.F_ADD_PCT if mfe7 is not None else None
        eligible = bool(ratio is not None and ratio >= a.min_ratio)
        old_row = old.get(pair)
        old_val = old_row.get("median_mfe_7h_pct") if old_row else None
        action = "baru"
        if old_row:
            if old_row.get("eligible") and not eligible:
                action = "turun"
            elif not old_row.get("eligible") and eligible:
                action = "naik"
            else:
                action = "tetap"
        rows_out.append({
            "pair": pair,
            "bars_15m": len(rows),
            "samples_7h": samples7,
            "mfe_1h_median_pct": round(res["1h"][0], 4),
            "mfe_4h_median_pct": round(res["4h"][0], 4),
            "mfe_7h_median_pct": round(mfe7, 4),
            "mae_7h_median_pct": round(mae7, 4),
            "ratio_7h": round(ratio, 4),
            "eligible_7h": eligible,
            "old_mfe_7h_m1_pct": old_val,
            "old_eligible": old_row.get("eligible") if old_row else None,
            "change_vs_old_gate": action,
        })
        print(f"  [{i}/{len(pairs)}] {pair:<14} MFE7h {mfe7:>6.2f}%  ratio {ratio:>5.2f}  "
              f"{'layak' if eligible else 'tidak'}", flush=True)

    rows_out.sort(key=lambda r: -r["ratio_7h"])
    eligible = [r for r in rows_out if r["eligible_7h"]]
    flips_up = [r["pair"] for r in rows_out if r["change_vs_old_gate"] == "naik"]
    flips_down = [r["pair"] for r in rows_out if r["change_vs_old_gate"] == "turun"]
    old_eligible = [r["pair"] for r in rows_out if r["old_eligible"]]

    doc = {
        "generated_at_utc": lib.now_utc(),
        "dataset": os.path.abspath(a.data),
        "timeframe": "15m",
        "start_ms": a.start_ms or None,
        "end_ms": a.end_ms or None,
        "horizons": {label: bars for label, bars in HORIZONS},
        "sampling": "step = max(1, n // 4000) bar; entry = close[k]; horizon mulai k+1",
        "fee_pct_additive": lib.F_ADD_PCT,
        "min_ratio": a.min_ratio,
        "pairs_total": len(rows_out),
        "pairs_eligible": len(eligible),
        "pairs_eligible_old_gate": len(old_eligible),
        "flips_up_vs_old_gate": flips_up,
        "flips_down_vs_old_gate": flips_down,
        "summary": {
            "median_mfe_7h_pct_across_pairs": round(
                st.median(r["mfe_7h_median_pct"] for r in rows_out), 4),
            "median_ratio_across_pairs": round(
                st.median(r["ratio_7h"] for r in rows_out), 4),
        },
        "pairs": rows_out,
    }
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w") as f:
        json.dump(doc, f, indent=2)
    if a.gate_out:
        gate_doc = {
            "generated_at_utc": lib.now_utc(),
            "source": "tools/spot_mfe.py (walk-forward E3)",
            "window": {"start_ms": a.start_ms or None, "end_ms": a.end_ms or None},
            "fee_pct_additive": lib.F_ADD_PCT,
            "min_ratio": a.min_ratio,
            "quotes": {"USDT": {"pairs": [
                {"pair": r["pair"], "eligible": r["eligible_7h"],
                 "median_mfe_7h_pct": r["mfe_7h_median_pct"],
                 "ratio_7h": r["ratio_7h"]} for r in rows_out
            ]}},
        }
        os.makedirs(os.path.dirname(a.gate_out), exist_ok=True)
        with open(a.gate_out, "w") as f:
            json.dump(gate_doc, f, indent=2)
        print(f"Gate walk-forward: {a.gate_out} ({len(eligible)} pair layak)")

    print(f"\n=== MFE 7 jam (28 bar 15m) | F = {lib.F_ADD_PCT:.4f}% | ambang {a.min_ratio:.1f}x ===")
    print(f"{'pair':<14}{'MFE1h%':>9}{'MFE4h%':>9}{'MFE7h%':>9}{'MAE7h%':>9}{'ratio':>8}{'layak':>7}{'lama':>7}")
    shown = rows_out if a.verbose else rows_out[:15] + rows_out[-5:]
    for r in shown:
        print(f"{r['pair']:<14}{r['mfe_1h_median_pct']:>9.2f}{r['mfe_4h_median_pct']:>9.2f}"
              f"{r['mfe_7h_median_pct']:>9.2f}{r['mae_7h_median_pct']:>9.2f}"
              f"{r['ratio_7h']:>8.2f}{'YA' if r['eligible_7h'] else 'tidak':>7}"
              f"{('YA' if r['old_eligible'] else 'tidak') if r['old_eligible'] is not None else '-':>7}")
    print(f"\nLayak (baru): {len(eligible)}/{len(rows_out)} | layak (tabel lama): {len(old_eligible)}")
    if flips_up:
        print(f"Naik (dulu tidak layak -> layak): {', '.join(flips_up)}")
    if flips_down:
        print(f"Turun (dulu layak -> tidak layak): {', '.join(flips_down)}")
    print(f"Ditulis: {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
