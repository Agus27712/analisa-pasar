package agu.analys.engine.secondwave

import agu.analys.engine.TestData
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

class SecondWaveEvaluatorTest {

    @Test
    fun testSecondWaveQualified() {
        // Mock a prior high and a healthy pullback
        val macro = TestData.generateCandles(60, 100.0).toMutableList()
        // Spike to 200
        macro.addAll(TestData.generateCandles(10, 100.0, 0.1)) // Reach ~200
        // Pullback to 120 (40% drawdown from 200)
        macro.addAll(TestData.generateCandles(20, 200.0, -0.02)) 
        
        val h1 = TestData.generateCandles(40, 120.0, 0.0)
        val m15 = TestData.generateCandles(40, 120.0, 0.001) // Small bounce
        
        val result = SecondWaveEvaluator.evaluate(120.0, macro, h1, m15)
        
        assertNotNull(result)
        assertTrue("Prior run gain should be detected", result.metrics.priorRunGainPct >= 40.0)
        assertTrue("Drawdown should be in healthy zone", result.metrics.drawdownPct in 35.0..78.0)
    }

    @Test
    fun testFastEvaluate() {
        val tick = agu.analys.model.MarketTick(
            symbol = "SOL_IDR",
            price = 1500000.0,
            high24h = 2000000.0,
            low24h = 1400000.0,
            volume24h = 5000000000.0,
            change24h = 5.0
        )
        val result = SecondWaveEvaluator.evaluateFast(tick, 2000000.0, 1400000.0)
        
        assertTrue("High score for good setup", result.score >= 8)
        assertTrue(result.isQualified)
    }
}
