# TradingView AI - Analisis Pasar & Trading Otomatis

Aplikasi Android berbasis **Jetpack Compose** dan **Kotlin** yang dirancang untuk analisis teknikal pasar kripto indodax.

---

## 🚀 Fitur Utama

### 1. Data Pasar Real-Time & Chart Interaktif
- **Konektivitas Fleksibel**: Streaming WebSocket langsung ke bursa Indodax dengan fallback REST API otomatis.
- **Multi-Timeframe Candlestick**: Mendukung timeframe 1m, 15m, 1h, 4h, hingga 1d.
- **Indikator Teknikal menggunakan lib ta4j**:
  - RSI (14) & Stochastic RSI
  - EMA Dinamis (EMA 7, EMA 21, EMA 50, EMA 200)
  - MACD (Moving Average Convergence Divergence)
  - Bollinger Bands & Average True Range (ATR)
  - Order Book Depth & Volume Flow Imbalance Tracker

### 2. Strategi (Learning Trading Engine)
- **Mode Scalping (M1 - M15)**: Pendeteksian momentum cepat, orderbook wall imbalance, dan lonjakan volume mikro.
- **Mode Swing (H1)**: Evaluasi 6-Checkpoint Confluence (Tren Makro, Ekspansi Volume, Validasi Pullback, Konfirmasi Breakout, Support/Resistance Dinamis).
- **Mode Intraday / Office Daily (H4)**: Proteksi Anti-Flash-Dump, filter anomali makro, dan deteksi likuiditas sehat.

### 3. News AI Screener (Groq & Gemini Flash)
- Mengkurasi berita kripto terkini dari berbagai feed RSS tepercaya (CoinDesk, Cointelegraph, Indodax, dll.).
- Analisis sentimen berbasis LLM (**Groq Qwen/Llama** & **Google Gemini Flash**) untuk menyaring koin berpotensi reli.

### 4. Eksekusi Trading & Manajemen Risiko
- **Mode Simulasi Realistis**: Virtual wallet dengan simulasi slippage, fee maker/taker, antrean limit order, dan pelacakan PnL historis.
- **Mode Real Indodax API (V2)**: Eksekusi order langsung ke pasar dengan API Key & Secret Key terenkripsi lokal di perangkat.
- **Trailing Stop Dinamis**: Mengunci profit otomatis mengikuti harga tertinggi (*peak price*) dan menaikkan batas *stop-limit* secara bertahap (tersedia mode *Tiered Trailing*).
- **Auto Take Profit (TP1 / TP2) & Cut Loss**: Eksekusi otomatis penjualan sebagian (*partial sell*) atau seluruh posisi saat target profit atau batas risiko tercapai.

### 5. Layanan Latar Belakang & Notifikasi Pintar
- **`TradingForegroundService`**: Pemantauan trailing stop dan batas harga 24/7 di latar belakang.
- **`CandidateScanWorker`**: Pemindaian berkala untuk menemukan koin potensial dari watchlist.
- **Notifikasi**: Pemberitahuan saat sinyal beli muncul, flashdump, peak harga naik, trailing stop terpicu.

### 6. Diagnostik & Logging Internal (`AppLogManager`)
- Log event internal real-time:
  - 🛡️ **Trailing Stop**: Pergerakan peak, kenaikan stop-limit, dan pemicu auto-sell.
  - 💼 **Trading & Order**: Status order simulasi/real, konfirmasi eksekusi (FILLED), dan realisasi PnL.
  - 📈 **Market Feed**: Status WebSocket, perubahan pair aktif, dan streaming data candle.
  - 🤖 **AI & Signal**: Transisi siklus sinyal teknikal dan hasil analisis screener berita.
  - ⚙️ **Background Service**: Lifecycle worker pemindaian latar belakang.
- **UI Diagnostik (`LogcatDiagnosticDialog`)**: Pemantauan langsung, pencarian log, salin dump state diagnostik ke clipboard, dan eksport log.

---

## 🛠️ Arsitektur & Teknologi

- **Bahasa**: Kotlin 100%
- **UI Framework**: Jetpack Compose dengan Material Design 3
- **Arsitektur**: MVVM (Model-View-ViewModel) + Clean Architecture berbasis Coordinator & Engine
- **Asinkron & Reaktif**: Kotlin Coroutines, StateFlow, dan SharedFlow
- **Penyimpanan Lokal**: Room Database (Entity, DAO, TypeConverters) & Encrypted SharedPreferences
- **Jaringan**: OkHttp, Ktor Client, WebSocket Engine
- **Logging & Diagnostik**: Custom `AppLogManager` reaktif + Timber

---

## 📂 Struktur Direktori Utama

```
app/src/main/java/agu/analys/
├── AnalysApplication.kt          # Entry point aplikasi & inisialisasi AppLogManager
├── MainActivity.kt               # Aktivitas utama Jetpack Compose
├── engine/                       # Logika mesin trading & evaluasi indikator
│   ├── LearningTradingEngine.kt # Koordinator utama sinyal AI
│   ├── scalping/                # Algoritma Scalping & Signal Lifecycle
│   ├── swing/                   # Algoritma Swing & 6-Checkpoint Confluence
│   ├── intraday/                # Algoritma Intraday & Anti-Flash-Dump
│   └── regime/                  # Deteksi anomali makro & volatilitas
├── service/                      # Background worker & layanan sistem
│   ├── CandidateScanWorker.kt   # Worker pemindaian sinyal watchlist berkala
│   ├── TradingForegroundService.kt # Service pemantau trailing stop background
│   └── NewsAiScreenerService.kt # Engine kurasi sentimen berita AI
├── trading/                      # Engine simulasi & manajemen posisi
│   ├── SimulationTradeStore.kt  # Logika dompet & order simulasi
│   └── SpotPositionStore.kt     # Pengelola posisi trailing stop lokal
├── viewmodel/                    # State management & interaksi UI
│   ├── TradingViewModel.kt      # ViewModel utama integrasi data & sinyal
│   ├── MarketDataCoordinator.kt # Koordinator feed data WebSocket/REST
│   ├── RealTradeExecutor.kt     # Eksekutor order Indodax riil
│   └── TradingViewModelAlerts.kt# Logika pemantauan trailing stop & auto-sell
├── ui/                           # Komponen antarmuka pengguna Compose
│   ├── screens/                 # Layar utama (Dashboard, Chart, Screener, Settings)
│   ├── components/              # Komponen modular (Chart, Stepper, Orderbook, Logs)
│   └── theme/                   # Palet warna & tipografi TradingView
└── util/                         # Utilitas (AppLogManager, Notifikasi, Price Formatter)
```

---

## ⚙️ Persyaratan Lingkungan & Konfigurasi

- **Android SDK**: Min SDK 24, Target SDK 34+
- **Build System**: Gradle dengan Kotlin DSL (`build.gradle.kts`)
- **Konfigurasi API Key (Opsional)**:
  - **Indodax API**: Diatur melalui menu **Pengaturan (Settings)** di dalam aplikasi untuk melakukan trading riil, gunakan TAPIv2 (api key & secret) yang diperoleh dari website terkait.
  - **Groq / Gemini API**: Diatur melalui menu **Pengaturan** untuk fitur News AI Screener & Analisa Koin.

---

