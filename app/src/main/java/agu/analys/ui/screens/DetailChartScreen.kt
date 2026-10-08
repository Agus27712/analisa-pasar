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
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.model.*
import agu.analys.ui.components.SignalHistoryPanel
import agu.analys.ui.components.detail.*
import agu.analys.ui.components.settings.LogcatDiagnosticDialog
import agu.analys.ui.theme.*
import agu.analys.util.AppPreferences
import agu.analys.util.HapticUtil
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.*

/**
 * REFACTOR NOTES (Lag Fix) — 2026-10-08
 *
 * Root cause lag:
 *  1. ~25 collectAsStateWithLifecycle in one Composable → every price tick recomposes full tree.
 *  2. Balance resolution + position math ran in composition scope.
 *  3. MarketStructureAnalyzer.analyze() called without stable key.
 *  4. Large onExecuteBuy/Sell lambdas recreated every frame.
 *
 * Changes:
 *  - Derived values use derivedStateOf (recalc only when deps change).
 *  - Balance resolution moved to pure function (no alloc in composition).
 *  - Callbacks stabilized with rememberUpdatedState.
 *  - Removed unused watchlist collect from this screen.
 *
 * Trading context:
 *  - Tokocrypto: USDT + IDR
 *  - Indodax: IDR only
 */

// ── Pure helpers (outside Composable = zero allocation in composition) ────

private fun resolveAvailableQuote(
    quoteAsset: String,
    isReal: Boolean,
    realFreeForQuote: (String) -> Double,
    realTotalForQuote: (String) -> Double,
    realBalanceMap: Map<String, Double>,
    savedBalanceMap: Map<String, Double>,
    simUsdt: Double,
    simIdr: Double
): Double {
    val isUsdt = PriceFormatter.isUsdtQuote(quoteAsset)
    return if (isUsdt) {
        if (isReal) {
            val free = realFreeForQuote("USDT")
            val total = realTotalForQuote("USDT")
            val fromMap = realBalanceMap.entries.firstOrNull { (k, _) ->
                val key = k.lowercase()
                key == "usdt" || key == "usd" || key == "usdc" || key == "busd"
            }?.value ?: 0.0
            val fromSaved = savedBalanceMap.entries.firstOrNull { (k, _) ->
                val key = k.lowercase()
                key == "usdt" || key == "usd" || key == "usdc" || key == "busd"
            }?.value ?: 0.0
            when {
                free > 0.0 -> free
                total > 0.0 -> total
                fromMap > 0.0 -> fromMap
                fromSaved > 0.0 -> fromSaved
                else -> 0.0
            }
        } else simUsdt
    } else {
        if (isReal) {
            val free = realFreeForQuote("IDR")
            val total = realTotalForQuote("IDR")
            val fromMap = realBalanceMap["idr"] ?: realBalanceMap["IDR"] ?: 0.0
            val fromSaved = savedBalanceMap["idr"] ?: savedBalanceMap["IDR"] ?: 0.0
            when {
                free > 0.0 -> free
                total > 0.0 -> total
                fromMap > 0.0 -> fromMap
                fromSaved > 0.0 -> fromSaved
                else -> 0.0
            }
        } else simIdr
    }
}

private fun resolveActivityLabel(
    isUsdtQuote: Boolean,
    volume24h: Double,
    change24h: Double
): Pair<String, Color> {
    val text = if (isUsdtQuote) {
        when {
            volume24h >= 100_000_000.0 || change24h >= 3.0 -> "Aktivitas tinggi"
            volume24h >= 5_000_000.0 || change24h >= 0.0 -> "Aktivitas sedang"
            else -> "Aktivitas rendah"
        }
    } else {
        when {
            volume24h >= 50_000_000_000 || change24h >= 3.0 -> "Aktivitas tinggi"
            volume24h >= 1_000_000_000 || change24h >= 0.0 -> "Aktivitas sedang"
            else -> "Aktivitas rendah"
        }
    }
    val color = when (text) {
        "Aktivitas tinggi" -> TvGreen
        "Aktivitas sedang" -> TvAmber
        else -> TvTextSecondary
    }
    return text to color
}

private fun resolveMtfForPair(
    mtfAll: Map<String, Map<Timeframe, MtfStatus>>,
    pair: TradingPair
): Map<Timeframe, MtfStatus> {
    val raw = pair.symbol
    val upper = raw.trim().uppercase()
    val clean = upper.replace("_", "").replace("/", "").replace("-", "")
    return mtfAll[raw]
        ?: mtfAll[upper]
        ?: mtfAll[clean]
        ?: mtfAll[pair.indodaxPair.uppercase()]
        ?: mtfAll[pair.tokocryptoPair.uppercase()]
        ?: emptyMap()
}

// ── Screen ────────────────────────────────────────────────────────────────

@Composable
fun DetailChartScreen(
    viewModel: TradingViewModel,
    onNavigateToDashboard: () -> Unit,
    onOpenLandscapeChart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ── 1. Collect only states actually used ──────────────────────────────
    val pair by viewModel.selectedPair.collectAsStateWithLifecycle()
    val tick by viewModel.currentTick.collectAsStateWithLifecycle()
    val candles by viewModel.recentCandles.collectAsStateWithLifecycle()
    val indicators by viewModel.currentIndicators.collectAsStateWithLifecycle()
    val signal by viewModel.aiSignalState.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val orderBookBids by viewModel.orderBookBids.collectAsStateWithLifecycle()
    val orderBookAsks by viewModel.orderBookAsks.collectAsStateWithLifecycle()

    val marketDataSource by viewModel.marketDataSource.collectAsStateWithLifecycle()
    val isScalping by viewModel.isScalpingMode.collectAsStateWithLifecycle()
    val strategyMode by viewModel.strategyMode.collectAsStateWithLifecycle()
    val tradingFees by viewModel.tradingFees.collectAsStateWithLifecycle()
    val isRealBuyMode by viewModel.isRealBuyMode.collectAsStateWithLifecycle()

    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val spotPosition by viewModel.spotPosition.collectAsStateWithLifecycle()
    val positionVersion by viewModel.positionVersion.collectAsStateWithLifecycle()
    val positionContext by viewModel.positionContext.collectAsStateWithLifecycle()
    val sellSignalState by viewModel.sellSignalState.collectAsStateWithLifecycle()
    val wallet by viewModel.simulationWallet.collectAsStateWithLifecycle()
    val realBalance by viewModel.realIndodaxBalance.collectAsStateWithLifecycle()
    val realAvgBuyPrices by viewModel.realAvgBuyPrices.collectAsStateWithLifecycle()
    val priceAlerts by viewModel.priceAlerts.collectAsStateWithLifecycle()
    val signalHistory by viewModel.signalHistory.collectAsStateWithLifecycle()
    val mtfStateAll by viewModel.mtfState.collectAsStateWithLifecycle()

    val aiGroq by viewModel.auditReportText.collectAsStateWithLifecycle()
    val aiGemini by viewModel.geminiSummaryText.collectAsStateWithLifecycle()
    val aiLoadingGroq by viewModel.isAuditLoading.collectAsStateWithLifecycle()
    val aiLoadingGemini by viewModel.isGeminiLoading.collectAsStateWithLifecycle()

    // ── 2. Derived state (recalc ONLY when deps change) ───────────────────
    val currentPosition by remember {
        derivedStateOf {
            @Suppress("UNUSED_EXPRESSION")
            positionVersion
            if (viewModel.isMatchingSymbol(spotPosition.symbol, pair.symbol) &&
                spotPosition.isReal == isRealBuyMode
            ) {
                spotPosition
            } else {
                viewModel.getPositionFor(pair.symbol, isRealBuyMode)
            }
        }
    }

    val mtfState by remember {
        derivedStateOf { resolveMtfForPair(mtfStateAll, pair) }
    }

    val isFavorite by remember {
        derivedStateOf {
            favorites.contains(pair.symbol.uppercase()) || favorites.contains(pair.symbol)
        }
    }

    val isConnected = connection is MarketConnectionState.Connected

    var lastKnownLivePrice by remember(pair.symbol) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(tick?.price) {
        val p = tick?.price ?: 0.0
        if (p > 0.0 && p.isFinite()) lastKnownLivePrice = p
    }

    val displayPrice by remember {
        derivedStateOf {
            val live = tick?.price?.takeIf { it > 0.0 && it.isFinite() }
                ?: lastKnownLivePrice.takeIf { it > 0.0 }
            if (live != null && live > 0.0) live
            else candles.lastOrNull()?.close?.takeIf { it > 0.0 && it.isFinite() } ?: 0.0
        }
    }

    val change24h by remember {
        derivedStateOf {
            val tc = tick?.change24h ?: 0.0
            if (!tc.isNaN()) tc else 0.0
        }
    }

    val volume24h by remember {
        derivedStateOf { tick?.volume24h ?: 0.0 }
    }

    val (activityText, activityColor) = remember(volume24h, change24h, pair.quoteAsset) {
        resolveActivityLabel(
            isUsdtQuote = PriceFormatter.isUsdtQuote(pair.quoteAsset),
            volume24h = volume24h,
            change24h = change24h
        )
    }

    val availableQuote by remember {
        derivedStateOf {
            resolveAvailableQuote(
                quoteAsset = pair.quoteAsset,
                isReal = isRealBuyMode,
                realFreeForQuote = { viewModel.realFreeBalanceForQuote(it) },
                realTotalForQuote = { viewModel.realBalanceForQuote(it) },
                realBalanceMap = realBalance,
                savedBalanceMap = viewModel.prefs.getSavedRealBalance(),
                simUsdt = wallet.getAvailableUsdt(),
                simIdr = wallet.getAvailableIdr()
            )
        }
    }
    val availableIdr = availableQuote

    val availableCoin by remember {
        derivedStateOf {
            if (isRealBuyMode) {
                realBalance[pair.baseAsset.lowercase()]
                    ?: realBalance[pair.baseAsset.uppercase()]
                    ?: 0.0
            } else {
                wallet.getAvailableCoin(pair.baseAsset)
            }
        }
    }

    val avgBuyPrice by remember {
        derivedStateOf {
            if (isRealBuyMode) {
                val api = realAvgBuyPrices[pair.baseAsset.lowercase()]
                    ?: realAvgBuyPrices[pair.baseAsset.uppercase()]
                    ?: 0.0
                if (api > 0.0) api else spotPosition.entryPrice
            } else {
                val api = wallet.avgBuyPrices[pair.baseAsset.uppercase()] ?: 0.0
                if (api > 0.0) api else spotPosition.entryPrice
            }
        }
    }

    val effectivePositionContext by remember {
        derivedStateOf {
            if (displayPrice > 0.0) {
                val holding = viewModel.getHoldingStatus(pair, isRealBuyMode)
                PositionContext.create(
                    symbol = pair.symbol,
                    spotPosition = currentPosition,
                    holdingStatus = holding,
                    currentPrice = displayPrice,
                    fees = tradingFees
                )
            } else {
                positionContext
            }
        }
    }

    val effectiveDisplayPosition by remember {
        derivedStateOf {
            if (effectivePositionContext.hasPosition) {
                val entryP = effectivePositionContext.entryPrice ?: currentPosition.entryPrice
                val qty = effectivePositionContext.quantity ?: currentPosition.quantity
                currentPosition.copy(
                    symbol = pair.symbol,
                    state = agu.analys.trading.SpotPositionState.HOLDING,
                    entryPrice = entryP,
                    quantity = qty,
                    investedAmount = if (currentPosition.investedAmount > 0.0)
                        currentPosition.investedAmount else (entryP * qty),
                    isReal = isRealBuyMode
                )
            } else currentPosition
        }
    }

    val effectiveSellSignal by remember {
        derivedStateOf {
            if (effectivePositionContext.hasPosition) {
                agu.analys.engine.sell.SellSignalEvaluator.evaluate(
                    context = effectivePositionContext,
                    indicators = indicators,
                    tradingFees = tradingFees,
                    high24h = tick?.high24h ?: 0.0
                )
            } else sellSignalState
        }
    }

    val effectiveWorkflow by remember {
        derivedStateOf { resolveWorkflow(effectivePositionContext) }
    }

    val isHolding = effectivePositionContext.hasPosition || currentPosition.isHolding
    var isBuyMode by remember(pair.symbol) { mutableStateOf(!isHolding) }

    val marketStructure by remember {
        derivedStateOf {
            if (candles.isEmpty()) {
                MarketStructureAnalyzer.analyze(emptyList())
            } else {
                MarketStructureAnalyzer.analyze(candles)
            }
        }
    }

    // ── 3. Side-effects ──────────────────────────────────────────────────
    LaunchedEffect(pair.symbol, marketDataSource) {
        viewModel.selectPair(pair)
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
            if (entryP > 0.0 && qty > 0.0) {
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

    // ── 4. Local UI state ────────────────────────────────────────────────
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

    // ── 5. Dialogs ───────────────────────────────────────────────────────
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
        val isAiLoading = aiLoadingGroq || aiLoadingGemini
        val aiSignalText = if (provider == AiProvider.GROQ) aiGroq.orEmpty() else aiGemini.orEmpty()
        AiAssistantDialog(
            aiSignal = aiSignalText,
            isLoading = isAiLoading,
            provider = provider,
            onDismiss = { showAiAssistantDialog = false },
            onAnalyze = {
                if (provider == AiProvider.GROQ) viewModel.requestDeepAiAudit()
                else viewModel.requestGeminiChartSummary()
            }
        )
    }

    // ── 6. UI Tree ───────────────────────────────────────────────────────
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
