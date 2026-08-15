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
import kotlin.math.abs

/**
 * Card watchlist — layout mockup:
 * rank · pair · harga · change · badge aktivitas · volume/momentum bars · Buka Chart
 * Warna aktivitas: hijau tinggi · kuning sedang · abu rendah (bukan selalu merah).
 */
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
    val volumeScore = when {
        volume >= 100_000_000_000 -> 10
        volume >= 10_000_000_000 -> 8
        volume >= 1_000_000_000 -> 6
        volume > 0 -> 4
        else -> 0
    }
    val momentumScore = if (change.isFinite()) {
        ((abs(change).coerceAtMost(10.0) / 10.0) * 10.0).toInt().coerceIn(0, 10)
    } else 0

    // Legenda mockup: tinggi / sedang / rendah
    val activityLevel = when {
        volumeScore >= 8 && momentumScore >= 6 -> ActivityLevel.HIGH
        volumeScore >= 6 || momentumScore >= 5 -> ActivityLevel.MEDIUM
        volumeScore > 0 || momentumScore > 0 -> ActivityLevel.LOW
        else -> ActivityLevel.LOW
    }
    val statusLabel = when (activityLevel) {
        ActivityLevel.HIGH -> "Aktivitas tinggi"
        ActivityLevel.MEDIUM -> "Aktivitas sedang"
        ActivityLevel.LOW -> "Aktivitas rendah"
    }
    val statusBg = when (activityLevel) {
        ActivityLevel.HIGH -> Color(0xFF123D2A)
        ActivityLevel.MEDIUM -> Color(0xFF3D3212)
        ActivityLevel.LOW -> Color(0xFF2A3038)
    }
    val statusFg = when (activityLevel) {
        ActivityLevel.HIGH -> TvGreen
        ActivityLevel.MEDIUM -> Color(0xFFFFC107)
        ActivityLevel.LOW -> TvTextSecondary
    }
    val barColor = statusFg
    val changeColor = when {
        change > 0 -> TvGreen
        change < 0 -> TvRed
        else -> TvTextSecondary
    }
    val rankText = rank?.let { String.format("%02d", it) } ?: "··"

    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rankText,
                    color = DashboardColors.AccentBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.width(28.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "${pair.baseAsset}/${pair.quoteAsset}",
                        color = TvTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .background(statusBg, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(statusLabel, color = statusFg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (tick != null) {
                        SmoothPriceText(tick.price, TvTextPrimary, 15.sp, FontWeight.ExtraBold)
                    } else {
                        Text("—", color = TvTextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    if (change.isFinite()) {
                        AnimatedMetricText(
                            PriceFormatter.formatPercentage(change),
                            changeColor,
                            12.sp,
                            FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (isScalping) {
                ScoreLine("Volume", volumeScore, barColor)
                Spacer(Modifier.height(4.dp))
                ScoreLine("Momentum", momentumScore, barColor)
            } else {
                val trend = when {
                    change >= 5 -> 9
                    change >= 1 -> 7
                    change >= 0 -> 6
                    change >= -3 -> 4
                    change.isFinite() -> 2
                    else -> 0
                }
                val structure = ((worth?.worthScore ?: 0) / 10).coerceIn(0, 10)
                ScoreLine("Trend", trend, barColor)
                Spacer(Modifier.height(4.dp))
                ScoreLine("Structure", structure, barColor)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onClick,
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DashboardColors.Border),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvTextPrimary)
                ) {
                    Text("Buka Chart", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ShowChart, null, Modifier.size(16.dp))
                }
                if (!isAuto) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.DeleteOutline, "Hapus", tint = TvRed, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private enum class ActivityLevel { HIGH, MEDIUM, LOW }

@Composable
private fun ScoreLine(label: String, score: Int, filled: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TvTextSecondary, fontSize = 11.sp, modifier = Modifier.width(72.dp))
        Text("$score/10", color = TvTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            repeat(10) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(
                            if (it < score) filled else Color(0xFF26313D),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}
