package agu.analys.engine.indicators

import agu.analys.engine.TestData
import org.junit.Assert.*
import org.junit.Test

class IndicatorMathTest {

    @Test
    fun testRsiCalculation() {
        val candles = TestData.generateCandles(50, 1000.0, 0.002)
        val rsiValue = IndicatorMath.rsi(candles, 14)
        assertTrue("RSI should be between 0 and 100", rsiValue in 0.0..100.0)
    }

    @Test
    fun testEmaCalculation() {
        val values = listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0)
        val emaVal = IndicatorMath.ema(values, 3)
        assertTrue("EMA should be valid", emaVal > 0.0)

        val series = IndicatorMath.emaSeries(values, 3)
        assertEquals(values.size, series.size)
    }

    @Test
    fun testMacdSeries() {
        val closes = listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 106.0, 107.0, 108.0, 109.0, 110.0)
        val macd = IndicatorMath.macdSeries(closes, 3, 5, 2)
        assertNotNull(macd)
        assertEquals(closes.size, macd.size)
    }

    @Test
    fun testBollingerBands() {
        val closes = listOf(100.0, 101.0, 102.0, 103.0, 104.0)
        val (lower, upper) = IndicatorMath.bollinger(closes, 4)
        assertTrue("Upper band should be >= lower band", upper >= lower)
    }

    @Test
    fun testAtr() {
        val candles = TestData.generateCandles(20, 1000.0)
        val atrVal = IndicatorMath.atr(candles, 14)
        assertTrue("ATR should be >= 0", atrVal >= 0.0)
    }
}
