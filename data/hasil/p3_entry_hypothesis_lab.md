# P3 — Lab hipotesis entry (HASIL)

> **LABEL: HIPOTESIS.** Dokumen ini bahan uji offline. Bukan rekomendasi trading, dan **bukan** default engine live. Tidak ada perubahan apa pun pada `ScalpingRuntimeConfig` / `ScalpingConfig` / `data/config/*.json`.

## Setup

- Data: `data/tokocrypto_v4` — 8 pair: ADAUSDT, BNBUSDT, BTCUSDT, DOGEUSDT, ETHUSDT, SOLUSDT, TRXUSDT, XRPUSDT
- Rentang: 2026-08-12 18:16 .. 2026-10-11 23:49 UTC (train 2026-08-12 18:16..2026-09-17 21:35, test 2026-09-17 21:35..2026-10-11 23:49)
- Horizon hasil: **60 bar M1**; exit `full` (max hold = horizon)
- Fee: buy 0.4044% + sell 0.4044% + slippage 0.08% x2 = **0.9688% round-trip**
- Gate: mean GROSS >= **1.0%** di TRAIN dan TEST, n >= 30 per split, p < 0.00625 (Bonferroni 0,05/8)
- Baseline: 500 kali permutasi acak, seed 20261012

## Ringkasan

- Hipotesis diuji **8**, lolos gate **0**.
- GROSS TEST terbaik: `h05_lowvol_ignition` **+0.026%** (ambang gate 1.0%). Terburuk: `h08_us_session_uptrend` -0.088%.
- Semua NET TEST berada di rentang **-1.051% .. -0.938%**, sedangkan friksi round-trip saja sudah 0.9688%.
- Signifikan di TRAIN (p < 0.05): `h06_sweep_reclaim`.
- **Tidak tereplikasi di TEST**: `h06_sweep_reclaim` (p_train 0.0020 -> p_test 0.3493). Contoh langsung kenapa signifikansi di satu periode tidak boleh dipercaya.

## Hasil per hipotesis

| Hipotesis | split | n | gross mean | net mean | MFE | MAE | win% | R (proksi) | p | gate |
|---|---|---|---|---|---|---|---|---|---|---|
| `h01_m15_oversold_uptrend` | train | 24 | -0.087% | -1.050% | +0.270% | -0.416% | 33.3 | -2.919 | 0.8822 | gagal |
| `h01_m15_oversold_uptrend` | test | 17 | -0.025% | -0.989% | +0.314% | -0.395% | 47.1 | -2.919 | 0.6287 | gagal |
| `h02_m1_bb_reclaim` | train | 4153 | +0.034% | -0.931% | +0.402% | -0.413% | 50.4 | -2.484 | 0.1257 | gagal |
| `h02_m1_bb_reclaim` | test | 2695 | +0.016% | -0.948% | +0.394% | -0.398% | 51.4 | -2.489 | 0.4870 | gagal |
| `h03_vol_spike_bull` | train | 4114 | +0.018% | -0.947% | +0.401% | -0.391% | 47.7 | -2.422 | 0.6407 | gagal |
| `h03_vol_spike_bull` | test | 2566 | +0.014% | -0.950% | +0.391% | -0.379% | 49.0 | -2.430 | 0.5749 | gagal |
| `h04_donchian_break_60` | train | 2914 | +0.013% | -0.952% | +0.442% | -0.409% | 46.5 | -2.292 | 0.7964 | gagal |
| `h04_donchian_break_60` | test | 1978 | +0.002% | -0.963% | +0.412% | -0.395% | 48.2 | -2.330 | 0.8483 | gagal |
| `h05_lowvol_ignition` | train | 1270 | -0.035% | -0.998% | +0.334% | -0.361% | 47.0 | -2.581 | 0.9980 | gagal |
| `h05_lowvol_ignition` | test | 876 | +0.026% | -0.938% | +0.327% | -0.307% | 53.6 | -2.672 | 0.2974 | gagal |
| `h06_sweep_reclaim` | train | 2358 | +0.069% | -0.895% | +0.464% | -0.449% | 55.4 | -2.463 | 0.0020 | gagal |
| `h06_sweep_reclaim` | test | 1572 | +0.021% | -0.943% | +0.435% | -0.461% | 53.4 | -2.548 | 0.3493 | gagal |
| `h07_m15_ema_cross_trend` | train | 292 | -0.036% | -0.999% | +0.378% | -0.388% | 44.2 | -2.196 | 0.9261 | gagal |
| `h07_m15_ema_cross_trend` | test | 154 | +0.025% | -0.940% | +0.395% | -0.329% | 42.9 | -2.276 | 0.4192 | gagal |
| `h08_us_session_uptrend` | train | 688 | +0.059% | -0.906% | +0.580% | -0.584% | 54.2 | -2.224 | 0.0778 | gagal |
| `h08_us_session_uptrend` | test | 520 | -0.088% | -1.051% | +0.532% | -0.666% | 48.3 | -2.459 | 0.9980 | gagal |

## Catatan per hipotesis

### `h01_m15_oversold_uptrend` — M15 RSI<30 di dalam tren naik (beli dip)

*Alasan:* Mean reversion: tekanan jual jangka pendek di pasar yang trennya masih naik (close M15 > EMA200 M15).

**Status: GAGAL gate.** n_train 24 < 30; n_test 17 < 30; gross_train -0.09% < 1.0%; gross_test -0.03% < 1.0%; p_train 0.88224 >= 0.00625; p_test 0.62874 >= 0.00625.

### `h02_m1_bb_reclaim` — Close M1 kembali ke atas lower Bollinger(20,2)

*Alasan:* Reclaim: bar sebelumnya menembus bawah lower band, bar ini ditutup naik — asumsi jual berlebih jangka sangat pendek.

**Status: GAGAL gate.** gross_train 0.03% < 1.0%; gross_test 0.02% < 1.0%; p_train 0.12575 >= 0.00625; p_test 0.48703 >= 0.00625.

### `h03_vol_spike_bull` — Lonjakan volume >3x MA20 + candle M1 hijau

*Alasan:* Partisipasi: volume ekstrem menandakan minat agresif; candle hijau mengonfirmasi arah.

**Status: GAGAL gate.** gross_train 0.02% < 1.0%; gross_test 0.01% < 1.0%; p_train 0.64072 >= 0.00625; p_test 0.57485 >= 0.00625.

### `h04_donchian_break_60` — Tembus high 60 bar M1 (Donchian)

*Alasan:* Breakout: penembusan tertinggi 1 jam terakhir, klasik momentum harian pendek.

**Status: GAGAL gate.** gross_train 0.01% < 1.0%; gross_test 0.00% < 1.0%; p_train 0.79641 >= 0.00625; p_test 0.84830 >= 0.00625.

### `h05_lowvol_ignition` — 3 candle M1 hijau saat ATR sedang rendah

*Alasan:* Ignition: dorongan naik beruntun dari kondisi volatilitas rendah (ATR < 0,7x rata-rata 200).

**Status: GAGAL gate.** gross_train -0.03% < 1.0%; gross_test 0.03% < 1.0%; p_train 0.99800 >= 0.00625; p_test 0.29741 >= 0.00625.

### `h06_sweep_reclaim` — Sapu low 60 bar lalu ditutup kembali di atasnya

*Alasan:* Failed breakdown: low baru dibuat tapi tidak bertahan, harga ditutup kembali di atas low lama.

**Status: GAGAL gate.** gross_train 0.07% < 1.0%; gross_test 0.02% < 1.0%; p_test 0.34930 >= 0.00625.

### `h07_m15_ema_cross_trend` — EMA9 menembus EMA21 di M15, filter EMA50>EMA200 M15

*Alasan:* Momentum berlapis: sinyal cross jangka menengah yang hanya diambil bila tren besar masih naik.

**Status: GAGAL gate.** gross_train -0.04% < 1.0%; gross_test 0.02% < 1.0%; p_train 0.92615 >= 0.00625; p_test 0.41916 >= 0.00625.

### `h08_us_session_uptrend` — Jam sesi AS (13-16 UTC) + H1 EMA50 > EMA200

*Alasan:* Waktu: likuiditas global memuncak saat sesi AS; ambil hanya bila tren H1 naik.

**Status: GAGAL gate.** gross_train 0.06% < 1.0%; gross_test -0.09% < 1.0%; p_train 0.07784 >= 0.00625; p_test 0.99800 >= 0.00625.

## Batasan

- R memakai **proksi** level risiko bergaya `risk_levels()`: SL = 1xATR M1 dijepit ke lantai 0.5%, TP2 = target_net_rr 1.25x. Yang tidak bisa ditiru adalah SL berbasis support/swing-low dan klamp TP1 ke resistance, karena hipotesis di lab ini tidak melewati detektor structure/regime. Karena itu gate dipasang di GROSS, bukan di R.
- MFE/MAE adalah ekskursi dalam horizon; bukan jaminan bisa dieksekusi di harga itu.
- Uji dilakukan pada satu periode. Hasil bagus pada satu periode bukan bukti edge; perlu walk-forward pada periode lain.
