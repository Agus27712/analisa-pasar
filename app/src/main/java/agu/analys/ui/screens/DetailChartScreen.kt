package agu.analys.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.AISignalState
import agu.analys.model.MarketConnectionState
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.model.TradingPair
import agu.analys.ui.animation.SmoothPriceText
import agu.analys.ui.components.SimpleComposeChart
import agu.analys.ui.components.SpotPositionCard
import agu.analys.ui.components.dashboard.ModeSwitchToggle
import agu.analys.ui.components.detail.*
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.TradingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailChartScreen(
    viewModel: TradingViewModel,
    onNavigateToDashboard: () -> Unit,
    onOpenLandscapeChart: () -> Unit,
    modifier: Modifier = Modifier
) {
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = TvBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateToDashboard, modifier = Modifier.testTag("back_to_dashboard_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Dashboard", tint = TvTextPrimary, modifier = Modifier.size(28.dp))
                    }
                },
                title = {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { showSymbolPickerSheet = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp).testTag("symbol_picker_trigger"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("${selectedPair.baseAsset}/${selectedPair.quoteAsset}", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary)
                            Text("Data real Indodax", fontSize = 11.sp, color = TvTextSecondary)
                        }
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Default.KeyboardArrowDown, "Pilih Koin", tint = TvTextSecondary, modifier = Modifier.size(21.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleWatchlist(selectedPair.symbol) }, modifier = Modifier.testTag("toggle_watchlist_button")) {
                        Icon(
                            if (selectedPair.symbol in watchlist) Icons.Default.Star else Icons.Default.StarBorder,
                            null,
                            tint = if (selectedPair.symbol in watchlist) TvGold else TvTextSecondary,
                            modifier = Modifier.size(27.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenLandscapeChart,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen),
                        border = BorderStroke(1.dp, TvGreen),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
                        modifier = Modifier.padding(end = 7.dp).testTag("trigger_landscape_chart_button")
                    ) {
                        Icon(Icons.Default.CropRotate, "Landscape", modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Chart", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TvBackground)
            )
        }
    ) { innerPadding ->
        if (isChartExpanded) {
            // Landscape/fullscreen: tetap besar
            Box(Modifier.fillMaxSize().padding(innerPadding).background(Color(0xFF0D0E12))) {
                SimpleComposeChart(
                    prices = emptyList(),
                    candles = recentCandles,
                    currentPrice = currentTick?.price ?: 0.0,
                    isPositiveTrend = (currentTick?.change24h ?: 0.0) >= 0,
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = { viewModel.toggleChartExpanded() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape).background(Color(0xCC121212))
                ) {
                    Icon(Icons.Default.FullscreenExit, "Tutup", tint = TvTextPrimary, modifier = Modifier.size(28.dp))
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                if (isShowingCached) Text("Data terakhir tersimpan, belum live.", fontSize = 12.sp, color = WarningAmber, modifier = Modifier.padding(bottom = 5.dp))
                currentTick?.let { PriceHeader(it.price, it.change24h) }
                Spacer(Modifier.height(8.dp))
                ModeSwitchToggle(isScalping = isScalpingMode, onToggle = { viewModel.setScalpingMode(it) })
                Spacer(Modifier.height(8.dp))
                // ⑤ Single source of truth — ChartLayoutDefaults.PortraitHeight
                ChartLayout { chartModifier ->
                    SimpleComposeChart(
                        prices = emptyList(),
                        candles = recentCandles,
                        currentPrice = currentTick?.price ?: 0.0,
                        isPositiveTrend = (currentTick?.change24h ?: 0.0) >= 0,
                        modifier = chartModifier
                    )
                }
                Spacer(Modifier.height(8.dp))
                TimeframeSelector(selectedTimeframe, viewModel::selectTimeframe)
                if (connectionState is MarketConnectionState.ConnectionLost) {
                    Spacer(Modifier.height(8.dp))
                    AnalysisCard {
                        IconTextRow(
                            Icons.Default.Info,
                            if (isShowingCached) "Koneksi terputus. Harga di layar adalah data terakhir yang tersimpan."
                            else "Data market sedang tidak tersedia.",
                            TvRed
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                MarketConditionCard(marketStructure, currentIndicators, aiSignalState, isScalpingMode)
                Spacer(Modifier.height(10.dp))
                ProgressEntryCard(aiSignalState, isScalpingMode)
                Spacer(Modifier.height(10.dp))
                RecommendationCard(aiSignalState, isScalpingMode)
                Spacer(Modifier.height(10.dp))
                WhyCard(aiSignalState, currentIndicators, marketStructure)
                Spacer(Modifier.height(10.dp))
                AiAssistantCard(
                    auditReportText, isAuditLoading, geminiSummaryText, isGeminiLoading,
                    viewModel::requestDeepAiAudit, viewModel::requestGeminiChartSummary,
                    viewModel::clearAuditReport, viewModel::clearGeminiSummary
                )
                Spacer(Modifier.height(10.dp))
                SpotPositionCard(
                    symbol = selectedPair.symbol,
                    signal = aiSignalState,
                    position = spotPosition,
                    currentPrice = currentTick?.price ?: 0.0,
                    onPositionChanged = viewModel::refreshSpotPosition
                )
                Spacer(Modifier.height(10.dp))
                ImportantLevelsCard(aiSignalState, marketStructure, currentTick?.price ?: 0.0)
                Spacer(Modifier.height(10.dp))
                TechnicalDetailsCard(
                    currentIndicators,
                    marketStructure,
                    currentTick?.volume24h ?: 0.0,
                    scalping = isScalpingMode
                )
                Spacer(Modifier.height(10.dp))
                MonitorCard(aiSignalState, marketStructure, currentTick?.price ?: 0.0, isShowingCached)
                Spacer(Modifier.height(10.dp))
                DisclaimerCard()
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    if (showSymbolPickerSheet) {
        SymbolPickerSheet(
            popularPairs = TradingPair.POPULAR_PAIRS,
            watchlist = watchlist,
            currentSymbol = selectedPair.symbol,
            onDismiss = { showSymbolPickerSheet = false },
            onSelect = { pair -> viewModel.selectPair(pair); showSymbolPickerSheet = false },
            onSelectAndWatch = { raw -> viewModel.selectAndWatch(raw, addToWatchlist = true); showSymbolPickerSheet = false },
            onToggleWatch = { viewModel.toggleWatchlist(it) }
        )
    }
}

@Composable
private fun PriceHeader(price: Double, change: Double) {
    val changeColor = if (change >= 0) TvGreen else TvRed
    Column(Modifier.fillMaxWidth()) {
        SmoothPriceText(
            price = price,
            color = TvTextPrimary,
            fontSize = 29.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.testTag("live_price_header")
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(PriceFormatter.formatPercentage(change), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = changeColor)
            Spacer(Modifier.width(7.dp))
            Text("(24 jam)", fontSize = 14.sp, color = TvTextSecondary)
        }
    }
}

@Composable
private fun TimeframeSelector(selected: Timeframe, onSelect: (Timeframe) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf(Timeframe.M1, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1).forEach { tf ->
            val active = selected == tf
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (active) Color(0xFF087FF5) else Color(0xFF162536))
                    .clickable { onSelect(tf) }
                    .padding(horizontal = 17.dp, vertical = 10.dp)
                    .testTag("timeframe_${tf.code}")
            ) {
                Text(tf.label.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else TvTextSecondary)
            }
        }
    }
}

@Composable
private fun WhyCard(signal: AISignalState, indicators: TechnicalIndicators, structure: MarketStructureSnapshot) {
    val reasons = buildSimpleReasons(signal, indicators, structure)
    AnalysisCard {
        SectionTitle("KENAPA?", Icons.Default.CheckCircle)
        Spacer(Modifier.height(7.dp))
        reasons.forEach { reason ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.CheckCircle, null, tint = TvGreen, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Text(reason, fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Skor adalah kekuatan setup, bukan jaminan profit.", fontSize = 12.sp, color = TvTextSecondary)
    }
}

private fun buildSimpleReasons(signal: AISignalState, indicators: TechnicalIndicators, structure: MarketStructureSnapshot): List<String> {
    val result = mutableListOf<String>()
    if (indicators.ema20.isFinite() && indicators.ema50.isFinite())
        result += if (indicators.ema20 > indicators.ema50) "EMA fast masih di atas EMA slow." else "EMA fast masih di bawah EMA slow."
    if (indicators.macdHist.isFinite())
        result += if (indicators.macdHist >= 0) "Momentum MACD masih naik." else "Momentum MACD masih turun."
    when (structure.trend) {
        "Bullish structure" -> result += "Struktur pasar membentuk Higher High dan Higher Low."
        "Bearish structure" -> result += "Struktur pasar membentuk Lower High dan Lower Low."
    }
    if (indicators.rsi14.isFinite()) result += when {
        indicators.rsi14 > 70 -> "RSI sudah tinggi, risiko koreksi perlu diperhatikan."
        indicators.rsi14 < 30 -> "RSI sudah rendah, potensi pantulan perlu dikonfirmasi."
        else -> "RSI masih di area tengah, belum ekstrem."
    }
    if (result.isEmpty()) result += signal.reasoning.firstOrNull() ?: "Data teknikal belum cukup."
    return result.take(3)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SymbolPickerSheet(
    popularPairs: List<TradingPair>,
    watchlist: Set<String>,
    currentSymbol: String,
    onDismiss: () -> Unit,
    onSelect: (TradingPair) -> Unit,
    onSelectAndWatch: (String) -> Unit,
    onToggleWatch: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customInput by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color(0xFF101720), contentColor = TvTextPrimary) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Pilih Koin", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary)
            Spacer(Modifier.height(5.dp))
            Text("Ketik simbol Indodax, misalnya ADA, AVAX, atau SHIB.", fontSize = 13.sp, color = TvTextSecondary)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = customInput,
                onValueChange = { customInput = it.uppercase().filter { c -> c.isLetterOrDigit() } },
                modifier = Modifier.fillMaxWidth().testTag("custom_symbol_input"),
                singleLine = true,
                placeholder = { Text("Contoh: ADA atau ADAIDR", color = TvTextSecondary, fontSize = 14.sp) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (customInput.isNotBlank()) onSelectAndWatch(customInput) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TvGreen, unfocusedBorderColor = Color(0xFF2A3540),
                    focusedTextColor = TvTextPrimary, unfocusedTextColor = TvTextPrimary
                )
            )
            Spacer(Modifier.height(12.dp))
            Text("POPULAR", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary)
            Spacer(Modifier.height(7.dp))
            popularPairs.forEach { pair ->
                val active = pair.symbol == currentSymbol
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(pair) }
                        .background(if (active) Color(0xFF162536) else Color.Transparent)
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${pair.baseAsset}/${pair.quoteAsset}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                        Text(pair.symbol, fontSize = 10.sp, color = TvTextSecondary)
                    }
                    IconButton(onClick = { onToggleWatch(pair.symbol) }) {
                        Icon(if (pair.symbol in watchlist) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (pair.symbol in watchlist) TvGold else TvTextSecondary)
                    }
                }
            }
        }
    }
}
