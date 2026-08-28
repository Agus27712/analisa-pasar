package agu.analys.viewmodel

import agu.analys.database.AppDatabase
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.service.IndodaxMarketService
import agu.analys.service.IndodaxTradeApiV2
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber

/**
 * Coordinator real INDODAX — Trade API V2 only.
 * Histori: saldo aktif + pair yang pernah di-trade/dijual + watchlist (max 4).
 */
class RealTradeCoordinator(
    private val scope: CoroutineScope,
    private val prefs: AppPreferences
) {
    private val _isRealBuyMode = MutableStateFlow(prefs.isRealBuyModeEnabled)
    val isRealBuyMode: StateFlow<Boolean> = _isRealBuyMode.asStateFlow()

    private val _isPinUnlocked = MutableStateFlow(false)
    val isPinUnlocked: StateFlow<Boolean> = _isPinUnlocked.asStateFlow()

    private val _realIndodaxBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realIndodaxBalance: StateFlow<Map<String, Double>> = _realIndodaxBalance.asStateFlow()

    private val _realFreeBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realFreeBalance: StateFlow<Map<String, Double>> = _realFreeBalance.asStateFlow()

    private val _realLockedBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realLockedBalance: StateFlow<Map<String, Double>> = _realLockedBalance.asStateFlow()

    private val _realOpenOrders = MutableStateFlow<List<RealOpenOrderEntity>>(emptyList())
    val realOpenOrders: StateFlow<List<RealOpenOrderEntity>> = _realOpenOrders.asStateFlow()

    private val _realTrades = MutableStateFlow<List<RealTradeEntity>>(emptyList())
    val realTrades: StateFlow<List<RealTradeEntity>> = _realTrades.asStateFlow()

    private var lastFetchTimeMs = 0L
    private var rateLimitedUntilMs = 0L

    companion object {
        /** Cooldown 60s biar aman dari rate-limit / ban. */
        private const val REFRESH_COOLDOWN_MS = 60_000L
        private const val INTER_REQUEST_DELAY_MS = 2_500L
        private const val MAX_HISTORY_ASSETS = 4
        private const val RATE_LIMIT_COOLDOWN_MS = 180_000L
        /** Poll BUY status max ~30 detik (15 x 2s). */
        private const val BUY_POLL_MAX_ATTEMPTS = 15
        private const val BUY_POLL_INTERVAL_MS = 2_000L
        private const val MIN_EXECUTED_QTY = 0.00000001
    }

    init {
        scope.launch {
            try {
                AppDatabase.getInstance().realTradeDao().getOpenOrdersFlow().collect {
                    _realOpenOrders.value = it
                }
            } catch (e: Exception) {
                Timber.e(e, "Gagal mengamati aliran open orders")
            }
        }
        scope.launch {
            try {
                AppDatabase.getInstance().realTradeDao().getAllTradesFlow().collect {
                    _realTrades.value = it
                }
            } catch (e: Exception) {
                Timber.e(e, "Gagal mengamati aliran histori trade")
            }
        }
    }

    private val _realAvgBuyPrices = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = _realAvgBuyPrices.asStateFlow()

    private val _realAvgBuyPartial = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val realAvgBuyPartial: StateFlow<Map<String, Boolean>> = _realAvgBuyPartial.asStateFlow()

    private val _isFetchingRealBalance = MutableStateFlow(false)
    val isFetchingRealBalance: StateFlow<Boolean> = _isFetchingRealBalance.asStateFlow()

    private val _realTradeStatus = MutableStateFlow<String?>(null)
    val realTradeStatus: StateFlow<String?> = _realTradeStatus.asStateFlow()

    private val _userPublicIp = MutableStateFlow("Memuat IP...")
    val userPublicIp: StateFlow<String> = _userPublicIp.asStateFlow()

    private val _failedPinAttempts = MutableStateFlow(prefs.failedPinAttempts)
    val failedPinAttempts: StateFlow<Int> = _failedPinAttempts.asStateFlow()

    private val _securityAlertMessage = MutableStateFlow<String?>(null)
    val securityAlertMessage: StateFlow<String?> = _securityAlertMessage.asStateFlow()

    fun checkPublicIp() {
        scope.launch {
            _userPublicIp.value = IndodaxMarketService.fetchPublicIp()
        }
    }

    fun clearSecurityAlert() {
        _securityAlertMessage.value = null
    }

    fun hasSecurityPin(): Boolean = prefs.hasSecurityPin()

    fun hasRealCredentialsConfigured(): Boolean =
        prefs.hasSecurityPin() && prefs.hasIndodaxCredentials()

    fun createSecurityPin(pin: String) {
        prefs.setSecurityPin(pin)
        _failedPinAttempts.value = 0
        _isPinUnlocked.value = true
    }

    fun saveRealCredentialsAndPin(pin: String, apiKey: String, secretKey: String): Boolean {
        prefs.setSecurityPin(pin)
        prefs.indodaxApiKey = apiKey
        prefs.indodaxSecretKey = secretKey
        prefs.isRealBuyModeEnabled = true
        _isRealBuyMode.value = true
        _isPinUnlocked.value = true
        _failedPinAttempts.value = 0
        fetchRealBalance()
        return true
    }

    fun wipeSecurityCredentials() {
        prefs.wipeAllRealSecurityData()
        _isRealBuyMode.value = false
        _isPinUnlocked.value = false
        _realIndodaxBalance.value = emptyMap()
        _realAvgBuyPrices.value = emptyMap()
        _realAvgBuyPartial.value = emptyMap()
        _failedPinAttempts.value = 0
        _realTradeStatus.value = "Kredensial API dan PIN telah dihapus."
    }

    fun verifyPin(pin: String): Boolean {
        val valid = prefs.verifySecurityPin(pin)
        if (valid) {
            prefs.resetFailedPinAttempts()
            _failedPinAttempts.value = 0
            _isPinUnlocked.value = true
            return true
        }
        val failedCount = prefs.recordFailedPinAttempt()
        _failedPinAttempts.value = failedCount
        if (failedCount >= 5) {
            wipeSecurityCredentials()
            _securityAlertMessage.value =
                "⚠️ KEAMANAN: PIN salah 5x! Kredensial API & PIN dihapus otomatis."
        }
        return false
    }

    fun lockPin() {
        _isPinUnlocked.value = false
    }

    fun setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean {
        if (enabled) {
            if (pin != null) {
                if (!verifyPin(pin)) return false
            } else if (!_isPinUnlocked.value && prefs.hasSecurityPin()) {
                return false
            }
            prefs.isRealBuyModeEnabled = true
            _isRealBuyMode.value = true
            _isPinUnlocked.value = true
            // Jangan auto-fetch di sini — biar UI (PIN unlock) yang trigger sekali.
            return true
        }
        prefs.isRealBuyModeEnabled = false
        _isRealBuyMode.value = false
        return true
    }

    private fun isRateLimitedNow(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs

    private fun markRateLimited(message: String) {
        rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
        _realTradeStatus.value = "$message · Jeda 3 menit sebelum refresh lagi."
    }

    private fun looksLikeRateLimit(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("-2015") || m.contains("-1003") ||
            m.contains("too many") || m.contains("rate-limit") || m.contains("rate limit")
    }

    private fun baseFromPair(pair: String): String {
        val s = pair.lowercase().replace("_", "")
        return when {
            s.endsWith("idr") -> s.removeSuffix("idr")
            s.endsWith("usdt") -> s.removeSuffix("usdt")
            else -> s
        }
    }

    /**
     * Kandidat histori: saldo aktif dulu, lalu pair yang pernah diingat (setelah jual),
     * lalu watchlist — max 4 biar rate-limit aman.
     */
    private fun buildHistoryCandidates(balance: Map<String, Double>): List<Pair<String, Double>> {
        val active = balance.filter { it.key != "idr" && it.value > 0.00000001 }
        prefs.rememberHistoryBases(active.keys)

        val ordered = linkedSetOf<String>()
        active.keys.forEach { ordered.add(it) }
        prefs.getRecentHistoryBases().forEach { ordered.add(it) }
        prefs.getWatchlist().forEach { sym ->
            val b = baseFromPair(sym)
            if (b.isNotBlank()) ordered.add(b)
        }

        return ordered.take(MAX_HISTORY_ASSETS).map { base ->
            base to (balance[base] ?: 0.0)
        }
    }

    fun fetchRealBalance() {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            _realTradeStatus.value = "Kredensial API INDODAX belum diisi."
            return
        }
        if (_isFetchingRealBalance.value) return

        val now = System.currentTimeMillis()
        if (isRateLimitedNow()) {
            val waitSec = ((rateLimitedUntilMs - now) / 1000L).coerceAtLeast(1)
            _realTradeStatus.value = "Rate-limit aktif. Tunggu ~${waitSec}s lagi."
            return
        }
        if (now - lastFetchTimeMs < REFRESH_COOLDOWN_MS && _realIndodaxBalance.value.isNotEmpty()) {
            _realTradeStatus.value = "Cache aktif (cooldown ${REFRESH_COOLDOWN_MS / 1000}s). Pakai data terakhir."
            return
        }

        scope.launch {
            _isFetchingRealBalance.value = true
            lastFetchTimeMs = now
            _realTradeStatus.value = "Memperbarui saldo INDODAX..."

            val (balances, message) = IndodaxTradeApiV2.getAccount(apiKey, secretKey)
            if (looksLikeRateLimit(message)) {
                markRateLimited(message)
                _isFetchingRealBalance.value = false
                return@launch
            }
            _realTradeStatus.value = message

            if (balances != null) {
                _realIndodaxBalance.value = balances.total
                _realFreeBalance.value = balances.free
                _realLockedBalance.value = balances.locked

                delay(INTER_REQUEST_DELAY_MS)
                val openOk = fetchRealOpenOrdersSafe(apiKey, secretKey)
                if (!openOk) {
                    _isFetchingRealBalance.value = false
                    return@launch
                }

                delay(INTER_REQUEST_DELAY_MS)
                fetchTradesAndAvgSafe(apiKey, secretKey, balances.total)
            }
            _isFetchingRealBalance.value = false
        }
    }

    private suspend fun fetchRealOpenOrdersSafe(apiKey: String, secretKey: String): Boolean {
        return try {
            val (ok, raw) = IndodaxTradeApiV2.openOrders(apiKey, secretKey)
            if (!ok) {
                if (looksLikeRateLimit(raw)) {
                    markRateLimited(raw)
                    return false
                }
                _realTradeStatus.value = "Open orders: $raw"
                return true
            }
            val db = AppDatabase.getInstance().realTradeDao()
            db.clearOpenOrders()
            val jsonArr = try { JSONArray(raw) } catch (e: Exception) {
                Timber.e(e, "Gagal parse JSONArray open orders")
                null
            }
            if (jsonArr != null) {
                val entityList = mutableListOf<RealOpenOrderEntity>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.optJSONObject(i) ?: continue
                    val orderId = obj.optString("orderId", "")
                    if (orderId.isBlank()) continue
                    entityList.add(
                        RealOpenOrderEntity(
                            orderId = orderId,
                            symbol = obj.optString("symbol", "").lowercase(),
                            side = obj.optString("side", "").uppercase(),
                            type = obj.optString("type", "LIMIT").uppercase(),
                            price = obj.optString("price", "0").toDoubleOrNull() ?: 0.0,
                            quantity = obj.optString("origQty", "0").toDoubleOrNull() ?: 0.0,
                            executedQty = obj.optString("executedQty", "0").toDoubleOrNull() ?: 0.0,
                            status = obj.optString("status", "OPEN").uppercase(),
                            time = obj.optLong("time", System.currentTimeMillis())
                        )
                    )
                }
                if (entityList.isNotEmpty()) db.insertOpenOrders(entityList)
            }
            true
        } catch (e: Exception) {
            _realTradeStatus.value = "Gagal open orders: ${e.localizedMessage}"
            true
        }
    }

    private suspend fun fetchTradesAndAvgSafe(
        apiKey: String,
        secretKey: String,
        balance: Map<String, Double>
    ) {
        val candidates = buildHistoryCandidates(balance)

        if (candidates.isEmpty()) {
            _realTradeStatus.value = "Saldo diperbarui. Tidak ada pair untuk histori (tambah ke watchlist: SOL/BTC)."
            return
        }

        val db = AppDatabase.getInstance().realTradeDao()
        val accumulatedEntities = mutableListOf<RealTradeEntity>()
        val newAvg = _realAvgBuyPrices.value.toMutableMap()
        val newPartial = _realAvgBuyPartial.value.toMutableMap()
        var rateLimited = false
        var fetchErrors = 0
        var assetsWithTrades = 0
        var emptyAssets = 0

        for ((index, entry) in candidates.withIndex()) {
            if (index > 0) delay(INTER_REQUEST_DELAY_MS)
            val asset = entry.first
            val currentQty = entry.second

            val (ok, raw) = try {
                IndodaxTradeApiV2.myTrades(apiKey, secretKey, "${asset}idr", limit = 200)
            } catch (e: Exception) {
                fetchErrors++
                _realTradeStatus.value = "Histori $asset gagal: ${e.localizedMessage}"
                continue
            }

            if (!ok) {
                if (looksLikeRateLimit(raw)) {
                    markRateLimited(raw)
                    rateLimited = true
                    break
                }
                fetchErrors++
                _realTradeStatus.value = "Histori $asset: $raw"
                continue
            }

            // Pair yang sukses di-query → ingat (meski qty 0 / sudah dijual)
            prefs.rememberHistoryBase(asset)

            val trades = IndodaxTradeApiV2.parseTradesList(raw)
            if (trades.isEmpty()) {
                emptyAssets++
                continue
            }

            assetsWithTrades++
            var accumulatedBuyQty = 0.0
            var accumulatedBuyCost = 0.0

            for (trade in trades) {
                val id = IndodaxTradeApiV2.tradeIdOf(trade)
                if (id.isBlank()) continue

                val isBuyer = IndodaxTradeApiV2.isBuyerOf(trade)
                val tPrice = IndodaxTradeApiV2.tradePriceOf(trade)
                val tQty = IndodaxTradeApiV2.tradeQtyOf(trade)
                if (tPrice <= 0.0 || tQty <= 0.0) continue

                val tTime = IndodaxTradeApiV2.tradeTimeMs(trade).takeIf { it > 0L }
                    ?: System.currentTimeMillis()

                accumulatedEntities.add(
                    RealTradeEntity(
                        id = id,
                        symbol = "${asset}idr",
                        price = tPrice,
                        qty = tQty,
                        amount = tPrice * tQty,
                        time = tTime,
                        side = if (isBuyer) "BUY" else "SELL",
                        isBuyer = isBuyer
                    )
                )

                if (isBuyer && currentQty > 0.0 && accumulatedBuyQty < currentQty) {
                    val remaining = currentQty - accumulatedBuyQty
                    val qtyToUse = minOf(tQty, remaining)
                    accumulatedBuyQty += qtyToUse
                    accumulatedBuyCost += qtyToUse * tPrice
                }
            }

            if (accumulatedBuyQty > 0.0) {
                newAvg[asset] = accumulatedBuyCost / accumulatedBuyQty
                newPartial[asset] = accumulatedBuyQty + 1e-12 < currentQty
            }
        }

        if (accumulatedEntities.isNotEmpty()) {
            db.insertTrades(accumulatedEntities)
        }
        _realAvgBuyPrices.value = newAvg
        _realAvgBuyPartial.value = newPartial

        if (!rateLimited) {
            val n = accumulatedEntities.size
            val pairList = candidates.joinToString(",") { it.first.uppercase() }
            _realTradeStatus.value = when {
                n > 0 ->
                    "Histori: $n trade ($assetsWithTrades pair: $pairList). Cek tab Riwayat."
                fetchErrors > 0 ->
                    "Saldo OK, histori gagal ($fetchErrors). Cek permission Trade History API."
                emptyAssets > 0 ->
                    "Saldo OK. myTrades kosong utk $emptyAssets/$pairList (7 hari / permission)."
                else ->
                    "Saldo OK, histori kosong."
            }
        }
    }

    fun executeCancelRealOrder(
        symbol: String,
        orderId: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            onResult(false, "API Key atau Secret Key INDODAX belum diisi di Settings.")
            return
        }
        if (isRateLimitedNow()) {
            onResult(false, "Rate-limit aktif. Tunggu dulu sebelum cancel/refresh.")
            return
        }

        scope.launch {
            _realTradeStatus.value = "Membatalkan order $orderId..."
            val (success, message) = IndodaxTradeApiV2.cancelOrder(
                apiKey = apiKey,
                secretKey = secretKey,
                symbol = symbol,
                orderId = orderId
            )
            if (!success && looksLikeRateLimit(message)) {
                markRateLimited(message)
                onResult(false, message)
                return@launch
            }
            _realTradeStatus.value = message
            if (success) {
                AppDatabase.getInstance().realTradeDao().deleteOpenOrderById(orderId)
                delay(INTER_REQUEST_DELAY_MS)
            }
            onResult(success, message)
        }
    }

    /**
     * Poll status order BUY sampai FILLED / PARTIALLY_FILLED dengan executedQty > 0,
     * atau timeout / CANCELLED.
     * Return executedQty aktual (0 jika gagal / timeout).
     */
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
            _realTradeStatus.value = "Menunggu BUY terisi... ($attempt/$BUY_POLL_MAX_ATTEMPTS)"

            val result = IndodaxTradeApiV2.getOrder(
                apiKey = apiKey,
                secretKey = secretKey,
                symbol = pair,
                orderId = orderId.takeIf { it.isNotBlank() && it != "0" },
                clientOrderId = clientOrderId.takeIf { it.isNotBlank() }
            )

            if (!result.success) {
                if (looksLikeRateLimit(result.message)) {
                    markRateLimited(result.message)
                    break
                }
                Timber.w("Poll BUY gagal: ${result.message}")
                continue
            }

            lastStatus = result.status
            lastExecuted = result.executedQty

            when (result.status) {
                "FILLED" -> {
                    Timber.i("BUY fully filled: executed=${result.executedQty}")
                    return result.executedQty.coerceAtLeast(0.0)
                }
                "PARTIALLY_FILLED" -> {
                    if (result.executedQty > MIN_EXECUTED_QTY) {
                        Timber.i("BUY partial fill: executed=${result.executedQty} / ${result.origQty}")
                        return result.executedQty
                    }
                }
                "CANCELLED", "REJECTED", "EXPIRED" -> {
                    Timber.w("BUY order $lastStatus, stop polling")
                    return 0.0
                }
                else -> {
                    // NEW / OPEN — continue polling
                }
            }
        }

        if (lastExecuted > MIN_EXECUTED_QTY) {
            Timber.w("BUY poll timeout, pakai partial executed=$lastExecuted status=$lastStatus")
            return lastExecuted
        }
        Timber.w("BUY poll timeout / belum terisi. requested=$requestedQty status=$lastStatus")
        return 0.0
    }

    fun executeRealTrade(
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
            onResult(false, "API Key atau Secret Key INDODAX belum diisi di Settings.")
            return
        }
        if (isRateLimitedNow()) {
            onResult(false, "Rate-limit aktif. Tunggu 2 menit sebelum order.")
            return
        }

        val quantity = if (type.equals("buy", ignoreCase = true)) {
            if (price <= 0L || amountIdr <= 0.0) 0.0 else amountIdr / price.toDouble()
        } else {
            amountIdr
        }

        if (quantity <= 0.0) {
            onResult(false, "Quantity order tidak valid. Cek harga dan jumlah.")
            return
        }

        scope.launch {
            _realTradeStatus.value = "Mengirim order $type ke INDODAX..."
            val clientOrderId = "agu-${type.lowercase()}-${System.currentTimeMillis()}"
            val buyResult = IndodaxTradeApiV2.createLimitOrderDetailed(
                apiKey = apiKey,
                secretKey = secretKey,
                symbol = pair,
                side = type,
                price = price.toDouble(),
                quantity = quantity,
                clientOrderId = clientOrderId
            )

            if (!buyResult.success && looksLikeRateLimit(buyResult.message)) {
                markRateLimited(buyResult.message)
                onResult(false, buyResult.message)
                return@launch
            }

            if (buyResult.success) {
                prefs.rememberHistoryBase(baseFromPair(pair))

                if (type.equals("buy", ignoreCase = true) && autoLimitSellPrice1 > price) {
                    val executedQty = if (buyResult.executedQty > MIN_EXECUTED_QTY &&
                        buyResult.status == "FILLED"
                    ) {
                        buyResult.executedQty
                    } else {
                        waitForBuyFill(
                            apiKey = apiKey,
                            secretKey = secretKey,
                            pair = pair,
                            orderId = buyResult.orderId,
                            clientOrderId = buyResult.clientOrderId.ifBlank { clientOrderId },
                            requestedQty = quantity
                        )
                    }

                    if (executedQty <= MIN_EXECUTED_QTY) {
                        _realTradeStatus.value =
                            "BUY terkirim tapi belum terisi (atau timeout). TP tidak dipasang. Cek Open Orders."
                        onResult(
                            true,
                            "BUY berhasil di server, tapi belum FILLED.\n" +
                                "Auto-TP dibatalkan biar aman (saldo koin belum ada).\n" +
                                "Pakai tombol Auto Sell manual setelah order terisi."
                        )
                        delay(INTER_REQUEST_DELAY_MS)
                        lastFetchTimeMs = 0L
                        fetchRealBalance()
                        return@launch
                    }

                    val halfQty = executedQty / 2.0
                    var finalMsg = "BUY filled ${"%.8f".format(executedQty)}. "

                    delay(INTER_REQUEST_DELAY_MS)
                    _realTradeStatus.value =
                        "Mengirim TP 1 (${PriceFormatter.formatPrice(autoLimitSellPrice1)}) qty=${"%.8f".format(halfQty)}..."
                    val sellClientOrderId1 = "agu-tp1-${System.currentTimeMillis()}"
                    val (sellSuccess1, sellMessage1) = IndodaxTradeApiV2.createLimitOrder(
                        apiKey = apiKey,
                        secretKey = secretKey,
                        symbol = pair,
                        side = "sell",
                        price = autoLimitSellPrice1,
                        quantity = halfQty,
                        clientOrderId = sellClientOrderId1
                    )

                    if (sellSuccess1) {
                        finalMsg += "TP1 (${PriceFormatter.formatPrice(autoLimitSellPrice1)}) berhasil."
                    } else {
                        finalMsg += "TP1 gagal: $sellMessage1."
                        if (looksLikeRateLimit(sellMessage1)) markRateLimited(sellMessage1)
                    }

                    val actualTp2Price =
                        if (autoLimitSellPrice2 > price) autoLimitSellPrice2 else autoLimitSellPrice1 * 1.03
                    delay(INTER_REQUEST_DELAY_MS)
                    _realTradeStatus.value =
                        "Mengirim TP 2 (${PriceFormatter.formatPrice(actualTp2Price)}) qty=${"%.8f".format(halfQty)}..."
                    val sellClientOrderId2 = "agu-tp2-${System.currentTimeMillis()}"
                    val (sellSuccess2, sellMessage2) = IndodaxTradeApiV2.createLimitOrder(
                        apiKey = apiKey,
                        secretKey = secretKey,
                        symbol = pair,
                        side = "sell",
                        price = actualTp2Price,
                        quantity = halfQty,
                        clientOrderId = sellClientOrderId2
                    )

                    if (sellSuccess2) {
                        finalMsg += " TP2 (${PriceFormatter.formatPrice(actualTp2Price)}) berhasil."
                    } else {
                        finalMsg += " TP2 gagal: $sellMessage2."
                        if (looksLikeRateLimit(sellMessage2)) markRateLimited(sellMessage2)
                    }

                    _realTradeStatus.value = "BUY + TP: $finalMsg"
                    onResult(true, "BUY berhasil (executed ${"%.8f".format(executedQty)})!\nAuto Sell: $finalMsg")
                } else {
                    _realTradeStatus.value = buyResult.message
                    onResult(true, buyResult.message)
                }
                delay(INTER_REQUEST_DELAY_MS)
                lastFetchTimeMs = 0L
                fetchRealBalance()
            } else {
                _realTradeStatus.value = buyResult.message
                onResult(false, buyResult.message)
            }
        }
    }

    fun executeRealAutoSellOnServer(
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
            onResult(false, "API Key atau Secret Key kosong! Harap atur di menu Settings.")
            return
        }
        if (isRateLimitedNow()) {
            onResult(false, "Rate-limit aktif. Tunggu dulu.")
            return
        }

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _realTradeStatus.value = "Mengambil saldo koin real untuk $pair..."
            val baseAsset = baseFromPair(pair)

            if (baseAsset.isEmpty()) {
                onResult(false, "Symbol pair tidak valid.")
                return@launch
            }

            val (balances, err) = IndodaxTradeApiV2.getAccount(apiKey, secretKey)
            if (balances == null) {
                if (looksLikeRateLimit(err)) markRateLimited(err)
                _realTradeStatus.value = "Gagal memuat saldo real: $err"
                onResult(false, "Gagal memuat saldo: $err")
                return@launch
            }

            val availableCoin = balances.free[baseAsset] ?: 0.0
            if (availableCoin <= 0.00000001) {
                _realTradeStatus.value = "Gagal: Saldo koin $baseAsset Anda 0 atau tidak terbaca (API V2)."
                onResult(false, "Gagal: Saldo koin $baseAsset tidak mencukupi untuk dijual.")
                return@launch
            }

            val qtyTp1 = (availableCoin * (tp1Percent / 100.0) * 100_000_000.0).toLong() / 100_000_000.0
            val qtyTp2 = availableCoin - qtyTp1

            var finalMsg = ""
            var successAll = true

            if (tp1Price > 0.0 && qtyTp1 > 0.0) {
                _realTradeStatus.value = "Mengirim TP1 (${PriceFormatter.formatPrice(tp1Price)}) ke INDODAX..."
                val (ok, msg) = IndodaxTradeApiV2.createLimitOrder(
                    apiKey = apiKey,
                    secretKey = secretKey,
                    symbol = pair,
                    side = "sell",
                    price = tp1Price,
                    quantity = qtyTp1,
                    clientOrderId = "agu-manualtp1-${System.currentTimeMillis()}"
                )
                if (ok) {
                    finalMsg += "TP1 berhasil."
                } else {
                    finalMsg += "TP1 gagal: $msg."
                    successAll = false
                    if (looksLikeRateLimit(msg)) {
                        markRateLimited(msg)
                        onResult(false, finalMsg)
                        return@launch
                    }
                }
                delay(INTER_REQUEST_DELAY_MS)
            }

            if (tp2Price > 0.0 && qtyTp2 > 0.0) {
                _realTradeStatus.value = "Mengirim TP2 (${PriceFormatter.formatPrice(tp2Price)}) ke INDODAX..."
                val (ok, msg) = IndodaxTradeApiV2.createLimitOrder(
                    apiKey = apiKey,
                    secretKey = secretKey,
                    symbol = pair,
                    side = "sell",
                    price = tp2Price,
                    quantity = qtyTp2,
                    clientOrderId = "agu-manualtp2-${System.currentTimeMillis()}"
                )
                if (ok) {
                    finalMsg += " TP2 berhasil."
                } else {
                    finalMsg += " TP2 gagal: $msg."
                    successAll = false
                    if (looksLikeRateLimit(msg)) markRateLimited(msg)
                }
            }

            if (successAll) prefs.rememberHistoryBase(baseAsset)
            _realTradeStatus.value = if (successAll) "Split TP Berhasil terpasang di Server!" else "Sebagian/Seluruh Split TP Gagal dipasang."
            onResult(successAll, finalMsg)
        }
    }
}
