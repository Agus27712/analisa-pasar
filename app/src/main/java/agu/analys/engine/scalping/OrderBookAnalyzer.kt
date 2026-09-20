package agu.analys.engine.scalping

import agu.analys.model.OrderBookItem

object OrderBookAnalyzer {
    fun calculateBuyPressure(bids: List<OrderBookItem>, asks: List<OrderBookItem>, levels: Int = 10): Double {
        val topBids = bids.take(levels).sumOf { it.amount }
        val topAsks = asks.take(levels).sumOf { it.amount }

        // An entirely empty order book contains no directional information.
        // Keep it neutral instead of treating it as bullish because topAsks == 0.
        if (topBids <= 0.0 && topAsks <= 0.0) return 1.0
        if (topAsks <= 0.0) return 1.5
        if (topBids <= 0.0) return 0.5
        return topBids / topAsks
    }
}
