package agu.analys.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import kotlin.math.abs

private val TvGold = Color(0xFFFFD54A)
private val AnalysisCard = Color(0xFF0D1722)
private val AnalysisBorder = Color(0xFF1A3347)
private val InfoBlue = Color(0xFF2196F3)
private val WarningAmber = Color(0xFFFFB300)

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
    val isShowingCached by viewModel.isShowingCachedData.collectAsStateWithLifecycle()
    val spotPosition by viewModel.spotPosition.collectAsStateWithLifecycle()
    val marketStructure = remember(recentCandles) { MarketStructureAnalyzer.analyze(recentCandles) }
    var showSymbolPickerSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = TvBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateToDashboard, modifier = Modifier.testTag("back_to_dashboard_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Dashboard", tint = TvTextPrimary)
                    }
                },
                title = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showSymbolPickerSheet = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("symbol_picker_trigger"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(selectedPair.baseAsset + "/" + selectedPair.quoteAsset, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary)
                            Text("Data real Indodax", fontSize = 8.sp, color = TvTextSecondary)
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.KeyboardArrowDown, "Pilih Koin", tint = TvTextSecondary, modifier = Modifier.size(18.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleWatchlist(selectedPair.symbol) }, modifier = Modifier.testTag("toggle_watchlist_button")) {
                        Icon(
                            if (selectedPair.symbol in watchlist) Icons.Default.Star else Icons.Default.StarBorder,
                            if (selectedPair.symbol in watchlist) "Hapus dari watchlist" else "Tambah ke watchlist",
                            tint = if (selectedPair.symbol in watchlist) TvGold else TvTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenLandscapeChart,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen),
                        border = BorderStroke(1.dp, TvGreen),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp),
                        modifier = Modifier.padding(end = 7.dp).testTag("trigger_landscape_chart_button")
                    ) {
                        Icon(Icons.Default.CropRotate, "Landscape", modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Chart", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TvBackground)
            )
        }
    ) { innerPadding ->
        if (isChartExpanded) {
            Box(Modifier.fillMaxSize().padding(innerPadding).background(Color(0xFF0D0E12))) {
                SimpleComposeChart(prices = emptyList(), candles = recentCandles, currentPrice = currentTick?.price ?: 0.0, isPositiveTrend = (currentTick?.change24h ?: 0.0) >= 0, modifier = Modifier.fillMaxSize())
                IconButton(onClick = { viewModel.toggleChartExpanded() }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape).background(Color(0xCC121212))) { Icon(Icons.Default.FullscreenExit, "Tutup", tint = TvTextPrimary) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp)) {
                if (isShowingCached) Text("Data terakhir tersimpan, belum live.", fontSize = 10.sp, color = WarningAmber, modifier = Modifier.padding(bottom = 5.dp))
                currentTick?.let { tick -> PriceHeader(tick.price, tick.change24h) }
                Spacer(Modifier.height(8.dp))
                SimpleComposeChart(prices = emptyList(), candles = recentCandles, currentPrice = currentTick?.price ?: 0.0, isPositiveTrend = (currentTick?.change24h ?: 0.0) >= 0, modifier = Modifier.fillMaxWidth().height(300.dp))
                Spacer(Modifier.height(8.dp))
                TimeframeSelector(selectedTimeframe, viewModel::selectTimeframe)
                if (connectionState is MarketConnectionState.ConnectionLost) {
                    Spacer(Modifier.height(6.dp))
                    AnalysisCard {
                        Text(if (isShowingCached) "Koneksi terputus. Harga di layar adalah data terakhir yang tersimpan." else "Data market sedang tidak tersedia.", fontSize = 10.sp, color = TvRed)
                    }
                }
                Spacer(Modifier.height(8.dp))
                MarketConditionCard(aiSignalState, marketStructure, currentIndicators)
                Spacer(Modifier.height(8.dp))
                RecommendationCard(aiSignalState)
                Spacer(Modifier.height(8.dp))
                WhyCard(aiSignalState, currentIndicators, marketStructure)
                Spacer(Modifier.height(8.dp))
                SpotPositionCard(symbol = selectedPair.symbol.removeSuffix("IDR"), signal = aiSignalState, position = spotPosition, currentPrice = currentTick?.price ?: 0.0, onPositionChanged = viewModel::refreshSpotPosition)
                Spacer(Modifier.height(8.dp))
                ImportantLevelsCard(aiSignalState, marketStructure, currentTick?.price ?: 0.0)
                Spacer(Modifier.height(8.dp))
                TechnicalDetailsCard(currentIndicators, marketStructure, currentTick?.volume24h ?: 0.0)
                Spacer(Modifier.height(8.dp))
                MonitorCard(aiSignalState, marketStructure, currentTick?.price ?: 0.0, isShowingCached)
                Spacer(Modifier.height(8.dp))
                DisclaimerCard()
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    if (showSymbolPickerSheet) {
        SymbolPickerSheet(popularPairs = TradingPair.POPULAR_PAIRS, watchlist = watchlist, currentSymbol = selectedPair.symbol, onDismiss = { showSymbolPickerSheet = false }, onSelect = { pair -> viewModel.selectPair(pair); showSymbolPickerSheet = false }, onSelectAndWatch = { raw -> viewModel.selectAndWatch(raw, addToWatchlist = true); showSymbolPickerSheet = false }, onToggleWatch = { symbol -> viewModel.toggleWatchlist(symbol) })
    }
}

@Composable
private fun PriceHeader(price: Double, change: Double) {
    val changeColor = if (change >= 0) TvGreen else TvRed
    Column(Modifier.fillMaxWidth()) {
        Text(PriceFormatter.formatPrice(price), fontSize = 25.sp, fontWeight = FontWeight.Black, color = TvTextPrimary, modifier = Modifier.testTag("live_price_header"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(PriceFormatter.formatPercentage(change), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = changeColor)
            Spacer(Modifier.width(6.dp))
            Text("(24 jam)", fontSize = 10.sp, color = TvTextSecondary)
        }
    }
}

@Composable
private fun TimeframeSelector(selected: Timeframe, onSelect: (Timeframe) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1).forEach { tf ->
            val active = selected == tf
            Box(Modifier.clip(RoundedCornerShape(9.dp)).background(if (active) Color(0xFF087FF5) else Color(0xFF162536)).clickable { onSelect(tf) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("timeframe_${tf.code}")) {
                Text(tf.label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else TvTextSecondary)
            }
        }
    }
}

@Composable
private fun MarketConditionCard(signal: AISignalState, structure: MarketStructureSnapshot, indicators: TechnicalIndicators) {
    val bullish = signal.action == SignalAction.BUY || structure.trend == "Bullish structure"
    val bearish = signal.action == SignalAction.SELL || structure.trend == "Bearish structure"
    val title = when { bullish && !bearish -> "CENDERUNG NAIK"; bearish && !bullish -> "CENDERUNG TURUN"; else -> "MASIH CAMPURAN" }
    val color = when { bullish && !bearish -> TvGreen; bearish && !bullish -> TvRed; else -> WarningAmber }
    val detail = when { bullish && !bearish -> "Harga dan beberapa indikator masih mendukung kenaikan."; bearish && !bullish -> "Harga dan beberapa indikator masih menunjukkan tekanan turun."; else -> "Sinyal belum cukup searah. Lebih aman menunggu konfirmasi." }
    AnalysisCard {
        SectionTitle("KONDISI PASAR")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).background(color, CircleShape)); Spacer(Modifier.width(8.dp)); Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
        Spacer(Modifier.height(5.dp))
        Text(detail, color = TvTextPrimary, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Text("RSI ${formatIndicator(indicators.rsi14)}  •  EMA20/50 ${emaRelation(indicators)}", color = TvTextSecondary, fontSize = 9.sp)
    }
}

@Composable
private fun RecommendationCard(signal: AISignalState) {
    val color = when (signal.action) { SignalAction.BUY -> TvGreen; SignalAction.SELL -> TvRed; SignalAction.HOLD -> WarningAmber }
    val title = when (signal.action) { SignalAction.BUY -> "BISA PERTIMBANGKAN BELI"; SignalAction.SELL -> "PERTIMBANGKAN JUAL"; SignalAction.HOLD -> "TAHAN / TUNGGU" }
    val description = when (signal.action) { SignalAction.BUY -> "Tren cukup mendukung, tetapi tetap gunakan area masuk dan batas risiko."; SignalAction.SELL -> "Tekanan turun lebih dominan. Jangan buru-buru masuk sebelum struktur membaik."; SignalAction.HOLD -> "Belum ada alasan yang cukup kuat untuk masuk atau keluar sekarang." }
    AnalysisCard {
        SectionTitle("REKOMENDASI")
        Spacer(Modifier.height(7.dp))
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Black, color = color)
        Spacer(Modifier.height(4.dp))
        Text(description, fontSize = 11.sp, color = TvTextPrimary, lineHeight = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text("Kekuatan sinyal: ${signal.confidence}/100", fontSize = 10.sp, color = TvTextSecondary)
    }
}

@Composable
private fun WhyCard(signal: AISignalState, indicators: TechnicalIndicators, structure: MarketStructureSnapshot) {
    val reasons = buildSimpleReasons(signal, indicators, structure)
    AnalysisCard {
        SectionTitle("KENAPA?")
        Spacer(Modifier.height(6.dp))
        reasons.forEach { reason ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(8.dp).background(TvGreen, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(reason, fontSize = 10.sp, color = TvTextPrimary, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text("Skor adalah kekuatan setup, bukan jaminan profit.", fontSize = 9.sp, color = TvTextSecondary)
    }
}

@Composable
private fun ImportantLevelsCard(signal: AISignalState, structure: MarketStructureSnapshot, price: Double) {
    AnalysisCard {
        SectionTitle("LEVEL PENTING")
        Spacer(Modifier.height(6.dp))
        LevelRow("Support terdekat", structure.support, Color(0xFF32D74B))
        LevelRow("Resistance terdekat", structure.resistance, TvRed)
        if (signal.action != SignalAction.HOLD && signal.entryPrice > 0) {
            LevelRow("Area masuk", signal.entryPrice, InfoBlue)
            LevelRow("Target 1", signal.targetPrice1, TvGreen)
            LevelRow("Target 2", signal.targetPrice2, TvGreen)
            LevelRow("Batas rugi", signal.stopLoss, TvRed)
        } else {
            LevelRow("Area masuk", null, InfoBlue, "Belum ada setup yang cukup kuat")
            if (price > 0 && structure.support != null && structure.resistance != null) Text("Pantau reaksi harga di antara support dan resistance sebelum menentukan entry.", fontSize = 9.sp, color = TvTextSecondary, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun LevelRow(label: String, value: Double?, color: Color, emptyText: String = "Belum tersedia") {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 10.sp, color = TvTextSecondary, modifier = Modifier.weight(1f))
        Text(value?.takeIf { it > 0 }?.let(PriceFormatter::formatPrice) ?: emptyText, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
    }
}

@Composable
private fun TechnicalDetailsCard(indicators: TechnicalIndicators, structure: MarketStructureSnapshot, volume24h: Double) {
    AnalysisCard {
        SectionTitle("DETAIL TEKNIKAL")
        Spacer(Modifier.height(5.dp))
        TechnicalRow("RSI (14)", formatIndicator(indicators.rsi14), rsiLabel(indicators.rsi14), if (indicators.rsi14 > 70) TvRed else if (indicators.rsi14 < 30) TvGreen else TvTextSecondary)
        TechnicalRow("MACD", macdLabel(indicators.macdHist), "", if (indicators.macdHist >= 0) TvGreen else TvRed)
        TechnicalRow("EMA 20 / 50 / 200", emaLabel(indicators), "", if (indicators.ema20 > indicators.ema50) TvGreen else TvRed)
        TechnicalRow("Volume (24 jam)", PriceFormatter.formatVolume(volume24h), "", TvGreen)
        TechnicalRow("ATR (14)", formatIndicator(indicators.atr), volatilityLabel(indicators.atr, indicators.ema20), WarningAmber)
        TechnicalRow("Bollinger Bands", bollingerLabel(indicators), "", TvTextSecondary)
        TechnicalRow("Market Structure", simpleStructure(structure), "", if (structure.trend == "Bullish structure") TvGreen else if (structure.trend == "Bearish structure") TvRed else WarningAmber)
        TechnicalRow("Trend", simpleTrend(indicators, structure), "", if (indicators.ema20 >= indicators.ema50) TvGreen else TvRed)
    }
}

@Composable
private fun TechnicalRow(label: String, value: String, extra: String, valueColor: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 10.sp, color = TvTextPrimary)
            Text(value, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = valueColor)
        }
        if (extra.isNotBlank()) Text(extra, fontSize = 8.sp, color = TvTextSecondary, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
private fun MonitorCard(signal: AISignalState, structure: MarketStructureSnapshot, price: Double, cached: Boolean) {
    AnalysisCard {
        SectionTitle("YANG PERLU DIPANTAU")
        Spacer(Modifier.height(6.dp))
        val support = structure.support
        val resistance = structure.resistance
        when {
            support != null && price > 0 && price < support -> Text("Harga berada di bawah support ${PriceFormatter.formatPrice(support)}. Tunggu konfirmasi sebelum mengambil posisi.", fontSize = 10.sp, color = TvTextPrimary)
            support != null && price > 0 && price <= support * 1.01 -> Text("Harga sedang dekat support ${PriceFormatter.formatPrice(support)}. Perhatikan apakah support bertahan atau jebol.", fontSize = 10.sp, color = TvTextPrimary)
            resistance != null && price > 0 && price >= resistance * 0.99 -> Text("Harga sedang dekat resistance ${PriceFormatter.formatPrice(resistance)}. Perhatikan apakah level ini ditembus dengan volume.", fontSize = 10.sp, color = TvTextPrimary)
            else -> Text("Pantau perubahan tren, support/resistance, dan volume. Jangan hanya melihat satu indikator.", fontSize = 10.sp, color = TvTextPrimary)
        }
        Spacer(Modifier.height(6.dp))
        if (signal.action == SignalAction.HOLD) Text("Saat ini belum ada setup kuat. Menunggu juga adalah keputusan.", fontSize = 9.sp, color = WarningAmber)
        if (cached) Text("Data sedang cache, jadi jangan anggap perubahan harga sebagai live.", fontSize = 9.sp, color = WarningAmber, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DisclaimerCard() {
    AnalysisCard {
        Text("⚠  Ini bukan saran finansial.", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = WarningAmber)
        Spacer(Modifier.height(3.dp))
        Text("Analisa dibuat dari data teknikal dan dapat berubah kapan saja. Selalu gunakan manajemen risiko.", fontSize = 9.sp, color = TvTextSecondary, lineHeight = 14.sp)
    }
}

@Composable
private fun AnalysisCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(AnalysisCard, RoundedCornerShape(16.dp)).border(1.dp, AnalysisBorder, RoundedCornerShape(16.dp)).padding(14.dp), content = content)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary, letterSpacing = 0.7.sp)
}

private fun buildSimpleReasons(signal: AISignalState, indicators: TechnicalIndicators, structure: MarketStructureSnapshot): List<String> {
    val result = mutableListOf<String>()
    if (indicators.ema20.isFinite() && indicators.ema50.isFinite()) result += if (indicators.ema20 > indicators.ema50) "Harga rata-rata jangka pendek masih di atas rata-rata menengah." else "Rata-rata harga jangka pendek masih di bawah rata-rata menengah."
    if (indicators.macdHist.isFinite()) result += if (indicators.macdHist >= 0) "Momentum naik masih lebih dominan." else "Momentum turun masih lebih dominan."
    if (structure.trend == "Bullish structure") result += "Struktur pasar membentuk Higher High dan Higher Low."
    else if (structure.trend == "Bearish structure") result += "Struktur pasar membentuk Lower High dan Lower Low."
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
            Text("Pilih Koin", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Ketik simbol Indodax, misalnya ADA, AVAX, atau SHIB.", fontSize = 11.sp, color = TvTextSecondary)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = customInput, onValueChange = { customInput = it.uppercase().filter { c -> c.isLetterOrDigit() } }, modifier = Modifier.fillMaxWidth().testTag("custom_symbol_input"), singleLine = true, placeholder = { Text("Contoh: ADA atau ADAIDR", color = TvTextSecondary, fontSize = 13.sp) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (customInput.isNotBlank()) onSelectAndWatch(customInput) }), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TvGreen, unfocusedBorderColor = Color(0xFF2A3540), focusedTextColor = TvTextPrimary, unfocusedTextColor = TvTextPrimary, cursorColor = TvGreen), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (customInput.isNotBlank()) onSelectAndWatch(customInput) }, enabled = customInput.isNotBlank(), modifier = Modifier.weight(1f).testTag("add_pair_and_watch_button"), colors = ButtonDefaults.buttonColors(containerColor = TvGreen), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = Color.Black); Spacer(Modifier.width(6.dp)); Text("Buka + Simpan", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
                OutlinedButton(onClick = { if (customInput.isNotBlank()) onSelect(TradingPair.fromCustomSymbol(customInput)) }, enabled = customInput.isNotBlank(), modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, TvGreen), colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen)) { Text("Hanya Buka", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(16.dp))
            Text("KOIN POPULER", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvGreen, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                popularPairs.forEach { pair ->
                    val watched = pair.symbol in watchlist
                    val selected = pair.symbol == currentSymbol
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) TvGreen.copy(alpha = 0.12f) else Color(0x14FFFFFF)).clickable { onSelect(pair) }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("pair_item_${pair.symbol}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(pair.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary); Text(pair.symbol, fontSize = 10.sp, color = TvTextSecondary) }
                        IconButton(onClick = { onToggleWatch(pair.symbol) }, modifier = Modifier.size(36.dp).testTag("watch_toggle_${pair.symbol}")) { Icon(if (watched) Icons.Default.Star else Icons.Default.StarBorder, if (watched) "Hapus watchlist" else "Tambah watchlist", tint = if (watched) TvGold else TvTextSecondary, modifier = Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }
}
