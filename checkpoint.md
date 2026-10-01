# Checkpoint: Resolusi 7 Temuan Audit Codebase (WebSocket AggTrade & Depth, 100% Strict Exchange Isolation, & Type 1/3 Ticker Routing)

- **Tanggal / Waktu:** 2026-10-01
- **Status:** Selesai (Completed, Verified Build Clean & All Unit Tests Pass)
- **Fitur / Resolusi Audit:**
  1. **WebSocket AggTrade & Depth (`TokocryptoMarketWebSocket.kt`, `MarketDataCoordinator.kt`)**:
     - Ditambahkan stream combined `@aggTrade` dan `@depth20@100ms` ke WebSocket Tokocrypto.
     - Parsing payload frame untuk `@aggTrade` (aggregate trade items) dan `@depth` (bids & asks order book).
     - Di-wiring langsung ke `_tradeStream` dan `_orderBookBids` / `_orderBookAsks` serta memperbarui `OrderBookDepthCache`.
  2. **Eliminasi Unscoped Fallback di `OrderBookDepthCache.kt`**:
     - Menghapus seluruh fallback tanpa exchange (`cache[normalizeSymbol(symbol)]`).
     - Semua pembacaan, penulisan, dan kalkulasi pressure strictly scoped ke `${exchange}_${symbol}`.
  3. **Eliminasi Unscoped Fallback di `MtfCacheManager.kt`**:
     - Menghapus seluruh fallback unscoped (`cache[norm]`) dan unpartitioned mirror.
     - Kunci in-memory MTF strictly menggunakan `${exchange}_${symbol}`.
  4. **Pembersihan Unscoped Key di `MarketDataCache.kt`**:
     - Menghapus penulisan dan pembacaan persistent cache tanpa prefix exchange (`pair_*`, `pairs_metadata_json`).
     - Semua cache persisten (`dashboard_ticks`, `worth_coins`, `pair_snapshots`, `pairs_metadata`) terisolasi 100% per `source.name.lowercase()`.
  5. **Scoping Room DB Queries & Flow (`OrderViewModel.kt`, `SignalLogRepository.kt`, `TradeHistoryRecorder.kt`)**:
     - `OrderViewModel.realOpenOrders` dan `realTrades` membaca dari `getOpenOrdersByExchangeFlow(exchange)` dan `getTradesByExchangeFlow(exchange)`.
     - `SignalLogRepository` & `TradeHistoryRecorder` memprioritaskan kueri DAO berpartisi `exchange`.
  6. **Isolasi Storage Saldo Real & Average Price (`AppPreferences.kt`, `RealTradeCoordinator.kt`)**:
     - `getSavedRealBalance()` dan `saveRealBalance()` dipartisi per bursa (`"${exchange.lowercase()}_real_balance"`).
     - `getSavedRealAvgBuyPrices()` dan `saveRealAvgBuyPrices()` dipartisi per bursa (`"${exchange.lowercase()}_real_avg_prices"`).
     - `RealTradeCoordinator` membaca dan menyimpan saldo real & harga beli rata-rata secara mandiri untuk Tokocrypto vs Indodax.
  7. **Routing Bulk Ticker Type 1 vs Type 3 (`TokocryptoMarketService.kt`)**:
     - `fetchTickers(symbols)` memetakan simbol Type 1 (MBX Cloud) dan Type 3 (NextMe) dan menggabungkan hasil fetch dari kedua cluster endpoint tanpa kehilangan data.
