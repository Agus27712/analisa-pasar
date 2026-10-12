# P7 — Uji Edge Entry di Altcoin Bervolatilitas Tinggi

Biaya round-trip: **0.9688%**. Long-only (spot). Hold T hari, exit di close.
Split kronologis: 70% train / 30% test. Total **201 uji**.
Ambang Bonferroni: **0.000249** (0,05 ÷ 201).

## Kenapa ada dua gate

Gate naif (`gross > biaya`) **menyesatkan**: periode test jatuh di fase bull market, jadi hampir semua posisi long untung — bukan karena sinyalnya pintar, tapi karena pasar naik. Yang benar adalah membandingkan dengan **drift pasar** (rata-rata semua window tanpa sinyal) pada periode yang sama.

| Gate | Arti |
|---|---|
| **Naif** | gross > biaya di train & test — tidak mengontrol kenaikan pasar |
| **Alpha** | gross − drift pasar > 0 di train & test — ini yang bermakna |
| **Bonferroni** | alpha lolos + p permutasi < 0,05/n — tahan uji ganda |
| **Robust** | lolos semua di atas + alpha positif di sepertiga akhir test |

## Hasil

| Tahap | Jumlah | Dari |
|---|---:|---:|
| Lolos gate naif | 42 | 201 |
| Lolos gate alpha | **47** | 201 |
| + Bonferroni | **0** | 201 |
| + tahan di sepertiga akhir | **0** | 201 |

> **Tidak ada satu pun kombinasi yang bertahan.**

### 20 hasil teratas menurut alpha di test

| Pair | Sinyal | T | episode | gross train | bench train | **alpha train** | gross test | bench test | **alpha test** | alpha 1/3 | p test |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| ENAUSDT | mom30 | 14h | 13 | +8.85% | -0.35% | **+9.20%** | +15.52% | +3.45% | **+12.06%** | +3.44% | 0.002 |
| NEARUSDT | rsi_high | 14h | 11 | -4.56% | +0.95% | **-5.51%** | +20.03% | +8.99% | **+11.04%** | +23.16% | 0.002 |
| ENAUSDT | sma50 | 14h | 12 | +9.66% | -0.35% | **+10.01%** | +14.13% | +3.45% | **+10.67%** | +1.63% | 0.002 |
| ZECUSDT | vol_spike | 14h | 7 | +32.87% | +10.73% | **+22.14%** | +17.00% | +8.79% | **+8.21%** | +24.52% | 0.116 |
| WLDUSDT | vol_spike | 14h | 10 | -3.50% | +1.56% | **-5.06%** | +10.11% | +2.73% | **+7.38%** | -1.81% | 0.056 |
| ZECUSDT | vol_spike | 3h | 9 | +5.92% | +1.88% | **+4.04%** | +8.47% | +1.93% | **+6.53%** | +15.55% | 0.012 |
| WLDUSDT | mom30 | 14h | 12 | -5.25% | +1.56% | **-6.82%** | +8.68% | +2.73% | **+5.95%** | +7.08% | 0.004 |
| WLDUSDT | sma50 | 14h | 12 | -3.05% | +1.56% | **-4.61%** | +8.62% | +2.73% | **+5.89%** | +6.58% | 0.006 |
| NEARUSDT | rsi_high | 7h | 18 | -2.26% | +0.48% | **-2.73%** | +9.95% | +4.27% | **+5.67%** | +8.96% | 0.002 |
| NEARUSDT | sma50 | 14h | 13 | -6.76% | +0.95% | **-7.70%** | +14.34% | +8.99% | **+5.36%** | +25.46% | 0.002 |
| SOLUSDT | vol_spike | 7h | 4 | +1.26% | +0.98% | **+0.28%** | +5.45% | +0.17% | **+5.27%** | +8.93% | 0.030 |
| ETHUSDT | pullback | 14h | 5 | +1.03% | +1.56% | **-0.53%** | +5.38% | +0.20% | **+5.18%** | +nan% | 0.078 |
| SOLUSDT | vol_spike | 14h | 4 | +1.56% | +1.90% | **-0.34%** | +5.70% | +0.60% | **+5.10%** | +2.79% | 0.104 |
| ENAUSDT | mom30 | 7h | 21 | +4.82% | +0.11% | **+4.71%** | +6.42% | +1.50% | **+4.92%** | +0.96% | 0.002 |
| SOLUSDT | vol_compress | 14h | 12 | -1.01% | +1.90% | **-2.91%** | +5.25% | +0.60% | **+4.65%** | +3.33% | 0.002 |
| SOLUSDT | vol_spike | 3h | 7 | +1.16% | +0.40% | **+0.77%** | +4.62% | +0.06% | **+4.56%** | +6.17% | 0.004 |
| NEARUSDT | mom30 | 14h | 16 | -7.23% | +0.95% | **-8.18%** | +13.26% | +8.99% | **+4.27%** | +22.25% | 0.002 |
| ZECUSDT | sma50 | 14h | 16 | +16.97% | +10.73% | **+6.24%** | +12.83% | +8.79% | **+4.04%** | +0.25% | 0.006 |
| NEARUSDT | vol_spike | 14h | 7 | +3.17% | +0.95% | **+2.22%** | +12.98% | +8.99% | **+3.99%** | +0.43% | 0.230 |
| TIAUSDT | rsi_low | 14h | 8 | -0.86% | -4.81% | **+3.94%** | +5.59% | +1.67% | **+3.92%** | -3.81% | 0.012 |

---

Reproduksi:
```
python3 tools/entry_edge_altcoins.py --pairs NEARUSDT,ENAUSDT,ZECUSDT,WLDUSDT,TAOUSDT,FETUSDT,TIAUSDT,SOLUSDT,ETHUSDT,BTCUSDT --horizons 3,7,14
```
