package agu.analys.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.AISignalState
import agu.analys.model.MarketConnectionState
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.model.TradingPair
import agu.analys.ui.components.SimpleComposeChart
import agu.analys.ui.components.SpotPositionCard
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.TradingViewModel

private val TvGold = Color(0xFFFFD54A)
private val AnalysisCard = Color(0xFF0D1722)
private val AnalysisBorder = Color(0xFF1A3347)
private val InfoBlue = Color(0xFF2196F3)
private val WarningAmber = Color(0xFFFFB300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailChartScreen(viewModel: TradingViewModel, onNavigateToDashboard: () -> Unit, onOpenLandscapeChart: () -> Unit, modifier: Modifier = Modifier) {
    val selectedPair by viewModel.selectedPair.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val isChartExpanded by viewModel.isChartExpanded.collectAsStateWithLifecycle()
    val recentCandles by viewModel.recentCandles.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val currentTick by viewModel.currentTick.collectAsStateWithLifecycle()
    val currentIndicators by viewModel.currentIndicators.collectAsStateWithLifecycle()
    val aiSignalState by viewModel.aiSignalState.collectAsStateWithLifecycle()
    val isScalpingMode by viewModel.isScalpingMode.collectAsStateWithLifecycle()
    val isShowingCached by viewModel.isShowingCachedData.collectAsStateWithLifecycle()
    val spotPosition by viewModel.spotPosition.collectAsStateWithLifecycle()
    val auditReportText by viewModel.auditReportText.collectAsStateWithLifecycle()
    val isAuditLoading by viewModel.isAuditLoading.collectAsStateWithLifecycle()
    val geminiSummaryText by viewModel.geminiSummaryText.collectAsStateWithLifecycle()
    val isGeminiLoading by viewModel.isGeminiLoading.collectAsStateWithLifecycle()
    val marketStructure = remember(recentCandles) { MarketStructureAnalyzer.analyze(recentCandles) }
    var showSymbolPickerSheet by remember { mutableStateOf(false) }

    Scaffold(modifier = modifier.fillMaxSize(), containerColor = TvBackground, topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onNavigateToDashboard, modifier = Modifier.testTag("back_to_dashboard_button")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Dashboard", tint = TvTextPrimary, modifier = Modifier.size(28.dp)) } },
            title = {
                Row(modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showSymbolPickerSheet = true }.padding(horizontal = 6.dp, vertical = 4.dp).testTag("symbol_picker_trigger"), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(selectedPair.baseAsset + "/" + selectedPair.quoteAsset, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary)
                        Text("Data real Indodax", fontSize = 11.sp, color = TvTextSecondary)
                    }
                    Spacer(Modifier.width(5.dp)); Icon(Icons.Default.KeyboardArrowDown, "Pilih Koin", tint = TvTextSecondary, modifier = Modifier.size(21.dp))
                }
            },
            actions = {
                IconButton(onClick = { viewModel.toggleWatchlist(selectedPair.symbol) }, modifier = Modifier.testTag("toggle_watchlist_button")) { Icon(if (selectedPair.symbol in watchlist) Icons.Default.Star else Icons.Default.StarBorder, if (selectedPair.symbol in watchlist) "Hapus dari watchlist" else "Tambah ke watchlist", tint = if (selectedPair.symbol in watchlist) TvGold else TvTextSecondary, modifier = Modifier.size(27.dp)) }
                OutlinedButton(onClick = onOpenLandscapeChart, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen), border = BorderStroke(1.dp, TvGreen), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp), modifier = Modifier.padding(end = 7.dp).testTag("trigger_landscape_chart_button")) { Icon(Icons.Default.CropRotate, "Landscape", modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("Chart", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = TvBackground)
        )
    }) { innerPadding ->
        if (isChartExpanded) {
            Box(Modifier.fillMaxSize().padding(innerPadding).background(Color(0xFF0D0E12))) {
                SimpleComposeChart(prices = emptyList(), candles = recentCandles, currentPrice = currentTick?.price ?: 0.0, isPositiveTrend = (currentTick?.change24h ?: 0.0) >= 0, modifier = Modifier.fillMaxSize())
                IconButton(onClick = { viewModel.toggleChartExpanded() }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape).background(Color(0xCC121212))) { Icon(Icons.Default.FullscreenExit, "Tutup", tint = TvTextPrimary, modifier = Modifier.size(28.dp)) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp)) {
                if (isShowingCached) Text("Data terakhir tersimpan, belum live.", fontSize = 12.sp, color = WarningAmber, modifier = Modifier.padding(bottom = 5.dp))
                currentTick?.let { tick -> PriceHeader(tick.price, tick.change24h) }
                Spacer(Modifier.height(8.dp))
                ModeSwitchToggle(isScalping = isScalpingMode, onToggle = { viewModel.setScalpingMode(it) })
                Spacer(Modifier.height(10.dp))
                SimpleComposeChart(prices = emptyList(), candles = recentCandles, currentPrice = currentTick?.price ?: 0.0, isPositiveTrend = (currentTick?.change24h ?: 0.0) >= 0, modifier = Modifier.fillMaxWidth().height(300.dp))
                Spacer(Modifier.height(10.dp)); TimeframeSelector(selectedTimeframe, viewModel::selectTimeframe)
                if (connectionState is MarketConnectionState.ConnectionLost) { Spacer(Modifier.height(8.dp)); AnalysisCard { IconTextRow(Icons.Default.Info, if (isShowingCached) "Koneksi terputus. Harga di layar adalah data terakhir yang tersimpan." else "Data market sedang tidak tersedia.", TvRed) } }
                Spacer(Modifier.height(10.dp)); MarketConditionCard(structure = marketStructure,indicators = currentIndicators)
                Spacer(Modifier.height(10.dp)); RecommendationCard(aiSignalState)
                Spacer(Modifier.height(10.dp)); WhyCard(aiSignalState, currentIndicators, marketStructure)
                Spacer(Modifier.height(10.dp)); AiAssistantCard(auditReportText, isAuditLoading, geminiSummaryText, isGeminiLoading, viewModel::requestDeepAiAudit, viewModel::requestGeminiChartSummary, viewModel::clearAuditReport, viewModel::clearGeminiSummary)
                Spacer(Modifier.height(10.dp)); SpotPositionCard(symbol = selectedPair.symbol, signal = aiSignalState, position = spotPosition, currentPrice = currentTick?.price ?: 0.0, onPositionChanged = viewModel::refreshSpotPosition)
                Spacer(Modifier.height(10.dp)); ImportantLevelsCard(aiSignalState, marketStructure, currentTick?.price ?: 0.0)
                Spacer(Modifier.height(10.dp)); TechnicalDetailsCard(currentIndicators, marketStructure, currentTick?.volume24h ?: 0.0)
                Spacer(Modifier.height(10.dp)); MonitorCard(aiSignalState, marketStructure, currentTick?.price ?: 0.0, isShowingCached)
                Spacer(Modifier.height(10.dp)); DisclaimerCard(); Spacer(Modifier.height(18.dp))
            }
        }
    }

    if (showSymbolPickerSheet) SymbolPickerSheet(popularPairs = TradingPair.POPULAR_PAIRS, watchlist = watchlist, currentSymbol = selectedPair.symbol, onDismiss = { showSymbolPickerSheet = false }, onSelect = { pair -> viewModel.selectPair(pair); showSymbolPickerSheet = false }, onSelectAndWatch = { raw -> viewModel.selectAndWatch(raw, addToWatchlist = true); showSymbolPickerSheet = false }, onToggleWatch = { symbol -> viewModel.toggleWatchlist(symbol) })
}

@Composable
private fun PriceHeader(price: Double, change: Double) {
    val changeColor = if (change >= 0) TvGreen else TvRed
    Column(Modifier.fillMaxWidth()) { Text(PriceFormatter.formatPrice(price), fontSize = 29.sp, fontWeight = FontWeight.Black, color = TvTextPrimary, modifier = Modifier.testTag("live_price_header")); Row(verticalAlignment = Alignment.CenterVertically) { Text(PriceFormatter.formatPercentage(change), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = changeColor); Spacer(Modifier.width(7.dp)); Text("(24 jam)", fontSize = 14.sp, color = TvTextSecondary) } }
}

@Composable
private fun TimeframeSelector(selected: Timeframe, onSelect: (Timeframe) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf(Timeframe.M1, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1).forEach { tf -> val active = selected == tf; Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (active) Color(0xFF087FF5) else Color(0xFF162536)).clickable { onSelect(tf) }.padding(horizontal = 17.dp, vertical = 10.dp).testTag("timeframe_${tf.code}")) { Text(tf.label.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else TvTextSecondary) } } }
}

@Composable
private fun MarketConditionCard(structure: MarketStructureSnapshot, indicators: TechnicalIndicators) {
    val bullishStructure = structure.trend == "Bullish structure"
    val bearishStructure = structure.trend == "Bearish structure"
    val emaBullish = indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 > indicators.ema50
    val emaBearish = indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 < indicators.ema50
    val macdBullish = indicators.macdHist.isFinite() && indicators.macdHist > 0
    val macdBearish = indicators.macdHist.isFinite() && indicators.macdHist < 0
    val bullishScore = listOf(bullishStructure, emaBullish, macdBullish).count { it }
    val bearishScore = listOf(bearishStructure, emaBearish, macdBearish).count { it }
    val title: String
    val color: Color
    val detail: String
    when {
        bullishScore >= 2 && bullishScore > bearishScore -> { title = "CENDERUNG NAIK"; color = TvGreen; detail = "Struktur pasar dan indikator lebih banyak mendukung kenaikan." }
        bearishScore >= 2 && bearishScore > bullishScore -> { title = "CENDERUNG TURUN"; color = TvRed; detail = "Struktur pasar dan indikator lebih banyak menunjukkan tekanan turun." }
        else -> { title = "MASIH CAMPURAN"; color = WarningAmber; detail = "Struktur pasar dan indikator belum cukup searah." }
    }
    AnalysisCard {
        SectionTitle("KONDISI PASAR", Icons.Default.TrendingUp)
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(title, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(7.dp))
        Text(detail, color = TvTextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(9.dp))
        Text("RSI ${formatIndicator(indicators.rsi14)}  •  EMA20/50 ${emaRelation(indicators)}", color = TvTextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun RecommendationCard(signal: AISignalState) {
    val color = when (signal.action) { SignalAction.BUY -> TvGreen; SignalAction.SELL -> TvRed; SignalAction.HOLD -> WarningAmber }
    val title = when (signal.action) { SignalAction.BUY -> "BISA PERTIMBANGKAN BELI"; SignalAction.SELL -> "PERTIMBANGKAN JUAL"; SignalAction.HOLD -> "TAHAN / TUNGGU" }
    val description = when (signal.action) { SignalAction.BUY -> "Tren cukup mendukung, tetapi tetap gunakan area masuk dan batas risiko."; SignalAction.SELL -> "Tekanan turun lebih dominan. Jangan buru-buru masuk sebelum struktur membaik."; SignalAction.HOLD -> "Belum ada alasan yang cukup kuat untuk masuk atau keluar sekarang." }
    AnalysisCard { SectionTitle("REKOMENDASI", if (signal.action == SignalAction.BUY) Icons.Default.TrendingUp else if (signal.action == SignalAction.SELL) Icons.Default.TrendingDown else Icons.Default.Shield); Spacer(Modifier.height(8.dp)); Text(title, fontSize = 23.sp, fontWeight = FontWeight.Black, color = color); Spacer(Modifier.height(5.dp)); Text(description, fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp); Spacer(Modifier.height(9.dp)); Text("Kekuatan sinyal: ${signal.confidence}/100", fontSize = 13.sp, color = TvTextSecondary) }
}

@Composable
private fun WhyCard(signal: AISignalState, indicators: TechnicalIndicators, structure: MarketStructureSnapshot) {
    val reasons = buildSimpleReasons(signal, indicators, structure)
    AnalysisCard { SectionTitle("KENAPA?", Icons.Default.CheckCircle); Spacer(Modifier.height(7.dp)); reasons.forEach { reason -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Default.CheckCircle, null, tint = TvGreen, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)); Text(reason, fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp) } }; Spacer(Modifier.height(6.dp)); Text("Skor adalah kekuatan setup, bukan jaminan profit.", fontSize = 12.sp, color = TvTextSecondary) }
}

@Composable
private fun AiAssistantCard(auditText: String?, auditLoading: Boolean, geminiText: String?, geminiLoading: Boolean, onGroq: () -> Unit, onGemini: () -> Unit, onClearGroq: () -> Unit, onClearGemini: () -> Unit) {
    val context = LocalContext.current
    AnalysisCard {
        SectionTitle("ANALISA AI", Icons.Default.AutoAwesome)
        Spacer(Modifier.height(5.dp))
        Text("Minta AI menjelaskan kondisi market dengan bahasa sederhana. Angka Entry/TP/SL tetap berasal dari engine aplikasi.", fontSize = 13.sp, color = TvTextSecondary, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onGroq, enabled = !auditLoading && !geminiLoading, modifier = Modifier.weight(1f)) { Text(if (auditLoading) "Groq..." else "Groq") }
            Button(onClick = onGemini, enabled = !auditLoading && !geminiLoading, modifier = Modifier.weight(1f)) { Text(if (geminiLoading) "Gemini..." else "Gemini") }
        }
        Spacer(Modifier.height(9.dp))
        Button(onClick = { val intent = context.packageManager.getLaunchIntentForPackage("id.co.bitcoin"); if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent) } else Toast.makeText(context, "Aplikasi Indodax belum terpasang di HP ini.", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth().height(46.dp).testTag("detail_open_indodax_button"), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5)), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Icon(Icons.Default.OpenInNew, null, Modifier.size(19.dp), tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Buka Indodax", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        if (auditText != null) { Spacer(Modifier.height(10.dp)); Text("GROQ • ANALISA", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvGreen); Spacer(Modifier.height(4.dp)); Text(auditText, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 19.sp); Text("Hapus", fontSize = 11.sp, color = TvTextSecondary, modifier = Modifier.padding(top = 4.dp).clickable { onClearGroq() }) }
        if (geminiText != null) { Spacer(Modifier.height(10.dp)); Text("GEMINI • ANALISA", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6FB8FF)); Spacer(Modifier.height(4.dp)); Text(geminiText, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 19.sp); Text("Hapus", fontSize = 11.sp, color = TvTextSecondary, modifier = Modifier.padding(top = 4.dp).clickable { onClearGemini() }) }
    }
}

@Composable
private fun ImportantLevelsCard(signal: AISignalState, structure: MarketStructureSnapshot, price: Double) {
    AnalysisCard {
        Text("LEVEL PENTING", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2196F3), letterSpacing = 0.8.sp)
        Spacer(Modifier.height(10.dp))
        ImportantLevelRow(dotColor = Color(0xFF32D74B), label = "Support Terdekat", value = structure.support?.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
        ImportantLevelRow(dotColor = TvRed, label = "Resistance Terdekat", value = structure.resistance?.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
        Spacer(Modifier.height(8.dp)); Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF))); Spacer(Modifier.height(8.dp))
        if (signal.action != SignalAction.HOLD && signal.entryPrice > 0) {
            ImportantLevelRow(dotColor = Color(0xFF2196F3), label = "Entry Area", value = PriceFormatter.formatPrice(signal.entryPrice))
            ImportantLevelRow(dotColor = Color(0xFF32D74B), label = "Take Profit 1 (TP1)", value = signal.targetPrice1.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
            ImportantLevelRow(dotColor = Color(0xFF32D74B), label = "Take Profit 2 (TP2)", value = signal.targetPrice2.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
            Spacer(Modifier.height(8.dp)); Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF))); Spacer(Modifier.height(8.dp))
            ImportantLevelRow(dotColor = TvRed, label = "Stop Loss (SL)", value = signal.stopLoss.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
            Spacer(Modifier.height(8.dp)); Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF))); Spacer(Modifier.height(8.dp))
            ImportantLevelRow(dotColor = Color(0xFF9C27B0), label = "Risk / Reward", value = signal.riskRewardRatio.ifBlank { "1 : 1,5" })
        } else {
            ImportantLevelRow(dotColor = Color(0xFF2196F3), label = "Entry Area", value = "Belum ada setup")
            ImportantLevelRow(dotColor = Color(0xFF32D74B), label = "Take Profit 1 (TP1)", value = "Belum tersedia")
            ImportantLevelRow(dotColor = Color(0xFF32D74B), label = "Take Profit 2 (TP2)", value = "Belum tersedia")
            Spacer(Modifier.height(8.dp)); Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF))); Spacer(Modifier.height(8.dp))
            ImportantLevelRow(dotColor = TvRed, label = "Stop Loss (SL)", value = "Belum tersedia")
            Spacer(Modifier.height(8.dp)); Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF))); Spacer(Modifier.height(8.dp))
            ImportantLevelRow(dotColor = Color(0xFF9C27B0), label = "Risk / Reward", value = "Belum tersedia")
            if (price > 0 && structure.support != null && structure.resistance != null) { Spacer(Modifier.height(6.dp)); Text("Pantau reaksi harga di antara support dan resistance sebelum menentukan entry.", fontSize = 12.sp, color = TvTextSecondary, lineHeight = 18.sp) }
        }
    }
}

@Composable
private fun ImportantLevelRow(dotColor: Color, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape)); Spacer(Modifier.width(10.dp)); Text(text = label, fontSize = 13.sp, color = TvTextSecondary, maxLines = 1)
        }
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
    }
}

@Composable
private fun TechnicalDetailsCard(indicators: TechnicalIndicators, structure: MarketStructureSnapshot, volume24h: Double) {
    var expanded by remember { mutableStateOf(true) }
    AnalysisCard {
        Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("DETAIL TEKNIKAL", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2196F3), letterSpacing = 0.8.sp)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = if (expanded) "Collapse" else "Expand", tint = TvTextSecondary, modifier = Modifier.size(22.dp).rotate(if (expanded) 180f else 0f))
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            val rsiVal = indicators.rsi14
            val rsiFormatted = if (rsiVal.isFinite()) String.format(java.util.Locale("id", "ID"), "%.2f", rsiVal) else "—"
            val rsiBull = rsiVal.isFinite() && rsiVal >= 50
            val rsiStatus = if (rsiVal.isFinite()) (if (rsiVal > 70) "Jenuh Beli" else if (rsiVal < 30) "Jenuh Jual" else if (rsiVal >= 50) "Bullish" else "Bearish") else "—"
            val rsiColor = if (rsiVal > 70) TvRed else if (rsiVal < 30) TvGreen else if (rsiBull) TvGreen else TvRed
            val macdVal = indicators.macdHist
            val macdBull = macdVal.isFinite() && macdVal >= 0
            val macdStatus = if (macdBull) "Bullish" else "Bearish"
            val macdColor = if (macdBull) TvGreen else TvRed
            val ema20 = indicators.ema20
            val ema50 = indicators.ema50
            val ema200 = indicators.ema200
            val emaBull = ema20.isFinite() && ema50.isFinite() && ema20 > ema50
            val emaStatus = if (emaBull) "Bullish" else "Bearish"
            val emaColor = if (emaBull) TvGreen else TvRed
            val emaSub = when { !ema20.isFinite() -> "Belum cukup data"; ema20 > ema50 && (!ema200.isFinite() || ema50 > ema200) -> "20 > 50 > 200"; ema20 < ema50 && (!ema200.isFinite() || ema50 < ema200) -> "20 < 50 < 200"; else -> "20 ≈ 50" }
            val volStatus = "Tinggi"
            val atrVal = if (indicators.atr.isFinite()) String.format(java.util.Locale("id", "ID"), "%.2f", indicators.atr) else "—"
            val bbLabel = bollingerLabel(indicators)
            val structLabel = when (structure.trend) { "Bullish structure" -> "HH + HL (Bullish)"; "Bearish structure" -> "LH + LL (Bearish)"; else -> "Range / Transisi" }
            val trendLabel = simpleTrend(indicators, structure)
            val trendColor = if (trendLabel.contains("Naik") || trendLabel.contains("Cenderung naik")) TvGreen else if (trendLabel.contains("Turun") || trendLabel.contains("Cenderung turun")) TvRed else TvTextPrimary
            val volaLabel = volatilityLabel(indicators.atr, indicators.ema20)
            DetailedTechRow(icon = Icons.Default.TrendingUp, iconTint = Color(0xFFFF5722), label = "RSI (14)", value = rsiFormatted, status = rsiStatus, statusColor = rsiColor)
            DetailedTechRow(icon = Icons.Default.TrendingUp, iconTint = Color(0xFFFFC107), label = "MACD", status = macdStatus, statusColor = macdColor)
            DetailedTechRow(icon = Icons.Default.TrendingUp, iconTint = Color(0xFF00BCD4), label = "EMA 20 / 50 / 200", status = emaStatus, statusColor = emaColor, subtext = emaSub)
            DetailedTechRow(icon = Icons.Default.TrendingUp, iconTint = Color(0xFF3F51B5), label = "Volume (24 jam)", status = volStatus, statusColor = TvGreen)
            DetailedTechRow(icon = Icons.Default.Shield, iconTint = Color(0xFF9C27B0), label = "ATR (14)", value = atrVal, statusColor = TvTextPrimary)
            DetailedTechRow(icon = Icons.Default.Info, iconTint = Color(0xFFE91E63), label = "Bollinger Bands", status = bbLabel, statusColor = TvTextSecondary)
            DetailedTechRow(icon = Icons.Default.CheckCircle, iconTint = Color(0xFFFFEB3B), label = "Market Structure", status = structLabel, statusColor = if (structLabel.contains("Bullish")) TvGreen else if (structLabel.contains("Bearish")) TvRed else WarningAmber)
            DetailedTechRow(icon = Icons.Default.TrendingUp, iconTint = TvGreen, label = "Trend", status = trendLabel, statusColor = trendColor)
            DetailedTechRow(icon = Icons.Default.Info, iconTint = Color(0xFFFF9800), label = "Volatilitas", status = volaLabel, statusColor = TvTextPrimary, showDivider = false)
        }
    }
}

@Composable
private fun DetailedTechRow(icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color, label: String, value: String? = null, status: String? = null, statusColor: Color = TvTextPrimary, subtext: String? = null, showDivider: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(10.dp)); Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TvTextPrimary, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (value != null) Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                if (status != null) Text(text = status, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = statusColor)
            }
        }
        if (!subtext.isNullOrBlank()) { Spacer(Modifier.height(2.dp)); Row(modifier = Modifier.fillMaxWidth()) { Spacer(Modifier.width(29.dp)); Text(text = subtext, fontSize = 11.sp, color = TvTextSecondary, maxLines = 1) } }
        if (showDivider) { Spacer(Modifier.height(6.dp)); Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF))) }
    }
}

@Composable
private fun MonitorCard(signal: AISignalState, structure: MarketStructureSnapshot, price: Double, cached: Boolean) {
    AnalysisCard { SectionTitle("YANG PERLU DIPANTAU", Icons.Default.Info); Spacer(Modifier.height(7.dp)); val support = structure.support; val resistance = structure.resistance; when { support != null && price > 0 && price < support -> Text("Harga berada di bawah support ${PriceFormatter.formatPrice(support)}. Tunggu konfirmasi sebelum mengambil posisi.", fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp); support != null && price > 0 && price <= support * 1.01 -> Text("Harga sedang dekat support ${PriceFormatter.formatPrice(support)}. Perhatikan apakah support bertahan atau jebol.", fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp); resistance != null && price > 0 && price >= resistance * 0.99 -> Text("Harga sedang dekat resistance ${PriceFormatter.formatPrice(resistance)}. Perhatikan apakah level ini ditembus dengan volume.", fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp); else -> Text("Pantau perubahan tren, support/resistance, dan volume. Jangan hanya melihat satu indikator.", fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp) }; Spacer(Modifier.height(8.dp)); if (signal.action == SignalAction.HOLD) Text("Saat ini belum ada setup kuat. Menunggu juga adalah keputusan.", fontSize = 13.sp, color = WarningAmber); if (cached) Text("Data sedang cache, jadi jangan anggap perubahan harga sebagai live.", fontSize = 13.sp, color = WarningAmber, modifier = Modifier.padding(top = 5.dp)) }
}

@Composable
private fun DisclaimerCard() { AnalysisCard { IconTextRow(Icons.Default.Shield, "Ini bukan saran finansial. Analisa dapat berubah kapan saja, jadi tetap gunakan manajemen risiko.", WarningAmber) } }
@Composable
private fun AnalysisCard(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxWidth().background(AnalysisCard, RoundedCornerShape(16.dp)).border(1.dp, AnalysisBorder, RoundedCornerShape(16.dp)).padding(16.dp), content = content) }
@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) { Row(verticalAlignment = Alignment.CenterVertically) { if (icon != null) { Icon(icon, null, tint = if (text == "DETAIL TEKNIKAL") Color(0xFF6FB8FF) else TvGreen, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(8.dp)) }; Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary, letterSpacing = 0.5.sp) } }
@Composable
private fun IconTextRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { Icon(icon, null, tint = color, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(9.dp)); Text(text, fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp) } }

private fun buildSimpleReasons(signal: AISignalState, indicators: TechnicalIndicators, structure: MarketStructureSnapshot): List<String> {
    val result = mutableListOf<String>()
    if (indicators.ema20.isFinite() && indicators.ema50.isFinite()) result += if (indicators.ema20 > indicators.ema50) "Harga rata-rata jangka pendek masih di atas rata-rata menengah." else "Rata-rata harga jangka pendek masih di bawah rata-rata menengah."
    if (indicators.macdHist.isFinite()) result += if (indicators.macdHist >= 0) "Momentum naik masih lebih dominan." else "Momentum turun masih lebih dominan."
    if (structure.trend == "Bullish structure") result += "Struktur pasar membentuk Higher High dan Higher Low." else if (structure.trend == "Bearish structure") result += "Struktur pasar membentuk Lower High dan Lower Low."
    if (indicators.rsi14.isFinite()) result += when { indicators.rsi14 > 70 -> "RSI sudah tinggi, jadi risiko koreksi perlu diperhatikan."; indicators.rsi14 < 30 -> "RSI sudah rendah, jadi potensi pantulan perlu dikonfirmasi."; else -> "RSI masih di area tengah, belum menunjukkan kondisi ekstrem." }
    if (result.isEmpty()) result += signal.reasoning.firstOrNull() ?: "Data teknikal belum cukup untuk menjelaskan arah pasar."
    return result.take(3)
}

private fun formatIndicator(value: Double): String = if (value.isFinite()) String.format("%.2f", value) else "—"
private fun emaRelation(i: TechnicalIndicators): String = when { !i.ema20.isFinite() || !i.ema50.isFinite() -> "belum cukup data"; i.ema20 > i.ema50 -> "20 > 50"; i.ema20 < i.ema50 -> "20 < 50"; else -> "20 ≈ 50" }
private fun emaLabel(i: TechnicalIndicators): String = when { !i.ema20.isFinite() -> "Belum cukup data"; i.ema20 > i.ema50 && i.ema50 > i.ema200 -> "20 > 50 > 200 • cenderung naik"; i.ema20 < i.ema50 && i.ema50 < i.ema200 -> "20 < 50 < 200 • cenderung turun"; else -> "Belum searah" }
private fun macdLabel(hist: Double): String = when { !hist.isFinite() -> "Belum cukup data"; hist > 0 -> "Momentum naik"; hist < 0 -> "Momentum turun"; else -> "Netral" }
private fun rsiLabel(rsi: Double): String = when { !rsi.isFinite() -> "Belum cukup data"; rsi > 70 -> "Jenuh beli"; rsi < 30 -> "Jenuh jual"; else -> "Normal" }
private fun bollingerLabel(i: TechnicalIndicators): String = when { !i.bbUpper.isFinite() || !i.bbLower.isFinite() -> "Belum cukup data"; else -> "Harga di antara batas atas & bawah" }
private fun volatilityLabel(atr: Double, ema20: Double): String { if (!atr.isFinite() || !ema20.isFinite() || ema20 <= 0) return "Belum cukup data"; val pct = atr / ema20 * 100.0; return when { pct >= 8 -> "Tinggi"; pct >= 4 -> "Sedang"; else -> "Rendah" } }
private fun simpleStructure(snapshot: MarketStructureSnapshot): String = when (snapshot.trend) { "Bullish structure" -> "HH + HL (naik)"; "Bearish structure" -> "LH + LL (turun)"; else -> "Range / transisi" }
private fun simpleTrend(indicators: TechnicalIndicators, structure: MarketStructureSnapshot): String = when { structure.trend == "Bullish structure" -> "Naik"; structure.trend == "Bearish structure" -> "Turun"; indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 > indicators.ema50 -> "Cenderung naik"; indicators.ema20.isFinite() && indicators.ema50.isFinite() -> "Cenderung turun"; else -> "Belum jelas" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SymbolPickerSheet(popularPairs: List<TradingPair>, watchlist: Set<String>, currentSymbol: String, onDismiss: () -> Unit, onSelect: (TradingPair) -> Unit, onSelectAndWatch: (String) -> Unit, onToggleWatch: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customInput by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color(0xFF101720), contentColor = TvTextPrimary) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Pilih Koin", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary); Spacer(Modifier.height(5.dp)); Text("Ketik simbol Indodax, misalnya ADA, AVAX, atau SHIB.", fontSize = 13.sp, color = TvTextSecondary); Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = customInput, onValueChange = { customInput = it.uppercase().filter { c -> c.isLetterOrDigit() } }, modifier = Modifier.fillMaxWidth().testTag("custom_symbol_input"), singleLine = true, placeholder = { Text("Contoh: ADA atau ADAIDR", color = TvTextSecondary, fontSize = 14.sp) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (customInput.isNotBlank()) onSelectAndWatch(customInput) }), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TvGreen, unfocusedBorderColor = Color(0xFF2A3540), focusedTextColor = TvTextPrimary, unfocusedTextColor = TvTextPrimary, cursorColor = TvGreen), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (customInput.isNotBlank()) onSelectAndWatch(customInput) }, enabled = customInput.isNotBlank(), modifier = Modifier.weight(1f).testTag("add_pair_and_watch_button"), colors = ButtonDefaults.buttonColors(containerColor = TvGreen), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = Color.Black); Spacer(Modifier.width(6.dp)); Text("Buka + Simpan", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
                OutlinedButton(onClick = { if (customInput.isNotBlank()) onSelect(TradingPair.fromCustomSymbol(customInput)) }, enabled = customInput.isNotBlank(), modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, TvGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen)) { Text("Hanya Buka", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(16.dp)); Text("KOIN POPULER", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvGreen, letterSpacing = 0.6.sp); Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                popularPairs.forEach { pair ->
                    val watched = pair.symbol in watchlist; val selected = pair.symbol == currentSymbol
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) TvGreen.copy(alpha = 0.12f) else Color(0x14FFFFFF)).clickable { onSelect(pair) }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("pair_item_${pair.symbol}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(pair.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary); Text(pair.symbol, fontSize = 12.sp, color = TvTextSecondary) }
                        IconButton(onClick = { onToggleWatch(pair.symbol) }, modifier = Modifier.size(38.dp).testTag("watch_toggle_${pair.symbol}")) { Icon(if (watched) Icons.Default.Star else Icons.Default.StarBorder, if (watched) "Hapus watchlist" else "Tambah watchlist", tint = if (watched) TvGold else TvTextSecondary, modifier = Modifier.size(22.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSwitchToggle(isScalping: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onToggle(false) }, modifier = Modifier.weight(1f).height(36.dp), colors = ButtonDefaults.buttonColors(containerColor = if (!isScalping) TvGreen else Color(0xFF101720)), shape = RoundedCornerShape(10.dp), border = if (!isScalping) null else BorderStroke(1.dp, Color(0xFF1F3540))) {
            Text("📈 Mode Swing (Long)", color = if (!isScalping) Color.Black else TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Button(onClick = { onToggle(true) }, modifier = Modifier.weight(1f).height(36.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isScalping) TvGreen else Color(0xFF101720)), shape = RoundedCornerShape(10.dp), border = if (isScalping) null else BorderStroke(1.dp, Color(0xFF1F3540))) {
            Text("⚡ Mode Scalping (Cepat)", color = if (isScalping) Color.Black else TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
