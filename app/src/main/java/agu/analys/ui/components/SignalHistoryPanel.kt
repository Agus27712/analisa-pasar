package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.AISignalState
import agu.analys.model.SignalAction
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvCardBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SignalHistoryPanel(history: List<AISignalState>, currentSymbol: String, modifier: Modifier = Modifier) {
    val visibleHistory = history.filter { it.marketSymbol.equals(currentSymbol, ignoreCase = true) }
    val buyCount = visibleHistory.count { it.action == SignalAction.BUY }
    val sellCount = visibleHistory.count { it.action == SignalAction.SELL }
    val openCount = visibleHistory.count { it.action != SignalAction.HOLD && hasPositionLevels(it) }
    val timeFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
    Card(modifier = modifier.fillMaxWidth().border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = TvCardBackground)) {
        Column(Modifier.padding(18.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("RIWAYAT & LOG SINYAL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, letterSpacing = 1.2.sp); Text("${visibleHistory.size} sinyal", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TvGreen) }
            if (visibleHistory.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) { HistorySummary("BUY", buyCount, TvGreen, Modifier.weight(1f)); HistorySummary("SELL", sellCount, TvRed, Modifier.weight(1f)); HistorySummary("OPEN", openCount, TvAmber, Modifier.weight(1f)) } }
            Spacer(Modifier.height(14.dp))
            if (visibleHistory.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { Text("Belum ada sinyal untuk koin ini.", fontSize = 12.sp, color = TvTextSecondary) }
            } else {
                visibleHistory.forEach { signal ->
                    val color = when (signal.action) { SignalAction.BUY -> TvGreen; SignalAction.SELL -> TvRed; SignalAction.HOLD -> TvAmber }
                    val actionLabel = when (signal.action) { SignalAction.BUY -> "BELI"; SignalAction.SELL -> "JUAL"; SignalAction.HOLD -> "TAHAN" }
                    val lifecycle = lifecycleLabel(signal)
                    val lifecycleColor = if (lifecycle == "NO POSITION") TvTextSecondary else TvAmber
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF121212)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)) { Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Black, color = color) }; Spacer(Modifier.width(10.dp)); Column { Text(currentSymbol, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color); Text("Entry ${PriceFormatter.formatPrice(signal.entryPrice)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TvTextPrimary) } }
                                Column(horizontalAlignment = Alignment.End) { Text("Score ${signal.confidence}/100", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color); Text(timeFormat.format(Date(signal.timestamp)), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TvTextSecondary) }
                            }
                            Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { PriceBox("TP1", signal.targetPrice1, TvGreen, Modifier.weight(1f)); PriceBox("TP2", signal.targetPrice2, TvGreen, Modifier.weight(1f)); PriceBox("SL", signal.stopLoss, TvRed, Modifier.weight(1f)) }
                            Spacer(Modifier.height(7.dp)); Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(lifecycleColor.copy(alpha = 0.09f)).padding(horizontal = 8.dp, vertical = 6.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("LIFECYCLE", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = lifecycleColor); Text(if (lifecycle == "OPEN") "OPEN · menunggu candle berikutnya untuk evaluasi TP/SL" else "Tidak ada posisi karena level entry/TP/SL tidak valid", fontSize = 9.sp, color = TvTextSecondary, maxLines = 2) }; Text(lifecycle, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = lifecycleColor) }
                            Spacer(Modifier.height(6.dp)); Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(signal.sentiment.displayName, fontSize = 9.sp, color = TvTextSecondary); Text(if (lifecycle == "OPEN") "STATUS: OPEN" else "STATUS: NO POSITION", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = lifecycleColor) }
                        }
                    }
                }
            }
        }
    }
}

private fun hasPositionLevels(signal: AISignalState): Boolean = signal.action != SignalAction.HOLD && signal.entryPrice > 0.0 && signal.targetPrice1 > 0.0 && signal.targetPrice2 > 0.0 && signal.stopLoss > 0.0
private fun lifecycleLabel(signal: AISignalState): String = if (hasPositionLevels(signal)) "OPEN" else "NO POSITION"

@Composable
private fun HistorySummary(label: String, count: Int, color: Color, modifier: Modifier = Modifier) { Box(modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 6.dp)) { Column { Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color); Text(count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Black, color = TvTextPrimary) } } }

@Composable
private fun PriceBox(label: String, value: Double, color: Color, modifier: Modifier = Modifier) { Column(modifier.clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.08f)).padding(horizontal = 6.dp, vertical = 5.dp)) { Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color); Text(if (value > 0) PriceFormatter.formatPrice(value) else "-", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TvTextPrimary, maxLines = 1) } }
