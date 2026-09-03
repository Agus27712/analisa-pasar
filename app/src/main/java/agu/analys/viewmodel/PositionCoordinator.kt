package agu.analys.viewmodel

import agu.analys.model.PriceAlert
import agu.analys.trading.PriceAlertStore
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PositionCoordinator(
    private val positionStore: SpotPositionStore,
    private val alertStore: PriceAlertStore,
    private val onPositionChanged: () -> Unit = {}
) {
    private val _positionVersion = MutableStateFlow(0L)
    val positionVersion: StateFlow<Long> = _positionVersion.asStateFlow()

    private val _spotPosition = MutableStateFlow(SpotPosition())
    val spotPosition: StateFlow<SpotPosition> = _spotPosition.asStateFlow()

    private val _priceAlerts = MutableStateFlow<List<PriceAlert>>(emptyList())
    val priceAlerts: StateFlow<List<PriceAlert>> = _priceAlerts.asStateFlow()

    private fun notifyPositionChange() {
        _positionVersion.value = System.currentTimeMillis()
        onPositionChanged()
    }

    fun refreshPosition(symbol: String) {
        _spotPosition.value = positionStore.get(symbol)
        notifyPositionChange()
    }

    fun refreshAlerts(symbol: String) {
        _priceAlerts.value = alertStore.getAlertsForSymbol(symbol)
    }

    fun setOwnership(symbol: String, owned: Boolean, entryPrice: Double = 0.0, quantity: Double = 0.0, invested: Double = 0.0, isReal: Boolean = false) {
        if (owned) {
            positionStore.markBought(symbol, entryPrice, invested, quantity, isReal)
        } else {
            positionStore.markSold(symbol)
            agu.analys.engine.sell.SellSignalLifecycleManager.reset(symbol)
        }
        refreshPosition(symbol)
    }

    fun setManualEntry(symbol: String, price: Double, amount: Double, isReal: Boolean = false) {
        positionStore.setManualEntryPrice(symbol, price, amount, isReal)
        refreshPosition(symbol)
    }

    fun setTrailing(symbol: String, enabled: Boolean, pct: Double, refPrice: Double) {
        positionStore.setTrailingStop(symbol, enabled, pct, refPrice)
        refreshPosition(symbol)
    }

    fun setTrailingOrderIdAndUpdateTime(symbol: String, orderId: String?, updateTime: Long) {
        positionStore.setTrailingOrderIdAndUpdateTime(symbol, orderId, updateTime)
        refreshPosition(symbol)
    }

    fun setAutoSell(symbol: String, enabled: Boolean, tp1: Double, tp1P: Double, tp2: Double, tp2P: Double) {
        positionStore.setAutoSellParams(symbol, enabled, tp1, tp1P, tp2, tp2P)
        refreshPosition(symbol)
    }

    fun resetTrailing(symbol: String) {
        positionStore.resetTrailingTrigger(symbol)
        refreshPosition(symbol)
    }

    fun addAlert(alert: PriceAlert, symbol: String) {
        alertStore.addAlert(alert)
        refreshAlerts(symbol)
    }

    fun removeAlert(id: String, symbol: String) {
        alertStore.removeAlert(id)
        refreshAlerts(symbol)
    }

    fun toggleAlert(id: String, symbol: String) {
        alertStore.toggleAlert(id)
        refreshAlerts(symbol)
    }

    fun checkAlertsAndTrailing(symbol: String, price: Double, rsi: Double?) {
        val triggered = alertStore.checkAlerts(symbol, price)
        if (triggered.isNotEmpty()) refreshAlerts(symbol)
        
        val pos = positionStore.get(symbol)
        if (pos.isTrailingEnabled) {
            positionStore.updateTrailingPrice(symbol, price)
            refreshPosition(symbol)
        }
    }
}
