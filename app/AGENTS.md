# AGENTS.md — aturan area `app/` (kode aplikasi)

Berkas ini dipisahkan dari `AGENTS.md` akar pada **2026-10-27** supaya aturan area tidak ikut
dimuat pada setiap permintaan. Isinya **salinan apa adanya** dari aturan yang dulu ada di akar —
tidak ada aturan yang dilunakkan di sini.

**Dibaca kapan:** setiap kali menyentuh `app/`, `engine`, paper trading, notifikasi, jurnal/P&L,
koneksi bursa, UI/Compose, atau FEE_TRUTH. Aturan kerja umum (bahasa, branch, dokumen wajib,
angka terukur, kontrol negatif) tetap di `AGENTS.md` akar.

---

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

## Engine scalping — aturan agen

- Threshold keputusan: pusat di `ScalpingConfig` (default) → target **`ScalpingRuntimeConfig`** (slider/JSON). Jangan hardcode angka gate di banyak file.
- **Lantai risiko berbasis friksi (2026-10-12; alasan diperbarui 2026-10-22, WAJIB):** jarak
  stop loss harus `≥ min_friction_multiple × (buyFee + sellFee + 2×slippage)`. Default **6,0**;
  jangan turunkan tanpa bukti replay baru. **Jangan mengutip klaim lama "0,5% → −1,8R/trade di
  28/28 pair":** R dihitung dari jarak SL, jadi perbandingan R antar-lantai bias ke SL lebar.
  Dasar yang berlaku sekarang adalah sweep **uang** (`docs/exp/exp_friction_multiple_ADA-DOGE-XRP.md`,
  alat `tools/friction_sweep.py`, 3 pair/60 hari, gate P0 + exit sama): **semua kelipatan 1–8
  rugi**; 3,0 sedikit lebih baik dari 6,0 (−0,993 vs −1,078 %/hari) tetapi tetap −1%/hari.
  **Default 6,0 dipertahankan sementara tanpa klaim optimal atau lebih aman:** pada sampel ini
  DD 6× (−79,2%) malah lebih buruk dari 3× (−71,7%) dan kerugian hariannya sedikit lebih besar;
  alasannya hanya **belum cukup bukti** untuk mengubah bawaan (selisih +0,085 %/hari, sampel
  3 pair/60 hari). Keputusan konfigurasi baru menunggu hasil entry/universe. Kelipatan
  0,5 tidak sah diuji: `tp2/risk` melampaui `max_tp2_r` dan sinyal ditolak (3–10 trade/60 hari).
  Bandingkan kandidat dengan **net %**, bukan avg R.
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
- Commit docs/config tidak wajib `[skip ci]` lagi, karena rilis dan unit test sudah manual.

## P0 KriptoYoi — TTL, MTF, BTC, regime (2026-10-22, WAJIB)

P0 sudah disinkronkan ke engine yang ada. Ini **bukan** engine baru dan **bukan** klaim edge.
Sumber semua angka: `ScalpingRuntimeConfig` (default di `ScalpingConfig`), mirror replay di
`data/config/scalp_config.json`. Jangan menulis angka gate P0 sebagai literal di engine/replay.

- **TTL 5 candle M1.** `SignalTtl` menghitung umur dari jam dinding (`elapsed_sec // 60`), bukan
  jumlah tick. `EXPIRED` **terminal** untuk setup + arah yang sama; setup yang hilang boleh
  memulai episode baru. Jangan mengembalikan perilaku lama yang bisa langsung menaikkan setup
  basi ke `DETECTED` lagi.
- **Gate MTF 4 TF.** H1, M15, M5, M1. H1/M15 **tidak boleh bearish**; minimal
  `mtfMinBullish` (default 3) bullish; **fail-closed** bila `knownCount < 4`. Gerbang dipasang
  setelah Step4, dan confidence non-ready di-cap **39** saat gate menolak. Pair dengan M5 belum
  siap **tidak boleh** dievaluasi; jangan melonggarkan fail-closed agar sinyal bertambah.
- **Veto BTC.** Veto LONG altcoin bila close 5M di bawah EMA20 **dan** return 5M terburuk
  < `btcReturnVetoPct` (−0,6%), atau ATR ratio > `btcAtrRatioVeto` (2,5×) dengan minimal 2/3
  candle bearish. Hanya candle **tutup**. Pair BTC sendiri tidak diveto. Harga BTCIDR **tidak
  boleh** ditulis ke `btcPriceUsdt`; status veto tetap boleh dihitung dari kuotasi bursa aktif.
  Jangan menghidupkan veto dari data yang belum tutup.
- **Regime OR.** Urutan: LOW_VOL (ATR rendah + CHOP > `chopTrendMax`), BREAKOUT, trending
  (`ADX ≥ adxTrending` dan `CHOP < chopTrendMax`), choppy (`CHOP ≥ chopRanging` **atau**
  `ADX < adxWeak`), lalu mild trend. `BREAKOUT` adalah ekstensi app; p0 belum mengembalikannya.
  Semua ambang dari config, bukan literal.
- **Replay.** `tools/scalp_replay.py` mengagregasi M5 dari M1, memakai BTC 5M/1M dari CSV
  (hanya candle tutup), dan menulis `gate_blocks`, `btc`, `p0`, `btc_source`. `gate_blocks`
  menghitung **bar sinyal siap**, bukan trade yang hilang. Tanpa CSV BTC, output wajib menulis
  `btc_source.available=false` dan veto tidak diukur.
- **TTL tidak diuji replay.** Replay masuk pada bar sinyal; TTL mengatur penantian setup di app.
  Jangan mengutip hasil replay sebagai bukti manfaat TTL.
- **Hasil terukur (2026-10-22, ADA/DOGE/XRP, 60 hari):** 620 trade, WR 14,84%, avg R **−0,1569**,
  **616 TIMEOUT**, 4 SL, **0 TP1/TP2**. Baseline 996 trade, WR 12,85%, avg R −0,1642. Gate
  bekerja dan frekuensi turun, tetapi **edge belum ada**. Jangan promosikan ke uang riil dan
  jangan menambah gate entry baru tanpa instruksi owner.
- **Riset exit-only ditutup lagi (2026-10-24)**: 0 dari 88 varian exit positif
  (`exp_exit_net_tp_ADA-DOGE-XRP.md`), 0 dari 27 kombinasi kelipatan friksi
  (`exp_friction_multiple_ADA-DOGE-XRP.md`), dan **0 dari 90 sel** sweep 5 mode
  (`exp_exit_mode_sweep_ADA-DOGE-XRP.md`). Prioritas berikutnya **pengukuran entry/universe**
  (potongan sinyal P0), bukan gate baru dan bukan utak-atik exit. TP1 (+7,99%) dan TP2 (+14,53%)
  tidak pernah tercapai dalam time-stop 90 menit.
- **Persentil entry (2026-10-24, diagnostik):** entry khas di persentil 93,3 harga 1 hari dan
  81,7% rentang harian (`exp_entry_price_percentile_ADA-DOGE-XRP.md`). "Beli di pucuk" benar
  secara posisi, tetapi **bukan** izin menambah filter/gate entry tanpa pengukuran tersendiri.

Rincian angka: `docs/exp/exp_p0_gatekeeper_ADA-DOGE-XRP.md`; ringkasan keputusan:
`workplan2.md` §65, §70–§71. Penjaga: `SignalTtlTest`, `MtfAlignmentPolicyTest`,
`BtcPulseAnalyzerTest`, `ScalpingMtfEvaluatorTest`, `MarketRegimeEngineTest`, dan
`tools/test_scalp_replay_parity.py` (30 test).

## UI & Compose

- **Default timeframe chart mengikuti mode strategi (2026-10-26, WAJIB).** Keputusan
  timeframe chart default hanya lewat resolver murni `resolveDefaultTimeframe(mode)`
  (`model/PositionContext.kt`): SCALPING → M15, INTRADAY → H1, SWING → D1 — jangan
  hardcode pemetaannya di UI/ViewModel lain. Dipasang di dua titik: `setStrategyMode`
  (hanya saat mode benar-benar berganti) dan startup (`initSubscriptionsAndPolling`,
  dari `prefs.strategyMode`). Bawaan `Timeframe.H4` di `MarketViewModel` bukan default
  mode mana pun. Chip tetap bebas dipilih manual; pilihan user tidak persisten antar
  perpindahan mode. Penjaga: `DefaultTimeframeResolverTest`.
- **Form beli paper TANPA nominal default (2026-10-26, WAJIB).** Nominal beli paper
  selalu milik user — tidak ada literal default (dulu `50000.0` dipakai apa adanya
  sebagai nominal kuotasi: Rp 50.000 menjadi $50.000 pada pair USDT, pola sama dengan
  §18.2). Keputusan nominal lewat resolver murni `resolveBuyNominalGate`
  (`util/BuyNominalGate.kt`: EMPTY/BELOW_MIN/OVER_BALANCE/OK) di tombol; jangan
  potong/naikkan nominal diam-diam (`coerceAtLeast` dilarang), tolak dengan alasan
  yang bisa dibaca. State nominal wajib ber-key `(baseAsset, quoteAsset)`. Saldo 0
  dihitung saldo kosong yang diketahui (fail-closed); engine order tetap gerbang
  terakhir. Nominal awal dialog limit juga mulai kosong. Chip cepat (% saldo /
  rekomendasi risiko) boleh — itu aksi eksplisit user. Penjaga:
  `BuyNominalGateTest`, `PaperBuyNominalSourceTest` (source-scan tanpa komentar).
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

## Fase 1 auto-order paper (2026-10-24)

Fase 1 integritas eksekusi paper sudah diterapkan di `patch` (commit `86a1f72`):
`PaperStopResolver.resolveForEntry`, auto-buy memakai harga/qty fill + SL hasil resolver,
kepemilikan exit tunggal via `PaperAutoTradeRuntime.ownsExit`, exit gagal tidak melepas FSM/posisi,
dan SELL paper hanya `quantity.coerceAtMost(simBal)`. Test regresi ada di
`PaperStopResolverTest` dan `PaperAutoBuyExecutionWiringTest`. Verifikasi:
GitHub Actions `all-unit-tests` sukses run `40099665592`, parity 30/30, `check_md_text.py` bersih.
Kode Fase 1 sudah masuk `main` lewat PR #64. Rincian: `workplan2.md` §72.

## Jeda entri-ulang auto-buy paper (2026-10-26, WAJIB)

**Masalah yang pernah terjadi:** setelah markSold, tryPaperAutoBuy hanya mencegah posisi
dobel — begitu sinyal BUY berikut datang, beli ulang langsung terjadi. Terukur pada
replay WINUSDT (2026-10-26): **3 dari 10 entri berjarak 60 detik dari exit sebelumnya**.
Dua friksi round-trip dibayar beruntun tanpa evaluasi baru.

Aturan yang mengikat agen:

1. **Logika jeda tinggal di `PaperReentryCooldown.verify(...)`** (murni, teruji). Jangan
   menghitung ulang jeda inline di call site baru — resolvernya diacu, bukan disalin.
2. **Angka jeda disimpan di config, bukan literal runtime:** `PaperAutoTradeConfig.
   reentryCooldownMinutes` (default **15**, `0` = nonaktif) + kunci prefs
   `paper_auto_reentry_cooldown_min_v1`. UI: stepper **1–30 menit** di kartu
   "Paper Trade Otomatis" (`PaperAutoTradeSettings`, seksi JEDA ENTRI-ULANG, 2026-10-26).
   Mengubah bawaan = pilih satu tempat + uji.
3. **Catatan sell wajib pada satu titik: `SpotPositionStore.markSold` (isReal=false)** —
   key per bursa `…_sim_…_last_sold_at`, persisten, dibaca `lastPaperSoldAt`. Selama jeda,
   auto-buy ditunda dengan log `⏳ … jeda entri-ulang …`, tetapi beli manual dan semua
   jalur uang riil tidak digated.
4. **Jeda menutup siklus paper, bukan menciptakan edge.** Angka edge (avg R −0,1569 dll.)
   tidak berubah akibat hal ini; angka replay CLI dihitung tanpa jeda — jangan mengutip
   hasil replay sebagai demonstrasi manfaat jeda.
5. Penjaga: `PaperReentryCooldownTest`, `SpotPositionStorePaperLastSoldTest`,
   `PaperAutoBuyReentryCooldownWiringTest` (uji wiring — kontrol negatifnya sudah
   diprovokasi gagal pada 2026-10-26).

## Mode INTRADAY (`OFFICE_DAILY`) — paket A+B (2026-10-27, WAJIB)

Mode intraday **bukan** scalping: ia punya evaluator, replay, dan config sendiri, dan **belum punya
slider**. Temuan lengkap: `docs/bugs/temuan_bugs_intraday.md`; ringkasan keputusan: `workplan2.md`
§79. Aturan yang mengikat:

1. **Config sendiri, tanpa state global.** Ambang intraday ada di
   `engine/intraday/IntradayConfig.kt` dan dioper **eksplisit** oleh pemanggil. Jangan menumpangkan
   parameter intraday ke `ScalpingRuntimeConfig`/slider: geseran satu slider scalping akan
   diam-diam mengubah sinyal intraday. Jangan menambah `object … current` yang bisa diubah runtime
   sebelum ada UI-nya (parameter tanpa pembaca dilarang).
2. **Net R:R dari TP1, tanpa clamp.** `netRrTp1 = FeeCalculator.roundTrip(… takeProfit = TP1 …).netRr`
   dan gate memakai `config.minNetRrTp1` (bawaan 1,5). **Jangan** mengembalikan `coerceAtLeast(1.6)`
   atau memakai TP2 untuk gate: keduanya membuat gate selalu lolos dan angka R:R yang tampil bukan
   yang bisa dieksekusi. Penjaga: `IntradayAccountingAndSessionTest`.
3. **Konsekuensi yang harus disebut jujur.** Geometri sekarang (SL ≥ −1,5%, TP1 ≤ +2,8%) memberi
   net R:R TP1 ≈**0,87** pada biaya Indodax IDR (impas net 0,7940%; multiplikatif; Tokocrypto IDR
   ≈0,85, Tokocrypto USDT ≈0,74), jadi **sinyal BUY intraday nol**. Sampai paket C selesai,
   **sinyal intraday jangan dipakai sebagai dasar keputusan**. Jangan "memperbaiki" dengan
   menurunkan ambang; perbaikannya ada di geometri (paket C) dan menunggu instruksi owner.
   (Koreksi 28 Okt 2026: angka lama "0,74 di Indodax IDR" keliru — 0,74 itu USDT. Periksa sejarah
   di `workplan2.md` §80.)
4. **Replay wajib net, dan wajib fee eksplisit.** `IntradayReplayEngine.replay(feeConfig = …)` tidak
   punya nilai bawaan dengan sengaja — `TradingFeeConfig()` tanpa argumen adalah preset legacy
   Indodax-ish. P&L memakai `FeeSchedule.netProfitPct` (taker + slippage dua sisi).
5. **Menang/kalah net dan partisinya lengkap.** `validEntries = trades.count { isWin(it.netPnlPct) }`
   dan `falseSignals = trades.size − validEntries`; profit factor net. `outcome` hanya mencatat
   peristiwa (TP/SL/close/ambiguous/unresolved) — **jangan** dipakai untuk menghitung win rate.
   Dulu `AMBIGUOUS` tidak pernah dihitung dan Close Malam dinilai gross, sehingga laporan bisa
   menulis Win Rate 100% di atas rata-rata net negatif.
6. **Fase sesi setengah-terbuka dan jam sekarang.** `[mulai, selesai)`: 11:30 Siang, 15:30 Sore,
   19:30 Close Malam, 23:30 Istirahat. Evaluasi memakai `nowMs` (jam sekarang); replay memakai
   `timestamp buka + durasi bar`. Memakai timestamp buka candle menggeser fase ≈4 jam pada H4.
7. **Klaim umur flash dump memakai jumlah candle, bukan "72 jam".** `flashDumpTraumaCandles` = 12
   candle (≈48 jam pada H4, ≈12 jam pada H1). Teks UI/dokumen/laporan wajib menyebut angka yang
   sama dengan kode.
8. **`macdHist >= -0,002` masih perlu diputuskan (paket C).** `macdHist` bersatuan harga, bukan
   persen, sehingga ambang itu praktis berarti "histogram tidak negatif". Jangan mengubahnya
   diam-diam; normalisasi atau penghapusan ambang menggeser frekuensi sinyal dan harus diukur.
   Arahan owner (28 Okt 2026): **ditangani di paket C** — unit diperbaiki lalu dampaknya diukur,
   jangan dibiarkan.
9. **Penjaga intraday harus uji perilaku, bukan pindai sumber (2026-10-28).** Uji
   `sumber.contains("...")` hanya menebak dari nama variabel — nama berubah, uji gagal, perilaku
   tidak terbukti. Penjaga intraday memakai dataset ter-commit `data/tokocrypto_v7/DOGEUSDT_1h.csv`
   (999 bar) lewat `IntradayReplayEngine.replay`: ambang config 0,0 vs 99,0, fee maker + slippage
   dari config, ringkasan net dihitung ulang dari daftar trade, dan konsistensi label `VALID_ENTRY_*`
   dengan klasifikasi net. `IntradayConfig` tetap tanpa slider/state global (aturan 1).
   **`ModeSignalEvaluator` menolak `nowMs <= 0` fail-closed** (null, deterministik) — jangan
   mengembalikan fallback `System.currentTimeMillis()`.
