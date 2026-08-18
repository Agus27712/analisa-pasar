package agu.analys.config

import kotlin.math.abs

enum class AiProvider(val label: String) { GROQ("Groq"), GEMINI("Gemini") }

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
    ),
    TOKOCRYPTO(
        label = "Tokocrypto",
        shortCode = "TOKO",
        defaultQuoteAsset = "USDT",
        defaultFeeConfig = TradingFeeConfig(
            buyMakerPct = 0.10,
            buyTakerPct = 0.10,
            sellMakerPct = 0.10,
            sellTakerPct = 0.10
        ),
        description = "Engine Binance Cloud (Pair USDT & BIDR) dengan likuiditas tinggi & pergerakan rapat."
    )
}
enum class MarketDataTransport(val label: String) { REST("REST"), WEBSOCKET("WebSocket") }

object MarketDataConfiguration {
    val transports = listOf(MarketDataTransport.REST, MarketDataTransport.WEBSOCKET)
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
