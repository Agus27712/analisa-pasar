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
import org.json.JSONObject

/**
 * Coordinator real INDODAX — Trade API V2 only.
 * Fetch pelan: 1x account + max 2x myTrades (hindari -2015).
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

    fun fetchRealBalance() {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            _realTradeStatus.value = "Kredensial API INDODAX belum diisi."
            return
        }
        if (_isFetchingRealBalance.value) return // anti double-tap refresh

        val now = System.currentTimeMillis()
        if (now - lastFetchTimeMs < 15_000L && _realIndodaxBalance.value.isNotEmpty()) {
            _realTradeStatus.value = "Menggunakan data ter-cache (jeda refresh 15s)."
            return
        }

        scope.launch {
            _isFetchingRealBalance.value = true
            lastFetchTimeMs = now
            _realTradeStatus.value = "Memperbarui saldo INDODAX..."

            val (balances, message) = IndodaxTradeApiV2.getAccount(apiKey, secretKey)
            _realTradeStatus.value = message

            if (balances != null) {
                _realIndodaxBalance.value = balances.total
                _realFreeBalance.value = balances.free
                _realLockedBalance.value = balances.locked

                // Fetch Open Orders from Indodax and save to Room DB
                fetchRealOpenOrdersSafe(apiKey, secretKey)

                // Fetch Trades History from Indodax and accumulate in Room DB
                fetchRealTradeHistoriesSafe(apiKey, secretKey, balances.total)

                // Avg buy: max 2 koin, 1 request/koin, jeda panjang
                calculateRealAvgBuyPricesSafe(apiKey, secretKey, balances.total)
            }
            _isFetchingRealBalance.value = false
        }
    }

    private suspend fun fetchRealOpenOrdersSafe(apiKey: String, secretKey: String) {
        try {
            val (ok, raw) = IndodaxTradeApiV2.openOrders(apiKey, secretKey)
            if (ok) {
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
                    db.insertOpenOrders(entityList)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun fetchRealTradeHistoriesSafe(
        apiKey: String,
        secretKey: String,
        balance: Map<String, Double>
    ) {
        val activeAssets = balance
            .filter { it.key != "idr" && it.value > 0.00000001 }
            .keys
            .take(3)
            .toMutableSet()

        if (activeAssets.size < 2) activeAssets.add("btc")
        if (activeAssets.size < 3) activeAssets.add("eth")

        val db = AppDatabase.getInstance().realTradeDao()
        val accumulatedEntities = mutableListOf<RealTradeEntity>()

        for (asset in activeAssets) {
            delay(1000)
            try {
                val trades = IndodaxTradeApiV2.myTradesRecent(apiKey, secretKey, "${asset}idr", limit = 100)
                for (trade in trades) {
                    val id = trade.optString("id", "")
                    if (id.isBlank()) continue

                    val isBuyer = when {
                        trade.has("isBuyer") -> trade.optBoolean("isBuyer", false)
                        else -> {
                            val type = trade.optString("type", "")
                            val side = trade.optString("side", "")
                            type.equals("buy", true) || side.equals("BUY", true)
                        }
                    }

                    val tPrice = trade.optString("price", "0").toDoubleOrNull() ?: 0.0
                    val tQty = trade.optString("qty", "0").toDoubleOrNull()
                        ?: trade.optString("amount", "0").toDoubleOrNull()
                        ?: 0.0
                    if (tPrice <= 0.0 || tQty <= 0.0) continue

                    accumulatedEntities.add(
                        RealTradeEntity(
                            id = id,
                            symbol = "${asset}idr",
                            price = tPrice,
                            qty = tQty,
                            amount = tPrice * tQty,
                            time = trade.optLong("time", System.currentTimeMillis()),
                            side = if (isBuyer) "BUY" else "SELL",
                            isBuyer = isBuyer
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        if (accumulatedEntities.isNotEmpty()) {
            db.insertTrades(accumulatedEntities)
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

        scope.launch {
            _realTradeStatus.value = "Membatalkan order $orderId..."
            val (success, message) = IndodaxTradeApiV2.cancelOrder(
                apiKey = apiKey,
                secretKey = secretKey,
                symbol = symbol,
                orderId = orderId
            )
            _realTradeStatus.value = message
            if (success) {
                AppDatabase.getInstance().realTradeDao().deleteOpenOrderById(orderId)
                delay(1_000)
                fetchRealBalance()
            }
            onResult(success, message)
        }
    }

    /**
     * Aman: max 2 aset, 1 myTrades per aset (7 hari), jeda 1.5s.
     * Stop total jika kena -2015.
     */
    private suspend fun calculateRealAvgBuyPricesSafe(
        apiKey: String,
        secretKey: String,
        balance: Map<String, Double>
    ) {
        val candidates = balance
            .filter { it.key != "idr" && it.value > 0.00000001 }
            .entries
            .sortedByDescending { it.value }
            .take(2) // max 2 koin biar tidak spam API

        if (candidates.isEmpty()) return

        val newAvg = _realAvgBuyPrices.value.toMutableMap()

        for ((asset, currentQty) in candidates) {
            delay(1_500) // jeda antar request

            val trades = try {
                IndodaxTradeApiV2.myTradesRecent(apiKey, secretKey, "${asset}idr", limit = 500)
            } catch (_: Exception) {
                emptyList()
            }

            // Kalau response error string sempat ikut di status sebelumnya, skip
            var accumulatedQty = 0.0
            var accumulatedCost = 0.0

            for (trade in trades) {
                val isBuyer = when {
                    trade.has("isBuyer") -> trade.optBoolean("isBuyer", false)
                    else -> {
                        val type = trade.optString("type", "")
                        val side = trade.optString("side", "")
                        type.equals("buy", true) || side.equals("BUY", true)
                    }
                }
                if (!isBuyer) continue

                val tPrice = trade.optString("price", "0").toDoubleOrNull() ?: 0.0
                val tQty = trade.optString("qty", "0").toDoubleOrNull()
                    ?: trade.optString("amount", "0").toDoubleOrNull()
                    ?: 0.0
                if (tPrice <= 0.0 || tQty <= 0.0) continue

                val remaining = currentQty - accumulatedQty
                if (remaining <= 0.0) break

                val qtyToUse = minOf(tQty, remaining)
                accumulatedQty += qtyToUse
                accumulatedCost += qtyToUse * tPrice
            }

            if (accumulatedQty > 0.0) {
                newAvg[asset] = accumulatedCost / accumulatedQty
            }
        }

        _realAvgBuyPrices.value = newAvg
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
            
            if (success) {
                if (type.equals("buy", ignoreCase = true) && autoLimitSellPrice1 > price) {
                    val halfQty = quantity / 2.0
                    // Kirim order TP 1 (50% koin)
                    delay(800)
                    _realTradeStatus.value = "Mengirim TP 1 (${PriceFormatter.formatPrice(autoLimitSellPrice1)}) - Qty: $halfQty ke INDODAX..."
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
                    }

                    // Kirim order TP 2 (50% koin) seandainya TP2 diaktifkan
                    val actualTp2Price = if (autoLimitSellPrice2 > price) autoLimitSellPrice2 else autoLimitSellPrice1 * 1.03
                    delay(800)
                    _realTradeStatus.value = "Mengirim TP 2 (${PriceFormatter.formatPrice(actualTp2Price)}) - Qty: $halfQty ke INDODAX..."
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
                    }

                    _realTradeStatus.value = "BUY Sukses! Status TP: $finalMsg"
                    onResult(true, "BUY berhasil di server Indodax!\nAuto Sell: $finalMsg")
                } else {
                    _realTradeStatus.value = message
                    onResult(true, message)
                }
                delay(1_000)
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

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _realTradeStatus.value = "Mengambil saldo koin real untuk $pair..."
            val pairId = pair.lowercase()
            val baseAsset = when {
                pairId.endsWith("idr") -> pairId.removeSuffix("idr")
                pairId.endsWith("usdt") -> pairId.removeSuffix("usdt")
                pairId.contains("_") -> pairId.split("_").first()
                else -> pairId // Fallback
            }

            if (baseAsset.isEmpty()) {
                onResult(false, "Symbol pair tidak valid.")
                return@launch
            }

            val (balances, err) = IndodaxTradeApiV2.getAccount(apiKey, secretKey)
            if (balances == null) {
                _realTradeStatus.value = "Gagal memuat saldo real: $err"
                onResult(false, "Gagal memuat saldo: $err")
                return@launch
            }

            // Gunakan available koin yang benar-benar free
            val availableCoin = balances.free[baseAsset] ?: 0.0
            if (availableCoin <= 0.00000001) {
                _realTradeStatus.value = "Gagal: Saldo koin $baseAsset Anda 0 atau tidak terbaca (API V2)."
                onResult(false, "Gagal: Saldo koin $baseAsset tidak mencukupi untuk dijual.")
                return@launch
            }

            // Pembulatan ke bawah yang aman (8 desimal)
            val qtyTp1 = (availableCoin * (tp1Percent / 100.0) * 100_000_000.0).toLong() / 100_000_000.0
            val qtyTp2 = (availableCoin - qtyTp1) 

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
                }
                delay(800)
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
                }
            }

            _realTradeStatus.value = if (successAll) "Split TP Berhasil terpasang di Server!" else "Sebagian/Seluruh Split TP Gagal dipasang."
            onResult(successAll, finalMsg)
            delay(1000)
            fetchRealBalance()
        }
    }
}
