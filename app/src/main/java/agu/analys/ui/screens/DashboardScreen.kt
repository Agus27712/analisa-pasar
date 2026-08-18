package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import agu.analys.config.MarketDataSource
import agu.analys.model.MarketConnectionState
import agu.analys.model.TradingPair
import agu.analys.ui.components.dashboard.*
import agu.analys.ui.theme.TvBackground
import agu.analys.viewmodel.TradingViewModel

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
    val connectionState by viewModel.connectionState.collectAsState()
    val dashboardTicks by viewModel.dashboardTicks.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val isScalpingMode by viewModel.isScalpingMode.collectAsState()
    val recentCandles by viewModel.recentCandles.collectAsState()
    var isManualTab by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(NavTab.WATCHLIST) }
    var showAddDialog by remember { mutableStateOf(false) }

    val defaultQuote = if (marketDataSource == MarketDataSource.TOKOCRYPTO) "USDT" else "IDR"

    val manualPairs = remember(watchlist, marketDataSource) {
        watchlist.map { TradingPair.fromCustomSymbol(it, defaultQuote) }.distinctBy { it.symbol }
    }
    val autoPairs = remember(hotCoins, worthCoins, isScalpingMode, marketDataSource) {
        val source = if (isScalpingMode) {
            if (hotCoins.isNotEmpty()) hotCoins.map { it.symbol } else worthCoins.map { it.pair.symbol }
        } else {
            worthCoins.map { it.pair.symbol }
        }
        val list = source.map { TradingPair.fromCustomSymbol(it, defaultQuote) }.distinctBy { it.symbol }
        if (list.isNotEmpty()) list.take(10) else TradingPair.popularPairsForSource(marketDataSource).take(6)
    }

    val displayPairs = if (isManualTab) manualPairs else autoPairs
    val allTicks = remember(dashboardTicks, hotCoins) { dashboardTicks + hotCoins.associateBy { it.symbol } }
    val worthBySymbol = remember(worthCoins) { worthCoins.associateBy { it.pair.symbol } }
    val isConnected = connectionState is MarketConnectionState.Connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // Header Mockup with Indodax / Tokocrypto active badge
        DashboardMockupHeader(
            allTicks = allTicks,
            marketDataSource = marketDataSource,
            isScalpingMode = isScalpingMode,
            isConnected = isConnected,
            isManualTab = isManualTab,
            onToggleTab = { isManualTab = it },
            onToggleMode = { viewModel.setScalpingMode(it) },
            onRefresh = { viewModel.retryConnection() },
            onMenuClick = onOpenSettings
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
                        isAuto = !isManualTab,
                        isScalping = isScalpingMode,
                        isFavorite = watchlist.contains(pair.symbol),
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

        // Bottom Navigation Bar
        AppBottomNavigationBar(
            currentTab = currentTab,
            onSelectTab = { tab ->
                currentTab = tab
                when (tab) {
                    NavTab.WATCHLIST -> { /* Sudah di Watchlist */ }
                    NavTab.SIMULASI -> {
                        val firstPair = displayPairs.firstOrNull() ?: TradingPair.popularPairsForSource(marketDataSource).first()
                        viewModel.openSimulation(firstPair)
                    }
                    NavTab.BELAJAR -> {
                        viewModel.openLearning()
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
