#!/usr/bin/env python3
"""Sweep lantai friksi (min_friction_multiple) dengan semua parameter lain tetap.

Yang diubah HANYA `min_friction_multiple`. Data, gate P0, fee, dan aturan exit identik.
Karena lantai friksi ikut menentukan SL **dan** target (target_net_rr 2:1), setiap
kelipatan menghasilkan geometri risiko yang berbeda; itu memang efek yang diukur.

Metrik utama adalah **net %** (uang), bukan R: R didefinisikan oleh SL, sehingga
membandingkan R antar-kelipatan bias ke arah SL lebar (lihat laporan).

Contoh:
  python3 tools/friction_sweep.py --pair ADAUSDT --data data/tokocrypto_v4
  python3 tools/friction_sweep.py --pair ADAUSDT --multiples 1,2,3,4,6,8,10
  python3 tools/friction_sweep.py --pair ADAUSDT --exit-mode net_tp --net-tp 0.5 --holds 420
"""
import argparse
import datetime as dt
import json
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import scalp_replay as sr  # noqa: E402

MULTIPLES = (0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0)


def collect_candidates(m1, cfg, fee, btc, cache_path):
    """Evaluasi gate sekali; simpan bar yang lolos s3 + gate P0 beserta masukan risk_levels."""
    if os.path.exists(cache_path):
        with open(cache_path) as f:
            return json.load(f)
    rp = cfg.get("replay", sr.DEFAULTS["replay"])
    m15_min = int(rp.get("m15_min_bars", 20))
    m5_min = int(rp.get("m5_min_bars", 20))
    h1_min = int(rp.get("h1_min_bars", 20))
    wlen = int(rp.get("m1_window", 250))
    m15 = sr.aggregate(m1, 15)
    h1 = sr.aggregate(m1, 60)
    m5 = sr.aggregate(m1, 5)
    btc5 = sr.aggregate(btc, 5) if btc else []
    p15 = p60 = p5 = p5b = pb1 = 0
    last_btc_key = None
    btc_res = {"veto": False, "reason": "", "sufficient": False, "status": "NO_DATA"}
    cands = []
    n = len(m1)
    i = 0
    while i < n:
        c = m1[i]
        t = c["t"]
        while p15 < len(m15) and m15[p15]["t"] + 15 * 60_000 <= t:
            p15 += 1
        while p60 < len(h1) and h1[p60]["t"] + 60 * 60_000 <= t:
            p60 += 1
        while p5 < len(m5) and m5[p5]["t"] + 5 * 60_000 <= t:
            p5 += 1
        while p5b < len(btc5) and btc5[p5b]["t"] + 5 * 60_000 <= t:
            p5b += 1
        while pb1 < len(btc) and btc[pb1]["t"] + 60_000 <= t:
            pb1 += 1
        m15w = m15[max(0, p15 - 60):p15]
        h1w = h1[max(0, p60 - 60):p60]
        m5w = m5[max(0, p5 - 60):p5]
        m1w = m1[max(0, i - wlen + 1):i + 1]
        if (len(m1w) < 20 or len(m15w) < m15_min or len(m5w) < m5_min
                or len(h1w) < h1_min):
            i += 1
            continue
        if btc5 and (p5b, pb1) != last_btc_key:
            btc_res = sr.evaluate_btc_veto(
                btc5[max(0, p5b - 120):p5b],
                btc[max(0, pb1 - 60):pb1] if btc else [], cfg)
            last_btc_key = (p5b, pb1)
        chg24 = 0.0
        if i >= 1440 and m1[i - 1440]["c"] > 0:
            chg24 = (c["c"] / m1[i - 1440]["c"] - 1.0) * 100.0
        r = sr.evaluate_bar(c["c"], m1w, m15w, h1w, cfg, fee, True,
                            change24h=chg24, btc_veto=btc_res["veto"],
                            btc_reason=btc_res["reason"], m5w=m5w,
                            want_risk_inputs=True)
        if r is not None:
            ri = r.get("risk_inputs")
            if ri and ri["s3"] and r["mtf_allow"] and not r["btc_veto"]:
                cands.append({
                    "i": i, "t": t, "price": c["c"],
                    "setup": ri["setup"], "score": ri["score"],
                    "support": ri["st"]["support"],
                    "resistance": ri["st"]["resistance"],
                    "last_swing_low": ri["st"]["last_swing_low"],
                    "atr_pct": ri["atr_pct"],
                })
        i += 1
    out = {"cands": cands, "bars": n, "from": m1[0]["t"], "to": m1[-1]["t"]}
    with open(cache_path, "w") as f:
        json.dump(out, f)
    return out


def simulate(m1, cache, cfg, fee, exit_cfg):
    cands = cache["cands"]
    lookahead = int(cfg["replay"]["lookahead_bars"])
    trades = []
    ready = invalid = 0
    skip_until = 0
    for c in cands:
        if c["i"] < skip_until:
            continue
        st = {"support": c["support"], "resistance": c["resistance"],
              "last_swing_low": c["last_swing_low"]}
        rl = sr.risk_levels(c["price"], c["setup"], st,
                            {"atr_pct": c["atr_pct"]}, fee, cfg)
        if rl is None:
            invalid += 1
            continue
        if not rl["valid"]:
            invalid += 1
            continue
        if c["score"] < sr.min_score_for_setup(c["setup"], cfg):
            continue
        ready += 1
        res = sr.resolve_trade(m1, c["i"], rl["entry"], rl["sl"],
                               rl["tp1"], rl["tp2"], fee, exit_cfg,
                               lookahead, atr_pct=c["atr_pct"])
        trades.append({
            "t": c["t"], "result": res["result"], "r": res["r"],
            "exit_reason": res.get("exit_reason", ""),
            "risk_pct": (rl["entry"] - rl["sl"]) / rl["entry"] * 100.0,
            "sl_gross_pct": (rl["entry"] - rl["sl"]) / rl["entry"] * 100.0,
            "tp2_gross_pct": (rl["tp2"] - rl["entry"]) / rl["entry"] * 100.0,
            "exit_t": (m1[res["exit_idx"]]["t"]
                       if res.get("exit_idx") is not None else None),
        })
        if res["result"] == "UNRESOLVED" or res.get("exit_idx") is None:
            skip_until = c["i"] + lookahead
            continue
        skip_until = res["exit_idx"] + 1
    return trades, ready, invalid


def summarize(trades, days, ready, invalid):
    tr = [t for t in trades if t["result"] in ("WIN", "LOSS")]
    if not tr:
        return None
    wins = [t for t in tr if t["result"] == "WIN"]
    win_net = [t["r"] * t["risk_pct"] for t in wins]
    loss_net = [t["r"] * t["risk_pct"] for t in tr if t["result"] == "LOSS"]
    day_wins, day_net = {}, {}
    cum = peak = max_dd = 0.0
    for t in tr:
        n = t["r"] * t["risk_pct"]
        cum += n
        peak = max(peak, cum)
        max_dd = min(max_dd, cum - peak)
        if t.get("exit_t") is not None:
            day = dt.datetime.fromtimestamp(t["exit_t"] / 1000, dt.timezone.utc).date()
            day_net[day] = day_net.get(day, 0.0) + n
            if t["result"] == "WIN":
                day_wins[day] = day_wins.get(day, 0) + 1
    win_avg = sum(win_net) / len(win_net) if win_net else 0.0
    loss_avg = sum(loss_net) / len(loss_net) if loss_net else 0.0
    total_net = sum(t["r"] * t["risk_pct"] for t in tr)
    return {
        "ready": ready, "invalid_risk": invalid, "n": len(tr),
        "wins": len(wins),
        "win_rate_pct": round(100 * len(wins) / len(tr), 2),
        "avg_r": round(sum(t["r"] for t in tr) / len(tr), 4),
        "avg_win_net_pct": round(win_avg, 3),
        "avg_loss_net_pct": round(loss_avg, 3),
        "breakeven_wr_pct": round(100 * abs(loss_avg) / (win_avg + abs(loss_avg)), 1),
        "avg_net_pct_per_trade": round(total_net / len(tr), 4),
        "net_pct_per_day": round(total_net / days, 4),
        "total_net_pct": round(total_net, 2),
        "max_dd_net_pct": round(max_dd, 2),
        "net_exits_per_day": round(len(wins) / days, 3),
        "days_pct_with_net_exit": round(100 * len(day_wins) / days, 1),
        "sl_pct": round(100 * sum(1 for t in tr
                                  if t["exit_reason"] == "SL") / len(tr), 1),
        "timeout_pct": round(100 * sum(1 for t in tr
                                       if t["exit_reason"] == "TIMEOUT") / len(tr), 1),
        "sl_gross_pct": round(sum(t["sl_gross_pct"] for t in tr) / len(tr), 2),
        "tp2_gross_pct": round(sum(t["tp2_gross_pct"] for t in tr) / len(tr), 2),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pair", required=True)
    ap.add_argument("--data", default=str(ROOT / "data/tokocrypto_v4"))
    ap.add_argument("--config", default=str(ROOT / "data/config/scalp_config.json"))
    ap.add_argument("--multiples", default=",".join(str(x) for x in MULTIPLES))
    ap.add_argument("--exit-mode", default="classic")
    ap.add_argument("--net-tp", type=float, default=1.0)
    ap.add_argument("--holds", default="90")
    ap.add_argument("--cache-dir",
                    default=os.path.join(tempfile.gettempdir(), "friction_sweep_cache"))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    os.makedirs(args.cache_dir, exist_ok=True)
    base = sr.merge_defaults(json.load(open(args.config)))
    fee = base["fee"]
    m1 = sr.load_csv(os.path.join(args.data, f"{args.pair}_1m.csv"))
    btc_path = os.path.join(args.data, "BTCUSDT_1m.csv")
    btc = sr.load_csv(btc_path) if os.path.exists(btc_path) else []
    cache = collect_candidates(
        m1, base, fee, btc,
        os.path.join(args.cache_dir, f"cand_{args.pair}.json"))
    days = (cache["to"] - cache["from"]) / 86_400_000.0
    holds = [int(h) for h in args.holds.split(",")]

    print(f"pair={args.pair} bars={cache['bars']} hari={days:.2f} "
          f"kandidat_s3={len(cache['cands'])} exit={args.exit_mode}")
    rows = []
    for hold in holds:
        cfg = json.loads(json.dumps(base))
        cfg["exit"]["mode"] = args.exit_mode
        cfg["exit"]["net_tp_pct"] = args.net_tp
        cfg["exit"]["max_hold_bars_m1"] = hold
        cfg["replay"]["lookahead_bars"] = max(hold, 120) + 60
        print(f"\n-- hold {hold}m --")
        print(f"{'mult':>5} {'SL%':>6} {'TP2%':>6} {'ready':>6} {'N':>4} {'WR%':>6} "
              f"{'BE%':>6} {'avgR':>8} {'net%/tr':>8} {'net%/hari':>9} "
              f"{'DD%':>7} {'SL%':>5} {'TO%':>5} {'hari%':>6}")
        for m in [float(x) for x in args.multiples.split(",")]:
            cfg["min_friction_multiple"] = m
            trades, ready, invalid = simulate(m1, cache, cfg, fee, cfg)
            s = summarize(trades, days, ready, invalid)
            if not s:
                continue
            print(f"{m:>5} {s['sl_gross_pct']:>6} {s['tp2_gross_pct']:>6} "
                  f"{s['ready']:>6} {s['n']:>4} {s['win_rate_pct']:>6} "
                  f"{s['breakeven_wr_pct']:>6} {s['avg_r']:>8} "
                  f"{s['avg_net_pct_per_trade']:>8} {s['net_pct_per_day']:>9} "
                  f"{s['max_dd_net_pct']:>7} {s['sl_pct']:>5} "
                  f"{s['timeout_pct']:>5} {s['days_pct_with_net_exit']:>6}")
            rows.append({"pair": args.pair, "hold": hold,
                         "multiple": m, **s})
    out = args.out or os.path.join(args.cache_dir, f"sweep_{args.pair}.json")
    with open(out, "w") as f:
        json.dump(rows, f, indent=1)
    print(f"\nhasil JSON: {out}")


if __name__ == "__main__":
    main()
