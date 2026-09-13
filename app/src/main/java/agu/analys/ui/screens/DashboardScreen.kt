package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.MarketConnectionState
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.ui.components.dashboard.*
import agu.analys.ui.components.settings.LogcatDiagnosticDialog
import agu.analys.ui.theme.*
import agu.analys.viewmodel.*

@Composable
fun DashboardScreen(
    viewModel: TradingViewModel,
    onNavigateToDetail: (TradingPair) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenLandscapeChart: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val marketDataSource by viewModel.marketDataSource.collectAsState()
    val worthCoins by viewModel.worthCoins.collectAsState()
    val hotCoins by viewModel.hotCoins.collectAsState()
    val gainersCoins by viewModel.gainersCoins.collectAsState()
    val losersCoins by viewModel.losersCoins.collectAsState()
    val secondWaveCoins by viewModel.secondWaveCoins.collectAsState()
    val topVolumeCoins by viewModel.topVolumeCoins.collectAsState()
    val usdtIdrRate by viewModel.usdtIdrRate.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val dashboardTicks by viewModel.dashboardTicks.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val coinBadges by viewModel.coinBadges.collectAsState()
    val isScalpingMode by viewModel.isScalpingMode.collectAsState()
    val strategyMode by viewModel.strategyMode.collectAsState()
    val scalpingSensitivity by viewModel.scalpingSensitivity.collectAsState()
    val aiSignalState by viewModel.aiSignalState.collectAsState()
    val recentCandles by viewModel.recentCandles.collectAsState()
    val holdingStatuses by viewModel.holdingStatuses.collectAsState()
    val spotPosition by viewModel.spotPosition.collectAsState()
    val currentTick by viewModel.currentTick.collectAsState()
    val mtfState by viewModel.mtfState.collectAsState()
    val newsScreenerState by viewModel.newsScreenerState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    var selectedQuickFilter by remember { mutableStateOf(DashboardQuickFilter.ALL) }
    var currentTab by remember { mutableStateOf(NavTab.WATCHLIST) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showNewsScreener by remember { mutableStateOf(false) }
    var showLogcatDialog by remember { mutableStateOf(false) }

    val defaultQuote = "IDR"

    // Gabungkan seluruh data ticks real-time (SSOT dengan Detail / WebSocket aktif)
    val allTicks = remember(dashboardTicks, currentTick, hotCoins, gainersCoins, losersCoins, secondWaveCoins, topVolumeCoins) {
        val base = dashboardTicks +
            hotCoins.associateBy { it.symbol } +
            gainersCoins.associateBy { it.symbol } +
            losersCoins.associateBy { it.symbol } +
            secondWaveCoins.associateBy { it.symbol } +
            topVolumeCoins.associateBy { it.symbol }
        if (currentTick != null) {
            val ct = currentTick!!
            base + (ct.symbol to ct) +
                   (ct.symbol.uppercase() to ct) +
                   (ct.symbol.lowercase() to ct)
        } else {
            base
        }
    }

    val worthBySymbol = remember(worthCoins) { worthCoins.associateBy { it.pair.symbol } }
    val isConnected = connectionState is MarketConnectionState.Connected

    // Kumpulan pasangan koin dasar dari pasar
    val basePopular = remember(marketDataSource) {
        TradingPair.popularPairsForSource(marketDataSource)
    }

    // Prioritaskan daftar koin fokus sesuai Mode Strategi yang aktif (Focus Mode)
    val strategyPairs = remember(
        strategyMode,
        gainersCoins,
        hotCoins,
        secondWaveCoins,
        topVolumeCoins,
        losersCoins,
        allTicks,
        watchlist,
        favorites,
        basePopular
    ) {
        when (strategyMode) {
            StrategyMode.SCALPING -> {
                val highVol = allTicks.values
                    .filter { it.price > 5.0 && it.volume24h >= 200_000_000.0 }
                    .sortedWith(compareByDescending<agu.analys.model.MarketTick> { it.change24h > 0 }.thenByDescending { it.volume24h })
                    .take(30)
                    .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                val explicit = (gainersCoins.map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) } +
                    hotCoins.map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) } +
                    watchlist.map { TradingPair.fromCustomSymbol(it, defaultQuote) } +
                    favorites.map { TradingPair.fromCustomSymbol(it, defaultQuote) })
                (explicit + highVol + basePopular).distinctBy { it.symbol }
            }
            StrategyMode.SECOND_WAVE -> {
                val secondWave = secondWaveCoins.map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                val dipReversal = losersCoins.take(15).map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                (secondWave + dipReversal + basePopular).distinctBy { it.symbol }
            }
            StrategyMode.SWING -> {
                val swingCandidates = allTicks.values
                    .filter { it.price > 5.0 && it.volume24h >= 500_000_000.0 && it.change24h in -3.0..8.0 }
                    .sortedByDescending { it.volume24h }
                    .take(25)
                    .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                (swingCandidates + basePopular).distinctBy { it.symbol }
            }
            StrategyMode.OFFICE_DAILY -> {
                val officeCandidates = allTicks.values
                    .filter { it.price > 5.0 && it.volume24h >= 1_000_000_000.0 }
                    .sortedByDescending { it.volume24h }
                    .take(25)
                    .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                (officeCandidates + basePopular).distinctBy { it.symbol }
            }
            StrategyMode.TRENCHING -> {
                val trenchCandidates = allTicks.values
                    .filter { it.price > 5.0 && it.volume24h >= 300_000_000.0 && it.change24h in -2.5..4.5 }
                    .sortedByDescending { it.volume24h }
                    .take(25)
                    .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                (trenchCandidates + basePopular).distinctBy { it.symbol }
            }
        }
    }

    // Terapkan Quick Filter Chips [Semua] [Signal Kuat ⭐] [💼 Holding] [⭐ Watchlist]
    val filteredFocusPairs = remember(
        strategyPairs,
        selectedQuickFilter,
        holdingStatuses,
        watchlist,
        favorites,
        worthBySymbol,
        coinBadges,
        aiSignalState,
        allTicks
    ) {
        when (selectedQuickFilter) {
            DashboardQuickFilter.ALL -> strategyPairs
            DashboardQuickFilter.STRONG_SIGNAL -> {
                strategyPairs.filter { pair ->
                    val tick = allTicks[pair.symbol]
                    val worth = worthBySymbol[pair.symbol]
                    val badges = coinBadges[pair.symbol] ?: emptyList()
                    val engineSignal = viewModel.getEngineSignal(pair.symbol)
                    val isAiBuy = engineSignal != null && engineSignal.action == SignalAction.BUY && engineSignal.confidence >= 55
                    val isWorth = worth != null && (worth.isWorthIt || worth.worthScore >= 65)
                    val isBreakout = badges.any { it.label == "BREAKOUT" || it.label == "MOMENTUM" }
                    val isHighGain = tick != null && tick.change24h >= 3.0
                    isAiBuy || isWorth || isBreakout || isHighGain
                }
            }
            DashboardQuickFilter.HOLDING -> {
                strategyPairs.filter { pair ->
                    holdingStatuses[pair.symbol]?.isHolding == true ||
                    holdingStatuses[pair.baseAsset.lowercase()]?.isHolding == true
                }
            }
            DashboardQuickFilter.WATCHLIST -> {
                strategyPairs.filter { pair ->
                    watchlist.contains(pair.symbol) || favorites.contains(pair.symbol)
                }
            }
        }
    }

    // Data posisi holding aktif real (SSOT: posisi spot realtime + chart sparkline 1 jam yang mencerminkan detail)
    val activeHoldingList = remember(holdingStatuses, allTicks, spotPosition, strategyPairs, mtfState, recentCandles) {
        holdingStatuses.filter { it.value.isHolding && it.value.quantity > 0.00000001 }.map { (symbol, status) ->
            val pair = strategyPairs.find { it.symbol.equals(symbol, ignoreCase = true) }
                ?: TradingPair.fromCustomSymbol(symbol, defaultQuote)
            val pos = if (viewModel.isMatchingSymbol(symbol, spotPosition.symbol)) {
                spotPosition
            } else {
                viewModel.positionCoordinator.getPosition(symbol)
            }
            val tick = allTicks[symbol] ?: allTicks[pair.symbol]
            val candles1h = viewModel.getH1Candles(pair.symbol)
            ActiveHoldingItemData(
                pair = pair,
                holding = status,
                position = pos,
                tick = tick,
                candles1h = candles1h
            )
        }
    }

    // Prefetch/sync candle 1H untuk semua holding aktif secara background agar chart sparkline selalu ready
    LaunchedEffect(activeHoldingList.map { it.pair.symbol }) {
        activeHoldingList.forEach { item ->
            viewModel.ensureH1Candles(item.pair.symbol)
        }
    }

    // Maksimal volume untuk rasio mini volume bar
    val maxVolume = remember(filteredFocusPairs, allTicks) {
        filteredFocusPairs.maxOfOrNull { allTicks[it.symbol]?.volume24h ?: 0.0 }?.takeIf { it > 0 } ?: 1.0
    }

    val focusListTitle = remember(strategyMode) {
        "FOCUS LIST — ${strategyMode.name.replace('_', ' ')} MODE"
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TvBackground)
        ) {
            // 1. Modern Header (Clean: Logo + Title + Status + Refresh + Logcat)
            DashboardModernHeader(
                isConnected = isConnected,
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refreshWorthCoinsFromMarket() },
                onOpenLogcat = { showLogcatDialog = true }
            )

            // Offline banner jika koneksi terputus
            if (connectionState is MarketConnectionState.ConnectionLost) {
                val lost = connectionState as MarketConnectionState.ConnectionLost
                OfflineBanner(lost.reason) { viewModel.retryConnection() }
            }

            // 2. Section HOLDING AKTIF (Sticky di atas, collapsible)
            ActiveHoldingSection(
                items = activeHoldingList,
                onCoinClick = { pair ->
                    viewModel.selectPair(pair)
                    onNavigateToDetail(pair)
                }
            )

            // 3. Quick Filter Chips [Semua] [Signal Kuat ⭐] [💼 Holding] [⭐ Watchlist]
            QuickFilterChips(
                selectedFilter = selectedQuickFilter,
                onSelectFilter = { selectedQuickFilter = it }
            )

            // 4. Focus List Section Header (Dinamis sesuai Strategy Mode)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = focusListTitle,
                    color = TvCyan,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "${filteredFocusPairs.size} aset",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 5. LazyColumn Focus List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filteredFocusPairs.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = TvCardBackground,
                            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterListOff,
                                    contentDescription = null,
                                    tint = TvTextSecondary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Tidak ada koin yang sesuai filter saat ini.",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Pilih kategori filter lain atau ubah mode strategi.",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { selectedQuickFilter = DashboardQuickFilter.ALL },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Reset Filter ke Semua", color = TvCyan, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    items(filteredFocusPairs, key = { it.symbol }) { pair ->
                        val tick = allTicks[pair.symbol]
                        val effectiveBadges = remember(coinBadges, pair.symbol, tick, strategyMode) {
                            val fromState = coinBadges[pair.symbol]
                            if (!fromState.isNullOrEmpty()) {
                                fromState
                            } else if (tick != null) {
                                agu.analys.engine.badge.CoinBadgeEvaluator.evaluateBadges(
                                    pair = pair,
                                    tick = tick,
                                    activeStrategy = strategyMode,
                                    maxBadges = 1
                                )
                            } else {
                                emptyList()
                            }
                        }

                        FocusCoinCard(
                            data = FocusCoinCardData(
                                pair = pair,
                                tick = tick,
                                worth = worthBySymbol[pair.symbol],
                                aiSignal = viewModel.getEngineSignal(pair.symbol),
                                badges = effectiveBadges,
                                isFavorite = favorites.contains(pair.symbol),
                                maxVolume = maxVolume,
                                isTopPicked = (pair.symbol == filteredFocusPairs.firstOrNull()?.symbol)
                            ),
                            onToggleFavorite = { viewModel.toggleFavorite(pair.symbol) },
                            onClick = {
                                viewModel.selectPair(pair)
                                onNavigateToDetail(pair)
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 6. Bottom Navigation Bar (5 Tab: Watchlist, Portfolio, Chart, Simulation, Settings)
            AppBottomNavigationBar(
                currentTab = currentTab,
                onSelectTab = { tab ->
                    currentTab = tab
                    when (tab) {
                        NavTab.WATCHLIST -> { /* Tetap di Watchlist */ }
                        NavTab.PORTOFOLIO -> {
                            viewModel.openPortfolio()
                        }
                        NavTab.SIMULASI -> {
                            val firstPair = filteredFocusPairs.firstOrNull() ?: basePopular.first()
                            viewModel.openSimulation(firstPair)
                        }
                        NavTab.SETTINGS -> {
                            onOpenSettings()
                        }
                    }
                }
            )
        }

        // Draggable AI Screener Shortcut Button
        var fabOffsetX by remember { mutableStateOf(0f) }
        var fabOffsetY by remember { mutableStateOf(0f) }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 68.dp)
                .offset { IntOffset(fabOffsetX.roundToInt(), fabOffsetY.roundToInt()) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragDistance = 0f
                        var isDown = true
                        while (isDown) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                isDown = false
                                if (dragDistance < 15f) {
                                    showNewsScreener = true
                                }
                            } else {
                                val delta = change.positionChange()
                                if (delta != androidx.compose.ui.geometry.Offset.Zero) {
                                    dragDistance += kotlin.math.abs(delta.x) + kotlin.math.abs(delta.y)
                                    if (dragDistance > 6f) {
                                        change.consume()
                                        fabOffsetX += delta.x
                                        fabOffsetY += delta.y
                                    }
                                }
                            }
                        }
                    }
                }
                .testTag("floating_ai_screener_btn")
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1E1B4B),
                shadowElevation = 5.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color(0xFF818CF8), Color(0xFF38BDF8))
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF312E81), Color(0xFF0369A1))
                            )
                        )
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("✨", fontSize = 11.sp)
                    Text(
                        text = "AI Screener",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }

        // Dialogs
        if (showAddDialog) {
            AddAssetDialog(
                currentFavorites = favorites,
                onDismiss = { showAddDialog = false },
                onAddPair = { pair ->
                    if (!favorites.contains(pair.symbol)) {
                        viewModel.toggleFavorite(pair.symbol)
                    }
                    showAddDialog = false
                }
            )
        }

        if (showNewsScreener) {
            NewsAiScreenerModal(
                state = newsScreenerState,
                provider = viewModel.prefs.aiProvider,
                onDismiss = { showNewsScreener = false },
                onRunScreener = { force ->
                    viewModel.runNewsAiScreener(forceRefresh = force)
                },
                onSelectCoin = { pair ->
                    viewModel.selectPair(pair)
                    onNavigateToDetail(pair)
                }
            )
        }

        if (showLogcatDialog) {
            LogcatDiagnosticDialog(
                onDismissRequest = { showLogcatDialog = false }
            )
        }
    }
}
