# Laporan Kerja — Titik 1 & 2 + Maksimalisasi Tokocrypto

**Tanggal:** 2026-10-12 · **Repo:** `Agus27712/analisa-pasar` (branch `main`)
**Arah dari owner:** engine scalping disiapkan untuk trading riil, tetapi **diuji dulu di data riil**;
target semua pair Tokocrypto & Indodax profit; jalur Indodax pakai **TAPI v2**;
**Tokocrypto dimaksimalkan lebih dulu** sebelum menyentuh Indodax.

---

## A. Titik 1 & 2 — perbaikan & sinkronisasi dokumen

| Titik | Berkas | Yang diubah |
|---|---|---|
| **1** | `workplan2.md` §8 (DoD) | Blok P2 dipisah menjadi **"Fitur"** + **"Keselamatan (wajib)"** (3 butir: kepemilikan exit tunggal, penjagaan per-tick, tanpa auto-execute riil); label P2 → **`[SELESAI ✅]`**; checkbox P3 diberi catatan sengaja belum dikerjakan; **DoD P3 ditambahkan** |
| **2** | `SCALP_EXIT.md` | Tabel pemetaan ditulis ulang jadi dua kolom (engine paper P2 vs `positionStore` lama); **"Port ke app" → `[SELESAI ✅ untuk jalur paper]`**; `max_hold_bars_m1` disamakan ke **90** (dicatat di 3 tempat); bagian gerbang dipecah **paper (tanpa gate profit)** vs **uang riil (wajib lolos)** |

Sekaligus ditemukan & diperbaiki **inkonsistensi dokumen**: default `min_friction_multiple` tertulis **3,0** di `workplan2.md` §4/§9.3 dan `FEE_TRUTH.md` §4, padahal nilai final adalah **6,0**. Semua sudah disinkronkan, termasuk range slider di kode yang semula `0..6` (default mentok di maksimum) → diubah menjadi `0..10`.

---

## B. Temuan akar masalah: lantai risiko berbasis friksi

Engine diuji pada **40 pair Tokocrypto USDT, 30 hari data M1 riil** (`data/tokocrypto_live_usdt/`).

**Diagnosis (bukan tebakan):** dengan `min_risk_pct` 0,5% dan friksi Tokocrypto USDT **0,9688%**,
satu kali kena stop loss kehilangan 0,5% + 0,97% ≈ **2,9R**. Unit risiko jauh lebih kecil
daripada biaya → matematikanya mustahil. Untuk impas pada target R:R 1,25 butuh win-rate 44%,
engine hanya mencapai 18–35%.

**Perbaikan yang diterapkan:**

| Perubahan | Nilai lama → baru |
|---|---|
| `min_friction_multiple` (lantai SL = kelipatan friksi) | *(baru)* → **6,0** (plateau optimum 5–10×) |
| `target_net_rr` | 1,25 → **2,0** |
| Slider UI "Lantai Risiko vs Friksi" | *(baru)*, range 0–10 |

**Bukti sebelum vs sesudah (dataset & config identik):**

| Metrik | Sebelum | Sesudah |
|---|---:|---:|
| Pair avg R > 0 | **0 / 28** | **28 / 32** |
| avg R terbobot | **−1,2177** | **+0,0325** |
| Total trade resolved | 3.934 | 2.550 |

---

## C. Gate out-of-sample (P4.1) — **LOLOS**, tetapi tipis

Tool baru `tools/split_validate.py`: data dibagi **menurut waktu** (train 70% / test 30%),
engine dijalankan di kedua potongan; potongan test memakai 300 bar pemanas dan membuang trade
yang entry-nya sebelum titik split (anti-kebocoran).

| Segmen | Trade | avg R terbobot | WR | Pair positif |
|---|---:|---:|---:|---:|
| Train 70% | 1.835 | +0,0350 | 95,4% | 28 / 32 |
| **Test 30%** | **669** | **+0,0216** | **94,0%** | **23 / 30** |

- 13 pair layak (test n ≥ 20) → rasio positif **84,6%**, **5 pair berbalik tanda**.
- **Verdict: LOLOS.**
- Namun dengan **gate ketat** `SCALP_EXIT.md` (avg R > 0 ∧ PF ≥ 1,2 ∧ n ≥ 30):
  **21/40 in-sample → hanya 5/40 di test** (APT, NEAR, SU, W, ZRO).

---

## D. Profil exit: **skew negatif** (temuan risiko utama)

Distribusi 2.550 trade menjelaskan kenapa marginnya tipis:

| Exit | Trade | Porsi | WR | avg R |
|---|---:|---:|---:|---:|
| `TRAIL_SL` (SL sudah naik ke BE/peak) | 2.263 | 88,7% | 100% | **+0,090** |
| `EARLY_PROFIT` | 163 | 6,4% | 100% | +0,144 |
| `SL` (stop keras) | 124 | 4,9% | 0% | **−1,156** |

Menang **kecil**, kalah **besar** (kalah 13× lebih besar daripada rata-rata menang).
Titik impas butuh win-rate ≈ **92,8%**; engine mencapai **95,1%** → margin hanya **~2,3 poin
persentase**. Satu pergeseran kecil pada laju kena stop (latensi, fill parsial, rezim
volatilitas berbeda) sudah cukup membalik tanda. Drawdown per pair dalam: **11,9R** (QNT),
**10,5R** (MUBARAK); STRK butuh ~2.000 trade untuk pulih dari DD 4,3R pada avg R +0,002.

**Penyebab struktural:** friksi 0,97% memaksa SL ≥ 5,8%, sementara median pergerakan 90 menit
hanya 1,5–3%. Penyebut R selalu besar, pergerakan yang bisa ditangkap kecil → avg R plafon ≈ 0,03.
Ini juga menjelaskan kenapa horizon lebih panjang **tidak** menolong (peluang menyentuh SL 5,8% naik).

---

## E. Gerbang edge pair (P4.3) — pekerjaan baru

Karena sebagian pair **secara matematis mustahil** profit, saya tambahkan gerbang berbasis data.
Aturan: pair layak ⟺ `median MFE 7 jam ≥ 2,0 × friksi round-trip`.

Dua garis berbeda (penting, jangan dicampur):

| Rasio (MFE ÷ friksi) | Arti | Contoh |
|---|---|---|
| **< 1,0** | Pergerakan median < biaya → **mustahil** profit | BTC 0,52×, XAUT 0,20×, USDC 0,01× |
| **1,0 – 2,0** | Mungkin secara teori, margin terlalu tipis → ditolak sebagai *buffer* | ADA 1,64×, AVAX 1,61× |
| **≥ 2,0** | Layak (masih perlu lolos uji out-of-sample) | ZRO 3,33×, NEAR 2,99×, APT 2,21× |

**Hasil: 14 dari 40 pair USDT layak** — ZRO, NEAR, MUBARAK, PUMP, W, STRK, ENA, QNT, PLUME,
APT, SU, FET, WLD, FIL.

Artefak: `engine/scalping/PairEdgeGate.kt` (logika + tabel data), `PairEdgeGateCard` (kartu UI
di layar sensitifitas scalping), `tools/pair_gate.py` (regenerasi tabel → `data/config/pair_gate.json`).

---

## F. Jalur IDR & Indodax

- **IDR Tokocrypto: belum konklusif.** 14 pair IDR hanya menghasilkan 96 trade dalam 30 hari;
  gate out-of-sample-nya cuma punya **6 trade di test**. Terlalu tipis untuk disimpulkan —
  jangan dipakai sebagai bukti. Pair IDR Tokocrypto terlalu tidak likuid untuk filter engine saat ini.
- **Indodax: belum bisa diuji.** Belum ada data historis sama sekali di `data/`. Klien TAPI v2
  (`IndodaxTradeApiV2.kt`) sudah terpasang, tetapi sesuai arahan Anda, Tokocrypto dimaksimalkan dulu.

---

## G. Verifikasi

| Uji | Hasil |
|---|---|
| Unit test | **168 test · 0 gagal · 1 dilewati** (`BUILD SUCCESSFUL`) |
| Test baru | +15 di `PairEdgeGateTest.kt` |
| Build APK debug | **BUILD SUCCESSFUL** — `app-debug.apk` 26,7 MB |

> Catatan lingkungan: `assembleDebug` semula gagal di `validateSigningDebug` karena
> `debug.keystore` tidak ada di sandbox (file ini memang gitignored). Setelah keystore debug
> dibuat lokal, build selesai tanpa error. **Tidak ada perubahan kode yang terlibat.**

---

## H. Rekomendasi (berpikir seperti scalper, bukan pemasar)

1. **Jangan aktifkan auto-execute uang riil.** Edge-nya nyata (tanda sudah benar), tetapi
   profilnya skew negatif dengan margin ~2,3 pp — terlalu rapuh untuk modal riil.
2. **Batasi trading ke 14 pair yang lolos gerbang edge**, dan prioritaskan 5 pair yang lolos
   gate ketat out-of-sample (APT, NEAR, SU, W, ZRO).
3. **Turunkan friksi** sebagai pengungkit berikutnya: Tokocrypto USDT maker = taker (tidak ada
   diskon), tetapi jalur IDR maker 0,61% vs 0,97% USDT. Perlu verifikasi fill limit yang realistis.
4. **Lanjutkan verifikasi walk-forward lintas bulan** sebelum menaikkan keyakinan; satu jendela
   30 hari belum cukup.
5. Baru setelah itu **sentuh Indodax**: ambil data historis IDR, jalankan harness replay dengan
   fee 0,79% round-trip, lalu uji jalur TAPI v2 end-to-end.

> **Ringkas satu kalimat:** lantai friksi memperbaiki **tanda** hasil (0/28 → 28/32 pair positif,
> lolos gate train/test), tetapi **besarnya** edge masih terlalu tipis dan skew-nya negatif —
> jadi ini kemajuan nyata, bukan lampu hijau untuk uang riil.
