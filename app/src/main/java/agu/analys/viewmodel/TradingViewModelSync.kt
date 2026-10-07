package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.database.AppDatabase
import agu.analys.database.RealTradeEntity
import agu.analys.config.MarketDataSource
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.service.IndodaxMarketService
import agu.analys.service.TokocryptoMarketService
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.TradeSignalSnapshot
import agu.analys.util.AlertNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Extension for TradingViewModel dealing with synchronizing real balances,
 * mirrored simulated trades, foreground service state, position store updates,
 * background trailing polling, and trade execution events.
 */

fun TradingViewModel.handleRealTradeExecution(
    pair: String,
    type: String,
    price: Double,
    quantity: Double,
    tp1: Double,
    tp2: Double
) {
    val currentEx = prefs.marketDataSource.name
    val symbol = pair.replace("_", "").uppercase()
    if (type.equals("sell", ignoreCase = true)) {
        positionStore.markSold(symbol, isReal = true, exchange = currentEx)
        positionStore.markSold(pair, isReal = true, exchange = currentEx)
        agu.analys.engine.sell.SellSignalLifecycleManager.reset(symbol, isReal = true)
        agu.analys.engine.sell.SellSignalLifecycleManager.reset(pair, isReal = true)
        positionCoordinator.markSoldAndClear(symbol, isReal = true)
        positionCoordinator.markSoldAndClear(pair, isReal = true)
        signalLogRepository.expireTrackingLogsForSymbol(symbol, "Posisi real sudah terjual (MarkSold)")
        tradeHistoryRecorder.recordSell(
            symbol = symbol, isReal = true, sellPrice = price, sellQuantity = quantity,
            sellReason = if (tp1 > 0 || tp2 > 0) "TAKE_PROFIT" else "MARKET_SELL",
            strategyMode = strategyMode.value.name,
            exchange = currentEx
        )
    } else if (type.equals("buy", ignoreCase = true)) {
        val snapshot = TradeSignalSnapshot.capture(
            symbol = symbol, strategyMode = strategyMode.value.name,
            tick = marketDataCoordinator.dashboardTicks.value[symbol],
            indicators = engine.indicators.value, signal = engine.signalState.value
        )
        val snapshotJson = snapshot.toJson().toString()
        tradeHistoryRecorder.recordBuy(
            symbol = symbol, isReal = true, strategyMode = strategyMode.value.name,
            buyPrice = price, buyQuantity = quantity, buyTotalIdr = price * quantity,
            buyOrderType = "LIMIT", snapshot = snapshot,
            signalPrice = engine.signalState.value.entryPrice.takeIf { it > 0 } ?: price,
            signalConfidence = engine.signalState.value.confidence, targetPrice1 = tp1, targetPrice2 = tp2,
            stopLossPrice = engine.signalState.value.stopLoss,
            exchange = currentEx
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localEntity = RealTradeEntity(
                    id = "local_${System.currentTimeMillis()}_${symbol}",
                    symbol = pair.lowercase().replace("_", ""),
                    price = price, qty = quantity, amount = price * quantity,
                    time = System.currentTimeMillis(), side = "BUY", isBuyer = true,
                    strategyMode = strategyMode.value.name,
                    holdingDurationMs = 0L, entryPrice = price, entryTimestamp = System.currentTimeMillis(),
                    signalSnapshotJson = snapshotJson,
                    exchange = currentEx
                )
                AppDatabase.getInstance().realTradeDao().insertTrades(listOf(localEntity))
            } catch (_: Exception) {}
        }
    }
    syncRealTradeToSimulation(pair, type, price, quantity, tp1, tp2)
    positionCoordinator.refreshPosition(selectedPair.value.symbol)
    refreshSpotPosition()
}

fun TradingViewModel.syncRealTradeToSimulation(
    pair: String,
    type: String,
    price: Double,
    quantity: Double,
    tp1: Double = 0.0,
    tp2: Double = 0.0
) {
    if (!prefs.isRealSimSyncEnabled) return
    val defaultQuote = prefs.marketDataSource.defaultQuoteAsset
    val tradingPair = TradingPair.fromCustomSymbol(pair, defaultQuote)
    val symbol = tradingPair.symbol
    val isBuy = type.equals("buy", ignoreCase = true)
    val currentEx = prefs.marketDataSource.name

    // 1. Mirror ke Riwayat Transaksi Simulasi dengan flag isRealMirror = true
    simCoordinator.recordMirroredRealTrade(
        symbol = symbol,
        baseAsset = tradingPair.baseAsset,
        quoteAsset = tradingPair.quoteAsset,
        side = if (isBuy) SimulationOrderSide.BUY else SimulationOrderSide.SELL,
        price = price,
        quantity = quantity
    )

    // 2. Sinkronkan ke SpotPositionStore agar engine tracking (Trailing Stop / TP / SL / Alert) aktif
    if (isBuy) {
        val currentPos = positionStore.get(symbol, isReal = true, exchange = currentEx)
        if (currentPos.isHolding && currentPos.quantity > 0.00000001 && currentPos.entryPrice > 0.0) {
            val totalQty = currentPos.quantity + quantity
            val totalCost = (currentPos.entryPrice * currentPos.quantity) + (price * quantity)
            val weightedAvgPrice = if (totalQty > 0.0) totalCost / totalQty else price
            positionStore.setHolding(
                symbol = symbol,
                invested = totalCost,
                entry = weightedAvgPrice,
                quantity = totalQty,
                isReal = true,
                exchange = currentEx
            )
        } else {
            positionStore.markBought(
                symbol = symbol,
                entryPrice = price,
                quantity = quantity,
                isReal = true,
                exchange = currentEx
            )
        }
        if (tp1 > price || tp2 > price) {
            val currentPos = positionStore.get(symbol, isReal = true, exchange = currentEx)
            positionStore.setAutoSellParams(
                symbol = symbol,
                enabled = true,
                tp1Price = if (tp1 > 0.0) tp1 else currentPos.tp1Price,
                tp1Percent = 50.0,
                tp2Price = if (tp2 > 0.0) tp2 else currentPos.tp2Price,
                tp2Percent = 50.0,
                isReal = true,
                exchange = currentEx
            )
        }
    } else {
        val currentPos = positionStore.get(symbol, isReal = true, exchange = currentEx)
        val remainingQty = (currentPos.quantity - quantity).coerceAtLeast(0.0)
        if (remainingQty <= 0.00000001) {
            positionStore.markSold(symbol, isReal = true, exchange = currentEx)
        } else {
            positionStore.setHolding(
                symbol = symbol,
                invested = currentPos.entryPrice * remainingQty,
                entry = currentPos.entryPrice,
                quantity = remainingQty,
                isReal = true,
                exchange = currentEx
            )
        }
    }
    refreshSpotPosition()
}

fun TradingViewModel.syncSimulationTradeToPositionStore(order: SimulationOrder) {
    val symbol = order.symbol
    val currentEx = prefs.marketDataSource.name
    val fillPrice = if (order.filledAvgPrice > 0.0) order.filledAvgPrice else order.limitPrice
    if (order.side == SimulationOrderSide.BUY) {
        val currentPos = positionStore.get(symbol, isReal = false, exchange = currentEx)
        if (currentPos.isHolding && currentPos.quantity > 0.00000001 && currentPos.entryPrice > 0.0) {
            val totalQty = currentPos.quantity + order.quantity
            val totalCost = (currentPos.entryPrice * currentPos.quantity) + (fillPrice * order.quantity)
            val weightedAvg = if (totalQty > 0.0) totalCost / totalQty else fillPrice
            positionStore.setHolding(
                symbol = symbol,
                invested = totalCost,
                entry = weightedAvg,
                quantity = totalQty,
                isReal = false,
                exchange = currentEx
            )
        } else {
            positionStore.markBought(
                symbol = symbol,
                entryPrice = fillPrice,
                quantity = order.quantity,
                isReal = false,
                exchange = currentEx
            )
        }
        val snapshot = TradeSignalSnapshot.capture(
            symbol = symbol, strategyMode = strategyMode.value.name,
            tick = marketDataCoordinator.dashboardTicks.value[symbol],
            indicators = engine.indicators.value, signal = engine.signalState.value
        )
        tradeHistoryRecorder.recordBuy(
            symbol = symbol,
            isReal = false,
            strategyMode = strategyMode.value.name,
            buyPrice = fillPrice,
            buyQuantity = order.quantity,
            buyTotalIdr = fillPrice * order.quantity,
            buyOrderType = order.type.name,
            snapshot = snapshot,
            signalPrice = engine.signalState.value.entryPrice.takeIf { it > 0 } ?: fillPrice,
            signalConfidence = engine.signalState.value.confidence,
            targetPrice1 = engine.signalState.value.targetPrice1,
            targetPrice2 = engine.signalState.value.targetPrice2,
            stopLossPrice = engine.signalState.value.stopLoss,
            exchange = currentEx
        )
    } else if (order.side == SimulationOrderSide.SELL) {
        val currentPos = positionStore.get(symbol, isReal = false, exchange = currentEx)
        val currentQty = currentPos.quantity
        val remainingQty = (currentQty - order.quantity).coerceAtLeast(0.0)
        if (remainingQty <= 0.00000001) {
            positionStore.markSold(symbol, isReal = false, exchange = currentEx)
        } else {
            positionStore.setHolding(
                symbol = symbol,
                invested = currentPos.entryPrice * remainingQty,
                entry = currentPos.entryPrice,
                quantity = remainingQty,
                isReal = false,
                exchange = currentEx
            )
        }
        tradeHistoryRecorder.recordSell(
            symbol = symbol,
            isReal = false,
            sellPrice = fillPrice,
            sellQuantity = order.quantity,
            sellReason = "SIMULATION_SELL",
            strategyMode = strategyMode.value.name,
            exchange = currentEx
        )
    }
    refreshSpotPosition()
}

fun TradingViewModel.baseFromSymbolOrPair(pair: String): String {
    val s = pair.lowercase().replace("_", "")
    return when {
        s.endsWith("idr") -> s.removeSuffix("idr")
        s.endsWith("usdt") -> s.removeSuffix("usdt")
        s.endsWith("bidr") -> s.removeSuffix("bidr")
        s.endsWith("usd") -> s.removeSuffix("usd")
        else -> s
    }
}

fun TradingViewModel.syncRealBalancesToPositionStore(
    balances: Map<String, Double> = realCoordinator.realIndodaxBalance.value,
    avgPrices: Map<String, Double> = realCoordinator.realAvgBuyPrices.value
) {
    val hasCreds = prefs.hasTokocryptoCredentials() || prefs.hasIndodaxCredentials()
    if (!hasCreds) return
    val currentEx = prefs.marketDataSource.name
    val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
    val defaultQuote = if (isToko) "USDT" else "IDR"
    val staleQuote = if (isToko) "IDR" else "USDT"
    
    val basePairs = if (isToko) {
        TradingPair.POPULAR_TOKOCRYPTO_PAIRS.map { it.baseAsset.uppercase() }
    } else {
        TradingPair.POPULAR_INDODAX_PAIRS.map { it.baseAsset.uppercase() }
    }
    val popularAndCustom = (basePairs + balances.keys.map { it.uppercase() }).distinct()
    
    for (baseUpper in popularAndCustom) {
        if (baseUpper == "IDR" || baseUpper == "BIDR" || baseUpper == "USDT" || baseUpper == "USDC" || baseUpper == "USD") continue
        val baseLower = baseUpper.lowercase()
        val symbol = "${baseUpper}${defaultQuote}"
        val pairSymbol = "${baseLower}_${defaultQuote.lowercase()}"
        val staleSymbol = "${baseUpper}${staleQuote}"
        val stalePairSymbol = "${baseLower}_${staleQuote.lowercase()}"
        
        val qty = balances[baseLower] ?: balances[baseUpper] ?: 0.0
        val pos = positionStore.get(symbol, isReal = true, exchange = currentEx)
        
        val avgPrice = avgPrices[symbol]
            ?: avgPrices["${baseLower}${defaultQuote.lowercase()}"]
            ?: avgPrices[baseUpper]
            ?: avgPrices[baseLower]
            ?: 0.0
        
        if (qty > 0.00000001) {
            val finalEntry = if (avgPrice > 0.0) avgPrice else if (pos.entryPrice > 0.0) pos.entryPrice else 0.0
            val totalInvested = if (finalEntry > 0.0) finalEntry * qty else pos.investedAmount
            if (!pos.isHolding) {
                positionStore.markBought(
                    symbol = symbol,
                    entryPrice = finalEntry,
                    quantity = qty,
                    invested = totalInvested,
                    isReal = true,
                    exchange = currentEx
                )
                positionStore.markBought(
                    symbol = pairSymbol,
                    entryPrice = finalEntry,
                    quantity = qty,
                    invested = totalInvested,
                    isReal = true,
                    exchange = currentEx
                )
            } else {
                positionStore.setHolding(
                    symbol = symbol,
                    invested = totalInvested,
                    entry = finalEntry,
                    quantity = qty,
                    isReal = true,
                    exchange = currentEx
                )
                positionStore.setHolding(
                    symbol = pairSymbol,
                    invested = totalInvested,
                    entry = finalEntry,
                    quantity = qty,
                    isReal = true,
                    exchange = currentEx
                )
            }
            // Bersihkan posisi duplikat kuotasi yang tidak aktif untuk koin ini pada exchange ini
            positionStore.markSold(staleSymbol, isReal = true, exchange = currentEx)
            positionStore.markSold(stalePairSymbol, isReal = true, exchange = currentEx)
        } else {
            if (pos.isHolding) {
                positionStore.markSold(symbol, isReal = true, exchange = currentEx)
                positionStore.markSold(pairSymbol, isReal = true, exchange = currentEx)
            }
            positionStore.markSold(staleSymbol, isReal = true, exchange = currentEx)
            positionStore.markSold(stalePairSymbol, isReal = true, exchange = currentEx)
        }
    }
    refreshSpotPosition()
}

fun TradingViewModel.updateForegroundServiceState() {
    val isReal = isRealBuyMode.value
    val currentEx = prefs.marketDataSource.name
    val hasActive = positionStore.getAllActiveTrailingSymbols(isReal = isReal, exchange = currentEx).isNotEmpty() ||
                    positionStore.hasAnyHolding(isReal = isReal, exchange = currentEx) ||
                    (!isReal && simCoordinator.wallet.value.coinBalances.any { it.value > 0.00000001 && !it.key.equals("IDR", true) && !it.key.equals("USDT", true) }) ||
                    (isReal && realCoordinator.realIndodaxBalance.value.any { it.value > 0.00000001 && !it.key.equals("IDR", true) && !it.key.equals("USDT", true) })

    if (hasActive && isNotificationsEnabled.value) {
        agu.analys.service.TradingForegroundService.startService(getApplication())
        agu.analys.service.TradingForegroundService.forceRefresh(getApplication())
    } else {
        agu.analys.service.TradingForegroundService.stopService(getApplication())
    }
}

fun TradingViewModel.initSubscriptionsAndPolling() {
    viewModelScope.launch {
        realCoordinator.isRealBuyEnabled.collect {
            refreshSpotPosition()
        }
    }
    watchlistViewModel.onWatchlistUpdated = {
        recalculateDashboardBadges()
    }
    agu.analys.util.MtfCacheManager.updateQueues(watchlist.value.toList(), emptyList(), prefs.marketDataSource.name)
    engine.strategyMode = prefs.strategyMode
    engine.isScalpingMode = prefs.isScalpingMode
    engine.tradingFees = prefs.tradingFees

    engine.onCandidateSignalTransition = { transition ->
        if (isNotificationsEnabled.value) {
            val isReal = isRealBuyMode.value
            val currentEx = prefs.marketDataSource.name
            val position = positionStore.get(transition.symbol, isReal = isReal, exchange = currentEx)
            if (!position.isHolding) {
                AlertNotificationHelper.sendCandidateFoundNotification(
                    context = getApplication(),
                    symbol = transition.symbol,
                    strategyMode = transition.mode,
                    signal = transition.signal,
                    exchange = currentEx
                )
            }
        }
        if (transition.signal.action != SignalAction.HOLD && transition.signal.entryPrice > 0.0) {
            signalLogRepository.recordSignal(
                symbol = transition.symbol,
                action = transition.signal.action.name,
                strategyMode = transition.mode.name,
                confidence = transition.signal.confidence,
                sentiment = transition.signal.sentiment.name,
                entryPrice = transition.signal.entryPrice,
                targetPrice1 = transition.signal.targetPrice1,
                targetPrice2 = transition.signal.targetPrice2,
                stopLoss = transition.signal.stopLoss,
                reasoning = transition.signal.reasoning.joinToString(" • "),
                scalpingStage = transition.signal.scalpingStage.name,
                exchange = prefs.marketDataSource.name,
                scalpingSetup = transition.signal.scalpingSetup,
                scalpingScore = transition.signal.scalpingScore,
                scalpingScoreCategory = transition.signal.scalpingScoreCategory,
                scalpingRegime = transition.signal.scalpingRegime
            )
        }
    }

    viewModelScope.launch(Dispatchers.IO) {
        val isToko = prefs.marketDataSource == agu.analys.config.MarketDataSource.TOKOCRYPTO
        if (isToko) {
            agu.analys.data.TokocryptoSymbolRepository.ensureSymbolsLoaded(false)
        } else {
            val meta = IndodaxMarketService.fetchPairsMetadata()
            if (meta.isNotEmpty()) {
                marketCache.savePairsMetadata(meta, agu.analys.config.MarketDataSource.INDODAX)
            }
        }
    }

    // Sinkronisasi satu sumber data harga antara MarketDataCoordinator dan MarketViewModel
    viewModelScope.launch {
        marketDataCoordinator.dashboardTicks.collect { ticks ->
            if (ticks.isNotEmpty()) {
                marketViewModel.updateDashboardTicks(ticks)
            }
            delay(500L) // batasi maks 2x/detik; nilai terbaru tetap terkirim (StateFlow conflated)
        }
    }
    viewModelScope.launch {
        marketViewModel.dashboardTicks.collect { ticks ->
            if (ticks.isNotEmpty()) {
                marketDataCoordinator.updateDashboardTicks(ticks)
            }
            delay(500L)
        }
    }

    marketDataCoordinator.restoreFromCache(prefs.marketDataSource)
    val initialPair = TradingPair.popularPairsForSource(prefs.marketDataSource).first()
    selectPair(initialPair)
    refreshWorthCoinsFromMarket()
    startDashboardPolling()
    startTrailingPolling()
    updateForegroundServiceState()
    listenToEngineSignals()
    checkPublicIp()

    if (prefs.hasIndodaxCredentials() || prefs.hasTokocryptoCredentials()) {
        syncRealBalancesToPositionStore()
    }

    viewModelScope.launch {
        tradeHistoryRecorder.seedSampleTradeJourneysIfEmpty()
    }

    viewModelScope.launch {
        simCoordinator.lastFilledOrder.collect { filledOrder ->
            if (filledOrder != null && filledOrder.status == agu.analys.trading.SimulationOrderStatus.FILLED) {
                if (filledOrder.side == SimulationOrderSide.SELL) {
                    positionCoordinator.markSoldAndClear(filledOrder.symbol)
                    positionCoordinator.setTrailing(filledOrder.symbol, enabled = false, 0.0, 0.0)
                    signalLogRepository.expireTrackingLogsForSymbol(filledOrder.symbol, "Simulasi sell filled (MarkSold)")
                    checkAndStopTrailingServiceIfEmpty()
                }
            }
        }
    }
}

fun TradingViewModel.startTrailingPolling() {
    if (trailingPollJob?.isActive == true) return
    trailingPollJob = viewModelScope.launch {
        while (isActive) {
            try {
                val currentEx = prefs.marketDataSource.name
                val isReal = isRealBuyMode.value
                val activeSymbols = positionStore.getAllActiveTrailingSymbols(isReal = isReal, exchange = currentEx)
                if (activeSymbols.isNotEmpty()) {
                    val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
                    val ticks = if (isToko) {
                        TokocryptoMarketService.fetchTickers(activeSymbols)
                    } else {
                        val pairs = activeSymbols.map {
                            TradingPair.fromCustomSymbol(it, "IDR", exchange = currentEx).effectiveIndodaxPair()
                        }
                        IndodaxMarketService.fetchTickers(pairs)
                    }
                    for (tick in ticks) {
                        simCoordinator.onPriceTick(tick.symbol, tick.price, tick.high24h, tick.low24h)
                        checkAlertsAndTrailing(tick.symbol, tick.price, exchange = currentEx)
                        signalLogRepository.processPriceTick(tick.symbol, tick.price, currentEx)
                        tradeHistoryRecorder.processPriceTick(tick.symbol, tick.price, currentEx)
                    }
                    delay(10_000L)
                } else {
                    checkAndStopTrailingServiceIfEmpty()
                    delay(20_000L)
                }
            } catch (_: Exception) {
                delay(12_000L)
            }
        }
    }
}

fun TradingViewModel.checkAndStopTrailingServiceIfEmpty() {
    updateForegroundServiceState()
    val currentEx = prefs.marketDataSource.name
    val isReal = isRealBuyMode.value
    if (positionStore.getAllActiveTrailingSymbols(isReal = isReal, exchange = currentEx).isEmpty()) {
        trailingPollJob?.cancel()
        trailingPollJob = null
    }
}

fun TradingViewModel.listenToEngineSignals() {
    viewModelScope.launch {
        engine.signalState.collect { signal ->
            val now = System.currentTimeMillis()
            if (signal.action != SignalAction.HOLD && now - lastSavedSignalTimestamp > 15000L) {
                lastSavedSignalTimestamp = now
                val list = _signalHistory.value.toMutableList()
                list.add(0, signal.copy(marketSymbol = selectedPair.value.symbol))
                if (list.size > 30) list.removeAt(list.lastIndex)
                _signalHistory.value = list

                signalLogRepository.recordSignal(
                    symbol = selectedPair.value.symbol, action = signal.action.name,
                    strategyMode = strategyMode.value.name, confidence = signal.confidence,
                    sentiment = signal.sentiment.name, entryPrice = if (signal.entryPrice > 0) signal.entryPrice else (currentTick.value?.price ?: 0.0),
                    targetPrice1 = signal.targetPrice1, targetPrice2 = signal.targetPrice2, stopLoss = signal.stopLoss,
                    reasoning = signal.reasoning.joinToString(" • "), scalpingStage = signal.scalpingStage.name,
                    exchange = prefs.marketDataSource.name,
                    scalpingSetup = signal.scalpingSetup, scalpingScore = signal.scalpingScore,
                    scalpingScoreCategory = signal.scalpingScoreCategory, scalpingRegime = signal.scalpingRegime
                )
            }
        }
    }
}
