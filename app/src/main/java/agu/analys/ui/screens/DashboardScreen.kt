package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
fun DashboardScreen(viewModel: TradingViewModel, onNavigateToDetail: (TradingPair) -> Unit, onOpenSettings: () -> Unit = {}, modifier: Modifier = Modifier) {
    val worthCoins by viewModel.worthCoins.collectAsState()
    val hotCoins by viewModel.hotCoins.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val dashboardTicks by viewModel.dashboardTicks.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val isScalpingMode by viewModel.isScalpingMode.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val manualPairs = watchlist.map(TradingPair::fromCustomSymbol).distinctBy { it.symbol }
    val autoPairs = remember(hotCoins, worthCoins, isScalpingMode) {
        val source = if (isScalpingMode) hotCoins.map { it.symbol } else worthCoins.map { it.pair.symbol }
        source.map(TradingPair::fromCustomSymbol).distinctBy { it.symbol }.take(4)
    }
    val displayPairs = (autoPairs + manualPairs).distinctBy { it.symbol }
    val autoSymbols = autoPairs.map { it.symbol }.toSet()
    val allTicks = remember(dashboardTicks, hotCoins) { dashboardTicks + hotCoins.associateBy { it.symbol } }
    val worthBySymbol = remember(worthCoins) { worthCoins.associateBy { it.pair.symbol } }
    val connectionColor = when (connectionState) { is MarketConnectionState.Connected -> TvGreen; is MarketConnectionState.Loading -> DashboardColors.Amber; is MarketConnectionState.ConnectionLost -> TvRed }
    val connectionLabel = when (connectionState) { is MarketConnectionState.Connected -> "INDODAX LIVE · IDR"; is MarketConnectionState.Loading -> "MENGHUBUNGKAN..."; is MarketConnectionState.ConnectionLost -> "OFFLINE · ketuk refresh" }

    Column(modifier.fillMaxSize().background(DashboardColors.Background)) {
        DashboardHeader(connectionLabel, connectionColor, { viewModel.retryConnection() }, onOpenSettings, { showAddDialog = true })
        if (connectionState is MarketConnectionState.ConnectionLost) { val lost = connectionState as MarketConnectionState.ConnectionLost; OfflineBanner(lost.reason) { viewModel.retryConnection() } }
        CompactMarketOverview(allTicks, connectionState is MarketConnectionState.Connected, isScalpingMode)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(if (isScalpingMode) "AUTO WATCHLIST · SCALPING" else "AUTO WATCHLIST · SWING", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp) }
            if (displayPairs.isEmpty()) item { EmptyWatchlistState { showAddDialog = true } }
            else items(displayPairs, key = { it.symbol }) { pair ->
                WatchlistCoinCard(pair, allTicks[pair.symbol], worthBySymbol[pair.symbol], autoPairs.indexOfFirst { it.symbol == pair.symbol }.takeIf { it >= 0 }?.plus(1), pair.symbol in autoSymbols, isScalpingMode, { viewModel.toggleWatchlist(pair.symbol) }, { viewModel.selectPair(pair); onNavigateToDetail(pair) })
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    if (showAddDialog) AddAssetDialog(currentWatchlist = watchlist, onDismiss = { showAddDialog = false }, onAddPair = { pair -> if (!watchlist.contains(pair.symbol)) viewModel.toggleWatchlist(pair.symbol); showAddDialog = false })
}
