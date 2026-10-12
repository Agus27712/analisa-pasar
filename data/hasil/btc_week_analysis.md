# BTCUSDT Tokocrypto — Analisis Mingguan vs Biaya Bursa

Data ditarik langsung dari API Tokocrypto. Bar terakhir: **2026-10-12 03:00 UTC**.
Biaya round-trip (fee all-in 0.4044%×2 + slippage 0.08%×2): **0.9688%**.

> Semua angka `net` di bawah sudah dikurangi biaya ini.

## Jawaban singkat

**Ada kemungkinan, tapi tidak untuk trading cepat.**

- Minggu ini BTC bergerak **7.36%** (high−low) = **7.6× biaya**. Secara matematis ruangnya ADA.
- Tapi di horizon 1–12 jam, MFE median (0,16%–0,67%) **di bawah** biaya (0,97%). Scalping/intraday **mustahil profit** walau exit sempurna.
- Hanya horizon **≥24 jam** yang MFE median-nya melewati biaya — dan itu pun cuma pas-pasan (capture wajib 98%).
- Tanpa sinyal arah, entry buta = **≈ −0,9% per trade** di semua horizon.

- Kenyataannya minggu ini BTC **turun -2.87%**. Di spot (long-only), siapa pun yang beli dan tahan **rugi**. Yang profit hanya yang menangkap ayunan di titik terbaik.

## 1. Ringkasan minggu ini

Periode: **2026-10-05 04:00 UTC → 2026-10-12 03:00 UTC** (7 hari, 168 bar 1 jam).

| Item | Nilai |
|---|---:|
| Harga buka minggu | 86,085.02 USDT |
| Tertinggi | 86,725.12 USDT |
| Terendah | 80,393.56 USDT |
| Harga sekarang | 83,613.06 USDT |
| **Perubahan bersih minggu** | **-2.87%** |
| **Rentang total (high−low)** | **7.36%** |
| Rentang ÷ biaya | **7.6×** |
| Perubahan minggu sebelumnya | +3.30% |
| Rentang minggu sebelumnya | 5.59% |

## 2. Rincian harian

| Tanggal | Buka | Tertinggi | Terendah | Tutup | Perubahan | Rentang | Rentang÷biaya |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2026-10-05 | 86,085 | 86,725 | 84,972 | 85,767 | -0.37% | 2.04% | 2.1× |
| 2026-10-06 | 85,767 | 86,699 | 85,136 | 85,550 | -0.25% | 1.82% | 1.9× |
| 2026-10-07 | 85,550 | 85,598 | 82,787 | 83,322 | -2.60% | 3.29% | 3.4× |
| 2026-10-08 | 83,322 | 83,521 | 80,394 | 81,754 | -1.88% | 3.75% | 3.9× |
| 2026-10-09 | 81,754 | 83,529 | 81,604 | 82,636 | +1.08% | 2.36% | 2.4× |
| 2026-10-10 | 82,636 | 83,173 | 82,546 | 82,988 | +0.43% | 0.76% | 0.8× |
| 2026-10-11 | 82,988 | 84,199 | 82,805 | 83,773 | +0.95% | 1.68% | 1.7× |
| 2026-10-12 | 83,773 | 84,027 | 83,462 | 83,613 | -0.19% | 0.67% | 0.7× |

## 3. MFE / MAE menurut waktu tahan

`capture wajib` = biaya ÷ MFE median. **>100% = mustahil profit walau exit sempurna.**

| Waktu tahan | n | MFE p25 | **MFE median** | MFE p75 | MAE median | %MFE>biaya | capture wajib | net exit-sempurna |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 jam | 1,998 | 0.070% | **0.159%** | 0.307% | -0.150% | 2.8% | **610%** | -0.810% |
| 2 jam | 1,997 | 0.101% | **0.232%** | 0.450% | -0.212% | 6.3% | **418%** | -0.737% |
| 4 jam | 1,995 | 0.153% | **0.343%** | 0.672% | -0.316% | 13.8% | **283%** | -0.626% |
| 8 jam | 1,991 | 0.245% | **0.521%** | 0.984% | -0.459% | 25.4% | **186%** | -0.447% |
| 12 jam | 1,987 | 0.306% | **0.674%** | 1.218% | -0.568% | 34.3% | **144%** | -0.295% |
| 24 jam | 1,975 | 0.486% | **0.985%** | 1.724% | -0.919% | 50.9% | **98%** | +0.016% |
| 48 jam | 1,951 | 0.810% | **1.533%** | 2.737% | -1.349% | 69.3% | **63%** | +0.564% |
| 72 jam | 1,927 | 0.980% | **1.992%** | 3.555% | -1.776% | 75.6% | **49%** | +1.023% |
| 7 hari | 1,831 | 1.510% | **3.312%** | 6.014% | -2.403% | 85.0% | **29%** | +2.343% |
| 15 mnt | 1,998 | 0.026% | **0.072%** | 0.144% | -0.070% | 0.2% | **1340%** | -0.897% |
| 1 jam (15m) | 1,995 | 0.071% | **0.156%** | 0.296% | -0.146% | 2.2% | **622%** | -0.813% |
| 4 jam (15m) | 1,983 | 0.160% | **0.327%** | 0.660% | -0.310% | 13.4% | **296%** | -0.642% |
| 1 hari (15m) | 1,903 | 0.543% | **0.992%** | 1.689% | -0.867% | 51.1% | **98%** | +0.023% |

## 4. Uji barrier buta — entry tiap bar, tanpa sinyal

| Waktu tahan | TP=SL | n | win% | timeout% | **E[net]/trade** | WR breakeven |
|---|---:|---:|---:|---:|---:|---:|
| 1 jam | ±0.5% | 1,998 | 10.9% | 78.0% | **-0.968%** | 146.9% |
| 1 jam | ±1.0% | 1,998 | 2.3% | 95.1% | **-0.963%** | 98.4% |
| 1 jam | ±1.5% | 1,998 | 0.8% | 98.5% | **-0.960%** | 82.3% |
| 1 jam | ±2.0% | 1,998 | 0.4% | 99.3% | **-0.960%** | 74.2% |
| 1 jam | ±3.0% | 1,998 | 0.2% | 99.8% | **-0.956%** | 66.1% |
| 1 jam | ±5.0% | 1,998 | 0.1% | 99.9% | **-0.955%** | 59.7% |
| 1 jam | ±8.0% | 1,998 | 0.0% | 100.0% | **-0.956%** | 56.1% |
| 1 jam | ±12.0% | 1,998 | 0.0% | 100.0% | **-0.956%** | 54.0% |
| 4 jam | ±0.5% | 1,995 | 32.0% | 37.4% | **-0.956%** | 146.9% |
| 4 jam | ±1.0% | 1,995 | 12.4% | 74.9% | **-0.951%** | 98.4% |
| 4 jam | ±1.5% | 1,995 | 4.8% | 90.5% | **-0.942%** | 82.3% |
| 4 jam | ±2.0% | 1,995 | 2.8% | 95.0% | **-0.937%** | 74.2% |
| 4 jam | ±3.0% | 1,995 | 1.5% | 98.2% | **-0.923%** | 66.1% |
| 4 jam | ±5.0% | 1,995 | 0.4% | 99.6% | **-0.915%** | 59.7% |
| 4 jam | ±8.0% | 1,995 | 0.0% | 100.0% | **-0.916%** | 56.1% |
| 4 jam | ±12.0% | 1,995 | 0.0% | 100.0% | **-0.916%** | 54.0% |
| 12 jam | ±0.5% | 1,987 | 47.6% | 12.0% | **-0.926%** | 146.9% |
| 12 jam | ±1.0% | 1,987 | 30.6% | 39.9% | **-0.926%** | 98.4% |
| 12 jam | ±1.5% | 1,987 | 17.5% | 65.7% | **-0.912%** | 82.3% |
| 12 jam | ±2.0% | 1,987 | 10.4% | 80.1% | **-0.909%** | 74.2% |
| 12 jam | ±3.0% | 1,987 | 5.5% | 92.8% | **-0.870%** | 66.1% |
| 12 jam | ±5.0% | 1,987 | 2.2% | 97.8% | **-0.821%** | 59.7% |
| 12 jam | ±8.0% | 1,987 | 0.7% | 99.3% | **-0.806%** | 56.1% |
| 12 jam | ±12.0% | 1,987 | 0.0% | 100.0% | **-0.812%** | 54.0% |
| 24 jam | ±0.5% | 1,975 | 53.1% | 3.8% | **-0.917%** | 146.9% |
| 24 jam | ±1.0% | 1,975 | 42.9% | 17.3% | **-0.904%** | 98.4% |
| 24 jam | ±1.5% | 1,975 | 30.9% | 41.1% | **-0.885%** | 82.3% |
| 24 jam | ±2.0% | 1,975 | 20.9% | 60.9% | **-0.865%** | 74.2% |
| 24 jam | ±3.0% | 1,975 | 11.5% | 80.9% | **-0.849%** | 66.1% |
| 24 jam | ±5.0% | 1,975 | 6.6% | 93.3% | **-0.744%** | 59.7% |
| 24 jam | ±8.0% | 1,975 | 2.1% | 97.9% | **-0.669%** | 56.1% |
| 24 jam | ±12.0% | 1,975 | 0.1% | 99.9% | **-0.663%** | 54.0% |
| 7 hari | ±0.5% | 1,831 | 56.0% | 0.0% | **-0.908%** | 146.9% |
| 7 hari | ±1.0% | 1,831 | 55.3% | 0.0% | **-0.862%** | 98.4% |
| 7 hari | ±1.5% | 1,831 | 56.0% | 0.0% | **-0.789%** | 82.3% |
| 7 hari | ±2.0% | 1,831 | 56.2% | 1.4% | **-0.698%** | 74.2% |
| 7 hari | ±3.0% | 1,831 | 48.3% | 15.1% | **-0.648%** | 66.1% |
| 7 hari | ±5.0% | 1,831 | 33.5% | 58.8% | **-0.078%** | 59.7% |
| 7 hari | ±8.0% | 1,831 | 17.7% | 82.3% | **+0.252%** | 56.1% |
| 7 hari | ±12.0% | 1,831 | 14.0% | 86.0% | **+0.810%** | 54.0% |

## 5. Peluang mentah: berapa kali harga bergerak > biaya

| Waktu tahan | n window | naik > biaya | turun > biaya | % peluang |
|---|---:|---:|---:|---:|
| 1 jam | 1,998 | 56 | 55 | 2.8% |
| 4 jam | 1,995 | 275 | 271 | 13.8% |
| 12 jam | 1,987 | 681 | 640 | 34.3% |
| 24 jam | 1,975 | 1,006 | 951 | 50.9% |
| 7 hari | 1,831 | 1,556 | 1,436 | 85.0% |

## 6. Skenario konkret minggu ini

| Skenario | Jumlah trade | Gross | **Net setelah biaya** | Catatan |
|---|---:|---:|---:|---|
| Beli di buka minggu, tahan 7 hari | 1 | -2.87% | **-3.84%** | arah salah, BTC turun |
| Short di buka minggu, tahan 7 hari | 1 | +2.87% | **+1.90%** | arah benar — TAPI spot Tokocrypto tidak bisa short |
| Day-trade: beli open harian, jual close harian | 8 | -2.85% | **-10.60%** | 8 trade |
| PERFECT: beli low harian, jual high harian | 8 | +16.66% | **+8.91%** | mustahil — butuh timing sempurna tiap hari |
| PERFECT: beli low minggu, jual high minggu | 1 | +7.88% | **+6.91%** | mustahil — hindsight sempurna |
| Entry buta tiap jam, TP/SL ±1% | 1998 | +0.01% | **-0.96%** | tanpa sinyal, 1 jam |

## 7. Konteks historis (bar harian)

- Rentang harian (high−low)/open, dari 1,998 hari: p25 = 2.31%, **median = 3.50%**, p75 = 5.16%
- Biaya = 0.9688% → rentang harian median ≈ 3.6× biaya

---

## 8. Kesimpulan

**Pertanyaan: apakah ada kemungkinan profit setelah dikurangi potongan bursa?**

| Horizon | Kemungkinan profit? | Alasan |
|---|---|---|
| 1–30 menit (scalping) | ❌ **Tidak** | MFE 0,07–0,16% vs biaya 0,97% → capture wajib >600% |
| 1–12 jam (intraday) | ❌ **Tidak** | MFE 0,16–0,67% vs biaya 0,97% → capture wajib 144–610% |
| 24 jam | ⚠️ **Sangat sulit** | MFE median 0,99% ≈ biaya; capture wajib 98% (harus nyaris sempurna) |
| 48–72 jam | ⚠️ **Sulit** | capture wajib 49–63% — butuh sinyal arah yang benar |
| 7 hari+ | ✅ **Mungkin** | capture wajib 29% — inilah satu-satunya zona yang realistis |

**Yang menentukan bukan seberapa besar BTC bergerak, tapi seberapa besar gerakan itu dibanding biaya.** Minggu ini rentangnya 7,36% (7,6× biaya) — besar. Tapi gerakan itu tersebar sepanjang 7 hari, dan di setiap potongan 1 jam, rata-rata yang tersedia hanya 0,16%.

**Angka paling jujur:** dengan exit SEMPURNA (menjual tepat di titik tertinggi) pada horizon 24 jam, hasil bersihnya **+0,02%** — nol. Artinya bahkan keahlian sempurna pun tidak memberi apa-apa di horizon itu. Baru di 7 hari, exit sempurna memberi +2,34%.

**Implikasi untuk aplikasi:**

1. Engine scalping M1/M15 yang ada sekarang **tidak akan pernah profit** di BTCUSDT — bukan karena sinyalnya salah, tapi karena biaya melebihi peluang yang tersedia.
2. Kalau tetap ingin auto-trading BTC di Tokocrypto, horizonnya harus **≥3 hari per posisi**, dengan SL lebar (MAE median 24 jam = −0,92%, 7 hari = −2,40%) dan target 5–10%.
3. Frekuensi trade harus **turun drastis**. Setiap trade memakan 0,97% — 10 trade = 9,7% dari modal, sebelum untung/rugi apa pun.
4. Kenaikan profit paling pasti bukan dari sinyal yang lebih pintar, tapi dari **menurunkan biaya** (maker/TKO/VIP) atau **menambah modal per trade** agar biaya tetap jadi lebih kecil.

---
```
python3 tools/fetch_tokocrypto_klines.py --pairs BTCUSDT --pages 2 --intervals 15m,1h,1d --out data/tokocrypto_btc_week
python3 tools/btc_week_analysis.py --data data/tokocrypto_btc_week --out-json data/hasil/btc_week_analysis.json --out-md data/hasil/btc_week_analysis.md
```
