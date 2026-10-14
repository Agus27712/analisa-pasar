#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validasi out-of-sample (train/test split) untuk engine scalping.

Aturan §2 poin 5 di `workplan2.md`: **jangan** promosikan konfigurasi ke default live
sebelum lolos gate train + test. Tool ini menjawab pertanyaan itu dengan data:

  - Bagi data M1 tiap pair menurut WAKTU: train = 70% awal, test = 30% akhir.
  - Jalankan engine replay yang sama di kedua potongan secara terpisah.
  - Bandingkan avg R, WR, PF. Edge dianggap LOLOS bila:
        (a) test avg R terbobot > 0, dan
        (b) mayoritas pair (>=60%) tetap avg R > 0 di test.

Tanpa kebocoran lookahead: potongan test memakai `--warmup` bar sebelum titik split
hanya sebagai pemanas indikator; trade dengan waktu entry < waktu split dibuang.

Mode walk-forward (--walk-forward):
  - Bagi data M1 menjadi beberapa jendela bergulir (rolling windows).
  - Tiap jendela: train = 70% awal jendela, test = 30% akhir jendela.
  - Laporan per-jendela + agregat. Menjawab: apakah tanda positif BERTAHAN di semua jendela?

Contoh:
  python3 tools/split_validate.py --data data/tokocrypto_live_usdt \
    --config data/config/scalp_config_exit_full.json --out data/hasil/u4_split_toko_usdt

  python3 tools/split_validate.py --data data/tokocrypto_v4 \
    --config data/config/scalp_config_exit_full.json --walk-forward \
    --window-bars 40000 --step-bars 15000 --out data/hasil/u7_walk_forward_v4
"""
import argparse
import glob
import importlib.util
import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))

SR = None
CFG = {}
FEE = {}
LOOKAHEAD = 120
DATA_DIR = ""
TRAIN_FRAC = 0.70
WARMUP = 300


def load_engine():
    spec = importlib.util.spec_from_file_location("scalp_replay", os.path.join(HERE, "scalp_replay.py"))
    mod = importlib.util.module_from_spec(spec)
    sys.modules["scalp_replay"] = mod
    spec.loader.exec_module(mod)
    return mod


def _init(data_dir, cfg, train_frac, warmup):
    global SR, CFG, FEE, LOOKAHEAD, DATA_DIR, TRAIN_FRAC, WARMUP
    SR = load_engine()
    CFG = cfg
    FEE = cfg.get("fee", {})
    LOOKAHEAD = int(cfg.get("replay", {}).get("lookahead_bars", 120))
    DATA_DIR = data_dir
    TRAIN_FRAC = train_frac
    WARMUP = warmup


def run_seg(seg, split_time):
    """Jalankan replay pada potongan; buang trade yang entry-nya sebelum split_time."""
    m15, h1 = SR.aggregate(seg, 15), SR.aggregate(seg, 60)
    rep = SR.run_replay(seg, m15, h1, CFG, FEE, True, LOOKAHEAD)
    if split_time is not None:
        rep["trades"] = [t for t in rep["trades"] if t["t"] >= split_time]
        cum = peak = max_dd = 0.0
        eq = []
        for t in rep["trades"]:
            if t["result"] not in ("WIN", "LOSS"):
                continue
            cum += t["r"]
            peak = max(peak, cum)
            max_dd = max(max_dd, peak - cum)
            eq.append(round(cum, 4))
        rep["equity_r"] = eq
        rep["max_dd_r"] = round(max_dd, 4)
    return SR.summarize(rep)


def _run_pair(pair):
    try:
        m1 = SR.load_csv(os.path.join(DATA_DIR, f"{pair}_1m.csv"))
    except Exception as e:  # noqa: BLE001
        return {"pair": pair, "error": str(e)}
    n = len(m1)
    if n < 600:
        return {"pair": pair, "skipped": "bars<600"}
    k = int(n * TRAIN_FRAC)
    tr_seg = m1[:k]
    te_lo = max(0, k - WARMUP)
    te_seg = m1[te_lo:n]
    split_t = m1[k]["t"]
    try:
        s_tr = run_seg(tr_seg, None)
        s_te = run_seg(te_seg, split_t)
    except Exception as e:  # noqa: BLE001
        return {"pair": pair, "error": str(e)}
    return {
        "pair": pair, "bars": n, "split_bar": k,
        "train": {"n": s_tr["trades_resolved"], "wr": s_tr["win_rate_pct"],
                  "avg_r": s_tr["avg_r"], "pf": s_tr["profit_factor"]},
        "test": {"n": s_te["trades_resolved"], "wr": s_te["win_rate_pct"],
                 "avg_r": s_te["avg_r"], "pf": s_te["profit_factor"]},
        "exit_reasons_test": s_te.get("by_exit_reason", {}),
    }


# --- Mode walk-forward: jendela bergulir ---

WF_WINDOW = 0   # ukuran jendela dalam bar M1
WF_STEP = 0     # langkah geser antar-jendela


def _init_walk_forward(data_dir, cfg, train_frac, warmup, window_bars, step_bars):
    _init(data_dir, cfg, train_frac, warmup)
    global WF_WINDOW, WF_STEP
    WF_WINDOW = window_bars
    WF_STEP = step_bars


def _run_pair_walk_forward(pair):
    """Satu pair di-semua jendela walk-forward. Return list hasil per-jendela."""
    try:
        m1 = SR.load_csv(os.path.join(DATA_DIR, f"{pair}_1m.csv"))
    except Exception as e:  # noqa: BLE001
        return [{"pair": pair, "error": str(e)}]
    n = len(m1)
    if n < 600:
        return [{"pair": pair, "skipped": "bars<600"}]
    results = []
    w_start = 0
    while w_start + WF_WINDOW <= n:
        w_end = w_start + WF_WINDOW
        win = m1[w_start:w_end]
        k = int(len(win) * TRAIN_FRAC)
        if k < 300 or len(win) - k < 300:
            w_start += WF_STEP
            continue
        tr_seg = win[:k]
        te_lo = max(0, k - WARMUP)
        te_seg = win[te_lo:]
        split_t = win[k]["t"]
        try:
            s_tr = run_seg(tr_seg, None)
            s_te = run_seg(te_seg, split_t)
        except Exception as e:  # noqa: BLE001
            w_start += WF_STEP
            continue
        results.append({
            "pair": pair, "window_start_bar": w_start, "window_end_bar": w_end,
            "window_start_t": win[0]["t"], "window_end_t": win[-1]["t"],
            "split_bar": w_start + k,
            "train": {"n": s_tr["trades_resolved"], "wr": s_tr["win_rate_pct"],
                      "avg_r": s_tr["avg_r"], "pf": s_tr["profit_factor"]},
            "test": {"n": s_te["trades_resolved"], "wr": s_te["win_rate_pct"],
                     "avg_r": s_te["avg_r"], "pf": s_te["profit_factor"]},
            "exit_reasons_test": s_te.get("by_exit_reason", {}),
        })
        w_start += WF_STEP
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="direktori berisi <PAIR>_1m.csv")
    ap.add_argument("--config", default="data/config/scalp_config_exit_full.json")
    ap.add_argument("--train-frac", type=float, default=0.70)
    ap.add_argument("--warmup", type=int, default=300, help="bar M1 pemanas di awal potongan test")
    ap.add_argument("--min-trades", type=int, default=20, help="pair dianggap layak bila trade test >= ini")
    ap.add_argument("--pos-ratio", type=float, default=0.60, help="rasio pair test positif minimum untuk LOLOS")
    ap.add_argument("--jobs", type=int, default=16)
    ap.add_argument("--out", default="")
    ap.add_argument("--top", type=int, default=0)
    ap.add_argument("--walk-forward", action="store_true",
                    help="mode jendela bergulir: beberapa train/test split sepanjang waktu")
    ap.add_argument("--window-bars", type=int, default=40000,
                    help="ukuran jendela walk-forward dalam bar M1 (default 40000 ≈ 27 hari)")
    ap.add_argument("--step-bars", type=int, default=15000,
                    help="langkah geser antar-jendela dalam bar M1 (default 15000 ≈ 10 hari)")
    a = ap.parse_args()

    load_engine()  # validasi modul di proses utama
    with open(a.config) as f:
        cfg = json.load(f)
    fee = cfg.get("fee", {})
    cost = (float(fee.get("buy_taker_pct", 0)) + float(fee.get("sell_taker_pct", 0))
            + 2.0 * float(cfg.get("slippage_pct", 0.08)))

    pairs = sorted(os.path.basename(p)[:-len("_1m.csv")]
                   for p in glob.glob(os.path.join(a.data, "*_1m.csv")))
    if a.top:
        pairs = pairs[:a.top]

    if a.walk_forward:
        print(f"Walk-forward: {len(pairs)} pair | jendela {a.window_bars} bar | "
              f"langkah {a.step_bars} bar | train {a.train_frac:.0%} / test {1-a.train_frac:.0%} "
              f"| friksi {cost:.4f}% | config {a.config}", flush=True)
        return _run_walk_forward(pairs, cfg, a, cost)
    else:
        print(f"Split-validate: {len(pairs)} pair | train {a.train_frac:.0%} / test {1-a.train_frac:.0%} "
              f"| friksi {cost:.4f}% | config {a.config}", flush=True)

    rows = []
    with ProcessPoolExecutor(max_workers=a.jobs, initializer=_init,
                             initargs=(a.data, cfg, a.train_frac, a.warmup)) as ex:
        for i, r in enumerate(ex.map(_run_pair, pairs, chunksize=1), 1):
            if "error" in r:
                print(f"  [{i}/{len(pairs)}] {r['pair']:>14} ERROR {r['error']}", file=sys.stderr, flush=True)
                continue
            if "skipped" in r:
                print(f"  [{i}/{len(pairs)}] {r['pair']:>14} SKIP {r['skipped']}", flush=True)
                continue
            rows.append(r)
            flip = "OK" if (r["train"]["avg_r"] > 0) == (r["test"]["avg_r"] > 0) else "BALIK"
            print(f"  [{i}/{len(pairs)}] {r['pair']:>14} "
                  f"TRAIN n {r['train']['n']:>4} WR {r['train']['wr']:>5.1f}% avgR {r['train']['avg_r']:>7.4f} | "
                  f"TEST n {r['test']['n']:>4} WR {r['test']['wr']:>5.1f}% avgR {r['test']['avg_r']:>7.4f} {flip}",
                  flush=True)

    def agg(key):
        rs = [x for x in rows if x[key]["n"] > 0]
        tot = sum(x[key]["n"] for x in rs)
        if not tot:
            return {}
        wsum = sum(x[key]["avg_r"] * x[key]["n"] for x in rs)
        wins = sum(x[key]["wr"] / 100.0 * x[key]["n"] for x in rs)
        return {
            "pairs": len(rs), "trades": tot,
            "avg_r_weighted": round(wsum / tot, 4),
            "wr_weighted": round(100.0 * wins / tot, 2),
            "pairs_positive": sum(1 for x in rs if x[key]["avg_r"] > 0),
            "pairs_positive_min_trades": sum(1 for x in rs if x[key]["avg_r"] > 0 and x[key]["n"] >= a.min_trades),
        }

    A_tr, A_te = agg("train"), agg("test")
    eligible = [x for x in rows if x["test"]["n"] >= a.min_trades]
    pos_ratio = (sum(1 for x in eligible if x["test"]["avg_r"] > 0) / len(eligible)) if eligible else 0.0
    flips = sum(1 for x in eligible if (x["train"]["avg_r"] > 0) != (x["test"]["avg_r"] > 0))
    passed = (A_te.get("avg_r_weighted", -1) > 0) and (pos_ratio >= a.pos_ratio)

    summary = {
        "data_dir": a.data, "config": a.config,
        "train_frac": a.train_frac, "warmup_bars": a.warmup,
        "cost_roundtrip_pct": round(cost, 4),
        "pairs_scanned": len(rows),
        "train": A_tr, "test": A_te,
        "test_pairs_eligible_min_trades": len(eligible),
        "test_positive_ratio": round(pos_ratio, 3),
        "sign_flips": flips,
        "verdict": "LOLOS" if passed else "TIDAK LOLOS",
    }

    print(f"\n=== RINGKASAN SPLIT ({a.data}) ===")
    print(f"train: n {A_tr.get('trades')} avgR {A_tr.get('avg_r_weighted')} pair+ {A_tr.get('pairs_positive')}/{A_tr.get('pairs')}")
    print(f"test : n {A_te.get('trades')} avgR {A_te.get('avg_r_weighted')} pair+ {A_te.get('pairs_positive')}/{A_te.get('pairs')}")
    print(f"pair layak (test n>={a.min_trades}): {len(eligible)} | rasio positif {pos_ratio:.1%} | balik tanda {flips}")
    print(f"VERDICT: {summary['verdict']}")

    if a.out:
        os.makedirs(os.path.dirname(a.out), exist_ok=True)
        with open(a.out + ".json", "w") as f:
            json.dump({"summary": summary, "pairs": rows}, f, indent=2)
        write_md(a.out + ".md", summary, rows)
        print(f"Ditulis: {a.out}.json / .md")
    return 0


def _run_walk_forward(pairs, cfg, a, cost):
    """Mode walk-forward: jalankan semua pair di semua jendela bergulir."""
    # Kumpulkan semua hasil per-jendela
    all_window_rows = []  # list of (window_idx, pair_result)
    with ProcessPoolExecutor(max_workers=a.jobs, initializer=_init_walk_forward,
                             initargs=(a.data, cfg, a.train_frac, a.warmup,
                                       a.window_bars, a.step_bars)) as ex:
        for i, pair_results in enumerate(ex.map(_run_pair_walk_forward, pairs, chunksize=1), 1):
            if not pair_results:
                # Pair lebih pendek daripada satu jendela (mis. listing baru) → tidak ada
                # jendela sama sekali. Jangan crash; lewati dan laporkan.
                print(f"  [{i}/{len(pairs)}] SKIP (lebih pendek dari {a.window_bars} bar)",
                      flush=True)
                continue
            pair = pair_results[0].get("pair", "?")
            if "error" in pair_results[0]:
                print(f"  [{i}/{len(pairs)}] {pair:>14} ERROR {pair_results[0]['error']}",
                      file=sys.stderr, flush=True)
                continue
            if "skipped" in pair_results[0]:
                print(f"  [{i}/{len(pairs)}] {pair:>14} SKIP {pair_results[0]['skipped']}", flush=True)
                continue
            for wr in pair_results:
                all_window_rows.append(wr)
            print(f"  [{i}/{len(pairs)}] {pair:>14} {len(pair_results)} jendela", flush=True)

    # Kelompokkan per jendela (pakai window_start_bar sebagai key unik)
    windows = {}
    for wr in all_window_rows:
        key = wr["window_start_bar"]
        windows.setdefault(key, []).append(wr)

    window_keys = sorted(windows.keys())
    print(f"\n=== WALK-FORWARD: {len(window_keys)} jendela ===")

    # Agregat per jendela
    window_summaries = []
    for wkey in window_keys:
        rows = windows[wkey]
        def agg(key):
            rs = [x for x in rows if x[key]["n"] > 0]
            tot = sum(x[key]["n"] for x in rs)
            if not tot:
                return {}
            wsum = sum(x[key]["avg_r"] * x[key]["n"] for x in rs)
            wins = sum(x[key]["wr"] / 100.0 * x[key]["n"] for x in rs)
            return {
                "pairs": len(rs), "trades": tot,
                "avg_r_weighted": round(wsum / tot, 4),
                "wr_weighted": round(100.0 * wins / tot, 2),
                "pairs_positive": sum(1 for x in rs if x[key]["avg_r"] > 0),
                "pairs_positive_min_trades": sum(1 for x in rs if x[key]["avg_r"] > 0 and x[key]["n"] >= a.min_trades),
            }
        A_tr, A_te = agg("train"), agg("test")
        eligible = [x for x in rows if x["test"]["n"] >= a.min_trades]
        pos_ratio = (sum(1 for x in eligible if x["test"]["avg_r"] > 0) / len(eligible)) if eligible else 0.0
        flips = sum(1 for x in eligible if (x["train"]["avg_r"] > 0) != (x["test"]["avg_r"] > 0))
        passed = (A_te.get("avg_r_weighted", -1) > 0) and (pos_ratio >= a.pos_ratio)
        window_summaries.append({
            # wkey adalah INDEKS BAR (0, 20000, …) — bukan timestamp. Simpan di
            # window_start_bar, dan ambil timestamp asli dari baris pertama jendela.
            "window_start_bar": wkey,
            "window_start_t": rows[0]["window_start_t"],
            "window_end_bar": rows[0]["window_end_bar"],
            "train": A_tr, "test": A_te,
            "eligible_pairs": len(eligible),
            "positive_ratio": round(pos_ratio, 3),
            "sign_flips": flips,
            "verdict": "LOLOS" if passed else "TIDAK LOLOS",
        })

    # Tampilkan ringkasan per jendela
    from datetime import datetime, timezone
    for i, ws in enumerate(window_summaries):
        t_str = datetime.fromtimestamp(ws["window_start_t"] / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
        print(f"  Jendela {i+1} ({t_str}): "
              f"train n {ws['train'].get('trades',0)} avgR {ws['train'].get('avg_r_weighted',0)} | "
              f"test n {ws['test'].get('trades',0)} avgR {ws['test'].get('avg_r_weighted',0)} | "
              f"pair+ {ws['test'].get('pairs_positive',0)}/{ws['test'].get('pairs',0)} | "
              f"rasio+ {ws['positive_ratio']:.1%} | {ws['verdict']}")

    # Agregat gabungan semua jendela
    all_test_trades = sum(ws["test"].get("trades", 0) for ws in window_summaries)
    all_test_avg_r = 0.0
    if all_test_trades:
        all_test_avg_r = sum(ws["test"].get("avg_r_weighted", 0) * ws["test"].get("trades", 0)
                             for ws in window_summaries) / all_test_trades
    all_train_trades = sum(ws["train"].get("trades", 0) for ws in window_summaries)
    all_train_avg_r = 0.0
    if all_train_trades:
        all_train_avg_r = sum(ws["train"].get("avg_r_weighted", 0) * ws["train"].get("trades", 0)
                              for ws in window_summaries) / all_train_trades
    n_windows_passed = sum(1 for ws in window_summaries if ws["verdict"] == "LOLOS")
    all_pairs = sum(ws["test"].get("pairs", 0) for ws in window_summaries)
    all_pairs_pos = sum(ws["test"].get("pairs_positive", 0) for ws in window_summaries)

    overall_verdict = "BERTAHAN" if n_windows_passed == len(window_summaries) else "TIDAK BERTAHAN"

    summary = {
        "mode": "walk-forward",
        "data_dir": a.data, "config": a.config,
        "train_frac": a.train_frac, "warmup_bars": a.warmup,
        "window_bars": a.window_bars, "step_bars": a.step_bars,
        "cost_roundtrip_pct": round(cost, 4),
        "pairs_scanned": len(pairs),
        "n_windows": len(window_summaries),
        "windows_passed": n_windows_passed,
        "overall_verdict": overall_verdict,
        "train_agg": {"trades": all_train_trades, "avg_r_weighted": round(all_train_avg_r, 4)},
        "test_agg": {"trades": all_test_trades, "avg_r_weighted": round(all_test_avg_r, 4),
                     "pairs_positive_total": all_pairs_pos, "pairs_total": all_pairs},
        "windows": window_summaries,
    }

    print(f"\n=== RINGKASAN WALK-FORWARD ({a.data}) ===")
    print(f"Jendela: {len(window_summaries)} | LOLOS: {n_windows_passed} | verdict: {overall_verdict}")
    print(f"train gabungan: n {all_train_trades} avgR {all_train_avg_r:.4f}")
    print(f"test  gabungan: n {all_test_trades} avgR {all_test_avg_r:.4f} "
          f"pair+ {all_pairs_pos}/{all_pairs}")
    print(f"VERDIKT KESELURUHAN: {overall_verdict}")

    if a.out:
        os.makedirs(os.path.dirname(a.out), exist_ok=True)
        with open(a.out + ".json", "w") as f:
            json.dump({"summary": summary, "windows": all_window_rows}, f, indent=2)
        write_md_walk_forward(a.out + ".md", summary, window_summaries, all_window_rows)
        print(f"Ditulis: {a.out}.json / .md")
    return 0


def write_md_walk_forward(path, summary, window_summaries, all_window_rows):
    from datetime import datetime, timezone
    L = []
    A = L.append
    A("# Validasi Walk-Forward (Jendela Bergulir)")
    A("")
    A(f"- Direktori: `{summary['data_dir']}`")
    A(f"- Config: `{summary['config']}`")
    A(f"- Jendela: {summary['window_bars']} bar (±{summary['window_bars'] // 1440} hari) | "
      f"langkah {summary['step_bars']} bar (±{summary['step_bars'] // 1440} hari)")
    A(f"- Split per jendela: train {summary['train_frac']:.0%} / test {1-summary['train_frac']:.0%}, "
      f"pemanas {summary['warmup_bars']} bar")
    A(f"- Friksi round-trip: **{summary['cost_roundtrip_pct']}%**")
    A(f"- Jendela lolos: **{summary['windows_passed']}/{summary['n_windows']}**")
    A(f"- **Verdict keseluruhan: {summary['overall_verdict']}**")
    A("")
    A("## Ringkasan per jendela")
    A("")
    A("| Jendela | Tanggal Mulai | Train n | Train avgR | Test n | Test WR | Test avgR | Pair+ | Rasio+ | Verdict |")
    A("|---|---|---:|---:|---:|---:|---:|---:|---:|---|")
    for i, ws in enumerate(window_summaries):
        t_str = datetime.fromtimestamp(ws["window_start_t"] / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
        A(f"| {i+1} | {t_str} | {ws['train'].get('trades',0)} | {ws['train'].get('avg_r_weighted',0)} | "
          f"{ws['test'].get('trades',0)} | {ws['test'].get('wr_weighted',0)} | "
          f"{ws['test'].get('avg_r_weighted',0)} | "
          f"{ws['test'].get('pairs_positive',0)}/{ws['test'].get('pairs',0)} | "
          f"{ws['positive_ratio']:.1%} | {ws['verdict']} |")
    A("")
    A("## Agregat gabungan")
    A("")
    A("| Metrik | Train | Test |")
    A("|---|---:|---:|")
    A(f"| Trade resolved | {summary['train_agg']['trades']} | {summary['test_agg']['trades']} |")
    A(f"| avg R terbobot | {summary['train_agg']['avg_r_weighted']} | {summary['test_agg']['avg_r_weighted']} |")
    A("")
    A(f"- Total pair positif di semua jendela: {summary['test_agg']['pairs_positive_total']}")
    A("")
    A("## Per pair per jendela")
    A("")
    A("| Pair | Jendela | Tanggal | Train n | Train avgR | Test n | Test avgR |")
    A("|---|---|---|---:|---:|---:|---:|")
    for wr in sorted(all_window_rows, key=lambda x: (x["pair"], x["window_start_bar"])):
        t_str = datetime.fromtimestamp(wr["window_start_t"] / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
        w_idx = next(i for i, ws in enumerate(window_summaries) if ws["window_start_bar"] == wr["window_start_bar"])
        A(f"| {wr['pair']} | {w_idx+1} | {t_str} | {wr['train']['n']} | {wr['train']['avg_r']:.4f} | "
          f"{wr['test']['n']} | {wr['test']['avg_r']:.4f} |")
    with open(path, "w") as f:
        f.write("\n".join(L) + "\n")


def write_md(path, summary, rows):
    L = []
    A = L.append
    A("# Validasi Out-of-Sample (Train/Test Split)")
    A("")
    A(f"- Direktori: `{summary['data_dir']}`")
    A(f"- Config: `{summary['config']}`")
    A(f"- Split: train {summary['train_frac']:.0%} / test {1-summary['train_frac']:.0%}, pemanas {summary['warmup_bars']} bar")
    A(f"- Friksi round-trip: **{summary['cost_roundtrip_pct']}%**")
    A(f"- **Verdict: {summary['verdict']}**")
    A("")
    A("## Agregat")
    A("")
    A("| Metrik | Train | Test |")
    A("|---|---:|---:|")
    for key, label in [("trades", "Trade resolved"), ("avg_r_weighted", "avg R terbobot"),
                       ("wr_weighted", "WR terbobot %"), ("pairs", "Pair bersinyal"),
                       ("pairs_positive", "Pair avg R > 0")]:
        A(f"| {label} | {summary['train'].get(key)} | {summary['test'].get(key)} |")
    A("")
    A(f"- Pair layak (test n ≥ {summary['test_pairs_eligible_min_trades']}): rasio positif "
      f"**{summary['test_positive_ratio']:.1%}**, balik tanda {summary['sign_flips']}")
    A("")
    A("## Per pair")
    A("")
    A("| Pair | Train n | Train WR | Train avgR | Test n | Test WR | Test avgR | Test PF |")
    A("|---|---:|---:|---:|---:|---:|---:|---:|")
    for r in sorted(rows, key=lambda x: -(x["test"]["avg_r"])):
        pf = r["test"]["pf"]
        pfs = "∞" if pf == float("inf") else f"{pf:.2f}"
        A(f"| {r['pair']} | {r['train']['n']} | {r['train']['wr']:.1f} | {r['train']['avg_r']:.4f} | "
          f"{r['test']['n']} | {r['test']['wr']:.1f} | {r['test']['avg_r']:.4f} | {pfs} |")
    with open(path, "w") as f:
        f.write("\n".join(L) + "\n")


if __name__ == "__main__":
    sys.exit(main())
