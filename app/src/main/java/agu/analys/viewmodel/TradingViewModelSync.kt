package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.database.AppDatabase
import agu.analys.database.RealTradeEntity
import agu.analys.config.MarketDataSource
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.service.IndodaxMarketService
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
    val symbol = pair.replace("_", "").uppercase()
    if (type.equals("sell", ignoreCase = true)) {
        positionStore.markSold(symbol, isReal = true)
        positionStore.markSold(pair, isReal = true)
        agu.analys.engine.sell.SellSignalLifecycleManager.reset(symbol, isReal = true)
        agu.analys.engine.sell.SellSignalLifecycleManager.reset(pair, isReal = true)
        positionCoordinator.markSoldAndClear(symbol, isReal = true)
        positionCoordinator.markSoldAndClear(pair, isReal = true)
        signalLogRepository.expireTrackingLogsForSymbol(symbol, "Posisi real sudah terjual (MarkSold)")
        tradeHistoryRecorder.recordSell(
            symbol = symbol, isReal = true, sellPrice = price, sellQuantity = quantity,
            sellReason = if (tp1 > 0 || tp2 > 0) "TAKE_PROFIT" else "MARKET_SELL",
            strategyMode = strategyMode.value.name
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
            stopLossPrice = engine.signalState.value.stopLoss
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
                    signalSnapshotJson = snapshotJson
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
    val base = baseFromSymbolOrPair(pair)
    val symbol = "${base.uppercase()}IDR"
    val isBuy = type.equals("buy", ignoreCase = true)

    // 1. Mirror ke Riwayat Transaksi Simulasi dengan flag isRealMirror = true
    simCoordinator.recordMirroredRealTrade(
        symbol = symbol,
        baseAsset = base,
        quoteAsset = "IDR",
        side = if (isBuy) SimulationOrderSide.BUY else SimulationOrderSide.SELL,
        price = price,
        quantity = quantity
    )

    // 2. Sinkronkan ke SpotPositionStore agar engine tracking (Trailing Stop / TP / SL / Alert) aktif
    if (isBuy) {
        val currentPos = positionStore.get(symbol, isReal = true)
        if (currentPos.isHolding && currentPos.quantity > 0.00000001 && currentPos.entryPrice > 0.0) {
            val totalQty = currentPos.quantity + quantity
            val totalCost = (currentPos.entryPrice * currentPos.quantity) + (price * quantity)
            val weightedAvgPrice = if (totalQty > 0.0) totalCost / totalQty else price
            positionStore.setHolding(
                symbol = symbol,
                invested = totalCost,
                entry = weightedAvgPrice,
                quantity = totalQty,
                isReal = true
            )
        } else {
            positionStore.markBought(
                symbol = symbol,
                entryPrice = price,
                quantity = quantity,
                isReal = true
            )
        }
        if (tp1 > price || tp2 > price) {
            val currentPos = positionStore.get(symbol, isReal = true)
            positionStore.setAutoSellParams(
                symbol = symbol,
                enabled = true,
                tp1Price = if (tp1 > 0.0) tp1 else currentPos.tp1Price,
                tp1Percent = 50.0,
                tp2Price = if (tp2 > 0.0) tp2 else currentPos.tp2Price,
                tp2Percent = 50.0,
                isReal = true
            )
        }
    } else {
        val currentPos = positionStore.get(symbol, isReal = true)
        val remainingQty = (currentPos.quantity - quantity).coerceAtLeast(0.0)
        if (remainingQty <= 0.00000001) {
            positionStore.markSold(symbol, isReal = true)
        } else {
            positionStore.setHolding(
                symbol = symbol,
                invested = currentPos.entryPrice * remainingQty,
                entry = currentPos.entryPrice,
                quantity = remainingQty,
                isReal = true
            )
        }
    }
    refreshSpotPosition()
}

fun TradingViewModel.syncSimulationTradeToPositionStore(order: SimulationOrder) {
    val symbol = order.symbol
    if (order.side == SimulationOrderSide.BUY) {
        val fillPrice = if (order.filledAvgPrice > 0.0) order.filledAvgPrice else order.limitPrice
        val currentPos = positionStore.get(symbol, isReal = false)
        if (currentPos.isHolding && currentPos.quantity > 0.00000001 && currentPos.entryPrice > 0.0) {
            val totalQty = currentPos.quantity + order.quantity
            val totalCost = (currentPos.entryPrice * currentPos.quantity) + (fillPrice * order.quantity)
            val weightedAvg = if (totalQty > 0.0) totalCost / totalQty else fillPrice
            positionStore.setHolding(
                symbol = symbol,
                invested = totalCost,
                entry = weightedAvg,
                quantity = totalQty,
                isReal = false
            )
        } else {
            positionStore.markBought(
                symbol = symbol,
                entryPrice = fillPrice,
                quantity = order.quantity,
                isReal = false
            )
        }
    } else if (order.side == SimulationOrderSide.SELL) {
        val currentPos = positionStore.get(symbol, isReal = false)
        val currentQty = currentPos.quantity
        val remainingQty = (currentQty - order.quantity).coerceAtLeast(0.0)
        if (remainingQty <= 0.00000001) {
            positionStore.markSold(symbol, isReal = false)
        } else {
            positionStore.setHolding(
                symbol = symbol,
                invested = currentPos.entryPrice * remainingQty,
                entry = currentPos.entryPrice,
                quantity = remainingQty,
                isReal = false
            )
        }
    }
    refreshSpotPosition()
}

fun TradingViewModel.baseFromSymbolOrPair(pair: String): String {
    val s = pair.lowercase().replace("_", "")
    return when {
        s.endsWith("idr") -> s.removeSuffix("idr")
        s.endsWith("usdt") -> s.removeSuffix("usdt")
        else -> s
    }
}

fun TradingViewModel.syncRealBalancesToPositionStore(
    balances: Map<String, Double> = realCoordinator.realIndodaxBalance.value,
    avgPrices: Map<String, Double> = realCoordinator.realAvgBuyPrices.value
) {
    if (!prefs.hasIndodaxCredentials()) return
    val popularAndCustom = (TradingPair.POPULAR_INDODAX_PAIRS.map { it.baseAsset.uppercase() } + balances.keys.map { it.uppercase() }).distinct()
    
    for (baseUpper in popularAndCustom) {
        if (baseUpper == "IDR" || baseUpper == "USDT") continue
        val baseLower = baseUpper.lowercase()
        val symbol = "${baseUpper}IDR"
        val pairSymbol = "${baseLower}_idr"
        val qty = balances[baseLower] ?: balances[baseUpper] ?: 0.0
        val pos = positionStore.get(symbol, isReal = true)
        
        val avgPrice = avgPrices[symbol]
            ?: avgPrices[baseUpper]
            ?: avgPrices[baseLower]
            ?: avgPrices["${baseLower}idr"]
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
                    isReal = true
                )
                positionStore.markBought(
                    symbol = pairSymbol,
                    entryPrice = finalEntry,
                    quantity = qty,
                    invested = totalInvested,
                    isReal = true
                )
            } else {
                positionStore.setHolding(
                    symbol = symbol,
                    invested = totalInvested,
                    entry = finalEntry,
                    quantity = qty,
                    isReal = true
                )
                positionStore.setHolding(
                    symbol = pairSymbol,
                    invested = totalInvested,
                    entry = finalEntry,
                    quantity = qty,
                    isReal = true
                )
            }
        } else {
            if (pos.isHolding) {
                positionStore.markSold(symbol, isReal = true)
                positionStore.markSold(pairSymbol, isReal = true)
            }
        }
    }
    refreshSpotPosition()
}

fun TradingViewModel.updateForegroundServiceState() {
    val isReal = isRealBuyMode.value
    val hasActive = positionStore.getAllActiveTrailingSymbols(isReal = isReal).isNotEmpty() ||
                    positionStore.hasAnyHolding(isReal = isReal) ||
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
    agu.analys.util.MtfCacheManager.updateQueues(watchlist.value.toList(), emptyList())
    engine.strategyMode = prefs.strategyMode
    engine.isScalpingMode = prefs.isScalpingMode
    engine.tradingFees = prefs.tradingFees

    engine.onCandidateSignalTransition = { transition ->
        if (isNotificationsEnabled.value) {
            val position = positionStore.get(transition.symbol)
            if (!position.isHolding) {
                AlertNotificationHelper.sendCandidateFoundNotification(
                    context = getApplication(),
                    symbol = transition.symbol,
                    strategyMode = transition.mode,
                    signal = transition.signal
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
                scalpingStage = transition.signal.scalpingStage.name
            )
        }
    }

    viewModelScope.launch {
        val meta = IndodaxMarketService.fetchPairsMetadata()
        if (meta.isNotEmpty()) {
            marketCache.savePairsMetadata(meta)
        }
    }

    marketDataCoordinator.restoreFromCache(MarketDataSource.INDODAX)
    val initialPair = TradingPair.popularPairsForSource(prefs.marketDataSource).first()
    selectPair(initialPair)
    startTrailingPolling()
    updateForegroundServiceState()
    listenToEngineSignals()
    checkPublicIp()

    if (prefs.hasIndodaxCredentials()) {
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
                val activeSymbols = positionStore.getAllActiveTrailingSymbols()
                if (activeSymbols.isNotEmpty()) {
                    val pairs = activeSymbols.map {
                        TradingPair.fromCustomSymbol(it, "IDR").effectiveIndodaxPair()
                    }
                    val ticks = IndodaxMarketService.fetchTickers(pairs)
                    for (tick in ticks) {
                        simCoordinator.onPriceTick(tick.symbol, tick.price, tick.high24h, tick.low24h)
                        checkAlertsAndTrailing(tick.symbol, tick.price)
                        signalLogRepository.processPriceTick(tick.symbol, tick.price)
                        tradeHistoryRecorder.processPriceTick(tick.symbol, tick.price)
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
    if (positionStore.getAllActiveTrailingSymbols().isEmpty()) {
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
                    reasoning = signal.reasoning.joinToString(" • "), scalpingStage = signal.scalpingStage.name
                )
            }
        }
    }
}
