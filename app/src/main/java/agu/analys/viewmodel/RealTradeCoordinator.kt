package agu.analys.viewmodel

import agu.analys.service.IndodaxMarketService
import agu.analys.service.IndodaxTradeApiV2
import agu.analys.util.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinator real INDODAX trading — Trade API 2.0 only.
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
        scope.launch {
            _isFetchingRealBalance.value = true
            val (balance, message) = IndodaxTradeApiV2.getAccount(apiKey, secretKey)
            _realTradeStatus.value = message
            if (balance != null) {
                _realIndodaxBalance.value = balance
                // Avg buy butuh myTrades — jalan di background biar saldo langsung muncul dulu
                calculateRealAvgBuyPrices(apiKey, secretKey, balance)
            }
            _isFetchingRealBalance.value = false
        }
    }

    /**
     * Hitung avg buy dari fills BUY (FIFO mundur dari trade terbaru)
     * sampai qty terkumpul = saldo koin saat ini.
     *
     * Docs V2: myTrades default cuma 24 jam; max window 7 hari.
     * Kita tarik multi-window ~90 hari supaya posisi lama tetap kebaca.
     */
    private suspend fun calculateRealAvgBuyPrices(
        apiKey: String,
        secretKey: String,
        balance: Map<String, Double>
    ) {
        val newAvgPrices = mutableMapOf<String, Double>()
        val nonZeroAssets = balance.filter { it.key != "idr" && it.value > 0.00000001 }

        for ((asset, currentQty) in nonZeroAssets) {
            try {
                val trades = IndodaxTradeApiV2.myTradesMultiWindow(
                    apiKey = apiKey,
                    secretKey = secretKey,
                    symbol = "${asset}idr",
                    lookbackDays = 90,
                    limitPerWindow = 1000
                )

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
                    val tQty = when {
                        trade.has("qty") -> trade.optString("qty", "0").toDoubleOrNull() ?: 0.0
                        trade.has("amount") -> trade.optString("amount", "0").toDoubleOrNull() ?: 0.0
                        else -> 0.0
                    }
                    if (tPrice <= 0.0 || tQty <= 0.0) continue

                    val remaining = currentQty - accumulatedQty
                    if (remaining <= 0.0) break

                    val qtyToUse = minOf(tQty, remaining)
                    accumulatedQty += qtyToUse
                    accumulatedCost += qtyToUse * tPrice
                }

                if (accumulatedQty > 0.0) {
                    newAvgPrices[asset] = accumulatedCost / accumulatedQty
                }
            } catch (_: Exception) {
                // Skip asset ini; avg tetap 0 → UI tampil "Belum Ada Posisi"
            }

            kotlinx.coroutines.delay(400)
        }

        _realAvgBuyPrices.value = newAvgPrices
        if (newAvgPrices.isNotEmpty()) {
            val n = newAvgPrices.size
            _realTradeStatus.value =
                "Saldo + avg buy ${n} koin dihitung dari histori Trade API V2."
        }
    }

    fun executeRealTrade(
        pair: String,
        type: String,
        price: Long,
        amountIdr: Double,
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
            _realTradeStatus.value = message
            if (success) fetchRealBalance()
            onResult(success, message)
        }
    }
}
