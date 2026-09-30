# Checkpoint: Implementasi Pemilihan Sumber Data Tokocrypto

- **Tanggal / Waktu:** 2026-09-30
- **Status:** In Progress
- **Fitur:** Pemilihan Sumber Data Pasar dari Tokocrypto (SSOT dengan fallback Binance)
- **Komponen Terdampak:**
  1. `AppConfiguration.kt`: Enum `MarketDataSource.TOKOCRYPTO`.
  2. `TradingViewModels.kt`: Daftar pair Tokocrypto (BIDR & USDT) dan pemetaan simbol.
  3. `TokocryptoMarketService.kt`: Service REST API Tokocrypto dengan fallback otomatis ke Binance API.
  4. `TokocryptoMarketWebSocket.kt`: WebSocket real-time live feed Tokocrypto/Binance.
  5. `TokocryptoTradeApi.kt`: Fetch Saldo Riil Spot Tokocrypto terenkripsi.
  6. `AppPreferences.kt`: Preferensi sumber data aktif & kredensial Tokocrypto.
  7. `MarketDataCoordinator.kt` & `MarketViewModel.kt`: Routing data stream sesuai sumber yang dipilih.
  8. `DashboardModernHeader.kt` & `DataSourceSelectionDialog.kt`: UI pemilihan sumber data langsung di Dashboard.
  9. `SettingsCategoryDetailContent.kt`: UI pemilihan sumber data di menu Settings.
