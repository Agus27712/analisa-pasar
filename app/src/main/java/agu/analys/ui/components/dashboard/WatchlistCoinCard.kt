package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Watchlist presentation only. Market values always come from live model data. */
@Composable
fun WatchlistCoinCard(
    pair: TradingPair,
    tick: MarketTick?,
    worth: WorthCoinInfo?,
    rank: Int?,
    isAuto: Boolean,
    isScalping: Boolean,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val change = tick?.change24h ?: Double.NaN
    val volume = tick?.volume24h ?: 0.0
    val changeColor = when {
        change > 0 -> TvGreen
        change < 0 -> TvRed
        else -> TvTextSecondary
    }
    val activity = when {
        volume >= 100_000_000_000 -> ActivityLevel.HIGH
        volume >= 1_000_000_000 -> ActivityLevel.MEDIUM
        volume > 0 -> ActivityLevel.LOW
        else -> ActivityLevel.UNKNOWN
    }
    val rankText = rank?.let { String.format("%02d", it) } ?: "··"
    val scoreText = worth?.worthScore?.let { "$it/100" } ?: "—"

    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rankText, color = DashboardColors.AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(30.dp))
                AssetAvatar(baseAsset = pair.baseAsset, iconUrl = pair.iconUrl, size = 38.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text("${pair.baseAsset}/${pair.quoteAsset}", color = TvTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    ActivityChip(activity)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (tick != null) SmoothPriceText(tick.price, TvTextPrimary, 16.sp, FontWeight.ExtraBold)
                    else Text("—", color = TvTextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (change.isFinite()) AnimatedMetricText(PriceFormatter.formatPercentage(change), changeColor, 11.sp, FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MetricLine("Score", scoreText)
                    Spacer(Modifier.height(4.dp))
                    MetricLine("Volume 24H", if (volume > 0) PriceFormatter.formatPrice(volume) else "—")
                    if (tick != null && tick.high24h > 0 && tick.low24h > 0) {
                        Spacer(Modifier.height(4.dp))
                        MetricLine("Range 24H", "${PriceFormatter.formatPrice(tick.low24h)} – ${PriceFormatter.formatPrice(tick.high24h)}")
                    }
                }
                Spacer(Modifier.width(8.dp))
                MiniSparkline(tick = tick, modifier = Modifier.width(76.dp).height(36.dp), lineColor = changeColor)
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("Lihat detail", color = TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ShowChart, null, modifier = Modifier.size(14.dp), tint = TvTextSecondary)
                if (!isAuto) {
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.DeleteOutline, "Hapus", tint = TvRed, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private enum class ActivityLevel { HIGH, MEDIUM, LOW, UNKNOWN }

@Composable
private fun ActivityChip(activity: ActivityLevel) {
    val (label, background, foreground) = when (activity) {
        ActivityLevel.HIGH -> Triple("Aktivitas tinggi", Color(0xFF123D2A), TvGreen)
        ActivityLevel.MEDIUM -> Triple("Aktivitas sedang", Color(0xFF3D3212), Color(0xFFFFC107))
        ActivityLevel.LOW -> Triple("Aktivitas rendah", Color(0xFF2A3038), TvTextSecondary)
        ActivityLevel.UNKNOWN -> Triple("Aktivitas —", Color(0xFF2A3038), TvTextSecondary)
    }
    Box(Modifier.background(background, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(label, color = foreground, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TvTextSecondary, fontSize = 11.sp, modifier = Modifier.width(72.dp))
        Text(value, color = TvTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
