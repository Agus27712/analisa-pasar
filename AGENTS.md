# AGENTS.md — analisa-pasar

Aplikasi Android Kotlin 100% (Jetpack Compose M3) untuk analisis & **scalp mode** kripto di **Tokocrypto** dan **Indodax**. Modul tunggal `:app`, package `agu.analys`, MVVM + Coordinator (`TradingViewModel` + MarketData/Position/Simulation/RealTradeCoordinator).

---

## ▶ MULAI DARI SINI (agen lanjutan — baca sebelum menyentuh kode)

### ⚠️ Langkah 0 — pastikan branch yang Anda baca benar

**Kerjaan aktif ada di `patch`.** Snapshot **2026-10-15** (tepat setelah **PR #60** → `7f890eb`):
**`patch` dan `main` identik secara isi** — `git diff HEAD origin/main` **kosong**; `main` hanya
unggul **2 commit merge** (PR #59 `bc48ac4`, PR #60 `7f890eb`), bukan commit konten. **Sejak itu tiap
commit baru mendarat di `patch` dulu, jadi `origin/main..HEAD` normalnya `> 0`** — itu wajar, bukan
tanda `main` ketinggalan. Klaim lama "`patch` 2 commit di depan" dan "`main` belum menerima
`f9e7fdd`/`56eb5cd`" **usang** — keduanya **sudah ada di `main`**. **Jangan pernah menyimpulkan
keadaan repo dari `main` tanpa mengecek angka ini dulu:**

```sh
git clone https://github.com/Agus27712/analisa-pasar.git
cd analisa-pasar && git checkout patch        # WAJIB — kerjaan aktif ada di sini
git rev-list --count origin/main..HEAD        # >0 = patch punya commit konten yang belum di main
git rev-list --count HEAD..origin/main        # >0 = main unggul (biasanya hanya commit merge)
git ls-remote --heads origin                  # sumber kebenaran daftar branch (bypass refspec)
git diff --name-status HEAD origin/main       # kosong = isi patch == isi main
```

Repo ini **public** — clone tidak butuh token.

⚠️ **Pernah terjadi, jangan terulang:** `main` pernah tertinggal **11 commit** dari `patch`.
Siapa pun yang membaca `main` saat itu melihat fitur horizon **masih ada** padahal sudah
di-revert di `patch` (`e525c64`), dan P0–P2 (integritas jurnal) serta S1–S9 (strategi
entry/exit) belum ada di sana — semua kesimpulan yang ditarik dari `main` jadi salah.

**Urutan baca 5 menit yang benar:**

1. **Berkas ini** sampai habis — terutama bagian "Yang tidak boleh dilakukan agen".
2. **`docs/PANDUAN-LANJUT-AGEN.md`** — status terkini, apa yang sudah selesai, apa yang berikutnya,
   dan perangkap yang sudah pernah ditemukan. **Ini titik masuk utama.**
3. **`docs/BELUM-DIKERJAKAN-2026-10-14.md`** — inventaris 27 item yang **belum** dikerjakan
   (temuan audit A/B, roadmap P4–P6, verifikasi lapangan), lengkap dengan titik kode dan
   prioritas. **Baca ini sebelum memilih apa yang akan dikerjakan**, supaya tidak mengulang
   yang sudah selesai atau mengerjakan yang sudah dibatalkan.
4. **`workplan2.md` § 13** — pekerjaan terakhir yang **masih berlaku**. Seksi sebelumnya
   (§9 lantai friksi, §11 UI scalping, §12 rehidrasi paper) menjelaskan **kenapa** kode hari ini
   berbentuk seperti sekarang. Jangan mengubahnya tanpa membacanya.
5. **`workplan2.md` § 14 dan § 15 — DIBATALKAN, jangan dibangun ulang.** Lihat kotak merah di
   awal kedua seksi itu. Keduanya mendokumentasikan fitur kesiapan multi-horizon yang sudah
   **dihapus dari kode** pada 2026-10-13. Dibaca hanya sebagai riwayat keputusan.
6. **`docs/bugs/`** — **folder temuan bug yang BELUM diperbaiki**, tiap berkas adalah audit
   terverifikasi-ke-kode dengan lokasi + baris + rekomendasi perbaikan. Mulai dari
   `docs/bugs/temuan_bugs_notifikasi.md` (audit jalur notifikasi & skor keyakinan, 2026-10-16).
   **Baca folder ini sebelum memperbaiki bug apa pun** di area yang tercakup — supaya tidak
   mengulang audit yang sudah ada, dan supaya tahu temuan mana yang masih hidup. Setelah sebuah
   temuan diperbaiki, **hapus/tandai berkas temuan itu** (lihat § "Folder temuan bug" di bawah).
7. `SCALP_EXIT.md` bila menyentuh exit.
8. `docs/LAPORAN-eksekusi-s1s9-2026-10-14.md` bila menyentuh entry/exit scalping — **dua
   keputusan di sana menyimpang dari daftar aslinya**: S9 dikirim **nonaktif** (ambang 25%
   terbukti menghapus seluruh trade GNS yang 4/4 menang) dan S2 **aktif** dengan default
   2× friksi, yang mengubah perilaku konfigurasi lama.
9. Baru buka kode.

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
5. **`docs/BELUM-DIKERJAKAN-2026-10-14.md`** — apa yang **belum** selesai (backlog terverifikasi
   ke kode). Mulai dari sini bila owner bertanya "apa lagi yang tersisa".
6. **`docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`** — 🔴 **baca ini sebelum memakai
   angka edge apa pun.** Menjelaskan kenapa angka "+0,0216 avg R / LOLOS" dari `u4` **tidak sah**
   (trade `UNRESOLVED` dulu dibuang dari statistik, ±81% sinyal) dan apa hasilnya setelah
   dihitung jujur (**avg R ≈ −0,17, 0/40 pair positif**).
7. **`docs/LAPORAN-eksekusi-*.md`** — jejak eksekusi terbaru: `p0p2` (integritas jurnal,
   2026-10-14) lalu `s1s9` (strategi entry/exit, 2026-10-14). Urutkan dari tanggal terbaru.

**DEPRECATED (jangan diikuti):** `workplan.md`, `checkpoint.md`, `checkpoint_progres.md`.

## Folder temuan bug (`docs/bugs/`)

**Kumpulan temuan audit bug yang BELUM diperbaiki.** Setiap berkas adalah hasil audit
read-only yang sudah **diverifikasi langsung ke kode** (path + nomor baris pada saat audit),
berisi peta jalur terkait, temuan kritis/serius/sedang, apa yang sudah benar (jangan
diutak-atik), dan rekomendasi perbaikan berprioritas.

Aturan main:

- **Sebelum memperbaiki bug** di area yang pernah diaudit, **baca berkas temuannya dulu** —
  supaya tidak mengulang audit, tidak menambal gejala, dan tidak menghapus bagian yang memang
  sudah benar.
- **Setelah sebuah temuan diperbaiki**, perbarui berkas temuan terkait: hapus temuan yang sudah
  beres atau tandai statusnya (mis. "✅ DIPERBAIKI di `<commit>`"), dan pindahkan jejak
  eksekusi ke `docs/LAPORAN-*.md` sesuai aturan "setiap perubahan wajib memperbarui markdown".
  Jangan biarkan daftar temuan berisi butir yang sudah tidak hidup — pembaca berikutnya akan
  membuang waktu memperbaiki ulang.
- Daftar berkas di folder ini bisa bertambah untuk area lain (exit, P&L, UI, dsb.) dengan
  pola penamaan `temuan_<area>_<topik>.md`.

Saat ini berisi:

- **`temuan_bugs_notifikasi.md`** (2026-10-16) — audit jalur notifikasi (4 pengirim: VM-sync,
  CandidateScanWorker, foreground service, VM-alerts) & alur skor keyakinan (produsen →
  notifikasi → jurnal → UI). Temuan kritis: notifikasi kandidat menampilkan confidence ×100
  (N1), dan balapan edge trailing yang bisa membuat auto-sell tidak pernah terkirim (N2).

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
     ditutup (`markSold` / `finalizePaperExit` / `clearPaperExitState` / posisi hilang);
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
     `finalizePaperExit`, `clearPaperExitState`, dan cabang "posisi sudah tidak ada" di
     `evaluatePaperAutoExitLocked`. Key yang tertinggal akan terbaca posisi berikutnya;
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

## Git

- **Branch kerja sekarang: `patch`** (bukan `main`). Owner me-review di `patch` lebih dulu; `main`
  hanya menerima merge setelah review. Jangan push langsung ke `main` tanpa instruksi eksplisit.
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
- Mengikuti checklist `checkpoint*.md` / `workplan.md` lama sebagai urutan kerja.
- Port auto-trading real dari state machine exit tanpa instruksi eksplisit owner + fee truth.
