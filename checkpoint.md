# Checkpoint: Pemisahan Mutlak Chart Fullscreen Berdasarkan Exchange (Indodax vs Tokocrypto)

- **Tanggal / Waktu:** 2026-09-30
- **Status:** Selesai (Completed & Verified)
- **Fitur:** Pemisahan Mutlak Charting Fullscreen Sesuai Exchange Aktif (Indodax Asli vs Tokocrypto/Binance)
- **Komponen Terdampak:**
  1. `TradingViewFullscreenChart.kt`: Mengisolasi pemuatan chart berdasarkan `marketDataSource`:
     - **Mode INDODAX**: Memuat halaman chart resmi Indodax (`https://indodax.com/chart/<SYMBOL>`) dengan data orderbook & transaksi asli dari bursa Indodax.
     - **Mode TOKOCRYPTO**: Memuat widget resmi TradingView untuk Binance/Tokocrypto (`BINANCE:<BASE>IDR`, `BINANCE:<BASE>USDT`, `BINANCE:<BASE>BTC`).
  2. `LandscapeChartScreen.kt`: Mengalirkan `marketDataSource` secara reaktif dari `TradingViewModel` ke `TradingViewFullscreenChart`.
  3. `TradingViewModels.kt`: Helper `effectiveTradingViewSymbol()` untuk pemetaan simbol Binance/Tokocrypto.
