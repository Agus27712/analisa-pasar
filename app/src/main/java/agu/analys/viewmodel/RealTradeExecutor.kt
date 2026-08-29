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
    private val onStatusUpdate: (String) -> Unit,
    private val onRateLimit: (String) -> Unit,
    private val isRateLimited: () -> Boolean,
    private val refreshBalance: () -> Unit
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

        scope.launch {
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
        if (isRateLimited()) {
            onResult(false, "Rate-limit aktif.")
            return
        }

        val quantity = if (type.equals("buy", ignoreCase = true)) {
            if (price <= 0L || amountIdr <= 0.0) 0.0 else amountIdr / price.toDouble()
        } else {
            amountIdr
        }

        if (quantity <= 0.0) {
            onResult(false, "Quantity order tidak valid.")
            return
        }

        scope.launch {
            onStatusUpdate("Mengirim order $type ke INDODAX...")
            val clientOrderId = "agu-${type.lowercase()}-${System.currentTimeMillis()}"
            val buyResult = IndodaxTradeApiV2.createLimitOrderDetailed(
                apiKey = apiKey, secretKey = secretKey, symbol = pair,
                side = type, price = price.toDouble(), quantity = quantity, clientOrderId = clientOrderId
            )

            if (!buyResult.success && looksLikeRateLimit(buyResult.message)) {
                onRateLimit(buyResult.message)
                onResult(false, buyResult.message)
                return@launch
            }

            if (buyResult.success) {
                agu.analys.engine.scalping.SignalLifecycleManager.markTriggered(pair)
                prefs.rememberHistoryBase(baseFromPair(pair))

                if (type.equals("buy", ignoreCase = true) && autoLimitSellPrice1 > price) {
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
                    onStatusUpdate(buyResult.message)
                    onResult(true, buyResult.message)
                }
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
                onResult(false, "Gagal saldo: $err")
                return@launch
            }

            val free = balances.free[base] ?: 0.0
            if (free <= 0.00000001) {
                onResult(false, "Saldo $base 0.")
                return@launch
            }

            val qty1 = (free * (tp1Percent / 100.0) * 100_000_000.0).toLong() / 100_000_000.0
            val qty2 = free - qty1
            var msg = ""
            var okAll = true

            if (tp1Price > 0.0 && qty1 > 0.0) {
                val (ok, m) = IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", tp1Price, qty1, "agu-manualtp1-${System.currentTimeMillis()}")
                msg += if (ok) "TP1 OK. " else "TP1 Gagal: $m. "
                if (!ok) {
                    okAll = false
                    if (looksLikeRateLimit(m)) { onRateLimit(m); onResult(false, msg); return@launch }
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
            onResult(okAll, msg)
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
