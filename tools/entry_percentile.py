#!/usr/bin/env python3
"""Ukur posisi harga entri P0 dalam rentang 1/3/7 hari + return maju.

Menjawab keluhan "beli di pucuk": untuk setiap entri non-overlap yang dipilih
engine (classic, hold 90m), hitung:

- persentil harga entri di antara close 1/3/7 hari terakhir (0 = terendah,
  100 = tertinggi);
- posisi harga dalam rentang high-low jendela yang sama;
- return close dan MFE/MAE kotor pada 1/3/7 hari ke depan.

Alat ini hanya membaca data dan cache kandidat P0. Tidak mengubah gate/exit.

Contoh:
  python3 tools/entry_percentile.py \
      --pairs ADAUSDT,DOGEUSDT,XRPUSDT --data data/tokocrypto_v4 \
      --cache-dir /tmp/friction_sweep_cache \
      --out data/hasil/exp_entry_price_percentile.json
"""
import argparse
import json
import os
import statistics as st
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import exit_mode_sweep as ems  # noqa: E402
import scalp_replay as sr  # noqa: E402

BARS_PER_DAY = 1440
WINDOWS = (1, 3, 7)
BUCKETS = ("0-20", "20-40", "40-60", "60-80", "80-100")


def trailing_window(m1, i, days):
    start = max(0, i - days * BARS_PER_DAY + 1)
    return m1[start:i + 1]


def range_position(m1, i, days):
    win = trailing_window(m1, i, days)
    price = m1[i]["c"]
    hi = max(b["h"] for b in win)
    lo = min(b["l"] for b in win)
    if hi <= lo:
        return None
    return 100.0 * (price - lo) / (hi - lo)


def forward_stats(m1, i, price, days):
    end = i + days * BARS_PER_DAY
    if end >= len(m1):
        return None
    close = m1[end]["c"]
    win = m1[i + 1:end + 1]
    if not win:
        return None
    return {
        "close_return_pct": round(sr._gross_pct(price, close), 4),
        "mfe_pct": round(sr._gross_pct(price, max(b["h"] for b in win)), 4),
        "mae_pct": round(sr._gross_pct(price, min(b["l"] for b in win)), 4),
    }


def median(values):
    vals = [v for v in values if v is not None]
    return round(st.median(vals), 3) if vals else None


def mean(values):
    vals = [v for v in values if v is not None]
    return round(sum(vals) / len(vals), 3) if vals else None


def summarize(rows):
    out = {"n": len(rows), "windows": {}}
    for w in WINDOWS:
        pct = [r[f"pctl_{w}d"] for r in rows if r[f"pctl_{w}d"] is not None]
        rng = [r[f"range_{w}d"] for r in rows if r[f"range_{w}d"] is not None]
        fwd = [r[f"fwd_{w}d"] for r in rows if r[f"fwd_{w}d"] is not None]
        bucket = {}
        for b in BUCKETS:
            sub = [r for r in rows
                   if r[f"pctl_{w}d"] is not None
                   and ems.bucket_of(r[f"pctl_{w}d"]) == b
                   and r[f"fwd_{w}d"] is not None]
            bucket[b] = {
                "n": len(sub),
                "median_close_return_pct": median(
                    [x[f"fwd_{w}d"]["close_return_pct"] for x in sub]),
                "pct_positive": round(
                    100.0 * sum(1 for x in sub
                                if x[f"fwd_{w}d"]["close_return_pct"] > 0)
                    / len(sub), 1) if sub else None,
                "median_mfe_pct": median(
                    [x[f"fwd_{w}d"]["mfe_pct"] for x in sub]),
                "median_mae_pct": median(
                    [x[f"fwd_{w}d"]["mae_pct"] for x in sub]),
            }
        top = [r for r in rows if r[f"pctl_{w}d"] is not None
               and r[f"pctl_{w}d"] >= 80 and r[f"fwd_{w}d"] is not None]
        bottom = [r for r in rows if r[f"pctl_{w}d"] is not None
                  and r[f"pctl_{w}d"] < 20 and r[f"fwd_{w}d"] is not None]
        out["windows"][f"{w}d"] = {
            "n_pctl": len(pct),
            "n_forward": len(fwd),
            "median_pctl": median(pct),
            "mean_pctl": mean(pct),
            "share_top20_pct": round(100.0 * sum(1 for p in pct if p >= 80)
                                     / len(pct), 1) if pct else None,
            "share_bottom20_pct": round(100.0 * sum(1 for p in pct if p < 20)
                                        / len(pct), 1) if pct else None,
            "median_range_position": median(rng),
            "median_forward_close_pct": median(
                [x["close_return_pct"] for x in fwd]),
            "median_forward_mfe_pct": median([x["mfe_pct"] for x in fwd]),
            "median_forward_mae_pct": median([x["mae_pct"] for x in fwd]),
            "top20_median_forward_pct": median(
                [x["fwd_{}d".format(w)]["close_return_pct"] for x in top]),
            "bottom20_median_forward_pct": median(
                [x["fwd_{}d".format(w)]["close_return_pct"] for x in bottom]),
            "buckets": bucket,
        }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", default="ADAUSDT,DOGEUSDT,XRPUSDT")
    ap.add_argument("--data", default=str(ROOT / "data/tokocrypto_v4"))
    ap.add_argument("--config", default=str(ROOT / "data/config/scalp_config.json"))
    ap.add_argument("--hold", type=int, default=90)
    ap.add_argument("--cache-dir",
                    default=os.path.join(tempfile.gettempdir(), "exit_mode_sweep_cache"))
    ap.add_argument("--rebuild", action="store_true")
    ap.add_argument("--out", default=str(ROOT / "data/hasil/exp_entry_price_percentile.json"))
    ap.add_argument("--dump-entries", action="store_true")
    args = ap.parse_args()

    base = sr.merge_defaults(json.load(open(args.config)))
    fee = base["fee"]
    pairs = [p.strip().upper() for p in args.pairs.split(",") if p.strip()]
    os.makedirs(args.cache_dir, exist_ok=True)

    result = {"meta": {
        "data": str(Path(args.data).resolve()),
        "config": str(Path(args.config).resolve()),
        "pairs": pairs, "hold": args.hold, "windows_days": list(WINDOWS),
        "fee": fee,
    }, "pairs": {}, "all": None}
    all_rows = []

    for pair in pairs:
        print(f"memuat {pair}...", flush=True)
        m1, cache = ems.candidates_for(args.data, pair, base, fee,
                                       args.cache_dir, args.rebuild)
        cfg = ems.pair_cfg(base, "classic", args.hold)
        entries, invalid = ems.select_entries(m1, cache, cfg, fee)
        rows = []
        for c, _rl in entries:
            i = c["i"]
            price = c["price"]
            row = {"pair": pair, "t": c["t"], "price": price,
                   "setup": c["setup"], "score": c["score"]}
            for w in WINDOWS:
                win = trailing_window(m1, i, w)
                row[f"pctl_{w}d"] = (
                    round(ems.percentile_rank([b["c"] for b in win], price), 2)
                    if len(win) >= w * BARS_PER_DAY * 0.8 else None)
                row[f"range_{w}d"] = (
                    round(range_position(m1, i, w), 2)
                    if len(win) >= w * BARS_PER_DAY * 0.8 else None)
                row[f"fwd_{w}d"] = forward_stats(m1, i, price, w)
            rows.append(row)
        summary = summarize(rows)
        summary["invalid_risk"] = invalid
        result["pairs"][pair] = summary
        all_rows.extend(rows)

        print(f"\n{pair}: n={summary['n']} (risk tidak sah={invalid})")
        print(f"{'window':>6} {'median_pctl':>12} {'top20%':>7} {'bottom20%':>9} "
              f"{'range_pos':>9} {'fwd_close':>9}")
        for w in WINDOWS:
            s = summary["windows"][f"{w}d"]
            print(f"{w:>5}d {s['median_pctl']:>12} {s['share_top20_pct']:>7} "
                  f"{s['share_bottom20_pct']:>9} {s['median_range_position']:>9} "
                  f"{s['median_forward_close_pct']:>9}")

    result["all"] = summarize(all_rows)
    if args.dump_entries:
        result["entries"] = all_rows
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w") as f:
        json.dump(result, f, indent=1)
    print(f"\nhasil JSON: {out}")


if __name__ == "__main__":
    main()
