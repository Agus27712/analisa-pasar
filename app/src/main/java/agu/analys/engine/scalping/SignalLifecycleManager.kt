package agu.analys.engine.scalping

import agu.analys.config.StrategyMode
import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SignalTransition(
    val symbol: String,
    val mode: StrategyMode,
    val previousState: LifecycleState,
    val newState: LifecycleState,
    val signal: AISignalState,
    val isNewDetected: Boolean,
    val isNewReady: Boolean
) {
    val hasTriggeringTransition: Boolean
        get() = (isNewDetected || isNewReady) && signal.action == SignalAction.BUY
}

data class TrackedSignal(
    val symbol: String,
    val mode: StrategyMode = StrategyMode.SCALPING,
    var state: LifecycleState = LifecycleState.IDLE,
    var detectedAt: Long = 0L,
    var lastUpdatedAt: Long = 0L,
    var entryPrice: Double = 0.0,
    var targetPrice: Double = 0.0,
    var stopLoss: Double = 0.0,
    var activeSignalState: AISignalState? = null,
    var transition: SignalTransition? = null,
    /** FIX: hysteresis counter — butuh N tick berturut-turut conf rendah sebelum INVALIDATED dari READY */
    var weakTickCount: Int = 0
)

object SignalLifecycleManager {
    private val activeSignals = ConcurrentHashMap<String, TrackedSignal>()
    private val lock = ReentrantLock()

    // Expire scalping signals older than 10 minutes if not triggered
    private const val EXPIRY_SCALPING_MS = 10 * 60 * 1000L
    // Expire swing / macro signals older than 4 hours (dinaikkan dari 2 jam)
    private const val EXPIRY_MACRO_MS = 4 * 60 * 60 * 1000L
    // Hysteresis: butuh 3 tick berturut-turut conf lemah sebelum drop dari READY
    private const val WEAK_TICK_THRESHOLD = 3

    fun normalizeSymbol(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("_", "").replace("-", "")

    private fun cacheKey(symbol: String, mode: StrategyMode): String = "${normalizeSymbol(symbol)}#${mode.name}"

    fun process(
        symbol: String,
        currentPrice: Double,
        rawSignal: AISignalState,
        mode: StrategyMode = StrategyMode.SCALPING
    ): TrackedSignal = lock.withLock {
        val now = System.currentTimeMillis()
        val key = cacheKey(symbol, mode)
        val tracked = activeSignals.getOrPut(key) {
            TrackedSignal(symbol = symbol, mode = mode)
        }

        val previousState = tracked.state

        // 1. Time-based Expiration
        val expiryMs = if (mode == StrategyMode.SCALPING) EXPIRY_SCALPING_MS else EXPIRY_MACRO_MS
        if (tracked.state in listOf(LifecycleState.DETECTED, LifecycleState.CONFIRMING, LifecycleState.READY)) {
            if (tracked.detectedAt > 0L && now - tracked.detectedAt > expiryMs) {
                tracked.state = LifecycleState.EXPIRED
                tracked.weakTickCount = 0
            }
        }

        // 2. Price-based Invalidation (drop below SL before triggered) — hard, instant
        val isPriceBelowStopLoss = (tracked.stopLoss > 0.0 && currentPrice <= tracked.stopLoss) ||
                (rawSignal.stopLoss > 0.0 && currentPrice <= rawSignal.stopLoss)
        if (isPriceBelowStopLoss) {
            if (tracked.state in listOf(LifecycleState.CONFIRMING, LifecycleState.READY, LifecycleState.DETECTED)) {
                tracked.state = LifecycleState.INVALIDATED
                tracked.weakTickCount = 0
            }
        }

        // 3. State progression
        if (isPriceBelowStopLoss) {
            updateSignalData(tracked, rawSignal, now)
        } else if (mode == StrategyMode.SCALPING) {
            // Mode SCALPING: rule spesifik berbasis scalpingStage
            when (tracked.state) {
                LifecycleState.IDLE, LifecycleState.EXPIRED, LifecycleState.INVALIDATED -> {
                    when (rawSignal.scalpingStage) {
                        ScalpingStage.EARLY_ENTRY, ScalpingStage.WAIT_PULLBACK -> {
                            tracked.state = LifecycleState.DETECTED
                            tracked.detectedAt = now
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.ENTRY -> {
                            tracked.state = LifecycleState.CONFIRMING
                            tracked.detectedAt = now
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.STRONG_ENTRY -> {
                            tracked.state = LifecycleState.READY
                            tracked.detectedAt = now
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        else -> {
                            updateSignalData(tracked, rawSignal, now)
                        }
                    }
                }
                LifecycleState.DETECTED -> {
                    when (rawSignal.scalpingStage) {
                        ScalpingStage.ENTRY -> {
                            tracked.state = LifecycleState.CONFIRMING
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.STRONG_ENTRY -> {
                            tracked.state = LifecycleState.READY
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.HOLD -> {
                            tracked.state = LifecycleState.INVALIDATED
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.WATCH -> {
                            tracked.state = LifecycleState.IDLE
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        else -> updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.CONFIRMING -> {
                    when (rawSignal.scalpingStage) {
                        ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY -> {
                            tracked.state = LifecycleState.READY
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        ScalpingStage.HOLD -> {
                            tracked.state = LifecycleState.INVALIDATED
                            tracked.weakTickCount = 0
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
                            tracked.weakTickCount = 0
                            updateSignalData(tracked, rawSignal, now)
                        }
                        else -> updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.TRIGGERED -> {
                    // Kept as triggered until UI/execution resets
                }
            }
        } else {
            // Mode NON-SCALPING (SWING / OFFICE_DAILY / dll)
            // Thresholds:
            // - CONF >= 75 -> READY
            // - CONF >= 65 -> CONFIRMING
            // - CONF >= 50 -> DETECTED
            // FIX: hysteresis saat keluar dari READY — butuh WEAK_TICK_THRESHOLD tick conf lemah
            val isBuy = rawSignal.action == SignalAction.BUY
            val conf = rawSignal.confidence

            when (tracked.state) {
                LifecycleState.IDLE, LifecycleState.EXPIRED, LifecycleState.INVALIDATED -> {
                    if (isBuy) {
                        when {
                            conf >= 75 -> {
                                tracked.state = LifecycleState.READY
                                tracked.detectedAt = now
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf >= 65 -> {
                                tracked.state = LifecycleState.CONFIRMING
                                tracked.detectedAt = now
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf >= 50 -> {
                                tracked.state = LifecycleState.DETECTED
                                tracked.detectedAt = now
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            else -> updateSignalData(tracked, rawSignal, now)
                        }
                    } else {
                        updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.DETECTED -> {
                    if (isBuy) {
                        when {
                            conf >= 75 -> {
                                tracked.state = LifecycleState.READY
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf >= 65 -> {
                                tracked.state = LifecycleState.CONFIRMING
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf < 45 -> {
                                tracked.state = LifecycleState.INVALIDATED
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            else -> updateSignalData(tracked, rawSignal, now)
                        }
                    } else {
                        tracked.state = LifecycleState.INVALIDATED
                        tracked.weakTickCount = 0
                        updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.CONFIRMING -> {
                    if (isBuy) {
                        when {
                            conf >= 75 -> {
                                tracked.state = LifecycleState.READY
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            conf < 50 -> {
                                tracked.state = LifecycleState.INVALIDATED
                                tracked.weakTickCount = 0
                                updateSignalData(tracked, rawSignal, now)
                            }
                            else -> updateSignalData(tracked, rawSignal, now)
                        }
                    } else {
                        tracked.state = LifecycleState.INVALIDATED
                        tracked.weakTickCount = 0
                        updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.READY -> {
                    // FIX HYSTERESIS: jangan langsung INVALIDATED saat conf < 50 sekali
                    // Butuh WEAK_TICK_THRESHOLD tick berturut-turut, kecuali conf sangat jelek (< 30)
                    if (!isBuy || conf < 30) {
                        tracked.state = LifecycleState.INVALIDATED
                        tracked.weakTickCount = 0
                        updateSignalData(tracked, rawSignal, now)
                    } else if (conf < 55) {
                        tracked.weakTickCount += 1
                        if (tracked.weakTickCount >= WEAK_TICK_THRESHOLD) {
                            tracked.state = LifecycleState.INVALIDATED
                            tracked.weakTickCount = 0
                        }
                        updateSignalData(tracked, rawSignal, now)
                    } else {
                        // conf masih sehat → reset counter
                        tracked.weakTickCount = 0
                        updateSignalData(tracked, rawSignal, now)
                    }
                }
                LifecycleState.TRIGGERED -> {
                    // Kept as triggered
                }
            }
        }

        val newState = tracked.state
        val isNewDetected = previousState != LifecycleState.DETECTED && newState == LifecycleState.DETECTED
        val isNewReady = previousState != LifecycleState.READY && newState == LifecycleState.READY

        val transition = SignalTransition(
            symbol = symbol,
            mode = mode,
            previousState = previousState,
            newState = newState,
            signal = rawSignal,
            isNewDetected = isNewDetected,
            isNewReady = isNewReady
        )
        tracked.transition = transition

        return tracked.copy(transition = transition)
    }

    private fun updateSignalData(tracked: TrackedSignal, raw: AISignalState, now: Long) {
        tracked.lastUpdatedAt = now
        if (tracked.stopLoss == 0.0 || (raw.stopLoss > 0.0 && raw.stopLoss > tracked.stopLoss)) {
            tracked.stopLoss = raw.stopLoss
        }
        tracked.entryPrice = raw.entryPrice
        tracked.targetPrice = raw.targetPrice1
        tracked.activeSignalState = raw.copy(
            marketSymbol = tracked.symbol,
            lifecycleState = tracked.state
        )
    }

    fun getSignal(symbol: String, mode: StrategyMode? = null): AISignalState? = lock.withLock {
        val norm = normalizeSymbol(symbol)
        if (mode != null) {
            val key = cacheKey(norm, mode)
            val tracked = activeSignals[key] ?: return@withLock null
            if (tracked.state == LifecycleState.EXPIRED || tracked.state == LifecycleState.INVALIDATED) {
                return@withLock null
            }
            tracked.activeSignalState
        } else {
            val prefix = "$norm#"
            activeSignals.entries
                .firstOrNull { it.key.startsWith(prefix) && it.value.state != LifecycleState.EXPIRED && it.value.state != LifecycleState.INVALIDATED }
                ?.value?.activeSignalState
        }
    }

    fun markTriggered(symbol: String, mode: StrategyMode? = null) = lock.withLock {
        if (mode != null) {
            val key = cacheKey(symbol, mode)
            activeSignals[key]?.let {
                if (it.state == LifecycleState.READY || it.state == LifecycleState.CONFIRMING) {
                    it.state = LifecycleState.TRIGGERED
                    it.lastUpdatedAt = System.currentTimeMillis()
                    it.weakTickCount = 0
                }
            }
        } else {
            val prefix = "${symbol.uppercase()}#"
            activeSignals.forEach { (k, it) ->
                if (k.startsWith(prefix) || k.equals(symbol, ignoreCase = true)) {
                    if (it.state == LifecycleState.READY || it.state == LifecycleState.CONFIRMING) {
                        it.state = LifecycleState.TRIGGERED
                        it.lastUpdatedAt = System.currentTimeMillis()
                        it.weakTickCount = 0
                    }
                }
            }
        }
    }

    fun reset(symbol: String, mode: StrategyMode? = null) = lock.withLock {
        if (mode != null) {
            activeSignals.remove(cacheKey(symbol, mode))
        } else {
            val prefix = "${symbol.uppercase()}#"
            activeSignals.keys.removeAll { it.startsWith(prefix) || it.equals(symbol, ignoreCase = true) }
        }
    }
}
