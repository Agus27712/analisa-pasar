package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SimulationOpenOrdersList(
    openOrders: List<SimulationOrder>,
    tradeHistory: List<SimulationTradeHistoryItem>,
    currentSymbol: String,
    onCancelOrder: (String) -> Unit,
    onCancelAllOrders: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showAllCoins by remember { mutableStateOf(false) }

    val filteredOpenOrders = remember(openOrders, showAllCoins, currentSymbol) {
        if (showAllCoins) openOrders else openOrders.filter { it.symbol.equals(currentSymbol, true) }
    }

    val filteredHistory = remember(tradeHistory, showAllCoins, currentSymbol) {
        if (showAllCoins) tradeHistory else tradeHistory.filter { it.symbol.equals(currentSymbol, true) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.clickable { selectedTab = 0 },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Open Orders",
                        color = if (selectedTab == 0) Color.White else TvTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = TvTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    if (openOrders.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(TvGreen)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${openOrders.size}",
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.clickable { selectedTab = 1 },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Riwayat",
                        tint = if (selectedTab == 1) Color.White else TvTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Riwayat",
                        color = if (selectedTab == 1) Color.White else TvTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            if (selectedTab == 0 && filteredOpenOrders.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(4.dp))
                        .clickable { onCancelAllOrders(if (showAllCoins) null else currentSymbol) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Batalkan Semua",
                        color = Color(0xFFD1D5DB),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = showAllCoins,
                onCheckedChange = { showAllCoins = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = TvGreen,
                    uncheckedColor = TvTextSecondary,
                    checkmarkColor = Color.Black
                ),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Tampilkan semua pasangan koin",
                color = TvTextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.clickable { showAllCoins = !showAllCoins }
            )
        }

        Spacer(Modifier.height(10.dp))

        if (selectedTab == 0) {
            if (filteredOpenOrders.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.ReceiptLong,
                    message = "Tidak ada order terbuka saat ini"
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredOpenOrders.forEach { order ->
                        OpenOrderItemCard(
                            order = order,
                            onCancel = { onCancelOrder(order.id) }
                        )
                    }
                }
            }
        } else {
            if (filteredHistory.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.History,
                    message = "Belum ada riwayat transaksi simulasi"
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredHistory.forEach { history ->
                        TradeHistoryItemCard(history = history)
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenOrderItemCard(
    order: SimulationOrder,
    onCancel: () -> Unit
) {
    val isBuy = order.side == SimulationOrderSide.BUY
    val quote = order.quoteAsset.ifBlank { "IDR" }
    val timeFormat = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162032)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isBuy) TvGreen.copy(alpha = 0.2f) else TvRed.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isBuy) "Beli" else "Jual",
                            color = if (isBuy) TvGreen else TvRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${order.baseAsset}/${quote}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "(${order.type.displayName})",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .clickable { onCancel() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "Batal",
                        color = Color(0xFFFCA5A5),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Harga Order", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = PriceFormatter.formatPrice(order.limitPrice, quoteAsset = quote),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (order.type == SimulationOrderType.STOP_LIMIT) {
                        Text(
                            text = "Stop: ${PriceFormatter.formatPrice(order.stopPrice, quoteAsset = quote)}",
                            color = TvOrange,
                            fontSize = 10.sp
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Jumlah", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = "${PriceFormatter.formatQuantity(order.quantity)} ${order.baseAsset}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Total ($quote)", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = PriceFormatter.formatPrice(order.totalIdr, quoteAsset = quote),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFormat.format(Date(order.createdAt)),
                    color = TvTextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                Text(
                    text = if (order.type == SimulationOrderType.STOP_LIMIT && !order.isStopTriggered) "Menunggu Trigger" else "Menunggu Match",
                    color = if (order.type == SimulationOrderType.STOP_LIMIT && !order.isStopTriggered) TvOrange else TvBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun TradeHistoryItemCard(history: SimulationTradeHistoryItem) {
    val isBuy = history.side == SimulationOrderSide.BUY
    val quote = history.quoteAsset.ifBlank { "IDR" }
    val timeFormat = remember { SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162032)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isBuy) TvGreen.copy(alpha = 0.2f) else TvRed.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isBuy) "Beli" else "Jual",
                            color = if (isBuy) TvGreen else TvRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${history.baseAsset}/${quote}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "(${history.type.displayName})",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                }

                if (history.pnlIdr != null && history.pnlPercent != null) {
                    val isProfit = history.pnlIdr >= 0
                    Text(
                        text = "${if (isProfit) "+" else ""}${PriceFormatter.formatPrice(kotlin.math.abs(history.pnlIdr), quoteAsset = quote)} (${String.format("%.2f", history.pnlPercent)}%)",
                        color = if (isProfit) TvGreen else TvRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Harga Eksekusi", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = PriceFormatter.formatPrice(history.executionPrice, quoteAsset = quote),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Jumlah Koin", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = "${PriceFormatter.formatQuantity(history.quantity)} ${history.baseAsset}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Total Eksekusi", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = PriceFormatter.formatPrice(history.totalIdr, quoteAsset = quote),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = timeFormat.format(Date(history.timestamp)),
                    color = TvTextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                Text(
                    text = "Fee: ${PriceFormatter.formatPrice(history.feeIdr, quoteAsset = quote)}",
                    color = TvTextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyStateView(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF475569),
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}
