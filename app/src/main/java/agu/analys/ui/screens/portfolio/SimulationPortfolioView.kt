package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.trading.SimulationWallet
import agu.analys.ui.components.simulation.OpenOrderItemCard
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale

/**
 * Tampilan khusus Portofolio Simulasi:
 * Menampilkan ringkasan portofolio virtual, kas Rupiah simulasi,
 * daftar koin virtual yang dimiliki, antrean open orders, serta riwayat eksekusi.
 */
@Composable
fun SimulationPortfolioView(
    wallet: SimulationWallet,
    history: List<SimulationTradeHistoryItem>,
    openOrders: List<SimulationOrder>,
    holdings: List<HoldingItem>,
    totalPortfolioValueIdr: Double,
    totalUnrealizedPnlIdr: Double,
    totalUnrealizedPnlPct: Double,
    selectedTab: PortfolioTab,
    onSelectTab: (PortfolioTab) -> Unit,
    onOpenTopUp: () -> Unit,
    onNavigateToDetail: (TradingPair) -> Unit,
    onNavigateToSimulation: (TradingPair) -> Unit,
    onCancelOrder: (String) -> Unit,
    onCancelAllOrders: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // CARD 1: TOTAL PORTOFOLIO & PNL OVERVIEW
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ESTIMASI TOTAL NILAI",
                            color = TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Row(
                            modifier = Modifier
                                .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PieChart,
                                contentDescription = null,
                                tint = TvBlue,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${holdings.size} Koin",
                                color = TvBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Total Nilai Portofolio
                    Text(
                        text = PriceFormatter.formatPrice(totalPortfolioValueIdr, quoteAsset = "IDR"),
                        color = TvTextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )

                    Spacer(Modifier.height(6.dp))

                    // Total Floating PnL
                    val pnlColor = if (totalUnrealizedPnlIdr >= 0) TvGreen else TvRed
                    val pnlPrefix = if (totalUnrealizedPnlIdr >= 0) "+" else ""
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Floating PnL: ",
                            color = TvTextSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "$pnlPrefix${PriceFormatter.formatPrice(totalUnrealizedPnlIdr, quoteAsset = "IDR")} ($pnlPrefix${String.format(Locale.US, "%.2f", totalUnrealizedPnlPct)}%)",
                            color = pnlColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // CARD 2: SALDO KAS RUPIAH (SISA SALDO BELUM TERPAKAI & SALDO TERKUNCI)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = TvGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "SALDO KAS RUPIAH (IDR)",
                                color = TvBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = onOpenTopUp,
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                        ) {
                            Text("+ Top Up Saldo", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Highlight Sisa Saldo Belum Terpakai
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Sisa Saldo Belum Terpakai (Siap Trading)",
                                color = TvTextSecondary,
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = PriceFormatter.formatPrice(wallet.getAvailableIdr(), quoteAsset = "IDR"),
                                color = TvGreen,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = TvGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Rincian Saldo Terkunci & Total Kas
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Terkunci di Open Order", color = TvTextSecondary, fontSize = 10.sp)
                            Text(
                                PriceFormatter.formatPrice(wallet.lockedIdr, quoteAsset = "IDR"),
                                color = TvAmber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text("Total Kas IDR", color = TvTextSecondary, fontSize = 10.sp)
                            Text(
                                PriceFormatter.formatPrice(wallet.idrBalance, quoteAsset = "IDR"),
                                color = TvTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // TAB SEGMENT: Koin Dimiliki | Riwayat Transaksi | Posisi Spot
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PortfolioTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) TvCardBackground else Color.Transparent)
                            .clickable { onSelectTab(tab) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            color = if (isSelected) TvBlue else TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // KONTEN TAB
        when (selectedTab) {
            PortfolioTab.HOLDINGS -> {
                if (holdings.isEmpty()) {
                    item {
                        EmptyHoldingsCard(
                            onNavigateToSimulation = {
                                val fallbackPair = TradingPair.popularPairsForSource().first()
                                onNavigateToSimulation(fallbackPair)
                            }
                        )
                    }
                } else {
                    items(holdings, key = { it.baseAsset }) { item ->
                        HoldingCoinCard(
                            item = item,
                            onTrade = { onNavigateToSimulation(item.tradingPair) },
                            onViewChart = { onNavigateToDetail(item.tradingPair) }
                        )
                    }
                }
            }

            PortfolioTab.OPEN_ORDERS -> {
                if (openOrders.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvCardBackground, RoundedCornerShape(10.dp))
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Tidak ada antrean order terbuka saat ini.",
                                color = TvTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total ${openOrders.size} Order Aktif",
                                color = TvTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(TvSurfaceVariant)
                                    .clickable { onCancelAllOrders(null) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Batalkan Semua",
                                    color = TvRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    items(openOrders, key = { it.id }) { order ->
                        OpenOrderItemCard(
                            order = order,
                            onCancel = { onCancelOrder(order.id) }
                        )
                    }
                }
            }

            PortfolioTab.HISTORY -> {
                if (history.isEmpty()) {
                    item {
                        EmptyHistoryCard()
                    }
                } else {
                    items(history, key = { it.id }) { trade ->
                        TradeHistoryItemCard(trade = trade)
                    }
                }
            }
        }
    }
}
