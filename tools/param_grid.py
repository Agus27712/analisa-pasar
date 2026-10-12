#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Grid experiment: cari tahu apakah ADA kombinasi parameter yang membuat engine +EV di data riil.

Hipotesis yang diuji (berpikir seperti scalper profesional):
  H1. Stop loss 0,5% terlalu rapat dibanding friksi 0,97% -> memperbesar lantai risiko menolong.
  H2. Horizon 90 menit terlalu pendek untuk mengejar gerakan > friksi -> memperpanjang menolong.
  H3. Target net RR lebih tinggi menurunkan win-rate minimum (w* = 1/(1+targetRR)).
  H4. Friksi IDR (0,81%) < USDT (0,97%) -> menolong, terutama bila memakai maker (0,61%).

Output: tabel ringkas + JSON. Dipakai untuk memutuskan penyesuaian engine, bukan untuk klaim profit.
"""
import argparse
import copy
import glob
import importlib.util
import itertools
import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))

DATA = {}
BASE = {}
LOOKAHEAD = 120


def _init(data_dir, pairs, base, lookahead, max_bars):
    global DATA, BASE, LOOKAHEAD
    sr = load_engine()
    BASE = base
    LOOKAHEAD = lookahead
    DATA = {}
    for pair in pairs:
        m1 = sr.load_csv(os.path.join(data_dir, f"{pair}_1m.csv"))
        if max_bars:
            m1 = m1[-max_bars:]
        DATA[pair] = (m1, sr.aggregate(m1, 15), sr.aggregate(m1, 60))


def _run_combo(combo):
    min_risk, target_rr, hold, feekey = combo
    sr = load_engine()
    cfg = copy.deepcopy(BASE)
    cfg["min_risk_pct"] = min_risk
    cfg["target_net_rr"] = target_rr
    cfg["max_tp2_r"] = 8.0
    cfg["min_net_rr"] = 1.05
    cfg["fee"] = dict(FEES[feekey])
    cfg.setdefault("exit", {})["max_hold_bars_m1"] = hold

    agg = {"trades": 0, "wins": 0, "r_sum": 0.0, "pairs_pos": 0}
    per_pair = {}
    for pair, (m1, m15, h1) in DATA.items():
        try:
            rep = sr.run_replay(m1, m15, h1, cfg, cfg["fee"], True, LOOKAHEAD)
            s = sr.summarize(rep)
        except Exception:  # noqa: BLE001
            continue
        n = s["trades_resolved"]
        per_pair[pair] = {"n": n, "avg_r": s["avg_r"], "wr": s["win_rate_pct"]}
        if n:
            agg["trades"] += n
            agg["wins"] += s["wins"]
            agg["r_sum"] += s["avg_r"] * n
            if s["avg_r"] > 0:
                agg["pairs_pos"] += 1
    n = agg["trades"]
    return {
        "min_risk_pct": min_risk, "target_net_rr": target_rr, "max_hold": hold, "fee": feekey,
        "trades": n,
        "win_rate_pct": round(100.0 * agg["wins"] / n, 2) if n else 0.0,
        "avg_r": round(agg["r_sum"] / n, 4) if n else 0.0,
        "pairs_positive": agg["pairs_pos"],
        "per_pair": per_pair,
    }


def load_engine():
    spec = importlib.util.spec_from_file_location("scalp_replay", os.path.join(HERE, "scalp_replay.py"))
    mod = importlib.util.module_from_spec(spec)
    sys.modules["scalp_replay"] = mod
    spec.loader.exec_module(mod)
    return mod


FEES = {
    "usdt": {"buy_taker_pct": 0.4044, "sell_taker_pct": 0.4044, "buy_maker_pct": 0.4044, "sell_maker_pct": 0.4044, "quote": "USDT"},
    "idr_taker": {"buy_taker_pct": 0.2222, "sell_taker_pct": 0.4322, "buy_maker_pct": 0.1222, "sell_maker_pct": 0.3322, "quote": "IDR"},
    "idr_maker": {"buy_taker_pct": 0.1222, "sell_taker_pct": 0.3322, "buy_maker_pct": 0.1222, "sell_maker_pct": 0.3322, "quote": "IDR"},
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--pairs", required=True, help="dipisah koma")
    ap.add_argument("--config", default="data/config/scalp_config_exit_full.json")
    ap.add_argument("--max-bars", type=int, default=20000)
    ap.add_argument("--jobs", type=int, default=16)
    ap.add_argument("--out", default="")
    a = ap.parse_args()

    load_engine()  # validasi modul
    with open(a.config) as f:
        base = json.load(f)
    lookahead = int(base.get("replay", {}).get("lookahead_bars", 120))
    pairs = [p.strip() for p in a.pairs.split(",") if p.strip()]

    grid = list(itertools.product(
        [0.5, 1.5, 3.0],            # min_risk_pct
        [1.25, 2.0, 3.0],           # target_net_rr
        [90, 240, 480],             # max_hold_bars_m1
        ["usdt", "idr_taker", "idr_maker"],  # fee model
    ))
    print(f"Grid: {len(grid)} kombinasi | pair: {pairs} | max-bars: {a.max_bars}", flush=True)

    rows = []
    with ProcessPoolExecutor(max_workers=a.jobs, initializer=_init,
                             initargs=(a.data, pairs, base, lookahead, a.max_bars)) as ex:
        for i, r in enumerate(ex.map(_run_combo, grid, chunksize=1), 1):
            rows.append(r)
            print(f"  [{i}/{len(grid)}] risk {r['min_risk_pct']} tgtRR {r['target_net_rr']} "
                  f"hold {r['max_hold']} fee {r['fee']:>9} -> n={r['trades']:>5} "
                  f"WR {r['win_rate_pct']:>5.1f}% avgR {r['avg_r']:>8.4f} pair+ {r['pairs_positive']}",
                  flush=True)

    rows.sort(key=lambda x: -(x["avg_r"] if x["trades"] >= 30 else -9))
    hdr = f"{'risk%':>6} {'tgtRR':>6} {'hold':>5} {'fee':>10} {'trade':>6} {'WR%':>6} {'avgR':>8} {'pair+':>6}"
    print("\n" + hdr)
    print("-" * len(hdr))
    for r in rows[:30]:
        print(f"{r['min_risk_pct']:>6} {r['target_net_rr']:>6} {r['max_hold']:>5} {r['fee']:>10} "
              f"{r['trades']:>6} {r['win_rate_pct']:>6.1f} {r['avg_r']:>8.4f} {r['pairs_positive']:>6}")

    best = [r for r in rows if r["trades"] >= 30]
    print(f"\nKombinasi dengan n>=30: {len(best)} | ada avgR>0: {sum(1 for r in best if r['avg_r'] > 0)}")
    if a.out:
        os.makedirs(os.path.dirname(a.out), exist_ok=True)
        with open(a.out, "w") as f:
            json.dump({"grid": rows, "pairs": pairs, "max_bars": a.max_bars}, f, indent=2)
        print(f"Ditulis: {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
