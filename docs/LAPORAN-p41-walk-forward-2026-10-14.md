# Laporan Eksekusi P4.1 — Walk-Forward Antar-Bulan (2026-10-14)

**Status:** 🟡 Dalam proses — job background berjalan  
**Data:** `data/tokocrypto_v4` (8 pair, ~87.000 bar M1 ≈ 60 hari)  
**Config:** `data/config/scalp_config_exit_full.json` (friksi round-trip 0,9688%)

---

## 1. Implementasi

`tools/split_validate.py` di-extend dengan mode `--walk-forward`:

| Parameter | Fungsi | Default |
|---|---|---|
| `--walk-forward` | Aktifkan mode jendela bergulir | off |
| `--window-bars` | Ukuran jendela dalam bar M1 | 40000 (±27 hari) |
| `--step-bars` | Langkah geser antar-jendela | 15000 (±10 hari) |

**Cara kerja:** untuk setiap jendela, data M1 dibagi train (70% awal jendela) / test (30% akhir jendela). Replay dijalankan terpisah per jendela. Trade dengan entry sebelum split dibuang. Pemanas 300 bar sebelum split hanya untuk indikator.

**Verdict per jendela:** LOLOS bila test avg R terbobot > 0 DAN rasio pair positif ≥ 60%.  
**Verdict keseluruhan:** BERTAHAN bila SEMUA jendela LOLOS.

---

## 2. Hasil

> Angka final diisi setelah job background selesai.

| Jendela | Tanggal Mulai | Train n | Train avgR | Test n | Test avgR | Pair+ | Verdict |
|---|---|---:|---:|---:|---:|---:|---|
| _pending_ | | | | | | | |

---

## 3. Kesimpulan

> Diisi setelah data lengkap.

---

## 4. Berikutnya

- Jalankan walk-forward pada data 40 pair (`data/tokocrypto_live_usdt`) setelah data diambil
- Bandingkan sebaran hasil antar-jendela dengan hasil single-split `u4_split_toko_usdt`
