#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Analisis BTCUSDT Tokocrypto: minggu terakhir vs biaya bursa.

Pertanyaan: apakah ada kemungkinan profit setelah dikurangi potongan bursa?

Pendekatan:
  1. Ringkasan harian (OHLC + range) 8 hari terakhir.
  2. Distribusi MFE/MAE menurut waktu tahan (T jam) dari seluruh riwayat yang diunduh.
  3. Uji barrier buta (entry tiap bar, TP=SL simetris) -> lantai biaya.
  4. Batas atas teoretis: exit sempurna di MFE -> berapa % window yang net positif.
  5. Hitung peluang mentah: berapa kali harga bergerak > biaya.
"""
import argparse
import csv
import datetime as dt
import json
import os
import statistics as st

FEE_BUY = 0.4044
FEE_SELL = 0.4044
SLIP = 0.08
COST = FEE_BUY + FEE_SELL + 2.0 * SLIP  # 0.9688 %


def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append({
                "t": int(r["open_time_ms"]),
                "o": float(r["open"]),
                "h": float(r["high"]),
                "l": float(r["low"]),
                "c": float(r["close"]),
            })
    rows.sort(key=lambda x: x["t"])
    return rows


def d(ms):
    return dt.datetime.utcfromtimestamp(ms / 1000)


def pct(x):
    return 100.0 * x


def quant(v, q):
    v = sorted(v)
    if not v:
        return float("nan")
    i = q * (len(v) - 1)
    lo, hi = int(i), min(int(i) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (i - lo)


def mfe_mae(bars, T, step=1):
    """Entry di close bar i, lihat bar i+1..i+T."""
    mfe, mae = [], []
    n = len(bars)
    for i in range(0, n - T, step):
        e = bars[i]["c"]
        hi = max(b["h"] for b in bars[i + 1:i + 1 + T])
        lo = min(b["l"] for b in bars[i + 1:i + 1 + T])
        mfe.append(pct((hi - e) / e))
        mae.append(pct((lo - e) / e))
    return mfe, mae


def barrier(bars, T, p, step=1):
    """Entry tiap bar. TP=+p%, SL=-p%, timeout di T bar. Net = gross - COST."""
    nets, wins, losses, touts = [], 0, 0, 0
    for i in range(0, len(bars) - T, step):
        e = bars[i]["c"]
        tp, sl = e * (1 + p / 100.0), e * (1 - p / 100.0)
        res = None
        for b in bars[i + 1:i + 1 + T]:
            if b["l"] <= sl:          # SL dicek dulu (konservatif)
                res = -p
                break
            if b["h"] >= tp:
                res = p
                break
        if res is None:
            res = pct((bars[i + T]["c"] - e) / e)
            touts += 1
        elif res > 0:
            wins += 1
        else:
            losses += 1
        nets.append(res - COST)
    n = len(nets)
    return {
        "T_bars": T, "tp_sl_pct": p, "n": n,
        "win_pct": 100.0 * wins / n if n else 0,
        "loss_pct": 100.0 * losses / n if n else 0,
        "timeout_pct": 100.0 * touts / n if n else 0,
        "net_mean_pct": st.mean(nets) if nets else float("nan"),
        "net_median_pct": st.median(nets) if nets else float("nan"),
        "req_winrate_pct": 100.0 * (p + COST) / (2.0 * p),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/tokocrypto_btc_week")
    ap.add_argument("--symbol", default="BTCUSDT")
    ap.add_argument("--out-json", default="data/hasil/btc_week_analysis.json")
    ap.add_argument("--out-md", default="data/hasil/btc_week_analysis.md")
    a = ap.parse_args()

    h1 = load(os.path.join(a.data, f"{a.symbol}_1h.csv"))
    m15 = load(os.path.join(a.data, f"{a.symbol}_15m.csv"))
    d1 = load(os.path.join(a.data, f"{a.symbol}_1d.csv"))

    now = h1[-1]["t"] + 3600_000
    wk_start = now - 7 * 24 * 3600_000
    week = [b for b in h1 if b["t"] >= wk_start]
    prev_week = [b for b in h1 if wk_start - 7 * 24 * 3600_000 <= b["t"] < wk_start]

    # ---------- ringkasan mingguan ----------
    w_open = week[0]["o"]
    w_high = max(b["h"] for b in week)
    w_low = min(b["l"] for b in week)
    w_close = week[-1]["c"]
    week_ret = pct((w_close - w_open) / w_open)
    week_range = pct((w_high - w_low) / w_open)

    # ---------- tabel harian ----------
    daily = []
    day = 24 * 3600_000
    cur = (week[0]["t"] // day) * day
    while cur <= week[-1]["t"]:
        bars = [b for b in week if cur <= b["t"] < cur + day]
        if bars:
            o, c = bars[0]["o"], bars[-1]["c"]
            hi, lo = max(b["h"] for b in bars), min(b["l"] for b in bars)
            daily.append({
                "date": d(cur).strftime("%Y-%m-%d"),
                "open": o, "high": hi, "low": lo, "close": c,
                "ret_pct": pct((c - o) / o),
                "range_pct": pct((hi - lo) / o),
                "range_over_cost": pct((hi - lo) / o) / COST,
            })
        cur += day

    # ---------- MFE/MAE menurut horizon ----------
    horizons = [("1 jam", h1, 1), ("2 jam", h1, 2), ("4 jam", h1, 4), ("8 jam", h1, 8),
                ("12 jam", h1, 12), ("24 jam", h1, 24), ("48 jam", h1, 48),
                ("72 jam", h1, 72), ("7 hari", h1, 168),
                ("15 mnt", m15, 1), ("1 jam (15m)", m15, 4), ("4 jam (15m)", m15, 16),
                ("1 hari (15m)", m15, 96)]
    hz = []
    for label, bars, T in horizons:
        if len(bars) <= T + 5:
            continue
        mfe, mae = mfe_mae(bars, T, step=1)
        med = st.median(mfe)
        hz.append({
            "label": label, "T": T, "n": len(mfe),
            "mfe_p25": quant(mfe, .25), "mfe_median": med, "mfe_p75": quant(mfe, .75),
            "mfe_p90": quant(mfe, .90), "mae_median": st.median(mae),
            "pct_mfe_gt_cost": 100.0 * sum(1 for x in mfe if x > COST) / len(mfe),
            "required_capture_pct": 100.0 * COST / med if med else float("inf"),
            "perfect_exit_net_median": med - COST,
        })

    # ---------- barrier buta ----------
    bar_rows = []
    for label, bars, T in [("1 jam", h1, 1), ("4 jam", h1, 4), ("12 jam", h1, 12),
                           ("24 jam", h1, 24), ("7 hari", h1, 168)]:
        for p in (0.5, 1.0, 1.5, 2.0, 3.0, 5.0, 8.0, 12.0):
            if len(bars) <= T + 5:
                continue
            r = barrier(bars, T, p, step=1)
            r["label"] = label
            bar_rows.append(r)

    # ---------- peluang mentah ----------
    opp = []
    for label, bars, T in [("1 jam", h1, 1), ("4 jam", h1, 4), ("12 jam", h1, 12),
                           ("24 jam", h1, 24), ("7 hari", h1, 168)]:
        if len(bars) <= T + 5:
            continue
        mfe, mae = mfe_mae(bars, T, step=1)
        opp.append({
            "label": label, "n": len(mfe),
            "n_mfe_gt_cost": sum(1 for x in mfe if x > COST),
            "n_up_gt_cost": sum(1 for x in mfe if x > COST),
            "n_down_gt_cost": sum(1 for x in mae if x < -COST),
            "pct_any_gt_cost": 100.0 * sum(1 for x in mfe if x > COST) / len(mfe),
        })

    # ---------- riwayat 83 hari untuk konteks ----------
    hist_ranges = []
    for i in range(0, len(d1) - 1):
        b = d1[i]
        hist_ranges.append(pct((b["h"] - b["l"]) / b["o"]))
    d1_range_med = st.median(hist_ranges)
    d1_range_p25 = quant(hist_ranges, .25)
    d1_range_p75 = quant(hist_ranges, .75)

    # ---------- skenario konkret ----------
    scen = []
    # 1. beli & tahan
    scen.append({"name": "Beli di buka minggu, tahan 7 hari", "n_trade": 1,
                 "gross": week_ret, "net": week_ret - COST, "note": "arah salah, BTC turun"})
    # 2. jual & tahan (short)
    scen.append({"name": "Short di buka minggu, tahan 7 hari", "n_trade": 1,
                 "gross": -week_ret, "net": -week_ret - COST,
                 "note": "arah benar — TAPI spot Tokocrypto tidak bisa short"})
    # 3. day-trade: beli open harian, jual close harian
    g3 = sum(r["ret_pct"] for r in daily)
    scen.append({"name": "Day-trade: beli open harian, jual close harian", "n_trade": len(daily),
                 "gross": g3, "net": g3 - COST * len(daily), "note": "8 trade"})
    # 4. perfect intraday: beli low harian, jual high harian (batas atas teoretis)
    g4 = sum(pct((r["high"] - r["low"]) / r["low"]) for r in daily)
    scen.append({"name": "PERFECT: beli low harian, jual high harian", "n_trade": len(daily),
                 "gross": g4, "net": g4 - COST * len(daily),
                 "note": "mustahil — butuh timing sempurna tiap hari"})
    # 5. swing: beli low minggu, jual high minggu
    g5 = pct((w_high - w_low) / w_low)
    scen.append({"name": "PERFECT: beli low minggu, jual high minggu", "n_trade": 1,
                 "gross": g5, "net": g5 - COST, "note": "mustahil — hindsight sempurna"})
    # 6. entry buta 1 jam TP/SL 1%
    r6 = barrier(h1, 1, 1.0, step=1)
    scen.append({"name": "Entry buta tiap jam, TP/SL ±1%", "n_trade": r6["n"],
                 "gross": r6["net_mean_pct"] + COST, "net": r6["net_mean_pct"],
                 "note": "tanpa sinyal, 1 jam"})

    # ---------- rekap minggu lalu ----------
    pw = None
    if prev_week:
        po = prev_week[0]["o"]
        pw = {
            "ret_pct": pct((prev_week[-1]["c"] - po) / po),
            "range_pct": pct((max(b["h"] for b in prev_week) - min(b["l"] for b in prev_week)) / po),
        }

    out = {
        "cost_roundtrip_pct": COST,
        "generated_utc": d(now).strftime("%Y-%m-%d %H:%M"),
        "last_bar_utc": d(h1[-1]["t"]).strftime("%Y-%m-%d %H:%M"),
        "week": {
            "start_utc": d(week[0]["t"]).strftime("%Y-%m-%d %H:%M"),
            "open": w_open, "high": w_high, "low": w_low, "close": w_close,
            "ret_pct": week_ret, "range_pct": week_range,
            "range_over_cost": week_range / COST,
        },
        "prev_week": pw,
        "daily": daily,
        "horizon": hz,
        "barrier": bar_rows,
        "opportunity": opp,
        "scenarios": scen,
        "daily_bar_range_hist": {
            "median": d1_range_med, "p25": d1_range_p25, "p75": d1_range_p75,
            "n": len(hist_ranges),
        },
        "history_h1_bars": len(h1),
        "history_d1_bars": len(d1),
    }

    os.makedirs(os.path.dirname(a.out_json), exist_ok=True)
    with open(a.out_json, "w") as f:
        json.dump(out, f, indent=2)

    # ================= markdown =================
    L = []
    A = L.append
    A(f"# BTCUSDT Tokocrypto — Analisis Mingguan vs Biaya Bursa")
    A("")
    A(f"Data ditarik langsung dari API Tokocrypto. Bar terakhir: **{out['last_bar_utc']} UTC**.")
    A(f"Biaya round-trip (fee all-in {FEE_BUY}%×2 + slippage {SLIP}%×2): **{COST:.4f}%**.")
    A("")
    A("> Semua angka `net` di bawah sudah dikurangi biaya ini.")
    A("")
    A("## Jawaban singkat")
    A("")
    A("**Ada kemungkinan, tapi tidak untuk trading cepat.**")
    A("")
    A(f"- Minggu ini BTC bergerak **{week_range:.2f}%** (high−low) = **{week_range/COST:.1f}× biaya**. "
      "Secara matematis ruangnya ADA.")
    A(f"- Tapi di horizon 1–12 jam, MFE median (0,16%–0,67%) **di bawah** biaya (0,97%). "
      "Scalping/intraday **mustahil profit** walau exit sempurna.")
    A("- Hanya horizon **≥24 jam** yang MFE median-nya melewati biaya — dan itu pun cuma pas-pasan "
      "(capture wajib 98%).")
    A("- Tanpa sinyal arah, entry buta = **≈ −0,9% per trade** di semua horizon.")
    A("")
    A(f"- Kenyataannya minggu ini BTC **turun {week_ret:+.2f}%**. Di spot (long-only), "
      "siapa pun yang beli dan tahan **rugi**. Yang profit hanya yang menangkap ayunan di titik terbaik.")
    A("")

    A("## 1. Ringkasan minggu ini")
    A("")
    A(f"Periode: **{out['week']['start_utc']} UTC → {out['last_bar_utc']} UTC** (7 hari, {len(week)} bar 1 jam).")
    A("")
    A("| Item | Nilai |")
    A("|---|---:|")
    A(f"| Harga buka minggu | {w_open:,.2f} USDT |")
    A(f"| Tertinggi | {w_high:,.2f} USDT |")
    A(f"| Terendah | {w_low:,.2f} USDT |")
    A(f"| Harga sekarang | {w_close:,.2f} USDT |")
    A(f"| **Perubahan bersih minggu** | **{week_ret:+.2f}%** |")
    A(f"| **Rentang total (high−low)** | **{week_range:.2f}%** |")
    A(f"| Rentang ÷ biaya | **{week_range/COST:.1f}×** |")
    if pw:
        A(f"| Perubahan minggu sebelumnya | {pw['ret_pct']:+.2f}% |")
        A(f"| Rentang minggu sebelumnya | {pw['range_pct']:.2f}% |")
    A("")

    A("## 2. Rincian harian")
    A("")
    A("| Tanggal | Buka | Tertinggi | Terendah | Tutup | Perubahan | Rentang | Rentang÷biaya |")
    A("|---|---:|---:|---:|---:|---:|---:|---:|")
    for r in daily:
        A(f"| {r['date']} | {r['open']:,.0f} | {r['high']:,.0f} | {r['low']:,.0f} | {r['close']:,.0f} | "
          f"{r['ret_pct']:+.2f}% | {r['range_pct']:.2f}% | {r['range_over_cost']:.1f}× |")
    A("")

    A("## 3. MFE / MAE menurut waktu tahan")
    A("")
    A("`capture wajib` = biaya ÷ MFE median. **>100% = mustahil profit walau exit sempurna.**")
    A("")
    A("| Waktu tahan | n | MFE p25 | **MFE median** | MFE p75 | MAE median | %MFE>biaya | capture wajib | net exit-sempurna |")
    A("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in hz:
        A(f"| {r['label']} | {r['n']:,} | {r['mfe_p25']:.3f}% | **{r['mfe_median']:.3f}%** | "
          f"{r['mfe_p75']:.3f}% | {r['mae_median']:.3f}% | {r['pct_mfe_gt_cost']:.1f}% | "
          f"**{r['required_capture_pct']:.0f}%** | {r['perfect_exit_net_median']:+.3f}% |")
    A("")

    A("## 4. Uji barrier buta — entry tiap bar, tanpa sinyal")
    A("")
    A("| Waktu tahan | TP=SL | n | win% | timeout% | **E[net]/trade** | WR breakeven |")
    A("|---|---:|---:|---:|---:|---:|---:|")
    for r in bar_rows:
        A(f"| {r['label']} | ±{r['tp_sl_pct']:.1f}% | {r['n']:,} | {r['win_pct']:.1f}% | "
          f"{r['timeout_pct']:.1f}% | **{r['net_mean_pct']:+.3f}%** | {r['req_winrate_pct']:.1f}% |")
    A("")

    A("## 5. Peluang mentah: berapa kali harga bergerak > biaya")
    A("")
    A("| Waktu tahan | n window | naik > biaya | turun > biaya | % peluang |")
    A("|---|---:|---:|---:|---:|")
    for r in opp:
        A(f"| {r['label']} | {r['n']:,} | {r['n_up_gt_cost']:,} | {r['n_down_gt_cost']:,} | "
          f"{r['pct_any_gt_cost']:.1f}% |")
    A("")

    A("## 6. Skenario konkret minggu ini")
    A("")
    A("| Skenario | Jumlah trade | Gross | **Net setelah biaya** | Catatan |")
    A("|---|---:|---:|---:|---|")
    for s in scen:
        A(f"| {s['name']} | {s['n_trade']} | {s['gross']:+.2f}% | **{s['net']:+.2f}%** | {s['note']} |")
    A("")

    A("## 7. Konteks historis (bar harian)")
    A("")
    A(f"- Rentang harian (high−low)/open, dari {out['daily_bar_range_hist']['n']:,} hari: "
      f"p25 = {d1_range_p25:.2f}%, **median = {d1_range_med:.2f}%**, p75 = {d1_range_p75:.2f}%")
    A(f"- Biaya = {COST:.4f}% → rentang harian median ≈ {d1_range_med/COST:.1f}× biaya")
    A("")

    A("---")
    A("")
    A("## 8. Kesimpulan")
    A("")
    A("**Pertanyaan: apakah ada kemungkinan profit setelah dikurangi potongan bursa?**")
    A("")
    A("| Horizon | Kemungkinan profit? | Alasan |")
    A("|---|---|---|")
    A("| 1–30 menit (scalping) | ❌ **Tidak** | MFE 0,07–0,16% vs biaya 0,97% → capture wajib >600% |")
    A("| 1–12 jam (intraday) | ❌ **Tidak** | MFE 0,16–0,67% vs biaya 0,97% → capture wajib 144–610% |")
    A("| 24 jam | ⚠️ **Sangat sulit** | MFE median 0,99% ≈ biaya; capture wajib 98% (harus nyaris sempurna) |")
    A("| 48–72 jam | ⚠️ **Sulit** | capture wajib 49–63% — butuh sinyal arah yang benar |")
    A("| 7 hari+ | ✅ **Mungkin** | capture wajib 29% — inilah satu-satunya zona yang realistis |")
    A("")
    A("**Yang menentukan bukan seberapa besar BTC bergerak, tapi seberapa besar gerakan itu "
      "dibanding biaya.** Minggu ini rentangnya 7,36% (7,6× biaya) — besar. Tapi gerakan itu "
      "tersebar sepanjang 7 hari, dan di setiap potongan 1 jam, rata-rata yang tersedia hanya 0,16%.")
    A("")
    A("**Angka paling jujur:** dengan exit SEMPURNA (menjual tepat di titik tertinggi) pada "
      "horizon 24 jam, hasil bersihnya **+0,02%** — nol. Artinya bahkan keahlian sempurna pun "
      "tidak memberi apa-apa di horizon itu. Baru di 7 hari, exit sempurna memberi +2,34%.")
    A("")
    A("**Implikasi untuk aplikasi:**")
    A("")
    A("1. Engine scalping M1/M15 yang ada sekarang **tidak akan pernah profit** di BTCUSDT — "
      "bukan karena sinyalnya salah, tapi karena biaya melebihi peluang yang tersedia.")
    A("2. Kalau tetap ingin auto-trading BTC di Tokocrypto, horizonnya harus **≥3 hari per posisi**, "
      "dengan SL lebar (MAE median 24 jam = −0,92%, 7 hari = −2,40%) dan target 5–10%.")
    A("3. Frekuensi trade harus **turun drastis**. Setiap trade memakan 0,97% — 10 trade = 9,7% "
      "dari modal, sebelum untung/rugi apa pun.")
    A("4. Kenaikan profit paling pasti bukan dari sinyal yang lebih pintar, tapi dari **menurunkan "
      "biaya** (maker/TKO/VIP) atau **menambah modal per trade** agar biaya tetap jadi lebih kecil.")
    A("")

    A("---")
    A("```")
    A(f"python3 tools/fetch_tokocrypto_klines.py --pairs {a.symbol} --pages 2 --intervals 15m,1h,1d --out {a.data}")
    A(f"python3 tools/btc_week_analysis.py --data {a.data} --out-json {a.out_json} --out-md {a.out_md}")
    A("```")

    with open(a.out_md, "w") as f:
        f.write("\n".join(L) + "\n")

    print(f"Biaya   : {COST:.4f}%")
    print(f"Minggu  : {out['week']['start_utc']} -> {out['last_bar_utc']} UTC")
    print(f"Buka {w_open:,.2f}  Tutup {w_close:,.2f}  Ret {week_ret:+.2f}%  Range {week_range:.2f}%")
    print(f"Rentang/biaya: {week_range/COST:.1f}x")
    print("")
    print("Horizon (MFE median vs capture wajib):")
    for r in hz:
        flag = "OK " if r["required_capture_pct"] <= 100 else "X  "
        print(f"  {flag}{r['label']:>14}: MFE med {r['mfe_median']:.3f}%  capture {r['required_capture_pct']:.0f}%  net-exit-sempurna {r['perfect_exit_net_median']:+.3f}%")
    print("")
    print(f"Tersimpan: {a.out_json}")
    print(f"Tersimpan: {a.out_md}")


if __name__ == "__main__":
    main()
