package agu.analys.trading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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

    /**
     * Membuat Order Baru (Limit, Market, atau Stop-Limit).
     * Jika MARKET ORDER: Seketika dieksekusi berdasarkan currentMarketPrice.
     * Jika LIMIT / STOP-LIMIT: Saldo IDR/Koin dikunci (locked) dan masuk ke Open Orders.
     */
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

        when (type) {
            SimulationOrderType.MARKET -> {
                val execPrice = if (currentMarketPrice > 0.0) currentMarketPrice else price
                if (execPrice <= 0.0) return SimulationOrderResult.Error("Harga pasar realtime belum tersedia.")
                val totalIdr = quantity * execPrice
                val feeRate = INDODAX_TAKER_FEE_RATE
                val feeIdr = totalIdr * feeRate

                if (side == SimulationOrderSide.BUY) {
                    val requiredIdr = totalIdr + feeIdr
                    if (wallet.getAvailableIdr() < requiredIdr) {
                        return SimulationOrderResult.Error("Saldo IDR tidak cukup. Dibutuhkan Rp ${formatNumber(requiredIdr)}, Saldo Rp ${formatNumber(wallet.getAvailableIdr())}.")
                    }

                    // Update wallet (Buy Market)
                    val newCoinBalances = wallet.coinBalances.toMutableMap()
                    val currentCoin = newCoinBalances[baseKey] ?: 0.0
                    val currentAvg = wallet.avgBuyPrices[baseKey] ?: 0.0
                    val newTotalCoin = currentCoin + quantity
                    val newAvgPrice = if (newTotalCoin > 0.0) {
                        ((currentCoin * currentAvg) + totalIdr) / newTotalCoin
                    } else execPrice

                    newCoinBalances[baseKey] = newTotalCoin
                    val newAvgMap = wallet.avgBuyPrices.toMutableMap().apply { put(baseKey, newAvgPrice) }

                    val updatedWallet = wallet.copy(
                        idrBalance = (wallet.idrBalance - requiredIdr).coerceAtLeast(0.0),
                        coinBalances = newCoinBalances,
                        avgBuyPrices = newAvgMap
                    )
                    saveWallet(updatedWallet)

                    val history = SimulationTradeHistoryItem(
                        id = UUID.randomUUID().toString(),
                        orderId = UUID.randomUUID().toString(),
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quoteAsset,
                        side = side,
                        type = type,
                        executionPrice = execPrice,
                        quantity = quantity,
                        totalIdr = totalIdr,
                        feeIdr = feeIdr,
                        timestamp = System.currentTimeMillis()
                    )
                    addTradeHistory(history)

                    val order = SimulationOrder(
                        id = history.orderId,
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quoteAsset,
                        side = side,
                        type = type,
                        limitPrice = execPrice,
                        quantity = quantity,
                        totalIdr = totalIdr,
                        filledQuantity = quantity,
                        filledAvgPrice = execPrice,
                        feeIdr = feeIdr,
                        status = SimulationOrderStatus.FILLED,
                        filledAt = System.currentTimeMillis()
                    )
                    return SimulationOrderResult.Success(order, "Market Buy berhasil dieksekusi di harga Rp ${formatNumber(execPrice)}!")
                } else {
                    // SELL MARKET
                    if (wallet.getAvailableCoin(baseKey) < quantity) {
                        return SimulationOrderResult.Error("Saldo $baseKey tidak cukup. Tersedia: ${wallet.getAvailableCoin(baseKey)}")
                    }

                    val netIdr = (totalIdr - feeIdr).coerceAtLeast(0.0)
                    val avgBuy = wallet.avgBuyPrices[baseKey] ?: execPrice
                    val costBasis = quantity * avgBuy
                    val pnlIdr = totalIdr - costBasis - feeIdr
                    val pnlPercent = if (costBasis > 0.0) (pnlIdr / costBasis) * 100.0 else 0.0

                    val newCoinBalances = wallet.coinBalances.toMutableMap()
                    val remaining = (newCoinBalances[baseKey] ?: 0.0) - quantity
                    if (remaining <= 0.00000001) {
                        newCoinBalances.remove(baseKey)
                    } else {
                        newCoinBalances[baseKey] = remaining
                    }

                    val updatedWallet = wallet.copy(
                        idrBalance = wallet.idrBalance + netIdr,
                        coinBalances = newCoinBalances
                    )
                    saveWallet(updatedWallet)

                    val history = SimulationTradeHistoryItem(
                        id = UUID.randomUUID().toString(),
                        orderId = UUID.randomUUID().toString(),
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quoteAsset,
                        side = side,
                        type = type,
                        executionPrice = execPrice,
                        quantity = quantity,
                        totalIdr = totalIdr,
                        feeIdr = feeIdr,
                        timestamp = System.currentTimeMillis(),
                        pnlIdr = pnlIdr,
                        pnlPercent = pnlPercent
                    )
                    addTradeHistory(history)

                    val order = SimulationOrder(
                        id = history.orderId,
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quoteAsset,
                        side = side,
                        type = type,
                        limitPrice = execPrice,
                        quantity = quantity,
                        totalIdr = totalIdr,
                        filledQuantity = quantity,
                        filledAvgPrice = execPrice,
                        feeIdr = feeIdr,
                        status = SimulationOrderStatus.FILLED,
                        filledAt = System.currentTimeMillis()
                    )
                    return SimulationOrderResult.Success(order, "Market Sell berhasil dieksekusi di harga Rp ${formatNumber(execPrice)}!")
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
                        return SimulationOrderResult.Error("Saldo IDR tidak cukup untuk memasang limit order. Tersedia: Rp ${formatNumber(wallet.getAvailableIdr())}")
                    }

                    // Kunci saldo IDR
                    val updatedWallet = wallet.copy(
                        lockedIdr = wallet.lockedIdr + requiredIdr
                    )
                    saveWallet(updatedWallet)

                    val order = SimulationOrder(
                        id = UUID.randomUUID().toString(),
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quoteAsset,
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

                    // Cek langsung apakah limit langsung match dengan market price saat ini
                    processPriceTick(symbol, currentMarketPrice, currentMarketPrice, currentMarketPrice)

                    return SimulationOrderResult.Success(order, "Order ${type.displayName} Beli berhasil dipasang pada Rp ${formatNumber(price)}.")
                } else {
                    // SELL LIMIT / STOP LIMIT
                    if (wallet.getAvailableCoin(baseKey) < quantity) {
                        return SimulationOrderResult.Error("Saldo koin $baseKey tidak cukup. Tersedia: ${wallet.getAvailableCoin(baseKey)}")
                    }

                    // Kunci koin
                    val lockedMap = wallet.lockedCoinBalances.toMutableMap()
                    lockedMap[baseKey] = (lockedMap[baseKey] ?: 0.0) + quantity
                    val updatedWallet = wallet.copy(lockedCoinBalances = lockedMap)
                    saveWallet(updatedWallet)

                    val order = SimulationOrder(
                        id = UUID.randomUUID().toString(),
                        symbol = symbol,
                        baseAsset = baseKey,
                        quoteAsset = quoteAsset,
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

                    // Cek langsung jika match
                    processPriceTick(symbol, currentMarketPrice, currentMarketPrice, currentMarketPrice)

                    return SimulationOrderResult.Success(order, "Order ${type.displayName} Jual berhasil dipasang pada Rp ${formatNumber(price)}.")
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

        // Buka kembali saldo yang dikunci
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

    /**
     * Matching Engine yang memeriksa order buku terbuka terhadap harga real Indodax saat ini.
     * Mengembalikan daftar order yang baru saja FILLED.
     */
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
                        // Limit BUY match jika harga pasar turun sampai <= limit price
                        if (currentPrice <= order.limitPrice) {
                            shouldFill = true
                        }
                    } else {
                        // Limit SELL match jika harga pasar naik sampai >= limit price
                        if (currentPrice >= order.limitPrice) {
                            shouldFill = true
                        }
                    }
                }
                SimulationOrderType.STOP_LIMIT -> {
                    if (order.side == SimulationOrderSide.BUY) {
                        // Stop trigger Buy: saat harga menyentuh stopPrice ke atas
                        val triggered = order.isStopTriggered || (currentPrice >= order.stopPrice && order.stopPrice > 0.0)
                        if (triggered) {
                            updatedOrder = order.copy(isStopTriggered = true)
                            if (currentPrice <= order.limitPrice) {
                                shouldFill = true
                            }
                        }
                    } else {
                        // Stop trigger Sell: saat harga turun menyentuh stopPrice ke bawah (Stop Loss)
                        val triggered = order.isStopTriggered || (currentPrice <= order.stopPrice && order.stopPrice > 0.0)
                        if (triggered) {
                            updatedOrder = order.copy(isStopTriggered = true)
                            if (currentPrice >= order.limitPrice) {
                                shouldFill = true
                            }
                        }
                    }
                }
                SimulationOrderType.MARKET -> {
                    shouldFill = true
                }
            }

            if (shouldFill) {
                val baseKey = order.baseAsset.uppercase()
                val execPrice = order.limitPrice
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
                    // SELL FILL
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
                // Update trigger state in list
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

    private fun formatNumber(value: Double): String {
        return String.format("%,.0f", value).replace(",", ".")
    }
}
