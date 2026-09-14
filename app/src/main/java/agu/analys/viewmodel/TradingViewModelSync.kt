package agu.analys.viewmodel

import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderSide

/**
 * Extension for TradingViewModel dealing with synchronizing real balances,
 * mirrored simulated trades, foreground service state, and position store updates.
 */

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
    if (!prefs.hasIndodaxCredentials() || !prefs.isRealSimSyncEnabled) return
    val popularAndCustom = (TradingPair.POPULAR_INDODAX_PAIRS.map { it.baseAsset.uppercase() } + balances.keys.map { it.uppercase() }).distinct()
    
    for (baseUpper in popularAndCustom) {
        if (baseUpper == "IDR" || baseUpper == "USDT") continue
        val baseLower = baseUpper.lowercase()
        val symbol = "${baseUpper}IDR"
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
                // Terdeteksi ada saldo real baru dari luar app -> auto-sync markBought
                positionStore.markBought(
                    symbol = symbol,
                    entryPrice = finalEntry,
                    quantity = qty,
                    invested = totalInvested,
                    isReal = true
                )
            } else {
                // Update kuantitas dan harga rata-rata secara sinkron
                positionStore.setHolding(
                    symbol = symbol,
                    invested = totalInvested,
                    entry = finalEntry,
                    quantity = qty,
                    isReal = true
                )
            }
        } else {
            if (pos.isHolding) {
                positionStore.markSold(symbol, isReal = true)
            }
        }
    }
    refreshSpotPosition()
}

fun TradingViewModel.updateForegroundServiceState() {
    val hasActive = positionStore.getAllActiveTrailingSymbols().isNotEmpty() ||
                    positionStore.hasAnyHolding()

    if (hasActive && isNotificationsEnabled.value) {
        agu.analys.service.TradingForegroundService.startService(getApplication())
    } else {
        agu.analys.service.TradingForegroundService.stopService(getApplication())
    }
}
