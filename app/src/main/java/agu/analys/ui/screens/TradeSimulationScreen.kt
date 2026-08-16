package agu.analys.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.ui.components.dashboard.AppBottomNavigationBar
import agu.analys.ui.components.dashboard.NavTab
import agu.analys.ui.components.simulation.*
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.TradingViewModel

@Composable
fun TradeSimulationScreen(
    viewModel: TradingViewModel,
    onOpenChart: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedPair by viewModel.selectedPair.collectAsState()
    val currentTick by viewModel.currentTick.collectAsState()
    val orderBookBids by viewModel.orderBookBids.collectAsState()
    val orderBookAsks by viewModel.orderBookAsks.collectAsState()
    val simulationWallet by viewModel.simulationWallet.collectAsState()
    val openOrders by viewModel.simulationOpenOrders.collectAsState()
    val tradeHistory by viewModel.simulationHistory.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val hotCoins by viewModel.hotCoins.collectAsState()
    val dashboardTicks by viewModel.dashboardTicks.collectAsState()
    val lastFilledOrder by viewModel.lastFilledSimulationOrder.collectAsState()

    var selectedSide by remember { mutableStateOf(SimulationOrderSide.BUY) }
    var selectedType by remember { mutableStateOf(SimulationOrderType.LIMIT) }
    var inputPrice by remember { mutableStateOf("") }
    var inputStopPrice by remember { mutableStateOf("") }
    var inputQuantity by remember { mutableStateOf("") }
    var inputTotalIdr by remember { mutableStateOf("") }

    var showPairSelector by remember { mutableStateOf(false) }
    var showTopUpModal by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    val currentPrice = currentTick?.price ?: 0.0
    val isPriceUp = (currentTick?.change24h ?: 0.0) >= 0

    // Auto update input price if empty when tick arrives
    LaunchedEffect(selectedPair, currentTick?.price) {
        if (inputPrice.isEmpty() && currentPrice > 0.0) {
            inputPrice = PriceFormatter.formatRawDecimal(currentPrice)
        }
    }

    // Toast notification when order matches/fills in background
    LaunchedEffect(lastFilledOrder) {
        lastFilledOrder?.let { order ->
            Toast.makeText(
                context,
                "Order ${order.side.displayName} ${order.baseAsset} FILLED pada Rp ${PriceFormatter.formatPrice(order.filledAvgPrice)}!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Auto-calculate Total when Price or Quantity changes
    val updateCalculatedTotal: (String, String) -> Unit = { priceStr, qtyStr ->
        val p = if (selectedType == SimulationOrderType.MARKET) currentPrice else (priceStr.toDoubleOrNull() ?: 0.0)
        val q = qtyStr.toDoubleOrNull() ?: 0.0
        if (p > 0.0 && q > 0.0) {
            inputTotalIdr = PriceFormatter.formatRawDecimal(p * q)
        }
    }

    val availablePairs = remember(watchlist, hotCoins) {
        val list = mutableListOf<TradingPair>()
        list.addAll(TradingPair.POPULAR_PAIRS)
        list.addAll(watchlist.map { TradingPair.fromCustomSymbol(it) })
        list.addAll(hotCoins.map { TradingPair.fromCustomSymbol(it.symbol) })
        list.distinctBy { it.symbol }
    }

    val allTicks = remember(dashboardTicks, hotCoins) {
        dashboardTicks + hotCoins.associateBy { it.symbol }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // TOP BAR
        SimulationTopBar(
            pair = selectedPair,
            onOpenPairSelector = { showPairSelector = true },
            onOpenChart = onOpenChart,
            onOpenMore = { showOptionsMenu = true }
        )

        // Dropdown Menu Top Right
        Box(modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.TopEnd)) {
            DropdownMenu(
                expanded = showOptionsMenu,
                onDismissRequest = { showOptionsMenu = false },
                modifier = Modifier.background(Color(0xFF162032))
            ) {
                DropdownMenuItem(
                    text = { Text("Top Up Saldo Virtual", color = Color.White, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = TvGreen) },
                    onClick = {
                        showOptionsMenu = false
                        showTopUpModal = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Reset Akun Simulasi", color = TvRed, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = TvRed) },
                    onClick = {
                        showOptionsMenu = false
                        viewModel.resetSimulationAccount()
                        Toast.makeText(context, "Akun simulasi direset ke saldo Rp 10.000.000", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        // MAIN BODY (Scrollable LazyColumn)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Notice Banner (Indodax style amber notice)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2E2413))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Simulasi Trade Spot Indodax menggunakan data harga live real-time.",
                            color = Color(0xFFFDE68A),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // 2 COLUMNS (Trade Form + Live Order Book)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // LEFT COLUMN (Order Form: 56% width)
                    Box(modifier = Modifier.weight(0.56f)) {
                        SimulationOrderForm(
                            pair = selectedPair,
                            currentPrice = currentPrice,
                            wallet = simulationWallet,
                            selectedSide = selectedSide,
                            selectedType = selectedType,
                            onSideChange = { selectedSide = it },
                            onTypeChange = {
                                selectedType = it
                                if (it == SimulationOrderType.MARKET) {
                                    updateCalculatedTotal("", inputQuantity)
                                }
                            },
                            inputPrice = inputPrice,
                            inputStopPrice = inputStopPrice,
                            inputQuantity = inputQuantity,
                            inputTotalIdr = inputTotalIdr,
                            onPriceChange = {
                                inputPrice = it
                                updateCalculatedTotal(it, inputQuantity)
                            },
                            onStopPriceChange = { inputStopPrice = it },
                            onQuantityChange = {
                                inputQuantity = it
                                updateCalculatedTotal(inputPrice, it)
                            },
                            onTotalIdrChange = {
                                inputTotalIdr = it
                                val tot = it.toDoubleOrNull() ?: 0.0
                                val p = if (selectedType == SimulationOrderType.MARKET) currentPrice else (inputPrice.toDoubleOrNull() ?: currentPrice)
                                if (p > 0.0 && tot > 0.0) {
                                    val q = tot / p
                                    inputQuantity = if (q >= 1000) String.format("%.2f", q) else String.format("%.4f", q).trimEnd('0').trimEnd('.')
                                }
                            },
                            onSubmitOrder = {
                                val p = if (selectedType == SimulationOrderType.MARKET) currentPrice else (inputPrice.toDoubleOrNull() ?: 0.0)
                                val stopP = inputStopPrice.toDoubleOrNull() ?: 0.0
                                val q = inputQuantity.toDoubleOrNull() ?: 0.0

                                if (q <= 0.0) {
                                    Toast.makeText(context, "Masukkan jumlah koin yang valid", Toast.LENGTH_SHORT).show()
                                    return@SimulationOrderForm
                                }

                                if (selectedType != SimulationOrderType.MARKET && p <= 0.0) {
                                    Toast.makeText(context, "Masukkan harga limit yang valid", Toast.LENGTH_SHORT).show()
                                    return@SimulationOrderForm
                                }

                                if (selectedType == SimulationOrderType.STOP_LIMIT && stopP <= 0.0) {
                                    Toast.makeText(context, "Masukkan harga stop trigger yang valid", Toast.LENGTH_SHORT).show()
                                    return@SimulationOrderForm
                                }

                                val result = viewModel.submitSimulationOrder(
                                    side = selectedSide,
                                    type = selectedType,
                                    price = p,
                                    stopPrice = stopP,
                                    quantity = q
                                )

                                when (result) {
                                    is SimulationOrderResult.Success -> {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                        inputQuantity = ""
                                        inputTotalIdr = ""
                                    }
                                    is SimulationOrderResult.Error -> {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onOpenTopUp = { showTopUpModal = true }
                        )
                    }

                    // RIGHT COLUMN (Live Order Book: 44% width)
                    Box(modifier = Modifier.weight(0.44f)) {
                        SimulationOrderBook(
                            bids = orderBookBids,
                            asks = orderBookAsks,
                            currentPrice = currentPrice,
                            isPriceUp = isPriceUp,
                            onSelectPrice = { selectedPrice ->
                                inputPrice = PriceFormatter.formatRawDecimal(selectedPrice)
                                updateCalculatedTotal(inputPrice, inputQuantity)
                            },
                            onViewMore = onOpenChart
                        )
                    }
                }
            }

            // BOTTOM SECTION: Open Orders & Riwayat
            item {
                SimulationOpenOrdersList(
                    openOrders = openOrders,
                    tradeHistory = tradeHistory,
                    currentSymbol = selectedPair.symbol,
                    onCancelOrder = { orderId ->
                        val ok = viewModel.cancelSimulationOrder(orderId)
                        if (ok) Toast.makeText(context, "Order berhasil dibatalkan", Toast.LENGTH_SHORT).show()
                    },
                    onCancelAllOrders = { symbol ->
                        val count = viewModel.cancelAllSimulationOrders(symbol)
                        Toast.makeText(context, "$count order berhasil dibatalkan", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }

        // BOTTOM NAVIGATION BAR
        AppBottomNavigationBar(
            currentTab = NavTab.SIMULASI,
            onSelectTab = { tab ->
                when (tab) {
                    NavTab.WATCHLIST -> onNavigateToDashboard()
                    NavTab.SIMULASI -> { /* Already on Simulasi */ }
                    NavTab.BELAJAR -> viewModel.openLearning()
                    NavTab.SETTINGS -> onOpenSettings()
                }
            }
        )
    }

    // Modal Pair Selector
    if (showPairSelector) {
        SimulationPairSelectorModal(
            availablePairs = availablePairs,
            ticksMap = allTicks,
            selectedPair = selectedPair,
            onSelectPair = { pair ->
                viewModel.selectPair(pair)
                inputPrice = ""
                inputQuantity = ""
                inputTotalIdr = ""
            },
            onDismiss = { showPairSelector = false }
        )
    }

    // Modal Top Up
    if (showTopUpModal) {
        SimulationTopUpModal(
            wallet = simulationWallet,
            onTopUp = { amount ->
                viewModel.topUpSimulationBalance(amount)
                Toast.makeText(context, "Modal berhasil ditambah Rp ${PriceFormatter.formatPrice(amount)}", Toast.LENGTH_SHORT).show()
            },
            onReset = {
                viewModel.resetSimulationAccount()
                Toast.makeText(context, "Akun simulasi direset ke saldo awal Rp 10.000.000", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showTopUpModal = false }
        )
    }
}

@Composable
private fun SimulationTopBar(
    pair: TradingPair,
    onOpenPairSelector: () -> Unit,
    onOpenChart: () -> Unit,
    onOpenMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Dropdown Pair Title (e.g. PRCL/IDR v)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onOpenPairSelector() }
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${pair.baseAsset}/${pair.quoteAsset}",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Pilih Koin",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Action Icons: Chart + More Vert
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onOpenChart) {
                Icon(
                    imageVector = Icons.Default.CandlestickChart,
                    contentDescription = "Buka Chart",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onOpenMore) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Menu Lainnya",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
