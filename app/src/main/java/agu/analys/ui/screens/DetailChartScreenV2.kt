package agu.analys.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.config.AiProvider
import agu.analys.model.*
import agu.analys.ui.animation.SmoothPriceText
import agu.analys.ui.components.SimpleComposeChart
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.TradingViewModel

@Composable
fun DetailChartScreenV2(viewModel: TradingViewModel, onNavigateToDashboard: () -> Unit, onOpenLandscapeChart: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pair by viewModel.selectedPair.collectAsStateWithLifecycle()
    val tick by viewModel.currentTick.collectAsStateWithLifecycle()
    val candles by viewModel.recentCandles.collectAsStateWithLifecycle()
    val indicators by viewModel.currentIndicators.collectAsStateWithLifecycle()
    val signal by viewModel.aiSignalState.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val isScalping by viewModel.isScalpingMode.collectAsStateWithLifecycle()
    val aiGroq by viewModel.auditReportText.collectAsStateWithLifecycle()
    val aiGemini by viewModel.geminiSummaryText.collectAsStateWithLifecycle()
    val aiLoadingGroq by viewModel.isAuditLoading.collectAsStateWithLifecycle()
    val aiLoadingGemini by viewModel.isGeminiLoading.collectAsStateWithLifecycle()
    var chartVisible by remember { mutableStateOf(false) }
    val provider = remember { AppPreferences(context).aiProvider }
    val timeframe = if (isScalping) Timeframe.M1 else Timeframe.H4

    LaunchedEffect(isScalping) { viewModel.selectTimeframe(timeframe) }

    Column(modifier.fillMaxSize().background(TvBackground).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onNavigateToDashboard) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = TvTextPrimary) }
            Column(Modifier.weight(1f)) {
                Text("${pair.baseAsset}/${pair.quoteAsset}", color = TvTextPrimary, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("${if (isScalping) "SCALPING · 1M" else "SWING · 4H"} · ${if (connection is MarketConnectionState.Connected) "LIVE" else "REALTIME TERPUTUS"}", color = if (connection is MarketConnectionState.Connected) TvGreen else TvRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        tick?.let {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF101720))) {
                Column(Modifier.padding(14.dp)) {
                    Text("HARGA ASET", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    SmoothPriceText(it.price, TvTextPrimary, 28.sp, FontWeight.Black)
                    Text(PriceFormatter.formatPercentage(it.change24h), color = if (it.change24h >= 0) TvGreen else TvRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { chartVisible = !chartVisible }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.ShowChart, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(if (chartVisible) "Sembunyikan Chart" else "Tampilkan Chart") }
            OutlinedButton(onClick = onOpenLandscapeChart, modifier = Modifier.weight(1f)) { Text("Chart penuh") }
        }
        if (chartVisible) {
            Spacer(Modifier.height(8.dp))
            SimpleComposeChart(prices = emptyList(), candles = candles, currentPrice = tick?.price ?: 0.0, isPositiveTrend = (tick?.change24h ?: 0.0) >= 0, modifier = Modifier.fillMaxWidth().height(250.dp))
        }
        Spacer(Modifier.height(9.dp))
        if (signal.action == SignalAction.BUY && signal.entryPrice > 0) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), colors = CardDefaults.cardColors(containerColor = if (isScalping) Color(0xFF123D2A) else Color(0xFF15304B))) {
                Column(Modifier.padding(14.dp)) {
                    Text("BUY READY", color = TvGreen, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text(if (isScalping) "1H bias → 15M setup → 1M trigger." else "Struktur dan indikator mendukung peluang beli.", color = TvTextPrimary, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        AnalysisSection("YANG PERLU DIPANTAU") {
            Text(signal.mtf.waitingFor.ifBlank { "Pantau perubahan struktur dan konfirmasi harga berikutnya." }, color = TvTextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
            if (isScalping) {
                Spacer(Modifier.height(8.dp)); Text("1H Bias · ${signal.mtf.biasStatus}", color = TvTextSecondary, fontSize = 11.sp); Text("15M Setup · ${signal.mtf.setupStatus}", color = TvTextSecondary, fontSize = 11.sp); Text("1M Trigger · ${signal.mtf.triggerStatus}", color = TvTextSecondary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(9.dp))
        AnalysisSection("KENAPA") {
            signal.reasoning.take(4).forEach { Text("• $it", color = TvTextPrimary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp)) }
            if (indicators.rsi14.isFinite()) Text("RSI14 ${fmt(indicators.rsi14)} · EMA20 ${fmt(indicators.ema20)} · EMA50 ${fmt(indicators.ema50)}", color = TvTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        }
        Spacer(Modifier.height(9.dp))
        AnalysisSection("REKOMENDASI EKSEKUSI") {
            if (signal.action == SignalAction.BUY && signal.entryPrice > 0) {
                LevelTapRow("Entry", signal.entryPrice, Color(0xFF72B7FF), context); LevelTapRow("Stop Loss", signal.stopLoss, TvRed, context); LevelTapRow("TP 1", signal.targetPrice1, TvGreen, context); LevelTapRow("TP 2", signal.targetPrice2, TvGreen, context); Text(signal.riskRewardRatio, color = TvTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            } else Text("Belum BUY READY. Tidak ada level entry/SL/TP yang boleh ditampilkan sebagai sinyal.", color = TvTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(9.dp))
        if (aiGroq != null || aiGemini != null) AnalysisSection("ANALISA AI") { Text(if (provider == AiProvider.GROQ) aiGroq.orEmpty() else aiGemini.orEmpty(), color = TvTextPrimary, fontSize = 12.sp, lineHeight = 18.sp) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openIndodax(context) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5))) { Text("Buka Indodax", color = Color.White, fontWeight = FontWeight.Bold) }
            Button(onClick = { if (provider == AiProvider.GROQ) viewModel.requestDeepAiAudit() else viewModel.requestGeminiChartSummary() }, enabled = !aiLoadingGroq && !aiLoadingGemini && connection is MarketConnectionState.Connected, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = TvGreen)) { Icon(Icons.Default.AutoAwesome, null, tint = Color.Black); Spacer(Modifier.width(4.dp)); Text(if (aiLoadingGroq || aiLoadingGemini) "Menganalisis..." else "AI Analisis", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun AnalysisSection(title: String, content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1722))) { Column(Modifier.padding(13.dp)) { Text(title, color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.height(7.dp)); content() } } }

@Composable
private fun LevelTapRow(label: String, value: Double, color: Color, context: Context) { Row(Modifier.fillMaxWidth().clickable { copyPrice(context, value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, color = TvTextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(PriceFormatter.formatPrice(value), color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ContentCopy, null, tint = TvTextSecondary, modifier = Modifier.size(15.dp)) } }

private fun copyPrice(context: Context, value: Double) { val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.setPrimaryClip(ClipData.newPlainText("Harga", PriceFormatter.formatPrice(value, showSymbol = false))); Toast.makeText(context, "Harga disalin", Toast.LENGTH_SHORT).show() }
private fun openIndodax(context: Context) { val intent = context.packageManager.getLaunchIntentForPackage("id.co.bitcoin"); if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent) } else Toast.makeText(context, "Aplikasi Indodax belum terpasang di HP ini.", Toast.LENGTH_SHORT).show() }
private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
