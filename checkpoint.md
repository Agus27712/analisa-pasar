# Checkpoint: Eliminasi Pasangan Koin Berbasis BIDR & Pembatasan Khusus Pair IDR dan USDT

- **Tanggal / Waktu:** 2026-09-30
- **Status:** Selesai (Completed & Verified Tests Clean)
- **Fitur:** Eliminasi Menyeluruh Koin/Pair dengan Prefix & Quote BIDR Serta Pembatasan Pasangan Pasar Eksklusif IDR dan USDT
- **Komponen Terdampak & Perubahan:**
  1. **Konfigurasi Sumber Data (`AppConfiguration.kt`)**:
     - Memperbarui `MarketDataSource.TOKOCRYPTO`: `shortCode = "IDR"`, `defaultQuoteAsset = "IDR"`.
     - Memperbarui deskripsi Tokocrypto: "SSOT Utama: Data market Tokocrypto (Pair IDR/USDT) dengan real-time REST & WebSocket serta fallback Binance."
  2. **Daftar Pasangan & Model Simbol (`TradingViewModels.kt`, `TokocryptoModels.kt`)**:
     - `POPULAR_TOKOCRYPTO_PAIRS`: Mengganti semua pasangan lama berakhiran BIDR (`BTCBIDR`, `ETHBIDR`, dsb.) menjadi IDR (`BTCIDR`, `ETHIDR`, dsb.) dan `tokocryptoPair` menjadi format IDR (`BTC_IDR`, `ETH_IDR`, `USDT_IDR`, dsb.).
     - `POPULAR_INDODAX_PAIRS`: Memperbarui `tokocryptoPair` menjadi format IDR (misal `BTC_IDR`).
     - `fromCustomSymbol()`: Menangani pemetaan otomatis jika ada input simbol lama dengan suffix BIDR ke IDR.
     - `effectiveTokocryptoPair()` & `effectiveTradingViewSymbol()`: Membersihkan referensi BIDR dan mengarahkan ke IDR/USDT.
     - `TokocryptoSymbolInfo.toTradingPair()`: Konversi otomatis quote/base `BIDR` ke `IDR`.
  3. **Repository Simbol Dinamis (`TokocryptoSymbolRepository.kt`)**:
     - Membatasi kuotasi hanya untuk `IDR` dan `USDT` (`quoteAsset == "IDR" || quoteAsset == "USDT"`).
     - Mengeliminasi koin dengan baseAsset `BIDR`, symbol berawalan `BIDR` (`BIDRUSDT`, `BIDRIDR`, dsb.), atau berakhiran `BIDR` (dengan proteksi tetap menjaga koin IDR berakhiran B seperti `BNBIDR` dan `SHIBIDR`).
     - Membersihkan fallback Binance Exchange Info dan filter pencarian simbol.
  4. **Layanan Pasar & Feed Harga (`TokocryptoMarketService.kt`, `MarketViewModel.kt`, `TradingForegroundService.kt`)**:
     - Menambahkan fungsi pembantu presisi `isBidrSymbol()` dan `isIdrOrUsdtPair()` untuk mengeliminasi token BIDR dan kuotasi BIDR.
     - Memperbaiki `toTokocryptoPair()` dan `toBinanceSymbol()` agar mengarahkan pair IDR ke `_IDR` / `IDR`.
     - Memperbarui evaluasi filter `isSafeTradableAsset` dengan parameter `isIdrPair`.
     - Memperbaiki resolusi tick referensi `BTCIDR` dan `USDTIDR` di `MarketViewModel.kt`.
     - Mengganti referensi kandidat `${base}_BIDR` ke `${base}_IDR` di `TradingForegroundService.kt`.
  5. **Antarmuka Pengguna (`AddAssetDialog.kt`, `DashboardModernHeader.kt`, `DataSourceSelectionDialog.kt`)**:
     - Mengubah tab default pencarian pasangan dari `BIDR` menjadi `IDR` (`listOf("IDR", "USDT", "SEMUA")`).
     - Memperbarui label header dan dialog menjadi "SSOT · Pair IDR/USDT".
