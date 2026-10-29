#!/usr/bin/env python3
"""Lab hipotesis entri "beli bawah, jual atas" (mean reversion / buy-the-dip).

Latar belakang
--------------
Engine scalping yang ada menghasilkan entri di **persentil 93** harga 1 hari
(`docs/exp/exp_entry_price_percentile_ADA-DOGE-XRP.md`): engine membeli saat
momentum sudah panas. Semua sweep exit (0/88 varian, 0/90 sel, 0/27 kelipatan
friksi) gagal bukan karena exit-nya salah, tetapi karena harga masuk sudah
mahal. Alat ini menguji sisi yang belum pernah diukur: **entri murah**.

Tesis yang diuji
----------------
1. entri BELI di bawah (persentil rendah / oversold / koreksi), bukan di puncak;
2. JUAL di atas: target = rata-rata bergerak (EMA20/EMA50), band atas
   Bollinger, atau puncak 7 hari sebelumnya (swing high);
3. stop loss berbasis ATR yang **lebar** (3-4 ATR) supaya mean reversion punya
   ruang, plus time-stop sebagai pengaman.

Kenapa exit berbasis target, bukan SL/TP ATR rapat
--------------------------------------------------
Uji pertama alat ini memakai SL/TP ATR rapat (1,0-2,0 ATR). Hasilnya gross
semua hipotesis ~0,00% karena stop rapat kena noise H1 (median hold 5 bar).
Itu mengukur geometri stop, bukan tesis beli-bawah. Mode target mengukur
pertanyaan sebenarnya: **apakah harga kembali ke atas setelah dibeli murah?**

Kontrol negatif (wajib, supaya alat tidak buta)
-----------------------------------------------
- `control_top_rsi70` : beli saat RSI > 70 (beli di puncak). Harus lebih buruk
  dari hipotesis beli-bawah, kalau tidak hasil positif apa pun tidak sah.
- `control_random`    : entri acak ber-seed pada rentang yang sama.

Biaya
-----
Net % = gross % - friksi all-in Tokocrypto USDT 0,9688%
(fee 0,4044% x 2 + slippage 0,08% x 2, FEE_TRUTH.md v1.1).

Batasan
-------
- Bar H1/4h/D1; bukan tick. Slippage tetap asumsi 0,08%/sisi.
- Satu posisi per pair (non-overlap); sinyal saat posisi terbuka dilewati.
- Riset offline. Tidak ada yang boleh dipromosikan ke app tanpa gate
  train+test dan keputusan owner.

Contoh
------
  python3 tools/entry_buy_low_lab.py --data data/tokocrypto_v8 --tf 1h \
      --out data/hasil/exp_buy_low_entry_v8_1h_regime.json

  # H4 + walk-forward 6 jendela + JSON ramping (dipakai laporan docs/exp/)
  python3 tools/entry_buy_low_lab.py --data data/tokocrypto_v8 --tf 4h \
      --walk-forward 6 --slim --out data/hasil/exp_buy_low_entry_v8_4h_regime.json
"""
import argparse
import csv
import glob
import json
import math
import os
import random
import statistics as st
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Friksi all-in Tokocrypto USDT (FEE_TRUTH.md v1.1): 0,4044% x 2 + 0,08% x 2.
FRICTION_PCT = 0.9688


# ---------------------------------------------------------------- indikator
def ema(values, n):
    if len(values) < n:
        return [None] * len(values)
    k = 2.0 / (n + 1.0)
    out = [None] * len(values)
    prev = sum(values[:n]) / n
    out[n - 1] = prev
    for i in range(n, len(values)):
        prev = values[i] * k + prev * (1 - k)
        out[i] = prev
    return out


def rsi(values, n=14):
    out = [None] * len(values)
    if len(values) <= n:
        return out
    gains = losses = 0.0
    for i in range(1, n + 1):
        d = values[i] - values[i - 1]
        if d >= 0:
            gains += d
        else:
            losses -= d
    avg_g, avg_l = gains / n, losses / n
    out[n] = 100.0 if avg_l == 0 else 100.0 - 100.0 / (1.0 + avg_g / avg_l)
    for i in range(n + 1, len(values)):
        d = values[i] - values[i - 1]
        g = d if d > 0 else 0.0
        l = -d if d < 0 else 0.0
        avg_g = (avg_g * (n - 1) + g) / n
        avg_l = (avg_l * (n - 1) + l) / n
        out[i] = 100.0 if avg_l == 0 else 100.0 - 100.0 / (1.0 + avg_g / avg_l)
    return out


def atr(bars, n=14):
    out = [None] * len(bars)
    if len(bars) <= n:
        return out
    trs = []
    prev_close = bars[0]["c"]
    for b in bars:
        trs.append(max(b["h"] - b["l"], abs(b["h"] - prev_close), abs(b["l"] - prev_close)))
        prev_close = b["c"]
    val = sum(trs[1:n + 1]) / n
    out[n] = val
    for i in range(n + 1, len(bars)):
        val = (val * (n - 1) + trs[i]) / n
        out[i] = val
    return out


def percentile_rank(values, x):
    if not values:
        return None
    return 100.0 * sum(1 for v in values if v < x) / len(values)


# ------------------------------------------------------------------- loader
def load_csv(path):
    bars = []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            bars.append({
                "t": int(row["open_time_ms"]),
                "o": float(row["open"]),
                "h": float(row["high"]),
                "l": float(row["low"]),
                "c": float(row["close"]),
                "v": float(row.get("volume") or 0.0),
            })
    return bars


def pair_files(data_dir, tf, pairs=None):
    out = []
    for f in sorted(glob.glob(os.path.join(data_dir, f"*_{tf}.csv"))):
        name = Path(f).stem.rsplit("_", 1)[0]
        if pairs and name not in pairs:
            continue
        out.append((name, f))
    return out


# ------------------------------------------------------------- indikator set
def build_features(bars):
    closes = [b["c"] for b in bars]
    f = {
        "close": closes,
        "ema20": ema(closes, 20),
        "ema50": ema(closes, 50),
        "ema200": ema(closes, 200),
        "rsi14": rsi(closes, 14),
        "atr14": atr(bars, 14),
    }
    upper = [None] * len(bars)
    lower = [None] * len(bars)
    for i in range(19, len(bars)):
        win = closes[i - 19:i + 1]
        m = sum(win) / 20.0
        sd = math.sqrt(sum((x - m) ** 2 for x in win) / 20.0)
        upper[i] = m + 2 * sd
        lower[i] = m - 2 * sd
    f["bb_upper"], f["bb_lower"] = upper, lower

    rmax = [None] * len(bars)
    rhi = [None] * len(bars)
    rlo = [None] * len(bars)
    for i in range(167, len(bars)):
        win = closes[i - 167:i + 1]
        rmax[i] = max(win)
        seg = bars[i - 167:i + 1]
        rhi[i] = max(b["h"] for b in seg)
        rlo[i] = min(b["l"] for b in seg)
    f["max168"], f["hi168"], f["lo168"] = rmax, rhi, rlo

    vwap = [None] * len(bars)
    for i in range(24, len(bars)):
        seg = bars[i - 23:i + 1]
        tp = [(b["h"] + b["l"] + b["c"]) / 3.0 for b in seg]
        vv = sum(b["v"] for b in seg)
        if vv > 0:
            m = sum(x * b["v"] for x, b in zip(tp, seg)) / vv
            sd = math.sqrt(sum(b["v"] * (x - m) ** 2 for x, b in zip(tp, seg)) / vv)
            vwap[i] = (m, sd)
    f["vwap"] = vwap
    return f


# --------------------------------------------------------------- hipotesis
HYPOTHESES = [
    ("dip_rsi25", "RSI14 < 25 (oversold murni)"),
    ("dip_rsi30_trend", "RSI14 < 30 dan close > EMA200 (dip dalam tren naik)"),
    ("dip_rsi35_trend", "RSI14 < 35 dan close > EMA200 (varian longgar)"),
    ("dip_ema20_min3pct", "close > EMA200 dan close >=3% di bawah EMA20 (koreksi dalam)"),
    ("pullback_ema20_uptrend", "close>EMA200, low menyentuh EMA20 lalu close di atas EMA20"),
    ("pullback_ema50_uptrend", "close>EMA200, low menyentuh EMA50 lalu close di atas EMA50"),
    ("range_bottom20", "close di 20% bawah rentang 168 bar, masih di atas EMA200"),
    ("bb_lower_touch", "menyentuh Bollinger bawah (20,2) lalu close di atasnya"),
    ("drawdown5pct_7d", "turun >=5% dari puncak 7 hari dan RSI < 40"),
    ("vwap_dev_1_5sd", "close < VWAP24 - 1,5 simpangan baku"),
    ("control_top_rsi70", "KONTROL: RSI14 > 70 (beli di puncak)"),
]


def signals(f, bars, key):
    n = len(bars)
    c = f["close"]
    out = []
    warm = 210
    if key == "dip_rsi25":
        out = [i for i in range(warm, n)
               if f["rsi14"][i] is not None and f["rsi14"][i] < 25]
    elif key == "dip_rsi30_trend":
        out = [i for i in range(warm, n)
               if f["rsi14"][i] is not None and f["ema200"][i] is not None
               and f["rsi14"][i] < 30 and c[i] > f["ema200"][i]]
    elif key == "dip_rsi35_trend":
        out = [i for i in range(warm, n)
               if f["rsi14"][i] is not None and f["ema200"][i] is not None
               and f["rsi14"][i] < 35 and c[i] > f["ema200"][i]]
    elif key == "dip_ema20_min3pct":
        for i in range(warm, n):
            e20, e200 = f["ema20"][i], f["ema200"][i]
            if e20 is None or e200 is None:
                continue
            if c[i] > e200 and c[i] <= e20 * 0.97:
                out.append(i)
    elif key == "pullback_ema20_uptrend":
        for i in range(warm, n):
            e20, e200 = f["ema20"][i], f["ema200"][i]
            if e20 is None or e200 is None:
                continue
            if c[i] > e200 and bars[i]["l"] <= e20 and c[i] > e20:
                out.append(i)
    elif key == "pullback_ema50_uptrend":
        for i in range(warm, n):
            e50, e200 = f["ema50"][i], f["ema200"][i]
            if e50 is None or e200 is None:
                continue
            if c[i] > e200 and bars[i]["l"] <= e50 and c[i] > e50:
                out.append(i)
    elif key == "range_bottom20":
        for i in range(warm, n):
            hi, lo, e200 = f["hi168"][i], f["lo168"][i], f["ema200"][i]
            if hi is None or lo is None or e200 is None or hi <= lo:
                continue
            if (c[i] - lo) / (hi - lo) <= 0.20 and c[i] > e200:
                out.append(i)
    elif key == "bb_lower_touch":
        for i in range(warm, n):
            lb = f["bb_lower"][i]
            if lb is not None and bars[i]["l"] <= lb and c[i] > lb:
                out.append(i)
    elif key == "drawdown5pct_7d":
        for i in range(warm, n):
            mx, r = f["max168"][i], f["rsi14"][i]
            if mx is None or r is None:
                continue
            if c[i] <= mx * 0.95 and r < 40:
                out.append(i)
    elif key == "vwap_dev_1_5sd":
        for i in range(warm, n):
            v = f["vwap"][i]
            if v is None or v[1] <= 0:
                continue
            if c[i] < v[0] - 1.5 * v[1]:
                out.append(i)
    elif key == "control_top_rsi70":
        out = [i for i in range(warm, n)
               if f["rsi14"][i] is not None and f["rsi14"][i] > 70]
    else:
        raise KeyError(key)
    return out


def random_signals(bars, n_signals, seed):
    rng = random.Random(seed)
    lo, hi = 210, len(bars) - 2
    if hi <= lo:
        return []
    return sorted(rng.sample(range(lo, hi), min(n_signals, hi - lo)))


# ------------------------------------------------------------------- exit
# type "fixed_atr": SL & TP = kelipatan ATR saat sinyal.
# type "target"   : TP = level harga tertentu (EMA20/EMA50/band atas/puncak 7 hari),
#                   SL = kelipatan ATR yang lebar (atau None = tanpa SL keras).
EXITS = [
    {"name": "atr1.5/2.0", "type": "fixed_atr", "sl": 1.5, "tp": 2.0},
    {"name": "atr3.0/3.0", "type": "fixed_atr", "sl": 3.0, "tp": 3.0},
    {"name": "atr3.0/5.0", "type": "fixed_atr", "sl": 3.0, "tp": 5.0},
    {"name": "pct_tp3_sl3", "type": "pct", "sl": 3.0, "tp": 3.0},
    {"name": "pct_tp5_sl4", "type": "pct", "sl": 4.0, "tp": 5.0},
    {"name": "pct_tp8_sl6", "type": "pct", "sl": 6.0, "tp": 8.0},
    {"name": "tgt_ema20_sl3", "type": "target", "target": "ema20", "sl_atr": 3.0},
    {"name": "tgt_ema50_sl4", "type": "target", "target": "ema50", "sl_atr": 4.0},
    {"name": "tgt_bbupper_sl3", "type": "target", "target": "bb_upper", "sl_atr": 3.0},
    {"name": "tgt_swinghi7d_sl4", "type": "target", "target": "hi168", "sl_atr": 4.0},
]
HOLDS = {"1h": {"fixed_atr": [24, 72, 168], "pct": [24, 72, 168],
                "target": [168, 336, 504]},
         "4h": {"fixed_atr": [6, 18, 42], "pct": [6, 18, 42],
                "target": [42, 84, 126]},
         "1d": {"fixed_atr": [1, 3, 7], "pct": [1, 3, 7],
                "target": [7, 14, 21]}}


def exit_levels(f, i, entry, spec):
    """Kembalikan (sl_price, tp_price, catatan) untuk satu entri."""
    note = None
    sl_price = None
    if spec["type"] == "fixed_atr":
        a = f["atr14"][i]
        if a is None or a <= 0:
            return None, None, "no_atr"
        sl_pct = spec["sl"] * 100.0 * a / f["close"][i]
        tp_pct = spec["tp"] * 100.0 * a / f["close"][i]
        return entry * (1 - sl_pct / 100.0), entry * (1 + tp_pct / 100.0), note

    if spec["type"] == "pct":
        return (entry * (1 - spec["sl"] / 100.0),
                entry * (1 + spec["tp"] / 100.0), note)

    # type target
    lvl = f[spec["target"]][i]
    if lvl is None:
        return None, None, "no_target"
    if lvl <= entry * 1.002:
        return None, None, "target_below_entry"
    sl_price = None
    if spec.get("sl_atr"):
        a = f["atr14"][i]
        if a is None or a <= 0:
            return None, None, "no_atr"
        sl_pct = spec["sl_atr"] * 100.0 * a / f["close"][i]
        sl_pct = min(sl_pct, 25.0)
        sl_price = entry * (1 - sl_pct / 100.0)
    return sl_price, lvl, note


def simulate(bars, f, sig, spec, hold, friction, min_tp_multiple=2.0,
             same_bar="sl_first", regime=None):
    n = len(bars)
    trades = []
    next_free = 0
    skipped_overlap = skipped_level = skipped_margin = 0
    for i in sig:
        if i + 1 >= n:
            continue
        if i + 1 < next_free:
            skipped_overlap += 1
            continue
        entry = bars[i + 1]["o"]
        sl_price, tp_price, note = exit_levels(f, i, entry, spec)
        if note:
            skipped_level += 1
            continue
        tp_pct = 100.0 * (tp_price - entry) / entry
        # Filter sadar-friksi: target yang tidak menutup 2x biaya bukan trade,
        # melainkan donasi ke bursa. Dilewati, bukan dipaksakan.
        if min_tp_multiple and tp_pct < min_tp_multiple * friction:
            skipped_margin += 1
            continue
        exit_price = reason = exit_i = None
        last = min(i + hold, n - 1)
        for j in range(i + 1, last + 1):
            b = bars[j]
            gap_sl = sl_price is not None and b["o"] <= sl_price
            gap_tp = b["o"] >= tp_price
            hit_sl = sl_price is not None and b["l"] <= sl_price
            hit_tp = b["h"] >= tp_price
            if gap_sl:
                exit_price, reason, exit_i = b["o"], "SL_GAP", j
                break
            if gap_tp:
                exit_price, reason, exit_i = b["o"], "TP_GAP", j
                break
            if hit_sl and hit_tp:
                # Urutan intrabar tidak diketahui dari OHLC. Dua batas dilaporkan:
                # sl_first = pesimistis (bawaan), tp_first = optimistis.
                if same_bar == "tp_first":
                    exit_price, reason, exit_i = tp_price, "TP*", j
                else:
                    exit_price, reason, exit_i = sl_price, "SL*", j
                break
            if hit_sl:
                exit_price, reason, exit_i = sl_price, "SL", j
                break
            if hit_tp:
                exit_price, reason, exit_i = tp_price, "TP", j
                break
        if exit_price is None:
            exit_i = last
            exit_price = bars[last]["c"]
            reason = "TIMEOUT"
        gross = 100.0 * (exit_price - entry) / entry
        win = range(i + 1, last + 1)
        trades.append({
            "i": i, "t": bars[i]["t"], "entry": entry, "exit": exit_price,
            "reason": reason, "bars": exit_i - i, "gross": gross,
            "net": gross - friction,
            "mfe": 100.0 * (max(bars[j]["h"] for j in win) - entry) / entry,
            "mae": 100.0 * (min(bars[j]["l"] for j in win) - entry) / entry,
            "tp_pct": 100.0 * (tp_price - entry) / entry,
            "sl_pct": None if sl_price is None else 100.0 * (entry - sl_price) / entry,
            "pctl": entry_percentile(f, i),
            "regime": regime_at(regime, bars[i]["t"]),
        })
        next_free = exit_i + 1
    return trades, {"signals": len(sig), "skipped_overlap": skipped_overlap,
                    "skipped_level": skipped_level,
                    "skipped_margin": skipped_margin}


def load_btc_regime(data_dir):
    """Peta waktu -> 'bull'/'bear' dari BTC D1 close vs EMA200 D1."""
    path = os.path.join(data_dir, "BTCUSDT_1d.csv")
    if not os.path.exists(path):
        return None
    bars = load_csv(path)
    closes = [b["c"] for b in bars]
    e200 = ema(closes, 200)
    out = []
    for b, e in zip(bars, e200):
        if e is None:
            continue
        out.append((b["t"], "bull" if b["c"] > e else "bear"))
    return out


def regime_at(regime, t_ms):
    if not regime:
        return None
    lo, hi = 0, len(regime) - 1
    idx = None
    while lo <= hi:
        mid = (lo + hi) // 2
        if regime[mid][0] <= t_ms:
            idx = mid
            lo = mid + 1
        else:
            hi = mid - 1
    return None if idx is None else regime[idx][1]


def entry_percentile(f, i):
    win = f["close"][max(0, i - 167):i + 1]
    if len(win) < 120:
        return None
    return percentile_rank(win, f["close"][i])


# ------------------------------------------------------------------ statistik
def bootstrap_ci(vals, reps=600, seed=20261029, alpha=0.05):
    if len(vals) < 5:
        return (None, None)
    rng = random.Random(seed)
    n = len(vals)
    means = [sum(rng.choices(vals, k=n)) / n for _ in range(reps)]
    means.sort()
    return (round(means[int(alpha / 2 * reps)], 4),
            round(means[int((1 - alpha / 2) * reps)], 4))


def summarize(trades, span_days, with_ci=True, with_regime=True):
    if not trades:
        return {"n": 0}
    nets = [t["net"] for t in trades]
    pcts = [t["pctl"] for t in trades if t.get("pctl") is not None]
    reasons = {}
    for t in trades:
        reasons[t["reason"]] = reasons.get(t["reason"], 0) + 1
    return {
        "n": len(trades),
        "net_mean_pct": round(sum(nets) / len(nets), 4),
        "net_median_pct": round(st.median(nets), 4),
        "net_ci95": bootstrap_ci(nets) if with_ci else (None, None),
        "gross_mean_pct": round(sum(t["gross"] for t in trades) / len(trades), 4),
        "win_rate_pct": round(100.0 * sum(1 for x in nets if x > 0) / len(nets), 2),
        "net_per_day_pct": round(sum(nets) / span_days, 4) if span_days > 0 else None,
        "median_hold_bars": st.median([t["bars"] for t in trades]),
        "median_mfe_pct": round(st.median([t["mfe"] for t in trades]), 3),
        "median_mae_pct": round(st.median([t["mae"] for t in trades]), 3),
        "median_tp_pct": round(st.median([t["tp_pct"] for t in trades]), 3),
        "median_sl_pct": round(st.median([t["sl_pct"] for t in trades
                                          if t["sl_pct"] is not None]), 3)
        if any(t["sl_pct"] is not None for t in trades) else None,
        "median_entry_pctl": round(st.median(pcts), 2) if pcts else None,
        "share_entry_pctl_ge80": round(100.0 * sum(1 for p in pcts if p >= 80) / len(pcts), 2)
        if pcts else None,
        "exit_reasons": reasons,
        "by_regime": ({
            r: summarize([t for t in trades if t.get("regime") == r], span_days,
                         with_ci=False, with_regime=False)
            for r in ("bull", "bear")
            if any(t.get("regime") == r for t in trades)
        } if with_regime else {}),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=str(ROOT / "data/tokocrypto_v8"))
    ap.add_argument("--tf", default="1h", choices=["1h", "4h", "1d"])
    ap.add_argument("--pairs", default="")
    ap.add_argument("--split", type=float, default=0.6, help="fraksi bar untuk TRAIN")
    ap.add_argument("--friction", type=float, default=FRICTION_PCT)
    ap.add_argument("--min-n", type=int, default=30)
    ap.add_argument("--min-tp-multiple", type=float, default=2.0,
                    help="TP gross minimal = kelipatan friksi; 0 = nonaktif")
    ap.add_argument("--same-bar", choices=["sl_first", "tp_first"], default="sl_first",
                    help="urutan bila SL dan TP tersentuh di bar yang sama")
    ap.add_argument("--out",
                    default=str(ROOT / "data/hasil/exp_buy_low_entry_v8_1h_regime.json"))
    ap.add_argument("--dump-trades", action="store_true")
    ap.add_argument("--walk-forward", type=int, default=0,
                    help="bagi bar tiap pair menjadi N jendela waktu berurutan; 0 = nonaktif")
    ap.add_argument("--wf-hypotheses", default="dip_rsi25,control_top_rsi70,control_random",
                    help="hipotesis yang dihitung walk-forward-nya (dipisah koma)")
    ap.add_argument("--slim", action="store_true",
                    help="hemat ukuran JSON: rincian per pair tanpa blok rezim "
                         "dan sel dengan n < --min-n tidak menyimpan pair")
    ap.add_argument("--cells", default="",
                    help="opsional: hanya sel ini yang dihitung/disimpan, dipisah koma; "
                         "format hipotesis|exit|hold (mis. dip_rsi25|atr3.0/5.0|18)")
    ap.add_argument("--pairs-for",
                    default="dip_rsi25|atr3.0/5.0|18,dip_rsi25|atr3.0/3.0|18,"
                            "control_top_rsi70|atr3.0/5.0|18,"
                            "control_random|atr3.0/5.0|18,"
                            "dip_ema20_min3pct|tgt_bbupper_sl3|168,"
                            "dip_rsi30_trend|tgt_ema50_sl4|168,"
                            "dip_rsi25|tgt_ema20_sl3|21",
                    help="hipotesis yang rincian per pair-nya tetap disimpan saat --slim; "
                         "boleh nama hipotesis (dip_rsi25) atau sel penuh "
                         "(dip_rsi25|atr3.0/5.0|18)")
    args = ap.parse_args()
    wf_hyp = set(h.strip() for h in args.wf_hypotheses.split(",") if h.strip())
    pairs_for = set(h.strip() for h in args.pairs_for.split(",") if h.strip())
    cells_sel = set(c.strip() for c in args.cells.split(",") if c.strip())
    if args.walk_forward == 1:
        sys.exit("--walk-forward minimal 2 jendela")

    pairs = set(p.strip().upper() for p in args.pairs.split(",") if p.strip()) or None
    files = pair_files(args.data, args.tf, pairs)
    if not files:
        sys.exit(f"tidak ada berkas *_{args.tf}.csv di {args.data}")

    holds = HOLDS[args.tf]
    result = {
        "meta": {
            "data": str(Path(args.data).resolve()), "tf": args.tf,
            "pairs": [p for p, _ in files], "friction_pct": args.friction,
            "friction_note": "Tokocrypto USDT all-in: fee 0,4044% x2 + slippage 0,08% x2",
            "split_train_frac": args.split, "exits": EXITS, "holds_bars": holds,
            "hypotheses": [{"key": k, "desc": d} for k, d in HYPOTHESES],
            "entry_timing": "open bar sinyal+1",
            "same_bar_rule": "SL dianggap kena lebih dulu bila SL dan TP tersentuh di bar yang sama",
            "warmup_bars": 210, "same_bar": args.same_bar,
            "btc_regime": "BTC D1 close vs EMA200 D1",
            "min_tp_multiple": args.min_tp_multiple,
            "walk_forward_windows": args.walk_forward,
        },
        "grid": {}, "per_pair": {}, "trades": [],
    }

    loaded = []
    for name, path in files:
        bars = load_csv(path)
        loaded.append((name, bars, build_features(bars)))
    span_pair = (loaded[0][1][-1]["t"] - loaded[0][1][0]["t"]) / 86400000.0
    span_all = span_pair * len(loaded)
    print(f"{args.tf}: {len(loaded)} pair, {span_pair:.0f} hari/pair", flush=True)

    btc_regime = load_btc_regime(args.data)
    print(f"rezim BTC: {'tersedia' if btc_regime else 'TIDAK ADA'}", flush=True)
    sig_cache = {}

    def sig_for(name, bars, f, key):
        ck = (name, key)
        if ck not in sig_cache:
            sig_cache[ck] = signals(f, bars, key)
        return sig_cache[ck]

    # kontrol acak: jumlah sinyal disamakan dengan median sinyal hipotesis nyata
    med_sig = st.median([len(sig_for(nm, b, ff, k))
                         for nm, b, ff in loaded for k, _ in HYPOTHESES
                         if k != "control_top_rsi70"]) or 100
    random_sig = {}
    for idx, (name, bars, f) in enumerate(loaded):
        random_sig[name] = random_signals(bars, int(med_sig), 20261029 + idx)

    work = [(k, d, "hyp") for k, d in HYPOTHESES]
    work.append(("control_random", "KONTROL: entri acak ber-seed", "random"))

    for key, desc, kind in work:
        for spec in EXITS:
            for hold in holds[spec["type"]]:
                gkey = f"{key}|{spec['name']}|{hold}"
                if cells_sel and gkey not in cells_sel:
                    continue
                agg = {"train": [], "test": [], "all": [], "pairs": {}}
                wf_on = args.walk_forward > 1 and key in wf_hyp
                wf_bins = [ [] for _ in range(args.walk_forward) ] if wf_on else None
                for name, bars, f in loaded:
                    k = int(len(bars) * args.split)
                    sig = (random_sig[name] if kind == "random"
                           else sig_for(name, bars, f, key))
                    tr_all, inf = simulate(bars, f, sig, spec, hold, args.friction,
                                           args.min_tp_multiple, args.same_bar,
                                           btc_regime)
                    agg["train"].extend([t for t in tr_all if t["i"] < k])
                    agg["test"].extend([t for t in tr_all if t["i"] >= k])
                    agg["all"].extend(tr_all)
                    if wf_on:
                        nb = len(bars)
                        for t in tr_all:
                            w = min(args.walk_forward - 1,
                                    int(t["i"] * args.walk_forward / nb))
                            wf_bins[w].append(t)
                    if args.dump_trades:
                        for t in tr_all:
                            result["trades"].append({
                                "pair": name, "hyp": key, "exit": spec["name"],
                                "hold": hold, "t": t["t"], "entry": t["entry"],
                                "net": round(t["net"], 4), "reason": t["reason"],
                                "pctl": t["pctl"],
                            })
                    if len(tr_all) > 0:
                        s = summarize(tr_all, span_pair, with_ci=False,
                                      with_regime=not args.slim)
                        s.update(inf)
                        agg["pairs"][name] = s
                agg["train_summary"] = summarize(agg["train"], span_all)
                agg["test_summary"] = summarize(agg["test"], span_all)
                agg["all_summary"] = summarize(agg["all"], span_all)
                nets = [v["net_mean_pct"] for v in agg["pairs"].values()
                        if v.get("n", 0) >= 10]
                agg["pairs_positive"] = sum(1 for x in nets if x > 0)
                agg["pairs_measured"] = len(nets)
                agg["pairs_median_net"] = round(st.median(nets), 4) if nets else None
                if (args.slim
                        and (agg["all_summary"].get("n", 0) < args.min_n
                             or not (key in pairs_for or gkey in pairs_for))):
                    # sel dengan sampel terlalu kecil / di luar daftar --pairs-for:
                    # rincian per pair tidak disimpan (regenerasi: jalankan tanpa --slim)
                    agg["pairs"] = {}
                if args.slim and agg["all_summary"].get("n", 0) < args.min_n:
                    # sel di bawah ambang sampel tidak disimpan sama sekali
                    continue
                del agg["train"], agg["test"], agg["all"]
                result["grid"][gkey] = agg
                if wf_on:
                    result.setdefault("walk_forward", {})[gkey] = [
                        summarize(b, span_all / args.walk_forward) for b in wf_bins]
        print(f"  selesai {key}", flush=True)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w") as fh:
        json.dump(result, fh, indent=1)
    print(f"\nhasil JSON: {out}")

    rows = []
    for gkey, v in result["grid"].items():
        s = v["all_summary"]
        if s.get("n", 0) < args.min_n:
            continue
        rows.append((s["net_mean_pct"], gkey, s, v))
    rows.sort(reverse=True, key=lambda x: x[0])
    print(f"\n{'hipotesis':<24}{'exit':<20}{'hold':>5}{'n':>6}{'net/trade':>11}"
          f"{'CI95':>20}{'WR%':>7}{'pctl':>7}{'tp%':>7}{'+pair':>8}")
    for net, gkey, s, v in rows:
        key, exit_name, hold = gkey.split("|")
        print(f"{key:<24}{exit_name:<20}{hold:>5}{s['n']:>6}{net:>11.4f}"
              f"{str(s['net_ci95']):>20}{s['win_rate_pct']:>7.1f}"
              f"{str(s['median_entry_pctl']):>7}{s['median_tp_pct']:>7.2f}"
              f"{v['pairs_positive']:>4}/{v['pairs_measured']:<3}")


    if result.get("walk_forward"):
        print(f"\nwalk-forward {args.walk_forward} jendela (berurutan, per pair):")
        for gkey, bins in result["walk_forward"].items():
            cells = [f"{b['net_mean_pct']:+.2f}({b['n']})" if b.get("n") else "n=0"
                     for b in bins]
            pos = sum(1 for b in bins if b.get("n") and b["net_mean_pct"] > 0)
            print(f"  {gkey:<40} " + " | ".join(cells)
                  + f"  -> {pos}/{len(bins)} positif")


if __name__ == "__main__":
    main()
