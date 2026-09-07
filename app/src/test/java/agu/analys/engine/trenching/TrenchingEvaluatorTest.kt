package agu.analys.engine.trenching

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrenchingEvaluatorTest {

    private val fees = TradingFeeConfig()

    private fun dummyBids(price: Double, count: Int = 10): List<OrderBookItem> {
        return (1..count).map {
            OrderBookItem(price = price * (1.0 - (it * 0.001)), amount = 1.5, total = price * 1.5, isBid = true)
        }
    }

    private fun dummyAsks(price: Double, count: Int = 10): List<OrderBookItem> {
        return (1..count).map {
            OrderBookItem(price = price * (1.0 + (it * 0.001)), amount = 1.0, total = price * 1.0, isBid = false)
        }
    }

    @Test
    fun testAccumulatingAndExpandingFlow() {
        val baseTime = 1000000L
        val candles = listOf(
            CandleBar(baseTime + 1000, 100.0, 101.0, 99.5, 100.8, 100.0),
            CandleBar(baseTime + 2000, 100.8, 101.5, 100.5, 101.2, 120.0),
            CandleBar(baseTime + 3000, 101.2, 102.0, 101.0, 101.8, 150.0),
            CandleBar(baseTime + 4000, 101.8, 102.5, 101.5, 102.3, 180.0)
        )
        val flow = FlowIntelligence.evaluate(candles, dummyBids(102.3), dummyAsks(102.3), 101.0)
        assertTrue(flow.flowPersistence >= 2)
        assertTrue(flow.flowState == FlowState.EXPANDING || flow.flowState == FlowState.ACCUMULATING)
        assertTrue(flow.flowScore >= 60.0)
    }

    @Test
    fun testReversingFlow() {
        val baseTime = 1000000L
        val candles = listOf(
            CandleBar(baseTime + 1000, 100.0, 101.0, 99.5, 100.5, 100.0),
            CandleBar(baseTime + 2000, 100.5, 100.8, 98.0, 98.2, 300.0) // Big dump with huge volume
        )
        // Order book heavy on ask
        val bids = listOf(OrderBookItem(98.0, 0.2, 98.0 * 0.2, true))
        val asks = listOf(OrderBookItem(98.5, 5.0, 98.5 * 5.0, false))

        val flow = FlowIntelligence.evaluate(candles, bids, asks, 100.0)
        assertEquals(FlowState.REVERSING, flow.flowState)
        assertTrue(flow.flowScore < 30.0)
    }

    @Test
    fun testTrenchQualityScoringComposite() {
        val baseTime = 1000000L
        // Tight compressed candles
        val candles = (1..10).map { i ->
            CandleBar(baseTime + (i * 60000), 100.0 + (i * 0.1), 101.2, 99.8, 100.5 + (i * 0.08), 120.0)
        }
        val flow = FlowIntelligence.evaluate(candles, dummyBids(101.0), dummyAsks(101.0), 100.5)
        assertTrue("Composite score should reflect healthy compressed structure", flow.totalTrenchScore > 50.0)
        assertTrue(flow.structureScore >= 70.0)
    }

    @Test
    fun testPullbackDoesNotTriggerInstantBuy() {
        val baseTime = 1000000L
        // 10 HTF Candles establishing compressed trench between 100.0 and 101.5
        val htfCandles = (1..10).map { i ->
            CandleBar(baseTime + (i * 3600000), 100.2, 101.4, 99.8, 100.8, 500.0)
        }
        // LTF candles: steady accumulation then low-volume healthy pullback to support
        val ltfCandles = listOf(
            CandleBar(baseTime + 1000, 100.2, 100.8, 100.1, 100.7, 150.0),
            CandleBar(baseTime + 2000, 100.7, 101.2, 100.6, 101.0, 180.0),
            CandleBar(baseTime + 3000, 101.0, 101.1, 100.4, 100.5, 40.0) // Low volume red candle (pullback)
        )
        val signal = TrenchingEvaluator.evaluate(
            globalContext = null,
            currentPrice = 100.5,
            candles = htfCandles,
            ltfCandles = ltfCandles,
            bids = dummyBids(100.5),
            asks = dummyAsks(100.5),
            hasPosition = false,
            tradingFees = fees
        )
        assertNotNull(signal)
        // Di fase pullback / absorption, TIDAK boleh langsung BUY!
        assertEquals(SignalAction.HOLD, signal!!.action)
        assertTrue(
            "Pattern should indicate PULLBACK or TRENCH_FORMING, but was: ${signal.patternDetected}",
            signal.patternDetected?.contains("PULLBACK") == true || signal.patternDetected?.contains("TRENCH_FORMING") == true
        )
    }

    @Test
    fun testFlowReturnTriggersBuy() {
        val baseTime = 1000000L
        // Tight trench with tight support at 100.0 (low risk distance = high Net R:R)
        val htfCandles = (1..10).map { i ->
            CandleBar(baseTime + (i * 3600000), 100.2, 101.2, 100.0, 100.6, 500.0)
        }
        // LTF candles: low-vol pullback then strong flow return with high persistence
        val ltfCandles = listOf(
            CandleBar(baseTime + 1000, 100.4, 100.6, 100.1, 100.2, 60.0), // Pullback dry volume
            CandleBar(baseTime + 2000, 100.2, 100.7, 100.1, 100.6, 160.0), // Flow returns green
            CandleBar(baseTime + 3000, 100.6, 101.1, 100.5, 101.0, 240.0)  // Expanding volume
        )
        val signal = TrenchingEvaluator.evaluate(
            globalContext = null,
            currentPrice = 101.0,
            candles = htfCandles,
            ltfCandles = ltfCandles,
            bids = dummyBids(101.0, count = 15),
            asks = dummyAsks(101.0, count = 5),
            hasPosition = false,
            tradingFees = fees
        )
        assertNotNull(signal)
        assertEquals("Action should be BUY on flow return, reasons: ${signal!!.reasoning}", SignalAction.BUY, signal.action)
        assertTrue(signal.patternDetected?.contains("ENTRY_READY") == true)
    }

    @Test
    fun testAntiFomoBlocksExtendedEntry() {
        val flow = FlowContext(
            flowState = FlowState.EXPANDING,
            flowScore = 85.0,
            structureScore = 80.0,
            volumeQualityScore = 85.0,
            pullbackQualityScore = 80.0,
            relativeStrengthScore = 70.0,
            vwapScore = 30.0,
            orderBookScore = 65.0,
            totalTrenchScore = 80.0,
            dataQuality = DataQuality.HIGH,
            vwapDistancePct = 6.2, // Too extended from VWAP
            relativeStrength = 1.0,
            volumeTrend = 1.8,
            volumeAcceleration = 0.5,
            flowPersistence = 3,
            isAbsorptionDetected = false,
            isHealthyPullback = false
        )
        val (blocked, reason) = TrenchingAntiFomoGuard.shouldBlockEntry(flow, 110.0, 105.0)
        assertTrue(blocked)
        assertTrue(reason.contains("extended dari VWAP"))
    }

    @Test
    fun testPriceDipDoesNotPanicExit() {
        val flow = FlowContext(
            flowState = FlowState.ABSORBING,
            flowScore = 65.0,
            structureScore = 80.0,
            volumeQualityScore = 85.0,
            pullbackQualityScore = 85.0,
            relativeStrengthScore = 60.0,
            vwapScore = 80.0,
            orderBookScore = 55.0,
            totalTrenchScore = 75.0,
            dataQuality = DataQuality.HIGH,
            vwapDistancePct = 0.5,
            relativeStrength = 1.0,
            volumeTrend = 0.5, // Volume is very low/dry
            volumeAcceleration = -0.2,
            flowPersistence = 1,
            isAbsorptionDetected = false,
            isHealthyPullback = true // Healthy pullback
        )
        // Entry at 100, current at 99 (small dip), support at 97.0
        val exit = TrenchingExitEvaluator.evaluateExit(
            hasPosition = true,
            flowContext = flow,
            entryPrice = 100.0,
            currentPrice = 99.0,
            supportLevel = 97.0
        )
        assertEquals(TrenchingExitState.HOLD, exit)
    }

    @Test
    fun testFlowReversalTriggersExit() {
        val flow = FlowContext(
            flowState = FlowState.REVERSING,
            flowScore = 20.0,
            structureScore = 40.0,
            volumeQualityScore = 30.0,
            pullbackQualityScore = 20.0,
            relativeStrengthScore = 30.0,
            vwapScore = 40.0,
            orderBookScore = 20.0,
            totalTrenchScore = 30.0,
            dataQuality = DataQuality.HIGH,
            vwapDistancePct = -3.5,
            relativeStrength = -2.0,
            volumeTrend = 2.5, // Big sell volume
            volumeAcceleration = 1.0,
            flowPersistence = 0,
            isAbsorptionDetected = false,
            isHealthyPullback = false
        )
        val exit = TrenchingExitEvaluator.evaluateExit(
            hasPosition = true,
            flowContext = flow,
            entryPrice = 100.0,
            currentPrice = 96.0,
            supportLevel = 98.0
        )
        assertEquals(TrenchingExitState.EXIT, exit)
    }

    @Test
    fun testRiskBasedPositionSizing() {
        val balance = 10_000_000.0 // 10 juta IDR
        val entry = 10_000.0
        val stopLoss = 9_800.0 // 2% risk distance
        val size = TrenchingPositionSizer.calculateRiskBasedPositionSize(
            accountBalance = balance,
            maxRiskPerTradePct = 0.02,
            entryPrice = entry,
            stopLossPrice = stopLoss,
            trenchQuality = TrenchQuality.STRONG,
            flowState = FlowState.EXPANDING
        )
        assertTrue(size > 0.0)
        assertTrue("Max allocation should not exceed 25% single coin cap", size <= balance * 0.25)
    }
}
