package agu.analys.viewmodel

import agu.analys.model.SignalAction
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SpotPosition
import agu.analys.util.PriceFormatter
import agu.analys.service.IndodaxTradeApiV2
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.min

fun TradingViewModel.printTrailingDiagnostics(symbol: String, currentPrice: Double, pos: SpotPosition) {
    if (!pos.isHolding) return
    val trailingStop = pos.peakPrice * (1.0 - pos.trailingPercent / 100.0)
    val isSimTrailing = pos.lastTrailingOrderId?.startsWith("sim-") == true
    val mode = if (isRealBuyMode.value && !isSimTrailing) "REAL" else "SIMULASI"
    Timber.d(
        """
        [DIAGNOSTIK TRAILING - $symbol]
        - Mode: $mode
        - Status: ${if (pos.isTrailingEnabled) "AKTIF" else "MATI"}
        - Entry Price: ${pos.entryPrice}
        - Peak Price (Tertinggi): ${pos.peakPrice}
        - Current Price (Sekarang): $currentPrice
        - Trailing Distance: ${pos.trailingPercent}%
        - Garis Stop-Loss (Cut-off): $trailingStop
        - Jarak Saat Ini ke SL: ${currentPrice - trailingStop}
        - Apakah Sudah Terpicu?: ${pos.isTrailingTriggered}
        - Qty Dimiliki: ${pos.quantity}
        """.trimIndent()
    )
}

fun TradingViewModel.checkAlertsAndTrailing(symbol: String, currentPrice: Double, rsiValue: Double? = null) {
    if (currentPrice <= 0.0) return
    val posBeforeUpdate = positionStore.get(symbol)
    val oldPeak = posBeforeUpdate.peakPrice
    printTrailingDiagnostics(symbol, currentPrice, posBeforeUpdate)
    val (updatedPos, justTriggered) = positionStore.updateTrailingPrice(symbol, currentPrice)

    if (justTriggered) {
        refreshSpotPosition()
        agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = "🚨 TRAILING STOP LOSS TERPICU ($symbol)",
            message = "Harga turun ke ${PriceFormatter.formatIdrNumber(currentPrice)} IDR.",
            notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000
        )
        
        // Eksekutor Jual Aktif
        val isSimTrailing = updatedPos.lastTrailingOrderId?.startsWith("sim-") == true
        val isReal = isRealBuyMode.value && !isSimTrailing
        val posQty = updatedPos.quantity
        if (posQty > 0.0) {
            if (isReal) {
                if (updatedPos.lastTrailingOrderId.isNullOrEmpty()) {
                    executeAutoSellOrder(symbol, currentPrice, posQty, "TRAILING STOP (CLIENT)", isReal = true)
                } else {
                    // Order bursa sudah keisi secara alami karena menyentuh SL.
                    // Bersihkan posisi lokal
                    positionStore.markSold(symbol)
                    refreshSpotPosition()
                }
            } else {
                executeAutoSellOrder(symbol, currentPrice, posQty, "TRAILING STOP (SIMULASI)", isReal = false)
            }
        }
    } else if (updatedPos.isTrailingEnabled) {
        if (symbol == selectedPair.value.symbol) {
            refreshSpotPosition()
        }
        
        // Logika update order limit di bursa saat harga naik signifikan
        val newPeak = updatedPos.peakPrice
        val isReal = isRealBuyMode.value
        if (newPeak > oldPeak && isReal && updatedPos.isHolding && !updatedPos.lastTrailingOrderId.isNullOrEmpty()) {
            val increasePct = (newPeak - oldPeak) / oldPeak * 100.0
            val timeSinceLastUpdate = System.currentTimeMillis() - updatedPos.lastOrderUpdateTime
            // Minimal kenaikan Peak: 0.4%, Cooldown: 12-20s (kita pakai 15s)
            if (increasePct >= 0.4 && timeSinceLastUpdate >= 15_000) {
                updateRealTrailingOrder(symbol, updatedPos, currentPrice)
            }
        }
    }

    // Check Auto-Sell TP/SL Triggers
    val pos = positionStore.get(symbol)
    if (pos.isHolding && pos.isAutoSellEnabled && pos.quantity > 0.0) {
        val isReal = isRealBuyMode.value
        // 1. Stop Loss
        if (pos.stopLossPrice > 0.0 && currentPrice <= pos.stopLossPrice && !pos.isSlTriggered) {
            positionStore.markSlTriggered(symbol)
            refreshSpotPosition()
            executeAutoSellOrder(symbol, currentPrice, pos.quantity, "STOP LOSS", isReal)
        }
        // 2. Take Profit 2 (Ultimate Target)
        else if (pos.tp2Price > 0.0 && currentPrice >= pos.tp2Price && !pos.isTp2Triggered) {
            positionStore.markTp2Triggered(symbol)
            refreshSpotPosition()
            executeAutoSellOrder(symbol, currentPrice, pos.quantity, "TAKE PROFIT 2 (100%)", isReal)
        }
        // 3. Take Profit 1 (Partial)
        else if (pos.tp1Price > 0.0 && currentPrice >= pos.tp1Price && !pos.isTp1Triggered) {
            positionStore.markTp1Triggered(symbol)
            val sellQty = pos.quantity * (pos.tp1Percent / 100.0)
            if (sellQty > 0.0 && sellQty < pos.quantity) {
                positionStore.deductQuantity(symbol, sellQty)
                refreshSpotPosition()
                executeAutoSellOrder(symbol, currentPrice, sellQty, "TAKE PROFIT 1 (${pos.tp1Percent}%)", isReal, isPartial = true)
            } else if (sellQty >= pos.quantity) {
                positionStore.markTp2Triggered(symbol) // fully closed
                refreshSpotPosition()
                executeAutoSellOrder(symbol, currentPrice, pos.quantity, "TAKE PROFIT 1 (100%)", isReal)
            }
        }
    }

    val activeAlerts = alertStore.getActiveAlertsForSymbol(symbol)
    for (alert in activeAlerts) {
        var shouldTrigger = false
        var triggerTitle = ""
        var triggerMsg = ""
        when (alert.type) {
            agu.analys.model.PriceAlertType.PRICE_ABOVE -> if (currentPrice >= alert.targetPrice) {
                shouldTrigger = true
                triggerTitle = "🎯 Target $symbol"
                triggerMsg = "Harga ${PriceFormatter.formatIdrNumber(currentPrice)} IDR ≥ target."
            }
            agu.analys.model.PriceAlertType.PRICE_BELOW -> if (currentPrice <= alert.targetPrice) {
                shouldTrigger = true
                triggerTitle = "⚠️ Alert bawah $symbol"
                triggerMsg = "Harga ${PriceFormatter.formatIdrNumber(currentPrice)} IDR ≤ batas."
            }
            agu.analys.model.PriceAlertType.RSI_OVERSOLD -> if (rsiValue != null && rsiValue < 30.0) {
                shouldTrigger = true
                triggerTitle = "⚡ RSI Oversold ($symbol)"
                triggerMsg = "RSI ${String.format(java.util.Locale.US, "%.1f", rsiValue)}"
            }
            agu.analys.model.PriceAlertType.RSI_OVERBOUGHT -> if (rsiValue != null && rsiValue > 70.0) {
                shouldTrigger = true
                triggerTitle = "🔥 RSI Overbought ($symbol)"
                triggerMsg = "RSI ${String.format(java.util.Locale.US, "%.1f", rsiValue)}"
            }
            agu.analys.model.PriceAlertType.SECOND_WAVE_RECLAIM -> if (aiSignalState.value.action == SignalAction.BUY) {
                shouldTrigger = true
                triggerTitle = "🚀 Reclaim $symbol"
                triggerMsg = "BUY di ${PriceFormatter.formatIdrNumber(currentPrice)} IDR"
            }
        }
        if (shouldTrigger) {
            alertStore.markTriggered(alert.id)
            refreshPriceAlerts()
            agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = triggerTitle,
                message = triggerMsg,
                notificationId = alert.id.hashCode() and 0x7FFFFFFF
            )
        }
    }
}

fun TradingViewModel.executeAutoSellOrder(symbol: String, price: Double, quantity: Double, triggerType: String, isReal: Boolean, isPartial: Boolean = false) {
    if (isReal) {
        executeRealTrade(symbol, "sell", price.toLong(), quantity, 0.0, 0.0) { success, msg ->
            if (!success && triggerType.contains("TRAILING")) {
                positionStore.resetTrailingTrigger(symbol)
            }
            val notifTitle = if (success) "✅ AUTO-SELL TERKIRIM ($symbol)" else "❌ AUTO-SELL GAGAL ($symbol)"
            val notifMsg = if (success) {
                "Trigger $triggerType aktif. Berhasil menjual $quantity $symbol di harga ${PriceFormatter.formatIdrNumber(price)} IDR."
            } else {
                "Gagal menjual karena: $msg"
            }
            agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = notifTitle,
                message = notifMsg,
                notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 2000
            )
        }
    } else {
        val pair = agu.analys.model.TradingPair.fromCustomSymbol(raw = symbol)
        val res = simCoordinator.submitOrder(
            pair = pair,
            currentPrice = price,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            price = price,
            stopPrice = 0.0,
            quantity = quantity
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
        val notifTitle = if (success) "✅ SIM AUTO-SELL ($symbol)" else "❌ SIM AUTO-SELL GAGAL ($symbol)"
        agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = notifTitle,
            message = "Simulasi $triggerType terpicu: $msg",
            notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 2000
        )
    }
}

fun TradingViewModel.deployTrailingOrder(symbol: String) {
    val pos = positionStore.get(symbol)
    if (!pos.isHolding || !pos.isTrailingEnabled) return
    val isReal = isRealBuyMode.value
    
    if (isReal) {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return
        }
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val slPrice = pos.peakPrice * (1.0 - pos.trailingPercent / 100.0)
            val roundedSlPrice = slPrice.toLong().toDouble()
            val clientOrderId = "agu-trailing-${System.currentTimeMillis()}"
            
            val orderResult = IndodaxTradeApiV2.createLimitOrderDetailed(
                apiKey = apiKey,
                secretKey = secretKey,
                symbol = symbol,
                side = "sell",
                price = roundedSlPrice,
                quantity = pos.quantity,
                clientOrderId = clientOrderId
            )
            
            if (orderResult.success) {
                positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, orderResult.orderId, System.currentTimeMillis())
                agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
                     context = getApplication(),
                     title = "🔒 TRAILING STOP DIPASANG ($symbol)",
                     message = "Stop-loss aktif di Rp ${PriceFormatter.formatIdrNumber(roundedSlPrice)}.",
                     notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000
                )
            } else {
                Timber.e("Gagal pasang trailing order: ${orderResult.message}")
            }
        }
    } else {
        // Simulation mode: just mark as deployed (virtual orderId)
        positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, "sim-${System.currentTimeMillis()}", System.currentTimeMillis())
    }
}

fun TradingViewModel.cancelTrailingOrder(symbol: String) {
    val pos = positionStore.get(symbol)
    val orderId = pos.lastTrailingOrderId
    val isReal = isRealBuyMode.value
    
    if (isReal && !orderId.isNullOrEmpty()) {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isNotBlank() && secretKey.isNotBlank()) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                IndodaxTradeApiV2.cancelOrder(apiKey, secretKey, symbol, orderId)
            }
        }
    }
    positionCoordinator.setTrailing(symbol, enabled = false, pos.trailingPercent, 0.0)
}

fun TradingViewModel.updateRealTrailingOrder(symbol: String, pos: SpotPosition, currentPrice: Double) {
    val oldOrderId = pos.lastTrailingOrderId ?: return
    val apiKey = prefs.indodaxApiKey
    val secretKey = prefs.indodaxSecretKey
    if (apiKey.isBlank() || secretKey.isBlank()) return
    
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        // 1. Cancel order lama
        val (cancelOk, cancelMsg) = IndodaxTradeApiV2.cancelOrder(apiKey, secretKey, symbol, oldOrderId)
        if (!cancelOk) {
            if (cancelMsg.contains("429") || cancelMsg.lowercase().contains("rate limit") || cancelMsg.lowercase().contains("too many requests")) {
                // Fallback ke client-side
                positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, null, System.currentTimeMillis())
                agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
                    context = getApplication(),
                    title = "⚠️ TRAILING FALLBACK ($symbol)",
                    message = "Rate-limit bursa terdeteksi saat cancel. Beralih ke pemantauan client-side.",
                    notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 3000
                )
            }
            return@launch
        }
        
        // Cooldown antar cancel + place order minimal 12–20 detik (kita beri delay pengamanan 2s)
        delay(2000L)
        
        // 2. Pasang order baru di harga SL baru
        val newSlPrice = pos.peakPrice * (1.0 - pos.trailingPercent / 100.0)
        val roundedSlPrice = newSlPrice.toLong().toDouble()
        val clientOrderId = "agu-trailing-${System.currentTimeMillis()}"
        
        val orderResult = IndodaxTradeApiV2.createLimitOrderDetailed(
            apiKey = apiKey,
            secretKey = secretKey,
            symbol = symbol,
            side = "sell",
            price = roundedSlPrice,
            quantity = pos.quantity,
            clientOrderId = clientOrderId
        )
        
        if (orderResult.success) {
            positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, orderResult.orderId, System.currentTimeMillis())
            agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = "📈 TRAILING STOP DIPERBARUI ($symbol)",
                message = "Stop-loss naik ke Rp ${PriceFormatter.formatIdrNumber(roundedSlPrice)}.",
                notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000
            )
        } else {
            if (orderResult.message.contains("429") || orderResult.message.lowercase().contains("rate limit") || orderResult.message.lowercase().contains("too many requests")) {
                // Fallback ke client-side
                positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, null, System.currentTimeMillis())
                agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
                    context = getApplication(),
                    title = "⚠️ TRAILING FALLBACK ($symbol)",
                    message = "Rate-limit bursa terdeteksi saat memasang order baru. Beralih ke pemantauan client-side.",
                    notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 3000
                )
            } else {
                Timber.e("Gagal memasang order trailing baru: ${orderResult.message}")
            }
        }
    }
}
