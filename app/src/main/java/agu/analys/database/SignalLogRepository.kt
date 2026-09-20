package agu.analys.database

import agu.analys.model.ConfidenceTierStats
import agu.analys.model.SignalReliabilitySummary
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SignalLogRepository(
    private val dao: SignalLogDao,
    private val scope: CoroutineScope
) {
    private val lastLoggedMap = ConcurrentHashMap<String, Long>()
    private val tickThrottleMap = ConcurrentHashMap<String, Long>()

    init {
        // Automatically consolidate any duplicate tracking logs from past runs
        scope.launch(Dispatchers.IO) {
            try {
                consolidateDuplicateTrackingLogs()
            } catch (_: Exception) {}
        }
    }

    val allLogsFlow: Flow<List<SignalLogEntity>> = dao.getAllLogsFlow()

    val reliabilitySummaryFlow: Flow<SignalReliabilitySummary> = dao.getAllLogsFlow().map { logs ->
        calculateReliabilitySummary(logs)
    }

    fun getLogsBySymbolFlow(symbol: String): Flow<List<SignalLogEntity>> =
        dao.getLogsBySymbolFlow(symbol)

    /**
     * Record or update a signal for a coin.
     * If the coin is already being tracked in the signal log, updates its parameters (confidence, reasoning, targets, etc.)
     * instead of creating a duplicate log entry.
     */
    fun recordSignal(
        symbol: String,
        action: String,
        strategyMode: String,
        confidence: Int,
        sentiment: String,
        entryPrice: Double,
        targetPrice1: Double = 0.0,
        targetPrice2: Double = 0.0,
        stopLoss: Double = 0.0,
        reasoning: String = "",
        scalpingStage: String = ""
    ) {
        if (entryPrice <= 0.0 || symbol.isBlank() || action.equals("HOLD", ignoreCase = true)) return

        val normSymbol = symbol.uppercase().replace("_", "").replace("/", "").trim()
        val normAction = action.uppercase().trim()
        val now = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Check if there are already active TRACKING logs for this coin
                val activeTrackingLogs = dao.getActiveTrackingLogsForSymbol(normSymbol)

                if (activeTrackingLogs.isNotEmpty()) {
                    // Coin already exists in active signal tracking! UPDATE existing log, do NOT insert new one
                    val primaryLog = activeTrackingLogs.first()

                    // If there are duplicate tracking entries for this symbol (from past bugs), clean them up
                    if (activeTrackingLogs.size > 1) {
                        val extraIds = activeTrackingLogs.drop(1).map { it.id }
                        dao.deleteLogsByIds(extraIds)
                    }

                    val isBuy = (if (normAction.isNotBlank()) normAction else primaryLog.action).equals("BUY", ignoreCase = true)
                    val currentTrackPrice = entryPrice
                    val rawPnlPct = if (isBuy) {
                        ((currentTrackPrice - primaryLog.entryPrice) / primaryLog.entryPrice) * 100.0
                    } else {
                        ((primaryLog.entryPrice - currentTrackPrice) / primaryLog.entryPrice) * 100.0
                    }

                    val updatedConfidence = if (confidence > 0) max(confidence, primaryLog.confidence) else primaryLog.confidence
                    val updatedReasoning = if (reasoning.isNotBlank()) reasoning else primaryLog.reasoning
                    val updatedSentiment = if (sentiment.isNotBlank()) sentiment else primaryLog.sentiment
                    val updatedStrategy = if (strategyMode.isNotBlank()) strategyMode else primaryLog.strategyMode
                    val updatedStage = if (scalpingStage.isNotBlank()) scalpingStage else primaryLog.scalpingStage

                    val updatedTp1 = if (targetPrice1 > 0.0) targetPrice1 else primaryLog.targetPrice1
                    val updatedTp2 = if (targetPrice2 > 0.0) targetPrice2 else primaryLog.targetPrice2
                    val updatedSl = if (stopLoss > 0.0) stopLoss else primaryLog.stopLoss

                    val newPeak = if (primaryLog.peakPrice <= 0.0) currentTrackPrice else max(primaryLog.peakPrice, currentTrackPrice)
                    val newTrough = if (primaryLog.troughPrice <= 0.0) currentTrackPrice else min(primaryLog.troughPrice, currentTrackPrice)
                    val newMaxProfit = max(primaryLog.maxProfitPct, max(0.0, rawPnlPct))
                    val newMaxDrawdown = min(primaryLog.maxDrawdownPct, min(0.0, rawPnlPct))

                    val updatedLog = primaryLog.copy(
                        action = if (normAction.isNotBlank()) normAction else primaryLog.action,
                        strategyMode = updatedStrategy,
                        confidence = updatedConfidence,
                        sentiment = updatedSentiment,
                        targetPrice1 = updatedTp1,
                        targetPrice2 = updatedTp2,
                        stopLoss = updatedSl,
                        reasoning = updatedReasoning,
                        scalpingStage = updatedStage,
                        peakPrice = newPeak,
                        troughPrice = newTrough,
                        maxProfitPct = newMaxProfit,
                        maxDrawdownPct = newMaxDrawdown
                    )

                    dao.updateLog(updatedLog)
                    return@launch
                }

                // 2. Check if coin already has a recent log in DB (e.g. resolved recently) to avoid immediate re-spam
                val latest = dao.getLatestLogForSymbol(normSymbol)
                if (latest != null) {
                    // If it was resolved within 3 minutes and price hasn't meaningfully moved (<0.8%), skip creating duplicate
                    val lastTimestamp = latest.resolvedAt ?: latest.firedAt
                    if (now - lastTimestamp < 180_000L) {
                        val diff = abs(latest.entryPrice - entryPrice) / latest.entryPrice
                        if (diff < 0.008) {
                            return@launch
                        }
                    }
                }

                // 3. Brand new coin entry for tracking
                val entity = SignalLogEntity(
                    symbol = normSymbol,
                    action = normAction,
                    strategyMode = strategyMode,
                    confidence = confidence,
                    sentiment = sentiment,
                    entryPrice = entryPrice,
                    targetPrice1 = targetPrice1,
                    targetPrice2 = targetPrice2,
                    stopLoss = stopLoss,
                    firedAt = now,
                    reasoning = reasoning,
                    scalpingStage = scalpingStage,
                    outcomeStatus = "TRACKING",
                    peakPrice = entryPrice,
                    troughPrice = entryPrice,
                    maxProfitPct = 0.0,
                    maxDrawdownPct = 0.0
                )
                dao.insertLog(entity)
            } catch (_: Exception) {}
        }
    }

    /**
     * Consolidate duplicate tracking logs so that each symbol only has 1 active tracking log
     */
    suspend fun consolidateDuplicateTrackingLogs() = withContext(Dispatchers.IO) {
        val allTracking = dao.getActiveTrackingLogs()
        val grouped = allTracking.groupBy { it.symbol }
        for ((_, logs) in grouped) {
            if (logs.size > 1) {
                // Keep the primary (latest by firedAt or with best tracked stats)
                val primary = logs.maxByOrNull { it.firedAt } ?: logs.first()
                val duplicates = logs.filter { it.id != primary.id }
                if (duplicates.isNotEmpty()) {
                    dao.deleteLogsByIds(duplicates.map { it.id })
                }
            }
        }
    }

    /**
     * Update active tracking signals based on live market tick
     */
    fun processPriceTick(symbol: String, currentPrice: Double) {
        if (currentPrice <= 0.0 || symbol.isBlank()) return
        val normSymbol = symbol.uppercase().replace("_", "").replace("/", "").trim()
        val now = System.currentTimeMillis()

        // Throttle updates per symbol to max 1 update per 1000ms
        val lastUpdate = tickThrottleMap[normSymbol] ?: 0L
        if (now - lastUpdate < 1000L) return
        tickThrottleMap[normSymbol] = now

        scope.launch(Dispatchers.IO) {
            try {
                val trackingLogs = dao.getActiveTrackingLogsForSymbol(normSymbol)
                if (trackingLogs.isEmpty()) return@launch

                for (log in trackingLogs) {
                    updateTrackingLogWithPrice(log, currentPrice, now)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Batch update all active tracking signals from whole market tickers
     */
    fun processBatchPriceTicks(priceMap: Map<String, Double>) {
        if (priceMap.isEmpty()) return
        val now = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            try {
                val trackingLogs = dao.getActiveTrackingLogs()
                if (trackingLogs.isEmpty()) return@launch

                for (log in trackingLogs) {
                    val currentPrice = priceMap[log.symbol]
                        ?: priceMap[log.symbol.lowercase()]
                        ?: priceMap["${log.symbol.removeSuffix("IDR").removeSuffix("idr")}idr"]
                        ?: priceMap["${log.symbol.removeSuffix("IDR").removeSuffix("idr")}IDR"]
                        ?: continue

                    if (currentPrice <= 0.0) continue
                    updateTrackingLogWithPrice(log, currentPrice, now)
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun updateTrackingLogWithPrice(log: SignalLogEntity, currentPrice: Double, now: Long) {
        val isBuy = log.action.equals("BUY", ignoreCase = true)
        val rawPnlPct = if (isBuy) {
            ((currentPrice - log.entryPrice) / log.entryPrice) * 100.0
        } else {
            ((log.entryPrice - currentPrice) / log.entryPrice) * 100.0
        }

        val newPeak = if (log.peakPrice <= 0.0) currentPrice else max(log.peakPrice, currentPrice)
        val newTrough = if (log.troughPrice <= 0.0) currentPrice else min(log.troughPrice, currentPrice)
        val newMaxProfit = max(log.maxProfitPct, max(0.0, rawPnlPct))
        val newMaxDrawdown = min(log.maxDrawdownPct, min(0.0, rawPnlPct))

        // Check for target outcomes
        var status = log.outcomeStatus
        var exitPrice: Double? = null
        var realizedPnl: Double? = null
        var resolvedAt: Long? = null
        var note: String? = null

        if (log.targetPrice2 > 0.0 && ((isBuy && currentPrice >= log.targetPrice2) || (!isBuy && currentPrice <= log.targetPrice2))) {
            status = "HIT_TP2"
            exitPrice = currentPrice
            realizedPnl = rawPnlPct
            resolvedAt = now
            note = "Target TP2 tercapai pada ${PriceFormatter.formatPrice(currentPrice)} (+${String.format(java.util.Locale.US, "%.2f", rawPnlPct)}%)"
        } else if (log.targetPrice1 > 0.0 && ((isBuy && currentPrice >= log.targetPrice1) || (!isBuy && currentPrice <= log.targetPrice1))) {
            status = "HIT_TP1"
            exitPrice = currentPrice
            realizedPnl = rawPnlPct
            resolvedAt = now
            note = "Target TP1 tercapai pada ${PriceFormatter.formatPrice(currentPrice)} (+${String.format(java.util.Locale.US, "%.2f", rawPnlPct)}%)"
        } else if (log.stopLoss > 0.0 && ((isBuy && currentPrice <= log.stopLoss) || (!isBuy && currentPrice >= log.stopLoss))) {
            status = "HIT_SL"
            exitPrice = currentPrice
            realizedPnl = rawPnlPct
            resolvedAt = now
            note = "Stop Loss tersentuh pada ${PriceFormatter.formatPrice(currentPrice)} (${String.format(java.util.Locale.US, "%.2f", rawPnlPct)}%)"
        } else if (now - log.firedAt > 86_400_000L * 3) {
            // Expire after 3 days
            status = "EXPIRED"
            exitPrice = currentPrice
            realizedPnl = rawPnlPct
            resolvedAt = now
            note = "Sinyal kedaluwarsa setelah 3 hari. Hasil akhir: ${String.format(java.util.Locale.US, "%.2f", rawPnlPct)}%"
        }

        val updatedLog = log.copy(
            peakPrice = newPeak,
            troughPrice = newTrough,
            maxProfitPct = newMaxProfit,
            maxDrawdownPct = newMaxDrawdown,
            outcomeStatus = status,
            exitPrice = exitPrice ?: log.exitPrice,
            realizedPnlPct = realizedPnl ?: log.realizedPnlPct,
            resolvedAt = resolvedAt ?: log.resolvedAt,
            resolutionNote = note ?: log.resolutionNote
        )

        dao.updateLog(updatedLog)
    }

    /**
     * Resolve a signal log manually (e.g. user manually took profit or cut loss)
     */
    suspend fun resolveLogManually(
        id: Long,
        isWin: Boolean,
        customExitPrice: Double? = null,
        customPnlPct: Double? = null,
        note: String = "Diselesaikan manual oleh pengguna"
    ) = withContext(Dispatchers.IO) {
        val log = dao.getLogById(id) ?: return@withContext
        val status = if (isWin) "MANUAL_WIN" else "MANUAL_LOSS"
        val now = System.currentTimeMillis()
        val updated = log.copy(
            outcomeStatus = status,
            exitPrice = customExitPrice ?: log.peakPrice.takeIf { it > 0 } ?: log.entryPrice,
            realizedPnlPct = customPnlPct ?: (if (isWin) max(1.5, log.maxProfitPct) else min(-1.5, log.maxDrawdownPct)),
            resolvedAt = now,
            resolutionNote = note
        )
        dao.updateLog(updated)
    }

    suspend fun deleteLog(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteLogById(id)
    }

    suspend fun clearAllLogs() = withContext(Dispatchers.IO) {
        dao.clearAllLogs()
    }

    /**
     * Pre-populate seed logs if database is clean so user has immediate rich data to test reliability evaluation
     */
    suspend fun seedSampleLogsIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.getLogCount() > 0) return@withContext

        val now = System.currentTimeMillis()
        val sampleLogs = listOf(
            SignalLogEntity(
                symbol = "BTCIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 88,
                sentiment = "BULLISH_REVERSAL",
                entryPrice = 1450000000.0,
                targetPrice1 = 1485000000.0,
                targetPrice2 = 1510000000.0,
                stopLoss = 1430000000.0,
                firedAt = now - 3600_000L * 4,
                reasoning = "Breakout resistance M15 + konfirmasi volume besar dan MACD Golden Cross.",
                scalpingStage = "STRONG_ENTRY",
                outcomeStatus = "HIT_TP2",
                maxProfitPct = 4.14,
                maxDrawdownPct = -0.42,
                exitPrice = 1510000000.0,
                realizedPnlPct = 4.14,
                peakPrice = 1515000000.0,
                troughPrice = 1445000000.0,
                resolvedAt = now - 3600_000L * 2,
                resolutionNote = "Target TP2 tercapai pada Rp 1.510.000.000 (+4.14%)"
            ),
            SignalLogEntity(
                symbol = "ETHIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 82,
                sentiment = "STRONG_BULLISH_CONTINUATION",
                entryPrice = 52000000.0,
                targetPrice1 = 53500000.0,
                targetPrice2 = 54600000.0,
                stopLoss = 51200000.0,
                firedAt = now - 3600_000L * 8,
                reasoning = "Volume Spike 3x rata-rata + RSI 58 momentum expansion.",
                scalpingStage = "ENTRY",
                outcomeStatus = "HIT_TP1",
                maxProfitPct = 2.88,
                maxDrawdownPct = -0.65,
                exitPrice = 53500000.0,
                realizedPnlPct = 2.88,
                peakPrice = 53800000.0,
                troughPrice = 51700000.0,
                resolvedAt = now - 3600_000L * 5,
                resolutionNote = "Target TP1 tercapai pada Rp 53.500.000 (+2.88%)"
            ),
            SignalLogEntity(
                symbol = "SOLIDR",
                action = "BUY",
                strategyMode = "SECOND_WAVE",
                confidence = 74,
                sentiment = "BULLISH_REVERSAL",
                entryPrice = 2850000.0,
                targetPrice1 = 2960000.0,
                targetPrice2 = 3050000.0,
                stopLoss = 2780000.0,
                firedAt = now - 3600_000L * 12,
                reasoning = "Pantulan gelombang 2 (Fibonacci 0.618) dengan candle hammer kuat.",
                scalpingStage = "ENTRY",
                outcomeStatus = "HIT_TP1",
                maxProfitPct = 3.86,
                maxDrawdownPct = -1.15,
                exitPrice = 2960000.0,
                realizedPnlPct = 3.86,
                peakPrice = 2975000.0,
                troughPrice = 2820000.0,
                resolvedAt = now - 3600_000L * 9,
                resolutionNote = "Target TP1 tercapai pada Rp 2.960.000 (+3.86%)"
            ),
            SignalLogEntity(
                symbol = "PEPEIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 62,
                sentiment = "ACCUMULATION_SQUEEZE",
                entryPrice = 0.185,
                targetPrice1 = 0.198,
                targetPrice2 = 0.208,
                stopLoss = 0.178,
                firedAt = now - 3600_000L * 16,
                reasoning = "Breakout volatil squeeze, tapi volume relatif moderat.",
                scalpingStage = "EARLY_ENTRY",
                outcomeStatus = "HIT_SL",
                maxProfitPct = 1.62,
                maxDrawdownPct = -3.78,
                exitPrice = 0.178,
                realizedPnlPct = -3.78,
                peakPrice = 0.188,
                troughPrice = 0.176,
                resolvedAt = now - 3600_000L * 14,
                resolutionNote = "Stop Loss tersentuh pada Rp 0.178 (-3.78%)"
            ),
            SignalLogEntity(
                symbol = "DOGEIDR",
                action = "BUY",
                strategyMode = "SWING",
                confidence = 85,
                sentiment = "STRONG_BULLISH_CONTINUATION",
                entryPrice = 3250.0,
                targetPrice1 = 3450.0,
                targetPrice2 = 3600.0,
                stopLoss = 3120.0,
                firedAt = now - 3600_000L * 24,
                reasoning = "EMA20 crossing EMA50 pada grafik 4H didukung volume buy dominan.",
                scalpingStage = "STRONG_ENTRY",
                outcomeStatus = "HIT_TP2",
                maxProfitPct = 10.77,
                maxDrawdownPct = -0.92,
                exitPrice = 3600.0,
                realizedPnlPct = 10.77,
                peakPrice = 3620.0,
                troughPrice = 3220.0,
                resolvedAt = now - 3600_000L * 18,
                resolutionNote = "Target TP2 tercapai pada Rp 3.600 (+10.77%)"
            ),
            SignalLogEntity(
                symbol = "XRPIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 58,
                sentiment = "NEUTRAL_CONSOLIDATION",
                entryPrice = 38500.0,
                targetPrice1 = 39800.0,
                targetPrice2 = 41000.0,
                stopLoss = 37600.0,
                firedAt = now - 3600_000L * 28,
                reasoning = "Sinyal spekulatif pembalikan awal support.",
                scalpingStage = "WATCH",
                outcomeStatus = "HIT_SL",
                maxProfitPct = 0.85,
                maxDrawdownPct = -2.34,
                exitPrice = 37600.0,
                realizedPnlPct = -2.34,
                peakPrice = 38800.0,
                troughPrice = 37500.0,
                resolvedAt = now - 3600_000L * 25,
                resolutionNote = "Stop Loss tersentuh pada Rp 37.600 (-2.34%)"
            ),
            SignalLogEntity(
                symbol = "NEARIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 90,
                sentiment = "BULLISH_REVERSAL",
                entryPrice = 85000.0,
                targetPrice1 = 88500.0,
                targetPrice2 = 91000.0,
                stopLoss = 83000.0,
                firedAt = now - 3600_000L * 2,
                reasoning = "Triple confluence: Orderbook buy depth 68%, RSI 48 crossing up, Vol 4.5x.",
                scalpingStage = "STRONG_ENTRY",
                outcomeStatus = "TRACKING",
                maxProfitPct = 2.35,
                maxDrawdownPct = -0.35,
                peakPrice = 87000.0,
                troughPrice = 84700.0
            )
        )
        dao.insertLogs(sampleLogs)
    }

    companion object {
        fun calculateReliabilitySummary(logs: List<SignalLogEntity>): SignalReliabilitySummary {
            if (logs.isEmpty()) return SignalReliabilitySummary()

            val total = logs.size
            val tracking = logs.count { it.outcomeStatus == "TRACKING" }
            val resolvedLogs = logs.filter { it.outcomeStatus != "TRACKING" }

            val wins = resolvedLogs.filter {
                it.outcomeStatus in listOf("HIT_TP1", "HIT_TP2", "MANUAL_WIN") || (it.realizedPnlPct ?: 0.0) > 0
            }
            val losses = resolvedLogs.filter {
                it.outcomeStatus in listOf("HIT_SL", "MANUAL_LOSS") || (it.realizedPnlPct ?: 0.0) < 0
            }

            val winCount = wins.size
            val lossCount = losses.size
            val resolvedCount = resolvedLogs.size

            val winRatePct = if (resolvedCount > 0) (winCount.toDouble() / resolvedCount) * 100.0 else 0.0

            val avgProfit = if (wins.isNotEmpty()) {
                wins.mapNotNull { it.realizedPnlPct ?: it.maxProfitPct.takeIf { p -> p > 0 } }.average().takeIf { it.isFinite() } ?: 0.0
            } else 0.0

            val avgLoss = if (losses.isNotEmpty()) {
                losses.mapNotNull { it.realizedPnlPct ?: it.maxDrawdownPct.takeIf { d -> d < 0 } }.average().takeIf { it.isFinite() } ?: 0.0
            } else 0.0

            val totalWinGain = wins.sumOf { (it.realizedPnlPct ?: it.maxProfitPct).coerceAtLeast(0.0) }
            val totalLossValue = losses.sumOf { abs((it.realizedPnlPct ?: it.maxDrawdownPct).coerceAtMost(0.0)) }
            val profitFactor = if (totalLossValue > 0) totalWinGain / totalLossValue else if (totalWinGain > 0) 9.99 else 0.0

            val maxWin = logs.maxOfOrNull { it.realizedPnlPct ?: it.maxProfitPct }?.takeIf { it > 0 } ?: 0.0
            val maxLoss = logs.minOfOrNull { it.realizedPnlPct ?: it.maxDrawdownPct }?.takeIf { it < 0 } ?: 0.0

            // Tier breakdowns
            val highTier = buildTierStats("Tinggi (≥80%)", 80, 100, logs)
            val medTier = buildTierStats("Sedang (60-79%)", 60, 79, logs)
            val lowTier = buildTierStats("Awal (<60%)", 0, 59, logs)

            // Best performing symbol & strategy
            val symbolGroups = resolvedLogs.groupBy { it.symbol }
            val bestSymbol = symbolGroups.maxByOrNull { group ->
                val w = group.value.count { it.outcomeStatus in listOf("HIT_TP1", "HIT_TP2", "MANUAL_WIN") }
                w.toDouble() / group.value.size
            }?.key ?: "-"

            val strategyGroups = resolvedLogs.groupBy { it.strategyMode }
            val bestStrategy = strategyGroups.maxByOrNull { group ->
                val w = group.value.count { it.outcomeStatus in listOf("HIT_TP1", "HIT_TP2", "MANUAL_WIN") }
                w.toDouble() / group.value.size
            }?.key ?: "-"

            return SignalReliabilitySummary(
                totalLogs = total,
                completedLogs = resolvedCount,
                winCount = winCount,
                lossCount = lossCount,
                trackingCount = tracking,
                overallWinRatePct = winRatePct,
                avgProfitPct = avgProfit,
                avgLossPct = avgLoss,
                profitFactor = profitFactor,
                maxSingleWinPct = maxWin,
                maxSingleLossPct = maxLoss,
                highConfidenceStats = highTier,
                mediumConfidenceStats = medTier,
                lowConfidenceStats = lowTier,
                bestPerformingSymbol = bestSymbol,
                bestStrategyMode = bestStrategy
            )
        }

        private fun buildTierStats(
            label: String,
            minConf: Int,
            maxConf: Int,
            allLogs: List<SignalLogEntity>
        ): ConfidenceTierStats {
            val tierLogs = allLogs.filter { it.confidence in minConf..maxConf }
            val total = tierLogs.size
            val tracking = tierLogs.count { it.outcomeStatus == "TRACKING" }
            val resolved = tierLogs.filter { it.outcomeStatus != "TRACKING" }
            val wins = resolved.count { it.outcomeStatus in listOf("HIT_TP1", "HIT_TP2", "MANUAL_WIN") || (it.realizedPnlPct ?: 0.0) > 0 }
            val losses = resolved.count { it.outcomeStatus in listOf("HIT_SL", "MANUAL_LOSS") || (it.realizedPnlPct ?: 0.0) < 0 }
            val winRate = if (resolved.isNotEmpty()) (wins.toDouble() / resolved.size) * 100.0 else 0.0
            val avgReturn = if (resolved.isNotEmpty()) {
                resolved.mapNotNull { it.realizedPnlPct }.average().takeIf { it.isFinite() } ?: 0.0
            } else 0.0

            return ConfidenceTierStats(
                tierLabel = label,
                minConfidence = minConf,
                maxConfidence = maxConf,
                totalSignals = total,
                winCount = wins,
                lossCount = losses,
                trackingCount = tracking,
                winRatePct = winRate,
                avgReturnPct = avgReturn
            )
        }
    }
}
