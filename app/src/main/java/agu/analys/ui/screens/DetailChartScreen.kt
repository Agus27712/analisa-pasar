package agu.analys.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataSource
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.*
import agu.analys.ui.components.SimpleComposeChart
import agu.analys.ui.components.detail.*
import agu.analys.ui.theme.*
import agu.analys.util.AppPreferences
import agu.analys.util.HapticUtil
import agu.analys.viewmodel.*

@Composable
fun DetailChartScreen(
    viewModel: TradingViewModel,
    onNavigateToDashboard: () -> Unit,
    onOpenLandscapeChart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val marketDataSource by viewModel.marketDataSource.collectAsStateWithLifecycle()
    val pair by viewModel.selectedPair.collectAsStateWithLifecycle()
    val tick by viewModel.currentTick.collectAsStateWithLifecycle()
    val candles by viewModel.recentCandles.collectAsStateWithLifecycle()
    val indicators by viewModel.currentIndicators.collectAsStateWithLifecycle()
    val signal by viewModel.aiSignalState.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val isScalping by viewModel.isScalpingMode.collectAsStateWithLifecycle()
    val strategyMode by viewModel.strategyMode.collectAsStateWithLifecycle()
    val tradingFees by viewModel.tradingFees.collectAsStateWithLifecycle()
    val isRealBuyMode by viewModel.isRealBuyMode.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val spotPosition by viewModel.spotPosition.collectAsStateWithLifecycle()
    val sellSignalState by viewModel.sellSignalState.collectAsStateWithLifecycle()
    val positionContext by viewModel.positionContext.collectAsStateWithLifecycle()
    val tradingWorkflow by viewModel.tradingWorkflow.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val aiGroq by viewModel.auditReportText.collectAsStateWithLifecycle()
    val aiGemini by viewModel.geminiSummaryText.collectAsStateWithLifecycle()
    val aiLoadingGroq by viewModel.isAuditLoading.collectAsStateWithLifecycle()
    val aiLoadingGemini by viewModel.isGeminiLoading.collectAsStateWithLifecycle()
    val wallet by viewModel.simulationWallet.collectAsStateWithLifecycle()
    val realBalance by viewModel.realIndodaxBalance.collectAsStateWithLifecycle()
    val realAvgBuyPrices by viewModel.realAvgBuyPrices.collectAsStateWithLifecycle()
    val priceAlerts by viewModel.priceAlerts.collectAsStateWithLifecycle()
    val mtfStateAll by viewModel.mtfState.collectAsStateWithLifecycle()
    val mtfState = remember(mtfStateAll, pair.symbol) { mtfStateAll[pair.symbol] ?: emptyMap() }

    var showPriceAlertDialog by remember { mutableStateOf(false) }
    var showAiAssistantDialog by remember { mutableStateOf(false) }
    val marketStructure = remember(candles) { MarketStructureAnalyzer.analyze(candles) }
    val isFavorite = favorites.contains(pair.symbol.uppercase()) || favorites.contains(pair.symbol)
    val provider = remember { AppPreferences(context).aiProvider }
    val isConnected = connection is MarketConnectionState.Connected

    // Dialogs
    if (showPriceAlertDialog) {
        PriceAlertDialog(
            symbol = pair.symbol,
            currentPrice = tick?.price ?: 0.0,
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
        val aiSignalText = if (provider == AiProvider.GROQ) aiGroq ?: "" else aiGemini ?: ""

        AiAssistantDialog(
            aiSignal = aiSignalText,
            isLoading = isAiLoading,
            provider = provider,
            onDismiss = { showAiAssistantDialog = false },
            onAnalyze = {
                if (provider == AiProvider.GROQ) {
                    viewModel.requestDeepAiAudit()
                } else {
                    viewModel.requestGeminiChartSummary()
                }
            }
        )
    }

    // Market Activity Calculation
    val volume = tick?.volume24h ?: 0.0
    val change = tick?.change24h ?: 0.0
    val isUsdt = pair.quoteAsset.equals("USDT", true) || pair.quoteAsset.equals("USD", true)
    val activityText = remember(isUsdt, volume, change) {
        if (isUsdt) {
            when {
                volume >= 100_000_000.0 || change >= 3.0 -> "Aktivitas tinggi"
                volume >= 5_000_000.0 || change >= 0.0 -> "Aktivitas sedang"
                else -> "Aktivitas rendah"
            }
        } else {
            when {
                volume >= 50_000_000_000 || change >= 3.0 -> "Aktivitas tinggi"
                volume >= 1_000_000_000 || change >= 0.0 -> "Aktivitas sedang"
                else -> "Aktivitas rendah"
            }
        }
    }
    val activityColor = when (activityText) {
        "Aktivitas tinggi" -> TvGreen
        "Aktivitas sedang" -> TvAmber
        else -> TvTextSecondary
    }

    // Balances Calculation
    val availableIdr = if (isRealBuyMode) (realBalance["idr"] ?: 0.0) else wallet.getAvailableIdr()
    val availableCoin = if (isRealBuyMode) {
        realBalance[pair.baseAsset.lowercase()] ?: realBalance[pair.baseAsset.uppercase()] ?: 0.0
    } else wallet.getAvailableCoin(pair.baseAsset)
    val realApiAvg = realAvgBuyPrices[pair.baseAsset.lowercase()] ?: realAvgBuyPrices[pair.baseAsset.uppercase()] ?: 0.0
    val simApiAvg = wallet.avgBuyPrices[pair.baseAsset.uppercase()] ?: 0.0
    val avgBuyPrice = if (isRealBuyMode) {
        if (realApiAvg > 0.0) realApiAvg else spotPosition.entryPrice
    } else {
        if (simApiAvg > 0.0) simApiAvg else spotPosition.entryPrice
    }

    val scrollState = rememberScrollState()
    val isScrolled by remember { derivedStateOf { scrollState.value > 140 } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // 1. Top Bar
            DetailTopBar(
                pair = pair,
                onNavigateToDashboard = onNavigateToDashboard,
                isConnected = isConnected
            )

            Spacer(Modifier.height(8.dp))

            // 2. Header Harga Aset
            DetailPriceHeader(
                price = tick?.price ?: 0.0,
                change24h = change,
                activityText = activityText,
                activityColor = activityColor,
                quoteAsset = pair.quoteAsset
            )

            Spacer(Modifier.height(10.dp))

            // 3. Timeframe Chips + Quick Action Icons
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
                onToggleFavorite = {
                    viewModel.toggleFavorite(pair.symbol)
                    HapticUtil.vibrateTick(context)
                }
            )

            Spacer(Modifier.height(8.dp))

            // 4. Chart Preview & Landscape Launcher
            DetailChartSection(
                candles = candles,
                tick = tick,
                signal = signal,
                pair = pair,
                selectedTimeframe = selectedTimeframe,
                onOpenLandscapeChart = onOpenLandscapeChart
            )

            Spacer(Modifier.height(10.dp))

            // 5. Radar Card & Transaction Section
            WaitingEntryRadarCard(
                signal = signal,
                strategyMode = strategyMode,
                scalping = isScalping,
                fees = tradingFees,
                currentPrice = tick?.price ?: 0.0,
                baseAsset = pair.baseAsset,
                quoteAsset = pair.quoteAsset,
                availableIdr = availableIdr,
                availableCoin = availableCoin,
                avgBuyPrice = avgBuyPrice,
                isRealBuyMode = isRealBuyMode,
                onExecuteBuy = { nominalIdr, tp1Price, tp2Price ->
                    val execPrice = if (tick?.price != null && tick!!.price > 0) tick!!.price else signal.entryPrice
                    if (execPrice > 0) {
                        if (isRealBuyMode) {
                            viewModel.executeRealTrade(pair.symbol, "buy", execPrice.toLong(), nominalIdr, tp1Price, tp2Price) { success, msg ->
                                if (success) HapticUtil.vibrateTradeSuccess(context)
                                else HapticUtil.vibrateTradeFailure(context)
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val qty = nominalIdr / execPrice
                            val res = viewModel.submitSimulationOrder(
                                side = agu.analys.trading.SimulationOrderSide.BUY,
                                type = agu.analys.trading.SimulationOrderType.MARKET,
                                price = execPrice,
                                quantity = qty
                            )
                            val isSuccess = res is agu.analys.trading.SimulationOrderResult.Success
                            val msg = when (res) {
                                is agu.analys.trading.SimulationOrderResult.Success -> res.message
                                is agu.analys.trading.SimulationOrderResult.Error -> res.message
                            }
                            viewModel.setOwnership(true, execPrice, quantity = qty, invested = nominalIdr, isReal = false)
                            if (isSuccess) HapticUtil.vibrateTradeSuccess(context)
                            else HapticUtil.vibrateTradeFailure(context)
                            android.widget.Toast.makeText(context, "Simulasi: $msg", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        HapticUtil.vibrateTradeFailure(context)
                        android.widget.Toast.makeText(context, "Harga belum tersedia.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onExecuteSell = { sellQty ->
                    val execPrice = if (tick?.price != null && tick!!.price > 0) tick!!.price else signal.targetPrice1
                    if (execPrice > 0) {
                        if (isRealBuyMode) {
                            val bal = realBalance[pair.baseAsset.lowercase()] ?: realBalance[pair.baseAsset.uppercase()] ?: 0.0
                            if (bal > 0 && sellQty > 0) {
                                viewModel.executeRealTrade(pair.symbol, "sell", execPrice.toLong(), sellQty.coerceAtMost(bal)) { success, msg ->
                                    if (success) HapticUtil.vibrateTradeSuccess(context)
                                    else HapticUtil.vibrateTradeFailure(context)
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            } else {
                                HapticUtil.vibrateTradeFailure(context)
                                android.widget.Toast.makeText(context, "Saldo ${pair.baseAsset} kosong.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val bal = wallet.getAvailableCoin(pair.baseAsset)
                            if (bal > 0 && sellQty > 0) {
                                val res = viewModel.submitSimulationOrder(
                                    side = agu.analys.trading.SimulationOrderSide.SELL,
                                    type = agu.analys.trading.SimulationOrderType.MARKET,
                                    price = execPrice,
                                    quantity = sellQty.coerceAtMost(bal)
                                )
                                if (sellQty >= bal) viewModel.setOwnership(false, isReal = false)
                                val isSuccess = res is agu.analys.trading.SimulationOrderResult.Success
                                val msg = when (res) {
                                    is agu.analys.trading.SimulationOrderResult.Success -> res.message
                                    is agu.analys.trading.SimulationOrderResult.Error -> res.message
                                }
                                if (isSuccess) HapticUtil.vibrateTradeSuccess(context)
                                else HapticUtil.vibrateTradeFailure(context)
                                android.widget.Toast.makeText(context, "Simulasi: $msg", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                HapticUtil.vibrateTradeFailure(context)
                                android.widget.Toast.makeText(context, "Saldo simulasi kosong.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onSetManualBuyPrice = { entryPrice, investedAmount ->
                    viewModel.setManualPositionPrice(pair.symbol, entryPrice, investedAmount, isReal = isRealBuyMode)
                    HapticUtil.vibrateTradeSuccess(context)
                    android.widget.Toast.makeText(context, "Harga beli manual tersimpan!", android.widget.Toast.LENGTH_SHORT).show()
                },
                spotPosition = spotPosition,
                sellSignalState = sellSignalState,
                positionContext = positionContext,
                workflow = tradingWorkflow,
                onSetTrailingStop = { enabled, pct ->
                    viewModel.setTrailingStop(enabled, pct)
                    HapticUtil.vibrateTradeSuccess(context)
                    android.widget.Toast.makeText(
                        context,
                        if (enabled) "Trailing $pct% aktif" else "Trailing off",
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
                    android.widget.Toast.makeText(context, "Jaring Pengaman Aktif!", android.widget.Toast.LENGTH_SHORT).show()
                },
                onCancelTrailingOrder = {
                    viewModel.cancelTrailingOrder(pair.symbol)
                    HapticUtil.vibrateTradeSuccess(context)
                    android.widget.Toast.makeText(context, "Jaring Pengaman Dimatikan", android.widget.Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(Modifier.height(8.dp))

            // 6. Market Condition Card
            MarketConditionCard(
                structure = marketStructure,
                indicators = indicators,
                signal = signal,
                strategyMode = strategyMode,
                scalping = isScalping,
                onRetry = { viewModel.retryConnection() },
                mtfState = mtfState
            )

            Spacer(Modifier.height(8.dp))

            // 7. Important Levels Card
            ImportantLevelsCard(
                signal = signal,
                structure = marketStructure,
                price = tick?.price ?: 0.0,
                quoteAsset = pair.quoteAsset
            )

            Spacer(Modifier.height(8.dp))

            // 8. Technical Details Accordion
            DetailTechnicalDetailsSection(
                indicators = indicators,
                structure = marketStructure,
                volume24h = tick?.volume24h ?: 0.0,
                scalping = isScalping,
                signal = signal,
                price = tick?.price ?: 0.0,
                quoteAsset = pair.quoteAsset
            )

            Spacer(Modifier.height(8.dp))
            DisclaimerCard()
            Spacer(Modifier.height(12.dp))

            // 9. Bottom Actions
            DetailBottomActions(
                marketDataSource = marketDataSource,
                onOpenPortfolio = { viewModel.openPortfolio() },
                onOpenExchange = { openExchange(context, marketDataSource) }
            )

            Spacer(Modifier.height(16.dp))
        }

        // Floating Sticky Status Bar
        AnimatedVisibility(
            visible = isScrolled,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            StickyFloatingStatusBar(
                connection = connection,
                strategyMode = strategyMode,
                onRetry = { viewModel.retryConnection() }
            )
        }
    }
}

