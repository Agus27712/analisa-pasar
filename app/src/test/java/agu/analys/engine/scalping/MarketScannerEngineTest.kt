package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketScannerEngineTest {

    private fun candles(n: Int, start: Double = 50_000.0): List<CandleBar> {
        var p = start
        return (1..n).map { i ->
            val open = p
            p *= 1.001
            CandleBar(i * 60_000L, open, p * 1.001, open * 0.999, p, 1500.0)
        }
    }

    @Test
    fun scan_emptyList_returnsZero() {
        val r = MarketScannerEngine.scan(emptyList())
        assertEquals(0, r.scanned)
        assertEquals(0, r.rows.size)
    }

    @Test
    fun scan_insufficientCandles_skipsPair() {
        val few = candles(5)
        val r = MarketScannerEngine.scan(
            listOf(
                MarketScannerEngine.PairInput(
                    symbol = "BTCUSDT",
                    price = few.last().close,
                    h1 = few,
                    m15 = few,
                    m1 = few
                )
            )
        )
        assertEquals(1, r.scanned)
        // Evaluator returns null if <20 candles → no rows
        assertTrue(r.rows.isEmpty())
    }
}
