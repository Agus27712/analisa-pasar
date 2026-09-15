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
import java.util.concurrent.ConcurrentHashMap

private val lastPeakNotificationTimes = ConcurrentHashMap<String, Long>()

fun TradingViewModel.printTrailingDiagnostics(symbol: String, currentPrice: Double, pos: SpotPosition) {
    if (!pos.isHolding || !pos.isTrailingEnabled) return
    val effectivePct = if (pos.activeTrailingPercent > 0.0) pos.activeTrailingPercent else pos.trailingPercent
    val slPrice = positionStore.calculateTrailingLimitPrice(pos.peakPrice, pos.entryPrice, effectivePct)
    Timber.d("[$symbol] Trailing - Current: $currentPrice, Peak: ${pos.peakPrice}, Stop: $slPrice, EffectivePct: $effectivePct%, Enabled: ${pos.isTrailingEnabled}")
}

fun TradingViewModel.checkAlertsAndTrailing(symbol: String, currentPrice: Double, rsi: Double? = null) {
    val activeIsReal = isRealBuyMode.value
    // Prioritaskan evaluasi trailing untuk mode yang aktif
    checkTrailingForMode(symbol, currentPrice, isReal = activeIsReal)
    
    // Evaluasi mode alternatif HANYA jika ada holding aktif dengan trailing di store
    if (!activeIsReal) {
        val realPos = positionStore.get(symbol, isReal = true)
        if (realPos.isHolding && realPos.isTrailingEnabled) {
            checkTrailingForMode(symbol, currentPrice, isReal = true)
        }
    } else {
        val simPos = positionStore.get(symbol, isReal = false)
        if (simPos.isHolding && simPos.isTrailingEnabled) {
            checkTrailingForMode(symbol, currentPrice, isReal = false)
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
                    triggerTitle = "🎯 Target Tercapai • $symbol"
                    triggerMsg = "Harga naik menyentuh Rp ${PriceFormatter.formatIdrNumber(currentPrice)} (Target: Rp ${PriceFormatter.formatIdrNumber(alert.targetPrice)})."
                }
            }
            PriceAlertType.PRICE_BELOW -> {
                if (currentPrice <= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "📉 Peringatan Turun • $symbol"
                    triggerMsg = "Harga turun ke Rp ${PriceFormatter.formatIdrNumber(currentPrice)} (Target: Rp ${PriceFormatter.formatIdrNumber(alert.targetPrice)})."
                }
            }
            PriceAlertType.RSI_OVERSOLD -> {
                if (rsi != null && rsi <= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "📊 RSI Oversold • $symbol"
                    triggerMsg = "Indikator RSI menyentuh ${"%.1f".format(rsi)} (Target: ${alert.targetPrice.toInt()})."
                }
            }
            PriceAlertType.RSI_OVERBOUGHT -> {
                if (rsi != null && rsi >= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "📊 RSI Overbought • $symbol"
                    triggerMsg = "Indikator RSI menyentuh ${"%.1f".format(rsi)} (Target: ${alert.targetPrice.toInt()})."
                }
            }
            PriceAlertType.SECOND_WAVE_RECLAIM -> {
                if (currentPrice >= alert.targetPrice && alert.targetPrice > 0.0) {
                    shouldTrigger = true
                    triggerTitle = "🌊 Second-Wave Reclaim • $symbol"
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

fun TradingViewModel.checkTrailingForMode(symbol: String, currentPrice: Double, isReal: Boolean) {
    val posBeforeUpdate = positionStore.get(symbol, isReal)
    if (!posBeforeUpdate.isHolding) return

    val oldPeak = posBeforeUpdate.peakPrice
    val oldEffectivePct = if (posBeforeUpdate.activeTrailingPercent > 0.0) posBeforeUpdate.activeTrailingPercent else posBeforeUpdate.trailingPercent
    val oldSlPrice = positionStore.calculateTrailingLimitPrice(oldPeak, posBeforeUpdate.entryPrice, oldEffectivePct)
    printTrailingDiagnostics(symbol, currentPrice, posBeforeUpdate)
    val (updatedPos, justTriggered) = positionStore.updateTrailingPrice(symbol, currentPrice, isReal)

    if (justTriggered) {
        refreshSpotPosition()
        val effectivePct = if (updatedPos.activeTrailingPercent > 0.0) updatedPos.activeTrailingPercent else updatedPos.trailingPercent
        val limitSellPrice = positionStore.calculateTrailingLimitPrice(updatedPos.peakPrice, updatedPos.entryPrice, effectivePct)
        
        val baseKey = TradingPair.fromCustomSymbol(symbol).baseAsset.uppercase()
        val baseLower = baseKey.lowercase()
        val posQty = if (updatedPos.quantity > 0.0) updatedPos.quantity else {
            if (isReal) {
                realCoordinator.realFreeBalance.value[baseLower]
                    ?: realCoordinator.realIndodaxBalance.value[baseLower]
                    ?: 0.0
            } else simCoordinator.wallet.value.getAvailableCoin(baseKey)
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
            // Eksekusi auto-sell jaring pengaman otomatis
            executeAutoSellOrder(symbol, limitSellPrice, posQty, "TRAILING", isReal)
        }
    } else if (updatedPos.isHolding && updatedPos.isTrailingEnabled && updatedPos.peakPrice > oldPeak) {
        val effectivePct = if (updatedPos.activeTrailingPercent > 0.0) updatedPos.activeTrailingPercent else updatedPos.trailingPercent
        val newSlPrice = positionStore.calculateTrailingLimitPrice(updatedPos.peakPrice, updatedPos.entryPrice, effectivePct)
        // NOTIFIKASI HANYA DIKIRIM JIKA BATAS AMAN (STOP LIMIT) BENAR-BENAR NAIK
        if (newSlPrice > oldSlPrice) {
            refreshSpotPosition()
            if (isReal) {
                updateRealTrailingOrder(symbol, updatedPos, newSlPrice)
            } else {
                updateSimTrailingOrder(symbol, updatedPos, newSlPrice, updatedPos.quantity)
            }

            // Notifikasi Trailing Peak Naik: Diberi jeda cerdas (minimal 30 detik antar notifikasi per koin)
            val now = System.currentTimeMillis()
            val lastAlertTime = lastPeakNotificationTimes["${symbol}_$isReal"] ?: 0L
            val currentProfitPct = if (updatedPos.entryPrice > 0.0) ((updatedPos.peakPrice - updatedPos.entryPrice) / updatedPos.entryPrice) * 100.0 else 0.0
            if (now - lastAlertTime > 30_000L) {
                lastPeakNotificationTimes["${symbol}_$isReal"] = now
                AlertNotificationHelper.sendTrailingPeakUpdateNotification(
                    context = getApplication(),
                    symbol = symbol,
                    newPeak = updatedPos.peakPrice,
                    stopLimitPrice = newSlPrice,
                    entryPrice = updatedPos.entryPrice,
                    profitPct = currentProfitPct,
                    isReal = isReal
                )
            }
        }
    }

    // Auto Take Profit / Stop Loss Check
    if (updatedPos.isHolding && updatedPos.isAutoSellEnabled) {
        val qty = updatedPos.quantity
        if (qty > 0.0) {
            // Check TP1
            if (!updatedPos.isTp1Triggered && updatedPos.tp1Price > 0.0 && currentPrice >= updatedPos.tp1Price) {
                positionStore.markTp1Triggered(symbol, isReal)
                refreshSpotPosition()
                val sellQty = qty * (updatedPos.tp1Percent / 100.0)
                executeAutoSellOrder(symbol, currentPrice, sellQty, "TP1", isReal, isPartial = updatedPos.tp1Percent < 100.0)
            }
            // Check TP2
            if (!updatedPos.isTp2Triggered && updatedPos.tp2Price > 0.0 && currentPrice >= updatedPos.tp2Price) {
                positionStore.markTp2Triggered(symbol, isReal)
                refreshSpotPosition()
                val sellQty = qty * (updatedPos.tp2Percent / 100.0)
                executeAutoSellOrder(symbol, currentPrice, sellQty, "TP2", isReal, isPartial = updatedPos.tp2Percent < 100.0)
            }
            // Check Stop Loss / Cut Loss Terpicu
            if (updatedPos.stopLossPrice > 0.0 && currentPrice <= updatedPos.stopLossPrice) {
                refreshSpotPosition()
                executeAutoSellOrder(symbol, currentPrice, qty, "STOP_LOSS", isReal, isPartial = false)
            }
        }
    }
}

fun TradingViewModel.executeAutoSellOrder(symbol: String, price: Double, quantity: Double, triggerType: String, isReal: Boolean, isPartial: Boolean = false) {
    val modeTag = if (isReal) "REAL" else "SIMULASI"
    val triggerLabel = when {
        triggerType.contains("TRAILING") -> "Trailing Stop Terpicu"
        triggerType.contains("STOP_LOSS") -> "Stop Loss / Cut Loss"
        triggerType.contains("TP1") -> "Target Profit 1 (TP1)"
        triggerType.contains("TP2") -> "Target Profit 2 (TP2)"
        else -> "Jual Otomatis"
    }

    if (isReal) {
        // Diskon 5% dari harga terkini agar berfungsi 100% layaknya Market Sell instan di orderbook
        val marketSellPrice = (price * 0.95).toLong()
        executeRealTrade(symbol, "sell", marketSellPrice, quantity, 0.0, 0.0) { success, msg ->
            if (!success && triggerType.contains("TRAILING")) {
                positionStore.resetTrailingTrigger(symbol, isReal = true)
            }
            val notifTitle = if (success) {
                if (triggerType.contains("STOP_LOSS")) "🛡️ [$modeTag] Cut Loss Terlaksana • $symbol"
                else "✅ [$modeTag] Aset Diamankan ($triggerLabel) • $symbol"
            } else "❌ [$modeTag] Gagal Jual • $symbol"

            val notifMsg = if (success) {
                "$triggerLabel [$modeTag] aktif! Koin berhasil dieksekusi di kisaran harga Rp ${PriceFormatter.formatIdrNumber(price)}."
            } else {
                "Sistem gagal mengeksekusi order [$modeTag]: $msg"
            }
            AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = notifTitle,
                message = notifMsg,
                notificationId = (symbol.hashCode() and 0x3FFFFFFF) + 100000 + 2000,
                symbol = symbol
            )
        }
    } else {
        val pair = TradingPair.fromCustomSymbol(raw = symbol)
        val simBal = simCoordinator.wallet.value.getAvailableCoin(pair.baseAsset)
        val finalSellQty = if (!isPartial) (if (simBal > 0.0) simBal else quantity) else quantity.coerceAtMost(simBal)
        if (finalSellQty <= 0.0) {
            positionCoordinator.setOwnership(symbol, false, price, isReal = false)
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
            positionCoordinator.setOwnership(symbol, false, price, isReal = false)
        } else {
            positionCoordinator.refreshPosition(symbol)
        }
        if (!success && triggerType.contains("TRAILING")) {
            positionStore.resetTrailingTrigger(symbol, isReal = false)
        }
        val notifTitle = if (success) {
            if (triggerType.contains("STOP_LOSS")) "🛡️ [$modeTag] Cut Loss Terlaksana • $symbol"
            else "✅ [$modeTag] Aset Diamankan ($triggerLabel) • $symbol"
        } else "❌ [$modeTag] Gagal Jual • $symbol"

        val notifMsg = if (success) {
            "$triggerLabel [$modeTag] aktif! Koin terjual di harga Rp ${PriceFormatter.formatIdrNumber(price)}."
        } else {
            "Gagal (Simulasi): $msg"
        }
        AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = notifTitle,
            message = notifMsg,
            notificationId = (symbol.hashCode() and 0x3FFFFFFF) + 200000 + 2000,
            symbol = symbol
        )
    }
}

fun TradingViewModel.deployTrailingOrder(symbol: String) {
    val isReal = isRealBuyMode.value
    var pos = positionStore.get(symbol, isReal)
    val pair = TradingPair.fromCustomSymbol(symbol)
    val baseKey = pair.baseAsset.uppercase()
    
    val currentPrice = (if (marketDataCoordinator.currentTick.value?.symbol?.equals(symbol, ignoreCase = true) == true) marketDataCoordinator.currentTick.value?.price else null)
        ?: marketDataCoordinator.dashboardTicks.value[symbol]?.price
        ?: marketDataCoordinator.dashboardTicks.value[TradingPair.fromCustomSymbol(symbol).symbol]?.price
        ?: (if (pos.peakPrice > 0.0) pos.peakPrice else pos.entryPrice)

    // Auto sync holding position if in Simulation mode and wallet has coin
    if (!isReal) {
        val simCoin = simCoordinator.wallet.value.getTotalCoin(baseKey)
        if (simCoin > 0.0 && (!pos.isHolding || pos.quantity <= 0.0)) {
            val entryP = if (pos.entryPrice > 0.0) pos.entryPrice else 0.0
            positionStore.setHolding(symbol, invested = simCoin * entryP, entry = entryP, quantity = simCoin, isReal = false)
            pos = positionStore.get(symbol, isReal = false)
        }
    }

    val effectiveTrailingPct = if (pos.trailingPercent > 0.0) pos.trailingPercent else 2.0
    val effectivePeak = if (pos.peakPrice > 0.0) pos.peakPrice.coerceAtLeast(currentPrice) else currentPrice

    // Ensure trailing stop is enabled & peak updated in storage with tiered settings preserved
    positionStore.setTrailingStop(
        symbol = symbol,
        enabled = true,
        trailingPercent = effectiveTrailingPct,
        referencePrice = effectivePeak,
        isTieredEnabled = pos.isTieredTrailingEnabled,
        customTiersJson = pos.tieredConfigJson,
        isReal = isReal
    )
    
    val trailingOrderId = if (isReal) "real-client-trailing" else "sim-client-trailing"
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, trailingOrderId, System.currentTimeMillis(), isReal = isReal)
    positionCoordinator.refreshPosition(symbol)

    updateForegroundServiceState()
    startTrailingPolling()

    val initialEffectivePct = if (pos.activeTrailingPercent > 0.0) pos.activeTrailingPercent else effectiveTrailingPct
    val slPrice = positionStore.calculateTrailingLimitPrice(effectivePeak, pos.entryPrice, initialEffectivePct)
    val notifTitle = if (isReal) "🛡️ [REAL] Trailing Stop Aktif • $symbol" else "🛡️ [SIMULASI] Trailing Stop Aktif • $symbol"
    val notifMsg = if (isReal) {
        "Aplikasi sedang memantau [REAL]. Koin akan dijual otomatis jika harga turun ke Rp ${PriceFormatter.formatIdrNumber(slPrice)}."
    } else {
        "Aplikasi sedang memantau [SIMULASI]. Koin akan dijual otomatis di Rp ${PriceFormatter.formatIdrNumber(slPrice)}."
    }

    AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = notifTitle,
        message = notifMsg,
        notificationId = (symbol.hashCode() and 0x3FFFFFFF) + (if (isReal) 100000 else 200000) + 1000,
        symbol = symbol
    )
    Timber.d("[$symbol] Trailing order deployed successfully ($trailingOrderId) @ peak=$effectivePeak, stop=$slPrice (isReal=$isReal)")
}

fun TradingViewModel.cancelTrailingOrder(symbol: String) {
    val isReal = isRealBuyMode.value
    val pos = positionStore.get(symbol, isReal)
    val orderId = pos.lastTrailingOrderId
    
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
    
    positionCoordinator.setTrailing(symbol, enabled = false, 0.0, 0.0, isReal = isReal)
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, null, 0L, isReal = isReal)
    positionCoordinator.refreshPosition(symbol)
    checkAndStopTrailingServiceIfEmpty()
}

fun TradingViewModel.updateSimTrailingOrder(symbol: String, pos: SpotPosition, slPrice: Double, quantityToSell: Double) {
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, "sim-client-trailing", System.currentTimeMillis(), isReal = false)
    AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = "📈 [SIMULASI] Trailing Stop Naik • $symbol",
        message = "Batas aman penjualan otomatis naik ke Rp ${PriceFormatter.formatIdrNumber(slPrice)} (Mengikuti kenaikan harga).",
        notificationId = (symbol.hashCode() and 0x3FFFFFFF) + 200000 + 1000,
        symbol = symbol,
        onlyWhenBackground = true
    )
}

fun TradingViewModel.updateRealTrailingOrder(symbol: String, pos: SpotPosition, newSlPrice: Double) {
    // Pure Client-Side update for REAL mode
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, "real-client-trailing", System.currentTimeMillis(), isReal = true)
    AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = "📈 [REAL] Trailing Stop Naik • $symbol",
        message = "Batas aman penjualan otomatis naik ke Rp ${PriceFormatter.formatIdrNumber(newSlPrice)}.",
        notificationId = (symbol.hashCode() and 0x3FFFFFFF) + 100000 + 1000,
        symbol = symbol,
        onlyWhenBackground = true
    )
}

fun TradingViewModel.executeTrailingSellLimitOrder(symbol: String, limitPrice: Double, quantity: Double, isReal: Boolean) {
    if (isReal) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val apiKey = prefs.indodaxApiKey
            val secretKey = prefs.indodaxSecretKey
            if (apiKey.isBlank() || secretKey.isBlank()) return@launch

            val latestTick = marketDataCoordinator.dashboardTicks.value[symbol]
            val maxAge = if (prefs.isScalpingMode) 400L else 800L
            if (latestTick != null && (System.currentTimeMillis() - latestTick.timestamp > maxAge)) {
                positionStore.resetTrailingTrigger(symbol, isReal = true)
                AlertNotificationHelper.sendPriceAlertNotification(
                    context = getApplication(),
                    title = "❌ [REAL] Gagal Trailing Sell • $symbol",
                    message = "Data harga terlalu usang (delay > ${maxAge}ms).",
                    notificationId = (symbol.hashCode() and 0x3FFFFFFF) + 100000 + 3000,
                    symbol = symbol
                )
                return@launch
            }
            
            // Terapkan diskon 5% ke FRESH PRICE agar jaring order laku instan (seperti market sell)
            val execPrice = latestTick?.price ?: limitPrice
            val discountedPrice = execPrice * 0.95

            val res = agu.analys.service.IndodaxTradeApiV2.createLimitOrderDetailed(
                apiKey = apiKey, secretKey = secretKey, symbol = symbol, side = "sell",
                price = discountedPrice, quantity = quantity, clientOrderId = "agu-trail-${System.currentTimeMillis()}"
            )
            
            if (!res.success) {
                positionStore.resetTrailingTrigger(symbol, isReal = true)
            } else {
                refreshRealBalance()
            }

            val notifTitle = if (res.success) "✅ [REAL] Trailing Sell Terlaksana • $symbol" else "❌ [REAL] Gagal Trailing Sell • $symbol"
            val notifMsg = if (res.success) {
                "Profit Lock [REAL] aktif! Koin berhasil dieksekusi di kisaran harga Rp ${PriceFormatter.formatIdrNumber(execPrice)}."
            } else {
                "Sistem gagal mengeksekusi order [REAL]: ${res.message}"
            }
            AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = notifTitle,
                message = notifMsg,
                notificationId = (symbol.hashCode() and 0x3FFFFFFF) + 100000 + 3000,
                symbol = symbol
            )
        }
    } else {
        val pair = TradingPair.fromCustomSymbol(raw = symbol)
        val simBal = simCoordinator.wallet.value.getAvailableCoin(pair.baseAsset)
        val finalSellQty = quantity.coerceAtMost(if (simBal > 0.0) simBal else quantity)
        
        if (finalSellQty <= 0.0) {
            positionCoordinator.setOwnership(symbol, false, limitPrice, isReal = false)
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
            positionStore.resetTrailingTrigger(symbol, isReal = false)
        }
        val notifTitle = if (success) "✅ [SIMULASI] Limit Sell Terpasang • $symbol" else "❌ [SIMULASI] Gagal Limit Sell • $symbol"
        val notifMsg = if (success) {
            "Profit Lock [SIMULASI] aktif! Limit Sell Order dipasang di Rp ${PriceFormatter.formatIdrNumber(limitPrice)}."
        } else {
            "Gagal (Simulasi): $msg"
        }
        AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = notifTitle,
            message = notifMsg,
            notificationId = (symbol.hashCode() and 0x3FFFFFFF) + 200000 + 3000,
            symbol = symbol
        )
    }
}
