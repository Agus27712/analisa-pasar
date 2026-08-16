package agu.analys.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import agu.analys.ui.animation.AnimatedPercentageBadge
import agu.analys.ui.animation.SmoothPriceText
import agu.analys.ui.components.MarketStructureLearningCard
import agu.analys.ui.components.SimpleComposeChart
import agu.analys.ui.components.SpotPositionCard
import agu.analys.ui.components.dashboard.AssetAvatar
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
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val spotPosition by viewModel.spotPosition.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val aiGroq by viewModel.auditReportText.collectAsStateWithLifecycle()
    val aiGemini by viewModel.geminiSummaryText.collectAsStateWithLifecycle()
    val aiLoadingGroq by viewModel.isAuditLoading.collectAsStateWithLifecycle()
    val aiLoadingGemini by viewModel.isGeminiLoading.collectAsStateWithLifecycle()

    val marketStructure = remember(candles) { MarketStructureAnalyzer.analyze(candles) }
    var chartVisible by remember { mutableStateOf(false) }
    val isFavorite = watchlist.contains(pair.symbol)
    val provider = remember { AppPreferences(context).aiProvider }
    val live = connection is MarketConnectionState.Connected
    val changeColor = when {
        tick == null -> TvTextSecondary
        tick!!.change24h >= 0 -> TvGreen
        else -> TvRed
    }

    val volume = tick?.volume24h ?: 0.0
    val change = tick?.change24h ?: 0.0
    val activityText = when {
        volume >= 50_000_000_000 || change >= 3.0 -> "Aktivitas tinggi"
        volume >= 1_000_000_000 || change >= 0.0 -> "Aktivitas sedang"
        else -> "Aktivitas rendah"
    }
    val activityColor = when (activityText) {
        "Aktivitas tinggi" -> TvGreen
        "Aktivitas sedang" -> Color(0xFFFFB300)
        else -> Color(0xFF90A4AE)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Top Bar: Back | Avatar + Name + Subtext | Belajar + Star + Landscape
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateToDashboard) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali",
                    tint = TvTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            AssetAvatar(baseAsset = pair.baseAsset, iconUrl = pair.iconUrl, size = 36.dp)
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = "${pair.baseAsset}/${pair.quoteAsset}",
                    color = TvTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = getCoinFullName(pair.baseAsset),
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            }

            IconButton(onClick = { viewModel.openLearning() }) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = "Mode Belajar",
                    tint = Color(0xFF72B7FF),
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(onClick = { viewModel.toggleWatchlist(pair.symbol) }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) Color(0xFFFFB300) else TvTextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(onClick = onOpenLandscapeChart) {
                Icon(
                    imageVector = Icons.Default.CropRotate,
                    contentDescription = "Landscape",
                    tint = TvGreen,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Price Header + 24H Change + Badge Aktivitas (Dengan Animasi Live Smooth & Percentage)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                if (tick != null && tick!!.price > 0) {
                    SmoothPriceText(tick!!.price, TvTextPrimary, 26.sp, FontWeight.Black)
                } else {
                    Text("Rp —", color = TvTextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(4.dp))
                // Badge Aktivitas
                Box(
                    modifier = Modifier
                        .background(activityColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .border(1.dp, activityColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = activityText,
                        color = activityColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                if (tick != null) {
                    AnimatedPercentageBadge(
                        percentage = tick!!.change24h,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                } else {
                    Text("—", color = TvTextSecondary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text(
                    text = "Perubahan 24H",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Timeframe Selector (1M, 15M, 1H, 4H, 1D) + MODE BADGE (SCALPING / SWING)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Timeframe.M1, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1).forEach { tf ->
                    val isSelected = selectedTimeframe == tf
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) Color(0xFF1E2836) else Color(0xFF101720),
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF72B7FF) else Color(0xFF1E2836),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { viewModel.selectTimeframe(tf) }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = tf.label.uppercase(),
                            color = if (isSelected) Color(0xFF72B7FF) else TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Mode Badge
            Box(
                modifier = Modifier
                    .background(
                        if (isScalping) Color(0xFF123D2A) else Color(0xFF122840),
                        RoundedCornerShape(6.dp)
                    )
                    .border(
                        1.dp,
                        if (isScalping) TvGreen else Color(0xFF72B7FF),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { viewModel.setScalpingMode(!isScalping) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isScalping) "SCALPING" else "SWING",
                    color = if (isScalping) TvGreen else Color(0xFF72B7FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Tombol Show/Hide Chart & Fullscreen Chart
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { chartVisible = !chartVisible },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen)
            ) {
                Icon(Icons.Default.ShowChart, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (chartVisible) "Sembunyikan Chart" else "Buka Chart",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onOpenLandscapeChart,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF72B7FF))
            ) {
                Text("Chart Penuh", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Animated Chart Canvas
        AnimatedVisibility(
            visible = chartVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09101A))
                ) {
                    SimpleComposeChart(
                        prices = emptyList(),
                        candles = candles,
                        currentPrice = tick?.price ?: 0.0,
                        isPositiveTrend = (tick?.change24h ?: 0.0) >= 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 1. REKOMENDASI EKSEKUSI (BUY READY / LEVEL ENTRY - SL - TP1 - TP2 + COPY + TOMBOL BELI INDODAX)
        RecommendationCard(
            signal = signal,
            scalping = isScalping,
            onOpenIndodax = { openIndodax(context) }
        )

        Spacer(Modifier.height(10.dp))

        // 2. RADAR & PROGRES MENUNGGU ENTRY (Status Live Scanning, MTF Steps, Micro-Tips Edukasi Interaktif)
        WaitingEntryRadarCard(
            signal = signal,
            scalping = isScalping
        )

        Spacer(Modifier.height(10.dp))

        // 3. KONDISI PASAR & BIAS STRUKTUR (Trend Pasar Real MTF)
        MarketConditionCard(
            structure = marketStructure,
            indicators = indicators,
            signal = signal,
            scalping = isScalping
        )

        Spacer(Modifier.height(10.dp))

        // 4. LEVEL PENTING (Support, Resistance, Jarak % dari harga terkini)
        ImportantLevelsCard(
            signal = signal,
            structure = marketStructure,
            price = tick?.price ?: 0.0
        )

        Spacer(Modifier.height(10.dp))

        // 5. DETAIL INDIKATOR TEKNIKAL REAL-TIME (RSI, EMA, MACD, Volume, ATR)
        TechnicalDetailsCard(
            indicators = indicators,
            structure = marketStructure,
            volume24h = tick?.volume24h ?: 0.0,
            scalping = isScalping
        )

        Spacer(Modifier.height(10.dp))

        // 6. YANG PERLU DIPANTAU (Area Observasi & Key Alert Level)
        MonitorCard(
            signal = signal,
            structure = marketStructure,
            price = tick?.price ?: 0.0,
            cached = false
        )

        Spacer(Modifier.height(10.dp))

        // 7. MANAJEMEN POSISI SPOT TRADING (Simulasi Portofolio & Average)
        SpotPositionCard(
            symbol = pair.symbol,
            signal = signal,
            position = spotPosition,
            currentPrice = tick?.price ?: 0.0,
            onPositionChanged = viewModel::refreshSpotPosition
        )

        Spacer(Modifier.height(10.dp))

        // 8. ASISTEN AI AUDIT & RINGKASAN
        AiAssistantCard(
            auditText = aiGroq,
            auditLoading = aiLoadingGroq,
            geminiText = aiGemini,
            geminiLoading = aiLoadingGemini,
            onGroq = viewModel::requestDeepAiAudit,
            onGemini = viewModel::requestGeminiChartSummary,
            onClearGroq = viewModel::clearAuditReport,
            onClearGemini = viewModel::clearGeminiSummary
        )

        Spacer(Modifier.height(10.dp))

        // 9. DISCLAIMER & MANAJEMEN RISIKO
        DisclaimerCard()

        Spacer(Modifier.height(14.dp))

        // Action Buttons: Buka Indodax & AI Analisis
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { openIndodax(context) },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5))
            ) {
                Text("Buka Indodax", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Button(
                onClick = {
                    if (provider == agu.analys.config.AiProvider.GROQ) viewModel.requestDeepAiAudit()
                    else viewModel.requestGeminiChartSummary()
                },
                enabled = !aiLoadingGroq && !aiLoadingGemini && live,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2836))
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = TvTextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (aiLoadingGroq || aiLoadingGemini) "Menganalisis..." else "AI Analisis",
                    color = TvTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun getCoinFullName(symbol: String): String = when (symbol.uppercase()) {
    "BTC" -> "Bitcoin"
    "ETH" -> "Ethereum"
    "SOL" -> "Solana"
    "XRP" -> "Ripple"
    "DOGE" -> "Dogecoin"
    "ADA" -> "Cardano"
    "BNB" -> "BNB"
    "USDT" -> "Tether"
    else -> symbol
}

private fun openIndodax(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("id.co.bitcoin")
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "Aplikasi Indodax belum terpasang di HP ini.", Toast.LENGTH_SHORT).show()
    }
}
