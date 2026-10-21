# AGENTS.md — analisa-pasar

Aplikasi Android Kotlin 100% (Jetpack Compose M3) untuk analisis & **scalp mode** kripto di **Tokocrypto** dan **Indodax**. Modul tunggal `:app`, package `agu.analys`, MVVM + Coordinator (`TradingViewModel` + MarketData/Position/Simulation/RealTradeCoordinator).

**Versi aplikasi saat ini:** **3.9.2** (`versionCode 91`) — `app/build.gradle.kts`. Rilis 3.9.2
(2026-10-18) berisi batch 6j–6n: seluruh temuan audit koneksi Tokocrypto/Indodax **K1–K15**
(daftar pair resmi, WebSocket Indodax hidup, reconnect berjitter, status live per bursa,
lingkup ticker watchlist, gate watchlist & dashboard). Tag rilis: `v3.9.2`
dibuat otomatis oleh `.github/workflows/release.yml` saat `main` menerima push.

---

## ▶ MULAI DARI SINI (agen lanjutan — baca sebelum menyentuh kode)

### ⚠️ Langkah 0 — pastikan branch yang Anda baca benar

**Kerjaan aktif ada di `patch`.** Snapshot **2026-10-21** (setelah rebase ke rilis **3.9.2**):
`origin/main` = `578d0de` (merge rilis 3.9.2, batch 6j–6n). Titik jangkar konten `patch` =
`40257e4` (13 commit konten); commit dokumentasi 2026-10-21 duduk di atasnya. Jadi `patch` =
**main + 13 commit konten + commit dokumentasi** (riset Spec v2, dataset 90 hari, batch 6o/6p).
**`patch` memang di depan `main`, jadi `git diff HEAD origin/main` TIDAK kosong** — itu isi
13 commit konten + dokumen tadi, bukan tanda `main` ketinggalan.
`main` adalah **ancestor** `patch`. Jangan membaca `main` untuk melihat pekerjaan terbaru;
`main` baru berisi rilis 3.9.2.
**Jangan pernah menyimpulkan keadaan repo dari `main` tanpa mengecek angka ini dulu:**

```sh
git clone https://github.com/Agus27712/analisa-pasar.git
cd analisa-pasar && git checkout patch        # WAJIB — kerjaan aktif ada di sini
git rev-list --count origin/main..HEAD        # 13 commit konten + commit dokumentasi
git rev-list --count HEAD..origin/main        # 0 = main tidak punya commit yang belum ada di patch
git log --oneline origin/main..HEAD           # 13 commit konten + commit dokumentasi
git ls-remote --heads origin                  # main, patch, backup/patch-v3-2026-10-21
git diff --name-status HEAD origin/main       # TIDAK kosong = isi 13 commit konten + dokumen
```

Repo ini **public** — clone tidak butuh token.

⚠️ **Pernah terjadi, jangan terulang:** `main` pernah tertinggal **11 commit** dari `patch`.
Siapa pun yang membaca `main` saat itu melihat fitur horizon **masih ada** padahal sudah
di-revert di `patch` (`e525c64`), dan P0–P2 (integritas jurnal) serta S1–S9 (strategi
entry/exit) belum ada di sana — semua kesimpulan yang ditarik dari `main` jadi salah.
Sekarang arahnya sebaliknya: `main` belum memuat batch 6o/6p dan riset Spec v2 sampai di-merge.
**Ukur ulang, jangan mengandalkan ingatan.**

**⛔ RISET V3 DIHENTIKAN (2026-10-21).** Spec v3 dan harness v3 pernah dibuat, diukur, lalu
**dihentikan owner** karena hasilnya negatif: gates produksi **0 trade out-of-sample**,
diagnostik tanpa G0 tetap negatif, dan entry kalah dari acak. Commit `ada71dc` (Spec v3) dan
`796a9d1` (harness v3 + dataset 180 hari) sudah **dibuang dari `patch`**; seluruh artefaknya
hanya ada di branch backup **`backup/patch-v3-2026-10-21`** (`796a9d1`, remote).
**Jangan membangun ulang v3 dari dokumen v2.** Acuan aktif tetap **Spec v2**
(`docs/exp/scalping_spot_strategy_spec.md`); kalau riset dilanjutkan, mulai dari branch backup
atau branch baru dengan keputusan owner tertulis.

**Urutan baca 5 menit yang benar:**

1. **Berkas ini** sampai habis — terutama bagian "Yang tidak boleh dilakukan agen".
2. **`docs/PANDUAN-LANJUT-AGEN.md`** — status terkini, apa yang sudah selesai, apa yang berikutnya,
   dan perangkap yang sudah pernah ditemukan. **Ini titik masuk utama.**
3. **`docs/bugs/`** — 🔴 **sumber kebenaran status temuan audit**, dan **sudah diverifikasi ke kode
   + direview owner pada 2026-10-16** (`patch` @ `547048d`). **Agen tidak perlu cek kode untuk
   memastikan status temuan** — cukup baca tabel status di tiap berkas. **Folder ini KOSONG
   per 2026-10-18** — seluruh berkas temuan sudah keluar karena semua temuannya tertutup:
   `temuan_bugs_paper_trade.md` **dipindahkan** ke
   `docs/laporan_temuan_bugs_paper_trade.md` (2026-10-18, batch 6o — P1–P9 tuntas; lihat berkas
   itu untuk aturan turunannya), `temuan_koneksi_tokocrypto_indodax.md` **dipindahkan** ke
   `docs/laporan_temuan_bugs_koneksi_tokocrypto_indodax.md` (2026-10-18, K1–K15 tuntas), dan
   `temuan_bugs_notifikasi.md` **dipindahkan** ke `docs/laporan_temuan_bugs_notifikasi.md`
   (2026-10-17, N1–N17 tuntas), sedangkan `temuan_bugs_jurnal_pnl.md` **dihapus** (2026-10-17,
   J1–J10 tuntas). **Jangan membuat ulang keempat berkas itu**; temuan baru di area itu ditulis
   ke berkas temuan baru (`temuan_bugs_<topik>.md`).
   **Baca folder ini sebelum memperbaiki bug apa pun** di area yang tercakup. Setelah sebuah
   temuan diperbaiki, **pangkas butir itu dari badan dokumen** dan pindahkan jejaknya ke tabel
   "Sudah ditutup" — begitu seluruh isi sebuah berkas tutup, pindahkan berkasnya keluar
   (lihat § "Folder temuan bug" di bawah).
4. **`docs/BELUM-DIKERJAKAN-2026-10-14.md`** — inventaris 27 item (per 2026-10-20: **22 selesai**,
   **P4.2 dibatalkan** karena taker-only; yang terbuka = **P5**, **P6** diblokir, dan **V1–V4**
   verifikasi lapangan),
   lengkap dengan titik kode dan prioritas. **Baca ini sebelum memilih apa yang akan dikerjakan**,
   supaya tidak mengulang yang sudah selesai atau mengerjakan yang sudah dibatalkan.
5. **`workplan2.md` § 13** — pekerjaan terakhir yang **masih berlaku**. Seksi sebelumnya
   (§9 lantai friksi, §11 UI scalping, §12 rehidrasi paper) menjelaskan **kenapa** kode hari ini
   berbentuk seperti sekarang. Jangan mengubahnya tanpa membacanya.
6. **`workplan2.md` § 14 dan § 15 — DIBATALKAN, jangan dibangun ulang.** Lihat kotak merah di
   awal kedua seksi itu. Keduanya mendokumentasikan fitur kesiapan multi-horizon yang sudah
   **dihapus dari kode** pada 2026-10-13. Dibaca hanya sebagai riwayat keputusan.
7. `SCALP_EXIT.md` bila menyentuh exit.
8. `docs/LAPORAN-eksekusi-s1s9-2026-10-14.md` bila menyentuh entry/exit scalping — **dua
   keputusan di sana menyimpang dari daftar aslinya**: S9 dikirim **nonaktif** (ambang 25%
   terbukti menghapus seluruh trade GNS yang 4/4 menang) dan S2 **aktif** dengan default
   2× friksi, yang mengubah perilaku konfigurasi lama.
9. **`docs/exp/`** — 🟠 **hasil eksperimen terukur** (uji balik, parameter, strategi). Setiap
   eksperimen ditulis sebagai satu berkas `exp_<testname>_<pair>.md`. **Baca ini sebelum
   mengusulkan perubahan parameter engine atau mengklaim "strategi ini pasti bisa profit"** —
   hampir semua pertanyaan "apakah X berhasil" sudah dijawab di sini dengan angka. Contoh:
   `docs/exp/exp_scalping_expectancy_ADA-DOGE-XRP.md` (eksperimen lama 42,7%) dan
   `docs/exp/exp_scalping_expectancy_parity_ADA-DOGE-XRP.md` (parity app: 14,88%, avg R
   −0,4898, 963/1.149 TIMEOUT → scalping di Tokocrypto tidak layak untuk engine saat ini).
   Pengukuran Spec v2: `docs/exp/exp_mfe_m15_41pair.md` (MFE 15m 90 hari, **4/41 layak**) dan
   `docs/exp/exp_spot_backtest_41pair.md` (produksi 15m **0 trade**, 1h **7 trade semua rugi**,
   varian riset terbaik **−0,2055R**). **Riset v3 tidak ada di branch ini** — sudah dihentikan
   dan diarsipkan di branch `backup/patch-v3-2026-10-21`; jangan mengutip angka v3 dari ingatan.
10. Baru buka kode.

**Aturan kerja yang berlaku di repo ini:**

- **🔤 Bahasa: Balas chat & pertanyaan ke owner dalam Bahasa Indonesia. Bahasa Mandarin hanya
  untuk reasoning/internal, tidak pernah untuk jawaban yang terlihat.** Owner pernah menerima
  jawaban Mandarin dan tidak bisa membacanya. Semua dokumen, komentar kode, dan pesan commit
  juga Bahasa Indonesia (kecuali istilah teknis).
- **Branch kerja sekarang `patch`, bukan `main`.** Owner me-review di `patch` dulu. Jangan push ke
  `main` tanpa instruksi eksplisit.
- **Setiap perubahan wajib memperbarui markdown terkait** (`workplan2.md` + `AGENTS.md` +
  `docs/LAPORAN-*.md`). Pekerjaan tanpa jejak dokumen dianggap belum selesai.
- **Setiap klaim angka harus diukur, bukan diingat.** Hitung test dari
  `app/build/test-results/testDebugUnitTest/*.xml`, bukan dari pesan lama. Untuk mengukur baseline,
  pakai `git stash` → jalankan test → `git stash pop` (pola ini sudah dipakai beberapa kali).
- **Kontrol negatif wajib** untuk perbaikan bug: matikan perbaikannya, buktikan test GAGAL, lalu
  kembalikan. Test yang selalu hijau tidak membuktikan apa pun.
- **Bila ragu, eksekusi keputusan terbaik dan catat alasannya** di dokumen — jangan berhenti
  menunggu jawaban.

**⛔ JANGAN BANGUN ULANG FITUR KESIAPAN MULTI-HORIZON (§14/§15 — DIBATALKAN 2026-10-13):**

Fitur itu pernah dibangun, lalu **dihapus dari kode** karena menghasilkan informasi yang sama
dengan kartu engine di permukaan berbeda — dua angka (`N/5`) untuk satu pertanyaan, tanpa cara
bagi pembaca untuk tahu mana yang harus dipercaya. Owner memutuskan membatalkannya.

Yang **tidak boleh** dilakukan lagi tanpa instruksi eksplisit owner:

- Membuat paket/kelas baru yang menilai kesiapan koin **di luar** engine yang sedang aktif
  (`engine/readiness/*`, `HorizonReadinessEngine`, `ReadinessScanner`, `EngineVerdict`,
  `TradeHorizon`, `ReadinessBoard`, `ReadinessState`, `ReadinessSource` — semuanya sudah dihapus).
- Menambah kartu ringkasan lintas-horizon di dashboard atau halaman detail.
- Menampilkan `N/5` dari daftar syarat yang **berbeda** untuk koin yang sama di dua kartu berbeda.
  Format yang sama akan dibaca sebagai satu kebenaran; ini akar bug yang membuat fitur itu dibuang.
- Menambah `AppScreen` baru untuk daftar kesiapan.

**Yang berlaku sekarang (dan sudah cukup):**

| Butuh | Berkas |
|---|---|
| Status/kesiapan scalping satu sinyal | `engine/scalping/ScalpReadiness.kt` |
| Kartu status di halaman detail | `ui/components/detail/scalp/ScalpDeskCard.kt`, `WaitingEntryRadarCard.kt` |
| Engine mana yang aktif | `config/StrategyMode` (`TradingViewModel.strategyMode`) |
| Exit paper (beli → tahan → keluar) | `engine/scalping/PaperExitResolver.kt`, `SCALP_EXIT.md` |
| Threshold & lantai friksi | `engine/scalping/ScalpingConfig.kt` + `ScalpingRuntimeConfig.kt` |
| Exit paper (beli → tahan → keluar) | `engine/scalping/PaperExitResolver.kt`, `SCALP_EXIT.md` |
| Wiring state ke UI | `viewmodel/TradingViewModel*.kt` (banyak berkas, per fitur) |
| Tambah layar | `model/TradingViewModels.kt` (`AppScreen`) → `MainActivity.kt` → `viewmodel/TradingViewModelNavigation.kt` |

---

## Dokumen yang harus dibaca dulu

1. **`workplan2.md`** — workplan **aktif** (arah produk, fase P0–P3, slider, alur paper/replay)
2. **`APP_CONTEXT.md`** — arsitektur; utamanya § arah produk / scalp
3. **`SCALP_EXIT.md`** — exit paper & R net
4. **`data/config/scalp_config.json`** — mirror threshold + fee untuk `tools/scalp_replay.py`
5. **`docs/bugs/`** — 🔴 **sumber kebenaran status temuan audit** (kini **kosong**; lihat § "Folder temuan bug"). Mulai dari sana bila owner
   bertanya "apa lagi yang tersisa". **Status di berkas-berkasnya sudah diverifikasi ke kode dan
   direview owner pada 2026-10-16** (`patch` @ `547048d`) — **agen tidak perlu cek kode untuk
   memastikan statusnya**, cukup baca tabel di tiap berkas (lihat § "Folder temuan bug" di bawah).
6. **`docs/BELUM-DIKERJAKAN-2026-10-14.md`** — inventaris 27 item (per 2026-10-20: **22 selesai**,
   **P4.2 dibatalkan** karena taker-only; yang terbuka = **P5**, **P6** diblokir, dan **V1–V4**
   verifikasi lapangan),
   lengkap dengan titik kode dan prioritas. **Baca ini sebelum memilih apa yang akan dikerjakan**,
   supaya tidak mengulang yang sudah selesai atau mengerjakan yang sudah dibatalkan.
7. **`docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`** — 🔴 **baca ini sebelum memakai
   angka edge apa pun.** Menjelaskan kenapa angka "+0,0216 avg R / LOLOS" dari `u4` **tidak sah**
   (trade `UNRESOLVED` dulu dibuang dari statistik, ±81% sinyal) dan apa hasilnya setelah
   dihitung jujur (**avg R ≈ −0,17, 0/40 pair positif**).
8. **`workplan2.md` § 13** — pekerjaan terakhir yang **masih berlaku**. Seksi sebelumnya
   (§9 lantai friksi, §11 UI scalping, §12 rehidrasi paper) menjelaskan **kenapa** kode hari ini
   berbentuk seperti sekarang. Jangan mengubahnya tanpa membacanya.
9. **`workplan2.md` § 14 dan § 15 — DIBATALKAN, jangan dibangun ulang.** Lihat kotak merah di
   awal kedua seksi itu. Keduanya mendokumentasikan fitur kesiapan multi-horizon yang sudah
   **dihapus dari kode** pada 2026-10-13. Dibaca hanya sebagai riwayat keputusan.
10. **`docs/LAPORAN-*.md`** — jejak eksekusi. Yang terbaru: `docs/LAPORAN-sinkronisasi-spec-scalping-spot-2026-10-20.md`
    (pemetaan Spec v2 + hasil pengukuran), `docs/LAPORAN-p44-persistensi-fsm-2026-10-15.md`, dan
    batch 1–5f (2026-10-16); ringkasan batch 6o/6p ada di § Riwayat
    `docs/laporan_temuan_bugs_*.md` dan `workplan2.md` §59–§60. Baca dari tanggal terbaru kalau
    butuh tahu **kenapa** suatu perbaikan berbentuk seperti sekarang.
11. `SCALP_EXIT.md` bila menyentuh exit.
12. **`docs/exp/`** — 🟠 **hasil eksperimen terukur.** Baca sebelum mengubah parameter engine,
    treadmill "strategi ini akan profit", atau wallpaper angka hasil. Satu berkas per eksperimen,
    bernama `exp_<testname>_<pair>.md`. Contoh: `docs/exp/exp_scalping_expectancy_ADA-DOGE-XRP.md`
    (lama 42,7%), `docs/exp/exp_scalping_expectancy_parity_ADA-DOGE-XRP.md` (parity 14,88%),
    `docs/exp/exp_mfe_m15_41pair.md` (MFE 4/41), dan `docs/exp/exp_spot_backtest_41pair.md`
    (backtest Spec v2: produksi 0/7 trade, riset terbaik −0,2055R). **Riset v3 sudah dihentikan**
    dan tidak ada di folder ini; arsip di branch `backup/patch-v3-2026-10-21`.

> **Dokumen yang sudah dihapus (2026-10-16):** `workplan.md`, `checkpoint.md`,
> `checkpoint_progres.md` — isinya cuma stub "DEPRECATED" yang menunjuk ke `workplan2.md` /
> `AGENTS.md`. Kalau kamu menemukan rujukan ke sana, itu rujukan basi: **jangan dibuat ulang**
> dan jangan dipakai sebagai urutan kerja.

## Folder temuan bug (`docs/bugs/`)

**Sumber kebenaran status temuan audit.** Setiap berkas berisi **hanya temuan yang masih hidup**
di badan dokumen; temuan yang sudah ditutup diringkas di tabel "Sudah ditutup" (commit + pointer
laporan). ⚠️ **Status di sini sudah diverifikasi langsung ke kode dan direview owner pada
2026-10-16** pada `patch` @ `547048d` — **agen tidak perlu cek kode untuk memastikan statusnya.**
Verifikasi ulang hanya perlu kalau: (a) kamu akan **memperbaiki** butir yang masih hidup, atau
(b) ada commit baru setelah `547048d` yang menyentuh area terkait (buka `git log --oneline -5`).

Aturan main:

- **Sebelum memperbaiki bug** di area yang pernah diaudit, **baca berkas temuannya dulu** —
  supaya tidak mengulang audit, tidak menambal gejala, dan tidak menghapus bagian yang memang
  sudah benar (setiap berkas punya bagian "Yang sudah benar — jangan diutak-atik").
- **Setelah sebuah temuan diperbaiki**, **pangkas butir itu dari badan dokumen**, pindahkan ke
  tabel "Sudah ditutup" (commit + pointer laporan), perbarui tabel status di header, dan
  tambahkan baris ke § Riwayat. Jangan biarkan badan dokumen memuat butir yang sudah tidak hidup
  — pembaca berikutnya akan membuang waktu memperbaikinya ulang.
- **Aturan pemindahan (sejak 2026-10-17).** Begitu **seluruh** isi sebuah berkas temuan tertutup,
  berkas itu **tidak boleh tinggal di `docs/bugs/`**. Pindahkan dengan `git mv`:

  ```
  docs/bugs/temuan_bugs_<topik>.md  →  docs/laporan_temuan_bugs_<topik>.md
  ```

  Berkas hasil pindahan **adalah laporannya**. Tidak perlu membuat `docs/LAPORAN-batch*.md` baru:
  ringkasan tiap batch ditulis ke § Riwayat berkas itu dan ke `workplan2.md`. `docs/bugs/` hanya
  boleh berisi temuan yang masih hidup.
- **Jangan menambah laporan per batch.** Berkas `docs/LAPORAN-batch*-*.md` yang sudah ada adalah
  arsip historis — biarkan, jangan diperbanyak. Kalau butuh mencatat pekerjaan, tambahkan baris
  ke berkas laporan temuan yang bersangkutan (atau ke `workplan2.md`).
- Daftar berkas di folder ini bisa bertambah untuk area lain (exit, P&L, UI, dsb.) dengan
  pola penamaan `temuan_bugs_<topik>.md`.

Saat ini **kosong** (diperbarui 2026-10-18) — tidak ada temuan audit yang masih hidup. Riwayat
berkasnya:

- **`temuan_bugs_paper_trade.md` → DIPINDAHKAN 2026-10-18** ke
  **`docs/laporan_temuan_bugs_paper_trade.md`** (batch 6o — **seluruh P1–P9 tuntas**).
  Ringkas: P1–P4 (batch 3b/3c), P5 (batch 5d), P6 bagian 1 (batch 5c), P8 (batch 6d), P7
  (batch 6e), lalu **P6 bagian 2 + P9 ditutup batch 6o** — `peak` kini melihat high intrabar M1
  (`PaperIntrabarPeakResolver`), dan mematikan auto-exit saat runtime menyerahkan posisi ke jalur
  auto-sell lama (`PaperHandBackResolver` + `handBackAllPaperEnginesToLegacy()`).
  **Jangan membuat ulang berkas di `docs/bugs/` dengan nama itu.**
  **Batas penting:** seluruh temuan `P*` hanya menyentuh posisi **paper** (`isReal = false`) —
  bukan kerusakan uang riil, tetapi posisi paper adalah dasar seluruh keputusan "apakah scalp
  punya edge".
- **~~`temuan_koneksi_tokocrypto_indodax.md`~~ — DIPINDAHKAN 2026-10-18** ke
  **`docs/laporan_temuan_bugs_koneksi_tokocrypto_indodax.md`**. Seluruh K1–K15 tertutup
  (batch 6j–6n), jadi berkasnya keluar dari `docs/bugs/` dan sekarang berperan sebagai
  laporannya. **Jangan membuat ulang berkas di `docs/bugs/` dengan nama itu.**
  Yang berlaku sekarang: daftar pair Tokocrypto **hanya** dari `common/symbols` resmi (864 simbol,
  `data.list`); `exchangeInfo` gateway (3.729 simbol Binance) tidak boleh dipakai untuk discovery.
  Gate listing dan `type` dibaca fail-closed di `TokocryptoSymbolFeed`. WebSocket Indodax memakai
  kanal `market:summary-24h` dengan token utuh 105 karakter; kanal `chart:tick-<pair>` tidak pernah
  menerbitkan data. Socket usang disambung ulang lewat `reconnectIfStale()` → `reconnectNow()`,
  **bukan** lewat `start()` — `start()` menjalankan `stop()` dan membuang candle menit berjalan.
  Ambang usang ada di `RealtimeSocketHealth` (zombie 20 detik, diam 6 × interval ping), bukan 30
  detik frame. Kebijakan sambung-ulang ada di `ReconnectBackoff` (reset hanya setelah stabil,
  jitter ±20%, jeda menyerah 5 menit), status live dipisah per bursa (`wsLiveToko`/`wsLiveIndodax`),
  worker latar & `fetchTickers` Indodax hanya menyentuh watchlist/pair yang diminta
  (`/api/ticker/<pair>`, bukan `/api/summaries`), dan `getSymbolType` mengembalikan
  `SYMBOL_TYPE_UNKNOWN = 0` (bukan default `1`) dengan log sekali per simbol.
  Watchlist hanya menerima simbol yang ada di bursa aktif (`WatchlistEntryGate` + `hasSymbol`
  di kedua repository; fail-closed, tanpa fallback "simpan dulu"), dan dashboard memakai satu
  gate listing `DashboardRanking.symbolListed` — `uniqueTicks` disaring sekali di awal, quick
  filter WATCHLIST dan fallback statis ikut tersaring.
- **~~`temuan_bugs_jurnal_pnl.md`~~ — DIHAPUS 2026-10-17.** Seluruh J1–J10 sudah tertutup:
  batch 3a (J1, J2), batch 4 (J7), batch 5a (J4 + N10), batch 5b (J3), batch 5e (J6 + N11),
  batch 5f (J5), batch 6a (J10 sisa — `PaperArmSignal`), batch 6b (J9 — `TradeSignalConfidence`),
  batch 6c (J8 — `RealTradePnlResolver`). Tidak ada butir hidup, jadi berkasnya tidak punya alasan
  untuk tetap ada — jejak auditnya ada di laporan per batch + riwayat git.
  **Jangan membuat ulang berkas ini.** Kalau muncul temuan baru di area jurnal, buat berkas temuan
  baru di `docs/bugs/` (`temuan_bugs_<topik>.md`) dan catat di `workplan2.md`; jangan menghidupkan
  kembali "dokumen temuan" kosong.
  **Catatan angka yang tetap berlaku:** friksi FSM Tokocrypto USDT yang tertulis 0,8226% di dokumen
  temuan awal itu **salah**; angka yang benar **0,9735%** (lihat `docs/LAPORAN-batch4-j7-2026-10-16.md` §2).
- **~~`temuan_bugs_notifikasi.md`~~ — DIPINDAHKAN 2026-10-17** ke
  **`docs/laporan_temuan_bugs_notifikasi.md`**. Seluruh N1–N17 tertutup (batch 1, 2, 5, 6b, 6f, 6g,
  6h, 6i), jadi berkasnya keluar dari `docs/bugs/` dan sekarang berperan sebagai laporannya.
  **Jangan membuat ulang berkas di `docs/bugs/` dengan nama itu**, dan jangan menambah laporan
  batch baru untuk area ini — tambahkan baris ke § Riwayat berkas laporan tersebut.
  **N11** ditutup dengan **tidak menampilkan** tabel tier lintas-mode; cap confidence
  `ScalpingMtfEvaluator` sengaja **tidak** diubah. **N6 + N17** ditutup dengan satu titik sambung evaluasi
  (`engine/ModeSignalEvaluator.kt`): worker hanya menghitung mode aktif, dan penalti anomali makro
  dihitung di dalam titik sambung itu dari candle D1.

## Folder eksperimen (`docs/exp/`)

Berisi **hasil eksperimen terukur** — satu berkas per eksperimen, bernama
`exp_<testname>_<pair>.md`. Berbeda dari tiga folder lain di `docs/`:

| Folder | Isi | Aturan |
|---|---|---|
| `docs/bugs/` | temuan audit yang **masih hidup** | kosong sejak 2026-10-18 |
| `docs/laporan_temuan_bugs_*.md` | laporan audit **yang sudah tutup** | satu per topik |
| `docs/LAPORAN-*.md` | jejak eksekusi batch (arsip) | jangan ditambah |
| **`docs/exp/`** | **hasil eksperimen** — angka yang sudah diukur | tambah bebas, satu per eksperimen |

**Aturan isi — ini yang membedakan `docs/exp/` dari dokumen lain:**

- **Angka wajib diukur, bukan karangan.** Tulis command-nya, dataset-nya, dan hasilnya.
  Kalau angka belum diukur, tulis "belum diukur" — jangan menebak.
- **Sertakan angka negatif.** Eksperimen yang gagal adalah informasi paling berharga di
  folder ini; laporan yang hanya berisi hasil bagus tidak berguna.
- **Tulis batasan eksperimen secara eksplisit** — cakupan config, jumlah pair, rentang data,
  dan apa yang **tidak** dibuktikan. Pembaca berikutnya harus bisa menilai apakah hasil itu
  masih berlaku.
- **Tunjuk eksperimen terkait** di laporan lain agar angka tidak berbeda antar dokumen.
- Nama berkas memakai pair yang dites; kalau multi-pair, pisahkan dengan `-`
  (contoh: `exp_scalping_expectancy_ADA-DOGE-XRP.md`).

**Contoh isi saat ini:** `exp_scalping_expectancy_ADA-DOGE-XRP.md` — eksperimen lama: win rate
42,7% (8 jam) dan 45,2% (+48 jam) vs ambang 60,1% pada SL 1,94% / TP2 2,42%, tanpa time-stop.
Pengulangan parity ada di `exp_scalping_expectancy_parity_ADA-DOGE-XRP.md`: **14,88%**,
avg R **−0,4898**, 963 dari 1.149 trade TIMEOUT, ambang impas payoff **55,08%**. Kesimpulan
tetap: scalping di Tokocrypto tidak layak untuk engine saat ini; hipotesis "trade UNRESOLVED =
tren berlanjut" juga terbantah (92 WIN vs 92 LOSS).

**Pengukuran Spec v2 (2026-10-20):** `exp_mfe_m15_41pair.md` — MFE 15m 90 hari: **4/41 pair
layak** (MUBARAK 2,78x, PUMP 2,58x, ZRO 2,34x, PLUME 2,12x); 10 pair yang dulu eligible turun,
0 pair naik. `exp_spot_backtest_41pair.md` — 14 varian backtest: produksi 15m **0 trade**,
produksi 1h **7 trade semua rugi** (avg R −0,3835), varian riset terbaik **−0,2055R**
(in-sample −0,0912 / out-of-sample −0,3828). Kesimpulan: Spec v2 belum punya bukti edge;
Fase 1 Kotlin diblokir sampai keputusan owner.

**Riset v3 (2026-10-21) tidak ada di folder ini.** Spec v3 + harness v3 sudah diukur, hasilnya
negatif, lalu dihentikan owner; seluruh artefaknya (termasuk `exp_spot_evidence_v3.md`) ada di
branch backup `backup/patch-v3-2026-10-21` (`796a9d1`). Jangan membuat berkas v3 baru di
`docs/exp/` branch ini.

## Aturan turunan dari audit notifikasi (batch 1 & 2, 2026-10-16)

- **Kepemilikan edge pemicu harus tunggal.** Pemicu yang bersifat *edge-once* (mis. trailing
  stop: pemanggil pertama yang melihat harga melewati batas mengonsumsi edge-nya) **hanya boleh
  dikonsumsi oleh jalur yang benar-benar mengeksekusi**. Pengamat lain — loop background, service,
  dashboard — wajib memakai jalur baca-saja (`consumeTrigger = false` di
  `SpotPositionStore.updateTrailingPrice`) supaya tidak mencuri edge dan membuat aksi otomatis
  tidak pernah terkirim. Kalau sebuah pemicu tidak punya pemilik tunggal, itu bug, bukan desain.
- **"SATU rumus P&L" juga berlaku di jalur notifikasi.** Dilarang memanggil
  `TradingFeeConfig()` tanpa argumen di jalur runtime mana pun — tanpa argumen ia jatuh ke preset
  **legacy Indodax-ish** (0,11 / 0,21 / 0,32 / 0,42), bukan biaya bursa aktif user. Pakai
  `AppPreferences.tradingFees` (atau `FeeSchedule.configFor`). Penjaga:
  `app/src/test/java/agu/analys/service/NotificationFeeSourceTest.kt`.
- **Skor `confidence` berskala 0–100, bukan 0–1.** Jangan pernah mengalikannya dengan 100 saat
  ditampilkan. Penjaga: `app/src/test/java/agu/analys/util/CandidateSignalMessageTest.kt`.
- **Ekstrak pembentuk teks/notifikasi jadi fungsi murni** supaya bisa diuji tanpa Android. Pesan
  notifikasi yang dirakit inline di dalam `NotificationCompat.Builder` tidak bisa diuji — itulah
  cara bug `6800%` bertahan lama.
- **Sakelar notifikasi hanya boleh dijaga di gerbang tunggal, bukan di call site.** Setiap
  pengiriman wajib lewat `AlertNotificationHelper.canNotify(context, kategori)`. Jangan menambah
  `if (prefs.isX) return` di pemanggil: ada belasan call site dan justru itu akar N4. Sakelar baru
  cukup ditambah sebagai satu cabang di `allowsNotification` (`util/NotificationGate.kt`) + satu
  properti di `AppPreferences.notificationSettings`. Penjaga:
  `app/src/test/java/agu/analys/util/NotificationGateTest.kt`.
- **Mematikan notifikasi TIDAK boleh mematikan eksekusi.** Gerbang notifikasi hanya hidup di
  lapisan notifikasi. `executeAutoSellOrder` / stop-loss / trailing harus tetap jalan walau semua
  sakelar notifikasi mati — mematikan bel bukan berarti melepas perlindungan posisi. Karena itu
  jangan pernah menaruh guard sakelar di awal `checkAlertsAndTrailing` (ia memanggil
  `checkTrailingForMode` yang mengeksekusi jual).
- **Semua pencarian key berbasis simbol wajib lewat `normalizeSymbol`.** Key `activeSignals`
  dibentuk `"${normalizeSymbol(symbol)}#${mode}"`; memakai `symbol.uppercase()` mentah membuat
  `"BTC_IDR#"` tidak pernah cocok dengan `"BTCIDR#SCALPING"` (N7 — terbukti aktif di jalur real
  trade). Berlaku untuk `markTriggered`, `reset`, dan fungsi baru apa pun yang menyusun prefix.
- **Uji perilaku notifikasi di Robolectric: jangan membuat `AppPreferences` kedua.** Instance baru
  **tidak** melihat tulisan instance pertama (jalur pemulihan keystore menimpa prefs), sehingga uji
  gagal karena artefak harness. Pakai
  `AlertNotificationHelper.notificationPrefsForTest(context)` — objek yang benar-benar dibaca
  gerbang.

## Aturan turunan dari audit jurnal & P&L (batch 3a, 2026-10-16)

- **Satu predikat menang/kalah, dan predikat itu eksklusif.** Menang/kalah ditentukan **hanya** oleh
  `classifySignalOutcome(status, realizedPnlPct)` (`database/SignalOutcome.kt`). Dilarang menulis
  ulang filter bergaya `outcomeStatus in listOf("HIT_TP1", …) || pnl > 0` di tempat baru — itulah
  cara satu log bisa dihitung menang **dan** kalah sekaligus (win-rate > 100%). Penjaga:
  `app/src/test/java/agu/analys/database/SignalOutcomeClassificationTest.kt`.
- **Status peristiwa menang atas tanda P&L.** `HIT_SL` dengan `realizedPnlPct` positif tetap
  dihitung **kalah**: status mencatat apa yang benar-benar terjadi, sedangkan besaran P&L bisa jadi
  data warisan yang salah. Jangan "membetulkan" klasifikasi berdasarkan tanda angka.
- **`realizedPnlPct` wajib net; `maxProfitPct`/`maxDrawdownPct` tetap gross (B6).** Net dihitung
  lewat `FeeSchedule.netProfitPctForDirected` (arah BUY/SELL dibedakan — SELL adalah cermin long
  dengan peran `entry`↔`price` ditukar) melalui pembungkus tunggal `database/SignalLogPnl.kt`.
  Label UI harus mengikuti basis yang benar-benar dipakai ("Net"/"Gross"), dan angka yang tidak
  diketahui ditulis "Hasil tidak diketahui", bukan "0,00%".
- **Jangan campur level dua episode sinyal dalam satu baris log.** Bila `entryPrice` bergerak lebih
  dari `ENTRY_SPLIT_TOLERANCE` (0,8%, sengaja sama dengan ambang anti-spam `recordSignal`), log lama
  harus ditutup apa adanya (`EXPIRED` pada harga terkini, memakai `entry`/TP/SL miliknya sendiri)
  lalu episode baru dibuat sebagai log terpisah. Harga tidak valid (`<= 0`, `NaN`, `∞`) **bukan**
  perpindahan material.
- **Efek amnesti adalah hasil yang benar, bukan regresi.** Setelah basis net dipakai, banyak sinyal
  yang dulu tampak "menang" berubah jadi kalah/`EXPIRED` negatif — kenaikan +0,50% di Tokocrypto USDT
  memang −0,468950% setelah friksi. Setiap laporan yang menyentuh angka win-rate **wajib**
  menyebutkan ini eksplisit supaya pembaca tidak menyimpulkan engine-nya rusak.

## Aturan turunan dari audit posisi paper (batch 3b, 2026-10-16)

- **Satu penentu harga SL untuk posisi paper.** Harga SL hanya boleh datang dari
  `PaperStopResolver.resolve(...)` (`engine/scalping/PaperStopResolver.kt`). Dilarang memakai SL
  sinyal apa adanya atau menghitung lantai friksi sendiri di call site — itulah cara SL 1,5–2,8%
  lolos ke posisi yang friksi round-tripnya ≈0,97% (P2). Lantai dihitung lewat
  `frictionFloorPct(buyFee, sellFee, slippage, minFrictionMultiple)`, dan **slippage dihitung dua
  sisi** (masuk + keluar).
- **Lantai friksi hanya melebarkan, tidak pernah menyempitkan.** `resolve` mengambil yang lebih
  **rendah** (lebih lebar) antara SL sinyal dan harga lantai. Jangan "mengoptimalkan" dengan memilih
  yang lebih rapat demi R yang terlihat lebih besar.
- **`stopLossPrice >= entryPrice` adalah keadaan terlarang.** Tidak ada jalur mana pun — termasuk
  serah-terima `HandBackToLegacy` — yang boleh menyalakan auto-sell dengan SL seperti itu. Lebih
  baik posisi berjalan tanpa stop (terlihat di UI, bisa dijual manual) daripada penjualan pasar
  berlabel `STOP_LOSS` yang tidak pernah tersentuh. Log wajib menyebut sumber SL-nya
  (`SIGNAL`/`FRICTION_FLOOR`/`NONE`).
  **Pengecualian sadar (batch 6o, P9):** stop yang berasal dari `activeStopLoss` mesin yang sudah
  naik **di atas entry** (BE/trailing terkunci) **dipertahankan** oleh `PaperHandBackResolver` —
  jalur lama menjual saat `currentPrice <= stopLossPrice`, jadi stop seperti itu aman dan justru
  menjual pada harga ≥ entry. Yang tetap terlarang: SL tersimpan/DCA/sintetis `>= entry` yang
  **bukan** dari mesin — itu wajib jatuh ke lantai friksi atau `NONE`.
- **Posisi paper + mesin exit adalah produk SCALPING.** `tryPaperAutoBuy` wajib memeriksa
  `transition.mode == StrategyMode.SCALPING`. Evaluator SWING/INTRADAY memasang
  `ScalpingStage.ENTRY` yang sama, jadi `stageQualifies` saja **tidak** menyaringnya — jangan hapus
  gerbang mode itu tanpa alasan eksplisit. Gerbangnya diletakkan **sebelum** `stageQualifies` supaya
  log diagnostiknya bisa tercapai.
- **Perubahan entry (DCA) wajib me-re-arm mesin exit.** Bila `entryPrice` posisi berubah, mesin
  harus dibangun ulang (`force = true`) dengan entry baru; mesin lama memakai entry fill **pertama**,
  dan selisih itulah yang memunculkan SL di atas entry.
- **Rumus yang dibutuhkan lebih dari satu berkas harus jadi fungsi murni, bukan `private`.**
  `atrPct` tidak bisa diteruskan dari `TradingViewModelSync` karena `atrPctAt` `private`; hasilnya
  `atrPct = 0.0` untuk setiap beli manual, dan nol itu ikut tersimpan ke disk (P4). Ekstrak ke
  `object` di `engine/scalping` supaya bisa diuji tanpa ViewModel.

## Aturan turunan dari audit rehidrasi paper (batch 3c, 2026-10-16)

- **Rehidrasi wajib lintas bursa, bukan bursa aktif.** Setelah restart, mesin exit harus dipulihkan
  untuk **semua** bursa yang punya posisi paper tersimpan — urutannya ditentukan
  `PaperRehydrateScope.decide(...)` (`engine/scalping/PaperRehydrateScope.kt`), bursa aktif lebih
  dulu. Membaca `prefs.marketDataSource.name` saja membuat posisi di bursa lain menggantung
  **permanen** (P1). Enumerasi lintas bursa memakai
  `SpotPositionStore.getPaperEnginePositions(isReal)`.
- **Ganti bursa aktif adalah peristiwa, bukan sekadar preferensi.** Setiap perubahan
  `prefs.marketDataSource` harus memicu rehidrasi; jangan menambah jalur baru yang mengganti bursa
  tanpa memicu rehidrasi.
- **Jangan membaca hanya dari memori untuk daftar posisi.** `PaperAutoTradeRuntime` kosong setelah
  restart sampai rehidrasi selesai — daftar simbol yang perlu dipantau harus juga dibaca dari disk.
- **Ingat batas yang belum ditutup:** mesin untuk bursa non-aktif sudah dipulihkan tetapi **belum
  bisa dievaluasi**, karena `evaluatePaperAutoExit` hanya menerima harga bursa aktif. Jangan
  mengklaim "posisi di bursa non-aktif terlindungi otomatis" sebelum ada umpan harga lintas bursa.
  Bukti batas ini: `docs/LAPORAN-batch3-rehidrasi-2026-10-16.md` §4.
- **Angka uji harus diukur pada pohon commit yang dimaksud.** Bila berkas yang belum di-commit masih
  ada di pohon kerja, jumlah test yang terukur adalah milik pohon kerja itu, bukan milik commit.
  Ukur ulang dengan mengeluarkan berkas di luar cakupan commit, atau tandai angkanya sebagai
  "diturunkan" — jangan tulis angka pohon kerja sebagai angka commit.

## Aturan turunan dari audit jurnal & P&L — J7 (batch 4, 2026-10-16)

- **Kartu uang riil tidak boleh menghitung sendiri.** `RadarSellSection` dulu punya rumusnya sendiri
  (hanya fee jual, tanpa fee beli, tanpa slippage) sementara `FeeSchedule` sudah jadi satu-satunya
  rumus P&L sejak §24 — dan kartunya menulis "sudah bersih fee & pajak" di atas angka itu. Setiap
  kartu yang menampilkan laba/rugi wajib menerima angka jadi dari resolver murni
  (`trading/SellEstimate.kt`), bukan menghitung ulang di dalam Composable.
- **Migrasi §24 menyentuh *evaluator*, bukan *kartu UI*.** "Radar jual" di §24.3 bermakna
  `engine/sell/SellSignalEvaluator.kt` dan `model/PositionContext.kt`. `RadarSellSection` +
  `SellCalculationCard` tidak pernah ikut. Kalau sebuah dokumen menyebut "sudah diseragamkan",
  **verifikasi ke kode**, jangan percaya tabelnya.
- **Kaki beli diasumsikan taker, kaki jual memakai order type terpilih.** Order beli sudah terjadi
  dan jenisnya tidak diketahui saat kartu jual dihitung; taker adalah asumsi terburuk dan sekaligus
  basis FSM. Jangan "memperbaiki" ini menjadi maker tanpa alasan — arah kesalahan harus pesimistis.
- **Label wajib menyebut komponennya, bukan satu persen tunggal.** "Potongan Biaya & Pajak (0,43%)"
  menyesatkan karena persen itu hanya fee jual. Sebut fee beli, fee jual, dan slippage satu per satu
  (`frictionBreakdownLabel`). Frasa **"sudah bersih fee & pajak" dilarang** — klaim itu yang jadi
  inti temuan J7.
- **Jangan mengklaim untung saat modal belum diketahui.** Tanpa `costBasisNet > 0`, laba dan persen
  laba wajib `0.0` dan kartu harus berkata jujur bahwa biaya beli belum diketahui.
- **Angka audit pun bisa salah.** Dokumen temuan menulis friksi FSM Tokocrypto USDT 0,8226%; angka
  yang benar 0,9735%. Bila angka audit tidak cocok dengan rumus mana pun, hitung ulang dan **catat
  koreksinya** di laporan — jangan mengutipnya sebagai kebenaran.

## Aturan turunan dari audit jurnal — J4 & N10 (batch 5a, 2026-10-16)

- **Kepemilikan sinyal diputuskan di satu tempat.** `engine.signalState.value` berisi hasil evaluasi
  pair **terakhir yang dinilai**, bukan pair yang sedang dibuka. Setiap pencatatan ke jurnal wajib
  lewat `trading/SignalSymbolAttribution.kt` — jangan menimpa `marketSymbol` dengan
  `selectedPair.value.symbol`, dan jangan memakai `engine.signalState.value` tanpa
  `matches(requestedSymbol, signal.marketSymbol)`.
- **Nilai yang bukan milik simbol ini dikosongkan, bukan dipakai.** Bila sinyal milik pair lain,
  confidence/TP/SL menjadi `0`/`0.0`, harga jatuh ke harga transaksi, waktu jatuh ke waktu transaksi.
  Angka pair lain yang menempel di pair ini membuat statistik reliability bohong tanpa terlihat.
- **Gerbang di pintu masuk, bukan di tiap pemanggil.** `TradeSignalSnapshot.capture` menggerbang
  sinyal sekali; keempat pemanggilnya ikut terlindungi. Bila menambah pemanggil baru, jangan
  menyalin pemeriksaannya — pakai `capture`.
- **Nilai turunan sinyal untuk KEPUTUSAN juga wajib teratribusi**, bukan hanya untuk catatan.
  `capture` melindungi jurnal; mesin exit punya pintu sendiri: `trading/PaperArmSignal.resolve`
  (batch 6a). Setiap jalur yang meng-*arm* mesin exit wajib lewat situ — TP pair lain yang
  kebetulan di bawah entry bisa memicu jual sebagian pada tick pertama. Bila menambah pemanggil
  `armPaperExitEngine` baru, jangan mengoper `engine.signalState.value.stopLoss/targetPrice1/
  targetPrice2` mentah.
- **Harga fallback harus milik simbol jurnal.** `currentTick.value?.price` adalah tick pair yang
  kebetulan dibuka; pakai `dashboardTicks[journalSymbol]`.

## Aturan turunan dari audit jurnal — J3 (batch 5b, 2026-10-16)

- **Bursa untuk query berskala bursa harus mengalir, bukan dibaca sekali.** Parameter Room terikat
  saat flow dibuat; `val x: String get() = prefs.marketDataSource.name` **tidak** menyelamatkan
  keadaan karena getter itu hanya dipanggil sekali, saat flow dibangun. Pakai
  `SignalLogExchangeBinder.bind(alurBursa) { repo.get…Flow(it) }` — jangan menulis `stateIn` di
  atas query yang parameternya dibaca langsung.
- **`flatMapLatest` yang di-import tapi tidak dipakai adalah petunjuk, bukan sampah.** Itu tanda
  kode reaktifnya pernah direncanakan lalu tertinggal; selesaikan, jangan hapus importnya.
- **Satu sumber untuk bursa.** Bila sebuah ViewModel punya `prefs.marketDataSource` **dan** flow
  turunan, keduanya bisa tidak sepakat. Teruskan `settingsViewModel.marketDataSource` dan bacalah
  `flow.value` bila perlu nilai sekarang.

## Aturan turunan dari audit jurnal — J5 (batch 5f, 2026-10-16)

- **Query jurnal trade wajib berversi exchange, tanpa fallback "semua bursa".** Tokocrypto
  melayani USDT + IDR, Indodax hanya IDR — `BTCIDR`/`ETHIDR`/`XAUTIDR` ada di kedua bursa
  sekaligus, jadi fallback menutup/memperbarui record bursa yang **salah** dengan data bursa
  lain. Pola yang benar sudah ada: `SignalLogRepository.processPriceTick` (query berversi
  exchange, tanpa fallback, komentar eksplisit). Jangan tambahkan `?:` ke query tanpa filter
  exchange — "mungkin ketemu di bursa lain" adalah **bug**, bukan fallback.
- **Kosong berarti kosong.** Bila bursa ini tidak punya record HOLDING untuk simbol itu,
  biarkan kosong. Jual mencatat penjualan tanpa menutup record bursa lain; tick harga tidak
  menyentuh posisi bursa lain. Posisi bursa lain tetap utuh sampai dijual di bursanya sendiri.

## Aturan turunan dari audit jurnal — J8 (batch 6c, 2026-10-17)

- **Jangan menulis ulang rumus P&L di ViewModel.** J8 adalah bukti bahwa aturan "SATU rumus P&L"
  bisa dilanggar tanpa terlihat: jalur jual trade real menulis
  `(exit×(1−sellFee))/(entry×(1+buyFee))−1` — **tanpa slippage** — sehingga jurnal berbeda
  ≈0,16 pp dari portofolio/radar jual untuk trade yang sama. Pakai `RealTradePnlResolver`
  (`trading/RealTradePnl.kt`) atau `FeeSchedule` langsung; jangan menyusun ulang
  `costBasisNet`/`netSellValue` dari komponennya.
- **Rupiah dan persen harus dari pasangan yang sama.** `pnlIdr = netSellValue − costBasisNet`
  dan `pnlPct = netProfitPctFor`; keduanya identik secara aljabar, jadi tidak mungkin menampilkan
  "+1,2%" dengan nominal yang tidak cocok.

## Aturan turunan dari audit jurnal — J6 (batch 5e, 2026-10-16)

- **Teks statistik di UI wajib menyebut basis + jumlah sampel, dan digerbang sampel cukup.**
  Kalimat seperti "skor keyakinan tinggi menunjukkan rasio kemenangan tertinggi" tanpa data adalah
  pelanggaran — walau kalau di belakangnya ada tabel. Bila sampel belum cukup, cetak "belum cukup",
  bukan klaim. Ambangnya **satu**: `SetupStats.MIN_RELIABLE_SAMPLE`.
- **Tanpa data, jangan cetak apa pun.** `resolve` mengembalikan `null` untuk `totalLogs == 0`;
  bar info tidak dirender. Bar kosong lebih jujur daripada bar berisi klaim.
- **Keterbatasan data disebut sebagai fakta, bukan disembunyikan.** Tier yang belum terisi
  sinyal ditulis sebagai "K dari 3 tier belum terisi sinyal" — pembaca tabel berhak tahu apa yang
  sedang diukur (N11: tier ≥80 mengukur "apakah sudah READY" untuk sinyal pra-entry).

## Aturan turunan dari audit paper trading — P6 (batch 5c, 2026-10-16)

- **Keputusan exit tidak boleh bergantung pada layar yang sedang terbuka.** Bila dua pemanggil
  bisa mengirim input yang berbeda ke mesin yang sama, hapus parameternya dan bacalah dari satu
  sumber di dalam — jangan "seragamkan" dengan mengirim nilai yang sama dari dua tempat.
  `evaluatePaperAutoExit` tidak lagi menerima `window`; jendelanya datang dari
  `PaperExitMomentumWindow` atas cache M1.
- **Ambang kesegaran data ikut yang sudah ada.** `MtfCacheManager.isCacheValid(M1)` = 20 bar /
  180 detik. Memakai angka lain berarti dua definisi "M1 masih segar" dalam satu aplikasi.
- **Mengurangi frekuensi sebuah exit bisa jadi justru perbaikannya.** `EARLY_PROFIT` yang hanya
  terjadi saat pair kebetulan dibuka bukan fitur — itu ketergantungan pada keadaan UI. Jangan
  "kembalikan" frekuensinya tanpa membaca temuan P6.

## Aturan turunan dari audit paper trading — P5 (batch 5d, 2026-10-16)

- **State per posisi tidak boleh jadi state global.** `publishPaperExitSnapshot` dulu menulis satu
  `MutableStateFlow` sementara dievaluasi untuk setiap simbol; simbol tanpa mesin lalu menimpa
  `null` dan menghapus kartu simbol lain. State yang dimiliki entitas (posisi/bursa/simbol) harus
  berkunci pada entitas itu — `_paperExitSnapshots: Map<String, PaperExitSnapshot>` berkunci
  `PaperAutoTradeRuntime.key(exchange, symbol)`.
- **Kunci yang sudah dipakai mesin jangan dipecahkan lagi.** Format kunci posisi hanya satu:
  `PaperAutoTradeRuntime.key`. Membuat format kedua berarti dua definisi posisi yang sama.
- **Konsumen wajib menyebut posisinya.** `paperSnapshotAt(exchange, symbol)` (layar simulasi) dan
  `paperSnapshotFor(...)` (halaman detail) — keduanya menyebut posisi yang dibuka, bukan
  "snapshot apa pun yang terakhir terbit".

## Aturan turunan dari audit paper trading — P8 (batch 6d, 2026-10-17)

- **"Dievaluasi sebagai bar baru" ≠ "menambah hitungan bar".** `PaperExitStateMachine.onBar` punya
  dua parameter terpisah: `isNewBar` (boleh memeriksa time-stop) dan `countAsNewBar` (boleh
  `barsHeld++`). Jangan pernah menyatukannya kembali.
- **Sesudah rehidrasi, menit yang berjalan sudah dihitung.** `PaperRehydrate.mergedBarsHeld`
  memakai jam dinding (`max(stored, (now − openedAt) / 60_000)`), jadi tick pertama sesudah
  `put(..., markFirstTickAsNewBar = true)` **wajib** `isNewBar = true` + `countAsNewBar = false`.
  Kalau keduanya `true`, time-stop keluar 1 menit lebih awal dan app tidak lagi sebanding dengan
  `tools/scalp_replay.py`.
- **Keputusan murni, bukan `if` di dalam lambda.** Pemetaan itu milik
  `PaperFirstBarResolver.countsAsNewBar(isNewBar, firstTickAfterRehydrate)`; runtime hanya
  melaporkan fakta lewat `BarBucketMark`. Penjaga:
  `app/src/test/java/agu/analys/trading/PaperFirstBarTest.kt`.
- **Sentinel bucket hanya menandai satu tick.** `FIRST_TICK_BUCKET = -1L` dihapus oleh
  `markBarBucket` pada pemanggilan pertama, sehingga pengecualian tidak bocor ke menit berikutnya.
  Jangan menambahkan field runtime terpisah untuk ini — penanda yang bertahan akan menahan
  time-stop lebih lama, bukan lebih pendek.

## Aturan turunan dari audit notifikasi — N6, N17, N11 (batch 6i, 2026-10-17)

- **Satu mode, satu mesin, satu angka.** Notifikasi kandidat wajib menampilkan `confidence` dari
  evaluator **mode yang sedang aktif**, dan worker latar hanya boleh menghitung mode itu
  (`prefs.strategyMode`). Jangan mengembalikan pemindaian tiga mode sekaligus: itu membuat satu key
  `SYMBOL#MODE` punya dua produsen dengan metode berbeda.
- **Pemanggil tidak boleh merakit evaluator sendiri.** `engine/ModeSignalEvaluator.kt` adalah
  satu-satunya pemetaan `StrategyMode → evaluator`. Penalti anomali makro dihitung **di dalam**
  titik sambung itu dari candle D1 — parameter `macroAnomalyResult` sengaja tidak diekspos ke
  pemanggil, supaya tidak ada jalur yang bisa "lupa" meneruskannya (itu akar N17). Kalau menambah
  mode/parameter, tambahkan di titik sambung itu + uji di `ModeSignalEvaluatorTest`.
- **Jangan hidupkan `diagnosticIgnoreOrderBookWhenUnavailable = true` di jalur produksi.** Tanpa
  orderbook nyata, gerbang Step2 harus **GAGAL**, bukan dilewati. Skor yang lebih tinggi daripada
  engine tidak boleh masuk ke notifikasi.
- **Batasi ke watchlist.** Worker hanya memindai `prefs.getWatchlist()`, tidak pernah seluruh pasar.
  Scalping dibatasi `MAX_SCALP_BACKGROUND_SYMBOLS` (4 permintaan/simbol: H1, M15, M1, orderbook).
  Menambah mode lain ke worker berarti menambah beban baterai — ukur dulu, jangan asal tambah.
- **Batas yang jujur:** worker tetap tidak punya forming volume/orderbook live seperti engine
  per-tick, jadi skor bisa bergerak antar-snapshot. Yang dijamin adalah **metode dan gerbang yang
  sama**, bukan snapshot detik yang sama. Jangan menjanjikan "angka identik" di UI/dokumen.
- **N11 — jangan tampilkan tabel tier lintas-mode.** Ambang ≥80/60–79/<60 membuat sinyal scalping
  pra-entry (cap ≤59) selalu "Awal", sehingga tabel mengukur READY-vs-bukan. Owner memutuskan
  **tidak menampilkan**; cap `ScalpingMtfEvaluator` tidak diubah. Penjaga:
  `ConfidenceTierRemovalTest`.

## Aturan turunan dari audit notifikasi — N12 (batch 6h, 2026-10-17)

- **Edge-trigger bukan pengganti cooldown.** Transisi `READY` mencegah pengiriman berulang pada
  state yang stabil, tetapi tidak pada state yang **flapping** (`READY → INVALIDATED → READY`).
  Setiap notifikasi berbasis transisi wajib punya jendela per entitas (pair), bukan hanya syarat
  "baru berubah".
- **Cooldown ditaruh di chokepoint pengiriman, bukan di pemanggil.** Ada dua pengirim kandidat
  (VM foreground, worker latar); menaruhnya di salah satu berarti jalur lain tetap spam.
- **Uji keputusan, bukan tampilan.** Notifikasi dengan ID sama hanya *menimpa* entri di shade,
  jadi "hitung notifikasi" tidak bisa membedakan satu kiriman dari dua. Yang diuji:
  `CandidateNotifyCooldown.allow(...)` + satu uji pindai sumber bahwa helper memanggilnya.

## Aturan turunan dari audit notifikasi — N15, N16 (batch 6g, 2026-10-17)

- **Foreground service adalah keep-alive, bukan pengirim push.** `TradingForegroundService`
  menjaga proses tetap hidup supaya pemantauan posisi (dan `executeAutoSellOrder`) bisa jalan.
  Sakelar notifikasi **tidak boleh** menjadi input keputusan start/stop: dulu
  `hasActive && isNotificationsEnabled.value`, dan akibatnya mematikan "push notifikasi" membuat
  `livePrices` kosong ⇒ P&L tampil 0%. Aturannya: `hasActive` menentukan hidup-mati; `canNotify`
  menentukan bunyi.
- **`AppPreferences` tidak boleh dibangun di jalur per-tick.** Konstruktornya membangun
  `EncryptedSharedPreferences` + `MasterKey`, dan catch block-nya **menghapus seluruh preferensi**
  bila gagal. Satu instance per komponen (`by lazy`), dan fungsi yang dipanggil tiap tick harus
  menerima data (mis. `exchange`) dari pemanggil, bukan membacanya sendiri.
- **Penjaganya uji pindai sumber, bukan uji perilaku.** Kedua aturan ini hidup di kode ber-
  `ViewModel`/`Context`; `ForegroundMonitorSourceTest` membaca badan fungsi dan hitungan
  `AppPreferences(`. Kalau menambah pembacaan prefs di service, uji itu gagal — itu memang
  tujuannya.

## Aturan turunan dari audit notifikasi — N13, N14, N8 (batch 6f, 2026-10-17)

- **Kategori notifikasi ditentukan oleh JENIS PERISTIWA, bukan oleh helper yang paling mudah
  dipanggil.** `PRICE_ALERT` hanya untuk pengingat harga yang diminta pengguna sendiri; laporan
  "order berhasil/gagal" adalah **hasil eksekusi** (`TRAILING_STOP`). Salah kategori punya dua
  akibat yang sama-sama merusak kontrol: sakelar trailing tidak mematikan notifikasi trailing, dan
  mematikan pengingat harga justru membungkam konfirmasi order uang riil. Pemetaannya ada di
  `util/NotificationRouting.kt`.
- **Nama helper harus membawa kategorinya.** `sendUserPriceAlertNotification` vs
  `sendExecutionResultNotification` — nama generik `sendPriceAlertNotification` sengaja **dihapus**
  supaya pemanggil baru tidak bisa "salah kategori tanpa sadar". Penjaga:
  `app/src/test/java/agu/analys/util/NotificationSourceTest.kt`.
- **Satu peristiwa, satu pengirim.** Notifikasi "Trailing Stop Naik" hanya boleh dikirim dari jalur
  ber-jendela (`TrailingPeakThrottle`, 30 dtk). Fungsi yang tugasnya memperbarui order
  (`updateSimTrailingOrder` / `updateRealTrailingOrder`) **tidak boleh** mengirim notifikasi:
  dulu keduanya mengirim tanpa pembatas, sehingga cooldown 30 dtk tidak pernah berlaku dan user
  mendengar bunyi tiap ~10 detik. Kalau butuh notifikasi baru, tambahkan di jalur ber-jendela.
- **Parameter yang tidak dibaca adalah utang, bukan opsi.** `onlyWhenBackground` diteruskan ke
  helper tetapi tidak pernah dipakai — dihapus (N8). Jangan menambah parameter "untuk nanti";
  implementasikan perilakunya + uji, atau jangan ada.

## Aturan turunan dari audit paper trading — P7 (batch 6e, 2026-10-17)

- **Fungsi tanpa pemanggil adalah kontrak palsu.** `clearPaperExitState` bertahan berbulan-bulan
  seolah-olah ada jalur "pengguna mengambil alih exit engine", padahal `grep -rn` hanya menemukan
  definisinya. Setiap hook siklus hidup yang tidak punya pemanggil harus **dihapus** atau
  **dipakai** — jangan dibiarkan sebagai harapan.
- **Serah-terima exit tidak boleh meninggalkan posisi tanpa stop.** `armPaperExitEngine` menulis
  `setAutoSellParams(enabled = false)`; siapa pun yang melepas mesin **wajib** menyalakan kembali
  SL/TP legacy lewat `PaperStopResolver` (contoh yang benar: `HandBackToLegacy` di
  `PaperRehydrate`). Melepas mesin tanpa itu = posisi tanpa exit sama sekali.
- **Jangan mengarang level serah-terima.** Bila SL sudah naik ke BE/trailing (`activeStopLoss` bisa
  **di atas** entry), `PaperStopResolver` menganggapnya tidak valid; memilih level pengganti adalah
  **keputusan produk**, bukan detail implementasi. Keputusan produknya diambil di **batch 6o**:
  `PaperHandBackResolver.resolveStopLoss(...)` memakai `max(SL aktif mesin, SL tersimpan/lantai)`
  dan **mempertahankan** SL ≥ entry yang berasal dari mesin (BE/trailing terkunci). Jangan
  "memperbaiki" ini menjadi jatuh ke lantai friksi — itu melebar mundur (lihat aturan P9/6o).

## Aturan turunan dari audit koneksi — K8–K12, K15 (batch 6n, 2026-10-18)

- **Reset backoff hanya setelah sesi terbukti stabil.** Jangan pernah menulis
  `reconnectAttempt = 0` di `onOpen` socket mana pun. Server yang menerima lalu menutup koneksi
  (token ditolak, kuota) akan membuat siklus buka-tutup tanpa henti. Pakai `ReconnectBackoff`
  (`isStable`, `delayMs` berjitter ±20%, `shouldGiveUp` dengan jeda menyerah 5 menit). Penjaga:
  `RealtimeReconnectPolicyTest` + `ReconnectBackoffTest`.
- **Listener socket wajib punya penjaga generasi.** Nomor generasi naik di `stop()`/`reconnectNow()`;
  listener generasi lama harus diabaikan (`gen == generation`), kalau tidak socket usang bisa
  mengubah status koneksi atau mengacaukan tick pair yang sedang aktif. Penjaga:
  `RealtimeReconnectPolicyTest`.
- **Status "live" merupakan milik satu bursa, bukan global.** Jangan memakai satu flag
  `wsLive` untuk dua bursa; socket bursa non-aktif yang terputus tidak boleh menjatuhkan status
  bursa yang sedang dipakai. Simpan per bursa dan baca lewat penentu bursa aktif. Penjaga:
  `MarketDataLiveFlagTest`.
- **Pemindaian latar dan pengambilan ticker mengikuti lingkup pemanggil.** Worker hanya watchlist;
  `IndodaxMarketService.fetchTickers(list)` mengambil **per-pair** (`/api/ticker/<pair>`), dan
  `/api/summaries` (seluruh pasar) hanya untuk dashboard penuh (`fetchAllMarketTicks`). Menambah
  endpoint "seluruh pasar" ke jalur per-tick/per-10-detik adalah pemborosan data & baterai. Penjaga:
  `IndodaxTickerScopeTest`.
- **Nilai bawaan yang menyembunyikan kesalahan konfigurasi dilarang.** `getSymbolType` dulu
  mengembalikan `1` untuk simbol tak dikenal, sehingga kesalahan format pair mengarah ke host yang
  salah tanpa jejak. Kembalikan nilai eksplisit (`SYMBOL_TYPE_UNKNOWN = 0`) + log sekali per simbol.
  Penjaga:   `TokocryptoSymbolTypeUnknownTest`.

## Aturan turunan dari audit paper trading — P6 (sisa) & P9 (batch 6o, 2026-10-18)

- **Bar sintetis dari tick saja bukan pengganti bar M1 untuk `peak`.** Level atas mesin exit wajib
  melihat high intrabar lewat `PaperIntrabarPeakResolver.effectiveHigh(...)`: candle satu menit yang
  dibuka **setelah** `openedAt` posisi, cache wajib segar (≥ 20 bar / ≤ 180 dtk — ambang
  `PaperExitMomentumWindow`), high non-finite diabaikan, hasil `max(currentPrice, high eligible)`
  tidak pernah di bawah harga tick. `low` **tetap** `currentPrice` (SL/TP dieksekusi pada harga tick
  saat pemeriksaan berjalan). Jangan kembalikan `open = high = low = close = currentPrice` (cacat
  P6). Penjaga: `PaperIntrabarPeakResolverTest`.
- **Satu pembacaan cache M1 per evaluasi.** `evaluatePaperAutoExitLocked` membaca
  `MtfCacheManager.getCachedCandles` **sekali** untuk jendela momentum + peak; dua pembacaan pada
  tick yang sama bisa melihat isi cache yang berbeda. Penjaga: `PaperIntrabarWiringTest`.
- **Serah-terima exit saat runtime wajib mengisi SL, bukan hanya melepas mesin.** Transisi
  `autoExitEnabled true → false` di `updatePaperAutoTradeConfig` memanggil
  `handBackAllPaperEnginesToLegacy()`: `setAutoSellParams(enabled = stop.usable, stopLossPrice =
  stop.price)`, lepas penanda `_paper_engine`, hapus `_paper_fsm`, lepas mesin dari
  `PaperAutoTradeRuntime`. Melepas mesin tanpa ini = posisi tanpa stop (P9). Penjaga:
  `PaperHandBackWiringTest`.
- **Level serah-terima = `max(SL aktif mesin, SL tersimpan/lantai)` — jangan melebar mundur.**
  Trailing yang sudah terkunci (mis. 97,97 dari entry 100) tidak boleh jatuh ke lantai friksi 94.
  SL dari mesin yang sudah **≥ entry** (BE/profit terkunci) dipertahankan apa adanya — pengecualian
  sadar terhadap invarian `0 < SL < entry` (lihat aturan batch 3b). Penjaga:
  `PaperHandBackResolverTest`.
- **Serah-terima mencakup semua bursa; `clearAll` hanya saat kedua sakelar mati.**
  `PaperAutoTradeRuntime.activePositions()` mengembalikan pasangan (bursa, simbol) semua mesin;
  mematikan auto-exit harus melepas posisi di bursa mana pun. `PaperAutoTradeRuntime.clearAll()`
  hanya boleh saat `!autoBuy && !autoExit` — mematikan auto-buy saja tidak boleh membunuh mesin
  exit posisi terbuka.
- **Pemantauan latar posisi hasil serah-terima mengikuti aturan jalur legacy** (dievaluasi saat
  pair-nya dibuka, atau di latar bila trailing aktif) — tidak ada pemantauan latar khusus dari
  batch 6o. Jangan mengklaim lebih dari itu di log, UI, atau dokumen.

## Aturan turunan dari FEE_TRUTH v1.1 (batch 6p, 2026-10-19)

- **Tarif wajib mengikuti kuotasi pair begitu simbolnya diketahui.** `prefs.tradingFees` adalah
  **satu nilai global** dan bawaan Tokocrypto-nya adalah preset **USDT** (0,4044%) — memakainya
  untuk pair IDR membuat lantai SL 5,81% (harusnya 4,89%) dan net R:R engine berbeda dari jalur
  paper. Di titik mana pun simbol sudah diketahui, pakai **`prefs.feeConfigFor(symbol)`**
  (menghormati tarif kustom pengguna). Bila posisi bisa berasal dari bursa mana pun — seperti
  `TradingForegroundService` — pakai **`prefs.feeConfigFor(symbol, exchange)`**; membaca bursa
  aktif di sana membuat posisi bursa lain dinilai dengan tarif yang salah. Saat ini dijaga di:
  `LearningTradingEngine` (`feeConfigForSymbol`), `CandidateScanWorker`, `sellSignalState`
  (TradingViewModel, memakai `ctx.symbol`), `balanceSlice` (`TradingViewModelDetailState`), dan
  `TradingForegroundService` (`feeConfigFor(sym, ex)`). Penjaga:
  `app/src/test/java/agu/analys/viewmodel/FeeQuoteAwarenessSourceTest.kt`.
- **Poin data fee hanya boleh bercabang di `FeeSchedule` / `TradingFeeConfig`.** Baris yang menulis
  `0.4044` atau `0.0021` sendiri di luar `AppConfiguration.kt` adalah definisi tarif kedua yang
  akan melenceng diam-diam. (Pengecualian yang disengaja: `SimulationOrderEngine` — P&L
  terealisasi, basisnya memang terpisah.)
- **Tiga angka friksi tidak boleh saling menggantikan.** (1) **F aditif** = fee 2 sisi + slippage
  2 sisi = **0,9688%** (USDT) — inilah yang dipakai `ScalpingRiskEngine.costPct` dan
  `PaperStopResolver` untuk lantai SL. (2) **Impas net multiplikatif** = **0,9735%** — kenaikan
  harga yang benar-benar menutup biaya; dipakai kartu "impas" dan ambang P&L. (3) **P2L
  terealisasi** memakai basis sendiri. Kalau sebuah dokumen menyebut "friksi" tanpa menyatakan
  bentuknya, perlakukan sebagai belum diverifikasi. Penjaga:
  `app/src/test/java/agu/analys/config/FeeTruthConstantsTest.kt`.
- **Mirror replay = satu berkas, dikunci uji.** `data/config/scalp_config.json` adalah satu-satunya
  config yang boleh dibaca `tools/scalp_replay.py` (bawaan `--config`). Fee, `min_friction_multiple`,
  `target_net_rr`, `slippage_pct`, `max_risk_atr`, `max_change_24h_pct`, dan blok `exit` di
  dalamnya diperiksa uji terhadap `FeeSchedule` dan `ScalpingConfig`, jadi revisi aplikasi ikut
  memakai angka yang sama dengan replay. Jangan membuat berkas config kedua di `tools/`.
- **Replay wajib memakai exit yang sama dengan app, bukan subset.** CLASSIC punya time-stop
  90 bar M1 (`ScalpExitParams.DEFAULT`), dan mode `full`/`tp1_be`/`trail`/`early` harus ada di
  replay. Jangan menghidupkan kembali `order_type`/tarif maker: jalur app hanya taker. Jangan
  menghapus time-stop agar “trade selesai” terlihat lebih baik — itu justru menyembunyikan
  exit yang benar-benar dijalankan app. Penjaga: `tools/test_scalp_replay_parity.py`.
- **Replay pair IDR wajib memakai `fee.idr_reference`.** `resolve_fee` menggabungkan referensi
  IDR sebelum menghitung friksi; tanpa itu pair IDR dinilai dengan tarif USDT dan lantai SL
  melenceng. Konfigurasi IDR mandiri boleh memakai blok `fee` tingkat atas, tetapi referensi
  yang disebut tetapi tidak ada harus **gagal**, bukan diam-diam memakai tarif USDT.
  Penjaga: `tools/test_scalp_replay_parity.py`.
- **Replay harus bisa dijalankan dari keadaan diam.** Kalau `--config` menunjuk berkas yang tidak
  ada, keluarannya JSON berisi lokasi yang dicoba + saran perintah — bukan traceback. Perintah
  yang tertulis di dokumen harus benar-benar berhasil dijalankan dari repo bersih.

## Aturan turunan dari pengukuran Spec v2 (2026-10-20)

- **Fase 1 Kotlin Spec v2 diblokir sampai owner memutuskan.** Hasil terukur: produksi 15m
  **0 trade**, produksi 1h **7 trade semua rugi**, seluruh varian riset negatif (terbaik −0,2055R,
  OOS −0,3828). Jangan mulai menulis engine baru hanya karena specnya sudah ada. Keputusan yang
  harus diambil lebih dulu ada di `docs/LAPORAN-sinkronisasi-spec-scalping-spot-2026-10-20.md`
  §12.
- **Hasil harness Python adalah riset, bukan hasil produksi.** `tools/spot_backtest.py` bukan
  mesin Kotlin; Spec §11 mewajibkan mesin Kotlin + uji paritas. Jangan mengutip angka harness
  sebagai "hasil app" atau "hasil produksi".
- **TP1 formula `entry + 1,0R` tidak boleh dipakai apa adanya.** Secara aljabar ia selalu memberi
  `netRr = (R − F)/(R + F) < 1`, sehingga G4 menolaknya. Kalau spec memakai kebijakan `floor`,
  tulis eksplisit `entry + max(1,0R, R + 2F)`; jangan biarkan kontradiksi ini masuk ke kode.
- **Jangan menurunkan lantai 6× untuk menambah sinyal.** Varian tanpa lantai justru −0,3373R;
  varian lantai 4×–5× juga negatif. Lantai bukan satu-satunya penyebab kerugian, dan
  menurunkannya tidak menghasilkan edge.
- **Jangan memperbarui `PairEdgeGate` dari satu jendela tanpa keputusan owner.** MFE 90 hari
  memberi 4 pair (bukan 14). Memperbaruinya hanya mempersempit sampel; kalau tabel dipertahankan,
  ia harus dihitung berkala, bukan hardcoded sekali.
- **Angka MFE/backtest wajib menyebut timeframe, periode, dan jumlah sampel.** MFE lama M1 30 hari
  tidak sebanding dengan MFE baru M15 90 hari; 2,0× adalah ambang yang sama, tetapi datanya berbeda.
- **Uji mekanika sebelum percaya angka.** `tools/test_spot_backtest.py` mengunci SL-before-TP,
  TP1 60%, TP2 setelah TP1, stop tidak turun, time-stop, impas `FeeSchedule`, HTF tanpa lookahead,
  dan TP1 `floor` vs `literal`. Kalau mengubah exit, tambahkan uji di sana.

## Aturan turunan dari penghentian riset Spec v3 (2026-10-21)

- **V3 berhenti, bukan tertunda.** Owner menghentikan riset v3 setelah hasil ukurnya negatif:
  gates produksi **0 trade out-of-sample**, diagnostik tanpa G0 negatif, dan entry kalah dari
  acak. Jangan memulai ulang v3 hanya karena dokumennya masih tersimpan.
- **Commit v3 sudah dibuang dari `patch`.** `ada71dc` (Spec v3) dan `796a9d1` (harness v3 +
  dataset 180 hari) tidak ada di branch ini. Jangan menambahkan kembali berkas v3 ke `patch`.
- **Arsip v3 ada di branch backup, bukan di `docs/exp/`.** Branch
  `backup/patch-v3-2026-10-21` (`796a9d1`, remote) menyimpan seluruh artefak v3. Angka v3 yang
  dikutip tanpa membuka arsip itu adalah angka dari ingatan — jangan dipakai.
- **Acuan aktif tetap Spec v2 dan Fase 1 Kotlin tetap diblokir.** Kalau riset dilanjutkan,
  mulai dari branch backup atau branch baru dengan keputusan owner tertulis; jangan membangun
  ulang v3 dari dokumen v2.

## Arah produk (ringkas)

- Fokus: **scalp mode** + **paper trade** untuk menguji engine.
- Real money untuk strategi scalp: **belum**, sampai paper + fee truth stabil.
- Slider sensitifitas = ubah threshold (frekuensi sinyal), **bukan** klaim edge.
- Fee: **all-in** Tokocrypto/Indodax (Fee Truth) — jangan pakai model upstream p0 ~0,72% RT sebagai default tanpa label.
- Engine scalping di-port dari logika [dikigambol/kriptoyoi](https://github.com/dikigambol/kriptoyoi) `p0_engine` / spec — decision support, bukan bot profit terjamin.

## Perintah build & tes

- `chmod +x gradlew` bila perlu.
- Unit test: `./gradlew :app:testDebugUnitTest` (JDK 17).
- Scope scalping: `./gradlew :app:testDebugUnitTest --tests "agu.analys.engine.scalping.*"`.
- Build: `./gradlew :app:assembleDebug` / `assembleRelease`.
- MFE Spec v2 (15m, 41 pair):
  `python3 tools/spot_mfe.py --data data/tokocrypto_spot --out data/hasil/mfe_m15_spot.json`.
- Backtest Spec v2:
  `python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 15m --pairs all --setups all --tp1-policy floor --out data/hasil/spot_backtest_v2_m15_prod`
  (ganti `--timeframe 1h` untuk eksekusi 1h).
- Uji mekanika harness: `python3 tools/test_spot_backtest.py`.
- String `compile_applet` di dokumen lama **bukan** perintah repo — pakai `./gradlew` di atas.

## Secrets & environment

- Jangan commit `.env`, `*.keystore`, `*.jks`.
- `app/build.gradle.kts`: `.env` → `.env.example` → env → Gradle props. Tanpa `.env`, key AI boleh kosong.
- API key bursa: terenkripsi di Pengaturan app, bukan `.env`.
- Jika `EncryptedSharedPreferences` gagal: hapus MasterKey corrupt di AndroidKeyStore sebelum retry; fallback plaintext hanya last resort + log error.

## Batasan pasar (jangan dilanggar)

- **Tokocrypto: hanya USDT + IDR. Indodax: hanya IDR.** Jangan USDT di Indodax.
- Quote BIDR dieliminasi (kecuali koin bernama `…BIDR` seperti `BNBIDR`/`SHIBIDR`).
- **Dilarang** request ke `binance.com` / `stream.binance.com` (REST/WS) — diblok di Indonesia.
- Data historis riset boleh dari arsip publik (mis. data.binance.vision) **hanya** di tools offline, bukan di app runtime.

## Isolasi exchange & kuotasi

- Cache, SharedPreferences, Room: partisi per exchange (`${exchange}_…`, kolom `exchange`). Query DAO filter `exchange`.
- `OrderBookDepthCache.updateOrderBook()` default exchange menipu — **selalu** teruskan exchange eksplisit.
- Format harga/PnL mengikuti `quoteAsset` (Rp vs $).

## Engine scalping — aturan agen

- Threshold keputusan: pusat di `ScalpingConfig` (default) → target **`ScalpingRuntimeConfig`** (slider/JSON). Jangan hardcode angka gate di banyak file.
- **Lantai risiko berbasis friksi (2026-10-12, WAJIB):** jarak stop loss harus
  `≥ min_friction_multiple × (buyFee + sellFee + 2×slippage)`. Default **6,0**. Jangan turunkan
  di bawah 5 tanpa bukti replay baru — lantai 0,5% (perilaku lama) membuat satu kali SL menelan
  ~2,9R dan hasilnya **−1,8R/trade di 28/28 pair**. Bukti: `workplan2.md` §9.
- Mirror offline: `data/config/scalp_config.json` + `tools/scalp_replay.py` (keduanya sudah punya
  `min_friction_multiple`; kalau ubah satu, ubah yang lain).
- **Gerbang edge pair (2026-10-12, WAJIB):** pair hanya layak di-scalp bila median MFE horizon
  (7 jam ≈ 420 bar M1) `≥ MIN_MFE_FRICTION_RATIO × friksi round-trip`, default **2,0**.
  Dua garis berbeda: rasio **< 1,0** berarti pergerakan median lebih kecil daripada biaya →
  **mustahil** profit (BTC 0,52×, XAUT 0,20×); rasio **1,0–2,0** secara teori mungkin tetapi
  marginnya terlalu tipis untuk diandalkan (ADA 1,64×) — ditolak sebagai *buffer*.
  Implementasi: `engine/scalping/PairEdgeGate.kt` (logika + tabel data),
  `ui/components/settings/PairEdgeGateCard.kt` (UI), `tools/pair_gate.py` (regenerasi tabel
  dari data → `data/config/pair_gate.json`). Sumber tabel: `data/hasil/u3_toko_usdt_after.json`.
  **Jangan** melemahkan/menghapus gerbang ini hanya untuk menambah jumlah sinyal.
- **Status scalping = `ScalpReadiness` (2026-10-12, WAJIB):** seluruh permukaan UI mode
  scalping harus menyimpulkan status dari `engine/scalping/ScalpReadiness.kt`, **bukan** dari
  `signal.lifecycleState` / `signal.scalpingStage` mentah.
  Invarian yang tidak boleh dilanggar: bila `scalpingSetup` kosong atau `"NONE"`, status
  **tidak boleh** READY/CONFIRMING/"TERDETEKSI" — meski `lifecycleState == DETECTED` atau
  `scalpingStage == EARLY_ENTRY`. Permukaan yang sudah memakainya: `ScalpDeskCard.kt`,
  `PaperTradeDeskCard.kt`, `MarketConditionCard.kt` (cabang scalping). Bukti: `ScalpReadinessTest.kt`.
- **Invarian pola di engine (2026-10-13, WAJIB) — `EARLY_ENTRY` mensyaratkan setup.** Sejak
  perbaikan P1, invarian di atas **juga ditegakkan di engine**, bukan hanya di UI:
  1. `ScalpingMtfEvaluator` — `early` wajib disertai `setup != ScalpSetupType.NONE`; tanpa pola,
     stage turun ke `WATCH`. **Jangan** melemahkan syarat ini: `step2Ok` hanya berarti tekanan
     orderbook, bukan pola.
  2. `SignalLifecycleManager` (cabang `SCALPING`) — stage tanpa pola dinormalisasi ke `WATCH`
     (kecuali `HOLD`, yang tetap diturunkan) sehingga **tidak ada** jalur promosi ke
     DETECTED/CONFIRMING/READY tanpa pola. Predikatnya memakai `ScalpReadiness.hasSetup()` —
     satu sumber kebenaran dengan UI.
  Alasan historis: `EARLY_ENTRY` dulu dipromosikan ke `DETECTED` hanya berdasar tekanan orderbook
  (`ScalpingMtfEvaluator.kt:246` → `SignalLifecycleManager.kt:112`), sehingga log sinyal mencatat
  "DETECTED" untuk sinyal tanpa pola. Aman dilakukan karena `EARLY_ENTRY` selalu ber-`action = HOLD`,
  sehingga `tryPaperAutoBuy` dan alert (keduanya mensyaratkan `action == BUY`) tidak terpengaruh.
  Bukti: `workplan2.md` §13, `ScalpingMtfEvaluatorTest`, `SignalLifecycleManagerTest`.
- **~~Kesiapan multi-horizon~~ (DIBATALKAN 2026-10-13).** Aturan lama di sini sudah tidak berlaku
  karena fiturnya dihapus dari kode — lihat kotak "JANGAN BANGUN ULANG" di atas. Yang tersisa dan
  tetap dipakai: gate ekonomi berbasis `PairEdgeGate` (pergerakan ≥ 2× biaya round-trip, estimasi
  `ATR × √bar`) tetap menjadi kriteria yang diuji data untuk menyaring pair — lihat
  `workplan2.md` §9, bukan §14.
- **Status SIAP bukan ajakan beli.** Setiap permukaan yang menampilkan status siap
  (`ScalpReadinessState.READY`) wajib menyertakan konteks bahwa eksekusi uang riil masih diblokir.
- **Snapshot posisi paper per pair:** di halaman detail **jangan** pakai
  `TradingViewModel.paperExitSnapshot` (StateFlow tunggal yang di-`publish` dari tick pair mana
  pun). Pakai `TradingViewModel.paperSnapshotFor(exchange, symbol, price)` supaya yang tampil
  selalu posisi pair yang sedang dibuka.
- **Rehidrasi state machine paper (2026-10-13, WAJIB):** `PaperAutoTradeRuntime` **hanya hidup di
  memori**, sedangkan posisi & `auto_sell_enabled` persisten di `SpotPositionStore`. Setiap
  perubahan pada siklus hidup posisi paper wajib menjaga tiga hal ini tetap sinkron:
  1. penanda `_paper_engine` ditulis saat engine mengambil alih exit dan dilepas saat posisi
     ditutup (`markSold` / `finalizePaperExit` / posisi hilang);
  2. SL asli sinyal ditulis lewat `setAutoSellParams(stopLossPrice = ...)`. **Jangan** andalkan
     `_stop_loss` dari `setHolding` — nilainya sintetis `entry * 0.99` (≈1%), sedangkan SL engine
     minimal sebesar lantai friksi (≥ 5,8% USDT). Rehidrasi dengan SL sintetis akan **memalsukan
     stop-out** di jurnal;
  3. keputusan rehidrasi lewat `engine/scalping/PaperRehydrate.kt` (murni, teruji), dijalankan
     sekali dari `initSubscriptionsAndPolling()`.
  Posisi yang dibeli **sebelum** penanda ini ada sengaja tidak disentuh — SL-nya tersimpan salah.
- **Progres FSM paper ikut dipersistenkan (2026-10-15, WAJIB).** Sejak P4.4, `barsHeld`, `peak`,
  `activeStopLoss`, `isBeArmed`/`isTrailArmed`/`isPartialDone`, `partialR`, dan `atrPct` disimpan
  sebagai **satu JSON** di `SpotPositionStore` dengan key `<key>_paper_fsm`. Empat hal yang harus
  dijaga setiap kali menyentuh siklus hidup posisi paper:
  1. tulis lewat `TradingViewModel.persistPaperFsm(...)` (ber-throttle) — jangan tulis langsung,
     karena `evaluatePaperAutoExit` berjalan **per tick harga**;
  2. hapus key-nya di **setiap** jalur pelepasan kepemilikan: `markSold` (dua blok),
     `finalizePaperExit`, dan cabang "posisi sudah tidak ada" di
     `evaluatePaperAutoExitLocked`. Key yang tertinggal akan terbaca posisi berikutnya.
     **Jangan** bangun ulang `clearPaperExitState` — fungsi itu dihapus di batch 6e (P7) karena
     tidak pernah dipanggil dan, apa adanya, justru meninggalkan posisi tanpa exit sama sekali
     (lihat P9 di `docs/laporan_temuan_bugs_paper_trade.md`);
  3. `atrPct` **tidak** dipulihkan lewat `restore()` — ia parameter konstruktor mesin, jadi harus
     dioper saat mesin dibangun di `PaperRehydrate.decide()`. Kalau terlewat, ambang BE/trailing S8
     diam-diam jatuh ke nilai persen tetap;
  4. `barsHeld` dihitung dari **jam dinding** (`mergedBarsHeld`), bukan dari nilai tersimpan saja —
     replay offline menghitung bar M1 terus-menerus, jadi app harus setara. Konsekuensinya posisi
     yang jendela tahannya lewat selama app mati **langsung** kena time-stop saat dibuka.
  State tersimpan yang tidak lolos invarian (`PaperExitStateMachine.restoreRejection`) **dibuang**,
  bukan dipakai setengah: mesin tetap dibangun dengan `entry`/`SL` asli, hanya progresnya direset,
  dan alasannya dicatat di log. Bukti: `docs/LAPORAN-p44-persistensi-fsm-2026-10-15.md`.
- Profil exit yang benar saat ini **skew negatif** (menang ≈ +0,09R, kalah ≈ −1,16R, WR ≈ 95%).
  Margin impas hanya ~2,3 poin persentase — jangan promosikan ke uang riil. Bukti: `workplan2.md` §9.5b.
- Ubah parameter untuk eksperimen: export JSON → replay; jangan mengarang “edge” dari satu window data.
- Historical Edge palsu dilarang (`HistoricalEdgeStub` / pesan insufficient).
- **Trade `UNRESOLVED` tidak boleh dibuang dari statistik (2026-10-14, WAJIB).** `summarize()` di
  `scalp_replay.py` hanya menghitung `WIN`/`LOSS`; trade yang tidak menyentuh SL/TP/early/trail di
  dalam jendela *lookahead* dulu ditandai `UNRESOLVED` dan **dikeluarkan** dari `avg_r`/`win_rate`.
  Pada data 40 pair, cara itu membuang **±83% sinyal** dan membalik verdict dari TIDAK LOLOS
  menjadi LOLOS. Commit `703c3b6` sudah menutup trade di akhir jendela; **jangan** kembalikan
  perilaku lama. Setiap angka avg R yang dilaporkan **wajib** menyebut berapa sinyal yang tidak
  tuntas. Bukti: `docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`.
- **Setelan nominal auto-buy adalah Rupiah, dan anggaran wajib menyisihkan biaya (2026-10-15, WAJIB).**
  `PaperAutoTradeConfig.fixedIdrAmount` (dulu `fixedQuoteAmount`) berisi **Rupiah**, bukan kas
  kuotasi pair. Pada pair USDT nilainya **wajib** dikonversi lewat kurs `ExchangeRateManager` di
  `PaperAutoTradeConfig.plan()`. Dua cacat yang pernah terjadi bersamaan dan membuat auto-buy
  **tidak pernah** tereksekusi: (a) Rp 100.000 dipakai apa adanya sebagai $100.000; (b) batas
  anggaran memakai saldo mentah padahal order MARKET memotong `slippage` **dan** `fee` di atas
  nilai posisi, sehingga setiap nominal ≥ saldo mustahil tereksekusi. Ukuran yang tidak layak
  **dilewati** dengan alasan yang bisa dibaca — **jangan** dipotong diam-diam, dan **jangan**
  menebak ukuran saat kurs belum tersedia. Bukti: `docs/LAPORAN-bug-auto-buy-nominal-idr-2026-10-15.md`.
- **Auto-buy dibatasi jumlah posisi paper bersamaan (2026-10-15, WAJIB).**
  `PaperAutoTradeConfig.maxConcurrentPositions` (default **3**, `0` = tanpa batas) membatasi berapa
  koin yang boleh dipegang bersamaan. Hitungannya **global** (lintas bursa & kuotasi) dan memakai
  **semua** posisi paper — termasuk yang dibeli manual — lewat
  `SpotPositionStore.countActivePaperPositions()`. Gerbangnya ada di `tryPaperAutoBuy`, tepat
  **setelah** dedup per-pair dan **sebelum** pembacaan saldo; sengaja **tidak** dipasang di jalur
  beli manual (itu keputusan sadar pengguna). Alasannya aritmetis: kas yang sudah terpotong posisi
  yang masih hold tetap "terpakai", jadi tanpa gerbang ini auto-buy menumpuk posisi sampai order
  berikutnya ditolak karena saldo kurang — bukan karena sinyalnya buruk. Bukti:
  `docs/LAPORAN-batas-posisi-paper-2026-10-15.md`.
- **SATU rumus P&L untuk seluruh aplikasi (2026-10-15, WAJIB).** Semua perhitungan profit/loss
  floating harus lewat `object FeeSchedule` di `config/AppConfiguration.kt`:
  `netProfitPct` / `netProfitPctFor` (default **taker**), `costBasisNet`, `netSellValue`,
  `breakevenPrice`. **Jangan** menulis ulang rumusnya di layar atau engine lain. Sebelum
  penyeragaman ini ada **empat** rumus berbeda untuk hal yang sama — halaman portofolio bahkan
  memakai selisih harga **bruto tanpa fee sama sekali** — sehingga koin yang sama tampil berbeda
  sampai **0,81 poin persen** antar halaman dan pengguna membacanya sebagai bug. Basis yang
  disepakati adalah basis FSM (taker + slippage dua sisi, multiplikatif), karena auto-buy dan
  auto-exit adalah order **market**. Invarian yang dijaga uji:
  `(netSellValue / costBasisNet − 1) × 100` harus sama persis dengan `netProfitPct`.
  **Pengecualian:** P&L **terealisasi** (`SimulationTradeStore`, `SimulationOrderEngine`) memakai
  basis berbeda — itu laba yang sudah dikunci, jangan ikut diubah. Bukti:
  `docs/LAPORAN-seragam-pnl-basis-fsm-2026-10-15.md`.
- **Ambang yang membaca `netProfitPct` ikut bergeser bila basis biaya berubah (2026-10-15).**
  Dengan basis taker + slippage, ambang `> 0` butuh kenaikan harga ≈0,82% (dulu ≈0,33%),
  `>= 2.0` (badge PROFIT) ≈2,84% (dulu ≈2,34%), `>= 5.0` ≈5,86% (dulu ≈5,35%). Bila mengubah tarif
  atau slippage, **hitung ulang ambang ini** dan perbarui `SellSignalEvaluatorTest`.
- Exit scalp paper: lihat `SCALP_EXIT.md`; real auto-execute dari sinyal scalp **bukan** prioritas sampai gate edge terpenuhi.
- Commit non-rilis (docs/fee/config): sertakan **`[skip ci]`** jika owner meminta agar rilis tidak terpicu.

## UI & Compose

- Material 3; jangan pecah state chart tanpa alasan (lihat pola `DetailUiState` bila ada di branch).
- Throttle UI ticker/depth bila diminta di catatan performa Tokocrypto (hindari spam recomposition).
- **Mode halaman detail koin OTORITATIF dari status kepemilikan (2026-10-15, WAJIB).** Tidak ada
  lagi tab Beli/Jual manual: `resolveDetailTradeMode(isHolding)` di `model/PositionContext.kt`
  menentukan hold → **SELL**, tidak hold → **BUY**. Toggle Beli/Jual di
  `RadarTransactionFeeSection` **sudah dihapus** — jangan dibangun ulang, karena itulah yang
  membuat kartu beli dan jual tampil bersamaan. Menambah posisi (DCA) tetap bisa lewat tombol
  "Tambah posisi (DCA)" di cabang SELL, yang membuka dialog berisi `RadarBuySection` (form yang
  sama, supaya jalur eksekusi tidak bisa menyimpang). Bukti:
  `docs/LAPORAN-mode-detail-dan-hapus-backtest-2026-10-15.md`.
- **Kartu backtest di halaman detail SUDAH DIBUANG (2026-10-15).** `radar/BacktestSection.kt`
  dihapus beserta dua pemanggilnya (`WaitingEntryRadarCard`, `ScalpDeskCard`). Mesinnya
  (`engine/backtest/*`, `WalkForwardEvaluator`, `ScalpingBacktestAdapter`) **tetap ada** dan masih
  dipakai tooling/test — jangan ikut dihapus, dan jangan menampilkan baseline backtest di UI lagi.
- **Jangan menyembunyikan/menampilkan frame layar detail berdasarkan `isScalping` saja
  (2026-10-15, WAJIB).** Tampilan yang bergantung pada **status kepemilikan koin** harus lewat
  resolver murni di `model/PositionContext.kt`, bukan `if` langsung di layar. Presedennya:
  `resolveDetailScalpPanel(isScalping, isHolding)` menentukan hold → `EXIT_FSM`,
  tidak hold → `SCALPING_DESK`, scalping nonaktif → `NONE`. Bug §22 lahir justru karena
  `DetailChartScreen` memakai `if (isScalping)` tanpa melihat kepemilikan, sehingga mode beli dan
  jual terlihat identik. Setiap resolver semacam ini **wajib** punya uji + kontrol negatif.
- **Kosmetik animasi di Compose memakai komponen yang sudah ada** di
  `ui/animation/AppAnimations.kt` (`AnimatedMetricText`, `AnimatedPercentageBadge`,
  `rememberLivePulseAlpha` + `Modifier.livePulse`) dan **wajib menghormati**
  `LocalPriceAnimationMode` / `PriceAnimationMode.STATIC` sebagai katup hemat baterai pengguna.
  Jangan mengarang gaya animasi baru, dan jangan menambah sakelar hemat daya kedua.
  Bukti: `docs/LAPORAN-mode-sell-fsm-2026-10-15.md`.

## Penulisan dokumen Markdown

Dokumen dibaca owner di editor/HP, bukan hanya di terminal. Tiga hal yang pernah merusak
keterbacaan dan **tidak boleh terulang**:

- **UTF-8 tanpa BOM, akhir baris LF.** Jangan menyimpan `.md` sebagai UTF-16/ANSI atau CRLF.
- **Tidak boleh ada aksara CJK** (Mandarin, Jepang, Korea) di berkas mana pun. Aksara seperti itu
  biasanya masuk lewat tempel-mentempel dari hasil terjemahan atau editor lain, dan membuat
  kalimat terbaca sebagai "tulisan aneh". Ganti dengan kata Indonesia yang sebenarnya.
- **Tulis dengan istilah yang jelas, bukan jargon pinjaman.** Contoh pengganti: *seam* → titik
  sambung/tempat perakitan; *chokepoint* → satu-satunya pintu masuk; *uji menggigit* → uji gagal
  saat kode dirusak. Kalau satu kalimat perlu dibaca dua kali, tulis ulang.
- **Satu laporan per berkas temuan.** Ringkasan batch ditulis ke § Riwayat berkas laporan temuan
  (`docs/laporan_temuan_bugs_<topik>.md`) dan ke `workplan2.md` — bukan berkas baru.
- **Teks yang diekspor aplikasi ikut aturan ini.** Jurnal trading diekspor sebagai `.md`
  (`trading/TradeLogExporter.kt`). Jangan memakai simbol matematika di labelnya — simbol irisan
  sudah dibuang dari label "Skor Konfidensi" karena tampil seperti aksara CJK di sebagian font.

Periksa sebelum commit:

```sh
python3 tools/check_md_text.py
```

Skrip itu memeriksa BOM, CRLF, UTF-8 rusak, aksara CJK, dan sisa mojibake dari penyuntingan
sebelumnya.

## Git

- **Branch kerja sekarang: `patch`** (bukan `main`). Owner me-review di `patch` lebih dulu; `main`
  hanya menerima merge setelah review. Jangan push langsung ke `main` tanpa instruksi eksplisit.
- ⚠️ **Merge ke `main` wajib di-pull dulu.** `origin/main` hampir selalu **lebih baru** dari `main`
  lokal (PR di-merge via GitHub, bukan via merge lokal). Kalau langsung `git merge patch` di `main`
  lokal yang tertinggal, hasilnya **merge yang tidak menyertakan PR terakhir** — `main` Remote
  akan tertinggal satu round dan diff isinya tidak akan pernah sama dengan `patch`. Pola aman:
  ```sh
  git checkout main && git pull --ff-only origin main && git merge --no-ff patch
  ```
  Lalu **verifikasi**: `git merge-base --is-ancestor <commit-terakhir-origin/main> HEAD` → `ADA`,
  dan `git diff --stat HEAD patch` → **kosong**.
- **Naikkan versi hanya di `patch`**, lalu merge ke `main` — jangan naikkan versi dua kali
  (merge akan membawa inlandingan). `versionName`/`versionCode` di `app/build.gradle.kts`;
  `release.yml` membacanya dan membuat tag `v<versionName>` otomatis saat `main` menerima push.
- Branch lama di remote (`audit/menyeluruh-2026-10`, `engine_scalping_update`, tag versi)
  **sudah tidak ada** — per 2026-10-15 `git ls-remote --heads origin` hanya mengembalikan
  `main` dan `patch`. Jangan mencari branch itu lagi.
- Jangan commit artefak data candle besar tanpa diminta.
- Jangan commit `.env`, `*.keystore`, `*.jks`, atau token.
- Pesan commit jelas; `[skip ci]` untuk docs/config-only sesuai kebijakan owner.
- Clone di sandbox ini kadang dibatasi `remote.origin.fetch=+refs/heads/main:...`. Bila
  `git branch -r` terlihat hanya berisi `main`, **perbaiki refspec dulu** sebelum menyimpulkan
  bahwa branch lain tidak ada.

## Yang tidak boleh dilakukan agen

- Menghidupkan Binance di path app production.
- Longgarkan semua gate “supaya banyak sinyal” tanpa paper/replay log.
- Menulis di UI bahwa sinyal punya winrate/expectancy historis tanpa data cukup.
- Mengikuti checklist `checkpoint*.md` / `workplan.md` lama sebagai urutan kerja (ketiganya sudah dihapus 2026-10-16).
- Port auto-trading real dari state machine exit tanpa instruksi eksplisit owner + fee truth.
