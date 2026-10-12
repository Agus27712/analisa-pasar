#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Grafik P6 (saringan pair) + P7 (uji edge)."""
import json
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

R = "/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d45-960a81c67000/repo"
p6 = json.load(open(f"{R}/data/hasil/p6_pair_screen.json"))
p7 = json.load(open(f"{R}/data/hasil/p7_entry_edge_altcoin.json"))
COST = 0.9688

fig = plt.figure(figsize=(13.5, 10))
gs = fig.add_gridspec(2, 2, height_ratios=[1.25, 1], hspace=0.33, wspace=0.24,
                      left=0.10, right=0.975, top=0.905, bottom=0.075)

# ---------- A. capture wajib per pair ----------
ax = fig.add_subplot(gs[0, :])
rank = p6["ranking_7d"]
sel = [r for r in rank if r["symbol"] != "BTCUSDT"][:16]
btc = next((r for r in rank if r["symbol"] == "BTCUSDT"), None)
rows = sel + ([btc] if btc else [])
rows = rows[::-1]
names = [r["symbol"].replace("USDT", "") for r in rows]
c1 = [r["capture_1d_pct"] for r in rows]
c7 = [r["capture_7d_pct"] for r in rows]
y = range(len(rows))
h = 0.38
ax.barh([i + h / 2 for i in y], c1, height=h, color="#E8590C", alpha=0.9,
        label="capture wajib @ 1 hari")
ax.barh([i - h / 2 for i in y], c7, height=h, color="#1971C2", alpha=0.9,
        label="capture wajib @ 7 hari")
ax.axvline(100, color="#212529", lw=2.0, ls="--")
ax.text(104, len(rows) - 0.5, "100% = batas mustahil", fontsize=9.5,
        color="#212529", fontweight="bold")
for i, r in enumerate(rows):
    if r["symbol"] == "BTCUSDT":
        ax.text(r["capture_1d_pct"] + 3, i + h, "◀ BTC — butuh 76% dari MFE",
                fontsize=10, fontweight="bold", color="#C92A2A", va="center")
ax.set_yticks(list(y))
ax.set_yticklabels(names, fontsize=9.5)
ax.set_xlabel("capture wajib (%) = biaya ÷ MFE median — makin kecil makin baik", fontsize=10)
ax.set_title("A.  Ruang gerak per pair: altcoin jauh lebih lega daripada BTC",
             fontsize=13, fontweight="bold", loc="left")
ax.grid(alpha=0.2, ls="--", axis="x")
ax.legend(fontsize=9.5, loc="center right")

# ---------- B. funnel gate ----------
ax2 = fig.add_subplot(gs[1, 0])
stages = ["Semua uji", "Naif\ngross>biaya", "Alpha\n>drift pasar", "Bonferroni", "Tahan\n1/3 akhir"]
vals = [p7["n_tests"], p7["n_passed_naive"], p7["n_passed_alpha"],
        p7["n_passed_bonferroni"], p7["n_robust"]]
cols = ["#868E96", "#F59F00", "#4C6EF5", "#C92A2A", "#C92A2A"]
b = ax2.bar(range(len(stages)), vals, color=cols, alpha=0.9, width=0.62)
for r, v in zip(b, vals):
    ax2.text(r.get_x() + r.get_width() / 2, v + 4, str(v), ha="center",
             fontsize=12, fontweight="bold")
ax2.set_xticks(range(len(stages)))
ax2.set_xticklabels(stages, fontsize=9.5)
ax2.set_ylabel("Jumlah kombinasi lolos")
ax2.set_ylim(0, 235)
ax2.set_title("B.  201 kombinasi diuji → 0 bertahan", fontsize=12,
              fontweight="bold", loc="left")
ax2.grid(alpha=0.2, ls="--", axis="y")

# ---------- C. scatter alpha train vs test ----------
ax3 = fig.add_subplot(gs[1, 1])
xs = [r["alpha_train"] for r in p7["results"]]
ys = [r["alpha_test"] for r in p7["results"]]
ax3.axhline(0, color="#495057", lw=1.2)
ax3.axvline(0, color="#495057", lw=1.2)
ax3.axhspan(0, 26, xmin=0.5, xmax=1.0, color="#2F9E44", alpha=0.10)
ax3.scatter(xs, ys, s=26, c="#4C6EF5", alpha=0.6, edgecolors="none")
# sorot yang alpha train & test positif
offs = {"ENAUSDT|mom30": (8, 5), "ENAUSDT|sma50": (8, -14), "ZECUSDT|vol_spike": (-72, 6),
        "NEARUSDT|sma50": (-78, -4)}
for r in p7["results"]:
    if r["alpha_train"] > 0 and r["alpha_test"] > 0 and r["alpha_test"] > 8:
        ax3.scatter([r["alpha_train"]], [r["alpha_test"]], s=60, c="#2F9E44",
                    edgecolors="#212529", lw=0.7, zorder=5)
        off = offs.get(f"{r['pair']}|{r['signal']}", (8, 6))
        ax3.annotate(f"{r['pair'].replace('USDT','')} {r['signal']} {r['T_days']}h",
                     (r["alpha_train"], r["alpha_test"]),
                     xytext=off, textcoords="offset points", fontsize=7.6, color="#212529")
ax3.text(0.97, 0.95, "IDEAL", transform=ax3.transAxes, ha="right", va="top",
         fontsize=10, color="#2F9E44", fontweight="bold")
ax3.set_xlabel("alpha di TRAIN (%)", fontsize=10)
ax3.set_ylabel("alpha di TEST (%)", fontsize=10)
ax3.set_title("C.  Kalau ada edge, titik menumpuk di kanan-atas.\nKenyataannya: acak.",
              fontsize=12, fontweight="bold", loc="left")
ax3.grid(alpha=0.2, ls="--")

fig.suptitle("Tokocrypto: ruang gerak altcoin 3–5× lebih lega dari BTC — tapi edge entry tetap tidak ada",
             fontsize=14.5, fontweight="bold", y=0.965)
fig.savefig("/workspace/P6-P7-saringan-pair.png", dpi=145)
print("Tersimpan: /workspace/P6-P7-saringan-pair.png")
