# Laporan: harness Spec v3 dan gerbang bukti E1–E4 (2026-10-21)

**Status:** riset offline. **Tidak ada perubahan kode aplikasi** (`app/`). Fase 1 Kotlin tetap
diblokir sampai E1–E3 lolos atau owner memutuskan lain secara tertulis.

## 1. Ringkasan

- Harness Spec v3 selesai: TP1 `tp1Floor`, G3 dicabut, time-stop dari jam, risiko net
  `FeeSchedule`, R kotor terpisah, benchmark entry acak, filter portofolio kronologis.
- Dataset 180 hari selesai: 41 pair, 82 CSV, **868.054 bar**, **0 celah**, **0 duplikat**.
- Uji harness: **30 uji lulus**, dengan kontrol negatif untuk time-stop, TP1 floor, sizing net,
  R kotor, filter portofolio, dan atribusi varian E4 per setup.
- **Gerbang bukti E1–E4 tidak lolos.** Gate edge dari jendela A hanya menyisakan **1 pair
  (IOUSDT)**; pada gates produksi, 15m dan 1h sama-sama menghasilkan **0 trade out-of-sample**.
  Satu-satunya trade produksi ada di jendela A (1 trade, avg R −0,4849).
- **Diagnostik tanpa G0 tetap negatif.** 15m jendela B: 7 trade, avg R **−0,2495**, R kotor
  **−0,1061**. 1h jendela B: 45 trade, avg R **−0,2561**, R kotor **−0,1146**. Panel penuh
  180 hari: 15m 12 trade (−0,2888), 1h 67 trade (−0,2391).
- **Entry kalah dari acak.** SWEEP 1h panel penuh (N=160): R kotor strategi −0,1165 vs acak
  rata-rata +0,0157 dan p99 +0,1122. E2 gagal di semua sampel yang cukup.
- **Fase 1 Kotlin tetap diblokir.** Masalah utama ada di entry, bukan di exit atau friksi.

## 2. Yang dibangun

### 2.1 Harness Python setara Spec v3

| Berkas | Isi |
|---|---|
| `tools/spot_lib.py` | Friksi aditif `F`, lantai SL 6×, `resolve_stop` (SIGNAL / FRICTION_FLOOR / NONE), `tp1Floor`, risiko per unit net `FeeSchedule`, konversi jam → candle |
| `tools/spot_backtest.py` | Backtest 5 setup, gap G0–G7, TP1 struktural/floor, qty dari risiko net, R kotor + friksi per R, jendela `--start-ms/--end-ms`, pemindaian kandidat, filter portofolio kronologis |
| `tools/spot_benchmark.py` | Benchmark entry acak §11.1: K=200 run, SL dari distribusi `slEff/ATR` sinyal, TP1 floor, exit §7 tanpa invalidasi, p99 lintas pair |
| `tools/spot_evidence.py` | Orkestrasi E1–E4: MFE jendela A → gate A → backtest B → benchmark OOS → diagnostik tanpa G0 → jendela A → 10 varian parameter |

Perubahan utama dibanding harness v2:

1. **TP1 tidak lagi `entry + 1,0R`.** `tp1FloorPct = F + minNetRrTp1 × (riskPct + F)`; kandidat
   struktural yang berada di bawah lantai ditolak atau diganti kandidat berikutnya, tidak digeser.
2. **G3 dicabut** (default 0). G2 tetap: `slAtr` 0,5–5,0.
3. **Time-stop dari jam**, `max(3, ceil(jam × 60 / menit-timeframe))`; cooldown dan pause juga
   dikonversi per timeframe.
4. **R denominator net**: `perUnitRisk = costBasisNet(1) − netSellValue(1, slEff)`, bukan
   `(entry − sl) + entry × F/100`.
5. **R kotor dan friksi per R dilaporkan terpisah**, sehingga hasil negatif tidak bisa
   disembunyikan di biaya.
6. **Benchmark entry acak wajib** sebelum klaim entry punya edge.
7. **Filter `maxConcurrentPositions = 2`** diterapkan sebagai filter kronologis setelah simulasi
   per pair, bukan simulasi portofolio penuh.

### 2.2 Dataset 180 hari

- `data/tokocrypto_spot_180`, 41 pair USDT, 15m + 1h, 82 CSV, **868.054 bar**, **44 MB**.
- Rentang: 2026-04-23 12:09 UTC → 2026-10-20 12:09 UTC.
- **0 celah, 0 duplikat** di dalam rentang tiap pair.
- Dua listing baru mulai lebih lambat: NVDAUSDT (2026-06-11) dan SOXSBUSDT (2026-07-29).
- Sumber: Tokocrypto `www.tokocrypto.site/api/v3`; Binance tidak dipakai.
- Panel 41 pair dibekukan dari dataset 90 hari, bukan dipilih ulang dari jendela B.

### 2.3 Uji mekanika dan kontrol negatif

`python3 tools/test_spot_backtest.py` → **30 uji lulus**. Cakupan: SL-before-TP pada candle yang
sama, TP2 tidak dievaluasi pada candle TP1, stop tidak pernah turun, time-stop, breakeven
`FeeSchedule`, TP1 floor vs literal, TP1 struktural ditolak, lantai SL, risiko net, qty, R kotor,
filter portofolio, batas jendela, determinisme seed benchmark, atribusi varian E4 per setup, dan
mirror fee `data/config/scalp_config.json`.

Kontrol negatif dilakukan untuk enam mekanika inti: time-stop minimum, TP1 floor, sizing risiko
net, R kotor, filter portofolio, dan atribusi varian E4 per setup. Pada tiap kasus, perbaikannya
dimatikan sementara → uji yang menjaga mekanika itu **gagal** → perbaikan dikembalikan → suite
hijau lagi. Uji yang selalu hijau tidak dianggap bukti.

**Determinisme.** Backtest 1h jendela B tanpa G0 dijalankan dua kali; `..._trades.csv` menghasilkan
SHA-256 identik (`04fe3efe5446...`) dan ringkasan JSON identik kecuali `generated_at_utc`
(45 trade keduanya). Backtest tidak memakai randomness; hanya benchmark acak yang memakai seed
tetap.

## 3. Hasil

Rincian angka, tabel per setup, dan batas tiap pengukuran ada di
`docs/exp/exp_spot_evidence_v3.md`. Bagian ini merangkum yang paling penting.

### 3.1 E1–E2 pada gates produksi: tidak ada trade OOS

| setup | 15m n OOS | 1h n OOS | E1 | E2 |
|---|---:|---:|---|---|
| PULLBACK_VWAP | 0 | 0 | gagal | gagal |
| BREAKOUT_RETEST | 0 | 0 | gagal | gagal |
| SWEEP_RECLAIM | 0 | 0 | gagal | gagal |
| RANGE_BOUNCE | 0 | 0 | gagal | gagal |
| SQUEEZE_BREAKOUT | 0 | 0 | gagal | gagal |

- **15m:** 1.217 sinyal, **0 kandidat** lolos G0–G2. G0 menolak 1.183; G2 menolak 34 sisanya.
- **1h jendela B:** 7 kandidat SWEEP + 5 BREAKOUT lolos G0–G2, tetapi semuanya ditolak G4/G5
  (TP1 struktural di bawah lantai atau R:R final kurang). **0 trade.**
- **1h jendela A:** 1 trade SWEEP, avg R −0,4849, R kotor −0,3417, invalidasi setelah 3 bar.
  Ini in-sample, bukan bukti E1.
- Benchmark pada gates produksi tidak bisa membandingkan apa pun: 15m tanpa kandidat; pada
  benchmark OOS, 1h hanya N=1 untuk SWEEP dan BREAKOUT dengan 0 trade strategi.

### 3.2 Diagnostik tanpa G0: negatif di kedua timeframe

| sampel | tf | trade | avg R | avg R kotor | friksi R | WR | PF |
|---|---|---:|---:|---:|---:|---:|---:|
| jendela B | 15m | 7 | −0,2495 | −0,1061 | 0,1434 | 14,3% | 0,2104 |
| jendela B | 1h | 45 | −0,2561 | −0,1146 | 0,1415 | 15,6% | 0,1839 |
| panel penuh 180 hari | 15m | 12 | −0,2888 | −0,1496 | 0,1391 | 8,3% | 0,1184 |
| panel penuh 180 hari | 1h | 67 | −0,2391 | −0,0969 | 0,1422 | 20,9% | 0,2123 |

- 15m jendela B: 7 trade, semuanya SWEEP. **0 TP1, 0 SL**; 5 keluar lewat `SETUP_INVALIDATION`,
  2 lewat `MOMENTUM_FAIL`. Median MFE hanya 0,129R.
- 1h jendela B: 44 SWEEP + 1 BREAKOUT. 24 dari 44 SWEEP keluar lewat invalidasi, kebanyakan
  setelah 2–3 bar. OOS 16 trade, avg R −0,3043.
- Panel penuh 180 hari: 66 dari 67 trade 1h adalah SWEEP; 11 dari 12 trade 15m adalah SWEEP.
  **Tidak ada satu pun sampel dengan avg R positif.**

### 3.3 Benchmark entry acak: entry strategi kalah dari acak

Semua baris di bawah adalah **diagnostik tanpa G0**; pada gates produksi tidak ada trade yang
bisa dibandingkan (lihat §3.1).

| sampel | tf | setup | N | trade | R kotor strategi | acak rata-rata | acak p99 | selisih |
|---|---|---|---:|---:|---:|---:|---:|---:|
| B 90 hari | 15m | SWEEP | 33 | 4 | −0,0866 | +0,0167 | +0,2560 | −0,1032 |
| B 90 hari | 1h | SWEEP | 78 | 15 | −0,1588 | +0,0116 | +0,1606 | −0,1703 |
| 180 hari | 15m | SWEEP | 47 | 5 | −0,1163 | +0,0084 | +0,1721 | −0,1247 |
| 180 hari | 1h | SWEEP | 160 | 25 | −0,1165 | +0,0157 | +0,1122 | −0,1322 |
| 180 hari | 1h | BREAKOUT | 22 | 1 | −0,2411 | +0,0121 | +0,2633 | −0,2533 |

Entry strategi berada **di bawah rata-rata acak**, bukan hanya di bawah p99. Acak juga rugi
bersih setelah friksi (net mean −0,14 sampai −0,24R), jadi strategi butuh R kotor **> +0,14R**
hanya untuk impas. Tidak ada setup yang mendekati angka itu.

### 3.4 Mengapa 15m tidak bisa dieksekusi

Lantai SL 5,8128% dibagi G2 maksimum 5 ATR berarti sinyal hanya lolos bila **ATR% ≥ 1,16256%**.

- IOUSDT 15m: median ATR% **0,6413%**; **95,5%** bar berada di bawah ambang.
- IOUSDT 1h: median ATR% **1,4227%**; 23,3% bar di bawah ambang.
- Panel 15m 180 hari: G2 menolak **1.490 dari 1.584 sinyal SWEEP (94,1%)**. Panel 1h:
  505 dari 901 (56,0%). Jendela B 90 hari memberi angka serupa (738/797, 92,6%).

Pair yang lolos gate A (IOUSDT) justru salah satu yang paling tidak cocok untuk 15m. Di 1h,
time-stop setup 1,5–3 jam berarti hanya **3 candle** (minimum), jadi pindah ke 1h tidak
memperpanjang horizon — yang berubah hanya sampling candle.

### 3.5 Stabilitas gate edge dan E3/E4

- Gate A (MFE jendela A): **1 pair**, IOUSDT 2,0052.
- Gate B (MFE jendela B): **4 pair**, MUBARAK 2,7782, PUMP 2,5819, ZRO 2,3367, PLUME 2,1216.
- **A ∩ B kosong.** Tabel lama 14 pair memuat keempat pair B, tetapi tidak memuat IOUSDT.
- E3 **sebagian**: gate dari jendela A, tetapi universe 41 pair dipilih dari snapshot 24 jam
  pada akhir jendela B.
- E4 **tidak cukup sampel**: base produksi 0 trade. Tanda negatif konsisten di tiga sampel
  diagnostik, tetapi E4 formal tidak bisa diklaim.
- Satu varian longgar (`min_net_rr_final ×0,8`) menghasilkan **1 trade** IOUSDT SWEEP
  **+1,403R** (R kotor +1,5608, TP1 kena, exit trailing setelah 30 bar). Satu trade bukan sampel;
  status E4 tetap `tidak_cukup_sampel`. Jangan mengutip angka ini sebagai bukti edge.

## 4. Batas pengukuran

- **Harness Python, bukan mesin Kotlin.** Belum ada uji paritas app ↔ harness untuk batch ini.
- **Fill memakai level mentah**; slippage masuk lewat `FeeSchedule`, bukan dengan mengalikan harga
  fill dua kali. Benchmark memakai konvensi yang sama.
- **G8 spread dan kedalaman order book tidak diuji** — data candle tidak menyediakannya. Gerbang
  G8 dilewati, bukan dianggap lolos.
- **Tick size dan lot step tidak dibulatkan**; notional minimum tidak ditegakkan.
- **Risk manager berjalan per pair.** Batas rugi harian dan pause 3 loss belum lintas pair, jadi
  jumlah trade bisa lebih banyak daripada produksi. Arah bias: **optimistis**.
- **Filter posisi bersamaan** tidak menggantikan trade yang ditolak; arah bias terhadap jumlah
  trade **konservatif**, tetapi komposisi pemenang/kalah tidak dijamin netral.
- **Universe belum walk-forward**: 41 pair dipilih dari snapshot 24 jam pada akhir jendela B.
  E3 baru terpenuhi untuk gate edge, belum untuk universe.
- **Modal tetap USD 10.000** (risiko 0,5% dari ekuitas awal, tanpa compounding).
- Hasil ini **tidak boleh** dikutip sebagai hasil aplikasi atau janji profit.

## 5. Keputusan yang diminta

Fase 1 Kotlin tetap **diblokir**. Pilihan yang tersedia untuk owner:

- **(a) Hentikan lini Spec v3.** Tidak ada bukti entry. Harness disimpan sebagai alat riset;
  tidak ada kode app yang ditulis.
- **(b) Rancang ulang entry SWEEP** (pemicu, level, aturan invalidasi) dan ukur ulang R kotor
  terhadap acak **sebelum** menyentuh gate/exit. Ini jalur yang paling masuk akal bila program
  dilanjutkan: SWEEP adalah 44 dari 45 trade 1h dan konsisten rugi.
- **(c) Uji horizon lebih panjang** dengan `timeStopHours` 8–24 jam dan exit yang sesuai, bukan
  sekadar pindah dari 15m ke 1h. Time-stop saat ini 1,5–3 jam dan pada 1h menjadi 3 candle.
- **(d) Perbaiki gate menjadi walk-forward berkala** (mis. MFE 90 hari bergulir) dan jangan
  memakai tabel hardcoded. Ini perlu, tetapi **bukan** penyebab utama kerugian.

**Rekomendasi:** (b) lebih dulu, dengan batas waktu dan kriteria berhenti yang jelas. Jangan
menulis engine Kotlin sebelum ada satu setup dengan R kotor positif dan mengalahkan acak pada
sampel yang cukup. Menambah data 2 tahun boleh dilakukan **setelah** entry menunjukkan R kotor
positif, bukan sebagai cara mencari edge.

## 6. Berkas dan reproduksi

**Berkas utama**

- `tools/spot_lib.py`, `tools/spot_backtest.py`, `tools/spot_benchmark.py`, `tools/spot_evidence.py`
- `tools/test_spot_backtest.py`
- `data/tokocrypto_spot_180/` (dataset + `_pairs.txt` + `_manifest.json`)
- `data/hasil/spot_evidence_v3/` (gate A, MFE A, backtest B, benchmark, varian,
  diagnostik panel penuh 180 hari, `evidence.json`)
- `docs/exp/exp_spot_evidence_v3.md` (laporan eksperimen)
- `docs/exp/scalping_spot_strategy_spec.md` (Spec v3, acuan)

**Perintah**

```sh
python3 tools/test_spot_backtest.py

python3 tools/spot_evidence.py \
    --data data/tokocrypto_spot_180 \
    --work data/hasil/spot_evidence_v3 \
    --timeframes 15m,1h
```

`--assemble-only` menyusun ulang `evidence.json` dari artefak yang sudah ada tanpa menjalankan
ulang backtest/MFE/benchmark. Mode ini dipakai setelah perbaikan atribusi varian E4 per setup
(tabel sebelumnya memakai ringkasan seluruh setup).

**Unduh ulang dataset**

```sh
python3 tools/fetch_tokocrypto_klines.py \
    --select file --file data/tokocrypto_spot_180/_pairs.txt \
    --quote USDT --intervals 15m,1h \
    --start-ms 1776946140657 --end-ms 1792498140657 \
    --out data/tokocrypto_spot_180 --resume --sleep 1.0 --write-readme
```
