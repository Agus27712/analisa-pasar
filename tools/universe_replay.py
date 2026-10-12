#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Harness uji edge di SELURUH universe pair Tokocrypto memakai engine replay asli
(`tools/scalp_replay.py`) dengan config aplikasi yang sama.

Tujuan: menjawab dengan data, bukan asumsi:
  - Berapa pair yang benar-benar menghasilkan sinyal?
  - Dari yang bersinyal, berapa yang avg R net > 0 setelah fee all-in?
  - Apakah friksi bursa adalah penghalang utama (dan untuk pair mana)?

Keluaran: ringkasan JSON + tabel Markdown.

Contoh:
  python3 tools/universe_replay.py --data data/tokocrypto_live_usdt \
    --config data/config/scalp_config_exit_full.json --out-prefix data/hasil/u1_toko_usdt
"""
import argparse
import glob
import importlib.util
import json
import os
import statistics as st
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def load_engine():
    spec = importlib.util.spec_from_file_location("scalp_replay", os.path.join(HERE, "scalp_replay.py"))
    mod = importlib.util.module_from_spec(spec)
    sys.modules["scalp_replay"] = mod
    spec.loader.exec_module(mod)
    return mod


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="direktori berisi <PAIR>_1m.csv")
    ap.add_argument("--config", default="data/config/scalp_config_exit_full.json")
    ap.add_argument("--out-prefix", default="")
    ap.add_argument("--min-trades", type=int, default=1, help="pair dengan trade < ini ditandai 'tipis'")
    ap.add_argument("--top", type=int, default=0, help="batasi N pair pertama (0 = semua)")
    a = ap.parse_args()

    sr = load_engine()
    with open(a.config) as f:
        cfg = json.load(f)
    fee = cfg.get("fee", {})
    rp = cfg.get("replay", {})
    lookahead = int(rp.get("lookahead_bars", 120))
    cost = float(fee.get("buy_taker_pct", 0)) + float(fee.get("sell_taker_pct", 0)) \
        + 2.0 * float(cfg.get("slippage_pct", 0.08))

    pairs = sorted(os.path.basename(p)[:-len("_1m.csv")]
                   for p in glob.glob(os.path.join(a.data, "*_1m.csv")))
    if a.top:
        pairs = pairs[:a.top]
    print(f"Universe: {len(pairs)} pair | friksi round-trip = {cost:.4f}% | config = {a.config}", flush=True)

    rows = []
    for i, pair in enumerate(pairs, 1):
        m1_path = os.path.join(a.data, f"{pair}_1m.csv")
        m1 = sr.load_csv(m1_path)
        m15p = os.path.join(a.data, f"{pair}_15m.csv")
        h1p = os.path.join(a.data, f"{pair}_1h.csv")
        if rp.get("htf_source") == "csv" and os.path.exists(m15p) and os.path.exists(h1p):
            m15, h1 = sr.load_csv(m15p), sr.load_csv(h1p)
        else:
            m15, h1 = sr.aggregate(m1, 15), sr.aggregate(m1, 60)
        try:
            rep = sr.run_replay(m1, m15, h1, cfg, fee, True, lookahead)
            s = sr.summarize(rep)
        except Exception as e:  # noqa: BLE001
            print(f"  [{i}/{len(pairs)}] {pair:>14} ERROR {e}", file=sys.stderr, flush=True)
            continue

        # median MFE/MAE pada horizon 7 jam (proxy: 7*60 bar M1) untuk konteks friksi
        bars_mfe = []
        T = 420
        n = len(m1)
        step = max(1, n // 4000)
        for k in range(0, n - T, step):
            e0 = m1[k]["c"]
            if e0 <= 0:
                continue
            hi = max(b["h"] for b in m1[k + 1:k + 1 + T])
            lo = min(b["l"] for b in m1[k + 1:k + 1 + T])
            bars_mfe.append((100.0 * (hi - e0) / e0, 100.0 * (lo - e0) / e0))
        mfe7 = st.median([x[0] for x in bars_mfe]) if bars_mfe else float("nan")

        r = {
            "pair": pair,
            "bars_m1": len(m1),
            "evaluations": s["evaluations"],
            "ready_signals": s["ready_signals"],
            "trades_resolved": s["trades_resolved"],
            "unresolved": s["unresolved"],
            "win_rate_pct": s["win_rate_pct"],
            "avg_r": s["avg_r"],
            "profit_factor": s["profit_factor"],
            "max_drawdown_r": s["max_drawdown_r"],
            "mfe_7h_median_pct": round(mfe7, 4) if mfe7 == mfe7 else None,
            "capture_required_7h_pct": round(100.0 * cost / mfe7, 1) if mfe7 and mfe7 == mfe7 else None,
            "by_exit_reason": s.get("by_exit_reason", {}),
            "by_setup": s.get("by_setup", {}),
        }
        rows.append(r)
        pf = r["profit_factor"]
        pfs = f"{pf:.2f}" if isinstance(pf, (int, float)) and pf != float("inf") else str(pf)
        print(f"  [{i}/{len(pairs)}] {pair:>14} sinyal {r['ready_signals']:>4} trade {r['trades_resolved']:>4} "
              f"WR {r['win_rate_pct']:>5.1f}% avgR {r['avg_r']:>7.4f} PF {pfs:>6} MFE7h {mfe7:>6.2f}%",
              flush=True)

    # ---- agregasi ----
    def agg(rs):
        tr = [x for x in rs if x["trades_resolved"] > 0]
        if not tr:
            return {}
        tot = sum(x["trades_resolved"] for x in tr)
        wsum = sum(x["avg_r"] * x["trades_resolved"] for x in tr)
        return {
            "pairs_with_trades": len(tr),
            "pairs_total": len(rs),
            "trades_total": tot,
            "avg_r_weighted": round(wsum / tot, 4) if tot else None,
            "pairs_positive": sum(1 for x in tr if x["avg_r"] > 0),
            "pairs_positive_min30": sum(1 for x in tr if x["avg_r"] > 0 and x["trades_resolved"] >= 30),
        }

    pos = [x for x in rows if x["trades_resolved"] > 0 and x["avg_r"] > 0]
    pos.sort(key=lambda x: -x["avg_r"])
    allr = sorted([x for x in rows if x["trades_resolved"] > 0], key=lambda x: -x["avg_r"])

    summary = {
        "data_dir": a.data,
        "config": a.config,
        "cost_roundtrip_pct": round(cost, 4),
        "pairs_scanned": len(rows),
        "aggregate": agg(rows),
        "best_pairs": pos[:25],
        "worst_pairs": list(reversed(allr))[:10],
    }

    if a.out_prefix:
        os.makedirs(os.path.dirname(a.out_prefix), exist_ok=True)
        with open(a.out_prefix + ".json", "w") as f:
            json.dump({"summary": summary, "pairs": rows}, f, indent=2)
        write_md(a.out_prefix + ".md", summary, allr, cost)
        print(f"\nDitulis: {a.out_prefix}.json / .md")

    A = summary["aggregate"]
    print(f"\n=== RINGKASAN ({a.data}) ===")
    print(f"pair dipindai        : {summary['pairs_scanned']}")
    print(f"pair ada trade       : {A.get('pairs_with_trades')}")
    print(f"total trade resolved : {A.get('trades_total')}")
    print(f"avg R terbobot       : {A.get('avg_r_weighted')}")
    print(f"pair avg R > 0       : {A.get('pairs_positive')}")
    print(f"pair avg R > 0 & n>=30: {A.get('pairs_positive_min30')}")
    return 0


def write_md(path, summary, allr, cost):
    L = []
    A = L.append
    A("# Uji Universe — Engine Scalping di Data Riil Tokocrypto")
    A("")
    A(f"- Direktori data: `{summary['data_dir']}`")
    A(f"- Config: `{summary['config']}`")
    A(f"- Friksi round-trip (fee all-in + 2x slippage): **{cost:.4f}%**")
    A(f"- Pair dipindai: **{summary['pairs_scanned']}**")
    A("")
    ag = summary["aggregate"]
    A("## Agregat")
    A("")
    A("| Metrik | Nilai |")
    A("|---|---:|")
    A(f"| Pair yang menghasilkan trade | {ag.get('pairs_with_trades')} / {ag.get('pairs_total')} |")
    A(f"| Total trade resolved | {ag.get('trades_total')} |")
    A(f"| avg R terbobot | {ag.get('avg_r_weighted')} |")
    A(f"| Pair avg R > 0 | {ag.get('pairs_positive')} |")
    A(f"| Pair avg R > 0 dan n ≥ 30 | {ag.get('pairs_positive_min30')} |")
    A("")
    A("## Semua pair (urut avg R)")
    A("")
    A("| Pair | Sinyal | Trade | WR % | avg R | PF | MaxDD R | MFE 7h med | capture wajib |")
    A("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in allr:
        pf = r["profit_factor"]
        pfs = "∞" if pf == float("inf") else (f"{pf:.2f}" if isinstance(pf, (int, float)) else "-")
        mfe = f"{r['mfe_7h_median_pct']:.2f}%" if r["mfe_7h_median_pct"] is not None else "-"
        cap = f"{r['capture_required_7h_pct']:.0f}%" if r["capture_required_7h_pct"] is not None else "-"
        A(f"| {r['pair']} | {r['ready_signals']} | {r['trades_resolved']} | {r['win_rate_pct']:.1f} | "
          f"{r['avg_r']:.4f} | {pfs} | {r['max_drawdown_r']:.2f} | {mfe} | {cap} |")
    with open(path, "w") as f:
        f.write("\n".join(L) + "\n")


if __name__ == "__main__":
    sys.exit(main())
