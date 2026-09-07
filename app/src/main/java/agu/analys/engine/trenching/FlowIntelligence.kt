package agu.analys.engine.trenching

import agu.analys.model.OrderBookItem
import agu.analys.engine.scalping.OrderBookAnalyzer
import agu.analys.model.CandleBar
import agu.analys.engine.global.GlobalMarketContext

data class FlowContext(
    val flowState: FlowState,
    val flowScore: Double,
    val structureScore: Double,
    val volumeQualityScore: Double,
    val pullbackQualityScore: Double,
    val relativeStrengthScore: Double,
    val vwapScore: Double,
    val orderBookScore: Double,
    val totalTrenchScore: Double,
    val dataQuality: DataQuality,
    val vwapDistancePct: Double,
    val relativeStrength: Double,
    val volumeTrend: Double,
    val volumeAcceleration: Double,
    val flowPersistence: Int, // Number of consecutive periods maintaining consistent positive flow
    val isAbsorptionDetected: Boolean,
    val isHealthyPullback: Boolean
)

object FlowIntelligence {
    fun evaluate(
        candles: List<CandleBar>,
        bids: List<OrderBookItem>,
        asks: List<OrderBookItem>,
        vwapValue: Double,
        globalContext: GlobalMarketContext? = null
    ): FlowContext {
        if (candles.isEmpty()) {
            return FlowContext(
                flowState = FlowState.NEUTRAL,
                flowScore = 0.0,
                structureScore = 0.0,
                volumeQualityScore = 0.0,
                pullbackQualityScore = 0.0,
                relativeStrengthScore = 50.0,
                vwapScore = 50.0,
                orderBookScore = 50.0,
                totalTrenchScore = 0.0,
                dataQuality = DataQuality.LOW,
                vwapDistancePct = 0.0,
                relativeStrength = 1.0,
                volumeTrend = 0.0,
                volumeAcceleration = 0.0,
                flowPersistence = 0,
                isAbsorptionDetected = false,
                isHealthyPullback = false
            )
        }

        val currentPrice = candles.last().close
        val vwapDistance = if (vwapValue > 0) ((currentPrice - vwapValue) / vwapValue) * 100 else 0.0

        // 1. Order Book & Pressure
        val dataQuality = if (bids.isEmpty() || asks.isEmpty()) DataQuality.MEDIUM else DataQuality.HIGH
        val buyPressure = if (dataQuality == DataQuality.HIGH) {
            OrderBookAnalyzer.calculateBuyPressure(bids, asks)
        } else 50.0
        val orderBookScore = buyPressure.coerceIn(0.0, 100.0)

        // 2. Volume Analysis & Acceleration
        val recentCandles = candles.takeLast(10)
        val sampleSize = recentCandles.size
        val avgVolume = if (sampleSize > 1) {
            recentCandles.dropLast(1).map { it.volume }.average().coerceAtLeast(1.0)
        } else recentCandles.first().volume.coerceAtLeast(1.0)

        val currentVol = recentCandles.last().volume
        val prevVol = if (sampleSize >= 2) recentCandles[sampleSize - 2].volume else avgVolume
        val volumeTrend = if (avgVolume > 0) currentVol / avgVolume else 1.0
        val volumeAcceleration = if (prevVol > 0) (currentVol - prevVol) / prevVol else 0.0

        // 3. Flow Persistence (rolling window)
        var persistenceCount = 0
        for (i in recentCandles.indices.reversed()) {
            val c = recentCandles[i]
            val isBullish = c.close >= c.open
            if (isBullish && c.volume >= avgVolume * 0.75) {
                persistenceCount++
            } else if (!isBullish && c.volume > avgVolume * 1.25) {
                break // Heavy bear distribution breaks persistence
            }
        }

        // 4. VSA & Absorption Pattern
        val last = recentCandles.last()
        val isGreen = last.close >= last.open
        val candleSpread = if (last.low > 0) (last.high - last.low) / last.low * 100 else 0.0
        val bodySpread = if (last.open > 0) Math.abs(last.close - last.open) / last.open * 100 else 0.0

        // High volume with small price progress & strong buy pressure = absorption
        val isAbsorptionDetected = currentVol > avgVolume * 1.1 && bodySpread < 0.8 && buyPressure >= 52.0

        // Low volume pullback with price staying near support
        val isHealthyPullback = !isGreen && volumeTrend < 0.75 && buyPressure >= 45.0

        // 5. Relative Strength vs Global BTC Context
        val btcChange24h = globalContext?.btc24hChangePct ?: 0.0
        val coinPriceStart = recentCandles.first().open
        val coinPerfPct = if (coinPriceStart > 0) ((currentPrice - coinPriceStart) / coinPriceStart) * 100 else 0.0
        val relativeStrength = if (btcChange24h != 0.0) coinPerfPct - btcChange24h else coinPerfPct
        val relativeStrengthScore = (50.0 + (relativeStrength * 5.0)).coerceIn(10.0, 95.0)

        // 6. VWAP Score
        val vwapScore = when {
            vwapDistance in -1.5..2.5 -> 90.0 // Near VWAP, ideal acceptance
            vwapDistance in 2.5..5.0 -> 70.0 // Moderate distance
            vwapDistance > 5.0 -> 35.0 // Overextended
            vwapDistance < -3.0 -> 40.0 // Breakdown under VWAP
            else -> 60.0
        }

        // 7. Pullback & Volume Quality Score
        val volumeQualityScore = when {
            volumeTrend in 1.1..2.5 && persistenceCount >= 2 -> 90.0
            volumeTrend in 0.8..1.5 -> 75.0
            isHealthyPullback -> 85.0
            volumeTrend > 3.0 && persistenceCount <= 1 -> 40.0 // Suspicious spike
            else -> 50.0
        }

        val pullbackQualityScore = when {
            isHealthyPullback -> 90.0
            isAbsorptionDetected -> 85.0
            !isGreen && volumeTrend > 1.5 -> 20.0 // Dangerous heavy sell
            else -> 60.0
        }

        // 8. Structure Score (Price Compression & Support Stability)
        val highRange = recentCandles.maxOfOrNull { it.high } ?: currentPrice
        val lowRange = recentCandles.minOfOrNull { it.low } ?: currentPrice
        val compressionPct = if (lowRange > 0) (highRange - lowRange) / lowRange * 100 else 0.0
        val structureScore = when {
            compressionPct in 0.5..3.5 -> 90.0 // Ideal tight trench
            compressionPct in 3.5..5.5 -> 70.0 // Moderate range
            compressionPct > 7.0 -> 30.0 // Too volatile / chaotic
            else -> 60.0
        }

        // 9. Flow State Determination
        var flowScore: Double
        var flowState: FlowState

        if (isGreen && volumeTrend >= 1.2 && persistenceCount >= 2 && vwapDistance <= 5.0) {
            flowState = FlowState.EXPANDING
            flowScore = (75.0 + (persistenceCount * 5.0) + (volumeTrend * 4.0)).coerceAtMost(95.0)
        } else if (isAbsorptionDetected || (isHealthyPullback && buyPressure >= 55.0)) {
            flowState = FlowState.ABSORBING
            flowScore = 68.0 + (buyPressure * 0.2)
        } else if (isGreen && persistenceCount >= 1 && buyPressure >= 50.0) {
            flowState = FlowState.ACCUMULATING
            flowScore = 60.0 + (buyPressure * 0.25)
        } else if (!isGreen && volumeTrend > 1.3 && buyPressure < 42.0) {
            flowState = FlowState.REVERSING
            flowScore = 20.0
        } else if (!isGreen && volumeTrend > 0.9) {
            flowState = FlowState.WEAKENING
            flowScore = 40.0
        } else if (vwapDistance > 5.5 && persistenceCount <= 1) {
            flowState = FlowState.EXHAUSTING
            flowScore = 35.0
        } else {
            flowState = FlowState.NEUTRAL
            flowScore = 50.0
        }

        if (vwapDistance > 6.0 && flowState == FlowState.EXPANDING) {
            flowState = FlowState.EXHAUSTING
            flowScore = 40.0
        }

        // 10. Composite Trench Quality Score (Weights per Master Prompt Section 8):
        // Flow (30%) + Structure (20%) + Volume (15%) + Pullback (15%) + Relative Strength (10%) + VWAP (5%) + Order Book (5%)
        val totalTrenchScore = (
            (flowScore * 0.30) +
            (structureScore * 0.20) +
            (volumeQualityScore * 0.15) +
            (pullbackQualityScore * 0.15) +
            (relativeStrengthScore * 0.10) +
            (vwapScore * 0.05) +
            (orderBookScore * 0.05)
        ).coerceIn(0.0, 100.0)

        return FlowContext(
            flowState = flowState,
            flowScore = flowScore.coerceIn(0.0, 100.0),
            structureScore = structureScore,
            volumeQualityScore = volumeQualityScore,
            pullbackQualityScore = pullbackQualityScore,
            relativeStrengthScore = relativeStrengthScore,
            vwapScore = vwapScore,
            orderBookScore = orderBookScore,
            totalTrenchScore = totalTrenchScore,
            dataQuality = dataQuality,
            vwapDistancePct = vwapDistance,
            relativeStrength = relativeStrength,
            volumeTrend = volumeTrend,
            volumeAcceleration = volumeAcceleration,
            flowPersistence = persistenceCount,
            isAbsorptionDetected = isAbsorptionDetected,
            isHealthyPullback = isHealthyPullback
        )
    }
}

