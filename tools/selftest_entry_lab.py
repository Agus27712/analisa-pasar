#!/usr/bin/env python3
"""
tools/selftest_entry_lab.py — Uji daya-deteksi (power test) untuk entry_hypothesis_lab.py

MENGAPA INI ADA
---------------
Lab hipotesis P3 melaporkan "0 dari 8 hipotesis lolos gate". Kesimpulan itu hanya berarti
kalau harness-nya memang BISA lolos ketika ada edge. Script ini membuktikannya:

  1. Bangkitkan data sintetis yang edge-nya DITANAM (diketahui kebenarannya).
     Setiap kali volume bar melonjak 20x rata-rata DAN candle-nya hijau, harga
     sengaja dinaikkan +2% pada 60 bar berikutnya.
  2. Jalankan mesin lab yang sama (build_ctx -> scan_pair -> summarize_signals ->
     baseline_pvalue) di atas data itu.
  3. Harapan:
       - hipotesis "tanam"  -> LOLOS gate   (harness peka terhadap edge nyata)
       - hipotesis "kontrol"-> GAGAL gate   (harness tidak asal meloloskan)
  4. Kalau hipotesis tanam TIDAK lolos, berarti harness-nya buta dan hasil 0/8
     di laporan P3 tidak boleh dipercaya.

Data sintetis dibuat di memori; tidak ada berkas yang ditulis.

Jalankan:  python3 tools/selftest_entry_lab.py
"""
import argparse
import importlib.util
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def gen_synthetic(symbol, n, marks, mark_step, start_at, horizon,
                  ramp_pct, vol_spike_x, seed):
    """Random walk M1 + lonjakan volume & ramp harga yang ditanam di indeks tertentu."""
    rng = random.Random(seed)
    closes = [100.0]
    for _ in range(1, n):
        closes.append(closes[-1] * (1.0 + rng.gauss(0.0, 0.0005)))

    mark_idx = []
    i = start_at
    while i < n - horizon - 5 and len(mark_idx) < marks:
        mark_idx.append(i)
        i += mark_step

    # tanam ramp +ramp_pct% merata sepanjang `horizon` bar setelah tiap mark
    for m in mark_idx:
        for k in range(1, horizon + 1):
            if m + k < n:
                closes[m + k] *= (1.0 + ramp_pct / 100.0 * k / horizon)

    base_vol = 100.0
    bars = []
    t0 = 1786000000000
    mark_set = set(mark_idx)
    for j in range(n):
        c = closes[j]
        o = closes[j - 1] if j > 0 else c
        if j in mark_set:
            o = c * 0.9995            # paksa candle hijau -> pola jadi teramati
        hi = max(o, c) * (1.0 + abs(rng.gauss(0.0, 0.0003)))
        lo = min(o, c) * (1.0 - abs(rng.gauss(0.0, 0.0003)))
        v = base_vol * (0.5 + rng.random())
        if j in mark_set:
            v = base_vol * vol_spike_x
        bars.append({"t": t0 + j * 60_000, "o": o, "h": hi, "l": lo, "c": c, "v": v})
    return bars, len(mark_idx)


def evaluate(fn, ctxs, t_split, H, fee, slip, replay, exit_cfg, risk_cfg,
             gate_gross, min_n, alpha_adj, reps, rng):
    sig_map = {ctx.symbol: lab.scan_pair(ctx, fn, H, 0, ctx.n) for ctx in ctxs}
    tr = lab.summarize_signals(ctxs, sig_map, 0, t_split, H, fee, slip, replay, exit_cfg, risk_cfg)
    te = lab.summarize_signals(ctxs, sig_map, t_split, 1 << 62, H, fee, slip, replay, exit_cfg, risk_cfg)
    p_tr, _, _ = lab.baseline_pvalue(ctxs, sig_map, 0, t_split, tr["gross_mean"], reps, rng)
    p_te, _, _ = lab.baseline_pvalue(ctxs, sig_map, t_split, 1 << 62, te["gross_mean"], reps, rng)
    reasons = []
    if tr["n"] < min_n:
        reasons.append(f"n_train {tr['n']} < {min_n}")
    if te["n"] < min_n:
        reasons.append(f"n_test {te['n']} < {min_n}")
    if tr["gross_mean"] < gate_gross:
        reasons.append(f"gross_train {tr['gross_mean']:.3f}% < {gate_gross}%")
    if te["gross_mean"] < gate_gross:
        reasons.append(f"gross_test {te['gross_mean']:.3f}% < {gate_gross}%")
    if p_tr is None or p_tr >= alpha_adj:
        reasons.append(f"p_train {p_tr} >= {alpha_adj:.5f}")
    if p_te is None or p_te >= alpha_adj:
        reasons.append(f"p_test {p_te} >= {alpha_adj:.5f}")
    return {"train": tr, "test": te, "p_train": p_tr, "p_test": p_te,
            "passed": not reasons, "reasons": reasons}


def main():
    ap = argparse.ArgumentParser(description="Uji daya-deteksi lab hipotesis P3.")
    ap.add_argument("--pairs", type=int, default=3)
    ap.add_argument("--bars", type=int, default=20000)
    ap.add_argument("--marks-per-pair", type=int, default=50)
    ap.add_argument("--mark-step", type=int, default=300)
    ap.add_argument("--start-at", type=int, default=500)
    ap.add_argument("--horizon", type=int, default=60)
    ap.add_argument("--ramp-pct", type=float, default=2.0)
    ap.add_argument("--vol-spike-x", type=float, default=20.0)
    ap.add_argument("--train-frac", type=float, default=0.6)
    ap.add_argument("--gate-gross", type=float, default=1.0)
    ap.add_argument("--min-n", type=int, default=30)
    ap.add_argument("--baseline-reps", type=int, default=300)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    global lab
    lab = load_module("entry_hypothesis_lab", os.path.join(HERE, "entry_hypothesis_lab.py"))
    replay = lab.load_replay(os.path.join(HERE, "scalp_replay.py"))

    fee = {"buy_taker_pct": 0.4044, "sell_taker_pct": 0.4044}
    slip = 0.08
    H = args.horizon
    exit_cfg = {"slippage_pct": slip,
                "exit": {"mode": "full", "max_hold_bars_m1": H,
                         "min_early_profit_net_pct": 0.45,
                         "breakeven_trigger_net_pct": 0.40, "trail_pct": 0.35}}
    risk_cfg = {"min_risk_pct": 0.5, "target_net_rr": 1.25}
    alpha_adj = 0.05 / 2.0  # dua hipotesis di uji ini

    print("=" * 72)
    print("SELFTEST lab hipotesis P3 — apakah harness bisa mendeteksi edge?")
    print("=" * 72)
    print(f"Data sintetis: {args.pairs} pair x {args.bars} bar M1 | "
          f"{args.marks_per_pair} mark/pair | ramp +{args.ramp_pct}% / {H} bar")
    print(f"Gate         : gross >= {args.gate_gross}% di train & test, n >= {args.min_n}, "
          f"p < {alpha_adj:.5f}")
    print()

    ctxs = []
    for p in range(args.pairs):
        sym = f"SYN{p}USDT"
        bars, nmarks = gen_synthetic(sym, args.bars, args.marks_per_pair, args.mark_step,
                                     args.start_at, H, args.ramp_pct, args.vol_spike_x,
                                     args.seed + p)
        ctx = lab.build_ctx(sym, bars, H, fee, slip, replay)
        ctxs.append(ctx)
        print(f"  {sym}: {ctx.n} bar | {nmarks} mark ditanam")

    t_lo = min(c.ts[c.valid[0]] for c in ctxs)
    t_hi = max(c.ts[c.valid[-1]] for c in ctxs)
    t_split = int(t_lo + args.train_frac * (t_hi - t_lo))
    print()

    rng = random.Random(args.seed)

    def planted(ctx, i):
        ma = ctx.vol_ma20[i]
        return bool(ma and ma > 0 and ctx.vol[i] > 5.0 * ma and ctx.close[i] > ctx.open[i])

    def control(ctx, i):
        return ctx.hour[i] == 3

    print("--- Hipotesis TANAM (edge sengaja dibuat ada) ---")
    r_planted = evaluate(planted, ctxs, t_split, H, fee, slip, replay, exit_cfg, risk_cfg,
                         args.gate_gross, args.min_n, alpha_adj, args.baseline_reps, rng)
    for nm, s in (("train", r_planted["train"]), ("test", r_planted["test"])):
        print(f"  {nm:5s} n={s['n']:4d} gross={s['gross_mean']:+.3f}% net={s['net_mean']:+.3f}% "
              f"R={s['r_mean']:+.3f} p={r_planted['p_' + nm]}")
    print(f"  -> {'LOLOS gate' if r_planted['passed'] else 'GAGAL: ' + '; '.join(r_planted['reasons'])}")
    print()

    print("--- Hipotesis KONTROL (tidak ada edge) ---")
    r_control = evaluate(control, ctxs, t_split, H, fee, slip, replay, exit_cfg, risk_cfg,
                         args.gate_gross, args.min_n, alpha_adj, args.baseline_reps, rng)
    for nm, s in (("train", r_control["train"]), ("test", r_control["test"])):
        print(f"  {nm:5s} n={s['n']:4d} gross={s['gross_mean']:+.3f}% net={s['net_mean']:+.3f}% "
              f"R={s['r_mean']:+.3f} p={r_control['p_' + nm]}")
    print(f"  -> {'LOLOS gate' if r_control['passed'] else 'GAGAL: ' + '; '.join(r_control['reasons'])}")
    print()

    ok = r_planted["passed"] and not r_control["passed"]
    print("=" * 72)
    if ok:
        print("HASIL SELFTEST: LULUS")
        print("  Harness peka terhadap edge nyata DAN tidak meloloskan hipotesis kosong.")
        print("  Artinya laporan '0/8 lolos gate' pada data Tokocrypto adalah temuan,")
        print("  bukan kegagalan alat.")
        return 0
    print("HASIL SELFTEST: GAGAL")
    if not r_planted["passed"]:
        print("  Hipotesis tanam TIDAK lolos -> harness buta. Hasil P3 tidak valid.")
    if r_control["passed"]:
        print("  Hipotesis kontrol LOLOS -> harness terlalu longgar. Gate tidak bermakna.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
