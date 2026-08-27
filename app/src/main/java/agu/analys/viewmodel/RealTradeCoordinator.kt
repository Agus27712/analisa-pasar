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

/**
 * Coordinator real INDODAX — Trade API V2 only.
 * Rate-limit safe:
 * - portfolio kecil (≤3 aset): fetch SEMUA (biar XRP dll tidak ke-skip)
 * - portfolio besar: max 3 aset
 * - 1x myTrades/aset, jeda 2s, cooldown 20s
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
        private const val REFRESH_COOLDOWN_MS = 20_000L
        private const val INTER_REQUEST_DELAY_MS = 2_000L
        private const val MAX_HISTORY_ASSETS = 3
        private const val RATE_LIMIT_COOLDOWN_MS = 120_000L
    }

    init {
        scope.launch {
            try {
                AppDatabase.getInstance().realTradeDao().getOpenOrdersFlow().collect {
                    _realOpenOrders.value = it
                }
            } catch (_: Exception) {}
        }
        scope.launch {
            try {
                AppDatabase.getInstance().realTradeDao().getAllTradesFlow().collect {
                    _realTrades.value = it
                }
            } catch (_: Exception) {}
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
            fetchRealBalance()
            return true
        }
        prefs.isRealBuyModeEnabled = false
        _isRealBuyMode.value = false
        return true
    }

    private fun isRateLimitedNow(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs

    private fun markRateLimited(message: String) {
        rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
        _realTradeStatus.value = "$message · Jeda 2 menit sebelum refresh lagi."
    }

    private fun looksLikeRateLimit(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("-2015") || m.contains("-1003") ||
            m.contains("too many") || m.contains("rate-limit") || m.contains("rate limit")
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
            _realTradeStatus.value = "Cache aktif (cooldown ${REFRESH_COOLDOWN_MS / 1000}s). Jangan spam refresh."
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
            val jsonArr = runCatching { JSONArray(raw) }.getOrNull()
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

    /**
     * Portfolio ≤3 aset → fetch SEMUA (kasus lo: XRP+MYX+ABYSS).
     * >3 aset → max 3. Jeda 2s antar request.
     */
    private suspend fun fetchTradesAndAvgSafe(
        apiKey: String,
        secretKey: String,
        balance: Map<String, Double>
    ) {
        val active = balance.filter { it.key != "idr" && it.value > 0.00000001 }
        // Jangan sort by qty coin (ABYSS 10 > XRP 1.5) — prefer alphabet stable + ambil semua kalau kecil
        val candidates = if (active.size <= MAX_HISTORY_ASSETS) {
            active.entries.toList()
        } else {
            active.entries.sortedByDescending { it.value }.take(MAX_HISTORY_ASSETS)
        }

        if (candidates.isEmpty()) {
            _realTradeStatus.value = "Saldo diperbarui. Tidak ada aset koin untuk histori."
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
            val asset = entry.key
            val currentQty = entry.value

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

                if (isBuyer && accumulatedBuyQty < currentQty) {
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
            _realTradeStatus.value = when {
                n > 0 ->
                    "Histori: $n trade ter-cache dari $assetsWithTrades aset (cek tab Riwayat)."
                fetchErrors > 0 ->
                    "Saldo OK, histori gagal ($fetchErrors error). Cek permission Trade History API."
                emptyAssets > 0 ->
                    "Saldo OK. API myTrades kosong utk $emptyAssets aset (window 7 hari / belum settle)."
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
            val (success, message) = IndodaxTradeApiV2.createLimitOrder(
                apiKey = apiKey,
                secretKey = secretKey,
                symbol = pair,
                side = type,
                price = price.toDouble(),
                quantity = quantity,
                clientOrderId = clientOrderId
            )

            if (!success && looksLikeRateLimit(message)) {
                markRateLimited(message)
                onResult(false, message)
                return@launch
            }

            if (success) {
                if (type.equals("buy", ignoreCase = true) && autoLimitSellPrice1 > price) {
                    val halfQty = quantity / 2.0
                    delay(INTER_REQUEST_DELAY_MS)
                    _realTradeStatus.value = "Mengirim TP 1 (${PriceFormatter.formatPrice(autoLimitSellPrice1)})..."
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

                    var finalMsg = ""
                    if (sellSuccess1) {
                        finalMsg += "TP1 (${PriceFormatter.formatPrice(autoLimitSellPrice1)}) berhasil."
                    } else {
                        finalMsg += "TP1 gagal: $sellMessage1."
                        if (looksLikeRateLimit(sellMessage1)) markRateLimited(sellMessage1)
                    }

                    val actualTp2Price = if (autoLimitSellPrice2 > price) autoLimitSellPrice2 else autoLimitSellPrice1 * 1.03
                    delay(INTER_REQUEST_DELAY_MS)
                    _realTradeStatus.value = "Mengirim TP 2 (${PriceFormatter.formatPrice(actualTp2Price)})..."
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

                    _realTradeStatus.value = "BUY Sukses! Status TP: $finalMsg"
                    onResult(true, "BUY berhasil di server Indodax!\nAuto Sell: $finalMsg")
                } else {
                    _realTradeStatus.value = message
                    onResult(true, message)
                }
                delay(INTER_REQUEST_DELAY_MS)
                lastFetchTimeMs = 0L
                fetchRealBalance()
            } else {
                _realTradeStatus.value = message
                onResult(false, message)
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
            val pairId = pair.lowercase()
            val baseAsset = when {
                pairId.endsWith("idr") -> pairId.removeSuffix("idr")
                pairId.endsWith("usdt") -> pairId.removeSuffix("usdt")
                pairId.contains("_") -> pairId.split("_").first()
                else -> pairId
            }

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

            _realTradeStatus.value = if (successAll) "Split TP Berhasil terpasang di Server!" else "Sebagian/Seluruh Split TP Gagal dipasang."
            onResult(successAll, finalMsg)
        }
    }
}
