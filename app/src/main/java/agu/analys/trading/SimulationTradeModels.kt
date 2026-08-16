package agu.analys.trading

enum class SimulationOrderType(val displayName: String) {
    LIMIT("Limit Order"),
    MARKET("Market Order"),
    STOP_LIMIT("Stop Limit Order")
}

enum class SimulationOrderSide(val displayName: String) {
    BUY("Beli"),
    SELL("Jual")
}

enum class SimulationOrderStatus {
    OPEN,
    FILLED,
    CANCELLED
}

data class SimulationOrder(
    val id: String,
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String = "IDR",
    val side: SimulationOrderSide,
    val type: SimulationOrderType,
    val limitPrice: Double,
    val stopPrice: Double = 0.0,
    val quantity: Double,
    val totalIdr: Double,
    val filledQuantity: Double = 0.0,
    val filledAvgPrice: Double = 0.0,
    val feeIdr: Double = 0.0,
    val status: SimulationOrderStatus = SimulationOrderStatus.OPEN,
    val isStopTriggered: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val filledAt: Long? = null
)

data class SimulationTradeHistoryItem(
    val id: String,
    val orderId: String,
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String = "IDR",
    val side: SimulationOrderSide,
    val type: SimulationOrderType,
    val executionPrice: Double,
    val quantity: Double,
    val totalIdr: Double,
    val feeIdr: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val pnlIdr: Double? = null,
    val pnlPercent: Double? = null
)

data class SimulationWallet(
    val idrBalance: Double = 10_000_000.0,
    val lockedIdr: Double = 0.0,
    val coinBalances: Map<String, Double> = emptyMap(),
    val lockedCoinBalances: Map<String, Double> = emptyMap(),
    val avgBuyPrices: Map<String, Double> = emptyMap()
) {
    fun getAvailableIdr(): Double = (idrBalance - lockedIdr).coerceAtLeast(0.0)
    
    fun getAvailableCoin(baseAsset: String): Double {
        val key = baseAsset.uppercase()
        val total = coinBalances[key] ?: 0.0
        val locked = lockedCoinBalances[key] ?: 0.0
        return (total - locked).coerceAtLeast(0.0)
    }

    fun getTotalCoin(baseAsset: String): Double {
        val key = baseAsset.uppercase()
        return coinBalances[key] ?: 0.0
    }
}

sealed class SimulationOrderResult {
    data class Success(val order: SimulationOrder, val message: String) : SimulationOrderResult()
    data class Error(val message: String) : SimulationOrderResult()
}
