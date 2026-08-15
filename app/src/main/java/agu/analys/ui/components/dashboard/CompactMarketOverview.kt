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
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OverviewValue("PAIR", "${ticks.size}", if (isLive) "LIVE" else "OFFLINE", TvGreen, Modifier.weight(0.7f))
                OverviewDivider()
                OverviewValue("24H VOL", PriceFormatter.formatPrice(totalVolume), "market", TvGreen, Modifier.weight(1.65f))
                OverviewDivider()
                OverviewValue(
                    "AVG 24H",
                    PriceFormatter.formatPercentage(avgChange),
                    "change",
                    if (avgChange >= 0) TvGreen else TvRed,
                    Modifier.weight(1.1f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            Text("MARKET SCORE", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
            Spacer(Modifier.width(6.dp))
            Text("$score/100", color = scoreCol, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF252D36))) {
                Box(
                    Modifier.fillMaxWidth(score.coerceIn(0, 100) / 100f).fillMaxSize()
                        .clip(RoundedCornerShape(4.dp)).background(scoreCol)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
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
private fun OverviewValue(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(detail, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun OverviewDivider() {
    Box(Modifier.width(1.dp).height(34.dp).background(DashboardColors.Border))
}
