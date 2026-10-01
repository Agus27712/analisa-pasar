# Checkpoint: Eliminasi Pasangan Koin Berbasis BIDR, Pembatasan Pair IDR/USDT, dan Pembersihan Fallback Binance

- **Tanggal / Waktu:** 2026-09-30
- **Status:** Selesai (Completed & Verified Build Clean)
- **Fitur:** Eliminasi Menyeluruh Koin/Pair BIDR, Pembatasan Pasangan Pasar Eksklusif IDR dan USDT, Serta Pembersihan Mutlak Network Fallback ke Binance
- **Komponen Terdampak & Perubahan:**
  1. **Konfigurasi Sumber Data (`AppConfiguration.kt`, `DataSourceSelectionDialog.kt`)**:
     - Memperbarui `MarketDataSource.TOKOCRYPTO`: `shortCode = "IDR"`, `defaultQuoteAsset = "IDR"`.
     - Memperbarui deskripsi Tokocrypto: "Data market Tokocrypto (Pair IDR/USDT) dengan real-time REST & WebSocket".
  2. **Daftar Pasangan & Model Simbol (`TradingViewModels.kt`, `TokocryptoModels.kt`)**:
     - `POPULAR_TOKOCRYPTO_PAIRS`: Mengganti semua pasangan lama berakhiran BIDR (`BTCBIDR`, `ETHBIDR`, dsb.) menjadi IDR (`BTCIDR`, `ETHIDR`, dsb.) dan `tokocryptoPair` menjadi format IDR (`BTC_IDR`, `ETH_IDR`, `USDT_IDR`, dsb.).
     - `effectiveTokocryptoPair()`, `effectiveBinanceSymbol()`, & `effectiveTradingViewSymbol()`: Menyediakan pembentuk nama simbol tanpa panggilan network ke Binance.
  3. **Repository & Service (`TokocryptoSymbolRepository.kt`, `TokocryptoMarketService.kt`, `TokocryptoTradeApi.kt`, `TokocryptoMarketWebSocket.kt`, `TokocryptoUserWebSocket.kt`)**:
     - Menghapus fungsi fallback network Binance (`fetchFromBinanceFallback`, `BINANCE_HOSTS`, `BINANCE_BASE_URL`, dan endpoint `stream.binance.com`).
     - Mengarahkan seluruh kueri REST dan WebSocket secara murni ke server Tokocrypto resmi (`tokocrypto.site`, `cloudme-toko.2meta.app`, `stream-cloud.tokocrypto.site`, `tokocrypto.com`).
     - Mempertahankan helper fungsi presisi `isBidrSymbol()`, `isIdrOrUsdtPair()`, `toTokocryptoPair()`, dan `toBinanceSymbol()` sebagai utility string formatting internal.

