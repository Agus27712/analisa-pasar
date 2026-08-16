package agu.analys.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun CompactMarketOverview(ticks: Map<String, MarketTick>, isLive: Boolean, isScalping: Boolean) {
    val validChanges = ticks.values.map { it.change24h }.filter { it.isFinite() }
    val totalVolume = ticks.values.sumOf { it.volume24h }
    val avgChange = if (validChanges.isEmpty()) Double.NaN else validChanges.average()
    val modeBg = if (isScalping) Color(0xFF123D2A) else Color(0xFF15304B)
    val modeText = if (isScalping) TvGreen else Color(0xFF72B7FF)
    val avgColor = when {
        !avgChange.isFinite() -> TvTextSecondary
        avgChange >= 0 -> TvGreen
        else -> TvRed
    }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OverviewValue("PAIR", "${ticks.size}", if (isLive) "LIVE" else "OFFLINE", DashboardColors.AccentBlue, Modifier.weight(0.8f))
            OverviewDivider()
            OverviewValue(
                "24H VOL",
                if (totalVolume > 0) PriceFormatter.formatPrice(totalVolume) else "—",
                "IDR",
                DashboardColors.AccentBlue,
                Modifier.weight(1.45f)
            )
            OverviewDivider()
            OverviewValue(
                "AVG 24H",
                if (avgChange.isFinite()) PriceFormatter.formatPercentage(avgChange) else "—",
                "change",
                avgColor,
                Modifier.weight(1.1f)
            )
            Spacer(Modifier.width(9.dp))
            Box(
                Modifier.background(modeBg, RoundedCornerShape(10.dp)).padding(horizontal = 11.dp, vertical = 9.dp)
            ) {
                Text(if (isScalping) "SCALPING" else "SWING", color = modeText, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun OverviewValue(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 5.dp)) {
        Text(label, color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        AnimatedMetricText(value, color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        AnimatedMetricText(detail, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun OverviewDivider() {
    Box(Modifier.width(1.dp).height(42.dp).background(DashboardColors.Border))
}
