package agu.analys.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.MarketConnectionState
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.TradingViewModel

private val DashboardBackground = Color(0xFF070A0F)
private val DashboardSurface = Color(0xFF0D1219)
private val DashboardCard = Color(0xFF101720)
private val DashboardBorder = Color(0xFF1F3540)
private val TvGold = Color(0xFFFFD54A)
private val TvAmber = Color(0xFFFFB300)

@Composable
fun DashboardScreen(
    viewModel: TradingViewModel,
    onNavigateToDetail: (TradingPair) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val worthCoins by viewModel.worthCoins.collectAsState()
    val selectedPair by viewModel.selectedPair.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val dashboardTicks by viewModel.dashboardTicks.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    BackHandler(enabled = selectedTab == 1) {
        selectedTab = 0
    }

    val allPairs = TradingPair.POPULAR_PAIRS
    val worthBySymbol = remember(worthCoins) { worthCoins.associateBy { it.pair.symbol } }
    val visiblePairs = if (selectedTab == 0) {
        allPairs
    } else {
        watchlist.map { TradingPair.fromCustomSymbol(it) }
            .distinctBy { it.symbol }
            .sortedBy { worthRank(worthCoins, it.symbol) ?: Int.MAX_VALUE }
    }

    val connectionColor = when (connectionState) {
        is MarketConnectionState.Connected -> TvGreen
        is MarketConnectionState.Loading -> TvAmber
        is MarketConnectionState.ConnectionLost -> TvRed
    }
    val connectionLabel = when (connectionState) {
        is MarketConnectionState.Connected -> "INDODAX LIVE · IDR"
        is MarketConnectionState.Loading -> "MENGHUBUNGKAN..."
        is MarketConnectionState.ConnectionLost -> "OFFLINE · ketuk refresh"
    }

    Column(modifier.fillMaxSize().background(DashboardBackground)) {
        DashboardHeader(connectionLabel, connectionColor, { viewModel.retryConnection() }, onOpenSettings) {
            onNavigateToDetail(selectedPair)
        }
        if (connectionState is MarketConnectionState.ConnectionLost) {
            val lost = connectionState as MarketConnectionState.ConnectionLost
            OfflineBanner(lost.reason) { viewModel.retryConnection() }
        }
        DashboardTabs(selectedTab) { selectedTab = it }
        CompactMarketOverview(dashboardTicks, worthCoins, connectionState is MarketConnectionState.Connected)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedTab == 1 && visiblePairs.isEmpty()) item { EmptyWatchlistState() }
            items(visiblePairs, key = { it.symbol }) { pair ->
                MarketCoinCard(
                    pair = pair,
                    tick = dashboardTicks[pair.symbol],
                    worth = worthBySymbol[pair.symbol],
                    rank = worthRank(worthCoins, pair.symbol),
                    onClick = {
                        viewModel.selectPair(pair)
                        onNavigateToDetail(pair)
                    }
                )
            }
        }
    }
}

private fun worthRank(items: List<WorthCoinInfo>, symbol: String): Int? =
    items.indexOfFirst { it.pair.symbol == symbol }.takeIf { it >= 0 }?.plus(1)

@Composable
private fun DashboardHeader(connectionLabel: String, connectionColor: Color, onRefresh: () -> Unit, onSettings: () -> Unit, onChart: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(9.dp).background(connectionColor, CircleShape)); Spacer(Modifier.width(7.dp))
            Text(connectionLabel, color = connectionColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.45.sp, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = onRefresh, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.Refresh, "Refresh", tint = TvTextPrimary, modifier = Modifier.size(27.dp)) }
            IconButton(onClick = onSettings, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.Settings, "Settings", tint = TvTextPrimary, modifier = Modifier.size(27.dp)) }
            Button(onClick = onChart, colors = ButtonDefaults.buttonColors(containerColor = TvGreen), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp), modifier = Modifier.testTag("dashboard_trigger_detail_button")) {
                Icon(Icons.Default.ShowChart, null, Modifier.size(18.dp), tint = Color.Black); Spacer(Modifier.width(5.dp)); Text("Chart", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
        Spacer(Modifier.height(2.dp)); Text("Dashboard Pasar & AI", color = TvTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun DashboardTabs(selectedTab: Int, onSelected: (Int) -> Unit) {
    TabRow(selectedTabIndex = selectedTab, containerColor = DashboardBackground, contentColor = TvTextPrimary, modifier = Modifier.fillMaxWidth(), indicator = { positions ->
        if (selectedTab < positions.size) TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[selectedTab]), color = TvGreen, height = 3.dp)
    }) {
        Tab(selectedTab == 0, { onSelected(0) }, text = { TabLabel("SEMUA", Icons.Default.GridView, selectedTab == 0, TvGreen) })
        Tab(selectedTab == 1, { onSelected(1) }, text = { TabLabel("WATCHLIST", Icons.Default.Star, selectedTab == 1, TvGold) })
    }
}

@Composable
private fun TabLabel(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, activeColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (selected) activeColor else TvTextSecondary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (selected) TvTextPrimary else TvTextSecondary)
    }
}

@Composable
private fun CompactMarketOverview(ticks: Map<String, MarketTick>, worthCoins: List<WorthCoinInfo>, isLive: Boolean) {
    val totalVolume = ticks.values.sumOf { it.volume24h }
    val avgChange = if (ticks.isEmpty()) 0.0 else ticks.values.map { it.change24h }.average()
    val score = worthCoins.maxOfOrNull { it.worthScore } ?: 0
    val scoreColor = scoreColor(score)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DashboardSurface), border = BorderStroke(1.dp, DashboardBorder)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                OverviewValue("PAIR", "${ticks.size}", if (isLive) "LIVE" else "OFFLINE", TvGreen, Modifier.weight(0.7f)); OverviewDivider()
                OverviewValue("24H VOL", PriceFormatter.formatPrice(totalVolume), "market", TvGreen, Modifier.weight(1.65f)); OverviewDivider()
                OverviewValue("AVG 24H", PriceFormatter.formatPercentage(avgChange), "change", if (avgChange >= 0) TvGreen else TvRed, Modifier.weight(1.1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MARKET SCORE", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp); Spacer(Modifier.width(6.dp))
            Text("$score/100", color = scoreColor, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF252D36))) { Box(Modifier.fillMaxWidth(score.coerceIn(0, 100) / 100f).fillMaxSize().clip(RoundedCornerShape(4.dp)).background(scoreColor)) }
            Spacer(Modifier.width(7.dp)); Text(marketScoreLabel(score), color = scoreColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun OverviewValue(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = TvTextSecondary, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1); Spacer(Modifier.height(2.dp))
        Text(value, color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1); Text(detail, color = color, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun OverviewDivider() { Box(Modifier.width(1.dp).height(30.dp).background(DashboardBorder)) }

@Composable
private fun MarketCoinCard(pair: TradingPair, tick: MarketTick?, worth: WorthCoinInfo?, rank: Int?, onClick: () -> Unit) {
    val change = tick?.change24h ?: 0.0
    val changeColor = when { change > 0 -> TvGreen; change < 0 -> TvRed; else -> TvTextSecondary }
    val score = worth?.worthScore
    val aiColor = score?.let(::scoreColor) ?: TvGreen
    val rangePct = tick?.let { if (it.low24h > 0) ((it.high24h - it.low24h) / it.low24h) * 100.0 else 0.0 } ?: 0.0
    Card(Modifier.fillMaxWidth().clickable { onClick() }, RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = DashboardCard), border = BorderStroke(1.dp, DashboardBorder)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssetBadge(pair.baseAsset, changeColor); Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (rank != null) { Box(Modifier.clip(RoundedCornerShape(6.dp)).background(TvGreen.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("#${rank}", color = TvGreen, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold) }; Spacer(Modifier.width(6.dp)) }
                        Text(pair.displayName, color = TvTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                    Spacer(Modifier.height(2.dp)); Text(pair.symbol, color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(tick?.let { PriceFormatter.formatPrice(it.price) } ?: "—", color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1); Spacer(Modifier.height(2.dp))
                    Text(tick?.let { PriceFormatter.formatPercentage(it.change24h) } ?: "—", color = changeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (score != null) { Text("SKOR $score/100", color = aiColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.width(7.dp)); Text("•", color = TvTextSecondary, fontSize = 8.sp); Spacer(Modifier.width(7.dp)) }
                Text(buildString { append("Vol "); append(tick?.let { PriceFormatter.formatPrice(it.volume24h) } ?: "—"); append("  ·  Range "); append(String.format("%.2f", rangePct)); append("%") }, color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.weight(1f)); Icon(Icons.Default.ShowChart, null, tint = TvGreen, modifier = Modifier.size(15.dp))
            }
            // Recommendation from ViewModel WorthCoinInfo — aligned with scoring logic
            worth?.recommendation?.takeIf { it.isNotBlank() }?.let { rec ->
                Spacer(Modifier.height(5.dp))
                Text(rec, color = aiColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.height(7.dp))
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(38.dp), colors = ButtonDefaults.buttonColors(containerColor = TvGreen), shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(vertical = 0.dp)) {
                Icon(Icons.Default.ShowChart, null, Modifier.size(15.dp), tint = Color.Black); Spacer(Modifier.width(5.dp)); Text("Buka Chart", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
            }
        }
    }
}

@Composable
private fun AssetBadge(asset: String, color: Color) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Text(asset.take(4), color = color, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
}

@Composable
private fun EmptyWatchlistState() {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = DashboardCard), border = BorderStroke(1.dp, DashboardBorder)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Star, null, tint = TvGold, modifier = Modifier.size(28.dp)); Spacer(Modifier.height(8.dp))
            Text("Watchlist masih kosong", color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.height(4.dp))
            Text("Pilih koin lewat Chart, lalu tekan bintang untuk memasukkannya ke Watchlist.", color = TvTextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun OfflineBanner(reason: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), RoundedCornerShape(11.dp), colors = CardDefaults.cardColors(containerColor = TvRed.copy(alpha = 0.09f)), border = BorderStroke(1.dp, TvRed.copy(alpha = 0.25f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Koneksi market terputus", color = TvRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(reason, color = TvTextSecondary, fontSize = 8.sp, maxLines = 2)
            }
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = TvRed), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("RETRY", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 75 -> TvGreen
    score >= 50 -> TvAmber
    else -> TvRed
}

private fun marketScoreLabel(score: Int): String = when {
    score >= 75 -> "BULLISH"
    score >= 50 -> "NETRAL"
    else -> "LEMAH"
}
