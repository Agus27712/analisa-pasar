#!/usr/bin/env python3
"""Grid exit-only untuk engine scalping: evaluasi bar sekali, lalu banyak varian exit.

Kenapa ada alat ini: `tools/scalp_replay.py` mengevaluasi ulang seluruh gate per bar
(±4,5 menit per pair). Untuk riset exit, keputusan gate tidak bergantung pada config exit,
jadi hasil evaluasi bar di-cache sekali; setiap varian exit lalu hanya berjalan di atas
cache itu. Kontrolnya: varian `classic` wajib menghasilkan angka identik dengan CLI replay.

Contoh:
  python3 tools/exit_grid.py --pair ADAUSDT --data data/tokocrypto_v4 --grid net_tp_stop
  python3 tools/exit_grid.py --pair ADAUSDT --data data/tokocrypto_v4 --grid net_tp
  python3 tools/exit_grid.py --pair ADAUSDT --data data/tokocrypto_v4 --bounds

Cache disimpan di --cache-dir (bawaan /tmp/exit_grid_cache) dan dipakai ulang, jadi
menjalankan grid kedua untuk pair yang sama tidak perlu evaluasi 4,5 menit lagi.
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

HOLDS = (90, 240, 420, 1440)
TP_NET = (0.4, 0.5, 0.75, 1.0, 1.5)
STOP_GROSS = (1.0, 1.5, 2.0, 2.5)


def load_pair(data_dir, pair):
    m1 = sr.load_csv(os.path.join(data_dir, f"{pair}_1m.csv"))
    btc_path = os.path.join(data_dir, "BTCUSDT_1m.csv")
    btc = sr.load_csv(btc_path) if os.path.exists(btc_path) else []
    return m1, btc


def collect_ready(m1, cfg, fee, btc, cache_path):
    """Satu lintasan gate P0; simpan sinyal siap + geometri risiko per indeks bar."""
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
    ready = {}
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
        btc_key = (p5b, pb1)
        if btc5 and btc_key != last_btc_key:
            btc_res = sr.evaluate_btc_veto(
                btc5[max(0, p5b - 120):p5b],
                btc[max(0, pb1 - 60):pb1] if btc else [], cfg)
            last_btc_key = btc_key
        chg24 = 0.0
        if i >= 1440 and m1[i - 1440]["c"] > 0:
            chg24 = (c["c"] / m1[i - 1440]["c"] - 1.0) * 100.0
        r = sr.evaluate_bar(c["c"], m1w, m15w, h1w, cfg, fee, True,
                            change24h=chg24, btc_veto=btc_res["veto"],
                            btc_reason=btc_res["reason"], m5w=m5w)
        if r is not None and r["ready"]:
            ready[str(i)] = {
                "t": t, "setup": r["setup"], "score": r["score"],
                "entry": r["entry"], "sl": r["sl"],
                "tp1": r["tp1"], "tp2": r["tp2"],
                "gross_risk_pct": r["gross_risk_pct"],
                "friction_pct": r["friction_pct"],
                "sl_floor_active": r["sl_floor_active"],
                "atr_pct": r.get("atr_pct", 0.0),
            }
        i += 1
    out = {"ready": ready, "bars": n, "from": m1[0]["t"], "to": m1[-1]["t"]}
    with open(cache_path, "w") as f:
        json.dump(out, f)
    return out


def run_variant(m1, cache, cfg, fee):
    lookahead = int(cfg["replay"]["lookahead_bars"])
    ready = cache["ready"]
    trades = []
    reasons = {}
    i = 0
    n = len(m1)
    while i < n:
        r = ready.get(str(i))
        if r is None:
            i += 1
            continue
        res = sr.resolve_trade(m1, i, r["entry"], r["sl"], r["tp1"], r["tp2"],
                               fee, cfg, lookahead, atr_pct=r["atr_pct"])
        reason = res.get("exit_reason", "")
        reasons[reason] = reasons.get(reason, 0) + 1
        trades.append({
            "t": r["t"], "result": res["result"], "r": res["r"],
            "exit_reason": reason, "risk_pct": r["gross_risk_pct"],
            "exit_t": (m1[res["exit_idx"]]["t"]
                       if res.get("exit_idx") is not None else None),
        })
        if res["result"] == "UNRESOLVED" or res.get("exit_idx") is None:
            i += lookahead
            continue
        i = res["exit_idx"] + 1
    return trades, reasons


def summarize(trades, days):
    tr = [t for t in trades if t["result"] in ("WIN", "LOSS")]
    if not tr:
        return None
    wins = [t for t in tr if t["result"] == "WIN"]
    win_net = [t["r"] * t["risk_pct"] for t in wins]
    loss_net = [t["r"] * t["risk_pct"] for t in tr if t["result"] == "LOSS"]
    day_wins = {}
    day_net = {}
    for t in tr:
        if t.get("exit_t") is None:
            continue
        day = dt.datetime.fromtimestamp(t["exit_t"] / 1000, dt.timezone.utc).date()
        day_net[day] = day_net.get(day, 0.0) + t["r"] * t["risk_pct"]
        if t["result"] == "WIN":
            day_wins[day] = day_wins.get(day, 0) + 1
    win_avg = sum(win_net) / len(win_net) if win_net else 0.0
    loss_avg = sum(loss_net) / len(loss_net) if loss_net else 0.0
    be_wr = 100.0 * abs(loss_avg) / (win_avg + abs(loss_avg)) if win_avg else 0.0
    return {
        "n": len(tr), "wins": len(wins),
        "win_rate_pct": round(100 * len(wins) / len(tr), 2),
        "avg_r": round(sum(t["r"] for t in tr) / len(tr), 4),
        "avg_win_net_pct": round(win_avg, 3),
        "avg_loss_net_pct": round(loss_avg, 3),
        "breakeven_wr_pct": round(be_wr, 1),
        "net_exits_per_day": round(len(wins) / days, 3),
        "days_pct_with_net_exit": round(100 * len(day_wins) / days, 1),
        "avg_net_pct_per_trade": round(
            sum(t["r"] * t["risk_pct"] for t in tr) / len(tr), 4),
        "avg_net_pct_per_day": round(
            sum(t["r"] * t["risk_pct"] for t in tr) / days, 4),
        "total_net_pct": round(sum(t["r"] * t["risk_pct"] for t in tr), 2),
        "timeout_pct": round(
            100 * sum(1 for t in tr if t["exit_reason"] == "TIMEOUT") / len(tr), 1),
    }


def build_variants(cfg, grid, holds=HOLDS, tps=TP_NET):
    variants = [("classic 90m (kontrol)", json.loads(json.dumps(cfg)))]
    if grid == "net_tp":
        combos = [(h, None, tp) for h in holds for tp in tps]
    else:
        combos = [(h, s, tp) for h in (240, 420, 1440)
                  for s in STOP_GROSS for tp in tps]
    for hold, stop, tp in combos:
        c = json.loads(json.dumps(cfg))
        c["exit"]["max_hold_bars_m1"] = hold
        c["replay"]["lookahead_bars"] = max(hold, 120) + 60
        c["exit"]["net_tp_pct"] = tp
        if stop is None:
            c["exit"]["mode"] = "net_tp"
            name = f"net_tp {tp}% / {hold}m"
        else:
            c["exit"]["mode"] = "net_tp_stop"
            c["exit"]["stop_gross_pct"] = stop
            name = f"tp{tp}/sl{stop}/{hold}m"
        variants.append((name, c))
    return variants


def print_bounds(m1, cache, cfg, fee, holds=(240, 420, 1440, 2880), tps=TP_NET):
    """Batas atas WR: sinyal yang HIGH-nya pernah mencapai TP net, tanpa stop sama sekali."""
    ready = cache["ready"]
    idx = sorted(int(k) for k in ready)
    print(f"\nbatas atas WR (exit sempurna di high, tanpa stop) — {len(idx)} sinyal")
    for hold in holds:
        parts = []
        for tp in tps:
            hit = 0
            for i in idx:
                need = sr._price_for_net(ready[str(i)]["entry"], tp, fee,
                                         cfg["slippage_pct"])
                hi = max((b["h"] for b in m1[i + 1:i + 1 + hold]), default=0.0)
                if hi >= need:
                    hit += 1
            parts.append(f"tp{tp}%={100 * hit / len(idx):.1f}%")
        print(f"  {hold:4}m  " + "  ".join(parts))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pair", required=True)
    ap.add_argument("--data", default=str(ROOT / "data/tokocrypto_v4"))
    ap.add_argument("--config", default=str(ROOT / "data/config/scalp_config.json"))
    ap.add_argument("--grid", choices=("net_tp", "net_tp_stop"), default="net_tp_stop")
    ap.add_argument("--cache-dir", default=os.path.join(tempfile.gettempdir(), "exit_grid_cache"))
    ap.add_argument("--holds", default=None,
                    help="daftar hold dipisah koma, mis. 1440,2880")
    ap.add_argument("--tps", default=None,
                    help="daftar TP net dipisah koma, mis. 1.0,1.5,2.0")
    ap.add_argument("--bounds", action="store_true")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    os.makedirs(args.cache_dir, exist_ok=True)
    cfg = sr.merge_defaults(json.load(open(args.config)))
    fee = cfg["fee"]
    m1, btc = load_pair(args.data, args.pair)
    cache = collect_ready(m1, cfg, fee, btc,
                          os.path.join(args.cache_dir, f"cache_{args.pair}.json"))
    days = (cache["to"] - cache["from"]) / 86_400_000.0
    print(f"pair={args.pair} bars={cache['bars']} hari={days:.2f} "
          f"ready={len(cache['ready'])}")

    holds = ([int(x) for x in args.holds.split(",")] if args.holds else HOLDS)
    tps = ([float(x) for x in args.tps.split(",")] if args.tps else TP_NET)
    if args.bounds:
        print_bounds(m1, cache, cfg, fee,
                     tuple(h for h in holds if h >= 240), tps)
        return

    print(f"{'varian':24} {'N':>4} {'WR%':>6} {'BE%':>6} {'avgR':>8} {'win%':>6} "
          f"{'loss%':>7} {'exit/hari':>9} {'hari%':>6} {'net%/hari':>9} {'TO%':>5}")
    rows = []
    for name, c in build_variants(cfg, args.grid, holds, tps):
        trades, reasons = run_variant(m1, cache, c, fee)
        s = summarize(trades, days)
        if not s:
            continue
        print(f"{name:24} {s['n']:>4} {s['win_rate_pct']:>6} "
              f"{s['breakeven_wr_pct']:>6} {s['avg_r']:>8} "
              f"{s['avg_win_net_pct']:>6} {s['avg_loss_net_pct']:>7} "
              f"{s['net_exits_per_day']:>9} {s['days_pct_with_net_exit']:>6} "
              f"{s['avg_net_pct_per_day']:>9} {s['timeout_pct']:>5}")
        rows.append({"variant": name, **s, "exits": reasons})
    out = args.out or os.path.join(args.cache_dir, f"grid_{args.pair}.json")
    with open(out, "w") as f:
        json.dump(rows, f, indent=1)
    print(f"hasil JSON: {out}")


if __name__ == "__main__":
    main()
