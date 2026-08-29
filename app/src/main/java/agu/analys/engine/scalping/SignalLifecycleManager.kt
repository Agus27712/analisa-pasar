package agu.analys.engine.scalping

import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.ScalpingStage

data class TrackedSignal(
    val symbol: String,
    var state: LifecycleState = LifecycleState.IDLE,
    var detectedAt: Long = 0L,
    var lastUpdatedAt: Long = 0L,
    var entryPrice: Double = 0.0,
    var targetPrice: Double = 0.0,
    var stopLoss: Double = 0.0,
    var activeSignalState: AISignalState? = null
)

object SignalLifecycleManager {
    private val activeSignals = mutableMapOf<String, TrackedSignal>()

    // Expire signals older than 3 minutes if not triggered (scalping is fast)
    private const val EXPIRY_MS = 3 * 60 * 1000L 

    fun process(symbol: String, currentPrice: Double, rawSignal: AISignalState): TrackedSignal {
        val now = System.currentTimeMillis()
        val tracked = activeSignals.getOrPut(symbol) { 
            TrackedSignal(symbol)
        }

        // 1. Time-based Expiration
        if (tracked.state in listOf(LifecycleState.DETECTED, LifecycleState.CONFIRMING, LifecycleState.READY)) {
            if (now - tracked.detectedAt > EXPIRY_MS) {
                tracked.state = LifecycleState.EXPIRED
            }
        }

        // 2. Price-based Invalidation (if drops below Stop Loss before Triggered)
        if (tracked.state in listOf(LifecycleState.CONFIRMING, LifecycleState.READY)) {
            if (tracked.stopLoss > 0.0 && currentPrice <= tracked.stopLoss) {
                tracked.state = LifecycleState.INVALIDATED
            }
        }

        // 3. State Progression based on rawSignal's scalping stage
        when (tracked.state) {
            LifecycleState.IDLE, LifecycleState.EXPIRED, LifecycleState.INVALIDATED -> {
                if (rawSignal.scalpingStage in listOf(ScalpingStage.EARLY_ENTRY, ScalpingStage.WAIT_PULLBACK)) {
                    tracked.state = LifecycleState.DETECTED
                    tracked.detectedAt = now
                    updateSignalData(tracked, rawSignal, now)
                } else if (rawSignal.scalpingStage in listOf(ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY)) {
                    tracked.state = LifecycleState.CONFIRMING
                    tracked.detectedAt = now
                    updateSignalData(tracked, rawSignal, now)
                } else {
                    tracked.activeSignalState = rawSignal // Ensure UI gets the HOLD state
                }
            }
            LifecycleState.DETECTED -> {
                if (rawSignal.scalpingStage in listOf(ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY)) {
                    tracked.state = LifecycleState.CONFIRMING
                    updateSignalData(tracked, rawSignal, now)
                } else if (rawSignal.scalpingStage == ScalpingStage.HOLD) {
                    // Momentum lost before it even confirmed
                    tracked.state = LifecycleState.INVALIDATED
                } else {
                    updateSignalData(tracked, rawSignal, now)
                }
            }
            LifecycleState.CONFIRMING -> {
                if (rawSignal.scalpingStage == ScalpingStage.STRONG_ENTRY) {
                    tracked.state = LifecycleState.READY
                    updateSignalData(tracked, rawSignal, now)
                } else if (rawSignal.scalpingStage == ScalpingStage.HOLD) {
                    tracked.state = LifecycleState.INVALIDATED
                } else {
                    updateSignalData(tracked, rawSignal, now)
                }
            }
            LifecycleState.READY -> {
                if (rawSignal.scalpingStage == ScalpingStage.HOLD) {
                    tracked.state = LifecycleState.INVALIDATED
                } else {
                    updateSignalData(tracked, rawSignal, now)
                }
            }
            LifecycleState.TRIGGERED -> {
                // Kept as triggered. (UI or execution engine will reset it when position closed)
            }
        }
        
        // Return a safe copy
        return tracked.copy()
    }

    private fun updateSignalData(tracked: TrackedSignal, raw: AISignalState, now: Long) {
        tracked.lastUpdatedAt = now
        // Keep the worst-case (lowest) stop loss to prevent shifting SL down maliciously
        if (tracked.stopLoss == 0.0 || raw.stopLoss > tracked.stopLoss) {
            tracked.stopLoss = raw.stopLoss
        }
        tracked.entryPrice = raw.entryPrice
        tracked.targetPrice = raw.targetPrice1
        tracked.activeSignalState = raw
    }

    fun markTriggered(symbol: String) {
        activeSignals[symbol]?.let {
            if (it.state == LifecycleState.READY || it.state == LifecycleState.CONFIRMING) {
                it.state = LifecycleState.TRIGGERED
                it.lastUpdatedAt = System.currentTimeMillis()
            }
        }
    }

    fun reset(symbol: String) {
        activeSignals.remove(symbol)
    }
}
