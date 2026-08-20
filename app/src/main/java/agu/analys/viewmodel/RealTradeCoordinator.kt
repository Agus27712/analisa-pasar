package agu.analys.viewmodel

import agu.analys.service.IndodaxMarketService
import agu.analys.util.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Koordinator khusus untuk mengelola data dan operasi Real Trading (Indodax):
 * - Kredensial API & PIN Keamanan
 * - Saldo Real (IDR & Kripto) langsung dari API Indodax
 * - Eksekusi Order Riil
 * - Deteksi IP Publik untuk Whitelist API
 * - Proteksi Brute-Force PIN & Auto-Wipe
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
            val ip = IndodaxMarketService.fetchPublicIp()
            _userPublicIp.value = ip
        }
    }

    fun clearSecurityAlert() {
        _securityAlertMessage.value = null
    }

    fun hasSecurityPin(): Boolean = prefs.hasSecurityPin()

    fun hasRealCredentialsConfigured(): Boolean = prefs.hasSecurityPin() && prefs.hasIndodaxCredentials()

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
        } else {
            val failedCount = prefs.recordFailedPinAttempt()
            _failedPinAttempts.value = failedCount
            if (failedCount >= 5) {
                wipeSecurityCredentials()
                _securityAlertMessage.value = "⚠️ KEAMANAN: Percobaan PIN salah 5x berturut-turut! Seluruh kredensial API dan PIN telah dihapus otomatis demi melindungi akun Anda."
            }
            return false
        }
    }

    fun lockPin() {
        _isPinUnlocked.value = false
    }

    fun setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean {
        if (enabled) {
            if (pin != null) {
                if (!verifyPin(pin)) return false
            } else {
                if (!_isPinUnlocked.value && prefs.hasSecurityPin()) return false
            }
            prefs.isRealBuyModeEnabled = true
            _isRealBuyMode.value = true
            _isPinUnlocked.value = true
            fetchRealBalance()
            return true
        } else {
            prefs.isRealBuyModeEnabled = false
            _isRealBuyMode.value = false
            return true
        }
    }

    fun fetchRealBalance() {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            _realTradeStatus.value = "Kredensial API Indodax belum diisi."
            return
        }
        scope.launch {
            _isFetchingRealBalance.value = true
            val (bal, msg) = IndodaxMarketService.fetchAccountBalanceDetails(apiKey, secretKey)
            _realTradeStatus.value = msg
            if (bal != null) {
                _realIndodaxBalance.value = bal
            }
            _isFetchingRealBalance.value = false
        }
    }

    fun executeRealTrade(pair: String, type: String, price: Long, amountIdr: Double, onResult: (Boolean, String) -> Unit) {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            onResult(false, "API Key atau Secret Key Indodax belum diisi di Settings.")
            return
        }
        scope.launch {
            _realTradeStatus.value = "Mengirim order $type ke Indodax..."
            val (success, message) = IndodaxMarketService.placeTradeOrder(apiKey, secretKey, pair, type, price, amountIdr)
            _realTradeStatus.value = message
            if (success) {
                fetchRealBalance()
            }
            onResult(success, message)
        }
    }
}
