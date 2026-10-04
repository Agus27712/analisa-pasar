package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpingBacktestAdapterTest {

    private fun candles(n: Int, start: Double = 100.0): List<CandleBar> {
        var p = start
        return (1..n).map { i ->
            val open = p
            p = p * (1.0 + 0.002)
            CandleBar(i * 60_000L, open, p * 1.001, open * 0.999, p, 1000.0)
        }
    }

    @Test
    fun run_onUptrendSeries_returnsReport() {
        val report = ScalpingBacktestAdapter.run(candles(80))
        assertTrue(report.note.isNotBlank())
        assertTrue(report.minNetRrUsed >= 1.0)
        assertTrue(report.result.sampleSizeBars >= 80)
    }

    @Test
    fun run_onTinySample_notesInsufficient() {
        val report = ScalpingBacktestAdapter.run(candles(10))
        assertTrue(report.note.contains("kecil") || report.result.totalTrades == 0)
    }
}
