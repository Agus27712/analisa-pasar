package agu.analys.engine.sell

import agu.analys.config.TradingFeeConfig
import agu.analys.model.MarketTick
import agu.analys.model.SellLifecycleState
import agu.analys.model.SellSignalState
import agu.analys.model.TechnicalIndicators
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SellSignalEvaluatorTest {

    @Test
    fun testNotHolding() {
        val position = SpotPosition(state = SpotPositionState.NO_POSITION)
        val tick = MarketTick("BTCIDR", 100000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig())
        
        assertEquals(SellLifecycleState.NOT_HOLDING, result.state)
    }

    @Test
    fun testNotHolding_ZeroQuantity() {
        val position = SpotPosition(state = SpotPositionState.HOLDING, quantity = 0.0)
        val tick = MarketTick("BTCIDR", 100000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig())
        
        assertEquals(SellLifecycleState.NOT_HOLDING, result.state)
    }

    @Test
    fun testMonitoring() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0
        )
        // Price is slightly down or break-even, so net profit is < 0 due to fees
        val tick = MarketTick("BTCIDR", 100000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig())
        
        assertEquals(SellLifecycleState.MONITORING, result.state)
    }

    @Test
    fun testReadyToSell_HighProfit() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0
        )
        // Price increased by 10%
        val tick = MarketTick("BTCIDR", 110000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig(sellMakerPct = 0.0))
        
        assertEquals(SellLifecycleState.READY_TO_SELL, result.state)
        assertEquals(10.0, result.netProfitPct, 0.01)
        assertEquals("Profit +5%", result.reason)
    }

    @Test
    fun testReadyToSell_TP1Reached() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0,
            tp1Price = 103000.0
        )
        val tick = MarketTick("BTCIDR", 103000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig(sellMakerPct = 0.0))
        
        assertEquals(SellLifecycleState.READY_TO_SELL, result.state)
        assertEquals("Target TP1 tercapai", result.reason)
        assertEquals(3.0, result.netProfitPct, 0.01)
    }

    @Test
    fun testReadyToSell_Near24hHigh() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0
        )
        // 24h high is 102000, current price is 101500 (>= 102000 * 0.98 = 99960) and profit is 1.5%
        val tick = MarketTick("BTCIDR", 101500.0, 102000.0, 95000.0, 1000.0, 1.5)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig(sellMakerPct = 0.0))
        
        assertEquals(SellLifecycleState.READY_TO_SELL, result.state)
        assertEquals("Dekat High 24j", result.reason)
    }

    @Test
    fun testReadyToSell_RsiOverbought() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0
        )
        // Slightly profitable (0.5%), RSI is 75 (overbought)
        val tick = MarketTick("BTCIDR", 100500.0, 105000.0, 95000.0, 1000.0, 0.5)
        val indicators = TechnicalIndicators(rsi14 = 75.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, indicators, TradingFeeConfig(sellMakerPct = 0.0))
        
        assertEquals(SellLifecycleState.READY_TO_SELL, result.state)
        assertEquals("RSI Overbought", result.reason)
    }

    @Test
    fun testTrailingTriggered() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0,
            isTrailingEnabled = true,
            isTrailingTriggered = true
        )
        val tick = MarketTick("BTCIDR", 105000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig(sellMakerPct = 0.0))
        
        assertEquals(SellLifecycleState.TRAILING_TRIGGERED, result.state)
        assertEquals("Trailing stop terpicu", result.reason)
    }

    @Test
    fun testLifecycleManager_TransitionDetectionAndDeduplication() {
        val symbol = "TESTIDR"
        SellSignalLifecycleManager.reset(symbol)

        // 1st tick: Monitoring -> should not trigger notification
        val stateMonitoring = SellSignalState(state = SellLifecycleState.MONITORING)
        val t1 = SellSignalLifecycleManager.process(symbol, stateMonitoring)
        assertFalse(t1.hasTriggeringTransition)

        // 2nd tick: Transition to READY_TO_SELL -> should trigger edge notification
        val stateReady = SellSignalState(state = SellLifecycleState.READY_TO_SELL, reason = "Target TP1 tercapai", netProfitPct = 4.5)
        val t2 = SellSignalLifecycleManager.process(symbol, stateReady)
        assertTrue(t2.hasTriggeringTransition)
        assertTrue(t2.isNewReadyToSell)

        // 3rd tick: Still READY_TO_SELL -> should NOT trigger duplicate notification
        val t3 = SellSignalLifecycleManager.process(symbol, stateReady)
        assertFalse(t3.hasTriggeringTransition)
        assertFalse(t3.isNewReadyToSell)

        // Transition to TRAILING_TRIGGERED -> should trigger edge notification
        val stateTrailing = SellSignalState(state = SellLifecycleState.TRAILING_TRIGGERED, reason = "Trailing stop terpicu")
        val t4 = SellSignalLifecycleManager.process(symbol, stateTrailing)
        assertTrue(t4.hasTriggeringTransition)
        assertTrue(t4.isNewTrailingTriggered)

        // Reset when sold
        SellSignalLifecycleManager.reset(symbol)
        
        // After reset, new READY_TO_SELL should trigger again
        val t5 = SellSignalLifecycleManager.process(symbol, stateReady)
        assertTrue(t5.hasTriggeringTransition)
        assertTrue(t5.isNewReadyToSell)
    }
}
