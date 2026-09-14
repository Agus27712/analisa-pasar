package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.database.RealTradeEntity
import agu.analys.trading.TradeLogExporter
import agu.analys.trading.TradeSignalSnapshot
import agu.analys.ui.components.trade.TradeLogDetailDialog
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun LazyListScope.realPortfolioHistorySection(
    realTrades: List<RealTradeEntity>
) {
    if (realTrades.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvCardBackground, RoundedCornerShape(10.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Belum ada riwayat transaksi ter-cache (Mulai terkumpul seiring refresh/trade).",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    } else {
        items(realTrades, key = { it.id }) { trade ->
            RealTradeHistoryItemCard(trade = trade)
        }
    }
}

@Composable
fun RealTradeHistoryItemCard(
    trade: RealTradeEntity,
    modifier: Modifier = Modifier
) {
    var showDetailDialog by remember { mutableStateOf(false) }
    val symbolUpper = trade.symbol.uppercase()
    val sideColor = if (trade.isBuyer) TvGreen else TvRed
    val baseAsset = symbolUpper.replace("IDR", "").replace("_IDR", "").replace("/", "")
    val quoteAsset = "IDR"

    val snapshot = remember(trade.signalSnapshotJson) {
        TradeSignalSnapshot.fromJsonString(trade.signalSnapshotJson)
    }

    if (showDetailDialog) {
        TradeLogDetailDialog(
            tradeId = trade.id,
            symbol = trade.symbol,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            side = trade.side,
            orderType = "LIMIT",
            executionPrice = trade.price,
            quantity = trade.qty,
            totalIdr = trade.amount,
            feeIdr = trade.amount * 0.003,
            timestamp = trade.time,
            isReal = true,
            strategyMode = trade.strategyMode,
            holdingDurationMs = trade.holdingDurationMs,
            entryPrice = trade.entryPrice,
            entryTimestamp = trade.entryTimestamp,
            pnlIdr = trade.pnlIdr,
            pnlPercent = trade.pnlPercent,
            isTrailingUsed = trade.isTrailingUsed,
            trailingPercent = trade.trailingPercent,
            trailingPeakPrice = trade.trailingPeakPrice,
            trailingLockPrice = trade.trailingLockPrice,
            snapshot = snapshot,
            onDismiss = { showDetailDialog = false }
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showDetailDialog = true },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = sideColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = trade.side.uppercase(),
                            color = sideColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$baseAsset / $quoteAsset",
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TvBlue.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = trade.strategyMode.uppercase(),
                            color = TvBlue,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TvGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "REAL",
                            color = TvGreen,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                if (trade.pnlIdr != null && trade.pnlPercent != null) {
                    val isProfit = trade.pnlIdr >= 0
                    Text(
                        text = "${if (isProfit) "+" else ""}${PriceFormatter.formatPrice(kotlin.math.abs(trade.pnlIdr))} (${String.format(Locale.US, "%.2f", trade.pnlPercent)}%)",
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
                        text = PriceFormatter.formatPrice(trade.price),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Jumlah", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = "${PriceFormatter.formatRawDecimal(trade.qty)} $baseAsset",
                        color = TvBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Total Nominal", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = PriceFormatter.formatPrice(trade.amount),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Metadata Row: Duration, Trailing, Snapshot, Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (trade.holdingDurationMs != null && trade.holdingDurationMs > 0L) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvSurfaceVariant)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "⏱ ${TradeLogExporter.formatDuration(trade.holdingDurationMs)}",
                                color = TvTextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (trade.isTrailingUsed) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "🔒 Trailing Lock",
                                color = TvGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (snapshot != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvAmber.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "📊 Sinyal OK",
                                color = TvAmber,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    text = "Detail & AI Log ›",
                    color = TvBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(4.dp))

            val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
            val dateStr = remember(trade.time) { dateFormat.format(Date(trade.time)) }
            Text(
                text = dateStr,
                color = TvTextSecondary.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
    }
}

