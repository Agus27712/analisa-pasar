# Workplan: Implementasi Lengkap Arsitektur Tokocrypto Official API (Dynamic Symbols & Symbol Type Routing)

## Objective
Mengintegrasikan arsitektur resmi Tokocrypto sesuai dokumentasi:
1. **Dynamic Symbol Discovery**: Menghilangkan hardcoded pair dan mengambil daftar symbol, filter trading (LOT_SIZE, PRICE_FILTER, MIN_NOTIONAL), dan precision secara dinamis dari `GET /open/v1/common/symbols`.
2. **Symbol Type Routing (Type 1 MBX vs Type 3 NextMe)**:
   - Type 1: REST market data via `https://www.tokocrypto.site/api/v3` (klines, depth, trades, aggTrades, executionRules)
   - Type 3: REST market data via `https://cloudme-toko.2meta.app/api/v1` (klines, depth, aggTrades) & `https://www.tokocrypto.com/open/v1/market/trades`
   - Fallback: Binance Cloud REST & WebSocket
3. **Pemisahan 3 Jalur (3-Layer Architecture)**:
   - **Jalur 1: REST Market Data**: Inisialisasi snapshot historical klines, depth, execution rules, dan sync symbols.
   - **Jalur 2: WebSocket Market Data**: Live klines (`<symbol>@kline_<interval>` dengan deteksi `k.x`), live trades (`<symbol>@trade`), live aggTrades, dan live depth (`<symbol>@depth`).
   - **Jalur 3: User WebSocket & Signed REST**: `POST /open/v1/user-listen-token` untuk user WebSocket, orders (`POST /open/v1/orders`), saldo spot (`GET /open/v1/account/spot` & `/account/spot/asset`), dan trade history (`GET /open/v1/orders/trades`).
4. **Order Engine & Filter Validation**:
   - Validasi LOT_SIZE (`stepSize`, `minQty`, `maxQty`), PRICE_FILTER (`tickSize`), MIN_NOTIONAL, dan PRICE_RANGE execution rules.
5. **UI & State Ingestion**:
   - Sinkronisasi dynamic symbol repository ke Dashboard, Watchlist, Search, Screener, Dialog Tambah Koin, dan Trading Detail.

## Tahapan Implementasi:

### Tahap 1: Models & Symbol Repository
- [x] Buat `TokocryptoModels.kt` (TokocryptoSymbolInfo, TokocryptoFilter, TokocryptoExecutionRule, TokocryptoOrderRequest, TokocryptoOrderResponse).
- [x] Buat `TokocryptoSymbolRepository.kt` untuk fetch dynamic symbols dari `GET https://www.tokocrypto.com/open/v1/common/symbols`, caching lokal, symbolType resolver, filter checking, dan search.

### Tahap 2: REST Market Service & Type-Aware Routing
- [x] Update `TokocryptoMarketService.kt`:
  - Fetch Server Time (`GET /open/v1/common/time`).
  - Dynamic route klines, depth, trades, aggTrades berdasarkan `symbolType` (Type 1 vs Type 3) dengan fallback Binance.
  - Fetch Execution Rules (`GET /api/v3/executionRules`) untuk price range guard.
  - Market Rankings dinamis dari seluruh pair aktif yang didapat dari `/common/symbols`.

### Tahap 3: WebSocket Market Stream & User WebSocket
- [x] Update `TokocryptoMarketWebSocket.kt`:
  - Support stream format resmi Tokocrypto (`wss://stream-cloud.tokocrypto.site/stream`) dengan fallback Binance WebSocket (`wss://stream.binance.com:9443/ws`).
  - Support multi-stream: `<symbol>@kline_<interval>`, `<symbol>@trade`, `<symbol>@aggTrade`, `<symbol>@depth`.
  - Handle `k.x` boolean untuk deteksi penutupan candle.
- [x] Buat `TokocryptoUserWebSocket.kt`:
  - Request `user-listen-token` via `POST /open/v1/user-listen-token`.
  - Listen ke real-time account & order execution events.

### Tahap 4: Order & Account API with Validation
- [x] Update `TokocryptoTradeApi.kt`:
  - Create Order (`POST /open/v1/orders`) dengan parameter lengkap (`quantity`, `quoteOrderQty`, `price`, `stopPrice`, `clientId`, `side`, `type`).
  - Query Order (`GET /open/v1/orders/detail`), All Orders (`GET /open/v1/orders`), Cancel Order (`POST /open/v1/orders/cancel`).
  - Get Account (`GET /open/v1/account/spot`), Asset (`GET /open/v1/account/spot/asset`), Trades (`GET /open/v1/orders/trades`).
  - Order Validator memeriksa LOT_SIZE, PRICE_FILTER, MIN_NOTIONAL sebelum submit.

### Tahap 5: Integrasi UI & State Coordinator
- [x] Update `MarketDataCoordinator.kt` dan `MarketViewModel.kt` untuk mengonsumsi dynamic symbol discovery dari Tokocrypto.
- [x] Update `AddAssetDialog.kt` agar menampilkan dynamic pairs dari Tokocrypto dengan filter BIDR, USDT, BTC dan pencarian instan.
- [x] Update `RealTradeCoordinator.kt` untuk eksekusi order Tokocrypto dengan order validation.
- [x] Verifikasi `compile_applet` dan unit test.
