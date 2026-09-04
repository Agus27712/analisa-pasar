package agu.analys.engine.sell

import agu.analys.config.TradingFeeConfig
import agu.analys.model.MarketTick
import agu.analys.model.PositionContext
import agu.analys.model.SellLifecycleState
import agu.analys.model.SellSignalState
import agu.analys.model.TechnicalIndicators
import agu.analys.trading.SpotPosition

object SellSignalEvaluator {
    fun evaluate(
        context: PositionContext,
        indicators: TechnicalIndicators? = null,
        tradingFees: TradingFeeConfig = TradingFeeConfig(),
        high24h: Double = 0.0
    ): SellSignalState {
        if (!context.hasPosition || (context.quantity ?: 0.0) <= 0.00000001) {
            return SellSignalState(state = SellLifecycleState.NOT_HOLDING)
        }

        val currentPrice = context.currentPrice ?: 0.0
        if (currentPrice <= 0.0) return SellSignalState(state = SellLifecycleState.MONITORING)

        val quantity = context.quantity ?: 0.0
        val entryPrice = context.entryPrice
        val hasCostBasis = entryPrice != null && entryPrice > 0.0
        val costBasis = if (hasCostBasis) quantity * entryPrice!! else 0.0

        val sellFeeRate = (tradingFees.sellMakerPct / 100.0).coerceAtLeast(0.0)
        val grossSell = quantity * currentPrice
        val netSell = grossSell * (1.0 - sellFeeRate)

        val netProfitPct = if (hasCostBasis && costBasis > 0.0) {
            val netProfitIdr = netSell - costBasis
            (netProfitIdr / costBasis) * 100.0
        } else {
            0.0
        }

        val tp1 = context.tp1 ?: 0.0
        val tp2 = context.tp2 ?: 0.0
        val sl = context.stopLoss ?: if (hasCostBasis && entryPrice != null && entryPrice > 0.0) entryPrice * 0.99 else 0.0
        val rsi = indicators?.rsi14

        // 1. Stop Loss Hit (Harga Beli - 1%)
        if (sl > 0.0 && currentPrice <= sl) {
            return SellSignalState(
                state = SellLifecycleState.STOP_LOSS_HIT,
                reason = "Stop loss terpicu",
                netProfitPct = netProfitPct
            )
        }

        // 2. Trailing Stop Triggered
        if (context.isTrailingTriggered) {
            return SellSignalState(
                state = SellLifecycleState.TRAILING_TRIGGERED,
                reason = "Trailing stop terpicu",
                netProfitPct = netProfitPct
            )
        }

        // 3. Take Profit 2 Target Reached
        if (tp2 > 0.0 && currentPrice >= tp2 && (!hasCostBasis || netProfitPct > 0.0)) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Target TP2 tercapai",
                netProfitPct = netProfitPct
            )
        }

        // 4. Take Profit 1 Target Reached
        if (tp1 > 0.0 && currentPrice >= tp1 && (!hasCostBasis || netProfitPct > 0.0)) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Target TP1 tercapai",
                netProfitPct = netProfitPct
            )
        }

        // 5. Approaching TP1 / TP2 Target
        if (tp1 > 0.0 && currentPrice >= tp1 * 0.98 && currentPrice < tp1) {
            return SellSignalState(
                state = SellLifecycleState.APPROACHING_TARGET,
                reason = "Mendekati target TP1",
                netProfitPct = netProfitPct
            )
        } else if (tp2 > 0.0 && tp1 <= 0.0 && currentPrice >= tp2 * 0.98 && currentPrice < tp2) {
            return SellSignalState(
                state = SellLifecycleState.APPROACHING_TARGET,
                reason = "Mendekati target TP2",
                netProfitPct = netProfitPct
            )
        }

        // 5. RSI Overbought (Evaluated before generic profit levels so it remains fully reachable)
        if (rsi != null && !rsi.isNaN() && rsi.isFinite() && rsi >= 70.0 && (!hasCostBasis || netProfitPct > 0.0)) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "RSI Overbought",
                netProfitPct = netProfitPct
            )
        }

        // 6. Near 24h High with positive profit
        if (high24h > 0.0 && currentPrice >= high24h * 0.98 && hasCostBasis && netProfitPct >= 1.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Dekat High 24j",
                netProfitPct = netProfitPct
            )
        }

        // 7. Significant Net Profit Levels
        if (hasCostBasis && netProfitPct >= 5.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Profit +5%",
                netProfitPct = netProfitPct
            )
        }

        if (hasCostBasis && netProfitPct >= 2.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Profit +2%",
                netProfitPct = netProfitPct
            )
        }

        if (hasCostBasis && netProfitPct > 0.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Siap profit",
                netProfitPct = netProfitPct
            )
        }

        return SellSignalState(
            state = SellLifecycleState.MONITORING,
            reason = "Memantau...",
            netProfitPct = netProfitPct
        )
    }

    /**
     * Overload convenience yang mendelegasikan secara langsung ke Single Source of Truth [evaluate]
     */
    fun evaluate(
        position: SpotPosition,
        tick: MarketTick?,
        indicators: TechnicalIndicators?,
        tradingFees: TradingFeeConfig
    ): SellSignalState {
        val context = PositionContext.create(
            symbol = tick?.symbol ?: "",
            spotPosition = position,
            holdingStatus = null,
            currentPrice = tick?.price ?: 0.0,
            fees = tradingFees
        )
        return evaluate(
            context = context,
            indicators = indicators,
            tradingFees = tradingFees,
            high24h = tick?.high24h ?: 0.0
        )
    }
}
