#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Fungsi bersama untuk riset offline Spec Scalping Spot v3 (Tokocrypto).

Dipakai oleh:
  tools/spot_mfe.py        — hitung MFE/MAE pada dataset 15m
  tools/spot_backtest.py   — backtest Spec v3
  tools/spot_benchmark.py  — benchmark entry acak (§11.1)
  tools/spot_evidence.py   — gerbang bukti E1–E4 (§11.2)

Aturan yang dijaga di sini:
- Fee dan P&L memakai angka `FeeSchedule` (Tokocrypto USDT taker, all-in) dan
  bentuk rumus yang sama dengan `FeeSchedule.netProfitPct` / `breakevenPrice`.
  Tidak ada rumus fee kedua.
- Semua indikator memakai candle yang sudah close; tidak ada akses ke bar masa depan.
- Nama parameter mengikuti Spec v3 (`docs/exp/scalping_spot_strategy_spec.md`).
"""
import csv
import datetime as dt
import math
import os

# --- Fee Tokocrypto USDT (FeeSchedule.kt, taker all-in, persen) ---
BUY_FEE_PCT = 0.4044
SELL_FEE_PCT = 0.4044
SLIPPAGE_PCT = 0.08
# F aditif: fee beli + fee jual + slippage dua sisi. Ini angka yang dipakai
# ScalpingRiskEngine/PaperStopResolver untuk lantai SL dan gerbang spec §6.
F_ADD_PCT = BUY_FEE_PCT + SELL_FEE_PCT + 2.0 * SLIPPAGE_PCT  # 0.9688
FLOOR_MULTIPLE = 6.0
FRICTION_FLOOR_PCT = FLOOR_MULTIPLE * F_ADD_PCT  # 5.8128

# --- Parameter backtest yang tidak boleh di-hardcode di call site ---
WARMUP_CANDLES = 150
HTF_WARMUP_CANDLES = 60
TP1_FRACTION = 0.6
MIN_NET_RR_TP1 = 1.0
MIN_NET_RR_FINAL = 1.5
MIN_REWARD_OVER_FRICTION = 3.0
# G3 dicabut di Spec v3. Nilai 0 mematikan gerbang ATR; kode gerbangnya dibiarkan
# supaya laporan lama dan eksperimen tetap bisa menyalakannya.
MIN_ATR_PCT_OVER_FRICTION = 0.0
MIN_SL_ATR = 0.5
MAX_SL_ATR = 5.0
DEFAULT_EQUITY = 10_000.0
RISK_PER_TRADE_PCT = 0.5
MAX_POSITION_PCT = 25.0
MAX_CONCURRENT_POSITIONS = 2
MAX_DAILY_LOSS_PCT = 2.0
MAX_CONSECUTIVE_LOSSES = 3
COOLDOWN_AFTER_LOSS_MIN = 15
LOSS_STREAK_PAUSE_MIN = 60
MIN_NOTIONAL_USDT = 5.0

# Time-stop Spec v3 §7 dalam JAM, bukan jumlah candle. Konversinya
# max(3, ceil(jam × 60 / menit-timeframe)) supaya horizon waktunya sama di 15m dan 1h.
TIME_STOP_HOURS = {
    "PULLBACK_VWAP": 2.0,
    "BREAKOUT_RETEST": 3.0,
    "SWEEP_RECLAIM": 1.5,
    "RANGE_BOUNCE": 3.0,
    "SQUEEZE_BREAKOUT": 2.0,
}
MIN_TIME_STOP_CANDLES = 3


def now_utc():
    return dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------

def load_csv(path):
    """Baca CSV hasil tools/fetch_tokocrypto_klines.py -> list tuple terurut."""
    rows = []
    with open(path) as f:
        r = csv.reader(f)
        header = next(r, None)
        if not header:
            return rows
        for x in r:
            try:
                rows.append((int(x[0]), float(x[1]), float(x[2]), float(x[3]), float(x[4]), float(x[5])))
            except (ValueError, IndexError):
                continue
    rows.sort()
    return rows


def load_pair(data_dir, pair, interval):
    path = os.path.join(data_dir, f"{pair}_{interval}.csv")
    if not os.path.exists(path):
        return None
    return load_csv(path)


def list_pairs(data_dir):
    out = set()
    for name in os.listdir(data_dir):
        if name.endswith("_15m.csv"):
            out.add(name[: -len("_15m.csv")])
    return sorted(out)


def htf_index(exec_times, exec_minutes, htf_times, htf_minutes):
    """
    Untuk setiap candle eksekusi, indeks candle HTF terakhir yang SUDAH CLOSE.

    Candle eksekusi yang open pukul 12:15 close 12:30; candle 1h 12:00 baru close
    13:00, jadi yang boleh dipakai adalah 11:00. Aturannya:
    open_time_htf + htf_minutes <= close_exec.
    """
    import bisect
    closes = [t + exec_minutes * 60_000 for t in exec_times]
    out = []
    for c in closes:
        idx = bisect.bisect_right(htf_times, c - htf_minutes * 60_000) - 1
        out.append(idx)
    return out


def aggregate(times, opens, highs, lows, closes, volumes, bucket_minutes, input_minutes=15):
    """Agregasi candle ke bucket UTC (dipakai bila perlu HTF dari data eksekusi)."""
    step = bucket_minutes * 60_000
    out = []
    bucket = None
    for i, t in enumerate(times):
        b = t - (t % step)
        if bucket is None or b != bucket[0]:
            if bucket is not None:
                out.append(bucket)
            bucket = [b, opens[i], highs[i], lows[i], closes[i], volumes[i], 1]
        else:
            bucket[2] = max(bucket[2], highs[i])
            bucket[3] = min(bucket[3], lows[i])
            bucket[4] = closes[i]
            bucket[5] += volumes[i]
            bucket[6] += 1
    if bucket is not None:
        out.append(bucket)
    # buang bucket terakhir yang belum lengkap
    expected = max(1, bucket_minutes // input_minutes)
    if out and out[-1][6] < expected:
        out.pop()
    return [tuple(x[:6]) for x in out]


# ---------------------------------------------------------------------------
# Indikator (definisi Spec v2 §4)
# ---------------------------------------------------------------------------

def sma(values, n):
    out = [None] * len(values)
    s = 0.0
    for i, v in enumerate(values):
        s += v
        if i >= n:
            s -= values[i - n]
        if i >= n - 1:
            out[i] = s / n
    return out


def ema(values, n):
    out = [None] * len(values)
    if len(values) < n:
        return out
    seed = sum(values[:n]) / n
    out[n - 1] = seed
    alpha = 2.0 / (n + 1.0)
    prev = seed
    for i in range(n, len(values)):
        prev = alpha * values[i] + (1.0 - alpha) * prev
        out[i] = prev
    return out


def wilder_rma(values, n):
    """Rata-rata Wilder dengan seed SMA, None sampai index n-1."""
    out = [None] * len(values)
    if len(values) < n:
        return out
    s = sum(values[:n]) / n
    out[n - 1] = s
    for i in range(n, len(values)):
        s = (s * (n - 1) + values[i]) / n
        out[i] = s
    return out


def true_range(high, low, close):
    tr = [high[0] - low[0]]
    for i in range(1, len(close)):
        tr.append(max(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])))
    return tr


def atr(high, low, close, n=14):
    return wilder_rma(true_range(high, low, close), n)


def rsi(close, n=14):
    gains = [0.0]
    losses = [0.0]
    for i in range(1, len(close)):
        d = close[i] - close[i - 1]
        gains.append(max(d, 0.0))
        losses.append(max(-d, 0.0))
    ag = wilder_rma(gains, n)
    al = wilder_rma(losses, n)
    out = [None] * len(close)
    for i in range(len(close)):
        if ag[i] is None or al[i] is None:
            continue
        if al[i] == 0:
            out[i] = 100.0
        else:
            rs = ag[i] / al[i]
            out[i] = 100.0 - 100.0 / (1.0 + rs)
    return out


def adx(high, low, close, n=14):
    """ADX/+DI/-DI Wilder standar."""
    ln = len(close)
    plus_dm = [0.0] * ln
    minus_dm = [0.0] * ln
    for i in range(1, ln):
        up = high[i] - high[i - 1]
        dn = low[i - 1] - low[i]
        plus_dm[i] = up if (up > dn and up > 0) else 0.0
        minus_dm[i] = dn if (dn > up and dn > 0) else 0.0
    tr = true_range(high, low, close)
    atr_s = wilder_rma(tr, n)
    pdm_s = wilder_rma(plus_dm, n)
    mdm_s = wilder_rma(minus_dm, n)
    plus_di = [None] * ln
    minus_di = [None] * ln
    dx = [None] * ln
    for i in range(ln):
        if atr_s[i] is None or atr_s[i] == 0:
            continue
        plus_di[i] = 100.0 * pdm_s[i] / atr_s[i]
        minus_di[i] = 100.0 * mdm_s[i] / atr_s[i]
        denom = plus_di[i] + minus_di[i]
        dx[i] = 100.0 * abs(plus_di[i] - minus_di[i]) / denom if denom > 0 else 0.0
    adx_out = [None] * ln
    start = n * 2 - 2
    if start < ln:
        seed = sum(dx[n - 1:start + 1]) / n
        adx_out[start] = seed
        prev = seed
        for i in range(start + 1, ln):
            prev = (prev * (n - 1) + dx[i]) / n
            adx_out[i] = prev
    return adx_out, plus_di, minus_di


def bollinger(close, n=20, mult=2.0):
    mid = sma(close, n)
    up = [None] * len(close)
    lo = [None] * len(close)
    for i in range(len(close)):
        if mid[i] is None:
            continue
        window = close[i - n + 1:i + 1]
        mean = mid[i]
        var = sum((x - mean) ** 2 for x in window) / n
        sd = math.sqrt(var)
        up[i] = mean + mult * sd
        lo[i] = mean - mult * sd
    return up, mid, lo


def keltner(close, atr_values, n=20, mult=1.5):
    mid = ema(close, n)
    up = [None] * len(close)
    lo = [None] * len(close)
    for i in range(len(close)):
        if mid[i] is None or atr_values[i] is None:
            continue
        up[i] = mid[i] + mult * atr_values[i]
        lo[i] = mid[i] - mult * atr_values[i]
    return up, mid, lo


def vwap_daily(times, high, low, close, volume):
    """VWAP reset 00:00 UTC; bila volume hari itu nol, pakai nilai sebelumnya."""
    out = [None] * len(times)
    day = None
    pv = 0.0
    vv = 0.0
    prev = None
    for i, t in enumerate(times):
        d = t // 86_400_000
        if d != day:
            day = d
            pv = 0.0
            vv = 0.0
        tp = (high[i] + low[i] + close[i]) / 3.0
        pv += tp * volume[i]
        vv += volume[i]
        if vv > 0:
            prev = pv / vv
        out[i] = prev
    return out


def prior_sma(values, n):
    """SMA dari n nilai SEBELUM i (tidak termasuk candle i)."""
    out = [None] * len(values)
    s = 0.0
    for i, v in enumerate(values):
        if i >= n:
            out[i] = s / n
            s -= values[i - n]
        s += v
    return out


def percentile_rank_series(values, n=100):
    """Persentase nilai dalam n candle terakhir yang <= nilai saat ini."""
    out = [None] * len(values)
    import bisect
    window = []
    for i, v in enumerate(values):
        if v is None:
            continue
        bisect.insort(window, v)
        if len(window) > n:
            window.pop(0)
        idx = bisect.bisect_right(window, v)
        out[i] = 100.0 * idx / len(window)
    return out


def candle_stats(o, h, l, c, atr_i):
    rng = h - l
    body = abs(c - o)
    lower_wick = min(o, c) - l
    close_pos = 0.5 if rng == 0 else (c - l) / rng
    rejection = lower_wick >= 1.5 * max(body, 0.1 * atr_i) and close_pos >= 0.6
    return rng, body, lower_wick, close_pos, rejection


def is_bull_engulf(o, c, o_prev, c_prev):
    return c > o and c_prev < o_prev and o <= c_prev and c >= o_prev


def is_confirm_candle(o, h, l, c, atr_i, o_prev, c_prev):
    rng, body, lw, cp, rejection = candle_stats(o, h, l, c, atr_i)
    return c >= o and (rejection or is_bull_engulf(o, c, o_prev, c_prev))


# ---------------------------------------------------------------------------
# Swing / level
# ---------------------------------------------------------------------------

def find_swings(high, low, left, right):
    """
    Kembalikan daftar (confirmed_index, price, 'H'/'L').
    Swing high: high[k] > high kiri dan >= high kanan.
    """
    out = []
    for k in range(left, len(high) - right):
        h = high[k]
        ok = True
        for j in range(k - left, k):
            if high[j] >= h:
                ok = False
                break
        if ok:
            for j in range(k + 1, k + right + 1):
                if high[j] > h:
                    ok = False
                    break
        if ok:
            out.append((k + right, h, "H"))
        l = low[k]
        ok = True
        for j in range(k - left, k):
            if low[j] <= l:
                ok = False
                break
        if ok:
            for j in range(k + 1, k + right + 1):
                if low[j] < l:
                    ok = False
                    break
        if ok:
            out.append((k + right, l, "L"))
    out.sort()
    return out


def last_swing_before(swings, i, kind):
    best = None
    for confirmed, price, k in swings:
        if confirmed > i:
            break
        if k == kind:
            best = (confirmed, price)
    return best


def swings_before(swings, i, kind, lookback_idx):
    out = []
    for confirmed, price, k in swings:
        if confirmed > i:
            break
        if k == kind and confirmed >= lookback_idx:
            out.append((confirmed, price))
    return out


def cluster_levels(items, tol, min_touches):
    """Cluster harga yang berdekatan; kembalikan [(level, touches)]."""
    if not items:
        return []
    vals = sorted(p for _, p in items)
    clusters = []
    cur = [vals[0]]
    for v in vals[1:]:
        if v - cur[-1] <= tol:
            cur.append(v)
        else:
            clusters.append(cur)
            cur = [v]
    clusters.append(cur)
    return [(sum(c) / len(c), len(c)) for c in clusters if len(c) >= min_touches]


def session_levels(times, high, low):
    """PDH/PDL hari UTC sebelumnya dan Asia low (00:00-08:00 UTC hari ini)."""
    n = len(times)
    pdh = [None] * n
    pdl = [None] * n
    asia = [None] * n
    by_day = {}
    for i, t in enumerate(times):
        d = t // 86_400_000
        by_day.setdefault(d, []).append(i)
    prev_day = None
    current_asia = None
    for d in sorted(by_day):
        idxs = by_day[d]
        if prev_day is not None:
            hh = max(high[i] for i in idxs)
            ll = min(low[i] for i in idxs)
            for i in idxs:
                pdh[i] = hh
                pdl[i] = ll
        # Asia low: low 00:00-08:00 UTC, valid setelah 08:00
        asia_idxs = [i for i in idxs if (times[i] % 86_400_000) < 8 * 3600 * 1000]
        al = min((low[i] for i in asia_idxs), default=None)
        for i in idxs:
            if (times[i] % 86_400_000) >= 8 * 3600 * 1000:
                asia[i] = al
        prev_day = d
    return pdh, pdl, asia


# ---------------------------------------------------------------------------
# Fee / P&L (mirror FeeSchedule.kt)
# ---------------------------------------------------------------------------

def net_profit_pct(entry, price, buy_fee=BUY_FEE_PCT, sell_fee=SELL_FEE_PCT,
                   slippage=SLIPPAGE_PCT):
    """Persis FeeSchedule.netProfitPct: bentuk aditif, bukan multiplikatif."""
    if entry <= 0 or price <= 0:
        return 0.0
    buy_cost = 1.0 + (buy_fee + slippage) / 100.0
    sell_net = max(0.0, 1.0 - (sell_fee + slippage) / 100.0)
    return ((price / entry) * sell_net / buy_cost - 1.0) * 100.0


def breakeven_price(entry, buy_fee=BUY_FEE_PCT, sell_fee=SELL_FEE_PCT,
                    slippage=SLIPPAGE_PCT):
    """Persis FeeSchedule.breakevenPrice."""
    if entry <= 0:
        return 0.0
    buy_cost = 1.0 + (buy_fee + slippage) / 100.0
    sell_net = max(1e-9, 1.0 - (sell_fee + slippage) / 100.0)
    return entry * buy_cost / sell_net


def cost_basis(quantity, entry):
    return quantity * entry * (1.0 + (BUY_FEE_PCT + SLIPPAGE_PCT) / 100.0)


def net_sell_value(quantity, price):
    return quantity * price * max(0.0, 1.0 - (SELL_FEE_PCT + SLIPPAGE_PCT) / 100.0)


def friction_floor_price(entry):
    """Harga SL minimum dari lantai friksi (PaperStopResolver.frictionFloorPct)."""
    return entry * (1.0 - FRICTION_FLOOR_PCT / 100.0)


def friction_floor_pct(min_friction_multiple=FLOOR_MULTIPLE):
    """Mirror `PaperStopResolver.frictionFloorPct` (persen, bukan fraksi)."""
    if not math.isfinite(min_friction_multiple) or min_friction_multiple <= 0:
        return 0.0
    floor = min_friction_multiple * F_ADD_PCT
    return floor if math.isfinite(floor) and 0.0 < floor < 100.0 else 0.0


def resolve_stop(signal_sl, entry, floor_pct):
    """
    Mirror `PaperStopResolver.resolve` -> (harga, sumber).

    Sumber: "SIGNAL" bila SL sinyal valid dan sudah melewati lantai,
    "FRICTION_FLOOR" bila SL sinyal tidak ada/terlalu rapat, "NONE" bila entry invalid.
    Lantai hanya melebarkan, tidak pernah menyempitkan.
    """
    if not math.isfinite(entry) or entry <= 0:
        return 0.0, "NONE"
    floor_active = math.isfinite(floor_pct) and 0.0 < floor_pct < 100.0
    signal_valid = (signal_sl is not None and math.isfinite(signal_sl)
                    and 0.0 < signal_sl < entry)
    if not floor_active:
        return (signal_sl, "SIGNAL") if signal_valid else (0.0, "NONE")
    floor_price = entry * (1.0 - floor_pct / 100.0)
    if signal_valid and signal_sl <= floor_price:
        return signal_sl, "SIGNAL"
    return floor_price, "FRICTION_FLOOR"


def tp1_floor_pct(risk_pct, f_pct=F_ADD_PCT, min_net_rr_tp1=MIN_NET_RR_TP1):
    """
    Spec v3 §6.2: `tp1FloorPct = F + minNetRrTp1 × (riskPct + F)`.

    Jangan diganti `entry + 1,0R`: secara aljabar selalu menghasilkan
    `netRr1 = (R − F)/(R + F) < 1`, sehingga G4 menolaknya.
    """
    return f_pct + min_net_rr_tp1 * (risk_pct + f_pct)


def tp1_floor_price(entry, risk_pct, f_pct=F_ADD_PCT, min_net_rr_tp1=MIN_NET_RR_TP1):
    return entry * (1.0 + tp1_floor_pct(risk_pct, f_pct, min_net_rr_tp1) / 100.0)


def per_unit_risk_net(entry, sl_eff):
    """
    Risiko per unit bersih menurut `FeeSchedule` (Spec v3 §6.4):

        costBasisNet(1, entry) − netSellValue(1, slEff)

    Pendekatan aditif `(entry − slEff) + entry × F / 100` sengaja TIDAK dipakai:
    selisihnya ±0,4% dan membuat qty serta R tidak sebanding dengan app.
    """
    if entry <= 0 or sl_eff <= 0 or sl_eff >= entry:
        return 0.0
    return cost_basis(1.0, entry) - net_sell_value(1.0, sl_eff)


def time_stop_candles(time_stop_hours, tf_minutes):
    """Spec v3 §7: max(3, ceil(jam × 60 / menit-timeframe))."""
    if time_stop_hours is None or tf_minutes <= 0:
        return MIN_TIME_STOP_CANDLES
    return max(MIN_TIME_STOP_CANDLES, int(math.ceil(time_stop_hours * 60.0 / tf_minutes)))


def candles_for_minutes(minutes, tf_minutes):
    """Cooldown/pause dalam menit -> candle, minimal 1 (Spec v3 §9)."""
    if tf_minutes <= 0:
        return 1
    return max(1, int(math.ceil(minutes / float(tf_minutes))))


# ---------------------------------------------------------------------------
# Konfigurasi
# ---------------------------------------------------------------------------

DEFAULT_SETUP_PARAMS = {
    "PULLBACK_VWAP": dict(impulseLookback=25, minImpulseAtr=2.0, minImpulseVolRatio=1.2,
                          pullbackVolMaxRatio=0.8, touchTolAtr=0.15, rsiFloor=40,
                          maxChaseAtr=1.2, slBufferAtr=0.3, entryValidCandles=2,
                          trailMode="EMA9", timeStopHours=TIME_STOP_HOURS["PULLBACK_VWAP"]),
    "BREAKOUT_RETEST": dict(levelLookback=80, swingFractal=3, levelTolAtr=0.2, minTouches=2,
                            breakoutVolRatio=1.5, breakoutBodyAtr=0.6, breakoutClosePos=0.7,
                            maxExtensionAtr=2.5, retestMaxCandles=10, retestTouchTolAtr=0.15,
                            retestFailAtr=0.1, retestVolMaxRatio=0.7, slBufferAtr=0.5,
                            entryValidCandles=2, trailMode="HIGHER_LOW",
                            timeStopHours=TIME_STOP_HOURS["BREAKOUT_RETEST"]),
    "SWEEP_RECLAIM": dict(liqLookback=100, swingFractal=5, equalTolAtr=0.15,
                          levelMinAgeCandles=10, sweepMinDepthAtr=0.05, sweepMaxDepthAtr=1.5,
                          reclaimMaxCandles=3, sweepWickRatio=0.5, volSpikeRatio=1.5,
                          entryGuardAtr=1.0, slBufferAtr=0.2, minScore=3,
                          failedReclaimCandles=3, trailMode="CHANDELIER",
                          timeStopHours=TIME_STOP_HOURS["SWEEP_RECLAIM"]),
    "RANGE_BOUNCE": dict(rangeLookback=60, minTouchesPerSide=2, sideTolAtr=0.2,
                         minRangeHeightPct=max(0.6, 3.0 * F_ADD_PCT), adxMax=20.0,
                         bottomZonePct=0.2, rsiOversold=35.0, slBufferAtr=0.7,
                         entryValidCandles=2, trailMode="NONE",
                         timeStopHours=TIME_STOP_HOURS["RANGE_BOUNCE"]),
    "SQUEEZE_BREAKOUT": dict(minSqueezeCandles=6, bwPercentileMax=20.0, atrRatioMax=0.8,
                             breakoutVolRatio=1.5, maxBreakoutRangeAtr=2.0,
                             marketEntryMaxAtr=0.6, slMode="BOX_MID", trailMode="EMA9",
                             timeStopHours=TIME_STOP_HOURS["SQUEEZE_BREAKOUT"]),
}
