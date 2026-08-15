package agu.analys.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.config.AiProvider
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.model.*
import agu.analys.ui.components.SimpleComposeChart
import agu.analys.ui.components.detail.*
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
    val selectedPair by viewModel.selectedPair.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val currentTick by viewModel.currentTick.collectAsStateWithLifecycle()
    val recentCandles by viewModel.recentCandles.collectAsStateWithLifecycle()
    val indicators by viewModel.currentIndicators.collectAsStateWithLifecycle()
    val signal by viewModel.aiSignalState.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val isScalping by viewModel.isScalpingMode.collectAsStateWithLifecycle()
    val audit by viewModel.auditReportText.collectAsStateWithLifecycle()
    val auditLoading by viewModel.isAuditLoading.collectAsStateWithLifecycle()
    val gemini by viewModel.geminiSummaryText.collectAsStateWithLifecycle()
    val geminiLoading by viewModel.isGeminiLoading.collectAsStateWithLifecycle()
    val showCached by viewModel.isShowingCachedData.collectAsStateWithLifecycle()
    var chartVisible by remember { mutableStateOf(false) }
    val selectedTf = if (isScalping) Timeframe.M1 else Timeframe.H4
    val structure = remember(recentCandles) { MarketStructureAnalyzer.analyze(recentCandles) }
    val provider = remember { AppPreferences(context).aiProvider }

    LaunchedEffect(isScalping) { viewModel.selectTimeframe(selectedTf) }

    Column(modifier.fillMaxSize().background(TvBackground).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onNavigateToDashboard) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = TvTextPrimary) }
            Column(Modifier.weight(1f)) {
                Text("${selectedPair.baseAsset}/${selectedPair.quoteAsset}", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary)
                Text("${if (isScalping) "SCALPING · 1M" else "SWING · 4H"} · ${if (connection is MarketConnectionState.Connected) "LIVE" else "CACHE"}", fontSize = 10.sp, color = if (connection is MarketConnectionState.Connected) TvGreen else TvTextSecondary)
            }
            IconButton(onClick = { viewModel.toggleWatchlist(selectedPair.symbol) }) { Icon(if (selectedPair.symbol in watchlist) Icons.Default.Star else Icons.Default.StarBorder, null, tint = TvTextSecondary) }
        }
        currentTick?.let { tick ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("HARGA ASET", fontSize = 9.sp, color = TvTextSecondary, fontWeight = FontWeight.Bold)
                    Text(PriceFormatter.formatPrice(tick.price), fontSize = 28.sp, color = TvTextPrimary, fontWeight = FontWeight.Black, modifier = Modifier.testTag("live_price_header"))
                    Text(PriceFormatter.formatPercentage(tick.change24h), fontSize = 13.sp, color = if (tick.change24h >= 0) TvGreen else TvRed, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AssistChip(onClick = { chartVisible = !chartVisible }, label = { Text(if (chartVisible) "Sembunyikan Chart" else "Tampilkan Chart") }, leadingIcon = { Icon(Icons.Default.ShowChart, null, Modifier.size(16.dp)) })
            AssistChip(onClick = onOpenLandscapeChart, label = { Text("Chart penuh") }, leadingIcon = { Icon(Icons.Default.CropRotate, null, Modifier.size(16.dp)) })
        }
        if (chartVisible) {
            Spacer(Modifier.height(8.dp)); SimpleComposeChart(prices = emptyList(), candles = recentCandles, currentPrice = currentTick?.price ?: 0.0, isPositiveTrend = (currentTick?.change24h ?: 0.0) >= 0, modifier = Modifier.fillMaxWidth().height(250.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(if (isScalping) "TIMEFRAME ENGINE · 1M" else "TIMEFRAME ENGINE · 4H", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (showCached) Text("Data cache terakhir, belum realtime.", color = WarningAmber, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        if (signal.action == SignalAction.BUY && signal.entryPrice > 0) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF123D2A)), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp)) {
                    Text("BUY READY", color = TvGreen, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text(if (isScalping) "Bias 1H → setup 15M → trigger 1M." else "Trend timeframe besar mendukung peluang beli.", color = TvTextPrimary, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        RecommendationCard(signal, isScalping)
        Spacer(Modifier.height(9.dp))
        AnalysisCard {
            Text("KENAPA", color = TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            val reasons = buildList {
                if (indicators.ema20.isFinite() && indicators.ema50.isFinite()) add(if (indicators.ema20 > indicators.ema50) "EMA20 di atas EMA50, bias naik." else "EMA20 di bawah EMA50, bias belum naik.")
                if (indicators.rsi14.isFinite()) add("RSI14 ${String.format("%.1f", indicators.rsi14)}")
                if (structure.trend.isNotBlank()) add("Struktur: ${structure.trend}")
            }.take(3)
            reasons.forEach { Text("• $it", color = TvTextPrimary, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)) }
        }
        Spacer(Modifier.height(9.dp))
        AiAssistantCard(audit, auditLoading, gemini, geminiLoading, viewModel::requestDeepAiAudit, viewModel::requestGeminiChartSummary, viewModel::clearAuditReport, viewModel::clearGeminiSummary)
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openIndodax(context, selectedPair.symbol) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5))) { Text("Buka Indodax", color = Color.White, fontWeight = FontWeight.Bold) }
            Button(onClick = { if (provider == AiProvider.GROQ) viewModel.requestDeepAiAudit() else viewModel.requestGeminiChartSummary() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = TvGreen)) { Icon(Icons.Default.AutoAwesome, null, tint = Color.Black); Spacer(Modifier.width(4.dp)); Text("AI Asisten", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(18.dp))
    }
}

private fun openIndodax(context: Context, symbol: String) {
    val intent = context.packageManager.getLaunchIntentForPackage("id.co.bitcoin")
    if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent) }
    else Toast.makeText(context, "Aplikasi Indodax belum terpasang di HP ini.", Toast.LENGTH_SHORT).show()
}
