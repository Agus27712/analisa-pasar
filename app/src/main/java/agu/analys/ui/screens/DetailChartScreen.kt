package agu.analys.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.config.StrategyMode
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.model.*
import agu.analys.ui.components.detail.*
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.AppPreferences
import agu.analys.viewmodel.TradingViewModel

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
    val spotPosition by viewModel.spotPosition.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val aiGroq by viewModel.auditReportText.collectAsStateWithLifecycle()
    val aiGemini by viewModel.geminiSummaryText.collectAsStateWithLifecycle()
    val aiLoadingGroq by viewModel.isAuditLoading.collectAsStateWithLifecycle()
    val aiLoadingGemini by viewModel.isGeminiLoading.collectAsStateWithLifecycle()
    val wallet by viewModel.simulationWallet.collectAsStateWithLifecycle()
    val realBalance by viewModel.realIndodaxBalance.collectAsStateWithLifecycle()
    val realAvgBuyPrices by viewModel.realAvgBuyPrices.collectAsStateWithLifecycle()
    val priceAlerts by viewModel.priceAlerts.collectAsStateWithLifecycle()

    var showPriceAlertDialog by remember { mutableStateOf(false) }
    val marketStructure = remember(candles) { MarketStructureAnalyzer.analyze(candles) }
    var chartVisible by remember { mutableStateOf(false) }
    val isFavorite = watchlist.contains(pair.symbol)
    val provider = remember { AppPreferences(context).aiProvider }
    val live = connection is MarketConnectionState.Connected

    val volume = tick?.volume24h ?: 0.0
    val change = tick?.change24h ?: 0.0
    val isUsdt = pair.quoteAsset.equals("USDT", true) || pair.quoteAsset.equals("USD", true)
    val activityText = if (isUsdt) {
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
    val activityColor = when (activityText) {
        "Aktivitas tinggi" -> TvGreen
        "Aktivitas sedang" -> Color(0xFFFFB300)
        else -> Color(0xFF78909C)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // ===== STICKY: status koneksi tidak ikut scroll =====
        StickyConnectionBar(
            connection = connection,
            strategyMode = strategyMode,
            onRetry = { viewModel.retryConnection() }
        )

        // ===== Konten scroll (compact padding untuk layar kecil) =====
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            DetailTopBar(
                pair = pair,
                isFavorite = isFavorite,
                onNavigateToDashboard = onNavigateToDashboard,
                onOpenSimulation = { viewModel.openSimulation(pair) },
                onOpenLearning = { viewModel.openLearning() },
                onToggleWatchlist = { viewModel.toggleWatchlist(pair.symbol) },
                onOpenLandscapeChart = onOpenLandscapeChart,
                activeAlertCount = priceAlerts.count { it.isEnabled && !it.isTriggered },
                onOpenAlerts = { showPriceAlertDialog = true }
            )

            Spacer(modifier.height(8.dp))

            DetailPriceHeader(
                price = tick?.price ?: 0.0,
                change24h = change,
                activityText = activityText,
                activityColor = activityColor,
                quoteAsset = pair.quoteAsset
            )

            Spacer(modifier.height(10.dp))

            // Timeframe chips — wrap-friendly, tanpa mode badge (sudah di sticky)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf(Timeframe.M1, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1).forEach { tf ->
                    val isSelected = selectedTimeframe == tf
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) Color(0xFF1E2836) else Color(0xFF101720),
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF72B7FF) else Color(0xFF1E2836),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { viewModel.selectTimeframe(tf) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = tf.label.uppercase(),
                            color = if (isSelected) Color(0xFF72B7FF) else TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { chartVisible = !chartVisible },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836)),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.ShowChart, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (chartVisible) "Tutup Chart" else "Chart", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onOpenLandscapeChart,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF72B7FF)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836)),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.CropRotate, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Fullscreen", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            AnimatedVisibility(
                visible = chartVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09101A)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "CHART ${selectedTimeframe.label.uppercase()}",
                            color = Color(0xFF72B7FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        agu.analys.ui.components.SimpleComposeChart(
                            prices = emptyList(),
                            candles = candles,
                            currentPrice = tick?.price ?: 0.0,
                            isPositiveTrend = (tick?.change24h ?: 0.0) >= 0,
                            entryPrice = signal.entryPrice,
                            targetPrice1 = signal.targetPrice1,
                            targetPrice2 = signal.targetPrice2,
                            stopLoss = signal.stopLoss,
                            quoteAsset = pair.quoteAsset,
                            modifier = Modifier.fillMaxWidth().height(200.dp)
                        )
                    }
                }
            }

            Spacer(modifier.height(12.dp))

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
                onExecuteBuy = { nominalIdr ->
                    val execPrice = if (tick?.price != null && tick!!.price > 0) tick!!.price else signal.entryPrice
                    if (execPrice > 0) {
                        if (isRealBuyMode) {
                            viewModel.executeRealTrade(pair.symbol, "buy", execPrice.toLong(), nominalIdr) { success, msg ->
                                if (success) agu.analys.util.HapticUtil.vibrateTradeSuccess(context)
                                else agu.analys.util.HapticUtil.vibrateTradeFailure(context)
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
                            viewModel.setOwnership(true, execPrice)
                            if (isSuccess) agu.analys.util.HapticUtil.vibrateTradeSuccess(context)
                            else agu.analys.util.HapticUtil.vibrateTradeFailure(context)
                            android.widget.Toast.makeText(context, "Simulasi: $msg", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        agu.analys.util.HapticUtil.vibrateTradeFailure(context)
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
                                    if (success) agu.analys.util.HapticUtil.vibrateTradeSuccess(context)
                                    else agu.analys.util.HapticUtil.vibrateTradeFailure(context)
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            } else {
                                agu.analys.util.HapticUtil.vibrateTradeFailure(context)
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
                                if (sellQty >= bal) viewModel.setOwnership(false)
                                val isSuccess = res is agu.analys.trading.SimulationOrderResult.Success
                                val msg = when (res) {
                                    is agu.analys.trading.SimulationOrderResult.Success -> res.message
                                    is agu.analys.trading.SimulationOrderResult.Error -> res.message
                                }
                                if (isSuccess) agu.analys.util.HapticUtil.vibrateTradeSuccess(context)
                                else agu.analys.util.HapticUtil.vibrateTradeFailure(context)
                                android.widget.Toast.makeText(context, "Simulasi: $msg", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                agu.analys.util.HapticUtil.vibrateTradeFailure(context)
                                android.widget.Toast.makeText(context, "Saldo simulasi kosong.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onSetManualBuyPrice = { entryPrice, investedAmount ->
                    viewModel.setManualPositionPrice(pair.symbol, entryPrice, investedAmount)
                    agu.analys.util.HapticUtil.vibrateTradeSuccess(context)
                    android.widget.Toast.makeText(context, "Harga beli manual tersimpan!", android.widget.Toast.LENGTH_SHORT).show()
                },
                spotPosition = spotPosition,
                onSetTrailingStop = { enabled, pct ->
                    viewModel.setTrailingStop(enabled, pct)
                    agu.analys.util.HapticUtil.vibrateTradeSuccess(context)
                    android.widget.Toast.makeText(
                        context,
                        if (enabled) "Trailing $pct% aktif" else "Trailing off",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                onResetTrailingTrigger = { viewModel.resetTrailingTrigger() }
            )

            Spacer(modifier.height(8.dp))

            MarketConditionCard(
                structure = marketStructure,
                indicators = indicators,
                signal = signal,
                strategyMode = strategyMode,
                scalping = isScalping
            )

            Spacer(modifier.height(8.dp))

            ImportantLevelsCard(
                signal = signal,
                structure = marketStructure,
                price = tick?.price ?: 0.0,
                quoteAsset = pair.quoteAsset
            )

            Spacer(modifier.height(8.dp))

            var showTechnicalDetails by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF09121C)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showTechnicalDetails = !showTechnicalDetails },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, null, tint = Color(0xFF72B7FF), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("INDIKATOR & OBSERVASI", color = Color(0xFF72B7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            if (showTechnicalDetails) "Tutup ▲" else "Lihat ▼",
                            color = TvTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    AnimatedVisibility(visible = showTechnicalDetails) {
                        Column(Modifier.padding(top = 8.dp)) {
                            TechnicalDetailsCard(
                                indicators = indicators,
                                structure = marketStructure,
                                volume24h = tick?.volume24h ?: 0.0,
                                scalping = isScalping
                            )
                            Spacer(Modifier.height(8.dp))
                            MonitorCard(
                                signal = signal,
                                structure = marketStructure,
                                price = tick?.price ?: 0.0,
                                cached = false,
                                quoteAsset = pair.quoteAsset
                            )
                        }
                    }
                }
            }

            Spacer(modifier.height(8.dp))

            AiAssistantCard(
                auditText = aiGroq,
                auditLoading = aiLoadingGroq,
                geminiText = aiGemini,
                geminiLoading = aiLoadingGemini,
                onGroq = viewModel::requestDeepAiAudit,
                onGemini = viewModel::requestGeminiChartSummary,
                onClearGroq = viewModel::clearAuditReport,
                onClearGemini = viewModel::clearGeminiSummary
            )

            Spacer(Modifier.height(8.dp))
            DisclaimerCard()
            Spacer(modifier.height(12.dp))

            Button(
                onClick = { viewModel.openSimulation(pair) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
            ) {
                Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Simulasi Trade ${pair.baseAsset}", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }

            Spacer(modifier.height(6.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.openPortfolio() },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF72B7FF)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
                ) {
                    Icon(Icons.Default.AccountBalanceWallet, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Portofolio", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { openExchange(context, marketDataSource) },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5))
                ) {
                    Text("Buka ${marketDataSource.label}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }

            Spacer(modifier.height(6.dp))

            Button(
                onClick = {
                    if (provider == agu.analys.config.AiProvider.GROQ) viewModel.requestDeepAiAudit()
                    else viewModel.requestGeminiChartSummary()
                },
                enabled = !aiLoadingGroq && !aiLoadingGemini && live,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2836))
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = TvTextPrimary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (aiLoadingGroq || aiLoadingGemini) "Menganalisis..." else "AI Analisis Pasar",
                    color = TvTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier.height(16.dp))
        }
    }

    if (showPriceAlertDialog) {
        PriceAlertDialog(
            symbol = pair.symbol,
            currentPrice = tick?.price ?: 0.0,
            quoteAsset = pair.quoteAsset,
            alerts = priceAlerts,
            onAddAlert = { alert ->
                viewModel.addPriceAlert(alert)
                agu.analys.util.HapticUtil.vibrateTradeSuccess(context)
                android.widget.Toast.makeText(context, "Alert tersimpan!", android.widget.Toast.LENGTH_SHORT).show()
            },
            onRemoveAlert = { id ->
                viewModel.removePriceAlert(id)
                android.widget.Toast.makeText(context, "Alert dihapus", android.widget.Toast.LENGTH_SHORT).show()
            },
            onToggleAlert = { viewModel.togglePriceAlert(it) },
            onDismiss = { showPriceAlertDialog = false }
        )
    }
}
