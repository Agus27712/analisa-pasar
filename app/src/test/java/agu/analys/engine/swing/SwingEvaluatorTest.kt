package agu.analys.engine.swing

import agu.analys.engine.TestData
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

class SwingEvaluatorTest {

    @Test
    fun testSwingQualified() {
        val price = 1000.0
        // Bullish trend for swing
        val history = TestData.generateCandles(200, 800.0, 0.002)
        
        val result = SwingEvaluator.evaluate(price, history)
        
        assertNotNull(result)
        // Should be bullish or at least holding
        assertTrue("Confidence should be reasonable (got ${result.signal.confidence})", result.signal.confidence > 20)
    }

    @Test
    fun testSwingWithLowData() {
        val price = 1000.0
        val history = TestData.generateCandles(5, 1000.0)
        
        val result = SwingEvaluator.evaluate(price, history)
        
        assertEquals("Action should be HOLD with low data", SignalAction.HOLD, result.signal.action)
        assertTrue("Reasoning should mention data sync", result.signal.reasoning.any { it.contains("data", true) })
    }
}
