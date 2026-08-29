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
        return prefs.verifySecurityPin(pin)
    }

    fun lockPin() {
        _isRealBuyEnabled.value = false
    }

    fun setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean {
        if (!enabled) {
            _isRealBuyEnabled.value = false
            prefs.isRealBuyModeEnabled = false
            return true
        }
        if (!prefs.hasSecurityPin()) {
            _isRealBuyEnabled.value = true
            prefs.isRealBuyModeEnabled = true
            return true
        }
        if (pin != null && verifyPin(pin)) {
            _isRealBuyEnabled.value = true
            prefs.isRealBuyModeEnabled = true
            return true
        }
        return false
    }
}
