package agu.analys.trading

import android.content.Context
import org.json.JSONArray
import java.util.UUID

class SimulationTradeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simulation_trade_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WALLET = "sim_wallet"
        private const val KEY_OPEN_ORDERS = "sim_open_orders"
        private const val KEY_TRADE_HISTORY = "sim_trade_history"
        const val INDODAX_MAKER_FEE_RATE = 0.001 // 0.1%
        const val INDODAX_TAKER_FEE_RATE = 0.003 // 0.3%
    }

    @Synchronized
    fun getWallet(): SimulationWallet {
        val raw = prefs.getString(KEY_WALLET, null)
        return SimulationTradeJson.walletFromJson(raw)
    }

    @Synchronized
    fun saveWallet(wallet: SimulationWallet) {
        val json = SimulationTradeJson.walletToJson(wallet)
        prefs.edit().putString(KEY_WALLET, json.toString()).apply()
    }

    @Synchronized
    fun topUpIdr(amount: Double) {
        val w = getWallet()
        val updated = w.copy(idrBalance = w.idrBalance + amount.coerceAtLeast(0.0))
        saveWallet(updated)
    }

    @Synchronized
    fun resetWallet(initialIdr: Double = 10_000_000.0) {
        saveWallet(SimulationWallet(idrBalance = initialIdr))
        prefs.edit().remove(KEY_OPEN_ORDERS).remove(KEY_TRADE_HISTORY).apply()
    }

    @Synchronized
    fun getOpenOrders(symbolFilter: String? = null): List<SimulationOrder> {
        val raw = prefs.getString(KEY_OPEN_ORDERS, null) ?: return emptyList()
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
    private fun saveOpenOrders(orders: List<SimulationOrder>) {
        val array = JSONArray()
        orders.filter { it.status == SimulationOrderStatus.OPEN }.forEach {
            array.put(SimulationTradeJson.orderToJson(it))
        }
        prefs.edit().putString(KEY_OPEN_ORDERS, array.toString()).apply()
    }

    @Synchronized
    fun getTradeHistory(symbolFilter: String? = null): List<SimulationTradeHistoryItem> {
        val raw = prefs.getString(KEY_TRADE_HISTORY, null) ?: return emptyList()
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
    private fun addTradeHistory(item: SimulationTradeHistoryItem) {
        val current = getTradeHistory().toMutableList()
        current.add(0, item)
        if (current.size > 100) {
            current.removeAt(current.lastIndex)
        }
        val array = JSONArray()
        current.forEach { array.put(SimulationTradeJson.historyToJson(it)) }
        prefs.edit().putString(KEY_TRADE_HISTORY, array.toString()).apply()
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
        currentMarketPrice: Double
    ): SimulationOrderResult {
        if (quantity <= 0.0) return SimulationOrderResult.Error("Jumlah koin harus lebih besar dari 0.")
        val wallet = getWallet()
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
                            saveWallet(res.updatedWallet)
                            addTradeHistory(res.historyItem)
                            SimulationOrderResult.Success(res.completedOrder, "Market Buy berhasil @ ${formatMoney(execPrice, quote)}!")
                        },
                        onFailure = { err ->
                            SimulationOrderResult.Error(err.message ?: "Gagal memproses Market Buy.")
                        }
                    )
                } else {
                    val result = SimulationOrderEngine.executeMarketSell(wallet, symbol, baseKey, quote, execPrice, quantity)
                    return result.fold(
                        onSuccess = { res ->
                            saveWallet(res.updatedWallet)
                            addTradeHistory(res.historyItem)
                            SimulationOrderResult.Success(res.completedOrder, "Market Sell berhasil @ ${formatMoney(execPrice, quote)}!")
                        },
                        onFailure = { err ->
                            SimulationOrderResult.Error(err.message ?: "Gagal memproses Market Sell.")
                        }
                    )
                }
            }

            SimulationOrderType.LIMIT, SimulationOrderType.STOP_LIMIT -> {
                if (price <= 0.0) return SimulationOrderResult.Error("Harga Limit harus lebih besar dari 0.")
                val totalIdr = quantity * price
                val feeRate = INDODAX_MAKER_FEE_RATE
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
                    saveWallet(updatedWallet)

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

                    val openList = getOpenOrders().toMutableList()
                    openList.add(order)
                    saveOpenOrders(openList)

                    processPriceTick(symbol, currentMarketPrice, currentMarketPrice, currentMarketPrice)

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
                    saveWallet(updatedWallet)

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

                    val openList = getOpenOrders().toMutableList()
                    openList.add(order)
                    saveOpenOrders(openList)

                    processPriceTick(symbol, currentMarketPrice, currentMarketPrice, currentMarketPrice)

                    return SimulationOrderResult.Success(order, "Order ${type.displayName} Jual dipasang @ ${formatMoney(price, quote)}.")
                }
            }
        }
    }

    @Synchronized
    fun cancelOrder(orderId: String): Boolean {
        val openOrders = getOpenOrders().toMutableList()
        val index = openOrders.indexOfFirst { it.id == orderId }
        if (index == -1) return false
        val order = openOrders.removeAt(index)

        val wallet = getWallet()
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

        saveWallet(updatedWallet)
        saveOpenOrders(openOrders)
        return true
    }

    @Synchronized
    fun cancelAllOrders(symbolFilter: String? = null): Int {
        val openOrders = getOpenOrders()
        val targets = if (symbolFilter != null) openOrders.filter { it.symbol.equals(symbolFilter, true) } else openOrders
        var count = 0
        targets.forEach {
            if (cancelOrder(it.id)) count++
        }
        return count
    }

    @Synchronized
    fun processPriceTick(
        symbol: String,
        currentPrice: Double,
        high24h: Double,
        low24h: Double
    ): List<SimulationOrder> {
        if (currentPrice <= 0.0) return emptyList()
        val allOpen = getOpenOrders().toMutableList()
        val relevant = allOpen.filter { it.symbol.equals(symbol, true) }
        if (relevant.isEmpty()) return emptyList()

        val filledOrders = mutableListOf<SimulationOrder>()
        var wallet = getWallet()

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
                            // Sell stop loss / trailing stop fills immediately when stop price is reached
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
                val feeIdr = totalIdr * INDODAX_MAKER_FEE_RATE

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
                    addTradeHistory(history)
                } else {
                    val lockedMap = wallet.lockedCoinBalances.toMutableMap()
                    val curLocked = lockedMap[baseKey] ?: 0.0
                    val remLocked = (curLocked - order.quantity).coerceAtLeast(0.0)
                    if (remLocked <= 0.00000001) lockedMap.remove(baseKey) else lockedMap[baseKey] = remLocked

                    val newCoinBalances = wallet.coinBalances.toMutableMap()
                    val curCoin = newCoinBalances[baseKey] ?: 0.0
                    val remCoin = (curCoin - order.quantity).coerceAtLeast(0.0)
                    if (remCoin <= 0.00000001) newCoinBalances.remove(baseKey) else newCoinBalances[baseKey] = remCoin

                    val avgBuy = wallet.avgBuyPrices[baseKey] ?: execPrice
                    val costBasis = order.quantity * avgBuy
                    val netIdr = totalIdr - feeIdr
                    val pnlIdr = totalIdr - costBasis - feeIdr
                    val pnlPercent = if (costBasis > 0.0) (pnlIdr / costBasis) * 100.0 else 0.0

                    wallet = wallet.copy(
                        idrBalance = wallet.idrBalance + netIdr,
                        coinBalances = newCoinBalances,
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
                        quantity = order.quantity,
                        totalIdr = totalIdr,
                        feeIdr = feeIdr,
                        timestamp = System.currentTimeMillis(),
                        pnlIdr = pnlIdr,
                        pnlPercent = pnlPercent
                    )
                    addTradeHistory(history)
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
            saveWallet(wallet)
            saveOpenOrders(allOpen)
        }

        return filledOrders
    }

    private fun formatMoney(value: Double, quoteAsset: String): String {
        val isUsdt = quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)
        return if (isUsdt) {
            String.format("%.4f %s", value, quoteAsset.uppercase())
        } else {
            "Rp " + String.format("%,.0f", value).replace(",", ".")
        }
    }
}
