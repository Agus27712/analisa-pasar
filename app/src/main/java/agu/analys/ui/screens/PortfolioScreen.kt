package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.model.AppScreen
import agu.analys.model.TradingPair
import agu.analys.ui.components.dashboard.AppBottomNavigationBar
import agu.analys.ui.components.dashboard.NavTab
import agu.analys.ui.components.security.SecurityPinDialog
import agu.analys.ui.components.simulation.SimulationTopUpModal
import agu.analys.ui.screens.portfolio.HoldingItem
import agu.analys.ui.screens.portfolio.PortfolioTab
import agu.analys.ui.screens.portfolio.RealPortfolioView
import agu.analys.ui.screens.portfolio.SimulationPortfolioView
import agu.analys.ui.theme.*
import agu.analys.viewmodel.TradingViewModel

/**
 * Screen Utama Portofolio (Coordinator):
 * Memisahkan secara bersih antara Portofolio Simulasi dan Portofolio Real Indodax.
 */
@Composable
fun PortfolioScreen(
    viewModel: TradingViewModel,
    onNavigateToDetail: (TradingPair) -> Unit,
    onNavigateToSimulation: (TradingPair) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wallet by viewModel.simulationWallet.collectAsStateWithLifecycle()
    val history by viewModel.simulationHistory.collectAsStateWithLifecycle()
    val dashboardTicks by viewModel.dashboardTicks.collectAsStateWithLifecycle()
    val currentTick by viewModel.currentTick.collectAsStateWithLifecycle()
    val selectedPair by viewModel.selectedPair.collectAsStateWithLifecycle()
    val spotPosition by viewModel.spotPosition.collectAsStateWithLifecycle()
    val signal by viewModel.aiSignalState.collectAsStateWithLifecycle()

    val isRealBuyMode by viewModel.isRealBuyMode.collectAsStateWithLifecycle()
    val isPinUnlocked by viewModel.isPinUnlocked.collectAsStateWithLifecycle()
    val realBalance by viewModel.realIndodaxBalance.collectAsStateWithLifecycle()
    val isFetchingRealBalance by viewModel.isFetchingRealBalance.collectAsStateWithLifecycle()
    val realTradeStatus by viewModel.realTradeStatus.collectAsStateWithLifecycle()

    LaunchedEffect(isPinUnlocked) {
        if (isPinUnlocked && viewModel.hasRealCredentialsConfigured()) {
            viewModel.fetchRealBalance()
        }
    }

    var showRealPortfolioMode by remember(isRealBuyMode) { mutableStateOf(isRealBuyMode) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinDialogError by remember { mutableStateOf<String?>(null) }

    var selectedTab by remember { mutableStateOf(PortfolioTab.HOLDINGS) }
    var showTopUpModal by remember { mutableStateOf(false) }

    // Hitung Koin Dimiliki & Metrik Portofolio Simulasi
    val holdings = remember(wallet, dashboardTicks, currentTick) {
        wallet.coinBalances.filter { it.value > 0.00000001 }.map { (baseAsset, qty) ->
            val symbol = "${baseAsset}IDR"
            val price = when {
                symbol.equals(currentTick?.symbol, ignoreCase = true) -> currentTick?.price ?: 0.0
                dashboardTicks.containsKey(symbol) -> dashboardTicks[symbol]?.price ?: 0.0
                else -> 0.0
            }
            val avgPrice = wallet.avgBuyPrices[baseAsset] ?: 0.0
            val effectivePrice = if (price > 0.0) price else avgPrice
            val totalValue = qty * effectivePrice
            val pnl = if (avgPrice > 0.0) (effectivePrice - avgPrice) * qty else 0.0
            val pnlPct = if (avgPrice > 0.0) ((effectivePrice - avgPrice) / avgPrice) * 100.0 else 0.0

            val pair = TradingPair.fromCustomSymbol(symbol, "IDR")
            HoldingItem(
                baseAsset = baseAsset,
                quantity = qty,
                avgBuyPrice = avgPrice,
                currentPrice = effectivePrice,
                totalValueIdr = totalValue,
                pnlIdr = pnl,
                pnlPercent = pnlPct,
                tradingPair = pair
            )
        }.sortedByDescending { it.totalValueIdr }
    }

    val totalCoinValueIdr = remember(holdings) { holdings.sumOf { it.totalValueIdr } }
    val totalPortfolioValueIdr = remember(wallet, totalCoinValueIdr) { wallet.idrBalance + totalCoinValueIdr }
    val totalUnrealizedPnlIdr = remember(holdings) { holdings.sumOf { it.pnlIdr } }
    val totalCostBasis = remember(holdings) { holdings.sumOf { it.quantity * it.avgBuyPrice } }
    val totalUnrealizedPnlPct = remember(totalCostBasis, totalUnrealizedPnlIdr) {
        if (totalCostBasis > 0.0) (totalUnrealizedPnlIdr / totalCostBasis) * 100.0 else 0.0
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // TOP APP BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF09101A))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Kembali",
                        tint = TvTextPrimary
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        text = "PORTOFOLIO SAYA",
                        color = TvTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = if (showRealPortfolioMode) "Aset Riil Indodax Terhubung" else "Simulasi Akun & Manajemen Aset",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!showRealPortfolioMode) {
                    IconButton(
                        onClick = { showTopUpModal = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = "Top Up / Reset",
                            tint = TvGreen
                        )
                    }
                }
            }
        }

        // SEGMENT SWITCHER: SIMULASI VS REAL INDODAX
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF09101A))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101720), RoundedCornerShape(10.dp))
                    .padding(3.dp)
            ) {
                // Tab 1: SIMULASI
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!showRealPortfolioMode) Color(0xFF1E2836) else Color.Transparent)
                        .clickable { showRealPortfolioMode = false }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = if (!showRealPortfolioMode) Color(0xFF72B7FF) else TvTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Portofolio Simulasi",
                            color = if (!showRealPortfolioMode) TvTextPrimary else TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Tab 2: REAL INDODAX
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (showRealPortfolioMode) Color(0xFF1E2836) else Color.Transparent)
                        .clickable {
                            showRealPortfolioMode = true
                            if (!isPinUnlocked) {
                                if (!viewModel.hasSecurityPin()) {
                                    onOpenSettings()
                                } else {
                                    showPinDialog = true
                                }
                            } else {
                                viewModel.fetchRealBalance()
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPinUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (showRealPortfolioMode) TvGreen else TvTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Portofolio Real (Indodax)",
                            color = if (showRealPortfolioMode) TvGreen else TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // CONTENT SECTION: REAL VS SIMULATION
        if (showRealPortfolioMode) {
            RealPortfolioView(
                isPinUnlocked = isPinUnlocked,
                realBalance = realBalance,
                isFetchingRealBalance = isFetchingRealBalance,
                dashboardTicks = dashboardTicks,
                currentTick = currentTick,
                realTradeStatus = realTradeStatus,
                onUnlockPin = {
                    if (!viewModel.hasSecurityPin()) {
                        onOpenSettings()
                    } else {
                        pinDialogError = null
                        showPinDialog = true
                    }
                },
                onRefreshRealBalance = { viewModel.fetchRealBalance() },
                onNavigateToDetail = onNavigateToDetail,
                onSelectPair = { viewModel.selectPair(it) },
                modifier = Modifier.weight(1f)
            )
        } else {
            SimulationPortfolioView(
                wallet = wallet,
                history = history,
                holdings = holdings,
                totalPortfolioValueIdr = totalPortfolioValueIdr,
                totalUnrealizedPnlIdr = totalUnrealizedPnlIdr,
                totalUnrealizedPnlPct = totalUnrealizedPnlPct,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onOpenTopUp = { showTopUpModal = true },
                onNavigateToDetail = onNavigateToDetail,
                onNavigateToSimulation = onNavigateToSimulation,
                selectedPair = selectedPair,
                spotPosition = spotPosition,
                signal = signal,
                currentTick = currentTick,
                onRefreshSpotPosition = viewModel::refreshSpotPosition,
                modifier = Modifier.weight(1f)
            )
        }

        // BOTTOM NAVIGATION BAR
        AppBottomNavigationBar(
            currentTab = NavTab.PORTOFOLIO,
            onSelectTab = { tab ->
                when (tab) {
                    NavTab.WATCHLIST -> viewModel.navigateTo(AppScreen.DASHBOARD)
                    NavTab.PORTOFOLIO -> { /* Sudah di Portofolio */ }
                    NavTab.SIMULASI -> viewModel.openSimulation()
                    NavTab.BELAJAR -> viewModel.openLearning()
                    NavTab.SETTINGS -> onOpenSettings()
                }
            }
        )
    }

    if (showTopUpModal) {
        SimulationTopUpModal(
            wallet = wallet,
            onTopUp = { amount ->
                viewModel.topUpSimulationBalance(amount)
                showTopUpModal = false
            },
            onReset = {
                viewModel.resetSimulationAccount()
                showTopUpModal = false
            },
            onDismiss = { showTopUpModal = false }
        )
    }

    if (showPinDialog) {
        SecurityPinDialog(
            title = "VERIFIKASI PIN PORTOFOLIO REAL",
            subtitle = "Masukkan 6-digit PIN untuk membuka akses Portofolio Indodax.",
            isSetupMode = false,
            errorMessage = pinDialogError,
            onPinSubmitted = { enteredPin ->
                val ok = viewModel.verifyPin(enteredPin)
                if (ok) {
                    showPinDialog = false
                    pinDialogError = null
                    viewModel.fetchRealBalance()
                } else {
                    pinDialogError = "PIN Keamanan Salah. Silakan coba lagi."
                }
            },
            onDismiss = {
                showPinDialog = false
                pinDialogError = null
            }
        )
    }
}
