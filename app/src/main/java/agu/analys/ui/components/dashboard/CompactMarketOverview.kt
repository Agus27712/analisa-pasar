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
    val avgChange = if (validChanges.isEmpty()) 0.0 else validChanges.average()
    val modeBg = if (isScalping) Color(0xFF123D2A) else Color(0xFF15304B)
    val modeText = if (isScalping) TvGreen else Color(0xFF72B7FF)
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = DashboardColors.Surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            OverviewValue("PAIR", "${ticks.size}", if (isLive) "LIVE" else "OFFLINE", DashboardColors.AccentBlue, Modifier.weight(0.8f))
            OverviewDivider()
            OverviewValue("24H VOL", PriceFormatter.formatPrice(totalVolume), "IDR", DashboardColors.AccentBlue, Modifier.weight(1.55f))
            OverviewDivider()
            OverviewValue("AVG 24H", PriceFormatter.formatPercentage(avgChange), "change", if (avgChange >= 0) DashboardColors.AccentBlue else TvRed, Modifier.weight(1.2f))
            Spacer(Modifier.width(7.dp))
            Box(Modifier.background(modeBg, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 7.dp)) {
                Text(if (isScalping) "SCALPING" else "SWING", color = modeText, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun OverviewValue(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 5.dp)) {
        Text(label, color = TvTextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        AnimatedMetricText(value, color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        AnimatedMetricText(detail, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun OverviewDivider() { Box(Modifier.width(1.dp).height(38.dp).background(DashboardColors.Border)) }
