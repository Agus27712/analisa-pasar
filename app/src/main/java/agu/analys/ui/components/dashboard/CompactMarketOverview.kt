package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.animation.AnimatedMetricText
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

@Composable
fun CompactMarketOverview(
    ticks: Map<String, MarketTick>,
    worthCoins: List<WorthCoinInfo>,
    isLive: Boolean
) {
    val totalVolume = ticks.values.sumOf { it.volume24h }
    val avgChange = if (ticks.isEmpty()) 0.0 else ticks.values.map { it.change24h }.filter { !it.isNaN() }.let {
        if (it.isEmpty()) 0.0 else it.average()
    }
    val score = worthCoins.maxOfOrNull { it.worthScore } ?: 0
    val scoreCol = scoreColor(score)

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DashboardColors.Surface),
            border = BorderStroke(1.dp, DashboardColors.Border)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OverviewValue(
                    label = "PAIR",
                    value = "${ticks.size}",
                    detail = if (isLive) "LIVE" else "OFFLINE",
                    color = TvGreen,
                    modifier = Modifier.weight(0.78f)
                )
                OverviewDivider()
                Spacer(Modifier.width(10.dp))
                OverviewValue(
                    label = "24H VOL",
                    value = PriceFormatter.formatPrice(totalVolume),
                    detail = "market",
                    color = TvGreen,
                    modifier = Modifier.weight(1.72f)
                )
                OverviewDivider()
                Spacer(Modifier.width(10.dp))
                OverviewValue(
                    label = "AVG 24H",
                    value = PriceFormatter.formatPercentage(avgChange),
                    detail = "change",
                    color = if (avgChange >= 0) TvGreen else TvRed,
                    modifier = Modifier.weight(1.12f)
                )
            }
        }

        Spacer(Modifier.height(9.dp))
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
                color = scoreCol,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF252D36))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(score.coerceIn(0, 100) / 100f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp))
                        .background(scoreCol)
                )
            }
            Spacer(Modifier.width(10.dp))
            AnimatedMetricText(
                value = when {
                    score >= 75 -> "BULLISH"
                    score >= 50 -> "NETRAL"
                    else -> "LEMAH"
                },
                color = scoreCol,
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
    modifier: Modifier
) {
    Column(modifier) {
        Text(
            label,
            color = TvTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(3.dp))
        AnimatedMetricText(
            value = value,
            color = TvTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
        AnimatedMetricText(
            value = detail,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun OverviewDivider() {
    Box(
        Modifier
            .padding(vertical = 2.dp)
            .width(1.dp)
            .height(34.dp)
            .background(DashboardColors.Border)
    )
}
