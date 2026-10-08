package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.model.MarketKey
import agu.analys.model.PriceAlert
import agu.analys.model.PriceAlertType
import agu.analys.model.TradingPair
import agu.analys.service.IndodaxTradeApiV2
import agu.analys.service.TokocryptoTradeApi
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

/** Jeda minimal antar order jual otomatis real per pair + jenis pemicu (cegah order ganda tiap tick). */
private val autoSellDispatchTimes = ConcurrentHashMap<String, Long>()
private const val AUTO_SELL_COOLDOWN_MS = 30_000L

/** Alasan keluar & harga acuan jual otomatis; dibaca saat order real sukses agar jurnal akurat. */
internal data class PendingAutoSell(val reason: String, val refPrice: Double, val createdAt: Long)
internal val pendingAutoSells = ConcurrentHashMap<String, PendingAutoSell>()

private fun tryAcquireAutoSell(key: String): Boolean {
    val now = System.currentTimeMillis()
    val last = autoSellDispatchTimes[key]
    if (last != null && now - last < AUTO_SELL_COOLDOWN_MS) return false
    autoSellDispatchTimes[key] = now
    return true
}

fun TradingViewModel.printTrailingDiagnostics(
    symbol: String,
    currentPrice: Double,
    pos: SpotPosition,
    exchange: String = prefs.marketDataSource.name
) {
    if (!pos.isHolding || !pos.isTrailingEnabled) return
    val effectivePct = if (pos.activeTrailingPercent > 0.0) pos.activeTrailingPercent else pos.trailingPercent
    val slPrice = positionStore.calculateTrailingLimitPrice(pos.peakPrice, pos.entryPrice, effectivePct)
    Timber.d("[$exchange:$symbol] Trailing - Current: $currentPrice, Peak: ${pos.peakPrice}, Stop: $slPrice, EffectivePct: $effectivePct%, Enabled: ${pos.isTrailingEnabled}")
}

fun TradingViewModel.checkAlertsAndTrailing(
    symbol: String,
    currentPrice: Double,
    rsi: Double? = null,
    exchange: String = prefs.marketDataSource.name
) {
    val activeIsReal = isRealBuyMode.value
    // Prioritaskan evaluasi trailing untuk mode yang aktif
    checkTrailingForMode(symbol, currentPrice, isReal = activeIsReal, exchange = exchange)
    
    // Evaluasi mode alternatif HANYA jika ada holding aktif dengan trailing di store
    if (!activeIsReal) {
        val realPos = positionStore.get(symbol, isReal = true, exchange = exchange)
        if (realPos.isHolding && realPos.isTrailingEnabled) {
            checkTrailingForMode(symbol, currentPrice, isReal = true, exchange = exchange)
        }
    } else {
        val simPos = positionStore.get(symbol, isReal = false, exchange = exchange)
        if (simPos.isHolding && simPos.isTrailingEnabled) {
            checkTrailingForMode(symbol, currentPrice, isReal = false, exchange = exchange)
        }
    }

    // Price Alerts Trigger Check dengan filter bursa
    val alerts = alertStore.getAlertsForSymbol(symbol, exchange = exchange)
    val marketKey = MarketKey.resolve(symbol, exchange)
    val quoteAsset = marketKey.quote

    for (alert in alerts) {
        if (!alert.isEnabled || alert.isTriggered) continue
        var shouldTrigger = false
        var triggerTitle = ""
        var triggerMsg = ""

        when (alert.type) {
            PriceAlertType.PRICE_ABOVE -> {
                if (currentPrice >= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "🎯 Target Tercapai • ${marketKey.formattedPair()}"
                    triggerMsg = "Harga naik menyentuh ${PriceFormatter.formatPrice(currentPrice, showSymbol = true, quoteAsset = quoteAsset)} (Target: ${PriceFormatter.formatPrice(alert.targetPrice, showSymbol = true, quoteAsset = quoteAsset)})."
                }
            }
            PriceAlertType.PRICE_BELOW -> {
                if (currentPrice <= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "📉 Peringatan Turun • ${marketKey.formattedPair()}"
                    triggerMsg = "Harga turun ke ${PriceFormatter.formatPrice(currentPrice, showSymbol = true, quoteAsset = quoteAsset)} (Target: ${PriceFormatter.formatPrice(alert.targetPrice, showSymbol = true, quoteAsset = quoteAsset)})."
                }
            }
            PriceAlertType.RSI_OVERSOLD -> {
                if (rsi != null && rsi <= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "📊 RSI Oversold • ${marketKey.formattedPair()}"
                    triggerMsg = "Indikator RSI menyentuh ${"%.1f".format(rsi)} (Target: ${alert.targetPrice.toInt()})."
                }
            }
            PriceAlertType.RSI_OVERBOUGHT -> {
                if (rsi != null && rsi >= alert.targetPrice) {
                    shouldTrigger = true
                    triggerTitle = "📊 RSI Overbought • ${marketKey.formattedPair()}"
                    triggerMsg = "Indikator RSI menyentuh ${"%.1f".format(rsi)} (Target: ${alert.targetPrice.toInt()})."
                }
            }
            PriceAlertType.SECOND_WAVE_RECLAIM -> {
                if (currentPrice >= alert.targetPrice && alert.targetPrice > 0.0) {
                    shouldTrigger = true
                    triggerTitle = "🌊 Second-Wave Reclaim • ${marketKey.formattedPair()}"
                    triggerMsg = "Setup Second-Wave terkonfirmasi di harga ${PriceFormatter.formatPrice(currentPrice, showSymbol = true, quoteAsset = quoteAsset)}."
                }
            }
        }
        if (shouldTrigger) {
            alertStore.markTriggered(alert.id)
            refreshPriceAlerts()
            agu.analys.util.AppLogManager.trailing("PriceAlert", "🔔 [${marketKey.formattedPair()}] $triggerTitle: $triggerMsg")
            AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = triggerTitle,
                message = triggerMsg,
                notificationId = alert.id.hashCode() and 0x7FFFFFFF,
                symbol = symbol,
                exchange = exchange,
                quote = quoteAsset
            )
        }
    }
}

fun TradingViewModel.checkTrailingForMode(
    symbol: String,
    currentPrice: Double,
    isReal: Boolean,
    exchange: String = prefs.marketDataSource.name
) {
    val posBeforeUpdate = positionStore.get(symbol, isReal, exchange = exchange)
    if (!posBeforeUpdate.isHolding) return

    val marketKey = MarketKey.resolve(symbol, exchange)
    val quoteAsset = marketKey.quote

    val oldPeak = posBeforeUpdate.peakPrice
    val oldEffectivePct = if (posBeforeUpdate.activeTrailingPercent > 0.0) posBeforeUpdate.activeTrailingPercent else posBeforeUpdate.trailingPercent
    val oldSlPrice = positionStore.calculateTrailingLimitPrice(oldPeak, posBeforeUpdate.entryPrice, oldEffectivePct)
    printTrailingDiagnostics(symbol, currentPrice, posBeforeUpdate, exchange)
    val (updatedPos, justTriggered) = positionStore.updateTrailingPrice(symbol, currentPrice, isReal, exchange = exchange)

    var fullExitDispatched = false
    if (justTriggered) {
        refreshSpotPosition()
        val effectivePct = if (updatedPos.activeTrailingPercent > 0.0) updatedPos.activeTrailingPercent else updatedPos.trailingPercent
        val limitSellPrice = positionStore.calculateTrailingLimitPrice(updatedPos.peakPrice, updatedPos.entryPrice, effectivePct)
        
        val baseKey = marketKey.base.uppercase()
        val baseLower = baseKey.lowercase()
        val posQty = if (updatedPos.quantity > 0.0) updatedPos.quantity else {
            if (isReal) {
                if (marketKey.isTokocrypto) {
                    realCoordinator.realFreeBalance.value[baseLower]
                        ?: realCoordinator.realFreeBalance.value[baseKey]
                        ?: 0.0
                } else {
                    realCoordinator.realIndodaxBalance.value[baseLower]
                        ?: realCoordinator.realIndodaxBalance.value[baseKey]
                        ?: 0.0
                }
            } else simCoordinator.wallet.value.getAvailableCoin(baseKey)
        }

        agu.analys.util.AppLogManager.trailing(
            "TrailingHit",
            "🚨 [${marketKey.formattedPair()}] Trailing Stop Terpicu! Harga ${PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset)} menyentuh Stop Limit ${PriceFormatter.formatPrice(limitSellPrice, quoteAsset = quoteAsset)} (Peak: ${PriceFormatter.formatPrice(updatedPos.peakPrice, quoteAsset = quoteAsset)}). Meluncurkan auto-sell $posQty koin (${if (isReal) "REAL" else "SIMULASI"})."
        )

        if (posQty > 0.0) {
            AlertNotificationHelper.sendTrailingHitNotification(
                context = getApplication(),
                marketKey = marketKey,
                entryPrice = updatedPos.entryPrice,
                peakPrice = updatedPos.peakPrice,
                currentPrice = currentPrice,
                limitSellPrice = limitSellPrice,
                quantity = posQty,
                isReal = isReal
            )
            // Eksekusi auto-sell jaring pengaman otomatis
            fullExitDispatched = executeAutoSellOrder(symbol, limitSellPrice, posQty, "TRAILING", isReal, isPartial = false, exchange = exchange)
        }
    } else if (updatedPos.isHolding && updatedPos.isTrailingEnabled && updatedPos.peakPrice > oldPeak) {
        val effectivePct = if (updatedPos.activeTrailingPercent > 0.0) updatedPos.activeTrailingPercent else updatedPos.trailingPercent
        val newSlPrice = positionStore.calculateTrailingLimitPrice(updatedPos.peakPrice, updatedPos.entryPrice, effectivePct)
        // NOTIFIKASI HANYA DIKIRIM JIKA BATAS AMAN (STOP LIMIT) BENAR-BENAR NAIK
        if (newSlPrice > oldSlPrice) {
            refreshSpotPosition()
            if (isReal) {
                updateRealTrailingOrder(symbol, updatedPos, newSlPrice, exchange = exchange)
            } else {
                updateSimTrailingOrder(symbol, updatedPos, newSlPrice, updatedPos.quantity, exchange = exchange)
            }

            val currentProfitPct = if (updatedPos.entryPrice > 0.0) ((updatedPos.peakPrice - updatedPos.entryPrice) / updatedPos.entryPrice) * 100.0 else 0.0
            agu.analys.util.AppLogManager.trailing(
                "TrailingAdjust",
                "🛡️ [${marketKey.formattedPair()}] Peak baru: ${PriceFormatter.formatPrice(updatedPos.peakPrice, quoteAsset = quoteAsset)} (naik dari ${PriceFormatter.formatPrice(oldPeak, quoteAsset = quoteAsset)}). Stop limit dinaikkan ke ${PriceFormatter.formatPrice(newSlPrice, quoteAsset = quoteAsset)} (-${effectivePct}%, Profit Peak: +${String.format(java.util.Locale.US, "%.2f", currentProfitPct)}%)"
            )

            // Notifikasi Trailing Peak Naik: Diberi jeda cerdas (minimal 30 detik antar notifikasi per koin)
            val now = System.currentTimeMillis()
            val peakKey = "${exchange}_${symbol}_$isReal"
            val lastAlertTime = lastPeakNotificationTimes[peakKey] ?: 0L
            if (now - lastAlertTime > 30_000L) {
                lastPeakNotificationTimes[peakKey] = now
                AlertNotificationHelper.sendTrailingPeakUpdateNotification(
                    context = getApplication(),
                    marketKey = marketKey,
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
    // Lewati bila trailing baru saja menjual semua (hindari jual ganda di tick yang sama).
    if (!fullExitDispatched && updatedPos.isHolding && updatedPos.isAutoSellEnabled) {
        var remainingQty = updatedPos.quantity
        // TP1 (flag baru ditandai setelah order benar-benar dikirim)
        if (remainingQty > 0.0 && !updatedPos.isTp1Triggered && updatedPos.tp1Price > 0.0 && currentPrice >= updatedPos.tp1Price) {
            val sellQty = remainingQty * (updatedPos.tp1Percent / 100.0)
            val dispatched = sellQty > 0.0 &&
                executeAutoSellOrder(symbol, currentPrice, sellQty, "TP1", isReal, isPartial = updatedPos.tp1Percent < 100.0, exchange = exchange)
            if (dispatched) {
                positionStore.markTp1Triggered(symbol, isReal, exchange = exchange)
                refreshSpotPosition()
                agu.analys.util.AppLogManager.trailing("AutoSellTP", "🎯 [${marketKey.formattedPair()}] Target TP1 tercapai di ${PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset)} (Target: ${PriceFormatter.formatPrice(updatedPos.tp1Price, quoteAsset = quoteAsset)})")
                remainingQty -= sellQty
            }
        }
        // TP2: dihitung dari sisa setelah TP1 di tick yang sama
        if (remainingQty > 0.0 && !updatedPos.isTp2Triggered && updatedPos.tp2Price > 0.0 && currentPrice >= updatedPos.tp2Price) {
            val sellQty = remainingQty * (updatedPos.tp2Percent / 100.0)
            val dispatched = sellQty > 0.0 &&
                executeAutoSellOrder(symbol, currentPrice, sellQty, "TP2", isReal, isPartial = updatedPos.tp2Percent < 100.0, exchange = exchange)
            if (dispatched) {
                positionStore.markTp2Triggered(symbol, isReal, exchange = exchange)
                refreshSpotPosition()
                agu.analys.util.AppLogManager.trailing("AutoSellTP", "🎯 [${marketKey.formattedPair()}] Target TP2 tercapai di ${PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset)} (Target: ${PriceFormatter.formatPrice(updatedPos.tp2Price, quoteAsset = quoteAsset)})")
                remainingQty -= sellQty
            }
        }
        // Stop Loss: dijaga cooldown di executeAutoSellOrder (tidak menembak order tiap tick)
        if (remainingQty > 0.0 && updatedPos.stopLossPrice > 0.0 && currentPrice <= updatedPos.stopLossPrice) {
            val dispatched = executeAutoSellOrder(symbol, currentPrice, remainingQty, "STOP_LOSS", isReal, isPartial = false, exchange = exchange)
            if (dispatched) {
                refreshSpotPosition()
                agu.analys.util.AppLogManager.trailing("AutoSellSL", "⚠️ [${marketKey.formattedPair()}] Stop Loss tercapai di ${PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset)} (Batas: ${PriceFormatter.formatPrice(updatedPos.stopLossPrice, quoteAsset = quoteAsset)})")
            }
        }
    }
}

fun TradingViewModel.executeAutoSellOrder(
    symbol: String,
    price: Double,
    quantity: Double,
    triggerType: String,
    isReal: Boolean,
    isPartial: Boolean = false,
    exchange: String = prefs.marketDataSource.name
): Boolean {
    // Penjaga order ganda (real): TP1 dan TP2 punya kunci sendiri; Trailing & Stop Loss berbagi kunci "FULL_EXIT".
    val normSym = symbol.uppercase().replace("_", "")
    val isTpTrigger = triggerType == "TP1" || triggerType == "TP2"
    val guardKey = "${exchange.uppercase()}_${normSym}_${if (isTpTrigger) triggerType else "FULL_EXIT"}"
    if (isReal && !tryAcquireAutoSell(guardKey)) {
        // Ditolak karena baru saja ada order jual sejenis; trailing harus bisa dicoba lagi nanti.
        if (triggerType.contains("TRAILING")) {
            positionStore.resetTrailingTrigger(symbol, isReal = true, exchange = exchange)
        }
        return false
    }

    val modeTag = if (isReal) "REAL" else "SIMULASI"
    val triggerLabel = when {
        triggerType.contains("TRAILING") -> "Trailing Stop Terpicu"
        triggerType.contains("STOP_LOSS") -> "Stop Loss / Cut Loss"
        triggerType.contains("TP1") -> "Target Profit 1 (TP1)"
        triggerType.contains("TP2") -> "Target Profit 2 (TP2)"
        else -> "Jual Otomatis"
    }

    val marketKey = MarketKey.resolve(symbol, exchange)
    val quoteAsset = marketKey.quote

    agu.analys.util.AppLogManager.trade(
        "AutoSellExec",
        "⚡ [${marketKey.formattedPair()}] Eksekusi $triggerLabel ($modeTag) | Qty: $quantity @ ${PriceFormatter.formatPrice(price, quoteAsset = quoteAsset)}"
    )

    if (isReal) {
        // Diskon 5% dari harga terkini agar berfungsi layaknya Market Sell di orderbook
        val marketSellPrice = price * 0.95
        // Simpan alasan & harga acuan agar jurnal mencatat pemicu yang benar (bukan harga diskon 95%)
        val pendingKey = "${exchange.uppercase()}_$normSym"
        pendingAutoSells[pendingKey] = PendingAutoSell(
            reason = when {
                triggerType.contains("TRAILING") -> "TRAILING_STOP"
                triggerType.contains("STOP_LOSS") -> "STOP_LOSS"
                triggerType == "TP1" -> "HIT_TP1"
                triggerType == "TP2" -> "HIT_TP2"
                else -> "AUTO_SELL"
            },
            refPrice = price,
            createdAt = System.currentTimeMillis()
        )
        executeRealTrade(symbol, "sell", marketSellPrice, quantity, 0.0, 0.0, exchange = exchange) { success, msg ->
            if (!success) {
                pendingAutoSells.remove(pendingKey)
                when {
                    triggerType.contains("TRAILING") -> positionStore.resetTrailingTrigger(symbol, isReal = true, exchange = exchange)
                    triggerType == "TP1" -> positionStore.resetTp1Trigger(symbol, isReal = true, exchange = exchange)
                    triggerType == "TP2" -> positionStore.resetTp2Trigger(symbol, isReal = true, exchange = exchange)
                }
            }
            val notifTitle = if (success) {
                if (triggerType.contains("STOP_LOSS")) "🛡️ [$modeTag] Cut Loss Terlaksana • ${marketKey.formattedPair()}"
                else "✅ [$modeTag] Aset Diamankan ($triggerLabel) • ${marketKey.formattedPair()}"
            } else "❌ [$modeTag] Gagal Jual • ${marketKey.formattedPair()}"

            val notifMsg = if (success) {
                val formattedPrice = PriceFormatter.formatPrice(price, showSymbol = true, quoteAsset = quoteAsset)
                "$triggerLabel [$modeTag] aktif! Koin berhasil dieksekusi di kisaran harga $formattedPrice."
            } else {
                "Sistem gagal mengeksekusi order [$modeTag]: $msg"
            }
            AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = notifTitle,
                message = notifMsg,
                notificationId = marketKey.toNotificationId(isReal, 2000),
                marketKey = marketKey
            )
        }
    } else {
        val pair = TradingPair.fromCustomSymbol(raw = symbol, exchange = exchange)
        val simBal = simCoordinator.wallet.value.getAvailableCoin(pair.baseAsset)
        val finalSellQty = if (!isPartial) (if (simBal > 0.0) simBal else quantity) else quantity.coerceAtMost(simBal)
        if (finalSellQty <= 0.0) {
            positionCoordinator.setOwnership(symbol, false, price, isReal = false)
            return false
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
            positionStore.resetTrailingTrigger(symbol, isReal = false, exchange = exchange)
        }
        val notifTitle = if (success) {
            if (triggerType.contains("STOP_LOSS")) "🛡️ [$modeTag] Cut Loss Terlaksana • ${marketKey.formattedPair()}"
            else "✅ [$modeTag] Aset Diamankan ($triggerLabel) • ${marketKey.formattedPair()}"
        } else "❌ [$modeTag] Gagal Jual • ${marketKey.formattedPair()}"

        val notifMsg = if (success) {
            val formattedPrice = PriceFormatter.formatPrice(price, showSymbol = true, quoteAsset = quoteAsset)
            "$triggerLabel [$modeTag] aktif! Koin terjual di harga $formattedPrice."
        } else {
            "Gagal (Simulasi): $msg"
        }
        AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = notifTitle,
            message = notifMsg,
            notificationId = marketKey.toNotificationId(isReal, 2000),
            marketKey = marketKey
        )
    }
    return true
}

fun TradingViewModel.deployTrailingOrder(symbol: String, exchange: String = prefs.marketDataSource.name) {
    val isReal = isRealBuyMode.value
    var pos = positionStore.get(symbol, isReal, exchange = exchange)
    val marketKey = MarketKey.resolve(symbol, exchange)
    val baseKey = marketKey.base.uppercase()
    
    val currentPrice = (if (marketDataCoordinator.currentTick.value?.symbol?.equals(symbol, ignoreCase = true) == true) marketDataCoordinator.currentTick.value?.price else null)
        ?: marketDataCoordinator.dashboardTicks.value[symbol]?.price
        ?: marketDataCoordinator.dashboardTicks.value[marketKey.symbol]?.price
        ?: (if (pos.peakPrice > 0.0) pos.peakPrice else pos.entryPrice)

    // Auto sync holding position if in Simulation mode and wallet has coin
    if (!isReal) {
        val simCoin = simCoordinator.wallet.value.getTotalCoin(baseKey)
        if (simCoin > 0.0 && (!pos.isHolding || pos.quantity <= 0.0)) {
            val entryP = if (pos.entryPrice > 0.0) pos.entryPrice else currentPrice
            positionStore.setHolding(symbol, invested = simCoin * entryP, entry = entryP, quantity = simCoin, isReal = false, exchange = exchange)
            pos = positionStore.get(symbol, isReal = false, exchange = exchange)
        }
    } else {
        val baseLower = baseKey.lowercase()
        val realCoin = if (marketKey.isTokocrypto) {
            realCoordinator.realFreeBalance.value[baseLower]
                ?: realCoordinator.realFreeBalance.value[baseKey]
                ?: 0.0
        } else {
            realCoordinator.realIndodaxBalance.value[baseLower]
                ?: realCoordinator.realIndodaxBalance.value[baseKey]
                ?: 0.0
        }
        if (realCoin > 0.0 && (!pos.isHolding || pos.quantity <= 0.0)) {
            val entryP = if (pos.entryPrice > 0.0) pos.entryPrice
                else (realCoordinator.realAvgBuyPrices.value[symbol]
                    ?: realCoordinator.realAvgBuyPrices.value[baseLower]
                    ?: realCoordinator.realAvgBuyPrices.value[baseKey]
                    ?: currentPrice)
            positionStore.setHolding(symbol, invested = realCoin * entryP, entry = entryP, quantity = realCoin, isReal = true, exchange = exchange)
            pos = positionStore.get(symbol, isReal = true, exchange = exchange)
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
        isReal = isReal,
        exchange = exchange
    )
    
    val trailingOrderId = if (isReal) "real-client-trailing" else "sim-client-trailing"
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, trailingOrderId, System.currentTimeMillis(), isReal = isReal)
    positionCoordinator.refreshPosition(symbol)

    updateForegroundServiceState()
    startTrailingPolling()

    val initialEffectivePct = if (pos.activeTrailingPercent > 0.0) pos.activeTrailingPercent else effectiveTrailingPct
    val slPrice = positionStore.calculateTrailingLimitPrice(effectivePeak, pos.entryPrice, initialEffectivePct)
    val notifQuoteAsset = marketKey.quote
    val notifTitle = if (isReal) "🛡️ [REAL] Trailing Stop Aktif • ${marketKey.formattedPair()}" else "🛡️ [SIMULASI] Trailing Stop Aktif • ${marketKey.formattedPair()}"
    val formattedSl = PriceFormatter.formatPrice(slPrice, showSymbol = true, quoteAsset = notifQuoteAsset)
    val notifMsg = if (isReal) {
        "Aplikasi sedang memantau [REAL - ${marketKey.exchange}]. Koin akan dijual otomatis jika harga turun ke $formattedSl."
    } else {
        "Aplikasi sedang memantau [SIMULASI - ${marketKey.exchange}]. Koin akan dijual otomatis di $formattedSl."
    }

    AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = notifTitle,
        message = notifMsg,
        notificationId = marketKey.toNotificationId(isReal, 1000),
        marketKey = marketKey
    )
    Timber.d("[$exchange:$symbol] Trailing order deployed successfully ($trailingOrderId) @ peak=$effectivePeak, stop=$slPrice (isReal=$isReal)")
}

fun TradingViewModel.cancelTrailingOrder(symbol: String, exchange: String = prefs.marketDataSource.name) {
    val isReal = isRealBuyMode.value
    val pos = positionStore.get(symbol, isReal, exchange = exchange)
    val orderId = pos.lastTrailingOrderId
    
    if (!orderId.isNullOrEmpty()) {
        if (isReal) {
            if (orderId != "real-client-trailing" && !orderId.startsWith("client-trailing")) {
                executeCancelRealOrder(symbol, orderId) { _, _ -> }
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

fun TradingViewModel.updateSimTrailingOrder(
    symbol: String,
    pos: SpotPosition,
    slPrice: Double,
    quantityToSell: Double,
    exchange: String = prefs.marketDataSource.name
) {
    val marketKey = MarketKey.resolve(symbol, exchange)
    val quoteAsset = marketKey.quote
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, "sim-client-trailing", System.currentTimeMillis(), isReal = false)
    AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = "📈 [SIMULASI] Trailing Stop Naik • ${marketKey.formattedPair()}",
        message = "Batas aman penjualan otomatis naik ke ${PriceFormatter.formatPrice(slPrice, showSymbol = true, quoteAsset = quoteAsset)} (Mengikuti kenaikan harga).",
        notificationId = marketKey.toNotificationId(false, 1000),
        marketKey = marketKey,
        onlyWhenBackground = true
    )
}

fun TradingViewModel.updateRealTrailingOrder(
    symbol: String,
    pos: SpotPosition,
    newSlPrice: Double,
    exchange: String = prefs.marketDataSource.name
) {
    val marketKey = MarketKey.resolve(symbol, exchange)
    val quoteAsset = marketKey.quote
    // Pure Client-Side update for REAL mode
    positionCoordinator.setTrailingOrderIdAndUpdateTime(symbol, "real-client-trailing", System.currentTimeMillis(), isReal = true)
    AlertNotificationHelper.sendPriceAlertNotification(
        context = getApplication(),
        title = "📈 [REAL] Trailing Stop Naik • ${marketKey.formattedPair()}",
        message = "Batas aman penjualan otomatis naik ke ${PriceFormatter.formatPrice(newSlPrice, showSymbol = true, quoteAsset = quoteAsset)}.",
        notificationId = marketKey.toNotificationId(true, 1000),
        marketKey = marketKey,
        onlyWhenBackground = true
    )
}

fun TradingViewModel.executeTrailingSellLimitOrder(
    symbol: String,
    limitPrice: Double,
    quantity: Double,
    isReal: Boolean,
    exchange: String = prefs.marketDataSource.name
) {
    val marketKey = MarketKey.resolve(symbol, exchange)
    val quoteAsset = marketKey.quote
    if (isReal) {
        viewModelScope.launch(Dispatchers.IO) {
            val latestTick = marketDataCoordinator.dashboardTicks.value[symbol]
                ?: marketDataCoordinator.dashboardTicks.value[marketKey.symbol]
            val execPrice = latestTick?.price ?: limitPrice
            val discountedPrice = execPrice * 0.95

            // Eksekusi rute bursa yang benar melalui executeRealTrade (otomatis menangani Tokocrypto vs Indodax)
            executeRealTrade(symbol, "sell", discountedPrice, quantity, 0.0, 0.0, exchange = exchange) { success, msg ->
                if (!success) {
                    positionStore.resetTrailingTrigger(symbol, isReal = true, exchange = exchange)
                } else {
                    refreshRealBalance()
                }

                val notifTitle = if (success) "✅ [REAL] Trailing Sell Terlaksana • ${marketKey.formattedPair()}" else "❌ [REAL] Gagal Trailing Sell • ${marketKey.formattedPair()}"
                val notifMsg = if (success) {
                    val formattedExec = PriceFormatter.formatPrice(execPrice, showSymbol = true, quoteAsset = quoteAsset)
                    "Profit Lock [REAL - ${marketKey.exchange}] aktif! Koin berhasil dieksekusi di kisaran harga $formattedExec."
                } else {
                    "Sistem gagal mengeksekusi order [REAL - ${marketKey.exchange}]: $msg"
                }
                AlertNotificationHelper.sendPriceAlertNotification(
                    context = getApplication(),
                    title = notifTitle,
                    message = notifMsg,
                    notificationId = marketKey.toNotificationId(true, 3000),
                    marketKey = marketKey
                )
            }
        }
    } else {
        // Wallet simulasi dipegang per bursa dan coordinator hanya melayani bursa aktif.
        if (!exchange.equals(prefs.marketDataSource.name, ignoreCase = true)) {
            AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                title = "⚠️ [SIMULASI] Jual dibatalkan • ${marketKey.formattedPair()}",
                message = "Dompet simulasi ${marketKey.displayExchange} sedang tidak aktif. " +
                    "Pindah ke ${marketKey.displayExchange} di Pengaturan, lalu jual dari Portofolio.",
                notificationId = marketKey.toNotificationId(false, 3000),
                marketKey = marketKey
            )
            return
        }
        val pair = TradingPair.fromCustomSymbol(raw = symbol, exchange = exchange)
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
            positionStore.resetTrailingTrigger(symbol, isReal = false, exchange = exchange)
        }
        val notifTitle = if (success) "✅ [SIMULASI] Limit Sell Terpasang • ${marketKey.formattedPair()}" else "❌ [SIMULASI] Gagal Limit Sell • ${marketKey.formattedPair()}"
        val notifMsg = if (success) {
            val formattedLimit = PriceFormatter.formatPrice(limitPrice, showSymbol = true, quoteAsset = quoteAsset)
            "Profit Lock [SIMULASI] aktif! Limit Sell Order dipasang di $formattedLimit."
        } else {
            "Gagal (Simulasi): $msg"
        }
        AlertNotificationHelper.sendPriceAlertNotification(
            context = getApplication(),
            title = notifTitle,
            message = notifMsg,
            notificationId = marketKey.toNotificationId(false, 3000),
            marketKey = marketKey
        )
    }
}
