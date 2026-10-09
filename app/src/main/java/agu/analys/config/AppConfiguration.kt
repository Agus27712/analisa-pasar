package agu.analys.config

import kotlin.math.abs

enum class AiProvider(val label: String) { GROQ("Groq"), GEMINI("Gemini") }

enum class StrategyMode(val label: String, val badge: String, val shortDesc: String) {
    SCALPING("Scalping Agresif", "⚡ SCALPING", "Cepat (1M–15M) · Trigger mikro"),
    SWING("Swing Trade", "📈 SWING", "Jangka menengah (1H–1D)"),
    OFFICE_DAILY("Intraday", "⚡ INTRADAY", "Open Pagi · Close Malam · Anti Flash Dump");
}

/**
 * Biaya trading **all-in per sisi** (%): trading fee + pajak + ICEx (bila ada).
 *
 * Tokocrypto (efektif 18 Jun 2026, support resmi):
 * - Pair **USDT/crypto**: 0,4044% per sisi (maker = taker; termasuk PPh 0,21% + ICEx 0,0444%).
 * - Pair **IDR**: beli taker 0,2222% / maker 0,1222%; jual taker 0,4322% / maker 0,3322%
 *   (jual termasuk PPh 0,21% + ICEx 0,0222%; beli IDR pajak 0%).
 * - Diskon TKO 25% / VIP tidak di-default; user bisa override di Settings.
 *
 * Indodax: preset lama app (bukan riset ulang di commit ini).
 *
 * Default data class = Indodax-ish legacy agar pemanggilan tanpa argumen tidak diam-diam
 * memakai fee Tokocrypto USDT.
 */
data class TradingFeeConfig(
    val buyMakerPct: Double = 0.11,
    val buyTakerPct: Double = 0.21,
    val sellMakerPct: Double = 0.32,
    val sellTakerPct: Double = 0.42
) {
    companion object {
        /** All-in Tokocrypto pair USDT/crypto — regular, tanpa TKO. */
        fun tokocryptoUsdt(): TradingFeeConfig = TradingFeeConfig(
            buyMakerPct = 0.4044,
            buyTakerPct = 0.4044,
            sellMakerPct = 0.4044,
            sellTakerPct = 0.4044
        )

        /** All-in Tokocrypto pair IDR — regular, tanpa TKO. */
        fun tokocryptoIdr(): TradingFeeConfig = TradingFeeConfig(
            buyMakerPct = 0.1222,
            buyTakerPct = 0.2222,
            sellMakerPct = 0.3322,
            sellTakerPct = 0.4322
        )

        /**
         * Pilih preset Tokocrypto menurut quote asset simbol.
         * USDT/USDC/BUSD/USD → usdt; selain itu (IDR, dll.) → idr.
         */
        fun forTokocrypto(quoteAsset: String): TradingFeeConfig {
            val q = quoteAsset.trim().uppercase()
            return when (q) {
                "USDT", "USDC", "BUSD", "USD" -> tokocryptoUsdt()
                else -> tokocryptoIdr()
            }
        }
    }
}

enum class MarketDataSource(
    val label: String,
    val shortCode: String,
    val defaultQuoteAsset: String,
    val defaultFeeConfig: TradingFeeConfig,
    val description: String
) {
    TOKOCRYPTO(
        label = "Tokocrypto",
        shortCode = "IDR",
        defaultQuoteAsset = "IDR",
        // Default all-in USDT: mayoritas pair scalping + data replay v2/v3.
        // Pair IDR: panggil TradingFeeConfig.forTokocrypto("IDR") / tokocryptoIdr().
        defaultFeeConfig = TradingFeeConfig.tokocryptoUsdt(),
        description = "Data market Tokocrypto (Pair IDR/USDT) dengan real-time REST & WebSocket"
    ),
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

    /** Round-trip fee is buy + sell plus estimated orderbook slippage with accurate multiplicative compounding. */
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

        // Multiplicative Net Reward & Net Risk
        val buyCostFactor = 1.0 + (buyFee + slippagePct) / 100.0
        val sellNetFactor = (1.0 - (sellFee + slippagePct) / 100.0).coerceAtLeast(0.0)
        val netRewardPct = ((takeProfit / entry * sellNetFactor / buyCostFactor) - 1.0) * 100.0
        val netRiskPct = (1.0 - (stopLoss / entry * sellNetFactor / buyCostFactor)) * 100.0

        val rr = if (netRiskPct > 0.0) (netRewardPct / netRiskPct).coerceAtLeast(0.0) else 0.0
        return Result(feePct, slippagePct, totalCostPct, netRewardPct, netRiskPct, rr)
    }
}
