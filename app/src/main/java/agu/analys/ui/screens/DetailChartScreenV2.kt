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
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.model.*
import agu.analys.ui.animation.SmoothPriceText
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
fun DetailChartScreenV2(
    viewModel: TradingViewModel,
    onNavigateToDashboard: () -> Unit,
    onOpenLandscapeChart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pair by viewModel.selectedPair.collectAsStateWithLifecycle()
    val tick by viewModel.currentTick.collectAsStateWithLifecycle()
    val candles by viewModel.recentCandles.collectAsStateWithLifecycle()
    val indicators by viewModel.currentIndicators.collectAsStateWithLifecycle()
    val signal by viewModel.aiSignalState.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val isScalping by viewModel.isScalpingMode.collectAsStateWithLifecycle()
    val showingCached by viewModel.isShowingCachedData.collectAsStateWithLifecycle()
    val aiGroq by viewModel.auditReportText.collectAsStateWithLifecycle()
    val aiGemini by viewModel.geminiSummaryText.collectAsStateWithLifecycle()
    val aiLoadingGroq by viewModel.isAuditLoading.collectAsStateWithLifecycle()
    val aiLoadingGemini by viewModel.isGeminiLoading.collectAsStateWithLifecycle()
    var chartVisible by remember { mutableStateOf(false) }
    val provider = remember { AppPreferences(context).aiProvider }
    val marketStructure = remember(candles) { MarketStructureAnalyzer.analyze(candles) }
    val live = connection is MarketConnectionState.Connected
    val changeColor = when {
        tick == null -> TvTextSecondary
        tick!!.change24h >= 0 -> TvGreen
        else -> TvRed
    }

    Column(
        modifier
            .fillMaxSize()
            .background(TvBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onNavigateToDashboard) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = TvTextPrimary, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${pair.baseAsset}/${pair.quoteAsset}", color = TvTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "${if (isScalping) "SCALPING · 1M" else "SWING · 4H"} · ${if (live) "REALTIME" else "REALTIME TERPUTUS"}",
                    color = if (live) TvGreen else TvRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp
                )
            }
        }

        if (showingCached) {
            Text("Data terakhir tersimpan, belum live.", color = TvTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        }

        tick?.let {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101720))
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
                    Text("HARGA ASET", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(3.dp))
                    SmoothPriceText(it.price, TvTextPrimary, 30.sp, FontWeight.Black)
                    Text(PriceFormatter.formatPercentage(it.change24h), color = changeColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { chartVisible = !chartVisible },
                modifier = Modifier.weight(1f).height(42.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen)
            ) {
                Icon(Icons.Default.ShowChart, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (chartVisible) "Sembunyikan Chart" else "Tampilkan Chart", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onOpenLandscapeChart,
                modifier = Modifier.weight(1f).height(42.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen)
            ) {
                Text("Chart penuh", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (chartVisible) {
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1722))
            ) {
                SimpleComposeChart(
                    prices = emptyList(),
                    candles = candles,
                    currentPrice = tick?.price ?: 0.0,
                    isPositiveTrend = (tick?.change24h ?: 0.0) >= 0,
                    modifier = Modifier.fillMaxWidth().height(250.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        if (isScalping) {
            ProgressEntryCard(signal, scalping = true)
            Spacer(Modifier.height(10.dp))
        }

        if (signal.action == SignalAction.BUY && signal.entryPrice > 0) {
            BuyReadyBanner(signal)
            Spacer(Modifier.height(10.dp))
        } else {
            WaitingBanner(signal, isScalping)
            Spacer(Modifier.height(10.dp))
        }

        RecommendationCard(signal, isScalping)
        Spacer(Modifier.height(10.dp))

        MonitorSummaryCard(signal, isScalping)
        Spacer(Modifier.height(10.dp))

        WhySummaryCard(signal, indicators)
        Spacer(Modifier.height(10.dp))

        ImportantLevelsCard(signal, marketStructure, tick?.price ?: 0.0)
        Spacer(Modifier.height(10.dp))

        TechnicalDetailsCard(
            indicators = indicators,
            structure = marketStructure,
            volume24h = tick?.volume24h ?: 0.0,
            scalping = isScalping
        )
        Spacer(Modifier.height(10.dp))

        if (aiGroq != null || aiGemini != null) {
            AiSummaryCard(if (provider == agu.analys.config.AiProvider.GROQ) aiGroq.orEmpty() else aiGemini.orEmpty())
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { openIndodax(context) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5))
            ) {
                Text("Buka Indodax", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    if (provider == agu.analys.config.AiProvider.GROQ) viewModel.requestDeepAiAudit()
                    else viewModel.requestGeminiChartSummary()
                },
                enabled = !aiLoadingGroq && !aiLoadingGemini && live,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2D2D))
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = TvTextPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (aiLoadingGroq || aiLoadingGemini) "Menganalisis..." else "AI Analisis", color = TvTextPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun BuyReadyBanner(signal: AISignalState) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF123D2A))
    ) {
        Column(Modifier.padding(15.dp)) {
            Text("BUY READY", color = TvGreen, fontSize = 21.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(3.dp))
            Text(
                "1H bias → 15M setup → 1M trigger terpenuhi. Level eksekusi tersedia di bawah.",
                color = TvTextPrimary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(4.dp))
            Text("Kekuatan setup ${signal.confidence}/100", color = TvTextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun WaitingBanner(signal: AISignalState, scalping: Boolean) {
    val waiting = signal.mtf.waitingFor.ifBlank { "Belum ada setup BUY yang valid untuk dieksekusi." }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101720))
    ) {
        Column(Modifier.padding(15.dp)) {
            Text(
                if (scalping) "MENUNGGU KONDISI" else "TAHAN / TUNGGU",
                color = Color(0xFFFFC107),
                fontSize = 17.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(4.dp))
            Text(waiting, color = TvTextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun MonitorSummaryCard(signal: AISignalState, scalping: Boolean) {
    AnalysisCard {
        SectionTitle("YANG PERLU DIPANTAU", null)
        Spacer(Modifier.height(7.dp))
        Text(
            signal.mtf.waitingFor.ifBlank { signal.mtf.entryCondition.ifBlank { "Pantau perubahan struktur dan konfirmasi harga berikutnya." } },
            color = TvTextPrimary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        if (scalping) {
            Spacer(Modifier.height(9.dp))
            Text("1H Bias · ${signal.mtf.biasStatus}", color = TvTextSecondary, fontSize = 11.sp)
            Text("15M Setup · ${signal.mtf.setupStatus}", color = TvTextSecondary, fontSize = 11.sp)
            Text("1M Trigger · ${signal.mtf.triggerStatus}", color = TvTextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun WhySummaryCard(signal: AISignalState, indicators: TechnicalIndicators) {
    AnalysisCard {
        SectionTitle("KENAPA", null)
        Spacer(Modifier.height(7.dp))
        signal.reasoning.take(4).forEach { reason ->
            Text("• $reason", color = TvTextPrimary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (indicators.rsi14.isFinite()) {
            Spacer(Modifier.height(7.dp))
            Text(
                "RSI ${fmt(indicators.rsi14)} · EMA20 ${fmt(indicators.ema20)} · EMA50 ${fmt(indicators.ema50)}",
                color = TvTextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun AiSummaryCard(text: String) {
    AnalysisCard {
        SectionTitle("ANALISA AI", Icons.Default.AutoAwesome)
        Spacer(Modifier.height(7.dp))
        Text(text, color = TvTextPrimary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

private fun fmt(value: Double): String = if (value.isFinite()) String.format(java.util.Locale.US, "%.2f", value) else "—"

private fun openIndodax(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("id.co.bitcoin")
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "Aplikasi Indodax belum terpasang di HP ini.", Toast.LENGTH_SHORT).show()
    }
}
