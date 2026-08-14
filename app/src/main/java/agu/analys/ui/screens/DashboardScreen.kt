package agu.analys.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
private val DashboardSurface = Color(0xFF0D1722)
private val DashboardCard = Color(0xFF0D1722)
private val DashboardBorder = Color(0xFF1A3347)
private val TvGold = Color(0xFFFFD54A)
private val TvAmber = Color(0xFFFFB300)
/** Accent biru senada section title di Detail */
private val AccentBlue = Color(0xFF2196F3)
private val AccentBlueSoft = Color(0xFF6FB8FF)

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
        is MarketConnectionState.Loading -> TvAmber
        is MarketConnectionState.ConnectionLost -> TvRed
    }
    val connectionLabel = when (connectionState) {
        is MarketConnectionState.Connected -> "INDODAX LIVE · IDR"
        is MarketConnectionState.Loading -> "MENGHUBUNGKAN..."
        is MarketConnectionState.ConnectionLost -> "OFFLINE · ketuk refresh"
    }

    Column(modifier.fillMaxSize().background(DashboardBackground)) {
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

        ModeSwitchToggle(isScalping = isScalpingMode, onToggle = { viewModel.setScalpingMode(it) })
        CompactMarketOverview(dashboardTicks, worthCoins, connectionState is MarketConnectionState.Connected)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Volume Tertinggi (live Indodax) ──
            if (hotCoins.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    ) {
                        Icon(Icons.Default.TrendingUp, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "VOLUME TERTINGGI",
                            color = AccentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "· 24 jam · live",
                            color = TvTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
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

            // ── Watchlist ──
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
                if (!watchlist.contains(pair.symbol)) {
                    viewModel.toggleWatchlist(pair.symbol)
                }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun VolumeLeaderChip(
    rank: Int,
    tick: MarketTick,
    isWatched: Boolean,
    onOpen: () -> Unit,
    onToggleWatch: () -> Unit
) {
    val base = tick.symbol.removeSuffix("IDR").ifBlank { tick.symbol }
    val rangePct = if (tick.low24h > 0) ((tick.high24h - tick.low24h) / tick.low24h) * 100.0 else 0.0
    val highlight = rank <= 3

    Card(
        modifier = Modifier
            .width(168.dp)
            .clickable { onOpen() }
            .testTag("volume_leader_${tick.symbol}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCard),
        border = BorderStroke(1.dp, if (highlight) AccentBlue.copy(alpha = 0.4f) else DashboardBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("#" + rank, color = AccentBlueSoft, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(base, color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text(tick.symbol, color = TvTextSecondary, fontSize = 9.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                PriceFormatter.formatPrice(tick.price),
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Vol " + PriceFormatter.formatPrice(tick.volume24h),
                color = AccentBlueSoft,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (rangePct > 0) {
                Text(
                    "Range " + String.format("%.1f", rangePct) + "%",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f).height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Chart", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                OutlinedButton(
                    onClick = onToggleWatch,
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, if (isWatched) TvGold.copy(alpha = 0.5f) else DashboardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isWatched) TvGold else TvTextSecondary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        if (isWatched) Icons.Default.Star else Icons.Default.Add,
                        null,
                        Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

private fun worthRank(items: List<WorthCoinInfo>, symbol: String): Int? =
    items.indexOfFirst { it.pair.symbol == symbol }.takeIf { it >= 0 }?.plus(1)

@Composable
private fun DashboardHeader(
    connectionLabel: String,
    connectionColor: Color,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onAddAsset: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(9.dp).background(connectionColor, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(
                connectionLabel,
                color = connectionColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.45.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Refresh, "Refresh", tint = TvTextPrimary, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Settings, "Settings", tint = TvTextPrimary, modifier = Modifier.size(24.dp))
            }
            Button(
                onClick = onAddAsset,
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("add_asset_button")
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = Color.Black)
                Spacer(Modifier.width(4.dp))
                Text("Tambah Koin", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Watchlist Koin", color = TvTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun CompactMarketOverview(ticks: Map<String, MarketTick>, worthCoins: List<WorthCoinInfo>, isLive: Boolean) {
    val totalVolume = ticks.values.sumOf { it.volume24h }
    val avgChange = if (ticks.isEmpty()) 0.0 else ticks.values.map { it.change24h }.filter { !it.isNaN() }.let { if (it.isEmpty()) 0.0 else it.average() }
    val score = worthCoins.maxOfOrNull { it.worthScore } ?: 0
    val scoreColor = scoreColor(score)

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DashboardSurface),
            border = BorderStroke(1.dp, DashboardBorder)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OverviewValue("PAIR", "${ticks.size}", if (isLive) "LIVE" else "OFFLINE", TvGreen, Modifier.weight(0.7f))
                OverviewDivider()
                OverviewValue("24H VOL", PriceFormatter.formatPrice(totalVolume), "market", TvGreen, Modifier.weight(1.65f))
                OverviewDivider()
                OverviewValue("AVG 24H", PriceFormatter.formatPercentage(avgChange), "change", if (avgChange >= 0) TvGreen else TvRed, Modifier.weight(1.1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            Text("MARKET SCORE", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
            Spacer(Modifier.width(6.dp))
            Text("$score/100", color = scoreColor, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF252D36))) {
                Box(Modifier.fillMaxWidth(score.coerceIn(0, 100) / 100f).fillMaxSize().clip(RoundedCornerShape(4.dp)).background(scoreColor))
            }
            Spacer(Modifier.width(7.dp))
            Text(marketScoreLabel(score), color = scoreColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun OverviewValue(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = TvTextSecondary, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(detail, color = color, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun OverviewDivider() {
    Box(Modifier.width(1.dp).height(30.dp).background(DashboardBorder))
}

@Composable
private fun WatchlistCoinCard(
    pair: TradingPair,
    tick: MarketTick?,
    worth: WorthCoinInfo?,
    rank: Int?,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val change = tick?.change24h ?: 0.0
    val changeColor = when {
        change.isNaN() -> TvTextSecondary
        change > 0 -> TvGreen
        change < 0 -> TvRed
        else -> TvTextSecondary
    }
    val score = worth?.worthScore
    val aiColor = score?.let(::scoreColor) ?: TvGreen
    val rangePct = tick?.let { if (it.low24h > 0) ((it.high24h - it.low24h) / it.low24h) * 100.0 else 0.0 } ?: 0.0

    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCard),
        border = BorderStroke(1.dp, DashboardBorder)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssetBadge(pair.baseAsset, changeColor)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (rank != null) {
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(TvGreen.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("#" + rank, color = TvGreen, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(pair.displayName, color = TvTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(pair.symbol, color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        tick?.let { PriceFormatter.formatPrice(it.price) } ?: "—",
                        color = TvTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tick?.let { PriceFormatter.formatPercentage(it.change24h) } ?: "—",
                        color = changeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (score != null) {
                    Text("SKOR $score/100", color = aiColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(7.dp))
                    Text("•", color = TvTextSecondary, fontSize = 8.sp)
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    text = buildString {
                        append("Vol ")
                        append(tick?.let { PriceFormatter.formatPrice(it.volume24h) } ?: "—")
                        append("  ·  Range ")
                        append(String.format("%.2f", rangePct))
                        append("%")
                    },
                    color = TvTextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            worth?.recommendation?.takeIf { it.isNotBlank() }?.let { rec ->
                Spacer(Modifier.height(4.dp))
                Text(rec, color = aiColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onClick,
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(vertical = 0.dp)
                ) {
                    Icon(Icons.Default.ShowChart, null, Modifier.size(16.dp), tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text("Buka Chart", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                }
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, TvRed.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvRed),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("Hapus", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AssetBadge(asset: String, color: Color) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Text(asset.take(4), color = color, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun EmptyWatchlistState(onAddClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCard),
        border = BorderStroke(1.dp, DashboardBorder)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Star, null, tint = TvGold, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("Watchlist masih kosong", color = TvTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text("Tambahkan koin favorit atau pilih dari Volume Tertinggi di atas.", color = TvTextSecondary, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onAddClick,
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = Color.Black)
                Spacer(Modifier.width(6.dp))
                Text("Tambah Koin", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

@Composable
private fun AddAssetDialog(
    currentWatchlist: Set<String>,
    onDismiss: () -> Unit,
    onAddPair: (TradingPair) -> Unit
) {
    val popularPairs = TradingPair.POPULAR_PAIRS
    var manualInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DashboardSurface),
            border = BorderStroke(1.dp, DashboardBorder)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tambah Koin ke Watchlist", color = TvTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = TvTextSecondary)
                    }
                }
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        placeholder = { Text("cth: DOGEIDR, SOLIDR", color = TvTextSecondary, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("manual_asset_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvGreen,
                            unfocusedBorderColor = DashboardBorder,
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            cursorColor = TvGreen
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = {
                            val trimmed = manualInput.trim().uppercase()
                            if (trimmed.isNotEmpty()) {
                                onAddPair(TradingPair.fromCustomSymbol(trimmed))
                                manualInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(50.dp).testTag("manual_add_button")
                    ) {
                        Text("Tambah", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Atau pilih dari daftar populer Indodax:", color = TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(popularPairs, key = { it.symbol }) { pair ->
                        val isAdded = currentWatchlist.contains(pair.symbol)
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onAddPair(pair) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = DashboardCard),
                            border = BorderStroke(1.dp, if (isAdded) TvGreen.copy(alpha = 0.5f) else DashboardBorder)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssetBadge(pair.baseAsset, TvGreen)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pair.displayName, color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(pair.symbol, color = TvTextSecondary, fontSize = 10.sp)
                                }
                                if (isAdded) {
                                    Text("Ditambahkan", color = TvGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Button(
                                        onClick = { onAddPair(pair) },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("+ Tambah", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner(reason: String, onRetry: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = TvRed.copy(alpha = 0.09f)),
        border = BorderStroke(1.dp, TvRed.copy(alpha = 0.25f))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Koneksi market terputus", color = TvRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(reason, color = TvTextSecondary, fontSize = 8.sp, maxLines = 2)
            }
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = TvRed), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text("RETRY", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
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

@Composable
private fun ModeSwitchToggle(
    isScalping: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { onToggle(false) },
            modifier = Modifier.weight(1f).height(36.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!isScalping) TvGreen else DashboardCard
            ),
            shape = RoundedCornerShape(10.dp),
            border = if (!isScalping) null else BorderStroke(1.dp, DashboardBorder)
        ) {
            Text(
                "📈 Mode Swing (Long)",
                color = if (!isScalping) Color.Black else TvTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Button(
            onClick = { onToggle(true) },
            modifier = Modifier.weight(1f).height(36.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isScalping) TvGreen else DashboardCard
            ),
            shape = RoundedCornerShape(10.dp),
            border = if (isScalping) null else BorderStroke(1.dp, DashboardBorder)
        ) {
            Text(
                "⚡ Mode Scalping (Cepat)",
                color = if (isScalping) Color.Black else TvTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
