package agu.analys.engine.scalping

import agu.analys.config.StrategyMode
import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SignalLifecycleManagerTest {

    private val symbol = "BTCIDR"

    @Before
    fun setUp() {
        SignalLifecycleManager.reset(symbol)
    }

    @Test
    fun testNonScalping_SwingTransitionDetectionAndDeduplication() {
        SignalLifecycleManager.reset(symbol, StrategyMode.SWING)

        // 1st tick: HOLD signal -> state remains IDLE, no notification
        val signalHold = AISignalState(
            action = SignalAction.HOLD,
            confidence = 20,
            entryPrice = 100_000.0,
            stopLoss = 95_000.0,
            targetPrice1 = 110_000.0
        )
        val t1 = SignalLifecycleManager.process(symbol, 100_000.0, signalHold, StrategyMode.SWING)
        assertEquals(LifecycleState.IDLE, t1.state)
        assertFalse(t1.transition?.hasTriggeringTransition == true)

        // 2nd tick: Qualified BUY with moderate confidence (55%) -> Transition to DETECTED (edge trigger)
        val signalDetected = AISignalState(
            action = SignalAction.BUY,
            confidence = 55,
            entryPrice = 101_000.0,
            stopLoss = 96_000.0,
            targetPrice1 = 112_000.0
        )
        val t2 = SignalLifecycleManager.process(symbol, 101_000.0, signalDetected, StrategyMode.SWING)
        assertEquals(LifecycleState.DETECTED, t2.state)
        assertTrue(t2.transition?.isNewDetected == true)
        assertTrue(t2.transition?.hasTriggeringTransition == true)

        // 3rd tick: Same DETECTED state -> No duplicate spam notification
        val t3 = SignalLifecycleManager.process(symbol, 101_200.0, signalDetected.copy(confidence = 58), StrategyMode.SWING)
        assertEquals(LifecycleState.DETECTED, t3.state)
        assertFalse(t3.transition?.isNewDetected == true)
        assertFalse(t3.transition?.hasTriggeringTransition == true)

        // 4th tick: Upgraded to CONFIRMING (68%)
        val signalConfirming = signalDetected.copy(confidence = 68)
        val t4 = SignalLifecycleManager.process(symbol, 102_000.0, signalConfirming, StrategyMode.SWING)
        assertEquals(LifecycleState.CONFIRMING, t4.state)
        assertFalse(t4.transition?.hasTriggeringTransition == true)

        // 5th tick: Upgraded to READY (80%) -> Transition to READY (edge trigger)
        val signalReady = signalDetected.copy(confidence = 80)
        val t5 = SignalLifecycleManager.process(symbol, 102_500.0, signalReady, StrategyMode.SWING)
        assertEquals(LifecycleState.READY, t5.state)
        assertTrue(t5.transition?.isNewReady == true)
        assertTrue(t5.transition?.hasTriggeringTransition == true)

        // 6th tick: Repeated READY tick -> No duplicate notification
        val t6 = SignalLifecycleManager.process(symbol, 102_600.0, signalReady.copy(confidence = 82), StrategyMode.SWING)
        assertEquals(LifecycleState.READY, t6.state)
        assertFalse(t6.transition?.isNewReady == true)
        assertFalse(t6.transition?.hasTriggeringTransition == true)

        // 7th tick: Invalidation (action switches to HOLD) -> State becomes INVALIDATED
        val t7 = SignalLifecycleManager.process(symbol, 102_000.0, signalHold, StrategyMode.SWING)
        assertEquals(LifecycleState.INVALIDATED, t7.state)
        assertFalse(t7.transition?.hasTriggeringTransition == true)

        // 8th tick: Recovering directly to strong BUY (85%) from INVALIDATED -> Edge trigger READY
        val t8 = SignalLifecycleManager.process(symbol, 103_000.0, signalReady.copy(confidence = 85), StrategyMode.SWING)
        assertEquals(LifecycleState.READY, t8.state)
        assertTrue(t8.transition?.isNewReady == true)
        assertTrue(t8.transition?.hasTriggeringTransition == true)
    }

    @Test
    fun testModeIsolation_SameSymbolDifferentModes() {
        // Mode SWING reaches READY
        val swingSignal = AISignalState(action = SignalAction.BUY, confidence = 80)
        val swingTracked = SignalLifecycleManager.process(symbol, 50_000.0, swingSignal, StrategyMode.SWING)
        assertEquals(LifecycleState.READY, swingTracked.state)

        // Mode SECOND_WAVE with neutral signal remains IDLE
        val secondWaveSignal = AISignalState(action = SignalAction.HOLD, confidence = 20)
        val secondWaveTracked = SignalLifecycleManager.process(symbol, 50_000.0, secondWaveSignal, StrategyMode.SECOND_WAVE)
        assertEquals(LifecycleState.IDLE, secondWaveTracked.state)

        // Verify SWING state was not overwritten by SECOND_WAVE
        val recheckSwing = SignalLifecycleManager.process(symbol, 50_100.0, swingSignal, StrategyMode.SWING)
        assertEquals(LifecycleState.READY, recheckSwing.state)
    }

    @Test
    fun testScalping_BackwardsCompatibility() {
        SignalLifecycleManager.reset(symbol, StrategyMode.SCALPING)

        // Scalping uses scalpingStage
        val scalpingEarly = AISignalState(
            action = SignalAction.BUY,
            scalpingStage = ScalpingStage.EARLY_ENTRY,
            confidence = 60
        )
        val t1 = SignalLifecycleManager.process(symbol, 10_000.0, scalpingEarly, StrategyMode.SCALPING)
        assertEquals(LifecycleState.DETECTED, t1.state)
        assertTrue(t1.transition?.hasTriggeringTransition == true)

        val scalpingStrong = AISignalState(
            action = SignalAction.BUY,
            scalpingStage = ScalpingStage.STRONG_ENTRY,
            confidence = 90
        )
        val t2 = SignalLifecycleManager.process(symbol, 10_100.0, scalpingStrong, StrategyMode.SCALPING)
        assertEquals(LifecycleState.READY, t2.state)
        assertTrue(t2.transition?.hasTriggeringTransition == true)
    }

    @Test
    fun testPriceDropBelowStopLoss_InvalidatesState() {
        val signal = AISignalState(
            action = SignalAction.BUY,
            confidence = 80,
            entryPrice = 100.0,
            stopLoss = 90.0,
            targetPrice1 = 120.0
        )
        val t1 = SignalLifecycleManager.process(symbol, 100.0, signal, StrategyMode.SWING)
        assertEquals(LifecycleState.READY, t1.state)

        // Price drops below stop loss (89.0 <= 90.0)
        val t2 = SignalLifecycleManager.process(symbol, 89.0, signal, StrategyMode.SWING)
        assertEquals(LifecycleState.INVALIDATED, t2.state)
        assertFalse(t2.transition?.hasTriggeringTransition == true)
    }
}
