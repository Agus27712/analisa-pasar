package agu.analys.engine.trenching

import agu.analys.engine.global.GlobalMarketContext
import agu.analys.model.OrderBookItem
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import agu.analys.model.TrendSentiment
import agu.analys.config.TradingFeeConfig
import agu.analys.config.FeeCalculator

/**
 * ARCHITECTURAL CONTRACT:
 * TrenchingEvaluator adalah EXCEPTION RESMI / HYBRID STRATEGY (Position-Aware Hybrid).
 * Berbeda dari Scalping, Second Wave, Swing, dan Office Daily yang merupakan pure BUY-analyzers,
 * Trenching secara fundamental adalah strategi DCA / Grid Pyramiding bertingkat yang memerlukan
 * status kepemilikan aset (hasPosition & entryPrice) untuk memetakan level averaging, defense zone,
 * dan exit threshold.
 */
object TrenchingEvaluator {
    fun evaluate(
        globalContext: GlobalMarketContext?,
        currentPrice: Double,
        candles: List<CandleBar>, // Default 1H atau H4 untuk struktur trench
        ltfCandles: List<CandleBar>, // Default M15 atau M5 untuk timing flow return
        bids: List<OrderBookItem>,
        asks: List<OrderBookItem>,
        hasPosition: Boolean,
        entryPrice: Double = 0.0,
        tradingFees: TradingFeeConfig
    ): AISignalState? {
        if (candles.isEmpty() || ltfCandles.isEmpty() || currentPrice <= 0.0) return null

        // 1. Calculate VWAP berbasis candle struktur
        var sumPV = 0.0
        var sumV = 0.0
        candles.takeLast(24).forEach { c ->
            val typical = (c.high + c.low + c.close) / 3.0
            sumPV += typical * c.volume
            sumV += c.volume
        }
        val vwap = if (sumV > 0) sumPV / sumV else currentPrice

        // 2. Evaluasi Flow Intelligence multi-komponen
        val flowContext = FlowIntelligence.evaluate(
            candles = ltfCandles,
            bids = bids,
            asks = asks,
            vwapValue = vwap,
            globalContext = globalContext
        )

        // 3. Evaluasi Struktur & Kompresi Trench
        val recentHTF = candles.takeLast(8)
        val maxH = recentHTF.maxOfOrNull { it.high } ?: currentPrice
        val minL = recentHTF.minOfOrNull { it.low } ?: currentPrice
        val compressionPct = if (minL > 0) (maxH - minL) / minL * 100.0 else 0.0

        // Tentukan Kualitas Trench berdasarkan Composite Score
        val compositeScore = flowContext.totalTrenchScore
        val trenchQuality = when {
            compressionPct > 6.0 || compositeScore < 40.0 -> TrenchQuality.INVALID
            compositeScore < 52.0 -> TrenchQuality.WEAK
            compositeScore < 72.0 -> TrenchQuality.VALID
            compositeScore < 85.0 -> TrenchQuality.STRONG
            else -> TrenchQuality.EXPANSION
        }

        // 4. Hitung Risk / Reward, Stop Loss, dan Target TP (TP1 2.0x, TP2 3.5x stop distance untuk meng-cover fee)
        val stopLossLevel = (minL * 0.99).coerceAtMost(currentPrice * 0.985)
        val stopDistance = (currentPrice - stopLossLevel).coerceAtLeast(currentPrice * 0.008)
        val tp1Level = currentPrice + (stopDistance * 2.0)
        val tp2Level = currentPrice + (stopDistance * 3.5)

        val feeResult = FeeCalculator.roundTrip(
            entry = currentPrice,
            stopLoss = stopLossLevel,
            takeProfit = tp1Level,
            fees = tradingFees
        )

        // 5. Anti-FOMO Guard Evaluation
        val lastLtf = ltfCandles.last()
        val candleSpreadPct = if (lastLtf.open > 0) Math.abs(lastLtf.close - lastLtf.open) / lastLtf.open * 100.0 else 0.0
        val (fomoBlocked, fomoReason) = TrenchingAntiFomoGuard.shouldBlockEntry(
            flowContext = flowContext,
            currentPrice = currentPrice,
            trenchResistance = maxH,
            candleSpreadPct = candleSpreadPct,
            netRewardRiskRatio = feeResult.netRr
        )

        // 6. State Machine & Workflow Decision
        var trenchingState: TrenchingState
        var action = SignalAction.HOLD
        val reasons = mutableListOf<String>()
        var confidence = 0

        val exitState = TrenchingExitEvaluator.evaluateExit(
            hasPosition = hasPosition,
            flowContext = flowContext,
            entryPrice = entryPrice,
            currentPrice = currentPrice,
            supportLevel = minL
        )

        if (hasPosition) {
            // WORKFLOW: IN-POSITION (HOLD & EXIT LOGIC)
            trenchingState = TrenchingState.IN_POSITION
            when (exitState) {
                TrenchingExitState.EXIT -> {
                    trenchingState = TrenchingState.FLOW_FAILURE
                    action = SignalAction.SELL
                    confidence = 88
                    reasons.add("FLOW REVERSAL / BREAKDOWN: Penjualan bervolume merusak struktur trench.")
                }
                TrenchingExitState.PROTECT_PROFIT -> {
                    trenchingState = TrenchingState.PROFIT_PROTECTION
                    action = SignalAction.SELL
                    confidence = 72
                    reasons.add("FLOW WEAKENING / EXHAUSTION: Amankan keuntungan saat flow mulai melambat.")
                }
                TrenchingExitState.REDUCE -> {
                    trenchingState = TrenchingState.PROFIT_PROTECTION
                    confidence = 55
                    reasons.add("FLOW STAGNANT: Kurangi ukuran posisi, flow kehilangan persistensi.")
                }
                TrenchingExitState.HOLD -> {
                    confidence = 65
                    reasons.add(if (flowContext.isHealthyPullback) {
                        "HEALTHY PULLBACK: Koreksi wajar bervolume tipis, struktur trench masih utuh."
                    } else {
                        "FLOW STABLE: Tekanan beli dan struktur posisi masih bertahan aman."
                    })
                }
            }
        } else {
            // WORKFLOW: NO-POSITION (ENTRY LIFECYCLE)
            when (trenchQuality) {
                TrenchQuality.INVALID, TrenchQuality.WEAK -> {
                    trenchingState = TrenchingState.SCANNING
                    confidence = 20
                    reasons.add("Trench belum terbentuk rapi (Kompresi: ${String.format(java.util.Locale.US, "%.1f", compressionPct)}%). Mode: SCANNING.")
                }
                TrenchQuality.VALID, TrenchQuality.STRONG, TrenchQuality.EXPANSION -> {
                    // Alur presisi: ABSORBING / PULLBACK -> FLOW RETURNS -> ENTRY
                    if (flowContext.flowState == FlowState.ABSORBING || flowContext.isHealthyPullback) {
                        // Jangan langsung entry saat absorption / pullback sedang berjalan!
                        trenchingState = TrenchingState.PULLBACK
                        confidence = 45
                        reasons.add("TRENCH FORMING: Penyerapan (absorption) & pullback sehat terdeteksi. Menunggu timing flow return.")
                    } else if (flowContext.flowState == FlowState.ACCUMULATING || flowContext.flowState == FlowState.EXPANDING) {
                        trenchingState = TrenchingState.FLOW_RETURNING
                        if (!fomoBlocked) {
                            action = SignalAction.BUY
                            trenchingState = TrenchingState.ENTRY_READY
                            confidence = when (trenchQuality) {
                                TrenchQuality.EXPANSION -> 88
                                TrenchQuality.STRONG -> 80
                                else -> 70
                            }
                            reasons.add("ENTRY READY: Flow return terkonfirmasi setelah kompresi trench sehat.")
                        } else {
                            reasons.add(fomoReason)
                        }
                    } else {
                        trenchingState = TrenchingState.TRENCH_FORMING
                        confidence = 35
                        reasons.add("Trench valid tapi flow masih netral. Memantau volume.")
                    }
                }
            }
        }

        val allocation = TrenchingPositionSizer.calculateAllocation(trenchQuality, flowContext.flowState)
        val allocPct = (allocation * 100).toInt()

        // Ringkasan Transparan & Explainability (Poin 27 & 28 Master Prompt)
        reasons.add(
            0,
            "Trench: ${trenchQuality.name} (${String.format(java.util.Locale.US, "%.0f", compositeScore)}/100) | Flow: ${flowContext.flowState.name} | Sizing: $allocPct%"
        )
        reasons.add(
            "Trace: Flow ${flowContext.flowScore.toInt()} · Struct ${flowContext.structureScore.toInt()} · Vol ${flowContext.volumeQualityScore.toInt()} · Pullback ${flowContext.pullbackQualityScore.toInt()} · Persist ${flowContext.flowPersistence}x"
        )

        return AISignalState(
            action = action,
            confidence = confidence,
            sentiment = if (action == SignalAction.BUY) TrendSentiment.STRONG_BULLISH_CONTINUATION else TrendSentiment.NEUTRAL_CONSOLIDATION,
            entryPrice = currentPrice,
            targetPrice1 = tp1Level,
            targetPrice2 = tp2Level,
            stopLoss = stopLossLevel,
            riskRewardRatio = "1 : ${String.format(java.util.Locale.US, "%.1f", feeResult.netRr)}",
            patternDetected = "Trenching: ${trenchingState.name}",
            reasoning = reasons,
            timestamp = System.currentTimeMillis()
        )
    }
}

