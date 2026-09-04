package agu.analys.ui.components.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import agu.analys.model.BatchExecutionState
import agu.analys.model.BatchResultSummary
import agu.analys.model.CandleBar
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.MarketTick
import agu.analys.model.ReadySellCoinSummary
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun ProactiveProfitSummaryCard(
    allPairs: List<TradingPair>,
    allTicks: Map<String, MarketTick>,
    worthBySymbol: Map<String, WorthCoinInfo>,
    recentCandles: List<CandleBar>,
    usdtIdrRate: Double,
    isRealTradingMode: Boolean,
    batchExecutionState: BatchExecutionState,
    hasSecurityPin: Boolean,
    holdingStatuses: Map<String, CoinHoldingStatus>,
    tradingFees: agu.analys.config.TradingFeeConfig = agu.analys.config.TradingFeeConfig(),
    onCoinClick: (TradingPair) -> Unit,
    onExecuteBatchSell: (List<ReadySellCoinSummary>, Boolean, String?) -> Unit,
    onResetBatchState: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLight = LocalAppColors.current == LightAppColors
    val colorOrange = TvOrange
    val colorRed = TvRed
    val colorGreen = TvGreen

    // Collect all coins with active Ready Sell status
    val readyCoins = remember(allPairs, allTicks, worthBySymbol, holdingStatuses, usdtIdrRate, tradingFees, isRealTradingMode, colorOrange, colorGreen, colorRed) {
        allPairs.mapNotNull { pair ->
            val holding = holdingStatuses[pair.symbol] ?: return@mapNotNull null
            if (!holding.isHolding || holding.quantity <= 0.00000001) return@mapNotNull null
            if (holding.isReal != isRealTradingMode) return@mapNotNull null

            val tick = allTicks[pair.symbol]
            val currentPrice = tick?.price ?: 0.0
            if (currentPrice <= 0.0) return@mapNotNull null

            val badge = ReadySellBadgeEvaluator.computeReadyBadge(
                holding = holding,
                tick = tick,
                tradingFees = tradingFees,
                colorOrange = colorOrange,
                colorGreen = colorGreen,
                colorRed = colorRed,
                rsi = null
            ) ?: return@mapNotNull null

            if (!badge.isExitDecisionEvent) return@mapNotNull null

            val entryPrice = holding.entryPrice
            val hasCostBasis = entryPrice > 0.0
            val sellFeeRate = (tradingFees.sellMakerPct / 100.0).coerceAtLeast(0.0)
            val grossSell = holding.quantity * currentPrice
            val netSell = grossSell * (1.0 - sellFeeRate)
            val costBasis = if (hasCostBasis) holding.quantity * entryPrice else 0.0
            val netProfitIdrLocal = if (hasCostBasis) netSell - costBasis else 0.0
            val netProfitPct = if (hasCostBasis && costBasis > 0.0) (netProfitIdrLocal / costBasis) * 100.0 else 0.0

            val rate = if (pair.quoteAsset.equals("USDT", true) || pair.quoteAsset.equals("USD", true)) usdtIdrRate else 1.0
            val cashOutValueIdr = netSell * rate
            val costIdr = costBasis * rate
            val profitIdr = cashOutValueIdr - costIdr

            ReadySellCoinSummary(
                pair = pair,
                quantity = holding.quantity,
                entryPrice = entryPrice,
                currentPrice = currentPrice,
                profitPct = netProfitPct,
                profitIdr = profitIdr,
                cashOutValueIdr = cashOutValueIdr,
                badgeLabel = badge.label,
                badgeColor = badge.color,
                isReal = holding.isReal
            )
        }
    }

    var showConfirmDialog by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = readyCoins.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val totalCashOutValueIdr = readyCoins.sumOf { it.cashOutValueIdr }
        val totalUnrealizedGainsIdr = readyCoins.sumOf { it.profitIdr }
        val totalCostIdr = totalCashOutValueIdr - totalUnrealizedGainsIdr
        val totalGainPct = if (totalCostIdr > 0.0) (totalUnrealizedGainsIdr / totalCostIdr) * 100.0 else 0.0

        val pulseTransition = rememberInfiniteTransition(label = "profit_summary_pulse")
        val pulseAlpha by pulseTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "summary_pulse_alpha"
        )

        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("proactive_profit_summary_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isLight) Color(0xFFE6F4EA) else Color(0xFF13231B) // Light mint canvas in Light mode, deep emerald in Dark mode
            ),
            border = BorderStroke(
                1.5.dp,
                Brush.horizontalGradient(
                    colors = listOf(
                        TvGreen.copy(alpha = pulseAlpha),
                        TvAmber.copy(alpha = pulseAlpha * 0.8f),
                        TvGreen.copy(alpha = pulseAlpha * 0.5f)
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Top Row: Title + Count Badge + Active Mode Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(TvGreen.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MonetizationOn,
                                contentDescription = null,
                                tint = TvGreen,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Proactive Profit Summary",
                                color = TvTextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.2.sp
                            )
                            Text(
                                text = "${readyCoins.size} Koin Siap Amankan Keuntungan",
                                color = TvTextSecondary,
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Trading Mode indicator badge
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isRealTradingMode) TvOrange.copy(alpha = 0.25f) else TvBlue.copy(alpha = 0.25f),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (isRealTradingMode) "REAL" else "SIM",
                                color = if (isRealTradingMode) TvOrange else TvBlue,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(
                                    TvGreen.copy(alpha = 0.2f),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "🎯 READY SELL",
                                color = TvGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Metric Cards: Total Unrealized Gains + Total Cash-Out Value
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Total Unrealized Profit Box
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (isLight) Color(0xFFFFFFFF) else Color(0xFF0A1811), RoundedCornerShape(12.dp))
                            .border(0.8.dp, TvGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                            .testTag("total_unrealized_profit_box")
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.TrendingUp,
                                    contentDescription = null,
                                    tint = TvGreen,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "PROFIT BELUM REALISASI",
                                    color = TvTextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = PriceFormatter.formatPrice(totalUnrealizedGainsIdr, showSymbol = true, quoteAsset = "IDR"),
                                color = TvGreen,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "${if (totalGainPct >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", totalGainPct)}% Akumulasi",
                                color = if (totalGainPct >= 0) TvGreen else TvRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Total Potential Cash-Out Value Box
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (isLight) Color(0xFFFFFFFF) else Color(0xFF0A1811), RoundedCornerShape(12.dp))
                            .border(0.8.dp, TvAmber.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                            .testTag("total_cashout_value_box")
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = TvAmber,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "POTENSI CASH-OUT KAS",
                                    color = TvTextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = PriceFormatter.formatPrice(totalCashOutValueIdr, showSymbol = true, quoteAsset = "IDR"),
                                color = TvAmber,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Estimasi Nilai Likuidasi",
                                color = TvTextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Action Bar: "Sell All Ready Assets" Button
                Button(
                    onClick = { showConfirmDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("sell_all_ready_assets_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRealTradingMode) Color(0xFFC0392B) else TvGreen,
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Jual Semua Aset Siap (${readyCoins.size})",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.2.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Black.copy(alpha = 0.25f)
                        ) {
                            Text(
                                text = if (isRealTradingMode) "INDODAX REAL" else "SIMULASI",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Horizontal scroll list of ready-to-sell coin chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    readyCoins.forEach { coin ->
                        ReadySellChip(
                            coin = coin,
                            onClick = { onCoinClick(coin.pair) }
                        )
                    }
                }
            }
        }
    }

    // Modal Confirmation Dialog for Batch Sell
    if (showConfirmDialog) {
        BatchSellConfirmationDialog(
            readyCoins = readyCoins,
            initialRealMode = isRealTradingMode,
            hasSecurityPin = hasSecurityPin,
            onDismiss = { showConfirmDialog = false },
            onConfirm = { modeIsReal, pin ->
                showConfirmDialog = false
                onExecuteBatchSell(readyCoins, modeIsReal, pin)
            }
        )
    }

    // Progress Modal Dialog
    if (batchExecutionState is BatchExecutionState.InProgress) {
        BatchSellProgressDialog(state = batchExecutionState)
    }

    // Completion Results Dialog
    if (batchExecutionState is BatchExecutionState.Completed) {
        BatchSellResultDialog(
            summary = batchExecutionState.summary,
            onDismiss = onResetBatchState
        )
    }

    // Error Alert Dialog
    if (batchExecutionState is BatchExecutionState.Error) {
        AlertDialog(
            onDismissRequest = onResetBatchState,
            title = {
                Text(
                    text = "Gagal Eksekusi Batch Sell",
                    color = TvRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = batchExecutionState.message,
                    color = TvTextPrimary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = onResetBatchState,
                    colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                ) {
                    Text("OK", color = Color.White)
                }
            },
            containerColor = Color(0xFF1B242C),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun ReadySellChip(
    coin: ReadySellCoinSummary,
    onClick: () -> Unit
) {
    val isLight = LocalAppColors.current == LightAppColors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isLight) Color(0xFFD1FAE5) else Color(0xFF1B2C22),
        border = BorderStroke(1.dp, coin.badgeColor.copy(alpha = if (isLight) 0.65f else 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssetAvatar(baseAsset = coin.pair.baseAsset, iconUrl = coin.pair.iconUrl, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = coin.pair.baseAsset,
                        color = TvTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${if (coin.profitPct >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", coin.profitPct)}%",
                        color = if (coin.profitPct >= 0) TvGreen else TvRed,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = coin.badgeLabel,
                    color = coin.badgeColor,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(Modifier.width(5.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TvTextSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun BatchSellConfirmationDialog(
    readyCoins: List<ReadySellCoinSummary>,
    initialRealMode: Boolean,
    hasSecurityPin: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (isReal: Boolean, pin: String?) -> Unit
) {
    val isLight = LocalAppColors.current == LightAppColors
    var isRealMode by remember { mutableStateOf(initialRealMode) }
    var pinText by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    val totalCashOut = readyCoins.sumOf { it.cashOutValueIdr }
    val totalProfit = readyCoins.sumOf { it.profitIdr }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = if (isLight) TvSurface else Color(0xFF141C24)),
            border = BorderStroke(1.dp, TvBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (isRealMode) TvOrange.copy(alpha = 0.2f) else TvGreen.copy(alpha = 0.2f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlashOn,
                                contentDescription = null,
                                tint = if (isRealMode) TvOrange else TvGreen,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Konfirmasi Batch Sell",
                                color = TvTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Eksekusi jual ${readyCoins.size} aset serentak",
                                color = TvTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Aggregated Summary Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isLight) Color(0xFFECFDF5) else Color(0xFF0F1A15), RoundedCornerShape(12.dp))
                        .border(1.dp, TvGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "ESTIMASI KAS DICAIRKAN",
                                color = TvTextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = PriceFormatter.formatPrice(totalCashOut, showSymbol = true, quoteAsset = "IDR"),
                                color = TvAmber,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "TOTAL ESTIMASI PROFIT",
                                color = TvTextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = PriceFormatter.formatPrice(totalProfit, showSymbol = true, quoteAsset = "IDR"),
                                color = TvGreen,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "DAFTAR KOIN YANG AKAN DIJUAL:",
                    color = TvTextSecondary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(6.dp))

                // List of coins in dialog
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    readyCoins.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isLight) TvSurfaceVariant else Color(0xFF0B1117),
                            border = BorderStroke(0.6.dp, TvBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AssetAvatar(baseAsset = item.pair.baseAsset, iconUrl = item.pair.iconUrl, size = 20.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = item.pair.baseAsset,
                                            color = TvTextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${String.format(java.util.Locale.US, "%.4f", item.quantity)} @ ${PriceFormatter.formatPrice(item.currentPrice, showSymbol = false, quoteAsset = item.pair.quoteAsset)}",
                                            color = TvTextSecondary,
                                            fontSize = 9.5.sp
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${if (item.profitPct >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", item.profitPct)}%",
                                        color = if (item.profitPct >= 0) TvGreen else TvRed,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = item.badgeLabel,
                                        color = item.badgeColor,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // If Real Mode and PIN is required
                if (isRealMode && hasSecurityPin) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = {
                            if (it.length <= 6) {
                                pinText = it
                                pinError = null
                            }
                        },
                        label = { Text("PIN Keamanan (6 Digit)", fontSize = 11.sp) },
                        placeholder = { Text("Masukkan PIN", fontSize = 11.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = pinError != null,
                        supportingText = pinError?.let { { Text(it, color = TvRed, fontSize = 10.sp) } },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("batch_sell_pin_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvOrange,
                            unfocusedBorderColor = TvBorder,
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, TvBorder)
                    ) {
                        Text("Batal", color = TvTextSecondary, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            if (isRealMode && hasSecurityPin && pinText.length < 6) {
                                pinError = "PIN minimal 6 digit"
                                return@Button
                            }
                            onConfirm(isRealMode, if (hasSecurityPin) pinText else null)
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(42.dp)
                            .testTag("confirm_batch_sell_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRealMode) Color(0xFFC0392B) else TvGreen
                        )
                    ) {
                        Text(
                            text = if (isRealMode) "⚡ Jual di INDODAX" else "🚀 Eksekusi Simulasi",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchSellProgressDialog(
    state: BatchExecutionState.InProgress
) {
    val isLight = LocalAppColors.current == LightAppColors
    Dialog(
        onDismissRequest = { /* Non-cancelable during execution */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = if (isLight) TvSurface else Color(0xFF131E27)),
            border = BorderStroke(1.dp, TvBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(42.dp),
                    color = TvGreen,
                    strokeWidth = 3.5.dp
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Mengeksekusi Batch Sell...",
                    color = TvTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = state.message,
                    color = TvTextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )

                Spacer(Modifier.height(14.dp))

                val progress = if (state.totalItems > 0) state.currentIndex.toFloat() / state.totalItems.toFloat() else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = TvGreen,
                    trackColor = Color(0xFF091219)
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Berhasil: ${state.successCount}",
                        color = TvGreen,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Gagal: ${state.failedCount}",
                        color = if (state.failedCount > 0) TvRed else TvTextSecondary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchSellResultDialog(
    summary: BatchResultSummary,
    onDismiss: () -> Unit
) {
    val isLight = LocalAppColors.current == LightAppColors
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000L)
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = if (isLight) TvSurface else Color(0xFF141D26)),
            border = BorderStroke(1.dp, TvBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (summary.failedCount == 0) TvGreen.copy(alpha = 0.2f) else TvOrange.copy(alpha = 0.2f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (summary.failedCount == 0) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (summary.failedCount == 0) TvGreen else TvOrange,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Hasil Batch Sell",
                                color = TvTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Mode: ${if (summary.isRealMode) "INDODAX Real Order" else "Simulasi Akun"}",
                                color = if (summary.isRealMode) TvOrange else TvBlue,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Stats Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isLight) Color(0xFFECFDF5) else Color(0xFF0F1A16), RoundedCornerShape(12.dp))
                        .border(1.dp, TvGreen.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Status Eksekusi:",
                                color = TvTextSecondary,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${summary.successCount} Berhasil / ${summary.totalItems} Total",
                                color = if (summary.successCount == summary.totalItems) TvGreen else TvOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Kas Diperoleh (Cair):",
                                color = TvTextSecondary,
                                fontSize = 11.sp
                            )
                            Text(
                                text = PriceFormatter.formatPrice(summary.totalCashOutIdr, showSymbol = true, quoteAsset = "IDR"),
                                color = TvAmber,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Total Realized Profit:",
                                color = TvTextSecondary,
                                fontSize = 11.sp
                            )
                            Text(
                                text = PriceFormatter.formatPrice(summary.totalEstimatedProfitIdr, showSymbol = true, quoteAsset = "IDR"),
                                color = TvGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "RINCIAN PER KOIN:",
                    color = TvTextSecondary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    summary.itemResults.forEach { res ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isLight) TvSurfaceVariant else Color(0xFF0C1319),
                            border = BorderStroke(
                                0.6.dp,
                                if (res.success) TvGreen.copy(alpha = 0.4f) else TvRed.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (res.success) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                        contentDescription = null,
                                        tint = if (res.success) TvGreen else TvRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = res.baseAsset,
                                            color = TvTextPrimary,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = res.message,
                                            color = TvTextSecondary,
                                            fontSize = 9.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                                if (res.success) {
                                    Text(
                                        text = "+${PriceFormatter.formatPrice(res.profitIdr, showSymbol = true, quoteAsset = "IDR")}",
                                        color = TvGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                ) {
                    Text("Selesai & Tutup", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
