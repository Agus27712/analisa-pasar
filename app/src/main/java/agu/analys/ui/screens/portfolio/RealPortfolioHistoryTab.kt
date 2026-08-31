package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.database.RealTradeEntity
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
    val symbolUpper = trade.symbol.uppercase()
    val sideColor = if (trade.isBuyer) TvGreen else TvRed

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = sideColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = trade.side,
                            color = sideColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = symbolUpper.replace("IDR", "/IDR"),
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
                val dateStr = remember(trade.time) { dateFormat.format(Date(trade.time)) }
                Text(
                    text = dateStr,
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Total: ${PriceFormatter.formatPrice(trade.amount)}",
                    color = TvTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "@ ${PriceFormatter.formatPrice(trade.price)} (${trade.qty})",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
            }
        }
    }
}
