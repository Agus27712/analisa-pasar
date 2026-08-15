package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.MarketTick
import agu.analys.ui.animation.AnimatedMetricText
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import kotlin.math.roundToInt

@Composable
fun CompactMarketOverview(
    ticks: Map<String, MarketTick>,
    isLive: Boolean
) {
    val validChanges = ticks.values.map { it.change24h }.filter { it.isFinite() }
    val totalVolume = ticks.values.sumOf { it.volume24h }
    val avgChange = if (validChanges.isEmpty()) 0.0 else validChanges.average()
    val positiveRatio = if (validChanges.isEmpty()) 0.5 else validChanges.count { it > 0.0 }.toDouble() / validChanges.size

    val breadthScore = positiveRatio * 40.0
    val momentumScore = ((avgChange.coerceIn(-5.0, 5.0) + 5.0) / 10.0) * 60.0
    val score = (breadthScore + momentumScore).roundToInt().coerceIn(1, 99)
    val scoreCol = scoreColor(score)

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(17.dp),
            colors = CardDefaults.cardColors(containerColor = DashboardColors.Surface),
            border = BorderStroke(1.dp, DashboardColors.Border)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OverviewValue(
                    label = "PAIR",
                    value = "${ticks.size}",
                    detail = if (isLive) "LIVE" else "OFFLINE",
                    color = DashboardColors.AccentBlue,
                    icon = Icons.Default.Groups,
                    modifier = Modifier.weight(0.85f)
                )
                OverviewDivider()
                OverviewValue(
                    label = "24H VOL",
                    value = PriceFormatter.formatPrice(totalVolume),
                    detail = "tracked",
                    color = DashboardColors.AccentBlue,
                    icon = Icons.Default.BarChart,
                    modifier = Modifier.weight(1.75f)
                )
                OverviewDivider()
                OverviewValue(
                    label = "AVG 24H",
                    value = PriceFormatter.formatPercentage(avgChange),
                    detail = "change",
                    color = if (avgChange >= 0) DashboardColors.AccentBlue else TvRed,
                    icon = Icons.Default.TrendingUp,
                    modifier = Modifier.weight(1.12f)
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                "MARKET SCORE",
                color = TvTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.width(7.dp))
            AnimatedMetricText(
                value = "$score/100",
                color = DashboardColors.Gold,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF26313D))
            ) {
                Box(
                    Modifier.fillMaxWidth(score.coerceIn(0, 100) / 100f).fillMaxSize()
                        .clip(RoundedCornerShape(4.dp)).background(DashboardColors.Gold)
                )
            }
            Spacer(Modifier.width(10.dp))
            AnimatedMetricText(
                value = when {
                    score >= 75 -> "BULLISH"
                    score >= 50 -> "NETRAL"
                    else -> "LEMAH"
                },
                color = DashboardColors.Gold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun OverviewValue(
    label: String,
    value: String,
    detail: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier
) {
    Column(modifier.padding(horizontal = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = 0.4.sp)
        }
        Spacer(Modifier.height(5.dp))
        AnimatedMetricText(value = value, color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        AnimatedMetricText(value = detail, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun OverviewDivider() {
    Box(
        Modifier.padding(vertical = 2.dp).width(1.dp).height(42.dp).background(DashboardColors.Border)
    )
}
