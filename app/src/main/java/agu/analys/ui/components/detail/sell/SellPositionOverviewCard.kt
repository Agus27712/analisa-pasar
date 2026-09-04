package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.PositionContext
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun SellPositionOverviewCard(
    context: PositionContext,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    val entry = context.entryPrice
    val current = context.currentPrice ?: (entry ?: 0.0)
    val pnlPct = context.floatingProfitPct
    val pnlNet = context.floatingProfitNet
    val cost = context.costBasis
    val isProfit = (pnlPct ?: 0.0) >= 0.0

    val pnlColor = if (pnlPct == null) TvTextSecondary else if (isProfit) TvGreen else TvRed

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(12.dp))
            .border(1.2.dp, if (pnlPct == null) TvBorder else if (isProfit) TvGreen.copy(alpha = 0.4f) else TvRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Baris Header: Label Posisi & Tipe (Real / Simulasi)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .background(pnlColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .border(1.dp, pnlColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (context.isReal) "● POSISI REAL" else "● POSISI SIMULASI",
                        color = pnlColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = context.symbol,
                    color = TvTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (context.trailingActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .background(TvOrange.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Trailing",
                        tint = TvOrange,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "TRAILING ON",
                        color = TvOrange,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Baris PnL Utama
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Floating Profit / Loss (Net)",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                Text(
                    text = if (pnlPct != null && pnlNet != null) {
                        "${PriceFormatter.formatPercentage(pnlPct, includePlusSign = true)} (${PriceFormatter.formatPrice(pnlNet, quoteAsset = quoteAsset)})"
                    } else {
                        "Entri belum tercatat"
                    },
                    color = pnlColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Modal Beli (Cost Basis)",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                Text(
                    text = if (cost != null) PriceFormatter.formatPrice(cost, quoteAsset = quoteAsset) else "—",
                    color = TvTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Info Detail: Entry vs Current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TvSurface, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Harga Beli (Avg Entry)",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = if (entry != null && entry > 0.0) PriceFormatter.formatPrice(entry, quoteAsset = quoteAsset) else "—",
                    color = TvTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Harga Pasar Saat Ini",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = PriceFormatter.formatPrice(current, quoteAsset = quoteAsset),
                    color = pnlColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Target TP1",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = if (context.tp1 != null && context.tp1 > 0.0) PriceFormatter.formatPrice(context.tp1, quoteAsset = quoteAsset) else "Belum diset",
                    color = TvAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
