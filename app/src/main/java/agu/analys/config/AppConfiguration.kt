package agu.analys.config

import kotlin.math.abs

enum class AiProvider(val label: String) { GROQ("Groq"), GEMINI("Gemini") }

data class TradingFeeConfig(
    val buyMakerPct: Double = 0.11,
    val buyTakerPct: Double = 0.21,
    val sellMakerPct: Double = 0.32,
    val sellTakerPct: Double = 0.42
)

enum class MarketDataSource(val label: String) { INDODAX("Indodax") }
enum class MarketDataTransport(val label: String) { REST("REST"), WEBSOCKET("WebSocket") }

object MarketDataConfiguration {
    val activeSource = MarketDataSource.INDODAX
    val transports = listOf(MarketDataTransport.REST, MarketDataTransport.WEBSOCKET)
    // Future providers such as Pintu/Pluang belong behind this seam. UI remains Indodax-only for now.
}

object FeeCalculator {
    data class Result(
        val feePct: Double,
        val netRewardPct: Double,
        val netRiskPct: Double,
        val netRr: Double
    )

    /** Round-trip fee is buy + sell. Uses absolute price distances so it also works for short setups. */
    fun roundTrip(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        fees: TradingFeeConfig,
        useMaker: Boolean = false
    ): Result {
        if (entry <= 0.0 || stopLoss <= 0.0 || takeProfit <= 0.0) return Result(0.0, 0.0, 0.0, 0.0)
        val buyFee = if (useMaker) fees.buyMakerPct else fees.buyTakerPct
        val sellFee = if (useMaker) fees.sellMakerPct else fees.sellTakerPct
        val feePct = buyFee + sellFee
        val reward = abs(takeProfit - entry) / entry * 100.0 - feePct
        val risk = abs(entry - stopLoss) / entry * 100.0 + feePct
        val rr = if (risk > 0.0) reward / risk else 0.0
        return Result(feePct, reward, risk, rr)
    }
}
