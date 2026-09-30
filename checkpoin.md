# Checkpoint: Implementasi Pemilihan Sumber Data Tokocrypto

- **Tanggal / Waktu:** 2026-09-30
- **Status:** Selesai (Completed & Verified)
- **Fitur:** Pemilihan Sumber Data Pasar dari Tokocrypto (SSOT dengan fallback Binance)
- **Komponen & Arsitektur yang Diimplementasikan:**
  1. `AppConfiguration.kt`:
     - Penambahan enum `MarketDataSource.TOKOCRYPTO` lengkap dengan properti default (Quote BIDR, fee 0.10%, deskripsi SSOT).
  2. `TradingViewModels.kt`:
     - Koleksi `POPULAR_TOKOCRYPTO_PAIRS` (BTCBIDR, ETHBIDR, SOLBIDR, BNBBIDR, XRPBIDR, PEPEBIDR, USDTBIDR, dsb).
     - Helper dinamis `popularPairsForSource(source)` dan resolver simbol exchange (`effectiveTokocryptoPair()`, `effectiveBinanceSymbol()`).
  3. `TokocryptoMarketService.kt`:
     - Service REST API publik Tokocrypto Open API v1 dengan fallback otomatis ke Binance Cloud API (`/api/v3/ticker/24hr`, `/api/v3/klines`, `/api/v3/depth`, `/api/v3/trades`).
     - Pemeringkatan pasar otomatis (Top Gainers, Losers, 24H Volume) untuk pair BIDR & USDT.
     - Filter likuiditas & keamanan aset (`isSafeTradableAsset`).
  4. `TokocryptoMarketWebSocket.kt`:
     - WebSocket streaming live ticker & kline real-time via Binance Cloud/Tokocrypto stream dengan reconnect otomatis & heartbeat monitoring.
  5. `TokocryptoTradeApi.kt`:
     - Fetch Saldo Riil Spot Account via Tokocrypto HMAC-SHA256 signature API dengan fallback Binance Cloud.
  6. `AppPreferences.kt`:
     - Konfigurasi `tokocryptoApiKey` dan `tokocryptoSecretKey` terenkripsi dengan AES-256 GCM.
     - Default `marketDataSource` disetel ke `MarketDataSource.TOKOCRYPTO` (SSOT Utama).
  7. `MarketDataCoordinator.kt` & `MarketViewModel.kt`:
     - Routing otomatis polling dan live WebSocket feed ke service Tokocrypto atau Indodax sesuai pilihan pengguna.
  8. `DataSourceSelectionDialog.kt` & `DashboardModernHeader.kt`:
     - UI dropdown switcher di header Dashboard yang memudahkan pengguna memilih sumber data secara langsung dengan 1 tap.
  9. `SettingsCategoryDetailContent.kt` & `SettingsScreen.kt`:
     - Kartu interaktif pilihan sumber pasar (Tokocrypto vs Indodax) dengan status badge dan deskripsi lengkap di menu Pengaturan.
