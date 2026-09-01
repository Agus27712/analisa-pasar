package agu.analys.ui.components.dashboard

import androidx.compose.ui.graphics.Color
import agu.analys.config.TradingFeeConfig
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.MarketTick
import agu.analys.ui.theme.DarkAppColors

/**
 * Single source of truth for evaluating "Ready Sell" badges across the application
 * (WatchlistCoinCard, ProactiveProfitSummaryCard, etc.)
 */
data class ReadySellBadge(
    val label: String,
    val color: Color
)

object ReadySellBadgeEvaluator {
    fun computeReadyBadge(
        holding: CoinHoldingStatus?,
        tick: MarketTick?,
        tradingFees: TradingFeeConfig,
        colorOrange: Color = DarkAppColors.orange,
        colorGreen: Color = DarkAppColors.green,
        colorRed: Color = DarkAppColors.red,
        rsi: Double? = null
    ): ReadySellBadge? {
        if (holding == null || !holding.isHolding || holding.quantity <= 0.00000001) return null
        val currentPrice = tick?.price ?: 0.0
        if (currentPrice <= 0.0) return null

        val entryPrice = holding.entryPrice
        val sellFeeRate = (tradingFees.sellMakerPct / 100.0).coerceAtLeast(0.0)
        val grossSell = holding.quantity * currentPrice
        val netSell = grossSell * (1.0 - sellFeeRate)
        val costBasis = holding.quantity * (if (entryPrice > 0.0) entryPrice else currentPrice)
        if (costBasis <= 0.0) return null

        val netProfitIdr = netSell - costBasis
        val netProfitPct = (netProfitIdr / costBasis) * 100.0

        val tp1 = holding.tp1Price
        val high24h = tick?.high24h ?: 0.0

        return when {
            tp1 > 0.0 && currentPrice >= tp1 && netProfitPct > 0.0 ->
                ReadySellBadge("🎯 TARGET TP1", colorOrange)
            netProfitPct >= 5.0 ->
                ReadySellBadge("🔥 PROFIT +5%", colorOrange)
            high24h > 0.0 && currentPrice >= high24h * 0.98 && netProfitPct >= 1.0 ->
                ReadySellBadge("📈 NEAR 24H HIGH", colorOrange)
            netProfitPct >= 2.5 || (holding.isTrailingTriggered && netProfitPct > 0.0) ->
                ReadySellBadge("💰 READY PROFIT", colorGreen)
            rsi != null && rsi.isFinite() && rsi >= 70.0 && netProfitPct > 0.0 ->
                ReadySellBadge("⚠️ RSI OVERBOUGHT", colorRed)
            else -> null
        }
    }
}
