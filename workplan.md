# Workplan: Implementasi Lengkap Arsitektur Tokocrypto Official API & Isolasi Data Multi-Exchange

## Objective
Mengintegrasikan arsitektur resmi Tokocrypto sesuai dokumentasi dan mengisolasi penuh data pasar, cache in-memory, storage, serta Room database antara Tokocrypto dan Indodax:
1. **Dynamic Symbol Discovery**: Mengambil daftar symbol, filter trading (LOT_SIZE, PRICE_FILTER, MIN_NOTIONAL), dan precision secara dinamis dari `GET /open/v1/common/symbols`.
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
5. **Isolasi Mutlak Cache & Database (Exchange Isolation)**:
   - Pemisahan total data cache in-memory, SharedPreferences storage, dan Room database antara Tokocrypto dan Indodax dengan wrapper prefix & query filtering.

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

### Tahap 6: Perbaikan Charting, MTF Engine, & Fullscreen Chart Zero-404
- [x] Refactor `MtfCacheManager.kt` untuk memanggil `TokocryptoMarketService.fetchCandles` secara eksklusif (SSOT Tokocrypto/Binance).
- [x] Standarisasi `CandleBar.timestamp` ke milidetik di seluruh service dan websocket agar sinkron dengan `CandleTimeUtil`, `MtfCacheManager`, dan `lightweight_chart.html`.
- [x] Perbaiki `TradingViewFullscreenChart.kt` dengan pemisahan mutlak berdasarkan exchange: Indodax memuat halaman chart resmi Indodax (`indodax.com/chart`), sedangkan Tokocrypto memuat widget TradingView Binance (`BINANCE:<BASE>IDR`/`USDT`).
- [x] Tambahkan overlay back button di `LandscapeChartScreen.kt` untuk navigasi layar penuh.
- [x] Bersihkan pemanggilan feed pasar Indodax di `LearningTradingEngine.kt`, `CandidateScanWorker.kt`, dan `TradingForegroundService.kt`.

### Tahap 7: Isolasi Total Cache & Room Database (Exchange Isolation)
- [x] Tambahkan kolom `exchange` pada seluruh entitas Room (`RealTradeEntity`, `RealOpenOrderEntity`, `SignalLogEntity`, `TradeHistoryRecordEntity`) dan naikkan versi DB ke 7 (angka ganjil).
- [x] Tambahkan kueri DAO berparameter `exchange` di `RealTradeDao`, `SignalLogDao`, dan `TradeHistoryRecordDao`.
- [x] Isolasi `SignalLogRepository.kt` dan `TradeHistoryRecorder.kt` agar sinyal dan rekaman eksekusi buy/sell terpisah per exchange.
- [x] Update `SpotPositionStore.kt` dengan prefix kunci `${exchange}_${real/sim}_${symbol}` untuk isolasi posisi spot dan trailing stop.
- [x] Update `SimulationTradeStore.kt` dengan prefix kunci `${exchange}_sim_*` untuk isolasi wallet saldo, orderbook, dan trade history simulasi.
- [x] Update `OrderBookDepthCache.kt` dan `MtfCacheManager.kt` dengan pemisahan kunci komposit in-memory `${exchange}_${symbol}`.
- [x] Partisi snapshot pair candles dan pairs metadata di `MarketDataCache.kt` serta filter exchange di `PriceAlertStore.kt`.
- [x] Verifikasi build via `compile_applet`.

### Tahap 8: Eliminasi Pasangan Koin Berbasis BIDR & Pembatasan Pair IDR/USDT Saja
- [x] Bersihkan `POPULAR_TOKOCRYPTO_PAIRS` dan `POPULAR_INDODAX_PAIRS` dari `BIDR` menjadi `IDR` dan `USDT` murni.
- [x] Konfigurasi `MarketDataSource.TOKOCRYPTO` defaultQuoteAsset dan shortCode menjadi `IDR`.
- [x] Terapkan filter ketat di `TokocryptoSymbolRepository.kt` agar hanya memuat pair dengan quote `IDR` dan `USDT`.
- [x] Eliminasi koin berbasis token BIDR (`BIDRUSDT`, `BIDRIDR`, dsb.) dan quote BIDR dengan proteksi nama koin asli seperti `BNBIDR` dan `SHIBIDR`.
- [x] Eliminasi pasangan koin BIDR dan pembersihan mutlak network fallback Binance pada `TokocryptoSymbolRepository`, `TokocryptoMarketService`, `TokocryptoTradeApi`, `TokocryptoMarketWebSocket`, dan `TokocryptoUserWebSocket`.
- [x] Verifikasi sukses `compile_applet`.

### Tahap 9: Pemisahan Mutlak Prefix Mata Uang ($ vs Rp), Dual Harga di Chart, dan Konversi Portofolio Live Exchange
- [x] **Pemisahan Prefix Kuotasi**:
  - Pasangan berkuotasi USDT selalu menampilkan dan memakai harga dalam `$` (baik di simulasi maupun portofolio).
  - Model `HoldingItem` diproteksi dengan `quoteAsset`, `totalValueInQuote`, dan `pnlInQuote` sehingga angka dollar tidak tercampur dengan Rupiah.
- [x] **State Portofolio & Konversi Otomatis**:
  - Saldo USDT ditambahkan pada `SimulationWallet` terpisah dari saldo IDR.
  - Implementasi auto-konversi: order simulasi koin USDT otomatis mengonversi saldo IDR jika saldo USDT tidak mencukupi berdasarkan kurs live exchange.
- [x] **Fitur Konversi di Portofolio**:
  - Pembuatan komponen `CurrencyConversionDialog.kt` dan kartu saldo USDT di `SimulationPortfolioView.kt` untuk konversi manual 2 arah (Rp ⇄ $).
  - Rate diambil murni dari exchange aktif (`USDTIDR` / `usdt_idr`) via `ExchangeRateManager` tanpa hardcode angka tetap.
- [x] **Pemisahan Saldo Real per Kuotasi**:
  - Real balance coordinator membedakan `realBalanceForQuote`: pair USDT mengambil saldo USDT exchange, pair IDR mengambil saldo IDR.
- [x] **Tampilan Dual Harga di Topbar Detail Chart**:
  - Pada `DetailTopBar.kt` dan `DetailPriceHeader`, pair berkuotasi USDT menampilkan harga `$` dan ekuivalen Rupiah (`≈ Rp ...`) di bawahnya berbasis kurs exchange real-time.
- [x] **Perbaikan & Audit Kode Error**:
  - Mengatasi konflik deklarasi dan sintaks unclosed lambda pada `SimulationOrderForm.kt`, `DetailChartScreen.kt`, `PortfolioScreen.kt`, `PortfolioComponents.kt`, `RealPortfolioView.kt`, `RealPortfolioSummaryCard.kt`, `MarketViewModel.kt`, dan `SimulationCoordinator.kt`.
- [x] **Verifikasi Build**:
  - `compile_applet` berhasil (Build succeeded).

### Tahap 10: Pemusatan Pemilihan Exchange di Settings, Hard Stop Otomatis & Pembersihan Total Cache saat Simpan, serta Eliminasi Mutlak Fallback ke Binance
- [x] **Pemusatan Pemilihan Tunggal di Settings**:
  - Hapus dialog pemilihan exchange di dashboard (`DataSourceSelectionDialog.kt` dihapus permanen).
  - Jadikan `DashboardModernHeader` sebagai indikator statis/informatif tanpa tombol dropdown.
  - Pusatkan satu-satunya pemilihan bursa di menu Settings (`SettingsCategory.TRADING`).
- [x] **Pemicu Hard-Stop & Pembersihan Total Cache saat Tap Simpan**:
  - Saat pengguna menekan "Simpan Perubahan" di Settings dan exchange berubah (atau dieksekusi), sistem memanggil `setMarketDataSource(selectedSource, forceHardStop = true)`.
  - Putus seketika WebSocket Tokocrypto & Indodax, matikan polling background jobs, dan reset price throttler.
  - Kosongkan in-memory StateFlow (`dashboardTicks`, `currentTick`, `recentCandles`, `orderBookBids/Asks`, dsb.).
  - Purge menyeluruh cache: `OrderBookDepthCache.clear()`, `MtfCacheManager.clear()`, `TickHistoryTracker.clear()`, dan `MarketDataCache.clearCacheForSource()`.
  - Hubungkan kembali koneksi baru HANYA ke bursa terpilih.
- [x] **Eliminasi Mutlak Fallback ke Binance**:
  - Pastikan tidak ada satupun request REST atau WebSocket yang mengarah ke `binance.com` atau `stream.binance.com`.
  - Ubah method helper `toBinanceSymbol` menjadi `toTokocryptoSymbol` dan `effectiveCompactSymbol`.
  - Bersihkan URL whitelist di `TradingViewFullscreenChart.kt`.
- [x] **Verifikasi Kompilasi**:
  - `compile_applet` berhasil (Build succeeded).

### Tahap 11: Sinkronisasi API Key/Secret & Routing Eksekusi Order Real Tokocrypto (Buy, Sell TP1/TP2, Direct Sell, Cancel, Query) di RealTradeExecutor
- [x] **Routing Dinamis Real Trade ke Tokocrypto**:
  - `RealTradeExecutor.executeRealSellOrders`: Mendeteksi bursa aktif via `prefs.marketDataSource`. Membaca kredensial Tokocrypto (`prefs.tokocryptoApiKey` & `prefs.tokocryptoSecretKey`), memeriksa saldo koin via `TokocryptoTradeApi.getAccount()`, dan mengeksekusi order sell (TP1, TP2, TP Full, ataupun Direct Sell) langsung ke `TokocryptoTradeApi.createOrder()`.
  - `RealTradeExecutor.executeAutoSellOnServer`: Mendukung routing eksekusi server TP Tokocrypto secara otomatis.
  - `RealTradeExecutor.executeCancelOrder`: Memanggil `TokocryptoTradeApi.cancelOrder()` saat bursa aktif adalah Tokocrypto.
  - `RealTradeExecutor.waitForBuyFill`: Memanggil `TokocryptoTradeApi.getOrder()` untuk polling status eksekusi BUY order Tokocrypto.
  - Sinkronisasi saldo lokal multi-quote IDR vs USDT setelah order filled/diekskusi.
- [x] **Penambahan Endpoint di `TokocryptoTradeApi.kt`**:
  - Implementasi fungsi `cancelOrder(apiKey, secretKey, symbol, orderId)` ke `POST /open/v1/orders/cancel`.
  - Implementasi fungsi `getOrder(apiKey, secretKey, symbol, orderId, clientOrderId)` ke `GET /open/v1/orders/detail`.
- [x] **Sinkronisasi ViewModel & UI**:
  - `OrderViewModel.hasRealCredentialsConfigured()` memeriksa `hasTokocryptoCredentials()` saat di mode Tokocrypto.
  - `TradingViewModelOrders.kt`: Evaluasi status koin holding dan penyimpanan posisi menyertakan partisi `exchange` yang aktif.
  - `SettingsScreen.kt`: Menyelaraskan teks banner, judul dialog PIN, dan toast pesan agar menampilkan bursa yang dipilih (`MODE REAL TOKOCRYPTO` / `MODE REAL INDODAX`).
- [x] **Verifikasi Kompilasi**:
  - `compile_applet` berhasil (Build succeeded).

### Tahap 12: Resolusi 7 Temuan Audit Codebase & Penegakan 100% Strict Exchange Isolation
- [x] **WebSocket AggTrade & Depth Stream**:
  - Implementasi combined stream `@aggTrade` dan `@depth20@100ms` di `TokocryptoMarketWebSocket.kt`.
  - Parsing frame `@aggTrade` dan orderbook `@depth` dan integrasi langsung ke StateFlow `tradeStream`, `orderBookBids`, `orderBookAsks`, dan `OrderBookDepthCache` di `MarketDataCoordinator.kt`.
- [x] **Eliminasi Total Unscoped Fallback di Cache**:
  - `OrderBookDepthCache.kt`: Menghapus semua fallback `cache[norm]`. Kueri dan pembaruan mewajibkan key `${exchange}_${symbol}`.
  - `MtfCacheManager.kt`: Menghapus fallback `cache[norm]` dan mirror unpartitioned.
  - `MarketDataCache.kt`: Menghapus penyimpanan dan pembacaan `pair_*` dan `pairs_metadata_json` tanpa namespace. Seluruh cache persisten dipartisi oleh `source.name.lowercase()`.
- [x] **Scoping Database Room & Partisi DAO**:
  - Menyelaraskan `OrderViewModel.kt` (`realOpenOrders`, `realTrades`), `SignalLogRepository.kt`, dan `TradeHistoryRecorder.kt` untuk memfilter data Room secara eksklusif berdasar `exchange`.
- [x] **Isolasi Storage Saldo Real & Average Price**:
  - `AppPreferences.kt`: `getSavedRealBalance()`, `saveRealBalance()`, `getSavedRealAvgBuyPrices()`, dan `saveRealAvgBuyPrices()` dipartisi per bursa (`"${exchange.lowercase()}_real_balance"` dan `"${exchange.lowercase()}_real_avg_prices"`).
  - `RealTradeCoordinator.kt`: StateFlow saldo dan harga rata-rata disinkronkan secara independen sesuai exchange aktif.
- [x] **Routing Bulk Ticker Type 1 vs Type 3**:
  - `TokocryptoMarketService.fetchTickers()` mendukung multi-cluster fetching untuk simbol Type 1 (MBX Cloud) dan Type 3 (NextMe) secara seamless.
- [x] **Verifikasi Kompilasi & Unit Tests**:
  - `compile_applet` & `gradle :app:testDebugUnitTest` berhasil (Build succeeded & All tests pass).

### Tahap 14: Audit & Perbaikan Real Trade Pipeline Tokocrypto (Multi-Endpoint Symbol Discovery, Parameter Sorting, & Default Fallback)
- [x] **Multi-Endpoint Symbol Discovery (`TokocryptoSymbolRepository.kt`)**:
  - Menambahkan rantai fallback 3 endpoint resmi Tokocrypto (`/open/v1/common/symbols`, `/api/v3/exchangeInfo` Tokocrypto site, `/api/v1/exchangeInfo` CloudMe) tanpa endpoint Binance sama sekali agar dapat diakses bebas dan lancar di Indonesia.
  - Parsing dinamis yang mendukung format Tokocrypto Open API maupun ExchangeInfo Tokocrypto.
- [x] **Static Default Symbol Metadata**:
  - Menambahkan `populateDefaultSymbols()` untuk pra-mengisi metadata trading bawaan seluruh pair populer (BTC, ETH, SOL, DOGE, XRP, SUI, ADA, BNB, SHIB, NEAR, AVAX, PEPE, TRX, LINK, RENDER, FET, FLOKI, BONK di IDR & USDT).
  - Menjamin order real tidak pernah tertahan oleh toast `"Metadata trading Tokocrypto gagal dimuat"` meskipun koneksi ke salah satu server metadata discovery mengalami keterlambatan.
- [x] **Alphabetical Query Parameter Sorting (`TokocryptoTradeApi.kt`)**:
  - Mengurutkan `formParams.sortedBy { it.first }` sebelum pembentukan `queryString` dan kalkulasi signature HMAC SHA-256.
- [x] **Debug Output Server Tokocrypto**:
  - Penambahan field diagnostik `httpCode`, `serverCode`, `serverBody`, `requestDebug` di `TokocryptoOrderResult`.
  - Logging terstruktur ke `AppLogManager.trade("TokocryptoOrderRejected", ...)` dan `RealTradeExecutor.kt` agar seluruh alasan penolakan dan raw response dari server Tokocrypto terekam di logcat/diagnostik in-app.
- [x] **Verifikasi Kompilasi & Unit Tests**:
  - `compile_applet` berhasil (Build succeeded).
  - `gradle :app:testDebugUnitTest` berhasil (BUILD SUCCESSFUL).

### Tahap 15: Perbaikan & Pembacaan Saldo Riil USDT Tokocrypto & Indodax Multi-Casing & Targeted Fallback
- [x] **Sinkronisasi Waktu Server Tokocrypto (`syncServerTime`)**:
  - Sinkronisasi selisih waktu (`serverTimeOffsetMs`) via `GET /open/v1/common/time` untuk mencegah error `-1021: Timestamp for this request is outside of recvWindow`.
- [x] **Ekstraksi Aset Fleksibel & Multiformat (`TokocryptoTradeApi.kt`)**:
  - `extractAssets` mendukung `accountAssets`, `balances`, `assets`, `userAssets`, root array, serta Map object aset dinamis.
- [x] **Dual-Casing Key Mapping (`parseBalances` & `AppPreferences.kt`)**:
  - Menyimpan aset ke `freeMap`, `holdMap`, `totalMap`, dan cache disk dengan key lowercase (`usdt`) dan uppercase (`USDT`) sekaligus.
- [x] **Targeted Fallback Saldo USDT (`/open/v1/account/spot/asset?asset=USDT`)**:
  - Query langsung saldo aset USDT sebagai proteksi ganda jika endpoint umum spot belum merespons atau bernilai 0.
- [x] **Force Refresh & Auto-Fetch Saldo Real (`RealTradeCoordinator.kt`)**:
  - Opsi `force: Boolean` pada `fetchRealBalance()` untuk melewati cooldown 30 detik saat refresh manual, dan auto-fetch saat inisialisasi.
- [x] **Penyelarasan UI Portofolio & Detail Chart**:
  - `PortfolioScreen.kt` & `RealPortfolioView.kt`: Menampilkan bursa aktif dinamis, serta kartu `SALDO USDT ($)` selalu tampil di mode Tokocrypto.
  - `DetailChartScreen.kt`: Resolusi bertingkat `availableQuote` (free balance -> total balance -> in-memory map -> disk cache) dan refresh saldo otomatis saat membuka layar koin.
- [x] **Verifikasi Kompilasi**:
  - `compile_applet` berhasil (BUILD SUCCESSFUL).
