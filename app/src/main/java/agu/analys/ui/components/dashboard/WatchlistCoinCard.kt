package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    val aiColor = score?.let(::scoreColor) ?: TvGreen
    val rangePct = tick?.let { if (it.low24h > 0) ((it.high24h - it.low24h) / it.low24h) * 100.0 else 0.0 } ?: 0.0

    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssetBadge(pair.baseAsset, changeColor)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (rank != null) {
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(TvGreen.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("#" + rank, color = TvGreen, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(pair.displayName, color = TvTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(pair.symbol, color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (tick != null) {
                        SmoothPriceText(
                            price = tick.price,
                            color = TvTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    } else {
                        Text("—", color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tick?.let { PriceFormatter.formatPercentage(it.change24h) } ?: "—",
                        color = changeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (score != null) {
                    Text("SKOR $score/100", color = aiColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(7.dp))
                    Text("•", color = TvTextSecondary, fontSize = 8.sp)
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    "Vol ${tick?.let { PriceFormatter.formatPrice(it.volume24h) } ?: "—"}  ·  Range ${String.format("%.2f", rangePct)}%",
                    color = TvTextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            worth?.recommendation?.takeIf { it.isNotBlank() }?.let { rec ->
                Spacer(Modifier.height(4.dp))
                Text(rec, color = aiColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onClick,
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(vertical = 0.dp)
                ) {
                    Icon(Icons.Default.ShowChart, null, Modifier.size(16.dp), tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text("Buka Chart", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                }
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, TvRed.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvRed),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("Hapus", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AssetBadge(asset: String, color: Color) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Text(asset.take(4), color = color, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

fun scoreColor(score: Int): Color = when {
    score >= 75 -> TvGreen
    score >= 50 -> DashboardColors.Amber
    else -> TvRed
}
