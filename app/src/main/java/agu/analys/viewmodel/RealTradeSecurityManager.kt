package agu.analys.viewmodel

import agu.analys.service.IndodaxMarketService
import agu.analys.util.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class RealTradeSecurityManager(
    private val scope: CoroutineScope,
    private val prefs: AppPreferences
) {
    private val _isRealBuyEnabled = MutableStateFlow(false)
    val isRealBuyEnabled: StateFlow<Boolean> = _isRealBuyEnabled.asStateFlow()

    private val _publicIp = MutableStateFlow<String?>(null)
    val publicIp: StateFlow<String?> = _publicIp.asStateFlow()

    private val _isPinRequired = MutableStateFlow(prefs.hasSecurityPin())
    val isPinRequired: StateFlow<Boolean> = _isPinRequired.asStateFlow()

    private val _isPinUnlocked = MutableStateFlow(!prefs.hasSecurityPin())
    val isPinUnlocked: StateFlow<Boolean> = _isPinUnlocked.asStateFlow()

    fun checkPublicIp() {
        scope.launch {
            try {
                _publicIp.value = IndodaxMarketService.fetchPublicIp()
            } catch (e: Exception) {
                Timber.e(e, "Gagal ambil public IP")
                _publicIp.value = "Gagal deteksi IP"
            }
        }
    }

    fun verifyPin(pin: String): Boolean {
        val ok = prefs.verifySecurityPin(pin)
        if (ok) {
            _isPinUnlocked.value = true
        }
        return ok
    }

    fun lockPin() {
        _isRealBuyEnabled.value = false
        _isPinUnlocked.value = !prefs.hasSecurityPin()
    }

    fun setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean {
        _isPinRequired.value = prefs.hasSecurityPin()
        if (!enabled) {
            _isRealBuyEnabled.value = false
            prefs.isRealBuyModeEnabled = false
            return true
        }
        if (!prefs.hasSecurityPin()) {
            _isRealBuyEnabled.value = true
            prefs.isRealBuyModeEnabled = true
            _isPinUnlocked.value = true
            return true
        }
        if (pin != null && verifyPin(pin)) {
            _isRealBuyEnabled.value = true
            prefs.isRealBuyModeEnabled = true
            _isPinUnlocked.value = true
            return true
        }
        return false
    }
}
