# Workplan: Pemilihan Sumber Data Tokocrypto (SSOT dengan Fallback Binance)

## Objective
Implementasikan pemilihan sumber data pasar (Exchange Source Selection) yang mendukung Tokocrypto sebagai SSOT utama dengan fallback Binance sesuai Aturan SSOT (#5 & #6), serta memungkinkan pengguna berganti sumber data (Tokocrypto vs Indodax) baik melalui Dashboard Header maupun menu Pengaturan.

## Tahapan Implementasi

### 1. Data Layer & Configuration
- [x] Analisis kebutuhan arsitektur dan aturan SSOT Tokocrypto + fallback Binance.
- [x] Update `AppConfiguration.kt` untuk menambahkan enum `MarketDataSource.TOKOCRYPTO` lengkap dengan label, default fee, dan deskripsi.
- [x] Update `TradingViewModels.kt` untuk menyertakan daftar pasangan koin populer Tokocrypto (BIDR & USDT) dan pemetaan simbol exchange.
- [x] Update `AppPreferences.kt` untuk menyimpan `tokocryptoApiKey`, `tokocryptoSecretKey`, dan pemilihan `marketDataSource` dengan default Tokocrypto.

### 2. Network Service & WebSocket
- [x] Buat `TokocryptoMarketService.kt`:
  - Fetch 24h Tickers (Tokocrypto OpenAPI dengan fallback ke Binance REST API `/api/v3/ticker/24hr`).
  - Fetch Klines / Candlesticks (interval 1m, 5m, 15m, 1h, 4h, 1d) dengan fallback Binance klines.
  - Fetch Order Book Depth (bids/asks).
  - Fetch Recent Trades.
  - Perhitungan Market Rankings (Top Gainers, Losers, Top Volume).
  - Validasi aset likuid `isSafeTradableAsset`.
- [x] Buat `TokocryptoMarketWebSocket.kt`:
  - Streaming real-time WebSocket ticker & klines via Binance Cloud / Tokocrypto WebSocket stream.
- [x] Buat `TokocryptoTradeApi.kt`:
  - Fetch Saldo Riil Spot Account via Tokocrypto HMAC-SHA256 authenticated API dengan fallback Binance.

### 3. ViewModel & Coordinator Integration
- [x] Update `MarketDataCoordinator.kt` untuk menangani switching dinamis antara Tokocrypto (dengan WebSocket/REST) dan Indodax.
- [x] Update `MarketViewModel.kt` untuk fetching market rankings dan scanning koin sesuai sumber data yang dipilih.
- [x] Update `RealTradeCoordinator.kt` untuk fetch saldo riil dari Tokocrypto saat sumber data Tokocrypto aktif.
- [x] Update `SettingsViewModel.kt` untuk persistensi dan broadcast perubahan sumber data.

### 4. UI Layer (Jetpack Compose)
- [x] Buat `DataSourceSelectionDialog.kt` untuk pemilihan cepat sumber data langsung dari Dashboard.
- [x] Update `DashboardModernHeader.kt` agar menampilkan badge sumber data aktif (Tokocrypto / Indodax) yang dapat diklik untuk memilih sumber data.
- [x] Update `SettingsCategoryDetailContent.kt` pada bagian "SUMBER PASAR (EXCHANGE)" agar menampilkan kartu pemilihan interaktif antara Tokocrypto dan Indodax.
- [x] Update `SetupRealApiDialog.kt` agar mendukung input API Key & Secret Tokocrypto maupun Indodax.

### 5. Verifikasi & Pengujian
- [x] Jalankan `compile_applet` untuk memastikan seluruh kode terkompilasi tanpa error.
- [x] Update `checkpoin.md` setelah seluruh implementasi selesai.
