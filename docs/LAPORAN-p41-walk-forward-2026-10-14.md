# Laporan Eksekusi P4.1 — Walk-Forward Antar-Bulan (2026-10-14)

**Status:** ✅ Selesai — tooling diperbaiki (3 bug), hasil v4 dikoreksi, hasil 40 pair dijalankan,
**dan ditemukan temuan yang lebih besar: angka edge lama tidak sah** (lihat §5)
**Branch:** `patch`
**Berkas kunci:** `tools/split_validate.py` (mode `--walk-forward`), `tools/fetch_tokocrypto_klines.py`,
`data/hasil/u8_walk_forward_40pair.{json,md}`
**Laporan pendamping:** `docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`

---

## 1. Implementasi

`tools/split_validate.py` di-extend dengan mode `--walk-forward`:

| Parameter | Fungsi | Default |
|---|---|---|
| `--walk-forward` | Aktifkan mode jendela bergulir | off |
| `--window-bars` | Ukuran jendela dalam bar M1 | 40000 (±27 hari) |
| `--step-bars` | Langkah geser antar-jendela | 15000 (±10 hari) |

**Cara kerja:** untuk setiap jendela, data M1 dibagi train (70% awal jendela) / test (30% akhir
jendela). Replay dijalankan terpisah per jendela. Trade dengan entry sebelum split dibuang.
Pemanas 300 bar sebelum split hanya untuk indikator.

**Verdict per jendela:** LOLOS bila test avg R terbobot > 0 DAN rasio pair positif ≥ 60%.
**Verdict keseluruhan:** BERTAHAN bila SEMUA jendela LOLOS.

---

## 2. Tiga bug ditemukan dan diperbaiki (2026-10-14)

Hasil `u7_walk_forward_v4` yang sudah ter-commit **tidak sah** karena cacat di tool-nya.

### Bug 1 — jendela dikelompokkan dengan kunci yang salah

`_run_pair_walk_forward` mengisi **dua** kolom waktu:

- `window_start_bar` = indeks bar (`0`, `20000`, `40000`) — **sama untuk semua pair**;
- `window_start_t` = timestamp bar pertama (`win[0]["t"]`) — **berbeda beberapa detik antar-pair**,
  karena tiap CSV mulai pada detik yang berbeda saat pengambilan data.

Agregasi per jendela mengelompokkan baris memakai `window_start_t`. Akibatnya 8 pair pecah menjadi
**9 "jendela" palsu**, masing-masing hanya berisi **1–2 pair** (BTC sendirian, BNB sendirian,
ADA+DOGE bersama) — bukan 3 jendela berisi 8 pair. Verdict per jendela jadi dihitung dari satu pair.

**Bukti:** di `data/hasil/u7_walk_forward_v4.json`, ketiga nilai `window_start_t` yang berbeda
(`1786545960000`, `1786546080000`, `1786546200000`) berselisih **12 detik**, bukan 14 hari.

**Perbaikan:** kunci agregasi memakai `window_start_bar`.

### Bug 2 — penulis markdown crash (`StopIteration`)

`write_md_walk_forward` mencocokkan baris per-pair ke ringkasan jendela lewat
`ws["window_start_t"] == wr["window_start_t"]`. Karena `window_start_t` ringkasan berisi **indeks
bar** sementara baris per-pair berisi **timestamp**, pencocokan tidak pernah ketemu →
`StopIteration`, dan **berkas `.md` tidak pernah ditulis**. Kolom "Tanggal Mulai" juga ikut salah
(tercetak `1970-01-01`).

**Perbaikan:** `window_summaries` kini menyimpan `window_start_bar` = indeks bar dan
`window_start_t` = timestamp asli; pencocokan memakai `window_start_bar`.

**Kontrol negatif:** sebelum perbaikan, perintah yang sama berhenti dengan
`StopIteration` di `split_validate.py:442` dan tidak menghasilkan `.md`. Sesudah perbaikan,
`.json` dan `.md` ditulis lengkap.

### Bug 3 — pair lebih pendek dari satu jendela membuat proses crash

`_run_pair_walk_forward` mengembalikan **list kosong** bila pair lebih pendek daripada
`--window-bars` (mis. listing baru). Pemanggilnya langsung membaca `pair_results[0]` →
`IndexError`, dan **seluruh run gagal**.

**Perbaikan:** pemanggil memeriksa list kosong lebih dulu, melewati pair tersebut, dan
melaporkannya sebagai `SKIP (lebih pendek dari <N> bar)`.

**Bukti:** pada run 40 pair, HYPEUSDT (28.951 bar < 40.000 bar) terlewati dengan pesan SKIP —
sebelum perbaikan, run yang sama akan berhenti total.

---

## 3. Hasil v4 — dikoreksi (8 pair, `data/tokocrypto_v4`)

Perintah:

```bash
python3 tools/split_validate.py --data data/tokocrypto_v4 \
  --config data/config/scalp_config_exit_full.json --walk-forward \
  --window-bars 30000 --step-bars 20000 --out data/hasil/u7_walk_forward_v4
```

| Jendela | Tanggal Mulai | Train n | Train avgR | Test n | Test avgR | Pair+ | Verdict |
|---|---|---:|---:|---:|---:|---:|---|
| 1 | 2026-08-12 | 714 | −0,1455 | 214 | −0,1837 | 0/8 | TIDAK LOLOS |
| 2 | 2026-08-26 | 616 | −0,1682 | 210 | −0,1737 | 0/8 | TIDAK LOLOS |
| 3 | 2026-09-09 | 652 | −0,1577 | 247 | −0,1794 | 0/8 | TIDAK LOLOS |

**Agregat:** train n 1.982 avg R **−0,1566** · test n 671 avg R **−0,1790** · pair positif 0/24.
**Jendela lolos: 0/3 → verdict TIDAK BERTAHAN.**

> **Catatan penting:** kedelapan pair di `data/tokocrypto_v4` (ADA, BNB, BTC, DOGE, ETH, SOL, TRX,
> XRP) **semuanya INELIGIBLE** menurut `data/config/pair_gate.json` — rasio MFE/friksi 0,242×
> (TRX) sampai 1,638× (ADA), semuanya di bawah ambang 2,0. Jadi verdict ini dihitung **seluruhnya
> di atas pair yang gate repo ini sendiri tolak**, dan **tidak boleh** dipakai untuk menyimpulkan
> strategi gagal. Yang menjawab pertanyaan P4.1 adalah §4 di bawah.

---

## 4. Hasil 40 pair — `data/tokocrypto_live_usdt`

**Data diambil sendiri** (folder ini tidak ada di repo, ter-gitignore):

```bash
python3 tools/fetch_tokocrypto_klines.py \
  --pairs ZROUSDT,NEARUSDT,MUBARAKUSDT,...,USDCUSDT,UUSDT \
  --intervals 1m --pages 87 --out data/tokocrypto_live_usdt
# → 40 pair berhasil, ~87.000 bar M1 per pair (±60 hari)
#    kecuali HYPEUSDT (28.951 bar — listing lebih baru)
```

**Perintah walk-forward** (parameter default tool):

```bash
python3 tools/split_validate.py --data data/tokocrypto_live_usdt \
  --config data/config/scalp_config_exit_full.json --walk-forward \
  --out data/hasil/u8_walk_forward_40pair
# jendela 40000 bar (±27 hari), langkah 15000 bar (±10 hari) → 4 jendela
```

| Jendela | Tanggal Mulai | Train n | Train avgR | Test n | Test WR | Test avgR | Pair+ | Verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| 1 | 2026-08-15 | 4.740 | −0,1525 | 1.766 | 18,06% | −0,1749 | 0/39 | TIDAK LOLOS |
| 2 | 2026-08-25 | 4.336 | −0,1701 | 2.258 | 26,84% | −0,1409 | 0/39 | TIDAK LOLOS |
| 3 | 2026-09-04 | 4.802 | −0,1582 | 2.160 | 24,77% | −0,1673 | 0/39 | TIDAK LOLOS |
| 4 | 2026-09-15 | 5.433 | −0,1510 | 1.839 | 22,40% | −0,1667 | 0/39 | TIDAK LOLOS |

**Agregat:** train n 19.311 avg R **−0,1574** · test n 8.023 avg R **−0,1614** ·
pair positif **0/156** (semua jendela).
**Jendela lolos: 0/4 → verdict TIDAK BERTAHAN.**
HYPEUSDT dilewati otomatis (28.951 bar < 40.000 bar = satu jendela penuh).

**Jawaban atas pertanyaan P4.1:** tanda positif **TIDAK bertahan** — bahkan tidak muncul di satu
jendela pun. Namun lihat §5: angka ini **bukan** penilaian atas strategi semata, melainkan
konsekuensi akuntansi exit yang baru diperbaiki.

---

## 5. Temuan yang lebih besar: angka edge lama tidak sah

Saat menjalankan walk-forward 40 pair, muncul kejanggalan: **win rate 13–27%** padahal dokumen
lama mencatat ≈95%. Penelusuran menemukan akar yang jauh lebih penting daripada walk-forward itu
sendiri:

> `summarize()` di `scalp_replay.py` **mengeluarkan** trade yang tidak menyentuh SL/TP/early/trail
> dalam jendela *lookahead* (`UNRESOLVED`) dari seluruh statistik. Commit `703c3b6` menutup trade
> tersebut di harga close bar terakhir — sejak itu angkanya berubah drastis.

Kontrol dengan data, config, dan kode yang **sama** (40 pair, split 70/30):

| Akuntansi | Test n | Test avgR | Pair+ | Verdict |
|---|---:|---:|---:|---|
| Lama — `UNRESOLVED` dikecualikan | 817 | **+0,0577** | 26/29 | LOLOS |
| Baru — semua trade ditutup di akhir jendela | 4.403 | **−0,1733** | 0/40 | TIDAK LOLOS |

Akuntansi lama membuang **±81% sinyal**, dan yang dibuang bukan yang menguntungkan.
Mematikan time-stop maupun lantai profit S2 **tidak** mengembalikan hasil positif
(lihat tabel 5 baris di `docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md` §3).

**Konsekuensi:** angka `u4_split_toko_usdt` ("LOLOS", avg R +0,0216) yang selama ini menjadi
dasar narasi "tandanya benar, besarnya belum cukup" **tidak sah**. Rata-rata hasil trade
(≈ −0,17 R) persis setara dengan `−friksi / SL` = −0,9688% / 5,81% = −0,167 R — yaitu **tidak ada
edge kotor yang tersisa setelah biaya**.

Rincian lengkap: `docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`.

---

## 6. Berikutnya

- Bandingkan sebaran hasil antar-jendela dengan hasil single-split `u4_split_toko_usdt`
  (catatan: angka `u4` sendiri sudah tidak sah — lihat §5).
- Kalibrasi ambang S9 (`max_change_24h_pct`) memakai sebaran walk-forward — sampai sekarang
  default-nya tetap **0 (nonaktif)**.
- **Pertanyaan baru yang lebih mendesak daripada P4.2/P4.4:** apakah ada varian entry yang
  menghasilkan avg R > +0,167 R kotor (yaitu melewati friksi) pada horizon 120 bar M1?
  Tanpa itu, tidak ada gunanya menyetel exit lebih jauh.
