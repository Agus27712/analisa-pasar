package agu.analys.viewmodel

import agu.analys.database.AppDatabase
import agu.analys.service.IndodaxTradeApiV2
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber

class RealTradeExecutor(
    private val scope: CoroutineScope,
    private val prefs: AppPreferences,
    private val getLatestTick: (String) -> agu.analys.model.MarketTick?,
    private val onStatusUpdate: (String) -> Unit,
    private val onRateLimit: (String) -> Unit,
    private val isRateLimited: () -> Boolean,
    private val refreshBalance: () -> Unit,
    private val onRealTradeSuccess: ((pair: String, type: String, price: Double, quantity: Double, tp1: Double, tp2: Double) -> Unit)? = null
) {
    private val INTER_REQUEST_DELAY_MS = 1500L
    private val BUY_POLL_INTERVAL_MS = 2500L
    private val BUY_POLL_MAX_ATTEMPTS = 15
    private val MIN_EXECUTED_QTY = 1e-12

    fun executeCancelOrder(
        symbol: String,
        orderId: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            onResult(false, "API Key atau Secret Key INDODAX belum diisi.")
            return
        }
        if (isRateLimited()) {
            onResult(false, "Rate-limit aktif. Tunggu dulu.")
            return
        }

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            onStatusUpdate("Membatalkan order $orderId...")
            val (success, message) = IndodaxTradeApiV2.cancelOrder(apiKey, secretKey, symbol, orderId)
            if (!success && looksLikeRateLimit(message)) {
                onRateLimit(message)
                onResult(false, message)
                return@launch
            }
            onStatusUpdate(message)
            if (success) {
                AppDatabase.getInstance().realTradeDao().deleteOpenOrderById(orderId)
                delay(INTER_REQUEST_DELAY_MS)
            }
            onResult(success, message)
        }
    }

    private suspend fun waitForBuyFill(
        apiKey: String,
        secretKey: String,
        pair: String,
        orderId: String,
        clientOrderId: String,
        requestedQty: Double
    ): Double {
        var lastExecuted = 0.0
        var lastStatus = "NEW"

        for (attempt in 1..BUY_POLL_MAX_ATTEMPTS) {
            delay(BUY_POLL_INTERVAL_MS)
            onStatusUpdate("Menunggu BUY terisi... ($attempt/$BUY_POLL_MAX_ATTEMPTS)")

            val result = IndodaxTradeApiV2.getOrder(
                apiKey = apiKey,
                secretKey = secretKey,
                symbol = pair,
                orderId = orderId.takeIf { it.isNotBlank() && it != "0" },
                clientOrderId = clientOrderId.takeIf { it.isNotBlank() }
            )

            if (!result.success) {
                if (looksLikeRateLimit(result.message)) {
                    onRateLimit(result.message)
                    break
                }
                Timber.w("Poll BUY gagal: ${result.message}")
                continue
            }

            lastStatus = result.status
            lastExecuted = result.executedQty

            when (result.status) {
                "FILLED" -> return result.executedQty.coerceAtLeast(0.0)
                "PARTIALLY_FILLED" -> {
                    if (result.executedQty > MIN_EXECUTED_QTY) return result.executedQty
                }
                "CANCELLED", "REJECTED", "EXPIRED" -> return 0.0
            }
        }
        return if (lastExecuted > MIN_EXECUTED_QTY) lastExecuted else 0.0
    }

    fun executeTrade(
        pair: String,
        type: String,
        price: Long,
        amountIdr: Double,
        autoLimitSellPrice1: Double = 0.0,
        autoLimitSellPrice2: Double = 0.0,
        onResult: (Boolean, String) -> Unit
    ) {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            onResult(false, "API Key atau Secret Key INDODAX belum diisi.")
            return
        }
        val isBuy = type.equals("buy", ignoreCase = true)
        // Rate-limit check hanya untuk BUY agar order jual/proteksi modal tidak tertahan
        if (isBuy && isRateLimited()) {
            onResult(false, "Rate-limit aktif.")
            return
        }

        var execPrice = price.toDouble()
        val latestTick = getLatestTick(pair)
        if (latestTick != null) {
            val tickAge = System.currentTimeMillis() - latestTick.timestamp
            val maxAge = if (prefs.isScalpingMode) 400L else 800L
            // Proteksi pembatalan delay/slippage HANYA untuk BUY!
            // Untuk SELL, jangan pernah batalkan order agar eksekusi langsung terlaksana
            if (isBuy && tickAge > maxAge) {
                onResult(false, "Data harga terlalu usang (delay ${tickAge}ms > limit ${maxAge}ms). Order dibatalkan untuk menghindari slippage.")
                return
            }
            execPrice = latestTick.price
        }

        val quantity = if (type.equals("buy", ignoreCase = true)) {
            if (execPrice <= 0.0 || amountIdr <= 0.0) 0.0 else amountIdr / execPrice
        } else {
            amountIdr
        }

        if (quantity <= 0.0) {
            onResult(false, "Quantity order tidak valid.")
            return
        }

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            onStatusUpdate("Mengirim order $type ke INDODAX...")
            val clientOrderId = "agu-${type.lowercase()}-${System.currentTimeMillis()}"
            val isBuy = type.equals("buy", ignoreCase = true)
            val buyResult = if (!isBuy) {
                // Untuk SELL: Coba kirim MARKET order terlebih dahulu untuk eksekusi instan proteksi modal
                val marketRes = IndodaxTradeApiV2.createMarketOrderDetailed(
                    apiKey = apiKey, secretKey = secretKey, symbol = pair,
                    side = type, quantity = quantity, clientOrderId = clientOrderId
                )
                if (marketRes.success) {
                    marketRes
                } else if (looksLikeRateLimit(marketRes.message)) {
                    marketRes
                } else {
                    // Fallback ke LIMIT order dengan harga tick terkini
                    IndodaxTradeApiV2.createLimitOrderDetailed(
                        apiKey = apiKey, secretKey = secretKey, symbol = pair,
                        side = type, price = execPrice, quantity = quantity, clientOrderId = clientOrderId
                    )
                }
            } else {
                IndodaxTradeApiV2.createLimitOrderDetailed(
                    apiKey = apiKey, secretKey = secretKey, symbol = pair,
                    side = type, price = execPrice, quantity = quantity, clientOrderId = clientOrderId
                )
            }

            if (!buyResult.success && looksLikeRateLimit(buyResult.message)) {
                onRateLimit(buyResult.message)
                onResult(false, buyResult.message)
                return@launch
            }

            if (buyResult.success) {
                agu.analys.engine.scalping.SignalLifecycleManager.markTriggered(pair)
                val base = baseFromPair(pair)
                prefs.rememberHistoryBase(base)

                // execPrice is already set
                var finalExecutedQty = quantity

                if (isBuy && autoLimitSellPrice1 > price) {
                    val executedQty = if (buyResult.executedQty > MIN_EXECUTED_QTY && buyResult.status == "FILLED") {
                        buyResult.executedQty
                    } else {
                        waitForBuyFill(apiKey, secretKey, pair, buyResult.orderId, buyResult.clientOrderId.ifBlank { clientOrderId }, quantity)
                    }

                    if (executedQty <= MIN_EXECUTED_QTY) {
                        onStatusUpdate("BUY terkirim tapi belum FILLED. TP tidak dipasang.")
                        onResult(true, "BUY berhasil di server, tapi belum FILLED.")
                        delay(INTER_REQUEST_DELAY_MS)
                        refreshBalance()
                        return@launch
                    }
                    finalExecutedQty = executedQty

                    val halfQty = executedQty / 2.0
                    var finalMsg = "BUY filled ${"%.8f".format(executedQty)}. "
                    delay(INTER_REQUEST_DELAY_MS)
                    onStatusUpdate("Memasang TP1...")
                    val (s1, m1) = IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", autoLimitSellPrice1, halfQty, "agu-tp1-${System.currentTimeMillis()}")
                    finalMsg += if (s1) "TP1 OK. " else "TP1 Gagal: $m1. "
                    if (!s1 && looksLikeRateLimit(m1)) onRateLimit(m1)

                    val p2 = if (autoLimitSellPrice2 > price) autoLimitSellPrice2 else autoLimitSellPrice1 * 1.03
                    delay(INTER_REQUEST_DELAY_MS)
                    onStatusUpdate("Memasang TP2...")
                    val (s2, m2) = IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", p2, halfQty, "agu-tp2-${System.currentTimeMillis()}")
                    finalMsg += if (s2) "TP2 OK." else "TP2 Gagal: $m2."
                    if (!s2 && looksLikeRateLimit(m2)) onRateLimit(m2)

                    onStatusUpdate("BUY + TP: $finalMsg")
                    onResult(true, "BUY berhasil!\nAuto Sell: $finalMsg")
                } else {
                    if (isBuy && buyResult.executedQty > MIN_EXECUTED_QTY) {
                        finalExecutedQty = buyResult.executedQty
                    }
                    onStatusUpdate(buyResult.message)
                    onResult(true, buyResult.message)
                }

                // Update cached real balance & average price instantly (agar saat IP berubah di luar rumah, saldo tetap akurat 1:1)
                runCatching {
                    val cachedBalances = prefs.getSavedRealBalance().toMutableMap()
                    val currentIdr = cachedBalances["idr"] ?: 0.0
                    val baseLower = base.lowercase()
                    val curCoin = cachedBalances[baseLower] ?: 0.0
                    if (isBuy) {
                        val cost = finalExecutedQty * execPrice
                        val newTotalCoin = curCoin + finalExecutedQty
                        cachedBalances["idr"] = (currentIdr - cost).coerceAtLeast(0.0)
                        cachedBalances[baseLower] = newTotalCoin
                        prefs.saveRealBalance(cachedBalances)

                        val cachedAvg = prefs.getSavedRealAvgBuyPrices().toMutableMap()
                        val prevAvg = cachedAvg[baseLower] ?: cachedAvg[base.uppercase()] ?: 0.0
                        val newAvgPrice = if (curCoin > 0.0 && prevAvg > 0.0 && newTotalCoin > 0.0) {
                            ((prevAvg * curCoin) + (execPrice * finalExecutedQty)) / newTotalCoin
                        } else {
                            execPrice
                        }
                        cachedAvg[baseLower] = newAvgPrice
                        cachedAvg[base.uppercase()] = newAvgPrice
                        cachedAvg["${baseLower}idr"] = newAvgPrice
                        cachedAvg["${base.uppercase()}IDR"] = newAvgPrice
                        prefs.saveRealAvgBuyPrices(cachedAvg)
                    } else {
                        val proceeds = finalExecutedQty * execPrice
                        cachedBalances["idr"] = currentIdr + proceeds
                        cachedBalances[baseLower] = (curCoin - finalExecutedQty).coerceAtLeast(0.0)
                        prefs.saveRealBalance(cachedBalances)
                    }
                }

                // Shadow Mirror: Sinkronkan langsung ke simulasi & SpotPosition
                onRealTradeSuccess?.invoke(
                    pair,
                    type,
                    execPrice,
                    finalExecutedQty,
                    if (isBuy) autoLimitSellPrice1 else 0.0,
                    if (isBuy) autoLimitSellPrice2 else 0.0
                )

                delay(INTER_REQUEST_DELAY_MS)
                refreshBalance()
            } else {
                onStatusUpdate(buyResult.message)
                onResult(false, buyResult.message)
            }
        }
    }

    fun executeAutoSellOnServer(
        pair: String,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        onResult: (Boolean, String) -> Unit
    ) {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            onResult(false, "API Key/Secret kosong.")
            return
        }
        if (isRateLimited()) {
            onResult(false, "Rate-limit aktif.")
            return
        }

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            onStatusUpdate("Mengecek saldo $pair...")
            val base = baseFromPair(pair)
            val (balances, err) = IndodaxTradeApiV2.getAccount(apiKey, secretKey)
            if (balances == null) {
                if (looksLikeRateLimit(err)) onRateLimit(err)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "Gagal saldo: $err")
                }
                return@launch
            }

            val free = balances.free[base] ?: 0.0
            if (free <= 0.00000001) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "Saldo $base 0.")
                }
                return@launch
            }

            val qty1 = if (tp1Percent >= 100.0) free else (free * (tp1Percent / 100.0) * 100_000_000.0).toLong() / 100_000_000.0
            val qty2 = if (tp1Percent >= 100.0) 0.0 else if (tp2Percent >= 100.0) free else free - qty1
            var msg = ""
            var okAll = true

            if (tp1Price > 0.0 && qty1 > 0.0) {
                val (ok, m) = IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", tp1Price, qty1, "agu-manualtp1-${System.currentTimeMillis()}")
                msg += if (ok) "TP1 OK. " else "TP1 Gagal: $m. "
                if (!ok) {
                    okAll = false
                    if (looksLikeRateLimit(m)) {
                        onRateLimit(m)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onResult(false, msg)
                        }
                        return@launch
                    }
                }
                delay(INTER_REQUEST_DELAY_MS)
            }
            if (tp2Price > 0.0 && qty2 > 0.0) {
                val (ok, m) = IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", tp2Price, qty2, "agu-manualtp2-${System.currentTimeMillis()}")
                msg += if (ok) "TP2 OK." else "TP2 Gagal: $m."
                if (!ok) {
                    okAll = false
                    if (looksLikeRateLimit(m)) onRateLimit(m)
                }
            }
            if (okAll) prefs.rememberHistoryBase(base)
            onStatusUpdate(if (okAll) "TP Berhasil!" else "Sebagian TP Gagal.")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult(okAll, msg)
            }
        }
    }

    fun executeRealSellOrders(
        pair: String,
        totalQuantity: Double,
        marketPrice: Double,
        isAutoTpEnabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        onResult: (Boolean, String) -> Unit
    ) {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            onResult(false, "API Key/Secret INDODAX belum diisi.")
            return
        }
        // Batasan rate-limit dilepas untuk order jual agar proteksi modal / eksekusi jual tidak tertahan

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            onStatusUpdate("Mengeksekusi order jual $pair...")
            val base = baseFromPair(pair)
            
            // Periksa saldo lokal terlebih dahulu agar eksekusi order instan tanpa jeda network
            val cachedBalances = prefs.getSavedRealBalance()
            val cachedFree = cachedBalances[base.lowercase()] ?: cachedBalances[base.uppercase()] ?: 0.0
            var sellQty = if (totalQuantity > 0.0) totalQuantity else cachedFree

            if (sellQty <= 0.00000001) {
                onStatusUpdate("Mengecek saldo real $pair...")
                val (balances, err) = IndodaxTradeApiV2.getAccount(apiKey, secretKey)
                if (balances != null) {
                    val free = balances.free[base] ?: 0.0
                    sellQty = if (totalQuantity > 0.0) totalQuantity.coerceAtMost(free) else free
                } else if (looksLikeRateLimit(err)) {
                    onRateLimit(err)
                }
            }

            if (sellQty <= 0.00000001) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "Saldo $base tidak mencukupi atau 0.")
                }
                return@launch
            }

            if (isAutoTpEnabled && tp1Price > 0.0 && tp2Price > 0.0) {
                val p1 = (tp1Percent / 100.0).coerceIn(0.01, 0.99)
                val qty1 = ((sellQty * p1) * 100_000_000.0).toLong() / 100_000_000.0
                val qty2 = sellQty - qty1

                var msg = ""
                var okAll = true

                if (qty1 > 0.0) {
                    onStatusUpdate("Memasang order jual TP1...")
                    val (ok1, m1) = IndodaxTradeApiV2.createLimitOrder(
                        apiKey, secretKey, pair, "sell", tp1Price, qty1, "agu-tp1-${System.currentTimeMillis()}"
                    )
                    msg += if (ok1) "TP1 (${agu.analys.util.PriceFormatter.formatCryptoExact(qty1, 8)} @ Rp ${agu.analys.util.PriceFormatter.formatIdrNumber(tp1Price)}) OK. " else "TP1 Gagal: $m1. "
                    if (!ok1) {
                        okAll = false
                        if (looksLikeRateLimit(m1)) onRateLimit(m1)
                    }
                    delay(INTER_REQUEST_DELAY_MS)
                }

                if (qty2 > 0.0) {
                    onStatusUpdate("Memasang order jual TP2...")
                    val (ok2, m2) = IndodaxTradeApiV2.createLimitOrder(
                        apiKey, secretKey, pair, "sell", tp2Price, qty2, "agu-tp2-${System.currentTimeMillis()}"
                    )
                    msg += if (ok2) "TP2 (${agu.analys.util.PriceFormatter.formatCryptoExact(qty2, 8)} @ Rp ${agu.analys.util.PriceFormatter.formatIdrNumber(tp2Price)}) OK." else "TP2 Gagal: $m2."
                    if (!ok2) {
                        okAll = false
                        if (looksLikeRateLimit(m2)) onRateLimit(m2)
                    }
                }

                if (okAll) prefs.rememberHistoryBase(base)
                onStatusUpdate(if (okAll) "2 Order TP Real Berhasil!" else "Sebagian Order TP Gagal.")
                scope.launch { refreshBalance() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(okAll, if (okAll) "2 Order TP Berhasil (100% tanpa sisa):\n$msg" else msg)
                }
            } else if (isAutoTpEnabled && tp1Price > 0.0) {
                onStatusUpdate("Memasang order jual TP1...")
                val (ok, m) = IndodaxTradeApiV2.createLimitOrder(
                    apiKey, secretKey, pair, "sell", tp1Price, sellQty, "agu-tp1-${System.currentTimeMillis()}"
                )
                if (ok) prefs.rememberHistoryBase(base)
                if (!ok && looksLikeRateLimit(m)) onRateLimit(m)
                scope.launch { refreshBalance() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(ok, if (ok) "Order TP1 (${agu.analys.util.PriceFormatter.formatCryptoExact(sellQty, 8)} @ Rp ${agu.analys.util.PriceFormatter.formatIdrNumber(tp1Price)}) berhasil terpasang." else "Order TP1 Gagal: $m")
                }
            } else if (isAutoTpEnabled && tp2Price > 0.0) {
                onStatusUpdate("Memasang order jual TP2...")
                val (ok, m) = IndodaxTradeApiV2.createLimitOrder(
                    apiKey, secretKey, pair, "sell", tp2Price, sellQty, "agu-tp2-${System.currentTimeMillis()}"
                )
                if (ok) prefs.rememberHistoryBase(base)
                if (!ok && looksLikeRateLimit(m)) onRateLimit(m)
                scope.launch { refreshBalance() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(ok, if (ok) "Order TP2 (${agu.analys.util.PriceFormatter.formatCryptoExact(sellQty, 8)} @ Rp ${agu.analys.util.PriceFormatter.formatIdrNumber(tp2Price)}) berhasil terpasang." else "Order TP2 Gagal: $m")
                }
            } else {
                // Switch OFF -> Eksekusi Jual Langsung (Market Order Instan tanpa delay)
                onStatusUpdate("Mengeksekusi order jual langsung ke bursa...")
                val clientOrderId = "agu-sell-${System.currentTimeMillis()}"
                
                // Prioritaskan MARKET ORDER agar order langsung MATCH tereksekusi tanpa antrean di orderbook
                val marketRes = IndodaxTradeApiV2.createMarketOrderDetailed(
                    apiKey = apiKey,
                    secretKey = secretKey,
                    symbol = pair,
                    side = "sell",
                    quantity = sellQty,
                    clientOrderId = clientOrderId
                )

                val finalRes = if (marketRes.success) {
                    marketRes
                } else {
                    // Fallback ke LIMIT ORDER jika pair bursa tidak mendukung market order di endpoint API
                    IndodaxTradeApiV2.createLimitOrderDetailed(
                        apiKey = apiKey,
                        secretKey = secretKey,
                        symbol = pair,
                        side = "sell",
                        price = marketPrice,
                        quantity = sellQty,
                        clientOrderId = clientOrderId
                    )
                }

                if (finalRes.success) {
                    prefs.rememberHistoryBase(base)

                    // Langsung sinkronkan saldo lokal
                    runCatching {
                        val currentBalances = prefs.getSavedRealBalance().toMutableMap()
                        val curCoin = currentBalances[base.lowercase()] ?: 0.0
                        val curIdr = currentBalances["idr"] ?: 0.0
                        currentBalances[base.lowercase()] = (curCoin - sellQty).coerceAtLeast(0.0)
                        val proceeds = sellQty * marketPrice
                        currentBalances["idr"] = curIdr + proceeds
                        prefs.saveRealBalance(currentBalances)
                    }

                    onRealTradeSuccess?.invoke(pair, "sell", marketPrice, sellQty, 0.0, 0.0)

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onResult(true, "Order Jual (${agu.analys.util.PriceFormatter.formatCryptoExact(sellQty, 8)} $base) berhasil dieksekusi!")
                    }
                    scope.launch { refreshBalance() }
                } else {
                    if (looksLikeRateLimit(finalRes.message)) onRateLimit(finalRes.message)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onResult(false, "Order Jual Gagal: ${finalRes.message}")
                    }
                }
            }
        }
    }

    private fun looksLikeRateLimit(msg: String): Boolean {
        return msg.contains("429") || msg.lowercase().contains("rate limit") || msg.lowercase().contains("too many requests")
    }

    private fun baseFromPair(pair: String): String {
        val s = pair.lowercase().replace("_", "")
        return when {
            s.endsWith("idr") -> s.removeSuffix("idr")
            s.endsWith("usdt") -> s.removeSuffix("usdt")
            else -> s
        }
    }
}
