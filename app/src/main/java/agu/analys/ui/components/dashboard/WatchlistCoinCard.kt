package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    val change = tick?.change24h ?: 0.0
    val changeColor = when { change > 0 -> TvGreen; change < 0 -> TvRed; else -> TvTextSecondary }
    val rangePct = tick?.let { if (it.low24h > 0) ((it.high24h - it.low24h) / it.low24h) * 100.0 else 0.0 } ?: 0.0
    val volumeScore = when { (tick?.volume24h ?: 0.0) >= 100_000_000_000 -> 10; (tick?.volume24h ?: 0.0) >= 10_000_000_000 -> 8; (tick?.volume24h ?: 0.0) >= 1_000_000_000 -> 6; else -> 4 }
    val momentumScore = if (change.isFinite()) ((kotlin.math.abs(change).coerceAtMost(10.0) / 10.0) * 10).toInt().coerceAtLeast(1) else (rangePct.coerceAtMost(10.0)).toInt().coerceAtLeast(1)
    val activity = when { (volumeScore + momentumScore) / 2 >= 8 -> "Aktivitas tinggi"; (volumeScore + momentumScore) / 2 >= 5 -> "Aktivitas sedang"; else -> "Aktivitas rendah" }
    val activityBg = when { activity == "Aktivitas tinggi" -> Color(0xFF123D2A); activity == "Aktivitas sedang" -> Color(0xFF4A3B12); else -> Color(0xFF26313D) }
    val activityText = when { activity == "Aktivitas tinggi" -> TvGreen; activity == "Aktivitas sedang" -> Color(0xFFFFD54A); else -> TvTextSecondary }

    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = DashboardColors.Card), border = BorderStroke(1.dp, DashboardColors.Border)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rank?.let { "#$it" } ?: "•", color = DashboardColors.AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(28.dp))
                Text("${pair.baseAsset}/${pair.quoteAsset}", color = TvTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f), maxLines = 1)
                if (isAuto) Text("AUTO", color = TvTextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))
                if (tick != null) {
                    SmoothPriceText(tick.price, TvTextPrimary, 14.sp, FontWeight.ExtraBold)
                    Spacer(Modifier.width(7.dp))
                    AnimatedMetricText(PriceFormatter.formatPercentage(change), changeColor, 10.sp, FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(activityBg, RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 4.dp)) { Text(activity, color = activityText, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                Text("● LIVE", color = TvGreen, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(6.dp))
            if (isScalping) {
                ScoreLine("Volume", volumeScore)
                ScoreLine("Momentum", momentumScore)
            } else {
                val trend = when { change >= 6 -> 9; change >= 2 -> 8; change >= 0 -> 6; change >= -3 -> 4; else -> 2 }
                val structure = ((worth?.worthScore ?: 50) / 10).coerceIn(1, 10)
                ScoreLine("Trend", trend)
                ScoreLine("Structure", structure)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(34.dp), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, DashboardColors.AccentBlue), colors = ButtonDefaults.outlinedButtonColors(contentColor = DashboardColors.AccentBlue), contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.ShowChart, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Buka Chart", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (!isAuto) { Spacer(Modifier.height(2.dp)); TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) { Text("Hapus dari manual", color = TvTextSecondary, fontSize = 9.sp) } }
        }
    }
}

@Composable
private fun ScoreLine(label: String, score: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TvTextSecondary, fontSize = 9.sp, modifier = Modifier.width(70.dp))
        Text("$score/10", color = TvTextPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            repeat(10) { Box(Modifier.weight(1f).height(5.dp).background(if (it < score) DashboardColors.AccentBlue else Color(0xFF26313D), RoundedCornerShape(3.dp))) }
        }
    }
}
