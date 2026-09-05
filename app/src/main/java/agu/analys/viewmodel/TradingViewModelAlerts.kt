package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.model.PriceAlert
import agu.analys.model.PriceAlertType
import agu.analys.model.TradingPair
import agu.analys.service.IndodaxTradeApiV2
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SpotPosition
import agu.analys.util.AlertNotificationHelper
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

fun TradingViewModel.printTrailingDiagnostics(symbol: String, currentPrice: Double, pos: SpotPosition) {
    if (!pos.isHolding || !pos.isTrailingEnabled) return
    val slPrice = positionStore.calculateTrailingLimitPrice(pos.peakPrice, pos.entryPrice, pos.trailingPercent)
    Timber.d("[$symbol] Trailing - Current: $currentPrice, Peak: ${pos.peakPrice}, Stop: $slPrice, Enabled: ${pos.isTrailingEnabled}")
}

fun TradingViewModel.checkAlertsAndTrailing(symbol: String, currentPrice: Double, rsi: Double? = null) {
    val posBeforeUpdate = positionStore.get(symbol)
    val oldPeak = posBeforeUpdate.peakPrice
    printTrailingDiagnostics(symbol, currentPrice, posBeforeUpdate)
    val (updatedPos, justTriggered) = positionStore.updateTrailingPrice(symbol, currentPrice)

    if (justTriggered) {
        refreshSpotPosition()
        val limitSellPrice = positionStore.calculateTrailingLimitPrice(updatedPos.peakPrice, updatedPos.entryPrice, updatedPos.trailingPercent)
        
        val isSimTrailing = updatedPos.lastTrailingOrderId?.startsWith("sim") == true
        val isReal = isRealBuyMode.value && !isSimTrailing
        val baseKey = TradingPair.fromCustomSymbol(symbol).baseAsset.uppercase()
        val posQty = if (updatedPos.quantity > 0.0) updatedPos.quantity else {
            if (isReal) 0.0 else simCoordinator.wallet.value.getAvailableCoin(baseKey)
        }

        if (posQty > 0.0) {
            AlertNotificationHelper.sendTrailingHitNotification(
                context = getApplication(),
                symbol = symbol,
                entryPrice = updatedPos.entryPrice,
                peakPrice = updatedPos.peakPrice,
                currentPrice = currentPrice,
                limitSellPrice = limitSellPrice,
                quantity = posQty,
                isReal = isReal
            )
        }
    } else if (updatedPos.isHolding && updatedPos.isTrailingEnabled && updatedPos.peakPrice > oldPeak) {
        refreshSpotPosition()
        val isSimTrailing = updatedPos.lastTrailingOrderId?.startsWith("sim") == true
        val isReal = isRealBuyMode.value && !isSimTrailing
        if (isReal) {
            updateRealTrailingOrder(symbol, updatedPos, currentPrice)
        } else {
            val slPrice = positionStore.calculateTrailingLimitPrice(updatedPos.peakPrice, updatedPos.entryPrice, updatedPos.trailingPercent)
            updateSimTrailingOrder(symbol, updatedPos, slPrice, updatedPos.quantity)
        }
    }

    // Auto Take Profit / Stop Loss Check
    if (updatedPos.isHolding && updatedPos.isAutoSellEnabled) {
        val qty = updatedPos.quantity
        if (qty > 0.0) {
            val isReal = isRealBuyMode.value
            // Check TP1
            if (!updatedPos.isTp1Triggered && updatedPos.tp1Price > 0.0 && currentPrice >= updatedPos.tp1Price) {
                positionStore.markTp1Triggered(symbol)
                refreshSpotPosition()
                val sellQty = qty * (updatedPos.tp1Percent / 100.0)
                executeAutoSellOrder(symbol, currentPrice, sellQty, "TP1", isReal, isPartial = updatedPos.tp1Percent < 100.0)
            }
            // Check TP2
            if (!updatedPos.isTp2Triggered && updatedPos.tp2Price > 0.0 && currentPrice >= updatedPos.tp2Price) {
                positionStore.markTp2Triggered(symbol)
                refreshSpotPosition()
                val sellQty = qty * (updatedPos.tp2Percent / 100.0)
                executeAutoSellOrder(symbol, currentPrice, sellQty, "TP2", isReal, isPartial = updatedPos.tp2Percent < 100.0)
            }
        }
    }

    // Price Alerts Trigger Check
    val alerts = alertStore.getAlertsForSymbol(symbol)
    for (alert in alerts) {
        if (!alert.isEnabled || alert.isTriggered) continue
        var shouldTrigger = false
        var triggerTitle = ""
        var triggerMsg = ""

        when (alert.type) {
            PriceAlertType.PRICE_ABOVE -> {
                if (currentPrice >= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "🎯 HARGA NAIK ($symbol)"
                    triggerMsg = "Harga telah mencapai Rp ${PriceFormatter.formatIdrNumber(currentPrice)} (Target: Rp ${PriceFormatter.formatIdrNumber(alert.targetPrice)})."
                }
            }
            PriceAlertType.PRICE_BELOW -> {
                if (currentPrice <= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "📉 HARGA TURUN ($symbol)"
                    triggerMsg = "Harga telah turun ke Rp ${PriceFormatter.formatIdrNumber(currentPrice)} (Target: Rp ${PriceFormatter.formatIdrNumber(alert.targetPrice)})."
                }
            }
            PriceAlertType.RSI_OVERSOLD -> {
                if (rsi != null && rsi <= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "📊 RSI OVERSOLD ($symbol)"
                    triggerMsg = "RSI telah menyentuh ${"%.1f".format(rsi)} (Target: ${alert.targetPrice.toInt()})."
                }
            }
            PriceAlertType.RSI_OVERBOUGHT -> {
                if (rsi != null && rsi >= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "📊 RSI OVERBOUGHT ($symbol)"
                    triggerMsg = "RSI telah menyentuh ${"%.1f".format(rsi)} (Target: ${alert.targetPrice.toInt()})."
                }
            }
            PriceAlertType.SECOND_WAVE_RECLAIM -> {
                if (currentPrice >= alert.targetPrice && alert.targetPrice > 0.0) {
                    shouldTrigger = true
                    triggerTitle = "🌊 SECOND-WAVE RECLAIM ($symbol)"
                    triggerMsg = "Setup Second-Wave terkonfirmasi di harga Rp ${PriceFormatter.formatIdrNumber(currentPrice)}."
                }
            }
        }
        if (shouldTrigger) {
            alertStore.markTriggered(alert.id)
            refreshPriceAlerts()
            AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = triggerTitle,
                message = triggerMsg,
                notificationId = alert.id.hashCode() and 0x7FFFFFFF,
                symbol = symbol
            )
        }
    }
}

fun TradingViewModel.executeAutoSellOrder(symbol: String, price: Double, quantity: Double, triggerType: String, isReal: Boolean, isPartial: Boolean = false) {
    if (isReal) {
        // Diskon 5% dari harga terkini agar berfungsi 100% layaknya Market Sell instan di orderbook
        val marketSellPrice = (price * 0.95).toLong()
        executeRealTrade(symbol, "sell", marketSellPrice, quantity, 0.0, 0.0) { success, msg ->
            if (!success && triggerType.contains("TRAILING")) {
                positionStore.resetTrailingTrigger(symbol)
            }
            val triggerLabel = if (triggerType.contains("TRAILING")) "Jaring Pengaman" else "Jual Otomatis"
            val notifTitle = if (success) "✅ ASET DIAMANKAN ($symbol)" else "❌ GAGAL DIJUAL ($symbol)"
            val notifMsg = if (success) {
                "$triggerLabel aktif! Koin berhasil dijual otomatis di kisaran harga ${PriceFormatter.formatIdrNumber(price)}."
            } else {
                "Sistem gagal menjual koin: $msg"
            }
            AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = notifTitle,
                message = notifMsg,
                notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 2000,
                symbol = symbol
            )
        }
    } else {
        val pair = TradingPair.fromCustomSymbol(raw = symbol)
        val simBal = simCoordinator.wallet.value.getAvailableCoin(pair.baseAsset)
        val finalSellQty = if (!isPartial) (if (simBal > 0.0) simBal else quantity) else quantity.coerceAtMost(simBal)
        if (finalSellQty <= 0.0) {
            positionCoordinator.setOwnership(symbol, false, price)
            return
        }
        val res = simCoordinator.submitOrder(
            pair = pair,
            currentPrice = price,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            price = price,
            stopPrice = 0.0,
            quantity = finalSellQty
        )
        val success = res is SimulationOrderResult.Success
        val msg = when (res) {
            is SimulationOrderResult.Success -> res.message
            is SimulationOrderResult.Error -> res.message
        }
        if (!isPartial) {
            positionCoordinator.setOwnership(symbol, false, price)
        } else {
            positionCoordinator.refreshPosition(symbol)
        }
        if (!success && triggerType.contains("TRAILING")) {
            positionStore.resetTrailingTrigger(symbol)
        }
        val triggerLabel = if (triggerType.contains("TRAILING")) "Jaring Pengaman" else "Jual Otomatis"
        val notifTitle = if (success) "✅ ASET DIAMANKAN [SIM] ($symbol)" else "❌ GAGAL DIJUAL [SIM] ($symbol)"
        val notifMsg = if (success) {
            "$triggerLabel aktif! Koin terjual di harga ${PriceFormatter.formatIdrNumber(price)} (Simulasi)."
        } else {
            "Gagal (Simulasi): $msg"
        }
        AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = notifTitle,
            message = notifMsg,
            notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 2000,
            symbol = symbol
        )
    }
}

fun TradingViewModel.deployTrailingOrder(symbol: String) {
    val isReal = isRealBuyMode.value
    var pos = positionStore.get(symbol)
    val pair = TradingPair.fromCustomSymbol(symbol)
    val baseKey = pair.baseAsset.uppercase()
    
    val currentPrice = marketDataCoordinator.currentTick.value?.price 
        ?: marketDataCoordinator.dashboardTicks.value[symbol]?.price 
        ?: (if (pos.peakPrice > 0.0) pos.peakPrice else pos.entryPrice)

    // Auto sync holding position if in Simulation mode and wallet has coin
    if (!isReal) {
        val simCoin = simCoordinator.wallet.value.getTotalCoin(baseKey)
        if (simCoin > 0.0 && (!pos.isHolding || pos.quantity <= 0.0)) {
            val entryP = if (pos.entryPrice > 0.0) pos.entryPrice else 0.0
            positionStore.setHolding(symbol, invested = simCoin * entryP, entry = entryP, quantity = simCoin)
            pos = positionStore.get(symbol)
        }
    }

    val effectiveTrailingPct = if (pos.trailingPercent > 0.0) pos.trailingPercent else 2.0
    val effectivePeak = if (pos.peakPrice > 0.0) pos.peakPrice.coerceAtLeast(currentPrice) else currentPrice

    // Ensure trailing stop is enabled & peak updated in storage
    positionStore.setTrailingStop(symbol, enabled = true, trailingPercent = effectiveTrailingPct, referencePrice = effectivePeak)
    
    val trailingOrderId = if (isReal) "real-client-trailing" else "sim-client-trailing"
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, trailingOrderId, System.currentTimeMillis())
    positionCoordinator.refreshPosition(symbol)

    agu.analys.service.TradingForegroundService.startService(getApplication<android.app.Application>())
    startTrailingPolling()

    val slPrice = positionStore.calculateTrailingLimitPrice(effectivePeak, pos.entryPrice, effectiveTrailingPct)
    val notifTitle = if (isReal) "🔒 JARING PENGAMAN AKTIF ($symbol)" else "🔒 JARING PENGAMAN AKTIF [SIM] ($symbol)"
    val notifMsg = if (isReal) {
        "Aplikasi sedang memantau. Koin akan dijual otomatis jika harga turun ke Rp ${PriceFormatter.formatIdrNumber(slPrice)}."
    } else {
        "Aplikasi sedang memantau (Simulasi). Koin akan dijual otomatis di Rp ${PriceFormatter.formatIdrNumber(slPrice)}."
    }

    AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = notifTitle,
        message = notifMsg,
        notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000,
        symbol = symbol
    )
    Timber.d("[$symbol] Trailing order deployed successfully ($trailingOrderId) @ peak=$effectivePeak, stop=$slPrice")
}

fun TradingViewModel.cancelTrailingOrder(symbol: String) {
    val pos = positionStore.get(symbol)
    val orderId = pos.lastTrailingOrderId
    val isReal = isRealBuyMode.value
    
    if (!orderId.isNullOrEmpty()) {
        if (isReal) {
            if (orderId != "real-client-trailing" && !orderId.startsWith("client-trailing")) {
                val apiKey = prefs.indodaxApiKey
                val secretKey = prefs.indodaxSecretKey
                if (apiKey.isNotBlank() && secretKey.isNotBlank()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        IndodaxTradeApiV2.cancelOrder(apiKey, secretKey, symbol, orderId)
                    }
                }
            }
        } else {
            if (orderId != "sim-client-trailing") {
                simCoordinator.cancelOrder(orderId)
            }
        }
    }
    
    positionCoordinator.setTrailing(symbol, enabled = false, 0.0, 0.0)
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, null, 0L)
    positionCoordinator.refreshPosition(symbol)
    checkAndStopTrailingServiceIfEmpty()
}

fun TradingViewModel.updateSimTrailingOrder(symbol: String, pos: SpotPosition, slPrice: Double, quantityToSell: Double) {
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, "sim-client-trailing", System.currentTimeMillis())
    AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = "📈 JARING PENGAMAN NAIK [SIM] ($symbol)",
        message = "Batas aman penjualan otomatis naik ke Rp ${PriceFormatter.formatIdrNumber(slPrice)} (Mengikuti harga tertinggi).",
        notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000,
        symbol = symbol
    )
}

fun TradingViewModel.updateRealTrailingOrder(symbol: String, pos: SpotPosition, currentPrice: Double) {
    // Pure Client-Side update for REAL mode
    val newSlPrice = positionStore.calculateTrailingLimitPrice(pos.peakPrice, pos.entryPrice, pos.trailingPercent)
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, "real-client-trailing", System.currentTimeMillis())
    AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = "📈 JARING PENGAMAN NAIK ($symbol)",
        message = "Batas aman penjualan otomatis naik ke Rp ${PriceFormatter.formatIdrNumber(newSlPrice)}.",
        notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000,
        symbol = symbol
    )
}

fun TradingViewModel.executeTrailingSellLimitOrder(symbol: String, limitPrice: Double, quantity: Double, isReal: Boolean) {
    if (isReal) {
        val limitPriceLong = limitPrice.toLong()
        executeRealTrade(symbol, "sell", limitPriceLong, quantity, 0.0, 0.0) { success, msg ->
            if (!success) {
                positionStore.resetTrailingTrigger(symbol)
            }
            val notifTitle = if (success) "✅ LIMIT SELL TERKIRIM ($symbol)" else "❌ GAGAL LIMIT SELL ($symbol)"
            val notifMsg = if (success) {
                "Profit Lock aktif! Limit Sell Order dipasang di harga Rp ${PriceFormatter.formatIdrNumber(limitPrice)}."
            } else {
                "Sistem gagal memasang Limit Sell: $msg"
            }
            AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = notifTitle,
                message = notifMsg,
                notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 2000,
                symbol = symbol
            )
        }
    } else {
        val pair = TradingPair.fromCustomSymbol(raw = symbol)
        val simBal = simCoordinator.wallet.value.getAvailableCoin(pair.baseAsset)
        val finalSellQty = quantity.coerceAtMost(if (simBal > 0.0) simBal else quantity)
        
        if (finalSellQty <= 0.0) {
            positionCoordinator.setOwnership(symbol, false, limitPrice)
            return
        }
        val res = simCoordinator.submitOrder(
            pair = pair,
            currentPrice = limitPrice,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.LIMIT,
            price = limitPrice,
            stopPrice = 0.0,
            quantity = finalSellQty
        )
        val success = res is SimulationOrderResult.Success
        val msg = when (res) {
            is SimulationOrderResult.Success -> res.message
            is SimulationOrderResult.Error -> res.message
        }
        if (success) {
            positionCoordinator.refreshPosition(symbol)
        } else {
            positionStore.resetTrailingTrigger(symbol)
        }
        val notifTitle = if (success) "✅ LIMIT SELL TERKIRIM [SIM] ($symbol)" else "❌ GAGAL LIMIT SELL [SIM] ($symbol)"
        val notifMsg = if (success) {
            "Profit Lock aktif! Limit Sell Order dipasang di Rp ${PriceFormatter.formatIdrNumber(limitPrice)} (Simulasi)."
        } else {
            "Gagal (Simulasi): $msg"
        }
        AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = notifTitle,
            message = notifMsg,
            notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 2000,
            symbol = symbol
        )
    }
}
