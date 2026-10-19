#!/usr/bin/env python3
"""
Replay offline engine scalping KriptoYoi di atas CSV candle real bursa.

Port faithful (setia) dari pipeline produksi:
  ScalpingMtfEvaluator.evaluate -> ScalpSetupDetector / SignalScoringEngine /
  ScalpingRiskEngine / MarketRegimeEngine / MarketStructureAnalyzer /
  MtfConfluenceMatrix / IndicatorMath / FeeCalculator.

Konsisten dengan FEE_TRUTH.md v1.1:

  Aturan R (FEE_TRUTH §0):
    - 1R = jarak SL gross dari entry, sebelum fee/slippage.
    - Loss saat SL kena ≈ (SL_gross + F) / SL_gross R, dengan F = total friksi.
    - Target_gross = k × (SL_gross + F) + F, dengan k = target_net_rr.
    - Untuk k=2: Target_gross = 2 × SL_gross + 3F.

  Fee dan slippage WAJIB dipisah di laporan (FEE_TRUTH §0, §3, §8).
  Buy/sell asimetris: PPh hanya di sisi jual untuk IDR.

  Default engine (FEE_TRUTH §8):
    - min_friction_multiple = 6.0  -> SL gross ≥ 6 × F
    - target_net_rr         = 2.0
    - slippage base case    = 0.08% per sisi
    - slippage stress case  = 0.20% dan 0.50% per sisi (wajib diuji)
    - order_type            = taker

  Referensi fee Tokocrypto (per sisi, akun reguler):
    - USDT Maker/Taker all-in: 0.4044%  (0.8088% round-trip)
    - IDR Beli  Maker: 0.1222%  Taker: 0.2222%
    - IDR Jual  Maker: 0.3322%  Taker: 0.4322%
  Referensi fee Indodax (IDR):
    - Beli Maker: 0.11%  Taker: 0.21%
    - Jual Maker: 0.32%  Taker: 0.42%

Quirk yang ikut di-port agar hasil sebanding aplikasi (lihat SCALP_REPLAY_NOTES):
  - bias bullish MtfConfluenceMatrix.evaluateLeg (ranging+flat -> BULLISH)
  - momentumBullish auto-lolos bila <10 candle
  - choppiness mengabaikan candle pertama window
  - hadBreakContext: klausa ketiga mati (operator precedence)

Yang TIDAK ada di CSV (orderbook depth):
  - Step2 (isOrderBookValid) default GAGAL seperti aplikasi tanpa depth.
  - Flag --assume-orderbook-ok mengaktifkan cabang diagnosticIgnore (Step2 lolos,
    tapi skor order-flow tetap 0 seperti aplikasi).

Contoh:
  python tools/fetch_tokocrypto_klines.py --pairs BTCUSDT --pages 10
  python tools/scalp_replay.py --pair BTCUSDT --data data/tokocrypto --assume-orderbook-ok --out hasil_btc.json
  # Stress test slippage (FEE_TRUTH §3):
  python tools/scalp_replay.py --pair BTCUSDT --slippage-pct 0.20 --out hasil_btc_slip20.json
  python tools/scalp_replay.py --pair BTCUSDT --slippage-pct 0.50 --out hasil_btc_slip50.json

Output: JSON saja (stdout + file bila --out). Kirim JSON ke pengembang untuk analisa tuning.
Hanya library standar Python 3, tanpa pip.
"""
import argparse
import csv
import json
import math
import os
import sys

# --------------------------------------------------------------------------
# Konstanta default (lihat FEE_TRUTH.md §7–§8)
# --------------------------------------------------------------------------

DEFAULTS = {
    # Friksi & R (FEE_TRUTH §5, §8)
    "min_friction_multiple": 6.0,   # SL gross ≥ 6 × F; plateau 5–10×
    "target_net_rr": 2.0,
    "slippage_pct": 0.08,           # base case per sisi
    "order_type": "taker",          # taker|maker
    "min_risk_pct": 0.5,            # legacy floor; lantai friksi biasanya menang
    "max_tp2_r": 20.0,
    "min_net_rr": 1.15,            # sama dengan ScalpingConfig.MIN_NET_RR (jangan beda diam-diam)

    # Detektor setup (threshold engine; tetap seperti pipeline)
    "chop_hard_ranging": 65.0,
    "rvol_setup_min": 1.2,
    "rvol_retest_min": 1.1,
    "rvol_breakout_min": 1.3,
    "buy_pressure_retest": 1.0,
    "buy_pressure_breakout": 1.15,
    "retest_above_factor": 0.998,
    "retest_max_dist_pct": 1.5,
    "retest_min_structure_strength": 55,
    "breakout_above_factor": 1.002,
    "pullback_support_max_dist_pct": 1.5,
    "min_score_breakout_retest": 70,
    "min_score_long": 65,
    "min_score_strong": 82,

    # Replay
    "replay": {
        "m15_min_bars": 20,
        "h1_min_bars": 20,
        "m1_window": 250,
        "lookahead_bars": 120,
        "htf_source": "synthesize",
    },
}

REQUIRED_FEE_KEYS = ("buy_taker_pct", "sell_taker_pct",
                     "buy_maker_pct", "sell_maker_pct")

# Config kanonik: mirror offline dari ScalpingConfig/ScalpingRuntimeConfig + FEE_TRUTH.md.
# Hanya boleh ada SATU berkas config; Fassungs yang kedua = dua definisi angka yang sama.
DEFAULT_CONFIG_PATH = "data/config/scalp_config.json"


def merge_defaults(cfg):
    """Isi key yang hilang dengan DEFAULTS; sub-dict 'replay' di-merge."""
    out = dict(cfg)
    for k, v in DEFAULTS.items():
        if k == "replay":
            out["replay"] = {**v, **cfg.get("replay", {})}
        elif k not in out:
            out[k] = v
    return out


def validate_fee(fee):
    missing = [k for k in REQUIRED_FEE_KEYS if k not in fee]
    if missing:
        raise ValueError(
            f"fee config kurang key: {missing}. "
            f"Wajib pisah buy/sell, maker/taker (FEE_TRUTH §0, §8)."
        )


def order_fees(fee, order_type):
    """(buy_fee_pct, sell_fee_pct) sesuai order_type."""
    if order_type == "maker":
        return fee["buy_maker_pct"], fee["sell_maker_pct"]
    return fee["buy_taker_pct"], fee["sell_taker_pct"]


# --------------------------------------------------------------------------
# Util indikator (mirror IndicatorMath.kt)
# --------------------------------------------------------------------------

def ema_last(values, period):
    """EMA rekursif dari nilai pertama (== emaFallback aplikasi; beda tipis vs TA4J di series panjang)."""
    if period <= 0 or not values:
        return 0.0
    k = 2.0 / (period + 1.0)
    e = values[0]
    for v in values[1:]:
        e = (v - e) * k + e
    return e


def ema_series(values, period):
    if period <= 0 or not values:
        return []
    k = 2.0 / (period + 1.0)
    out = [values[0]]
    e = values[0]
    for v in values[1:]:
        e = (v - e) * k + e
        out.append(e)
    return out


def rsi_wilder(closes, period):
    """Wilder RSI (== calculateRsiFallback; TA4J RSIIndicator setara MMA)."""
    n = len(closes)
    if period <= 0 or n <= period:
        return 50.0
    gain = loss = 0.0
    for i in range(1, period + 1):
        ch = closes[i] - closes[i - 1]
        if ch >= 0:
            gain += ch
        else:
            loss -= ch
    ag = gain / period
    al = loss / period
    for i in range(period + 1, n):
        ch = closes[i] - closes[i - 1]
        ag = (ag * (period - 1) + (ch if ch > 0 else 0.0)) / period
        al = (al * (period - 1) + (-ch if ch < 0 else 0.0)) / period
    if al == 0.0:
        return 50.0 if ag == 0.0 else 100.0
    return 100.0 - (100.0 / (1.0 + ag / al))


def true_range(h, l, pc):
    return max(h - l, abs(h - pc), abs(l - pc))


def atr_wilder(candles, period):
    """candles: list dict(o,h,l,c,v). == calculateAtrFallback."""
    n = len(candles)
    if period <= 0 or n <= 1:
        return 0.0
    if n <= period:
        s = 0.0
        for i in range(1, n):
            s += true_range(candles[i]["h"], candles[i]["l"], candles[i - 1]["c"])
        return s / (n - 1)
    s = 0.0
    for i in range(1, period + 1):
        s += true_range(candles[i]["h"], candles[i]["l"], candles[i - 1]["c"])
    a = s / period
    for i in range(period + 1, n):
        a = (a * (period - 1) + true_range(candles[i]["h"], candles[i]["l"], candles[i - 1]["c"])) / period
    return a


def macd_last(closes, fast=12, slow=26, sig=9):
    """(macd_line_last, signal_last). Aproksimasi TA4J (seed EMA dari nilai pertama)."""
    if len(closes) < slow + sig:
        return 0.0, 0.0
    ef = ema_series(closes, fast)
    es = ema_series(closes, slow)
    line = [a - b for a, b in zip(ef, es)]
    se = ema_series(line, sig)
    return line[-1], se[-1]


def rolling_vwap(candles, period):
    if period <= 0 or not candles:
        return 0.0
    w = candles[max(0, len(candles) - period):]
    pv = sv = 0.0
    for c in w:
        t = (c["h"] + c["l"] + c["c"]) / 3.0
        pv += t * c["v"]
        sv += c["v"]
    return pv / sv if sv > 0 else candles[-1]["c"]


def relative_volume(candles, period=20):
    if period <= 0 or len(candles) < 2:
        return 1.0
    cur = max(0.0, candles[-1]["v"])
    hist = candles[:-1]
    if not hist:
        return 1.0
    w = hist[max(0, len(hist) - period):]
    avg = sum(max(0.0, c["v"]) for c in w) / len(w)
    if avg <= 0:
        return 1.0
    return cur / avg


def choppiness(candles, period=14):
    """Port exact termasuk quirk: candle pertama window tidak masuk highest/lowest."""
    if period < 2 or len(candles) < period + 1:
        return 50.0
    sl = candles[-(period + 1):]
    s = 0.0
    hi = float("-inf")
    lo = float("inf")
    for i in range(1, len(sl)):
        c = sl[i]
        pc = sl[i - 1]["c"]
        s += true_range(c["h"], c["l"], pc)
        if c["h"] > hi:
            hi = c["h"]
        if c["l"] < lo:
            lo = c["l"]
    rng = hi - lo
    if rng <= 0 or s <= 0:
        return 50.0
    return max(0.0, min(100.0, 100.0 * math.log10(s / rng) / math.log10(float(period))))


def adx_fallback(candles, period=14):
    """== calculateAdxFallback (aproksimasi ADX TA4J)."""
    n = len(candles)
    if n < period + 2:
        return 0.0
    pdm = [0.0] * n
    mdm = [0.0] * n
    tr = [0.0] * n
    for i in range(1, n):
        up = candles[i]["h"] - candles[i - 1]["h"]
        dn = candles[i - 1]["l"] - candles[i]["l"]
        pdm[i] = up if (up > dn and up > 0) else 0.0
        mdm[i] = dn if (dn > up and dn > 0) else 0.0
        tr[i] = true_range(candles[i]["h"], candles[i]["l"], candles[i - 1]["c"])
    if n <= period:
        return 0.0
    st = sum(tr[1:period + 1])
    sp = sum(pdm[1:period + 1])
    sm = sum(mdm[1:period + 1])
    dxs = []
    for i in range(period + 1, n):
        st = st - st / period + tr[i]
        sp = sp - sp / period + pdm[i]
        sm = sm - sm / period + mdm[i]
        pdi = 100.0 * sp / st if st > 0 else 0.0
        mdi = 100.0 * sm / st if st > 0 else 0.0
        ds = pdi + mdi
        dxs.append(100.0 * abs(pdi - mdi) / ds if ds > 0 else 0.0)
    if not dxs:
        return 0.0
    return max(0.0, min(100.0, sum(dxs[-min(period, len(dxs)):]) / min(period, len(dxs))))


# --------------------------------------------------------------------------
# Struktur pasar (mirror MarketStructureAnalyzer.kt)
# --------------------------------------------------------------------------

def analyze_structure(candles):
    """Return dict snapshot struktur (setara toStructureSnapshot). None bila data <12."""
    if len(candles) < 12:
        return None
    recent = candles[-60:]
    n = len(recent)
    swing_h, swing_l = [], []
    for i in range(2, n - 2):
        c = recent[i]
        if c["h"] >= recent[i-1]["h"] and c["h"] >= recent[i-2]["h"] and \
           c["h"] >= recent[i+1]["h"] and c["h"] >= recent[i+2]["h"]:
            swing_h.append(c["h"])
        if c["l"] <= recent[i-1]["l"] and c["l"] <= recent[i-2]["l"] and \
           c["l"] <= recent[i+1]["l"] and c["l"] <= recent[i+2]["l"]:
            swing_l.append(c["l"])
    last = recent[-1]["c"]
    highs = swing_h[-2:]
    lows = swing_l[-2:]
    hhh_l = len(highs) >= 2 and len(lows) >= 2 and highs[1] > highs[0] and lows[1] > lows[0]
    lhl_l = len(highs) >= 2 and len(lows) >= 2 and highs[1] < highs[0] and lows[1] < lows[0]

    below = [x for x in swing_l if x <= last]
    support = max(below) if below else (min(swing_l) if swing_l else min(c["l"] for c in recent[:-1]))
    above = [x for x in swing_h if x >= last]
    resistance = min(above) if above else (max(swing_h) if swing_h else max(c["h"] for c in recent[:-1]))

    closes_all = [c["c"] for c in candles]
    e13 = ema_last(closes_all, min(13, len(closes_all)))
    e21 = ema_last(closes_all, min(21, len(closes_all)))
    ema_bounce = last >= e13 * 0.995 and e13 >= e21 * 0.998

    win = recent[-4:]
    sweep = any(w["l"] < support * 0.998 and w["c"] >= support for w in win)

    prev_break = any(c["h"] > resistance for c in recent[:-3])
    retest_now = abs(last - resistance) / resistance <= 0.015 and last >= resistance * 0.993
    retest_valid = bool(prev_break and retest_now and ema_bounce)

    lsh = swing_h[-1] if swing_h else None
    lsl = swing_l[-1] if swing_l else None
    bull_bos = lsh is not None and any(w["c"] > lsh for w in win)
    bear_bos = lsl is not None and any(w["c"] < lsl for w in win)
    bull_choch = bool(lhl_l and bull_bos)
    bear_choch = bool(hhh_l and bear_bos)
    choch = bull_choch or bear_choch

    sup_t = sum(1 for c in recent if abs(c["l"] - support) / support <= 0.008)
    res_t = sum(1 for c in recent if abs(c["h"] - resistance) / resistance <= 0.008)

    strength = 35
    if hhh_l or lhl_l:
        strength += 18
    if len(swing_h) >= 3 and len(swing_l) >= 3:
        strength += 10
    if sup_t >= 2 or res_t >= 2:
        strength += 8
    if ema_bounce:
        strength += 8
    if bull_bos or bear_bos:
        strength += 10
    if choch:
        strength += 12
    if sweep:
        strength += 6
    if retest_valid:
        strength += 10
    strength = max(0, min(100, strength))

    if hhh_l or (bull_choch):
        bias = "BULLISH"
    elif lhl_l or (bear_choch):
        bias = "BEARISH"
    else:
        bias = "NEUTRAL"
    if choch:
        pattern = "CHOCH"
    elif bull_bos or bear_bos:
        pattern = "BOS"
    elif hhh_l:
        pattern = "HH_HL"
    elif lhl_l:
        pattern = "LH_LL"
    else:
        pattern = "RANGING"
    return {
        "bias": bias, "pattern": pattern,
        "bos": bool(bull_bos or bear_bos), "choch": bool(choch),
        "strength": strength,
        "support": support, "resistance": resistance,
        "sweep": bool(sweep), "retest_valid": bool(retest_valid),
        "hhhl": bool(hhh_l), "lhll": bool(lhl_l),
    }


# --------------------------------------------------------------------------
# Regime (mirror MarketRegimeEngine.kt)
# --------------------------------------------------------------------------

def detect_regime(candles, price, bias, rvol, res_broken, cfg):
    if len(candles) < 20:
        return {"regime": "RANGING", "adx": 0.0, "chop": 50.0, "atr_pct": 0.0, "ema": "mixed"}
    atr = atr_wilder(candles, 14)
    atr_pct = (atr / price * 100.0) if price > 0 else 0.0
    adx = adx_fallback(candles, 14)
    chop = choppiness(candles, 14)
    closes = [c["c"] for c in candles]
    e9 = ema_last(closes, min(9, len(closes)))
    e21 = ema_last(closes, min(21, len(closes)))
    e50 = ema_last(closes, min(50, len(closes)))
    if e9 > e21 and e21 > e50:
        ema = "bullish"
    elif e9 < e21 and e21 < e50:
        ema = "bearish"
    else:
        ema = "mixed"
    if atr_pct >= 3.5:
        reg = "HIGH_VOLATILITY"
    elif 0.0 < atr_pct < 0.8 and adx < 18.0:
        reg = "LOW_VOLATILITY"
    elif res_broken and rvol >= 1.5:
        reg = "BREAKOUT"
    elif chop >= 61.8 and adx < 20.0:
        reg = "RANGING"
    elif adx >= 25.0:
        if (bias == "BULLISH" and ema == "bullish") or (ema == "bullish" and bias != "BEARISH"):
            reg = "TRENDING_UP"
        elif (bias == "BEARISH" and ema == "bearish") or (ema == "bearish" and bias != "BULLISH"):
            reg = "TRENDING_DOWN"
        else:
            reg = "RANGING"
    elif adx >= 20.0 and chop < 50.0:
        if ema == "bullish" or bias == "BULLISH":
            reg = "TRENDING_UP"
        elif ema == "bearish" or bias == "BEARISH":
            reg = "TRENDING_DOWN"
        else:
            reg = "RANGING"
    else:
        reg = "RANGING"
    return {"regime": reg, "adx": adx, "chop": chop, "atr_pct": atr_pct, "ema": ema}


# --------------------------------------------------------------------------
# Setup (mirror ScalpSetupDetector.kt)
# --------------------------------------------------------------------------

def has_rejection(candles, bullish=True):
    if not candles:
        return False
    c = candles[-1]
    rng = max(c["h"] - c["l"], 1e-12)
    body = abs(c["c"] - c["o"])
    if bullish:
        return (min(c["o"], c["c"]) - c["l"]) >= rng * 0.4 and c["c"] >= c["l"] + rng * 0.5 and body <= rng * 0.5
    return (c["h"] - max(c["o"], c["c"])) >= rng * 0.4 and c["c"] <= c["h"] - rng * 0.5 and body <= rng * 0.5


def momentum_bullish(candles, price):
    if len(candles) < 10:
        return price > 0
    closes = [c["c"] for c in candles]
    e9 = ema_last(closes, min(9, len(closes)))
    e21 = ema_last(closes, min(21, len(closes)))
    r = rsi_wilder(closes, min(14, len(closes) - 1))
    return e9 >= e21 * 0.998 and price >= e9 * 0.995 and 40.0 <= r <= 78.0


def near_ema_pullback(candles, price):
    if len(candles) < 15:
        return False
    closes = [c["c"] for c in candles]
    e9 = ema_last(closes, 9)
    e21 = ema_last(closes, 21)
    n9 = abs(price - e9) / e9 * 100.0 <= 0.8
    n21 = abs(price - e21) / e21 * 100.0 <= 1.0
    return (n9 or n21) and price >= e21 * 0.992


def detect_setup(price, st, rg, rvol, bp, m1, cfg):
    if price <= 0:
        return "NONE"
    ranging_hard = rg["regime"] == "RANGING" and rg["chop"] >= cfg["chop_hard_ranging"]
    # 1. sweep
    if st["sweep"] or st["choch"]:
        rej = has_rejection(m1, True)
        vol_ok = rvol >= cfg["rvol_setup_min"] or not m1
        struct_rev = st["choch"] or st["bos"] or st["sweep"]
        if struct_rev and (not m1 or rej or st["choch"]) and (vol_ok or st["choch"]):
            lb = True if (st["choch"] and st["bias"] == "BULLISH") else \
                 False if (st["choch"] and st["bias"] == "BEARISH") else \
                 True if (st["sweep"] and st["bias"] != "BEARISH") else True
            if lb:
                return "LIQUIDITY_SWEEP"
    if not ranging_hard:
        # 2. retest
        if st["bias"] != "BEARISH":
            rvol_ok = rvol >= cfg["rvol_retest_min"]
            depth_hint = abs(bp - 1.0) > 0.02
            flow_ok = (not depth_hint) or bp >= cfg["buy_pressure_retest"]
            if st["retest_valid"]:
                if rvol_ok and flow_ok and not (0 < st["strength"] < cfg["retest_min_structure_strength"]):
                    return "BREAKOUT_RETEST"
            else:
                res = st["resistance"]
                if res and res > 0 and price > 0:
                    dist = abs(price - res) / res * 100.0
                    above = price >= res * cfg["retest_above_factor"]
                    near = dist <= cfg["retest_max_dist_pct"]
                    had_break = st["bos"] or st["pattern"] == "BOS" or (st["pattern"] == "HH_HL" and st["bos"])
                    str_ok = st["strength"] >= cfg["retest_min_structure_strength"]
                    mom_ok = momentum_bullish(m1, price)
                    if above and near and had_break and rvol_ok and str_ok and (mom_ok or flow_ok):
                        return "BREAKOUT_RETEST"
        # 3. breakout
        res = st["resistance"]
        broken = (res is not None and res > 0 and price >= res * cfg["breakout_above_factor"]) or \
                 (st["bos"] and st["bias"] == "BULLISH") or (rg["regime"] == "BREAKOUT")
        if broken:
            rvol_ok = rvol >= cfg["rvol_breakout_min"]
            flow_ok = bp >= cfg["buy_pressure_breakout"]
            mom_ok = momentum_bullish(m1, price)
            conf = sum([rvol_ok, flow_ok, mom_ok])
            if conf >= 2 or (rg["regime"] == "BREAKOUT" and conf >= 1):
                return "BREAKOUT"
        # 4. pullback
        trending = rg["regime"] == "TRENDING_UP" or \
                   (rg["regime"] != "TRENDING_DOWN" and st["bias"] == "BULLISH" and rg["ema"] == "bullish")
        if (trending or st["pattern"] == "HH_HL") and st["bias"] != "BEARISH":
            sup = st["support"]
            near_sup = sup is not None and sup > 0 and \
                abs(price - sup) / sup * 100.0 <= cfg["pullback_support_max_dist_pct"] and price >= sup * 0.995
            near_ema = near_ema_pullback(m1, price)
            if near_sup or near_ema:
                rec = momentum_bullish(m1, price) or bp >= 1.1 or rvol >= 1.15
                if rec or len(m1) < 15:
                    return "TREND_PULLBACK"
    return "NONE"


# --------------------------------------------------------------------------
# MTF leg (mirror MtfConfluenceMatrix.evaluateLeg, termasuk quirk bias)
# --------------------------------------------------------------------------

def mtf_leg(candles):
    if len(candles) < 12:
        return "UNKNOWN"
    st = analyze_structure(candles)
    if st is None:
        return "UNKNOWN"
    closes = [c["c"] for c in candles]
    e9 = ema_last(closes, min(9, len(closes)))
    e21 = ema_last(closes, min(21, len(closes)))
    ema_bull = e9 > e21 * 0.998
    ema_bear = e9 < e21 * 1.002
    if st["hhhl"] or (ema_bull and not st["lhll"]):
        return "BULLISH"
    if st["lhll"] or (ema_bear and not st["hhhl"]):
        return "BEARISH"
    return "NEUTRAL"


# --------------------------------------------------------------------------
# Skor (mirror SignalScoringEngine.kt)
# --------------------------------------------------------------------------

SETUP_PA = {"BREAKOUT_RETEST": 14, "LIQUIDITY_SWEEP": 13, "BREAKOUT": 12, "TREND_PULLBACK": 11, "NONE": 0}


def score_all(price, st, rg, setup, rvol, bp, has_ob, mtf_aligned, mtf_total, m1):
    if price <= 0:
        return {"total": 0, "category": "NO_TRADE", "parts": {}}
    parts = {}
    # structure
    pts = max(0, min(100, st["strength"])) * 15 / 100.0
    pts += {"BULLISH": 5.0, "NEUTRAL": 2.0, "BEARISH": 0.0}[st["bias"]]
    if st["choch"] and st["bias"] == "BULLISH":
        pts += 3.0
    elif st["bos"] and st["bias"] != "BEARISH":
        pts += 3.0
    if st["sweep"]:
        pts += 2.0
    if st["bias"] == "BEARISH" and not st["choch"]:
        pts *= 0.3
    parts["structure"] = int(round(max(0, min(25, pts))))
    # mtf
    if mtf_total <= 0:
        parts["mtf"] = 0
    else:
        parts["mtf"] = int(round(max(0, min(15, min(mtf_aligned, mtf_total) * 15.0 / mtf_total))))
    # price action
    pa = SETUP_PA[setup]
    if len(m1) >= 6:
        last = m1[-1]
        rng = max(last["h"] - last["l"], 1e-12)
        bonus = 0
        if last["c"] > last["o"]:
            bonus += 2
        if (last["c"] - last["l"]) / rng >= 0.6:
            bonus += 2
        rl = min(c["l"] for c in m1[-3:])
        pl = min(c["l"] for c in m1[-8:-3]) if len(m1) >= 8 else rl
        if rl >= pl:
            bonus += 2
        pa += bonus
    parts["price_action"] = max(0, min(20, pa))
    # volume
    if math.isnan(rvol) or rvol < 0.8:
        parts["volume"] = 0
    elif rvol < 1.0:
        parts["volume"] = 3
    elif rvol < 1.2:
        parts["volume"] = 6
    elif rvol < 1.5:
        parts["volume"] = 9
    elif rvol < 2.0:
        parts["volume"] = 12
    else:
        parts["volume"] = 15
    # momentum
    mom = 0
    if len(m1) >= 15:
        closes = [c["c"] for c in m1]
        e9 = ema_last(closes, min(9, len(closes)))
        e21 = ema_last(closes, min(21, len(closes)))
        if e9 > e21:
            mom += 4
        r = rsi_wilder(closes, 14)
        if 50.0 <= r <= 68.0:
            mom += 4
        elif (42.0 <= r <= 50.0) or (68.0 <= r <= 75.0):
            mom += 2
        if len(closes) >= 35:
            ml, sl = macd_last(closes)
            if ml > sl:
                mom += 2
    parts["momentum"] = max(0, min(10, mom))
    # order flow (tanpa depth di CSV: has_ob=False -> 0, persis aplikasi;
    # bila has_ob True, imbalan imbalance 0.0 -> 1 poin seperti aplikasi)
    if not has_ob:
        parts["order_flow"] = 0
    else:
        imb = 0.0
        bpp = 0 if math.isnan(bp) else (6 if bp >= 1.5 else 5 if bp >= 1.2 else 3 if bp >= 1.05 else 1 if bp >= 0.95 else 0)
        ipp = 0 if math.isnan(imb) else (4 if imb >= 0.3 else 2 if imb >= 0.1 else 1 if imb >= -0.1 else 0)
        parts["order_flow"] = max(0, min(10, bpp + ipp))
    # volatility
    ap = rg["atr_pct"]
    if ap <= 0:
        v = 2
    elif 0.3 <= ap <= 1.5:
        v = 5
    elif (0.15 <= ap <= 0.3) or (1.5 <= ap <= 2.5):
        v = 3
    else:
        v = 1
    if rg["regime"] == "HIGH_VOLATILITY":
        v = min(v, 2)
    if rg["chop"] >= 65.0:
        v -= 2
    parts["volatility"] = max(0, min(5, v))
    total = sum(parts.values())
    cat = "VERY_STRONG" if total >= 90 else "STRONG" if total >= 75 else \
          "WATCH" if total >= 60 else "WEAK" if total >= 40 else "NO_TRADE"
    return {"total": total, "category": cat, "parts": parts}


# --------------------------------------------------------------------------
# Risk + fee (mirror ScalpingRiskEngine.kt + FeeCalculator.kt, FEE_TRUTH v1.1)
# --------------------------------------------------------------------------

def roundtrip_fee(entry, sl, tp, fee, slip, maker=False):
    """
    Net RR dan komponen biaya.
    Fee dan slippage dipisah (FEE_TRUTH §0, §8).
    """
    if entry <= 0 or sl <= 0 or tp <= 0:
        return {"net_rr": 0.0, "net_reward_pct": 0.0, "net_risk_pct": 0.0,
                "fee_cost_pct": 0.0, "slippage_cost_pct": 0.0, "total_cost_pct": 0.0}
    bf = fee["buy_maker_pct"] if maker else fee["buy_taker_pct"]
    sf = fee["sell_maker_pct"] if maker else fee["sell_taker_pct"]
    bcf = 1.0 + (bf + slip) / 100.0
    snf = max(0.0, 1.0 - (sf + slip) / 100.0)
    rew = (tp / entry * snf / bcf - 1.0) * 100.0
    risk = (1.0 - sl / entry * snf / bcf) * 100.0
    rr = max(0.0, rew / risk) if risk > 0 else 0.0
    fee_cost = bf + sf
    slip_cost = 2.0 * slip
    return {"net_rr": rr, "net_reward_pct": rew, "net_risk_pct": risk,
            "fee_cost_pct": fee_cost,
            "slippage_cost_pct": slip_cost,
            "total_cost_pct": fee_cost + slip_cost}


def risk_levels(price, setup, st, rg, fee, cfg):
    """
    SL/TP dengan LANTAI FRIKSI (FEE_TRUTH §5):
        SL_gross ≥ min_friction_multiple × (buy_fee + sell_fee + 2 × slippage)
    Target gross (FEE_TRUTH §0):
        Target_gross = k × (SL_gross + F) + F,  k = target_net_rr
    """
    if price <= 0 or setup == "NONE":
        return None

    order_type = cfg.get("order_type", DEFAULTS["order_type"])
    maker = order_type == "maker"
    bf, sf = order_fees(fee, order_type)
    slip = cfg.get("slippage_pct", DEFAULTS["slippage_pct"])

    friction_pct = (bf + sf) + 2.0 * slip
    min_friction_multiple = cfg.get("min_friction_multiple", DEFAULTS["min_friction_multiple"])
    friction_floor_pct = min_friction_multiple * friction_pct

    atr_pct = rg["atr_pct"] if rg["atr_pct"] > 0 else 0.5
    atr = price * atr_pct / 100.0
    low_k, high_k = (0.1, 0.2) if setup == "BREAKOUT" else (0.3, 0.1)
    zl = price - low_k * atr
    zh = price + high_k * atr
    entry = zh

    struct = st["last_swing_low"] if setup == "LIQUIDITY_SWEEP" else st["support"]
    if setup == "LIQUIDITY_SWEEP" and struct is None:
        struct = st["support"]
    if setup != "LIQUIDITY_SWEEP" and struct is None:
        struct = st["last_swing_low"]
    ssl = (struct - 0.2 * atr) if (struct and struct > 0 and struct < zl) else None

    min_risk_pct = cfg.get("min_risk_pct", DEFAULTS["min_risk_pct"])
    atr_floor_price = 0.8 * atr
    min_risk_pct_price = min_risk_pct / 100.0 * entry
    friction_floor_price = friction_floor_pct / 100.0 * entry
    min_risk = max(atr_floor_price, min_risk_pct_price, friction_floor_price)
    max_risk = max(2.0 * atr, min_risk)

    sl = ssl if ssl is not None else entry - 1.0 * atr
    raw = entry - sl
    # True bila tanpa lantai friksi, SL awal (struktur) tidak akan menutup biaya.
    sl_floor_active = raw < friction_floor_price

    if raw > max_risk:
        sl = entry - max_risk
    elif raw < min_risk:
        sl = entry - min_risk

    risk = entry - sl
    risk_pct_tp = risk / entry * 100.0

    target_net_rr = cfg.get("target_net_rr", DEFAULTS["target_net_rr"])
    # FEE_TRUTH §0: Target_gross = k × (SL_gross + F) + F
    req = target_net_rr * (risk_pct_tp + friction_pct) + friction_pct
    tp2_pct = max(req, 1.5 * risk_pct_tp)
    tp1 = entry * (1.0 + tp2_pct * 0.55 / 100.0)

    res = st["resistance"]
    too_close = False
    if res is not None and res > entry and res * 0.9995 < tp1:
        tp1 = res * 0.9995
        if (tp1 - entry) < 1.0 * risk:
            too_close = True

    tp2 = max(entry * (1.0 + tp2_pct / 100.0), tp1 + 0.5 * risk)
    tp2r = (tp2 - entry) / risk if risk > 0 else 0.0

    f = roundtrip_fee(entry, sl, tp2, fee, slip, maker=maker)
    max_tp2_r = cfg.get("max_tp2_r", DEFAULTS["max_tp2_r"])
    min_net_rr = cfg.get("min_net_rr", DEFAULTS["min_net_rr"])

    ok = sl > 0 and sl < zl and tp1 > entry and not too_close
    valid = bool(ok and tp2r <= max_tp2_r and f["net_rr"] >= min_net_rr)

    return {
        "entry": entry, "sl": sl, "tp1": tp1, "tp2": tp2,
        "net_rr": f["net_rr"],
        "net_risk_pct": f["net_risk_pct"],
        "net_reward_pct": f["net_reward_pct"],
        "fee_cost_pct": f["fee_cost_pct"],
        "slippage_cost_pct": f["slippage_cost_pct"],
        "total_cost_pct": f["total_cost_pct"],
        "friction_pct": friction_pct,
        "friction_floor_pct": friction_floor_pct,
        "sl_floor_active": bool(sl_floor_active),
        "order_type": order_type,
        "valid": valid,
    }


# --------------------------------------------------------------------------
# Evaluasi per bar (mirror ScalpingMtfEvaluator.evaluate gate)
# --------------------------------------------------------------------------

def min_score_for_setup(setup, cfg):
    return cfg["min_score_breakout_retest"] if setup == "BREAKOUT_RETEST" else cfg["min_score_long"]


def evaluate_bar(price, m1w, m15w, h1w, cfg, fee, assume_ob):
    if price <= 0 or len(h1w) < 20 or len(m15w) < 20 or len(m1w) < 20:
        return None
    last = m1w[-1]
    avg_vol = sum(c["v"] for c in m1w[-20:]) / 20.0
    forming = last["v"]
    crange = last["h"] - last["l"]
    cbody = abs(last["c"] - last["o"])
    cwick = crange - cbody
    top_third = (last["h"] - last["c"]) <= crange / 3.0 if crange > 0 else False
    vsa = (avg_vol > 0) and (forming > avg_vol * 1.2) and top_third
    atr = atr_wilder(m1w, 14)
    vol_pct = atr / price * 100.0
    extreme = vol_pct >= 4.0
    momentum_candle = cbody > cwick and last["c"] > last["o"]
    noise = extreme and not momentum_candle
    vw = rolling_vwap(m1w[-min(60, len(m1w)):], min(60, len(m1w)))
    closes = [c["c"] for c in m1w]
    rsi = rsi_wilder(closes, min(14, len(closes) - 1))
    overbought = rsi >= 85.0
    e13 = ema_last(closes, min(13, len(closes)))
    e21 = ema_last(closes, min(21, len(closes)))
    ema_aligned = e13 >= e21 * 0.998
    ema_bounce = e13 > 0 and (e13 * 0.995 <= price <= e13 * 1.015)
    rvol = relative_volume(m1w, 20)

    m15_ready = len(m15w) >= 40
    st = analyze_structure(m15w[-40:]) if m15_ready else None
    if st is None:
        st = {"bias": "NEUTRAL", "pattern": "RANGING", "bos": False, "choch": False,
              "strength": 0, "support": None, "resistance": None, "sweep": False,
              "retest_valid": False, "hhhl": False, "lhll": False, "last_swing_low": None}
    else:
        # lastSwingLow dibutuhkan risk sweep; hitung dari window m15
        win = m15w[-40:]
        lows = []
        for i in range(2, len(win) - 2):
            c = win[i]
            if c["l"] <= win[i-1]["l"] and c["l"] <= win[i-2]["l"] and \
               c["l"] <= win[i+1]["l"] and c["l"] <= win[i+2]["l"]:
                lows.append(c["l"])
        st["last_swing_low"] = lows[-1] if lows else None

    resistance = st["resistance"] if st["resistance"] else price * 1.05
    above_res = price >= resistance
    with_vol = price >= resistance * 0.995 and vsa
    room_clear = price < resistance * 0.995
    room = room_clear or above_res or with_vol

    rg = detect_regime(m1w, price, st["bias"], rvol, m15_ready and above_res, cfg)

    bp = 1.0  # tanpa depth
    ob_valid = True if assume_ob else False
    setup = detect_setup(price, st, rg, rvol, bp, m1w, cfg)

    legs = [mtf_leg(h1w), mtf_leg(m15w), mtf_leg(m1w)]
    known = [l for l in legs if l != "UNKNOWN"]
    bull = sum(1 for l in known if l == "BULLISH")
    sc = score_all(price, st, rg, setup, rvol, bp, False, bull, max(1, len(known)), m1w)

    rl = risk_levels(price, setup if setup != "NONE" else "NONE",
                     st, rg, fee, cfg)
    risk_valid = setup != "NONE" and rl is not None and rl["valid"]
    net_rr = rl["net_rr"] if rl else 0.0

    knife = st["bias"] == "BEARISH" and len(m1w) >= 5 and \
        m1w[-1]["c"] < m1w[-5]["c"] * 0.992
    dangerous = noise or overbought or knife

    s1 = (not dangerous) and room
    s2 = s1 and ob_valid
    vwap_ok = price > vw or vsa or \
        (38.0 <= rsi <= 68.0 and last["c"] > last["o"] and last["c"] >= vw * 0.9985) or ema_bounce
    s3 = s2 and setup != "NONE" and vwap_ok
    min_sc = min_score_for_setup(setup, cfg)
    s4 = s3 and risk_valid and sc["total"] >= min_sc

    ready = s4
    strong = ready and sc["total"] >= cfg["min_score_strong"]
    early = (not ready) and (not dangerous) and s2
    stage = "STRONG_ENTRY" if strong else "ENTRY" if ready else \
            "EARLY_ENTRY" if early else "HOLD" if dangerous else "WATCH"
    if dangerous:
        conf = 0
    elif ready:
        conf = sc["total"]
    else:
        cap = 59 if s3 else 49 if s2 else 39 if s1 else 29
        conf = max(10, min(sc["total"], cap))

    gross_risk_pct = 0.0
    if rl and rl["entry"] > 0:
        gross_risk_pct = (rl["entry"] - rl["sl"]) / rl["entry"] * 100.0

    return {
        "ready": ready, "strong": strong, "stage": stage, "setup": setup,
        "score": sc["total"], "category": sc["category"], "parts": sc["parts"],
        "confidence": conf, "net_rr": net_rr,
        "steps": [s1, s2, s3, s4],
        "regime": rg["regime"], "rvol": rvol, "rsi": rsi,
        "entry": rl["entry"] if rl else 0.0,
        "sl": rl["sl"] if rl else 0.0,
        "tp1": rl["tp1"] if rl else 0.0,
        "tp2": rl["tp2"] if rl else 0.0,
        "net_risk_pct": rl["net_risk_pct"] if rl else 0.0,
        "gross_risk_pct": gross_risk_pct,
        "fee_cost_pct": rl["fee_cost_pct"] if rl else 0.0,
        "slippage_cost_pct": rl["slippage_cost_pct"] if rl else 0.0,
        "friction_pct": rl["friction_pct"] if rl else 0.0,
        "sl_floor_active": rl["sl_floor_active"] if rl else False,
        "order_type": rl["order_type"] if rl else cfg.get("order_type", DEFAULTS["order_type"]),
    }


# --------------------------------------------------------------------------
# CSV + agregasi HTF kausal
# --------------------------------------------------------------------------

def load_csv(path):
    out = []
    with open(path, newline="") as f:
        for row in csv.reader(f):
            if len(row) < 6:
                continue
            try:
                out.append({"t": int(float(row[0])), "o": float(row[1]), "h": float(row[2]),
                            "l": float(row[3]), "c": float(row[4]), "v": float(row[5])})
            except ValueError:
                continue
    out.sort(key=lambda c: c["t"])
    return out


def aggregate(candles, minutes):
    """Agregasi kausal ke grid UTC (catatan: grid bursa D1/H4 bisa beda; M15/H1 UTC cukup)."""
    step = minutes * 60_000
    bars = []
    cur = None
    for c in candles:
        w = (c["t"] // step) * step
        if cur is None or cur["t"] != w:
            if cur is not None:
                bars.append(cur)
            cur = {"t": w, "o": c["o"], "h": c["h"], "l": c["l"], "c": c["c"], "v": c["v"]}
        else:
            cur["h"] = max(cur["h"], c["h"])
            cur["l"] = min(cur["l"], c["l"])
            cur["c"] = c["c"]
            cur["v"] += c["v"]
    if cur is not None:
        bars.append(cur)
    return bars


# --------------------------------------------------------------------------
# Replay
# --------------------------------------------------------------------------

def run_replay(m1, m15, h1, cfg, fee, assume_ob, lookahead):
    rp = cfg.get("replay", DEFAULTS["replay"])
    m15_min = int(rp.get("m15_min_bars", 20))
    h1_min = int(rp.get("h1_min_bars", 20))
    evals = 0
    bott = {"s1": 0, "s2": 0, "s3": 0, "s4": 0}
    setup_hits = {}
    trades = []
    equity = []
    cum = 0.0
    peak = 0.0
    max_dd = 0.0
    i = 0
    n = len(m1)
    wlen = int(rp.get("m1_window", 250))
    # Pointer HTF: m15/h1 terurut waktu; hanya candle CLOSED (tutup <= t bar M1 berjalan).
    p15 = p60 = 0
    while i < n:
        c = m1[i]
        t = c["t"]
        while p15 < len(m15) and m15[p15]["t"] + 15 * 60_000 <= t:
            p15 += 1
        while p60 < len(h1) and h1[p60]["t"] + 60 * 60_000 <= t:
            p60 += 1
        m15w = m15[max(0, p15 - 60):p15]
        h1w = h1[max(0, p60 - 60):p60]
        m1w = m1[max(0, i - wlen + 1):i + 1]
        if len(m1w) < 20 or len(m15w) < m15_min or len(h1w) < h1_min:
            i += 1
            continue
        r = evaluate_bar(c["c"], m1w, m15w, h1w, cfg, fee, assume_ob)
        if r is None:
            i += 1
            continue
        evals += 1
        s1, s2, s3, s4 = r["steps"]
        if not s1:
            bott["s1"] += 1
        elif not s2:
            bott["s2"] += 1
        elif not s3:
            bott["s3"] += 1
        elif not s4:
            bott["s4"] += 1
        if not r["ready"]:
            i += 1
            continue
        setup_hits[r["setup"]] = setup_hits.get(r["setup"], 0) + 1
        # resolusi forward non-overlapping
        entry, sl, tp2 = r["entry"], r["sl"], r["tp2"]
        exit_r = None
        exit_idx = None
        ambiguous = False
        for j in range(i + 1, min(n, i + 1 + lookahead)):
            b = m1[j]
            hit_sl = b["l"] <= sl
            hit_tp = b["h"] >= tp2
            if hit_sl and hit_tp:
                ambiguous = True
                exit_r = -1.0  # konservatif: SL dulu (konvensi BacktestEngine)
                exit_idx = j
                break
            if hit_sl:
                exit_r = -1.0
                exit_idx = j
                break
            if hit_tp:
                exit_r = r["net_rr"]
                exit_idx = j
                break
        if exit_r is None:
            trades.append({"setup": r["setup"], "score": r["score"], "result": "UNRESOLVED",
                           "r": 0.0, "t": t, "ambiguous": False,
                           "entry": entry, "sl": sl, "tp2": tp2,
                           "gross_risk_pct": r["gross_risk_pct"],
                           "friction_pct": r["friction_pct"],
                           "sl_floor_active": r["sl_floor_active"],
                           "order_type": r["order_type"]})
            i += lookahead
            continue
        cum += exit_r
        peak = max(peak, cum)
        max_dd = max(max_dd, peak - cum)
        equity.append(round(cum, 4))
        trades.append({"setup": r["setup"], "score": r["score"],
                       "result": "WIN" if exit_r > 0 else "LOSS", "r": round(exit_r, 4),
                       "t": t, "ambiguous": ambiguous,
                       "entry": entry, "sl": sl, "tp2": tp2,
                       "gross_risk_pct": r["gross_risk_pct"],
                       "friction_pct": r["friction_pct"],
                       "sl_floor_active": r["sl_floor_active"],
                       "order_type": r["order_type"],
                       "exit_bar": m1[exit_idx]["t"]})
        i = exit_idx + 1
    return {"evals": evals, "bottleneck": bott, "setup_hits": setup_hits,
            "trades": trades, "equity_r": equity, "max_dd_r": round(max_dd, 4)}


def summarize(rep):
    tr = [t for t in rep["trades"] if t["result"] in ("WIN", "LOSS")]
    wins = [t for t in tr if t["result"] == "WIN"]
    gw = sum(t["r"] for t in wins)
    gl = -sum(t["r"] for t in tr if t["result"] == "LOSS")
    by_setup = {}
    for t in tr:
        d = by_setup.setdefault(t["setup"], {"trades": 0, "wins": 0, "gross_r": 0.0})
        d["trades"] += 1
        d["gross_r"] += t["r"]
        if t["result"] == "WIN":
            d["wins"] += 1
    for s, d in by_setup.items():
        d["win_rate"] = round(100.0 * d["wins"] / d["trades"], 2) if d["trades"] else 0.0
        d["avg_r"] = round(d["gross_r"] / d["trades"], 4) if d["trades"] else 0.0
        d["gross_r"] = round(d["gross_r"], 4)
    n = len(tr)

    floor_active = sum(1 for t in tr if t.get("sl_floor_active"))
    avg_gross = round(sum(t.get("gross_risk_pct", 0) for t in tr) / n, 4) if n else 0.0
    avg_friction = round(sum(t.get("friction_pct", 0) for t in tr) / n, 4) if n else 0.0
    order_types = sorted({t.get("order_type", "") for t in tr if t.get("order_type")})

    return {
        "evaluations": rep["evals"],
        "ready_signals": len(tr) + sum(1 for t in rep["trades"] if t["result"] == "UNRESOLVED"),
        "trades_resolved": n,
        "unresolved": sum(1 for t in rep["trades"] if t["result"] == "UNRESOLVED"),
        "wins": len(wins),
        "losses": n - len(wins),
        "win_rate_pct": round(100.0 * len(wins) / n, 2) if n else 0.0,
        "avg_r": round(sum(t["r"] for t in tr) / n, 4) if n else 0.0,
        "expectancy_r": round(sum(t["r"] for t in tr) / n, 4) if n else 0.0,
        "profit_factor": round(gw / gl, 4) if gl > 0 else (float("inf") if gw > 0 else 0.0),
        "max_drawdown_r": rep["max_dd_r"],
        "sl_floor_active_trades": floor_active,
        "sl_floor_active_pct": round(100.0 * floor_active / n, 2) if n else 0.0,
        "avg_gross_risk_pct": avg_gross,
        "avg_friction_pct": avg_friction,
        "order_types_seen": order_types,
        "bottleneck": rep["bottleneck"],
        "setup_hits": rep["setup_hits"],
        "by_setup": by_setup,
    }


def main():
    ap = argparse.ArgumentParser(description="Replay offline engine scalping (output JSON saja).")
    ap.add_argument("--pair", required=True, help="cth BTCUSDT")
    ap.add_argument("--data", default="data/tokocrypto", help="direktori CSV")
    ap.add_argument("--config", default=DEFAULT_CONFIG_PATH,
                    help=f"berkas konfigurasi replay (bawaan: {DEFAULT_CONFIG_PATH}; "
                         "mirror offline dari ScalpingConfig/FEE_TRUTH.md)")
    ap.add_argument("--assume-orderbook-ok", action="store_true",
                    help="Aktifkan cabang diagnosticIgnore (Step2 lolos tanpa depth)")
    ap.add_argument("--out", default="", help="tulis JSON ke file (selain stdout)")
    ap.add_argument("--max-bars", type=int, default=0, help="batasi jumlah bar M1 (0 = semua)")
    ap.add_argument("--slippage-pct", type=float, default=None,
                    help="Override slippage per sisi (mis. 0.08 base, 0.20/0.50 stress)")
    ap.add_argument("--order-type", choices=["taker", "maker"], default=None,
                    help="Override order type (taker|maker)")
    args = ap.parse_args()

    config_path = args.config
    if not os.path.exists(config_path):
        # Jalur lama ("tools/scalp_config.json") pernah jadi bawaan tapi berkasnya tidak pernah
        # ada; config kanonik hidup di data/config/. Jangan diam-diam memakai konstanta DEFAULTS.
        legacy = os.path.join("tools", "scalp_config.json")
        hint = legacy if os.path.exists(legacy) else DEFAULT_CONFIG_PATH
        print(json.dumps({
            "error": f"config tidak ditemukan: {config_path}",
            "cari_di": [config_path, legacy, DEFAULT_CONFIG_PATH],
            "saran": f"jalankan ulang dengan --config {hint}",
        }))
        return 1

    with open(config_path) as f:
        cfg = merge_defaults(json.load(f))
    if args.slippage_pct is not None:
        cfg["slippage_pct"] = args.slippage_pct
    if args.order_type is not None:
        cfg["order_type"] = args.order_type

    fee = cfg.get("fee", {})
    try:
        validate_fee(fee)
    except ValueError as e:
        print(json.dumps({"error": str(e)}))
        return 1

    rp = cfg["replay"]
    lookahead = int(rp.get("lookahead_bars", 120))

    m1_path = os.path.join(args.data, f"{args.pair}_1m.csv")
    if not os.path.exists(m1_path):
        print(json.dumps({"error": f"CSV tidak ditemukan: {m1_path}"}))
        return 1
    m1 = load_csv(m1_path)
    if args.max_bars > 0:
        m1 = m1[-args.max_bars:]

    htf = rp.get("htf_source", "synthesize")
    m15_path = os.path.join(args.data, f"{args.pair}_15m.csv")
    h1_path = os.path.join(args.data, f"{args.pair}_1h.csv")
    if htf == "csv" and os.path.exists(m15_path) and os.path.exists(h1_path):
        m15 = load_csv(m15_path)
        h1 = load_csv(h1_path)
        htf_used = "csv"
    else:
        m15 = aggregate(m1, 15)
        h1 = aggregate(m1, 60)
        htf_used = "synthesize"

    rep = run_replay(m1, m15, h1, cfg, fee, args.assume_orderbook_ok, lookahead)

    # Fee per sisi efektif (untuk audit FEE_TRUTH §8)
    bf_eff, sf_eff = order_fees(fee, cfg["order_type"])
    friction_pct_eff = (bf_eff + sf_eff) + 2.0 * cfg["slippage_pct"]
    friction_floor_pct_eff = cfg["min_friction_multiple"] * friction_pct_eff

    out = {
        "tool": "scalp_replay",
        "pair": args.pair,
        "assume_orderbook_ok": args.assume_orderbook_ok,
        "htf_source": htf_used,
        "bars_m1": len(m1),
        "bars_m15": len(m15),
        "bars_h1": len(h1),
        "range": {"from": m1[0]["t"], "to": m1[-1]["t"]} if m1 else {},
        "fee": fee,
        # Parameter efektif (audit FEE_TRUTH §5, §8)
        "effective_order_type": cfg["order_type"],
        "effective_slippage_pct": cfg["slippage_pct"],
        "min_friction_multiple": cfg["min_friction_multiple"],
        "target_net_rr": cfg["target_net_rr"],
        "friction_pct_effective": round(friction_pct_eff, 4),
        "friction_floor_pct_effective": round(friction_floor_pct_eff, 4),
        "fee_cost_pct_effective": round(bf_eff + sf_eff, 4),
        "slippage_cost_pct_effective": round(2.0 * cfg["slippage_pct"], 4),
        "thresholds": {k: v for k, v in cfg.items() if k not in ("fee", "replay")},
        "summary": summarize(rep),
        "trades": rep["trades"],
    }
    text = json.dumps(out)
    if args.out:
        with open(args.out, "w") as f:
            f.write(text)
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())


SCALP_REPLAY_NOTES = """
Deviasi sadar vs aplikasi (didokumentasikan agar hasil bisa dibandingkan):
1. EMA/MACD/ADX memakai rumus fallback deterministik (seed dari nilai pertama /
   Wilder manual). Aplikasi memakai TA4J bila tersedia — beda tipis di series panjang.
2. Grid agregasi HTF memakai UTC. Bila sesi bursa tidak selaras UTC (mis. D1 Indodax
   00:00 WIB), H4/D1 bisa bergeser; untuk M15/H1 dampak minimal.
3. Forward-resolution memakai TP2/SL penuh satu posisi (tanpa split TP1 55%).
   Same-bar SL+TP -> SL dulu (konvensi BacktestEngine.kt).
4. Quirk aplikasi yang DIPORT SETIA: bias bullish evaluateLeg, momentumBullish <10,
   choppiness abaikan candle pertama, klausa mati hadBreakContext, avgVol nol
   (VSA dimatikan bila avg<=0 agar tidak Infinity seperti aplikasi).
5. ConfluenceEvaluator tidak di-port (sidecar display-only, tidak meng-gate BUY).

Konsistensi FEE_TRUTH v1.1 (lihat FEE_TRUTH.md):
- Fee all-in dan slippage DIPISAH di output (fee_cost_pct vs slippage_cost_pct).
- Buy/sell asimetris: memakai fee["buy_*_pct"] dan fee["sell_*_pct"] terpisah.
- Lantai friksi diterapkan: SL_gross >= min_friction_multiple × (buy_fee + sell_fee + 2×slip).
- Target gross: Target_gross = k × (SL_gross + F) + F, dengan F = total friksi.
- Trade yang kena lantai friksi ditandai sl_floor_active=True; summary melaporkan
  sl_floor_active_trades dan sl_floor_active_pct.
- Stress test slippage: jalankan --slippage-pct 0.20 dan 0.50 (FEE_TRUTH §3).
"""