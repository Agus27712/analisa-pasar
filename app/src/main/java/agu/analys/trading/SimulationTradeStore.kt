package agu.analys.trading

import android.content.Context
import org.json.JSONArray
import java.util.UUID

class SimulationTradeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simulation_trade_prefs", Context.MODE_PRIVATE)

    companion object {
        const val INDODAX_MAKER_FEE_RATE = 0.001 // 0.1%
        const val INDODAX_TAKER_FEE_RATE = 0.003 // 0.3%
        const val TOKOCRYPTO_MAKER_FEE_RATE = 0.001 // 0.1%
        const val TOKOCRYPTO_TAKER_FEE_RATE = 0.001 // 0.1%
    }

    private fun getWalletKey(exchange: String): String = "${exchange.trim().lowercase()}_sim_wallet"
    private fun getOrdersKey(exchange: String): String = "${exchange.trim().lowercase()}_sim_open_orders"
    private fun getHistoryKey(exchange: String): String = "${exchange.trim().lowercase()}_sim_trade_history"

    @Synchronized
    fun getWallet(exchange: String = "TOKOCRYPTO"): SimulationWallet {
        val exKey = getWalletKey(exchange)
        val raw = prefs.getString(exKey, null) ?: prefs.getString("sim_wallet", null)
        return SimulationTradeJson.walletFromJson(raw)
    }

    @Synchronized
    fun saveWallet(wallet: SimulationWallet, exchange: String = "TOKOCRYPTO") {
        val json = SimulationTradeJson.walletToJson(wallet)
        val exKey = getWalletKey(exchange)
        prefs.edit().putString(exKey, json.toString()).apply()
    }

    @Synchronized
    fun topUpIdr(amount: Double, exchange: String = "TOKOCRYPTO") {
        val w = getWallet(exchange)
        val updated = w.copy(idrBalance = w.idrBalance + amount.coerceAtLeast(0.0))
        saveWallet(updated, exchange)
    }

    @Synchronized
    fun setBalance(amount: Double, exchange: String = "TOKOCRYPTO") {
        val w = getWallet(exchange)
        val updated = w.copy(idrBalance = amount.coerceAtLeast(0.0))
        saveWallet(updated, exchange)
    }

    @Synchronized
    fun resetWallet(initialIdr: Double = 10_000_000.0, exchange: String = "TOKOCRYPTO") {
        saveWallet(SimulationWallet(idrBalance = initialIdr), exchange)
        val ordersKey = getOrdersKey(exchange)
        val historyKey = getHistoryKey(exchange)
        prefs.edit().remove(ordersKey).remove(historyKey).apply()
    }

    @Synchronized
    fun getOpenOrders(exchange: String = "TOKOCRYPTO", symbolFilter: String? = null): List<SimulationOrder> {
        val exKey = getOrdersKey(exchange)
        val raw = prefs.getString(exKey, null) ?: prefs.getString("sim_open_orders", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<SimulationOrder>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sym = obj.optString("symbol", "")
                if (symbolFilter != null && !sym.equals(symbolFilter, true)) continue
                list.add(SimulationTradeJson.orderFromJson(obj))
            }
            list.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getOpenOrders(symbolFilter: String?): List<SimulationOrder> =
        getOpenOrders("TOKOCRYPTO", symbolFilter)

    @Synchronized
    fun getOpenOrders(): List<SimulationOrder> =
        getOpenOrders("TOKOCRYPTO", null)

    @Synchronized
    private fun saveOpenOrders(orders: List<SimulationOrder>, exchange: String = "TOKOCRYPTO") {
        val array = JSONArray()
        orders.filter { it.status == SimulationOrderStatus.OPEN }.forEach {
            array.put(SimulationTradeJson.orderToJson(it))
        }
        val exKey = getOrdersKey(exchange)
        prefs.edit().putString(exKey, array.toString()).apply()
    }

    @Synchronized
    fun getTradeHistory(exchange: String = "TOKOCRYPTO", symbolFilter: String? = null): List<SimulationTradeHistoryItem> {
        val exKey = getHistoryKey(exchange)
        val raw = prefs.getString(exKey, null) ?: prefs.getString("sim_trade_history", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<SimulationTradeHistoryItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sym = obj.optString("symbol", "")
                if (symbolFilter != null && !sym.equals(symbolFilter, true)) continue
                list.add(SimulationTradeJson.historyFromJson(obj))
            }
            list.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getTradeHistory(symbolFilter: String?): List<SimulationTradeHistoryItem> =
        getTradeHistory("TOKOCRYPTO", symbolFilter)

    @Synchronized
    fun getTradeHistory(): List<SimulationTradeHistoryItem> =
        getTradeHistory("TOKOCRYPTO", null)

    @Synchronized
    private fun addTradeHistory(item: SimulationTradeHistoryItem, exchange: String = "TOKOCRYPTO") {
        val current = getTradeHistory(exchange).toMutableList()
        current.add(0, item)
        if (current.size > 100) {
            current.removeAt(current.lastIndex)
        }
        val array = JSONArray()
        current.forEach { array.put(SimulationTradeJson.historyToJson(it)) }
        val exKey = getHistoryKey(exchange)
        prefs.edit().putString(exKey, array.toString()).apply()
    }

    @Synchronized
    fun recordMirroredRealTrade(
        symbol: String,
        baseAsset: String,
        quoteAsset: String = "IDR",
        side: SimulationOrderSide,
        price: Double,
        quantity: Double,
        pnlIdr: Double? = null,
        pnlPercent: Double? = null,
        exchange: String = "TOKOCRYPTO"
    ) {
        val totalIdr = quantity * price
        val feeRate = if (exchange.equals("INDODAX", true)) INDODAX_TAKER_FEE_RATE else TOKOCRYPTO_TAKER_FEE_RATE
        val feeIdr = totalIdr * feeRate
        val item = SimulationTradeHistoryItem(
            id = UUID.randomUUID().toString(),
            orderId = "real-${System.currentTimeMillis()}",
            symbol = symbol,
            baseAsset = baseAsset.uppercase(),
            quoteAsset = quoteAsset,
            side = side,
            type = SimulationOrderType.MARKET,
            executionPrice = price,
            quantity = quantity,
            totalIdr = totalIdr,
            feeIdr = feeIdr,
            timestamp = System.currentTimeMillis(),
            pnlIdr = pnlIdr,
            pnlPercent = pnlPercent,
            isRealMirror = true
        )
        addTradeHistory(item, exchange)
    }

    @Synchronized
    fun placeOrder(
        symbol: String,
        baseAsset: String,
        quoteAsset: String = "IDR",
        side: SimulationOrderSide,
        type: SimulationOrderType,
        price: Double,
        stopPrice: Double = 0.0,
        quantity: Double,
        currentMarketPrice: Double,
        exchange: String = "TOKOCRYPTO"
    ): SimulationOrderResult {
        if (quantity <= 0.0) return SimulationOrderResult.Error("Jumlah koin harus lebih besar dari 0.")
        val wallet = getWallet(exchange)
        val baseKey = baseAsset.uppercase()
        val quote = quoteAsset.ifBlank { "IDR" }

        when (type) {
            SimulationOrderType.MARKET -> {
                val execPrice = if (currentMarketPrice > 0.0) currentMarketPrice else price
                if (execPrice <= 0.0) return SimulationOrderResult.Error("Harga pasar realtime belum tersedia.")

                if (side == SimulationOrderSide.BUY) {
                    val result = SimulationOrderEngine.executeMarketBuy(wallet, symbol, baseKey, quote, execPrice, quantity)
                    return result.fold(
                        onSuccess = { res ->
                            saveWallet(res.updatedWallet, exchange)
                            addTradeHistory(res.historyItem, exchange)
                            agu.analys.util.AppLogManager.trade(
                                "SimOrder",
                                "💼 [SIMULASI BUY] Berhasil beli $quantity $baseKey @ Rp ${formatMoney(execPrice, quote)} | Total: Rp ${formatMoney(quantity * execPrice, quote)} | Sisa Saldo: Rp ${formatMoney(res.updatedWallet.idrBalance, quote)}"
                            )
                            SimulationOrderResult.Success(res.completedOrder, "Market Buy berhasil @ ${formatMoney(execPrice, quote)}!")
                        },
                        onFailure = { err ->
                            agu.analys.util.AppLogManager.warn("SimOrder", "Gagal simulasi Buy $symbol: ${err.message}")
                            SimulationOrderResult.Error(err.message ?: "Gagal memproses Market Buy.")
                        }
                    )
                } else {
                    val result = SimulationOrderEngine.executeMarketSell(wallet, symbol, baseKey, quote, execPrice, quantity)
                    return result.fold(
                        onSuccess = { res ->
                            saveWallet(res.updatedWallet, exchange)
                            addTradeHistory(res.historyItem, exchange)
                            val pnlStr = if (res.historyItem.pnlIdr != null) {
                                val sign = if (res.historyItem.pnlIdr!! >= 0) "+" else ""
                                " | PnL: $sign Rp ${formatMoney(res.historyItem.pnlIdr!!, quote)} (${String.format(java.util.Locale.US, "%.2f", res.historyItem.pnlPercent ?: 0.0)}%)"
                            } else ""
                            agu.analys.util.AppLogManager.trade(
                                "SimOrder",
                                "💼 [SIMULASI SELL] Berhasil jual $quantity $baseKey @ Rp ${formatMoney(execPrice, quote)} | Hasil: Rp ${formatMoney(quantity * execPrice, quote)}$pnlStr"
                            )
                            SimulationOrderResult.Success(res.completedOrder, "Market Sell berhasil @ ${formatMoney(execPrice, quote)}!")
                        },
                        onFailure = { err ->
                            agu.analys.util.AppLogManager.warn("SimOrder", "Gagal simulasi Sell $symbol: ${err.message}")
                            SimulationOrderResult.Error(err.message ?: "Gagal memproses Market Sell.")
                        }
                    )
                }
            }

            SimulationOrderType.LIMIT, SimulationOrderType.STOP_LIMIT -> {
                if (price <= 0.0) return SimulationOrderResult.Error("Harga Limit harus lebih besar dari 0.")
                val totalIdr = quantity * price
                val feeRate = if (exchange.equals("INDODAX", true)) INDODAX_MAKER_FEE_RATE else TOKOCRYPTO_MAKER_FEE_RATE
                val feeIdr = totalIdr * feeRate

                if (side == SimulationOrderSide.BUY) {
                    val requiredIdr = totalIdr + feeIdr
                    if (wallet.getAvailableIdr() < requiredIdr) {
                        return SimulationOrderResult.Error(
                            "Saldo $quote tidak cukup untuk limit order. Tersedia: ${formatMoney(wallet.getAvailableIdr(), quote)}"
                        )
                    }

                    val updatedWallet = wallet.copy(
                        lockedIdr = wallet.lockedIdr + requiredIdr
                    )
                    saveWallet(updatedWallet, exchange)

                    val order = SimulationOrder(
                        id = UUID.randomUUID().toString(),
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quote,
                        side = side,
                        type = type,
                        limitPrice = price,
                        stopPrice = stopPrice,
                        quantity = quantity,
                        totalIdr = totalIdr,
                        feeIdr = feeIdr,
                        status = SimulationOrderStatus.OPEN,
                        isStopTriggered = type == SimulationOrderType.LIMIT
                    )

                    val openList = getOpenOrders(exchange).toMutableList()
                    openList.add(order)
                    saveOpenOrders(openList, exchange)

                    processPriceTick(symbol, currentMarketPrice, currentMarketPrice, currentMarketPrice, exchange)

                    return SimulationOrderResult.Success(order, "Order ${type.displayName} Beli dipasang @ ${formatMoney(price, quote)}.")
                } else {
                    val availableCoin = wallet.getAvailableCoin(baseKey)
                    val actualQuantity = if (quantity > availableCoin && (quantity - availableCoin < 0.001 || (quantity - availableCoin) / availableCoin.coerceAtLeast(0.0001) < 0.001)) {
                        availableCoin
                    } else {
                        quantity
                    }

                    if (availableCoin < actualQuantity) {
                        return SimulationOrderResult.Error("Saldo koin $baseKey tidak cukup. Tersedia: $availableCoin")
                    }

                    val lockedMap = wallet.lockedCoinBalances.toMutableMap()
                    lockedMap[baseKey] = (lockedMap[baseKey] ?: 0.0) + actualQuantity
                    val updatedWallet = wallet.copy(lockedCoinBalances = lockedMap)
                    saveWallet(updatedWallet, exchange)

                    val order = SimulationOrder(
                        id = UUID.randomUUID().toString(),
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quote,
                        side = side,
                        type = type,
                        limitPrice = price,
                        stopPrice = stopPrice,
                        quantity = actualQuantity,
                        totalIdr = totalIdr,
                        feeIdr = feeIdr,
                        status = SimulationOrderStatus.OPEN,
                        isStopTriggered = type == SimulationOrderType.LIMIT
                    )

                    val openList = getOpenOrders(exchange).toMutableList()
                    openList.add(order)
                    saveOpenOrders(openList, exchange)

                    processPriceTick(symbol, currentMarketPrice, currentMarketPrice, currentMarketPrice, exchange)

                    return SimulationOrderResult.Success(order, "Order ${type.displayName} Jual dipasang @ ${formatMoney(price, quote)}.")
                }
            }
        }
    }

    @Synchronized
    fun cancelOrder(orderId: String, exchange: String = "TOKOCRYPTO"): Boolean {
        val openOrders = getOpenOrders(exchange).toMutableList()
        val index = openOrders.indexOfFirst { it.id == orderId }
        if (index == -1) return false
        val order = openOrders.removeAt(index)

        val wallet = getWallet(exchange)
        val baseKey = order.baseAsset.uppercase()

        val updatedWallet = if (order.side == SimulationOrderSide.BUY) {
            val lockedTotal = order.totalIdr + order.feeIdr
            wallet.copy(lockedIdr = (wallet.lockedIdr - lockedTotal).coerceAtLeast(0.0))
        } else {
            val lockedMap = wallet.lockedCoinBalances.toMutableMap()
            val currentLocked = lockedMap[baseKey] ?: 0.0
            val remLocked = (currentLocked - order.quantity).coerceAtLeast(0.0)
            if (remLocked <= 0.00000001) lockedMap.remove(baseKey) else lockedMap[baseKey] = remLocked
            wallet.copy(lockedCoinBalances = lockedMap)
        }

        saveWallet(updatedWallet, exchange)
        saveOpenOrders(openOrders, exchange)
        return true
    }

    @Synchronized
    fun cancelAllOrders(symbolFilter: String? = null, exchange: String = "TOKOCRYPTO"): Int {
        val openOrders = getOpenOrders(exchange)
        val targets = if (symbolFilter != null) openOrders.filter { it.symbol.equals(symbolFilter, true) } else openOrders
        var count = 0
        targets.forEach {
            if (cancelOrder(it.id, exchange)) count++
        }
        return count
    }

    @Synchronized
    fun processPriceTick(
        symbol: String,
        currentPrice: Double,
        high24h: Double,
        low24h: Double,
        exchange: String = "TOKOCRYPTO"
    ): List<SimulationOrder> {
        if (currentPrice <= 0.0) return emptyList()
        val allOpen = getOpenOrders(exchange).toMutableList()
        val relevant = allOpen.filter { it.symbol.equals(symbol, true) }
        if (relevant.isEmpty()) return emptyList()

        val filledOrders = mutableListOf<SimulationOrder>()
        var wallet = getWallet(exchange)
        val feeRate = if (exchange.equals("INDODAX", true)) INDODAX_MAKER_FEE_RATE else TOKOCRYPTO_MAKER_FEE_RATE

        relevant.forEach { order ->
            var shouldFill = false
            var updatedOrder = order

            when (order.type) {
                SimulationOrderType.LIMIT -> {
                    if (order.side == SimulationOrderSide.BUY) {
                        if (currentPrice <= order.limitPrice) shouldFill = true
                    } else {
                        if (currentPrice >= order.limitPrice) shouldFill = true
                    }
                }
                SimulationOrderType.STOP_LIMIT -> {
                    if (order.side == SimulationOrderSide.BUY) {
                        val triggered = order.isStopTriggered || (currentPrice >= order.stopPrice && order.stopPrice > 0.0)
                        if (triggered) {
                            updatedOrder = order.copy(isStopTriggered = true)
                            if (currentPrice <= order.limitPrice) shouldFill = true
                        }
                    } else {
                        val triggered = order.isStopTriggered || (currentPrice <= order.stopPrice && order.stopPrice > 0.0)
                        if (triggered) {
                            updatedOrder = order.copy(isStopTriggered = true)
                            shouldFill = true
                        }
                    }
                }
                SimulationOrderType.MARKET -> shouldFill = true
            }

            if (shouldFill) {
                val baseKey = order.baseAsset.uppercase()
                val execPrice = if (order.type == SimulationOrderType.STOP_LIMIT && order.side == SimulationOrderSide.SELL) {
                    currentPrice
                } else {
                    order.limitPrice
                }
                val totalIdr = order.quantity * execPrice
                val feeIdr = totalIdr * feeRate

                if (order.side == SimulationOrderSide.BUY) {
                    val lockedToRelease = order.totalIdr + order.feeIdr
                    val newLockedIdr = (wallet.lockedIdr - lockedToRelease).coerceAtLeast(0.0)
                    val newIdrBalance = (wallet.idrBalance - (totalIdr + feeIdr)).coerceAtLeast(0.0)

                    val newCoinBalances = wallet.coinBalances.toMutableMap()
                    val currentCoin = newCoinBalances[baseKey] ?: 0.0
                    val currentAvg = wallet.avgBuyPrices[baseKey] ?: 0.0
                    val newTotalCoin = currentCoin + order.quantity
                    val newAvgPrice = if (newTotalCoin > 0.0) {
                        ((currentCoin * currentAvg) + totalIdr) / newTotalCoin
                    } else execPrice

                    newCoinBalances[baseKey] = newTotalCoin
                    val newAvgMap = wallet.avgBuyPrices.toMutableMap().apply { put(baseKey, newAvgPrice) }

                    wallet = wallet.copy(
                        idrBalance = newIdrBalance,
                        lockedIdr = newLockedIdr,
                        coinBalances = newCoinBalances,
                        avgBuyPrices = newAvgMap
                    )

                    val history = SimulationTradeHistoryItem(
                        id = UUID.randomUUID().toString(),
                        orderId = order.id,
                        symbol = order.symbol,
                        baseAsset = baseKey,
                        quoteAsset = order.quoteAsset,
                        side = order.side,
                        type = order.type,
                        executionPrice = execPrice,
                        quantity = order.quantity,
                        totalIdr = totalIdr,
                        feeIdr = feeIdr,
                        timestamp = System.currentTimeMillis()
                    )
                    addTradeHistory(history, exchange)
                } else {
                    val lockedMap = wallet.lockedCoinBalances.toMutableMap()
                    val curLocked = lockedMap[baseKey] ?: 0.0
                    val remLocked = (curLocked - order.quantity).coerceAtLeast(0.0)
                    if (remLocked <= 0.00000001) lockedMap.remove(baseKey) else lockedMap[baseKey] = remLocked

                    val newCoinBalances = wallet.coinBalances.toMutableMap()
                    val newAvgMap = wallet.avgBuyPrices.toMutableMap()
                    val curCoin = newCoinBalances[baseKey] ?: 0.0
                    val sellQty = when {
                        order.quantity <= 0.0 -> 0.0
                        order.quantity >= curCoin -> curCoin
                        (curCoin - order.quantity) <= 0.00000001 -> curCoin
                        (curCoin > 0.0 && (curCoin - order.quantity) / curCoin < 1e-6) -> curCoin
                        else -> order.quantity
                    }
                    val remCoin = (curCoin - sellQty).coerceAtLeast(0.0)
                    val isDustRemaining = remCoin <= 0.00000001 ||
                        (curCoin > 0.0 && remCoin / curCoin < 1e-6)
                    if (isDustRemaining) {
                        newCoinBalances.remove(baseKey)
                        newAvgMap.remove(baseKey)
                    } else {
                        newCoinBalances[baseKey] = remCoin
                    }

                    val fillTotalIdr = sellQty * execPrice
                    val fillFeeIdr = fillTotalIdr * feeRate
                    val avgBuy = wallet.avgBuyPrices[baseKey] ?: execPrice
                    val costBasis = sellQty * avgBuy
                    val netIdr = fillTotalIdr - fillFeeIdr
                    val pnlIdr = fillTotalIdr - costBasis - fillFeeIdr
                    val pnlPercent = if (costBasis > 0.0) (pnlIdr / costBasis) * 100.0 else 0.0

                    wallet = wallet.copy(
                        idrBalance = wallet.idrBalance + netIdr,
                        coinBalances = newCoinBalances,
                        avgBuyPrices = newAvgMap,
                        lockedCoinBalances = lockedMap
                    )

                    val history = SimulationTradeHistoryItem(
                        id = UUID.randomUUID().toString(),
                        orderId = order.id,
                        symbol = order.symbol,
                        baseAsset = baseKey,
                        quoteAsset = order.quoteAsset,
                        side = order.side,
                        type = order.type,
                        executionPrice = execPrice,
                        quantity = sellQty,
                        totalIdr = fillTotalIdr,
                        feeIdr = fillFeeIdr,
                        timestamp = System.currentTimeMillis(),
                        pnlIdr = pnlIdr,
                        pnlPercent = pnlPercent
                    )
                    addTradeHistory(history, exchange)
                }

                allOpen.remove(order)
                val completed = updatedOrder.copy(
                    status = SimulationOrderStatus.FILLED,
                    filledQuantity = order.quantity,
                    filledAvgPrice = execPrice,
                    feeIdr = feeIdr,
                    filledAt = System.currentTimeMillis()
                )
                filledOrders.add(completed)
            } else if (updatedOrder != order) {
                val idx = allOpen.indexOf(order)
                if (idx != -1) allOpen[idx] = updatedOrder
            }
        }

        if (filledOrders.isNotEmpty()) {
            saveWallet(wallet, exchange)
            saveOpenOrders(allOpen, exchange)
        }

        return filledOrders
    }

    private fun formatMoney(value: Double, quoteAsset: String): String {
        return agu.analys.util.PriceFormatter.formatPrice(value, showSymbol = true, quoteAsset = quoteAsset)
    }
}
