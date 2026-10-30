# TokoCrypto & Indodax AI Trading System — Analisis Pasar & Trading Otomatis

Aplikasi Android berbasis **Jetpack Compose** dan **Kotlin** mutakhir yang dirancang untuk analisis teknikal pasar kripto, sinyal multi-timeframe berbasis AI, simulasi trading realistis, pencatatan siklus perjalanan trade 4-tahap lengkap, serta eksekusi trading riil terisolasi pada bursa **Tokocrypto** dan **Indodax**.

![Rilis APK](https://github.com/Agus27712/analisa-pasar/actions/workflows/release.yml/badge.svg?branch=main)

---

## 🚀 Fitur Utama

### 1. Arsitektur Multi-Exchange Terisolasi (Tokocrypto & Indodax)
- **Pemusatan Pilihan Bursa**: Pemilihan bursa terpusat melalui menu *Pengaturan (Settings)* dengan mekanisme *hard-stop*, pemutusan WebSocket seketika, dan pembersihan total cache saat bursa beralih.
- **Tokocrypto Official API Integration**:
  - *Dynamic Symbol Discovery*: Menemukan daftar pair, filter LOT_SIZE, PRICE_FILTER, dan MIN_NOTIONAL secara dinamis dari endpoint pasar Tokocrypto (routing **Type 1 MBX Broker** dan **Type 3 NextMe**) sesuai tipe simbol.
  - *Symbol Type Routing*: Otomatisasi perutean pasar Type 1 (MBX Broker / Cloud) dan Type 3 (NextMe Cloud).
  - *Live Multi-Stream WebSockets*: Kline (`<symbol>@kline_<interval>`), Trade (`<symbol>@trade`), AggTrade, dan Depth live feed.
  - *Signed Trade API*: Pembuatan order spot (`POST /open/v1/orders`), pembatalan, query status detail, sinkronisasi selisih waktu server (`syncServerTime`), dan pembacaan saldo multi-format.
- **Indodax API Integration**:
  - Streaming pasar real-time, depth orderbook, recent trades, candlestick historical, dan Trade API V2 (TAPIv2) ber-signature HMAC-SHA256 (header `X-APIKEY` + `Sign`).
- **Isolasi Penuh (Zero Cross-Contamination)**:
  - Partisi namespace Room Database, SharedPreferences, in-memory cache, dan StateFlow per exchange (`tokocrypto_*` vs `indodax_*`).

### 2. Pemisahan Kuotasi Mata Uang ($ USDT vs Rp IDR) & Portofolio
- **Tampilan Dual Harga & Format Presisi**:
  - Pasangan berkuotasi USDT menggunakan format Dollar (`$`) dan ekuivalen Rupiah (`≈ Rp ...`) berbasis kurs live exchange (`ExchangeRateManager`).
  - Pasangan berkuotasi IDR menggunakan format Rupiah (`Rp`).
  - Evaluasi reason sinyal, Confluence Evaluator, MTF Scalping, dan OrderBook Analyzer otomatis menyesuaikan simbol mata uang (`$` untuk USDT/USDC/BUSD, `Rp` untuk IDR/BIDR).
- **Simulasi Dompet Multi-Currency**:
  - Saldo IDR dan USDT terpisah dengan kalkulasi PnL akurat sesuai kuotasi aset.
  - Fitur auto-konversi saldo IDR $\rightarrow$ USDT saat eksekusi order USDT jika saldo USDT kurang.
  - Dialog konversi manual 2 arah (Rp $\leftrightarrow$ $) pada tab Portofolio berbasis live rate exchange.
- **Portofolio Real Terproteksi PIN**:
  - Pembacaan saldo spot riil bebas (*free*) dan terkunci (*locked*) dengan targeted query fallback dan sinkronisasi server time.

### 3. Riwayat & Log Sinyal AI serta Histori Siklus Perjalanan Trade
- **4-Stage Trade Journey Lifecycle (`TradeHistoryRecordEntity` & `TradeHistoryRecorder`)**:
  1. **Tahap 1: Pengeluaran Sinyal Buy**: Mode strategi (Scalping/Swing/Intraday), skor keyakinan, breakdown teknikal (RSI, MACD, EMA, BB, ATR), alasan AI, dan snapshot level target (TP1, TP2, SL).
  2. **Tahap 2: Eksekusi Order Buy**: Timestamp eksekusi, harga beli, kuantitas koin, total modal, tipe order (LIMIT/MARKET), dan status (1:1 Real / Simulasi).
  3. **Tahap 3: Durasi Hold & Tracking Perjalanan**: Waktu menahan (*holding time*), pelacakan harga tertinggi (*peak price*), *max drawdown*, dan status *Trailing Profit Lock*.
  4. **Tahap 4: Eksekusi Sell & Realized PnL**: Harga jual, waktu jual, alasan exit (TP/SL/Trailing/Manual), nominal PnL bersih (format `$` untuk USDT atau `Rp` untuk IDR), dan persentase keuntungan.
- **Layar Sinyal Log & Histori Perjalanan (`SignalLogScreen.kt`)**:
  - Ringkasan statistik performa: Win Rate Selesai, Total Realized PnL terpisah per kuotasi ($ & Rp), serta durasi rata-rata hold.
  - Filter interaktif (Semua, Selesai, Sedang Hold, Menang, Kalah, Filter Bursa, Filter Mode Strategi).
  - Kartu siklus perjalanan (`TradeHistoryJourneyCard.kt`) dengan drawer detail komprehensif.
  - Dialog detail audit transaksi (`TradeLogDetailDialog.kt`) dengan breakdown 5-kategori teknikal.
- **Export Laporan Markdown untuk Audit LLM**:
  - Salin laporan log transaksi terstruktur siap-pakai ke clipboard untuk verifikasi prompt LLM (Gemini, Claude, Grok, ChatGPT) via `TradeLogExporter.kt`.

### 4. Dashboard Pintar & Paginasi Volume 24 Jam
- **Peringkat Volume 24H Dinamis**:
  - Tab **[Semua]** secara otomatis memuat dan mengurutkan seluruh pasangan koin aktif berdasarkan Volume 24 Jam Tertinggi (volume USDT dinormalisasi ke IDR via kurs live).
  - Paginasi 15 koin awal yang bertambah +15 secara seamless saat pengguna melakukan scroll ke bawah.
- **Filter Cepat (Quick Filter Chips)**:
  - `[Semua]`: Seluruh pasangan koin aktif terurut volume 24 jam.
  - `[Signal Kuat ⭐]`: Koin dengan konfirmasi sinyal AI Beli (Confidence $\ge 55\%$) atau koin breakout momentum.
  - `[💼 Holding]`: Daftar seluruh aset yang sedang dimiliki (baik simulasi maupun real) dengan pencocokan kuotasi ketat.
  - `[⭐ Watchlist]`: Koin pantauan dan favorit pilihan pengguna.
- **Section Holding Aktif & Sparkline**:
  - Header sticky collapsible `● HOLDING AKTIF` dengan avatar aset, harga live, persentase PnL, indikator trailing stop, dan mini chart sparkline 1 jam.

### 5. Engine Strategi (Learning Trading Engine)
- **Mode Scalping (M1 – M15)**:
  - Deteksi lonjakan volume mikro, orderbook bid/ask wall pressure, VWAP reclaim, Spread Guard anti-slippage, dan validasi spread.
- **Mode Swing Trade (H1)**:
  - Evaluasi 6-Checkpoint Confluence: Tren Makro, Ekspansi Volume, Validasi Pullback, Konfirmasi Breakout/Rejection/Retest, serta Support/Resistance Dinamis.
- **Mode Intraday / Office Daily (H4)**:
  - Siklus 5-Sesi Disiplin Waktu (Open Pagi 06:00–11:30, Hold Siang 11:30–15:30, Sore Trailing 15:30–19:30, Close Malam 19:30–23:30, Rest Malam 23:30–06:00 WIB), Proteksi Anti-Flash-Dump, filter anomali makro (Macro Anomaly Detector), dan screening likuiditas sehat.

### 6. News AI Screener (Gemini Flash)
- Kurasi berita kripto terhangat dari feed RSS global terpercaya.
- Analisis sentimen fundamental dan scoring koin berpotensi reli menggunakan LLM (**Google Gemini**).

### 7. Charting Interaktif & Mode Layar Penuh
- **Lightweight Charts (HTML5 / JS Bridge)**: Render candlestick interaktif responsif dengan dukungan multi-timeframe (1m, 5m, 15m, 1h, 4h, 1d).
- **Mode Landscape Fullscreen**: Tampilan grafik layar penuh dengan tombol navigasi kembali dan overlay ringkasan teknikal.

### 8. Manajemen Risiko & Eksekusi Otomatis
- **Smart Step Trailing Stop**:
  - Mengunci keuntungan mengikuti harga tertinggi (*peak price*) dan menaikkan level stop limit secara bertahap (*Tiered Trailing*).
- **Auto Take Profit (TP1 / TP2) & Cut Loss**:
  - Eksekusi parsial (50% TP1, 50% TP2) atau penjualan penuh saat target harga atau batas risiko tercapai.
- **Layanan Latar Belakang 24/7**:
  - `TradingForegroundService`: Pemantauan trailing stop posisi aktif dan price alerts secara realtime di background.
  - `CandidateScanWorker`: Pemindaian sinyal koin potensial secara periodik.

### 9. Paper Auto-Trade & Validasi Edge (Jalur Utama Uji Strategi)
- **Paper Auto-Trade (Simulasi Otomatis)**: Jalur utama untuk menguji strategi scalp **tanpa uang riil**, terisolasi penuh per bursa & kuotasi.
  - **Exit FSM (Finite State Machine)** dengan 5 mode exit: early profit net, breakeven lock, tiered trailing, time-stop (`maxHoldBarsM1`), dan SL/TP klasik — seluruhnya memakai **Fee Truth all-in** (bukan asumsi fee usang).
  - **Paritas dengan Offline Replay**: parameter exit (`ScalpExitParams`) mem-mirror blok `exit` di `data/config/scalp_config_exit_*.json`, sehingga hasil paper trade di app konsisten dengan `tools/scalp_replay.py`.
  - **Kartu FSM Visual & Statistik**: visualisasi transisi state exit, jurnal trade, win-rate, dan distribusi PnL per mode.
- **Slider Sensitivitas & Paritas Replay**: mengubah keketatan gate sinyal (`ScalpingRuntimeConfig`) tanpa mengubah model biaya.
- **Backtest & Walk-Forward**: `engine/backtest/` untuk validasi historis sebelum mempercayai sinyal.
- **Peringatan Jujur**: replay historis dengan fee all-in **belum** menunjukkan edge stabil pada kelas sinyal long-scalp teknikal. Aplikasi **tidak** menampilkan probabilitas palsu (lihat `workplan2.md` §5).

### 11. Fitur Pendukung Lain
- **Learning Path (`LearningPathScreen.kt`)**: modul edukasi trading bertahap dengan kartu struktur pasar (`MarketStructureLearningCard`).
- **Sell Engine (`engine/sell/`)**: evaluator sinyal jual, lifecycle manager, dan pelacak riwayat tick.
- **In-App Updater (`GitHubUpdater.kt` + `AppUpdateCoordinator.kt`)**: pembaruan aplikasi langsung dari halaman rilis GitHub, dipicu dari kartu pemeliharaan di Pengaturan.

### 12. Diagnostik & Log Internal (`AppLogManager`)
- Pencatatan log terstruktur in-app:
  - 🛡️ **Trailing**: Pergerakan peak price, penyesuaian trailing stop, dan trigger eksekusi.
  - 💼 **Trading**: Order submission, konfirmasi status FILLED, dan kalkulasi PnL.
  - 📈 **Market**: Status WebSocket, parsing ticker, dan latensi feed.
  - 🤖 **AI & Signal**: Transisi siklus sinyal trading dan reliability metrics.
- **Modal Logcat Diagnostik**: Filter kategori, pencarian log, dan fitur salin dump state ke clipboard.

---

## 🛠️ Arsitektur & Teknologi

- **Bahasa**: Kotlin 100%
- **UI Framework**: Jetpack Compose dengan Material Design 3 (M3)
- **Arsitektur**: MVVM (Model-View-ViewModel) + Clean Architecture berbasis Engine & Coordinator
- **Asinkron & Reaktif**: Kotlin Coroutines, StateFlow, dan SharedFlow
- **Penyimpanan Lokal**: Room Database (Database Versioning **8** dengan partisi kolom `exchange`) & Encrypted SharedPreferences
- **Jaringan**: OkHttp (REST + WebSocket) dengan WebSocket Reconnection Manager & DNS IPv4-first
- **Keamanan Kredensial**: Penyimpanan lokal API Key/Secret terenkripsi AES-256

---

## 📂 Struktur Direktori Utama

```
app/src/main/java/agu/analys/
├── AnalysApplication.kt             # Inisialisasi aplikasi, database & AppLogManager
├── MainActivity.kt                  # Aktivitas utama Jetpack Compose & Navigation
├── AnalysApplication.kt             # Inisialisasi aplikasi, database & AppLogManager
├── AppContextProvider.kt            # Penyedia Application Context global
├── MainActivity.kt                  # Aktivitas utama Jetpack Compose & Navigation
├── bridge/                          # Jembatan WebView ↔ Kotlin (Lightweight Charts HTML5)
├── config/                          # Konfigurasi bursa, strategi, FeeSchedule & kalkulator biaya
├── data/                            # Discovery simbol dinamis & cache orderbook depth
│   ├── TokocryptoSymbolRepository.kt  # Discovery pair, LOT_SIZE, PRICE_FILTER, MIN_NOTIONAL (Tokocrypto)
│   ├── IndodaxSymbolRepository.kt     # Discovery pair dinamis Indodax (IDR-only)
│   └── OrderBookDepthCache.kt         # Cache kedalaman orderbook
├── database/                        # Room Database v8 + DAO (RealTrade+OpenOrder, SignalLog, TradeHistoryRecord)
├── domain/                          # Domain model & use case abstrak
├── engine/                          # Mesin strategi trading & deteksi pola
│   ├── scalping/                    # Engine Scalping, OrderBookAnalyzer, PaperExitResolver & Lifecycle Manager
│   ├── swing/                       # Engine Swing Trade & 6-Checkpoint Confluence
│   ├── intraday/                    # Engine Intraday 5-Sesi & Anti-Flash-Dump
│   ├── confluence/                  # 6-Checkpoint Confluence Evaluator Multi-Currency
│   ├── indicators/                  # Perhitungan indikator teknikal (RSI, EMA, MACD, ATR, BB)
│   ├── global/                      # Global Market Context & Risk Position Sizer
│   ├── regime/                      # Deteksi anomali makro & volatilitas pasar
│   ├── backtest/                    # Backtest engine & walk-forward validation
│   ├── sell/                        # Sell engine & batch sell
│   └── badge/                       # Logika badge / pencapaian pembelajaran
├── model/                           # Data models (Tokocrypto, Indodax, Signals, Badges, Portofolio, PairRules)
├── network/                         # Provider sentral OkHttpClient & DNS IPv4-first
├── service/                         # Background worker & service
│   ├── TokocryptoMarketService.kt   # REST API pasar Tokocrypto (Type 1 & Type 3)
│   ├── TokocryptoMarketWebSocket.kt # WebSocket streaming pasar Tokocrypto
│   ├── TokocryptoTradeApi.kt        # Signed Order & Account API Tokocrypto
│   ├── IndodaxMarketService.kt      # REST API pasar Indodax
│   ├── IndodaxTradeApiV2.kt         # TAPIv2 Indodax
│   ├── TradingForegroundService.kt  # Service pemantau trailing stop background
│   ├── CandidateScanWorker.kt       # Worker scanner sinyal periodik
│   ├── NewsAiScreenerService.kt     # Engine AI analisis sentimen berita
│   ├── GeminiAiService.kt           # Integrasi Google Gemini API
│   └── PairNarrative.kt             # Konteks narasi dan profil koin AI
├── trading/                         # Simulation order engine, wallet, TradeLogExporter & spot position store
├── ui/                              # Antarmuka Jetpack Compose
│   ├── screens/                     # Layar (Dashboard, DetailChart, LandscapeChart, TradeSimulation, Portfolio, SignalLog, Settings, LearningPath)
│   ├── components/                  # Komponen modular UI (Header, Chart, OrderBook, Dialogs, Cards, Trade Journey, Simulation, Security)
│   └── theme/                       # Palet warna TradingView Dark Theme & Tipografi
├── viewmodel/                       # State management & coordinator pipeline (24 file)
│   ├── TradingViewModel.kt          # ViewModel induk integrasi data pasar & UI
│   ├── MarketDataCoordinator.kt     # Koordinator feed live WebSocket/REST
│   ├── PositionCoordinator.kt       # Koordinator posisi spot & alert
│   ├── SimulationCoordinator.kt     # Koordinator order & dompet simulasi
│   ├── PaperAutoTradeCoordinator.kt # Koordinator Paper Auto-Trade (exit FSM, statistik)
│   ├── RealTradeCoordinator.kt      # Koordinator saldo & eksekusi bursa riil
│   ├── RealTradeExecutor.kt         # Eksekutor order jual/beli riil Tokocrypto & Indodax
│   ├── RealTradeSecurityManager.kt  # Manajer PIN & sesi keamanan trading riil
│   └── OrderViewModel.kt            # ViewModel histori order & transaksi
└── util/                            # Utilitas (AppLogManager, AppPreferences, PriceFormatter, HapticUtil, Notifikasi)
```

---

## ⚙️ Persyaratan Lingkungan & Konfigurasi

- **Android SDK**: Min SDK 24, Target SDK 35 (Compile SDK 36)
- **Build System**: Gradle dengan Kotlin DSL (`build.gradle.kts`)
- **Konfigurasi Kredensial Bursa (Opsional)**:
  - **Tokocrypto**: Masukkan API Key & Secret Key Tokocrypto di menu *Pengaturan $\rightarrow$ Mode Real Tokocrypto*.
  - **Indodax**: Masukkan API Key & Secret Key Indodax TAPIv2 di menu *Pengaturan $\rightarrow$ Mode Real Indodax*.
- **Konfigurasi AI Provider (Opsional)**:
  - Masukkan Gemini API Key di menu *Pengaturan* untuk mengaktifkan audit sentimen berita pasar realtime.

---

## 📚 Dokumen Rujukan

| Dokumen | Isi |
|---------|-----|
| [`APP_CONTEXT.md`](APP_CONTEXT.md) | Arah produk, alur scalp, dan fakta teknis kunci (SSOT cepat) |
| [`AGENTS.md`](AGENTS.md) | Aturan agen, batasan bursa, dan larangan |
| [`FEE_TRUTH.md`](FEE_TRUTH.md) | Model biaya bursa sebenarnya (all-in) per bursa & kuotasi |
| [`SCALP_EXIT.md`](SCALP_EXIT.md) | Logika exit paper trade & R net |
| [`TOKOCRYPTO_API_COMPLIANCE.md`](TOKOCRYPTO_API_COMPLIANCE.md) | Kepatuhan integrasi API Tokocrypto |
| [`workplan2.md`](workplan2.md) | Fase kerja P0–P3, slider map, alur `ScalpingRuntimeConfig` |
