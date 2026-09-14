package agu.analys.trading

import java.util.UUID

object SimulationOrderEngine {
    const val INDODAX_MAKER_FEE_RATE = 0.001 // 0.1%
    const val INDODAX_TAKER_FEE_RATE = 0.003 // 0.3%

    data class ExecutionResult(
        val updatedWallet: SimulationWallet,
        val historyItem: SimulationTradeHistoryItem,
        val completedOrder: SimulationOrder
    )

    fun executeMarketBuy(
        wallet: SimulationWallet,
        symbol: String,
        baseKey: String,
        quote: String,
        execPrice: Double,
        quantity: Double,
        strategyMode: String = "SCALPING",
        signalSnapshot: TradeSignalSnapshot? = null
    ): Result<ExecutionResult> {
        val totalIdr = quantity * execPrice
        val feeIdr = totalIdr * INDODAX_TAKER_FEE_RATE
        val requiredIdr = totalIdr + feeIdr

        if (wallet.getAvailableIdr() < requiredIdr) {
            return Result.failure(
                IllegalArgumentException(
                    "Saldo $quote tidak cukup. Dibutuhkan ${formatMoney(requiredIdr, quote)}, saldo ${formatMoney(wallet.getAvailableIdr(), quote)}."
                )
            )
        }

        val newCoinBalances = wallet.coinBalances.toMutableMap()
        val currentCoin = newCoinBalances[baseKey] ?: 0.0
        val currentAvg = wallet.avgBuyPrices[baseKey] ?: 0.0
        val newTotalCoin = currentCoin + quantity
        val newAvgPrice = if (newTotalCoin > 0.0) {
            ((currentCoin * currentAvg) + totalIdr) / newTotalCoin
        } else execPrice

        newCoinBalances[baseKey] = newTotalCoin
        val newAvgMap = wallet.avgBuyPrices.toMutableMap().apply { put(baseKey, newAvgPrice) }

        val updatedWallet = wallet.copy(
            idrBalance = (wallet.idrBalance - requiredIdr).coerceAtLeast(0.0),
            coinBalances = newCoinBalances,
            avgBuyPrices = newAvgMap
        )

        val now = System.currentTimeMillis()
        val history = SimulationTradeHistoryItem(
            id = UUID.randomUUID().toString(),
            orderId = UUID.randomUUID().toString(),
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.BUY,
            type = SimulationOrderType.MARKET,
            executionPrice = execPrice,
            quantity = quantity,
            totalIdr = totalIdr,
            feeIdr = feeIdr,
            timestamp = now,
            strategyMode = strategyMode,
            holdingDurationMs = 0L,
            entryPrice = execPrice,
            entryTimestamp = now,
            signalSnapshot = signalSnapshot
        )

        val order = SimulationOrder(
            id = history.orderId,
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.BUY,
            type = SimulationOrderType.MARKET,
            limitPrice = execPrice,
            quantity = quantity,
            totalIdr = totalIdr,
            filledQuantity = quantity,
            filledAvgPrice = execPrice,
            feeIdr = feeIdr,
            status = SimulationOrderStatus.FILLED,
            filledAt = now,
            strategyMode = strategyMode,
            entryPrice = execPrice,
            entryTimestamp = now,
            signalSnapshot = signalSnapshot
        )

        return Result.success(ExecutionResult(updatedWallet, history, order))
    }

    fun executeMarketSell(
        wallet: SimulationWallet,
        symbol: String,
        baseKey: String,
        quote: String,
        execPrice: Double,
        quantity: Double,
        strategyMode: String = "SCALPING",
        holdingDurationMs: Long? = null,
        entryPrice: Double? = null,
        entryTimestamp: Long? = null,
        isTrailingUsed: Boolean = false,
        trailingPercent: Double? = null,
        trailingPeakPrice: Double? = null,
        trailingLockPrice: Double? = null,
        signalSnapshot: TradeSignalSnapshot? = null
    ): Result<ExecutionResult> {
        val available = wallet.getAvailableCoin(baseKey)
        val actualQty = if (quantity > available && (quantity - available) < 0.0001) available else quantity

        if (available < actualQty) {
            return Result.failure(
                IllegalArgumentException("Saldo $baseKey tidak cukup. Tersedia: ${wallet.getAvailableCoin(baseKey)}")
            )
        }

        val totalIdr = actualQty * execPrice
        val feeIdr = totalIdr * INDODAX_TAKER_FEE_RATE
        val netIdr = (totalIdr - feeIdr).coerceAtLeast(0.0)
        val effectiveEntry = entryPrice ?: (wallet.avgBuyPrices[baseKey] ?: execPrice)
        val costBasis = actualQty * effectiveEntry
        val pnlIdr = totalIdr - costBasis - feeIdr
        val pnlPercent = if (costBasis > 0.0) (pnlIdr / costBasis) * 100.0 else 0.0

        val newCoinBalances = wallet.coinBalances.toMutableMap()
        val remaining = (newCoinBalances[baseKey] ?: 0.0) - actualQty
        if (remaining <= 0.00000001) {
            newCoinBalances.remove(baseKey)
        } else {
            newCoinBalances[baseKey] = remaining
        }

        val updatedWallet = wallet.copy(
            idrBalance = wallet.idrBalance + netIdr,
            coinBalances = newCoinBalances
        )

        val now = System.currentTimeMillis()
        val history = SimulationTradeHistoryItem(
            id = UUID.randomUUID().toString(),
            orderId = UUID.randomUUID().toString(),
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            executionPrice = execPrice,
            quantity = quantity,
            totalIdr = totalIdr,
            feeIdr = feeIdr,
            timestamp = now,
            pnlIdr = pnlIdr,
            pnlPercent = pnlPercent,
            strategyMode = strategyMode,
            holdingDurationMs = holdingDurationMs,
            entryPrice = effectiveEntry,
            entryTimestamp = entryTimestamp,
            isTrailingUsed = isTrailingUsed,
            trailingPercent = trailingPercent,
            trailingPeakPrice = trailingPeakPrice,
            trailingLockPrice = trailingLockPrice,
            signalSnapshot = signalSnapshot
        )

        val order = SimulationOrder(
            id = history.orderId,
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            limitPrice = execPrice,
            quantity = quantity,
            totalIdr = totalIdr,
            filledQuantity = quantity,
            filledAvgPrice = execPrice,
            feeIdr = feeIdr,
            status = SimulationOrderStatus.FILLED,
            filledAt = now,
            strategyMode = strategyMode,
            entryPrice = effectiveEntry,
            entryTimestamp = entryTimestamp,
            isTrailingUsed = isTrailingUsed,
            trailingPercent = trailingPercent,
            trailingPeakPrice = trailingPeakPrice,
            trailingLockPrice = trailingLockPrice,
            signalSnapshot = signalSnapshot
        )

        return Result.success(ExecutionResult(updatedWallet, history, order))
    }

    fun formatMoney(value: Double, quoteAsset: String): String {
        val isUsdt = quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)
        return if (isUsdt) {
            String.format("%.4f %s", value, quoteAsset.uppercase())
        } else {
            "Rp " + String.format("%,.0f", value).replace(",", ".")
        }
    }
}
