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
