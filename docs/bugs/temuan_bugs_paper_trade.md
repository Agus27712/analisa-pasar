# Temuan Audit — Siklus Hidup Posisi Paper Trading & Mesin Exit

**Tanggal:** 2026-10-16
**Status:** DOKUMEN TEMUAN — **P1, P2, P3, P4 sudah DIPERBAIKI** (batch 3b & 3c); **P6 DIPERBAIKI
sebagian** (batch 5c — jendela momentum kini satu sumber; bagian `peak` vs high M1 **belum**);
**P5, P7, P8 masih hidup.** Ketiga temuan **kritis** (P1, P2, P3) sudah ditutup.
Diverifikasi terhadap kode di branch `patch` @ `a842e35`.
**Cakupan:** `PaperAutoTradeCoordinator.kt` · `PaperRehydrate.kt` · `PaperExitResolver.kt` ·
`SpotPositionStore.kt` · `TradingViewModelSync.kt` · `TradingViewModel.kt` ·
`TradingViewModelAlerts.kt` · `PaperAutoTradeRuntime`

> Semua temuan di bawah **diverifikasi langsung ke kode** (bukan kutipan memori).
> Setiap baris kode dirujuk dengan path + nomor baris pada saat audit.

### Catatan pengukuran (penting sebelum mulai memperbaiki)

`app/build/test-results/testDebugUnitTest/` di repo ini **basi** — berisi hasil **2026-10-09**
(358 test), yaitu **sebelum** batch 1 & 2 notifikasi. Jadi angka 462 test di
`docs/PANDUAN-LANJUT-AGEN.md` **belum bisa dikonfirmasi** dari sandbox yang dipakai audit ini
(sandbox tidak punya Android SDK, jadi `./gradlew testDebugUnitTest` tidak bisa dijalankan).
Ukur ulang baseline lewat `git stash` di mesin yang punya SDK sebelum membandingkan.

---

## 1. Peta jalur (hasil pemetaan)

```
PEMBELIAN
├─ auto-buy   PaperAutoTradeCoordinator.tryPaperAutoBuy        (transition dari engine)
└─ manual    TradingViewModelSync.syncSimulationTradeToPositionStore
             └─ setHolding / markBought  →  armPaperExitEngine

PEMILIKAN EXIT (satu pemilik per posisi — invarian perangkap #20)
├─ engine paper:  PaperAutoTradeRuntime.machines (MEMORI SAJA)
│   └─ PaperExitStateMachine  (PaperExitResolver.kt)
│       └─ persistPaperFsm → key <key>_paper_fsm (SpotPositionStore)
└─ jalur legacy: checkAlertsAndTrailing → executeAutoSellOrder (TP1/TP2/SL/trailing)

PEMUTUSAN KAPAN EVALUASI
├─ tick pair dibuka     TradingViewModel.kt:157      (window M1 ikut)
└─ polling 10 detik    TradingViewModelSync.kt:635  (tanpa window M1)

REHIDRASI (sekali, saat init)
└─ initSubscriptionsAndPolling → rehydratePaperAutoExit
```

---

## 2. 🔴 Temuan kritis

### P1 — Posisi paper di bursa non-aktif menggantung permanen setelah restart

**Lokasi:**
- `viewmodel/PaperAutoTradeCoordinator.kt:817-821`
- `viewmodel/TradingViewModelSync.kt:611-618` (polling)
- `viewmodel/TradingViewModel.kt:421-467` (`setMarketDataSource`, **tidak** memanggil rehidrasi)
- `trading/SpotPositionStore.kt:692-704` (`getPaperEngineSymbols` — prefiks bursa wajib)

```kotlin
// PaperAutoTradeCoordinator.kt:817-821
fun TradingViewModel.rehydratePaperAutoExit(): Int {
    val cfg = prefs.paperAutoTradeConfig
    val exchange = prefs.marketDataSource.name          // ← hanya bursa AKTIF
    val symbols = positionStore.getPaperEngineSymbols(isReal = false, exchange = exchange)
    if (symbols.isEmpty()) return 0
```

```kotlin
// TradingViewModelSync.kt:611-618 (loop 10 detik)
val currentEx = prefs.marketDataSource.name
val activeSymbols = (
    positionStore.getAllActiveTrailingSymbols(isReal = isReal, exchange = currentEx) +
        PaperAutoTradeRuntime.activeSymbols(currentEx)   // ← hanya bursa AKTIF
    ).distinct()
```

**Rantai aktual.** Pengguna punya posisi paper di Indodax (`_paper_engine = true`,
`auto_sell_enabled = false` karena diambil alih `armPaperExitEngine:351-364`), lalu pindah ke
Tokocrypto, lalu app di-kill → restart:

1. `rehydratePaperAutoExit()` hanya membaca partisi `tokocrypto_sim_*` ⇒ mesin Indodax **tidak pernah dibangun**.
2. `setMarketDataSource()` tidak memicu rehidrasi ⇒ **sekalipun user kembali ke Indodax**, mesin tetap kosong.
3. Polling hanya memindai bursa aktif. Pyramiding: `getAllActiveTrailingSymbols` mensyaratkan
   `trailing_enabled = true`, sedangkan engine paper **mematikan** trailing (`:367-373`) ⇒ simbol
   tidak pernah ditemukan polling lewat jalur pertama; `PaperAutoTradeRuntime.activeSymbols(ex)`
   juga hanya bursa aktif.

**Dampak.** Posisi itu **tidak pernah punya SL, TP, BE, trailing, maupun time-stop — selamanya**.
Kas simulasi tetap terpotong (order market benar-benar mengurangi saldo), posisi tidak pernah
menutup, jurnal paper tidak pernah mencatatnya, dan `countActivePaperPositions()`
(`SpotPositionStore.kt:845`, global tanpa bursa) **tetap menghitungnya** sehingga ikut memakan 1
dari 3 slot auto-buy. Di layar pun tidak terlihat, karena `paperSnapshotFor` hanya membaca bursa aktif.

**Perbaikan.**
1. Rehidrasi mengiterasi **semua** bursa (butuh enumerasi lintas bursa di `SpotPositionStore`),
   bukan hanya `prefs.marketDataSource`.
2. `setMarketDataSource` memicu rehidrasi setelah mengganti bursa.
3. Polling menemukan simbol paper dari disk (`getPaperEngineSymbols` tanpa argumen bursa), bukan
   hanya dari `PaperAutoTradeRuntime` yang isi memorinya kosong setelah restart.

**Sifat:** salah/partially-dead state machine ⇒ **wajib kontrol negatif** (posisi di bursa kedua
harus tetap punya mesin setelah siklus ganti-bursa → rehidrasi).

**Catatan arsitektur:** logikanya sekarang tidak murni (butuh ViewModel). Ekstrak resolver murni
mis. `decideRehydrateScope(exchanges: List<String>)` lebih dulu, baru sambungkan.

> ✅ **DIPERBAIKI 2026-10-16 di `e33b238` (batch 3c)** — rekomendasi arsitektur di atas diikuti apa adanya:
> resolver murni `engine/scalping/PaperRehydrateScope.kt` (`decide(storedExchanges, activeExchange)`
> — bursa aktif didahulukan, sisanya alfabetis) dipakai `rehydratePaperAutoExit`, yang kini
> mengiterasi **semua** bursa ber-posisi paper lewat `SpotPositionStore.getPaperEnginePositions(isReal)`
> + `trading/PaperEnginePosition.kt` (baru). Tiga jalur lain ikut ditutup: ganti bursa memicu
> rehidrasi (mengumpulkan aliran `marketDataSource`, emisi pertama dilewati),
> `startTrailingPolling` juga membaca simbol paper dari **disk** (bukan hanya
> `PaperAutoTradeRuntime` yang kosong setelah restart), dan bursa non-aktif dicatat eksplisit di log.
>
> **Batas yang wajib dibaca:** mesin untuk bursa non-aktif **dibangun** tetapi **belum bisa
> dievaluasi** — kedua pemanggil `evaluatePaperAutoExit` (`TradingViewModel.kt:157`,
> `TradingViewModelSync.kt:669`) memakai bursa aktif, dan harga hanya tersedia untuk bursa yang
> sedang dipakai. Jadi yang ditutup adalah "menggantung **permanen**" (kembali ke bursa itu tidak
> lagi sia-sia), **bukan** "posisi di bursa non-aktif kini terlindungi otomatis". Rinciannya di
> `docs/LAPORAN-batch3-rehidrasi-2026-10-16.md` §4.
>
> Uji: `engine/scalping/PaperRehydrateScopeTest.kt` (7 kasus) +
> `trading/SpotPositionStorePaperEngineScopeTest.kt` (6 kasus, Robolectric).

---

### P2 — Auto-buy menerima sinyal SWING/INTRADAY; SL-nya 2–4× lebih rapat dari lantai friksi wajib

**Lokasi:**
- `viewmodel/PaperAutoTradeCoordinator.kt:426-428` (gerbang auto-buy)
- `viewmodel/TradingViewModelSync.kt:531-542` (satu-satunya pemanggil `tryPaperAutoBuy`)
- `engine/intraday/IntradayEvaluator.kt:302-305` dan `:525`
- `engine/swing/SwingEvaluator.kt:551` dan `:556-561`
- `engine/LearningTradingEngine.kt:344-350` (SWING) dan `:388-394` (INTRADAY) — keduanya memanggil
  `onCandidateSignalTransition` yang **sama**

```kotlin
// PaperAutoTradeCoordinator.kt:426-428
val signal = transition.signal
if (signal.action != SignalAction.BUY) return false
if (!cfg.stageQualifies(signal.scalpingStage)) return false
// ⚠️ transition.mode TIDAK pernah diperiksa
```

`minStage` default = `ScalpingStage.ENTRY` (`PaperAutoTradeConfig.kt:71`), dan `stageQualifies`
adalah `ordinal >= minStage.ordinal` (`:83`). Kedua evaluator non-scalping **mem salesman
`ScalpingStage.ENTRY` untuk sinyal BUY**:

```kotlin
// IntradayEvaluator.kt:525
scalpingStage = if (completedSteps == 4 && intradayPhase.isOpenWindow) ScalpingStage.ENTRY else ...
// SwingEvaluator.kt:556-561
scalpingStage = when { completedSteps == 4 -> ScalpingStage.ENTRY ...
```

SL-nya (jauh di bawah lantai friksi):

```kotlin
// IntradayEvaluator.kt:302-305
val calculatedSl = maxOf(
    supportLevel - (intradayAtr * 0.3),
    price * 0.972
).coerceAtMost(price * 0.985)        // ← jarak SL hanya 1,5%–2,8%
```

Lantai friksi default = `6,0 × (buyTaker + sellTaker + 2×slippage)` ≈ **5,81%** (Tokocrypto USDT).
`ScalpingRiskEngine` menegakkan lantai itu, **tapi jalur ini tidak** — dan `armPaperExitEngine`
memakai SL sinyal apa adanya selama "valid":

```kotlin
// PaperAutoTradeCoordinator.kt:303-317
val signalSlValid = stopLoss > 0.0 && stopLoss.isFinite() && stopLoss < entryPrice
val effectiveSl = if (signalSlValid) {
    stopLoss                      // ← lantai friksi TIDAK diperiksa untuk SL dari sinyal
} else { /* hitung dari lantai */ }
```

**Dampak.** Saat mode `OFFICE_DAILY`/`SWING` aktif dan auto-buy menyala:
- posisi paper dibuka dengan risiko 1,5–2,8% sementara friksi round-trip ≈ 0,82% ⇒ **satu SL ≈ −1,3R…−2R**;
- jurnal paper (dasar seluruh keputusan "apakah scalp punya edge") tercampur strategi non-scalping;
- mesin exit **scalping** ikut|time-stop `maxHoldBarsM1` (horizon 2 jam) untuk strategi ber-horizon
  harian ⇒ banyak `TIMEOUT` palsu;
- `transition.mode` hanya dipakai sebagai label (`:510`).

**Perbaikan.**
1. Gate `transition.mode == StrategyMode.SCALPING` di `tryPaperAutoBuy` (posisi paper + mesin exit
   adalah produk scalping).
2. Resolver murni `resolvePaperStopLoss(signalSl, entry, frictionFloorPct)` yang **selalu** melebar
   SL ke lantai — dipakai bersama oleh auto-buy, beli manual, **dan** rehidrasi.

**Sifat:** guard yang tidak diuji ⇒ **wajib kontrol negatif** (SL 2% pada pair USDT harus
ditolak/dilLebarkan ke ≈5,81%; sinyal mode SWING tidak boleh membuka posisi).

> ✅ **DIPERBAIKI 2026-10-16 di `8d22fbb` (batch 3b)** — dua bagian. (1) Gerbang
> `transition.mode != StrategyMode.SCALPING` di `tryPaperAutoBuy`, ditempatkan **sebelum**
> `stageQualifies` supaya sinyal non-scalping yang lolos ambang tetap tercatat di log (kalau
> ditaruh sesudah, cabang log-nya tidak pernah tercapai). (2) Resolver murni
> `engine/scalping/PaperStopResolver.kt` (`frictionFloorPct` + `resolve`) yang **hanya melebar,
> tidak pernah menyempitkan** SL, kini dipakai bersama oleh auto-buy, beli manual, re-arm DCA, dan
> serah-terima ke jalur lama. Lantai Tokocrypto USDT = 5,8128%. Uji:
> `engine/scalping/PaperStopResolverTest.kt` (16 kasus). Lihat
> `docs/LAPORAN-batch3-paper-safety-2026-10-16.md` §2.
>
> **Batas:** gerbang mode itu sendiri **belum** punya uji otomatis (`tryPaperAutoBuy` butuh
> `TradingViewModel` hidup) — yang teruji adalah invarian resolvernya. Dicatat di §7 laporan.

---

### P3 — DCA tidak pernah me-re-arm mesin exit; SL bisa berada di atas entry → **jual paksa berlabel STOP_LOSS**

**Lokasi:**
- `viewmodel/TradingViewModelSync.kt:231-243` (entry jadi rata-rata saat DCA) dan `:284-296`
- `trading/SpotPositionStore.kt:325-331` (`setHolding` mempertahankan SL lama)
- `viewmodel/PaperAutoTradeCoordinator.kt:296-298` (`force = false` ⇒ bail kalau mesin ada)
- `engine/scalping/PaperRehydrate.kt:114-122` dan `:144-147`
- `viewmodel/PaperAutoTradeCoordinator.kt:885-899` (`HandBackToLegacy`)
- `viewmodel/TradingViewModelAlerts.kt:271-277` (penjualan paksa)

```kotlin
// TradingViewModelSync.kt:232-243 — DCA mengubah entry jadi rata-rata
val totalQty = currentPos.quantity + order.quantity
val totalCost = (currentPos.entryPrice * currentPos.quantity) + (fillPrice * order.quantity)
val weightedAvg = if (totalQty > 0.0) totalCost / totalQty else fillPrice
positionStore.setHolding(symbol = symbol, invested = totalCost, entry = weightedAvg, quantity = totalQty, ...)
```

```kotlin
// SpotPositionStore.kt:331 — SL posisi lama dipertahankan apa adanya
val stopLossPrice = if (current.stopLossPrice > 0.0) current.stopLossPrice else if (entry > 0.0) entry * 0.99 else 0.0
```

```kotlin
// PaperAutoTradeCoordinator.kt:296-298
if (!force && PaperAutoTradeRuntime.machineFor(exchangeKey, pair.symbol) != null) return true
// ↑ pemanggil DCA (TradingViewModelSync.kt:289) memakai default force = false ⇒ mesin TIDAK dibangun ulang
```

Mesin yang ada masih memakai `entry` **fill pertama**, sedangkan store memakai rata-rata. Setelah restart:

```kotlin
// PaperRehydrate.kt:120-122
if (stopLoss <= 0.0 || !stopLoss.isFinite() || stopLoss >= entry) {
    return PaperRehydrateAction.HandBackToLegacy(REASON_INVALID_SL)
}
```

`HandBackToLegacy` menulis ulang SL itu **dengan auto-sell dinyalakan**:

```kotlin
// PaperAutoTradeCoordinator.kt:886-899
is PaperRehydrateAction.HandBackToLegacy -> {
    positionStore.setAutoSellParams(symbol = pair.symbol, enabled = true, ..., stopLossPrice = pos.stopLossPrice)
    positionStore.setPaperEngineOwned(..., owned = false)
    positionStore.clearPaperFsmState(...)
```

Lalu pada tick berikutnya jalur legacy langsung menjual seluruhnya:

```kotlin
// TradingViewModelAlerts.kt:271-277
if (remainingQty > 0.0 && updatedPos.stopLossPrice > 0.0 && currentPrice <= updatedPos.stopLossPrice) {
    val dispatched = executeAutoSellOrder(symbol, currentPrice, remainingQty, "STOP_LOSS", ...)
```

**Dua kasus deterministik:**

| Kasus | Akibat |
|---|---|
| DCA di harga **lebih tinggi** | rata-rata ≠ `machine.entry` ⇒ `restoreRejection` menolak (`PaperExitResolver.kt:508-511`) ⇒ progres FSM dibuang tiap restart; posisi jalan dengan SL/TP fill pertama |
| DCA di harga **lebih rendah** (posisi turun lalu ditambah) | rata-rata turun **di bawah SL lama** ⇒ `stopLoss >= entry` ⇒ `HandBackToLegacy` ⇒ **jual seluruh posisi di harga pasar, dilabeli `STOP_LOSS`** di jurnal, padahal tidak ada SL yang tersentuh |

Selain itu, `finalizePaperExit` menghitung R dari `machine.entry` (fill pertama), bukan rata-rata
sesungguhnya ⇒ angka R di jurnal tidak cocok dengan posisi yang tercatat.

**Perbaikan.**
1. Resolver murni `PaperRearmDecision.decide(prevMachine, newEntry, newQty, storedSl)` dengan tiga
   cabang: `Rearm` (kuantitas bertambah ⇒ hitung ulang mesin), `Reset` (simbol dipakai ulang),
   `Keep`.
2. `HandBackToLegacy` **dilarang** menulis SL ≥ entry — kalau SL tidak bisa dipertahankan, pakai
   resolver lantai friksi atau bucket eksplisit tanpa auto-sell.

**Sifat:** menghasilkan transaksi uang pada kondisi yang tidak dikehendaki ⇒ **wajib kontrol
negatif** (rata-rata di bawah SL lama tidak boleh menghasilkan mesin dengan SL ≥ entry).

> ✅ **DIPERBAIKI 2026-10-16 di `8d22fbb` (batch 3b)** — `TradingViewModelSync` kini menghitung `isDca` sekali,
> memakai `effectiveEntry` (rata-rata) sebagai `entryPrice`, dan memanggil
> `armPaperExitEngine(..., force = isDca)` — satu-satunya jalur yang boleh menimpa mesin hidup.
> `HandBackToLegacy` mengambil SL dari `PaperStopResolver` yang sama dan menyetel
> `enabled = resolvedHandBack.usable`: bila SL tersimpan tidak valid **dan** lantai friksi
> nonaktif, auto-sell sengaja **tidak** dinyalakan. Log mencatat sumber SL
> (`SIGNAL`/`FRICTION_FLOOR`/`NONE`).
>
> **Penyimpangan yang disengaja:** rekomendasi `PaperRearmDecision.decide(...)` (tiga cabang
> `Rearm`/`Reset`/`Keep`) **tidak** diadopsi — cabang `Reset`/`Keep` belum punya konsumen nyata,
> jadi objek itu hanya akan jadi kode mati. Invarian intinya ("SL < entry", "lantai hanya melebar")
> ditegakkan di `PaperStopResolver`. Alasannya dicatat di
> `docs/LAPORAN-batch3-paper-safety-2026-10-16.md` §3.4.
>
> **Perubahan perilaku yang perlu diketahui:** dengan lantai friksi dimatikan, posisi ber-SL tidak
> valid kini berjalan **tanpa** auto-sell (dulu: jual pasar berlabel `STOP_LOSS`). Itu penggantian
> yang disengaja, bukan regresi — perlu SL manual. Lihat laporan §7.2.

---

## 3. 🟠 Temuan serius

### P4 — `atrPct` tidak pernah diteruskan di jalur beli manual ⇒ S8 mati untuk separuh posisi

**Lokasi:** `viewmodel/TradingViewModelSync.kt:289-296` vs `viewmodel/PaperAutoTradeCoordinator.kt:255-262`, `:329-337`, `:441-455`

```kotlin
// TradingViewModelSync.kt:289-296 — BELUM ada atrPct
armPaperExitEngine(
    symbol = symbol, exchange = currentEx, entryPrice = fillPrice,
    stopLoss = engine.signalState.value.stopLoss,
    tp1 = engine.signalState.value.targetPrice1,
    tp2 = engine.signalState.value.targetPrice2
)
```

```kotlin
// PaperAutoTradeCoordinator.kt:255-262
private fun TradingViewModel.atrPctAt(referencePrice: Double): Double {
    if (referencePrice <= 0.0) return 0.0
    val atr = engine.indicators.value.atr
    return if (atr > 0.0) atr / referencePrice * 100.0 else 0.0
}
```

Penyebabnya struktural: `atrPctAt` `private`, jadi tidak bisa dipanggil dari berkas lain. Karena
`atrPct = 0.0` berarti "tidak diketahui", `persistPaperFsm` menulis nol dan rehidrasi juga
menerima nol ⇒ **kerusanannya persisten**, bukan hilang setelah restart.

**Dampak.** `effectiveBreakevenTriggerNetPct` dan `effectiveTrailDistancePct` jatuh ke nilai
persen tetap untuk **setiap** posisi yang dibeli manual: ambang BE lebih cepat tercapai, trailing
lebih rapat. Statistik paper menggabungkan dua kebijakan exit berbeda tanpa Bedanya terlihat.

**Perbaikan.** jadikan `atrPctAt` `internal`, atau lebih baik: fungsi murni `PaperAtr.of(atr, price)`
di `engine/scalping` supaya bisa diuji tanpa ViewModel. Tambahkan juga test: beli manual →
`atrPct > 0` bila ATR tersedia.

> ✅ **DIPERBAIKI 2026-10-16 di `8d22fbb` (batch 3b)** — rekomendasi di atas diikuti apa adanya: rumusnya
> diekstrak ke `engine/scalping/PaperAtr.kt` (`PaperAtr.of(atr, referencePrice)`), lalu dipakai
> `atrPctAt` **dan** jalur beli manual di `TradingViewModelSync` — yang terakhir kini juga mengirim
> `atrPct` bersamaan dengan `force = isDca` (perbaikan P3). Penjaga: `PaperAtr.of` mengembalikan
> `0.0` (bukan `NaN`/`∞`) untuk ATR atau harga acuan yang belum layak. Uji: bagian "P4 — ATR%" di
> `engine/scalping/PaperStopResolverTest.kt`. Lihat
> `docs/LAPORAN-batch3-paper-safety-2026-10-16.md` §4.
>
> **Batas:** uji membuktikan rumusnya, bukan bahwa `persistPaperFsm` benar-benar menulis `> 0` pada
> siklus beli-manual → restart (butuh ViewModel + store hidup).

---

### P5 — Kartu FSM di layar simulasi dihapus oleh tick pair lain

**Lokasi:** `viewmodel/TradingViewModel.kt:93-99`, `viewmodel/PaperAutoTradeCoordinator.kt:614-619`, `viewmodel/TradingViewModelSync.kt:629-637`, konsumen `ui/screens/TradeSimulationScreen.kt:57` dan `:311`

```kotlin
// PaperAutoTradeCoordinator.kt:614-619
val machine = PaperAutoTradeRuntime.machineFor(exchange, symbol)
if (machine == null) {
    // Tidak ada posisi paper berjalan -> pastikan kartu FSM kosong.
    publishPaperExitSnapshot(null)
    return null
}
```

`publishPaperExitSnapshot` menulis ke **satu** `MutableStateFlow` global, sementara
`evaluatePaperAutoExit` dipanggil untuk **setiap** simbol pada loop polling
(`TradingViewModelSync.kt:629-637`) **dan** untuk pair yang dipilih (`TradingViewModel.kt:157`).

**Dampak.** Setiap simbol non-paper yang mendapat tick menimpa flow dengan `null`. Bila user
membuka detail BTC sementara posisi paper-nya ada di AMP/USDT, kartu FSM di layar simulasi
berkedip hilang. Ini persis kebingungan "satu state untuk dua tampilan" yang dulu membuat fitur
§14/§15 dibuang.

**Sudah benar (jangan diubah):** halaman detail **tidak** memakai flow global — sudah lewat
`paperSnapshotFor` (`PaperAutoTradeCoordinator.kt:776-784`, dipakai `DetailChartScreen.kt:116-122`).
Yang belum punya padanannya adalah `TradeSimulationScreen`.

**Perbaikan.** snapshot per-key (`paperSnapshots: StateFlow<Map<String, PaperExitSnapshot>>`),
atau `publishPaperExitSnapshot` hanya menulis bila simbol adalah pemilik aktif. Test: evaluating
simbol tanpa mesin **tidak boleh** mengubah snapshot simbol lain.

---

### P6 — Dua jalur pemantauan menghasilkan exit berbeda untuk trade yang sama

**Lokasi:** `viewmodel/TradingViewModel.kt:157`, `viewmodel/TradingViewModelSync.kt:629-637`, `viewmodel/PaperAutoTradeCoordinator.kt:645-660`

```kotlin
// TradingViewModel.kt:157 — pair dibuka: jendela momentum DIKIRIM
runCatching { evaluatePaperAutoExit(symbol, currentEx, price, recentCandles.takeLast(15)) }

// TradingViewModelSync.kt:629-637 — posisi di latar: jendela momentum KOSONG
for (tick in ticks) { ... runCatching { evaluatePaperAutoExit(tick.symbol, currentEx, tick.price) } ... }
```

Selain itu, bar sintetis dibuat dengan OHLC identik dan `volume = 0.0`
(`PaperAutoTradeCoordinator.kt:645-660`), `peak = max(peak, bar.high)` tidak pernah bisa melewati
harga tick, dan sampling jalur latar 10 detik.

**Dampak.**
- `EARLY_PROFIT` mustahil terjadi pada posisi yang **tidak sedang dibuka** ⇒ jurnal mencatat `TP2`
  di trade yang sama akan tercatat `EARLY_PROFIT` bila kebetulan user membuka pair-nya.
- `peak` di jalur latar tertinggal dari high M1 ⇒ trailing SL lebih longgar daripada replay.
- App dan `tools/scalp_replay.py` tidak sebanding, padahal paritas itu alasan utama jurnal ini ada.

**Perbaikan.** satu sumber tick untuk kedua pemanggil (atau satu scheduler), dan window M1 dari
`MtfCacheManager` bila tersedia.

> ✅ **DIPERBAIKI 2026-10-16 (batch 5c)** — bagian "satu sumber" diikuti apa adanya: parameter
> `window` **dihapus** dari `evaluatePaperAutoExit`, jadi tidak ada lagi pemanggil yang bisa
> mengirim jendela berbeda. Jendela kini diresolusi di dalam dari cache M1 lewat
> `trading/PaperExitMomentumWindow.kt`, dengan ambang kesegaran yang **mengikuti
> `MtfCacheManager.isCacheValid(M1)`** (≥20 bar, ≤180 detik) — bukan ambang baru. Kedua pemanggil
> kini identik: `evaluatePaperAutoExit(symbol, currentEx, price)`.
> Efek yang disengaja: `EARLY_PROFIT` lebih jarang di jalur foreground — itulah inti temuan.
> Uji: `PaperExitMomentumWindowTest` (12 kasus); kontrol negatif 5/12 gagal.
> Laporan: `docs/LAPORAN-batch5-p6-2026-10-16.md`.
>
> ⚠️ **Bagian kedua P6 belum beres:** `peak` di jalur latar masih tertinggal dari high M1 (bar
> sintetis OHLC identik, sampling 10 detik). Ini bukan cabang kode yang berbeda antar pemanggil,
> melainkan tidak adanya umpan intrabar — perlu temuan dan uji sendiri, sengaja tidak disisipkan
> di sini (lihat §6 laporan).

---

## 4. 🟡 Temuan sedang / rendah

| # | Temuan | Lokasi | Dampak |
|---|---|---|---|
| P7 | `clearPaperExitState` **tidak punya satu pun pemanggil** | `PaperAutoTradeCoordinator.kt:756-761` | Jalur "user mengambil alih exit engine" yang menjanjikan AGENTS §12 tidak ada implementasinya; satu-satunya jalan keluar adalah `HandBackToLegacy`, yang justru melempar progres FSM (lihat P3) |
| P8 | `barsHeld` dihitung **dua kali** pada tick pertama setelah rehidrasi | `PaperRehydrate.kt:143` + `:165-169`, `PaperAutoTradeCoordinator.kt:150`, `PaperExitResolver.kt:586` | `markFirstTickAsNewBar` membuat `onBar` menaikkan `barsHeld` lagi pada tick yang sama ⇒ time-stop keluar **1 bar (1 menit) lebih awal** dari seharusnya |

---

## 5. Yang sudah benar (jangan diutak-atik)

Semua aturan rehidrasi P4.4 **terpasang benar** dan sudah diuji:

- key `_paper_fsm` dihapus di **setiap** jalur pelepasan kepemilikan — `SpotPositionStore.kt:416`
  dan `:452` (`markSold`), `PaperAutoTradeCoordinator.kt:630` ("posisi sudah tidak ada"),
  `:750` (`finalizePaperExit`), `:759` (`clearPaperExitState`). Dijaga
  `app/src/test/java/agu/analys/trading/SpotPositionStorePaperFsmTest.kt`.
- Penanda `_paper_engine` ditulis saat engine mengambil alih (`:342-346`) dan dilepas di
  `:627`, `:747`, `:758`, `markSold:415`. Dijaga `SpotPositionStorePaperMarkerTest.kt`.
- SL asli sinyal ditulis lewat `setAutoSellParams(stopLossPrice = …)` (`:354-364`, `:536-551`),
  bukan `_stop_loss` sintetis dari `setHolding`.
- `atrPct` dioper saat mesin dibangun di `PaperRehydrate.decide()` (`:135`) — **bukan** lewat
  `restore()`. Dijaga `PaperRehydrateTest.kt:296`.
- `barsHeld` dihitung dari jam dinding (`mergedBarsHeld`, `PaperRehydrate.kt:165-169`).
- State tersimpan yang gagal validasi **dibuang**, bukan dipakai setengah
  (`PaperRehydrate.kt:144-147` + warning dicatat `:877-882`).
- Batas posisi paper bersamaan(global, lintas bursa) memakai **semua** posisi paper termasuk yang
  dibeli manual: `PaperAutoTradeCoordinator.kt:441-442` + `SpotPositionStore.kt:845`.
- Normalisasi simbol runtime konsisten: `PaperAutoTradeRuntime.key()` hanya buang `_`, tetapi
  `PairRules.normalizeBidr` (`PairRules.kt:40-45`) sudah membuang `_ / - / space`, dan
  `TradingPair.fromCustomSymbol` memakainya sebelum mesin disimpan.

---

## 6. Rekomendasi perbaikan (urut nilai ÷ risiko)

| Urut | Temuan | Alasan |
|---|---|---|
| 1 | **P3** | Menghasilkan transaksi yang tidak dikehendaki (jual paksa berlabel `STOP_LOSS`) |
| 2 | **P1** | Posisi tanpa perlindungan apa pun selamanya + satu slot auto-buy terblokir |
| 3 | **P2** | Jurnal paper tercampur strategi lain; SL jauh di bawah lantai wajib |
| 4 | **P4** | S8 mati untuk separuh posisi; kerusakan persisten |
| 5 | **P5** | Angka tidak stabil di layar — masalah kontras yang sudah pernah menggugurkan fitur |
| 6 | **P6** | Hasil app vs replay tidak sebanding |
| 7 | **P7, P8** | Kebersihan & determinisme |

### Prasyarat yang berulang muncul di semua temuan ini

Logika penting terkubur di extension `TradingViewModel.*` yang butuh ViewModel + Android, sehingga
**tidak bisa diuji**. Semua yang benar (PaperRehydrate, PaperAutoTradeRuntime, SpotPositionStore)
sudah **murni dan teruji**; yang belum murni adalah **keputusan**-nya. Syarat sebelum memperbaiki:

1. `resolvePaperStopLoss(signalSl, entry, floorPct)` — murni, dipakai 3 pemanggil (P2, P3, P4).
2. `PaperRearmDecision.decide(...)` — murni (P3).
3. `decideRehydrateScope(exchanges)` — murni (P1).
4. `PaperAtr.of(atr, price)` — murni (P4).

Setiap perbaikan **wajib** kontrol negatif (matikan perbaikan → buktikan test GAGAL → kembalikan),
dan angka test **diukur** dari `app/build/test-results/testDebugUnitTest/*.xml`, bukan dari pesan lama.

---

## 7. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit read-only siklus hidup posisi paper & mesin exit (verifikasi ke kode, branch `patch` @ `a842e35`) |
| 2026-10-16 | Temuan diekspor ke `docs/bugs/temuan_bugs_paper_trade.md` (dokumen ini) — **belum ada perbaikan kode** |
| 2026-10-16 | **Batch 3b:** P2 + P3 + P4 diperbaiki + diuji di `8d22fbb` (16 uji di `PaperStopResolverTest`, kontrol negatif 4/16 gagal). Lihat `docs/LAPORAN-batch3-paper-safety-2026-10-16.md` |
| 2026-10-16 | **Batch 3c:** P1 diperbaiki + diuji di `e33b238` (13 uji baru, kontrol negatif 8/13 gagal) ⇒ **ketiga temuan kritis (P1, P2, P3) tertutup.** Lihat `docs/LAPORAN-batch3-rehidrasi-2026-10-16.md` |