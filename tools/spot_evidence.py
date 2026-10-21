#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gerbang bukti E1-E4 Spec v3 §11.2 untuk harness Python (riset, bukan produksi).

Alur:
1. MFE jendela A (90 hari pertama) -> gate pair walk-forward (E3 untuk gate edge).
2. Backtest jendela B (90 hari kedua) dengan gate dari A, TP1 `spec`, G3 mati.
3. Benchmark entry acak pada jendela out-of-sample B (30% terakhir).
4. Backtest jendela A (pembanding E4; gate-nya in-sample, dicatat).
5. Variasi parameter +-20% dan tp1Fraction 0.5/0.6 pada jendela B (E4).
6. Tabel lolos/gagal per setup.

Mode --assemble-only melewati langkah 1-5 dan hanya menyusun ulang evidence.json dari
artefak yang sudah ada (dipakai setelah perbaikan tabel E4 per setup).

Batas yang dilaporkan, bukan disembunyikan:
- Universe 41 pair dipilih dari snapshot 24 jam pada AKHIR jendela B, jadi E3 baru
  terpenuhi untuk gate edge; pemilihan universe belum walk-forward penuh.
- Angka harness adalah riset. Mesin produksi tetap Kotlin dan belum ada paritas.

Contoh:
  python3 tools/spot_evidence.py --data data/tokocrypto_spot_180 \
      --work data/hasil/spot_evidence_v3 --timeframes 15m,1h
"""
import argparse
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import spot_lib as lib  # noqa: E402
import spot_backtest as bt  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(REPO_ROOT, "tools")
DAY_MS = 86_400_000


def repo_path(rel):
    return rel if os.path.isabs(rel) else os.path.join(REPO_ROOT, rel)


def run(cmd):
    print("$ " + " ".join(str(c) for c in cmd), flush=True)
    subprocess.run(cmd, check=True, cwd=REPO_ROOT)


def load(path):
    with open(path) as f:
        return json.load(f)


def sign(x):
    if x is None:
        return None
    if x > 0:
        return 1
    if x < 0:
        return -1
    return 0


def variant_defs():
    """Daftar varian E4. Satu sumber untuk menjalankan dan menyusun ulang tabel."""
    return [
        ("max_sl_atr_x0.8", ["--max-sl-atr", str(lib.MAX_SL_ATR * 0.8)]),
        ("max_sl_atr_x1.2", ["--max-sl-atr", str(lib.MAX_SL_ATR * 1.2)]),
        ("min_net_rr_tp1_x0.8", ["--min-net-rr-tp1", str(lib.MIN_NET_RR_TP1 * 0.8)]),
        ("min_net_rr_tp1_x1.2", ["--min-net-rr-tp1", str(lib.MIN_NET_RR_TP1 * 1.2)]),
        ("min_net_rr_final_x0.8", ["--min-net-rr-final", str(lib.MIN_NET_RR_FINAL * 0.8)]),
        ("min_net_rr_final_x1.2", ["--min-net-rr-final", str(lib.MIN_NET_RR_FINAL * 1.2)]),
        ("min_reward_friction_x0.8",
         ["--min-reward-over-friction", str(lib.MIN_REWARD_OVER_FRICTION * 0.8)]),
        ("min_reward_friction_x1.2",
         ["--min-reward-over-friction", str(lib.MIN_REWARD_OVER_FRICTION * 1.2)]),
        ("tp1_fraction_0.5", ["--tp1-fraction", "0.5"]),
        ("tp1_fraction_0.6", ["--tp1-fraction", "0.6"]),
    ]


def variant_row(doc, setup):
    """Ambil hasil satu setup untuk satu varian, bukan ringkasan seluruh setup."""
    s = doc.get("summary", {}).get("by_setup", {}).get(setup, {})
    return {"trades": s.get("trades", 0), "avg_r": s.get("avg_r")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=repo_path("data/tokocrypto_spot_180"))
    ap.add_argument("--work", default=repo_path("data/hasil/spot_evidence_v3"))
    ap.add_argument("--timeframes", default="15m,1h")
    ap.add_argument("--start-ms", type=int, default=0)
    ap.add_argument("--mid-ms", type=int, default=0)
    ap.add_argument("--end-ms", type=int, default=0)
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--seed", type=int, default=20261021)
    ap.add_argument("--skip-variants", action="store_true",
                    help="Lewati E4 variasi parameter (lebih cepat)")
    ap.add_argument("--skip-window-a", action="store_true",
                    help="Lewati backtest jendela A (E4 dua jendela tidak lengkap)")
    ap.add_argument("--assemble-only", action="store_true",
                    help="Susun ulang evidence.json dari artefak yang sudah ada; "
                         "tidak menjalankan backtest/MFE/benchmark")
    a = ap.parse_args()

    os.makedirs(a.work, exist_ok=True)
    manifest_path = os.path.join(a.data, "_manifest.json")
    end_ms = a.end_ms
    if not end_ms and os.path.exists(manifest_path):
        end_ms = load(manifest_path)["window"]["end_ms"]
    if not end_ms:
        raise SystemExit("--end-ms wajib bila manifest dataset tidak ada")
    mid_ms = a.mid_ms or (end_ms - 90 * DAY_MS)
    start_ms = a.start_ms or (end_ms - 180 * DAY_MS)
    oos_start = mid_ms + int((end_ms - mid_ms) * 0.7)
    if not (start_ms < mid_ms < oos_start < end_ms):
        raise SystemExit("urutan jendela tidak valid")

    timeframes = [t.strip() for t in a.timeframes.split(",") if t.strip()]
    print(f"Jendela A {start_ms}..{mid_ms} | B {mid_ms}..{end_ms} | OOS B {oos_start}..{end_ms}")

    # 1) MFE jendela A -> gate edge walk-forward.
    mfe_out = os.path.join(a.work, "mfe_A.json")
    gate_path = os.path.join(a.work, "gate_A.json")
    if not a.assemble_only:
        run([sys.executable, os.path.join(TOOLS, "spot_mfe.py"),
             "--data", a.data, "--start-ms", str(start_ms), "--end-ms", str(mid_ms),
             "--out", mfe_out, "--gate-out", gate_path])
    gate_doc = load(gate_path)
    eligible = [p["pair"] for p in gate_doc["quotes"]["USDT"]["pairs"] if p.get("eligible")]
    print(f"Gate A: {len(eligible)} pair layak -> {', '.join(eligible) if eligible else '(kosong)'}")

    base = {}
    window_a = {}
    diagnostics = {}
    benchmarks = {}
    variants = {}
    for tf in timeframes:
        out_b = os.path.join(a.work, f"bt_B_{tf}")
        out_bench = os.path.join(a.work, f"bench_{tf}")
        out_nogate = os.path.join(a.work, f"bt_B_nogate_{tf}")
        out_bench_diag = os.path.join(a.work, f"bench_nogate_{tf}")
        if a.assemble_only:
            base[tf] = load(out_b + ".json")
            benchmarks[tf] = load(out_bench + ".json")
            diagnostics[tf] = load(out_nogate + ".json")
            diagnostics[tf]["benchmark_no_gate"] = load(out_bench_diag + ".json")["by_setup"]
            window_a[tf] = load(os.path.join(a.work, f"bt_A_{tf}.json"))
            variants[tf] = {
                name: load(os.path.join(a.work, f"var_{name}_{tf}.json"))
                for name, _ in variant_defs()
            }
            continue

        # 2) Backtest jendela B (gate dari A).
        run([sys.executable, os.path.join(TOOLS, "spot_backtest.py"),
             "--data", a.data, "--timeframe", tf, "--setups", "all",
             "--pair-gate", gate_path, "--tp1-policy", "spec",
             "--start-ms", str(mid_ms), "--end-ms", str(end_ms),
             "--out", out_b])
        base[tf] = load(out_b + ".json")

        # 3) Benchmark entry acak pada OOS B.
        run([sys.executable, os.path.join(TOOLS, "spot_benchmark.py"),
             "--data", a.data, "--timeframe", tf,
             "--candidates", out_b + "_candidates.csv",
             "--trades", out_b + "_trades.csv",
             "--start-ms", str(oos_start), "--end-ms", str(end_ms), "--oos-only",
             "--runs", str(a.runs), "--seed", str(a.seed), "--out", out_bench])
        benchmarks[tf] = load(out_bench + ".json")

        # 3b) Diagnostik: panel penuh tanpa G0 (bukan produksi) untuk memisahkan
        # masalah sampel dari masalah gate.
        run([sys.executable, os.path.join(TOOLS, "spot_backtest.py"),
             "--data", a.data, "--timeframe", tf, "--setups", "all",
             "--no-pair-edge", "--tp1-policy", "spec",
             "--start-ms", str(mid_ms), "--end-ms", str(end_ms),
             "--out", out_nogate])
        diagnostics[tf] = load(out_nogate + ".json")
        run([sys.executable, os.path.join(TOOLS, "spot_benchmark.py"),
             "--data", a.data, "--timeframe", tf,
             "--candidates", out_nogate + "_candidates.csv",
             "--trades", out_nogate + "_trades.csv",
             "--start-ms", str(oos_start), "--end-ms", str(end_ms), "--oos-only",
             "--runs", str(a.runs), "--seed", str(a.seed), "--out", out_bench_diag])
        diagnostics[tf]["benchmark_no_gate"] = load(out_bench_diag + ".json")["by_setup"]

        # 4) Jendela A (pembanding E4). Gate A dipakai apa adanya; ini in-sample
        # untuk gate, jadi hanya dipakai untuk stabilitas tanda, bukan E3.
        if not a.skip_window_a:
            out_a = os.path.join(a.work, f"bt_A_{tf}")
            run([sys.executable, os.path.join(TOOLS, "spot_backtest.py"),
                 "--data", a.data, "--timeframe", tf, "--setups", "all",
                 "--pair-gate", gate_path, "--tp1-policy", "spec",
                 "--start-ms", str(start_ms), "--end-ms", str(mid_ms),
                 "--no-candidate-scan", "--out", out_a])
            window_a[tf] = load(out_a + ".json")

        # 5) E4: variasi parameter +-20% dan tp1Fraction 0.5/0.6.
        if not a.skip_variants:
            for name, extra in variant_defs():
                out_v = os.path.join(a.work, f"var_{name}_{tf}")
                run([sys.executable, os.path.join(TOOLS, "spot_backtest.py"),
                     "--data", a.data, "--timeframe", tf, "--setups", "all",
                     "--pair-gate", gate_path, "--tp1-policy", "spec",
                     "--start-ms", str(mid_ms), "--end-ms", str(end_ms),
                     "--no-candidate-scan", "--out", out_v] + extra)
                variants.setdefault(tf, {})[name] = load(out_v + ".json")

    # 6) Tabel E1-E4 per setup.
    setups = list(bt.SETUPS)
    table = {}
    for setup in setups:
        per_tf = {}
        for tf in timeframes:
            b = base[tf]["summary"]
            oos = b.get("portfolio_out_of_sample_by_setup", {}).get(setup, {})
            n_oos = oos.get("trades", 0)
            avg_oos = oos.get("avg_r")
            bench = benchmarks[tf]["by_setup"].get(setup, {})
            e1 = n_oos >= 100
            e2 = bool(bench.get("e2_pass")) and avg_oos is not None and avg_oos > 0
            base_all = b["overall"].get("avg_r", 0.0)
            a_all = (window_a.get(tf, {}).get("summary", {}).get("overall", {})
                     .get("avg_r") if tf in window_a else None)
            a_trades = (window_a.get(tf, {}).get("summary", {}).get("overall", {})
                        .get("trades") if tf in window_a else 0)
            var_rows = {}
            for name, doc in (variants.get(tf) or {}).items():
                var_rows[name] = variant_row(doc, setup)
            tested = [v for v in var_rows.values() if v["trades"] > 0]
            sign_stable_params = (bool(tested)
                                  and all(sign(v["avg_r"]) == sign(base_all) for v in tested))
            sign_stable_windows = (a_all is not None and a_trades > 0
                                   and b["overall"].get("trades", 0) > 0
                                   and sign(a_all) == sign(base_all))
            e4 = bool(sign_stable_params and sign_stable_windows)
            e4_status = ("tidak_cukup_sampel"
                         if b["overall"].get("trades", 0) < 10
                         else ("lolos" if e4 else "gagal"))
            diag = diagnostics[tf]["summary"]["by_setup"].get(setup, {})
            per_tf[tf] = {
                "oos_trades": n_oos,
                "oos_avg_r": avg_oos,
                "oos_avg_gross_r": oos.get("avg_r_gross"),
                "oos_pairs": oos.get("pairs"),
                "e1_pass": e1,
                "e2_pass": e2,
                "benchmark": bench,
                "window_b_avg_r": base_all,
                "window_a_avg_r": a_all,
                "window_a_trades": a_trades,
                "variants": var_rows,
                "sign_stable_params": sign_stable_params,
                "sign_stable_windows": sign_stable_windows,
                "e4_pass": e4,
                "e4_status": e4_status,
                "diagnostic_no_gate_trades": diag.get("trades", 0),
                "diagnostic_no_gate_avg_r": diag.get("avg_r"),
                "diagnostic_no_gate_benchmark": diagnostics[tf].get(
                    "benchmark_no_gate", {}).get(setup),
            }
        best = None
        for tf in timeframes:
            if per_tf[tf]["e1_pass"] and per_tf[tf]["e2_pass"]:
                best = tf
                break
        e3_pass = False
        implementable = any(
            per_tf[tf]["e1_pass"] and per_tf[tf]["e2_pass"] and e3_pass
            and per_tf[tf]["e4_pass"] for tf in timeframes)
        table[setup] = {
            "per_timeframe": per_tf,
            "e3_gate_from_prior_window": True,
            "e3_universe_from_prior_window": False,
            "e3_pass": e3_pass,
            "e3_note": ("Gate edge dari jendela A (walk-forward), tetapi universe 41 pair "
                        "dipilih dari snapshot 24 jam pada akhir jendela B. E3 belum penuh."),
            "implementable": implementable,
            "best_timeframe_if_any": best,
        }

    doc = {
        "meta": {
            "generated_at_utc": lib.now_utc(),
            "dataset": os.path.abspath(a.data),
            "work_dir": os.path.abspath(a.work),
            "windows": {"A_start_ms": start_ms, "A_end_ms": mid_ms,
                        "B_start_ms": mid_ms, "B_end_ms": end_ms,
                        "B_oos_start_ms": oos_start},
            "timeframes": timeframes,
            "runs": a.runs, "seed": a.seed,
            "eligible_pairs_gate_a": eligible,
            "tp1_policy": "spec",
            "g3": "dicabut (0)",
            "max_concurrent_positions": lib.MAX_CONCURRENT_POSITIONS,
            "e1_definition": "trade out-of-sample portofolio (maks 2 posisi) per setup >= 100",
            "e2_definition": "benchmark p99 R kotor + R bersih OOS > 0",
            "e3_definition": "gate edge dari jendela A; universe BELUM walk-forward",
            "e4_definition": "tanda avg R sama pada variasi +-20%, tp1 0.5/0.6, dan jendela A vs B",
        },
        "setups": table,
    }
    out_json = os.path.join(a.work, "evidence.json")
    with open(out_json, "w") as f:
        json.dump(doc, f, indent=2)

    print("\n=== GERBANG BUKTI E1-E4 (produksi: gate A, TP1 spec, G3 mati) ===")
    header = (f"{'setup':<18}{'tf':<5}{'nOOS':>6}  {'avgR OOS':>8}  {'acak p99':>8}  "
              f"{'E1':>5}  {'E2':>5}  {'E3':>8}  {'E4':>19}")
    print(header)
    for s in setups:
        for tf in timeframes:
            x = table[s]["per_timeframe"][tf]
            p99 = x["benchmark"].get("random_gross_p99")
            avg = ('%.4f' % x['oos_avg_r']) if x['oos_avg_r'] is not None else '-'
            p99s = ('%.4f' % p99) if p99 is not None else '-'
            print(f"{s:<18}{tf:<5}{x['oos_trades']:>6}  {avg:>8}  {p99s:>8}  "
                  f"{'LOLOS' if x['e1_pass'] else 'gagal':>5}  "
                  f"{'LOLOS' if x['e2_pass'] else 'gagal':>5}  "
                  f"{'sebagian':>8}  {x['e4_status']:>19}")
    print(f"\nDitulis: {out_json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
