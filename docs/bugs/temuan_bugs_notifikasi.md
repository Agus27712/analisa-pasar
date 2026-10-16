# Temuan Audit — Jalur Notifikasi & Skor Keyakinan

**Tanggal:** 2026-10-16
**Status:** DOKUMEN TEMUAN — **sebagian sudah diperbaiki.** Batch 1 (2026-10-16) menutup
**N1, N2, N3**; batch 2 (2026-10-16) menutup **N4, N5, N7**. Temuan **N6, N8–N12 masih hidup**.
Diverifikasi terhadap kode di branch `patch`.
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

> ✅ **DIPERBAIKI 2026-10-16 (batch 2)** — dan **diperluas**: selain master yang diabaikan di
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

> ✅ **DIPERBAIKI 2026-10-16 (batch 2)** — konstanta `CANDIDATE_DISCLAIMER` ikut di badan pesan
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
> ✅ **DIPERBAIKI 2026-10-16 (batch 2)** — `markTriggered` **dan `reset`** kini memakai
> `normalizeSymbol(symbol)`. Uji: `engine/scalping/SignalLifecycleSymbolNormalizationTest.kt`
> (2 test). Lihat `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` §4.

**Perbaikan:** pakai `cacheKey()`/`normalizeSymbol()` di `markTriggered`, bukan
`symbol.uppercase()`.

---

## 4. 🟡 Temuan sedang/rendah

| # | Temuan | Lokasi | Catatan |
|---|---|---|---|
| N8 | `onlyWhenBackground = true` diteruskan tapi **tidak pernah dibaca** di dalam `sendPriceAlertNotification` — notifikasi "Trailing Stop Naik" tetap muncul saat app di foreground | `util/AlertNotificationHelper.kt:543-579` (param mati); dipanggil dari `TradingViewModelAlerts.kt:553,573` | Param dead-code; implementasi fitur atau hapus parameternya |
| N9 | `recordBuy` default `signalConfidence = snapshot?.confidenceScore ?: 75` — **mengarang skor 75** saat snapshot null | `database/TradeHistoryRecorder.kt:61` | Pola sama dengan pelanggaran B1 (perangkap #17). Kedua pemanggil (`TradingViewModelSync.kt:109,262`) kini mengirim eksplisit, jadi jebakan masih "tidur" — tapi hapus default `?: 75` agar tidak terpakai otomatis di masa depan |
| N10 | `TradeSignalSnapshot.capture` / `recordBuy` memakai `engine.signalState.value` **tanpa verifikasi** `signal.marketSymbol == symbol` yang dibeli | `trading/TradeSignalSnapshot.kt:275`, `viewmodel/TradingViewModelSync.kt:100-122, 253-282` | Bila engine terakhir menilai pair lain, confidence/TP/SL koin lain ikut tercatat di jurnal — audit serupa B-series |
| N11 | Tier confidence (≥80 / 60-79 / <60) mencampur mode: semua sinyal scalping **pra-entry** di-cap ≤59 → selalu jatuh ke tier "Awal", sehingga tabel "Akurasi per Level Confidence" sebagian besar mengukur READY-vs-bukan, bukan kualitas berjenjang | `ui/screens/SignalLogScreen.kt:1275`, `model/SignalReliabilityModels.kt:70-72` | Bukan bug fungsional; masalah interpretasi statistik lintas-mode |
| N12 | Tidak ada cooldown per-pair untuk notifikasi kandidat (bandingkan: peak 30 dtk, emergency 60 dtk) — siklus READY→INVALIDATED→READY yang rapuh bisa mengulang notifikasi | `viewmodel/TradingViewModelSync.kt:495` + `AlertNotificationHelper.kt` | Edge-trigger menangkal sebagian, tapi state yang flapping (apalagi bila N6 diperbaiki dengan benar) bisa spam |

---

## 5. Yang sudah benar (jangan diutak-atik)

- `hasTriggeringTransition` = `(isNewDetected || isNewReady) && action == BUY` → sinyal HOLD
  tidak pernah memberi notifikasi kandidat; invarian P1 (`EARLY_ENTRY` mensyaratkan setup)
  terproteksi di sini juga.
- Notifikasi kandidat **dilewati** bila coin sedang HOLDING
  (`TradingViewModelSync.kt:500`, `CandidateScanWorker.kt:140,171,224`).
- **B5 sudah benar:** confidence di signal-log = nilai saat firing, bukan MAX/SKARANG
  (`database/SignalLogRepository.kt:111`).
- Cooldown emergency 60 dtk + peak 30 dtk ada dan berfungsi; izin OS dicek di setiap helper.
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
| 6 | **N6** | Dua produsen satu state machine — sumber notifikasi ganda. **Butuh keputusan desain.** |
| ~~7a~~ | ~~**N7**~~ ✅ selesai 2026-10-16 (batch 2) | Terverifikasi **aktif**, bukan latent; `reset` ikut cacat |
| 7b | **N9 / N10 / N12** | Bug latent / kebersihan kode |

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
| 2026-10-16 | **Batch 2:** N4 (+ perluasan: keempat sakelar kategori juga mati), N5, N7 diperbaiki + diuji (9 test baru, kontrol negatif 6/13 gagal). Lihat `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` |
| 2026-10-16 | Ditemukan catatan sampingan **di luar cakupan batch 2**: `AppPreferences` bukan singleton dan `TradingForegroundService.updatePrice/updatePrices` membangunnya tiap tick harga → `EncryptedSharedPreferences` dibuat ulang terus-menerus. Kandidat temuan area performa, **belum diangkat jadi nomor**. |
