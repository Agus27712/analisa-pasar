package agu.analys.config

enum class AiProvider(val label: String) { GROQ("Groq"), GEMINI("Gemini") }

data class TradingFeeConfig(
    val buyMakerPct: Double = 0.11,
    val buyTakerPct: Double = 0.21,
    val sellMakerPct: Double = 0.32,
    val sellTakerPct: Double = 0.42
)

/** Market-source seam for future providers. UI currently exposes Indodax only. */
enum class MarketDataSource(val label: String) { INDODAX("Indodax") }

enum class MarketDataTransport(val label: String) {
    REST("REST"),
    WEBSOCKET("WebSocket")
}

object MarketDataConfiguration {
    // Future: add Pintu/Pluang/etc. without changing the analysis engine contract.
    val activeSource = MarketDataSource.INDODAX
    val transports = listOf(MarketDataTransport.REST, MarketDataTransport.WEBSOCKET)
}

object FeeCalculator {
    data class Result(
        val feePct: Double,
        val netRewardPct: Double,
        val netRiskPct: Double,
        val netRr: Double
    )

    fun roundTrip(entry: Double, stopLoss: Double, takeProfit: Double, fees: TradingFeeConfig): Result {
        if (entry <= 0.0 || stopLoss <= 0.0 || takeProfit <= 0.0) return Result(0.0, 0.0, 0.0, 0.0)
        val feePct = (fees.buyTakerPct + fees.sellTakerPct)
        val reward = ((takeProfit - entry) / entry) * 100.0 - feePct
        val risk = ((entry - stopLoss) / entry) * 100.0 + feePct
        val rr = if (risk > 0.0) reward / risk else 0.0
        return Result(feePct, reward, risk, rr)
    }
}
