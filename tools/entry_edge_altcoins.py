#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P7 — Uji edge entry di pair bervolatilitas tinggi.

Latar: saringan P6 menunjukkan capture wajib di altcoin hanya 8-11%,
jauh lebih ringan dari BTC (98%). Tapi MFE hanya langit-langit.
Pertanyaannya: apakah ada SINYAL yang bisa menangkapnya?

Uji: 8 sinyal klasik x 3 horizon x N pair, long-only (spot).
Gate: mean GROSS > biaya di TRAIN **dan** TEST.
Kontrol: baseline permutasi 500x + koreksi Bonferroni.
"""
import argparse
import json
import os
import statistics as st
import time

import requests

BASE = "https://www.tokocrypto.site/api/v3"
UA = {"User-Agent": "Mozilla/5.0 TokoClient/3.5"}
COST = 0.4044 + 0.4044 + 2 * 0.08  # 0.9688


def kl(sym, interval="1d", limit=1000, tries=4):
    for k in range(tries):
        try:
            r = requests.get(f"{BASE}/klines",
                             params={"symbol": sym, "interval": interval, "limit": limit},
                             headers=UA, timeout=25)
            if r.status_code == 200:
                d = r.json()
                return [{"t": int(x[0]), "o": float(x[1]), "h": float(x[2]),
                         "l": float(x[3]), "c": float(x[4]), "v": float(x[5])} for x in d]
        except Exception:
            pass
        time.sleep(0.8 * (k + 1))
    return []


def sma(x, n, i):
    if i + 1 < n:
        return None
    return sum(x[i + 1 - n:i + 1]) / n


def rsi(cl, n, i):
    if i < n:
        return None
    gains = losses = 0.0
    for k in range(i - n + 1, i + 1):
        ch = cl[k] - cl[k - 1]
        if ch > 0:
            gains += ch
        else:
            losses -= ch
    if losses == 0:
        return 100.0
    rs = (gains / n) / (losses / n)
    return 100.0 - 100.0 / (1 + rs)


def atr(bars, n, i):
    if i < n:
        return None
    s = 0.0
    for k in range(i - n + 1, i + 1):
        tr = max(bars[k]["h"] - bars[k]["l"],
                 abs(bars[k]["h"] - bars[k - 1]["c"]),
                 abs(bars[k]["l"] - bars[k - 1]["c"]))
        s += tr
    return s / n


def signals(bars):
    """Kembalikan dict nama -> list of bool (True = sinyal long di bar i)."""
    cl = [b["c"] for b in bars]
    vo = [b["v"] for b in bars]
    n = len(bars)
    out = {k: [False] * n for k in
           ("sma50", "donchian20", "mom30", "rsi_low", "rsi_high",
            "vol_compress", "pullback", "vol_spike")}
    for i in range(60, n - 1):
        s50 = sma(cl, 50, i)
        s100 = sma(cl, 100, i)
        if s50 and cl[i] > s50:
            out["sma50"][i] = True
        if i >= 20 and cl[i] >= max(b["h"] for b in bars[i - 19:i + 1]):
            out["donchian20"][i] = True
        if i >= 30 and cl[i] > cl[i - 30]:
            out["mom30"][i] = True
        r = rsi(cl, 14, i)
        if r is not None:
            if r < 35:
                out["rsi_low"][i] = True
            if r > 65:
                out["rsi_high"][i] = True
        a10, a50 = atr(bars, 10, i), atr(bars, 50, i)
        if a10 and a50 and a10 / a50 < 0.8:
            out["vol_compress"][i] = True
        if s100 and r is not None and cl[i] > s100 and r < 45:
            out["pullback"][i] = True
        vs = sma(vo, 20, i)
        if vs and vs > 0 and vo[i] > 2 * vs:
            out["vol_spike"][i] = True
    return out


def evaluate(bars, sig, T):
    """Return list gross return per sinyal (hold T hari, exit close)."""
    cl = [b["c"] for b in bars]
    n = len(bars)
    rets = []
    for i in range(n - T):
        if sig[i]:
            rets.append(100.0 * (cl[i + T] - cl[i]) / cl[i])
    return rets


def benchmark(bars, T):
    """Return tidak bersyarat: rata-rata semua window (tanpa sinyal). = drift pasar."""
    cl = [b["c"] for b in bars]
    return [100.0 * (cl[i + T] - cl[i]) / cl[i] for i in range(len(bars) - T)]


def n_episodes(sig, T):
    """Jumlah trade independen: setelah sinyal di i, lompati T bar."""
    n, cnt, i = len(sig), 0, 0
    while i < n:
        if sig[i]:
            cnt += 1
            i += T
        else:
            i += 1
    return cnt


def perm_p(bars, sig, T, iters=500, seed=7):
    import random
    rnd = random.Random(seed)
    idx = [i for i in range(len(bars) - T)]
    real = evaluate(bars, sig, T)
    if len(real) < 20:
        return float("nan"), float("nan")
    obs = st.mean(real)
    cl = [b["c"] for b in bars]
    n_sig = len(real)
    cnt = 0
    for _ in range(iters):
        pick = rnd.sample(idx, min(n_sig, len(idx)))
        m = st.mean([100.0 * (cl[i + T] - cl[i]) / cl[i] for i in pick])
        if m >= obs:
            cnt += 1
    return obs, (cnt + 1) / (iters + 1)


def perm_p_period(bars, sig, T, iters=500, seed=11):
    """Uji permutasi pada satu periode: apakah sinyal mengalahkan entry acak?"""
    import random
    rnd = random.Random(seed)
    idx = [i for i in range(len(bars) - T)]
    real = evaluate(bars, sig, T)
    if len(real) < 8 or len(idx) < 20:
        return float("nan"), float("nan"), float("nan")
    obs = st.mean(real)
    cl = [b["c"] for b in bars]
    allr = [100.0 * (cl[i + T] - cl[i]) / cl[i] for i in idx]
    bench = st.mean(allr)
    n_sig = min(len(real), len(idx))
    cnt = 0
    for _ in range(iters):
        pick = rnd.sample(idx, n_sig)
        m = st.mean([100.0 * (cl[i + T] - cl[i]) / cl[i] for i in pick])
        if m >= obs:
            cnt += 1
    return obs, bench, (cnt + 1) / (iters + 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", default="NEARUSDT,ENAUSDT,ZECUSDT,WLDUSDT,TAOUSDT,"
                                       "FETUSDT,TIAUSDT,SOLUSDT,ETHUSDT,BTCUSDT")
    ap.add_argument("--horizons", default="3,7,14")
    ap.add_argument("--train-frac", type=float, default=0.7)
    ap.add_argument("--out-json", default="data/hasil/p7_entry_edge_altcoin.json")
    ap.add_argument("--out-md", default="data/hasil/p7_entry_edge_altcoin.md")
    a = ap.parse_args()

    pairs = [p.strip() for p in a.pairs.split(",") if p.strip()]
    horizons = [int(x) for x in a.horizons.split(",")]

    data = {}
    for p in pairs:
        bars = kl(p, "1d", 1000)
        if len(bars) >= 300:
            data[p] = bars
            print(f"  {p}: {len(bars)} bar harian")
        time.sleep(0.15)

    results = []
    for p, bars in data.items():
        sigs = signals(bars)
        split = int(len(bars) * a.train_frac)
        for name, sig in sigs.items():
            for T in horizons:
                # train
                btr = bars[:split]
                str_ = sig[:split]
                rtr = evaluate(btr, str_, T)
                # test
                bte = bars[split:]
                ste = sig[split:]
                rte = evaluate(bte, ste, T)
                if len(rtr) < 20 or len(rte) < 10:
                    continue
                mtr, mte = st.mean(rtr), st.mean(rte)
                # benchmark = drift pasar (tanpa sinyal) pada periode yang sama
                btr = st.mean(benchmark(bars[:split], T))
                bte = st.mean(benchmark(bars[split:], T))
                # permutasi terpisah per periode
                _, _, p_tr = perm_p_period(bars[:split], sig[:split], T)
                _, _, p_te = perm_p_period(bars[split:], sig[split:], T)
                # sepertiga terakhir dari test = uji ketahanan
                q = split + (len(bars) - split) * 2 // 3
                rq = evaluate(bars[q:], sig[q:], T)
                mq = st.mean(rq) if len(rq) >= 5 else float("nan")
                bq = st.mean(benchmark(bars[q:], T))
                results.append({
                    "pair": p, "signal": name, "T_days": T,
                    "n_train": len(rtr), "n_test": len(rte),
                    "episodes_test": n_episodes(sig[split:], T),
                    "gross_train": mtr, "gross_test": mte,
                    "bench_train": btr, "bench_test": bte,
                    "alpha_train": mtr - btr, "alpha_test": mte - bte,
                    "net_train": mtr - COST, "net_test": mte - COST,
                    "net_test_alpha": (mte - bte) - COST,
                    "gross_last3rd": mq, "bench_last3rd": bq,
                    "alpha_last3rd": (mq - bq) if mq == mq else float("nan"),
                    "win_train": 100.0 * sum(1 for x in rtr if x > COST) / len(rtr),
                    "win_test": 100.0 * sum(1 for x in rte if x > COST) / len(rte),
                    "perm_p": p_te, "perm_p_train": p_tr,
                })
        print(f"  selesai: {p}")

    n_tests = len(results)
    bonf = 0.05 / n_tests if n_tests else 1.0
    # gate lama (naif): gross > biaya di train & test
    naive = [r for r in results
             if r["gross_train"] > COST and r["gross_test"] > COST]
    # gate sebenarnya: harus mengalahkan DRIFT PASAR di train & test
    passed = [r for r in results
              if r["alpha_train"] > 0 and r["alpha_test"] > 0]
    passed_bonf = [r for r in passed
                   if r["perm_p"] == r["perm_p"] and r["perm_p"] < bonf]
    robust = [r for r in passed_bonf if r["alpha_last3rd"] == r["alpha_last3rd"]
              and r["alpha_last3rd"] > 0]
    results.sort(key=lambda x: -(x["alpha_test"] if x["alpha_test"] == x["alpha_test"] else -99))

    os.makedirs(os.path.dirname(a.out_json), exist_ok=True)
    with open(a.out_json, "w") as f:
        json.dump({"cost_roundtrip_pct": COST, "n_tests": n_tests,
                   "bonferroni_alpha": bonf,
                   "n_passed_naive": len(naive),
                   "n_passed_alpha": len(passed),
                   "n_passed_bonferroni": len(passed_bonf),
                   "n_robust": len(robust),
                   "results": results, "passed": passed, "robust": robust}, f, indent=2)

    L = []
    A = L.append
    A("# P7 — Uji Edge Entry di Altcoin Bervolatilitas Tinggi")
    A("")
    A(f"Biaya round-trip: **{COST:.4f}%**. Long-only (spot). Hold T hari, exit di close.")
    A(f"Split kronologis: 70% train / 30% test. Total **{n_tests} uji**.")
    A(f"Ambang Bonferroni: **{bonf:.6f}** (0,05 ÷ {n_tests}).")
    A("")
    A("## Kenapa ada dua gate")
    A("")
    A("Gate naif (`gross > biaya`) **menyesatkan**: periode test jatuh di fase bull market, "
      "jadi hampir semua posisi long untung — bukan karena sinyalnya pintar, tapi karena "
      "pasar naik. Yang benar adalah membandingkan dengan **drift pasar** "
      "(rata-rata semua window tanpa sinyal) pada periode yang sama.")
    A("")
    A("| Gate | Arti |")
    A("|---|---|")
    A("| **Naif** | gross > biaya di train & test — tidak mengontrol kenaikan pasar |")
    A("| **Alpha** | gross − drift pasar > 0 di train & test — ini yang bermakna |")
    A("| **Bonferroni** | alpha lolos + p permutasi < 0,05/n — tahan uji ganda |")
    A("| **Robust** | lolos semua di atas + alpha positif di sepertiga akhir test |")
    A("")
    A("## Hasil")
    A("")
    A("| Tahap | Jumlah | Dari |")
    A("|---|---:|---:|")
    A(f"| Lolos gate naif | {len(naive)} | {n_tests} |")
    A(f"| Lolos gate alpha | **{len(passed)}** | {n_tests} |")
    A(f"| + Bonferroni | **{len(passed_bonf)}** | {n_tests} |")
    A(f"| + tahan di sepertiga akhir | **{len(robust)}** | {n_tests} |")
    A("")
    if robust:
        A("### Yang bertahan di semua tahap")
        A("")
        A("| Pair | Sinyal | T | episode | alpha train | alpha test | alpha 1/3 akhir | p | net test |")
        A("|---|---|---:|---:|---:|---:|---:|---:|---:|")
        for r in robust:
            A(f"| {r['pair']} | {r['signal']} | {r['T_days']}h | {r['episodes_test']} | "
              f"{r['alpha_train']:+.2f}% | {r['alpha_test']:+.2f}% | {r['alpha_last3rd']:+.2f}% | "
              f"{r['perm_p']:.4f} | {r['net_test']:+.2f}% |")
        A("")
    else:
        A("> **Tidak ada satu pun kombinasi yang bertahan.**")
        A("")
    A("### 20 hasil teratas menurut alpha di test")
    A("")
    A("| Pair | Sinyal | T | episode | gross train | bench train | **alpha train** | gross test | bench test | **alpha test** | alpha 1/3 | p test |")
    A("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in results[:20]:
        A(f"| {r['pair']} | {r['signal']} | {r['T_days']}h | {r['episodes_test']} | "
          f"{r['gross_train']:+.2f}% | {r['bench_train']:+.2f}% | **{r['alpha_train']:+.2f}%** | "
          f"{r['gross_test']:+.2f}% | {r['bench_test']:+.2f}% | **{r['alpha_test']:+.2f}%** | "
          f"{r['alpha_last3rd']:+.2f}% | {r['perm_p']:.3f} |")
    A("")
    A("---")
    A("")
    A("Reproduksi:")
    A("```")
    A(f"python3 tools/entry_edge_altcoins.py --pairs {a.pairs} --horizons {a.horizons}")
    A("```")
    with open(a.out_md, "w") as f:
        f.write("\n".join(L) + "\n")

    print("")
    print(f"Total uji        : {n_tests}")
    print(f"Lolos gate naif  : {len(naive)}")
    print(f"Lolos gate alpha : {len(passed)}")
    print(f"Lolos Bonferroni : {len(passed_bonf)}  (alpha {bonf:.6f})")
    print(f"Tahan 1/3 akhir  : {len(robust)}")
    print("")
    print("TOP 12 menurut alpha test:")
    for r in results[:12]:
        print(f"  {r['pair']:>10} {r['signal']:>13} T={r['T_days']:>2}h  "
              f"gross tr {r['gross_train']:+6.2f}% bench {r['bench_train']:+6.2f}% "
              f"alpha {r['alpha_train']:+6.2f}% | te {r['gross_test']:+6.2f}% "
              f"bench {r['bench_test']:+6.2f}% alpha {r['alpha_test']:+6.2f}%  p={r['perm_p']:.3f}")
    print("")
    print(f"Tersimpan: {a.out_json}")
    print(f"Tersimpan: {a.out_md}")


if __name__ == "__main__":
    main()
