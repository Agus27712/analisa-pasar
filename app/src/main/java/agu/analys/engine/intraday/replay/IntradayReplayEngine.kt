package agu.analys.engine.intraday.replay

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.intraday.IntradayEvaluator
import agu.analys.engine.regime.MacroAnomalyDetector
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction

enum class IntradayTradeOutcome {
    VALID_ENTRY_TP,
    VALID_ENTRY_CLOSE_MALAM,
    FALSE_SIGNAL_SL,
    FALSE_SIGNAL_CLOSE_MALAM,
    MISSED_OPPORTUNITY,
    AVOIDED_LOSS,
    UNRESOLVED,
    AMBIGUOUS
}

data class IntradayTradeRecord(
    val entryIndex: Int,
    val entryTime: Long,
    val entryPrice: Double,
    val targetPrice1: Double,
    val targetPrice2: Double,
    val stopLoss: Double,
    val exitIndex: Int,
    val exitTime: Long,
    val exitPrice: Double,
    val exitReason: String,
    val grossPnlPct: Double,
    val netPnlPct: Double,
    val barsHeld: Int,
    val outcome: IntradayTradeOutcome
)

data class IntradayReplayFrame(
    val index: Int,
    val candle: CandleBar,
    val timestamp: Long,
    val phase: IntradayEvaluator.IntradayPhase,
    val action: SignalAction,
    val confidence: Int,
    val setupScore: Int,
    val isQualified: Boolean,
    val isMomentumCandidate: Boolean,
    val rejectionReason: String? = null,
    val outcome: IntradayTradeOutcome? = null
)

data class IntradayBottleneckReport(
    val totalMomentumCandidates: Int,
    val step1Rejections: Int,
    val step2Rejections: Int,
    val step3Rejections: Int,
    val step4Rejections: Int,
    val sessionGateRejections: Int,
    val flashDumpTraumaBlocks: Int,
    val pumpAndDumpTrapBlocks: Int,
    val missedAfterStep1: Int,
    val missedAfterStep2: Int,
    val missedAfterStep3: Int,
    val missedAfterStep4: Int,
    val missedAfterSessionGate: Int,
    val primaryBottleneckStep: String,
    val primaryBottleneckDescription: String
)

data class IntradayReplayReport(
    val symbol: String,
    val totalEvaluations: Int,
    val totalSignalsTriggered: Int,
    val validEntries: Int,
    val falseSignals: Int,
    val missedOpportunities: Int,
    val avoidedLosses: Int,
    val unresolvedOutcomes: Int,
    val ambiguousOutcomes: Int,
    val totalNetPnlPct: Double,
    val avgNetPnlPct: Double,
    val winRatePct: Double,
    val profitFactor: Double,
    val maxDrawdownPct: Double,
    val bottleneck: IntradayBottleneckReport,
    val trades: List<IntradayTradeRecord>,
    val phaseDistribution: Map<IntradayEvaluator.IntradayPhase, Int>,
    val frames: List<IntradayReplayFrame>
) {
    val totalResolvedTrades: Int
        get() = validEntries + falseSignals

    val captureRatePct: Double
        get() {
            val viable = validEntries + missedOpportunities
            return if (viable > 0) validEntries.toDouble() / viable * 100.0 else 0.0
        }
}

object IntradayReplayEngine {

    fun replay(
        symbol: String = "BTCIDR",
        candles: List<CandleBar>,
        dailyCandles: List<CandleBar> = emptyList(),
        forwardLookaheadBars: Int = 24,
        enforceSessionClose: Boolean = true,
        feeConfig: TradingFeeConfig = TradingFeeConfig()
    ): IntradayReplayReport {
        val sortedCandles = candles.sortedBy { it.timestamp }
        val sortedDaily = dailyCandles.sortedBy { it.timestamp }

        if (sortedCandles.size < 25) {
            return emptyReport(symbol)
        }

        val frames = mutableListOf<IntradayReplayFrame>()
        val trades = mutableListOf<IntradayTradeRecord>()
        val phaseCounts = mutableMapOf<IntradayEvaluator.IntradayPhase, Int>()

        var step1Rejections = 0
        var step2Rejections = 0
        var step3Rejections = 0
        var step4Rejections = 0
        var sessionGateRejections = 0
        var flashDumpTraumaBlocks = 0
        var pumpAndDumpTrapBlocks = 0

        var missedAfterStep1 = 0
        var missedAfterStep2 = 0
        var missedAfterStep3 = 0
        var missedAfterStep4 = 0
        var missedAfterSessionGate = 0

        var validEntries = 0
        var falseSignals = 0
        var missedOpportunities = 0
        var avoidedLosses = 0
        var unresolvedOutcomes = 0
        var ambiguousOutcomes = 0

        var totalMomentumCandidates = 0

        val startIndex = 20
        val endIndex = sortedCandles.size - 1

        var currentHoldingUntilIndex = -1

        for (i in startIndex..endIndex) {
            val currentCandle = sortedCandles[i]
            val evalTime = currentCandle.timestamp
            val window = sortedCandles.subList(0, i + 1)

            val phase = IntradayEvaluator.getCurrentIntradayPhase(evalTime)
            phaseCounts[phase] = (phaseCounts[phase] ?: 0) + 1

            val dailySlice = sortedDaily.filter { it.timestamp <= evalTime }
            val anomalyResult = if (dailySlice.size >= 14) {
                MacroAnomalyDetector.evaluate(dailySlice, currentCandle.close)
            } else null

            val evalResult = IntradayEvaluator.evaluate(
                globalContext = agu.analys.engine.global.GlobalMarketContext(),
                price = currentCandle.close,
                history = window,
                fees = feeConfig,
                macroAnomalyResult = anomalyResult,
                evaluationTimestamp = evalTime
            )

            val signal = evalResult.signal
            val mtf = signal.mtf

            // Momentum candidate definition (for counterfactual bottleneck analysis)
            val lookback = maxOf(0, i - 14)
            val priorSlice = sortedCandles.subList(lookback, i)
            val priorAvgVol = if (priorSlice.isNotEmpty()) priorSlice.map { it.volume }.average() else currentCandle.volume
            val isVolumeSurge = currentCandle.volume >= priorAvgVol * 1.25
            val isGreenBreakout = currentCandle.close > currentCandle.open && (currentCandle.close >= priorSlice.maxOfOrNull { it.high } ?: currentCandle.close)
            val isMomentumCandidate = isGreenBreakout || (isVolumeSurge && currentCandle.close > currentCandle.open)

            // Calculate hypothetical TP/SL for counterfactual check if candidate rejected
            val effTp = signal.targetPrice1.takeIf { it > currentCandle.close } ?: (currentCandle.close * 1.03)
            val effSl = signal.stopLoss.takeIf { it > 0.0 && it < currentCandle.close } ?: (currentCandle.close * 0.98)

            // Forward simulation for this candidate
            val maxLookahead = minOf(sortedCandles.size - 1, i + forwardLookaheadBars)
            var futureRalliedWithoutSl = false
            var futureHitSl = false

            if (i < maxLookahead) {
                for (f in (i + 1)..maxLookahead) {
                    val fb = sortedCandles[f]
                    if (fb.high >= effTp) {
                        futureRalliedWithoutSl = true
                        break
                    }
                    if (fb.low <= effSl) {
                        futureHitSl = true
                        break
                    }
                }
            }

            var rejectionReason: String? = null
            if (signal.action != SignalAction.BUY) {
                rejectionReason = when {
                    signal.reasoning.any { it.contains("Fake Pump Trap", true) || it.contains("Upper Wick", true) } -> {
                        pumpAndDumpTrapBlocks++
                        "PUMP_DUMP_TRAP"
                    }
                    signal.reasoning.any { it.contains("Anti Flash Dump", true) || it.contains("Pernah flash dump", true) } -> {
                        flashDumpTraumaBlocks++
                        "ANTI_FLASH_DUMP"
                    }
                    !mtf.biasOk -> "STEP_1_TREND_DANGER"
                    !mtf.setupOk -> "STEP_2_SUPPORT_OR_HIGH_EXTENSION"
                    !mtf.triggerOk -> "STEP_3_RSI_OR_MACD"
                    !mtf.entryPriceOk -> "STEP_4_RR_OR_SCORE"
                    !phase.isOpenWindow -> "SESSION_GATE_${phase.name}"
                    else -> "NOT_QUALIFIED"
                }
            }

            if (isMomentumCandidate) {
                totalMomentumCandidates++
                when {
                    !mtf.biasOk -> {
                        step1Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep1++
                    }
                    !mtf.setupOk -> {
                        step2Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep2++
                    }
                    !mtf.triggerOk -> {
                        step3Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep3++
                    }
                    !mtf.entryPriceOk -> {
                        step4Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep4++
                    }
                    !phase.isOpenWindow -> {
                        sessionGateRejections++
                        if (futureRalliedWithoutSl) missedAfterSessionGate++
                    }
                }
            }

            var tradeOutcome: IntradayTradeOutcome? = null

            if (signal.action == SignalAction.BUY && i >= currentHoldingUntilIndex) {
                // Execute BUY simulation
                var exitIndex = i
                var exitPrice = currentCandle.close
                var exitReason = "UNRESOLVED"
                var hitTp = false
                var hitSl = false
                var closeMalamExit = false

                val tradeTp1 = signal.targetPrice1
                val tradeTp2 = signal.targetPrice2
                val tradeSl = signal.stopLoss

                for (f in (i + 1)..maxLookahead) {
                    val fb = sortedCandles[f]
                    val fbPhase = IntradayEvaluator.getCurrentIntradayPhase(fb.timestamp)

                    val barHitTp = fb.high >= tradeTp1
                    val barHitSl = fb.low <= tradeSl

                    if (barHitTp && barHitSl) {
                        ambiguousOutcomes++
                        tradeOutcome = IntradayTradeOutcome.AMBIGUOUS
                        exitIndex = f
                        exitPrice = tradeSl // Conservative: assume SL hit first
                        exitReason = "AMBIGUOUS_SAME_BAR"
                        break
                    } else if (barHitTp) {
                        hitTp = true
                        exitIndex = f
                        exitPrice = if (fb.high >= tradeTp2) tradeTp2 else tradeTp1
                        exitReason = if (fb.high >= tradeTp2) "TP2_HIT" else "TP1_HIT"
                        tradeOutcome = IntradayTradeOutcome.VALID_ENTRY_TP
                        validEntries++
                        break
                    } else if (barHitSl) {
                        hitSl = true
                        exitIndex = f
                        exitPrice = tradeSl
                        exitReason = "STOP_LOSS_HIT"
                        tradeOutcome = IntradayTradeOutcome.FALSE_SIGNAL_SL
                        falseSignals++
                        break
                    } else if (enforceSessionClose && fbPhase == IntradayEvaluator.IntradayPhase.CLOSE_MALAM) {
                        closeMalamExit = true
                        exitIndex = f
                        exitPrice = fb.close
                        exitReason = "CLOSE_MALAM_SESSION_EXIT"
                        val pnl = (exitPrice - currentCandle.close) / currentCandle.close
                        if (pnl >= 0.0) {
                            validEntries++
                            tradeOutcome = IntradayTradeOutcome.VALID_ENTRY_CLOSE_MALAM
                        } else {
                            falseSignals++
                            tradeOutcome = IntradayTradeOutcome.FALSE_SIGNAL_CLOSE_MALAM
                        }
                        break
                    }
                }

                if (!hitTp && !hitSl && !closeMalamExit && tradeOutcome == null) {
                    unresolvedOutcomes++
                    exitIndex = maxLookahead
                    exitPrice = sortedCandles[maxLookahead].close
                    exitReason = "LOOKAHEAD_EXPIRY"
                    tradeOutcome = IntradayTradeOutcome.UNRESOLVED
                }

                currentHoldingUntilIndex = exitIndex

                val grossPnlPct = ((exitPrice - currentCandle.close) / currentCandle.close) * 100.0
                val totalFeePct = feeConfig.buyTakerPct + feeConfig.sellTakerPct
                val netPnlPct = grossPnlPct - totalFeePct

                trades += IntradayTradeRecord(
                    entryIndex = i,
                    entryTime = currentCandle.timestamp,
                    entryPrice = currentCandle.close,
                    targetPrice1 = tradeTp1,
                    targetPrice2 = tradeTp2,
                    stopLoss = tradeSl,
                    exitIndex = exitIndex,
                    exitTime = sortedCandles[exitIndex].timestamp,
                    exitPrice = exitPrice,
                    exitReason = exitReason,
                    grossPnlPct = grossPnlPct,
                    netPnlPct = netPnlPct,
                    barsHeld = exitIndex - i,
                    outcome = tradeOutcome ?: IntradayTradeOutcome.UNRESOLVED
                )
            } else if (signal.action != SignalAction.BUY && isMomentumCandidate) {
                if (futureRalliedWithoutSl) {
                    missedOpportunities++
                    tradeOutcome = IntradayTradeOutcome.MISSED_OPPORTUNITY
                } else if (futureHitSl) {
                    avoidedLosses++
                    tradeOutcome = IntradayTradeOutcome.AVOIDED_LOSS
                }
            }

            frames += IntradayReplayFrame(
                index = i,
                candle = currentCandle,
                timestamp = evalTime,
                phase = phase,
                action = signal.action,
                confidence = signal.confidence,
                setupScore = evalResult.setupScore,
                isQualified = evalResult.isQualified,
                isMomentumCandidate = isMomentumCandidate,
                rejectionReason = rejectionReason,
                outcome = tradeOutcome
            )
        }

        // Bottleneck identification
        val maxRejections = maxOf(step1Rejections, step2Rejections, step3Rejections, step4Rejections, sessionGateRejections)
        val primaryStep = when {
            maxRejections == 0 -> "NONE"
            step1Rejections == maxRejections -> "STEP_1_MACRO_TREND"
            step2Rejections == maxRejections -> "STEP_2_SUPPORT_AND_HIGH"
            step3Rejections == maxRejections -> "STEP_3_RSI_MACD"
            step4Rejections == maxRejections -> "STEP_4_RR_SCORE"
            else -> "SESSION_GATE"
        }

        val primaryDesc = when (primaryStep) {
            "STEP_1_MACRO_TREND" -> "STEP 1 (Macro Trend & Anti Flash Dump) menolak terbanyak: $step1Rejections kandidat."
            "STEP_2_SUPPORT_AND_HIGH" -> "STEP 2 (Support & Near High Filter) menolak terbanyak: $step2Rejections kandidat."
            "STEP_3_RSI_MACD" -> "STEP 3 (RSI & MACD Momentum) menolak terbanyak: $step3Rejections kandidat."
            "STEP_4_RR_SCORE" -> "STEP 4 (Risk-Reward & Setup Score) menolak terbanyak: $step4Rejections kandidat."
            "SESSION_GATE" -> "SESSION GATE (Rest Malam & Hold Sore) menolak terbanyak: $sessionGateRejections kandidat."
            else -> "Tidak ada bottleneck dominan."
        }

        val bottleneck = IntradayBottleneckReport(
            totalMomentumCandidates = totalMomentumCandidates,
            step1Rejections = step1Rejections,
            step2Rejections = step2Rejections,
            step3Rejections = step3Rejections,
            step4Rejections = step4Rejections,
            sessionGateRejections = sessionGateRejections,
            flashDumpTraumaBlocks = flashDumpTraumaBlocks,
            pumpAndDumpTrapBlocks = pumpAndDumpTrapBlocks,
            missedAfterStep1 = missedAfterStep1,
            missedAfterStep2 = missedAfterStep2,
            missedAfterStep3 = missedAfterStep3,
            missedAfterStep4 = missedAfterStep4,
            missedAfterSessionGate = missedAfterSessionGate,
            primaryBottleneckStep = primaryStep,
            primaryBottleneckDescription = primaryDesc
        )

        val resolvedCount = validEntries + falseSignals
        val winRatePct = if (resolvedCount > 0) (validEntries.toDouble() / resolvedCount) * 100.0 else 0.0

        val totalNetPnlPct = trades.sumOf { it.netPnlPct }
        val avgNetPnlPct = if (trades.isNotEmpty()) totalNetPnlPct / trades.size else 0.0

        val grossWins = trades.filter { it.grossPnlPct > 0 }.sumOf { it.grossPnlPct }
        val grossLosses = trades.filter { it.grossPnlPct < 0 }.sumOf { kotlin.math.abs(it.grossPnlPct) }
        val profitFactor = if (grossLosses > 0.0) grossWins / grossLosses else if (grossWins > 0) 99.0 else 0.0

        // Max drawdown calculation on equity curve
        var runningEquity = 100.0
        var peakEquity = 100.0
        var maxDd = 0.0
        for (t in trades) {
            runningEquity *= (1.0 + (t.netPnlPct / 100.0))
            if (runningEquity > peakEquity) {
                peakEquity = runningEquity
            }
            val dd = if (peakEquity > 0.0) ((peakEquity - runningEquity) / peakEquity) * 100.0 else 0.0
            if (dd > maxDd) {
                maxDd = dd
            }
        }

        return IntradayReplayReport(
            symbol = symbol,
            totalEvaluations = frames.size,
            totalSignalsTriggered = frames.count { it.action == SignalAction.BUY },
            validEntries = validEntries,
            falseSignals = falseSignals,
            missedOpportunities = missedOpportunities,
            avoidedLosses = avoidedLosses,
            unresolvedOutcomes = unresolvedOutcomes,
            ambiguousOutcomes = ambiguousOutcomes,
            totalNetPnlPct = totalNetPnlPct,
            avgNetPnlPct = avgNetPnlPct,
            winRatePct = winRatePct,
            profitFactor = profitFactor,
            maxDrawdownPct = maxDd,
            bottleneck = bottleneck,
            trades = trades,
            phaseDistribution = phaseCounts,
            frames = frames
        )
    }

    private fun emptyReport(symbol: String) = IntradayReplayReport(
        symbol = symbol,
        totalEvaluations = 0,
        totalSignalsTriggered = 0,
        validEntries = 0,
        falseSignals = 0,
        missedOpportunities = 0,
        avoidedLosses = 0,
        unresolvedOutcomes = 0,
        ambiguousOutcomes = 0,
        totalNetPnlPct = 0.0,
        avgNetPnlPct = 0.0,
        winRatePct = 0.0,
        profitFactor = 0.0,
        maxDrawdownPct = 0.0,
        bottleneck = IntradayBottleneckReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "NO_DATA", "Data tidak mencukupi"),
        trades = emptyList(),
        phaseDistribution = emptyMap(),
        frames = emptyList()
    )
}
