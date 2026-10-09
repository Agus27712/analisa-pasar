# Laporan Bug — analisa-pasar (Terverifikasi Ulang)

Tanggal: 2026-10-09
Metode: baca kode langsung (tanpa eksekusi test — Android SDK tidak tersedia di environment ini, `ANDROID_HOME` hilang sehingga `./gradlew :app:testDebugUnitTest` gagal konfigurasi).
Status: **sudah diperbaiki (2026-10-09). Lihat bagian D.**

> Catatan: laporan pertama (12 temuan) sudah diverifikasi ulang. 5 klaim **gugur** (false positive), 7 **terkonfirmasi** di bawah.

---

## A. Bug Terkonfirmasi

### 1. [Tinggi] Orderbook Indodax tertulis ke cache Tokocrypto
- **File:** `app/src/main/java/agu/analys/viewmodel/MarketDataCoordinator.kt:414`
- **Bukti:**
  ```kotlin
  // di blok polling depth 20-detik:
  if (bids.isNotEmpty() || asks.isNotEmpty()) {
      engine.onOrderBookUpdate(bids, asks)
      agu.analys.data.OrderBookDepthCache.updateOrderBook(pair.symbol, bids, asks)
  }
  ```
  Signature di `app/src/main/java/agu/analys/data/OrderBookDepthCache.kt:42-46`:
  ```kotlin
  fun updateOrderBook(symbol: String, bids: ..., asks: ..., exchange: String = "TOKOCRYPTO")
  ```
- **Masalah:** saat `isToko == false` (Indodax), depth Indodax tetap disimpan dengan key `TOKOCRYPTO_*`. Melanggar aturan AGENTS.md (isolasi exchange).
- **Dampak:** pressure orderbook / sinyal scalping baca data bursa yang salah setelah polling REST.
- **Saran (belum dikerjakan):** teruskan exchange aktif, mis. `if (isToko) "TOKOCRYPTO" else "INDODAX"`.

### 2. [Tinggi] Histori & open-order IDR Tokocrypto tidak pernah diambil
- **File:** `app/src/main/java/agu/analys/viewmodel/RealTradeCoordinator.kt:413-417` dan `:288-292`
- **Bukti histori:**
  ```kotlin
  } else {
      // Tokocrypto: pair USDT
      val tokoSymbol = "${asset}_USDT".uppercase()
      val (ok, raw) = ... TokocryptoTradeApi.myTrades(apiKey, secretKey, tokoSymbol, limit = 100)
  ```
- **Bukti open-order fallback:**
  ```kotlin
  } else {
      val symUsdt = "${base}_USDT".uppercase()
      val (okSym, rawSym) = TokocryptoTradeApi.openOrders(apiKey, secretKey, symUsdt)
  ```
- **Masalah:** Tokocrypto mendukung USDT + IDR (AGENTS.md), tapi kedua jalur hanya query `*_USDT`. Pair `*_IDR` Tokocrypto tidak pernah dicek.
- **Dampak:** user trade `BTC_IDR` di Tokocrypto tidak dapat avg-price / histori / open-order IDR.

### 3. [Sedang] Filter kandidat histori case-sensitive bocor (`IDR`/`USDT` uppercase)
- **File:** `app/src/main/java/agu/analys/viewmodel/RealTradeCoordinator.kt:189` dan `:277`
- **Bukti:**
  ```kotlin
  val active = balance.filter { it.key != "idr" && it.value > 0.00000001 }
  balances.locked.filter { it.key != "idr" && it.key != "usdt" && it.value > 0.0 }
  ```
  Sedangkan `app/src/main/java/agu/analys/service/TokocryptoTradeApi.kt:345-350` menyimpan dual-casing:
  ```kotlin
  freeMap[assetLower] = free; freeMap[assetUpper] = free // dst. untuk total/locked
  ```
- **Masalah:** key `"IDR"` / `"USDT"` lolos filter, masuk kandidat → request sampah seperti `myTrades("IDR_USDT")` / `openOrders("IDR_USDT")`.
- **Dampak:** request sia-sia, potensi rate-limit, log error membingungkan.

### 4. [Sedang] `IndodaxTradeApiV2.decimal()` bikin `MarketDataCache` tiap order + rawan crash
- **File:** `app/src/main/java/agu/analys/service/IndodaxTradeApiV2.kt:654-657`
- **Bukti:**
  ```kotlin
  private fun decimal(value: Double, symbol: String, isPrice: Boolean): String {
      val meta = agu.analys.util.MarketDataCache(agu.analys.AppContextProvider.context)
          .loadPairsMetadata(agu.analys.config.MarketDataSource.INDODAX)
  ```
  `app/src/main/java/agu/analys/AppContextProvider.kt:7`: `lateinit var context: Context`.
- **Masalah:** (a) crash `UninitializedPropertyAccessException` bila dipanggil sebelum `AppContextProvider.init()` (mis. unit test), (b) IO SharedPreferences + `migrateLegacyPairsMetadata()` di setiap order.
- **Dampak:** latensi order, crash di test / edge startup.

### 5. [Sedang - Keamanan] Fallback ke SharedPreferences plaintext
- **File:** `app/src/main/java/agu/analys/util/AppPreferences.kt:19-49`
- **Bukti:**
  ```kotlin
  } catch (_: Exception) {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }
  ```
- **Masalah:** saat `EncryptedSharedPreferences` gagal (KeyStore corrupt), API key/secret exchange tersimpan tanpa enkripsi. `MasterKey` corrupt tidak dihapus sehingga gagal berulang tiap restart.
- **Dampak:** kredensial sensitif di disk plaintext.

### 6. [Rendah] LIMIT tanpa price lolos validasi tapi terkirim tanpa price
- **File:** `app/src/main/java/agu/analys/service/TokocryptoTradeApi.kt:444-447` vs `:484-486`
- **Bukti validasi:**
  ```kotlin
  price = request.price ?: referenceMarketPrice,
  ```
  **Bukti pengiriman:**
  ```kotlin
  if (request.price != null && request.price > 0 && !isMarket) {
      formParams.add("price" to ...)
  }
  ```
- **Masalah:** LIMIT dengan `price == null` lolos validasi (pakai reference) tapi terkirim tanpa field `price` → pasti ditolak exchange.
- **Catatan:** caller saat ini (`RealTradeExecutor.kt:198-205` dkk.) selalu isi price, jadi belum ketrigger. Kontrak API tetap berlubang.

### 7. [Rendah - Dead code] `synthesizeLiveCandle()` salah untuk H4/D1
- **File:** `app/src/main/java/agu/analys/util/MarketDataCache.kt:112-148`, khususnya `:124-131`
- **Bukti:**
  ```kotlin
  val candleMs = when (timeframe) {
      Timeframe.M1 -> 60_000L; Timeframe.M5 -> 300_000L
      Timeframe.M15 -> 900_000L; Timeframe.H1 -> 3_600_000L
      else -> 60_000L
  }
  ```
- **Masalah:** H4/D1 jatuh ke 60 detik → bikin bar baru tiap 60 dtk. Grep menunjukkan nol pemakaian; versi aktif yang benar adalah `CandleTimeUtil.synthesizeRealtimeCandles()` (sudah handle H4/D1 via `timeframeDurationMs()`).
- **Dampak:** tidak ada saat ini (dead code), tapi menyesatkan bila dipakai lagi.

---

## B. Klaim yang Gugur (false positive lama)

1. **MtfCacheManager backgroundJob bocor saat ganti exchange** — GUGUR. `updateExchange()` → `clear()` (`util/MtfCacheManager.kt:42-48, 284-292`) sudah `cancel()` `tier1Job` + `backgroundJob`.
2. **Format simbol Tokocrypto di MtfCacheManager salah** — GUGUR. `TokocryptoMarketService.fetchCandles()` konversi internal (`toTokocryptoSymbol()` / `toTokocryptoPair()`).
3. **myTrades tertukar Indodax/Tokocrypto** — GUGUR. Cabang `if (!isToko)` sudah benar; bug sebenarnya adalah A.2 (IDR hilang).
4. **`clearCacheForSource()` tidak cocok** — GUGUR. Key `dashboard_ticks_json_tokocrypto` cocok `contains("_tokocrypto")`, key `tokocrypto_pair_*` cocok `startsWith()`. (`util/MarketDataCache.kt:36-45` vs `:57-58, :94`).
5. **`OrderBookDepthCache` tanpa isolasi / double hard-stop / `extractQuote` / `getSymbolInfo` / race `ExchangeRateManager`** — GUGUR atau tak berdampak. Cache memang terpartisi via `buildKey()`; `clearAllState()` (UI) + `hardStopAndPurgeAll()` (coordinator+cache) saling melengkapi; hanya USDT+IDR yang didukung.

---

## C. Prioritas Saran Perbaikan (belum dikerjakan)

1. A.1 (exchange OrderBookDepthCache) → A.2 (IDR Tokocrypto) → A.3 (filter case) → A.4 (decimal cache) → A.5 (plaintext fallback).

---

## D. Status Perbaikan (2026-10-09)

1. A.1 — FIXED di `MarketDataCoordinator.kt`: `updateOrderBook(..., if (isToko) "TOKOCRYPTO" else "INDODAX")`.
2. A.2 — FIXED di `RealTradeCoordinator.kt`: histori `myTrades` Tokocrypto fetch `*_USDT` + `*_IDR` (avg per kuotasi, tidak dicampur); fallback `openOrders` cek dua-duanya.
3. A.3 — FIXED di `RealTradeCoordinator.kt`: filter quote pakai `lowercase() !in {idr,idrt,bidr,usdt,usdc,busd,usd}`, kandidat dinormalisasi lowercase + lookup saldo case-insensitive.
4. A.4 — FIXED di `IndodaxTradeApiV2.kt`: metadata pair di-cache in-memory 30 mnt (`loadPairsMetaSafe()`), aman bila `AppContextProvider.context` belum init (fallback ke cache/kosong, tidak crash).
5. A.5 — FIXED di `AppPreferences.kt`: sebelum retry, hapus `MasterKey` corrupt dari AndroidKeyStore; fallback plaintext diberi log error eksplisit.
6. A.6 — FIXED di `TokocryptoTradeApi.kt`: LIMIT tanpa `price` langsung ditolak validasi.
7. A.7 — FIXED di `MarketDataCache.kt`: `synthesizeLiveCandle()` pakai `CandleTimeUtil.timeframeDurationMs()` (H4/D1 benar).
