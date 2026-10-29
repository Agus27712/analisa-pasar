# Dataset riset `tokocrypto_v8` (USDT)

Data candle **Tokocrypto USDT** yang dipakai pengukuran paket D (mode INTRADAY) mulai
29 Okt 2026. Diambil dari API publik Tokocrypto; tidak ada data Binance di app runtime.

Perintah pengambilan (dijalankan 29 Okt 2026):

```
python3 tools/fetch_tokocrypto_klines.py \
    --pairs AAVEUSDT,ADAUSDT,... \
    --intervals 4h,1d,1h --pages 5 --out data/tokocrypto_v8
```

Alasan timeframe: engine intraday di aplikasi berjalan di **H4**, jadi H4 adalah data ukur
utama. H1 dipakai untuk diagnostik jendela pendek (4/12/24 jam) yang tidak bisa diukur di H4,
dan D1 dipakai karena evaluator app mengoper candle harian (veto anomali makro + konteks).

Jumlah pair: **26** (semua USDT). Berkas: `_4h.csv`, `_1d.csv`, `_1h.csv` per pair.

| Pair | H4 | D1 | H1 |
|---|---|---|---|
| AAVEUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2205 bar, 2020-10-15 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| ADAUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 3117 bar, 2018-04-17 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| ARBUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 1316 bar, 2023-03-23 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| AVAXUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2228 bar, 2020-09-22 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| BANKUSDT | 2098 bar, 2025-11-13 s.d. 2026-10-29 | 350 bar, 2025-11-13 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| BNBUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 3279 bar, 2017-11-06 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| BTCUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 3360 bar, 2017-08-17 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| CHIPUSDT | 1144 bar, 2026-04-21 s.d. 2026-10-29 | 191 bar, 2026-04-21 s.d. 2026-10-28 | 4578 bar, 2026-04-21 s.d. 2026-10-29 |
| DOGEUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2673 bar, 2019-07-05 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| ENAUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 940 bar, 2024-04-02 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| ETHUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 3360 bar, 2017-08-17 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| FETUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2800 bar, 2019-02-28 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| HBARUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2587 bar, 2019-09-29 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| LINKUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2843 bar, 2019-01-16 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| LTCUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 3242 bar, 2017-12-13 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| NEARUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2206 bar, 2020-10-14 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| PEPEUSDT | 2999 bar, 2025-06-16 s.d. 2026-10-29 | 1273 bar, 2023-05-05 s.d. 2026-10-28 | 2999 bar, 2026-06-26 s.d. 2026-10-29 |
| QNTUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 1918 bar, 2021-07-29 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| SOLUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2270 bar, 2020-08-11 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| SUIUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 1275 bar, 2023-05-03 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| TAOUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 931 bar, 2024-04-11 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| TRXUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 3062 bar, 2018-06-11 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| UNIUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2233 bar, 2020-09-17 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| WLDUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 1193 bar, 2023-07-24 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| XRPUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 3100 bar, 2018-05-04 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |
| ZECUSDT | 4999 bar, 2024-07-18 s.d. 2026-10-29 | 2779 bar, 2019-03-21 s.d. 2026-10-28 | 4999 bar, 2026-04-04 s.d. 2026-10-29 |

Catatan kualitas data:

- **PEPEUSDT** terpotong: 2.999 bar H4, 2.999 bar H1, dan 1.273 bar D1 karena sebagian
  permintaan kena `HTTP 429` saat pengambilan. Bar yang ada tetap valid; jangan menganggapnya
  sama panjang dengan pair lain. Untuk mengulang: beri jeda, lalu jalankan perintah dengan
  `--pairs PEPEUSDT` saja.
- **CHIPUSDT** (H4 1.144 bar) dan **BANKUSDT** (H4 2.098 bar) lebih pendek karena listing baru;
  walk-forward tetap memotongnya menjadi 4 fold selama bar >= 300 (pemanasan) + 100 per fold.
  `CHIPUSDT_1d.csv` hanya 191 bar, jadi veto anomali makro berbasis D1 di pair itu berjalan
  dengan riwayat pendek — sebutkan batas ini kalau mengutip hasilnya.
- Kolom: `open_time_ms,open,high,low,close,volume` (UTC, tanpa header tambahan).
- Tidak ada pengisian candle yang hilang: replay membaca apa adanya.

Berkas ini tidak mengubah aturan engine. Sumber aturan: `AGENTS.md` akar dan `app/AGENTS.md`.
