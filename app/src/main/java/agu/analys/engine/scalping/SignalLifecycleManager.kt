package agu.analys.engine.scalping

import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.ScalpingStage

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val activeSignals = ConcurrentHashMap<String, TrackedSignal>()
    private val lock = ReentrantLock()

    // Expire signals older than 10 minutes if not triggered
    private const val EXPIRY_MS = 10 * 60 * 1000L

    fun process(symbol: String, currentPrice: Double, rawSignal: AISignalState): TrackedSignal = lock.withLock {
        val now = System.currentTimeMillis()
        val tracked = activeSignals.getOrPut(symbol) {
            TrackedSignal(symbol)
        }

        // 1. Time-based Expiration
        if (tracked.state in listOf(LifecycleState.DETECTED, LifecycleState.CONFIRMING, LifecycleState.READY)) {
            if (tracked.detectedAt > 0L && now - tracked.detectedAt > EXPIRY_MS) {
                tracked.state = LifecycleState.EXPIRED
            }
        }

        // 2. Price-based Invalidation (drop below SL before triggered)
        if (tracked.state in listOf(LifecycleState.CONFIRMING, LifecycleState.READY)) {
            if (tracked.stopLoss > 0.0 && currentPrice <= tracked.stopLoss) {
                tracked.state = LifecycleState.INVALIDATED
            }
        }

        // 3. State progression — ENTRY juga boleh naik ke READY (bukan cuma STRONG_ENTRY)
        when (tracked.state) {
            LifecycleState.IDLE, LifecycleState.EXPIRED, LifecycleState.INVALIDATED -> {
                when (rawSignal.scalpingStage) {
                    ScalpingStage.EARLY_ENTRY, ScalpingStage.WAIT_PULLBACK -> {
                        tracked.state = LifecycleState.DETECTED
                        tracked.detectedAt = now
                        updateSignalData(tracked, rawSignal, now)
                    }
                    ScalpingStage.ENTRY -> {
                        tracked.state = LifecycleState.CONFIRMING
                        tracked.detectedAt = now
                        updateSignalData(tracked, rawSignal, now)
                    }
                    ScalpingStage.STRONG_ENTRY -> {
                        // Langsung READY biar nggak stuck CONFIRMING
                        tracked.state = LifecycleState.READY
                        tracked.detectedAt = now
                        updateSignalData(tracked, rawSignal, now)
                    }
                    else -> {
                        // WATCH/HOLD: tetap publish signal ke UI, state idle
                        updateSignalData(tracked, rawSignal, now)
                    }
                }
            }
            LifecycleState.DETECTED -> {
                when (rawSignal.scalpingStage) {
                    ScalpingStage.ENTRY -> {
                        tracked.state = LifecycleState.CONFIRMING
                        updateSignalData(tracked, rawSignal, now)
                    }
                    ScalpingStage.STRONG_ENTRY -> {
                        tracked.state = LifecycleState.READY
                        updateSignalData(tracked, rawSignal, now)
                    }
                    ScalpingStage.HOLD -> {
                        tracked.state = LifecycleState.INVALIDATED
                        updateSignalData(tracked, rawSignal, now)
                    }
                    ScalpingStage.WATCH -> {
                        // Mundur ke idle calmly
                        tracked.state = LifecycleState.IDLE
                        updateSignalData(tracked, rawSignal, now)
                    }
                    else -> updateSignalData(tracked, rawSignal, now)
                }
            }
            LifecycleState.CONFIRMING -> {
                when (rawSignal.scalpingStage) {
                    ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY -> {
                        // ENTRY stabil / STRONG → READY (fix bengong)
                        tracked.state = LifecycleState.READY
                        updateSignalData(tracked, rawSignal, now)
                    }
                    ScalpingStage.HOLD -> {
                        tracked.state = LifecycleState.INVALIDATED
                        updateSignalData(tracked, rawSignal, now)
                    }
                    ScalpingStage.WATCH, ScalpingStage.EARLY_ENTRY -> {
                        updateSignalData(tracked, rawSignal, now)
                    }
                    else -> updateSignalData(tracked, rawSignal, now)
                }
            }
            LifecycleState.READY -> {
                when (rawSignal.scalpingStage) {
                    ScalpingStage.HOLD, ScalpingStage.WATCH -> {
                        tracked.state = LifecycleState.INVALIDATED
                        updateSignalData(tracked, rawSignal, now)
                    }
                    else -> updateSignalData(tracked, rawSignal, now)
                }
            }
            LifecycleState.TRIGGERED -> {
                // Kept as triggered until UI/execution resets
            }
        }

        return tracked.copy()
    }

    private fun updateSignalData(tracked: TrackedSignal, raw: AISignalState, now: Long) {
        tracked.lastUpdatedAt = now
        // Keep the safest (highest) stop for long bias — jangan geser SL makin jauh ke bawah tanpa alasan
        if (tracked.stopLoss == 0.0 || (raw.stopLoss > 0.0 && raw.stopLoss > tracked.stopLoss)) {
            tracked.stopLoss = raw.stopLoss
        }
        tracked.entryPrice = raw.entryPrice
        tracked.targetPrice = raw.targetPrice1
        tracked.activeSignalState = raw
    }

    fun markTriggered(symbol: String) = lock.withLock {
        activeSignals[symbol]?.let {
            if (it.state == LifecycleState.READY || it.state == LifecycleState.CONFIRMING) {
                it.state = LifecycleState.TRIGGERED
                it.lastUpdatedAt = System.currentTimeMillis()
            }
        }
    }

    fun reset(symbol: String) = lock.withLock {
        activeSignals.remove(symbol)
    }
}
