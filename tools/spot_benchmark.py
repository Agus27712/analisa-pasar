#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Benchmark entry acak (null baseline) Spec v3 §11.1 untuk harness Python.

Tujuan: memisahkan edge sinyal dari keberuntungan dan dari efek friksi. Tanpa
baseline ini, avg R positif/negatif tidak bisa ditafsirkan.

Metode:
- N = jumlah sinyal setup itu yang lolos G0-G2 pada jendela uji (dari CSV kandidat
  yang ditulis `spot_backtest.py`).
- K = 200 run. Tiap run memilih N bar entry acak (seragam) dari bar yang regime-nya
  diizinkan setup itu, entry `MARKET_NEXT_OPEN` (open bar berikutnya).
- Jarak SL diambil dari distribusi `slEff/ATR` sinyal asli setup itu.
- TP1 = `tp1Floor`; TP2 = null; exit memakai mesin §7 TANPA `SETUP_INVALIDATION`.
- Fee, slippage, dan fill sama persis dengan backtest.

Batas:
- Entry acak tidak memakai filter BTC/level; yang disamakan hanya regime dan distribusi SL.
- Run acak tidak saling mengecualikan posisi (bukan simulasi portofolio).
- p99 dihitung dari distribusi RATA-RATA RUN lintas pair (satu run = satu portofolio
  acak), bukan dari distribusi trade individual. Ini yang dibandingkan dengan rata-rata
  R strategi pada jendela yang sama.

Contoh:
  python3 tools/spot_benchmark.py --data data/tokocrypto_spot --timeframe 1h \
      --candidates data/hasil/spot_backtest_v3_candidates.csv \
      --trades data/hasil/spot_backtest_v3_trades.csv --oos-only \
      --runs 200 --seed 20261021 --out data/hasil/spot_benchmark_v3
"""
import argparse
import csv
import json
import math
import os
import random
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import spot_lib as lib  # noqa: E402
import spot_backtest as bt  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def repo_path(rel):
    return rel if os.path.isabs(rel) else os.path.join(REPO_ROOT, rel)


def read_csv_rows(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def truthy(v):
    return str(v).strip().lower() in ("1", "true", "ya", "yes")


def percentile(values, pct):
    """Persentil dengan interpolasi linear (gaya numpy), tanpa dependensi."""
    if not values:
        return None
    xs = sorted(values)
    if len(xs) == 1:
        return xs[0]
    pos = (len(xs) - 1) * pct / 100.0
    lo = int(math.floor(pos))
    hi = int(math.ceil(pos))
    if lo == hi:
        return xs[lo]
    return xs[lo] + (xs[hi] - xs[lo]) * (pos - lo)


def stable_seed(base, *parts):
    """Seed deterministik lintas proses (hash() Python bergantung PYTHONHASHSEED)."""
    import hashlib
    h = hashlib.sha256(("|".join([str(base)] + [str(p) for p in parts])).encode()).hexdigest()
    return int(h[:16], 16)


def simulate_random_trade(ctx, entry_index, sl_atr, setup, params, tf_minutes, end_index):
    """Satu trade acak: entry open[entry_index], SL = fill − sl_atr × ATR[entry_index-1]."""
    if entry_index < 1 or entry_index > end_index:
        return None
    sig_index = entry_index - 1
    fill = ctx.o[entry_index]
    atr = ctx.atr[sig_index]
    if not fill or fill <= 0 or not atr or atr <= 0:
        return None
    sl = fill - sl_atr * atr
    if sl <= 0 or sl >= fill:
        return None
    risk_pct = (fill - sl) / fill * 100.0
    tp1 = lib.tp1_floor_price(fill, risk_pct)
    sig = {
        "setup": setup, "entry_mode": "MARKET_NEXT_OPEN", "entry": fill,
        "sl_eff": sl, "tp1": tp1, "tp2": None, "qty": 1.0, "risk_pct": risk_pct,
        "risk_net_pct": lib.per_unit_risk_net(fill, sl) / fill * 100.0,
        "net_rr1": lib.MIN_NET_RR_TP1, "net_rr_final": lib.MIN_NET_RR_TP1,
        "score": 0, "trail": params["trailMode"], "tp1_source": "formula",
        "sl_source": "RANDOM",
        "time_stop": lib.time_stop_candles(params["timeStopHours"], tf_minutes),
        "data": {"signal_index": sig_index},
    }
    tr = bt.Trade(ctx.pair, sig, entry_index, fill)
    for j in range(entry_index, end_index + 1):
        if tr.qty_remaining <= 0:
            break
        if tr.exit_next_open:
            tr.sell(tr.qty_remaining, ctx.o[j], tr.exit_next_open)
            return tr.finalize(j, ctx.t[j])
        bt.process_open_trade(ctx, tr, j, {}, [], 0.0, allow_invalidation=False)
        if tr.qty_remaining <= 0:
            return tr.finalize(j, ctx.t[j])
        if j == end_index:
            tr.sell(tr.qty_remaining, ctx.c[j], "END_OF_DATA")
            return tr.finalize(j, ctx.t[j])
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=repo_path("data/tokocrypto_spot"))
    ap.add_argument("--timeframe", choices=["15m", "1h"], default="1h")
    ap.add_argument("--candidates", required=True,
                    help="CSV kandidat dari spot_backtest.py (kolom passes_g0_g2)")
    ap.add_argument("--trades", default="",
                    help="CSV trade strategi pada jendela yang sama (untuk pembanding)")
    ap.add_argument("--pairs", default="all")
    ap.add_argument("--setups", default="all")
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--seed", type=int, default=20261021)
    ap.add_argument("--start-ms", type=int, default=0)
    ap.add_argument("--end-ms", type=int, default=0)
    ap.add_argument("--oos-only", action="store_true",
                    help="Hanya trade strategi dengan in_sample=False")
    ap.add_argument("--out", default=repo_path("data/hasil/spot_benchmark_v3"))
    a = ap.parse_args()
    if a.runs <= 0:
        raise SystemExit("--runs harus > 0")

    setups = list(bt.SETUPS) if a.setups == "all" else [s.strip().upper() for s in a.setups.split(",")]
    for s in setups:
        if s not in bt.SETUPS:
            raise SystemExit(f"setup tidak dikenal: {s}")
    pairs = lib.list_pairs(a.data) if a.pairs == "all" else [p.strip().upper() for p in a.pairs.split(",")]

    cand_rows = read_csv_rows(a.candidates)
    cand = [r for r in cand_rows if truthy(r.get("passes_g0_g2"))]
    if a.start_ms:
        cand = [r for r in cand if int(r["signal_time_ms"]) >= a.start_ms]
    if a.end_ms:
        cand = [r for r in cand if int(r["signal_time_ms"]) <= a.end_ms]

    strategy_trades = read_csv_rows(a.trades) if a.trades else []
    if a.oos_only:
        strategy_trades = [t for t in strategy_trades if not truthy(t.get("in_sample"))]
    if a.start_ms:
        strategy_trades = [t for t in strategy_trades
                           if int(float(t["entry_time_ms"])) >= a.start_ms]
    if a.end_ms:
        strategy_trades = [t for t in strategy_trades
                           if int(float(t["entry_time_ms"])) <= a.end_ms]

    tf_minutes = 15 if a.timeframe == "15m" else 60
    btc_ret_by_time = {}
    _, btc_htf, _, _ = bt.build_frames(a.data, "BTCUSDT", a.timeframe)
    if btc_htf:
        closes = [r[4] for r in btc_htf]
        for k in range(4, len(btc_htf)):
            btc_ret_by_time[btc_htf[k][0]] = (closes[k] / closes[k - 4] - 1.0) * 100.0

    per_pair = {}
    for pair in pairs:
        if not any(r["pair"] == pair for r in cand):
            continue
        rows, htf, exec_minutes, htf_minutes = bt.build_frames(a.data, pair, a.timeframe)
        if not rows or not htf or len(rows) < lib.WARMUP_CANDLES + 50:
            continue
        ctx = bt.Ctx(pair, rows, htf, btc_ret_by_time, exec_minutes, htf_minutes)
        i0, i1 = bt.window_bounds(ctx.t, a.start_ms, a.end_ms, lib.WARMUP_CANDLES)
        # Entry market memakai open bar berikutnya; bar terakhir tidak punya next open.
        allowed_end = i1 - 1
        if allowed_end <= i0:
            continue
        for setup in setups:
            rows_c = [r for r in cand if r["pair"] == pair and r["setup"] == setup]
            if not rows_c:
                continue
            try:
                sl_ratios = [float(r["sl_atr"]) for r in rows_c
                             if r.get("sl_atr") not in (None, "")]
            except ValueError:
                sl_ratios = []
            if not sl_ratios:
                continue
            allowed = [i for i in range(i0, allowed_end + 1)
                       if ctx.regime[i] in bt.ALLOWED_REGIMES[setup]]
            if not allowed:
                continue
            params = lib.DEFAULT_SETUP_PARAMS[setup]
            rng = random.Random(stable_seed(a.seed, pair, setup))
            run_means_net = []
            run_means_gross = []
            all_net = []
            all_gross = []
            for _ in range(a.runs):
                picks = [allowed[rng.randrange(len(allowed))] for _ in range(len(rows_c))]
                rs_net = []
                rs_gross = []
                for idx in picks:
                    ratio = sl_ratios[rng.randrange(len(sl_ratios))]
                    rec = simulate_random_trade(ctx, idx + 1, ratio, setup, params,
                                                tf_minutes, i1)
                    if rec is None:
                        continue
                    rs_net.append(rec["r_net"])
                    rs_gross.append(rec["r_gross"])
                    all_net.append(rec["r_net"])
                    all_gross.append(rec["r_gross"])
                if rs_net:
                    run_means_net.append(sum(rs_net) / len(rs_net))
                    run_means_gross.append(sum(rs_gross) / len(rs_gross))
            if not run_means_gross:
                continue
            per_pair[(pair, setup)] = {
                "pair": pair, "setup": setup,
                "candidates": len(rows_c),
                "allowed_bars": len(allowed),
                "runs_with_trades": len(run_means_gross),
                "random_trades": len(all_gross),
                "run_mean_gross": run_means_gross,
                "run_mean_net": run_means_net,
                "trade_mean_gross": sum(all_gross) / len(all_gross),
                "trade_mean_net": sum(all_net) / len(all_net),
            }
            print(f"  {pair:<14} {setup:<18} N={len(rows_c):>4} run={len(run_means_gross):>3} "
                  f"acakGross {per_pair[(pair, setup)]['trade_mean_gross']:>7.4f}R", flush=True)

    by_setup = {}
    for setup in setups:
        entries = [v for (p, s), v in per_pair.items() if s == setup]
        strat = [t for t in strategy_trades if t.get("setup") == setup]
        strat_net = [float(t["r_net"]) for t in strat]
        strat_gross = [float(t["r_gross"]) for t in strat] if strat and "r_gross" in strat[0] else []
        portfolio_runs_gross = []
        portfolio_runs_net = []
        for k in range(a.runs):
            g = [v["run_mean_gross"][k] for v in entries if k < len(v["run_mean_gross"])]
            n = [v["run_mean_net"][k] for v in entries if k < len(v["run_mean_net"])]
            if g:
                portfolio_runs_gross.append(sum(g) / len(g))
                portfolio_runs_net.append(sum(n) / len(n))
        if not portfolio_runs_gross:
            by_setup[setup] = {"status": "tidak_ada_kandidat", "candidates": 0}
            continue
        p99_gross = percentile(portfolio_runs_gross, 99)
        p99_net = percentile(portfolio_runs_net, 99)
        strat_gross_mean = sum(strat_gross) / len(strat_gross) if strat_gross else None
        strat_net_mean = sum(strat_net) / len(strat_net) if strat_net else None
        beats = (strat_gross_mean is not None and strat_gross_mean >= p99_gross)
        by_setup[setup] = {
            "status": "diukur",
            "pairs_with_candidates": len(entries),
            "candidates": sum(v["candidates"] for v in entries),
            "runs": a.runs,
            "random_trades": sum(v["random_trades"] for v in entries),
            "strategy_trades": len(strat),
            "strategy_gross_r": round(strat_gross_mean, 4) if strat_gross_mean is not None else None,
            "strategy_net_r": round(strat_net_mean, 4) if strat_net_mean is not None else None,
            "random_gross_mean": round(sum(portfolio_runs_gross) / len(portfolio_runs_gross), 4),
            "random_gross_p99": round(p99_gross, 4),
            "random_net_mean": round(sum(portfolio_runs_net) / len(portfolio_runs_net), 4),
            "random_net_p99": round(p99_net, 4),
            "delta_gross_r": (round(strat_gross_mean - sum(portfolio_runs_gross)
                                    / len(portfolio_runs_gross), 4)
                              if strat_gross_mean is not None else None),
            "delta_net_r": (round(strat_net_mean - sum(portfolio_runs_net) / len(portfolio_runs_net), 4)
                            if strat_net_mean is not None else None),
            "beats_random_p99_gross": bool(beats),
            "net_positive": bool(strat_net_mean is not None and strat_net_mean > 0),
            "e2_pass": bool(beats and strat_net_mean is not None and strat_net_mean > 0),
        }

    doc = {
        "meta": {
            "generated_at_utc": lib.now_utc(),
            "dataset": os.path.abspath(a.data),
            "timeframe": a.timeframe,
            "candidates_file": os.path.abspath(a.candidates),
            "trades_file": os.path.abspath(a.trades) if a.trades else None,
            "oos_only": a.oos_only,
            "start_ms": a.start_ms or None,
            "end_ms": a.end_ms or None,
            "runs": a.runs,
            "seed": a.seed,
            "entry": "MARKET_NEXT_OPEN (open bar berikutnya, fill mentah; slippage lewat FeeSchedule)",
            "sl": "distribusi slEff/ATR sinyal asli; SL = entry - ratio x ATR sinyal",
            "tp1": "tp1Floor Spec v3",
            "tp2": None,
            "exit": "Spec v3 §7 tanpa SETUP_INVALIDATION",
            "percentile": "p99 dari rata-rata run lintas pair (interpolasi linear)",
        },
        "by_setup": by_setup,
        "by_pair_setup": [
            {k: v for k, v in row.items() if k not in ("run_mean_gross", "run_mean_net")}
            for row in per_pair.values()
        ],
    }
    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    with open(a.out + ".json", "w") as f:
        json.dump(doc, f, indent=2)

    print(f"\n=== BENCHMARK ACAK ({a.timeframe} | K={a.runs} | seed {a.seed} | "
          f"{'OOS saja' if a.oos_only else 'semua trade'}) ===")
    for s in setups:
        x = by_setup[s]
        if x.get("status") != "diukur":
            print(f"{s:<18} {x['status']}")
            continue
        print(f"{s:<18} N={x['candidates']:>5} trade={x['strategy_trades']:>4} "
              f"strategiGross {x['strategy_gross_r']}R vs acak p99 {x['random_gross_p99']}R "
              f"(delta {x['delta_gross_r']}R) -> {'LOLOS' if x['e2_pass'] else 'GAGAL'}")
    print(f"Ditulis: {a.out}.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
