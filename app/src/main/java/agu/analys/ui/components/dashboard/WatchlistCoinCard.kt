package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.animation.AnimatedMetricText
import agu.analys.ui.animation.SmoothPriceText
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

@Composable
fun WatchlistCoinCard(
    pair: TradingPair,
    tick: MarketTick?,
    worth: WorthCoinInfo?,
    rank: Int?,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val change = tick?.change24h ?: 0.0
    val changeColor = when {
        change.isNaN() -> TvTextSecondary
        change > 0 -> TvGreen
        change < 0 -> TvRed
        else -> TvTextSecondary
    }
    val score = worth?.worthScore
    val aiColor = score?.let(::scoreColor) ?: TvTextSecondary
    val rangePct = tick?.let {
        if (it.low24h > 0) ((it.high24h - it.low24h) / it.low24h) * 100.0 else 0.0
    } ?: 0.0

    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                rank?.let {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
                            .background(DashboardColors.AccentBlue.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("#$it", color = DashboardColors.AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(10.dp))
                }

                AssetBadge(pair.baseAsset, changeColor)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pair.displayName, color = TvTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Text(pair.symbol, color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (tick != null) {
                        SmoothPriceText(
                            price = tick.price,
                            color = TvTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    } else {
                        Text("—", color = TvTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(3.dp))
                    AnimatedMetricText(
                        value = tick?.let { formatSignedPercentage(it.change24h) } ?: "—",
                        color = changeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (score != null) {
                    AnimatedMetricText(
                        value = "SKOR $score/100",
                        color = aiColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("•", color = TvTextSecondary, fontSize = 9.sp)
                    Spacer(Modifier.width(9.dp))
                }
                AnimatedMetricText(
                    value = "Vol ${tick?.let { PriceFormatter.formatPrice(it.volume24h) } ?: "—"}  ·  Range ${String.format("%.2f", rangePct)}%",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            worth?.recommendation?.takeIf { it.isNotBlank() }?.let { rec ->
                Spacer(Modifier.height(8.dp))
                AnimatedMetricText(
                    value = rec,
                    color = aiColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(13.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onClick,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DashboardColors.AccentBlue),
                    border = BorderStroke(1.dp, DashboardColors.AccentBlue),
                    shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.ShowChart, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Buka Chart", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.width(82.dp).height(48.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, TvRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvRed),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hapus", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AssetBadge(asset: String, color: Color) {
    Box(
        Modifier.size(58.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center
        ) {
            Text(asset.take(1), color = color, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

private fun formatSignedPercentage(value: Double): String {
    val formatted = PriceFormatter.formatPercentage(value)
    return when {
        value > 0 -> "▲ $formatted"
        value < 0 -> "▼ $formatted"
        else -> formatted
    }
}

fun scoreColor(score: Int): Color = when {
    score >= 75 -> TvGreen
    score >= 50 -> DashboardColors.Gold
    else -> TvRed
}
