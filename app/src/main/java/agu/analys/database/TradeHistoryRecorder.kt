package agu.analys.database

import agu.analys.trading.TradeSignalSnapshot
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Manajer pencatatan siklus hidup perdagangan (Trade Lifecycle Recorder).
 * Mengintegrasikan alur:
 * Pengeluaran Sinyal Buy (Mode & Perhitungan) -> User Buy -> Durasi Hold -> Sell (Profit/Loss).
 */
class TradeHistoryRecorder(
    private val dao: TradeHistoryRecordDao,
    private val scope: CoroutineScope
) {
    private val tickThrottleMap = ConcurrentHashMap<String, Long>()

    val allRecordsFlow: Flow<List<TradeHistoryRecordEntity>> = dao.getAllRecordsFlow()

    fun getRecordsBySymbolFlow(symbol: String): Flow<List<TradeHistoryRecordEntity>> =
        dao.getRecordsBySymbolFlow(symbol)

    fun getHoldingRecordsFlow(): Flow<List<TradeHistoryRecordEntity>> =
        dao.getHoldingRecordsFlow()

    /**
     * Tahap 1 & 2: Catat pengeluaran sinyal buy + eksekusi pembelian pengguna.
     */
    fun recordBuy(
        symbol: String,
        isReal: Boolean,
        strategyMode: String,
        buyPrice: Double,
        buyQuantity: Double,
        buyTotalIdr: Double,
        buyOrderType: String = "LIMIT",
        snapshot: TradeSignalSnapshot? = null,
        signalPrice: Double = buyPrice,
        signalConfidence: Int = snapshot?.confidenceScore ?: 75,
        targetPrice1: Double = 0.0,
        targetPrice2: Double = 0.0,
        stopLossPrice: Double = 0.0,
        customUuid: String? = null
    ): String {
        val tradeUuid = customUuid ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val normSymbol = symbol.uppercase().replace("_", "")

        // Bangun ringkasan perhitungan teknikal saat sinyal buy dikeluarkan
        val calculationSummary = buildCalculationSummary(snapshot, strategyMode)
        val reasons = snapshot?.reasons?.joinToString(" • ")
            ?: "Sinyal indikator teknikal terkonfirmasi pada mode ${strategyMode.uppercase()}"
        val snapshotJson = snapshot?.toJson()?.toString()

        val feeRate = if (isReal) 0.003 else 0.003
        val feeIdr = buyTotalIdr * feeRate

        scope.launch(Dispatchers.IO) {
            try {
                val entity = TradeHistoryRecordEntity(
                    tradeUuid = tradeUuid,
                    symbol = normSymbol,
                    isRealTrade = isReal,
                    strategyMode = strategyMode.uppercase(),
                    signalTime = now,
                    signalPrice = if (signalPrice > 0) signalPrice else buyPrice,
                    signalConfidence = signalConfidence,
                    signalCalculationSummary = calculationSummary,
                    signalReasons = reasons,
                    signalSnapshotJson = snapshotJson,
                    targetPrice1 = targetPrice1,
                    targetPrice2 = targetPrice2,
                    stopLossPrice = stopLossPrice,
                    buyTime = now,
                    buyPrice = buyPrice,
                    buyQuantity = buyQuantity,
                    buyTotalIdr = buyTotalIdr,
                    buyFeeIdr = feeIdr,
                    buyOrderType = buyOrderType,
                    status = "HOLDING",
                    holdingDurationMs = 0L,
                    peakPriceDuringHold = buyPrice,
                    troughPriceDuringHold = buyPrice,
                    maxProfitPctDuringHold = 0.0,
                    maxDrawdownPctDuringHold = 0.0,
                    isTrailingUsed = false,
                    trailingLockPrice = null
                )
                dao.insertRecord(entity)
            } catch (_: Exception) {}
        }
        return tradeUuid
    }

    /**
     * Tahap 3: Update durasi hold dan evolusi harga (peak & drawdown) saat market tick berjalan.
     */
    fun processPriceTick(symbol: String, currentPrice: Double) {
        if (currentPrice <= 0.0 || symbol.isBlank()) return
        val normSymbol = symbol.uppercase().replace("_", "")
        val now = System.currentTimeMillis()

        // Throttle agar tidak membebani database Room
        val lastUpdate = tickThrottleMap[normSymbol] ?: 0L
        if (now - lastUpdate < 2000L) return
        tickThrottleMap[normSymbol] = now

        scope.launch(Dispatchers.IO) {
            try {
                val holdings = dao.getHoldingRecords()
                    .filter { it.symbol.equals(normSymbol, ignoreCase = true) }
                if (holdings.isEmpty()) return@launch

                for (record in holdings) {
                    val duration = (now - record.buyTime).coerceAtLeast(0L)
                    val rawPnlPct = if (record.buyPrice > 0) {
                        ((currentPrice - record.buyPrice) / record.buyPrice) * 100.0
                    } else 0.0

                    val newPeak = if (record.peakPriceDuringHold <= 0.0) currentPrice else max(record.peakPriceDuringHold, currentPrice)
                    val newTrough = if (record.troughPriceDuringHold <= 0.0) currentPrice else min(record.troughPriceDuringHold, currentPrice)
                    val newMaxProfit = max(record.maxProfitPctDuringHold, max(0.0, rawPnlPct))
                    val newMaxDrawdown = min(record.maxDrawdownPctDuringHold, min(0.0, rawPnlPct))

                    val updated = record.copy(
                        holdingDurationMs = duration,
                        peakPriceDuringHold = newPeak,
                        troughPriceDuringHold = newTrough,
                        maxProfitPctDuringHold = newMaxProfit,
                        maxDrawdownPctDuringHold = newMaxDrawdown
                    )
                    dao.updateRecord(updated)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Tahap 4: Catat penjualan (Sell), durasi hold final, serta realisasi Profit/Loss (IDR & %).
     */
    fun recordSell(
        symbol: String,
        isReal: Boolean,
        sellPrice: Double,
        sellQuantity: Double,
        sellReason: String = "MANUAL_SELL",
        strategyMode: String? = null,
        customPnlIdr: Double? = null,
        customPnlPercent: Double? = null,
        tradeUuid: String? = null,
        isTrailingUsed: Boolean = false,
        trailingLockPrice: Double? = null
    ) {
        if (sellPrice <= 0.0 || sellQuantity <= 0.0) return
        val normSymbol = symbol.uppercase().replace("_", "")
        val now = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            try {
                // Cari record HOLDING aktif yang cocok
                val targetRecord = if (tradeUuid != null) {
                    dao.getRecordByUuid(tradeUuid)
                } else {
                    dao.getActiveHoldingForSymbol(normSymbol, isReal)
                }

                val feeRate = if (isReal) 0.003 else 0.003
                val sellTotalIdr = sellPrice * sellQuantity
                val sellFeeIdr = sellTotalIdr * feeRate

                if (targetRecord != null && targetRecord.status == "HOLDING") {
                    val durationMs = (now - targetRecord.buyTime).coerceAtLeast(1000L)
                    val totalCost = targetRecord.buyTotalIdr + targetRecord.buyFeeIdr
                    val netProceeds = sellTotalIdr - sellFeeIdr
                    val computedPnlIdr = customPnlIdr ?: (netProceeds - totalCost)
                    val computedPnlPct = customPnlPercent ?: if (totalCost > 0) ((computedPnlIdr / totalCost) * 100.0) else 0.0
                    val isWin = computedPnlIdr >= 0.0

                    val closedRecord = targetRecord.copy(
                        status = "CLOSED",
                        holdingDurationMs = durationMs,
                        sellTime = now,
                        sellPrice = sellPrice,
                        sellQuantity = sellQuantity,
                        sellTotalIdr = sellTotalIdr,
                        sellFeeIdr = sellFeeIdr,
                        sellReason = sellReason,
                        pnlIdr = computedPnlIdr,
                        pnlPercent = computedPnlPct,
                        isProfit = isWin,
                        isTrailingUsed = isTrailingUsed || targetRecord.isTrailingUsed,
                        trailingLockPrice = trailingLockPrice ?: targetRecord.trailingLockPrice
                    )
                    dao.updateRecord(closedRecord)
                } else {
                    // Jika tidak ada record open sebelumnya (misal histori transaksi langsung/eksternal), buat record langsung CLOSED
                    val fallbackUuid = tradeUuid ?: UUID.randomUUID().toString()
                    val estBuyPrice = if (customPnlPercent != null && customPnlPercent != 0.0) {
                        sellPrice / (1.0 + (customPnlPercent / 100.0))
                    } else sellPrice * 0.98

                    val estBuyTotal = estBuyPrice * sellQuantity
                    val estBuyFee = estBuyTotal * feeRate
                    val computedPnlIdr = customPnlIdr ?: (sellTotalIdr - sellFeeIdr - estBuyTotal - estBuyFee)
                    val computedPnlPct = customPnlPercent ?: if (estBuyTotal > 0) ((computedPnlIdr / estBuyTotal) * 100.0) else 0.0
                    val isWin = computedPnlIdr >= 0.0

                    val closedRecord = TradeHistoryRecordEntity(
                        tradeUuid = fallbackUuid,
                        symbol = normSymbol,
                        isRealTrade = isReal,
                        strategyMode = (strategyMode ?: "SCALPING").uppercase(),
                        signalTime = now - 900_000L,
                        signalPrice = estBuyPrice,
                        signalConfidence = 80,
                        signalCalculationSummary = "Perhitungan teknikal: Eksekusi otomatis sesuai trigger bursa",
                        signalReasons = "Eksekusi strategi $sellReason",
                        buyTime = now - 900_000L,
                        buyPrice = estBuyPrice,
                        buyQuantity = sellQuantity,
                        buyTotalIdr = estBuyTotal,
                        buyFeeIdr = estBuyFee,
                        buyOrderType = "LIMIT",
                        status = "CLOSED",
                        holdingDurationMs = 900_000L,
                        peakPriceDuringHold = max(estBuyPrice, sellPrice),
                        troughPriceDuringHold = min(estBuyPrice, sellPrice),
                        maxProfitPctDuringHold = max(0.0, computedPnlPct),
                        maxDrawdownPctDuringHold = min(0.0, computedPnlPct),
                        isTrailingUsed = isTrailingUsed,
                        trailingLockPrice = trailingLockPrice,
                        sellTime = now,
                        sellPrice = sellPrice,
                        sellQuantity = sellQuantity,
                        sellTotalIdr = sellTotalIdr,
                        sellFeeIdr = sellFeeIdr,
                        sellReason = sellReason,
                        pnlIdr = computedPnlIdr,
                        pnlPercent = computedPnlPct,
                        isProfit = isWin
                    )
                    dao.insertRecord(closedRecord)
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun deleteRecord(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteRecordById(id)
    }

    suspend fun clearAllRecords() = withContext(Dispatchers.IO) {
        dao.clearAllRecords()
    }

    /**
     * Membangun ringkasan perhitungan indikator teknikal saat sinyal dikeluarkan.
     */
    private fun buildCalculationSummary(snapshot: TradeSignalSnapshot?, mode: String): String {
        if (snapshot == null) {
            return "Perhitungan Mode: ${mode.uppercase()} (Kombinasi RSI, MACD Trend, & Order Book Imbalance)"
        }
        val parts = mutableListOf<String>()

        snapshot.rsi14?.let { parts.add("RSI(14)=${String.format(Locale.US, "%.1f", it)}") }
        snapshot.macdHist?.let {
            val type = if (it >= 0) "Golden Cross (+$it)" else "Death Cross ($it)"
            parts.add("MACD=$type")
        }
        if (snapshot.ema20 != null && snapshot.ema50 != null) {
            val relation = if (snapshot.ema20 > snapshot.ema50) "EMA20 > EMA50 (Uptrend)" else "EMA20 < EMA50 (Downtrend)"
            parts.add(relation)
        }
        snapshot.bidRatioPct?.let { parts.add("Bid Depth=${String.format(Locale.US, "%.1f%%", it)}") }
        snapshot.orderBookPressure?.let {
            parts.add(if (it > 0) "Pressure=+$it% (Buyer)" else "Pressure=$it% (Seller)")
        }
        snapshot.bbWidthPct?.let { parts.add("BB Width=${String.format(Locale.US, "%.2f%%", it)}") }

        return if (parts.isNotEmpty()) parts.joinToString(" • ") else "Mode ${mode.uppercase()} terverifikasi indikator multi-timeframe."
    }

    /**
     * Muat data sampel daur hidup trade jika database kosong, agar user dapat menguji fitur secara langsung.
     */
    suspend fun seedSampleTradeJourneysIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.getRecordCount() > 0) return@withContext

        val now = System.currentTimeMillis()
        val samples = listOf(
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-btc-01",
                symbol = "BTCIDR",
                isRealTrade = true,
                strategyMode = "SCALPING",
                signalTime = now - 3600_000L * 3,
                signalPrice = 1450000000.0,
                signalConfidence = 88,
                signalCalculationSummary = "RSI(14)=32.4 • MACD=Golden Cross (+0.018) • EMA20 > EMA50 (Uptrend) • Bid Depth=68.5% • Pressure=+24% (Buyer)",
                signalReasons = "Breakout resistance M15 + lonjakan volume 3.4x rata-rata + order book buyer tebal.",
                targetPrice1 = 1485000000.0,
                targetPrice2 = 1510000000.0,
                stopLossPrice = 1430000000.0,
                buyTime = now - 3600_000L * 3 + 12_000L,
                buyPrice = 1452000000.0,
                buyQuantity = 0.00344352,
                buyTotalIdr = 5000000.0,
                buyFeeIdr = 15000.0,
                buyOrderType = "LIMIT",
                status = "CLOSED",
                holdingDurationMs = 1120_000L, // ~18 menit 40 detik
                peakPriceDuringHold = 1492000000.0,
                troughPriceDuringHold = 1448000000.0,
                maxProfitPctDuringHold = 2.75,
                maxDrawdownPctDuringHold = -0.27,
                isTrailingUsed = false,
                sellTime = now - 3600_000L * 3 + 1132_000L,
                sellPrice = 1485000000.0,
                sellQuantity = 0.00344352,
                sellTotalIdr = 5113627.0,
                sellFeeIdr = 15340.0,
                sellReason = "HIT_TP1",
                pnlIdr = 83287.0,
                pnlPercent = 1.66,
                isProfit = true
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-eth-02",
                symbol = "ETHIDR",
                isRealTrade = false,
                strategyMode = "SCALPING",
                signalTime = now - 3600_000L * 6,
                signalPrice = 52000000.0,
                signalConfidence = 84,
                signalCalculationSummary = "RSI(14)=46.2 • MACD=Golden Cross (+0.042) • EMA20 > EMA50 (Uptrend) • Bid Depth=62.0% • Pressure=+18% (Buyer)",
                signalReasons = "Momentum expansion RSI crossing 50 + konfirmasi volume buy bursa.",
                targetPrice1 = 53500000.0,
                targetPrice2 = 54600000.0,
                stopLossPrice = 51200000.0,
                buyTime = now - 3600_000L * 6 + 8_000L,
                buyPrice = 52100000.0,
                buyQuantity = 0.09596929,
                buyTotalIdr = 5000000.0,
                buyFeeIdr = 15000.0,
                buyOrderType = "LIMIT",
                status = "CLOSED",
                holdingDurationMs = 2840_000L, // ~47 menit 20 detik
                peakPriceDuringHold = 54200000.0,
                troughPriceDuringHold = 51900000.0,
                maxProfitPctDuringHold = 4.03,
                maxDrawdownPctDuringHold = -0.38,
                isTrailingUsed = true,
                trailingLockPrice = 53800000.0,
                sellTime = now - 3600_000L * 6 + 2848_000L,
                sellPrice = 53800000.0,
                sellQuantity = 0.09596929,
                sellTotalIdr = 5163147.0,
                sellFeeIdr = 15489.0,
                sellReason = "TRAILING_STOP",
                pnlIdr = 132658.0,
                pnlPercent = 2.65,
                isProfit = true
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-sol-03",
                symbol = "SOLIDR",
                isRealTrade = false,
                strategyMode = "SECOND_WAVE",
                signalTime = now - 3600_000L * 12,
                signalPrice = 2850000.0,
                signalConfidence = 76,
                signalCalculationSummary = "RSI(14)=38.0 • EMA20=2830000 • Bid Depth=59.4% • Pressure=+12% (Buyer) • Pattern=Bullish Hammer",
                signalReasons = "Pantulan gelombang 2 (Fibonacci 0.618) pada zona support kuat.",
                targetPrice1 = 2960000.0,
                targetPrice2 = 3050000.0,
                stopLossPrice = 2780000.0,
                buyTime = now - 3600_000L * 12 + 15_000L,
                buyPrice = 2855000.0,
                buyQuantity = 1.05078809,
                buyTotalIdr = 3000000.0,
                buyFeeIdr = 9000.0,
                buyOrderType = "LIMIT",
                status = "CLOSED",
                holdingDurationMs = 5040_000L, // ~1 jam 24 menit
                peakPriceDuringHold = 3065000.0,
                troughPriceDuringHold = 2840000.0,
                maxProfitPctDuringHold = 7.35,
                maxDrawdownPctDuringHold = -0.52,
                isTrailingUsed = false,
                sellTime = now - 3600_000L * 12 + 5055_000L,
                sellPrice = 3050000.0,
                sellQuantity = 1.05078809,
                sellTotalIdr = 3204903.0,
                sellFeeIdr = 9614.0,
                sellReason = "HIT_TP2",
                pnlIdr = 186289.0,
                pnlPercent = 6.20,
                isProfit = true
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-pepe-04",
                symbol = "PEPEIDR",
                isRealTrade = false,
                strategyMode = "SCALPING",
                signalTime = now - 3600_000L * 16,
                signalPrice = 0.185,
                signalConfidence = 64,
                signalCalculationSummary = "RSI(14)=62.0 • BB Width=5.4% • Bid Depth=48.0% • Pressure=-4% (Seller)",
                signalReasons = "Breakout volatil awal namun likuiditas tipis.",
                targetPrice1 = 0.198,
                targetPrice2 = 0.208,
                stopLossPrice = 0.178,
                buyTime = now - 3600_000L * 16 + 5_000L,
                buyPrice = 0.186,
                buyQuantity = 10752688.0,
                buyTotalIdr = 2000000.0,
                buyFeeIdr = 6000.0,
                buyOrderType = "MARKET",
                status = "CLOSED",
                holdingDurationMs = 740_000L, // ~12 menit 20 detik
                peakPriceDuringHold = 0.189,
                troughPriceDuringHold = 0.177,
                maxProfitPctDuringHold = 1.61,
                maxDrawdownPctDuringHold = -4.83,
                isTrailingUsed = false,
                sellTime = now - 3600_000L * 16 + 745_000L,
                sellPrice = 0.178,
                sellQuantity = 10752688.0,
                sellTotalIdr = 1913978.0,
                sellFeeIdr = 5741.0,
                sellReason = "STOP_LOSS",
                pnlIdr = -97763.0,
                pnlPercent = -4.88,
                isProfit = false
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-near-05",
                symbol = "NEARIDR",
                isRealTrade = true,
                strategyMode = "SCALPING",
                signalTime = now - 1800_000L,
                signalPrice = 85000.0,
                signalConfidence = 90,
                signalCalculationSummary = "RSI(14)=34.8 • MACD=Golden Cross (+0.024) • EMA20 > EMA50 (Uptrend) • Bid Depth=71.2% • Pressure=+32% (Buyer)",
                signalReasons = "Triple confluence: Order book buyer 71% + RSI crossing up 35 + Volume 4x rata-rata.",
                targetPrice1 = 88500.0,
                targetPrice2 = 91000.0,
                stopLossPrice = 83000.0,
                buyTime = now - 1800_000L + 10_000L,
                buyPrice = 85200.0,
                buyQuantity = 35.2112676,
                buyTotalIdr = 3000000.0,
                buyFeeIdr = 9000.0,
                buyOrderType = "LIMIT",
                status = "HOLDING",
                holdingDurationMs = 1790_000L, // ~29 menit
                peakPriceDuringHold = 87400.0,
                troughPriceDuringHold = 84900.0,
                maxProfitPctDuringHold = 2.58,
                maxDrawdownPctDuringHold = -0.35,
                isTrailingUsed = false
            )
        )
        dao.insertRecords(samples)
    }
}
