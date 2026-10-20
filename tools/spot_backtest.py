#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Backtest offline Spec Scalping Spot v2 (Tokocrypto, long-only, taker-only).

Harness ini adalah alat RISET, bukan mesin produksi. Mesin produksi tetap Kotlin
(`agu.analys.engine.spot`). Sebelum integrasi, hasil harness ini wajib diparity-kan
dengan fixture Kotlin (Spec v2 §11).

Yang diimplementasikan:
- 5 setup: PULLBACK_VWAP, BREAKOUT_RETEST, SWEEP_RECLAIM, RANGE_BOUNCE, SQUEEZE_BREAKOUT.
- Gerbang G0-G8 (§6), lantai friksi 6x (PaperStopResolver), position sizing (§6.3).
- Exit §7: SL/TP1/TP2 intrabar, SL-before-TP, TP2 setelah candle TP1, break-even,
  trailing, time-stop, invalidasi setup, momentum fail, regime change.
- Funnel per gerbang, metrik per setup/pair, split 70/30.
- Fee/P&L lewat rumus `FeeSchedule` (bentuk aditif yang sama).

Batas yang disengaja (dilaporkan, bukan disembunyikan):
- Harga fill = level mentah (trigger/SL/TP). Slippage masuk lewat rumus FeeSchedule,
  sama seperti app, jadi tidak dihitung dua kali.
- Risk manager berjalan per simbol (cooldown, 3 loss beruntun, batas rugi harian).
  Batas posisi bersamaan lintas simbol tidak disimulasikan karena tiap pair dijalankan
  terpisah; agregat P&L mengasumsikan modal tak terbatas.
- Tick size memakai 1e-5 x harga (SymbolRules offline belum tersedia).
- Spread (G8) tidak tersedia di data candle; gerbang dilewati dan dicatat.

Contoh:
  python3 tools/spot_backtest.py --data data/tokocrypto_spot \
      --out data/hasil/spot_backtest_v2 --setups all --tp1-policy floor
"""
import argparse
import csv
import json
import math
import os
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import spot_lib as lib  # noqa: E402

REGIMES = ("TREND_UP", "TREND_DOWN", "RANGE", "COMPRESSION", "UNDEFINED")
SETUPS = ("PULLBACK_VWAP", "BREAKOUT_RETEST", "SWEEP_RECLAIM", "RANGE_BOUNCE", "SQUEEZE_BREAKOUT")
ALLOWED_REGIMES = {
    "PULLBACK_VWAP": {"TREND_UP"},
    "BREAKOUT_RETEST": {"TREND_UP", "RANGE", "COMPRESSION", "UNDEFINED"},
    "SWEEP_RECLAIM": {"RANGE", "TREND_UP", "UNDEFINED"},
    "RANGE_BOUNCE": {"RANGE"},
    "SQUEEZE_BREAKOUT": {"COMPRESSION"},
}
GATE_ORDER = ("SIGNAL", "PAIR_EDGE", "FRICTION_FLOOR", "SL_ATR_RANGE", "ATR_TOO_LOW",
              "NET_RR_TP1", "NET_RR_FINAL", "REWARD_OVER_FRICTION", "INVALID_LEVELS",
              "SPREAD", "SIZING", "RISK_MANAGER")


def tick_for(price):
    return max(1e-8, price * 1e-5)


def median_or_none(values):
    vals = [v for v in values if v is not None]
    return st.median(vals) if vals else None


class Ctx:
    """Satu simbol: data 15m + HTF 1h + indikator + level."""

    def __init__(self, pair, rows, htf_rows, btc_ret_by_time, exec_minutes=15, htf_minutes=60):
        self.pair = pair
        self.t = [r[0] for r in rows]
        self.o = [r[1] for r in rows]
        self.h = [r[2] for r in rows]
        self.l = [r[3] for r in rows]
        self.c = [r[4] for r in rows]
        self.v = [r[5] for r in rows]
        self.n = len(rows)
        self.h1_t = [r[0] for r in htf_rows]
        self.h1_c = [r[4] for r in htf_rows]

        self.atr = lib.atr(self.h, self.l, self.c, 14)
        self.atr20 = lib.atr(self.h, self.l, self.c, 20)
        self.ema9 = lib.ema(self.c, 9)
        self.ema21 = lib.ema(self.c, 21)
        self.ema50 = lib.ema(self.c, 50)
        self.rsi = lib.rsi(self.c, 14)
        self.adx, self.pdi, self.mdi = lib.adx(self.h, self.l, self.c, 14)
        self.bb_up, self.bb_mid, self.bb_lo = lib.bollinger(self.c, 20, 2.0)
        self.kc_up, self.kc_mid, self.kc_lo = lib.keltner(self.c, self.atr20, 20, 1.5)
        self.vwap = lib.vwap_daily(self.t, self.h, self.l, self.c, self.v)
        self.volma = lib.prior_sma(self.v, 20)
        self.volratio = [None] * self.n
        for i in range(self.n):
            if self.volma[i] and self.volma[i] > 0:
                self.volratio[i] = self.v[i] / self.volma[i]
        self.bw = [None] * self.n
        for i in range(self.n):
            if self.bb_up[i] and self.bb_lo[i] and self.bb_mid[i]:
                self.bw[i] = (self.bb_up[i] - self.bb_lo[i]) / self.bb_mid[i]
        self.bw_pct = lib.percentile_rank_series(self.bw, 100)
        self.atr_sma50 = lib.sma([x if x is not None else 0.0 for x in self.atr], 50)
        self.atr_ratio = [None] * self.n
        for i in range(self.n):
            if self.atr[i] and self.atr_sma50[i] and self.atr_sma50[i] > 0:
                self.atr_ratio[i] = self.atr[i] / self.atr_sma50[i]
        self.squeeze = [False] * self.n
        for i in range(self.n):
            if self.bb_up[i] is not None and self.kc_up[i] is not None:
                self.squeeze[i] = self.bb_up[i] < self.kc_up[i] and self.bb_lo[i] > self.kc_lo[i]
        self.squeeze_run = [0] * self.n
        for i in range(1, self.n):
            self.squeeze_run[i] = self.squeeze_run[i - 1] + 1 if self.squeeze[i] else 0

        self.sw3 = lib.find_swings(self.h, self.l, 3, 3)
        self.sw2 = lib.find_swings(self.h, self.l, 2, 2)
        self.sw5 = lib.find_swings(self.h, self.l, 5, 5)
        self.pdh, self.pdl, self.asia = lib.session_levels(self.t, self.h, self.l)

        self.h_idx = lib.htf_index(self.t, exec_minutes, self.h1_t, htf_minutes)
        self.h_ema9 = lib.ema(self.h1_c, 9)
        self.h_ema21 = lib.ema(self.h1_c, 21)
        self.btc_ret = [None] * self.n
        for i, t in enumerate(self.h_idx):
            if t >= 0:
                self.btc_ret[i] = btc_ret_by_time.get(self.h1_t[t])
        self.regime = [self._classify(i) for i in range(self.n)]

    # --- regime (§5) ---
    def _classify(self, i):
        adx_i = self.adx[i]
        if adx_i is None or self.pdi[i] is None or self.ema9[i] is None or self.vwap[i] is None:
            return "UNDEFINED"
        close, vwap = self.c[i], self.vwap[i]
        if adx_i >= 25 and self.mdi[i] > self.pdi[i] and close < vwap and self.ema9[i] < self.ema21[i]:
            return "TREND_DOWN"
        if self.squeeze[i] or (i > 0 and self.squeeze[i - 1]):
            return "COMPRESSION"
        if (adx_i >= 20 and self.pdi[i] > self.mdi[i] and close > vwap
                and self.ema9[i] > self.ema21[i] and i >= 3 and self.ema21[i - 3] is not None
                and self.ema21[i] > self.ema21[i - 3]):
            return "TREND_UP"
        window = [x for x in self.adx[max(0, i - 9):i + 1] if x is not None]
        if window and sum(window) / len(window) < 20:
            return "RANGE"
        return "UNDEFINED"

    def htf_up(self, i):
        t = self.h_idx[i]
        if t < 21 or self.h_ema9[t] is None or self.h_ema21[t] is None:
            return False
        return self.h_ema9[t] > self.h_ema21[t] and self.h1_c[t] > self.h_ema21[t]

    def confirm(self, i):
        if i < 1 or self.atr[i] is None:
            return False
        return lib.is_confirm_candle(self.o[i], self.h[i], self.l[i], self.c[i],
                                     self.atr[i], self.o[i - 1], self.c[i - 1])

    # --- §8.1 PULLBACK_VWAP ---
    def setup_pullback(self, i, p):
        if self.regime[i] != "TREND_UP" or not self.htf_up(i):
            return None
        atr = self.atr[i]
        if not atr or i < p["impulseLookback"] + 22 or self.rsi[i] is None:
            return None
        look = p["impulseLookback"]
        ih = max(range(i - look + 1, i + 1), key=lambda k: self.h[k])
        if ih > i - 2 or ih < 21:
            return None
        il = min(range(ih - 20, ih), key=lambda k: self.l[k])
        impulse_size = self.h[ih] - self.l[il]
        if impulse_size < p["minImpulseAtr"] * atr:
            return None
        avg_imp_vol = sum(self.v[il:ih + 1]) / (ih - il + 1)
        if self.volma[i] is None or avg_imp_vol < p["minImpulseVolRatio"] * self.volma[i]:
            return None
        if i - ih < 2:
            return None
        avg_pull_vol = sum(self.v[ih + 1:i + 1]) / (i - ih)
        if avg_pull_vol > p["pullbackVolMaxRatio"] * avg_imp_vol:
            return None
        for k in range(ih + 1, i + 1):
            if self.vwap[k] is not None and self.c[k] < self.vwap[k] - 0.3 * atr:
                return None
        if self.vwap[i] is None:
            return None
        if not (self.l[i] <= self.vwap[i] + p["touchTolAtr"] * atr and self.c[i] > self.vwap[i]):
            return None
        if not self.confirm(i) or self.volratio[i] is None or self.volratio[i] < 1.0:
            return None
        rsi_pull = [self.rsi[k] for k in range(ih + 1, i + 1) if self.rsi[k] is not None]
        if self.rsi[i] < p["rsiFloor"] or not rsi_pull or min(rsi_pull) < 35:
            return None
        entry = self.h[i] + tick_for(self.c[i])
        if entry - self.vwap[i] > p["maxChaseAtr"] * atr:
            return None
        pullback_low = min(self.l[ih:i + 1])
        sl = pullback_low - p["slBufferAtr"] * atr
        r_struct = entry - sl
        if r_struct <= 0:
            return None
        tp1 = self.h[ih]
        if tp1 < entry + r_struct:
            return None
        tp2 = self.h[ih] + 0.5 * impulse_size
        score = 0
        if self.volratio[i] >= 1.5:
            score += 1
        if self.ema21[i] is not None and abs(self.ema21[i] - self.vwap[i]) <= 0.3 * atr:
            score += 1
        return {
            "setup": "PULLBACK_VWAP", "entry_mode": "CONFIRM_BREAK", "entry": entry,
            "cancel_below": pullback_low, "valid": p["entryValidCandles"], "sl": sl,
            "tp1": tp1, "tp2": tp2, "tp1_source": "structural", "trail": p["trailMode"],
            "time_stop": p["timeStopCandles"], "score": score, "atr": atr,
            "data": {"vwap_signal": self.vwap[i], "signal_index": i},
        }

    # --- §8.2 BREAKOUT_RETEST ---
    def setup_breakout(self, i, p):
        if self.regime[i] == "TREND_DOWN" or not self.confirm(i):
            return None
        atr = self.atr[i]
        if not atr or i < p["levelLookback"] + 45:
            return None
        tol = p["levelTolAtr"] * atr
        highs = lib.swings_before(self.sw3, i, "H", i - p["levelLookback"])
        levels = [lvl for lvl, _ in lib.cluster_levels(highs, tol, p["minTouches"])]
        if self.pdh[i]:
            levels.append(self.pdh[i])
        levels = sorted({round(x, 10) for x in levels}, reverse=True)
        best = None
        for L in levels:
            if not (self.l[i] <= L + p["retestTouchTolAtr"] * atr and self.c[i] > L):
                continue
            for b in range(max(i - p["retestMaxCandles"], 45), i):
                atb = self.atr[b]
                if not atb:
                    continue
                if self.c[b] <= L + 0.1 * atb:
                    continue
                if self.volratio[b] is None or self.volratio[b] < p["breakoutVolRatio"]:
                    continue
                rng_b, body_b, _, cp_b, _ = lib.candle_stats(self.o[b], self.h[b], self.l[b],
                                                             self.c[b], atb)
                if body_b < p["breakoutBodyAtr"] * atb or cp_b < p["breakoutClosePos"]:
                    continue
                if self.c[b] - L > p["maxExtensionAtr"] * atb:
                    continue
                if self.c[b - 1] > L + p["levelTolAtr"] * self.atr[b - 1]:
                    continue
                ok = True
                for k in range(b + 1, i + 1):
                    if self.c[k] < L - p["retestFailAtr"] * self.atr[k]:
                        ok = False
                        break
                if not ok:
                    continue
                vols = self.v[b + 1:i + 1]
                if not vols or (sum(vols) / len(vols)) > p["retestVolMaxRatio"] * self.v[b]:
                    continue
                retest_low = min(self.l[b + 1:i + 1])
                sl = min(retest_low, L) - p["slBufferAtr"] * atr
                entry = self.h[i] + tick_for(self.c[i])
                if entry - L > 1.5 * atr:
                    continue
                r_struct = entry - sl
                if r_struct <= 0:
                    continue
                range_low = min(self.l[b - 40:b])
                height = L - range_low
                tp1_raw = entry + r_struct
                tp2_raw = L + height if height > 0 else None
                cand = {
                    "setup": "BREAKOUT_RETEST", "entry_mode": "CONFIRM_BREAK", "entry": entry,
                    "cancel_below": L - 0.1 * atr, "valid": p["entryValidCandles"], "sl": sl,
                    "tp1": tp1_raw, "tp2": tp2_raw, "tp1_source": "formula",
                    "trail": p["trailMode"], "time_stop": p["timeStopCandles"],
                    "score": 0, "atr": atr,
                    "data": {"level": L, "signal_index": i, "breakout_index": b,
                             "retest_low": retest_low},
                }
                if best is None or entry - L < best["entry"] - best["data"]["level"]:
                    best = cand
        return best

    # --- §8.3 SWEEP_RECLAIM ---
    def setup_sweep(self, i, p):
        if self.regime[i] not in ALLOWED_REGIMES["SWEEP_RECLAIM"]:
            return None
        if self.pair != "BTCUSDT" and self.btc_ret[i] is not None and self.btc_ret[i] <= -1.5:
            return None
        atr = self.atr[i]
        if not atr or i < p["liqLookback"] + 10 or self.vwap[i] is None or self.rsi[i] is None:
            return None
        lows = lib.swings_before(self.sw5, i, "L", i - p["liqLookback"])
        usable = [(c, pr) for c, pr in lows if i - c >= p["levelMinAgeCandles"]]
        candidates = [(pr, "SWING", c) for c, pr in usable]
        for lvl, _ in lib.cluster_levels(usable, p["equalTolAtr"] * atr, 2):
            candidates.append((lvl, "EQUAL", None))
        if self.pdl[i]:
            candidates.append((self.pdl[i], "PDL", None))
        if self.asia[i]:
            candidates.append((self.asia[i], "ASIA", None))
        below = [(pr, kind, c) for pr, kind, c in candidates if pr < self.c[i]]
        if not below:
            return None
        L, level_kind, _ = max(below, key=lambda x: x[0])
        sweep_j = None
        for j in range(max(0, i - p["reclaimMaxCandles"] + 1), i + 1):
            atj = self.atr[j]
            if not atj:
                continue
            if not (self.l[j] < L - p["sweepMinDepthAtr"] * atj):
                continue
            if self.l[j] < L - p["sweepMaxDepthAtr"] * atj:
                continue
            reclaim = None
            for k in range(j, min(j + p["reclaimMaxCandles"], i + 1)):
                if self.c[k] > L:
                    reclaim = k
                    break
            if reclaim != i:
                continue
            if j == i:
                rng = self.h[j] - self.l[j]
                if rng <= 0 or (min(self.o[j], self.c[j]) - self.l[j]) / rng < p["sweepWickRatio"]:
                    continue
            sweep_j = j
            break
        if sweep_j is None:
            return None
        j = sweep_j
        score = 0
        if self.volratio[j] is not None and self.volratio[j] >= p["volSpikeRatio"]:
            score += 1
        prev_low = lib.last_swing_before(self.sw5, j - 1, "L")
        if prev_low and prev_low[1] > self.l[j] and self.rsi[j] is not None and self.rsi[prev_low[0]] is not None:
            if self.rsi[j] > self.rsi[prev_low[0]]:
                score += 1
        if self.c[i] > self.vwap[i] or abs(self.vwap[i] - L) <= atr:
            score += 1
        if level_kind in ("PDL", "ASIA", "EQUAL"):
            score += 1
        if self.confirm(i):
            score += 1
        if score < p["minScore"]:
            return None
        entry = self.c[i]
        if entry - L > p["entryGuardAtr"] * atr:
            return None
        sweep_low = min(self.l[j:i + 1])
        sl = sweep_low - p["slBufferAtr"] * atr
        r_struct = entry - sl
        if r_struct <= 0:
            return None
        range_high = max(self.h[max(0, i - 59):i + 1])
        range_low = min(self.l[max(0, i - 59):i + 1])
        cands = [self.vwap[i], (range_high + range_low) / 2.0, range_high]
        eq_highs = lib.swings_before(self.sw5, i, "H", i - p["liqLookback"])
        for lvl, _ in lib.cluster_levels(eq_highs, p["equalTolAtr"] * atr, 2):
            cands.append(lvl)
        cands = sorted({round(x, 10) for x in cands if x > entry})
        tp1 = next((x for x in cands if x >= entry + r_struct), None)
        if tp1 is None:
            return None
        tp2 = next((x for x in cands if x > tp1), None)
        return {
            "setup": "SWEEP_RECLAIM", "entry_mode": "MARKET_NEXT_OPEN", "entry": entry,
            "cancel_below": None, "valid": 1, "sl": sl, "tp1": tp1, "tp2": tp2,
            "tp1_source": "structural", "trail": p["trailMode"],
            "time_stop": p["timeStopCandles"], "score": score, "atr": atr,
            "data": {"level": L, "level_kind": level_kind, "signal_index": i,
                     "sweep_index": j},
        }

    # --- §8.4 RANGE_BOUNCE ---
    def setup_range(self, i, p):
        if self.regime[i] != "RANGE":
            return None
        atr = self.atr[i]
        if not atr or i < p["rangeLookback"] + 10 or self.bb_lo[i] is None:
            return None
        highs = lib.swings_before(self.sw3, i, "H", i - p["rangeLookback"])
        lows = lib.swings_before(self.sw3, i, "L", i - p["rangeLookback"])
        hc = lib.cluster_levels(highs, p["sideTolAtr"] * atr, p["minTouchesPerSide"])
        lc = lib.cluster_levels(lows, p["sideTolAtr"] * atr, p["minTouchesPerSide"])
        if not hc or not lc:
            return None
        range_high = max(lvl for lvl, _ in hc)
        range_low = min(lvl for lvl, _ in lc)
        if range_high <= range_low:
            return None
        if (range_high - range_low) / self.c[i] * 100.0 < p["minRangeHeightPct"]:
            return None
        outside = 0
        for k in range(i - p["rangeLookback"] + 1, i + 1):
            atk = self.atr[k]
            if not atk:
                continue
            if self.c[k] < range_low - 0.3 * atk or self.c[k] > range_high + 0.3 * atk:
                outside += 1
        if outside > 1:
            return None
        pos = (self.c[i] - range_low) / (range_high - range_low)
        if pos > p["bottomZonePct"]:
            return None
        trig_touch = any(self.bb_lo[k] is not None and self.l[k] <= self.bb_lo[k]
                         for k in range(max(0, i - 2), i + 1))
        rsi3 = [self.rsi[k] for k in range(max(0, i - 2), i + 1) if self.rsi[k] is not None]
        if not trig_touch or not rsi3 or min(rsi3) > p["rsiOversold"]:
            return None
        if not self.confirm(i) or self.c[i] <= self.bb_lo[i]:
            return None
        if self.volratio[i] is None or self.volratio[i] < 1.0:
            return None
        entry = self.h[i] + tick_for(self.c[i])
        sl = min(range_low - p["slBufferAtr"] * atr, self.l[i] - 0.2 * atr)
        r_struct = entry - sl
        if r_struct <= 0:
            return None
        mid = (range_high + range_low) / 2.0
        if mid < entry + r_struct:
            return None
        tp2 = range_high - 0.15 * atr
        return {
            "setup": "RANGE_BOUNCE", "entry_mode": "CONFIRM_BREAK", "entry": entry,
            "cancel_below": range_low - 0.2 * atr, "valid": p["entryValidCandles"], "sl": sl,
            "tp1": mid, "tp2": tp2, "tp1_source": "structural", "trail": p["trailMode"],
            "time_stop": p["timeStopCandles"], "score": 0, "atr": atr,
            "data": {"range_low": range_low, "range_high": range_high, "signal_index": i},
        }

    # --- §8.5 SQUEEZE_BREAKOUT ---
    def setup_squeeze(self, i, p):
        if i < 2 or self.atr[i] is None or self.atr[i - 1] is None:
            return None
        run = self.squeeze_run[i - 1]
        if run < p["minSqueezeCandles"]:
            return None
        if self.bw_pct[i - 1] is None or self.bw_pct[i - 1] > p["bwPercentileMax"]:
            return None
        if self.atr_ratio[i - 1] is None or self.atr_ratio[i - 1] > p["atrRatioMax"]:
            return None
        start = i - run
        comp_high = max(self.h[start:i])
        comp_low = min(self.l[start:i])
        box = comp_high - comp_low
        if box <= 0 or self.vwap[i] is None or self.ema9[i] is None or self.bb_up[i] is None:
            return None
        if not (self.c[i] > self.vwap[i] and self.ema9[i] >= self.ema21[i]):
            return None
        if self.c[i] <= comp_high or self.c[i] < self.bb_up[i]:
            return None
        if self.volratio[i] is None or self.volratio[i] < p["breakoutVolRatio"]:
            return None
        rng = self.h[i] - self.l[i]
        if rng > p["maxBreakoutRangeAtr"] * self.atr[i - 1]:
            return None
        cp = 0.5 if rng == 0 else (self.c[i] - self.l[i]) / rng
        if cp < 0.6:
            return None
        if self.c[i] - comp_high > p["marketEntryMaxAtr"] * self.atr[i]:
            return None
        entry = self.c[i]
        sl = (comp_high + comp_low) / 2.0 - 0.2 * self.atr[i]
        r_struct = entry - sl
        if r_struct <= 0:
            return None
        tp1_raw = entry + r_struct
        tp2_raw = comp_high + 1.5 * box
        return {
            "setup": "SQUEEZE_BREAKOUT", "entry_mode": "MARKET_NEXT_OPEN", "entry": entry,
            "cancel_below": None, "valid": 1, "sl": sl, "tp1": tp1_raw, "tp2": tp2_raw,
            "tp1_source": "formula", "trail": p["trailMode"],
            "time_stop": p["timeStopCandles"], "score": 0, "atr": self.atr[i],
            "data": {"comp_high": comp_high, "comp_low": comp_low, "signal_index": i},
        }

    def evaluate(self, i, setup, params):
        return {
            "PULLBACK_VWAP": self.setup_pullback,
            "BREAKOUT_RETEST": self.setup_breakout,
            "SWEEP_RECLAIM": self.setup_sweep,
            "RANGE_BOUNCE": self.setup_range,
            "SQUEEZE_BREAKOUT": self.setup_squeeze,
        }[setup](i, params[setup])


def net_rr(reward_pct, risk_pct):
    denom = risk_pct + lib.F_ADD_PCT
    if denom <= 0:
        return -99.0
    return (reward_pct - lib.F_ADD_PCT) / denom


def finalize_signal(ctx, i, sig, opts):
    """G1-G8 + sizing. Mengembalikan (sig_final, None) atau (None, alasan)."""
    entry = sig["entry"]
    atr = ctx.atr[i]
    if not atr or atr <= 0 or entry <= 0:
        return None, "SL_ATR_RANGE"
    g = opts["gates"]
    floor = entry * (1.0 - g["friction_multiple"] * lib.F_ADD_PCT / 100.0) \
        if g["friction_multiple"] > 0 else 0.0
    sl_eff = min(sig["sl"], floor) if floor > 0 else sig["sl"]  # lantai hanya melebarkan
    risk_pct = (entry - sl_eff) / entry * 100.0
    if risk_pct <= 0:
        return None, "FRICTION_FLOOR"
    sl_atr = (entry - sl_eff) / atr
    if sl_atr < lib.MIN_SL_ATR or sl_atr > g["max_sl_atr"]:
        return None, "SL_ATR_RANGE"
    atr_pct = atr / entry * 100.0
    min_atr_pct = max(g["min_atr_friction"] * lib.F_ADD_PCT,
                      (g["friction_multiple"] * lib.F_ADD_PCT / g["max_sl_atr"]
                       if g["friction_multiple"] > 0 else 0.0))
    if atr_pct < min_atr_pct:
        return None, "ATR_TOO_LOW"

    tp1 = sig["tp1"]
    if sig["tp1_source"] == "formula" and opts["tp1_policy"] == "floor":
        tp1_min = entry * (1.0 + (risk_pct + 2.0 * lib.F_ADD_PCT) / 100.0)
        tp1 = max(tp1, tp1_min)
    tp2 = sig["tp2"]
    if tp2 is not None and tp2 < entry + 1.5 * (entry - sl_eff):
        tp2 = None
    if tp2 is not None and tp2 <= tp1:
        tp2 = None

    reward1 = (tp1 - entry) / entry * 100.0
    reward_final = ((tp2 if tp2 is not None else tp1) - entry) / entry * 100.0
    nr1 = net_rr(reward1, risk_pct)
    nrf = net_rr(reward_final, risk_pct)
    if nr1 < g["min_net_rr_tp1"]:
        return None, "NET_RR_TP1"
    if nrf < g["min_net_rr_final"]:
        return None, "NET_RR_FINAL"
    if reward1 < g["min_reward_friction"] * lib.F_ADD_PCT:
        return None, "REWARD_OVER_FRICTION"
    if not (sl_eff < entry < tp1 and (tp2 is None or tp1 < tp2)):
        return None, "INVALID_LEVELS"

    qty_risk = opts["risk_usd"] / ((entry - sl_eff) + entry * lib.F_ADD_PCT / 100.0)
    qty_cap = opts["equity"] * lib.MAX_POSITION_PCT / 100.0 / entry
    qty = min(qty_risk, qty_cap)
    if qty <= 0 or qty * entry < lib.MIN_NOTIONAL_USDT:
        return None, "SIZING"

    sig = dict(sig)
    sig.update({"sl_eff": sl_eff, "tp1": tp1, "tp2": tp2, "risk_pct": risk_pct,
                "net_rr1": nr1, "net_rr_final": nrf, "qty": qty, "atr_pct": atr_pct,
                "sl_atr": sl_atr})
    return sig, None


class Trade:
    def __init__(self, pair, sig, entry_index, fill):
        self.pair = pair
        self.setup = sig["setup"]
        self.sig = sig
        self.entry_index = entry_index
        self.entry = fill
        self.sl_initial = sig["sl_eff"]
        self.sl_current = sig["sl_eff"]
        self.tp1 = sig["tp1"]
        self.tp2 = sig["tp2"]
        self.qty_total = sig["qty"]
        self.qty_remaining = sig["qty"]
        self.tp1_hit = False
        self.tp1_index = None
        self.highest = fill
        self.lowest = fill
        self.fills = []
        self.exit_next_open = None
        self.exit_reason = None
        self.closed = False
        self.fees = 0.0

    def unit_risk(self):
        return max(1e-12, self.entry - self.sl_initial)

    def favorable_r(self):
        return (self.highest - self.entry) / self.unit_risk()

    def adverse_r(self):
        return (self.lowest - self.entry) / self.unit_risk()

    def sell(self, qty, price, reason):
        qty = min(qty, self.qty_remaining)
        if qty <= 0:
            return
        self.fills.append((qty, price, reason))
        self.qty_remaining -= qty
        if self.qty_remaining <= 1e-12:
            self.qty_remaining = 0.0

    def finalize(self, exit_index, exit_time):
        sold = sum(q for q, _, _ in self.fills)
        if abs(sold - self.qty_total) > 1e-6:
            raise AssertionError(
                f"qty keluar {sold:.12f} != qty total {self.qty_total:.12f} "
                f"({self.pair} {self.setup})")
        cost = lib.cost_basis(self.qty_total, self.entry)
        proceeds = sum(lib.net_sell_value(q, p) for q, p, _ in self.fills)
        net_pct = (proceeds / cost - 1.0) * 100.0 if cost > 0 else 0.0
        risk_net_pct = self.sig["risk_pct"] + lib.F_ADD_PCT
        r = net_pct / risk_net_pct if risk_net_pct > 0 else 0.0
        gross_cost = self.qty_total * self.entry
        gross_sell = sum(q * p for q, p, _ in self.fills)
        self.fees = (cost - gross_cost) + (gross_sell - proceeds)
        self.closed = True
        return {
            "pair": self.pair, "setup": self.setup,
            "signal_index": self.sig["data"].get("signal_index"),
            "entry_index": self.entry_index, "exit_index": exit_index,
            "entry_time_ms": None, "exit_time_ms": exit_time,
            "entry": self.entry, "sl_eff": self.sl_initial, "tp1": self.tp1, "tp2": self.tp2,
            "qty": self.qty_total, "tp1_hit": self.tp1_hit,
            "exit_reason": self.fills[-1][2] if self.fills else "UNKNOWN",
            "r_net": r, "net_pnl_pct": net_pct, "fees_usd": self.fees,
            "mfe_r": self.favorable_r(), "mae_r": self.adverse_r(),
            "hold_bars": exit_index - self.entry_index,
            "risk_pct": self.sig["risk_pct"], "net_rr1": self.sig["net_rr1"],
            "net_rr_final": self.sig["net_rr_final"], "score": self.sig["score"],
        }


def invalidated(ctx, trade, i):
    setup = trade.setup
    atr = ctx.atr[i]
    if not atr:
        return False
    if setup == "PULLBACK_VWAP":
        v = ctx.vwap[i]
        return (v is not None and ctx.c[i] < v - 0.2 * atr
                and ctx.volratio[i] is not None and ctx.volratio[i] >= 1.0)
    if setup == "BREAKOUT_RETEST":
        L = trade.sig["data"]["level"]
        return ctx.c[i] < L - 0.1 * atr
    if setup == "SWEEP_RECLAIM":
        age = i - trade.entry_index
        L = trade.sig["data"]["level"]
        return 1 <= age <= 3 and ctx.c[i] < L - 0.05 * atr
    if setup == "RANGE_BOUNCE":
        rl = trade.sig["data"]["range_low"]
        return (ctx.c[i] < rl - 0.2 * atr
                or (ctx.adx[i] is not None and ctx.adx[i] >= 25
                    and ctx.pdi[i] is not None and ctx.mdi[i] > ctx.pdi[i]))
    if setup == "SQUEEZE_BREAKOUT":
        age = i - trade.entry_index
        ch = trade.sig["data"]["comp_high"]
        return 1 <= age <= 3 and ctx.c[i] < ch - 0.1 * atr
    return False


def trailing_stop(ctx, trade, i):
    atr = ctx.atr[i]
    if not atr:
        return None
    mode = trade.sig["trail"]
    if mode == "EMA9":
        return (ctx.ema9[i] - 0.25 * atr) if ctx.ema9[i] is not None else None
    if mode == "HIGHER_LOW":
        sw = lib.last_swing_before(ctx.sw2, i, "L")
        if sw and sw[0] > trade.entry_index:
            return sw[1] - 0.1 * atr
        return None
    if mode == "CHANDELIER":
        return trade.highest - 1.5 * atr
    return None


def process_open_trade(ctx, trade, i, opts, trades, equity):
    """Fase A intrabar + Fase B close. Mengembalikan trade bila ditutup."""
    if trade.closed:
        return trade
    trade.highest = max(trade.highest, ctx.h[i])
    trade.lowest = min(trade.lowest, ctx.l[i])

    # SL selalu dicek lebih dulu (SL-before-TP)
    if ctx.l[i] <= trade.sl_current:
        fill = min(ctx.o[i], trade.sl_current)
        if trade.tp1_hit and trade.sig["trail"] != "NONE":
            reason = "TRAILING_STOP"
        elif trade.sl_current >= lib.breakeven_price(trade.entry):
            reason = "BREAKEVEN_STOP"
        else:
            reason = "STOP_LOSS"
        trade.sell(trade.qty_remaining, fill, reason)
        trade.exit_reason = reason
        return trade

    if trade.entry_index == i:
        return trade  # TP tidak dievaluasi di candle entry

    if not trade.tp1_hit and ctx.h[i] >= trade.tp1:
        qty1 = trade.qty_total * lib.TP1_FRACTION
        remainder = trade.qty_remaining - qty1
        if remainder * trade.tp1 < lib.MIN_NOTIONAL_USDT:
            trade.sell(trade.qty_remaining, trade.tp1, "TAKE_PROFIT_1")
        else:
            trade.sell(qty1, trade.tp1, "TAKE_PROFIT_1")
        trade.tp1_hit = True
        trade.tp1_index = i
        be = lib.breakeven_price(trade.entry)
        trade.sl_current = max(trade.sl_current, be)
        if trade.qty_remaining <= 0:
            return trade

    if trade.tp1_hit and trade.tp2 is not None and i > trade.tp1_index and ctx.h[i] >= trade.tp2:
        trade.sell(trade.qty_remaining, trade.tp2, "TAKE_PROFIT_2")
        return trade

    # Fase B: keputusan di close candle
    reason = None
    if invalidated(ctx, trade, i):
        reason = "SETUP_INVALIDATION"
    elif (ctx.c[i] < ctx.o[i] and (ctx.o[i] - ctx.c[i]) >= 0.8 * ctx.atr[i]
          and ctx.volratio[i] is not None and ctx.volratio[i] >= 1.5):
        reason = "MOMENTUM_FAIL"
    elif ctx.regime[i] == "TREND_DOWN":
        reason = "REGIME_CHANGE"
    elif (not trade.tp1_hit and i - trade.entry_index >= trade.sig["time_stop"]
          and trade.favorable_r() < 0.5):
        reason = "TIME_STOP"

    if reason:
        trade.exit_next_open = reason
        return trade

    if trade.tp1_hit:
        new_stop = trailing_stop(ctx, trade, i)
        if new_stop is not None and math.isfinite(new_stop):
            trade.sl_current = max(trade.sl_current, new_stop)
    return trade


def run_pair(ctx, opts, funnel, equity):
    params = opts["setup_params"]
    trades = []
    pending = None
    open_trade = None
    exit_next_open = None
    cooldown_until = -1
    pause_until = -1
    losses_streak = 0
    day_key = None
    day_pnl = 0.0
    n = ctx.n

    def finalize(trade, exit_index):
        nonlocal equity, day_pnl, losses_streak, cooldown_until, pause_until
        rec = trade.finalize(exit_index, ctx.t[exit_index])
        rec["entry_time_ms"] = ctx.t[trade.entry_index]
        rec["in_sample"] = (trade.sig["data"].get("signal_index") or trade.entry_index) < opts["split_index"]
        trades.append(rec)
        equity += rec["r_net"] * opts["risk_usd"]
        day_pnl += rec["r_net"] * opts["risk_usd"]
        if rec["r_net"] < 0:
            losses_streak += 1
            cooldown_until = exit_index + 1
            if losses_streak >= lib.MAX_CONSECUTIVE_LOSSES:
                pause_until = exit_index + 4
                losses_streak = 0
        else:
            losses_streak = 0

    for i in range(lib.WARMUP_CANDLES, n):
        dk = (ctx.t[i] + 7 * 3600_000) // 86_400_000
        if dk != day_key:
            day_key = dk
            day_pnl = 0.0

        # 1) exit yang diantre dari close candle sebelumnya
        if open_trade is not None and open_trade.exit_next_open:
            fill = ctx.o[i]
            open_trade.sell(open_trade.qty_remaining, fill, open_trade.exit_next_open)
            finalize(open_trade, i)
            open_trade = None
            exit_next_open = None

        # 2) kelola trade terbuka
        if open_trade is not None:
            process_open_trade(ctx, open_trade, i, opts, trades, equity)
            if open_trade.qty_remaining <= 0:
                finalize(open_trade, i)
                open_trade = None
            elif open_trade.exit_next_open is None and i == n - 1:
                # paksa tutup di akhir data, jangan dibuang dari statistik
                open_trade.sell(open_trade.qty_remaining, ctx.c[i], "END_OF_DATA")
                finalize(open_trade, i)
                open_trade = None

        # 3) pending entry
        if open_trade is None and pending is not None:
            sig = pending["sig"]
            sig_i = pending["sig_index"]
            if sig["entry_mode"] == "CONFIRM_BREAK":
                if sig["cancel_below"] is not None and ctx.l[i] < sig["cancel_below"]:
                    funnel[sig["setup"]]["ENTRY_CANCELLED"] = funnel[sig["setup"]].get("ENTRY_CANCELLED", 0) + 1
                    pending = None
                elif ctx.h[i] >= sig["entry"]:
                    fill = max(ctx.o[i], sig["entry"])
                    if fill <= sig["sl_eff"]:
                        funnel[sig["setup"]]["ENTRY_GAP_THROUGH_STOP"] = (
                            funnel[sig["setup"]].get("ENTRY_GAP_THROUGH_STOP", 0) + 1)
                        pending = None
                    else:
                        # validasi ulang G2/G4/G5 dengan harga fill
                        ok, why = revalidate(ctx, i, sig, fill, opts)
                        if not ok:
                            funnel[sig["setup"]][why] = funnel[sig["setup"]].get(why, 0) + 1
                            pending = None
                        else:
                            tr = Trade(ctx.pair, sig, i, fill)
                            process_open_trade(ctx, tr, i, opts, trades, equity)
                            if tr.qty_remaining <= 0:
                                finalize(tr, i)
                            else:
                                open_trade = tr
                            pending = None
                elif i - sig_i >= sig["valid"]:
                    funnel[sig["setup"]]["ENTRY_EXPIRED"] = funnel[sig["setup"]].get("ENTRY_EXPIRED", 0) + 1
                    pending = None
            else:  # MARKET_NEXT_OPEN
                if i == sig_i + 1:
                    fill = ctx.o[i]
                    if fill <= sig["sl_eff"]:
                        funnel[sig["setup"]]["ENTRY_GAP_THROUGH_STOP"] = (
                            funnel[sig["setup"]].get("ENTRY_GAP_THROUGH_STOP", 0) + 1)
                        pending = None
                    else:
                        ok, why = revalidate(ctx, i, sig, fill, opts)
                        if not ok:
                            funnel[sig["setup"]][why] = funnel[sig["setup"]].get(why, 0) + 1
                            pending = None
                        else:
                            tr = Trade(ctx.pair, sig, i, fill)
                            process_open_trade(ctx, tr, i, opts, trades, equity)
                            if tr.qty_remaining <= 0:
                                finalize(tr, i)
                            else:
                                open_trade = tr
                            pending = None
                elif i > sig_i + 1:
                    pending = None

        # 4) sinyal baru
        risk_blocked = (i < cooldown_until or i < pause_until
                        or day_pnl <= -lib.MAX_DAILY_LOSS_PCT / 100.0 * opts["equity"])
        if open_trade is None and pending is None and not risk_blocked:
            best = None
            for setup in opts["setups"]:
                if ctx.regime[i] not in ALLOWED_REGIMES[setup]:
                    continue
                sig = ctx.evaluate(i, setup, params)
                if sig is None:
                    continue
                funnel[setup]["SIGNAL"] = funnel[setup].get("SIGNAL", 0) + 1
                if ctx.pair not in opts["eligible_pairs"] and not opts["no_pair_edge"]:
                    funnel[setup]["PAIR_EDGE"] = funnel[setup].get("PAIR_EDGE", 0) + 1
                    continue
                final, why = finalize_signal(ctx, i, sig, opts)
                if final is None:
                    funnel[setup][why] = funnel[setup].get(why, 0) + 1
                    continue
                if best is None or (final["score"], final["net_rr_final"]) > (
                        best["score"], best["net_rr_final"]):
                    best = final
            if best is not None:
                pending = {"sig": best, "sig_index": i}

    if open_trade is not None:
        open_trade.sell(open_trade.qty_remaining, ctx.c[n - 1], "END_OF_DATA")
        finalize(open_trade, n - 1)
    return trades


def revalidate(ctx, i, sig, fill, opts):
    atr = ctx.atr[i]
    if not atr:
        return False, "SL_ATR_RANGE"
    risk_pct = (fill - sig["sl_eff"]) / fill * 100.0
    if risk_pct <= 0:
        return False, "INVALID_LEVELS"
    sl_atr = (fill - sig["sl_eff"]) / atr
    g = opts["gates"]
    if sl_atr < lib.MIN_SL_ATR or sl_atr > g["max_sl_atr"]:
        return False, "SL_ATR_RANGE"
    r1 = (sig["tp1"] - fill) / fill * 100.0
    rf = ((sig["tp2"] if sig["tp2"] is not None else sig["tp1"]) - fill) / fill * 100.0
    if net_rr(r1, risk_pct) < g["min_net_rr_tp1"]:
        return False, "NET_RR_TP1"
    if net_rr(rf, risk_pct) < g["min_net_rr_final"]:
        return False, "NET_RR_FINAL"
    if not (sig["sl_eff"] < fill < sig["tp1"]):
        return False, "INVALID_LEVELS"
    return True, None


def summarize(trades):
    if not trades:
        return {"trades": 0}
    rs = [t["r_net"] for t in trades]
    wins = [r for r in rs if r > 0]
    losses = [r for r in rs if r <= 0]
    gross_win = sum(wins)
    gross_loss = abs(sum(losses))
    cum = 0.0
    peak = 0.0
    max_dd = 0.0
    for t in sorted(trades, key=lambda x: (x["exit_time_ms"], x["pair"])):
        cum += t["r_net"]
        peak = max(peak, cum)
        max_dd = max(max_dd, peak - cum)
    return {
        "trades": len(trades),
        "wins": len(wins),
        "losses": len(losses),
        "win_rate_pct": round(100.0 * len(wins) / len(trades), 2),
        "avg_r": round(sum(rs) / len(trades), 4),
        "expectancy_r": round(sum(rs) / len(trades), 4),
        "avg_win_r": round(sum(wins) / len(wins), 4) if wins else None,
        "avg_loss_r": round(sum(losses) / len(losses), 4) if losses else None,
        "profit_factor": round(gross_win / gross_loss, 4) if gross_loss > 0 else None,
        "max_drawdown_r": round(max_dd, 4),
        "avg_hold_bars": round(sum(t["hold_bars"] for t in trades) / len(trades), 2),
        "tp1_hit_rate_pct": round(100.0 * sum(1 for t in trades if t["tp1_hit"]) / len(trades), 2),
        "median_mfe_r": round(median_or_none([t["mfe_r"] for t in trades]), 4)
        if median_or_none([t["mfe_r"] for t in trades]) is not None else None,
        "median_mae_r": round(median_or_none([t["mae_r"] for t in trades]), 4)
        if median_or_none([t["mae_r"] for t in trades]) is not None else None,
        "total_fees_usd": round(sum(t["fees_usd"] for t in trades), 2),
        "end_of_data": sum(1 for t in trades if t["exit_reason"] == "END_OF_DATA"),
        "exit_reasons": _count(t["exit_reason"] for t in trades),
    }


def _count(items):
    out = {}
    for x in items:
        out[x] = out.get(x, 0) + 1
    return out


def aggregate_rows(rows, bucket_minutes, input_minutes):
    if not rows:
        return []
    t = [r[0] for r in rows]
    o = [r[1] for r in rows]
    h = [r[2] for r in rows]
    l = [r[3] for r in rows]
    c = [r[4] for r in rows]
    v = [r[5] for r in rows]
    return lib.aggregate(t, o, h, l, c, v, bucket_minutes, input_minutes)


def build_frames(data_dir, pair, timeframe):
    """Kembalikan (rows eksekusi, rows HTF, menit eksekusi, menit HTF)."""
    if timeframe == "15m":
        return (lib.load_pair(data_dir, pair, "15m"), lib.load_pair(data_dir, pair, "1h"), 15, 60)
    rows = lib.load_pair(data_dir, pair, "1h")
    return (rows, aggregate_rows(rows, 240, 60), 60, 240)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/tokocrypto_spot")
    ap.add_argument("--timeframe", choices=["15m", "1h"], default="15m")
    ap.add_argument("--pairs", default="all")
    ap.add_argument("--setups", default="all")
    ap.add_argument("--tp1-policy", choices=["floor", "literal"], default="floor")
    ap.add_argument("--no-pair-edge", action="store_true",
                    help="RISET: matikan G0 (produksi tetap wajib G0)")
    ap.add_argument("--pair-gate", default="data/config/pair_gate.json")
    ap.add_argument("--out", default="data/hasil/spot_backtest_v2")
    ap.add_argument("--equity", type=float, default=lib.DEFAULT_EQUITY)
    ap.add_argument("--split", type=float, default=0.7)
    ap.add_argument("--max-sl-atr", type=float, default=lib.MAX_SL_ATR,
                    help="RISET: override G2 (default 5.0)")
    ap.add_argument("--friction-multiple", type=float, default=lib.FLOOR_MULTIPLE,
                    help="RISET: override lantai friksi (default 6.0; 0 = tanpa lantai)")
    ap.add_argument("--min-atr-over-friction", type=float, default=lib.MIN_ATR_PCT_OVER_FRICTION,
                    help="RISET: override faktor 1.5F di G3")
    ap.add_argument("--min-net-rr-tp1", type=float, default=lib.MIN_NET_RR_TP1,
                    help="RISET: override G4 (default 1.0)")
    ap.add_argument("--min-net-rr-final", type=float, default=lib.MIN_NET_RR_FINAL,
                    help="RISET: override G5 (default 1.5)")
    ap.add_argument("--min-reward-over-friction", type=float, default=lib.MIN_REWARD_OVER_FRICTION,
                    help="RISET: override G6 (default 3.0)")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    if a.max_sl_atr <= 0:
        raise SystemExit("--max-sl-atr harus > 0")

    pairs = lib.list_pairs(a.data) if a.pairs == "all" else [p.strip().upper() for p in a.pairs.split(",")]
    setups = list(SETUPS) if a.setups == "all" else [s.strip().upper() for s in a.setups.split(",")]
    for s in setups:
        if s not in SETUPS:
            raise SystemExit(f"setup tidak dikenal: {s}")
    eligible = set()
    if os.path.exists(a.pair_gate):
        gate = json.load(open(a.pair_gate))
        eligible = {p["pair"] for p in (gate.get("quotes") or {}).get("USDT", {}).get("pairs", [])
                    if p.get("eligible")}
    else:
        print(f"! pair_gate tidak ada: {a.pair_gate}; G0 akan menolak semua", file=sys.stderr)

    # BTC HTF return untuk filter altcoin (4 candle HTF terakhir)
    btc_ret_by_time = {}
    _, btc_htf, _, _ = build_frames(a.data, "BTCUSDT", a.timeframe)
    if btc_htf:
        closes = [r[4] for r in btc_htf]
        for k in range(4, len(btc_htf)):
            btc_ret_by_time[btc_htf[k][0]] = (closes[k] / closes[k - 4] - 1.0) * 100.0

    opts = {
        "setups": setups, "tp1_policy": a.tp1_policy, "no_pair_edge": a.no_pair_edge,
        "eligible_pairs": eligible, "equity": a.equity,
        "risk_usd": a.equity * lib.RISK_PER_TRADE_PCT / 100.0,
        "setup_params": lib.DEFAULT_SETUP_PARAMS,
        "gates": {
            "max_sl_atr": a.max_sl_atr,
            "friction_multiple": a.friction_multiple,
            "min_atr_friction": a.min_atr_over_friction,
            "min_net_rr_tp1": a.min_net_rr_tp1,
            "min_net_rr_final": a.min_net_rr_final,
            "min_reward_friction": a.min_reward_over_friction,
        },
        "split_index": 0,
    }
    funnel = {s: {} for s in setups}
    all_trades = []
    per_pair = {}
    for idx, pair in enumerate(pairs, 1):
        rows, htf, exec_minutes, htf_minutes = build_frames(a.data, pair, a.timeframe)
        if not rows or not htf or len(rows) < lib.WARMUP_CANDLES + 50:
            print(f"  [{idx}/{len(pairs)}] {pair:<14} dilewati (data kurang)", file=sys.stderr)
            continue
        ctx = Ctx(pair, rows, htf, btc_ret_by_time, exec_minutes, htf_minutes)
        opts["split_index"] = int(len(rows) * a.split)
        trades = run_pair(ctx, opts, funnel, a.equity)
        all_trades.extend(trades)
        per_pair[pair] = summarize(trades)
        s = per_pair[pair]
        print(f"  [{idx}/{len(pairs)}] {pair:<14} trade {s['trades']:>4} "
              f"avgR {s.get('avg_r', 0):>7.4f} WR {s.get('win_rate_pct', 0):>5.1f}%", flush=True)

    in_sample = [t for t in all_trades if t["in_sample"]]
    out_sample = [t for t in all_trades if not t["in_sample"]]
    by_setup = {s: summarize([t for t in all_trades if t["setup"] == s]) for s in setups}
    doc = {
        "meta": {
            "generated_at_utc": lib.now_utc(),
            "dataset": os.path.abspath(a.data),
            "pairs_requested": len(pairs),
            "timeframe": a.timeframe,
            "htf": "1h" if a.timeframe == "15m" else "4h",
            "setups": setups,
            "tp1_policy": a.tp1_policy,
            "pair_edge_enabled": not a.no_pair_edge,
            "gates": opts["gates"],
            "eligible_pairs_gate": sorted(eligible),
            "equity_usd": a.equity,
            "risk_per_trade_pct": lib.RISK_PER_TRADE_PCT,
            "fee_buy_pct": lib.BUY_FEE_PCT, "fee_sell_pct": lib.SELL_FEE_PCT,
            "slippage_pct": lib.SLIPPAGE_PCT, "f_pct": lib.F_ADD_PCT,
            "friction_floor_pct": a.friction_multiple * lib.F_ADD_PCT,
            "friction_multiple": a.friction_multiple,
            "tp1_fraction": lib.TP1_FRACTION,
            "split": a.split,
            "warmup_candles": lib.WARMUP_CANDLES,
        },
        "summary": {
            "overall": summarize(all_trades),
            "in_sample": summarize(in_sample),
            "out_of_sample": summarize(out_sample),
            "by_setup": by_setup,
            "by_pair": per_pair,
        },
        "funnel": funnel,
    }
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out + ".json", "w") as f:
        json.dump(doc, f, indent=2)
    with open(a.out + "_trades.csv", "w", newline="") as f:
        w = csv.writer(f)
        cols = ["pair", "setup", "signal_index", "entry_index", "exit_index", "entry_time_ms",
                "exit_time_ms", "entry", "sl_eff", "tp1", "tp2", "qty", "tp1_hit",
                "exit_reason", "r_net", "net_pnl_pct", "fees_usd", "mfe_r", "mae_r",
                "hold_bars", "risk_pct", "net_rr1", "net_rr_final", "score", "in_sample"]
        w.writerow(cols)
        for t in sorted(all_trades, key=lambda x: (x["entry_time_ms"], x["pair"])):
            w.writerow([t[c] for c in cols])

    ov = doc["summary"]["overall"]
    print(f"\n=== RINGKASAN ({len(pairs)} pair | {a.timeframe} | setup {','.join(setups)} | TP1 {a.tp1_policy} | "
          f"G0 {'OFF' if a.no_pair_edge else 'ON'}) ===")
    print(f"trade {ov.get('trades', 0)} | avgR {ov.get('avg_r', 0):.4f} | "
          f"WR {ov.get('win_rate_pct', 0):.1f}% | PF {ov.get('profit_factor')} | "
          f"maxDD {ov.get('max_drawdown_r', 0):.2f}R | EOD {ov.get('end_of_data', 0)}")
    print(f"in-sample  n={doc['summary']['in_sample'].get('trades', 0)} "
          f"avgR={doc['summary']['in_sample'].get('avg_r', 0)} | "
          f"out-sample n={doc['summary']['out_of_sample'].get('trades', 0)} "
          f"avgR={doc['summary']['out_of_sample'].get('avg_r', 0)}")
    for s in setups:
        x = by_setup[s]
        if x.get("trades", 0) == 0:
            print(f"{s:<18} trade 0 | funnel: {funnel[s]}")
        else:
            print(f"{s:<18} trade {x['trades']:>4} avgR {x['avg_r']:>7.4f} "
                  f"WR {x['win_rate_pct']:>5.1f}% PF {x['profit_factor']}")
    print(f"\nDitulis: {a.out}.json dan {a.out}_trades.csv")
    return 0


if __name__ == "__main__":
    sys.exit(main())
