# Checkpoint: Pemisahan Mutlak Prefix Mata Uang ($ & Rp), Konversi Real-Time Portofolio, dan Dual Harga Detail Chart

- **Tanggal / Waktu:** 2026-10-01
- **Status:** Selesai (Completed & Verified Build Clean)
- **Fitur:** Pemisahan Mutlak Mata Uang Kuotasi USDT ($) dan IDR (Rp), Auto-Konversi Order Simulasi, Fitur Konversi Saldo Live Exchange di Portofolio, Pemisahan Saldo Real per Kuotasi, dan Dual Harga di Topbar Detail Chart
- **Komponen Terdampak & Perubahan:**
  1. **Pemisahan Prefix & Model Data (`PortfolioModels.kt`, `SimulationTradeModels.kt`, `SimulationTradeJson.kt`)**:
     - `HoldingItem`: Menyimpan `quoteAsset` ("IDR" / "USDT") dan menghitung `pnlInQuote` & `totalValueInQuote` dalam mata uang kuotasi posisi sehingga tidak pernah tercampur antara `$` dan `Rp`.
     - `SimulationWallet`: Menambahkan `usdtBalance`, `lockedUsdt`, dan `coinQuoteAssets` yang dipersistensikan secara isolatif ke JSON storage.
     - `PriceFormatter`: Menyediakan helper presisi `isUsdtQuote()`, `formatIdrEquivalent()`, `minOrderNominal()`, dan `defaultOrderNominal()` berbasis kurs live tanpa hardcode.
  2. **Single Source of Truth Kurs Exchange (`ExchangeRateManager.kt`, `MarketViewModel.kt`, `TradingViewModel.kt`)**:
     - `ExchangeRateManager`: Mengambil rate live pair `USDTIDR` / `usdt_idr` dari exchange aktif (dengan fallback cross-BTC) dan auto-refresh setiap 60 detik. Mengembalikan `0.0` bila belum ada data (tidak memakai tebakan hardcode).
     - Menyatukan akses rate ke `ExchangeRateManager.usdtIdrRate` di seluruh ViewModel dan UI.
  3. **Auto-Konversi & Konversi Portofolio (`SimulationTradeStore.kt`, `SimulationCoordinator.kt`, `CurrencyConversionDialog.kt`)**:
     - `SimulationTradeStore.ensureQuoteBalance()`: Mengonversi otomatis saldo IDR ke USDT saat pengguna mengeksekusi order simulasi pada pair USDT jika saldo USDT kurang.
     - Fitur dialog konversi manual di tab Portofolio (`CurrencyConversionDialog.kt`) dengan rate live exchange dan proteksi anti-hardcode.
  4. **Pemisahan Saldo Real per Kuotasi (`RealTradeCoordinator.kt`, `OrderViewModel.kt`, `RealPortfolioSummaryCard.kt`)**:
     - Saldo real dipisahkan: pair USDT mengambil saldo USDT di exchange, pair IDR mengambil saldo IDR.
     - Kartu portofolio real menampilkan sub-saldo USDT terpisah dengan prefix `$` dan estimasi ekuivalen Rupiahnya.
  5. **Tampilan Dual Harga di Topbar Detail Chart (`DetailTopBar.kt`, `DetailChartScreen.kt`)**:
     - Menampilkan harga koin USDT dalam `$` dan secara bersamaan memunculkan harga ekuivalen Rupiah (`≈ Rp ...`) di bawahnya yang dihitung secara real-time dari exchange rate.
     - Perbaikan error duplikasi deklarasi dan kompilasi pada `SimulationOrderForm.kt`, `DetailChartScreen.kt`, `PortfolioScreen.kt`, `PortfolioComponents.kt`, dan `SimulationCoordinator.kt`.

