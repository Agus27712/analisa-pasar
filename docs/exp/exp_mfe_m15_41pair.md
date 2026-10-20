# Eksperimen MFE 15m — 41 pair Tokocrypto, 90 hari (2026-10-20)

Laporan ini menjawab satu pertanyaan: **pair mana yang pergerakan mediannya cukup besar untuk
menutup friksi round-trip 0,9688% di Tokocrypto?** Ini adalah pengulangan tabel `PairEdgeGate`
pada dataset baru `data/tokocrypto_spot` (candle 15m, 90 hari), bukan pada data M1 30 hari yang
dipakai tabel lama.

- **Dataset:** `data/tokocrypto_spot` — 41 pair USDT, 90 hari, candle 15m.
- **Perintah:** `python3 tools/spot_mfe.py --data data/tokocrypto_spot --out data/hasil/mfe_m15_spot.json`
- **Artefak:** `data/hasil/mfe_m15_spot.json`
- **Status:** pengukuran offline. Kode aplikasi belum disentuh. Hasil ini belum boleh disebut
  hasil produksi.

---

## 1. Definisi yang dipakai

Untuk setiap candle sampel `k`:

```
entry = close[k]
MFE   = max(high[k+1 .. k+T]) / close[k] - 1
MAE   = min(low[k+1  .. k+T]) / close[k] - 1
```

`T` adalah horizon dalam bar: 1 jam = 4 bar, 4 jam = 16 bar, 7 jam = 28 bar. Horizon 7 jam
dipakai karena tabel `PairEdgeGate` lama memakai 7 jam (420 bar M1). Sampel diambil dengan
`step = max(1, n // 4000)` supaya seluruh 41 pair selesai dalam waktu wajar.

Rasio kelayakan tetap sama dengan aturan lama:

```
ratio = median MFE 7 jam / F
F     = buyFee + sellFee + 2 x slippage = 0,4044 + 0,4044 + 2 x 0,08 = 0,9688%
layak bila ratio >= 2,0
```

`F` di sini adalah friksi **aditif** yang dipakai lantai SL dan `ScalpingRiskEngine.costPct`.
Angka impas net multiplikatif `FeeSchedule` adalah **0,9735%**; keduanya sengaja tidak dicampur
(lihat `AGENTS.md`, aturan FEE_TRUTH v1.1).

**Validasi sampling.** Untuk BTCUSDT, MUBARAKUSDT, dan ZROUSDT, hasil `step = 1` dibandingkan
dengan `step = 2`:

| Pair | MFE 7h `step=1` | MFE 7h `step=2` | Selisih |
|---|---:|---:|---:|
| BTCUSDT | 0,4803% | 0,4810% | 0,0007 pp |
| MUBARAKUSDT | 2,6909% | 2,6915% | 0,0006 pp |
| ZROUSDT | 2,2392% | 2,2638% | 0,0246 pp |

Selisih di bawah 0,03 poin persen, jadi sampling tidak mengubah kesimpulan.

---

## 2. Hasil utama

| Item | Angka |
|---|---|
| Pair diukur | 41 |
| Pair layak (`ratio >= 2,0`) | **4** |
| Pair yang tabel lama anggap layak | 14 |
| Pair yang turun status (lama layak, baru tidak) | **10** |
| Pair yang naik status (lama tidak, baru layak) | **0** |
| Median MFE 7 jam lintas pair | 1,4371% |
| Median rasio lintas pair | 1,4834x |

Empat pair yang layak:

| Pair | MFE 7h | MAE 7h | Rasio | Status lama |
|---|---:|---:|---:|:---:|
| MUBARAKUSDT | 2,69% | -2,32% | 2,78x | layak |
| PUMPUSDT | 2,50% | -2,19% | 2,58x | layak |
| ZROUSDT | 2,26% | -1,87% | 2,34x | layak |
| PLUMEUSDT | 2,06% | -1,83% | 2,12x | layak |

Sepuluh pair yang **turun** status: ENAUSDT, NEARUSDT, WLDUSDT, STRKUSDT, WUSDT, SUSDT,
FETUSDT, APTUSDT, FILUSDT, QNTUSDT. Tidak ada pair baru yang naik. Artinya, pada jendela 90 hari
ini, tabel lama **terlalu longgar**, bukan terlalu ketat: memperbaruinya akan **mengurangi**
jumlah pair yang boleh di-scalp, bukan menambah.

---

## 3. Tabel lengkap 41 pair

Tabel diurutkan dari rasio tertinggi. Kolom "Lama" adalah status di `data/config/pair_gate.json`
(tabel M1 30 hari).

| Pair | MFE 1h | MFE 4h | MFE 7h | MAE 7h | Rasio | Baru | Lama |
|---|---:|---:|---:|---:|---:|:---:|:---:|
| MUBARAKUSDT | 0.95% | 2.00% | 2.69% | -2.32% | 2.78x | ya | ya |
| PUMPUSDT | 0.86% | 1.82% | 2.50% | -2.19% | 2.58x | ya | ya |
| ZROUSDT | 0.74% | 1.64% | 2.26% | -1.87% | 2.34x | ya | ya |
| PLUMEUSDT | 0.70% | 1.49% | 2.06% | -1.83% | 2.12x | ya | ya |
| PHAUSDT | 0.70% | 1.37% | 1.83% | -1.64% | 1.89x | tidak | tidak |
| BMTUSDT | 0.56% | 1.25% | 1.77% | -1.58% | 1.83x | tidak | tidak |
| ENAUSDT | 0.66% | 1.36% | 1.76% | -1.72% | 1.81x | tidak | ya |
| REZUSDT | 0.56% | 1.26% | 1.73% | -1.47% | 1.79x | tidak | tidak |
| NEARUSDT | 0.61% | 1.27% | 1.72% | -1.64% | 1.78x | tidak | ya |
| WLDUSDT | 0.64% | 1.27% | 1.71% | -1.75% | 1.77x | tidak | ya |
| STRKUSDT | 0.59% | 1.25% | 1.69% | -1.75% | 1.74x | tidak | ya |
| WUSDT | 0.54% | 1.22% | 1.68% | -1.45% | 1.74x | tidak | ya |
| GTCUSDT | 0.53% | 1.19% | 1.68% | -1.41% | 1.73x | tidak | tidak |
| SUSDT | 0.56% | 1.17% | 1.61% | -1.49% | 1.66x | tidak | ya |
| ERAUSDT | 0.52% | 1.16% | 1.56% | -1.40% | 1.61x | tidak | tidak |
| IOUSDT | 0.54% | 1.13% | 1.54% | -1.50% | 1.59x | tidak | tidak |
| FETUSDT | 0.57% | 1.14% | 1.50% | -1.55% | 1.54x | tidak | ya |
| BEAMXUSDT | 0.47% | 1.09% | 1.49% | -1.28% | 1.54x | tidak | tidak |
| TIAUSDT | 0.55% | 1.10% | 1.46% | -1.49% | 1.51x | tidak | tidak |
| METISUSDT | 0.42% | 1.09% | 1.45% | -1.38% | 1.50x | tidak | tidak |
| BATUSDT | 0.44% | 1.03% | 1.44% | -1.23% | 1.48x | tidak | tidak |
| SOXSBUSDT | 0.40% | 0.95% | 1.41% | -1.39% | 1.46x | tidak | tidak |
| APTUSDT | 0.52% | 1.04% | 1.40% | -1.43% | 1.45x | tidak | ya |
| AUSDT | 0.43% | 0.95% | 1.36% | -1.10% | 1.41x | tidak | tidak |
| FILUSDT | 0.47% | 0.97% | 1.31% | -1.38% | 1.35x | tidak | ya |
| ALTUSDT | 0.40% | 0.93% | 1.29% | -1.05% | 1.33x | tidak | tidak |
| ADAUSDT | 0.45% | 0.92% | 1.21% | -1.21% | 1.25x | tidak | tidak |
| QNTUSDT | 0.41% | 0.87% | 1.17% | -1.13% | 1.21x | tidak | ya |
| LINKUSDT | 0.36% | 0.75% | 1.01% | -0.99% | 1.04x | tidak | tidak |
| COMPUSDT | 0.35% | 0.74% | 1.00% | -0.96% | 1.03x | tidak | tidak |
| HBARUSDT | 0.35% | 0.72% | 0.97% | -0.97% | 1.00x | tidak | tidak |
| GNSUSDT | 0.39% | 0.68% | 0.95% | -0.99% | 0.98x | tidak | tidak |
| DOGEUSDT | 0.29% | 0.62% | 0.82% | -0.88% | 0.85x | tidak | tidak |
| SOLUSDT | 0.28% | 0.59% | 0.80% | -0.80% | 0.82x | tidak | tidak |
| XRPUSDT | 0.28% | 0.57% | 0.76% | -0.83% | 0.79x | tidak | tidak |
| ETHUSDT | 0.21% | 0.46% | 0.63% | -0.61% | 0.65x | tidak | tidak |
| BNBUSDT | 0.18% | 0.39% | 0.55% | -0.50% | 0.57x | tidak | tidak |
| BTCUSDT | 0.16% | 0.34% | 0.48% | -0.45% | 0.50x | tidak | tidak |
| NVDABUSDT | 0.10% | 0.24% | 0.34% | -0.32% | 0.35x | tidak | tidak |
| TRXUSDT | 0.06% | 0.15% | 0.24% | -0.18% | 0.24x | tidak | tidak |
| XAUTUSDT | 0.07% | 0.15% | 0.22% | -0.24% | 0.23x | tidak | tidak |

---

## 4. Perbandingan 14 pair tabel lama

Tabel ini membandingkan MFE lama (M1 30 hari) dengan MFE baru (M15 90 hari). Perbedaan metode dan
periode bercampur; tabel **tidak** memisahkan keduanya, tetapi menunjukkan arah perubahan untuk
setiap pair.

| Pair | MFE lama (M1 30 hari) | MFE baru (M15 90 hari) | Selisih | Status baru |
|---|---:|---:|---:|:---:|
| MUBARAKUSDT | 2.73% | 2.69% | -0.03 pp | layak |
| PUMPUSDT | 2.72% | 2.50% | -0.22 pp | layak |
| ZROUSDT | 3.22% | 2.26% | -0.96 pp | layak |
| PLUMEUSDT | 2.22% | 2.06% | -0.17 pp | layak |
| ENAUSDT | 2.25% | 1.76% | -0.49 pp | tidak layak |
| NEARUSDT | 2.89% | 1.72% | -1.17 pp | tidak layak |
| WLDUSDT | 2.01% | 1.71% | -0.30 pp | tidak layak |
| STRKUSDT | 2.61% | 1.69% | -0.92 pp | tidak layak |
| WUSDT | 2.64% | 1.68% | -0.96 pp | tidak layak |
| SUSDT | 2.13% | 1.61% | -0.52 pp | tidak layak |
| FETUSDT | 2.05% | 1.50% | -0.55 pp | tidak layak |
| APTUSDT | 2.14% | 1.40% | -0.74 pp | tidak layak |
| FILUSDT | 1.99% | 1.31% | -0.68 pp | tidak layak |
| QNTUSDT | 2.24% | 1.17% | -1.06 pp | tidak layak |

Catatan: FILUSDT lama tetap eligible meski MFE 1,99% karena rasio dihitung terhadap `F` 0,9688%
(1,9915 / 0,9688 = 2,0556), bukan terhadap ambang MFE 2,0% secara langsung.

**Bacaan:**

- Empat pair yang tetap layak: MUBARAK turun hanya 0,03 pp; PUMP −0,22 pp; PLUME −0,17 pp; ZRO
  turun paling banyak, 3,22% menjadi 2,26% (−0,96 pp), tetapi masih di atas ambang 2,0x.
- Sepuluh pair yang turun: penurunannya 0,30–1,17 pp, misalnya ENA 2,25% menjadi 1,76%, NEAR
  2,89% menjadi 1,72%, dan QNT 2,24% menjadi 1,17%.
- Laporan ini **tidak memisahkan** sebab periode (30 hari vs 90 hari) dari sebab timeframe
  (M1 vs M15). Yang bisa dikatakan: pada jendela 90 hari yang sama untuk semua pair, 10 pair
  kehilangan statusnya, dan 4 pair bertahan. Karena MFE lama juga turun pada pair yang bertahan
  (kecuali MUBARAK), tidak tepat menyimpulkan "timeframe M15 lebih buruk" atau "periode 90 hari
  lebih sepi" secara terpisah tanpa eksperimen tambahan.
- Yang penting untuk keputusan: **tidak ada pair baru yang naik**. Apa pun sebabnya, tabel lama
  tidak bisa dipakai untuk menambah pair; paling banyak ia harus dipersempit.

---

## 5. Temuan

1. **Gerbang 2x friksi hanya lolos 4 dari 41 pair.** Semua pair major (BTC, ETH, BNB, SOL, XRP,
   ADA, DOGE) berada di rasio 0,50x–1,25x. BTC 0,50x dan XAUT 0,23x: pergerakan median 7 jam
   **lebih kecil daripada biaya round-trip**, sehingga secara aritmetika mustahil profit dengan
   entry acak.
2. **Tabel `PairEdgeGate` yang sekarang kedaluwarsa untuk jendela 90 hari.** Sepuluh dari 14 pair
   tidak lagi memenuhi ambang. Tabel itu hardcoded di Kotlin dan dihitung dari M1 30 hari.
3. **Memperbarui tabel tidak menyelamatkan strategi.** Empat pair yang tersisa adalah subset dari
   tabel lama. Backtest H1 pada 4 pair itu menghasilkan **5 trade, semuanya rugi, avg R −0,3997**
   (lihat `docs/exp/exp_spot_backtest_41pair.md`). Gerbang yang lebih ketat hanya memperkecil
   sampel.
4. **MFE bukan edge.** MFE mengukur pergerakan yang tersedia, bukan peluang entry menangkapnya.
   Pair dengan MFE tinggi tetap bisa rugi kalau entry, SL, dan exit tidak cocok. Ini yang terjadi
   pada SWEEP_RECLAIM: pair dengan MFE 2,7% tetap menghasilkan avg R negatif.

---

## 6. Batas eksperimen

- **Timeframe berbeda dari tabel lama.** Tabel lama memakai M1; laporan ini memakai M15. Entry
  `close[k]` pada M15 tidak sama dengan entry M1. Perbandingan angka lama vs baru karena itu
  **bukan** perbandingan murni metode.
- **Periode berbeda.** Tabel lama 30 hari; dataset ini 90 hari (2026-07-22 sampai 2026-10-20).
  Pasar yang sedang tenang akan menurunkan MFE semua pair; pasar yang sedang trending akan
  menaikkannya. Angka ini berlaku untuk jendela tersebut, bukan selamanya.
- **Median tanpa syarat setup.** MFE dihitung dari **semua** candle, bukan hanya candle yang
  memenuhi setup. Pair yang lolos gerbang masih bisa tidak punya sinyal, dan sebaliknya.
- **Belum memasukkan spread, antrean orderbook, atau slippage tambahan.** Friksi yang dipakai
  hanya fee taker + slippage 0,08% per sisi.
- **Sampling.** `step = max(1, n // 4000)`; sudah divalidasi untuk tiga pair, tetapi bukan untuk
  seluruh 41 pair.
- **SOXSBUSDT lebih pendek.** Pair ini hanya punya 7.968 bar 15m (sejak tersedia), bukan 90 hari
  penuh; MFE-nya dihitung dari sampel yang ada.
- **Tidak membuktikan arah mana pun.** MFE/MAE simetris terhadap long dan short; strategi di
  aplikasi adalah spot long-only.

---

## 7. Konsekuensi untuk keputusan berikutnya

- Jangan memperbarui `PairEdgeGate` hanya karena laporan ini: keputusan itu akan mempersempit
  universe dari 14 ke 4 pair, dan belum ada bukti bahwa 4 pair itu menghasilkan edge.
- Kalau tabel ingin dibuat jujur, MFE harus **dihitung ulang berkala** dari jendela terbaru,
  bukan dihardcode sekali. Itu perubahan produk, bukan perubahan angka.
- Langkah berikutnya yang lebih informatif adalah membaca laporan backtest:
  `docs/exp/exp_spot_backtest_41pair.md`. Di sana terlihat bahwa masalahnya bukan hanya gerbang
  pair, tetapi juga geometri SL/TP dan setup-nya.

---

## 8. Reproduksi

```sh
python3 tools/spot_mfe.py --data data/tokocrypto_spot \
    --out data/hasil/mfe_m15_spot.json
```

Berkas hasil memuat seluruh 41 pair, termasuk `samples_7h`, `mfe_1h_median_pct`,
`mfe_4h_median_pct`, `mfe_7h_median_pct`, `mae_7h_median_pct`, `ratio_7h`, `eligible_7h`,
`old_mfe_7h_m1_pct`, dan `change_vs_old_gate`.

---

## 9. Riwayat

| Tanggal | Peristiwa |
|---|---|
| 2026-10-20 | Dataset 41 pair 90 hari selesai diunduh dan diverifikasi. |
| 2026-10-20 | `tools/spot_mfe.py` dijalankan; hasil 4/41 layak, 10 pair turun, 0 pair naik. |
| 2026-10-20 | Laporan ini ditulis; backtest belum dijalankan saat MFE diukur. |
| 2026-10-20 | Backtest dijalankan; hasilnya ditautkan di §5 butir 3 dan laporan terpisah. |
