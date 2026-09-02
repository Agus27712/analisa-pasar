package agu.analys.engine.officedaily

import agu.analys.engine.TestData
import agu.analys.model.MarketTick
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

class OfficeDailyEvaluatorTest {

    @Test
    fun testOfficeDailyEvaluationWithGoodData() {
        val history = TestData.generateCandles(200, 800.0, 0.0015)
        val price = history.last().close

        val result = OfficeDailyEvaluator.evaluate(price, history)

        assertNotNull(result)
        assertTrue("Confidence should be at least 20", result.signal.confidence >= 20)
        assertNotNull(result.signal.riskRewardRatio)
    }

    @Test
    fun testOfficeDailyWithLowData() {
        val price = 1000.0
        val history = TestData.generateCandles(5, 1000.0)

        val result = OfficeDailyEvaluator.evaluate(price, history)

        assertEquals("Action should be HOLD with low data", SignalAction.HOLD, result.signal.action)
        assertTrue("Reasoning should mention data sync", result.signal.reasoning.any { it.contains("candle", true) || it.contains("data", true) })
    }

    @Test
    fun testOfficeDailyScreenerFast() {
        val goodTick = MarketTick(
            symbol = "BTCIDR",
            price = 1_000_000_000.0,
            high24h = 1_050_000_000.0,
            low24h = 980_000_000.0,
            volume24h = 15_000_000_000.0,
            change24h = 3.5
        )

        val score = OfficeDailyScreener.evaluateFast(goodTick)
        assertNotNull(score)
        assertTrue("Good tick should qualify for office daily", score.isQualified)
        assertTrue("Score should be >= 7", score.score >= 7)
    }
}
