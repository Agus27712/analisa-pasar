# Eksperimen: Gerbang Bukti Spec v3 — harness Python, 41 pair, 180 hari

**Tanggal:** 2026-10-21
**Status:** RISET OFFLINE. Bukan hasil aplikasi, bukan hasil produksi.
**Dataset:** `data/tokocrypto_spot_180` (Tokocrypto USDT, 15m + 1h, 180 hari).
**Alat:** `tools/spot_evidence.py` → `spot_mfe.py`, `spot_backtest.py`, `spot_benchmark.py`.
**Spec acuan:** `docs/exp/scalping_spot_strategy_spec.md` v3 (§6, §6.2, §7, §11.1, §11.2).

## 0. Ringkasan

- Dataset 180 hari selesai: **868.054 bar**, 82 CSV, **0 celah**, **0 duplikat**.
- Gate edge dari jendela A hanya menyisakan **1 pair (IOUSDT, rasio 2,0052)**. Jendela B
  menyisakan 4 pair, dan **irisannya kosong**. Gate tidak stabil.
- **E1 tidak terukur, bukan sekadar kurang sampel.** Pada gates produksi, 15m menghasilkan
  **0 trade** dan 1h juga **0 trade out-of-sample** (hanya 1 trade di jendela A). Tidak ada setup
  yang punya satu pun trade OOS.
- **E2 tidak terukur pada gates produksi** karena tidak ada trade. Pada diagnostik tanpa G0,
  entry strategi **kalah dari entry acak** di semua sampel yang cukup: 15m SWEEP R kotor
  −0,0866 vs acak p99 +0,2560; 1h panel penuh 180 hari SWEEP −0,1165 vs p99 +0,1122.
- **E3 sebagian.** Gate edge dihitung dari jendela A, tetapi universe 41 pair dipilih dari
  snapshot 24 jam pada akhir jendela B.
- **E4 tidak cukup sampel.** Base produksi < 10 trade; variasi parameter tidak dapat mengubah
  status itu. Satu varian longgar (`min_net_rr_final ×0,8`) memberi **1 trade** +1,403R — satu
  trade bukan sampel. Tanda negatif konsisten di tiga sampel diagnostik, tetapi itu bukan E4 formal.
- **Fase 1 Kotlin tetap diblokir.** Langkah berikutnya bukan menulis engine, melainkan merancang
  ulang entry (terutama SWEEP) dan/atau horizon, lalu mengukur ulang R kotor terhadap acak.

## 1. Pertanyaan yang diukur

Spec v3 §11.2 menetapkan empat gerbang sebelum Fase 1 Kotlin boleh dimulai:

| Gerbang | Pertanyaan | Ambang |
|---|---|---|
| **E1** | Apakah tiap setup punya cukup sampel out-of-sample? | ≥ 100 trade OOS per setup |
| **E2** | Apakah entry mengalahkan entry acak? | R kotor setup ≥ persentil ke-99 distribusi acak **dan** R bersih OOS > 0 |
| **E3** | Apakah gate/universe dipilih dari data sebelum jendela uji? | gate edge dari jendela A; universe walk-forward |
| **E4** | Apakah tanda hasil stabil? | tanda avg R sama pada parameter ±20%, `tp1Fraction` 0,5/0,6, dan ≥ 2 jendela tidak tumpang tindih |

E1 dan E2 diukur **per setup dan per timeframe**. Tidak ada penggabungan setup untuk mengejar
jumlah sampel.

## 2. Dataset dan jendela

- Sumber candle: Tokocrypto `www.tokocrypto.site/api/v3` (bukan Binance).
- 41 pair USDT, panel yang sama dengan dataset 90 hari (`data/tokocrypto_spot`), dibekukan agar
  perbandingan tidak berubah karena pemilihan pair baru.
- 82 berkas CSV: 41 × 15m dan 41 × 1h. Total **868.054 bar**, **44 MB**, **0 celah**,
  **0 duplikat**.
- Dua pair listing baru mulai lebih lambat: NVDAUSDT (2026-06-11) dan SOXSBUSDT (2026-07-29).
  Tidak ada celah di dalam rentang yang tersedia.
- Jendela: **A** = 90 hari pertama (2026-04-23 12:09 → 2026-07-22 12:09), **B** = 90 hari kedua
  (2026-07-22 12:09 → 2026-10-20 12:09). Out-of-sample B = 30% terakhir B
  (2026-09-23 12:09 → 2026-10-20 12:09).
- Gate edge (G0) dihitung dari MFE 7 jam jendela A, ambang 2,0× friksi aditif (0,9688%),
  lalu dipakai untuk jendela B. Tabel `data/config/pair_gate.json` yang lama **tidak diubah**.

## 3. Metode

### 3.1 Backtest

- 5 setup Spec v3, long-only, taker-only, modal awal $10.000, risiko 0,5% per trade.
- G0–G2 produksi; G3 dicabut (0); TP1 `spec` = `tp1Floor`; G4–G7 aktif.
- Lantai SL 6× friksi = 5,8128%; SL efektif dari `resolve` (hanya melebar).
- Risiko per unit = `costBasisNet(1) − netSellValue(1, slEff)`; qty = risiko USD / risiko per unit.
- Exit §7: SL-before-TP, TP1 60%, breakeven, trailing per setup, time-stop dari jam,
  momentum fail, regime change, invalidasi setup.
- `maxConcurrentPositions = 2` diterapkan sebagai **filter kronologis** setelah simulasi per pair.
- Split 70/30 di dalam jendela B; trade `END_OF_DATA` tetap dihitung.

### 3.2 Benchmark entry acak (§11.1)

- K = 200 run, seed 20261021.
- Tiap run memilih N bar entry acak (seragam) dari bar yang regime-nya diizinkan setup itu,
  N = jumlah sinyal setup yang lolos G0–G2 pada jendela uji.
- Entry `MARKET_NEXT_OPEN`; jarak SL diambil dari distribusi `slEff/ATR` sinyal asli; TP1 =
  `tp1Floor`; TP2 = null; exit §7 **tanpa** `SETUP_INVALIDATION`.
- Persentil ke-99 dihitung dari distribusi **rata-rata run lintas pair**, bukan dari trade
  individual. Ini yang dibandingkan dengan rata-rata R strategi pada jendela yang sama.

### 3.3 Batas yang disengaja

- Harga fill = level mentah; slippage masuk lewat `FeeSchedule` (bukan dikalikan dua kali).
- G8 spread dan kedalaman order book tidak tersedia di data candle; gerbang dilewati.
- Tick size memakai 1e-5 × harga; lot step tidak dibulatkan.
- Risk manager berjalan **per pair**; batas rugi harian dan pause 3 loss belum lintas pair.
  Akibatnya jumlah trade bisa lebih banyak daripada produksi — arah biasnya optimistis.
- Filter posisi bersamaan tidak menggantikan trade yang ditolak; arah biasnya konservatif
  terhadap jumlah trade, tetapi tidak terhadap komposisi pemenang/kalah.
- Universe 41 pair dipilih dari snapshot 24 jam pada akhir jendela B, jadi E3 baru terpenuhi
  untuk gate edge, bukan untuk universe.
- Angka ini riset Python; mesin Kotlin dan uji paritas belum ada.

## 4. Hasil E1–E4

### 4.1 Hasil produksi (gate A, TP1 spec, G3 mati)

| setup | 15m n OOS | 15m avg R | 1h n OOS | 1h avg R | E1 | E2 |
|---|---:|---:|---:|---:|---|---|
| PULLBACK_VWAP | 0 | – | 0 | – | gagal | gagal |
| BREAKOUT_RETEST | 0 | – | 0 | – | gagal | gagal |
| SWEEP_RECLAIM | 0 | – | 0 | – | gagal | gagal |
| RANGE_BOUNCE | 0 | – | 0 | – | gagal | gagal |
| SQUEEZE_BREAKOUT | 0 | – | 0 | – | gagal | gagal |

**Tidak ada satu pun trade OOS.** Rinciannya:

- **15m:** 1.217 sinyal dipindai; **0 kandidat** lolos G0–G2. G0 menolak 1.183 sinyal; G2
  menolak 34 sinyal sisanya. Penyebab utamanya IOUSDT (satu-satunya pair gate A) punya ATR 15m
  terlalu kecil untuk lantai SL 5,8128% (lihat §4.5).
- **1h jendela B:** 7 kandidat SWEEP dan 5 kandidat BREAKOUT lolos G0–G2, tetapi semuanya
  ditolak G4/G5 (TP1 struktural di bawah lantai atau R:R final kurang). **0 trade.**
- **1h jendela A:** 1 trade SWEEP, avg R **−0,4849**, R kotor **−0,3417**, keluar lewat
  `SETUP_INVALIDATION` setelah 3 bar. Ini in-sample untuk gate A, jadi tidak boleh dipakai
  sebagai bukti E1.

Benchmark acak pada gates produksi juga tidak bisa dibandingkan: 15m tidak punya kandidat;
pada benchmark OOS, 1h hanya punya N=1 untuk SWEEP dan BREAKOUT, dengan 0 trade strategi. E2
karena itu **gagal karena tidak ada yang bisa diukur**, bukan karena strategi terbukti lebih
baik.

### 4.2 Diagnostik tanpa G0 — jendela B (90 hari)

Diagnostik ini **bukan produksi**: G0 dimatikan agar terlihat apakah masalahnya gate atau entry.
Semua setup lain tetap sama (TP1 spec, G3 mati, lantai 6×).

| setup | tf | trade | avg R | avg R kotor | friksi R | WR | exit utama |
|---|---|---:|---:|---:|---:|---:|---|
| SWEEP_RECLAIM | 15m | 7 | −0,2495 | −0,1061 | 0,1434 | 14,3% | INVALIDATION 5, MOMENTUM 2 |
| SWEEP_RECLAIM | 1h | 44 | −0,2533 | −0,1117 | 0,1415 | 15,9% | INVALIDATION 24, TIME_STOP 8, MOMENTUM 7 |
| BREAKOUT_RETEST | 1h | 1 | −0,3816 | −0,2411 | 0,1404 | 0,0% | INVALIDATION 1 |

Rincian 15m: 7 trade dari 5 pair, PF 0,2104, maxDD 1,75R, TP1 hit **0%**, avg hold 7,57 bar,
median MFE 0,129R, median MAE −0,2364R. SL: 1 dari sinyal, 6 dari lantai friksi. Tidak ada
trade yang menyentuh TP1 atau SL; semuanya keluar lewat invalidasi/momentum.

Rincian 1h: 45 trade dari 21 pair (44 SWEEP + 1 BREAKOUT), PF 0,1839, maxDD 11,67R,
TP1 hit **0%**, avg hold 7,02 bar. OOS 16 trade, avg R **−0,3043**, R kotor **−0,1639**.
Exit: 25 invalidasi, 8 time-stop, 7 momentum, 3 SL, 2 regime change. Hanya 1 trade memakai SL
dari sinyal; 44 memakai lantai friksi.

**Tidak ada satu pun setup dengan avg R positif.** SWEEP — setup dengan sinyal terbanyak —
konsisten menjadi sumber kerugian utama.

### 4.3 Diagnostik panel penuh 180 hari (tanpa G0)

Untuk mengukur batas atas jumlah sampel: seluruh 41 pair, 180 hari, tanpa G0. Ini **bukan**
E1 karena tidak memakai gates produksi dan universe-nya belum walk-forward.

| tf | trade | OOS trade | avg R | OOS avg R | avg R kotor | WR | PF | maxDD |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 15m | 12 | 5 | −0,2888 | −0,2510 | −0,1496 | 8,3% | 0,1184 | 3,47R |
| 1h | 67 | 26 | −0,2391 | −0,2624 | −0,0969 | 20,9% | 0,2123 | 16,17R |

- 15m: 11 dari 12 trade adalah SWEEP (avg R −0,2445, R kotor −0,1017); 1 BREAKOUT (−0,7751).
- 1h: 66 dari 67 trade adalah SWEEP (avg R −0,2369, R kotor −0,0947); 1 BREAKOUT (−0,3816).
- Filter portofolio 2 posisi: 1h 65 trade, avg R −0,2474.
- **Batas sampel:** bahkan dengan seluruh panel dan 180 hari, OOS hanya **5 trade (15m)** dan
  **26 trade (1h)** per SWEEP. E1 menuntut 100 trade OOS per setup. Data ini **4–20 kali** lebih
  sedikit. Menambah data tidak akan menyelamatkan entry yang R kotornya negatif.

### 4.4 Benchmark entry acak

Perbandingan memakai jendela OOS yang sama. `N` = kandidat G0–G2; `trade` = trade strategi
yang benar-benar tereksekusi. R kotor strategi dan acak memakai exit yang sama.

**Semua baris di tabel ini adalah diagnostik tanpa G0**, karena gates produksi tidak menghasilkan
trade yang bisa dibandingkan. Baris "B 90 hari" memakai jendela B; "180 hari" memakai panel penuh.
Benchmark gates produksi ada di §4.1 dan hasilnya tidak terukur.

| sampel | tf | setup | N | trade | R kotor strategi | acak rata-rata | acak p99 | selisih | putusan |
|---|---|---|---:|---:|---:|---:|---:|---:|---|
| B 90 hari | 15m | SWEEP | 33 | 4 | −0,0866 | +0,0167 | +0,2560 | −0,1032 | gagal |
| B 90 hari | 1h | SWEEP | 78 | 15 | −0,1588 | +0,0116 | +0,1606 | −0,1703 | gagal |
| B 90 hari | 1h | BREAKOUT | 8 | 1 | −0,2411 | +0,0266 | +0,5087 | −0,2677 | gagal |
| 180 hari | 15m | SWEEP | 47 | 5 | −0,1163 | +0,0084 | +0,1721 | −0,1247 | gagal |
| 180 hari | 1h | SWEEP | 160 | 25 | −0,1165 | +0,0157 | +0,1122 | −0,1322 | gagal |
| 180 hari | 1h | BREAKOUT | 22 | 1 | −0,2411 | +0,0121 | +0,2633 | −0,2533 | gagal |

- **Entry strategi berada di bawah rata-rata acak**, bukan hanya di bawah p99. Pada sampel
  terbesar (SWEEP 1h, N=160), R kotor strategi −0,1165 sementara acak rata-rata +0,0157.
- Acak **juga rugi bersih** (net mean −0,14 sampai −0,24R) karena friksi ±0,14R per trade. Artinya
  entry acak pun tidak cukup untuk menutup biaya; strategi harus punya R kotor **> +0,14R** hanya
  untuk impas. Tidak ada setup yang mendekati itu.
- Pada 15m panel penuh, **OOS** net strategi (−0,2510) hampir sama dengan net acak (−0,2422):
  R kotor strategi lebih buruk, tetapi friksi mendominasi keduanya. Pada 1h, strategi lebih buruk
  secara kotor **dan** bersih (OOS net −0,2576 vs acak −0,1422).
- 15m dan 1h memberi tanda yang sama: pemicu SWEEP memilih momen dengan follow-through lebih
  buruk daripada entry acak.

### 4.5 Mengapa 15m tidak bisa dieksekusi

Lantai SL 5,8128% dibagi G2 maksimum 5 ATR berarti sebuah sinyal hanya bisa lolos bila
**ATR% ≥ 1,16256%**.

| pair | tf | median ATR% | porsi bar ATR% < 1,16256% |
|---|---|---:|---:|
| IOUSDT | 15m | 0,6413% | **95,5%** |
| IOUSDT | 1h | 1,4227% | 23,3% |

Di seluruh panel 15m 180 hari, G2 menolak **1.490 dari 1.584 sinyal SWEEP (94,1%)**. Di 1h,
G2 menolak 505 dari 901 (56,0%). (Jendela B 90 hari memberi angka serupa: 738 dari 797, 92,6%.)
Jadi:

- **15m:** lantai 6× dan ATR 15m tidak cocok. Hampir semua sinyal mati di G2; pair yang lolos
  gate A (IOUSDT) justru salah satu yang paling tidak cocok.
- **1h:** G2 lebih longgar, tetapi time-stop setup adalah 1,5–3 jam. Pada 1h itu berarti
  **3 candle** (minimum). Gate edge menilai pergerakan **7 jam**, sedangkan trade ditutup
  setelah 1,5–3 jam — horizon gate dan horizon trade tidak sama. Pindah ke 1h **tidak**
  memperpanjang horizon; yang berubah hanya sampling candle.

### 4.6 E4 — ketahanan parameter

Base produksi < 10 trade di kedua timeframe, jadi status E4 adalah **`tidak_cukup_sampel`**.
Variasi `max_sl_atr`, `min_net_rr_tp1`, `min_net_rr_final`, `min_reward_over_friction` ±20%,
serta `tp1Fraction` 0,5/0,6 dijalankan pada gates produksi; hasilnya tetap tidak bisa
menghasilkan sampel yang layak. E4 formal **tidak bisa diklaim**.

**Satu pengecualian yang harus disebut:** varian `min_net_rr_final ×0,8` menghasilkan **1 trade**
IOUSDT SWEEP_RECLAIM di jendela B (OOS untuk gate A) dengan avg R **+1,403** (R kotor +1,5608,
TP1 kena, exit trailing setelah 30 bar). Satu trade bukan sampel, dan varian ini adalah yang paling
longgar pada gate R:R final. Jangan mengutip +1,403R sebagai bukti edge; status E4 tetap
`tidak_cukup_sampel`.

Yang bisa dikatakan: tanda avg R **negatif** di tiga sampel diagnostik yang berbeda
(15m B −0,2495; 1h B −0,2561; 1h 180 hari −0,2391). Tidak ada satu pun sampel yang positif.
Itu bukan E4 formal, tetapi cukup untuk menolak hipotesis "mungkin positif kalau jendelanya lain".

## 5. Stabilitas gate edge

Gate edge dihitung dari MFE 7 jam pada tiap jendela 90 hari, ambang 2,0× friksi. Hasilnya
**tidak stabil**:

| Sumber gate | Jumlah pair layak | Pair |
|---|---:|---|
| `data/config/pair_gate.json` (tabel lama, 14 pair) | 14 | APT, ENA, FET, FIL, MUBARAK, NEAR, PLUME, PUMP, QNT, STRK, S, WLD, W, ZRO |
| MFE jendela A (2026-04-23 → 2026-07-22) | **1** | IOUSDT (rasio 2,0052) |
| MFE jendela B (2026-07-22 → 2026-10-20) | **4** | MUBARAK (2,7782), PUMP (2,5819), ZRO (2,3367), PLUME (2,1216) |

- Irisan A ∩ B **kosong**. IOUSDT — satu-satunya pair yang lolos di A — tidak lolos di B.
- Tabel lama memuat keempat pair B, tetapi **tidak memuat IOUSDT**.
- Di jendela A banyak pair berada tepat di bawah ambang: WLD 1,9494; PLUME 1,8820; TIA 1,8749;
  PHA 1,8320; PUMP 1,7213. Perubahan data kecil bisa membalik keputusan lolos/tidak.

Konsekuensinya: gate edge dari jendela A hanya menyisakan **satu pair** untuk jendela B. Jumlah
sampel E1 di jalur produksi karena itu tidak mungkin mencapai 100, terlepas dari kualitas entry.
Tabel produksi tidak boleh diperbarui dari satu jendela seperti ini; gate harus dihitung berkala
dan universe-nya harus walk-forward, bukan panel 41 pair yang dipilih dari snapshot akhir B.

## 6. Kesimpulan dan keputusan yang diminta

1. **Tidak ada bukti edge.** Di gates produksi, sampelnya nol; di diagnostik tanpa G0, R kotor
   negatif di kedua timeframe dan kalah dari entry acak. Fase 1 Kotlin **tetap diblokir**.
2. **Masalah utamanya entry, bukan exit atau friksi.** R kotor sudah negatif (−0,10 sampai
   −0,15R) sebelum biaya. Friksi ±0,14R hanya memperbesar kerugian. Menyetel exit, TP1, atau
   trailing tidak akan mengubah tanda selama entry tidak punya follow-through.
3. **SWEEP_RECLAIM adalah sumber kerugian utama** (44 dari 45 trade 1h B; 66 dari 67 trade 1h
   180 hari) dan sudah ditandai "jangan diimplementasi apa adanya" di spec. 25 dari 45 trade 1h
   keluar lewat `SETUP_INVALIDATION`, kebanyakan setelah 2–3 bar. Reclaim yang dipilih tidak
   bertahan.
4. **Gate edge tidak stabil.** Jendela A hanya menyisakan IOUSDT; jendela B menyisakan 4 pair
   yang sama sekali berbeda. Gate dari satu jendela tidak boleh dijadikan tabel produksi.
5. **Pindah timeframe bukan jawaban.** Time-stop spec dalam jam; 1h hanya berarti 3 candle,
   bukan horizon lebih panjang. Kalau ingin horizon lebih panjang, ubah `timeStopHours` dan
   exit-nya, lalu ukur ulang — jangan hanya mengganti candle.
6. **Sampel tidak akan cukup dengan data ini.** Panel penuh 180 hari hanya memberi 26 trade OOS
   pada 1h dan 5 pada 15m. E1 menuntut 100 per setup. Menambah data 2 tahun bisa menutup
   kekurangan sampel, tetapi tidak memperbaiki R kotor yang negatif.

**Pilihan untuk owner (jangan dikerjakan tanpa keputusan):**

- **(a) Hentikan lini Spec v3.** Tidak ada bukti entry; menyimpan kode harness sebagai alat
  riset sudah cukup.
- **(b) Rancang ulang entry SWEEP** (pemicu, level, invalidation) dan ukur R kotor terhadap
  acak sebelum menyentuh gate/exit. Ini jalur yang paling masuk akal bila program dilanjutkan.
- **(c) Uji horizon lebih panjang** dengan `timeStopHours` 8–24 jam dan exit yang sesuai,
  bukan sekadar pindah ke 1h.
- **(d) Perbaiki gate** menjadi walk-forward berkala (mis. MFE 90 hari bergulir) dan jangan
  memakai tabel hardcoded; tetapi gate bukan penyebab utama kerugian.

## 7. Reproduksi

```sh
python3 tools/test_spot_backtest.py

python3 tools/spot_evidence.py \
    --data data/tokocrypto_spot_180 \
    --work data/hasil/spot_evidence_v3 \
    --timeframes 15m,1h
```

Diagnostik panel penuh 180 hari (tanpa G0, bukan produksi):

```sh
python3 tools/spot_backtest.py --data data/tokocrypto_spot_180 \
    --timeframe 1h --setups all --no-pair-edge --tp1-policy spec \
    --start-ms 1776946140657 --end-ms 1792498140657 \
    --out data/hasil/spot_evidence_v3/full_180_nogate_1h

python3 tools/spot_backtest.py --data data/tokocrypto_spot_180 \
    --timeframe 15m --setups all --no-pair-edge --tp1-policy spec \
    --start-ms 1776946140657 --end-ms 1792498140657 \
    --out data/hasil/spot_evidence_v3/full_180_nogate_15m
```

Unduh ulang dataset:

```sh
python3 tools/fetch_tokocrypto_klines.py \
    --select file --file data/tokocrypto_spot_180/_pairs.txt \
    --quote USDT --intervals 15m,1h \
    --start-ms 1776946140657 --end-ms 1792498140657 \
    --out data/tokocrypto_spot_180 --resume --sleep 1.0 --write-readme
```

**Determinisme.** Backtest 1h jendela B tanpa G0 dijalankan dua kali. Berkas
`bt_B_nogate_1h_trades.csv` menghasilkan SHA-256 yang sama
(`04fe3efe5446bc2e907a4488eed932a1e88ed00cbd2905a214bba6e837a98af2`), dan ringkasan JSON identik
kecuali `generated_at_utc`; keduanya 45 trade. Tidak ada randomness di backtest.
