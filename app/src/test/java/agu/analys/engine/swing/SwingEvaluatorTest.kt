package agu.analys.engine.swing

import agu.analys.engine.TestData
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

class SwingEvaluatorTest {

    @Test
    fun testSwingQualified() {
        // Bullish trend dengan koreksi sehat untuk swing (mencegah RSI overbought >= 72)
        val history = TestData.generateCandles(200, 800.0, 0.001).toMutableList()
        val lastClose = history.last().close
        val time = history.last().timestamp
        for (i in 1..5) {
            val c = lastClose - (i * 1.5)
            history.add(agu.analys.model.CandleBar(time + (i * 3600000L), c + 1.0, c + 2.0, c - 2.0, c, 5000.0))
        }
        val price = history.last().close
        
        val result = SwingEvaluator.evaluate(price, history)
        
        assertNotNull(result)
        // Should be bullish or at least holding
        assertTrue("Confidence should be reasonable (got ${result.signal.confidence})", result.signal.confidence >= 20)
    }

    @Test
    fun testSwingWithLowData() {
        val price = 1000.0
        val history = TestData.generateCandles(5, 1000.0)
        
        val result = SwingEvaluator.evaluate(price, history)
        
        assertEquals("Action should be HOLD with low data", SignalAction.HOLD, result.signal.action)
        assertTrue("Reasoning should mention data sync", result.signal.reasoning.any { it.contains("data", true) })
    }

    @Test
    fun testSwingBearishTrend() {
        val price = 1000.0
        // Bearish trend setup causing HOLD / defensive signal
        val history = TestData.generateCandles(100, 1500.0, -0.005)
        
        val result = SwingEvaluator.evaluate(price = price, history = history)
        assertNotNull(result)
        // In strong downtrend without setup, action should not be BUY
        assertNotEquals("Should not be BUY in clear downtrend without setup", SignalAction.BUY, result.signal.action)
    }
}
