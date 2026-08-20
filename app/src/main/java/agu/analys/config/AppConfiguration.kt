package agu.analys.config

import kotlin.math.abs

enum class AiProvider(val label: String) { GROQ("Groq"), GEMINI("Gemini") }

enum class StrategyMode(val label: String, val badge: String, val shortDesc: String) {
    SCALPING("Scalping Agresif", "⚡ SCALPING", "Cepat (1M–15M) · Trigger mikro"),
    SECOND_WAVE("Second-Wave Hunter", "🌊 2ND-WAVE", "Pantulan 50–85% · Reclaim base"),
    SWING("Swing Trad", "📈 SWING", "Jangka menengah (1H–1D)")
}

data class TradingFeeConfig(
    val buyMakerPct: Double = 0.11,
    val buyTakerPct: Double = 0.21,
    val sellMakerPct: Double = 0.32,
    val sellTakerPct: Double = 0.42
)

enum class MarketDataSource(
    val label: String,
    val shortCode: String,
    val defaultQuoteAsset: String,
    val defaultFeeConfig: TradingFeeConfig,
    val description: String
) {
    INDODAX(
        label = "Indodax",
        shortCode = "IDR",
        defaultQuoteAsset = "IDR",
        defaultFeeConfig = TradingFeeConfig(
            buyMakerPct = 0.11,
            buyTakerPct = 0.21,
            sellMakerPct = 0.32,
            sellTakerPct = 0.42
        ),
        description = "Pasar Kripto Indonesia (Pair IDR) dengan orderbook & candle live Indodax."
    )
}
enum class MarketDataTransport(val label: String) { REST("REST"), WEBSOCKET("WebSocket") }

object MarketDataConfiguration {
    val transports = listOf(MarketDataTransport.REST, MarketDataTransport.WEBSOCKET)
}

object FeeCalculator {
    data class Result(
        val feePct: Double,
        val slippagePct: Double,
        val totalCostPct: Double,
        val netRewardPct: Double,
        val netRiskPct: Double,
        val netRr: Double
    )

    /** Round-trip fee is buy + sell plus estimated orderbook slippage. */
    fun roundTrip(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        fees: TradingFeeConfig,
        useMaker: Boolean = false,
        slippagePct: Double = 0.08
    ): Result {
        if (entry <= 0.0 || stopLoss <= 0.0 || takeProfit <= 0.0) return Result(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val buyFee = if (useMaker) fees.buyMakerPct else fees.buyTakerPct
        val sellFee = if (useMaker) fees.sellMakerPct else fees.sellTakerPct
        val feePct = buyFee + sellFee
        val totalCostPct = feePct + (2 * slippagePct) // Slippage saat buy & sell
        val reward = abs(takeProfit - entry) / entry * 100.0 - totalCostPct
        val risk = abs(entry - stopLoss) / entry * 100.0 + totalCostPct
        val rr = if (risk > 0.0) (reward / risk).coerceAtLeast(0.0) else 0.0
        return Result(feePct, slippagePct, totalCostPct, reward, risk, rr)
    }
}
