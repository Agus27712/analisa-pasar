# Checkpoint: Pemusatan Pemilihan Exchange di Settings, Hard Stop Otomatis & Pembersihan Total Cache, serta Eliminasi Mutlak Fallback ke Binance

- **Tanggal / Waktu:** 2026-10-01
- **Status:** Selesai (Completed & Verified Build Clean)
- **Fitur:** Pemusatan Pemilihan Exchange Tunggal di Settings, Hard Stop Otomatis & Pembersihan Total Cache saat Simpan, serta Eliminasi Mutlak Fallback ke Binance
- **Komponen Terdampak & Perubahan:**
  1. **Penghapusan Jalur Pemilihan Exchange di Dashboard (`DashboardModernHeader.kt`, `DashboardScreen.kt`, `DataSourceSelectionDialog.kt`)**:
     - `DashboardModernHeader`: Menghapus event click, dialog popup, dan icon arrow dropdown. Header kini berfungsi murni sebagai indikator visual informatif mengenai bursa aktif saat ini.
     - `DashboardScreen`: Menghapus state `showDataSourceDialog`, lambda `onSelectDataSource`, dan komponen modal dialog exchange dari layar dashboard.
     - `DataSourceSelectionDialog.kt`: Berkas modal pemilihan exchange di dashboard dihapus permanen.
  2. **Pemusatan Pemilihan Tunggal di Settings & Hard-Stop Otomatis (`SettingsScreen.kt`, `SettingsCategoryDetailContent.kt`)**:
     - Kategori `SettingsCategory.TRADING` menjadi satu-satunya tempat untuk memilih bursa pasar (`Tokocrypto` vs `Indodax`).
     - Ditambahkan teks panduan eksplisit bahwa pergantian bursa akan memutus koneksi dan membersihkan cache secara total saat menekan tombol "Simpan Perubahan".
     - Pada `saveAllSettings()`, saat pengguna menekan tombol "Simpan Perubahan" dan mendeteksi perubahan bursa (atau force), fungsi `setMarketDataSource(selectedSource, forceHardStop = true)` dieksekusi secara otomatis.
  3. **Hard-Stop Jalur Koneksi & Pembersihan Cache Total (`MarketDataCoordinator.kt`, `MarketViewModel.kt`, `TradingViewModel.kt`)**:
     - `MarketDataCoordinator.hardStopAndPurgeAll()`:
       - Memutus seketika seluruh WebSocket aktif (`tokocryptoWebSocket.stop()`, `indodaxWebSocket.stop()`).
       - Membatalkan background polling jobs (`marketPollJob?.cancel()`, `dashboardPollJob?.cancel()`).
       - Reset total `uiPriceThrottler` dan membatalkan state koneksi live.
       - Mengosongkan data in-memory: `dashboardTicks`, `currentTick`, `recentCandles`, `recentPrices`, `orderBookBids`, `orderBookAsks`, `tradeStream`.
       - Mengeksekusi purge cache menyeluruh: `engine.resetForOffline()`, `OrderBookDepthCache.clear()`, `MtfCacheManager.clear()`, `TickHistoryTracker.clear()`, dan `MarketDataCache.clearCacheForSource()`.
     - `MarketViewModel.clearAllState()`: Mengosongkan map ticks, hot coins, gainers, losers, top volume, dan worth coins bursa lama.
     - Sambungan baru dibuka secara bersih dan segar HANYA untuk bursa baru yang dipilih.
  4. **Eliminasi Mutlak Fallback ke Binance (`TokocryptoMarketService.kt`, `TradingViewModels.kt`, `TradingViewFullscreenChart.kt`, `CandidateScanWorker.kt`, `TradingForegroundService.kt`, `RealTradeExecutor.kt`, `MtfCacheManager.kt`)**:
     - Memastikan seluruh REST dan WebSocket endpoint Tokocrypto mengarah murni ke host resmi Tokocrypto (`tokocrypto.com`, `tokocrypto.site`, `cloudme-toko.2meta.app`, `stream-cloud.tokocrypto.site`) tanpa satupun fallback ke `binance.com` atau `stream.binance.com`.
     - Mengubah fungsi dan variabel penamaan dari `toBinanceSymbol` menjadi `toTokocryptoSymbol` / `effectiveCompactSymbol`.
     - Menghapus aturan `binance.com` dari whitelist URL di `TradingViewFullscreenChart.kt`.
