package agu.analys.viewmodel

import agu.analys.model.SignalAction
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SpotPosition
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.launch
import kotlin.math.min

fun TradingViewModel.checkAlertsAndTrailing(symbol: String, currentPrice: Double, rsiValue: Double? = null) {
    if (currentPrice <= 0.0) return
    val (updatedPos, justTriggered) = positionStore.updateTrailingPrice(symbol, currentPrice)
    if (justTriggered) {
        refreshSpotPosition()
        agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = "🚨 TRAILING STOP LOSS TERPICU ($symbol)",
            message = "Harga turun ke ${PriceFormatter.formatIdrNumber(currentPrice)} IDR.",
            notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000
        )
    } else if (updatedPos.isTrailingEnabled && symbol == selectedPair.value.symbol) {
        refreshSpotPosition()
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
        val res = submitSimulationOrder(
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            price = price,
            quantity = quantity
        )
        val success = res is SimulationOrderResult.Success
        val msg = when (res) {
            is SimulationOrderResult.Success -> res.message
            is SimulationOrderResult.Error -> res.message
        }
        if (!isPartial) {
            setOwnership(false)
        } else {
            refreshSpotPosition()
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
