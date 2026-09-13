package agu.analys.engine.scalping

import agu.analys.engine.TestData
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

class ScalpingMtfEvaluatorTest {

    @Test
    fun testEvaluateWithBullishTrend() {
        // Generate bullish trend (0.001 per candle)
        val h1 = TestData.generateCandles(100, 900.0, 0.001)
        val m15 = TestData.generateCandles(100, 950.0, 0.0005)
        val m1 = TestData.generateCandles(100, 980.0, 0.0002).toMutableList()
        // Konsolidasi sehat sebelum entry agar RSI tidak jenuh beli (overbought >= 80)
        val lastClose = m1.last().close
        val time = m1.last().timestamp
        m1.add(CandleBar(time + 60000, lastClose, lastClose + 1.0, lastClose - 2.0, lastClose - 1.0, 1500.0))
        m1.add(CandleBar(time + 120000, lastClose - 1.0, lastClose + 1.0, lastClose - 2.0, lastClose - 0.5, 1600.0))
        m1.add(CandleBar(time + 180000, lastClose - 0.5, lastClose + 4.0, lastClose - 1.0, lastClose + 3.0, 4000.0))
        val price = m1.last().close

        val result = ScalpingMtfEvaluator.evaluate(price, h1, m15, m1)
        
        assertNotNull("Result should not be null", result)
        assertTrue("Confidence should be positive in bullish trend (got ${result?.signal?.confidence})", (result?.signal?.confidence ?: 0) > 0)
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
