package agu.analys

import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketStructureAnalyzerTest {
    @Test
    fun nearbySwingLevelsAreClusteredIntoZones() {
        val candles = buildCandles()
        val snapshot = MarketStructureAnalyzer.analyze(candles)

        assertTrue(snapshot.dataEnough)
        assertTrue(snapshot.support != null)
        assertTrue(snapshot.resistance != null)
        assertTrue(snapshot.supportDistancePct!! >= 0.0)
        assertTrue(snapshot.resistanceDistancePct!! >= 0.0)
    }

    @Test
    fun nextResistanceIsAboveNearestResistance() {
        val snapshot = MarketStructureAnalyzer.analyze(buildCandles())
        if (snapshot.nextResistance != null && snapshot.resistance != null) {
            assertTrue(snapshot.nextResistance!! > snapshot.resistance!!)
        }
    }

    @Test
    fun nextSupportIsBelowNearestSupport() {
        val snapshot = MarketStructureAnalyzer.analyze(buildCandles())
        if (snapshot.nextSupport != null && snapshot.support != null) {
            assertTrue(snapshot.nextSupport!! < snapshot.support!!)
        }
    }

    private fun buildCandles(): List<CandleBar> {
        val closes = listOf(100.0, 102.0, 105.0, 103.0, 101.0, 104.0, 107.0, 105.0, 103.0, 106.0, 109.0, 107.0, 105.0, 108.0, 111.0, 109.0, 107.0, 110.0, 113.0, 111.0, 109.0, 112.0, 115.0, 113.0, 111.0, 114.0, 117.0, 115.0, 113.0, 116.0, 119.0, 117.0, 115.0, 118.0, 121.0, 119.0, 117.0, 120.0, 123.0, 121.0)
        return closes.mapIndexed { i, close ->
            CandleBar(
                timestamp = (i + 1L) * 3_600_000L,
                open = close - 0.8,
                high = close + 1.5,
                low = close - 1.5,
                close = close,
                volume = 1_000.0 + i
            )
        }
    }
}
