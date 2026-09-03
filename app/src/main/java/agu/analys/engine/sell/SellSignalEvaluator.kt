package agu.analys.engine.sell

import agu.analys.config.TradingFeeConfig
import agu.analys.model.MarketTick
import agu.analys.model.SellLifecycleState
import agu.analys.model.SellSignalState
import agu.analys.model.TechnicalIndicators
import agu.analys.trading.SpotPosition

object SellSignalEvaluator {
    fun evaluate(
        position: SpotPosition,
        tick: MarketTick?,
        indicators: TechnicalIndicators?,
        tradingFees: TradingFeeConfig
    ): SellSignalState {
        // Guard clause — sama seperti ReadySellBadgeEvaluator, wajib di baris awal
        if (!position.isHolding || position.quantity <= 0.00000001) {
            return SellSignalState(state = SellLifecycleState.NOT_HOLDING)
        }
        
        val currentPrice = tick?.price ?: 0.0
        if (currentPrice <= 0.0) return SellSignalState(state = SellLifecycleState.MONITORING)

        // Rumus profit bersih berikut IDENTIK dengan ReadySellBadgeEvaluator.kt
        val entryPrice = position.entryPrice
        val sellFeeRate = (tradingFees.sellMakerPct / 100.0).coerceAtLeast(0.0)
        val grossSell = position.quantity * currentPrice
        val netSell = grossSell * (1.0 - sellFeeRate)
        val costBasis = position.quantity * (if (entryPrice > 0.0) entryPrice else currentPrice)
        if (costBasis <= 0.0) return SellSignalState(state = SellLifecycleState.MONITORING)

        val netProfitIdr = netSell - costBasis
        val netProfitPct = (netProfitIdr / costBasis) * 100.0

        // Variables dari ReadySellBadgeEvaluator.kt
        val tp1 = position.tp1Price
        val high24h = tick?.high24h ?: 0.0
        val rsi = indicators?.rsi14

        // Prioritas state
        if (position.isTrailingTriggered) {
            return SellSignalState(
                state = SellLifecycleState.TRAILING_TRIGGERED,
                reason = "Trailing stop terpicu",
                netProfitPct = netProfitPct
            )
        }

        if (tp1 > 0.0 && currentPrice >= tp1 && netProfitPct > 0.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Target TP1 tercapai",
                netProfitPct = netProfitPct
            )
        }
        
        if (netProfitPct >= 5.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Profit +5%",
                netProfitPct = netProfitPct
            )
        }
        
        if (high24h > 0.0 && currentPrice >= high24h * 0.98 && netProfitPct >= 1.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Dekat High 24j",
                netProfitPct = netProfitPct
            )
        }
        
        if (netProfitPct >= 2.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Profit +2%",
                netProfitPct = netProfitPct
            )
        }
        
        if (netProfitPct > 0.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Siap profit",
                netProfitPct = netProfitPct
            )
        }
        
        if (rsi != null && !rsi.isNaN() && rsi >= 70.0 && netProfitPct > 0.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "RSI Overbought",
                netProfitPct = netProfitPct
            )
        }

        return SellSignalState(
            state = SellLifecycleState.MONITORING,
            reason = "Memantau...",
            netProfitPct = netProfitPct
        )
    }
}
