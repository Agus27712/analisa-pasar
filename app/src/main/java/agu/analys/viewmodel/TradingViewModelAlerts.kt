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
            title = "🚨 PERINGATAN HARGA TURUN ($symbol)",
            message = "Harga menyentuh batas aman di Rp ${PriceFormatter.formatIdrNumber(currentPrice)}. Memproses penjualan otomatis...",
            notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000
        )
        
        // Eksekutor Jual Aktif
        val isSimTrailing = updatedPos.lastTrailingOrderId?.startsWith("sim-") == true
        val isReal = isRealBuyMode.value && !isSimTrailing
        val posQty = updatedPos.quantity
        if (posQty > 0.0) {
            if (isReal) {
                // Client-side trailing trigger for real mode.
                executeAutoSellOrder(symbol, currentPrice, posQty, "TRAILING STOP (CLIENT)", isReal = true)
            } else {
                val simOrderId = updatedPos.lastTrailingOrderId
                val isSimOrderOpen = simOrderId != null && simCoordinator.openOrders.value.any { it.id == simOrderId }
                if (simOrderId != null && !isSimOrderOpen) {
                    // STOP_LIMIT engine akan/sudah mengisi order ini.
                    // Bersihkan posisi lokal
                    positionStore.markSold(symbol)
                    refreshSpotPosition()
                } else {
                    executeAutoSellOrder(symbol, currentPrice, posQty, "TRAILING STOP (SIMULASI)", isReal = false)
                }
            }
        }
    } else if (updatedPos.isTrailingEnabled) {
        if (symbol == selectedPair.value.symbol) {
            refreshSpotPosition()
        }
        
        // Logika update order limit di bursa / sim saat harga naik (new peak)
        val newPeak = updatedPos.peakPrice
        val isSimTrailing = updatedPos.lastTrailingOrderId?.startsWith("sim-") == true || !isRealBuyMode.value
        val isReal = isRealBuyMode.value && !isSimTrailing

        if (newPeak > oldPeak && updatedPos.isHolding) {
            val increasePct = if (oldPeak > 0.0) (newPeak - oldPeak) / oldPeak * 100.0 else 100.0
            val timeSinceLastUpdate = System.currentTimeMillis() - updatedPos.lastOrderUpdateTime
            
            if (isReal) {
                // Bursa Real: Minimal kenaikan Peak: 0.4%, Cooldown: 15s (agar tidak kena rate-limit bursa)
                if (!updatedPos.lastTrailingOrderId.isNullOrEmpty() && increasePct >= 0.4 && timeSinceLastUpdate >= 15_000) {
                    updateRealTrailingOrder(symbol, updatedPos, currentPrice)
                }
            } else {
                // Simulasi: Responsif seketika saat Peak naik (cooldown 1 detik)
                if (timeSinceLastUpdate >= 1_000) {
                    if (updatedPos.lastTrailingOrderId.isNullOrEmpty()) {
                        deployTrailingOrder(symbol)
                    } else {
                        updateSimTrailingOrder(symbol, updatedPos, currentPrice)
                    }
                }
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
        val triggerLabel = if (triggerType.contains("TRAILING")) "Jaring Pengaman" else "Jual Otomatis"
        val notifTitle = if (success) "✅ ASET DIAMANKAN [SIM] ($symbol)" else "❌ GAGAL DIJUAL [SIM] ($symbol)"
        val notifMsg = if (success) {
            "$triggerLabel aktif! Koin terjual di harga ${PriceFormatter.formatIdrNumber(price)} (Simulasi)."
        } else {
            "Gagal (Simulasi): $msg"
        }
        agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = notifTitle,
            message = notifMsg,
            notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 2000
        )
    }
}

fun TradingViewModel.deployTrailingOrder(symbol: String) {
    val pos = positionStore.get(symbol)
    if (!pos.isHolding || !pos.isTrailingEnabled) {
        Timber.e("Gagal pasang trailing order: Status holding = ${pos.isHolding}, Trailing Enabled = ${pos.isTrailingEnabled}")
        // Set a message to UI via _realTradeStatus if we could
        return
    }
    val isReal = isRealBuyMode.value
    agu.analys.service.TradingForegroundService.startService(getApplication<android.app.Application>())
    startTrailingPolling()
    
    if (isReal) {
        // Pure Client-Side Deployment for REAL mode (Indodax doesn't support STOP LIMIT via basic trade API)
        positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, "real-client-trailing", System.currentTimeMillis())
        val slPrice = pos.peakPrice * (1.0 - pos.trailingPercent / 100.0)
        agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
             context = getApplication(),
             title = "🔒 JARING PENGAMAN AKTIF ($symbol)",
             message = "Aplikasi sedang memantau. Koin akan dijual otomatis jika harga turun ke Rp ${PriceFormatter.formatIdrNumber(slPrice)}.",
             notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000
        )
    } else {
        // Simulation mode: submit STOP_LIMIT order in simulation store
        val quantityToSell = if (pos.quantity > 0.0) pos.quantity else {
            val wallet = simCoordinator.wallet.value
            val baseKey = agu.analys.model.TradingPair.fromCustomSymbol(symbol).baseAsset.uppercase()
            wallet.getTotalCoin(baseKey).takeIf { it > 0.0 } ?: (if (pos.investedAmount > 0.0 && pos.entryPrice > 0.0) pos.investedAmount / pos.entryPrice else 0.0)
        }
        
        if (quantityToSell <= 0.0) {
            Timber.e("Gagal pasang trailing order simulasi: Jumlah koin 0 atau belum ada posisi koin di wallet simulasi.")
            return
        }

        val pair = agu.analys.model.TradingPair.fromCustomSymbol(symbol)
        val slPrice = pos.peakPrice * (1.0 - pos.trailingPercent / 100.0)
        val currentPrice = marketDataCoordinator.currentTick.value?.price 
            ?: marketDataCoordinator.dashboardTicks.value[symbol]?.price 
            ?: pos.peakPrice
        
        // Ensure wallet has available coin or add it for simulation so trailing can be placed
        val wallet = simCoordinator.wallet.value
        val baseKey = pair.baseAsset.uppercase()
        if (wallet.getTotalCoin(baseKey) < quantityToSell) {
            // Auto top-up or record holding in simulation wallet so sim trailing works seamlessly
            val store = agu.analys.trading.SimulationTradeStore(getApplication())
            val w = store.getWallet()
            val coins = w.coinBalances.toMutableMap()
            coins[baseKey] = (coins[baseKey] ?: 0.0) + quantityToSell
            val avgs = w.avgBuyPrices.toMutableMap()
            avgs[baseKey] = if (pos.entryPrice > 0.0) pos.entryPrice else currentPrice
            store.saveWallet(w.copy(coinBalances = coins, avgBuyPrices = avgs))
            simCoordinator.refresh()
        }

        val res = simCoordinator.submitOrder(
            pair = pair,
            currentPrice = currentPrice,
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.STOP_LIMIT,
            price = slPrice,
            stopPrice = slPrice,
            quantity = quantityToSell
        )
        if (res is SimulationOrderResult.Success) {
            positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, res.order.id, System.currentTimeMillis())
            agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
                 context = getApplication(),
                 title = "🔒 JARING PENGAMAN AKTIF [SIM] ($symbol)",
                 message = "Aplikasi sedang memantau (Simulasi). Koin akan dijual otomatis di Rp ${PriceFormatter.formatIdrNumber(slPrice)}.",
                 notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000
            )
        } else if (res is SimulationOrderResult.Error) {
            Timber.e("Gagal pasang trailing order simulasi: ${res.message}")
        }
    }
}

fun TradingViewModel.cancelTrailingOrder(symbol: String) {
    val pos = positionStore.get(symbol)
    val orderId = pos.lastTrailingOrderId
    val isSimTrailing = orderId?.startsWith("sim-") == true || !isRealBuyMode.value
    val isReal = isRealBuyMode.value && !isSimTrailing
    
    if (!orderId.isNullOrEmpty()) {
        if (isReal) {
            if (orderId != "real-client-trailing" && !orderId.startsWith("client-trailing")) {
                val apiKey = prefs.indodaxApiKey
                val secretKey = prefs.indodaxSecretKey
                if (apiKey.isNotBlank() && secretKey.isNotBlank()) {
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        IndodaxTradeApiV2.cancelOrder(apiKey, secretKey, symbol, orderId)
                    }
                }
            }
        } else {
            simCoordinator.cancelOrder(orderId)
        }
    }
    positionCoordinator.setTrailing(symbol, enabled = false, pos.trailingPercent, 0.0)
    checkAndStopTrailingServiceIfEmpty()
}

fun TradingViewModel.updateSimTrailingOrder(symbol: String, pos: SpotPosition, currentPrice: Double) {
    val oldOrderId = pos.lastTrailingOrderId ?: return
    simCoordinator.cancelOrder(oldOrderId)
    
    val pair = agu.analys.model.TradingPair.fromCustomSymbol(symbol)
    val slPrice = pos.peakPrice * (1.0 - pos.trailingPercent / 100.0)
    val quantityToSell = if (pos.quantity > 0.0) pos.quantity else {
        val wallet = simCoordinator.wallet.value
        val baseKey = pair.baseAsset.uppercase()
        wallet.getTotalCoin(baseKey).takeIf { it > 0.0 } ?: (if (pos.investedAmount > 0.0 && pos.entryPrice > 0.0) pos.investedAmount / pos.entryPrice else 0.0)
    }

    if (quantityToSell <= 0.0) {
        return
    }

    val res = simCoordinator.submitOrder(
        pair = pair,
        currentPrice = currentPrice,
        side = SimulationOrderSide.SELL,
        type = SimulationOrderType.STOP_LIMIT,
        price = slPrice,
        stopPrice = slPrice,
        quantity = quantityToSell
    )
    if (res is SimulationOrderResult.Success) {
        positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, res.order.id, System.currentTimeMillis())
        agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = "📈 JARING PENGAMAN NAIK [SIM] ($symbol)",
            message = "Batas aman penjualan otomatis naik ke Rp ${PriceFormatter.formatIdrNumber(slPrice)} (Mengikuti harga tertinggi).",
            notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000
        )
    }
}

fun TradingViewModel.updateRealTrailingOrder(symbol: String, pos: SpotPosition, currentPrice: Double) {
    // Pure Client-Side update for REAL mode
    val newSlPrice = pos.peakPrice * (1.0 - pos.trailingPercent / 100.0)
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, "real-client-trailing", System.currentTimeMillis())
    agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = "📈 JARING PENGAMAN NAIK ($symbol)",
        message = "Batas aman penjualan otomatis naik ke Rp ${PriceFormatter.formatIdrNumber(newSlPrice)}.",
        notificationId = (symbol.hashCode() and 0x7FFFFFFF) + 1000
    )
}
