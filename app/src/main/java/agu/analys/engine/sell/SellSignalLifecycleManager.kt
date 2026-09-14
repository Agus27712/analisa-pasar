package agu.analys.engine.sell

import agu.analys.model.SellLifecycleState
import agu.analys.model.SellSignalState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SellTransition(
    val symbol: String,
    val newState: SellSignalState,
    val isNewReadyToSell: Boolean,
    val isNewTrailingTriggered: Boolean,
    val isNewStopLossHit: Boolean
) {
    val hasTriggeringTransition: Boolean
        get() = isNewReadyToSell || isNewTrailingTriggered || isNewStopLossHit
}

object SellSignalLifecycleManager {
    // Menyimpan state terakhir per key (real/sim + symbol), HANYA untuk deteksi transisi (edge-trigger notifikasi)
    private val activeStates = ConcurrentHashMap<String, SellLifecycleState>()
    private val lock = ReentrantLock()

    private fun buildKey(symbol: String, isReal: Boolean): String {
        return "${if (isReal) "real" else "sim"}_${symbol.uppercase().trim()}"
    }

    fun process(symbol: String, newState: SellSignalState, isReal: Boolean = false): SellTransition = lock.withLock {
        val key = buildKey(symbol, isReal)
        val previousState = activeStates[key] ?: SellLifecycleState.NOT_HOLDING
        
        // Simpan state baru
        activeStates[key] = newState.state

        val isNewReadyToSell = previousState != SellLifecycleState.READY_TO_SELL && newState.state == SellLifecycleState.READY_TO_SELL
        val isNewTrailingTriggered = previousState != SellLifecycleState.TRAILING_TRIGGERED && newState.state == SellLifecycleState.TRAILING_TRIGGERED
        val isNewStopLossHit = previousState != SellLifecycleState.STOP_LOSS_HIT && newState.state == SellLifecycleState.STOP_LOSS_HIT

        return SellTransition(
            symbol = symbol,
            newState = newState,
            isNewReadyToSell = isNewReadyToSell,
            isNewTrailingTriggered = isNewTrailingTriggered,
            isNewStopLossHit = isNewStopLossHit
        )
    }

    fun reset(symbol: String, isReal: Boolean = false) = lock.withLock {
        activeStates.remove(buildKey(symbol, isReal))
    }
}
