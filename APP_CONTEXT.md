# 📘 Konteks Arsitektur & Domain Aplikasi (TradingView AI / Analys)

Dokumen ini menyajikan gambaran menyeluruh, mendalam, dan teknis mengenai arsitektur, domain bisnis, alur data, integrasi bursa, mesin algoritma analitik, manajemen risiko, serta mekanisme keselamatan pada aplikasi **TradingView AI** (kode paket: `agu.analys`).

---

## 📑 Daftar Isi
1. [Ringkasan Eksekutif & Identitas Aplikasi](#1-ringkasan-eksekutif--identitas-aplikasi)
2. [Arsitektur Sistem Tingkat Tinggi](#2-arsitektur-sistem-tingkat-tinggi)
3. [Model Data Inti & Single Source of Truth (SSOT)](#3-model-data-inti--single-source-of-truth-ssot)
4. [Integrasi Bursa: Tokocrypto vs Indodax](#4-integrasi-bursa-tokocrypto-vs-indodax)
5. [Mesin Analisis Teknikal & AI Trading Engine](#5-mesin-analisis-teknikal--ai-trading-engine)
6. [Simulasi Trading & Real Execution Flow](#6-simulasi-trading--real-execution-flow)
7. [Jalur Notifikasi, Background Service, & Safety Guardrails](#7-jalur-notifikasi-background-service--safety-guardrails)
8. [Pencatatan Siklus Trade (Trade Journey Lifecycle 4-Stage)](#8-pencatatan-siklus-trade-trade-journey-lifecycle-4-stage)
9. [Keamanan, Kredensial, & PIN Protection](#9-keamanan-kredensial--pin-protection)
10. [Struktur Direktori & Kamus Komponen Utama](#10-struktur-direktori--kamus-komponen-utama)

---

## 1. Ringkasan Eksekutif & Identitas Aplikasi

**TradingView AI** adalah aplikasi Android berbasis **Kotlin** dan **Jetpack Compose (Material Design 3)** yang berfungsi sebagai:
1. **Pusat Analisis Pasar Real-Time**: Streaming data pasar (ticker, candlestick, orderbook depth, order trade flow) dari bursa kripto terkemuka di Indonesia (**Tokocrypto** dan **Indodax**) menggunakan WebSocket dan REST API berlatensi rendah.
2. **Mesin Sinyal Berbasis Multi-Timeframe AI**: Menggabungkan analisis kuantitatif teknikal (RSI, MACD, EMA, Bollinger Bands, ATR, deteksi pola candlestick, analisis struktur pasar MSS/BOS) dengan model AI (Gemini / Groq) untuk mengidentifikasi setup *High-Probability Entry*.
3. **Dual Execution Mode**:
   - **Mode Simulasi (Paper Trading)**: Simulasi saldo virtual terisolasi per bursa dan kuotasi (IDR & USDT), orderbook-matching, slippage model, serta simulasi eksekusi limit dan market order.
   - **Mode Riil (Real Trading)**: Eksekusi live order ke akun exchange pengguna melalui Trade API resmi (Tokocrypto OpenAPI v1/v3 & Indodax TAPIv2) yang terproteksi PIN keamanan 6-digit.
4. **Sistem Pengaman Aset Mandiri 24/7**: Pemantauan portofolio latar belakang melalui Android Foreground Service (`TradingForegroundService`) dengan fitur *Smart Step Trailing Stop*, *Auto Take Profit* berjenjang (TP1/TP2), dan *Emergency Flash Dump Exit*.

---

## 2. Arsitektur Sistem Tingkat Tinggi

Aplikasi ini menerapkan pola **Clean Architecture + Reactive MVVM (Model-View-ViewModel)** dengan pemisahan tanggung jawab berbasis **Coordinators**:

```
┌────────────────────────────────────────────────────────────────────────┐
│                       UI Layer (Jetpack Compose M3)                    │
│  Layar: Dashboard, Detail Chart, Portfolio, Simulation, Logs, Settings  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Observes StateFlows / Dispatches Actions
┌───────────────────────────────────▼────────────────────────────────────┐
│                  ViewModel & Coordinators Layer                        │
│                                                                        │
│   TradingViewModel (Orchestrator Utama)                                │
│   ├── MarketDataCoordinator   (WebSocket, Ticker, Chart, Candles; depth cache per exchange) │
│   ├── PositionCoordinator     (Spot Positions, Trailing, Alerts)       │
│   ├── SimulationCoordinator   (Virtual Wallet, Order Engine)           │
│   ├── RealTradeCoordinator    (Live Balances, PIN Guard, Execution)    │
│   │     └── RealTradeExecutor (API Calls, Polling Fill, Order Match)   │
│   ├── BatchSellCoordinator    (One-Click Sell All Ready Coins)         │
│   └── WatchlistViewModel      (Favorit, Pinned Coins, Filter)          │
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │                                │
┌───────────────────▼──────────────┐   ┌─────────────▼───────────────────┐
│       Domain Engine Layer        │   │    Data & Persistence Layer     │
│ ├── LearningTradingEngine        │   │ ├── SpotPositionStore           │
│ │   ├── ScalpSetupDetector       │   │ ├── SimulationTradeStore        │
│ │   ├── SwingEvaluator           │   │ ├── PriceAlertStore             │
│ │   ├── IntradayEvaluator        │   │ ├── AppPreferences (Encrypted)  │
│ │   └── ConfluenceEvaluator      │   │ ├── Room AppDatabase            │
│ ├── TickHistoryTracker           │   │ │   ├── SignalLogRepository     │
│ ├── SellSignalLifecycleManager   │   │ │   └── TradeHistoryRecorder    │
│ └── NewsAiScreenerService        │   │ └── MarketDataCache             │
└──────────────────────────────────┘   └─────────────────┬───────────────┘
                                                         │
┌────────────────────────────────────────────────────────▼───────────────┐
│                    Network & External Services Layer                   │
│ ├── Tokocrypto: MarketService, MarketWebSocket, UserWebSocket,         │
│ │   TradeApi, SymbolRepository (tanpa fallback Binance)                │
│ ├── Indodax: IndodaxMarketService, WebSocket, IndodaxTradeApiV2        │
│ ├── Background: TradingForegroundService, CandidateScanWorker          │
│ └── AI: GeminiAiService, GroqAiService, NewsRssFeedService             │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Model Data Inti & Single Source of Truth (SSOT)

### 3.1. `MarketKey` (State Identitas Pasar, di `model/MarketKey.kt`)
Menghilangkan ketergantungan tebak-tebakan kuotasi dan bursa dari string simbol:
- **Definisi**: `data class MarketKey(val exchange: String, val symbol: String, val quote: String)`
- **Karakteristik**:
  - `exchange`: `"TOKOCRYPTO"` atau `"INDODAX"`.
  - `symbol`: Simbol terstandardisasi tanpa delimiter (mis. `"BTCUSDT"`, `"BTCIDR"`).
  - `quote`: Kuotasi resmi (`"USDT"`, `"IDR"`, `"BIDR"`, `"USDC"`, `"BUSD"`).
  - `base`: Aset dasar yang diperdagangkan (`symbol.removeSuffix(quote)`).
  - `toNotificationId(isReal: Boolean, offset: Int)`: ID notifikasi deterministik unik berbasis `hash(exchange, symbol, isReal)`.

### 3.2. `TradingPair`
Mewakili pasangan perdagangan dengan metadata format tampilan:
- Menyediakan konversi ke format kanonikal masing-masing bursa:
  - `effectiveIndodaxPair()`: format garis bawah huruf kecil (mis. `btc_idr`, `usdt_idr`).
  - `effectiveTokocryptoPair()`: format underscore kapital (mis. `BTC_USDT`, `BTC_IDR`).
  - `effectiveCompactSymbol()`: format tanpa underscore (mis. `BTCUSDT`).

### 3.3. `SpotPosition` & `SpotPositionStore`
Penyimpanan posisi aset yang dipegang pengguna:
- **Multi-Exchange Isolation**: Setiap key penyimpanan SharedPreferences dipartisi dengan prefix bursa: `${exchange.lowercase()}_${if (isReal) "real" else "sim"}_${symbol}`.
- **Atribut Utama**:
  - `entryPrice`, `investedAmount`, `quantity`, `peakPrice`.
  - `isTrailingEnabled`, `trailingPercent`, `activeTrailingPercent`, `isTieredTrailingEnabled`.
  - `isAutoSellEnabled`, `tp1Price`, `tp1Percent`, `tp2Price`, `tp2Percent`, `stopLossPrice`.
  - `isTp1Triggered`, `isTp2Triggered`, `isTrailingTriggered`.

### 3.4. `SimulationWallet` & `SimulationTradeStore`
Dompet simulasi virtual terisolasi per bursa:
- Saldo kas terpisah untuk `IDR` dan `USDT`.
- `quoteForCoin(base)`: Mencatat kuotasi pembelian asli tiap koin.
- **Quote Conflict Guard**: Fungsi `findQuoteConflict` menolak order yang mencoba mencampur kuotasi (misal membeli `BTC` via `USDT` padahal sudah memegang `BTC` hasil pembelian `IDR`), melindungi integritas harga rata-rata (*average price*) dan saldo kas.

---

## 4. Integrasi Bursa: Tokocrypto vs Indodax

Aplikasi mengimplementasikan isolasi ketat (*Zero Cross-Contamination*) antar bursa. Perpindahan bursa di Pengaturan memutus koneksi WebSocket aktif, mereset state chart, dan memuat ulang dataset bursa terpilih.

| Fitur / Komponen | Tokocrypto | Indodax |
| :--- | :--- | :--- |
| **Penyedia Data** | `MarketDataSource.TOKOCRYPTO` | `MarketDataSource.INDODAX` |
| **Market WebSocket** | `TokocryptoMarketWebSocket` (host resmi per tipe simbol: Type 1 `wss://stream-cloud.tokocrypto.site/stream`, Type 3 `wss://stream-toko.2meta.app`; tanpa fallback Binance) | `IndodaxMarketWebSocket` (`wss://ws3.indodax.com/ws/`) |
| **User WebSocket** | `TokocryptoUserWebSocket` (`wss://stream-cloud.tokocrypto.site/ws/<listenKey>`) | — (REST TAPI polling) |
| **REST Market API** | `TokocryptoMarketService` (Type 1 `https://www.tokocrypto.site/api/v3`, Type 3 `https://cloudme-toko.2meta.app/api/v1`) | `IndodaxMarketService` |
| **Dynamic Symbol Discovery** | `TokocryptoSymbolRepository` (Endpoint resmi `/open/v1/common/symbols`, fallback `exchangeInfo` resmi) | `IndodaxMarketService.fetchPairsMetadata()` |
| **Kuotasi yang didukung** | **USDT + IDR saja** (BIDR dieliminasi total) | **IDR saja** |
| **Filter listing dashboard** | Fail-closed: hanya simbol `spotTradingEnable` (status TRADING) yang tampil; simbol delisted/tak dikenal disembunyikan dari ranking, dashboard, dan cache | Filter anti-zombi/delisting berbasis harga & volume (`isSafeTradableAsset`) |
| **Symbol Type Handling** | Type 1 (MBX Broker / Cloud) vs Type 3 (NextMe Cloud) | Tipe seragam, berkuotasi IDR |
| **Trade API** | `TokocryptoTradeApi` (`POST /open/v1/orders`) | `IndodaxTradeApiV2` (REST TAPIv2) |
| **Autentikasi Order** | HMAC-SHA256 signature + API-Key header + Server time synchronization (param di-sort alfabetis sebelum signature) | HMAC-SHA256 signature + `X-APIKEY` & `Sign` headers (Trade API V2 di `api.indodax.com/api/v2`; `Key`+SHA512 hanya TAPI lama) |
| **Format Simbol Order** | `BTC_USDT` (underscore) | `BTCIDR` (order) / `btcidr` (myTrades/histories), tanpa underscore (Trade API V2; `btc_idr` hanya format TAPI lama) |

Catatan koreksi 2026-10-09: histori (`myTrades`) & fallback open-order Tokocrypto mengambil **USDT + IDR** (`${base}_USDT` dan `${base}_IDR`) dengan avg-buy **per kuotasi** (harga USDT/IDR tidak dicampur); kandidat base dinormalisasi lowercase dengan filter kuotasi case-insensitive. Update depth polling `OrderBookDepthCache` selalu membawa exchange eksplisit agar data Indodax tidak tertulis ke key Tokocrypto.

---

## 5. Mesin Analisis Teknikal & AI Trading Engine

Komponen utama: `LearningTradingEngine.kt`.

### 5.1. Tiga Mode Strategi Transaksi
1. **SCALPING (Timeframe 1M – 15M)**:
   - Didesain untuk eksekusi cepat momentum mikro.
   - Menggunakan deteksi *OrderBook Analyzer* (tekanan bid-ask wall), lonjakan volume transaksional (*Micro Volume Spike*), *VWAP Reclaim*, dan *Spread Guard* (mencegah order saat spread bid-ask terlalu lebar).
2. **SWING (Timeframe H1, label 1H–1D)**:
   - Menggunakan evaluasi **6-Checkpoint Confluence Matrix**:
     1. `TREND`: Arah tren makro (EMA 20/50/200).
     2. `LEVEL`: Reclaim level kunci Support/Resistance atau Fibonacci retracement.
     3. `VOL`: Validasi ekspansi volume pada saat breakout atau pengeringan volume pada pullback.
     4. `TRG`: Sinyal konfirmasi candlestick (*Price Action Trigger*).
     5. `MOM`: Filter momentum sehat (RSI tidak ekstrem, MACD histogram crossing).
     6. `RR`: Rasio *Risk-to-Reward* bersih minimal $\ge 1:2.0$ setelah memperhitungkan fee bursa.
3. **INTRADAY / OFFICE DAILY (enum `OFFICE_DAILY`, label "Intraday")**:
   - Dirancang untuk trader dengan waktu pemantauan terbatas.
   - Siklus 4 sesi waktu: Open Pagi (evaluasi kandidat), Hold Siang, Close Malam (evaluasi exit), Rest Dini Hari.
   - Dilengkapi *Macro Anomaly Detector* dan *Anti-Flash Dump Exit Protection*.

### 5.2. News AI Screener & Sentimen
- Mengambil feed berita kripto internasional secara berkala (`NewsRssFeedService`).
- Memanfaatkan **Gemini AI** (`GeminiAiService`) atau **Groq AI** (`GroqAiService`) untuk merangkum sentimen berita (Bullish / Neutral / Bearish), memberikan skor dampak (1–10), dan menandai koin-koin yang terdampak.

---

## 6. Simulasi Trading & Real Execution Flow

### 6.1. Alur Transaksi Simulasi
```
User / Auto Trigger ──► submitSimulationOrder()
                             │
                             ▼
                 SimulationTradeStore.placeOrder()
                 ├── Cek saldo kas (IDR atau USDT)
                 ├── Cek Quote Conflict (sameQuoteClass)
                 ├── Kalkulasi fee transaksi
                 └── Eksekusi (Market order = Instan FILLED; Limit order = OPEN di orderbook)
                             │
                             ▼
                 PositionCoordinator.setOwnership() & refreshPosition()
```

### 6.2. Alur Transaksi Riil (Real Order)
```
User / Trailing Trigger ──► executeRealTrade()
                                 │
                                 ▼
                     RealTradeExecutor.executeTrade()
                     ├── Verifikasi Keberadaan Kredensial (API Key & Secret Key)
                     ├── Validasi PIN Keamanan (Bila diaktifkan)
                     ├── Resolusi Bursa Tujuan (resolveSource: TOKOCRYPTO vs INDODAX)
                      ├── Pengambilan Precision & Aturan Lot Size (LOT_SIZE, MIN_NOTIONAL; metadata pair Indodax di-cache in-memory 30 menit)
                      ├── Validasi Harga (order LIMIT Tokocrypto wajib bawa `price` eksplisit — ditolak bila kosong)
                     ├── Penyelarasan Server Time (Tokocrypto timestamp sync)
                     ├── Pembuatan Payload & Hashing HMAC Signature
                     └── Dispatch HTTP Request
                                 │
                                 ▼
                     waitForBuyFill() (Polling status order hingga FILLED)
                                 │
                                 ▼
                     Pembaruan Saldo Riil Lokal & Pencatatan History Transaksi
```

---

## 7. Jalur Notifikasi, Background Service, & Safety Guardrails

### 7.1. Saluran Notifikasi Android
Aplikasi membuat 4 channel berprioritas tinggi (`AlertNotificationHelper`):
1. `channel_candidate_buy_alerts`: Notifikasi koin kandidat entry strategi.
2. `channel_trailing_stop_alerts`: Notifikasi kenaikan peak trailing stop dan eksekusi profit lock.
3. `channel_emergency_exit_alerts`: Peringatan darurat level tertinggi (Flash Dump / Stop Loss Hit) dengan suara alarm.
4. `channel_price_alerts`: Peringatan target harga kustom & trigger indikator.

### 7.2. Mekanisme Keamanan Tombol "Jual Sekarang" dari Notifikasi
- Setiap intent notifikasi menyertakan data lengkap: `EXTRA_SYMBOL`, `EXTRA_EXCHANGE`, `EXTRA_QUOTE`, `EXTRA_LIMIT_PRICE`, `EXTRA_QUANTITY`, `EXTRA_IS_REAL`.
- Pada `MainActivity.kt`:
  - **Aksi Simulasi**: Dapat langsung diproses melalui dompet simulasi bursa terkait.
  - **Aksi Real**: **Wajib memicu dialog konfirmasi interaktif** (`AlertDialog`) yang menampilkan detail koin, kuantitas, bursa tujuan, dan harga acuan sebelum order dikirim ke bursa. Hal ini mencegah terjadinya *accidental tap* di lock screen atau notification drawer yang berisiko pada dana riil.

### 7.3. Layanan Latar Belakang (`TradingForegroundService`)
- Berjalan sebagai Android Foreground Service terus-menerus ketika ada posisi terbuka atau fitur trailing diaktifkan.
- **Multi-Exchange Fetching**: Mengelompokkan koin yang sedang di-hold berdasarkan bursa (`TOKOCRYPTO` dan `INDODAX`), lalu menarik harga live menggunakan endpoint resmi bursa masing-masing.
- **Pemisahan Real & Sim**: Memantau portofolio Real dan Simulasi secara independen tanpa menimpa satu sama lain.
- **Isolasi Key Tracking**: `TickHistoryTracker`, `SellSignalLifecycleManager`, dan jeda waktu alert dikunci dengan kombinasi `${exchange}_${symbol}_${isReal}`.

---

## 8. Pencatatan Siklus Trade (Trade Journey Lifecycle 4-Stage)

Aplikasi mendokumentasikan siklus hidup setiap transaksi secara menyeluruh ke dalam Room Database (`TradeHistoryRecordEntity`) melalui `TradeHistoryRecorder`:

```
┌────────────────────────────────────────────────────────────────────────┐
│ Tahap 1: Pengeluaran Sinyal Buy (Signal Emission)                      │
│ - Mode Strategi (Scalping / Swing / Intraday)                          │
│ - Keyakinan AI & Alasan Analisis                                       │
│ - Snapshot Indikator (RSI, MACD, EMA, ATR, Volume)                    │
│ - Level Target (TP1, TP2, SL)                                          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Tahap 2: Eksekusi Order Beli (Buy Order Execution)                     │
│ - Waktu Beli, Harga Beli, Kuantitas Koin, Total Modal                  │
│ - Tipe Order (MARKET / LIMIT) & Status (Real / Simulasi)               │
│ - Bursa Eksekusi (Tokocrypto / Indodax)                                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Tahap 3: Durasi Hold & Tracking Perjalanan (Holding Phase)              │
│ - Pelacakan Harga Puncak (Peak Price)                                  │
│ - Kalkulasi Max Drawdown                                               │
│ - Histori Penyesuaian Trailing Stop Level                              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Tahap 4: Eksekusi Jual & Realized PnL (Sell Order & Realization)       │
│ - Waktu Jual, Harga Jual, Alasan Exit (TP1/TP2/Trailing/SL/Manual)     │
│ - Nominal Bersih PnL ($ untuk USDT atau Rp untuk IDR)                  │
│ - Persentase Keuntungan Bersih (Net Profit %)                          │
└────────────────────────────────────────────────────────────────────────┘
```

Fitur **Export Laporan Markdown** (`TradeLogExporter.kt`) memungkinkan pengguna mengekspor rekaman transaksi dalam format teks terstruktur yang dapat diaudit langsung oleh model LLM (Claude, ChatGPT, Gemini, Grok).

---

## 9. Keamanan, Kredensial, & PIN Protection

1. **Penyimpanan Kredensial Terenkripsi**:
   - Kredensial API Key dan Secret Key disimpan secara terisolasi per bursa di `AppPreferences`.
   - Tidak pernah di-hardcode di kode sumber atau diekspor ke file log publik.
   - Bila KeyStore corrupt, entri `MasterKey` corrupt dihapus dari AndroidKeyStore sebelum retry; fallback plaintext hanya jalan terakhir dan dilog sebagai error.
2. **PIN Keamanan**:
   - Melindungi aktivasi mode Real Trading, eksekusi order manual, eksekusi trailing sell, dan tindakan batch sell.
   - Kegagalan input dicatat di penghitung `failedPinAttempts`; bila bermasalah, flag `isPinResetRequired` mewajibkan reset PIN.
3. **Spread Guard & Slippage Protection**:
   - Membatasi eksekusi market sell pada batas deviasi wajar orderbook guna mencegah kerugian akibat orderbook yang tipis.
4. **Rate Limit & Backoff**:
   - Throttle otomatis pada `IndodaxMarketService` (minimum interval request 200ms) dan `TokocryptoMarketService` (100ms), plus backoff eksponensial dan jeda antar request order (`INTER_REQUEST_DELAY_MS`).

---

## 10. Struktur Direktori & Kamus Komponen Utama

```
app/src/main/java/agu/analys/
├── MainActivity.kt                      # Entry point activity, penanganan navigasi, intent notifikasi
├── config/
│   └── AppConfiguration.kt              # Enum bursa (MarketDataSource), konfigurasi fee, mode strategi
├── data/
│   ├── OrderBookDepthCache.kt           # In-memory orderbook depth cache
│   └── TokocryptoSymbolRepository.kt    # SSOT Dynamic Symbol Discovery Tokocrypto
├── database/
│   ├── AppDatabase.kt                   # Room database v8 (kolom `exchange` partisi per bursa) & konfigurasi
│   ├── SignalLogRepository.kt           # Database repository untuk rekaman sinyal AI
│   └── TradeHistoryRecorder.kt          # Recorder siklus 4-tahap perjalanan trade
├── domain/
│   ├── model/                           # DomainAiSignal, DomainOrder, DomainMarketData
│   └── usecase/BaseUseCase.kt           # Use case abstrak
├── model/
│   ├── MarketKey.kt                     # Model SSOT bursa, simbol, dan kuotasi
│   ├── TradingViewModels.kt             # TradingPair, MarketTick, POPULAR_*_PAIRS, Timeframe
│   └── TokocryptoModels.kt              # TokocryptoSymbolInfo & filter trading
├── engine/
│   ├── LearningTradingEngine.kt         # Engine kalkulasi sinyal trading multi-strategi
│   ├── indicators/                      # Penghitung RSI, MACD, Bollinger Bands, ATR, dll.
│   ├── scalping/                        # Logika Scalp M1/M5, MTF Confluence Matrix, OrderBook Analyzer
│   │   (ScalpSetupDetector, ScalpingMtfEvaluator, SignalScoringEngine,
│   │    ScalpingRiskEngine, MarketScannerEngine, HistoricalEdgeStub, replay)
│   ├── swing/                           # Logika Swing H1, 6-Checkpoint Confluence Evaluator
│   ├── intraday/                        # Logika Intraday, IntradayScreener, 4-Sesi Disiplin Waktu, Anomaly Detector
│   ├── regime/                          # MarketRegimeEngine/Detector, MacroAnomalyDetector
│   ├── global/                          # GlobalContextManager, RiskBasedPositionSizer
│   ├── badge/                           # CoinBadgeEvaluator
│   └── backtest/                        # BacktestEngine, WalkForwardEvaluator
│   └── sell/
│       ├── TickHistoryTracker.kt        # Pelacak tick per bursa untuk deteksi flash dump & drop
│       └── SellSignalLifecycleManager.kt # Manajemen transisi siklus sinyal jual
├── service/
│   ├── TokocryptoMarketService.kt       # REST API market data Tokocrypto (Type 1 & Type 3)
│   ├── TokocryptoMarketWebSocket.kt     # Live streaming kline, trade, depth Tokocrypto
│   ├── TokocryptoUserWebSocket.kt       # Stream akun & eksekusi order real-time
│   ├── TokocryptoTradeApi.kt            # Signed trade API Tokocrypto
│   ├── IndodaxMarketService.kt          # REST API market data Indodax
│   ├── IndodaxMarketWebSocket.kt        # Live streaming market Indodax
│   ├── IndodaxTradeApiV2.kt             # HMAC-SHA256 Trade API V2 Indodax (`X-APIKEY`+`Sign`, simbol `BTCIDR`/`btcidr`)
│   ├── TradingForegroundService.kt      # Layanan pemantauan latar belakang 24/7 multi-bursa
│   ├── CandidateScanWorker.kt           # Periodic background scanning (WorkManager)
│   ├── NewsAiScreenerService.kt         # Orkestrasi screening berita + skor AI
│   ├── NewsRssFeedService.kt            # Pengambil feed RSS kripto
│   ├── CryptoHeadlineService.kt         # Agregator headline kripto
│   ├── GeminiAiService.kt               # Integrasi Google Gemini API
│   └── GroqAiService.kt                 # Integrasi Groq AI
├── trading/
│   ├── SpotPositionStore.kt             # Penyimpanan posisi spot (holding, trailing, TP/SL)
│   ├── SimulationTradeStore.kt          # Dompet dan engine eksekusi simulasi
│   ├── PriceAlertStore.kt               # Penyimpanan alert target harga
│   └── TradeLogExporter.kt              # Generator laporan markdown transaksi
├── ui/
│   ├── screens/                         # Dashboard, DetailChart, Portfolio, Simulation, Settings, Logs
│   ├── components/                      # Kartu sinyal, dialog transaksi, mini chart sparkline
│   └── theme/                           # ColorScheme, Typography, Material 3 Theme
├── util/
│   ├── AlertNotificationHelper.kt       # Builder & dispatcher notifikasi per bursa
│   ├── AppPreferences.kt                # Pengaturan aplikasi & kredensial terenkripsi
│   ├── AppLogManager.kt                 # In-app logging & diagnostik (Trailing, Trade, Market, AI)
│   └── PriceFormatter.kt                # Formatter harga, persentase, kuotasi ($ USDT vs Rp IDR)
└── viewmodel/
    ├── TradingViewModel.kt              # Hub pusat integrasi ViewModel (+ ekstensi Orders/Sync/AI/Detail/Update/Navigation)
    ├── MarketViewModel.kt               # Refresh ranking, gainers & worth-coins dashboard
    ├── OrderViewModel.kt                # Histori order & transaksi, penghitung PIN gagal
    ├── MarketDataCoordinator.kt         # Pengelola koneksi live market & orderbook
    ├── PositionCoordinator.kt           # Pengelola status posisi, trailing, dan alert
    ├── SimulationCoordinator.kt         # Pengelola portofolio simulasi
    ├── RealTradeCoordinator.kt          # Pengelola portofolio riil & PIN
    ├── RealTradeExecutor.kt             # Eksekutor order riil Tokocrypto & Indodax
    ├── RealTradeSecurityManager.kt      # PIN guard mode real (verify/lock)
    ├── BatchSellCoordinator.kt          # Eksekutor jual massal aset siap profit
    └── TradingViewModelAlerts.kt        # Ekstensi penanganan alert & trailing auto-sell
```

---
*Disinkronkan dengan `main` pada 2026-10-09: eliminasi fallback Binance, kuotasi Tokocrypto USDT+IDR / Indodax IDR saja (tanpa BIDR), filter listing fail-closed dashboard, format order `BTC_USDT`, histori/open-order Tokocrypto dual-kuotasi (avg per kuotasi), isolasi exchange depth cache, dan koreksi path komponen. Dokumen ini diperbarui secara berkala mengikuti iterasi pengembangan sistem.*
