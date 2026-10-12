#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Grafik analisis BTCUSDT mingguan vs biaya bursa."""
import csv
import datetime as dt
import json
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates

COST = 0.9688
REPO = "/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d45-960a81c67000/repo"


def load(p):
    rows = []
    with open(p) as f:
        for r in csv.DictReader(f):
            rows.append({"t": int(r["open_time_ms"]), "o": float(r["open"]),
                         "h": float(r["high"]), "l": float(r["low"]), "c": float(r["close"])})
    return sorted(rows, key=lambda x: x["t"])


d = json.load(open(f"{REPO}/data/hasil/btc_week_analysis.json"))
h1 = load(f"{REPO}/data/tokocrypto_btc_week/BTCUSDT_1h.csv")
now = h1[-1]["t"] + 3600_000
week = [b for b in h1 if b["t"] >= now - 7 * 24 * 3600_000]

fig = plt.figure(figsize=(13, 9.5))
gs = fig.add_gridspec(2, 2, height_ratios=[1.15, 1], hspace=0.34, wspace=0.22,
                      left=0.075, right=0.975, top=0.905, bottom=0.075)

# ---------- A. harga ----------
ax = fig.add_subplot(gs[0, :])
xs = [dt.datetime.utcfromtimestamp(b["t"] / 1000) for b in week]
cs = [b["c"] for b in week]
hi = max(b["h"] for b in week)
lo = min(b["l"] for b in week)
op = week[0]["o"]

ax.fill_between(xs, [b["l"] for b in week], [b["h"] for b in week],
                color="#4C6EF5", alpha=0.13, lw=0)
ax.plot(xs, cs, color="#2B3A67", lw=2.0, label="Harga BTC (close 1 jam)")
ax.axhline(hi, color="#C92A2A", ls="--", lw=1.2)
ax.axhline(lo, color="#2F9E44", ls="--", lw=1.2)
ax.axhline(op, color="#868E96", ls=":", lw=1.2)

# pita biaya di sekitar buka minggu
band = op * COST / 100.0
ax.axhspan(op - band, op + band, color="#F59F00", alpha=0.25, zorder=0)

ax.annotate(f"Tertinggi {hi:,.0f}", xy=(xs[3], hi), xytext=(0, 9),
            textcoords="offset points", color="#C92A2A", fontsize=10, fontweight="bold")
ax.annotate(f"Terendah {lo:,.0f}", xy=(xs[len(xs)//2], lo), xytext=(6, 26),
            textcoords="offset points", color="#2F9E44", fontsize=10, fontweight="bold",
            arrowprops=dict(arrowstyle="->", color="#2F9E44", lw=1.1))
ax.annotate("Pita oranye = lebar biaya round-trip (0,97%)\n"
            "Rentang minggu ini 7,36% = 7,6× biaya → ruang gerak ada",
            xy=(0.015, 0.045), xycoords="axes fraction", fontsize=10.5,
            bbox=dict(boxstyle="round,pad=0.5", fc="#FFF9DB", ec="#F59F00", lw=1.1))

ax.set_title("A.  BTCUSDT Tokocrypto — 7 hari terakhir (5–12 Okt 2026)",
             fontsize=13, fontweight="bold", loc="left")
ax.set_ylabel("USDT")
ax.xaxis.set_major_formatter(mdates.DateFormatter("%d %b\n%H:%M"))
ax.grid(alpha=0.22, ls="--")
ax.legend(loc="upper right", fontsize=10, framealpha=0.92)

# ---------- B. MFE median vs biaya ----------
ax2 = fig.add_subplot(gs[1, 0])
labels, meds, caps = [], [], []
for r in d["horizon"]:
    if r["label"] in ("15 mnt", "1 jam", "4 jam", "12 jam", "24 jam", "48 jam", "72 jam", "7 hari"):
        labels.append(r["label"]); meds.append(r["mfe_median"])
        caps.append(r["required_capture_pct"])
order = [labels.index(x) for x in ("15 mnt", "1 jam", "4 jam", "12 jam", "24 jam", "48 jam", "72 jam", "7 hari")]
labels = [labels[i] for i in order]
meds = [meds[i] for i in order]
cols = ["#2F9E44" if m > COST else "#C92A2A" for m in meds]
b = ax2.bar(range(len(labels)), meds, color=cols, alpha=0.85, width=0.68)
ax2.axhline(COST, color="#F59F00", lw=2.2, ls="--")
ax2.text(-0.42, COST + 0.13, f"biaya {COST:.2f}%", color="#B8860B",
         fontsize=9.5, ha="left", fontweight="bold")
for i, (r, m) in enumerate(zip(b, meds)):
    ax2.text(r.get_x() + r.get_width() / 2, m + 0.08, f"{m:.2f}", ha="center", fontsize=8.6)
ax2.set_xticks(range(len(labels)))
ax2.set_xticklabels(labels, fontsize=9.5)
ax2.set_title("B.  MFE median vs biaya\n(hijau = di atas biaya, merah = di bawah)",
              fontsize=11.5, fontweight="bold", loc="left")
ax2.set_ylabel("MFE median (%)")
ax2.grid(alpha=0.22, ls="--", axis="y")

# ---------- C. capture wajib ----------
ax3 = fig.add_subplot(gs[1, 1])
caps_o = [caps[i] for i in order]
colc = ["#C92A2A" if c > 100 else "#2F9E44" for c in caps_o]
ax3.bar(range(len(labels)), caps_o, color=colc, alpha=0.85, width=0.68)
ax3.axhline(100, color="#212529", lw=2.0, ls="--")
ax3.text(len(labels) - 0.45, 250, "100% = batas mustahil", color="#212529",
         fontsize=9.5, ha="right", fontweight="bold")
ax3.set_yscale("log")
for i, c in enumerate(caps_o):
    ax3.text(i, c * 1.18, f"{c:.0f}%", ha="center", fontsize=8.6)
ax3.set_xticks(range(len(labels)))
ax3.set_xticklabels(labels, fontsize=9.5)
ax3.set_title("C.  Capture wajib = biaya ÷ MFE median\n(>100% artinya mustahil, skala log)",
              fontsize=11.5, fontweight="bold", loc="left")
ax3.set_ylabel("Capture wajib (%)")
ax3.grid(alpha=0.22, ls="--", axis="y")

fig.suptitle("BTCUSDT: ruang gerak ada, tapi hanya di horizon ≥24 jam — dan itu pun pas-pasan",
             fontsize=14.5, fontweight="bold", y=0.968)
fig.savefig("/workspace/BTC-mingguan-analisis.png", dpi=145)
print("Tersimpan: /workspace/BTC-mingguan-analisis.png")
