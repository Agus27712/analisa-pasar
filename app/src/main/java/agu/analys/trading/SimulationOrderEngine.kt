package agu.analys.trading

import java.util.UUID

object SimulationOrderEngine {
    // Sesuai aturan PPN/PPh & tarif Indodax Spot (0.11% Maker, 0.21% Buy Taker, 0.42% Sell Taker)
    const val INDODAX_MAKER_FEE_RATE = 0.0011 // 0.11%
    const val INDODAX_BUY_TAKER_FEE_RATE = 0.0021 // 0.21%
    const val INDODAX_SELL_TAKER_FEE_RATE = 0.0042 // 0.42%
    const val INDODAX_TAKER_FEE_RATE = 0.0021 // Alias untuk kompatibilitas backward

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
        feeRate: Double = INDODAX_BUY_TAKER_FEE_RATE
    ): Result<ExecutionResult> {
        val totalIdr = quantity * execPrice
        val feeIdr = totalIdr * feeRate
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
            timestamp = System.currentTimeMillis()
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
            filledAt = System.currentTimeMillis()
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
        feeRate: Double = INDODAX_SELL_TAKER_FEE_RATE
    ): Result<ExecutionResult> {
        val available = wallet.getAvailableCoin(baseKey)
        // Toleransi absolut + relatif agar trailing/full-close tidak menyisakan dust
        val qtyDiff = quantity - available
        val actualQty = when {
            quantity <= 0.0 || available <= 0.0 -> 0.0
            qtyDiff <= 0.0 -> quantity
            qtyDiff < 0.0001 || (available > 0.0 && qtyDiff / available < 1e-4) -> available
            else -> quantity
        }

        if (available < actualQty || actualQty <= 0.0) {
            return Result.failure(
                IllegalArgumentException("Saldo $baseKey tidak cukup. Tersedia: ${wallet.getAvailableCoin(baseKey)}")
            )
        }

        val totalIdr = actualQty * execPrice
        val feeIdr = totalIdr * feeRate
        val netIdr = (totalIdr - feeIdr).coerceAtLeast(0.0)
        val avgBuy = wallet.avgBuyPrices[baseKey] ?: execPrice
        val costBasis = actualQty * avgBuy
        val pnlIdr = totalIdr - costBasis - feeIdr
        val pnlPercent = if (costBasis > 0.0) (pnlIdr / costBasis) * 100.0 else 0.0

        val newCoinBalances = wallet.coinBalances.toMutableMap()
        val newAvgMap = wallet.avgBuyPrices.toMutableMap()
        val remaining = (newCoinBalances[baseKey] ?: 0.0) - actualQty
        // Full close jika sisa dust (absolut atau relatif)
        val isDustRemaining = remaining <= 0.00000001 ||
            ((newCoinBalances[baseKey] ?: 0.0) > 0.0 && remaining / (newCoinBalances[baseKey] ?: 1.0) < 1e-6)
        if (isDustRemaining) {
            newCoinBalances.remove(baseKey)
            newAvgMap.remove(baseKey)
        } else {
            newCoinBalances[baseKey] = remaining
        }

        val updatedWallet = wallet.copy(
            idrBalance = wallet.idrBalance + netIdr,
            coinBalances = newCoinBalances,
            avgBuyPrices = newAvgMap
        )

        val history = SimulationTradeHistoryItem(
            id = UUID.randomUUID().toString(),
            orderId = UUID.randomUUID().toString(),
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            executionPrice = execPrice,
            quantity = actualQty,
            totalIdr = totalIdr,
            feeIdr = feeIdr,
            timestamp = System.currentTimeMillis(),
            pnlIdr = pnlIdr,
            pnlPercent = pnlPercent
        )

        val order = SimulationOrder(
            id = history.orderId,
            symbol = symbol,
            baseAsset = baseKey,
            quoteAsset = quote,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            limitPrice = execPrice,
            quantity = actualQty,
            totalIdr = totalIdr,
            filledQuantity = actualQty,
            filledAvgPrice = execPrice,
            feeIdr = feeIdr,
            status = SimulationOrderStatus.FILLED,
            filledAt = System.currentTimeMillis()
        )

        return Result.success(ExecutionResult(updatedWallet, history, order))
    }

    fun formatMoney(value: Double, quoteAsset: String): String {
        return agu.analys.util.PriceFormatter.formatPrice(value, showSymbol = true, quoteAsset = quoteAsset)
    }
}
