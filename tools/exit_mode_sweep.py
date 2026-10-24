#!/usr/bin/env python3
"""Sweep mode exit pada sinyal P0 yang sama.

Tujuan: mengukur apakah salah satu geometri exit (`classic`, `tp1_be`, `early`,
`trail`, `full`) memperbaiki **net %** pada entry yang sudah ada. Gate entry,
fee, dan lantai friksi tidak diubah.

Dua cara membandingkan:

1. ``sequential`` — meniru replay/CLI: setelah exit, sinyal berikutnya boleh masuk.
   Kontrol ``classic`` wajib 620 trade / WR 14,84% / avg R -0,1569.
2. ``same_entry`` — entri yang dipilih ``classic`` pada hold yang sama di-resolve
   ulang oleh semua mode. Ini mengisolasi efek exit, tanpa perubahan jumlah trade.

Metrik utama adalah **net %** (uang), bukan R: R dihitung dari jarak SL sehingga
perbandingan antar-geometri bias ke SL lebar.

Contoh:
  python3 tools/exit_mode_sweep.py \
      --pairs ADAUSDT,DOGEUSDT,XRPUSDT --data data/tokocrypto_v4 \
      --modes classic,tp1_be,early,trail,full --holds 90,240,420 \
      --cache-dir /tmp/friction_sweep_cache \
      --out data/hasil/exp_exit_mode_sweep.json
"""
import argparse
import bisect
import datetime as dt
import json
import os
import random
import sys
import tempfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import friction_sweep as fs  # noqa: E402
import scalp_replay as sr  # noqa: E402

MODES = ("classic", "tp1_be", "early", "trail", "full")
HOLDS = (90, 240, 420)
PAIRS = ("ADAUSDT", "DOGEUSDT", "XRPUSDT")
CONTROL = {"n": 620, "win_rate_pct": 14.84, "avg_r": -0.1569}


def pair_cfg(base, mode, hold):
    cfg = json.loads(json.dumps(base))
    cfg["exit"]["mode"] = mode
    cfg["exit"]["max_hold_bars_m1"] = hold
    cfg["replay"]["lookahead_bars"] = max(hold, 120) + 60
    return cfg


def candidates_for(data_dir, pair, cfg, fee, cache_dir, rebuild=False):
    cache_path = os.path.join(cache_dir, f"cand_{pair}.json")
    if rebuild and os.path.exists(cache_path):
        os.remove(cache_path)
    m1 = sr.load_csv(os.path.join(data_dir, f"{pair}_1m.csv"))
    btc_path = os.path.join(data_dir, "BTCUSDT_1m.csv")
    btc = sr.load_csv(btc_path) if os.path.exists(btc_path) else []
    cache = fs.collect_candidates(m1, cfg, fee, btc, cache_path)
    return m1, cache


def select_entries(m1, cache, cfg, fee):
    """Entri non-overlap persis seperti `friction_sweep.simulate`."""
    look = int(cfg["replay"]["lookahead_bars"])
    entries = []
    invalid = 0
    skip = 0
    for c in cache["cands"]:
        if c["i"] < skip:
            continue
        st = {"support": c["support"], "resistance": c["resistance"],
              "last_swing_low": c["last_swing_low"]}
        rl = sr.risk_levels(c["price"], c["setup"], st,
                            {"atr_pct": c["atr_pct"]}, fee, cfg)
        if rl is None or not rl["valid"]:
            invalid += 1
            continue
        if c["score"] < sr.min_score_for_setup(c["setup"], cfg):
            continue
        entries.append((c, rl))
        res = sr.resolve_trade(m1, c["i"], rl["entry"], rl["sl"],
                               rl["tp1"], rl["tp2"], fee, cfg, look,
                               atr_pct=c["atr_pct"])
        if res["result"] == "UNRESOLVED" or res.get("exit_idx") is None:
            skip = c["i"] + look
        else:
            skip = res["exit_idx"] + 1
    return entries, invalid


def resolve_entries(m1, entries, cfg, fee):
    look = int(cfg["replay"]["lookahead_bars"])
    trades = []
    for c, rl in entries:
        res = sr.resolve_trade(m1, c["i"], rl["entry"], rl["sl"],
                               rl["tp1"], rl["tp2"], fee, cfg, look,
                               atr_pct=c["atr_pct"])
        risk_pct = (rl["entry"] - rl["sl"]) / rl["entry"] * 100.0
        exit_idx = res.get("exit_idx")
        trades.append({
            "pair": None,
            "t": c["t"],
            "result": res["result"],
            "r": res["r"],
            "risk_pct": risk_pct,
            "exit_reason": res.get("exit_reason", ""),
            "exit_t": m1[exit_idx]["t"] if exit_idx is not None else None,
        })
    return trades


def percentile_rank(values, x):
    """Persentil 0-100 dari `x` di antara `values` (fraksi nilai <= x)."""
    if not values:
        return 0.0
    s = sorted(values)
    return 100.0 * bisect.bisect_right(s, x) / len(s)


def bucket_of(p):
    if p is None:
        return "na"
    lo = min(80, int(p // 20) * 20)
    return f"{lo}-{lo + 20}"


def bootstrap_ci(values, resamples=1000, seed=0):
    if len(values) < 2:
        return None
    rng = random.Random(seed)
    n = len(values)
    means = []
    for _ in range(resamples):
        total = 0.0
        for _ in range(n):
            total += values[rng.randrange(n)]
        means.append(total / n)
    means.sort()

    def pct(q):
        pos = q * (len(means) - 1)
        lo = int(pos)
        hi = min(lo + 1, len(means) - 1)
        frac = pos - lo
        return means[lo] * (1.0 - frac) + means[hi] * frac

    return [round(pct(0.025), 4), round(pct(0.975), 4)]


def summarize(trades, days, resamples=0, seed=0):
    valid = [t for t in trades if t["result"] in ("WIN", "LOSS")]
    unresolved = len(trades) - len(valid)
    if not valid:
        return {"n": 0, "unresolved": unresolved}
    wins = [t for t in valid if t["result"] == "WIN"]
    nets = [t["r"] * t["risk_pct"] for t in valid]
    win_net = [t["r"] * t["risk_pct"] for t in wins]
    loss_net = [t["r"] * t["risk_pct"] for t in valid if t["result"] == "LOSS"]
    win_avg = sum(win_net) / len(win_net) if win_net else 0.0
    loss_avg = sum(loss_net) / len(loss_net) if loss_net else 0.0
    be_wr = (100.0 * abs(loss_avg) / (win_avg + abs(loss_avg))
             if win_avg else 0.0)

    reasons = Counter(t["exit_reason"] for t in valid)
    day_wins, day_net = {}, {}
    ordered = sorted(valid, key=lambda t: t["exit_t"] if t["exit_t"] is not None else t["t"])
    cum = peak = max_dd = 0.0
    for t in ordered:
        net = t["r"] * t["risk_pct"]
        cum += net
        peak = max(peak, cum)
        max_dd = min(max_dd, cum - peak)
        if t["exit_t"] is not None:
            day = dt.datetime.fromtimestamp(
                t["exit_t"] / 1000, dt.timezone.utc).date()
            day_net[day] = day_net.get(day, 0.0) + net
            if t["result"] == "WIN":
                day_wins[day] = day_wins.get(day, 0) + 1

    out = {
        "n": len(valid),
        "unresolved": unresolved,
        "wins": len(wins),
        "win_rate_pct": round(100.0 * len(wins) / len(valid), 2),
        "avg_r": round(sum(t["r"] for t in valid) / len(valid), 4),
        "avg_win_net_pct": round(win_avg, 3),
        "avg_loss_net_pct": round(loss_avg, 3),
        "breakeven_wr_pct": round(be_wr, 1),
        "avg_net_pct_per_trade": round(sum(nets) / len(nets), 4),
        "net_pct_per_day": round(sum(nets) / days, 4),
        "total_net_pct": round(sum(nets), 2),
        "max_dd_net_pct": round(max_dd, 2),
        "net_exits_per_day": round(len(wins) / days, 3),
        "days_pct_with_net_exit": round(100.0 * len(day_wins) / days, 1),
        "timeout_pct": round(100.0 * reasons.get("TIMEOUT", 0) / len(valid), 1),
        "sl_pct": round(100.0 * reasons.get("SL", 0) / len(valid), 1),
        "exits": dict(sorted(reasons.items())),
    }
    if resamples > 0:
        out["avg_net_ci95"] = bootstrap_ci(nets, resamples, seed)
        out["avg_r_ci95"] = bootstrap_ci(
            [t["r"] for t in valid], resamples, seed + 1)
    return out


def aggregate(trade_groups, days, resamples=0):
    trades = [t for group in trade_groups for t in group]
    return summarize(trades, days, resamples=resamples, seed=7)


def fmt_summary(name, s):
    if not s or not s.get("n"):
        return f"{name:>16}  (tidak ada trade)"
    ci = s.get("avg_net_ci95")
    ci_txt = f" CI{ci}" if ci else ""
    return (f"{name:>16} {s['n']:>4} {s['win_rate_pct']:>6.2f} "
            f"{s['avg_r']:>8.4f} {s['avg_net_pct_per_trade']:>8.4f} "
            f"{s['net_pct_per_day']:>9.4f} {s['max_dd_net_pct']:>8.1f} "
            f"{s['timeout_pct']:>5.1f}{ci_txt}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", default=",".join(PAIRS))
    ap.add_argument("--data", default=str(ROOT / "data/tokocrypto_v4"))
    ap.add_argument("--config", default=str(ROOT / "data/config/scalp_config.json"))
    ap.add_argument("--modes", default=",".join(MODES))
    ap.add_argument("--holds", default=",".join(str(h) for h in HOLDS))
    ap.add_argument("--cache-dir",
                    default=os.path.join(tempfile.gettempdir(), "exit_mode_sweep_cache"))
    ap.add_argument("--rebuild", action="store_true")
    ap.add_argument("--resamples", type=int, default=1000)
    ap.add_argument("--out", default=str(ROOT / "data/hasil/exp_exit_mode_sweep.json"))
    ap.add_argument("--skip-control", action="store_true")
    args = ap.parse_args()

    os.makedirs(args.cache_dir, exist_ok=True)
    base = sr.merge_defaults(json.load(open(args.config)))
    fee = base["fee"]
    pairs = [p.strip().upper() for p in args.pairs.split(",") if p.strip()]
    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    holds = [int(h) for h in args.holds.split(",") if h.strip()]

    loaded = {}
    for pair in pairs:
        print(f"memuat {pair}...", flush=True)
        m1, cache = candidates_for(args.data, pair, base, fee,
                                   args.cache_dir, args.rebuild)
        days = (cache["to"] - cache["from"]) / 86_400_000.0
        loaded[pair] = {"m1": m1, "cache": cache, "days": days,
                        "entries": {}, "classic": {}}
        for hold in holds:
            cfg = pair_cfg(base, "classic", hold)
            entries, invalid = select_entries(m1, cache, cfg, fee)
            loaded[pair]["entries"][hold] = entries
            loaded[pair]["classic"][hold] = invalid

    result = {"meta": {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "data": str(Path(args.data).resolve()),
        "config": str(Path(args.config).resolve()),
        "pairs": pairs, "modes": modes, "holds": holds,
        "fee": fee, "control": CONTROL,
    }, "sequential": {}, "same_entry": {}}

    print("\n=== sequential (paritas CLI) ===")
    print(f"{'mode':>16} {'N':>4} {'WR%':>6} {'avgR':>8} {'net/tr':>8} "
          f"{'net/hari':>9} {'DD%':>8} {'TO%':>5}")
    for hold in holds:
        print(f"\n-- hold {hold}m --")
        for mode in modes:
            groups = []
            per_pair = {}
            for pair in pairs:
                cfg = pair_cfg(base, mode, hold)
                entries, _ = select_entries(
                    loaded[pair]["m1"], loaded[pair]["cache"], cfg, fee)
                trades = resolve_entries(
                    loaded[pair]["m1"], entries, cfg, fee)
                for t in trades:
                    t["pair"] = pair
                per_pair[pair] = summarize(trades, loaded[pair]["days"])
                groups.append(trades)
            total_days = sum(loaded[p]["days"] for p in pairs)
            agg = aggregate(groups, total_days)
            result["sequential"][f"{hold}"] = result["sequential"].get(f"{hold}", {})
            result["sequential"][f"{hold}"][mode] = {
                "pairs": per_pair, "aggregate": agg}
            print(fmt_summary(mode, agg))

    print("\n=== same_entry (isolasi exit, N identik antar-mode) ===")
    print(f"{'mode':>16} {'N':>4} {'WR%':>6} {'avgR':>8} {'net/tr':>8} "
          f"{'net/hari':>9} {'DD%':>8} {'TO%':>5}")
    for hold in holds:
        print(f"\n-- hold {hold}m --")
        for mode in modes:
            groups = []
            per_pair = {}
            for pair in pairs:
                cfg = pair_cfg(base, mode, hold)
                trades = resolve_entries(
                    loaded[pair]["m1"],
                    loaded[pair]["entries"][hold], cfg, fee)
                for t in trades:
                    t["pair"] = pair
                per_pair[pair] = summarize(
                    trades, loaded[pair]["days"],
                    resamples=args.resamples,
                    seed=1000 + hold)
                groups.append(trades)
            total_days = sum(loaded[p]["days"] for p in pairs)
            agg = aggregate(groups, total_days,
                            resamples=args.resamples)
            result["same_entry"][f"{hold}"] = result["same_entry"].get(f"{hold}", {})
            result["same_entry"][f"{hold}"][mode] = {
                "pairs": per_pair, "aggregate": agg}
            print(fmt_summary(mode, agg))

    control = result["sequential"].get("90", {}).get("classic", {}).get("aggregate")
    ok = True
    if not args.skip_control and control:
        ok = (control["n"] == CONTROL["n"]
              and abs(control["win_rate_pct"] - CONTROL["win_rate_pct"]) <= 0.02
              and abs(control["avg_r"] - CONTROL["avg_r"]) <= 0.0002)
        result["meta"]["control_ok"] = ok
        result["meta"]["control_measured"] = {
            "n": control["n"], "win_rate_pct": control["win_rate_pct"],
            "avg_r": control["avg_r"]}
        print(f"\nKONTROL classic 90m: N={control['n']} "
              f"WR={control['win_rate_pct']} avgR={control['avg_r']} "
              f"-> {'OK' if ok else 'GAGAL'}")
    elif not args.skip_control:
        print("\nKONTROL tidak dijalankan: butuh pairs ADA/DOGE/XRP, hold 90, mode classic.")
        ok = False

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w") as f:
        json.dump(result, f, indent=1)
    print(f"\nhasil JSON: {out}")

    if not ok and not args.skip_control:
        print("Kontrol gagal: hasil tidak boleh dipakai. Periksa data/config/cache.")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
