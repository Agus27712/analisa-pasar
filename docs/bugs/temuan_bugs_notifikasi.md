# Temuan Audit — Jalur Notifikasi & Skor Keyakinan

**Tanggal:** 2026-10-16 (audit lanjutan diperbarui hari yang sama)
**Status:** DOKUMEN TEMUAN — **sebagian sudah diperbaiki.** Batch 1 (2026-10-16) menutup
**N1, N2, N3**; batch 2 (2026-10-16) menutup **N4, N5, N7**. Temuan **N6, N8–N12 masih hidup**,
dan audit lanjutan 2026-10-16 menambah **N13–N17** (baru, semua masih hidup).
Diverifikasi terhadap kode di branch `patch` @ `a842e35`.
**Cakupan:** seluruh pengirim notifikasi + seluruh alur `confidence` (produsen → notifikasi → jurnal → UI)

> Semua temuan di bawah **diverifikasi langsung ke kode** (bukan kutipan memori).
> Setiap baris kode dirujuk dengan path + nomor baris pada saat audit.

---

## 1. Peta jalur (hasil pemetaan)

```
PRODUSEN CONFIDENCE
├─ engine/scalping/ScalpingMtfEvaluator.kt:434  — conf = min(score, cap 59/49/39/29); ready → score.total
├─ engine/swing/SwingEvaluator.kt:538           — conf = finalScore (− anomalyPenalty, s.d. −60)
└─ engine/intraday/IntradayEvaluator.kt:372-378 — conf = baseScore × regimeMultiplier

JALUR NOTIFIKASI (4 pengirim)
├─ A. viewmodel/TradingViewModelSync.kt:495  onCandidateSignalTransition → sendCandidateFoundNotification (foreground)
├─ B. service/CandidateScanWorker.kt         periodic 15 menit → sendCandidateFoundNotification (background, 3 mode)
├─ C. service/TradingForegroundService.kt    loop 4 detik → emergency/trailing/TP via SellSignalLifecycleManager
└─ D. viewmodel/TradingViewModelAlerts.kt    per tick → price alert, trailing hit, peak update, hasil auto-sell

KONSUMEN CONFIDENCE
├─ database/SignalLogRepository.kt  (B5: conf = nilai saat firing, sudah benar)
├─ database/TradeHistoryRecorder.kt → jurnal (confidenceScore dari snapshot)
├─ ui/components/detail/RadarCheckpointStepper.kt (bobot 30% progress), RadarBuySection.kt (faktor ukuran posisi)
└─ ui/screens/SignalLogScreen.kt (tier ≥80 / 60-79 / <60), ui/components/dashboard/DashboardScreen.kt:228 (≥55)
```

---

## 2. 🔴 Temuan kritis

### N1 — Notifikasi kandidat menampilkan confidence ×100 (salah 100×)

> ✅ **DIPERBAIKI 2026-10-16 di `cd6c032` (batch 1)** — badan pesan diekstrak ke fungsi murni
> `buildCandidateSignalMessage(marketKey, signal)` di `util/AlertNotificationHelper.kt` dan faktor
> `* 100` dihapus. Uji: `app/src/test/java/agu/analys/util/CandidateSignalMessageTest.kt` (4 test).
> Lihat `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` §3.

**Lokasi:** `util/AlertNotificationHelper.kt:174`

```kotlin
"• Skor Keyakinan: ${(signal.confidence * 100).toInt()}%"
```

`AISignalState.confidence` bertipe **`Int` skala 0–100** (`model/TradingViewModels.kt:221`).
Confidence `68` tampil sebagai **"Skor Keyakinan: 6800%"** di notifikasi push kandidat.

Permukaan lain konsisten memakai nilai mentah:
- `ui/components/detail/SignalHistoryPanel.kt` → `Skor ${signal.confidence}/100`
- `engine/LearningTradingEngine.kt` (log) → `Confidence: ${trans.signal.confidence}%`
- `ui/screens/SignalLogScreen.kt` → `${log.confidence}%`

Baris ini ditambahkan di commit `1923ede` ("feat: add exchange-specific context to trading
engines") dan tidak pernah disesuaikan ketika tipe confidence berubah dari `Double 0–1` ke
`Int 0–100`.

**Dampak:** display-only (tidak memengaruhi keputusan engine), tapi di notifikasi yang paling
sering dilihat user dan merusak kepercayaan pada angka lain.

**Perbaikan:** hapus `* 100`, cukup `${signal.confidence}%`.
**Sifat:** bug display, perbaikan trivial, **wajib kontrol negatif** (tulis test yang membuktikan
nilai confidence asli tampil tanpa faktor pengali).

---

### N2 — Balapan (race) hilangnya edge trailing → auto-sell bisa tidak pernah dieksekusi

> ✅ **DIPERBAIKI 2026-10-16 di `cd6c032` (batch 1)** — opsi 1 dipilih: parameter `consumeTrigger: Boolean = true`
> ditambahkan ke `SpotPositionStore.updateTrailingPrice`; saat `false` hanya Rule 1 (peak) yang
> dijalankan sehingga pengamat tidak bisa mencuri edge. `TradingForegroundService` mengirim `false`.
> Uji: `app/src/test/java/agu/analys/trading/TrailingEdgeOwnershipTest.kt` (3 test).
> Lihat `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` §2.

**Lokasi:**
- `service/TradingForegroundService.kt:162` — loop 4 detik memanggil
  `positionStore.updateTrailingPrice(...)` dan **membuang hasil `justTriggered`**
- `viewmodel/TradingViewModelAlerts.kt` `checkTrailingForMode` — hanya mengeksekusi
  `sendTrailingHitNotification` + `executeAutoSellOrder` saat `justTriggered == true`

`SpotPositionStore.updateTrailingTrigger` bersifat **edge-once**: siapa pun yang memanggil
pertama kali saat harga melewati batas "mengonsumsi" edge-nya dan `justTriggered` hanya `true`
sekali.

Kedua loop berjalan bersamaan saat posisi aktif + notifikasi menyala:
- `TradingForegroundService` polling tiap 4 detik
- `checkTrailingForMode` dipanggil per tick harga dari VM

Bila **service menang balapan**, `justTriggered=true` hanya dilihat service (yang membuangnya),
sehingga VM tidak pernah melihat `true` → **notifikasi trailing hilang dan `executeAutoSellOrder`
tidak terkirim**. Posisi menggantung sampai kena SL/TP manual.

**Dampak:** kehilangan sinyal jual otomatis pada kondisi pasar yang bergerak cepat — jalur
aman-in-profit (`trailing`) bisa lumpuh senyap.

**Perbaikan (salah satu):**
1. Service hanya **membaca** `pos.isTrailingTriggered` untuk notifikasi, eksekusi tetap
   tunggal di `checkTrailingForMode` (baca saja, jangan panggil `updateTrailingPrice`);
2. Atau satukan seluruh loop trailing ke satu koordinator tunggal.

**Sifat:** race condition, **wajib kontrol negatif** (uji bahwa edge terkonsumsi hanya sekali
untuk dua pemanggil simultan).

---

## 3. 🟠 Temuan serius

### N3 — Service foreground menghitung P&L notifikasi dengan fee yang salah

> ✅ **DIPERBAIKI 2026-10-16 di `cd6c032` (batch 1)** — service kini memakai
> `AppPreferences(applicationContext).tradingFees` (objek prefs di-cache `by lazy`, nilainya dibaca
> ulang tiap tick), sama dengan jalur VM. Ini menutup **regresi dari `workplan2.md` §24**. Uji:
> `app/src/test/java/agu/analys/service/NotificationFeeSourceTest.kt` (3 test) — invarian sumber +
> bukti dampak. Lihat `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` §4.

**Lokasi:** `service/TradingForegroundService.kt:196`

```kotlin
SellSignalEvaluator.evaluate(context = posContext, indicators = null,
    tradingFees = TradingFeeConfig(), riskSnapshot = riskSnapshot)
```

`TradingFeeConfig()` default adalah preset **legacy Indodax-ish** (0,11 / 0,21 / 0,32 / 0,42) —
komentar di `config/AppConfiguration.kt:25-27` mengakuinya sendiri:
*"Default data class = Indodax-ish legacy agar pemanggilan tanpa argumen tidak diam-diam memakai
fee Tokocrypto USDT."*

Bukan fee Tokocrypto (0,4044%), apalagi `prefs.tradingFees` milik user.

Akibat:
- "Estimasi PnL" di notifikasi exit darurat/TP dihitung dengan fee yang berbeda dari kenyataan;
- Ambang `netProfitPct > 0` di service **berbeda** dengan jalur UI
  (`TradingViewModel.sellSignalState` memakai `_tradingFees` user).

Ini melanggar aturan **"SATU rumus P&L"** (AGENTS.md §24) di permukaan notifikasi.

**Perbaikan:** service memakai `AppPreferences(applicationContext).tradingFees`
(atau `FeeSchedule.forExchange`) — samakan dengan jalur VM.

---

### N4 — Toggle "Push notifikasi" di Pengaturan tidak konsisten

> ✅ **DIPERBAIKI 2026-10-16 di `1a87e3c` (batch 2)** — dan **diperluas**: selain master yang diabaikan di
> `TradingViewModelAlerts`, ternyata **keempat sakelar kategori** juga tidak dibaca oleh jalur
> pengiriman mana pun (hanya ditulis di `SettingsViewModel` + dirender di UI). Perbaikan memakai
> **gerbang tunggal** `util/NotificationGate.kt` (`allowsNotification`, fungsi murni) yang dipasang
> di keenam chokepoint `AlertNotificationHelper`, bukan guard per-call-site.
>
> ⚠️ Saran audit ("guard di awal `checkAlertsAndTrailing`") **tidak dipakai** karena akan
> **mematikan auto-sell** — `checkTrailingForMode` dipanggil dari sana dan di dalamnya ada
> `executeAutoSellOrder`.
>
> Uji: `app/src/test/java/agu/analys/util/NotificationGateTest.kt` (6 test).
> Lihat `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` §2.

**Lokasi:** `viewmodel/TradingViewModelAlerts.kt` (seluruh file) + `util/AlertNotificationHelper.kt`

`checkAlertsAndTrailing` **tidak pernah** membaca `isNotificationsEnabled` — price alert, trailing
hit, dan notifikasi hasil auto-sell tetap dikirim walau toggle mati (helper hanya mengecek
izin OS).

Toggle hanya memengaruhi:
- jalur kandidat (`TradingViewModelSync.kt:496`)
- start/stop foreground service (`TradingViewModel.kt` `updateForegroundServiceState`, baris 460)
- `CandidateScanWorker` (baris 40, 139, 171, 224)

Jadi user yang mematikan notifikasi di Pengaturan **masih menerima** bel puluhan notifikasi
trailing/TP/price-alert dari jalur VM.

**Perbaikan:** tambah guard `prefs.isNotificationsEnabled` di awal `checkAlertsAndTrailing`
(atau putuskan bahwa toggle memang hanya untuk kandidat, lalu **dokumentasikan** di Settings).

---

### N5 — Notifikasi kandidat tidak membawa konteks "uang riil masih diblokir"

> ✅ **DIPERBAIKI 2026-10-16 di `1a87e3c` (batch 2)** — konstanta `CANDIDATE_DISCLAIMER` ikut di badan pesan
> `buildCandidateSignalMessage`. Uji: `util/CandidateSignalMessageTest.kt`.
> Lihat `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` §3.

**Lokasi:** `util/AlertNotificationHelper.kt` `sendCandidateFoundNotification`

Pesan menyajikan "🚀 Sinyal Entry Siap + Entry + Target + Stop Loss" **tanpa disclaimer apa pun**,
padahal AGENTS.md mewajibkan:

> *"Status SIAP bukan ajakan beli. Setiap permukaan yang menampilkan status siap wajib
> menyertakan konteks bahwa eksekusi uang riil masih diblokir."*

Angka riset terakhir: **avg R ≈ −0,17, 0/40 pair positif** (out-of-sample).
Push notification berisi angka entry/TP/SL tanpa disclaimer paling berisiko dianggap sebagai
ajakan beli nyata.

**Perbaikan:** tambahkan baris konteks di badan pesan kandidat, mis.
`"→ Eksekusi uang riil masih DIBLOKIR. Sinyal ini decision support, bukan ajakan beli."`

---

### N6 — Dua produsen menulis satu mesin lifecycle dengan data kualitas berbeda

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

---

### N7 — `markTriggered` tidak menormalkan simbol

**Lokasi:** `engine/scalping/SignalLifecycleManager.kt:382`

```kotlin
val prefix = "${symbol.uppercase()}#"
```

Prefix dibangun **tanpa** strip `/ _ -`, sedangkan key disimpan ternormalisasi lewat
`normalizeSymbol` (uppercase + buang pembatas). Bila pemanggil mengirim simbol berpembatas
(mis. `"BTC/USDT"`), prefix `"BTC/USDT#"` tidak pernah cocok dengan key `"BTCUSDT#SCALPING"` →
sinyal tidak pernah berstatus TRIGGERED diam-diam.

**Dampak:** efek samping — siklus bisa langsung mendeteksi ulang (loop notifikasi).

**Pemanggil terdampak:** `viewmodel/RealTradeExecutor.kt:323` (`markTriggered(pair)`) dan
`viewmodel/SimulationCoordinator.kt:110` (`markTriggered(pair.symbol)`) — format simbol
keduanya harus diverifikasi sebelum memastikan apakah bug ini aktif atau latent.

> ⚠️ **VERIFIKASI 2026-10-16 — bug ini AKTIF, bukan latent.** Audit aslinya masih ragu. Buktinya:
> `RealTradeExecutor.baseFromPair`/`quoteFromPair` (`:908`, `:915`) sama-sama melakukan
> `pair.lowercase().replace("_", "")` — strip `_` yang defensif itu hanya masuk akal bila `pair`
> memang berdelimiter (format Indodax `btc_idr`). Jadi `pair.uppercase()` = `"BTC_IDR"` → prefix
> `"BTC_IDR#"` tidak akan pernah cocok dengan key ternormalisasi `"BTCIDR#SCALPING"`.
> **Koreksi path:** berkas `viewmodel/TradingViewModelSimulation.kt` sudah tidak ada; pemanggil
> keduanya kini `viewmodel/SimulationCoordinator.kt:110`.
>
> ✅ **DIPERBAIKI 2026-10-16 di `1a87e3c` (batch 2)** — `markTriggered` **dan `reset`** kini memakai
> `normalizeSymbol(symbol)`. Uji: `engine/scalping/SignalLifecycleSymbolNormalizationTest.kt`
> (2 test). Lihat `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` §4.

**Perbaikan:** pakai `cacheKey()`/`normalizeSymbol()` di `markTriggered`, bukan
`symbol.uppercase()`.

---

## 3b. Temuan baru — audit lanjutan 2026-10-16

Semua diverifikasi ke kode pada `patch` @ `a842e35`. Nomor baris pada dokumen ini memakai
kondisi **setelah** batch 2.

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

---

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

---

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

---

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

---

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

---

## 4. 🟡 Temuan sedang/rendah

| # | Temuan | Lokasi | Catatan |
|---|---|---|---|
| N8 | `onlyWhenBackground = true` diteruskan tapi **tidak pernah dibaca** di dalam `sendPriceAlertNotification` — notifikasi "Trailing Stop Naik" tetap muncul saat app di foreground | `util/AlertNotificationHelper.kt:588` & `:600` (param mati), forward `:591`; pemanggil `viewmodel/TradingViewModelAlerts.kt:553,573` | Param dead-code; implementasi fitur atau hapus parameternya. (Nomor baris lama `543-579` sudah usang) |
| N9 | `recordBuy` default `signalConfidence = snapshot?.confidenceScore ?: 75` — **mengarang skor 75** saat snapshot null | `database/TradeHistoryRecorder.kt:61` | Pola sama dengan pelanggaran B1 (perangkap #17). Kedua pemanggil (`viewmodel/TradingViewModelSync.kt:272` dan `:114`) mengirim eksplisit, jadi jebakan masih "tidur" — tapi hapus default `?: 75` agar tidak terpakai otomatis di masa depan |
| N10 | `TradeSignalSnapshot.capture` / `recordBuy` memakai `engine.signalState.value` **tanpa verifikasi** `signal.marketSymbol == symbol` yang dibeli | `trading/TradeSignalSnapshot.kt:275`, `viewmodel/TradingViewModelSync.kt:253-282` | Bila engine terakhir menilai pair lain, confidence/TP/SL koin lain ikut tercatat di jurnal. **Manifestasinya sudah didokumentasikan terpisah sebagai J4** di `docs/bugs/temuan_bugs_jurnal_pnl.md` (jurnal & kartu riwayat memakai `selectedPair`, bukan simbol yang dievaluasi engine) |
| N11 | Tier confidence (≥80 / 60-79 / <60) mencampur mode: semua sinyal scalping **pra-entry** di-cap ≤59 → selalu jatuh ke tier "Awal", sehingga tabel "Akurasi per Level Confidence" sebagian besar mengukur READY-vs-bukan, bukan kualitas berjenjang | `ui/screens/SignalLogScreen.kt:1275`, `model/SignalReliabilityModels.kt:70-72`, cap di `engine/scalping/ScalpingMtfEvaluator.kt:434-446` | Bukan bug fungsional; masalah interpretasi statistik lintas-mode. Inilah dasar temuan **J6**: klaim hardcoded di `SignalLogScreen.kt:1349-1354` mengutip tier ini sebagai keunggulan analitis |
| N12 | Tidak ada cooldown per-pair untuk notifikasi kandidat (bandingkan: peak 30 dtk, emergency 60 dtk) — siklus READY→INVALIDATED→READY yang rapuh bisa mengulang notifikasi | `viewmodel/TradingViewModelSync.kt:495-509` + `AlertNotificationHelper.kt` | Edge-trigger menangkal sebagian, tapi state yang flapping (apalagi bila N6 diperbaiki dengan benar) bisa spam. Bandingkan dengan **N14**: cooldown 30 dtk pun ternyata sudah tidak berlaku di jalur trailing |

---

## 5. Yang sudah benar (jangan diutak-atik)

- `hasTriggeringTransition` = `(isNewDetected || isNewReady) && action == BUY` → sinyal HOLD
  tidak pernah memberi notifikasi kandidat; invarian P1 (`EARLY_ENTRY` mensyaratkan setup)
  terproteksi di sini juga.
- Notifikasi kandidat **dilewati** bila coin sedang HOLDING
  (`TradingViewModelSync.kt:500`, `CandidateScanWorker.kt:140,170,223`).
- **B5 sudah benar:** confidence di signal-log = nilai saat firing, bukan MAX/SKARANG
  (`database/SignalLogRepository.kt:111`).
- Gerbang tunggal N4 sudah benar: `AlertNotificationHelper.canNotify(context, category)` dipasang di
  enam chokepoint, dan `AppPreferences` di-cache. Yang **belum** benar adalah **kategori** yang dipilih
  tiap call site (lihat **N13**) dan fakta masih ada guard call site di luar gerbang (lihat **N15**).
- Izin OS dicek di setiap helper.
- Cooldown **emergency 60 dtk** (`TradingForegroundService.kt:236-250`) berfungsi. ⚠️ **Cooldown peak
  30 dtk TIDAK berfungsi** — jalur kembar mengirim lebih dulu tanpa cooldown (**N14**); klaim ini
  dicoret pada 2026-10-16.
- `util/TradeLogExporter.kt` melabeli jujur "Skor Konfidensi (engine ∩ cap tahap)".

---

## 6. Rekomendasi perbaikan (urut nilai/risiko)

| Prioritas | Temuan | Alasan |
|---|---|---|
| ~~1~~ | ~~**N2**~~ ✅ selesai 2026-10-16 (batch 1) | Kehilangan eksekusi auto-sell — paling merugikan user secara finansial |
| ~~2~~ | ~~**N1**~~ ✅ selesai 2026-10-16 (batch 1) | Bug display paling terlihat, perbaikan trivial |
| ~~3~~ | ~~**N3**~~ ✅ selesai 2026-10-16 (batch 1) | Melanggar aturan "SATU rumus P&L"; estimasi PnL salah di notifikasi |
| ~~4~~ | ~~**N4**~~ ✅ selesai 2026-10-16 (batch 2, diperluas) | Inkonsistensi toggle; user kehilangan kontrol atas notifikasi |
| ~~5~~ | ~~**N5**~~ ✅ selesai 2026-10-16 (batch 2) | Kepatuhan aturan AGENTS (status siap ≠ ajakan beli) |
| ~~6~~ | ~~**N7**~~ ✅ selesai 2026-10-16 (batch 2) | Terverifikasi **aktif**, bukan latent; `reset` ikut cacat |
| 7 | **N13** | Sakelar yang diklik user tidak berlaku; konfirmasi jual riil tunduk pada sakelar yang salah |
| 8 | **N14** | Bunyi notifikasi tiap ~10 detik saat trailing aktif; cooldown tertulis tidak berlaku |
| 9 | **N15** | Mematikan notifikasi = kehilangan keep-alive pemantauan posisi di latar |
| 10 | **N6 + N17** | Dua produsen satu state machine, dan skornya **berbeda** dari engine. **Butuh keputusan desain.** |
| 11 | **N16** | `AppPreferences` dibangun ulang per tick; satu kegagalan bisa menghapus seluruh preferensi user |
| 12 | **N8 / N9 / N10 / N12** | Bug latent / kebersihan kode |

> **Catatan lintas-berkas:** N10 dan N11 punya manifestation di area jurnal — lihat
> `docs/bugs/temuan_bugs_jurnal_pnl.md` (J4 = N10, J6 = N11). Audit area paper trading ada di
> `docs/bugs/temuan_bugs_paper_trade.md`.

Setiap perbaikan **wajib**:
- mengikuti aturan branch `patch` (jangan push ke `main` tanpa instruksi owner);
- memperbarui markdown terkait (`workplan2.md` § terkait + `AGENTS.md` + `docs/LAPORAN-*.md`);
- menjalankan **kontrol negatif** (matikan perbaikan → buktikan test GAGAL → kembalikan);
- menghitung test dari XML (`app/build/test-results/testDebugUnitTest/*.xml`), bukan memori.

---

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
