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
    - order_type            = taker (tanpa opsi maker; replay = jalur market)

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
    "min_risk_pct": 0.5,            # legacy floor; lantai friksi biasanya menang
    "max_tp2_r": 5.0,
    "min_net_rr": 1.15,            # sama dengan ScalpingConfig.MIN_NET_RR (jangan beda diam-diam)
    "max_risk_atr": 2.0,
    "max_change_24h_pct": 0.0,

    # P0 — gatekeeper & regime (mirror ScalpingConfig/ScalpingRuntimeConfig).
    # TTL tidak mengubah entry replay (replay masuk pada bar sinyal), tetapi nilainya
    # dilaporkan supaya pembaca tidak mengira TTL diabaikan tanpa alasan.
    "ttl_bars_m1": 5,
    "mtf_min_bullish": 3,
    "mtf_block_high_tf_bearish": True,
    "btc_veto_enabled": True,
    "btc_return_veto_pct": -0.6,
    "btc_atr_ratio_veto": 2.5,
    "btc_ema_period": 20,
    "regime_adx_trending": 25.0,
    "regime_adx_weak": 20.0,
    "regime_chop_ranging": 61.8,
    "regime_chop_trend_max": 55.0,

    # Detektor setup (threshold engine; tetap seperti pipeline)
    "chop_hard_ranging": 65.0,
    "rvol_setup_min": 1.2,
    "rvol_retest_min": 1.5,
    "rvol_breakout_min": 1.5,
    "buy_pressure_retest": 1.15,
    "buy_pressure_breakout": 1.15,
    "retest_above_factor": 0.995,
    "retest_max_dist_pct": 1.2,
    "retest_min_structure_strength": 60,
    "breakout_above_factor": 0.998,
    "pullback_support_max_dist_pct": 1.2,
    "min_score_breakout_retest": 75,
    "min_score_long": 60,
    "min_score_strong": 75,

    # Exit paper (mirror ScalpExitParams.DEFAULT). CLASSIC tetap memakai time-stop 90 bar.
    "exit": {
        "mode": "classic",
        "min_early_profit_net_pct": 0.45,
        "breakeven_trigger_net_pct": 0.40,
        "trail_pct": 0.35,
        "max_hold_bars_m1": 90,
        "min_profit_friction_multiple": 2.0,
        "be_trigger_atr_fraction": 0.5,
        "trail_atr_fraction": 0.6,
    },

    # Replay
    "replay": {
        "m15_min_bars": 20,
        "m5_min_bars": 20,
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
    """Isi key yang hilang dengan DEFAULTS; sub-dict 'replay' dan 'exit' di-merge."""
    out = dict(cfg)
    for k, v in DEFAULTS.items():
        if k in ("replay", "exit"):
            out[k] = {**v, **cfg.get(k, {})}
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


def resolve_fee(cfg, pair):
    """
    Fee sesuai kuotasi pair. Replay adalah jalur market, jadi selalu taker.

    Config kanonik memakai fee USDT di level atas + `idr_reference`; pair berakhiran IDR
    wajib memakai referensi IDR (FEE_TRUTH §0.2). Config IDR mandiri (mis.
    `scalp_config_exit_full_idr.json`) sudah punya fee IDR di level atas.
    """
    fee = dict(cfg.get("fee", {}))
    quote = str(fee.get("quote", "")).upper()
    wants_idr = str(pair).upper().endswith("IDR")
    if wants_idr and quote != "IDR":
        ref = fee.get("idr_reference")
        if not ref:
            raise ValueError(
                f"pair {pair} butuh fee IDR, tetapi config tidak punya `fee.idr_reference` "
                "dan `fee.quote` bukan IDR."
            )
        fee = {**fee, **ref, "quote": "IDR"}
        fee.pop("idr_reference", None)
    validate_fee(fee)
    return fee


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
    # Ambang ATR mengikuti ScalpingConfig.ATR_PCT_HIGH / ATR_PCT_LOW (bukan angka P0 baru).
    atr_pct_high = 3.5
    atr_pct_low = 0.8
    if atr_pct >= atr_pct_high:
        return {"regime": "HIGH_VOLATILITY", "adx": adx, "chop": chop,
                "atr_pct": atr_pct, "ema": ema}
    # p0: LOW_VOL = ATR rendah DAN CHOP > batas trend. Versi lama memakai ADX < 18.
    if 0.0 < atr_pct < atr_pct_low and chop > cfg["regime_chop_trend_max"]:
        return {"regime": "LOW_VOLATILITY", "adx": adx, "chop": chop,
                "atr_pct": atr_pct, "ema": ema}
    # BREAKOUT adalah ekstensi app; p0 belum mengembalikannya.
    if res_broken and rvol >= 1.5:
        return {"regime": "BREAKOUT", "adx": adx, "chop": chop,
                "atr_pct": atr_pct, "ema": ema}

    # p0 — trending diperiksa lebih dulu: adx >= trending DAN chop < batas atas.
    is_trending = adx >= cfg["regime_adx_trending"] and chop < cfg["regime_chop_trend_max"]
    if is_trending:
        if (bias == "BULLISH" and ema == "bullish") or (ema == "bullish" and bias != "BEARISH"):
            reg = "TRENDING_UP"
        elif (bias == "BEARISH" and ema == "bearish") or (ema == "bearish" and bias != "BULLISH"):
            reg = "TRENDING_DOWN"
        else:
            reg = "RANGING"
        return {"regime": reg, "adx": adx, "chop": chop, "atr_pct": atr_pct, "ema": ema}

    # p0 — choppy memakai OR: CHOP tinggi ATAU ADX lemah.
    if chop >= cfg["regime_chop_ranging"] or adx < cfg["regime_adx_weak"]:
        return {"regime": "RANGING", "adx": adx, "chop": chop,
                "atr_pct": atr_pct, "ema": ema}

    # Mild trend tanpa ADX kuat — hanya fase transisi (sama seperti app).
    if adx >= cfg["regime_adx_weak"] and chop < 50.0:
        if ema == "bullish" or bias == "BULLISH":
            return {"regime": "TRENDING_UP", "adx": adx, "chop": chop,
                    "atr_pct": atr_pct, "ema": ema}
        if ema == "bearish" or bias == "BEARISH":
            return {"regime": "TRENDING_DOWN", "adx": adx, "chop": chop,
                    "atr_pct": atr_pct, "ema": ema}

    return {"regime": "RANGING", "adx": adx, "chop": chop, "atr_pct": atr_pct, "ema": ema}


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
            # S1 — RVOL WAJIB, sama dengan ScalpSetupDetector.detectBreakout.
            # Tanpa ini breakout di pasar sepi lolos sebagai sinyal (kasus AMP/USDT).
            if rvol_ok and sum([flow_ok, mom_ok]) >= 1:
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
# P0 — BTC market gatekeeper (mirror BtcPulseAnalyzer.kt)
# --------------------------------------------------------------------------

def btc_atr_ratio(candles, period=14):
    """ATR% terakhir dibagi rata-rata ATR% historis (bukan ATR absolut)."""
    if period <= 0 or len(candles) < period + 2:
        return 1.0
    tr = []
    for i in range(1, len(candles)):
        high = candles[i]["h"]
        low = candles[i]["l"]
        prev_close = candles[i - 1]["c"]
        tr.append(max(high - low, abs(high - prev_close), abs(low - prev_close)))
    if len(tr) < period:
        return 1.0
    atr_values = [sum(tr[:period]) / period]
    for i in range(period, len(tr)):
        atr_values.append((atr_values[-1] * (period - 1) + tr[i]) / period)

    current_price = candles[-1]["c"]
    if current_price <= 0:
        return 1.0
    current_atr_pct = atr_values[-1] / current_price * 100.0
    baseline = []
    for i, atr in enumerate(atr_values):
        close = candles[i + period]["c"]
        if close > 0:
            baseline.append(atr / close * 100.0)
    mean_pct = sum(baseline) / len(baseline) if baseline else current_atr_pct
    return current_atr_pct / mean_pct if mean_pct > 0 else 1.0


def _btc_confirmation_1m(candles, period):
    if len(candles) < max(22, period + 2):
        return None
    closes = [c["c"] for c in candles]
    ema = ema_last(closes, period)
    close = closes[-1]
    prev = closes[-2]
    ret = (close - prev) / prev * 100.0 if prev > 0 else 0.0
    if ema > 0 and close < ema and ret < -0.3:
        return f"Konfirmasi 1M: close di bawah EMA{period}, return {ret:.2f}%"
    return None


def evaluate_btc_veto(btc5m, btc1m, cfg):
    """
    Veto LONG altcoin bila:
      1. close 5M < EMA dan return terburuk 2 candle < btc_return_veto_pct, atau
      2. ATR ratio > btc_atr_ratio_veto dan minimal 2/3 candle terakhir bearish.
    Candle yang dioper wajib sudah tutup; replay menggeser pointer secara kausal.
    """
    ema_period = max(2, int(cfg.get("btc_ema_period", 20)))
    min_bars = max(22, ema_period + 2)
    out = {
        "veto": False, "status": "INSUFFICIENT_DATA", "reason": "",
        "btc_price": 0.0, "return_5m_pct": 0.0, "ema": 0.0,
        "atr_ratio": 0.0, "bearish_bars": 0, "sufficient": False,
    }
    if len(btc5m) < min_bars:
        return out

    closes = [c["c"] for c in btc5m]
    price = closes[-1]
    ema = ema_last(closes, ema_period)
    prev = closes[-2]
    prev2 = closes[-3]
    ret1 = (price - prev) / prev * 100.0 if prev > 0 else 0.0
    ret2 = (prev - prev2) / prev2 * 100.0 if prev2 > 0 else 0.0
    worst = min(ret1, ret2)

    atr_ratio = btc_atr_ratio(btc5m, 14)
    bearish_bars = sum(1 for c in btc5m[-3:] if c["c"] < c["o"])
    below_ema = ema > 0 and price < ema
    cond1 = below_ema and worst < cfg.get("btc_return_veto_pct", -0.6)
    cond2 = atr_ratio > cfg.get("btc_atr_ratio_veto", 2.5) and bearish_bars >= 2

    reasons = []
    if cond1:
        reasons.append(
            f"BTC 5M close {price:.2f} < EMA{ema_period} {ema:.2f} dan return "
            f"{worst:.2f}% < {cfg.get('btc_return_veto_pct', -0.6):.2f}%"
        )
    if cond2:
        reasons.append(
            f"BTC ATR ratio {atr_ratio:.2f}x > {cfg.get('btc_atr_ratio_veto', 2.5):.2f}x "
            f"dengan {bearish_bars}/3 candle bearish impulsif"
        )
    confirmation = _btc_confirmation_1m(btc1m, ema_period)
    if (cond1 or cond2) and confirmation:
        reasons.append(confirmation)

    enabled = bool(cfg.get("btc_veto_enabled", True))
    veto = enabled and (cond1 or cond2)
    status = "DUMP_RISK" if veto else ("CAUTION" if below_ema else
                                      "CAUTION" if worst < -0.3 else "SAFE")
    return {
        "veto": veto, "status": status, "reason": "; ".join(reasons),
        "btc_price": price, "return_5m_pct": worst, "ema": ema,
        "atr_ratio": atr_ratio, "bearish_bars": bearish_bars, "sufficient": True,
    }


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

def roundtrip_fee(entry, sl, tp, fee, slip):
    """
    Net RR dan komponen biaya untuk order market (taker dua sisi).
    Fee dan slippage dipisah (FEE_TRUTH §0, §8).
    """
    if entry <= 0 or sl <= 0 or tp <= 0:
        return {"net_rr": 0.0, "net_reward_pct": 0.0, "net_risk_pct": 0.0,
                "fee_cost_pct": 0.0, "slippage_cost_pct": 0.0, "total_cost_pct": 0.0}
    bf = fee["buy_taker_pct"]
    sf = fee["sell_taker_pct"]
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

    bf, sf = fee["buy_taker_pct"], fee["sell_taker_pct"]
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
    max_risk_atr = cfg.get("max_risk_atr", DEFAULTS["max_risk_atr"])
    max_risk = max(float(max_risk_atr) * atr, min_risk)

    sl = ssl if ssl is not None else entry - 1.0 * atr
    raw = entry - sl
    # True bila SL struktur (sebelum lantai) tidak menutup biaya round-trip.
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

    f = roundtrip_fee(entry, sl, tp2, fee, slip)
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
        "valid": valid,
    }


# --------------------------------------------------------------------------
# Evaluasi per bar (mirror ScalpingMtfEvaluator.evaluate gate)
# --------------------------------------------------------------------------

def min_score_for_setup(setup, cfg):
    return cfg["min_score_breakout_retest"] if setup == "BREAKOUT_RETEST" else cfg["min_score_long"]


def evaluate_mtf_gate(h1w, m15w, m5w, m1w, cfg):
    """
    P0 — gate MTF 4 TF (mirror MtfAlignmentPolicy.kt).
    H1/M15 tidak boleh bearish; minimal `mtf_min_bullish` bullish; fail-closed bila
    salah satu TF belum punya data (known < 4), supaya label 3/4 tidak menyesatkan.
    """
    legs = [mtf_leg(h1w), mtf_leg(m15w), mtf_leg(m5w), mtf_leg(m1w)]
    known = [l for l in legs if l != "UNKNOWN"]
    bull = sum(1 for l in known if l == "BULLISH")
    high_bear = legs[0] == "BEARISH" or legs[1] == "BEARISH"
    min_bull = int(cfg.get("mtf_min_bullish", 3))
    block_high = bool(cfg.get("mtf_block_high_tf_bearish", True))

    if min_bull <= 0:
        return {"allow": True, "bull": bull, "known": len(known),
                "high_bear": high_bear, "reason": "Gerbang MTF nonaktif (mtf_min_bullish=0)."}
    if len(known) < 4:
        return {"allow": False, "bull": bull, "known": len(known),
                "high_bear": high_bear,
                "reason": f"Data MTF belum lengkap ({len(known)}/4 TF)."}
    if block_high and high_bear:
        return {"allow": False, "bull": bull, "known": len(known),
                "high_bear": True,
                "reason": "H1/M15 bearish — LONG ditolak gate MTF."}
    if bull < min_bull:
        return {"allow": False, "bull": bull, "known": len(known),
                "high_bear": high_bear,
                "reason": f"MTF {bull}/{len(known)} bullish < minimal {min_bull}."}
    return {"allow": True, "bull": bull, "known": len(known),
            "high_bear": high_bear,
            "reason": f"MTF {bull}/{len(known)} bullish."}


def evaluate_bar(price, m1w, m15w, h1w, cfg, fee, assume_ob, change24h=0.0,
                 btc_veto=False, btc_reason="", m5w=None):
    if price <= 0 or len(h1w) < 20 or len(m15w) < 20 or len(m1w) < 20:
        return None
    # Fallback agregasi M1 -> M5 untuk pemanggil lama (mis. uji parity). Pipeline utama
    # selalu mengoper M5 yang sudah diagregasi sekali di run_replay.
    if m5w is None:
        m5w = aggregate(m1w, 5)
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

    # P0 — gate MTF 4 TF (H1, M15, M5, M1) dan veto BTC. Keduanya memblokir eksekusi,
    # bukan mengubah geometri risiko/skor.
    mtf = evaluate_mtf_gate(h1w, m15w, m5w, m1w, cfg)
    sc = score_all(price, st, rg, setup, rvol, bp, False,
                   mtf["bull"], max(1, mtf["known"]), m1w)

    rl = risk_levels(price, setup if setup != "NONE" else "NONE",
                     st, rg, fee, cfg)
    risk_valid = setup != "NONE" and rl is not None and rl["valid"]
    net_rr = rl["net_rr"] if rl else 0.0

    knife = st["bias"] == "BEARISH" and len(m1w) >= 5 and \
        m1w[-1]["c"] < m1w[-5]["c"] * 0.992
    # S9 — koin yang sudah naik ekstrem 24 jam ditahan. `change24h == 0` berarti
    # tidak diketahui (mis. data < 1440 bar) dan gerbang dilewati, sama seperti app.
    max_change = cfg.get("max_change_24h_pct", DEFAULTS["max_change_24h_pct"])
    extended = max_change > 0 and change24h >= max_change
    dangerous = noise or overbought or knife or extended

    s1 = (not dangerous) and room
    s2 = s1 and ob_valid
    vwap_ok = price > vw or vsa or \
        (38.0 <= rsi <= 68.0 and last["c"] > last["o"] and last["c"] >= vw * 0.9985) or ema_bounce
    s3 = s2 and setup != "NONE" and vwap_ok
    min_sc = min_score_for_setup(setup, cfg)
    s4 = s3 and risk_valid and sc["total"] >= min_sc

    gate_ok = mtf["allow"] and not btc_veto
    ready = s4 and gate_ok
    strong = ready and sc["total"] >= cfg["min_score_strong"]
    # P1 — early wajib punya pola, sama seperti app.
    early = (not ready) and (not dangerous) and s2 and setup != "NONE" and gate_ok
    stage = "STRONG_ENTRY" if strong else "ENTRY" if ready else \
            "EARLY_ENTRY" if early else "HOLD" if (dangerous or btc_veto) else "WATCH"
    if dangerous or btc_veto:
        conf = 0
    elif ready:
        conf = sc["total"]
    else:
        cap = 39 if not mtf["allow"] else 59 if s3 else 49 if s2 else 39 if s1 else 29
        conf = max(10, min(sc["total"], cap))

    gross_risk_pct = 0.0
    if rl and rl["entry"] > 0:
        gross_risk_pct = (rl["entry"] - rl["sl"]) / rl["entry"] * 100.0

    return {
        "ready": ready, "strong": strong, "stage": stage, "setup": setup,
        "score": sc["total"], "category": sc["category"], "parts": sc["parts"],
        "confidence": conf, "net_rr": net_rr,
        "steps": [s1, s2, s3, s4],
        "mtf_allow": mtf["allow"], "mtf_bull": mtf["bull"],
        "mtf_known": mtf["known"], "mtf_reason": mtf["reason"],
        "btc_veto": btc_veto, "btc_reason": btc_reason,
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
        # S8 — ATR% diperlukan resolve_trade untuk BE/trailing proporsional.
        "atr_pct": rg["atr_pct"] if rg["atr_pct"] > 0 else 0.0,
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

# --------------------------------------------------------------------------
# Exit resolution (spot long): classic TP2/SL vs TP1+BE+trail vs early profit
# --------------------------------------------------------------------------

def _net_profit_pct(entry, price, fee, slip):
    """Profit net % setelah buy+sell fee dan 2x slippage (perkiraan spot)."""
    if entry <= 0 or price <= 0:
        return 0.0
    buy_cost = 1.0 + (fee.get("buy_taker_pct", 0.1) + slip) / 100.0
    sell_net = max(0.0, 1.0 - (fee.get("sell_taker_pct", 0.1) + slip) / 100.0)
    return ((price / entry) * sell_net / buy_cost - 1.0) * 100.0


def be_price(entry, fee, slip):
    """Harga impas net (profit net = 0). Kebalikan dari _net_profit_pct."""
    buy_cost = 1.0 + (fee.get("buy_taker_pct", 0.1) + slip) / 100.0
    sell_net = max(1e-9, 1.0 - (fee.get("sell_taker_pct", 0.1) + slip) / 100.0)
    return entry * buy_cost / sell_net


def _momentum_weak(m1_slice):
    """Momentum lemah: EMA9 < EMA21*0.998 atau RSI di luar 40-78 atau candle merah dominan."""
    if len(m1_slice) < 10:
        return True
    closes = [c["c"] for c in m1_slice]
    e9 = ema_last(closes, 9)
    e21 = ema_last(closes, 21)
    r = rsi_wilder(closes, min(14, len(closes) - 1))
    last = m1_slice[-1]
    bear_candle = last["c"] < last["o"]
    ema_weak = e9 < e21 * 0.998
    rsi_weak = r < 40.0 or r > 78.0
    return ema_weak or (rsi_weak and bear_candle) or (bear_candle and e9 < e21)


def _round_trip_cost_pct(fee, slip):
    """Friksi round-trip (%) = buy fee + sell fee + 2x slippage (satu sisi slippage tiap transaksi)."""
    return fee.get("buy_taker_pct", 0.1) + fee.get("sell_taker_pct", 0.1) + 2.0 * slip


def _gross_pct(entry, price):
    """Profit kotor (%) berbasis harga — dasar perbandingan lantai profit (S2)."""
    if entry <= 0 or price <= 0:
        return 0.0
    return (price / entry - 1.0) * 100.0


# Fase A — horizon MFE/MAE dari bar masuk sinyal, dalam bar M1.
MFE_HORIZONS_M1 = (30, 60, 90)
MFE_KEYS = tuple(
    key
    for h in (30, 60, 90)
    for key in (f"mfe_{h}", f"mae_{h}", f"bars_{h}")
)
MFE_ALL_KEYS = ("mfe_exit_pct",) + MFE_KEYS


def _pctl(vals, q):
    """Kuantil linier (interpolasi, setara percentile numpy)."""
    s = sorted(vals)
    if not s:
        return 0.0
    pos = q * (len(s) - 1)
    lo = int(pos)
    hi = min(lo + 1, len(s) - 1)
    frac = pos - lo
    return s[lo] * (1.0 - frac) + s[hi] * frac


def _entry_excursions(m1, i, entry, horizons=MFE_HORIZONS_M1):
    """
    MFE/MAE kotor (%) dari harga sinyal, per horizon bar M1.

    Untuk horizon h: MFE = persen high tertinggi pada bar i+1..i+h, MAE = persen
    low terendah pada jendela yang sama. `bars_<h>` = jumlah bar terpakai — kalau
    data berhenti lebih awal, nilainya dibatasi data yang tersedia.
    """
    n = len(m1)
    out = {}
    for h in horizons:
        used = max(0, min(n, i + 1 + h) - (i + 1))
        if used <= 0:
            out[f"mfe_{h}"] = 0.0
            out[f"mae_{h}"] = 0.0
            out[f"bars_{h}"] = 0
            continue
        win = m1[i + 1:i + 1 + used]
        out[f"mfe_{h}"] = round(_gross_pct(entry, max(b["h"] for b in win)), 4)
        out[f"mae_{h}"] = round(_gross_pct(entry, min(b["l"] for b in win)), 4)
        out[f"bars_{h}"] = used
    return out


def resolve_trade(m1, i, entry, sl, tp1, tp2, fee, cfg, lookahead, atr_pct=0.0):
    """
    Resolve forward dari bar i+1.
    Satuan R seragam untuk SEMUA exit:
        R = profit_net_pct(harga_exit) / risiko_kotor_pct(entry -> sl)
    exit_mode di cfg['exit']:
      classic — SL / TP2 saja, tetap terkena time-stop `max_hold_bars_m1`
      tp1_be  — TP1 partial 50% + SL ke BE(net); lalu TP2 atau SL
      trail   — trailing dari peak setelah net >= be_trigger
      early   — early SELL jika net_close >= min_early dan momentum lemah
      full    — tp1_be + trail + early (prioritas: SL > early > trail > TP2)

    S2 — profit sukarela (early) dan peng-arm-an BE/trailing hanya setelah profit
    KOTOR >= `min_profit_friction_multiple` x friksi round-trip.
    S8 — trigger BE / jarak trailing memakai fraksi ATR bila ATR tersedia.
    """
    ex = cfg.get("exit", {})
    mode = ex.get("mode", "classic")
    slip = cfg.get("slippage_pct", DEFAULTS["slippage_pct"])
    min_early = float(ex.get("min_early_profit_net_pct", 0.45))
    be_trigger = float(ex.get("breakeven_trigger_net_pct", 0.40))
    trail_pct = float(ex.get("trail_pct", 0.35))
    max_hold = int(ex.get("max_hold_bars_m1", 0))

    min_profit_mult = float(ex.get("min_profit_friction_multiple", 2.0))
    be_atr_frac = float(ex.get("be_trigger_atr_fraction", 0.5))
    trail_atr_frac = float(ex.get("trail_atr_fraction", 0.6))
    floor_gross = min_profit_mult * _round_trip_cost_pct(fee, slip) if min_profit_mult > 0 else 0.0
    if be_atr_frac > 0 and atr_pct > 0:
        be_trigger = max(be_trigger, be_atr_frac * atr_pct)
    trail_dist = trail_atr_frac * atr_pct if (trail_atr_frac > 0 and atr_pct > 0) else trail_pct

    # Fase A — MFE/MAE dari bar masuk sinyal, dihitung untuk SEMUA sinyal siap,
    # termasuk yang lulus tak matematika risiko (bad_risk).
    exc = _entry_excursions(m1, i, entry)

    risk = entry - sl
    if risk <= 0 or entry <= 0:
        return {"result": "UNRESOLVED", "r": 0.0, "exit_idx": None, "ambiguous": False,
                "exit_reason": "bad_risk", "mfe_exit_pct": 0.0, **exc}
    risk_pct = risk / entry * 100.0

    def r_at(price):
        return _net_profit_pct(entry, price, fee, slip) / risk_pct

    be_px = be_price(entry, fee, slip)

    n = len(m1)
    end = min(n, i + 1 + lookahead)
    if max_hold > 0:
        end = min(end, i + 1 + max_hold)

    active_sl = sl
    be_armed = False
    trail_armed = False
    peak = entry
    partial_r = 0.0
    partial_done = False
    ambiguous = False

    def _done(total_r, idx, reason, amb=False):
        # mfe_exit_pct = MFE kotor sampai bar exit terakhir (vars peak trailing-sudah-jalan),
        # pembanding langsung dengan `r` realised.
        return {
            "result": "WIN" if total_r > 0 else "LOSS",
            "r": round(total_r, 4),
            "exit_idx": idx,
            "ambiguous": amb,
            "exit_reason": reason,
            "mfe_exit_pct": round(_gross_pct(entry, peak), 4),
            **exc,
        }

    for j in range(i + 1, end):
        b = m1[j]
        hi, lo, cl = b["h"], b["l"], b["c"]
        peak = max(peak, hi)

        # --- Hard SL (selalu dicek dulu) ---
        if lo <= active_sl:
            if hi >= tp2 and active_sl <= sl + 1e-12:
                ambiguous = True
            rem = r_at(active_sl)
            total_r = partial_r + 0.5 * rem if partial_done else rem
            if active_sl <= sl + 1e-12:
                reason = "SL"
            elif be_armed and not trail_armed:
                reason = "BE_SL"
            else:
                reason = "TRAIL_SL"
            return _done(total_r, j, reason, ambiguous)

        # --- TP2 full ---
        if hi >= tp2:
            total_r = partial_r + 0.5 * r_at(tp2) if partial_done else r_at(tp2)
            return _done(total_r, j, "TP2")

        # --- Mode-specific ---
        if mode in ("tp1_be", "trail", "early", "full"):
            net_hi = _net_profit_pct(entry, hi, fee, slip)
            net_cl = _net_profit_pct(entry, cl, fee, slip)

            if mode in ("tp1_be", "full") and (not partial_done) and hi >= tp1:
                partial_done = True
                partial_r = 0.5 * r_at(tp1)
                active_sl = max(active_sl, be_px)
                be_armed = True

            if (mode in ("trail", "early", "full") and (not be_armed)
                    and net_hi >= be_trigger and _gross_pct(entry, hi) >= floor_gross):
                active_sl = max(active_sl, be_px)
                be_armed = True

            if (mode in ("trail", "full") and net_hi >= be_trigger
                    and _gross_pct(entry, hi) >= floor_gross):
                trail_armed = True
                trail_sl = peak * (1.0 - trail_dist / 100.0)
                active_sl = max(active_sl, trail_sl)

            if (mode in ("early", "full") and net_cl >= min_early
                    and _gross_pct(entry, cl) >= floor_gross):
                window = m1[max(0, j - 14):j + 1]
                if _momentum_weak(window):
                    rem = r_at(cl)
                    total_r = partial_r + 0.5 * rem if partial_done else rem
                    return _done(total_r, j, "EARLY_PROFIT")

    # Time-stop / akhir data: tutup di close bar terakhir yang tersedia.
    if end > i + 1:
        last_idx = end - 1
        rem = r_at(m1[last_idx]["c"])
        total_r = partial_r + 0.5 * rem if partial_done else rem
        return _done(total_r, last_idx, "TIMEOUT", ambiguous)

    return {"result": "UNRESOLVED", "r": 0.0, "exit_idx": None, "ambiguous": False,
            "exit_reason": "TIMEOUT", "mfe_exit_pct": 0.0, **exc}


def run_replay(m1, m15, h1, cfg, fee, assume_ob, lookahead, btc1m=None):
    rp = cfg.get("replay", DEFAULTS["replay"])
    m15_min = int(rp.get("m15_min_bars", 20))
    m5_min = int(rp.get("m5_min_bars", 20))
    h1_min = int(rp.get("h1_min_bars", 20))
    # M5 diagregasi sekali dari M1, persis seperti app menyintesis HTF dari M1.
    m5 = aggregate(m1, 5)
    btc5 = aggregate(btc1m, 5) if btc1m else []
    evals = 0
    bott = {"s1": 0, "s2": 0, "s3": 0, "s4": 0}
    gate_blocks = {"mtf": 0, "btc": 0}
    btc_checks = 0
    btc_veto_checks = 0
    setup_hits = {}
    exit_reasons = {}
    trades = []
    equity = []
    cum = 0.0
    peak = 0.0
    max_dd = 0.0
    i = 0
    n = len(m1)
    wlen = int(rp.get("m1_window", 250))
    # Pointer HTF: m15/h1/m5 terurut waktu; hanya candle CLOSED (tutup <= t bar M1 berjalan).
    p15 = p60 = p5 = 0
    # Pointer BTC 5M/1M; konteks dihitung ulang hanya saat pointer bergerak.
    p5b = pb1 = 0
    last_btc_key = None
    btc_res = {"veto": False, "reason": "", "sufficient": False, "status": "NO_DATA"}
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
        while pb1 < len(btc1m or []) and btc1m[pb1]["t"] + 60_000 <= t:
            pb1 += 1
        m15w = m15[max(0, p15 - 60):p15]
        h1w = h1[max(0, p60 - 60):p60]
        m5w = m5[max(0, p5 - 60):p5]
        m1w = m1[max(0, i - wlen + 1):i + 1]
        if len(m1w) < 20 or len(m15w) < m15_min or len(m5w) < m5_min or len(h1w) < h1_min:
            i += 1
            continue
        btc_key = (p5b, pb1)
        if btc5 and btc_key != last_btc_key:
            btc_res = evaluate_btc_veto(
                btc5[max(0, p5b - 120):p5b],
                btc1m[max(0, pb1 - 60):pb1] if btc1m else [],
                cfg
            )
            last_btc_key = btc_key
            if btc_res["sufficient"]:
                btc_checks += 1
                if btc_res["veto"]:
                    btc_veto_checks += 1
        # S9 — perubahan harga 24 jam (1440 bar M1) untuk gerbang extended move.
        chg24 = 0.0
        if i >= 1440 and m1[i - 1440]["c"] > 0:
            chg24 = (c["c"] / m1[i - 1440]["c"] - 1.0) * 100.0
        r = evaluate_bar(c["c"], m1w, m15w, h1w, cfg, fee, assume_ob,
                         change24h=chg24, btc_veto=btc_res["veto"],
                         btc_reason=btc_res["reason"], m5w=m5w)
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
        # Gate P0 dihitung terpisah: hanya relevan bila langkah dasar sudah lolos.
        if s4 and not r["mtf_allow"]:
            gate_blocks["mtf"] += 1
        if s4 and r["btc_veto"]:
            gate_blocks["btc"] += 1
        if not r["ready"]:
            i += 1
            continue
        setup_hits[r["setup"]] = setup_hits.get(r["setup"], 0) + 1
        entry, sl, tp1, tp2 = r["entry"], r["sl"], r["tp1"], r["tp2"]
        resolved = resolve_trade(m1, i, entry, sl, tp1, tp2, fee, cfg, lookahead,
                                 atr_pct=r.get("atr_pct", 0.0))
        reason = resolved.get("exit_reason", "")
        exit_reasons[reason] = exit_reasons.get(reason, 0) + 1
        if resolved["result"] == "UNRESOLVED":
            rec = {"setup": r["setup"], "score": r["score"], "result": "UNRESOLVED",
                   "r": 0.0, "t": t, "ambiguous": False,
                   "entry": entry, "sl": sl, "tp1": tp1, "tp2": tp2,
                   "gross_risk_pct": r["gross_risk_pct"],
                   "friction_pct": r["friction_pct"],
                   "sl_floor_active": r["sl_floor_active"],
                   "exit_reason": reason}
            rec.update({k: resolved[k] for k in MFE_ALL_KEYS if k in resolved})
            trades.append(rec)
            i += lookahead
            continue
        exit_r = resolved["r"]
        exit_idx = resolved["exit_idx"]
        cum += exit_r
        peak = max(peak, cum)
        max_dd = max(max_dd, peak - cum)
        equity.append(round(cum, 4))
        rec = {"setup": r["setup"], "score": r["score"],
               "result": resolved["result"], "r": exit_r,
               "t": t, "ambiguous": resolved["ambiguous"],
               "entry": entry, "sl": sl, "tp1": tp1, "tp2": tp2,
               "gross_risk_pct": r["gross_risk_pct"],
               "friction_pct": r["friction_pct"],
               "sl_floor_active": r["sl_floor_active"],
               "exit_bar": m1[exit_idx]["t"], "exit_reason": reason}
        rec.update({k: resolved[k] for k in MFE_ALL_KEYS if k in resolved})
        trades.append(rec)
        i = exit_idx + 1
    return {"evals": evals, "bottleneck": bott, "gate_blocks": gate_blocks,
            "btc": {"available": bool(btc5), "bars_m1": len(btc1m or []),
                    "bars_5m": len(btc5), "checks": btc_checks,
                    "veto_checks": btc_veto_checks},
            "setup_hits": setup_hits, "exit_reasons": exit_reasons,
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
    by_exit = {}
    for t in tr:
        er = t.get("exit_reason", "?")
        d = by_exit.setdefault(er, {"trades": 0, "wins": 0, "gross_r": 0.0})
        d["trades"] += 1
        d["gross_r"] += t["r"]
        if t["result"] == "WIN":
            d["wins"] += 1
    for s, d in by_exit.items():
        d["win_rate"] = round(100.0 * d["wins"] / d["trades"], 2) if d["trades"] else 0.0
        d["avg_r"] = round(d["gross_r"] / d["trades"], 4) if d["trades"] else 0.0
        d["gross_r"] = round(d["gross_r"], 4)
    n = len(tr)
    total_signals = len(rep["trades"])

    floor_active = sum(1 for t in rep["trades"] if t.get("sl_floor_active"))
    avg_gross = round(sum(t.get("gross_risk_pct", 0) for t in tr) / n, 4) if n else 0.0
    avg_friction = round(sum(t.get("friction_pct", 0) for t in tr) / n, 4) if n else 0.0

    return {
        "evaluations": rep["evals"],
        "ready_signals": total_signals,
        "trades_resolved": n,
        "unresolved": total_signals - n,
        "wins": len(wins),
        "losses": n - len(wins),
        "win_rate_pct": round(100.0 * len(wins) / n, 2) if n else 0.0,
        "avg_r": round(sum(t["r"] for t in tr) / n, 4) if n else 0.0,
        "expectancy_r": round(sum(t["r"] for t in tr) / n, 4) if n else 0.0,
        "profit_factor": round(gw / gl, 4) if gl > 0 else (float("inf") if gw > 0 else 0.0),
        "max_drawdown_r": rep["max_dd_r"],
        "sl_floor_active_trades": floor_active,
        "sl_floor_active_pct": round(100.0 * floor_active / total_signals, 2) if total_signals else 0.0,
        "avg_gross_risk_pct": avg_gross,
        "avg_friction_pct": avg_friction,
        "entry_mfe": _entry_mfe_summary(rep["trades"]),
        "bottleneck": rep["bottleneck"],
        "gate_blocks": rep.get("gate_blocks", {}),
        "btc": rep.get("btc", {}),
        "setup_hits": rep["setup_hits"],
        "exit_reasons_count": rep.get("exit_reasons", {}),
        "by_setup": by_setup,
        "by_exit_reason": by_exit,
    }


def _entry_mfe_summary(trades, horizons=MFE_HORIZONS_M1):
    """
    Fase A — ringkasan MFE/MAE dari bar masuk sinyal, atas SEMUA sinyal siap
    (termasuk yang UNRESOLVED). `n_usable` = sinyal dengan jendela penuh h bar,
    `n_truncated` = data berhenti lebih awal; share dihitung atas sinyal usable.
    `mfe_exit_resolved` = MFE kotor sampai bar exit untuk trade yang tuntas —
    pembanding "rekam sempurna" terhadap R yang direalisasi.
    """
    if not trades:
        return {"n_signals": 0, "horizons": {}, "mfe_exit_resolved": {"n": 0}}
    out = {"n_signals": len(trades), "horizons": {}}
    for h in horizons:
        bkey, fkey, akey = f"bars_{h}", f"mfe_{h}", f"mae_{h}"
        with_key = [t for t in trades if t.get(bkey) is not None]
        usable = [t for t in with_key if t.get(bkey) == h]
        truncated = len([t for t in with_key if 0 < t.get(bkey) < h])
        mfes = [t[fkey] for t in usable]
        maes = [t[akey] for t in usable]

        def _share(pred):
            c = sum(1 for t in usable if pred(t))
            return round(100.0 * c / len(usable), 2) if usable else 0.0

        out["horizons"][str(h)] = {
            "n_usable": len(usable),
            "n_truncated": truncated,
            "median_mfe_pct": round(_pctl(mfes, 0.5), 4),
            "p25_mfe_pct": round(_pctl(mfes, 0.25), 4),
            "p75_mfe_pct": round(_pctl(mfes, 0.75), 4),
            "median_mae_pct": round(_pctl(maes, 0.5), 4),
            "p25_mae_pct": round(_pctl(maes, 0.25), 4),
            "share_mfe_ge_cost_pct": _share(
                lambda t: t[fkey] >= t.get("friction_pct", 0.0)),
            "share_mfe_ge_2cost_pct": _share(
                lambda t: t[fkey] >= 2.0 * t.get("friction_pct", 0.0)),
            "share_mfe_ge_4cost_pct": _share(
                lambda t: t[fkey] >= 4.0 * t.get("friction_pct", 0.0)),
            "share_mfe_ge_tp1_pct": _share(
                lambda t: t.get("entry", 0) > 0 and t.get("tp1", 0) > 0
                and t[fkey] >= (t["tp1"] - t["entry"]) / t["entry"] * 100.0),
            "share_sl_hit_pct": _share(
                lambda t: t.get("gross_risk_pct", 0.0) > 0
                and t[akey] <= -t["gross_risk_pct"]),
        }
    ex = [t["mfe_exit_pct"] for t in trades
          if t.get("exit_idx") is not None and "mfe_exit_pct" in t]
    out["mfe_exit_resolved"] = {
        "n": len(ex),
        "median_pct": round(_pctl(ex, 0.5), 4) if ex else 0.0,
        "p25_pct": round(_pctl(ex, 0.25), 4) if ex else 0.0,
        "p75_pct": round(_pctl(ex, 0.75), 4) if ex else 0.0,
    }
    return out


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
    ap.add_argument("--btc-csv", default="",
                    help="CSV M1 BTC untuk veto P0 (bawaan: cari BTCUSDT_1m.csv / "
                         "BTCIDR_1m.csv di --data; kosong = veto tidak dapat diukur)")
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

    try:
        fee = resolve_fee(cfg, args.pair)
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

    # P0 — BTC gatekeeper. Untuk pair IDR, BTCIDR lebih relevan; untuk USDT, BTCUSDT.
    btc_path = args.btc_csv
    if btc_path and not os.path.exists(btc_path):
        print(json.dumps({
            "error": f"CSV BTC tidak ditemukan: {btc_path}",
            "saran": "hilangkan --btc-csv agar tool mencari BTCUSDT_1m.csv/BTCIDR_1m.csv "
                     "di --data, atau arahkan ke berkas yang benar",
        }))
        return 1
    if not btc_path:
        candidates = (["BTCIDR_1m.csv", "BTCUSDT_1m.csv"]
                      if args.pair.upper().endswith("IDR")
                      else ["BTCUSDT_1m.csv", "BTCIDR_1m.csv"])
        for name in candidates:
            candidate = os.path.join(args.data, name)
            if os.path.exists(candidate):
                btc_path = candidate
                break
    btc1m = load_csv(btc_path) if btc_path else []
    btc_source = {
        "path": btc_path,
        "available": bool(btc1m),
        "bars_m1": len(btc1m),
        "note": ("Veto BTC diukur dari candle yang sudah tutup."
                 if btc1m else
                 "BTC tidak tersedia — kolom veto BTC TIDAK mengukur apa pun; "
                 "sinyal replay tidak memakai veto BTC."),
    }

    rep = run_replay(m1, m15, h1, cfg, fee, args.assume_orderbook_ok, lookahead,
                     btc1m=btc1m)

    # Fee per sisi efektif (untuk audit FEE_TRUTH §8)
    bf_eff, sf_eff = fee["buy_taker_pct"], fee["sell_taker_pct"]
    friction_pct_eff = (bf_eff + sf_eff) + 2.0 * cfg["slippage_pct"]
    friction_floor_pct_eff = cfg["min_friction_multiple"] * friction_pct_eff

    out = {
        "tool": "scalp_replay",
        "pair": args.pair,
        "assume_orderbook_ok": args.assume_orderbook_ok,
        "htf_source": htf_used,
        "bars_m1": len(m1),
        "bars_m5": len(aggregate(m1, 5)),
        "bars_m15": len(m15),
        "bars_h1": len(h1),
        "range": {"from": m1[0]["t"], "to": m1[-1]["t"]} if m1 else {},
        "fee": fee,
        "fee_quote": fee.get("quote"),
        # Parameter efektif (audit FEE_TRUTH §5, §8)
        "effective_slippage_pct": cfg["slippage_pct"],
        "min_friction_multiple": cfg["min_friction_multiple"],
        "target_net_rr": cfg["target_net_rr"],
        "max_risk_atr": cfg["max_risk_atr"],
        "max_change_24h_pct": cfg["max_change_24h_pct"],
        "exit": cfg["exit"],
        "friction_pct_effective": round(friction_pct_eff, 4),
        "friction_floor_pct_effective": round(friction_floor_pct_eff, 4),
        "fee_cost_pct_effective": round(bf_eff + sf_eff, 4),
        "slippage_cost_pct_effective": round(2.0 * cfg["slippage_pct"], 4),
        "btc_source": btc_source,
        "p0": {
            "ttl_bars_m1": cfg["ttl_bars_m1"],
            "ttl_note": ("Replay masuk posisi pada bar sinyal, jadi TTL tidak mengubah entry; "
                         "TTL mengatur berapa lama setup boleh menunggu di app. Angka trade "
                         "di sini TIDAK membuktikan manfaat TTL."),
            "mtf_min_bullish": cfg["mtf_min_bullish"],
            "mtf_block_high_tf_bearish": cfg["mtf_block_high_tf_bearish"],
            "btc_veto_enabled": cfg["btc_veto_enabled"],
            "btc_return_veto_pct": cfg["btc_return_veto_pct"],
            "btc_atr_ratio_veto": cfg["btc_atr_ratio_veto"],
            "btc_ema_period": cfg["btc_ema_period"],
            "regime_thresholds": {
                "adx_trending": cfg["regime_adx_trending"],
                "adx_weak": cfg["regime_adx_weak"],
                "chop_ranging": cfg["regime_chop_ranging"],
                "chop_trend_max": cfg["regime_chop_trend_max"],
            },
        },
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
3. Forward-resolution mengikuti blok `exit` (classic/tp1_be/trail/early/full).
   Partial TP1 = 50%; time-stop `max_hold_bars_m1` berlaku juga untuk CLASSIC.
   Same-bar SL+TP -> SL dulu (konvensi BacktestEngine.kt).
4. Quirk aplikasi yang DIPORT SETIA: bias bullish evaluateLeg, momentumBullish <10,
   choppiness abaikan candle pertama, klausa mati hadBreakContext, avgVol nol
   (VSA dimatikan bila avg<=0 agar tidak Infinity seperti aplikasi).
5. ConfluenceEvaluator tidak di-port (sidecar display-only, tidak meng-gate BUY).
6. P0 — M5 diagregasi dari M1 (grid UTC), lalu gate MTF 4 TF memakai H1/M15/M5/M1.
   H1 atau M15 bearish memblokir LONG; minimal `mtf_min_bullish` bullish; data kurang
   dari 4 TF = fail-closed. Gate dihitung setelah Step4 agar audit tidak menyalahkan R:R.
7. P0 — veto BTC memakai candle 5M/1M yang sudah tutup dari `BTCUSDT_1m.csv` (atau
   `BTCIDR_1m.csv` untuk pair IDR). Tanpa berkas BTC, kolom veto TIDAK mengukur apa pun
   dan disebut jujur di output (`btc_source.available=false`).
8. P0 — TTL 5 candle M1 TIDAK diuji oleh replay ini: replay masuk posisi pada bar sinyal,
   sedangkan TTL mengatur berapa lama setup menunggu sebelum entry di app. Hasil trade di
   sini tidak boleh dipakai sebagai bukti manfaat TTL.

Konsistensi FEE_TRUTH v1.1 (lihat FEE_TRUTH.md):
- Fee all-in dan slippage DIPISAH di output (fee_cost_pct vs slippage_cost_pct).
- Buy/sell asimetris: memakai fee["buy_*_pct"] dan fee["sell_*_pct"] terpisah.
- Lantai friksi diterapkan: SL_gross >= min_friction_multiple × (buy_fee + sell_fee + 2×slip).
- Target gross: Target_gross = k × (SL_gross + F) + F, dengan F = total friksi.
- Trade yang kena lantai friksi ditandai sl_floor_active=True; summary melaporkan
  sl_floor_active_trades dan sl_floor_active_pct.
- Stress test slippage: jalankan --slippage-pct 0.20 dan 0.50 (FEE_TRUTH §3).
"""