package agu.analys.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

@Composable
fun AISignalCard(
    signal: AISignalState,
    onDeepAuditClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenIndodaxClick: (() -> Unit)? = null,
    auditText: String? = null,
    isAuditLoading: Boolean = false,
    onRequestGemini: (() -> Unit)? = null,
    geminiSummaryText: String? = null,
    isGeminiLoading: Boolean = false,
    onClearAudit: (() -> Unit)? = null,
    onClearGemini: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var detailsExpanded by remember { mutableStateOf(false) }
    val actionColor by animateColorAsState(targetValue = when (signal.action) { SignalAction.BUY -> TvGreen; SignalAction.SELL -> TvRed; SignalAction.HOLD -> TvAmber }, label = "actionColorAnimation")
    val actionNameIndo = when (signal.action) { SignalAction.BUY -> "BELI"; SignalAction.SELL -> "JUAL"; SignalAction.HOLD -> "TAHAN" }
    val scoreLabel = if (signal.action == SignalAction.HOLD) "SETUP BELUM KUAT • ${signal.confidence}/100" else "SETUP ${signal.confidence}/100"
    val structureBlocked = signal.reasoning.any {
        it.contains("bertentangan dengan market structure", ignoreCase = true)
    }
    Card(modifier = modifier.fillMaxWidth().border(1.dp, TvGreen.copy(alpha = 0.25f), RoundedCornerShape(20.dp)).testTag("ai_signal_card"), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = TvCardBackground), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(TvGreen, CircleShape)); Spacer(Modifier.width(8.dp)); Text("ANALISIS TEKNIKAL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvGreen, letterSpacing = 1.2.sp) }
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(actionColor).padding(horizontal = 10.dp, vertical = 4.dp)) { Text(if (signal.confidence == 0) "DATA BELUM CUKUP" else scoreLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }
            if (structureBlocked) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TvRed.copy(alpha = 0.12f))
                        .border(1.dp, TvRed.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        "STRUCTURE BLOCK — sinyal dibatalkan karena bertentangan dengan struktur pasar",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TvRed,
                        maxLines = 2
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(actionColor.copy(alpha = 0.12f)).border(1.dp, actionColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(when (signal.action) { SignalAction.BUY -> Icons.Default.TrendingUp; SignalAction.SELL -> Icons.Default.TrendingDown; SignalAction.HOLD -> Icons.Default.Shield }, contentDescription = actionNameIndo, tint = actionColor, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) { Text(signal.sentiment.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp); Text(signal.patternDetected?.let { "Pola: $it" } ?: "Belum ada pola candle yang dikonfirmasi", fontSize = 11.sp, color = TvTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text(actionNameIndo, fontSize = 18.sp, fontWeight = FontWeight.Black, color = actionColor, modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { (signal.confidence / 100.0f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = actionColor, trackColor = Color(0x1AFFFFFF))
            Spacer(Modifier.height(14.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LevelRow("ENTRY / MASUK", formatLevel(signal.entryPrice), TvTextPrimary)
                LevelRow("TP1 • 2× ATR", formatLevel(signal.targetPrice1), TvGreen)
                LevelRow("TP2 • 3,5× ATR", formatLevel(signal.targetPrice2), TvGreen)
                LevelRow("STOP LOSS • 1,5× ATR", formatLevel(signal.stopLoss), TvRed)
                LevelRow("R:R MATEMATIS", signal.riskRewardRatio, TvTextPrimary)
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x0AFFFFFF)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Default.Info, contentDescription = null, tint = TvTextSecondary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("TP/SL adalah level latihan berbasis ATR, bukan prediksi harga pasti atau support/resistance.", fontSize = 10.sp, color = TvTextSecondary, lineHeight = 14.sp) }
            Spacer(Modifier.height(10.dp))
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp)).animateContentSize()) {
                Row(modifier = Modifier.fillMaxWidth().clickable { detailsExpanded = !detailsExpanded }.padding(horizontal = 12.dp, vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) { Text("KENAPA HASILNYA BEGINI?", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvGreen, letterSpacing = 0.8.sp); Text(if (detailsExpanded) "Ringkasan lengkap faktor analisis" else "Ketuk untuk belajar Market Regime, RSI, EMA, MACD, ATR, dan lainnya", fontSize = 11.sp, color = TvTextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Icon(if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = TvGreen, modifier = Modifier.size(22.dp))
                }
                if (detailsExpanded) {
                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        LearningFactorRow("Market Regime", findReason(signal, "Market regime"), "Kondisi umum pasar: trending, sideways, transisi, atau volatilitas tinggi.")
                        LearningFactorRow("RSI (14)", findReason(signal, "RSI"), "RSI mengukur momentum. Di bawah 30 disebut jenuh jual, di atas 70 jenuh beli. Nilai tengah bukan sinyal otomatis.")
                        LearningFactorRow("EMA 20 / EMA 50", findReason(signal, "EMA20"), "EMA membantu membaca tren. Harga dan EMA20 di atas EMA50 mendukung bullish, sebaliknya mendukung bearish.")
                        LearningFactorRow("MACD", findReason(signal, "MACD"), "MACD membantu membaca momentum. Histogram positif mendukung momentum naik, negatif mendukung turun.")
                        LearningFactorRow("Bollinger Band", findReason(signal, "Bollinger"), "Band memberi konteks volatilitas dan posisi harga, bukan support/resistance pasti.")
                        LearningFactorRow("ATR", "ATR dipakai untuk mengukur jarak volatilitas", "ATR makin besar berarti pergerakan candle cenderung lebih lebar. Di aplikasi ini ATR dipakai untuk level latihan TP/SL.")
                        LearningFactorRow("Volume", findReason(signal, "Volume"), "Lonjakan volume dibanding 5 candle terakhir adalah konfirmasi tambahan, bukan penentu tunggal.")
                        signal.patternDetected?.let { LearningFactorRow("Candlestick", "Pola: $it", "Pola candle hanya konfirmasi tambahan dan tidak menjamin arah berikutnya.") }
                        Spacer(Modifier.height(2.dp)); Text("${if (signal.action == SignalAction.HOLD) "SETUP BELUM CUKUP KUAT" else "SCORE ${signal.confidence}/100"}. Ini kekuatan setup, BUKAN ${signal.confidence}% kemungkinan profit.", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvAmber, lineHeight = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { val launchIntent = context.packageManager.getLaunchIntentForPackage("id.co.bitcoin"); if (launchIntent != null) { launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(launchIntent) } else Toast.makeText(context, "Aplikasi Indodax belum terpasang di HP ini.", Toast.LENGTH_SHORT).show() }, modifier = Modifier.weight(1f).height(44.dp).testTag("execute_signal_button"), colors = ButtonDefaults.buttonColors(containerColor = TvGreen), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp), tint = Color.Black); Spacer(Modifier.width(4.dp)); Text("Buka Indodax", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1) }
                OutlinedButton(onClick = onDeepAuditClick, enabled = !isAuditLoading, modifier = Modifier.weight(1f).height(44.dp).testTag("ai_audit_button"), colors = ButtonDefaults.outlinedButtonColors(contentColor = TvTextPrimary), border = BorderStroke(1.dp, Color(0x33FFFFFF)), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    if (isAuditLoading) CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = TvGreen) else Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF8E8CD8), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Groq Audit", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            if (onRequestGemini != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRequestGemini, enabled = !isGeminiLoading, modifier = Modifier.fillMaxWidth().height(42.dp).testTag("gemini_summary_button"), colors = ButtonDefaults.outlinedButtonColors(contentColor = TvTextPrimary), border = BorderStroke(1.dp, Color(0x3324A8FF)), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    if (isGeminiLoading) CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = TvGreen) else Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF6FB8FF), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp)); Text("Gemini 24J", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            if (auditText != null || geminiSummaryText != null) {
                Spacer(Modifier.height(10.dp))
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp)).padding(12.dp)) {
                    if (auditText != null) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("GROQ • AUDIT AI", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvGreen); if (onClearAudit != null) Text("Hapus", fontSize = 10.sp, color = TvTextSecondary, modifier = Modifier.clickable { onClearAudit() }) }
                        Spacer(Modifier.height(6.dp)); Text(auditText, fontSize = 11.sp, color = TvTextPrimary, lineHeight = 16.sp)
                    }
                    if (geminiSummaryText != null) {
                        if (auditText != null) Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("GEMINI • CHART 24J", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6FB8FF)); if (onClearGemini != null) Text("Hapus", fontSize = 10.sp, color = TvTextSecondary, modifier = Modifier.clickable { onClearGemini() }) }
                        Spacer(Modifier.height(6.dp)); Text(geminiSummaryText, fontSize = 11.sp, color = TvTextPrimary, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

private fun findReason(signal: AISignalState, prefix: String): String = signal.reasoning.firstOrNull { it.startsWith(prefix, ignoreCase = true) } ?: "Belum ada penjelasan dari data candle saat ini."

@Composable
private fun LearningFactorRow(title: String, value: String, lesson: String) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Color(0x0AFFFFFF)).padding(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) { Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary, modifier = Modifier.weight(0.35f)); Text(value, fontSize = 10.sp, color = TvGreen, lineHeight = 14.sp, modifier = Modifier.weight(0.65f)) }
        Spacer(Modifier.height(4.dp)); Text("Belajar: $lesson", fontSize = 10.sp, color = TvTextSecondary, lineHeight = 14.sp)
    }
}

private fun formatLevel(value: Double): String = if (value > 0.0 && value.isFinite()) PriceFormatter.formatPriceFull(value) else "Belum tersedia"

@Composable
private fun LevelRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x0AFFFFFF)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.3.sp)
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
