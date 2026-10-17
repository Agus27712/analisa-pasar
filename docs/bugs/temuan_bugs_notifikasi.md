# Temuan Audit — Jalur Notifikasi & Skor Keyakinan

**Audit:** 2026-10-16 (dilanjutkan hari yang sama) · **Status disinkronkan:** 2026-10-16 (verifikasi
ulang ke kode + review owner)
**Isi dokumen:** **hanya temuan yang MASIH hidup.** Temuan yang sudah ditutup dipangkas dari badan
dokumen dan diringkas di §4 (dengan pointer ke laporan eksekusinya).
**Verifikasi terakhir:** branch `patch` @ `547048d`, dibaca ulang langsung ke kode pada 2026-10-16
dan **sudah direview owner**. Status di bawah **otoritatif — agen tidak perlu cek kode lagi** untuk
memastikannya; cukup baca tabel ini. Nomor baris refers ke `547048d`.
**Cakupan:** seluruh pengirim notifikasi + seluruh alur `confidence` (produsen → notifikasi → jurnal → UI)

| Status | Temuan | Ringkas |
|---|---|---|
| 🟠 **Hidup** | **N6 + N17** | Dua produsen menulis satu `SignalLifecycleManager`, dan skor worker **tidak pernah** kena penalti anomali makro |
| 🟠 **Hidup** | **N15** | Sakelar master mematikan foreground service (= keep-alive pemantauan posisi) |
| 🟠 **Hidup** | **N16** | `AppPreferences` dibangun ulang 7× di service, termasuk per tick dashboard |
| 🟠 **Hidup sebagian** | **N11** | Manifestasi J6 (klaim hardcoded) tertutup batch 5e; **tabel tier & cap confidence belum diubah** |
| 🟡 **Hidup** | **N12** | Tidak ada cooldown per-pair untuk notifikasi kandidat |
| ✅ Ditutup | N1–N5, N7, N8, N9, N10, N13, N14 | Lihat §4 |

## 1. Peta jalur (hasil pemetaan)

```
PRODUSEN CONFIDENCE
├─ engine/scalping/ScalpingMtfEvaluator.kt:434  — conf = min(score, cap 59/49/39/29); ready → score.total
├─ engine/swing/SwingEvaluator.kt:538           — conf = finalScore (− anomalyPenalty, s.d. −60)
└─ engine/intraday/IntradayEvaluator.kt:372-378 — conf = baseScore × regimeMultiplier

JALUR NOTIFIKASI (4 pengirim)
├─ A. viewmodel/TradingViewModelSync.kt:591  onCandidateSignalTransition → sendCandidateFoundNotification (foreground)
├─ B. service/CandidateScanWorker.kt         periodic 15 menit → sendCandidateFoundNotification (background, 3 mode)
├─ C. service/TradingForegroundService.kt    loop 4 detik → emergency/trailing/TP via SellSignalLifecycleManager
└─ D. viewmodel/TradingViewModelAlerts.kt    per tick → price alert, trailing hit, peak update, hasil auto-sell

KONSUMEN CONFIDENCE
├─ database/SignalLogRepository.kt  (B5: conf = nilai saat firing, sudah benar)
├─ database/TradeHistoryRecorder.kt → jurnal (confidenceScore dari snapshot)
├─ ui/components/detail/RadarCheckpointStepper.kt (bobot 30% progress), RadarBuySection.kt (faktor ukuran posisi)
└─ ui/screens/SignalLogScreen.kt (tier ≥80 / 60-79 / <60), ui/components/dashboard/DashboardScreen.kt:228 (≥55)
```

## 2. 🟠 Temuan serius (masih hidup)

### N6 — Dua produsen menulis satu mesin lifecycle dengan data kualitas berbeda

> 🔎 **Diverifikasi ulang 2026-10-16 @ `547048d` — masih hidup.** `SignalLifecycleManager.process`
> dipanggil dari `CandidateScanWorker.kt:133` (SWING), `:164` (INTRADAY), `:217` (SCALPING) **dan**
> dari `LearningTradingEngine.kt:282` (SCALPING), `:326` (SWING), `:370` (OFFICE_DAILY) — satu
> object singleton yang sama.

**Lokasi:** `service/CandidateScanWorker.kt` vs `engine/LearningTradingEngine.kt`

`CandidateScanWorker` memanggil `SignalLifecycleManager.process` untuk **SWING, INTRADAY, dan
SCALPING** (tanpa anomali makro untuk swing, `formingVolume = 0`, tanpa orderbook untuk scalp
dengan `diagnosticIgnoreOrderBookWhenUnavailable = true`), berbagi **object singleton yang sama**
dengan engine foreground.

Bila mode aktif user adalah SWING, worker 15-menit dan engine per-tick **keduanya** memajukan
state `symbol#SWING` dengan confidence yang berbeda → transisi `isNewDetected`/`isNewReady` bisa
terpicu dua kali atau dari data yang lebih lemah → notifikasi ganda/keliru.

**Untungnya** invarian P1 tetap terproteksi: `hasTriggeringTransition` mensyaratkan
`action == BUY`, dan `EARLY_ENTRY` mensyaratkan `setup != NONE`, jadi tidak ada notifikasi
kandidat tanpa pola — meski worker memakai `diagnosticIgnoreOrderBookWhenUnavailable`.

**Perbaikan (salah satu):**
1. `CandidateScanWorker` hanya mengevaluasi mode yang sedang aktif (`prefs.strategyMode`);
2. Atau beri instance `SignalLifecycleManager` terpisah khusus worker (state tidak dicampur).

> 📌 **Lanjutan 2026-10-16 — lihat N17:** dampaknya lebih konkret dari dugaan awal. Worker SWING
> **tidak pernah** mengirim `macroAnomalyResult` ke `SwingEvaluator`, sehingga skor yang ia tulis
> ke key yang sama dengan engine **tidak pernah** kena penalty anomali.

## 2b. Temuan dari audit lanjutan 2026-10-16 (semua masih hidup)

Semua diverifikasi ke kode pada `patch` @ `a842e35`, lalu **diverifikasi ulang di `547048d`** pada
2026-10-16 (status tidak berubah). Nomor baris di bawah memakai kondisi **setelah** batch 2.

### N13 — Kategori notifikasi ≠ sakelar di Pengaturan: hasil eksekusi jual & pembaruan trailing tunduk pada sakelar "Trading & Price Alerts"

> 🟠 **SERIUS.** Gerbang tunggal N4 sudah benar, tapi **kategori yang dipilih di call site salah**.

**Lokasi:**
- `util/AlertNotificationHelper.kt:602` — `sendPriceAlertNotification` memakai
  `NotificationCategory.PRICE_ALERT`
- pemanggil yang seharusnya `TRAILING_STOP`:
  `viewmodel/TradingViewModelAlerts.kt:355` ("🛡️ Cut Loss Terlaksana" / "✅ Aset Diamakan" / "❌ Gagal Jual"),
  `:404` (varian simulasi), `:503` ("🛡️ Trailing Stop Aktif"), `:547` & `:567` ("📈 Trailing Stop Naik"),
  `:608` & `:664` ("✅ Trailing Sell/Limit Sell Terlaksana" / "❌ Gagal"), `:620` ("⚠️ Jual dibatalkan")
- peta kategori yang sekarang: `AlertNotificationHelper.kt:211` (CANDIDATE_BUY), `:280` (EMERGENCY_EXIT),
  `:372`/`:446`/`:514` (TRAILING_STOP), `:602` (PRICE_ALERT)

```kotlin
// TradingViewModelAlerts.kt:547-554
AlertNotificationHelper.sendPriceAlertNotification(
    context = getApplication(),
    title = "📈 [SIMULASI] Trailing Stop Naik • ${marketKey.formattedPair()}",
    ...
    notificationId = marketKey.toNotificationId(false, 1000),
    marketKey = marketKey,
    onlyWhenBackground = true
)
```

**Dampak — dua arah, keduanya merusak kontrol user:**
1. Mematikan sakelar **"Trailing Stop & Eksekusi"** **tidak** mematikan notifikasi trailing —
   "📈 Trailing Stop Naik" dan "✅ Aset Diamakan" tetap berbunyi. Sakelar yang diklik tidak berlaku.
2. Mematikan sakelar **"Trading & Price Alerts"** mematikan **konfirmasi bahwa order jual uang riil
   berhasil atau gagal** — informasi paling kritis dan paling jarang muncul. User tidak pernah tahu
   ordernya gagal.

**Test penjaga:** tidak ada, dan `util/NotificationGateTest.kt` **tidak bisa** menangkapnya karena
hanya mengirim `sendPriceAlertNotification` (PRICE_ALERT) dan `sendTrailingHitNotification`
(TRAILING_STOP) — jalur yang bermasalah justru tidak diuji.

**Perbaikan.** jangan gunakan helper generik untuk laporan eksekusi: tambah helper
mis. `sendExecutionResultNotification` dengan kategori `TRAILING_STOP` (sesuai label Pengaturan),
dan pindahkan semua call site trailing/eksekusi ke sana.

### N14 — Cooldown 30 detik notifikasi trailing-peak di-bypass oleh jalur kembar yang mengirim lebih dulu

> 🟠 **SERIUS.** Dua jalur notifikasi untuk **peristiwa yang sama**.

**Lokasi:** `viewmodel/TradingViewModelAlerts.kt:205-239`, `:537-555`, `:557-575`

```kotlin
// TradingViewModelAlerts.kt:209-238
if (newSlPrice > oldSlPrice) {
    refreshSpotPosition()
    if (isReal) {
        updateRealTrailingOrder(symbol, updatedPos, newSlPrice, exchange = exchange)   // ← LANGSUNG KIRIM
    } else {
        updateSimTrailingOrder(symbol, updatedPos, newSlPrice, updatedPos.quantity, exchange = exchange)
    }
    ...
    if (now - lastAlertTime > 30_000L) {          // ← BARU DI SINI ADA COOLDOWN
        lastPeakNotificationTimes[peakKey] = now
        AlertNotificationHelper.sendTrailingPeakUpdateNotification(...)
    }
```

`updateRealTrailingOrder`/`updateSimTrailingOrder` (`:537-575`) mengirim notifikasi **tanpa cooldown
apa pun**, dan dipanggil **sebelum** gate 30 detik. `AlertNotificationHelper` tidak memanggil
`setOnlyAlertOnce(true)` (grep nihil), jadi `notify()` dengan ID yang sama tetap berbunyi ulang.
Polling trailing jalan tiap 10 detik (`TradingViewModelSync.kt:639`).

**Dampak.** Selama trailing aktif dan harga naik, user menerima bunyi **setiap ~10 detik** selamanya.
Cooldown 30 detik yang tercatat "sudah benar" di §5 dokumen ini **tidak berlaku** untuk notifikasi
yang benar-benar berbunyi. Keduanya juga memakai ID berbeda (offset `1000` vs `400`), jadi muncul
sebagai dua entri terpisah di shade.

**Perbaikan.** satu event, satu pengiriman (gabungkan ke `sendTrailingPeakUpdateNotification`),
atau terapkan cooldown yang sama di kedua jalur lewat satu helper.

### N15 — Sakelar master "push notifikasi" mematikan foreground service, yaitu keep-alive pemantauan posisi

> 🟠 **SERIUS.** Melanggar aturan turunan N4: *"Mematikan notifikasi TIDAK boleh mematikan eksekusi."*

**Lokasi:**
- `viewmodel/TradingViewModelSync.kt:452-465`
- `service/TradingForegroundService.kt:579-582` (`startService`), `:594-596` (`forceRefresh`),
  `:617-619` (`updatePrice`), `:636-638` (`updatePrices`)

```kotlin
// TradingViewModelSync.kt:460-465
if (hasActive && isNotificationsEnabled.value) {
    agu.analys.service.TradingForegroundService.startService(getApplication())
    agu.analys.service.TradingForegroundService.forceRefresh(getApplication())
} else {
    agu.analys.service.TradingForegroundService.stopService(getApplication())
}
```

```kotlin
// TradingForegroundService.kt:579-582
fun startService(context: Context) {
    val prefs = AppPreferences(context)
    if (!prefs.isNotificationsEnabled) return      // ← service tidak pernah hidup
```

**Dinyatakan jujur:** service itu sendiri **tidak** placing order — jadi `executeAutoSellOrder`
secara literal tidak diblokir. Tapi service-lah yang membuat proses **foreground**
(`startForeground`, `:437`), dan `executeAutoSellOrder` / `evaluatePaperAutoExit` /
`checkTrailingForMode` semuanya dijalankan ViewModel yang butuh proses hidup. Jadi **mematikan sakelar
"push notifikasi" mematikan juga nilai keep-alive itu** — persis skenario yang dilarang AGENTS.md.
Efek samping: `updatePrices`/`updatePrice` yang di-guard membuat peta `livePrices` kosong ⇒
`getHoldingsData()` jatuh ke `?: entryPrice` (`:491`, `:522`) sehingga P&L tampil 0% untuk semua posisi.

**Konfirmasi:** ini **bukan** regresi dari N1–N7 — `git log -S` menunjukkan kedua guard berasal dari
`92b0be9` dan `0d95919`, yaitu sisa lama yang belum dibersihkan.

**Perbaikan.** pisahkan "pemantauan" dari "notifikasi": service start/stop ditentukan oleh
keberadaan posisi (`hasActive`), sedangkan `canNotify(...)` di dalam service tetap mengatur bunyi.

### N16 — `AppPreferences` dibangun ulang 7× di `TradingForegroundService`, termasuk per tick dashboard

> 🟠 **SERIUS** (performa + risiko kehilangan preferensi). Ini adalah "catatan sampingan" di §7
> yang sekarang diangkat jadi nomor, karena bertentangan langsung dengan perbaikan N3.

**Lokasi:** `service/TradingForegroundService.kt:324`, `:346`, `:467`, `:580`, `:594`, `:617`, `:636`
(sudah ada satu cache `by lazy` di `:53`, tapi berkas yang sama tetap membangun instance baru)

```kotlin
// TradingForegroundService.kt:53
private val appPrefs by lazy { AppPreferences(applicationContext) }
// …tetapi:
val meta = cache.loadPairsMetadata(AppPreferences(this).marketDataSource)   // :324
val prefs = AppPreferences(this)                                            // :346
val prefs = AppPreferences(context)                                          // :467, :580, :594, :617, :636
```

`updatePrices` (`:636`) dipanggil dari `viewmodel/MarketViewModel.kt:328` — **setiap tick dashboard**.

Constructor-nya mahal **dan destruktif** (`util/AppPreferences.kt:19-63`):

```kotlin
} catch (e: Exception) {
    try {
        context.deleteSharedPreferences(PREFS_NAME)        // ← menghapus SELURUH prefs
        … ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)  // ← menghapus master key
```

**Dampak.** (1) `MasterKey` + `EncryptedSharedPreferences` dibangun ulang berkali-kali per menit di
jalur yang seharusnya hemat baterai; (2) bila **satu** konstruksi gagal, catch block menghapus
seluruh SharedPreferences dan MasterKey — pada jalur yang dipanggil per tick, satu kegagalan
transien bisa menghapus preferensi user (fee, sakelar notifikasi, threshold scalping).
Catatan: `updatePrice` (`:617`) **tidak punya pemanggil** — dead code.

**Perbaikan.** satu `AppPreferences` per service instance (pakai `appPrefs`), hapus
`updatePrice` yang tidak terpakai, dan jangan pernah membangun prefs di loop.

### N17 — N6 melarat: skor SWING dari worker **tidak pernah** memakai penalty anomali makro

> 🟠 **SERIUS** — tambahan dari N6, ditemukan saat verifikasi ulang.

**Lokasi:** `service/CandidateScanWorker.kt:126-132` vs `engine/LearningTradingEngine.kt:317-324`;
efeknya di `engine/swing/SwingEvaluator.kt:443-444`

```kotlin
// CandidateScanWorker.kt:126-132
val swingResult = SwingEvaluator.evaluate(
    globalContext = globalCtx, price = price, history = h1Candles,
    fees = prefs.tradingFees, symbol = cleanSymbol
)   // ← macroAnomalyResult TIDAK diteruskan
```

```kotlin
// LearningTradingEngine.kt:317-324
val result = agu.analys.engine.swing.SwingEvaluator.evaluate(
    globalContext = …, price = tick.price, history = history, fees = tradingFees,
    macroAnomalyResult = anomalyResult,   // ← diteruskan
    symbol = tick.symbol
)
```

```kotlin
// SwingEvaluator.kt:443-444
val anomalyPenalty = macroAnomalyResult?.confidencePenalty ?: 0
val finalScore = maxOf(0, (finalScoreRaw * regimeMultiplier).toInt() + anomalyPenalty)
```

Kedua PRODUSEN menulis key yang **sama** (`BTCIDR#SWING`). Kalau worker menang balapan, skor yang
dipakai notifikasi adalah skor **tanpa** guarding anomali — nilainya selalu lebih tinggi daripada
yang pernah dihasilkan engine. Untuk SCALPING, worker juga memakai `formingVolume = 0.0`,
`bids/asks = emptyList()`, `diagnosticIgnoreOrderBookWhenUnavailable = true`
(`CandidateScanWorker.kt:208-213`).

**Perbaikan.** sama seperti N6: worker hanya boleh mengevaluasi mode aktif, atau punya instance
`SignalLifecycleManager` sendiri.

## 3. 🟡 Temuan sedang/rendah (masih hidup)

| # | Temuan | Lokasi (verifikasi ulang @ `547048d`) | Catatan |
|---|---|---|---|
| N11 | Tier confidence (≥80 / 60-79 / <60) mencampur mode: semua sinyal scalping **pra-entry** di-cap ≤59 → selalu jatuh ke tier "Awal", sehingga tabel "Akurasi per Level Confidence" sebagian besar mengukur READY-vs-bukan, bukan kualitas berjenjang | `ui/screens/SignalLogScreen.kt:1275`, `model/SignalReliabilityModels.kt:70-72`, cap di `engine/scalping/ScalpingMtfEvaluator.kt` (± baris 434-446: `step3Ok -> 59 / step2Ok -> 49 / step1Ok -> 39 / else -> 29`) | **🟡 SEBAGIAN.** Manifestasi **J6** (kalimat klaim hardcoded) ditutup batch 5e — diganti deskripsi bergate sampel + fakta tier kosong (`model/ConfidenceTierNote.kt`). **Yang belum:** tabel tier sendiri dan **cap confidence di `ScalpingMtfEvaluator` tidak diubah sama sekali** (diverifikasi ulang 2026-10-16). Perlu keputusan produk: apakah tabelnya dihapus, atau capnya diubah — keduanya mengubah angka yang sudah tampil |
| N12 | Tidak ada cooldown per-pair untuk notifikasi kandidat (bandingkan: peak 30 dtk, emergency 60 dtk) — siklus READY→INVALIDATED→READY yang rapuh bisa mengulang notifikasi | `viewmodel/TradingViewModelSync.kt:591-604` + `AlertNotificationHelper.kt` | Edge-trigger menangkal sebagian, tapi state yang flapping (apalagi bila N6 diperbaiki dengan benar) bisa spam. Bandingkan dengan peak (30 dtk) dan emergency (60 dtk) yang sudah berfungsi sejak batch 6f |

## 4. Sudah ditutup (ringkasan + pointer laporan)

| Temuan | Commit | Laporan |
|---|---|---|
| N1 — confidence tampil ×100 (6800%) | `cd6c032` | `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` |
| N2 — balapan hilangnya edge trailing | `cd6c032` | `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` |
| N3 — P&L notifikasi pakai fee preset legacy | `cd6c032` | `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` |
| N4 — sakelar notifikasi diabaikan (diperluas: keempat sakelar kategori) | `1a87e3c` | `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` |
| N5 — notifikasi kandidat tanpa disclaimer "uang riil diblokir" | `1a87e3c` | `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` |
| N7 — `markTriggered`/`reset` tidak menormalkan simbol | `1a87e3c` | `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` |
| N10 — atribusi sinyal per simbol di jurnal | `9767c88` | `docs/LAPORAN-batch5-j4n10-2026-10-16.md` |
| N9 (= J9) — default `?: 75` mengarang skor keyakinan | batch 6b | `docs/LAPORAN-batch6-j9-2026-10-17.md` |
| N13 — laporan eksekusi jual memakai kategori `PRICE_ALERT` | batch 6f | `docs/LAPORAN-batch6f-n13n14-2026-10-17.md` |
| N14 — cooldown peak di-bypass jalur kembar | batch 6f | `docs/LAPORAN-batch6f-n13n14-2026-10-17.md` |
| N8 — parameter mati `onlyWhenBackground` | batch 6f | `docs/LAPORAN-batch6f-n13n14-2026-10-17.md` |

> ✅ **N10 (= J10) kini tuntas penuh** — jalur beli paper manual yang masih membaca SL/TP mentah
> ditutup batch 6a (`docs/LAPORAN-batch6-j10-2026-10-17.md`).

## 5. Yang sudah benar (jangan diutak-atik)

- `hasTriggeringTransition` = `(isNewDetected || isNewReady) && action == BUY` → sinyal HOLD
  tidak pernah memberi notifikasi kandidat; invarian P1 (`EARLY_ENTRY` mensyaratkan setup)
  terproteksi di sini juga.
- Notifikasi kandidat **dilewati** bila coin sedang HOLDING
  (`TradingViewModelSync.kt:500`, `CandidateScanWorker.kt:140,170,223`).
- **B5 sudah benar:** confidence di signal-log = nilai saat firing, bukan MAX/SKARANG
  (`database/SignalLogRepository.kt:111`).
- Gerbang tunggal N4 sudah benar: `AlertNotificationHelper.canNotify(context, category)` dipasang di
  setiap chokepoint, dan `AppPreferences` di-cache. Sejak batch 6f **kategori** juga sudah benar:
  laporan eksekusi memakai `sendExecutionResultNotification` (kategori `TRAILING_STOP`), dan hanya
  pengingat harga milik pengguna yang memakai `sendUserPriceAlertNotification` (`PRICE_ALERT`).
  Yang **belum** benar adalah guard call site di luar gerbang (lihat **N15**).
- Izin OS dicek di setiap helper.
- Cooldown **emergency 60 dtk** (`TradingForegroundService.kt:236-250`) berfungsi.
- Cooldown **peak 30 dtk** kini berfungsi penuh (batch 6f, N14): satu-satunya pengirim adalah jalur
  ber-jendela `TrailingPeakThrottle`, dan `updateSimTrailingOrder`/`updateRealTrailingOrder` tidak
  lagi mengirim notifikasi sendiri. Dijaga `TrailingPeakThrottleTest` +
  `NotificationSourceTest` (uji "satu pengirim").
- `util/TradeLogExporter.kt` melabeli jujur "Skor Konfidensi (engine ∩ cap tahap)".

## 6. Rekomendasi perbaikan (urut nilai/risiko) — hanya yang tersisa

| Prioritas | Temuan | Alasan |
|---|---|---|
| 1 | **N15** | Mematikan notifikasi = kehilangan keep-alive pemantauan posisi di latar |
| 2 | **N6 + N17** | Dua produsen satu state machine, dan skornya **berbeda** dari engine. **Butuh keputusan desain** |
| 3 | **N16** | `AppPreferences` dibangun ulang per tick; satu kegagalan bisa menghapus seluruh preferensi user |
| 4 | **N12** | Beri cooldown per-pair pada notifikasi kandidat |
| 5 | **N11 (sisa)** | Butuh keputusan produk: ubah cap bertingkat, atau jangan tampilkan tier lintas-mode sama sekali |

> N13 + N14 + N8 sudah dikerjakan berpasangan di batch 6f (satu helper, satu pengirim).
> Berikutnya **N15 + N16** berpasangan — keduanya di `TradingForegroundService`.

> **Catatan lintas-berkas:** N10 dan N11 punya manifestasi di area jurnal (J4 = N10, J6 = N11).
> Berkas temuan jurnal (`temuan_bugs_jurnal_pnl.md`) **sudah dihapus 2026-10-17** karena J1–J10
> seluruhnya tertutup — jejaknya ada di `docs/LAPORAN-batch5-j4n10-*.md`,
> `docs/LAPORAN-batch5-j6n11-*.md`, dan laporan batch 6. Audit area paper trading ada di
> `docs/bugs/temuan_bugs_paper_trade.md`.

Setiap perbaikan **wajib**:
- mengikuti aturan branch `patch` (jangan push ke `main` tanpa instruksi owner);
- memperbarui markdown terkait (`workplan2.md` § terkait + `AGENTS.md` + `docs/LAPORAN-*.md`);
- menjalankan **kontrol negatif** (matikan perbaikan → buktikan test GAGAL → kembalikan);
- menghitung test dari XML (`app/build/test-results/testDebugUnitTest/*.xml`), bukan memori.

## 7. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit awal jalur notifikasi & confidence dilakukan (read-only, terverifikasi ke kode) |
| 2026-10-16 | Temuan diekspor ke `docs/bugs/temuan_bugs_notifikasi.md` (dokumen ini) — **belum ada perbaikan kode** |
| 2026-10-16 | **Batch 1:** N1, N2, N3 diperbaiki + diuji di `cd6c032` (10 test baru, kontrol negatif 4/10 gagal). Lihat `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` |
| 2026-10-16 | N7 diverifikasi ulang → **AKTIF** (bukan latent); path pemanggil kedua dikoreksi ke `SimulationCoordinator.kt:110` |
| 2026-10-16 | **Batch 2:** N4 (+ perluasan: keempat sakelar kategori juga mati), N5, N7 diperbaiki + diuji di `1a87e3c` (9 test baru, kontrol negatif 6/13 gagal). Lihat `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` |
| 2026-10-16 | Ditemukan catatan sampingan **di luar cakupan batch 2**: `AppPreferences` bukan singleton dan `TradingForegroundService.updatePrice/updatePrices` membangunnya tiap tick harga → `EncryptedSharedPreferences` dibuat ulang terus-menerus. Kandidat temuan area performa, **belum diangkat jadi nomor** |
| 2026-10-16 | **Audit lanjutan (read-only, `patch` @ `a842e35`):** N6 dan N8–N12 **diverifikasi masih hidup** (nomor baris diperbarui). Ditambah **N13–N17**: kategori notifikasi ≠ sakelar Pengaturan (N13), cooldown peak di-bypass jalur kembar (N14), sakelar master mematikan foreground service (N15), `AppPreferences` dibangun 7× di service — catatan sampingan itu sekarang jadi nomor (N16), dan N6 melarat: worker SWING mengabaikan `macroAnomalyResult` sehingga skor notifikasi bisa lebih tinggi daripada engine (N17). **Koreksi §5:** klaim "cooldown peak 30 dtk berfungsi" dicoret. **Belum ada perbaikan kode.** |
| 2026-10-16 | **Batch 5a:** N10 ditutup di `9767c88`. **Batch 5e:** manifestasi J6 dari N11 ditutup di `b690208`. |
| 2026-10-17 | **Batch 6b:** N9 (= J9) ditutup — `TradeSignalConfidence.resolve` menggantikan default `?: 75`; 6 uji, kontrol negatif 1/6 gagal. Lihat `docs/LAPORAN-batch6-j9-2026-10-17.md` |
| 2026-10-16 | **Sinkronisasi dokumen (tanpa perubahan kode):** N1–N5, N7, N10 dipangkas dari badan dokumen dan diringkas di §4; tabel §3 dipertahankan hanya untuk N8, N9, N11, N12 dengan nomor baris terverifikasi ulang. Status diverifikasi ulang ke `patch` @ `547048d` + **review owner** ⇒ status di dokumen ini otoritatif, agen tidak perlu cek kode lagi. Lihat `docs/LAPORAN-sync-dokumen-temuan-2026-10-16.md` |
| 2026-10-17 | **Batch 6f:** N13 + N14 + N8 ditutup — `sendExecutionResultNotification` (kategori `TRAILING_STOP`), `sendUserPriceAlertNotification` (kategori `PRICE_ALERT`), pengirim peak tunggal lewat `TrailingPeakThrottle`, parameter mati `onlyWhenBackground` dihapus. 13 uji baru; kontrol negatif 3/3/1/1 gagal. Lihat `docs/LAPORAN-batch6f-n13n14-2026-10-17.md` |
