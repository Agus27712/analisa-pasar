package agu.analys.ui.screens.portfolio

import agu.analys.model.TradingPair

enum class PortfolioTab(val title: String) {
    HOLDINGS("Koin Dimiliki"),
    HISTORY("Riwayat Transaksi"),
    SPOT_TRACKER("Catatan Spot")
}

data class HoldingItem(
    val baseAsset: String,
    val quantity: Double,
    val avgBuyPrice: Double,
    val currentPrice: Double,
    val totalValueIdr: Double,
    val pnlIdr: Double,
    val pnlPercent: Double,
    val tradingPair: TradingPair
)
