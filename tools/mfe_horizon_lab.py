#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P4 -- Lab Horizon: MFE/MAE vs waktu tahan (holding time).

Tujuan
------
Menjawab: pada horizon berapa MFE (Maximum Favorable Excursion) cukup besar
relatif terhadap friksi round-trip Tokocrypto USDT (~0,9688%)?

Metrik kunci
------------
  required_capture = cost / median_MFE * 100
    > 100%  -> mustahil profit walau exit SEMPURNA (MFE < biaya)
    30%     -> butuh menangkap >30% dari MFE tipikal secara konsisten

Metode
------
- Sumber : <data>/<SYM>_<interval>.csv (hanya candle CLOSED).
- Jendela NON-OVERLAP (step = T_bars) agar sampel independen.
- Entry hipotetis di close bar i, lihat ke depan T_bars.
- Barrier test: TP/SL simetris p%, resolusi di bar mentah, SL diutamakan
  bila TP dan SL tersentuh pada bar yang sama (konservatif).

Catatan interval
----------------
--bar-minutes menyatakan durasi satu bar dalam menit. Semua T (baik
--t-minutes maupun --barrier-t-minutes) dinyatakan dalam MENIT, lalu
dikonversi ke jumlah bar. Contoh: bar harian -> --bar-minutes 1440,
dan T=10080 berarti 7 bar = 7 hari.

Referensi biaya: FEE_TRUTH.md (all-in Tokocrypto USDT).
"""

import argparse
import csv
import json
import os
import statistics as st

# --- Friksi all-in Tokocrypto USDT (FEE_TRUTH.md) ---
FEE_BUY = 0.4044
FEE_SELL = 0.4044
SLIP = 0.08
COST = FEE_BUY + FEE_SELL + 2.0 * SLIP  # 0.9688 %

T_MIN_DEFAULT = "1,2,3,5,10,15,30,60,120,240,480,1440"
BARRIER_T_DEFAULT = "15,60,240,1440"
BARRIER_P_DEFAULT = "0.5,1,2,3,5,8"


def fmt_T(minutes):
    """Label manusiawi untuk durasi dalam menit."""
    m = float(minutes)
    if m < 60:
        return f"{m:g}m"
    if m < 1440:
        h = m / 60.0
        return f"{h:g}h"
    d = m / 1440.0
    return f"{d:g}d"


def load_bars(path):
    ts, o, h, l, c, v = [], [], [], [], [], []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            ts.append(int(row["open_time_ms"]))
            o.append(float(row["open"]))
            h.append(float(row["high"]))
            l.append(float(row["low"]))
            c.append(float(row["close"]))
            v.append(float(row["volume"]))
    return ts, o, h, l, c, v


def pct(vals, q):
    if not vals:
        return float("nan")
    s = sorted(vals)
    if len(s) == 1:
        return s[0]
    pos = q * (len(s) - 1)
    lo = int(pos)
    hi = min(lo + 1, len(s) - 1)
    frac = pos - lo
    return s[lo] * (1 - frac) + s[hi] * frac


def excursion_stats(h, l, c, T_bars):
    """MFE/MAE non-overlap, arah LONG (entry di close bar i)."""
    n = len(c)
    mfe, mae = [], []
    i = 0
    while i + T_bars < n:
        e = c[i]
        hi = max(h[i + 1:i + 1 + T_bars])
        lo = min(l[i + 1:i + 1 + T_bars])
        mfe.append((hi - e) / e * 100.0)
        mae.append((lo - e) / e * 100.0)
        i += T_bars
    return mfe, mae


def barrier_test(h, l, c, T_bars, p):
    """TP/SL simetris p%. Return (win, loss, timeout, nets[])."""
    n = len(c)
    wins = losses = tos = 0
    nets = []
    i = 0
    while i + T_bars < n:
        e = c[i]
        tp = e * (1.0 + p / 100.0)
        sl = e * (1.0 - p / 100.0)
        res = None
        for j in range(i + 1, i + 1 + T_bars):
            if l[j] <= sl:          # SL diutamakan (konservatif)
                res = -p
                break
            if h[j] >= tp:
                res = p
                break
        if res is None:
            res = (c[i + T_bars] - e) / e * 100.0
            tos += 1
        elif res > 0:
            wins += 1
        else:
            losses += 1
        nets.append(res - COST)
        i += T_bars
    return wins, losses, tos, nets


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/tokocrypto_v4")
    ap.add_argument("--interval", default="1m", help="Sufiks file candle, mis. 1m / 15m / 1h / 4h / 1d")
    ap.add_argument("--bar-minutes", type=float, default=1.0, help="Durasi 1 bar dalam menit")
    ap.add_argument("--t-minutes", default=T_MIN_DEFAULT, help="Grid waktu tahan (menit), dipisah koma")
    ap.add_argument("--barrier-t-minutes", default=BARRIER_T_DEFAULT)
    ap.add_argument("--barrier-p", default=BARRIER_P_DEFAULT)
    ap.add_argument("--out-json", default="data/hasil/p4_mfe_horizon_lab.json")
    ap.add_argument("--out-md", default="data/hasil/p4_mfe_horizon_lab.md")
    ap.add_argument("--symbols", default="")
    args = ap.parse_args()

    bar_min = float(args.bar_minutes)
    t_list = [float(x) for x in args.t_minutes.split(",") if x.strip()]
    bt_list = [float(x) for x in args.barrier_t_minutes.split(",") if x.strip()]
    bp_list = [float(x) for x in args.barrier_p.split(",") if x.strip()]

    if args.symbols:
        symbols = [s.strip().upper() for s in args.symbols.split(",") if s.strip()]
    else:
        suffix = f"_{args.interval}.csv"
        symbols = sorted(
            f.replace(suffix, "") for f in os.listdir(args.data) if f.endswith(suffix)
        )

    print(f"Simbol   : {', '.join(symbols)}")
    print(f"Interval : {args.interval} ({bar_min:g} menit/bar)")
    print(f"Biaya    : {COST:.4f}% round-trip (fee {FEE_BUY}+{FEE_SELL} + slip {SLIP}x2)")

    data = {}
    for sym in symbols:
        path = os.path.join(args.data, f"{sym}_{args.interval}.csv")
        if not os.path.exists(path):
            print(f"  ! lewat {sym}: file tidak ada")
            continue
        data[sym] = load_bars(path)
        print(f"  ok {sym}: {len(data[sym][0]):,} bar")

    # ---------- Bagian A ----------
    print("\n[A] MFE/MAE vs waktu tahan ...")
    horizon_rows = []
    per_symbol_mfe = {}
    for T_min in t_list:
        T_bars = int(round(T_min / bar_min))
        if T_bars < 1:
            print(f"  ! lewat T={T_min}m (< 1 bar)")
            continue
        all_mfe, all_mae = [], []
        sym_med = {}
        for sym, (ts, o, h, l, c, v) in data.items():
            mfe, mae = excursion_stats(h, l, c, T_bars)
            all_mfe.extend(mfe)
            all_mae.extend(mae)
            if mfe:
                sym_med[sym] = st.median(mfe)
        if not all_mfe:
            print(f"  ! T={T_min}m: sampel kosong")
            continue
        med_mfe = st.median(all_mfe)
        med_mae = st.median(all_mae)
        row = {
            "T_min": T_min,
            "T_bars": T_bars,
            "T_label": fmt_T(T_min),
            "n_windows": len(all_mfe),
            "mfe_median": med_mfe,
            "mfe_p25": pct(all_mfe, 0.25),
            "mfe_p75": pct(all_mfe, 0.75),
            "mfe_p90": pct(all_mfe, 0.90),
            "mae_median": med_mae,
            "mfe_mae_ratio": (med_mfe / abs(med_mae)) if med_mae else float("nan"),
            "pct_mfe_gt_cost": 100.0 * sum(1 for x in all_mfe if x > COST) / len(all_mfe),
            "required_capture_pct": 100.0 * COST / med_mfe if med_mfe else float("inf"),
        }
        horizon_rows.append(row)
        per_symbol_mfe[T_min] = sym_med
        print(
            f"  T={fmt_T(T_min):>6} ({T_bars:>4} bar)  n={row['n_windows']:>7}  "
            f"MFE med={med_mfe:6.3f}%  MAE med={med_mae:7.3f}%  "
            f"capture_wajib={row['required_capture_pct']:7.1f}%"
        )

    # ---------- Bagian B ----------
    print("\n[B] Barrier test (TP/SL simetris, entry buta) ...")
    barrier_rows = []
    for T_min in bt_list:
        T_bars = int(round(T_min / bar_min))
        if T_bars < 1:
            continue
        for p in bp_list:
            W = L = TO = 0
            nets = []
            for sym, (ts, o, h, l, c, v) in data.items():
                w, lo, to, nt = barrier_test(h, l, c, T_bars, p)
                W += w
                L += lo
                TO += to
                nets.extend(nt)
            tot = W + L + TO
            if not tot:
                continue
            exp_net = sum(nets) / len(nets)
            req_wr = (p + COST) / (2.0 * p) * 100.0
            barrier_rows.append({
                "T_min": T_min,
                "T_label": fmt_T(T_min),
                "T_bars": T_bars,
                "tp_sl_pct": p,
                "n": tot,
                "win_pct": 100.0 * W / tot,
                "loss_pct": 100.0 * L / tot,
                "timeout_pct": 100.0 * TO / tot,
                "exp_net_pct": exp_net,
                "req_winrate_breakeven_pct": req_wr,
            })
            print(
                f"  T={fmt_T(T_min):>6} p={p:>4.1f}%  win={100.0*W/tot:5.1f}%  "
                f"timeout={100.0*TO/tot:5.1f}%  E[net]={exp_net:+.3f}%  "
                f"(perlu WR {req_wr:.1f}%)"
            )

    # ---------- Bagian C ----------
    wr_table = []
    for p in [0.5, 1.0, 1.5, 2.0, 3.0, 5.0, 8.0, 12.0, 20.0]:
        wr_table.append({"tp_sl_pct": p, "req_winrate_pct": (p + COST) / (2.0 * p) * 100.0})

    out = {
        "cost_roundtrip_pct": COST,
        "fee_buy_pct": FEE_BUY,
        "fee_sell_pct": FEE_SELL,
        "slippage_pct": SLIP,
        "interval": args.interval,
        "bar_minutes": bar_min,
        "symbols": symbols,
        "horizon": horizon_rows,
        "barrier": barrier_rows,
        "breakeven_winrate": wr_table,
        "per_symbol_mfe_median": {str(k): v for k, v in per_symbol_mfe.items()},
    }
    os.makedirs(os.path.dirname(args.out_json), exist_ok=True)
    with open(args.out_json, "w") as f:
        json.dump(out, f, indent=2)
    print(f"\nJSON -> {args.out_json}")

    # ---------- Markdown ----------
    L_ = []
    A = L_.append
    A("# P4 — Lab Horizon: MFE/MAE vs Waktu Tahan\n")
    A(f"Biaya round-trip Tokocrypto USDT (all-in): **{COST:.4f}%** "
      f"(fee {FEE_BUY}%×2 + slippage {SLIP}%×2).\n")
    A(f"Simbol: {', '.join(symbols)}. Candle `{args.interval}` ({bar_min:g} menit/bar). "
      "Jendela non-overlap (step = T).\n")
    A("> `required_capture` = biaya ÷ median MFE. **Jika > 100%, profit mustahil "
      "walau exit sempurna** — langit-langit lebih rendah dari lantai.\n")

    A("\n## A. MFE / MAE menurut waktu tahan\n")
    A("| T | bar | n | MFE p25 | **MFE median** | MFE p75 | MFE p90 | MAE median | MFE/MAE | %MFE>biaya | **capture wajib** |")
    A("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in horizon_rows:
        flag = " ❌" if r["required_capture_pct"] > 100 else (" ⚠️" if r["required_capture_pct"] > 50 else " ✅")
        A(f"| {r['T_label']} | {r['T_bars']} | {r['n_windows']:,} | {r['mfe_p25']:.3f}% | "
          f"**{r['mfe_median']:.3f}%** | {r['mfe_p75']:.3f}% | {r['mfe_p90']:.3f}% | "
          f"{r['mae_median']:.3f}% | {r['mfe_mae_ratio']:.2f} | "
          f"{r['pct_mfe_gt_cost']:.1f}% | **{r['required_capture_pct']:.1f}%**{flag} |")

    A("\n## B. Barrier test — entry buta (tanpa sinyal)\n")
    A("TP dan SL simetris. Entry di close setiap jendela, tanpa sinyal apa pun. "
      "Ini mengukur **lantai biaya**: berapa yang hilang tanpa edge.\n")
    A("| T | TP=SL | n | win% | timeout% | **E[net]/trade** | WR breakeven |")
    A("|---|---:|---:|---:|---:|---:|---:|")
    for r in barrier_rows:
        A(f"| {r['T_label']} | ±{r['tp_sl_pct']:.1f}% | {r['n']:,} | {r['win_pct']:.1f}% | "
          f"{r['timeout_pct']:.1f}% | **{r['exp_net_pct']:+.3f}%** | "
          f"{r['req_winrate_breakeven_pct']:.1f}% |")

    A("\n## C. Win-rate minimum agar breakeven (analitik)\n")
    A("Untuk TP = SL = p%, dengan biaya c: `w_min = (p + c) / (2p)`.\n")
    A("| TP=SL | Win-rate minimum |")
    A("|---:|---:|")
    for r in wr_table:
        A(f"| ±{r['tp_sl_pct']:.1f}% | {r['req_winrate_pct']:.1f}% |")

    A("\n## D. Median MFE per simbol\n")
    cols = [r["T_label"] for r in horizon_rows]
    A("| Simbol | " + " | ".join(cols) + " |")
    A("|---|" + "---:|" * len(cols))
    for sym in symbols:
        if sym not in data:
            continue
        cells = []
        for r in horizon_rows:
            v = per_symbol_mfe.get(r["T_min"], {}).get(sym)
            cells.append(f"{v:.3f}%" if v is not None else "-")
        A(f"| {sym} | " + " | ".join(cells) + " |")

    A("\n---\n")
    A("Reproduksi:\n```\n"
      f"python3 tools/mfe_horizon_lab.py --data {args.data} --interval {args.interval} "
      f"--bar-minutes {bar_min:g} --t-minutes {args.t_minutes} "
      f"--barrier-t-minutes {args.barrier_t_minutes} "
      f"--out-json {args.out_json} --out-md {args.out_md}\n```\n")

    with open(args.out_md, "w") as f:
        f.write("\n".join(L_))
    print(f"MD   -> {args.out_md}")


if __name__ == "__main__":
    main()
