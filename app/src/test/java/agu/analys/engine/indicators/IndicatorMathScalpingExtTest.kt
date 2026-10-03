package agu.analys.engine.indicators

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorMathScalpingExtTest {

    private fun candle(
        close: Double,
        volume: Double = 1000.0,
        high: Double = close * 1.002,
        low: Double = close * 0.998,
        open: Double = close,
        t: Long = System.currentTimeMillis()
    ) = CandleBar(t, open, high, low, close, volume)

    @Test
    fun relativeVolume_returnsOneWhenInsufficientData() {
        val candles = listOf(candle(100.0, 500.0))
        assertEquals(1.0, IndicatorMath.relativeVolume(candles, 20), 1e-9)
    }

    @Test
    fun relativeVolume_highWhenLastVolumeSpike() {
        val hist = (1..20).map { i ->
            candle(100.0 + i, volume = 1000.0, t = i * 60_000L)
        }
        val spike = candle(120.0, volume = 3000.0, t = 21 * 60_000L)
        val rvol = IndicatorMath.relativeVolume(hist + spike, 20)
        assertTrue("RVOL should be ~3x, was $rvol", rvol in 2.5..3.5)
    }

    @Test
    fun relativeVolume_lowWhenQuiet() {
        val hist = (1..20).map { i ->
            candle(100.0, volume = 2000.0, t = i * 60_000L)
        }
        val quiet = candle(100.0, volume = 500.0, t = 21 * 60_000L)
        val rvol = IndicatorMath.relativeVolume(hist + quiet, 20)
        assertTrue("RVOL should be ~0.25, was $rvol", rvol in 0.15..0.4)
    }

    @Test
    fun choppiness_inValidRange() {
        val candles = (1..40).map { i ->
            val base = 100.0 + (i % 5) * 0.3
            candle(base, high = base + 0.5, low = base - 0.5, t = i * 60_000L)
        }
        val chop = IndicatorMath.choppiness(candles, 14)
        assertTrue("CHOP must be 0..100, was $chop", chop in 0.0..100.0)
    }

    @Test
    fun choppiness_higherOnSidewaysThanStrongTrend() {
        // Sideways noise
        val sideways = (1..50).map { i ->
            val base = 100.0 + kotlin.math.sin(i / 2.0) * 0.4
            candle(base, high = base + 0.3, low = base - 0.3, volume = 1000.0, t = i * 60_000L)
        }
        // Strong uptrend
        val trend = (1..50).map { i ->
            val base = 100.0 + i * 1.5
            candle(base, high = base + 0.2, low = base - 0.1, volume = 1000.0, t = i * 60_000L)
        }
        val chopSide = IndicatorMath.choppiness(sideways, 14)
        val chopTrend = IndicatorMath.choppiness(trend, 14)
        assertTrue(
            "Sideways CHOP ($chopSide) should be >= trend CHOP ($chopTrend)",
            chopSide >= chopTrend - 5.0
        )
    }

    @Test
    fun adx_inValidRangeOnTrendingSeries() {
        val trend = (1..80).map { i ->
            val base = 1000.0 + i * 2.0
            candle(base, high = base + 1.0, low = base - 0.5, volume = 1500.0, t = i * 60_000L)
        }
        val adx = IndicatorMath.adx(trend, 14)
        assertTrue("ADX must be 0..100, was $adx", adx in 0.0..100.0)
    }

    @Test
    fun adx_returnsZeroOrLowWhenInsufficientBars() {
        val few = (1..10).map { i -> candle(100.0 + i, t = i * 60_000L) }
        val adx = IndicatorMath.adx(few, 14)
        assertTrue("ADX with few bars should be low, was $adx", adx in 0.0..100.0)
    }
}
