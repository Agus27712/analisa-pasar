#!/usr/bin/env python3
"""
tools/entry_hypothesis_lab.py — Laboratorium hipotesis ENTRY (fase P3)

TUJUAN
------
Menguji hipotesis ENTRY scalping secara offline, DI LUAR PATH LIVE, memakai gate
yang tertulis di workplan2.md §P3:

    LOLOS  <=>  mean GROSS >= gate_gross pada TRAIN **dan** TEST
                dan n >= min_n pada kedua split
                dan mengalahkan baseline acak (uji permutasi satu sisi,
                ambang Bonferroni 0.05 / jumlah_hipotesis)

Definisi:
  GROSS = (close[i+H] / close[i] - 1) * 100          -> murni pergerakan harga
  NET   = gross setelah buy fee + sell fee + 2x slippage (Fee Truth)
  R     = NET / risiko_kotor_pct, exit dijalankan oleh resolve_trade() yang sama
          dengan tools/scalp_replay.py (mode default: full, max hold = H)

GATE DIPASANG DI GROSS, bukan di NET — sesuai workplan. Alasannya: kalau edge kotor
saja tidak menembus fee all-in (~0,97% round-trip Tokocrypto USDT), NET pasti negatif
dan tidak ada gunanya dituning.

BATAS / KEJUJURAN
-----------------
* Semua keluaran di sini berlabel **HIPOTESIS**. Ini bahan uji, bukan rekomendasi.
* Tool ini **TIDAK** menulis ke ScalpingConfig.kt, ScalpingRuntimeConfig, atau
  data/config/*.json. Promosi ke default live hanya boleh dilakukan manual oleh owner
  setelah gate lolos pada train DAN test.
* Entry non-overlapping per pair (satu posisi pada satu waktu), sama seperti
  run_replay() di scalp_replay.py. Ini mencegah n digelembungkan oleh sinyal yang
  menempel di bar bersebelahan.
* Split TRAIN/TEST bersifat kronologis (bukan acak) supaya tidak ada kebocoran waktu.
* R di sini memakai proksi level risiko berbasis ATR (SL = 1xATR, TP1 = 1xATR,
  TP2 = 2xATR), bukan risk_levels() penuh milik app — hipotesis di lab ini tidak
  melewati detektor setup/structure/regime, jadi level aslinya tidak tersedia.
  Karena itu gate tidak dipasang di R.

PEMAKAIAN
---------
  # ambil data dulu (dari jaringan Indonesia):
  python3 tools/fetch_tokocrypto_klines.py --pages 87 --out data/tokocrypto_v4

  python3 tools/entry_hypothesis_lab.py --data data/tokocrypto_v4 \
      --out-json data/hasil/p3_entry_hypothesis_lab.json \
      --out-md   data/hasil/p3_entry_hypothesis_lab.md

Hanya library standar Python 3, tanpa pip.
"""
import argparse
import csv
import glob
import importlib.util
import json
import math
import os
import random
import sys
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

# --------------------------------------------------------------------------
# Muat scalp_replay.py sebagai modul (bukan package, jadi pakai importlib).
# Tujuannya: exit resolution + fee harus PERSIS sama dengan replay produksi.
# --------------------------------------------------------------------------


def load_replay(path):
    spec = importlib.util.spec_from_file_location("scalp_replay", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# --------------------------------------------------------------------------
# Seri indikator (versi full-series, kausal). scalp_replay hanya punya versi
# "last value", sedangkan lab ini perlu nilai di setiap bar.
# --------------------------------------------------------------------------


def sma_series(vals, period):
    n = len(vals)
    out = [None] * n
    s = 0.0
    for i, v in enumerate(vals):
        s += v
        if i >= period:
            s -= vals[i - period]
        if i >= period - 1:
            out[i] = s / period
    return out


def std_series(vals, period):
    n = len(vals)
    out = [None] * n
    s = 0.0
    sq = 0.0
    for i, v in enumerate(vals):
        s += v
        sq += v * v
        if i >= period:
            s -= vals[i - period]
            sq -= vals[i - period] * vals[i - period]
        if i >= period - 1:
            m = s / period
            var = max(0.0, sq / period - m * m)
            out[i] = math.sqrt(var)
    return out


def rsi_series(closes, period=14):
    """Wilder RSI, versi seri. Sama seperti rsi_wilder() di scalp_replay."""
    n = len(closes)
    out = [None] * n
    if period <= 0 or n <= period:
        return out
    gain = 0.0
    loss = 0.0
    for i in range(1, period + 1):
        ch = closes[i] - closes[i - 1]
        if ch >= 0:
            gain += ch
        else:
            loss -= ch
    ag = gain / period
    al = loss / period

    def val(a_gain, a_loss):
        if a_loss == 0.0:
            return 50.0 if a_gain == 0.0 else 100.0
        return 100.0 - (100.0 / (1.0 + a_gain / a_loss))

    out[period] = val(ag, al)
    for i in range(period + 1, n):
        ch = closes[i] - closes[i - 1]
        ag = (ag * (period - 1) + (ch if ch > 0 else 0.0)) / period
        al = (al * (period - 1) + (-ch if ch < 0 else 0.0)) / period
        out[i] = val(ag, al)
    return out


def atr_series(candles, period=14):
    """Wilder ATR, versi seri."""
    n = len(candles)
    out = [None] * n
    if period <= 0 or n <= 1:
        return out
    trs = [0.0] * n
    for i in range(1, n):
        pc = candles[i - 1]["c"]
        trs[i] = max(candles[i]["h"] - candles[i]["l"],
                     abs(candles[i]["h"] - pc),
                     abs(candles[i]["l"] - pc))
    if n <= period:
        a = sum(trs[1:]) / max(1, n - 1)
        for i in range(n):
            out[i] = a
        return out
    a = sum(trs[1:period + 1]) / period
    out[period] = a
    for i in range(period + 1, n):
        a = (a * (period - 1) + trs[i]) / period
        out[i] = a
    return out


def rolling_extreme_prev(vals, window, want_max):
    """max/min dari vals[i-window .. i-1] (TIDAK termasuk i). None bila belum cukup.

    Monotonic deque => O(n). Nilai None di dalam window melewati perhitungan.
    """
    n = len(vals)
    out = [None] * n
    dq = deque()  # indeks, nilai monoton
    for i in range(n):
        while dq and dq[0] < i - window:
            dq.popleft()
        if dq:
            out[i] = vals[dq[0]]
        v = vals[i]
        if v is None:
            continue
        if want_max:
            while dq and (vals[dq[-1]] is None or vals[dq[-1]] <= v):
                dq.pop()
        else:
            while dq and (vals[dq[-1]] is None or vals[dq[-1]] >= v):
                dq.pop()
        dq.append(i)
    return out


def align_htf(m1, htf, step_ms):
    """Untuk tiap indeks M1, kembalikan indeks bar HTF terakhir yang SUDAH CLOSED."""
    n = len(m1)
    nh = len(htf)
    out = [-1] * n
    p = 0
    for i in range(n):
        t = m1[i]["t"]
        while p < nh and htf[p]["t"] + step_ms <= t:
            p += 1
        out[i] = p - 1
    return out


def pick(series, idx, i):
    """Nilai series HTF di indeks M1 i, atau None bila belum ada bar HTF tertutup."""
    j = idx[i]
    if j < 0 or j >= len(series):
        return None
    return series[j]


def aggregate(candles, minutes):
    """Agregasi kausal ke grid UTC — sama seperti aggregate() di scalp_replay."""
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
# Konteks per pair
# --------------------------------------------------------------------------


class PairCtx(object):
    __slots__ = ("symbol", "m1", "n", "close", "open", "high", "low", "vol", "ts",
                 "ema9", "ema21", "ema50", "ema200", "rsi14", "atr14", "atr_ma200",
                 "vol_ma20", "bb_low", "high60", "low60", "hour",
                 "m15_close", "m15_rsi", "m15_ema9", "m15_ema21", "m15_ema50", "m15_ema200",
                 "h1_ema50", "h1_ema200",
                 "gross", "net", "mfe", "mae", "valid")


def build_ctx(symbol, m1, horizon, fee, slip, replay):
    c = PairCtx()
    c.symbol = symbol
    c.m1 = m1
    c.n = n = len(m1)
    c.close = close_l = [b["c"] for b in m1]
    c.open = [b["o"] for b in m1]
    c.high = high_l = [b["h"] for b in m1]
    c.low = low_l = [b["l"] for b in m1]
    c.vol = vol_l = [max(0.0, b["v"]) for b in m1]
    c.ts = [b["t"] for b in m1]

    c.ema9 = replay.ema_series(close_l, 9)
    c.ema21 = replay.ema_series(close_l, 21)
    c.ema50 = replay.ema_series(close_l, 50)
    c.ema200 = replay.ema_series(close_l, 200)
    c.rsi14 = rsi_series(close_l, 14)
    c.atr14 = atr_series(m1, 14)
    c.atr_ma200 = sma_series([x if x is not None else 0.0 for x in c.atr14], 200)
    c.vol_ma20 = sma_series(vol_l, 20)

    mid20 = sma_series(close_l, 20)
    sd20 = std_series(close_l, 20)
    c.bb_low = [None if (m is None or s is None) else m - 2.0 * s
                for m, s in zip(mid20, sd20)]

    c.high60 = rolling_extreme_prev(high_l, 60, True)
    c.low60 = rolling_extreme_prev(low_l, 60, False)
    c.hour = [((t // 3_600_000) % 24) for t in c.ts]

    # --- HTF (M15 / H1) ---
    m15 = aggregate(m1, 15)
    h1 = aggregate(m1, 60)
    i15 = align_htf(m1, m15, 15 * 60_000)
    i60 = align_htf(m1, h1, 60 * 60_000)
    m15_c = [b["c"] for b in m15]
    h1_c = [b["c"] for b in h1]
    s15_rsi = rsi_series(m15_c, 14)
    s15_e9 = replay.ema_series(m15_c, 9)
    s15_e21 = replay.ema_series(m15_c, 21)
    s15_e50 = replay.ema_series(m15_c, 50)
    s15_e200 = replay.ema_series(m15_c, 200)
    s60_e50 = replay.ema_series(h1_c, 50)
    s60_e200 = replay.ema_series(h1_c, 200)

    c.m15_close = [pick(m15_c, i15, i) for i in range(n)]
    c.m15_rsi = [pick(s15_rsi, i15, i) for i in range(n)]
    c.m15_ema9 = [pick(s15_e9, i15, i) for i in range(n)]
    c.m15_ema21 = [pick(s15_e21, i15, i) for i in range(n)]
    c.m15_ema50 = [pick(s15_e50, i15, i) for i in range(n)]
    c.m15_ema200 = [pick(s15_e200, i15, i) for i in range(n)]
    c.h1_ema50 = [pick(s60_e50, i60, i) for i in range(n)]
    c.h1_ema200 = [pick(s60_e200, i60, i) for i in range(n)]

    # --- hasil forward (horizon tetap) ---
    H = horizon
    gross = [None] * n
    net = [None] * n
    mfe = [None] * n
    mae = [None] * n
    for i in range(n - H):
        e = close_l[i]
        if e <= 0:
            continue
        hi = max(high_l[i + 1:i + 1 + H])
        lo = min(low_l[i + 1:i + 1 + H])
        cl = close_l[i + H]
        gross[i] = (cl / e - 1.0) * 100.0
        mfe[i] = (hi / e - 1.0) * 100.0
        mae[i] = (lo / e - 1.0) * 100.0
        net[i] = replay._net_profit_pct(e, cl, fee, slip)
    c.gross = gross
    c.net = net
    c.mfe = mfe
    c.mae = mae

    # indeks yang boleh dipakai sebagai entry (sudah lewat pemanasan + masih ada ruang H)
    c.valid = [i for i in range(210, n - H) if gross[i] is not None]
    return c


# --------------------------------------------------------------------------
# Hipotesis entry
# --------------------------------------------------------------------------
# Setiap entri: (key, judul, alasan ekonomi singkat, fungsi(ctx, i) -> bool)
# Fungsi WAJIB hanya memakai data <= i (kausal).


def _bull3(ctx, i):
    if i < 3:
        return False
    return all(ctx.close[k] > ctx.open[k] for k in (i - 2, i - 1, i))


def _m15_cross_up(ctx, i):
    """EMA9 M15 menembus ke atas EMA21 pada bar M15 tertutup terakhir."""
    a0, b0 = ctx.m15_ema9[i], ctx.m15_ema21[i]
    if a0 is None or b0 is None:
        return False
    # cari bar M15 sebelumnya yang nilainya berbeda
    for k in range(i - 1, max(-1, i - 30), -1):
        a1, b1 = ctx.m15_ema9[k], ctx.m15_ema21[k]
        if a1 is None or b1 is None:
            return False
        if (a1, b1) != (a0, b0):
            return a1 <= b1 and a0 > b0
    return False


HYPOTHESES = [
    ("h01_m15_oversold_uptrend",
     "M15 RSI<30 di dalam tren naik (beli dip)",
     "Mean reversion: tekanan jual jangka pendek di pasar yang trennya masih naik (close M15 > EMA200 M15).",
     lambda ctx, i: (ctx.m15_rsi[i] is not None and ctx.m15_rsi[i] < 30.0
                     and ctx.m15_close[i] is not None and ctx.m15_ema200[i] is not None
                     and ctx.m15_close[i] > ctx.m15_ema200[i])),

    ("h02_m1_bb_reclaim",
     "Close M1 kembali ke atas lower Bollinger(20,2)",
     "Reclaim: bar sebelumnya menembus bawah lower band, bar ini ditutup naik — asumsi jual berlebih jangka sangat pendek.",
     lambda ctx, i: (i >= 1 and ctx.bb_low[i - 1] is not None and ctx.bb_low[i] is not None
                     and ctx.close[i - 1] < ctx.bb_low[i - 1] and ctx.close[i] > ctx.close[i - 1])),

    ("h03_vol_spike_bull",
     "Lonjakan volume >3x MA20 + candle M1 hijau",
     "Partisipasi: volume ekstrem menandakan minat agresif; candle hijau mengonfirmasi arah.",
     lambda ctx, i: (ctx.vol_ma20[i] is not None and ctx.vol_ma20[i] > 0
                     and ctx.vol[i] > 3.0 * ctx.vol_ma20[i] and ctx.close[i] > ctx.open[i])),

    ("h04_donchian_break_60",
     "Tembus high 60 bar M1 (Donchian)",
     "Breakout: penembusan tertinggi 1 jam terakhir, klasik momentum harian pendek.",
     lambda ctx, i: ctx.high60[i] is not None and ctx.close[i] > ctx.high60[i]),

    ("h05_lowvol_ignition",
     "3 candle M1 hijau saat ATR sedang rendah",
     "Ignition: dorongan naik beruntun dari kondisi volatilitas rendah (ATR < 0,7x rata-rata 200).",
     lambda ctx, i: (_bull3(ctx, i) and ctx.atr14[i] is not None
                     and ctx.atr_ma200[i] is not None and ctx.atr_ma200[i] > 0
                     and ctx.atr14[i] < 0.7 * ctx.atr_ma200[i])),

    ("h06_sweep_reclaim",
     "Sapu low 60 bar lalu ditutup kembali di atasnya",
     "Failed breakdown: low baru dibuat tapi tidak bertahan, harga ditutup kembali di atas low lama.",
     lambda ctx, i: (ctx.low60[i] is not None and ctx.low[i] < ctx.low60[i]
                     and ctx.close[i] > ctx.low60[i])),

    ("h07_m15_ema_cross_trend",
     "EMA9 menembus EMA21 di M15, filter EMA50>EMA200 M15",
     "Momentum berlapis: sinyal cross jangka menengah yang hanya diambil bila tren besar masih naik.",
     lambda ctx, i: (_m15_cross_up(ctx, i) and ctx.m15_ema50[i] is not None
                     and ctx.m15_ema200[i] is not None and ctx.m15_ema50[i] > ctx.m15_ema200[i])),

    ("h08_us_session_uptrend",
     "Jam sesi AS (13-16 UTC) + H1 EMA50 > EMA200",
     "Waktu: likuiditas global memuncak saat sesi AS; ambil hanya bila tren H1 naik.",
     lambda ctx, i: (ctx.h1_ema50[i] is not None and ctx.h1_ema200[i] is not None
                     and ctx.h1_ema50[i] > ctx.h1_ema200[i]
                     and ctx.hour[i] in (13, 14, 15, 16))),
]


# --------------------------------------------------------------------------
# Pemindaian sinyal (non-overlapping per pair)
# --------------------------------------------------------------------------


def scan_pair(ctx, fn, horizon, lo_idx, hi_idx):
    """Kumpulkan indeks entry di [lo_idx, hi_idx). Setelah entry, lompat H bar."""
    out = []
    i = lo_idx
    while i < hi_idx:
        if ctx.gross[i] is None:
            i += 1
            continue
        if fn(ctx, i):
            out.append(i)
            i += horizon
        else:
            i += 1
    return out


# --------------------------------------------------------------------------
# Statistik
# --------------------------------------------------------------------------


def mean(xs):
    return sum(xs) / len(xs) if xs else 0.0


def median(xs):
    if not xs:
        return 0.0
    s = sorted(xs)
    m = len(s) // 2
    return s[m] if len(s) % 2 else 0.5 * (s[m - 1] + s[m])


def stdev(xs):
    if len(xs) < 2:
        return 0.0
    m = mean(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))


def lab_risk_levels(entry, atr, risk_cfg, fee, slip):
    """Proksi level risiko, mengikuti risk_levels() di scalp_replay.

    Yang TIDAK bisa ditiru (butuh detektor structure/regime yang tidak dijalankan di lab):
    SL berbasis support/swing-low dan klamp TP1 ke resistance. Sisanya sama:
      min_risk = max(0.8*ATR, min_risk_pct% * entry)
      max_risk = max(2.0*ATR, min_risk)
      SL       = entry - 1.0*ATR, dijepit ke [min_risk, max_risk]
      TP2      = max(target_net_rr*(risk%+cost)+cost, 1.5*risk%)   [cost = fee+2x slippage]
      TP1      = 0.55 * jarak TP2
    """
    if entry <= 0 or atr <= 0:
        return None
    cost = fee["buy_taker_pct"] + fee["sell_taker_pct"] + 2.0 * slip
    min_risk = max(0.8 * atr, risk_cfg["min_risk_pct"] / 100.0 * entry)
    max_risk = max(2.0 * atr, min_risk)
    sl = entry - 1.0 * atr
    raw = entry - sl
    if raw > max_risk:
        sl = entry - max_risk
    elif raw < min_risk:
        sl = entry - min_risk
    risk = entry - sl
    if risk <= 0:
        return None
    risk_pct = risk / entry * 100.0
    tp2_pct = max(risk_cfg["target_net_rr"] * (risk_pct + cost) + cost, 1.5 * risk_pct)
    tp1 = entry * (1.0 + tp2_pct * 0.55 / 100.0)
    tp2 = max(entry * (1.0 + tp2_pct / 100.0), tp1 + 0.5 * risk)
    return sl, tp1, tp2, risk_pct


def summarize_signals(ctxs, sig_map, split_lo, split_hi, horizon, fee, slip, replay, cfg, risk_cfg):
    """Hitung metrik untuk sekumpulan sinyal (per pair) pada satu split."""
    gross, net, mfe, mae, rs = [], [], [], [], []
    for ctx in ctxs:
        for i in sig_map.get(ctx.symbol, []):
            if not (split_lo <= ctx.ts[i] < split_hi):
                continue
            if ctx.gross[i] is None:
                continue
            e = ctx.close[i]
            gross.append(ctx.gross[i])
            net.append(ctx.net[i])
            mfe.append(ctx.mfe[i])
            mae.append(ctx.mae[i])
            lv = lab_risk_levels(e, ctx.atr14[i] or 0.0, risk_cfg, fee, slip)
            if lv is None:
                continue
            sl, tp1, tp2, _risk_pct = lv
            res = replay.resolve_trade(ctx.m1, i, e, sl, tp1, tp2, fee, cfg, horizon)
            if res.get("result") in ("WIN", "LOSS"):
                rs.append(res["r"])
    n = len(gross)
    wins = sum(1 for g in gross if g > 0)
    return {
        "n": n,
        "gross_mean": round(mean(gross), 4),
        "gross_median": round(median(gross), 4),
        "gross_sd": round(stdev(gross), 4),
        "net_mean": round(mean(net), 4),
        "net_median": round(median(net), 4),
        "mfe_mean": round(mean(mfe), 4),
        "mae_mean": round(mean(mae), 4),
        "win_rate_gross": round(100.0 * wins / n, 2) if n else 0.0,
        "r_mean": round(mean(rs), 4),
        "r_n": len(rs),
        "_gross_list": gross,
    }


def baseline_pvalue(ctxs, sig_map, split_lo, split_hi, obs_mean, reps, rng):
    """Uji permutasi: sebaran mean GROSS bila entry dipilih acak.

    Komposisi dijaga: untuk tiap pair, jumlah entry acak = jumlah sinyal nyata pair itu,
    dan diambil dari rentang indeks valid yang sama pada split yang sama. Jadi yang diuji
    benar-benar 'apakah TIMING-nya berguna', bukan 'apakah pair-nya sedang naik'.
    """
    pools = {}
    counts = {}
    for ctx in ctxs:
        pools[ctx.symbol] = [i for i in ctx.valid if split_lo <= ctx.ts[i] < split_hi]
        counts[ctx.symbol] = sum(1 for i in sig_map.get(ctx.symbol, [])
                                 if split_lo <= ctx.ts[i] < split_hi)
    total = sum(counts.values())
    if total == 0:
        return None, None, 0
    ge = 0
    null_means = []
    for _ in range(reps):
        acc = []
        for sym, pool in pools.items():
            k = counts[sym]
            if k == 0 or not pool:
                continue
            if k >= len(pool):
                idxs = pool
            else:
                idxs = rng.sample(pool, k)
            ctx = next(c for c in ctxs if c.symbol == sym)
            acc.extend(ctx.gross[i] for i in idxs)
        if not acc:
            continue
        m = mean(acc)
        null_means.append(m)
        if m >= obs_mean:
            ge += 1
    reps_done = len(null_means)
    if reps_done == 0:
        return None, None, 0
    p = (1.0 + ge) / (1.0 + reps_done)
    return p, round(mean(null_means), 4), reps_done


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser(description="Lab hipotesis entry (P3) — offline, berlabel HIPOTESIS.")
    ap.add_argument("--data", default="data/tokocrypto_v4", help="direktori CSV")
    ap.add_argument("--pairs", default="", help="filter pair, dipisah koma (kosong = semua)")
    ap.add_argument("--config", default="data/config/scalp_config.json")
    ap.add_argument("--horizon", type=int, default=60, help="horizon hasil, dalam bar M1 (default 60 = 1 jam)")
    ap.add_argument("--train-frac", type=float, default=0.6)
    ap.add_argument("--min-n", type=int, default=30, help="n minimum per split agar hipotesis dinilai")
    ap.add_argument("--gate-gross", type=float, default=1.0, help="ambang mean GROSS (%) di train DAN test")
    ap.add_argument("--baseline-reps", type=int, default=400)
    ap.add_argument("--alpha", type=float, default=0.05)
    ap.add_argument("--exit-mode", default="full", help="mode exit resolve_trade (classic/tp1_be/early/trail/full)")
    ap.add_argument("--min-risk-pct", type=float, default=-1.0,
                    help="lantai risiko % (default: ikut min_risk_pct di config, biasanya 0.5)")
    ap.add_argument("--seed", type=int, default=20261012)
    ap.add_argument("--max-bars", type=int, default=0, help="batasi bar M1 per pair (0 = semua)")
    ap.add_argument("--out-json", default="")
    ap.add_argument("--out-md", default="")
    args = ap.parse_args()

    replay = load_replay(os.path.join(HERE, "scalp_replay.py"))

    cfg_path = args.config if os.path.isabs(args.config) else os.path.join(REPO, args.config)
    with open(cfg_path) as f:
        base_cfg = json.load(f)
    fee = base_cfg["fee"]
    slip = float(base_cfg.get("slippage_pct", 0.08))

    data_dir = args.data if os.path.isabs(args.data) else os.path.join(REPO, args.data)
    files = sorted(glob.glob(os.path.join(data_dir, "*_1m.csv")))
    if not files:
        print(f"Tidak ada *_1m.csv di {data_dir}", file=sys.stderr)
        return 1
    want = {p.strip().upper() for p in args.pairs.split(",") if p.strip()}
    if want:
        files = [f for f in files if os.path.basename(f).split("_")[0].upper() in want]

    H = args.horizon
    lab_cfg = {
        "slippage_pct": slip,
        "exit": {
            "mode": args.exit_mode,
            "max_hold_bars_m1": H,
            "min_early_profit_net_pct": 0.45,
            "breakeven_trigger_net_pct": 0.40,
            "trail_pct": 0.35,
        },
    }

    risk_cfg = {
        "min_risk_pct": args.min_risk_pct if args.min_risk_pct > 0 else float(base_cfg.get("min_risk_pct", 0.5)),
        "target_net_rr": float(base_cfg.get("target_net_rr", 1.25)),
    }

    print(f"Data      : {data_dir}")
    print(f"Horizon   : {H} bar M1 | split train {int(args.train_frac*100)}% (kronologis)")
    print(f"Fee       : buy {fee['buy_taker_pct']}% + sell {fee['sell_taker_pct']}% + slippage {slip}% x2 "
          f"=> round-trip ~{2*(fee['buy_taker_pct']+fee['sell_taker_pct'])/2 + 2*slip:.4f}%")
    print(f"Risiko    : lantai {risk_cfg['min_risk_pct']}% | target_net_rr {risk_cfg['target_net_rr']}")
    print(f"Gate      : mean GROSS >= {args.gate_gross}% di TRAIN dan TEST, n >= {args.min_n}, "
          f"p < {args.alpha}/{len(HYPOTHESES)} (Bonferroni)")

    ctxs = []
    for f in files:
        sym = os.path.basename(f).split("_")[0].upper()
        m1 = replay.load_csv(f)
        if args.max_bars > 0:
            m1 = m1[-args.max_bars:]
        if len(m1) < 400:
            print(f"  {sym}: dilewati (hanya {len(m1)} bar)")
            continue
        ctx = build_ctx(sym, m1, H, fee, slip, replay)
        ctxs.append(ctx)
        print(f"  {sym}: {ctx.n} bar M1 | entry valid: {len(ctx.valid)} | "
              f"{ctx.ts[0]} .. {ctx.ts[-1]}")

    if not ctxs:
        print("Tidak ada pair yang layak.", file=sys.stderr)
        return 1

    t_lo = min(c.ts[c.valid[0]] for c in ctxs if c.valid)
    t_hi = max(c.ts[c.valid[-1]] for c in ctxs if c.valid)
    t_split = int(t_lo + args.train_frac * (t_hi - t_lo))
    import datetime

    def fmt(ms):
        return datetime.datetime.utcfromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M")

    print(f"Rentang   : {fmt(t_lo)} .. {fmt(t_hi)} UTC")
    print(f"Split     : train < {fmt(t_split)} <= test")
    print()

    rng = random.Random(args.seed)
    alpha_adj = args.alpha / len(HYPOTHESES)

    # pindai sinyal sekali per hipotesis
    sig_cache = {}
    for key, _title, _why, fn in HYPOTHESES:
        sig_map = {}
        for ctx in ctxs:
            sig_map[ctx.symbol] = scan_pair(ctx, fn, H, 0, ctx.n)
        sig_cache[key] = sig_map

    results = []
    for key, title, why, fn in HYPOTHESES:
        sig_map = sig_cache[key]
        tr = summarize_signals(ctxs, sig_map, 0, t_split, H, fee, slip, replay, lab_cfg, risk_cfg)
        te = summarize_signals(ctxs, sig_map, t_split, 1 << 62, H, fee, slip, replay, lab_cfg, risk_cfg)
        p_tr, null_tr, reps_tr = baseline_pvalue(ctxs, sig_map, 0, t_split,
                                                 tr["gross_mean"], args.baseline_reps, rng)
        p_te, null_te, reps_te = baseline_pvalue(ctxs, sig_map, t_split, 1 << 62,
                                                 te["gross_mean"], args.baseline_reps, rng)

        reasons = []
        if tr["n"] < args.min_n:
            reasons.append(f"n_train {tr['n']} < {args.min_n}")
        if te["n"] < args.min_n:
            reasons.append(f"n_test {te['n']} < {args.min_n}")
        if tr["gross_mean"] < args.gate_gross:
            reasons.append(f"gross_train {tr['gross_mean']:.2f}% < {args.gate_gross}%")
        if te["gross_mean"] < args.gate_gross:
            reasons.append(f"gross_test {te['gross_mean']:.2f}% < {args.gate_gross}%")
        if p_tr is None or p_tr >= alpha_adj:
            reasons.append(f"p_train {('%.5f' % p_tr) if p_tr is not None else 'n/a'} >= {alpha_adj:.5f}")
        if p_te is None or p_te >= alpha_adj:
            reasons.append(f"p_test {('%.5f' % p_te) if p_te is not None else 'n/a'} >= {alpha_adj:.5f}")
        passed = not reasons

        tr.pop("_gross_list", None)
        te.pop("_gross_list", None)
        results.append({
            "key": key,
            "title": title,
            "rationale": why,
            "label": "HIPOTESIS",
            "gate_passed": passed,
            "gate_fail_reasons": reasons,
            "train": dict(tr, p_value=p_tr, baseline_gross_mean=null_tr, baseline_reps=reps_tr),
            "test": dict(te, p_value=p_te, baseline_gross_mean=null_te, baseline_reps=reps_te),
        })
        flag = "LOLOS" if passed else "GAGAL"
        print(f"[{flag}] {key}")
        print(f"    train n={tr['n']:4d} gross={tr['gross_mean']:+.3f}% net={tr['net_mean']:+.3f}% "
              f"R={tr['r_mean']:+.3f} p={p_tr}")
        print(f"    test  n={te['n']:4d} gross={te['gross_mean']:+.3f}% net={te['net_mean']:+.3f}% "
              f"R={te['r_mean']:+.3f} p={p_te}")
        if reasons:
            print(f"    gagal karena: {'; '.join(reasons)}")

    payload = {
        "_label": "HIPOTESIS — bahan uji offline, BUKAN rekomendasi dan BUKAN default live",
        "_warning": ("Gate P3 belum tentu lolos. Jangan promosikan hipotesis mana pun ke "
                     "ScalpingRuntimeConfig/ScalpingConfig tanpa gate lolos di train DAN test."),
        "meta": {
            "data_dir": os.path.relpath(data_dir, REPO),
            "pairs": [c.symbol for c in ctxs],
            "bars_m1": {c.symbol: c.n for c in ctxs},
            "horizon_bars_m1": H,
            "train_frac": args.train_frac,
            "train_range": [fmt(t_lo), fmt(t_split)],
            "test_range": [fmt(t_split), fmt(t_hi)],
            "fee": fee,
            "slippage_pct": slip,
            "roundtrip_friction_pct": round(fee["buy_taker_pct"] + fee["sell_taker_pct"] + 2 * slip, 4),
            "exit_mode": args.exit_mode,
            "risk_proxy": risk_cfg,
            "gate": {
                "gross_min_pct": args.gate_gross,
                "min_n_per_split": args.min_n,
                "alpha": args.alpha,
                "bonferroni_alpha": round(alpha_adj, 6),
                "n_hypotheses": len(HYPOTHESES),
            },
            "baseline_reps": args.baseline_reps,
            "seed": args.seed,
        },
        "hypotheses": results,
        "no_live_promotion": True,
    }

    if args.out_json:
        p = args.out_json if os.path.isabs(args.out_json) else os.path.join(REPO, args.out_json)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            json.dump(payload, f, indent=2)
        print(f"\nJSON -> {p}")

    if args.out_md:
        p = args.out_md if os.path.isabs(args.out_md) else os.path.join(REPO, args.out_md)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(render_md(payload))
        print(f"MD   -> {p}")

    n_pass = sum(1 for r in results if r["gate_passed"])
    print(f"\nHasil: {n_pass}/{len(results)} hipotesis lolos gate.")
    return 0


def render_md(payload):
    m = payload["meta"]
    out = []
    out.append("# P3 — Lab hipotesis entry (HASIL)")
    out.append("")
    out.append("> **LABEL: HIPOTESIS.** Dokumen ini bahan uji offline. Bukan rekomendasi trading, "
               "dan **bukan** default engine live. Tidak ada perubahan apa pun pada "
               "`ScalpingRuntimeConfig` / `ScalpingConfig` / `data/config/*.json`.")
    out.append("")
    out.append("## Setup")
    out.append("")
    out.append(f"- Data: `{m['data_dir']}` — {len(m['pairs'])} pair: {', '.join(m['pairs'])}")
    out.append(f"- Rentang: {m['train_range'][0]} .. {m['test_range'][1]} UTC "
               f"(train {m['train_range'][0]}..{m['train_range'][1]}, "
               f"test {m['test_range'][0]}..{m['test_range'][1]})")
    out.append(f"- Horizon hasil: **{m['horizon_bars_m1']} bar M1**; exit `{m['exit_mode']}` "
               f"(max hold = horizon)")
    out.append(f"- Fee: buy {m['fee']['buy_taker_pct']}% + sell {m['fee']['sell_taker_pct']}% "
               f"+ slippage {m['slippage_pct']}% x2 = **{m['roundtrip_friction_pct']}% round-trip**")
    out.append(f"- Gate: mean GROSS >= **{m['gate']['gross_min_pct']}%** di TRAIN dan TEST, "
               f"n >= {m['gate']['min_n_per_split']} per split, "
               f"p < {m['gate']['bonferroni_alpha']} (Bonferroni 0,05/{m['gate']['n_hypotheses']})")
    out.append(f"- Baseline: {m['baseline_reps']} kali permutasi acak, seed {m['seed']}")
    out.append("")

    gate_gross = m["gate"]["gross_min_pct"]
    alpha = m["gate"]["alpha"]
    n_pass = sum(1 for r in payload["hypotheses"] if r["gate_passed"])
    best = max(payload["hypotheses"], key=lambda r: r["test"]["gross_mean"])
    worst = min(payload["hypotheses"], key=lambda r: r["test"]["gross_mean"])
    net_vals = [r["test"]["net_mean"] for r in payload["hypotheses"]]
    train_sig = [r for r in payload["hypotheses"]
                 if r["train"]["p_value"] is not None and r["train"]["p_value"] < alpha]
    not_replicated = [r for r in train_sig
                      if r["test"]["p_value"] is None or r["test"]["p_value"] >= alpha]

    out.append("## Ringkasan")
    out.append("")
    out.append(f"- Hipotesis diuji **{len(payload['hypotheses'])}**, lolos gate **{n_pass}**.")
    out.append(f"- GROSS TEST terbaik: `{best['key']}` **{best['test']['gross_mean']:+.3f}%** "
               f"(ambang gate {gate_gross}%). Terburuk: `{worst['key']}` "
               f"{worst['test']['gross_mean']:+.3f}%.")
    out.append(f"- Semua NET TEST berada di rentang **{min(net_vals):+.3f}% .. {max(net_vals):+.3f}%**, "
               f"sedangkan friksi round-trip saja sudah {m['roundtrip_friction_pct']}%.")
    if train_sig:
        out.append(f"- Signifikan di TRAIN (p < {alpha}): " +
                   ", ".join(f"`{r['key']}`" for r in train_sig) + ".")
    if not_replicated:
        out.append("- **Tidak tereplikasi di TEST**: " +
                   ", ".join(f"`{r['key']}` (p_train {r['train']['p_value']:.4f} -> "
                             f"p_test {r['test']['p_value']:.4f})" for r in not_replicated) +
                   ". Contoh langsung kenapa signifikansi di satu periode tidak boleh dipercaya.")
    out.append("")
    out.append("## Hasil per hipotesis")
    out.append("")
    out.append("| Hipotesis | split | n | gross mean | net mean | MFE | MAE | win% | R (proksi) | p | gate |")
    out.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for r in payload["hypotheses"]:
        for name, s in (("train", r["train"]), ("test", r["test"])):
            pv = s["p_value"]
            out.append(
                f"| `{r['key']}` | {name} | {s['n']} | {s['gross_mean']:+.3f}% | {s['net_mean']:+.3f}% | "
                f"{s['mfe_mean']:+.3f}% | {s['mae_mean']:+.3f}% | {s['win_rate_gross']:.1f} | "
                f"{s['r_mean']:+.3f} | {('%.4f' % pv) if pv is not None else 'n/a'} | "
                f"{'LOLOS' if r['gate_passed'] else 'gagal'} |")
    out.append("")
    out.append("## Catatan per hipotesis")
    out.append("")
    for r in payload["hypotheses"]:
        out.append(f"### `{r['key']}` — {r['title']}")
        out.append("")
        out.append(f"*Alasan:* {r['rationale']}")
        out.append("")
        if r["gate_passed"]:
            out.append("**Status: LOLOS gate.** Tetap wajib diuji ulang pada data periode lain "
                       "sebelum dipertimbangkan untuk promosi.")
        else:
            out.append("**Status: GAGAL gate.** " + "; ".join(r["gate_fail_reasons"]) + ".")
        out.append("")
    out.append("## Batasan")
    out.append("")
    out.append("- R memakai **proksi** level risiko bergaya `risk_levels()`: SL = 1xATR M1 dijepit ke "
               f"lantai {payload['meta']['risk_proxy']['min_risk_pct']}%, TP2 = target_net_rr "
               f"{payload['meta']['risk_proxy']['target_net_rr']}x. Yang tidak bisa ditiru adalah SL "
               "berbasis support/swing-low dan klamp TP1 ke resistance, karena hipotesis di lab ini "
               "tidak melewati detektor structure/regime. Karena itu gate dipasang di GROSS, bukan di R.")
    out.append("- MFE/MAE adalah ekskursi dalam horizon; bukan jaminan bisa dieksekusi di harga itu.")
    out.append("- Uji dilakukan pada satu periode. Hasil bagus pada satu periode bukan bukti edge; "
               "perlu walk-forward pada periode lain.")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    sys.exit(main())
