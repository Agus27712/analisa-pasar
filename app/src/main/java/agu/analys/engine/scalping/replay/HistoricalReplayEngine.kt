package agu.analys.engine.scalping.replay

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.scalping.ScalpingMtfEvaluator
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.model.SignalAction
import agu.analys.model.SignalAudit

/**
 * Historical replay yang causal:
 * - MTF hanya boleh memakai candle yang sudah CLOSED pada saat evaluasi.
 * - Tidak membuat synthetic orderbook secara default.
 * - Outcome memakai TP/SL aktual dari evaluator bila tersedia.
 * - Menyimpan distribusi rejection dan missed opportunity per checkpoint.
 */
enum class ReplayTradeOutcome {
    VALID_ENTRY,
    FALSE_SIGNAL,
    MISSED_BUY,
    AVOIDED_LOSS,
    UNRESOLVED,
    AMBIGUOUS
}

data class ReplayFrame(
    val index: Int,
    val candle: CandleBar,
    val audit: SignalAudit,
    val isMomentumCandidate: Boolean,
    val futureRalliedWithoutSl: Boolean,
    val outcome: ReplayTradeOutcome? = null
)

data class BottleneckAnalysis(
    val totalMomentumOpportunities: Int,
    val step1Rejections: Int,
    val step2Rejections: Int,
    val step3Rejections: Int,
    val step4Rejections: Int,
    val missedAfterStep1: Int,
    val missedAfterStep2: Int,
    val missedAfterStep3: Int,
    val missedAfterStep4: Int,
    val unmeasuredOrderBookFrames: Int,
    val primaryBottleneckStep: Int,
    val primaryBottleneckDescription: String
) {
    val totalRejectedMomentumOpportunities: Int
        get() = step1Rejections + step2Rejections + step3Rejections + step4Rejections

    private fun pctOfCandidates(value: Int): Double =
        if (totalMomentumOpportunities > 0) value.toDouble() / totalMomentumOpportunities * 100.0 else 0.0

    private fun pctOfRejections(value: Int): Double =
        if (totalRejectedMomentumOpportunities > 0) {
            value.toDouble() / totalRejectedMomentumOpportunities * 100.0
        } else 0.0

    val step1RejectionPct: Double get() = pctOfCandidates(step1Rejections)
    val step2RejectionPct: Double get() = pctOfCandidates(step2Rejections)
    val step3RejectionPct: Double get() = pctOfCandidates(step3Rejections)
    val step4RejectionPct: Double get() = pctOfCandidates(step4Rejections)

    val step1RejectionSharePct: Double get() = pctOfRejections(step1Rejections)
    val step2RejectionSharePct: Double get() = pctOfRejections(step2Rejections)
    val step3RejectionSharePct: Double get() = pctOfRejections(step3Rejections)
    val step4RejectionSharePct: Double get() = pctOfRejections(step4Rejections)
}

data class HistoricalReplayReport(
    val symbol: String,
    val totalEvaluations: Int,
    val totalSignalsTriggered: Int,
    val validEntries: Int,
    val falseSignals: Int,
    val missedOpportunities: Int,
    val avoidedLosses: Int,
    val unresolvedOutcomes: Int,
    val ambiguousOutcomes: Int,
    val orderBookDataAvailable: Boolean,
    val orderBookMode: String,
    val bottleneck: BottleneckAnalysis,
    val frames: List<ReplayFrame>
) {
    val resolvedSignalOutcomes: Int
        get() = validEntries + falseSignals

    val winRatePct: Double
        get() = if (resolvedSignalOutcomes > 0) validEntries.toDouble() / resolvedSignalOutcomes * 100.0 else 0.0

    val captureRatePct: Double
        get() {
            val totalViable = validEntries + missedOpportunities
            return if (totalViable > 0) validEntries.toDouble() / totalViable * 100.0 else 0.0
        }

    val outcomeCoveragePct: Double
        get() {
            val total = resolvedSignalOutcomes + unresolvedOutcomes + ambiguousOutcomes
            return if (total > 0) resolvedSignalOutcomes.toDouble() / total * 100.0 else 0.0
        }
}

object HistoricalReplayEngine {

    fun replay(
        symbol: String = "BTCIDR",
        m1Candles: List<CandleBar>,
        m15Candles: List<CandleBar> = emptyList(),
        h1Candles: List<CandleBar> = emptyList(),
        orderBookProvider: ((index: Int, candle: CandleBar) -> Pair<List<OrderBookItem>, List<OrderBookItem>>)? = null,
        targetProfitPct: Double = 1.5,
        stopLossPct: Double = 1.0,
        forwardLookaheadBars: Int = 15,
        feeConfig: TradingFeeConfig = TradingFeeConfig(),
        synthesizeMissingHigherTimeframes: Boolean = true,
        diagnosticIgnoreOrderBookWhenUnavailable: Boolean = false
    ): HistoricalReplayReport {
        if (m1Candles.size < 20) return emptyReport(symbol)

        val sortedM1 = m1Candles.sortedBy { it.timestamp }
        val sourceM15 = m15Candles.sortedBy { it.timestamp }
        val sourceH1 = h1Candles.sortedBy { it.timestamp }
        val endIndex = sortedM1.size - 1

        val frames = mutableListOf<ReplayFrame>()
        var step1Rejections = 0
        var step2Rejections = 0
        var step3Rejections = 0
        var step4Rejections = 0
        var missedAfterStep1 = 0
        var missedAfterStep2 = 0
        var missedAfterStep3 = 0
        var missedAfterStep4 = 0
        var unmeasuredOrderBookFrames = 0
        var totalMomentumOpportunities = 0
        var validEntries = 0
        var falseSignals = 0
        var missedOpportunities = 0
        var avoidedLosses = 0
        var unresolvedOutcomes = 0
        var ambiguousOutcomes = 0
        var anyOrderBookData = false

        for (i in 19..endIndex) {
            val currentCandle = sortedM1[i]
            val evaluationTime = currentCandle.timestamp + 60_000L
            val window = sortedM1.subList(0, i + 1)

            val effectiveM15 = if (sourceM15.isNotEmpty()) {
                sourceM15
            } else if (synthesizeMissingHigherTimeframes) {
                generateSyntheticHigherTimeframe(window, 15)
            } else {
                emptyList()
            }

            val effectiveH1 = if (sourceH1.isNotEmpty()) {
                sourceH1
            } else if (synthesizeMissingHigherTimeframes) {
                generateSyntheticHigherTimeframe(window, 60)
            } else {
                emptyList()
            }

            val m15Slice = effectiveM15.filter { it.timestamp + 15 * 60_000L <= evaluationTime }
            val h1Slice = effectiveH1.filter { it.timestamp + 60 * 60_000L <= evaluationTime }

            // Tidak boleh memakai fallback future candle. Tunggu sampai 20 candle MTF benar-benar closed.
            if (m15Slice.size < 20 || h1Slice.size < 20) continue

            val (bids, asks) = orderBookProvider?.invoke(i, currentCandle)
                ?: (emptyList<OrderBookItem>() to emptyList())

            val orderBookAvailable = bids.isNotEmpty() || asks.isNotEmpty()
            anyOrderBookData = anyOrderBookData || orderBookAvailable
            if (!orderBookAvailable && diagnosticIgnoreOrderBookWhenUnavailable) {
                unmeasuredOrderBookFrames++
            }

            val evalResult = ScalpingMtfEvaluator.evaluate(
                price = currentCandle.close,
                h1Candles = h1Slice,
                m15Candles = m15Slice,
                m1Candles = window,
                bids = bids,
                asks = asks,
                fees = feeConfig,
                symbol = symbol,
                diagnosticIgnoreOrderBookWhenUnavailable = diagnosticIgnoreOrderBookWhenUnavailable
            )

            val audit = evalResult?.audit ?: SignalAudit(
                symbol = symbol,
                timestamp = evaluationTime,
                price = currentCandle.close,
                finalAction = SignalAction.HOLD.name,
                rejectionReason = "INSUFFICIENT_DATA"
            )

            val recent20 = sortedM1.subList(maxOf(0, i - 20), i)
            val avgVol = if (recent20.isNotEmpty()) recent20.map { it.volume }.average() else currentCandle.volume
            val isVolumeSurge = currentCandle.volume >= avgVol * 1.2
            val isGreenBreakout = currentCandle.close > currentCandle.open && isVolumeSurge
            val isMomentumCandidate = isGreenBreakout ||
                (currentCandle.close > (recent20.maxOfOrNull { it.high } ?: currentCandle.close))

            val riskTarget = evalResult?.signal?.targetPrice1?.takeIf { it.isFinite() && it > currentCandle.close }
                ?: currentCandle.close * (1.0 + targetProfitPct / 100.0)
            val riskStop = evalResult?.signal?.stopLoss?.takeIf { it.isFinite() && it < currentCandle.close }
                ?: currentCandle.close * (1.0 - stopLossPct / 100.0)

            val maxLookahead = minOf(sortedM1.size - 1, i + forwardLookaheadBars)
            var futureRalliedWithoutSl = false
            var hitSl = false
            var ambiguous = false
            var resolved = false

            if (i < maxLookahead) {
                for (f in (i + 1)..maxLookahead) {
                    val futureBar = sortedM1[f]
                    val hitTp = futureBar.high >= riskTarget
                    val hitStop = futureBar.low <= riskStop
                    when {
                        hitTp && hitStop -> {
                            ambiguous = true
                            resolved = true
                            break
                        }
                        hitStop -> {
                            hitSl = true
                            resolved = true
                            break
                        }
                        hitTp -> {
                            futureRalliedWithoutSl = true
                            resolved = true
                            break
                        }
                    }
                }
            }

            val hasTradeOutcomeCandidate =
                audit.finalAction == SignalAction.BUY.name || isMomentumCandidate

            val outcome = if (!hasTradeOutcomeCandidate) {
                null
            } else {
                when {
                    ambiguous -> {
                        ambiguousOutcomes++
                        ReplayTradeOutcome.AMBIGUOUS
                    }
                    audit.finalAction == SignalAction.BUY.name && futureRalliedWithoutSl -> {
                        validEntries++
                        ReplayTradeOutcome.VALID_ENTRY
                    }
                    audit.finalAction == SignalAction.BUY.name && hitSl -> {
                        falseSignals++
                        ReplayTradeOutcome.FALSE_SIGNAL
                    }
                    audit.finalAction == SignalAction.BUY.name -> {
                        unresolvedOutcomes++
                        ReplayTradeOutcome.UNRESOLVED
                    }
                    isMomentumCandidate && futureRalliedWithoutSl -> {
                        missedOpportunities++
                        ReplayTradeOutcome.MISSED_BUY
                    }
                    isMomentumCandidate && hitSl -> {
                        avoidedLosses++
                        ReplayTradeOutcome.AVOIDED_LOSS
                    }
                    !resolved -> {
                        unresolvedOutcomes++
                        ReplayTradeOutcome.UNRESOLVED
                    }
                    else -> null
                }
            }

            if (isMomentumCandidate) {
                totalMomentumOpportunities++
                when {
                    audit.step1Ok.not() -> {
                        step1Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep1++
                    }
                    audit.step2Ok.not() -> {
                        step2Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep2++
                    }
                    audit.step3Ok.not() -> {
                        step3Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep3++
                    }
                    audit.step4Ok.not() -> {
                        step4Rejections++
                        if (futureRalliedWithoutSl) missedAfterStep4++
                    }
                }
            }

            frames += ReplayFrame(
                index = i,
                candle = currentCandle,
                audit = audit,
                isMomentumCandidate = isMomentumCandidate,
                futureRalliedWithoutSl = futureRalliedWithoutSl,
                outcome = outcome
            )
        }

        val maxRejections = maxOf(step1Rejections, step2Rejections, step3Rejections, step4Rejections)
        val primaryStep = when {
            maxRejections == 0 -> 0
            step1Rejections == maxRejections -> 1
            step2Rejections == maxRejections -> 2
            step3Rejections == maxRejections -> 3
            else -> 4
        }

        val primaryDesc = when (primaryStep) {
            1 -> "STEP 1 menolak $step1Rejections/$totalMomentumOpportunities peluang momentum."
            2 -> "STEP 2 menolak $step2Rejections/$totalMomentumOpportunities peluang momentum."
            3 -> "STEP 3 menolak $step3Rejections/$totalMomentumOpportunities peluang momentum."
            4 -> "STEP 4 menolak $step4Rejections/$totalMomentumOpportunities peluang momentum."
            else -> "Tidak ada bottleneck yang terukur."
        }

        val orderBookMode = when {
            anyOrderBookData && diagnosticIgnoreOrderBookWhenUnavailable -> "MIXED"
            anyOrderBookData -> "PROVIDER_SUPPLIED"
            diagnosticIgnoreOrderBookWhenUnavailable -> "UNAVAILABLE_BYPASSED"
            else -> "UNAVAILABLE_BLOCKING"
        }

        return HistoricalReplayReport(
            symbol = symbol,
            totalEvaluations = frames.size,
            totalSignalsTriggered = frames.count { it.audit.finalAction == SignalAction.BUY.name },
            validEntries = validEntries,
            falseSignals = falseSignals,
            missedOpportunities = missedOpportunities,
            avoidedLosses = avoidedLosses,
            unresolvedOutcomes = unresolvedOutcomes,
            ambiguousOutcomes = ambiguousOutcomes,
            orderBookDataAvailable = anyOrderBookData,
            orderBookMode = orderBookMode,
            bottleneck = BottleneckAnalysis(
                totalMomentumOpportunities = totalMomentumOpportunities,
                step1Rejections = step1Rejections,
                step2Rejections = step2Rejections,
                step3Rejections = step3Rejections,
                step4Rejections = step4Rejections,
                missedAfterStep1 = missedAfterStep1,
                missedAfterStep2 = missedAfterStep2,
                missedAfterStep3 = missedAfterStep3,
                missedAfterStep4 = missedAfterStep4,
                unmeasuredOrderBookFrames = unmeasuredOrderBookFrames,
                primaryBottleneckStep = primaryStep,
                primaryBottleneckDescription = primaryDesc
            ),
            frames = frames
        )
    }

    private fun emptyReport(symbol: String) = HistoricalReplayReport(
        symbol = symbol,
        totalEvaluations = 0,
        totalSignalsTriggered = 0,
        validEntries = 0,
        falseSignals = 0,
        missedOpportunities = 0,
        avoidedLosses = 0,
        unresolvedOutcomes = 0,
        ambiguousOutcomes = 0,
        orderBookDataAvailable = false,
        orderBookMode = "NO_DATA",
        bottleneck = BottleneckAnalysis(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "Data tidak cukup"),
        frames = emptyList()
    )

    /** Utility test-only: membangun HTF dari window yang tersedia saat ini (causal, bukan full-dataset). */
    fun generateSyntheticHigherTimeframe(m1Candles: List<CandleBar>, intervalMinutes: Int): List<CandleBar> {
        if (m1Candles.isEmpty()) return emptyList()
        val intervalMs = intervalMinutes * 60_000L
        val grouped = m1Candles.groupBy { it.timestamp / intervalMs }.values.map { group ->
            CandleBar(
                timestamp = group.minOf { it.timestamp / intervalMs } * intervalMs,
                open = group.minBy { it.timestamp }.open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = group.maxBy { it.timestamp }.close,
                volume = group.sumOf { it.volume }
            )
        }.sortedBy { it.timestamp }

        if (grouped.size < 21) {
            val first = grouped.firstOrNull() ?: return emptyList()
            val padding = mutableListOf<CandleBar>()
            val needed = 21 - grouped.size
            for (k in needed downTo 1) {
                padding += CandleBar(
                    timestamp = first.timestamp - (k * intervalMs),
                    open = first.open,
                    high = first.open * 1.002,
                    low = first.open * 0.998,
                    close = first.open,
                    volume = 1000.0
                )
            }
            return padding + grouped
        }
        return grouped
    }
}
