package agu.analys.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.TrendingUp
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.model.AppScreen
import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.ui.components.SpotPositionCard
import agu.analys.ui.components.dashboard.AppBottomNavigationBar
import agu.analys.ui.components.dashboard.AssetAvatar
import agu.analys.ui.components.dashboard.NavTab
import agu.analys.ui.components.security.SecurityPinDialog
import agu.analys.ui.components.simulation.SimulationTopUpModal
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.TradingViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PortfolioTab(val title: String) {
    HOLDINGS("Koin Dimiliki"),
    HISTORY("Riwayat Transaksi"),
    SPOT_TRACKER("Catatan Spot")
}

data class HoldingItem(
    val baseAsset: String,
    val quantity: Double,
    val avgBuyPrice: Double,
    val currentPrice: Double,
    val totalValueIdr: Double,
    val pnlIdr: Double,
    val pnlPercent: Double,
    val tradingPair: TradingPair
)

@Composable
fun PortfolioScreen(
    viewModel: TradingViewModel,
    onNavigateToDetail: (TradingPair) -> Unit,
    onNavigateToSimulation: (TradingPair) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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

    var showRealPortfolioMode by remember(isRealBuyMode) { mutableStateOf(isRealBuyMode) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinDialogError by remember { mutableStateOf<String?>(null) }

    var selectedTab by remember { mutableStateOf(PortfolioTab.HOLDINGS) }
    var showTopUpModal by remember { mutableStateOf(false) }

    // Hitung Koin Dimiliki & Metrik Portofolio
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
                        text = "Simulasi Akun & Manajemen Aset",
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

        if (showRealPortfolioMode) {
            // REAL INDODAX PORTFOLIO VIEW
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isPinUnlocked) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1722)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(TvGreen.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = TvGreen,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "PORTOFOLIO REAL TERKUNCI",
                                    color = TvTextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Masukkan PIN Keamanan untuk melihat rincian saldo & aset Indodax riil Anda.",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        if (!viewModel.hasSecurityPin()) {
                                            onOpenSettings()
                                        } else {
                                            pinDialogError = null
                                            showPinDialog = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(16.dp), tint = Color.Black)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Buka Access Portofolio Real", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    // UNLOCKED REAL PORTFOLIO
                    item {
                        // TOTAL REAL VALUE CARD
                        val realIdr = realBalance["idr"] ?: 0.0
                        val realCoinItems = remember(realBalance, dashboardTicks, currentTick) {
                            realBalance.filter { it.key != "idr" && it.value > 0.00000001 }.map { (coin, qty) ->
                                val symbol = "${coin.uppercase()}IDR"
                                val price = when {
                                    symbol.equals(currentTick?.symbol, ignoreCase = true) -> currentTick?.price ?: 0.0
                                    dashboardTicks.containsKey(symbol) -> dashboardTicks[symbol]?.price ?: 0.0
                                    else -> 0.0
                                }
                                val estIdr = qty * price
                                Pair(coin.uppercase(), Pair(qty, estIdr))
                            }.sortedByDescending { it.second.second }
                        }
                        val estTotalCryptoIdr = realCoinItems.sumOf { it.second.second }
                        val totalRealPortfolioIdr = realIdr + estTotalCryptoIdr

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1826)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TvGreen.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.VerifiedUser, null, tint = TvGreen, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "SALDO REAL INDODAX",
                                            color = TvGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }

                                    if (isFetchingRealBalance) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TvGreen, strokeWidth = 2.dp)
                                    } else {
                                        IconButton(
                                            onClick = { viewModel.fetchRealBalance() },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, "Refresh", tint = TvTextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = "Rp ${PriceFormatter.formatPrice(totalRealPortfolioIdr)}",
                                    color = TvTextPrimary,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "Estimasi Total Aset (Cash IDR + Koin Kripto)",
                                    color = TvTextSecondary,
                                    fontSize = 10.sp
                                )

                                Spacer(Modifier.height(14.dp))
                                HorizontalDivider(color = Color(0xFF1E2836))
                                Spacer(Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("SALDO CASH IDR", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                        Text("Rp ${PriceFormatter.formatPrice(realIdr)}", color = TvGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("ESTIMASI KOIN", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                        Text("Rp ${PriceFormatter.formatPrice(estTotalCryptoIdr)}", color = Color(0xFF72B7FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // REAL HOLDINGS ITEM LIST
                    item {
                        Text(
                            "ASET KRIPTO DI INDODAX",
                            color = TvTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    val realCoinItemsList = realBalance.filter { it.key != "idr" && it.value > 0.00000001 }.entries.toList()
                    if (realCoinItemsList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF101720), RoundedCornerShape(10.dp))
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Belum ada aset koin kripto terdeteksi di akun Indodax.",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        items(realCoinItemsList) { (coin, qty) ->
                            val coinUpper = coin.uppercase()
                            val symbol = "${coinUpper}IDR"
                            val price = when {
                                symbol.equals(currentTick?.symbol, ignoreCase = true) -> currentTick?.price ?: 0.0
                                dashboardTicks.containsKey(symbol) -> dashboardTicks[symbol]?.price ?: 0.0
                                else -> 0.0
                            }
                            val estVal = qty * price

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AssetAvatar(baseAsset = coinUpper, size = 32.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(coinUpper, color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Text("Jumlah: $qty $coinUpper", color = TvTextSecondary, fontSize = 10.sp)
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "Rp ${PriceFormatter.formatPrice(estVal)}",
                                            color = TvTextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            if (price > 0) "@ Rp ${PriceFormatter.formatPrice(price)}" else "Harga Ticker Off",
                                            color = TvTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // TOP-UP & WITHDRAW NOTICE CARD FOR REAL MODE
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B2A)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F))
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, null, tint = Color(0xFF72B7FF), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("INFORMASI DEPOSIT & WITHDRAW", color = Color(0xFF72B7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Akses API Indodax dikhususkan untuk Mode Trade saja. Untuk melakukan Top-Up Rupiah atau Penarikan Dana (Withdraw), silakan gunakan aplikasi atau website resmi Indodax.",
                                    color = TvTextSecondary,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // SIMULATION PORTFOLIO VIEW
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // CARD 1: TOTAL PORTOFOLIO & PNL OVERVIEW
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1826)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2F46))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ESTIMASI TOTAL NILAI",
                                color = TvTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFF162338), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PieChart,
                                    contentDescription = null,
                                    tint = Color(0xFF72B7FF),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "${holdings.size} Koin",
                                    color = Color(0xFF72B7FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Total Nilai Portofolio
                        Text(
                            text = PriceFormatter.formatPrice(totalPortfolioValueIdr, quoteAsset = "IDR"),
                            color = TvTextPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )

                        Spacer(Modifier.height(6.dp))

                        // Total Floating PnL
                        val pnlColor = if (totalUnrealizedPnlIdr >= 0) TvGreen else TvRed
                        val pnlPrefix = if (totalUnrealizedPnlIdr >= 0) "+" else ""
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Floating PnL: ",
                                color = TvTextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "$pnlPrefix${PriceFormatter.formatPrice(totalUnrealizedPnlIdr, quoteAsset = "IDR")} ($pnlPrefix${String.format(Locale.US, "%.2f", totalUnrealizedPnlPct)}%)",
                                color = pnlColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // CARD 2: SALDO KAS RUPIAH (SISA SALDO BELUM TERPAKAI & SALDO TERKUNCI)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09121C)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = TvGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "SALDO KAS RUPIAH (IDR)",
                                    color = Color(0xFF72B7FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Button(
                                onClick = { showTopUpModal = true },
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                            ) {
                                Text("+ Top Up Saldo", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Highlight Sisa Saldo Belum Terpakai
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF101C2B), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Sisa Saldo Belum Terpakai (Siap Trading)",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = PriceFormatter.formatPrice(wallet.getAvailableIdr(), quoteAsset = "IDR"),
                                    color = TvGreen,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = TvGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Rincian Saldo Terkunci & Total Kas
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Terkunci di Open Order", color = TvTextSecondary, fontSize = 10.sp)
                                Text(
                                    PriceFormatter.formatPrice(wallet.lockedIdr, quoteAsset = "IDR"),
                                    color = Color(0xFFFFB300),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Text("Total Kas IDR", color = TvTextSecondary, fontSize = 10.sp)
                                Text(
                                    PriceFormatter.formatPrice(wallet.idrBalance, quoteAsset = "IDR"),
                                    color = TvTextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // TAB SEGMENT: Koin Dimiliki | Riwayat Transaksi | Posisi Spot
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B111A), RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PortfolioTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF1E2836) else Color.Transparent)
                                .clickable { selectedTab = tab }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.title,
                                color = if (isSelected) Color(0xFF72B7FF) else TvTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // KONTEN TAB
            when (selectedTab) {
                PortfolioTab.HOLDINGS -> {
                    if (holdings.isEmpty()) {
                        item {
                            EmptyHoldingsCard(
                                onNavigateToSimulation = {
                                    val fallbackPair = TradingPair.popularPairsForSource().first()
                                    onNavigateToSimulation(fallbackPair)
                                }
                            )
                        }
                    } else {
                        items(holdings, key = { it.baseAsset }) { item ->
                            HoldingCoinCard(
                                item = item,
                                onTrade = { onNavigateToSimulation(item.tradingPair) },
                                onViewChart = { onNavigateToDetail(item.tradingPair) }
                            )
                        }
                    }
                }

                PortfolioTab.HISTORY -> {
                    if (history.isEmpty()) {
                        item {
                            EmptyHistoryCard()
                        }
                    } else {
                        items(history, key = { it.id }) { trade ->
                            TradeHistoryItemCard(trade = trade)
                        }
                    }
                }

                PortfolioTab.SPOT_TRACKER -> {
                    item {
                        SpotPositionCard(
                            symbol = selectedPair.symbol,
                            signal = signal,
                            position = spotPosition,
                            currentPrice = currentTick?.price ?: 0.0,
                            quoteAsset = selectedPair.quoteAsset,
                            onPositionChanged = viewModel::refreshSpotPosition
                        )
                    }
                }
            }
        }
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

@Composable
private fun HoldingCoinCard(
    item: HoldingItem,
    onTrade: () -> Unit,
    onViewChart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1420)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetAvatar(
                        baseAsset = item.baseAsset,
                        size = 36.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "${item.baseAsset} / IDR",
                            color = TvTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${PriceFormatter.formatRawDecimal(item.quantity)} ${item.baseAsset}",
                            color = Color(0xFF72B7FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Total Nilai Aset Rp & PnL
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = PriceFormatter.formatPrice(item.totalValueIdr, quoteAsset = "IDR"),
                        color = TvTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                    val pnlColor = if (item.pnlIdr >= 0) TvGreen else TvRed
                    val pnlPrefix = if (item.pnlIdr >= 0) "+" else ""
                    Text(
                        text = "$pnlPrefix${PriceFormatter.formatPrice(item.pnlIdr, quoteAsset = "IDR")} ($pnlPrefix${String.format(Locale.US, "%.2f", item.pnlPercent)}%)",
                        color = pnlColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Rincian Harga Beli Rata-Rata vs Harga Terkini
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF070D14), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Beli Rata2: ${PriceFormatter.formatPrice(item.avgBuyPrice, quoteAsset = "IDR")}",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = "Harga Pasar: ${PriceFormatter.formatPrice(item.currentPrice, quoteAsset = "IDR")}",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
            }

            Spacer(Modifier.height(10.dp))

            // Tombol Aksi: Trade / Jual & Buka Chart
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onViewChart,
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF72B7FF)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.ShowChart, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Chart", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onTrade,
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Trade / Jual", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun TradeHistoryItemCard(
    trade: SimulationTradeHistoryItem,
    modifier: Modifier = Modifier
) {
    val isBuy = trade.side == SimulationOrderSide.BUY
    val sideColor = if (isBuy) TvGreen else TvRed
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    val formattedTime = remember(trade.timestamp) { dateFormat.format(Date(trade.timestamp)) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1420)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(sideColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = trade.side.displayName.uppercase(),
                            color = sideColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${trade.baseAsset} / ${trade.quoteAsset}",
                        color = TvTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = formattedTime,
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Harga Eksekusi", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        PriceFormatter.formatPrice(trade.executionPrice, quoteAsset = trade.quoteAsset),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Jumlah", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        "${PriceFormatter.formatRawDecimal(trade.quantity)} ${trade.baseAsset}",
                        color = Color(0xFF72B7FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Total Rupiah", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        PriceFormatter.formatPrice(trade.totalIdr, quoteAsset = "IDR"),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            if (trade.pnlIdr != null && trade.pnlIdr != 0.0) {
                Spacer(Modifier.height(6.dp))
                val pnlColor = if (trade.pnlIdr >= 0) TvGreen else TvRed
                val pnlPrefix = if (trade.pnlIdr >= 0) "+" else ""
                Text(
                    text = "Realized PnL: $pnlPrefix${PriceFormatter.formatPrice(trade.pnlIdr, quoteAsset = "IDR")} (${pnlPrefix}${String.format(Locale.US, "%.2f", trade.pnlPercent ?: 0.0)}%)",
                    color = pnlColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EmptyHoldingsCard(
    onNavigateToSimulation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF09121C)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Savings,
                contentDescription = null,
                tint = TvTextSecondary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Belum Ada Koin yang Dimiliki",
                color = TvTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Gunakan saldo Rupiah yang tersedia untuk mulai membeli koin pada simulasi trading.",
                color = TvTextSecondary,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onNavigateToSimulation,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
            ) {
                Icon(Icons.AutoMirrored.Filled.CompareArrows, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Beli Koin Pertama", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF09121C)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = TvTextSecondary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Belum Ada Riwayat Transaksi",
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Setiap order beli atau jual yang berhasil dieksekusi akan tercatat otomatis di sini.",
                color = TvTextSecondary,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
