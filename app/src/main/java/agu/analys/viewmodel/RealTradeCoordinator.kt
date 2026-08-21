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
 * Coordinator for real INDODAX trading.
 *
 * Uses the current INDODAX API contract:
 * - /tapi for account, create order and cancel order
 * - Trade API 2.0 dedicated endpoints for order/trade history
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
            _securityAlertMessage.value = "⚠️ KEAMANAN: Percobaan PIN salah 5x berturut-turut! Seluruh kredensial API dan PIN telah dihapus otomatis demi melindungi akun Anda."
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
                calculateRealAvgBuyPrices(apiKey, secretKey, balance)
            }
            _isFetchingRealBalance.value = false
        }
    }

    private suspend fun calculateRealAvgBuyPrices(apiKey: String, secretKey: String, balance: Map<String, Double>) {
        val newAvgPrices = mutableMapOf<String, Double>()
        val nonZeroAssets = balance.filter { it.key != "idr" && it.value > 0.00000001 }

        for ((asset, currentQty) in nonZeroAssets) {
            val symbol = "${asset}idr"
            // Gunakan legacy /tapi tradeHistory yang bisa menarik riwayat lama
            val (success, jsonResponse) = IndodaxTradeApiV2.tradeHistoryLegacy(apiKey, secretKey, symbol, 1000)
            if (success) {
                try {
                    val jsonObj = org.json.JSONObject(jsonResponse)
                    val tradesArray = if (jsonObj.has("return")) {
                        jsonObj.getJSONObject("return").optJSONArray("trades") ?: org.json.JSONArray()
                    } else {
                        org.json.JSONArray()
                    }
                    
                    // Ekstrak ke List dan urutkan berdasarkan waktu transaksi (terbaru ke terlama)
                    val tradeList = mutableListOf<org.json.JSONObject>()
                    for (i in 0 until tradesArray.length()) {
                        val trade = tradesArray.optJSONObject(i)
                        if (trade != null) tradeList.add(trade)
                    }
                    
                    tradeList.sortByDescending { 
                        it.optLong("time", it.optString("trade_time", "0").toLongOrNull() ?: 0L) 
                    }

                    var accumulatedQty = 0.0
                    var accumulatedCost = 0.0
                    for (trade in tradeList) {
                        val isBuyer = if (trade.has("isBuyer")) {
                            trade.optBoolean("isBuyer", false)
                        } else {
                            trade.optString("type", "").equals("buy", ignoreCase = true) || trade.optString("side", "").equals("BUY", ignoreCase = true)
                        }

                        if (isBuyer) {
                            val priceStr = trade.optString("price", "0")
                            // Pada endpoint /tapi, field koin dinamai sesuai koinnya (contoh: "btc")
                            val qtyStr = if (trade.has("qty")) trade.optString("qty", "0") 
                                         else if (trade.has("amount")) trade.optString("amount", "0")
                                         else trade.optString(asset.lowercase(), "0")
                                         
                            val tPrice = priceStr.toDoubleOrNull() ?: 0.0
                            val tQty = qtyStr.toDoubleOrNull() ?: 0.0

                            if (tPrice > 0 && tQty > 0) {
                                val remainingNeeded = currentQty - accumulatedQty
                                if (remainingNeeded <= 0) break

                                val qtyToUse = minOf(tQty, remainingNeeded)
                                accumulatedQty += qtyToUse
                                accumulatedCost += (qtyToUse * tPrice)
                            }
                        }
                    }
                    if (accumulatedQty > 0) {
                        newAvgPrices[asset] = accumulatedCost / accumulatedQty
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // Jeda 1 detik antar request koin agar tidak diblokir Indodax (Error -2015 / Limit API)
            kotlinx.coroutines.delay(1000)
        }
        _realAvgBuyPrices.value = newAvgPrices
    }

    /**
     * UI contract remains unchanged: amountIdr means total IDR for BUY and
     * base-coin quantity for SELL, matching the existing TradeSimulationScreen.
     */
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
