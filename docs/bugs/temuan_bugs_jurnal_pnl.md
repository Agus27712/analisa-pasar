# Temuan Audit — Jurnal Sinyal, Jurnal Trade & Angka P&L

**Tanggal:** 2026-10-16
**Status:** DOKUMEN TEMUAN — **J1, J2 (batch 3a) & J7 (batch 4) sudah DIPERBAIKI**; **J3–J6, J8–J10
masih hidup.**
Diverifikasi terhadap kode di branch `patch` @ `a842e35`.
**Cakupan:** `database/SignalLogRepository.kt` · `database/TradeHistoryRecorder.kt` ·
`trading/TradeSignalSnapshot.kt` · `viewmodel/SignalLogViewModel.kt` ·
`viewmodel/TradingViewModelSync.kt` · `ui/screens/SignalLogScreen.kt` ·
`ui/components/detail/RadarSellSection.kt` · `ui/components/detail/sell/SellCalculationCard.kt`

> Semua temuan di bawah **diverifikasi langsung ke kode** (bukan kutipan memori).
> Setiap baris kode dirujuk dengan path + nomor baris pada saat audit.

### Catatan pengukuran

`app/build/test-results/testDebugUnitTest/` yang ada di repo **basi** (hasil 2026-10-09, 358
test — sebelum batch 1 & 2 notifikasi), dan sandbox audit ini tidak punya Android SDK sehingga
`./gradlew testDebugUnitTest` tidak bisa dijalankan. Ukur baseline ulang di mesin ber-SDK.

---

## 1. Peta jalur (hasil pemetaan)

```
PRODUKEN SINYAL
├─ engine/LearningTradingEngine.kt:251,284,328,372  → marketSymbol = tick.symbol  (BENAR)
└─ viewmodel/TradingViewModelSync.kt:664-684         → menimpa marketSymbol dgn selectedPair (J1)

JURNAL SINYAL (signal_logs)
├─ recordSignal()      :59-194   insert / update (entryPrice beku, TP/SL bisa berubah)
├─ processPriceTick()  :217-241  per simbol + bursa
├─ processBatchTicks() :246-269  per bursa
├─ updateTrackingLogWithPrice() :271-341  → status HIT_TP1/TP2/SL/EXPIRED + realizedPnlPct
└─ resolveLogManually() :346-…   resolusi manual

KONSUMEN ANGKA
├─ ui/screens/SignalLogScreen.kt:1587-1596   label "Net" untuk angka yang sebenarnya KOTOR
├─ calculateReliabilitySummary() :670-700     win/loss/avg/profitFactor
└─ buildTierStats()             :804-832     tabel tier confidence (tab yang dipakai N11)
    (bandingkan buildGroupStats() :770-803    — satu-satunya yang SUDAH benar & teruji)

JURNAL TRADE (trade_history_records)
├─ recordBuy()  :…  ·  recordSell() :258-…   ⚠️ ada fallback lintas bursa
└─ processPriceTick() :152-…                ⚠️ ada fallback lintas bursa
```

---

## 2. 🔴 Temuan kritis

### J1 — Jurnal menampilkan P&L **KOTOR** dengan label **"Net"**, dan statistik reliability dihitung dari angka itu

**Lokasi:**
- produsen: `database/SignalLogRepository.kt:271-277` → `:301-326` → `:335`
- konsumen: `ui/screens/SignalLogScreen.kt:1587-1596`

```kotlin
// SignalLogRepository.kt:271-277
private suspend fun updateTrackingLogWithPrice(log: SignalLogEntity, currentPrice: Double, now: Long) {
    val isBuy = log.action.equals("BUY", ignoreCase = true)
    val rawPnlPct = if (isBuy) {
        ((currentPrice - log.entryPrice) / log.entryPrice) * 100.0
    } else {
        ((log.entryPrice - currentPrice) / log.entryPrice) * 100.0
    }
```

`rawPnlPct` adalah **selisih harga mentah** — tanpa fee beli, tanpa fee jual, tanpa slippage.
Nilainya disalin ke `realizedPnlPct` (`:304`, `:312`, `:318`, `:323` → `:335`).

UI melabelinya "Net" dan **komentar di kode justru menyatakan hal yang salah**:

```kotlin
// SignalLogScreen.kt:1587-1596
// B6 — Label GROSS vs NET eksplisit. realizedPnlPct = NET (setelah fee); maxProfitPct /
// maxDrawdownPct = GROSS (kotor, sebelum fee). ...
"HIT_TP1" -> Triple("🎯 TARGET TP1 TERCAPAI", tvGreen, "Net +${String.format(Locale.US, "%.2f", log.realizedPnlPct ?: log.maxProfitPct)}%${if (log.realizedPnlPct == null) " (gross)" else ""}"),
"HIT_SL"  -> Triple("🛑 STOP LOSS TERSENTUH", tvRed,  "Net ${String.format(Locale.US, "%.2f", log.realizedPnlPct ?: log.maxDrawdownPct)}%${if (log.realizedPnlPct == null) " (gross)" else ""}"),
```

**Dampak.**
1. **Menyesatkan secara(display):** "Net +0,35%" padahal impas net Tokocrypto USDT ≈ **+0,82%**
   (lihat `workplan2.md` §24.4) — angka itu sebenarnya **rugi**.
2. **Statistik ikut tercemar:** `avgProfitPct`, `avgLossPct`, `profitFactor`
   (`SignalLogRepository.kt:687-699`, `:814-819`) dihitung dari angka yang sama, begitu pula
   "LAPORAN AUDIT DIAGNOSTIK KINERJA SINYAL" (`viewmodel/SignalLogViewModel.kt:64-88`).
   Tabel "Akurasi per Level Confidence" jadi bias optimistis **sistematis**.
3. Melanggar aturan **"SATU rumus P&L"** (AGENTS.md §24): di jurnal sinyal rumusnya
   `FeeSchedule.netProfitPct` (taker + slippage dua sisi), di sini tidak ada biaya sama sekali.

**Perbaikan.** `updateTrackingLogWithPrice` memakai `FeeSchedule.netProfitPctFor` dengan bursa +
kuotasi dari log; label UI tidak lagi menulis "Net" untuk angka yang belum netto — atau basisnya
dibuat benar-benar net. **Perhatikan efek amnesty:** banyak sinyal "menang" akan berubah jadi
"kalah"/`EXPIRED` negatif — itu memang kenyataannya, dan harus disebut eksplisit di laporan.

**Sifat:** display + statistik ⇒ **wajib kontrol negatif** (paksa basis KOTOR → test harus gagal).

> ✅ **DIPERBAIKI 2026-10-16 di `d97a539` (batch 3a)** — `realizedPnlPct` kini memakai
> `FeeSchedule.netProfitPctForDirected` (arah BUY/SELL dibedakan) lewat pembungkus tunggal
> `database/SignalLogPnl.kt` → `netSignalPnlPct`, dipasang di **tiga** jalur yang dulu masing-masing
> menulis rumusnya sendiri: `updateTrackingLogWithPrice`, `resolveLogManually`, dan
> `expireTrackingLogsForSymbol`. Label UI (`SignalLogScreen.signalPnlLabel`) memilih "Net"/"Gross"
> sesuai basis yang benar-benar dipakai, dan `EXPIRED` tanpa P&L menulis "Hasil tidak diketahui"
> alih-alih mengarang "0,00%". `maxProfitPct`/`maxDrawdownPct` **tetap kotor** sesuai aturan B6.
> Uji: `database/SignalOutcomeClassificationTest.kt`. **Efek amnesti:** win-rate jurnal akan turun —
> kenaikan +0,50% di Tokocrypto USDT sebenarnya −0,468950% net. Lihat
> `docs/LAPORAN-batch3-jurnal-2026-10-16.md` §3 dan §4.

---

### J2 — Tiga definisi W/L dalam satu berkas; dua tidak konsisten ⇒ satu log bisa dihitung MENANG **dan** KALAH

**Lokasi:**
- (a) **sudah benar & teruji:** `SignalLogRepository.kt:777-783` (`buildGroupStats`)
- (b) **tidak konsisten:** `SignalLogRepository.kt:814-815` (`buildTierStats`)
- (c) **tidak konsisten:** `SignalLogRepository.kt:676-681` (`calculateReliabilitySummary`)
- **presmisnya:** `SignalLogRepository.kt:117-146` (TP/SL diperbarui, `entryPrice` **tidak pernah**)

```kotlin
// (a) BENAR — eksklusif
val wins = resolved.filter {
    it.outcomeStatus in winStatuses || (it.outcomeStatus !in lossStatuses && pnl(it) > 0.0)
}
val losses = resolved.filter {
    it.outcomeStatus in lossStatuses || (it !in wins && pnl(it) < 0.0)
}
```

```kotlin
// (b) & (c) TIDAK eksklusif — dua filter independen
val wins = resolved.filter { it.outcomeStatus in listOf("HIT_TP1","HIT_TP2","MANUAL_WIN") || (it.realizedPnlPct ?: 0.0) > 0 }
val losses = resolved.filter { it.outcomeStatus in listOf("HIT_SL","MANUAL_LOSS") || (it.realizedPnlPct ?: 0.0) < 0 }
```

(`buildTierStats` memakai `count`, `calculateReliabilitySummary` memakai `filter` — logis sama.)

**Bagaimana skenario "dilihat dua kali" terjadi nyata:**

```kotlin
// SignalLogRepository.kt:117-119 — hanya TP/SL yang diperbarui …
val updatedTp1 = if (targetPrice1 > 0.0) targetPrice1 else primaryLog.targetPrice1
val updatedSl  = if (stopLoss > 0.0) stopLoss else primaryLog.stopLoss
// :126-146 — copy() TIDAK pernah menyalin entryPrice ⇒ entryPrice tetap milik sinyal PERTAMA
```

Log dibuat saat entry 100 / SL 90. Harga naik ke 200, sinyal baru terbit entry 200 / SL 190 ⇒
`stopLoss = 190`, `entryPrice` tetap 100. Harga turun ke 190 ⇒

```kotlin
// SignalLogRepository.kt:313-318
} else if (log.stopLoss > 0.0 && ((isBuy && currentPrice <= log.stopLoss) || ...)) {
    status = "HIT_SL"
    realizedPnl = rawPnlPct      // ← (190 − 100)/100 = +90%
```

`HIT_SL` **dan** `realizedPnlPct = +90%` ⇒ masuk `wins` (pnl > 0) **dan** `losses` (status).
Akibatnya `winCount + lossCount > resolvedCount`, dan

```kotlin
val winRatePct = if (resolvedCount > 0) (winCount.toDouble() / resolvedCount) * 100.0 else 0.0
```

bisa **melebihi 100%**. Di layar (`SignalLogScreen.kt:1530-1540`) user bisa membaca
**"1W / 1L · 100,0% Win"** untuk satu sinyal yang resolved, dan "Rata-rata Menang/Kalah"
(`SignalLogScreen.kt:1245-1250`) dihitung dari himpunan yang tumpang tindih.

**Perbaikan.** satu fungsi predikat (mis. `classifyOutcome(log): Outcome`) dipakai ketiga
call site; dan `recordSignal` tidak boleh mencampur `entryPrice` lama dengan TP/SL baru —
kalau entry berubah, log lama di-`resolve` dan log baru dibuat.

**Sifat:** angka salah di layar ⇒ **wajib kontrol negatif** (log `HIT_SL` dengan P&L positif harus
**tidak** masuk `wins`; `winRatePct` tidak boleh > 100 untuk sampel mana pun).

> ✅ **DIPERBAIKI 2026-10-16 di `d97a539` (batch 3a)** — satu predikat eksklusif `classifySignalOutcome`
> (`database/SignalOutcome.kt`) dipasang di **empat** call site: `calculateReliabilitySummary`,
> `buildTierStats`, `buildGroupStats`, dan `bestSymbol`/`bestStrategy` (yang terakhir dulu memakai
> definisi **keempat** sendiri). **Status menang/kalah menang atas tanda P&L**, sehingga log `HIT_SL`
> ber-P&L positif dihitung kalah dan tidak lagi masuk dua keranjang sekaligus.
> Akar penyebabnya juga ditutup: `recordSignal` memisahkan episode bila `entryPrice` bergerak lebih
> dari `ENTRY_SPLIT_TOLERANCE = 0,8%` (`hasEntryDrifted`) — log lama di-`EXPIRED` pada harga terkini
> memakai `entry`/TP/SL miliknya sendiri (`resolveSupersededTrackingLog`), bukan digabung.
> `avgProfitPct`/`avgLossPct`/`maxSingleWinPct`/`maxSingleLossPct` kini hanya dihitung dari log yang
> P&L-nya **searah** keranjangnya. Uji: `database/SignalOutcomeClassificationTest.kt` (14 uji).
> Lihat `docs/LAPORAN-batch3-jurnal-2026-10-16.md` §2.

---

## 3. 🟠 Temuan serius

### J3 — `SignalLogViewModel` mengikat bursa **sekali saat konstruksi** ⇒ tabel log & tier menampilkan bursa lama

**Lokasi:** `viewmodel/SignalLogViewModel.kt:22-34`

```kotlin
private val prefs = AppPreferences(application)

val currentExchange: String get() = prefs.marketDataSource.name          // ← getter, tapi…

val allSignalLogs: StateFlow<List<SignalLogEntity>> =
    signalLogRepository.getLogsByExchangeFlow(currentExchange)            // ← dievaluasi SEKALI
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val signalReliabilitySummary: StateFlow<SignalReliabilitySummary> =
    signalLogRepository.getReliabilitySummaryByExchangeFlow(currentExchange)   // ← SEKALI
```

Parameter Room terikat saat flow dibuat (`database/SignalLogRepository.kt:40-41` →
`dao.getLogsByExchangeFlow(exchange.uppercase())`), jadi mengganti bursa **tidak** mengulang
subscription. ViewModel ini dibuat sekali di `viewmodel/TradingViewModel.kt:52` dan bertahan
sepanjang activity.

Jejak bahwa pola reaktif pernah dimaksudkan: `flatMapLatest` di-import (`:18`) tapi **tidak pernah
dipakai**. Sumber reaktif sudah tersedia (`TradingViewModel.kt:198` punya
`marketDataSource: StateFlow<MarketDataSource>`).

**Dampak.** User membuka app di Tokocrypto → pindah ke Indodax → membuka tab "Log & Evaluasi
Sinyal": seluruh log sinyal **dan** tabel "RELIABILITAS SKOR KEYAKINAN" masih berisi data
Tokocrypto, tanpa label penjelas. Melanggar aturan isolasi exchange untuk state.

**Perbaikan.** `marketDataSource.flatMapLatest { repo.get…Flow(it.name) }.stateIn(…)`.

---

### J4 — Jurnal & kartu riwayat memakai `selectedPair`, bukan simbol yang benar-benar dievaluasi engine

**Lokasi:** `viewmodel/TradingViewModelSync.kt:664-684`; engine sudah benar di
`engine/LearningTradingEngine.kt:251`, `:284`, `:328`, `:372`

```kotlin
// TradingViewModelSync.kt:671
list.add(0, signal.copy(marketSymbol = selectedPair.value.symbol))   // ← menimpa field yang SUDAH BENAR
// TradingViewModelSync.kt:676-678
signalLogRepository.recordSignal(
    symbol = selectedPair.value.symbol, action = signal.action.name,
    strategyMode = strategyMode.value.name, confidence = signal.confidence,
    entryPrice = if (signal.entryPrice > 0) signal.entryPrice else (currentTick.value?.price ?: 0.0),
    targetPrice1 = signal.targetPrice1, targetPrice2 = signal.targetPrice2, stopLoss = signal.stopLoss,
```

`selectPair()` mengganti pair lebih dulu, sedangkan `engine.signalState.value` masih berisi hasil
evaluasi pair **sebelumnya** sampai tick berikutnya. Selama jendela itu:

1. **Baris 676** menulis row jurnal dengan `symbol` pair **baru**, sementara
   entry/TP1/TP2/SL/confidence milik pair **lama**.
2. **Baris 671** men-stempel `marketSymbol` pair baru, dan
   `ui/components/SignalHistoryPanel.kt:74` memfilter berdasarkan `marketSymbol` sementara `:206`
   mencetak `currentSymbol` ⇒ **kartu "RIWAYAT & LOG SINYAL" untuk ETH menampilkan sinyal BTC**
   lengkap dengan harga BTC dan `Skor ${signal.confidence}/100` milik BTC.

Ini adalah manifestasi langsung dari **N10** (tidak ada verifikasi `signal.marketSymbol == symbol`);
N10Mutation-nya: field-nya sudah benar, tapi ditimpa.

**Perbaikan.** pakai `signal.marketSymbol` (dengan fallback ke simbol yang dievaluasi engine),
dan validasi `== symbol` sebelum mencatat (perbaiki N10 sekalian).

---

### J5 — Pelanggaran isolasi exchange di jurnal trade (fallback lintas bursa)

**Lokasi:** `database/TradeHistoryRecorder.kt:265-271` dan `:156-161`;
query-nya `database/TradeHistoryRecordEntity.kt:166-167` dan `:172-173`

```kotlin
// TradeHistoryRecorder.kt:265-271 (recordSell)
val targetRecord = if (tradeUuid != null) {
    dao.getRecordByUuid(tradeUuid)
} else {
    dao.getActiveHoldingForSymbolAndExchange(normSymbol, isReal, normExchange)
        ?: dao.getActiveHoldingForSymbol(normSymbol, isReal)   // ← TANPA filter exchange
}
```

```kotlin
// TradeHistoryRecorder.kt:156-161 (processPriceTick)
val holdings = dao.getHoldingRecordsByExchange(normExchange)
    .filter { it.symbol.equals(normSymbol, ignoreCase = true) }
    .ifEmpty {
        dao.getHoldingRecords().filter { it.symbol.equals(normSymbol, ignoreCase = true) }  // ← SEMUA bursa
    }
```

**Mengapa ini nyata di domain ini:** Tokocrypto melayani **USDT + IDR**, Indodax **hanya IDR**
(AGENTS.md §"Batasan pasar"). Jadi simbol bergaya `…IDR` — `BTCIDR`, `ETHIDR`, `XAUTIDR` —
**ada di kedua bursa sekaligus**. Yang berbeda adalah pasarnya, bukan simbolnya.

**Dampak.**
1. Jual `BTCIDR` di Indodax sementara record HOLDING `BTCIDR` ada di Tokocrypto ⇒ fallback menutup
   **record bursa yang salah**, dengan `sellPrice`/`customPnlIdr` dari bursa Indodax.
2. `processPriceTick`: bila bursa A tidak punya record HOLDING untuk simbol itu, harga dari bursa
   **B** memperbarui `peakPriceDuringHold`/`troughPriceDuringHold`/`maxProfitPct` record bursa A
   (`:165-186`) ⇒ statistik MFE/drawdown per posisi tercemar.

**Yang sudah benar (pola yang harus ditiru):** `SignalLogRepository.processPriceTick:231-233`
memakai query berversi exchange **tanpa fallback**, dengan komentar eksplisit.

---

### J6 — Klaim statistik historis hardcoded di UI, tanpa syarat dan tanpa data

**Lokasi:** `ui/screens/SignalLogScreen.kt:1349-1354`

```kotlin
Text(
    text = "Skor keyakinan tinggi (≥80%) menunjukkan rasio kemenangan tertinggi dan drawdown terendah pada eksekusi real-time.",
    color = TvTextSecondary, fontSize = 8.5.sp, lineHeight = 11.sp
)
```

Melanggar bagian "Yang tidak boleh dilakukan agen" di AGENTS.md: *"Menulis di UI bahwa sinyal punya
winrate/expectancy historis tanpa data cukup."*

**Yang membuatnya lebih berbahaya, bukan lebih ringan:**
- **N11** membuktikan tier ≥80 nyaris mustahil diisi sinyal scalping pra-entry
  (`engine/scalping/ScalpingMtfEvaluator.kt:434-446` ⇒ cap ≤59 kecuali READY). Jadi yang diukur
  bukan "kualitas keyakinan", melainkan **"apakah sudah READY"**.
- Angka P&L di tabel itu **kotor** (J1) dan W/L-nya bisa tumpang tindih (J2) ⇒ landasan
  statistiknya memang tidak dapat dipakai.
- Kalimatnya tanpa kondisi: kalau tidak ada data, sampel 1, atau semua tier kosong, teks tetap tercetak.

**Perbaikan.** hapus klaim, atau ganti dengan deskripsi netral yang menyebut basis + jumlah sampel,
dan gate tampilnya pada sampel cukup. Selesaikan setelah J1 & J2 karena keduanya menentukan
apakah tabel itu layak dikutip.

---

### J7 — Radar jual **belum** ikut basis FSM; kartu menulis "sudah bersih fee & pajak"

**Lokasi:** `ui/components/detail/RadarSellSection.kt:95-102`, `ui/components/detail/sell/SellCalculationCard.kt:250-302`

```kotlin
// RadarSellSection.kt:95-102
val grossSellValueIdr = activeSellQty * effectiveSellPrice
val sellFeeIdr = grossSellValueIdr * (activeFeePct / 100.0)
val netReceivedSellIdr = (grossSellValueIdr - sellFeeIdr).coerceAtLeast(0.0)
val costBasisIdr = activeSellQty * effectiveBuyPrice          // ← TANPA fee beli
val netProfitIdr = if (effectiveBuyPrice > 0.0) netReceivedSellIdr - costBasisIdr else 0.0
val netProfitPct = if (costBasisIdr > 0.0) (netProfitIdr / costBasisIdr) * 100.0 else 0.0
```

```kotlin
// SellCalculationCard.kt:293-298
text = if (isProfitable) {
    "Hasil bersih melebihi modal (sudah bersih fee & pajak)"
} else {
    "Hasil bersih di bawah modal (termasuk fee & pajak)"
}
```

Basis di sini: **hanya fee jual**; tidak ada fee beli, tidak ada slippage dua sisi.
`workplan2.md` §24.3 mengklaim radar jual sudah naik ke basis FSM (0,3322% → 0,8119%), dan
`workplan2.md` §24.1 sempat menulis basis lama radar sebagai "fee jual maker saja" — kode hari ini
justru **tidak memakai maker maupun slippage**. Drift yang tersisa:

| Pair | Friksi di kartu | Friksi agreed (FSM) | Selisih |
|---|---|---|---|
| Tokocrypto IDR taker | 0,4322% | 0,8186% | **0,3864 pp** |
| Tokocrypto USDT taker | 0,4044% | **0,9735%** | **0,5691 pp** |

> **Koreksi angka di tabel di atas (2026-10-16, batch 4).** Baris Tokocrypto USDT semula menulis
> friksi FSM **0,8226%** (selisih 0,4182 pp). Angka itu tidak cocok dengan rumus mana pun: bentuk
> aditif memberi 0,9688%, bentuk multiplikatif — yang dipakai `FeeSchedule` dan sudah terdokumentasi
> sebagai 0,9735% di `workplan2.md` §24 dan `NetPnlParityTest` — memberi **0,9735%**. Selisih yang
> benar **0,5691 pp**. Arah temuan tidak berubah. Rincian: `docs/LAPORAN-batch4-j7-2026-10-16.md` §2.

**Dampak.** "JUAL UNTUNG (PROFIT) … sudah bersih fee & pajak" padahal angka tersebut belum
memotong fee beli maupun slippage ⇒ **klaim tidak jujur** di kartu yang paling sering dipakai user
sebelum menekan tombol jual, dan berbeda dari angka yang dipakai FSM di halaman yang sama.

**Perbaikan.** `RadarSellSection` memakai `FeeSchedule.costBasisNet` + `netSellValue` dengan tarif
dari `fees` yang sudah diterima (dan slippage dari `scalpingRuntimeConfig`), atau cukup
`FeeSchedule.netProfitPct(entry, price, buyTakerPct, sellTakerPct, slippagePct)`.

> ✅ **DIPERBAIKI 2026-10-16 di `d1097aa` (batch 4)** — rekomendasi di atas diikuti, tetapi lewat **resolver murni
> baru** `trading/SellEstimate.kt` (`SellEstimateResolver.resolve`) supaya bisa diuji tanpa Compose:
> `RadarSellSection` berhenti menghitung sama sekali, `SellCalculationCard` menerima satu objek
> `SellEstimate`.
>
> Basis yang dipakai: fee **jual** = tarif order type terpilih (maker/taker, itu yang benar-benar
> dibayar); fee **beli** = tarif **taker** (order beli sudah terjadi, jenisnya tak diketahui — asumsi
> terburuk sekaligus basis FSM); **slippage** dua sisi dari `scalpingRuntimeConfig.slippagePct`.
> Akibatnya pada mode taker kartu kini sama persis dengan FSM (IDR 0,8186%, USDT 0,9735%). Mode maker
> sengaja tetap berbeda 0,1012 pp (0,7174% vs 0,8186%) — pengguna memang memilih limit order.
>
> Klaimnya juga diperbaiki: `"sudah bersih fee & pajak"` → `"setelah biaya beli, biaya jual, pajak &
> slippage"`, judul `"Potongan Biaya & Pajak (0,43%)"` → `"Potongan Biaya, Pajak & Slippage"` dengan
> sub-label yang menyebut komponennya satu per satu, dan saat modal belum diketahui kartu berkata
> jujur `"(biaya beli belum diketahui)"`.
>
> Efek amnesti: pada bruto **+0,50%** kartu dulu menulis **+0,0656% "UNTUNG"**, sekarang
> **−0,3160% RUGI**. Ambang pertama "JUAL UNTUNG" naik dari ≈+0,4341% ke ≈+0,8186%.
>
> Uji: `app/src/test/java/agu/analys/trading/SellEstimateTest.kt` (17 kasus). Kontrol negatif:
> resolver dikembalikan ke rumus lama + label lama ⇒ **13 dari 17 uji GAGAL**.
> Rincian: `docs/LAPORAN-batch4-j7-2026-10-16.md`.

---

## 4. 🟡 Temuan sedang

| # | Temuan | Lokasi | Dampak |
|---|---|---|---|
| J8 | Rumus P&L ditulis ulang sendiri, **tanpa slippage**, di jalur eksekusi trade real | `viewmodel/TradingViewModelSync.kt:54-64` | `customPnlPct` di jurnal trade real berbeda ≈ **0,16 pp** dari `FeeSchedule.netProfitPctFor` yang dipakai halaman portofolio/radar jual (2 × slippage 0,08%) — pelanggaran "SATU rumus P&L"; pengecualian yang ada di AGENTS hanya untuk `SimulationTradeStore`/`SimulationOrderEngine` |
| J9 | N9 — `recordBuy` masih punya default `signalConfidence = snapshot?.confidenceScore ?: 75` | `database/TradeHistoryRecorder.kt:61` | Mengarang skor saat snapshot null (perangkap #17). Saat ini **latent**: dua pemanggil (`TradingViewModelSync.kt:262`, `:114`… atau equivalents) mengirim eksplisit — tapi jebakan masih "tidur" |
| J10 | N10 — `TradeSignalSnapshot.capture` memakai `engine.signalState.value` tanpa verifikasi `signal.marketSymbol == symbol` | `trading/TradeSignalSnapshot.kt:275` | Manifestasinya adalah J4: confidence/TP/SL pair lain ikut tercatat di jurnal |

---

## 5. Yang sudah benar (jangan diutak-atik)

- **B5:** confidence di signal-log = nilai saat firing, bukan MAX — `SignalLogRepository.kt:107-111`.
- **B3:** sinyal `EXPIRED` memakai harga terakhir, bukan `peakPrice`.
- **B4:** `resolveLogManually` tidak lagi mengarang besaran (clamp ±1,5% dihapus) —
  `SignalLogRepository.kt:356-366`.
- **B6:** label gross/net sudah ada di `maxProfitPct`/`maxDrawdownPct` (yang **tetap** kotor dan
  berlabel gross) — J1 hanya menyangkut `realizedPnlPct`.
- **B1:** fallback yang mengarang trade sudah dihapus di `recordSell` (tetap ada fallback bursa J5).
- `buildGroupStats` (`:770-803`) adalah **satu-satunya** definisi W/L yang eksklusif — harus jadi
  acuan, dan sudah dijaga `app/src/test/java/agu/analys/database/SignalLogSetupStatsTest.kt:84`.
- `SignalLogRepository.processPriceTick` sudah benar memakai query berversi exchange **tanpa
  fallback lintas bursa** (`:231-233`) — pola yang harus ditiru `TradeHistoryRecorder`.
- Anti-spam log memakai bursa yang sama (`:152-164`) — benar.

---

## 6. Rekomendasi perbaikan (urut nilai ÷ risiko)

| Urut | Temuan | Alasan |
|---|---|---|
| 1 | **J2** | Angka win-rate bisa salah secara struktural (>100%) — fondasi semua statistik |
| 2 | **J1** | Label "Net" untuk angka kotor = menyesatkan + statistik bias optimistis |
| 3 | **J5** | Jurnal bisa merekam bursa yang salah — merusak audit |
| 4 | **J4 + J10** | Data pair salah masuk jurnal & tampil di kartu riwayat |
| 5 | **J3** | Tabel menampilkan bursa yang salah |
| 6 | **J7** | Kartu jual mengklaim bersih biaya padahal tidak — ✅ **selesai di `d1097aa` (batch 4)** |
| 7 | **J8** | Basis P&L berbeda dari yang disepakati |
| 8 | **J6** | Klaim statistik tanpa data (hapus setelah J1 & J2) |
| 9 | **J9** | Kebersihan / jebakan latent |

### Prasyarat

Satu definisi W/L (**`classifyOutcome`**) dan satu sumber basis P&L harus lahir lebih dulu; J1,
J2, J6, J7 semuanya bergantung pada keduanya. Setiap perbaikan **wajib** kontrol negatif dan angka
test **diukur** dari XML.

---

## 7. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit read-only jurnal sinyal, jurnal trade, dan angka P&L (branch `patch` @ `a842e35`) |
| 2026-10-16 | Temuan diekspor ke `docs/bugs/temuan_bugs_jurnal_pnl.md` (dokumen ini) — **belum ada perbaikan kode** |
| 2026-10-16 | **Batch 3a:** J2 + J1 diperbaiki + diuji di `d97a539` (14 uji baru, kontrol negatif 9/22 gagal). Lihat `docs/LAPORAN-batch3-jurnal-2026-10-16.md` |
| 2026-10-16 | **Batch 4:** J7 diperbaiki + diuji di `d1097aa` (17 uji baru, kontrol negatif 13/17 gagal). Sekaligus **koreksi angka audit**: friksi FSM Tokocrypto USDT adalah 0,9735%, bukan 0,8226%. Lihat `docs/LAPORAN-batch4-j7-2026-10-16.md` |