# Checkpoint: Perbaikan & Pembacaan Saldo Riil USDT Tokocrypto & Indodax (`TokocryptoTradeApi.kt`, `RealTradeCoordinator.kt`, `PortfolioScreen.kt`, `DetailChartScreen.kt`)

- **Tanggal / Waktu:** 2026-10-02
- **Status:** Selesai (Completed & Verified Build Clean)
- **Komponen Terdampak:**
  1. `TokocryptoTradeApi.kt`
  2. `RealTradeCoordinator.kt`
  3. `OrderViewModel.kt` & `TradingViewModel.kt` & `TradingViewModelOrders.kt`
  4. `PortfolioScreen.kt` & `RealPortfolioView.kt` & `RealPortfolioSummaryCard.kt`
  5. `DetailChartScreen.kt`
  6. `AppPreferences.kt`
- **Akar Masalah & Resolusi:**
  1. **Akar Masalah Saldo Riil USDT Tidak Terbaca**:
     - *Parsing JSON Response Token/Assets*: `extractAssets()` sebelumnya hanya mencari `data.accountAssets`. Jika server Tokocrypto merespons struktur array langsung, `balances`, atau `assets`, aset tidak terdeteksi dan parsing saldo spot gagal.
     - *Ketiadaan Sinkronisasi Waktu Server*: Selisih jam perangkat (device clock drift) memicu error `-1021: Timestamp for this request is outside of recvWindow`.
     - *Case-Sensitivity Kunci Aset*: Kunci saldo hanya tersimpan dalam huruf kecil (`usdt`), sementara beberapa komponen ViewModel dan UI melakukan lookup huruf kapital (`USDT`).
     - *Ketiadaan Fallback Targeted Aset*: Jika endpoint umum `/open/v1/account/spot` gagal atau mengembalikan daftar koin tanpa baris USDT, saldo USDT tidak diperbarui.
     - *UI Summary Card Menyembunyikan Kartu USDT*: `RealPortfolioSummaryCard.kt` sebelumnya hanya menampilkan bagian USDT jika `realUsdt > 0.00000001 || lockedUsdt > 0.0`. Pada Tokocrypto (yang berbasis USDT), pengguna melihat seolah saldo USDT tidak terbaca sama sekali jika saldo awal masih belum termuat atau bernilai 0.
     - *DetailChartScreen Tidak Memiliki Fallback Resolusi*: `availableQuote` untuk pair USDT hanya memeriksa `realBalance` StateFlow tanpa memeriksa `realFreeBalanceForQuote("USDT")`, `realBalanceForQuote("USDT")`, atau disk cache `prefs.getSavedRealBalance()`.
  2. **Implementasi Solusi & Peningkatan**:
     - **Sinkronisasi Jam Server Tokocrypto (`syncServerTime`)**: Mengambil waktu resmi dari `https://www.tokocrypto.com/open/v1/common/time` dan menghitung offset milidetik agar query `timestamp` selalu selaras dengan server exchange (menghindari error `-1021`).
     - **Ekstraksi Aset Multiformat (`extractAssets`)**: Mendukung `accountAssets`, `balances`, `assets`, `userAssets`, array root, dan Map object aset sintetis.
     - **Penyimpanan Dual-Casing (`parseBalances`)**: Menyimpan semua aset ke `freeMap`, `holdMap`, dan `totalMap` baik dalam huruf kecil (`usdt`, `idr`) maupun huruf besar (`USDT`, `IDR`).
     - **Targeted Fallback USDT (`/open/v1/account/spot/asset?asset=USDT`)**: Jika endpoint umum tidak memuat USDT, sistem otomatis mengeksekusi request targeted ke `/open/v1/account/spot/asset?asset=USDT` dengan query param terurut dan signature HMAC-SHA256 yang valid.
     - **Penyimpanan Cache Disk Dual-Casing (`AppPreferences.kt`)**: Memastikan `getSavedRealBalance()` mengembalikan key lowercase dan uppercase.
     - **Dukungan Force Refresh & Auto-Fetch (`RealTradeCoordinator.kt`)**: Menambahkan parameter `force: Boolean = false` agar pengguna bisa melakukan refresh instan tanpa terhalang cooldown, dan trigger auto-fetch saat inisialisasi jika API key tersedia.
     - **Penyelarasan Tampilan Portofolio & Detail (`PortfolioScreen.kt` & `DetailChartScreen.kt`)**:
       - `PortfolioScreen.kt` menyegarkan saldo saat PIN di-unlock atau mode Real dibuka, serta menampilkan label bursa yang dinamis ("Aset Riil Tokocrypto Terhubung").
       - `RealPortfolioSummaryCard.kt` selalu menampilkan kartu "SALDO USDT ($)" secara transparan dan jelas jika bursa aktif adalah Tokocrypto.
       - `DetailChartScreen.kt` menyegarkan saldo real di background saat membuka koin di Mode Real dan melakukan resolusi bertingkat (free balance -> total balance -> in-memory map -> disk cache) untuk mencegah nominal 0 palsu.
  3. **Verifikasi Kompilasi**:
     - `compile_applet` berhasil (BUILD SUCCESSFUL).

---

# Checkpoint: Perbaikan Bug Order Simulasi BTCUSDT Tokocrypto, Isolasi Kuotasi Holding Status ($ vs Rp), Akselerasi Loading Dashboard & Paging 15 Aset Volume 24H

- **Tanggal / Waktu:** 2026-10-03
- **Status:** Selesai (Completed, Build Clean, All Tests Passed)
- **Komponen Terdampak:**
  1. `TradingViewModelOrders.kt`
  2. `TradingViewModel.kt`
  3. `DashboardScreen.kt`
  4. `SimulationCoordinator.kt`
  5. `TokocryptoMarketService.kt`
  6. `workplan.md` & `checkpoint.md`
- **Akar Masalah & Resolusi:**
  1. **Akar Masalah Order BTCUSDT Jadi BTCIDR di Dashboard**:
     - *Validasi Kuotasi pada `getHoldingStatus`*: `getHoldingStatus(pair)` dalam mode simulasi memeriksa `simQty = simWallet.coinBalances[base]` tanpa memverifikasi apakah `pair.quoteAsset` cocok dengan kuotasi koin yang dibeli (`simWallet.quoteForCoin(base)`). Sehingga ketika `BTCUSDT` dibeli, `getHoldingStatus(TradingPair("BTCIDR"))` ikut mengembalikan `isHolding = true` dengan harga beli $65.000 dibandingkan harga IDR Rp 1 Miliar.
     - *Fallback Resolusi Simbol di `ActiveHoldingSection` & Quick Filter*: `DashboardScreen.kt` sebelumnya melakukan lookup `strategyPairs.find { ... }` yang berisi koin dengan `defaultQuote` (IDR). Jika `BTCUSDT` tidak ada di `strategyPairs`, terjadi fallback salah kuotasi. Pada tab `[💼 Holding]`, sistem memfilter `strategyPairs` IDR sehingga pair `BTCUSDT` tidak muncul.
     - *Ketiadaan Partisi Exchange di `SimulationCoordinator`*: `SimulationCoordinator` memanggil `store.getWallet()` dan `store.placeOrder()` dengan exchange default tanpa menyertakan exchange aktif yang sedang dipilih pengguna.
  2. **Akar Masalah Dashboard Loading Lambat & Paging Volume 24 Jam**:
     - *Endpoint Fallback yang Menggantung*: `TokocryptoMarketService.fetchMarketRankings` memuat daftar fallback ke domain Binance (`api.binance.me`, `data-api.binance.vision`) yang terblokir di Indonesia, menyebabkan timeout 15-30 detik sebelum data pasar termuat.
     - *Threshold Volume Terlalu Ketat*: `isSafeTradableAsset` mematok threshold 100 Juta IDR dan 10.000 USDT sehingga banyak pair liquid tereliminasi sebelum diurutkan.
     - *Redundant Duplicate Ticks*: `allTicks` memuat entitas berulang untuk key underscore dan non-underscore (`btc_idr`, `BTC_IDR`, `BTCIDR`) yang memperlambat sorting.
  3. **Implementasi Solusi & Peningkatan**:
     - **Pencocokan Kuotasi Ketat (`getHoldingStatus` & `holdingStatuses`)**:
       - Memeriksa kesesuaian `pair.quoteAsset.equals(simWallet.quoteForCoin(base), ignoreCase = true)` sebelum menetapkan status holding pada pair koin simulasi.
       - Mengalirkan `simCoordinator.wallet` ke dalam StateFlow `holdingStatuses` sehingga pembelian koin USDT (cth: `BTCUSDT`) secara reaktif hanya memberi tanda holding pada pair USDT.
     - **Resolusi Pasangan Holding Mandiri (`DashboardScreen.kt`)**:
       - `activeHoldingList` langsung menggunakan `TradingPair.fromCustomSymbol(symbol)` untuk setiap entri holding aktif, mempertahankan kuotasi asli (`BTCUSDT` tetap `BTCUSDT`).
       - Tab filter `[💼 Holding]` mengumpulkan pasangan koin langsung dari kunci `holdingStatuses` yang aktif, memastikan semua aset yang sedang di-hold (baik USDT maupun IDR) tampil sempurna.
     - **Isolasi Exchange di `SimulationCoordinator.kt`**:
       - Meneruskan `exchangeProvider = { prefs.marketDataSource.name }` ke `SimulationCoordinator`, `placeOrder`, `getWallet`, `getOpenOrders`, `getTradeHistory`, dan `executeSimulationSellOrders`.
     - **Akselerasi Jaringan & Fast Ticker Discovery (`TokocryptoMarketService.kt`)**:
       - Menghapus semua fallback domain Binance yang terblokir; hanya menggunakan endpoint resmi Tokocrypto Type 1, Type 3 (`cloudme-toko.2meta.app`), dan Open API.
       - Mengurangi threshold filter volume agar semua aset liquid Tokocrypto IDR/USDT masuk dalam ranking 24h.
     - **Sorting & Paging 15 Pasangan Koin Volume 24H**:
       - Tab `[Semua]` secara tegas mengurutkan aset bursa aktif murni berdasarkan Volume 24 Jam Tertinggi (USDT dinormalisasi ke IDR via live rate `ExchangeRateManager`).
       - Menampilkan 15 aset pertama, dan memuat 15 aset berikutnya secara seamless saat pengguna melakukan scroll mendekati bagian bawah list. Tab filter lainnya (`Signal Kuat`, `Holding`, `Watchlist`) tetap tampil utuh tanpa paginasi.
  4. **Verifikasi**:
     - `compile_applet` berhasil (BUILD SUCCESSFUL).
     - `gradle :app:testDebugUnitTest` berhasil (BUILD SUCCESSFUL, semua unit tests lulus).

---

# Checkpoint: Audit & Perbaikan Format Kuotasi Multi-Mata Uang ($ vs Rp) pada Riwayat & Log Sinyal AI, Confluence Evaluator, Histori Siklus Trade, dan Tab Real Portfolio

- **Tanggal / Waktu:** 2026-10-05
- **Status:** Selesai (Completed, Build Clean, All Tests Passed)
- **Komponen Terdampak:**
  1. `TradeHistoryRecordEntity.kt`
  2. `SignalLogRepository.kt` & `TradeHistoryRecorder.kt`
  3. `RealPortfolioHistoryTab.kt` & `SimulationOrderCards.kt`
  4. `ConfluenceEvaluator.kt` & `ScalpingMtfEvaluator.kt` & `OrderBookAnalyzer.kt`
  5. `IntradayEvaluator.kt` & `SwingEvaluator.kt` & `LearningTradingEngine.kt`
  6. `OrderBookAndTradesPanel.kt` & `IndicatorDashboard.kt` & `LogcatDiagnosticDialog.kt`
  7. `MarketViewModel.kt` & `CandidateScanWorker.kt`
  8. `TradeLogDetailDialog.kt` & `TradeLogExporter.kt`
  9. `PriceFormatter.kt`
  10. `README.md` & `workplan.md` & `checkpoint.md`
- **Akar Masalah & Resolusi:**
  1. **Akar Masalah Masih Ada Kuotasi "Rp" pada Nilai Dollar ($)**:
     - *Default Fallback di PriceFormatter*: `PriceFormatter.formatPrice` menggunakan parameter bawaan `quoteAsset = "IDR"`. Pemanggilan `formatPrice(price)` tanpa menyertakan `quoteAsset` pada pasangan USDT (cth: `BTCUSDT`, `ETHUSDT`) secara otomatis memformat harga dengan prefix `"Rp "`.
     - *Hardcoded Prefix "Rp" di Engine Evaluator*:
       - `ConfluenceEvaluator.kt`: String pesan `AOV` dan `RR` menuliskan `"Rp ${PriceFormatter.formatPrice(supportLevel, showSymbol = false)}"`, `"SL di Rp ... TP1 di Rp ..."`.
       - `OrderBookAnalyzer.kt`: Advice orderbook spread menuliskan `"Taker @ Rp ..."` dan `"gap spread Rp ..."`.
       - `SwingEvaluator.kt` & `IntradayEvaluator.kt`: String alasan analisa menuliskan `"Level penting → Support Rp ... | Resistance Rp ..."`, `"REJECTION di Support: candle pantul naik dari Rp ..."`, `"BREAKOUT di Resistance Rp ..."`, `"Recent high Rp ..."`.
     - *Omission quoteAsset di UI Component*:
       - `RealPortfolioHistoryTab.kt`: PnL calculation line 212 memanggil `PriceFormatter.formatPrice(kotlin.math.abs(effectivePnlIdr))` tanpa `quoteAsset`, menampilkan `+Rp 12.50` untuk trade USDT.
       - `SimulationOrderCards.kt`: PnL nilai negatif kehilangan tanda minus saat di-abs tanpa sign handler eksplisit.
       - `MarketViewModel.kt`: `aiRationale` memanggil `PriceFormatter.formatPrice(tick.price)` tanpa `pair.quoteAsset`.
       - `CandidateScanWorker.kt`: Notifikasi scanner memanggil `PriceFormatter.formatPrice` tanpa `quoteAsset`.
       - `OrderBookAndTradesPanel.kt` & `IndicatorDashboard.kt`: Header tab dan label indikator mengasumsikan kuotasi IDR.
       - `TradeHistoryRecordEntity.kt`: Getter `quoteAsset` sebelumnya hanya memeriksa `endsWith("USDT")` tanpa menangani token lain seperti `USDC`, `BUSD`, `BIDR`, atau simbol berafiks underscore (`BTC_USDT`).
  2. **Implementasi Solusi & Peningkatan**:
     - **Penyempurnaan Parser Kuotasi (`PriceFormatter.extractQuote`)**:
       - Menambahkan helper terpusat `PriceFormatter.extractQuote(symbol)` yang secara presisi mengidentifikasi `USDT`, `USDC`, `BUSD`, `BIDR`, atau `IDR` dari berbagai format penulisan simbol.
       - Memperbarui getter `baseAsset` dan `quoteAsset` pada `TradeHistoryRecordEntity` agar konsisten di seluruh lapisan database dan UI.
     - **Pembersihan Hardcoded "Rp" pada Seluruh Engine Evaluator**:
       - `ConfluenceEvaluator.kt`: Menambahkan parameter `quoteAsset: String = "IDR"` dan mengganti seluruh teks `"Rp "` menjadi interpolasi dinamis `${PriceFormatter.formatPrice(level, quoteAsset = quoteAsset)}`.
       - `OrderBookAnalyzer.kt`: `analyzeSpread` menerima `quoteAsset` dan menghasilkan advice orderbook dengan format mata uang sesuai ($ untuk USDT, Rp untuk IDR).
       - `ScalpingMtfEvaluator.kt`, `IntradayEvaluator.kt`, `SwingEvaluator.kt`: Mengambil `quoteAsset` dari `symbol` dan menyuntikkannya ke dalam string alasan sinyal teknikal dan checkpoint waterfall.
       - `LearningTradingEngine.kt` & `CandidateScanWorker.kt`: Meneruskan `symbol = tick.symbol` saat memicu evaluasi swing dan intraday.
     - **Penyelarasan Seluruh Komponen Tampilan UI**:
       - `RealPortfolioHistoryTab.kt`: Mengirimkan `quoteAsset = quoteAsset` dan format sign `+`/`-` pada kartu riwayat trade real.
       - `SimulationOrderCards.kt`: Memperbaiki tampilan sign PnL dan kuotasi dollar pada kartu order simulasi.
       - `MarketViewModel.kt`: Menyertakan `pair.quoteAsset` pada `aiRationale` dan format volume.
       - `OrderBookAndTradesPanel.kt`: Header kolom berubah dinamis `"HARGA ($quoteAsset)"` dan baris harga pasar diformat sesuai kuotasi aktif.
       - `IndicatorDashboard.kt`: Menerima `quoteAsset` untuk format EMA20, EMA50, dan ATR.
       - `LogcatDiagnosticDialog.kt`: Menggunakan `quoteAsset` dinamis dari simbol posisi aktif.
       - `TradeLogDetailDialog.kt` & `TradeLogExporter.kt`: Menampilkan nama exchange dinamis (`Tokocrypto` untuk pasangan USDT, `Indodax` untuk IDR) dan mengekspor laporan markdown audit terstruktur untuk verifikasi LLM.
  3. **Verifikasi Kompilasi & Pengujian**:
     - `compile_applet` berhasil (BUILD SUCCESSFUL).

---

# Checkpoint: Resolusi Bug Sinkronisasi Data MTF & Error Saat Berganti Bursa (`MtfCacheManager.kt`, `TokocryptoMarketService.kt`, `LearningTradingEngine.kt`, `TradingViewModel.kt`)

- **Tanggal / Waktu:** 2026-10-05
- **Status:** Selesai (Completed & Verified Build Clean)
- **Komponen Terdampak:**
  1. `TokocryptoMarketService.kt`
  2. `MtfCacheManager.kt`
  3. `LearningTradingEngine.kt`
  4. `TradingViewModel.kt`
  5. `checkpoint.md` & `workplan.md`
- **Akar Masalah & Resolusi:**
  1. **Akar Masalah Error Data MTF Saat Ganti Bursa**:
     - *URL Fallback Bug pada TokocryptoMarketService*: Pemanggilan `getWithFallback` sebelumnya menghasilkan URL dengan duplikasi `/api/v3/api/v3` pada path relatif, menyebabkan HTTP 404 pada fallback Kline. Selain itu, bila deteksi `symbolType` belum sinkron (defaulting ke Type 1 untuk koin Type 3 atau sebaliknya), endpoint cadangan (Type 3 / Open API / Binance) tidak dicoba secara komprehensif, sehingga Kline kosong dan MTF status langsung ditandai `ERROR`.
     - *Kegagalan Transisi Cepat Status MTF pada MtfCacheManager*: Saat beralih bursa, pembersihan cache tanpa pemberian status awal `SYNCING` menyebabkan UI membaca status null/error sementara prefetch jaringan sedang berlangsung. Ketiadaan retry ringan pada `safeFetch` membuat gangguan soket sekejap saat pergantian bursa langsung memicu status `MtfStatus.ERROR`.
     - *Stuck Cooldown 10s & Symbol Mismatch di LearningTradingEngine*: `refreshScalpingTimeframesIfDue` sebelumnya mencatat `lastMtfRefresh = now` meskipun candle yang termuat `< 20` (masih dalam antrean fetch), sehingga engine terkunci selama 10 detik dan menampilkan status `"DATA MTF: Sinkronisasi candle (H1 0/20 · M15 0/20 · M1 0/20)"`. Perbandingan simbol `currentTick?.symbol == symbol` juga sensitif format/underscore.
     - *Kondisi Bersih Engine & Aktivasi MTF Tak Bersyarat di TradingViewModel*: `selectPair` sebelumnya hanya mengaktifkan MTF cache jika `strategyMode == SCALPING`, dan `setMarketDataSource` tidak mereset buffer candle engine saat hard stop bursa dilakukan.
  2. **Implementasi Solusi & Peningkatan**:
     - **Robust URL Routing & Multi-Fallback Kline (`TokocryptoMarketService.kt`)**:
       - Memperbaiki `getWithFallback` agar resolusi path relatif maupun absolut URL bebas dari duplikasi `/api/v3`.
       - Menyusun fallback rantai lengkap: Type 1 URL, Type 3 URL (`cloudme-toko.2meta.app`), Tokocrypto Open API (`/open/v1/market/klines`), dan Binance Kline.
     - **Transisi Status Responsif & Multi-Alias Caching (`MtfCacheManager.kt`)**:
       - `setActiveSymbol` segera memetakan status `SYNCING`/`READY` ke StateFlow `_mtfState` untuk mencegah kedipan badge `ERROR` di UI saat perpindahan bursa.
       - `safeFetch` menyertakan retry backoff otomatis 200ms jika request pertama kosong.
       - Caching multi-alias mencakup format scoped, stripped/clean, compact symbol, dan pair underscore.
     - **Polling Sinkronisasi & Resiliensi Simbol (`LearningTradingEngine.kt`)**:
       - Menambahkan helper normalisasi simbol `isMatchingSymbol` yang kebal terhadap variasi underscore/casing.
       - Menambahkan loop polling cepat (delay 250ms hingga 5 percobaan) saat candle MTF sedang di-fetch, sehingga engine langsung terisi dan mengeksekusi analisis tanpa menunggu tick berikutnya.
       - Jika candle belum lengkap setelah polling, `lastMtfRefresh` direset ke `0L` agar siklus berikutnya dapat langsung mencoba lagi tanpa penalti cooldown 10 detik.
     - **Penyelarasan Siklus Hidup Bursa di ViewModel (`TradingViewModel.kt`)**:
       - `setMarketDataSource` membersihkan dan mereset buffer engine (`engine.resetForOffline()`).
       - `selectPair` mengaktifkan `MtfCacheManager.setActiveSymbol` secara tanpa syarat di semua mode strategi.
  3. **Verifikasi Kompilasi & Pengujian**:
     - `compile_applet` berhasil (BUILD SUCCESSFUL).
     - `gradle :app:testDebugUnitTest` berhasil (BUILD SUCCESSFUL, seluruh unit tests lulus).
