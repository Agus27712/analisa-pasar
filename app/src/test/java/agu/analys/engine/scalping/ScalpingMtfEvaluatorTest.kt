package agu.analys.engine.scalping

import agu.analys.engine.TestData
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

class ScalpingMtfEvaluatorTest {

    @Test
    fun testEvaluateWithBullishTrend() {
        val price = 1000.0
        // Generate bullish trend (0.001 per candle)
        val h1 = TestData.generateCandles(100, 900.0, 0.001)
        val m15 = TestData.generateCandles(100, 950.0, 0.0005)
        val m1 = TestData.generateCandles(100, 990.0, 0.0001)

        val result = ScalpingMtfEvaluator.evaluate(price, h1, m15, m1)
        
        assertNotNull("Result should not be null", result)
        // In a bullish trend, confidence should be reasonably high
        assertTrue("Confidence should be > 30 in bullish trend", result!!.signal.confidence > 30)
    }

    @Test
    fun testEvaluateWithBearishTrend() {
        // Generate bearish trend (-0.001 per candle)
        val h1 = TestData.generateCandles(100, 1100.0, -0.001)
        val m15 = TestData.generateCandles(100, 1050.0, -0.0005)
        val m1 = TestData.generateCandles(100, 1010.0, -0.0001)
        val price = m1.last().close

        val result = ScalpingMtfEvaluator.evaluate(price, h1, m15, m1)
        
        assertNotNull("Result should not be null", result)
        assertEquals("Action should be HOLD in bearish trend", SignalAction.HOLD, result!!.signal.action)
        assertTrue("Confidence should be below 70 in bearish trend", result.signal.confidence < 70)
    }

    @Test
    fun testEvaluateWithInsufficientData() {
        val price = 1000.0
        val h1 = TestData.generateCandles(10, 1000.0)
        val m15 = TestData.generateCandles(10, 1000.0)
        val m1 = TestData.generateCandles(10, 1000.0)

        val result = ScalpingMtfEvaluator.evaluate(price, h1, m15, m1)
        assertNull("Result should be null with insufficient data", result)
    }
}
