package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.MarketConnectionState
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.components.dashboard.*
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.viewmodel.TradingViewModel

@Composable
fun DashboardScreen(
    viewModel: TradingViewModel,
    onNavigateToDetail: (TradingPair) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val worthCoins by viewModel.worthCoins.collectAsState()
    val hotCoins by viewModel.hotCoins.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val dashboardTicks by viewModel.dashboardTicks.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val isScalpingMode by viewModel.isScalpingMode.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val worthBySymbol = remember(worthCoins) { worthCoins.associateBy { it.pair.symbol } }
    val watchlistPairs = watchlist.map { TradingPair.fromCustomSymbol(it) }
        .distinctBy { it.symbol }
        .sortedBy { worthRank(worthCoins, it.symbol) ?: Int.MAX_VALUE }

    val connectionColor = when (connectionState) {
        is MarketConnectionState.Connected -> TvGreen
        is MarketConnectionState.Loading -> DashboardColors.Amber
        is MarketConnectionState.ConnectionLost -> TvRed
    }
    val connectionLabel = when (connectionState) {
        is MarketConnectionState.Connected -> "INDODAX LIVE · IDR"
        is MarketConnectionState.Loading -> "MENGHUBUNGKAN..."
        is MarketConnectionState.ConnectionLost -> "OFFLINE · ketuk refresh"
    }

    Column(modifier.fillMaxSize().background(DashboardColors.Background)) {
        DashboardHeader(
            connectionLabel = connectionLabel,
            connectionColor = connectionColor,
            onRefresh = { viewModel.retryConnection() },
            onSettings = onOpenSettings,
            onAddAsset = { showAddDialog = true }
        )

        if (connectionState is MarketConnectionState.ConnectionLost) {
            val lost = connectionState as MarketConnectionState.ConnectionLost
            OfflineBanner(lost.reason) { viewModel.retryConnection() }
        }

        ModeSwitchToggle(
            isScalping = isScalpingMode,
            onToggle = { viewModel.setScalpingMode(it) },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )
        CompactMarketOverview(dashboardTicks, connectionState is MarketConnectionState.Connected)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (hotCoins.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    ) {
                        Icon(Icons.Default.TrendingUp, null, tint = DashboardColors.AccentBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "VOLUME TERTINGGI",
                            color = DashboardColors.AccentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("· 24 jam · live", color = TvTextSecondary, fontSize = 10.sp)
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        hotCoins.forEachIndexed { index, tick ->
                            VolumeLeaderChip(
                                rank = index + 1,
                                tick = tick,
                                isWatched = tick.symbol in watchlist,
                                onOpen = {
                                    val pair = TradingPair.fromCustomSymbol(tick.symbol)
                                    viewModel.selectPair(pair)
                                    onNavigateToDetail(pair)
                                },
                                onToggleWatch = { viewModel.toggleWatchlist(tick.symbol) }
                            )
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "WATCHLIST",
                        color = TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.7.sp
                    )
                }
            }

            if (watchlistPairs.isEmpty()) {
                item { EmptyWatchlistState { showAddDialog = true } }
            } else {
                items(watchlistPairs, key = { it.symbol }) { pair ->
                    WatchlistCoinCard(
                        pair = pair,
                        tick = dashboardTicks[pair.symbol],
                        worth = worthBySymbol[pair.symbol],
                        rank = worthRank(worthCoins, pair.symbol),
                        onRemove = { viewModel.toggleWatchlist(pair.symbol) },
                        onClick = {
                            viewModel.selectPair(pair)
                            onNavigateToDetail(pair)
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
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

private fun worthRank(items: List<WorthCoinInfo>, symbol: String): Int? =
    items.indexOfFirst { it.pair.symbol == symbol }.takeIf { it >= 0 }?.plus(1)
