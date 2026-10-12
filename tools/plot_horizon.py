#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Grafik P4: MFE median vs waktu tahan, dan capture wajib vs biaya."""
import argparse
import json
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--intraday", default="data/hasil/p4_mfe_horizon_lab.json")
    ap.add_argument("--daily", default="data/hasil/p4b_horizon_harian.json")
    ap.add_argument("--out", default="/workspace/P4-grafik-horizon.png")
    a = ap.parse_args()

    intra = json.load(open(a.intraday))
    daily = json.load(open(a.daily))
    cost = intra["cost_roundtrip_pct"]

    pts = {}
    for r in intra["horizon"]:
        pts[float(r["T_min"])] = (r["mfe_median"], r["mfe_p75"], r["required_capture_pct"])
    for r in daily["horizon"]:
        pts[float(r["T_min"])] = (r["mfe_median"], r["mfe_p75"], r["required_capture_pct"])

    xs = sorted(pts)
    days = [x / 1440.0 for x in xs]
    med = [pts[x][0] for x in xs]
    p75 = [pts[x][1] for x in xs]
    cap = [pts[x][2] for x in xs]

    ticks = [1 / 1440, 15 / 1440, 1 / 24, 1, 7, 30, 90]
    labels = ["1mnt", "15mnt", "1jam", "1hari", "7hari", "30hari", "90hari"]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14.5, 5.8))

    # --- Panel 1: MFE vs T ---
    ax1.plot(days, med, "o-", color="#1f77b4", lw=2.2, ms=5, label="MFE median")
    ax1.plot(days, p75, "s--", color="#8ab8e0", lw=1.4, ms=4, label="MFE p75")
    ax1.axhline(cost, color="#c0392b", lw=2.0)
    ax1.text(days[0], cost * 1.15, f"biaya round-trip {cost:.2f}%",
             color="#c0392b", fontsize=10, fontweight="bold")
    lo = [min(m, cost) for m in med]
    ax1.fill_between(days, lo, cost, where=[m < cost for m in med],
                     color="#c0392b", alpha=0.13, interpolate=True)
    ax1.set_xscale("log")
    ax1.set_yscale("log")
    ax1.set_xticks(ticks)
    ax1.set_xticklabels(labels, fontsize=8.5)
    ax1.set_xlabel("Waktu tahan (skala log)")
    ax1.set_ylabel("Ekskursi menguntungkan (%)")
    ax1.set_title("MFE vs waktu tahan\n(area merah = MFE di bawah biaya → mustahil profit)")
    ax1.grid(alpha=0.3, which="both")
    ax1.legend(loc="upper left", fontsize=9)

    # --- Panel 2: required capture ---
    ax2.plot(days, cap, "o-", color="#e67e22", lw=2.2, ms=5)
    ax2.axhline(100, color="#c0392b", lw=2.0, ls="-")
    ax2.axhline(50, color="#27ae60", lw=1.6, ls="--")
    ax2.text(days[0], 118, "100% — batas mustahil", color="#c0392b",
             fontsize=9.5, fontweight="bold")
    ax2.text(days[0], 60, "50% — batas realistis", color="#27ae60", fontsize=9.5)
    ax2.set_xscale("log")
    ax2.set_yscale("log")
    ax2.set_xticks(ticks)
    ax2.set_xticklabels(labels, fontsize=8.5)
    ax2.set_xlabel("Waktu tahan (skala log)")
    ax2.set_ylabel("Capture wajib = biaya ÷ MFE median (%)")
    ax2.set_title("Berapa % dari MFE harus ditangkap agar breakeven")
    ax2.grid(alpha=0.3, which="both")

    fig.suptitle("P4 — Mengapa horizon M1 mustahil, dan di mana matematikanya mulai bisa ditembus",
                 fontsize=12.5, fontweight="bold")
    fig.tight_layout(rect=[0, 0, 1, 0.94])
    fig.savefig(a.out, dpi=140)
    print(f"Grafik -> {a.out}")


if __name__ == "__main__":
    main()
