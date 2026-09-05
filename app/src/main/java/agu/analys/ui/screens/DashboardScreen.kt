package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.model.MarketConnectionState
import agu.analys.model.TradingPair
import agu.analys.ui.components.dashboard.*
import agu.analys.ui.theme.TvBackground
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
    val secondWaveCoins by viewModel.secondWaveCoins.collectAsState()
    val topVolumeCoins by viewModel.topVolumeCoins.collectAsState()
    val usdtIdrRate by viewModel.usdtIdrRate.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val dashboardTicks by viewModel.dashboardTicks.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val coinBadges by viewModel.coinBadges.collectAsState()
    val isScalpingMode by viewModel.isScalpingMode.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val strategyMode by viewModel.strategyMode.collectAsState()
    val recentCandles by viewModel.recentCandles.collectAsState()
    val simulationWallet by viewModel.simulationWallet.collectAsState()
    val realIndodaxBalance by viewModel.realIndodaxBalance.collectAsState()
    val realAvgBuyPrices by viewModel.realAvgBuyPrices.collectAsState()
    val spotPosition by viewModel.spotPosition.collectAsState()
    val isRealBuyMode by viewModel.isRealBuyMode.collectAsState()
    val batchExecutionState by viewModel.batchExecutionState.collectAsState()
    val holdingStatuses by viewModel.holdingStatuses.collectAsState()
    val tradingFees by viewModel.tradingFees.collectAsState()
    val newsScreenerState by viewModel.newsScreenerState.collectAsState()
    val hasSecurityPin = remember { viewModel.hasSecurityPin() }
    var selectedRankingTab by remember { mutableStateOf(MarketRankingTab.WATCHLIST) }
    var currentTab by remember { mutableStateOf(NavTab.WATCHLIST) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showNewsScreener by remember { mutableStateOf(false) }

    val defaultQuote = "IDR"

    val allTicks = remember(dashboardTicks, hotCoins, gainersCoins, secondWaveCoins, topVolumeCoins) {
        dashboardTicks +
            hotCoins.associateBy { it.symbol } +
            gainersCoins.associateBy { it.symbol } +
            secondWaveCoins.associateBy { it.symbol } +
            topVolumeCoins.associateBy { it.symbol }
    }

    val displayPairs = remember(
        selectedRankingTab,
        watchlist,
        favorites,
        marketDataSource,
        hotCoins,
        gainersCoins,
        topVolumeCoins,
        secondWaveCoins,
        allTicks
    ) {
        when (selectedRankingTab) {
            MarketRankingTab.WATCHLIST -> {
                // Auto-isi dari market movers (gainers/hot/volume/second-wave) + watchlist user + popular
                // Max 25, diurutkan aktivitas (volume + change)
                val fromUser = watchlist.map { TradingPair.fromCustomSymbol(it, defaultQuote) }
                val fromMarket = (gainersCoins + hotCoins + topVolumeCoins + secondWaveCoins)
                    .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                val fromPopular = TradingPair.popularPairsForSource(marketDataSource)
                val merged = (fromMarket + fromUser + fromPopular).distinctBy { it.symbol }
                merged.sortedByDescending { pair ->
                    val t = allTicks[pair.symbol] ?: return@sortedByDescending -1.0
                    val ch = t.change24h.takeIf { c -> c.isFinite() } ?: 0.0
                    val vol = t.volume24h.coerceAtLeast(0.0)
                    val volScore = kotlin.math.ln(vol + 1.0)
                    val momScore = kotlin.math.abs(ch) * 2.5 + if (ch > 0.0) 8.0 else 0.0
                    volScore + momScore
                }.take(25)
            }
            MarketRankingTab.FAVORITE -> {
                favorites.map { TradingPair.fromCustomSymbol(it, defaultQuote) }.distinctBy { it.symbol }
            }
        }
    }
    val worthBySymbol = remember(worthCoins) { worthCoins.associateBy { it.pair.symbol } }
    val isConnected = connectionState is MarketConnectionState.Connected

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TvBackground)
        ) {
            // Header Mockup with Indodax active badge & Redesigned Ranking Tabs & Rotating Refresh Button
            DashboardMockupHeader(
                allTicks = allTicks,
                marketDataSource = marketDataSource,
                strategyMode = strategyMode,
                isConnected = isConnected,
                isRefreshing = isRefreshing,
                selectedTab = selectedRankingTab,
                onSelectTab = { tab ->
                    selectedRankingTab = tab
                },
                onRefresh = { viewModel.retryConnection() },
                onAddAsset = { showAddDialog = true }
            )

        if (connectionState is MarketConnectionState.ConnectionLost) {
            val lost = connectionState as MarketConnectionState.ConnectionLost
            OfflineBanner(lost.reason) { viewModel.retryConnection() }
        }

        // Proactive Profit Summary (Aggregates total unrealized gains across all Ready Sell coins)
        val allEvaluatedPairs = remember(displayPairs, watchlist, favorites, holdingStatuses, marketDataSource) {
            (displayPairs +
                watchlist.map { TradingPair.fromCustomSymbol(it, defaultQuote) } +
                favorites.map { TradingPair.fromCustomSymbol(it, defaultQuote) } +
                holdingStatuses.keys.map { TradingPair.fromCustomSymbol(it, defaultQuote) } +
                TradingPair.popularPairsForSource(marketDataSource))
                .distinctBy { it.symbol }
        }
        ProactiveProfitSummaryCard(
            allPairs = allEvaluatedPairs,
            allTicks = allTicks,
            worthBySymbol = worthBySymbol,
            recentCandles = recentCandles,
            usdtIdrRate = usdtIdrRate,
            isRealTradingMode = isRealBuyMode,
            batchExecutionState = batchExecutionState,
            hasSecurityPin = hasSecurityPin,
            holdingStatuses = holdingStatuses,
            tradingFees = tradingFees,
            onCoinClick = { pair ->
                viewModel.selectPair(pair)
                onNavigateToDetail(pair)
            },
            onExecuteBatchSell = { items, isReal, pin ->
                viewModel.executeBatchSellReadyAssets(items, isReal, pin)
            },
            onResetBatchState = {
                viewModel.resetBatchSellState()
            }
        )

        // List Aset
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (displayPairs.isEmpty()) {
                item {
                    EmptyWatchlistState(
                        isFavoriteTab = selectedRankingTab == MarketRankingTab.FAVORITE,
                        onAddClick = { showAddDialog = true }
                    )
                }
            } else {
                itemsIndexed(displayPairs, key = { _, pair -> pair.symbol }) { index, pair ->
                    val holdingStatus = holdingStatuses[pair.symbol] ?: viewModel.getHoldingStatus(pair)
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
                    WatchlistCoinCard(
                        pair = pair,
                        tick = tick,
                        worth = worthBySymbol[pair.symbol],
                        rank = index + 1,
                        isAuto = selectedRankingTab != MarketRankingTab.WATCHLIST && selectedRankingTab != MarketRankingTab.FAVORITE,
                        isScalping = isScalpingMode,
                        isFavorite = favorites.contains(pair.symbol),
                        badges = effectiveBadges,
                        usdtIdrRate = usdtIdrRate,
                        recentCandles = recentCandles,
                        holdingStatus = holdingStatus,
                        tradingFees = tradingFees,
                        onToggleFavorite = { viewModel.toggleFavorite(pair.symbol) },
                        onClick = {
                            viewModel.selectPair(pair)
                            onNavigateToDetail(pair)
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }

        // Bottom Navigation Bar (4 Tab Bersih)
        AppBottomNavigationBar(
            currentTab = currentTab,
            onSelectTab = { tab ->
                currentTab = tab
                when (tab) {
                    NavTab.WATCHLIST -> { /* Sudah di Watchlist */ }
                    NavTab.PORTOFOLIO -> {
                        viewModel.openPortfolio()
                    }
                    NavTab.SIMULASI -> {
                        val firstPair = displayPairs.firstOrNull() ?: TradingPair.popularPairsForSource(marketDataSource).first()
                        viewModel.openSimulation(firstPair)
                    }
                    NavTab.SETTINGS -> {
                        onOpenSettings()
                    }
                }
            }
        )
    }

    // Shortcut Floating AI Screener Button (Melayang tepat di atas Bottom Navigation)
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 68.dp)
    ) {
        FloatingActionButton(
            onClick = { showNewsScreener = true },
            containerColor = Color.Transparent,
            contentColor = Color.White,
            shape = RoundedCornerShape(22.dp),
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 10.dp),
            modifier = Modifier
                .border(
                    width = 1.2.dp,
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF818CF8), Color(0xFF38BDF8))
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
                .testTag("floating_ai_screener_btn")
        ) {
            Box(
                modifier = Modifier
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color(0xFF4338CA), Color(0xFF0284C7))
                        ),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("✨", fontSize = 13.sp)
                    Text(
                        text = "AI Screener",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.3.sp
                    )
                }
            }
        }
    }

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
            preferredProvider = viewModel.prefs.aiProvider,
            onDismiss = { showNewsScreener = false },
            onRunScreener = { force, provider ->
                viewModel.runNewsAiScreener(forceRefresh = force, overrideProvider = provider)
            },
            onSelectCoin = { pair ->
                viewModel.selectPair(pair)
                onNavigateToDetail(pair)
            }
        )
    }
}
}
