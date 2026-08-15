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
    val volumeScore = when { volume >= 100_000_000_000 -> 10; volume >= 10_000_000_000 -> 8; volume >= 1_000_000_000 -> 6; volume > 0 -> 4; else -> 0 }
    val momentumScore = if (change.isFinite()) ((abs(change).coerceAtMost(10.0) / 10.0) * 10.0).toInt().coerceIn(0, 10) else 0
    val status = when {
        isScalping && volumeScore >= 8 && momentumScore >= 6 -> "Aktivitas tinggi"
        isScalping && volumeScore >= 6 -> "Aktivitas sedang"
        worth?.recommendation == "TEKANAN JUAL" -> "Tekanan jual"
        worth?.recommendation == "MOMENTUM KUAT" -> "Momentum kuat"
        else -> "Netral"
    }
    val statusBg = when (status) { "Aktivitas tinggi", "Momentum kuat" -> Color(0xFF123D2A); "Tekanan jual" -> Color(0xFF3B1820); else -> Color(0xFF26313D) }
    val statusText = when (status) { "Aktivitas tinggi", "Momentum kuat" -> TvGreen; "Tekanan jual" -> TvRed; else -> TvTextSecondary }
    val changeColor = when { change > 0 -> TvGreen; change < 0 -> TvRed; else -> TvTextSecondary }

    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rank?.let { "#$it" } ?: "•", color = DashboardColors.AccentBlue, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(27.dp))
                Column(Modifier.weight(1f)) {
                    Text("${pair.baseAsset}/${pair.quoteAsset}", color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.background(statusBg, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp)) { Text(status, color = statusText, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(5.dp)); Text("● LIVE", color = TvGreen, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (tick != null) SmoothPriceText(tick.price, TvTextPrimary, 13.sp, FontWeight.ExtraBold)
                    if (change.isFinite()) AnimatedMetricText(PriceFormatter.formatPercentage(change), changeColor, 9.sp, FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (isScalping) {
                ScoreLine("Volume", volumeScore); ScoreLine("Momentum", momentumScore)
            } else {
                val trend = when { change >= 5 -> 9; change >= 1 -> 7; change >= 0 -> 6; change >= -3 -> 4; change.isFinite() -> 2; else -> 0 }
                val structure = ((worth?.worthScore ?: 0) / 10).coerceIn(0, 10)
                ScoreLine("Trend", trend); ScoreLine("Structure", structure)
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(if (tick != null) "Vol ${PriceFormatter.formatVolume(volume)}" else "Data belum tersedia", color = TvTextSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onClick, modifier = Modifier.height(30.dp), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, DashboardColors.AccentBlue), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) { Icon(Icons.Default.ShowChart, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Buka Chart", fontSize = 9.sp, color = DashboardColors.AccentBlue) }
                if (!isAuto) IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.DeleteOutline, "Hapus", tint = TvRed, modifier = Modifier.size(17.dp)) }
            }
        }
    }
}

@Composable
private fun ScoreLine(label: String, score: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TvTextSecondary, fontSize = 8.sp, modifier = Modifier.width(60.dp))
        Text("$score/10", color = TvTextPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) { repeat(10) { Box(Modifier.weight(1f).height(4.dp).background(if (it < score) DashboardColors.AccentBlue else Color(0xFF26313D), RoundedCornerShape(2.dp))) } }
    }
}
