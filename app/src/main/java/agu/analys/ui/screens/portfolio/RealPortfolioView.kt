package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.ui.theme.*

/**
 * Tampilan khusus Portofolio Real (Indodax):
 * Memisahkan secara total data riil dari data simulasi.
 * Mengelola kartu saldo live, tab ber-Navigasi (Aset, Antrean, Riwayat), antrean order aktif dengan tombol cancel, riwayat transaksi, dan shortcut trade.
 */
@Composable
fun RealPortfolioView(
    isPinUnlocked: Boolean,
    realBalance: Map<String, Double>,
    realFreeBalance: Map<String, Double> = emptyMap(),
    realLockedBalance: Map<String, Double> = emptyMap(),
    realOpenOrders: List<RealOpenOrderEntity> = emptyList(),
    realTrades: List<RealTradeEntity> = emptyList(),
    realAvgBuyPrices: Map<String, Double> = emptyMap(),
    isFetchingRealBalance: Boolean,
    dashboardTicks: Map<String, MarketTick>,
    currentTick: MarketTick?,
    realTradeStatus: String? = null,
    onUnlockPin: () -> Unit,
    onRefreshRealBalance: () -> Unit,
    onCancelRealOrder: (String, String) -> Unit = { _, _ -> },
    onNavigateToDetail: (TradingPair) -> Unit,
    onSelectPair: (TradingPair) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedRealTab by remember { mutableStateOf(RealPortfolioTab.ASSETS) }

    val realIdr = realBalance["idr"] ?: 0.0
    val freeIdr = realFreeBalance["idr"] ?: realIdr
    val lockedIdr = realLockedBalance["idr"] ?: 0.0

    val realCoinItemsList = remember(realBalance, realFreeBalance, realLockedBalance, realAvgBuyPrices, dashboardTicks, currentTick) {
        realBalance.filter { it.key != "idr" && it.value > 0.00000001 }.entries.map { (coin, qty) ->
            val coinUpper = coin.uppercase()
            val coinLower = coin.lowercase()
            val symbol = "${coinUpper}IDR"
            val price = when {
                symbol.equals(currentTick?.symbol, ignoreCase = true) -> currentTick?.price ?: 0.0
                dashboardTicks.containsKey(symbol) -> dashboardTicks[symbol]?.price ?: 0.0
                dashboardTicks.containsKey("${coinLower}_idr") -> dashboardTicks["${coinLower}_idr"]?.price ?: 0.0
                dashboardTicks.containsKey(coinUpper) -> dashboardTicks[coinUpper]?.price ?: 0.0
                else -> 0.0
            }
            val avgPrice = realAvgBuyPrices[coin] ?: 0.0
            val effectivePrice = if (price > 0.0) price else avgPrice
            val estVal = qty * effectivePrice
            val pnlIdr = if (avgPrice > 0.0) (effectivePrice - avgPrice) * qty else 0.0
            val pnlPct = if (avgPrice > 0.0) ((effectivePrice - avgPrice) / avgPrice) * 100.0 else 0.0

            val freeQty = realFreeBalance[coinLower] ?: qty
            val lockedQty = realLockedBalance[coinLower] ?: 0.0

            Pair(coinUpper, Triple(qty, freeQty, lockedQty)) to Pair(estVal, Triple(price, avgPrice, Pair(pnlIdr, pnlPct)))
        }.sortedByDescending { it.second.first }
    }
    
    val estTotalCryptoIdr = remember(realCoinItemsList) { realCoinItemsList.sumOf { it.second.first } }
    val totalRealPortfolioIdr = realIdr + estTotalCryptoIdr

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isPinUnlocked) {
            item {
                RealPortfolioLockedCard(onUnlockPin = onUnlockPin)
            }
        } else {
            // STATUS BANNER (IF ANY)
            if (!realTradeStatus.isNullOrBlank()) {
                item {
                    RealPortfolioStatusBanner(status = realTradeStatus)
                }
            }

            // UNLOCKED REAL PORTFOLIO SUMMARY CARD
            item {
                RealPortfolioSummaryCard(
                    totalRealPortfolioIdr = totalRealPortfolioIdr,
                    realIdr = realIdr,
                    freeIdr = freeIdr,
                    lockedIdr = lockedIdr,
                    estTotalCryptoIdr = estTotalCryptoIdr,
                    isFetchingRealBalance = isFetchingRealBalance,
                    onRefreshRealBalance = onRefreshRealBalance
                )
            }

            // TAB SEGMENT: Aset | Antrean | Riwayat
            item {
                RealPortfolioTabSelector(
                    selectedTab = selectedRealTab,
                    onTabSelected = { selectedRealTab = it },
                    assetsCount = realCoinItemsList.size,
                    ordersCount = realOpenOrders.size,
                    tradesCount = realTrades.size
                )
            }

            // KONTEN TAB REAL PORTOFOLIO
            when (selectedRealTab) {
                RealPortfolioTab.ASSETS -> {
                    if (realCoinItemsList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TvCardBackground, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                    .padding(20.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                androidx.compose.material3.Text(
                                    "Belum ada aset koin kripto terdeteksi di akun Indodax.",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        items(realCoinItemsList, key = { it.first.first }) { itemData ->
                            val (coinUpper, qtyTriple) = itemData.first
                            val (qty, freeQty, lockedQty) = qtyTriple
                            val (estVal, details) = itemData.second
                            val (price, avgPrice, pnlPair) = details
                            val (pnlIdr, pnlPct) = pnlPair

                            RealPortfolioAssetItem(
                                coinUpper = coinUpper,
                                qty = qty,
                                freeQty = freeQty,
                                lockedQty = lockedQty,
                                estVal = estVal,
                                price = price,
                                avgPrice = avgPrice,
                                pnlIdr = pnlIdr,
                                pnlPct = pnlPct,
                                onSelectPair = onSelectPair,
                                onNavigateToDetail = onNavigateToDetail
                            )
                        }
                    }
                }

                RealPortfolioTab.OPEN_ORDERS -> {
                    realPortfolioOpenOrdersSection(
                        realOpenOrders = realOpenOrders,
                        onCancelRealOrder = onCancelRealOrder
                    )
                }

                RealPortfolioTab.HISTORY -> {
                    realPortfolioHistorySection(
                        realTrades = realTrades
                    )
                }
            }

            // TOP-UP & WITHDRAW NOTICE CARD FOR REAL MODE
            item {
                RealPortfolioNoticeCard()
            }
        }
    }
}
