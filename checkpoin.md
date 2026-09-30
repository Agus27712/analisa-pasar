# Checkpoint: Isolasi Total Data Cache & Room Database Berdasarkan Exchange (Tokocrypto vs Indodax)

- **Tanggal / Waktu:** 2026-09-30
- **Status:** Selesai (Completed & Verified Build Clean)
- **Fitur:** Isolasi Menyeluruh Cache In-Memory, Storage SharedPreferences, dan Room Database per Exchange
- **Komponen Terdampak & Perubahan:**
  1. **Room Database (`AppDatabase.kt`, `TradeHistoryRecordEntity.kt`, `RealTradeDao`, `SignalLogDao`, `TradeHistoryRecordDao`)**:
     - Menambahkan kolom `exchange: String = "TOKOCRYPTO"` ("TOKOCRYPTO" vs "INDODAX") pada seluruh entitas: `RealTradeEntity`, `RealOpenOrderEntity`, `SignalLogEntity`, dan `TradeHistoryRecordEntity`.
     - Menyediakan kueri DAO berbasis `WHERE exchange = :exchange` untuk mencegah kontaminasi histori transaksi, sinyal AI, dan pesanan terbuka antar bursa.
     - Menaikkan versi database Room ke `version = 7` (angka ganjil sesuai aturan).
  2. **Signal & Trade Repositories (`SignalLogRepository.kt`, `TradeHistoryRecorder.kt`)**:
     - Mengisolasi pemrosesan tick pasar, pencatatan eksekusi buy/sell, dan penghitungan akurasi win rate per exchange.
     - Menyediakan Flow `getLogsByExchangeFlow(exchange)` dan `getRecordsByExchangeFlow(exchange)`.
  3. **Spot Position & Trailing Store (`SpotPositionStore.kt`)**:
     - Mengubah skema kunci penyimpanan menjadi `${exchange}_${if (isReal) "real" else "sim"}_${symbol}`.
     - Mendukung isolasi posisi terbuka, trailing stop, auto-sell TP1/TP2, dan riwayat posisi antar exchange dengan backward compatibility.
  4. **Simulation Engine Store (`SimulationTradeStore.kt`)**:
     - Mengisolasi wallet saldo IDR, daftar orderbook simulasi (open orders), dan riwayat trade simulasi ke kunci per bursa (`${exchange}_sim_wallet`, `${exchange}_sim_open_orders`, `${exchange}_sim_trade_history`).
  5. **Orderbook & Depth Cache (`OrderBookDepthCache.kt`)**:
     - Mengisolasi snapshot orderbook dan rasio orderbook pressure berdasarkan prefix `${exchange}_${symbol}`.
     - Routing fetch otomatis ke Tokocrypto REST/WebSocket saat mode Tokocrypto aktif dan ke Indodax saat mode Indodax aktif.
  6. **Multi-Timeframe Kline Cache (`MtfCacheManager.kt`)**:
     - Mengisolasi in-memory candle cache (M1, M15, H1, H4) menggunakan kunci komposit `${exchange}_${symbol}`.
  7. **Market Cache (`MarketDataCache.kt`) & Price Alerts (`PriceAlertStore.kt`)**:
     - Mempartisi snapshot pair candles dan metadata pasangan mata uang dengan sumber `MarketDataSource`.
     - Menambahkan filter dan kolom `exchange` pada `PriceAlert`.
