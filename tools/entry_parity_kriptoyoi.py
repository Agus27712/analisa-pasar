#!/usr/bin/env python3
"""
Eksperimen paritas entri BUY: engine app analisa-pasar (port p0_engine) vs
spek kriptoyoi lapisan frontend (static/app.js `generateScalperSignals`).

Port setia dari app.js kriptoyoi (master, diklone 2026-10-22):
  - calculateEMA        (app.js:260)
  - calculateStochRSI   (app.js:285, 14-14-3-3, seed 50)
  - calculateRSI        (app.js:364, seed simple-avg)
  - generateScalperSignals BUY/SELL/CLOSE (app.js:554-780)
  - getScalperParams    (app.js:447, 1m & 5m)

Fee/risko/target diambil dari data/config/scalp_config.json (satu-satunya
berkas config; FEE_TRUTH v1.1) lewat fungsi tools/scalp_replay.py:
risk_levels + resolve_trade dipakai identik untuk KEDUA engine, jadi
selisih hasil hanya berasal dari bar entri, bukan dari biaya/exit.

Antarmuka:
  python3 tools/entry_parity_kriptoyoi.py --pair ADAUSDT \
      --data data/tokocrypto_v4 --config data/config/scalp_config.json \
      --interval 1m --out /tmp/paritas.json
  python3 tools/entry_parity_kriptoyoi.py --selftest
"""

import argparse
import json
import os
import sys

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
if TOOLS_DIR not in sys.path:
    sys.path.insert(0, TOOLS_DIR)

import scalp_replay as sr  # noqa: E402


# ---------------------------------------------------------------------------
# Port app.js — indikator
# ---------------------------------------------------------------------------

def ema_js(candles, period):
    """Port app.js:260 — seed SMA `period`, output mulai indeks period-1."""
    n = len(candles)
    out = [None] * n
    if n < period:
        return out
    k = 2.0 / (period + 1)
    ema = sum(c["c"] for c in candles[:period]) / period
    out[period - 1] = ema
    for i in range(period, n):
        ema = (candles[i]["c"] - ema) * k + ema
        out[i] = ema
    return out


def rsi_standalone_js(candles, period=14):
    """Port app.js:364 — RSI mulai indeks `period`, value bulat 2 desimal."""
    results = {}
    if len(candles) < period + 1:
        return results
    ag, al = 0.0, 0.0
    for i in range(1, period + 1):
        d = candles[i]["c"] - candles[i - 1]["c"]
        if d >= 0:
            ag += d
        else:
            al -= d
    ag /= period
    al /= period

    def calc():
        if al == 0:
            return 100.0 if ag > 0 else 0.0 if ag == 0 else None
        return 100.0 - 100.0 / (1.0 + ag / al)

    results[candles[period]["t"]] = round(calc(), 2)
    for i in range(period + 1, len(candles)):
        d = candles[i]["c"] - candles[i - 1]["c"]
        g = d if d >= 0 else 0.0
        l = -d if d < 0 else 0.0
        ag = (ag * (period - 1) + g) / period
        al = (al * (period - 1) + l) / period
        results[candles[i]["t"]] = round(calc(), 2)
    return results


def stoch_rsi_js(candles, rsi_period=14, stoch_period=14, k_period=3, d_period=3):
    """Port app.js:285 — seed rsiValues = seedRsi di bar awal, Wilder setelahnya."""
    n = len(candles)
    if n < 5:
        return {}, {}
    gains = losses = 0.0
    warm = min(rsi_period, n - 1)
    for i in range(1, warm + 1):
        d = candles[i]["c"] - candles[i - 1]["c"]
        gains += d if d >= 0 else 0.0
        losses += -d if d < 0 else 0.0
    ag = gains / max(1, warm)
    al = losses / max(1, warm)
    if al == 0:
        rs = 100.0 if ag > 0 else 1.0
        seed = 50.0 if (al == 0 and ag == 0) else 100.0 - 100.0 / (1.0 + rs)
    else:
        seed = 100.0 - 100.0 / (1.0 + ag / al)
    rsi_vals = []
    for _ in range(min(rsi_period, n)):
        rsi_vals.append(seed)
    for i in range(rsi_period, n):
        d = candles[i]["c"] - candles[i - 1]["c"]
        g = d if d >= 0 else 0.0
        l = -d if d < 0 else 0.0
        ag = (ag * (rsi_period - 1) + g) / rsi_period
        al = (al * (rsi_period - 1) + l) / rsi_period
        rsi_vals.append(50.0 if (al == 0 and ag == 0) else
                        100.0 - 100.0 / (1.0 + ag / (al if al > 0 else 1.0e-12)))
    raw = []
    for i in range(n):
        s = rsi_vals[max(0, i - stoch_period + 1): i + 1]
        lo, hi = min(s), max(s)
        raw.append(50.0 if hi == lo else (rsi_vals[i] - lo) / (hi - lo) * 100.0)
    k_data, d_data = {}, {}
    k_vals = []
    for i in range(n):
        s = raw[max(0, i - k_period + 1): i + 1]
        kv = round(sum(s) / len(s), 2)
        k_vals.append(kv)
        s = k_vals[max(0, i - d_period + 1): i + 1]
        dv = sum(s) / len(s)
        k_data[candles[i]["t"]] = kv
        d_data[candles[i]["t"]] = dv
    return k_data, d_data


# ---------------------------------------------------------------------------
# Port app.js:447 — getScalperParams (hanya interval yang diuji)
# ---------------------------------------------------------------------------

def scalper_params(interval):
    p = {"index": interval == "1m", "cooldown": 6, "oversoldK": 30,
         "oversoldLoose": 40, "oversoldKCap": 50, "overboughtK": 70,
         "overboughtLoose": 60, "overboughtKFloor": 50,
         "closeOverboughtK": 75, "closeOversoldK": 25,
         "maxActiveCandles": 24, "closeCooldown": 5}
    if interval == "1m":
        p.update(cooldown=7, oversoldK=25, oversoldLoose=35, oversoldKCap=45,
                 overboughtK=75, overboughtLoose=65, overboughtKFloor=55,
                 closeOverboughtK=78, closeOversoldK=22, maxActiveCandles=30,
                 closeCooldown=5)
    elif interval == "5m":
        p.update(cooldown=5, oversoldK=30, oversoldLoose=40, oversoldKCap=50,
                 overboughtK=70, overboughtLoose=60, overboughtKFloor=50,
                 closeOverboughtK=75, closeOversoldK=25, maxActiveCandles=20,
                 closeCooldown=4)
    return p


# ---------------------------------------------------------------------------
# Port app.js:554-780 — generateScalperSignals (marker BUY/SELL/CLOSE)
# ---------------------------------------------------------------------------

def generate_scalper_signals(candles, interval):
    p = scalper_params(interval)
    e9, e21, e50 = (ema_js(candles, per) for per in (9, 21, 50))
    e9m = {c["t"]: v for c, v in zip(candles, e9) if v is not None}
    e21m = {c["t"]: v for c, v in zip(candles, e21) if v is not None}
    e50m = {c["t"]: v for c, v in zip(candles, e50) if v is not None}
    kmap, dmap = stoch_rsi_js(candles)
    rsi_list = rsi_standalone_js(candles, 14)

    markers = []
    buy, sell, close = [], [], []
    li, si, ci = -100, -100, -100
    prev = None
    for i in range(3, len(candles)):
        c = candles[i]
        prev = candles[i - 1]
        e9v, e21v, e50v = e9m.get(c["t"]), e21m.get(c["t"]), e50m.get(c["t"])
        pe9, pe21 = e9m.get(prev["t"]), e21m.get(prev["t"])
        k, d = kmap.get(c["t"]), dmap.get(c["t"])
        pk, pd = kmap.get(prev["t"]), dmap.get(prev["t"])
        rsi = rsi_list.get(c["t"], 50.0)
        prsi = rsi_list.get(prev["t"], 50.0)
        if e9v is None or e21v is None or pe9 is None or pe21 is None or \
           k is None or d is None or pk is None or pd is None:
            continue

        rng = c["h"] - c["l"]
        body = abs(c["c"] - c["o"])
        green = c["c"] > c["o"]
        red = c["c"] < c["o"]
        lwick = min(c["o"], c["c"]) - c["l"]
        uwick = c["h"] - max(c["o"], c["c"])

        has_active_buy = li > ci and (i - li) <= p["maxActiveCandles"]
        has_active_sell = si > ci and (i - si) <= p["maxActiveCandles"]

        gold = pe9 <= pe21 and e9v > e21v
        death = pe9 >= pe21 and e9v < e21v
        bull_stack = e9v >= e21v and (e50v is None or e21v >= e50v * 0.998)
        bear_stack = e9v <= e21v and (e50v is None or e21v <= e50v * 1.002)

        cross_up = pk <= pd and k > d
        cross_dn = pk >= pd and k < d
        oversold = pk <= p["oversoldK"] or (pk < p["oversoldLoose"] and k < p["oversoldKCap"])
        overbought = pk >= p["overboughtK"] or (pk > p["overboughtLoose"] and k > p["overboughtKFloor"])
        rsi_bounce = rsi > prsi and (rsi >= 40 or prsi <= 45)
        rsi_roll = rsi < prsi and (rsi <= 60 or prsi >= 55)

        price_above9 = prev["c"] <= pe9 and c["c"] > e9v
        price_below9 = prev["c"] >= pe9 and c["c"] < e9v
        price_above21 = prev["c"] <= pe21 and c["c"] > e21v
        price_below21 = prev["c"] >= pe21 and c["c"] < e21v

        tested_support = (c["l"] <= e21v * 1.0015 or prev["l"] <= pe21 * 1.0015) and \
            c["c"] >= e21v * 0.999
        bounce_reject = green or (rng > 0 and lwick >= rng * 0.35)
        pullback_bounce = (bull_stack or c["c"] >= (e50v or e21v)) and tested_support and \
            bounce_reject and (cross_up or (k > pk and pk <= 40)) and rsi_bounce

        tested_resist = (c["h"] >= e21v * 0.9985 or prev["h"] >= pe21 * 0.9985) and \
            c["c"] <= e21v * 1.001
        reject_bear = red or (rng > 0 and uwick >= rng * 0.35)
        pullback_reject = (bear_stack or c["c"] <= (e50v or e21v)) and tested_resist and \
            reject_bear and (cross_dn or (k < pk and pk >= 60)) and rsi_roll

        top_exhaust = (pk >= 72 or prsi >= 65) and (cross_dn or k < d) and \
            (price_below9 or (c["c"] < e9v and red and rng > 0 and body >= rng * 0.3))

        bull_candle = green and (rng == 0 or body >= rng * 0.20)
        bear_candle = red and (rng == 0 or body >= rng * 0.20)

        can_buy = (i - li) >= p["cooldown"]
        b_bounce = pullback_bounce
        b_cross = gold and c["c"] >= e9v and (cross_up or rsi >= 48)
        b_break = bull_stack and price_above9 and bull_candle and (cross_up or rsi_bounce)
        b_stoch = bull_stack and c["c"] >= e9v and cross_up and oversold and \
            bull_candle and rsi_bounce
        if can_buy and (b_bounce or b_cross or b_break or b_stoch):
            if has_active_sell:
                close.append(("CLOSE", c["t"], i))
                ci = i
            buy.append(("BUY", c["t"], i))
            li = i
            continue

        can_sell = (i - si) >= p["cooldown"]
        s_top = top_exhaust
        s_cross = death and c["c"] <= e9v and (cross_dn or rsi <= 52)
        s_reject = pullback_reject
        s_stoch = bear_stack and c["c"] <= e9v and cross_dn and overbought and \
            bear_candle and rsi_roll
        if can_sell and (s_top or s_cross or s_reject or s_stoch):
            if has_active_buy:
                close.append(("CLOSE", c["t"], i))
                ci = i
            sell.append(("SELL", c["t"], i))
            si = i
            continue

        buy_tp = (cross_dn and pk >= p["closeOverboughtK"]) or (rsi >= 75 and rsi < prsi)
        buy_trend = death or price_below21 or (c["c"] < e21v and prev["c"] < e21v)
        sell_tp = (cross_up and pk <= p["closeOversoldK"]) or (rsi <= 25 and rsi > prsi)
        sell_trend = gold or price_above21 or (c["c"] > e21v and prev["c"] > e21v)
        if has_active_buy and (buy_tp or buy_trend) and (i - ci) >= p["closeCooldown"]:
            close.append(("CLOSE", c["t"], i))
            ci = i
        elif has_active_sell and (sell_tp or sell_trend) and (i - ci) >= p["closeCooldown"]:
            close.append(("CLOSE", c["t"], i))
            ci = i
    markers = [("BUY", m[1], m[2]) for m in buy] + [("SELL", m[1], m[2]) for m in sell] \
        + list(close)
    return {"buy": buy, "sell": sell, "close": close, "all": sorted(markers,
            key=lambda m: (m[2], m[0]))}


def buy_entries_flat(signals, n_candles):
    """Entri hanya saat posisi BUY tidak aktif — sepadan dengan engine app
    yang memegang satu posisi sampai exit."""
    max_active = scalper_params("1m")["maxActiveCandles"]
    out, active_buy_start = [], None
    for kind, t, i in signals["all"]:
        if kind == "BUY":
            hold_ok = active_buy_start is None or (i - active_buy_start) > max_active
            if hold_ok:
                out.append((t, i))
                active_buy_start = i
        elif kind == "CLOSE":
            active_buy_start = None
    return out


# ---------------------------------------------------------------------------
# Pembanding ekonomi: geometri engine app pada bar entri kriptoyoi
# ---------------------------------------------------------------------------

def geometry_at(i, m1, m15, m5, wlen, cfg, fee):
    """Konstruksi SL/TP entry persis rule engine app (risk_levels) di bar ini,
    setup generik TREND_PULLBACK (marker kriptoyoi tidak punya pola engine)."""
    price = m1[i]["c"]
    step15 = 15 * 60_000
    step5 = 5 * 60_000
    hi15 = (i * 60_000) // step15
    while hi15 < len(m15) and m15[hi15]["t"] + step15 <= m1[i]["t"]:
        hi15 += 1
    hi5 = (i * 60_000) // step5
    while hi5 < len(m5) and m5[hi5]["t"] + step5 <= m1[i]["t"]:
        hi5 += 1
    m15w = m15[max(0, hi15 - 60):hi15]
    m5w = m5[max(0, hi5 - 60):hi5]
    m1w = m1[max(0, i - wlen + 1):i + 1]
    if len(m1w) < 20 or len(m15w) < 20 or len(m5w) < 20:
        return None
    st = sr.analyze_structure(m15w[-40:])
    if st is None:
        return None
    lows = []
    for j in range(2, len(m15w) - 2):
        c = m15w[j]
        if c["l"] <= m15w[j - 1]["l"] and c["l"] <= m15w[j - 2]["l"] and \
           c["l"] <= m15w[j + 1]["l"] and c["l"] <= m15w[j + 2]["l"]:
            lows.append(c["l"])
    st["last_swing_low"] = lows[-1] if lows else None
    rvol = sr.relative_volume(m1w, 20)
    resistance = st["resistance"] or price * 1.05
    above_res = price >= resistance
    rg = sr.detect_regime(m1w, price, st["bias"], rvol, above_res, cfg)
    rl = sr.risk_levels(price, "TREND_PULLBACK", st, rg, fee, cfg)
    if rl is None or rl["entry"] <= 0:
        return None
    return {"rl": rl, "atr_pct": rg["atr_pct"], "m15_ready": len(m15w) >= 40}


# ---------------------------------------------------------------------------
# Statistik
# ---------------------------------------------------------------------------

def summarize(trades):
    win = sum(1 for t in trades if t["result"] == "WIN")
    loss = sum(1 for t in trades if t["result"] == "LOSS")
    res = [t["r"] for t in trades if t["result"] in ("WIN", "LOSS")]
    exits = {}
    for t in trades:
        exits[t["exit_reason"]] = exits.get(t["exit_reason"], 0) + 1
    return {"n": len(trades), "wins": win, "losses": loss,
            "win_rate_pct": round(100.0 * win / (win + loss), 2) if (win + loss) else 0.0,
            "avg_r": round(sum(res) / len(res), 4) if res else 0.0,
            "exits": exits}


# ---------------------------------------------------------------------------
# Selftest — kontrol positif & negatif pola BUY
# ---------------------------------------------------------------------------

def selftest():
    # Negatif: turun terus → EMA9 tak pernah cross atas EMA21 → nol BUY.
    down = [{"t": i * 60_000, "o": 100.0 - 0.5 * i, "h": 100.0 - 0.5 * i + 0.2,
             "l": 100.0 - 0.5 * i - 0.2, "c": 100.0 - 0.5 * i, "v": 1000.0}
            for i in range(80)]
    assert not generate_scalper_signals(down, "1m")["buy"], "kontrol negatif gagal"

    # Positif: dorong EMA golden cross dekat akhir seri + close ≥ EMA9 + RSI naik.
    up = []
    base = 100.0
    for i in range(80):
        c = base - 0.6 * i
        up.append({"t": i * 60_000, "o": c, "h": c + 0.01, "l": c - 0.01,
                   "c": c, "v": 1000.0})
    for j in range(20):
        c = base - 0.6 * 79 + 1.2 * (j + 1)
        up.append({"t": (80 + j) * 60_000, "o": c - 1.0, "h": c + 0.02,
                   "l": c - 0.02, "c": c, "v": 2000.0})
    buys = generate_scalper_signals(up, "1m")["buy"]
    assert buys, "kontrol positif gagal: golden cross tidak memicu BUY"
    print(f"selftest OK (kontrol negatif: 0 BUY, kontrol positif: {len(buys)} BUY)")


# ---------------------------------------------------------------------------
# Utama
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="Paritas entri BUY app vs kriptoyoi frontend")
    ap.add_argument("--pair", default="ADAUSDT")
    ap.add_argument("--data", default="data/tokocrypto_v4")
    ap.add_argument("--config", default="data/config/scalp_config.json")
    ap.add_argument("--interval", default="1m", choices=["1m", "5m"])
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    if args.selftest:
        selftest()
        return

    cfg = json.load(open(args.config))
    fee = cfg["fee"] if "fee" in cfg else sr.resolve_fee(cfg, args.pair)
    fee = sr.merge_defaults(cfg).get("fee", None) or fee
    fee = json.loads(json.dumps(fee))
    # HTF di-sintesis dari M1 (htf_source=csv tidak dipakai di config aktif) —
    # sama seperti CLI scalp_replay.
    m1 = sr.load_csv(os.path.join(args.data, f"{args.pair}_1m.csv"))
    m15 = sr.aggregate(m1, 15)
    h1 = sr.aggregate(m1, 60)
    rp = cfg.get("replay", sr.DEFAULTS["replay"])
    lookahead = int(rp.get("lookahead_bars", 120))
    wlen = int(rp.get("m1_window", 250))

    # --- Engine app (port p0) — identik dengan CLI scalp_replay ---
    a = sr.run_replay(m1, m15, h1, cfg, fee, True, lookahead,
                      btc1m=sr.load_csv(os.path.join(args.data, "BTCUSDT_1m.csv")))
    a_summary = sr.summarize(a)
    a_trades = [t for t in a["trades"] if t["result"] in ("WIN", "LOSS")]
    a_ts = {t["t"] for t in a_trades}

    # --- Spek kriptoyoi frontend ---
    if args.interval == "1m":
        candles = m1
    else:
        candles = sr.aggregate(m1, 5)
    sig = generate_scalper_signals(candles, args.interval)
    if args.interval == "1m":
        entries = buy_entries_flat(sig, len(candles))
        b_ts = {t for t, _ in entries}
    else:
        entries = sig["buy"]
        b_ts = {t for _, t, _ in entries}

    # --- Ekonomi bar entri kriptoyoi (fee truth + geometri app) ---
    m5 = sr.aggregate(m1, 5)
    b_trades = []
    for t, i in entries:
        if i + 1 >= len(m1):
            continue
        geo = geometry_at(i, m1, m15, m5, wlen, cfg, fee)
        if geo is None:
            continue
        rl = geo["rl"]
        resolved = sr.resolve_trade(m1, i, rl["entry"], rl["sl"], rl["tp1"],
                                    rl["tp2"], fee, cfg, len(m1) - i,
                                    atr_pct=geo["atr_pct"])
        if resolved["result"] not in ("WIN", "LOSS"):
            continue
        b_trades.append({"t": t, "result": resolved["result"], "r": resolved["r"],
                         "exit_reason": resolved["exit_reason"],
                         "entry": rl["entry"], "sl_floor_active": rl["sl_floor_active"]})

    inter = a_ts & b_ts
    union = a_ts | b_ts
    a_by_ts = {t["t"]: t for t in a_trades}
    b_by_ts = {t["t"]: t for t in b_trades}
    app_at_inter = [a_by_ts[t] for t in sorted(inter) if t in a_by_ts]
    kripto_at_inter = [b_by_ts[t] for t in sorted(inter) if t in b_by_ts]
    res = {
        "pair": args.pair, "interval_kriptoyoi": args.interval,
        "bars_m1": len(m1),
        "range": {"from": m1[0]["t"], "to": m1[-1]["t"]},
        "fee": {"buy_taker_pct": fee["buy_taker_pct"],
                "sell_taker_pct": fee["sell_taker_pct"],
                "slippage_pct": cfg.get("slippage_pct")},
        "friction_additive_pct": (fee["buy_taker_pct"] + fee["sell_taker_pct"]
                                  + 2.0 * cfg.get("slippage_pct", 0.08)),
        "app_engine": {"summary": a_summary, "entry_ts_resolved": len(a_ts)},
        "kriptoyoi_markers": {
            "buy_all": len(sig["buy"]), "sell": len(sig["sell"]),
            "close": len(sig["close"]),
            "buy_entry_flat": len(entries) if args.interval == "1m" else None,
            "flat_entries_resolved": len(b_trades),
        },
        "parity": {
            "intersection": len(inter),
            "jaccard_pct": round(100.0 * len(inter) / len(union), 3) if union else 0.0,
            "coverage_app_pct": round(100.0 * len(inter) / max(1, len(a_ts)), 3),
            "coverage_kriptoyoi_pct": round(100.0 * len(inter) / max(1, len(b_ts)), 3),
            "app_quality_at_intersection": summarize(app_at_inter),
            "kriptoyoi_quality_at_intersection": summarize(kripto_at_inter),
        },
        "kriptoyoi_economics_fee_truth": summarize(b_trades),
    }
    print(json.dumps(res, indent=1))
    if args.out:
        with open(args.out, "w") as f:
            json.dump(res, f, indent=1)


if __name__ == "__main__":
    main()
