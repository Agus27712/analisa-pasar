# Eksperimen backtest Spec Scalping Spot v2 — 41 pair Tokocrypto, 90 hari (2026-10-20)

Laporan ini mengukur **Spec Scalping Spot v2** (`docs/exp/scalping_spot_strategy_spec.md`) pada
dataset `data/tokocrypto_spot` (41 pair USDT, 90 hari, candle 15m dan 1h). Harness-nya adalah
`tools/spot_backtest.py` — **bukan** mesin Kotlin. Spec §11 mewajibkan mesin Kotlin + uji paritas,
jadi angka di sini adalah **hasil riset**, bukan hasil produksi.

- **Pertanyaan:** apakah setup, gerbang G0–G8, dan exit Spec v2 menghasilkan expectancy positif
  setelah friksi Tokocrypto?
- **Jawaban singkat:** **tidak ada konfigurasi yang diukur menghasilkan expectancy positif.**
  Pada gates produksi: **0 trade di 15m**, **7 trade di 1h dan semuanya rugi**. Setelah gate
  dilonggarkan sebagai riset: 37–74 trade, semua avg R negatif; yang "terbaik" −0,2055R dan
  performanya memburuk out-of-sample.
- **Artefak:** `data/hasil/spot_backtest_v2_matrix.txt`, `data/hasil/spot_backtest_v2_*.json`,
  `data/hasil/spot_backtest_v2_*_trades.csv`.
- **Status:** pengukuran offline. Kode aplikasi belum disentuh.

---

## 1. Metode

| Komponen | Nilai |
|---|---|
| Data | `data/tokocrypto_spot` — 41 pair, 90 hari (2026-07-22 sampai 2026-10-20) |
| Timeframe eksekusi | 15m dan 1h |
| HTF | 15m memakai 1h; 1h memakai 4h hasil agregasi (tanpa melihat candle yang belum close) |
| Warmup | 150 candle eksekusi; pada 1h produksi indeks entry minimum 359 (terukur) |
| Fee | taker Tokocrypto USDT: beli 0,4044%, jual 0,4044%, slippage 0,08% per sisi; `F` aditif 0,9688% |
| P&L | bentuk `FeeSchedule` yang sama dengan aplikasi; impas net 0,9735% |
| Modal / risiko | equity 10.000 USD, risiko 0,5% per trade |
| Split | 70% in-sample, 30% out-of-sample, kronologis per pair |
| Lantai SL | 6x `F` = 5,8128% (default produksi) |
| G0 | `data/config/pair_gate.json` — 14 pair USDT |
| Setup | PULLBACK_VWAP, BREAKOUT_RETEST, SWEEP_RECLAIM, RANGE_BOUNCE, SQUEEZE_BREAKOUT |
| Exit | TP1 sebagian 60%, stop pindah break-even setelah TP1, trailing per setup, time-stop per setup, invalidasi setup, momentum fail, regime change |

Kebijakan TP1: Spec v2 menulis TP1 formula `entry + 1,0R`. Secara aritmetika itu **selalu**
menghasilkan `netRr1 = (R − F) / (R + F) < 1,0`, sehingga pasti ditolak G4. Karena itu harness
menyediakan dua mode:

- **`floor`** (dipakai untuk seluruh varian utama): TP1 formula dinaikkan ke
  `entry + max(1,0R, R + 2F)` sehingga `netRr1 = 1,0`. TP1 struktural tidak digeser; bila
  `netRr1 < 1,0` sinyal ditolak.
- **`literal`** (kontrol negatif): TP1 formula dipakai apa adanya. Ini membuktikan kontradiksi
  Spec v2: seluruh setup formula menghasilkan **0 trade** karena G4.

Perintah matriks lengkap ada di `data/hasil/spot_backtest_v2_matrix.txt`. Contoh perintah inti:

```sh
# produksi 15m (G0 aktif, lantai 6x, gates default)
python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 15m \
    --pairs all --setups all --tp1-policy floor \
    --out data/hasil/spot_backtest_v2_m15_prod

# produksi 1h
python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 1h \
    --pairs all --setups all --tp1-policy floor \
    --out data/hasil/spot_backtest_v2_h1_prod

# riset: lantai 4x + G3 1,0F + G4/G5/G6 longgar (bukan produksi)
python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 1h \
    --pairs all --setups all --tp1-policy floor --no-pair-edge \
    --friction-multiple 4 --min-atr-over-friction 1.0 \
    --min-net-rr-tp1 0.5 --min-net-rr-final 1.0 --min-reward-over-friction 2.0 \
    --out data/hasil/spot_backtest_v2_h1_noedge_floor4_g4g5loose
```

---

## 2. Matriks hasil

Kolom "G0" menyatakan gate pair aktif atau tidak. Kolom "lantai" adalah `min_friction_multiple`.
Kolom "G3/G4/G5/G6" menyatakan gate default (D) atau longgar (L). `n` = jumlah trade;
`IS`/`OOS` = avg R in-sample/out-of-sample.

| Varian | TF | G0 | Lantai | G3-G6 | n | avg R | WR | PF | IS (n, avg R) | OOS (n, avg R) |
|---|---|:---:|---|---:|---:|---:|---:|---:|---|---|
| 15m produksi | 15m | ya | 6x | D | 0 | - | - | - | 0, - | 0, - |
| 15m literal | 15m | ya | 6x | D | 0 | - | - | - | 0, - | 0, - |
| 15m no-G0 | 15m | tidak | 6x | D | 1 | -0.8084 | 0.0% | 0.0000 | 0, - | 1, -0.8084 |
| 15m no-G0, lantai 5x + G3 1.0F + G4-G6 longgar | 15m | tidak | 5x | L | 44 | -0.2260 | 22.7% | 0.351 | 22, -0.1542 | 22, -0.2978 |
| 15m no-G0, lantai 4x + G3 1.0F + G4-G6 longgar | 15m | tidak | 4x | L | 37 | -0.2758 | 21.6% | 0.332 | 21, -0.1965 | 16, -0.3798 |
| 1h produksi | 1h | ya | 6x | D | 7 | -0.3835 | 0.0% | 0.0000 | 3, -0.3769 | 4, -0.3885 |
| 1h produksi, 4 pair MFE | 1h | ya | 6x | D | 5 | -0.3997 | 0.0% | 0.0000 | 2, -0.3676 | 3, -0.4211 |
| 1h literal | 1h | ya | 6x | D | 6 | -0.3841 | 0.0% | 0.0000 | 3, -0.3769 | 3, -0.3914 |
| 1h no-G0 | 1h | tidak | 6x | D | 13 | -0.4028 | 7.7% | 0.0002 | 7, -0.4265 | 6, -0.3750 |
| 1h no-G0, lantai 5x | 1h | tidak | 5x | D | 21 | -0.2970 | 19.1% | 0.176 | 13, -0.2906 | 8, -0.3073 |
| 1h no-G0, G4-G6 longgar | 1h | tidak | 6x | L | 48 | -0.2320 | 18.8% | 0.320 | 33, -0.1394 | 15, -0.4357 |
| 1h no-G0, lantai 5x + G4-G6 longgar | 1h | tidak | 5x | L | 60 | -0.2087 | 23.3% | 0.456 | 37, -0.0869 | 23, -0.4046 |
| 1h no-G0, lantai 4x + G4-G6 longgar | 1h | tidak | 4x | L | 74 | -0.2055 | 23.0% | 0.500 | 45, -0.0912 | 29, -0.3828 |
| 1h no-G0, tanpa lantai | 1h | tidak | 0 | D | 54 | -0.3373 | 20.4% | 0.377 | 35, -0.3419 | 19, -0.3288 |

Catatan: baris "1h produksi, 4 pair MFE" dijalankan dengan membatasi `--pairs` ke
MUBARAKUSDT, PUMPUSDT, ZROUSDT, PLUMEUSDT; `data/config/pair_gate.json` **tidak** diubah.

**Bacaan utama:**

- **Produksi 15m: 0 trade.** Bukan karena harness rusak — funnel menunjukkan ratusan sinyal
  masuk, lalu gugur di G0/G2/G3. Lihat §3.
- **Produksi 1h: 7 trade, WR 0%, avg R −0,3835.** Sampel terlalu kecil untuk menyimpulkan
  expectancy, tetapi cukup untuk menyatakan gates produksi hampir tidak bisa dieksekusi.
- **Semua varian riset tetap negatif.** Yang paling longgar (lantai 4x + gates G3–G6 longgar)
  menghasilkan 74 trade dengan avg R **−0,2055**; in-sample −0,0912, out-sample **−0,3828**.
  Artinya, satu-satunya varian yang terlihat "mendekati impas" pun memburuk di luar sampel.
- **Melonggarkan lantai friksi bukan penyelamat.** Tanpa lantai sama sekali (`--friction-multiple 0`)
  hasilnya −0,3373R dengan 54 trade; lebih buruk daripada lantai 4x–5x. Lantai bukan satu-satunya
  penyebab kerugian.
- **TP1 `literal` mengonfirmasi kontradiksi Spec v2.** 15m literal 0 trade; 1h literal 6 trade
  (hanya setup struktural SWEEP), sementara setup formula BREAKOUT hilang sama sekali.

---

## 3. Funnel: kenapa produksi 15m nol

Gerbang yang mengikat pada 15m adalah **kombinasi lantai 6x dan G3**:

- Lantai SL 6x = **5,8128%**. G2 mensyaratkan jarak SL maksimum 5x ATR, sehingga ATR% harus
  **≥ 1,16256%** agar lantai tidak melebihi 5 ATR.
- G3 mensyaratkan ATR% **≥ 1,5 x F = 1,4532%** (batas bawah ini tidak bisa diturunkan dengan
  menaikkan `maxSlAtr`; ia konstanta 1,5F).
- ATR% median 15m pada dataset ini (ATR 14, seluruh bar setelah warmup): BTC 0,21%, ETH 0,29%,
  SOL 0,36%, sampai MUBARAK 1,09%, PUMP 1,02%, ZRO 0,88%.

Akibatnya, mayoritas sinyal gugur sebelum sempat diuji R:R. Funnel produksi 15m (agregat 41 pair):

| Setup | Sinyal | Gugur G0 | SL_ATR_RANGE | ATR_TOO_LOW | NET_RR_TP1 | NET_RR_FINAL |
|---|---:|---:|---:|---:|---:|---:|
| PULLBACK_VWAP | 7 | 7 | 0 | 0 | 0 | 0 |
| BREAKOUT_RETEST | 324 | 214 | 88 | 12 | 2 | 8 |
| SWEEP_RECLAIM | 711 | 456 | 219 | 24 | 8 | 4 |
| RANGE_BOUNCE | 2 | 2 | 0 | 0 | 0 | 0 |
| SQUEEZE_BREAKOUT | 85 | 65 | 14 | 1 | 2 | 3 |

Funnel produksi 1h (agregat 41 pair):

| Setup | Sinyal | Gugur G0 | SL_ATR_RANGE | ATR_TOO_LOW | NET_RR_TP1 | NET_RR_FINAL |
|---|---:|---:|---:|---:|---:|---:|
| PULLBACK_VWAP | 1 | 1 | 0 | 0 | 0 | 0 |
| BREAKOUT_RETEST | 59 | 43 | 5 | 3 | 3 | 4 |
| SWEEP_RECLAIM | 422 | 277 | 45 | 26 | 63 | 5 |
| SQUEEZE_BREAKOUT | 24 | 16 | 2 | 2 | 3 | 1 |

Pada 1h, ATR% lebih besar sehingga G3 lebih jarang menggugurkan sinyal, tetapi G2/G3 masih
menghabiskan 71 sinyal SWEEP (45 `SL_ATR_RANGE` + 26 `ATR_TOO_LOW`). Setelah itu, **G4/G5
(net R:R)** menjadi penyaring terbesar: 63 sinyal SWEEP gugur di `NET_RR_TP1` karena target
strukturalnya tidak mencapai 1,0 net R setelah risiko 5,81%. G0 (tabel pair lama) tetap
membuang 277 sinyal SWEEP.

**PULLBACK_VWAP praktis mati.** Funnel 15m hanya menemukan **7 sinyal**, dan 1h hanya **1 sinyal**;
seluruhnya gugur di G0 karena terjadi pada pair yang tidak ada di tabel gate. Setup ini memang
mensyaratkan konfirmasi candle **dan** volume ≥ 1,0, sehingga sinyalnya jarang. Dengan 0 trade yang
lolos gate, tidak ada bukti setup ini bisa dieksekusi pada universe 14 pair.

**RANGE_BOUNCE tidak pernah terpicu** (2 sinyal di 15m, 0 di 1h) — syarat dua sentuhan per sisi
plus konfirmasi terlalu ketat untuk jendela 90 hari.

---

## 4. Per setup

Tabel ini dari varian riset paling longgar (1h, lantai 4x, G3 1.0F, G4 0.5, G5 1.0, G6 2.0):

| Setup | n | avg R | WR | PF | Catatan |
|---|---:|---:|---:|---:|---|
| BREAKOUT_RETEST | 6 | +0,2055 | 33,3% | 1,579 | positif, tetapi n=6 |
| SWEEP_RECLAIM | 67 | **−0,2765** | 20,9% | 0,344 | setup terbanyak, konsisten rugi |
| SQUEEZE_BREAKOUT | 1 | +2,0903 | 100% | — | tidak bisa disimpulkan |
| PULLBACK_VWAP | 0 | — | — | — | tidak ada sinyal |
| RANGE_BOUNCE | 0 | — | — | — | tidak ada sinyal |

Pola yang sama terlihat di varian lain:

- **SWEEP_RECLAIM** adalah setup dengan sampel terbesar dan ia rugi di **semua** konfigurasi:
  6 trade di produksi 1h (−0,3841), 12 di 1h no-G0 (−0,4047), 13–18 di varian longgar 15m
  (−0,17 sampai −0,21), dan 40–67 di varian longgar 1h (−0,28 sampai −0,37). Alasan keluar
  terbanyak adalah `SETUP_INVALIDATION` (32 dari 74 trade pada varian 1h lantai 4x), bukan SL/TP.
  Artinya masalahnya ada di **pemicu masuk dan aturan invalidasi**, bukan hanya di geometri TP.
- **BREAKOUT_RETEST** tidak konsisten: negatif pada 14–22 trade di 15m longgar (−0,28R), tetapi
  positif pada n=6 di 1h longgar (+0,19 sampai +0,21R). Dengan n=6, satu trade menang bisa
  mengubah tanda; arahnya belum bisa disimpulkan.
- **SQUEEZE_BREAKOUT** n=1–7 dengan tanda yang berubah-ubah. Tidak bisa dinilai.
- **PULLBACK** hanya 2 trade (15m longgar, keduanya rugi, −0,83R dan −0,99R); **RANGE** 0 trade
  di semua varian.

---

## 5. Di mana trade keluar

Varian 1h lantai 4x + gates longgar (74 trade):

| Alasan keluar | Jumlah |
|---|---:|
| SETUP_INVALIDATION | 32 |
| MOMENTUM_FAIL | 13 |
| TRAILING_STOP | 10 |
| STOP_LOSS | 10 |
| TIME_STOP | 7 |
| REGIME_CHANGE | 1 |
| TAKE_PROFIT_2 | 1 |

Hanya **1 dari 74** trade mencapai TP2; TP1 tersentuh 16,2%. Mayoritas trade berakhir karena
invalidasi setup atau momentum fail, dengan median MFE hanya 0,43R. Ini konsisten dengan
kesimpulan §4: pemicu masuk menangkap gerakan yang terlalu kecil untuk menutup friksi.

Varian produksi 1h (7 trade) bahkan lebih ekstrem: 6 dari 7 keluar `SETUP_INVALIDATION`,
TP1 0%, median MFE 0,13R.

---

## 6. Uji mekanika harness

`tools/test_spot_backtest.py` berisi 10 uji yang lulus:

1. SL diperiksa sebelum TP pada candle yang sama (SL menang).
2. TP1 menjual 60%, sisa 40%.
3. TP2 hanya dievaluasi pada candle **setelah** TP1.
4. Stop tidak pernah turun (break-even lalu trailing).
5. Time-stop memakai syarat `maxFavorable < 0,5R`.
6. Impas net 100 → 100,9735157% sama dengan `FeeSchedule`.
7. Gate struktural lolos.
8. TP1 formula `floor` lolos G4 dengan `netRr1 = 1,0`.
9. TP1 formula `literal` ditolak G4.
10. Pemetaan HTF tidak memakai candle yang belum close.

Pemeriksaan tambahan:

- **Determinisme:** dua kali jalan perintah yang sama menghasilkan CSV trade dengan SHA-256
  identik.
- **Tanpa trade sebelum warmup:** indeks entry minimum 359 (warmup 150 + waktu tunggu sinyal).
- **Invarian kuantitas:** `Trade.finalize` melempar `AssertionError` bila jumlah kuantitas keluar
  tidak sama dengan kuantitas total; seluruh varian selesai tanpa error.

---

## 7. Batas harness yang harus dibaca sebelum mengutip angka

- **Bukan mesin Kotlin.** Spec §11 mensyaratkan mesin Kotlin dan uji paritas. Harness ini adalah
  prototipe riset; angka tidak boleh diklaim sebagai hasil produksi.
- **Harga fill = level mentah.** Slippage dimasukkan lewat `FeeSchedule` (bentuk fee), bukan
  simulasi orderbook. Spread, antrean, dan dampak pasar tidak dimodelkan.
- **G8 (orderbook) tidak diuji** karena dataset candle tidak memuat orderbook.
- **Manajemen risiko per simbol, bukan portofolio.** `maxConcurrentPositions`, korelasi antar
  pair, dan batas rugi harian lintas pair tidak dimodelkan seperti aplikasi.
- **Cooldown dan pause diskalakan per timeframe.** Aturan "15 menit" dan "1 jam" di 15m menjadi
  1 bar dan 4 bar; di 1h menjadi 1 jam dan 4 jam. Perbandingan 15m vs 1h karena itu bukan
  perbandingan murni.
- **Time-stop dan lookback dihitung dalam candle.** Pada 1h, `timeStopCandles = 8` berarti 8 jam,
  bukan 2 jam. Ini mengubah strategi, bukan hanya memperbesar timeframe.
- **Pemilihan universe tidak out-of-sample.** 41 pair dipilih dari snapshot momentum 24 jam pada
  jendela yang sama dengan data pengukuran. Split 70/30 hanya kronologis, bukan pemilihan pair
  out-of-sample.
- **SOXSBUSDT tidak mencakup 90 hari penuh.** Dataset hanya memuat 7.968 bar 15m / 1.992 bar 1h
  (sejak pair ini tersedia), jadi jendela dan bobotnya lebih kecil daripada 40 pair lain.
- **G0 memakai tabel lama yang kedaluwarsa.** Menurut `docs/exp/exp_mfe_m15_41pair.md`, tabel itu
  seharusnya hanya 4 pair pada jendela 90 hari. Varian "G0 aktif" karena itu **lebih longgar**
  daripada gate yang jujur; varian "no-G0" menunjukkan hasil tanpa gate sama sekali.
- **Sampel produksi sangat kecil** (0 dan 7 trade). Kesimpulan "expectancy negatif" bersandar pada
  varian riset yang lebih longgar (37–74 trade), bukan pada angka produksi.
- **Tidak ada kontrol multiple-testing.** Empat belas varian dijalankan; sebagian perbedaan bisa
  berasal dari variasi acak. Arah semua varian konsisten negatif, jadi kesimpulan arah tidak
  bergantung pada satu varian.

---

## 8. Kesimpulan

1. **Spec v2 dengan gates produksi tidak dapat dieksekusi pada dataset ini.** 15m: 0 trade dari
   41 pair dalam 90 hari. 1h: 7 trade, semuanya rugi, WR 0%.
2. **Penyebab utama di 15m adalah aritmetika, bukan bug.** Lantai 6x (5,8128%) + G2 maksimum 5 ATR
   + G3 minimum 1,5F (1,4532%) saling mengunci. ATR% 15m Tokocrypto sebagian besar 0,2–1,1%,
   sehingga hanya segelintir bar yang bisa lolos.
3. **Setelah gate dilonggarkan, strategi tetap rugi.** 37–74 trade, avg R −0,2055 sampai −0,3373,
   dengan performa out-of-sample lebih buruk daripada in-sample. Tidak ada tanda edge.
4. **SWEEP_RECLAIM adalah sumber kerugian utama** dan konsisten rugi di semua konfigurasi;
   BREAKOUT_RETEST negatif pada 14–22 trade di 15m dan positif hanya pada n=6 di 1h;
   SQUEEZE n=1–7 dengan tanda berubah-ubah; PULLBACK hanya 2 trade (keduanya rugi); RANGE 0.
5. **Kontradiksi TP1/G4 Spec v2 terbukti.** Mode `literal` menghasilkan 0 trade untuk setup
   formula; mode `floor` adalah satu-satunya cara setup formula bisa lolos, dan itu berarti
   spec perlu direvisi sebelum implementasi.
6. **Rekomendasi:** **jangan mulai Fase 1 Kotlin dulu.** Perbaiki dulu kontradiksi spec dan
   rancang ulang pemicu/exit SWEEP, atau ubah arah ke horizon yang lebih panjang. Kalau owner
   ingin melanjutkan, urutan yang paling murah: (a) putuskan apakah lantai 6x + G3 1,5F tetap
   dipertahankan (kalau ya, 15m mati secara aritmetika); (b) uji ulang SWEEP dengan aturan
   invalidasi yang lebih longgar dan entry yang lebih awal; (c) baru pertimbangkan Kotlin.

---

## 9. Reproduksi

```sh
# uji mekanika harness
python3 tools/test_spot_backtest.py

# matriks lengkap (14 varian) — lihat isi skrip di data/hasil/spot_backtest_v2_matrix.txt
python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 15m \
    --pairs all --setups all --tp1-policy floor \
    --out data/hasil/spot_backtest_v2_m15_prod
python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 1h \
    --pairs all --setups all --tp1-policy floor \
    --out data/hasil/spot_backtest_v2_h1_prod
```

Setiap varian menghasilkan satu `.json` (ringkasan + funnel + metrik per setup/pair) dan satu
`_trades.csv` (daftar trade). Ringkasan teks seluruh varian ada di
`data/hasil/spot_backtest_v2_matrix.txt`.

---

## 10. Riwayat

| Tanggal | Peristiwa |
|---|---|
| 2026-10-20 | `tools/spot_lib.py`, `tools/spot_mfe.py`, `tools/spot_backtest.py` dibuat; MFE 4/41 layak. |
| 2026-10-20 | Backtest produksi 15m dijalankan: 0 trade. Funnel menunjukkan G3/lantai sebagai penghalang. |
| 2026-10-20 | Dukungan timeframe 1h + override gate riset ditambahkan; produksi 1h: 7 trade, semuanya rugi. |
| 2026-10-20 | Matriks 14 varian dijalankan; semuanya negatif. Uji mekanika 10 uji lulus; determinisme diverifikasi. |
| 2026-10-20 | Laporan ini ditulis. |
