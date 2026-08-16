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
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Surface)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MARKET OVERVIEW", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(if (isLive) "Indodax · IDR" else "Data belum tersambung", color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.background(modeBg, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 6.dp)) {
                    Text(if (isScalping) "SCALPING" else "SWING", color = modeText, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OverviewValue("PAIR", "${ticks.size}", "LIVE", DashboardColors.AccentBlue, Modifier.weight(0.75f))
                OverviewDivider()
                OverviewValue("24H VOL", if (totalVolume > 0) PriceFormatter.formatPrice(totalVolume) else "—", "IDR", DashboardColors.AccentBlue, Modifier.weight(1.25f))
                OverviewDivider()
                OverviewValue("AVG 24H", if (avgChange.isFinite()) PriceFormatter.formatPercentage(avgChange) else "—", "change", avgColor, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OverviewValue(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 4.dp)) {
        Text(label, color = TvTextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        AnimatedMetricText(value, color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        AnimatedMetricText(detail, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun OverviewDivider() {
    Box(Modifier.width(1.dp).height(35.dp).background(DashboardColors.Border))
}
