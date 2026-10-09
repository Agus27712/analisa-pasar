package agu.analys.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataSource
import agu.analys.model.*
import agu.analys.ui.components.SignalHistoryPanel
import agu.analys.ui.components.detail.*
import agu.analys.ui.components.settings.LogcatDiagnosticDialog
import agu.analys.ui.theme.*
import agu.analys.util.AppPreferences
import agu.analys.util.HapticUtil
import agu.analys.viewmodel.*

/**
 * REFACTOR NOTES (Lag Fix) — 2026-10-08 → DetailUiState migration
 *
 * Phase 1: derivedStateOf + pure helpers in composition.
 * Phase 2 (this): screen collects ONE [DetailUiState] from ViewModel.
 *   - ~25 collectAsStateWithLifecycle → 1 (+ optional mtf)
 *   - Price/position/balance math runs in ViewModel combine, not composition.
 *
 * Trading context:
 *  - Tokocrypto: USDT + IDR
 *  - Indodax: IDR only
 */

@Composable
fun DetailChartScreen(
    viewModel: TradingViewModel,
    onNavigateToDashboard: () -> Unit,
    onOpenLandscapeChart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ── 1. Single aggregated UI state ─────────────────────────────────────
    val ui by viewModel.detailUiState.collectAsStateWithLifecycle()

    // MTF still separate (not yet folded into DetailUiState)
    val mtfStateAll by viewModel.mtfState.collectAsStateWithLifecycle()
    val mtfState by remember(mtfStateAll, ui.pair.symbol) {
        derivedStateOf {
            DetailUiStateFactory.resolveMtfForPair(mtfStateAll, ui.pair)
        }
    }

    // Unpack for readability (stable references when ui identity changes)
    val pair = ui.pair
    val tick = ui.tick
    val candles = ui.candles
    val indicators = ui.indicators
    val signal = ui.signal
    val selectedTimeframe = ui.selectedTimeframe
    val marketDataSource = ui.marketDataSource
    val isScalping = ui.isScalping
    val strategyMode = ui.strategyMode
    val tradingFees = ui.tradingFees
    val isRealBuyMode = ui.isRealBuyMode
    val orderBookBids = ui.orderBookBids
    val orderBookAsks = ui.orderBookAsks
    val displayPrice = ui.displayPrice
    val change24h = ui.change24h
    val volume24h = ui.volume24h
    val activityText = ui.activityText
    val activityColor = when (activityText) {
        "Aktivitas tinggi" -> TvGreen
        "Aktivitas sedang" -> TvAmber
        else -> TvTextSecondary
    }
    val availableIdr = ui.availableIdr
    val availableCoin = ui.availableCoin
    val avgBuyPrice = ui.avgBuyPrice
    val isFavorite = ui.isFavorite
    val isConnected = ui.isConnected
    val priceAlerts = ui.priceAlerts
    val signalHistory = ui.signalHistory
    val currentPosition = ui.currentPosition
    val effectivePositionContext = ui.effectivePositionContext
    val effectiveDisplayPosition = ui.effectiveDisplayPosition
    val effectiveSellSignal = ui.effectiveSellSignal
    val effectiveWorkflow = ui.workflow
    val marketStructure = ui.marketStructure
        ?: agu.analys.engine.MarketStructureAnalyzer.analyze(emptyList())
    val isHolding = ui.isHolding

    // ── 2. Local UI-only state ────────────────────────────────────────────
    var isBuyMode by remember(pair.symbol) { mutableStateOf(!isHolding) }
    LaunchedEffect(isHolding) {
        // Sync tab when position appears/disappears for this pair
        if (isHolding) isBuyMode = false
    }

    var showPriceAlertDialog by remember { mutableStateOf(false) }
    var showAiAssistantDialog by remember { mutableStateOf(false) }
    var showLogcatDialog by remember { mutableStateOf(false) }

    var buyCooldownRemainingMs by remember { mutableLongStateOf(0L) }
    var buyCooldownTotalMs by remember { mutableLongStateOf(0L) }
    var buyCooldownReason by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(buyCooldownRemainingMs > 0) {
        if (buyCooldownRemainingMs > 0) {
            val stepMs = 35L
            while (buyCooldownRemainingMs > 0) {
                kotlinx.coroutines.delay(stepMs)
                buyCooldownRemainingMs = (buyCooldownRemainingMs - stepMs).coerceAtLeast(0L)
            }
            HapticUtil.vibrateTick(context)
        }
    }

    val provider = remember { AppPreferences(context).aiProvider }
    val scrollState = rememberScrollState()

    val latestDisplayPrice by rememberUpdatedState(displayPrice)
    val latestSignal by rememberUpdatedState(signal)
    val latestPair by rememberUpdatedState(pair)
    val latestIsReal by rememberUpdatedState(isRealBuyMode)
    val latestBids by rememberUpdatedState(orderBookBids)

    // ── 3. Side-effects ──────────────────────────────────────────────────
    LaunchedEffect(pair.symbol, marketDataSource) {
        if (pair.symbol.isNotBlank()) viewModel.selectPair(pair)
    }

    DisposableEffect(pair.baseAsset) {
        if (pair.baseAsset.isNotBlank()) {
            agu.analys.engine.global.GlobalContextManager.subscribeCoin(pair.baseAsset)
        }
        onDispose { agu.analys.engine.global.GlobalContextManager.stop() }
    }

    LaunchedEffect(isRealBuyMode, pair.symbol) {
        if (isRealBuyMode && viewModel.hasRealCredentialsConfigured()) {
            viewModel.fetchRealBalance(force = true)
        }
    }

    LaunchedEffect(effectivePositionContext.hasPosition, isRealBuyMode, pair.symbol) {
        if (effectivePositionContext.hasPosition && !currentPosition.isHolding) {
            val entryP = effectivePositionContext.entryPrice ?: 0.0
            val qty = effectivePositionContext.quantity ?: 0.0
            if (entryP > 0.0 && qty > 0.0 && pair.symbol.isNotBlank()) {
                viewModel.positionStore.markBought(
                    symbol = pair.symbol,
                    entryPrice = entryP,
                    quantity = qty,
                    invested = entryP * qty,
                    isReal = isRealBuyMode
                )
                viewModel.refreshSpotPosition()
            }
        }
    }

    // ── 4. Dialogs ───────────────────────────────────────────────────────
    if (showPriceAlertDialog) {
        PriceAlertDialog(
            symbol = pair.symbol,
            currentPrice = displayPrice,
            quoteAsset = pair.quoteAsset,
            alerts = priceAlerts,
            onAddAlert = { alert ->
                viewModel.addPriceAlert(alert)
                HapticUtil.vibrateTradeSuccess(context)
                android.widget.Toast.makeText(context, "Alert tersimpan!", android.widget.Toast.LENGTH_SHORT).show()
            },
            onRemoveAlert = { id ->
                viewModel.removePriceAlert(id)
                android.widget.Toast.makeText(context, "Alert dihapus", android.widget.Toast.LENGTH_SHORT).show()
            },
            onToggleAlert = { id -> viewModel.togglePriceAlert(id) },
            onDismiss = { showPriceAlertDialog = false }
        )
    }

    if (showAiAssistantDialog) {
        AiAssistantDialog(
            aiSignal = ui.aiReportText,
            isLoading = ui.isAiLoading,
            provider = provider,
            onDismiss = { showAiAssistantDialog = false },
            onAnalyze = {
                if (provider == AiProvider.GROQ) viewModel.requestDeepAiAudit()
                else viewModel.requestGeminiChartSummary()
            }
        )
    }

    // ── 5. UI Tree ───────────────────────────────────────────────────────
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
            .statusBarsPadding()
    ) {
        Surface(color = TvBackground, modifier = Modifier.fillMaxWidth()) {
            DetailTopBar(
                pair = pair,
                onNavigateToDashboard = onNavigateToDashboard,
                isConnected = isConnected,
                onOpenLogcat = { showLogcatDialog = true },
                strategyMode = strategyMode,
                currentPrice = displayPrice,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(TvCardBackground)
                    .border(1.dp, TvBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    DetailPriceHeader(
                        price = displayPrice,
                        change24h = change24h,
                        activityText = activityText,
                        activityColor = activityColor,
                        quoteAsset = pair.quoteAsset,
                        baseAsset = pair.baseAsset,
                        symbol = pair.symbol,
                        isFavorite = isFavorite
                    )

                    Spacer(Modifier.height(14.dp))

                    DetailControlsRow(
                        selectedTimeframe = selectedTimeframe,
                        onSelectTimeframe = { viewModel.selectTimeframe(it) },
                        priceAlerts = priceAlerts,
                        isFavorite = isFavorite,
                        onOpenAlerts = { showPriceAlertDialog = true },
                        onOpenPortfolio = { viewModel.openPortfolio() },
                        onOpenAiAssistant = { showAiAssistantDialog = true },
                        onOpenSimulation = { viewModel.openSimulation(pair) },
                        onOpenLearning = { viewModel.openLearning() },
                        onOpenSignalLogs = { viewModel.openSignalLogs() },
                        onToggleFavorite = {
                            viewModel.toggleFavorite(pair.symbol)
                            HapticUtil.vibrateTick(context)
                        }
                    )

                    Spacer(Modifier.height(14.dp))

                    DetailChartSection(
                        candles = candles,
                        tick = tick,
                        signal = signal,
                        pair = pair,
                        selectedTimeframe = selectedTimeframe,
                        onOpenLandscapeChart = onOpenLandscapeChart
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            WaitingEntryRadarCard(
                signal = signal,
                strategyMode = strategyMode,
                scalping = isScalping,
                fees = tradingFees,
                currentPrice = displayPrice,
                baseAsset = pair.baseAsset,
                quoteAsset = pair.quoteAsset,
                exchange = marketDataSource.name,
                availableIdr = availableIdr,
                availableCoin = availableCoin,
                avgBuyPrice = avgBuyPrice,
                isRealBuyMode = isRealBuyMode,
                isBuyMode = isBuyMode,
                onBuyModeChanged = { isBuyMode = it },
                orderBookBids = orderBookBids,
                orderBookAsks = orderBookAsks,
                buyCooldownRemainingMs = if (isRealBuyMode) 0L else buyCooldownRemainingMs,
                buyCooldownTotalMs = if (isRealBuyMode) 0L else buyCooldownTotalMs,
                buyCooldownReason = if (isRealBuyMode) null else buyCooldownReason,
                onExecuteBuy = { nominalIdr, customBuyPrice, tp1Price, tp2Price ->
                    val price = latestDisplayPrice
                    val execPrice = when {
                        customBuyPrice > 0.0 -> customBuyPrice
                        price > 0.0 -> price
                        else -> latestSignal.entryPrice
                    }
                    if (execPrice > 0) {
                        if (latestIsReal) {
                            viewModel.executeRealTrade(
                                latestPair.symbol, "buy", execPrice, nominalIdr, tp1Price, tp2Price
                            ) { success, msg ->
                                buyCooldownRemainingMs = 0L
                                buyCooldownTotalMs = 0L
                                buyCooldownReason = null
                                if (success) HapticUtil.vibrateTradeSuccess(context)
                                else HapticUtil.vibrateTradeFailure(context)
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val qty = nominalIdr / execPrice
                            val orderType = if (price > 0.0 && execPrice < price) {
                                agu.analys.trading.SimulationOrderType.LIMIT
                            } else {
                                agu.analys.trading.SimulationOrderType.MARKET
                            }
                            val res = viewModel.submitSimulationOrder(
                                side = agu.analys.trading.SimulationOrderSide.BUY,
                                type = orderType,
                                price = execPrice,
                                quantity = qty
                            )
                            val isSuccess = res is agu.analys.trading.SimulationOrderResult.Success
                            val msg = when (res) {
                                is agu.analys.trading.SimulationOrderResult.Success -> res.message
                                is agu.analys.trading.SimulationOrderResult.Error -> res.message
                            }
                            if (orderType == agu.analys.trading.SimulationOrderType.MARKET && isSuccess) {
                                viewModel.setOwnership(
                                    true, execPrice, quantity = qty, invested = nominalIdr, isReal = false
                                )
                            }
                            if (isSuccess) {
                                HapticUtil.vibrateTradeSuccess(context)
                                buyCooldownRemainingMs = 0L
                                android.widget.Toast.makeText(context, "Simulasi: $msg", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                HapticUtil.vibrateTradeFailure(context)
                                buyCooldownTotalMs = 3500L
                                buyCooldownRemainingMs = 3500L
                                buyCooldownReason = "Simulasi: $msg"
                            }
                        }
                    } else {
                        HapticUtil.vibrateTradeFailure(context)
                        if (latestIsReal) {
                            buyCooldownRemainingMs = 0L
                            buyCooldownTotalMs = 0L
                            buyCooldownReason = null
                            android.widget.Toast.makeText(
                                context,
                                "Harga belum tersedia dari pasar. Coba sesaat lagi.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            buyCooldownTotalMs = 3000L
                            buyCooldownRemainingMs = 3000L
                            buyCooldownReason = "Harga belum tersedia dari pasar. Menunggu tick baru..."
                        }
                    }
                },
                onExecuteSell = { sellQty, isAutoSell, tp1P, tp1Pct, tp2P, tp2Pct ->
                    val topBid = latestBids.firstOrNull { it.price > 0.0 }?.price
                    val execPrice = topBid
                        ?: latestDisplayPrice.takeIf { it > 0.0 }
                        ?: latestSignal.targetPrice1
                    if (execPrice > 0) {
                        viewModel.executeSellOrders(
                            pair = latestPair,
                            sellQty = sellQty,
                            marketPrice = execPrice,
                            isAutoTpEnabled = isAutoSell,
                            tp1Price = tp1P,
                            tp1Percent = tp1Pct,
                            tp2Price = tp2P,
                            tp2Percent = tp2Pct,
                            isRealMode = latestIsReal
                        ) { success, msg ->
                            if (success) HapticUtil.vibrateTradeSuccess(context)
                            else HapticUtil.vibrateTradeFailure(context)
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
                    } else {
                        HapticUtil.vibrateTradeFailure(context)
                        android.widget.Toast.makeText(
                            context,
                            "Harga belum tersedia dari pasar.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                spotPosition = currentPosition,
                sellSignalState = effectiveSellSignal,
                positionContext = effectivePositionContext,
                workflow = effectiveWorkflow,
                onSetTrailingStop = { enabled, pct ->
                    viewModel.setTrailingStop(pair.symbol, enabled, pct)
                    HapticUtil.vibrateTradeSuccess(context)
                    android.widget.Toast.makeText(
                        context,
                        if (enabled) "Trailing $pct% aktif" else "Trailing off",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                onSetTieredTrailingStop = { enabled, pct, isTiered, json ->
                    viewModel.setTrailingStop(pair.symbol, enabled, pct, isTiered, json)
                    HapticUtil.vibrateTradeSuccess(context)
                    android.widget.Toast.makeText(
                        context,
                        if (enabled) "Trailing $pct% ${if (isTiered) "(Smart Step Aktif)" else ""} disimpan"
                        else "Trailing off",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                onResetTrailingTrigger = { viewModel.resetTrailingTrigger() },
                onSetAutoSellParams = { enabled, tp1Price, tp1Percent, tp2Price, tp2Percent ->
                    viewModel.setAutoSellParams(enabled, tp1Price, tp1Percent, tp2Price, tp2Percent) { success, msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                    HapticUtil.vibrateTradeSuccess(context)
                },
                onDeployTrailingOrder = {
                    viewModel.deployTrailingOrder(pair.symbol)
                    HapticUtil.vibrateTradeSuccess(context)
                    android.widget.Toast.makeText(context, "Trailing Aktif!", android.widget.Toast.LENGTH_SHORT).show()
                },
                onCancelTrailingOrder = {
                    viewModel.cancelTrailingOrder(pair.symbol)
                    HapticUtil.vibrateTradeSuccess(context)
                    android.widget.Toast.makeText(context, "Trailing Dimatikan", android.widget.Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(Modifier.height(10.dp))

            MarketConditionCard(
                structure = marketStructure,
                indicators = indicators,
                signal = signal,
                strategyMode = strategyMode,
                scalping = isScalping,
                onRetry = { viewModel.refreshMtfForActiveSymbol() },
                mtfState = mtfState
            )

            Spacer(Modifier.height(8.dp))

            DetailTechnicalDetailsSection(
                indicators = indicators,
                structure = marketStructure,
                volume24h = volume24h,
                scalping = isScalping,
                signal = signal,
                price = displayPrice,
                quoteAsset = pair.quoteAsset
            )

            Spacer(Modifier.height(10.dp))

            SignalHistoryPanel(
                history = signalHistory,
                currentSymbol = pair.symbol,
                position = effectiveDisplayPosition,
                onOpenAllLogs = { viewModel.openSignalLogs() }
            )

            Spacer(Modifier.height(14.dp))

            DetailBottomActions(
                marketDataSource = marketDataSource,
                onOpenPortfolio = { viewModel.openPortfolio() },
                onOpenExchange = { openExchange(context, marketDataSource) }
            )

            Spacer(Modifier.height(16.dp))
        }

        if (showLogcatDialog) {
            LogcatDiagnosticDialog(onDismissRequest = { showLogcatDialog = false })
        }
    }
}
