package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    val isScalpingMode by viewModel.isScalpingMode.collectAsState()
    val strategyMode by viewModel.strategyMode.collectAsState()
    val recentCandles by viewModel.recentCandles.collectAsState()
    var selectedRankingTab by remember { mutableStateOf(MarketRankingTab.SCALPING_FAST) }
    var currentTab by remember { mutableStateOf(NavTab.WATCHLIST) }
    var showAddDialog by remember { mutableStateOf(false) }

    val defaultQuote = "IDR"

    val displayPairs = remember(
        selectedRankingTab,
        gainersCoins,
        secondWaveCoins,
        hotCoins,
        topVolumeCoins,
        worthCoins,
        watchlist,
        marketDataSource
    ) {
        when (selectedRankingTab) {
            MarketRankingTab.SCALPING_FAST -> {
                val list = if (gainersCoins.isNotEmpty()) gainersCoins.take(10).map { it.symbol }
                else if (hotCoins.isNotEmpty()) hotCoins.take(10).map { it.symbol }
                else worthCoins.filter { it.isWorthIt || it.potentialProfitPct > 0 }.take(10).map { it.pair.symbol }

                val mapped = list.map { TradingPair.fromCustomSymbol(it, defaultQuote) }.distinctBy { it.symbol }
                if (mapped.isNotEmpty()) mapped else TradingPair.popularPairsForSource(marketDataSource).take(10)
            }
            MarketRankingTab.SECOND_WAVE -> {
                val list = if (secondWaveCoins.isNotEmpty()) secondWaveCoins.take(10).map { it.symbol }
                else if (gainersCoins.isNotEmpty()) gainersCoins.take(10).map { it.symbol }
                else worthCoins.map { it.pair.symbol }.take(10)

                val mapped = list.map { TradingPair.fromCustomSymbol(it, defaultQuote) }.distinctBy { it.symbol }
                if (mapped.isNotEmpty()) mapped else TradingPair.popularPairsForSource(marketDataSource).take(10)
            }
            MarketRankingTab.WATCHLIST -> {
                watchlist.map { TradingPair.fromCustomSymbol(it, defaultQuote) }.distinctBy { it.symbol }
            }
        }
    }

    val allTicks = remember(dashboardTicks, hotCoins, gainersCoins, secondWaveCoins, topVolumeCoins) {
        dashboardTicks +
            hotCoins.associateBy { it.symbol } +
            gainersCoins.associateBy { it.symbol } +
            secondWaveCoins.associateBy { it.symbol } +
            topVolumeCoins.associateBy { it.symbol }
    }
    val worthBySymbol = remember(worthCoins) { worthCoins.associateBy { it.pair.symbol } }
    val isConnected = connectionState is MarketConnectionState.Connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // Header Mockup with Indodax active badge & Redesigned Ranking Tabs & Rotating Refresh Button
        DashboardMockupHeader(
            allTicks = allTicks,
            marketDataSource = marketDataSource,
            strategyMode = strategyMode,
            isConnected = isConnected,
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

        // List Aset
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (displayPairs.isEmpty()) {
                item {
                    EmptyWatchlistState { showAddDialog = true }
                }
            } else {
                itemsIndexed(displayPairs, key = { _, pair -> pair.symbol }) { index, pair ->
                    WatchlistCoinCard(
                        pair = pair,
                        tick = allTicks[pair.symbol],
                        worth = worthBySymbol[pair.symbol],
                        rank = index + 1,
                        isAuto = selectedRankingTab != MarketRankingTab.WATCHLIST,
                        isScalping = isScalpingMode,
                        isFavorite = watchlist.contains(pair.symbol),
                        usdtIdrRate = usdtIdrRate,
                        recentCandles = recentCandles,
                        onToggleFavorite = { viewModel.toggleWatchlist(pair.symbol) },
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

    if (showAddDialog) {
        AddAssetDialog(
            currentWatchlist = watchlist,
            onDismiss = { showAddDialog = false },
            onAddPair = { pair ->
                if (!watchlist.contains(pair.symbol)) viewModel.toggleWatchlist(pair.symbol)
                showAddDialog = false
            }
        )
    }
}
