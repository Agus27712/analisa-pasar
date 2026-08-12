package agu.analys

import agu.analys.engine.LearningTradingEngine
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import org.junit.Assert.assertSame
import org.junit.Test

class LearningTradingEngineTest {
    @Test
    fun liveTickDoesNotRecalculateCompletedCandleSignal() {
        val engine = LearningTradingEngine()
        engine.onTickUpdate(MarketTick("BTCIDR", 100.0, 110.0, 90.0, 1_000_000.0, 1.0))
        engine.replaceCompletedCandles(sampleCandles())

        val before = engine.signalState.value
        engine.onTickUpdate(MarketTick("BTCIDR", 125.0, 130.0, 90.0, 1_000_000.0, 2.0))

        assertSame("A live tick must not recalculate the candle-based signal", before, engine.signalState.value)
    }

    @Test
    fun duplicateCandleTimestampCannotRepaintSignal() {
        val engine = LearningTradingEngine()
        engine.onTickUpdate(MarketTick("BTCIDR", 100.0, 110.0, 90.0, 1_000_000.0, 1.0))
        val candles = sampleCandles()
        engine.replaceCompletedCandles(candles)

        val before = engine.signalState.value
        val last = candles.last()
        engine.onCandleUpdate(last.copy(close = last.close + 25.0, high = last.high + 25.0))

        assertSame("A closed candle timestamp must be immutable to the signal engine", before, engine.signalState.value)
    }

    // Regression coverage for the P1 candle-close synchronization workflow.
    private fun sampleCandles(): List<CandleBar> = (0 until 40).map { i ->
        val base = 100.0 + i * 0.5
        CandleBar(
            timestamp = (i + 1L) * 3_600_000L,
            open = base,
            high = base + 2.0,
            low = base - 2.0,
            close = base + 0.8,
            volume = 1_000.0 + i
        )
    }
}
