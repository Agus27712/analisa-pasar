package agu.analys.engine.scalping

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.model.MarketRegime
import agu.analys.model.RegimeSnapshot
import agu.analys.model.ScalpSetupType
import agu.analys.model.StructureBias
import agu.analys.model.StructureSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpingRiskEngineTest {

    private val tokoFees = TradingFeeConfig(0.10, 0.10, 0.10, 0.10)
    private val indodaxFees = TradingFeeConfig(0.11, 0.21, 0.32, 0.42)

    private fun regime(atrPct: Double = 1.0) = RegimeSnapshot(
        regime = MarketRegime.TRENDING_UP,
        adx = 28.0,
        chop = 35.0,
        atrPct = atrPct
    )

    private fun structure(price: Double) = StructureSnapshot(
        bias = StructureBias.BULLISH,
        support = price * 0.985,
        lastSwingLow = price * 0.985
    )

    private fun input(
        price: Double = 1_000_000.0,
        setup: ScalpSetupType = ScalpSetupType.BREAKOUT_RETEST,
        fees: TradingFeeConfig = tokoFees,
        atrPct: Double = 1.0,
        structure: StructureSnapshot = structure(price)
    ) = ScalpingRiskEngine.Input(
        price = price,
        setup = setup,
        structure = structure,
        regime = regime(atrPct),
        fees = fees
    )

    @Test
    fun entryZone_lowNotAboveHigh() {
        val r = ScalpingRiskEngine.evaluate(input())
        val z = r.levels!!.entryZone
        assertTrue(z.low <= z.high)
        assertTrue(z.low > 0.0)
        assertTrue(z.confirmationTrigger.isNotBlank())
    }

    @Test
    fun levelsOrder_slBelowZone_tp1AboveEntry_tp2AboveTp1() {
        val l = ScalpingRiskEngine.evaluate(input()).levels!!
        assertTrue(l.stopLoss < l.entryZone.low)
        assertTrue(l.takeProfit1 > l.entryZone.high)
        assertTrue(l.takeProfit2 > l.takeProfit1)
        assertEquals(l.stopLoss, l.entryZone.invalidation, 0.0)
    }

    @Test
    fun netRr_matchesFeeCalculator() {
        val l = ScalpingRiskEngine.evaluate(input()).levels!!
        val expected = FeeCalculator.roundTrip(
            entry = l.entryZone.high,
            stopLoss = l.stopLoss,
            takeProfit = l.takeProfit1,
            fees = tokoFees,
            useMaker = false,
            slippagePct = ScalpingRiskEngine.DEFAULT_SLIPPAGE_PCT
        )
        assertEquals(expected.netRr, l.netRr, 1e-9)
        assertTrue(l.netRr > 0.0)
    }

    @Test
    fun defaultMinNetRr_is115() {
        assertEquals(1.15, ScalpingRiskEngine.DEFAULT_MIN_NET_RR, 0.0)
    }

    @Test
    fun tokocryptoFees_validPlan_whenNetRrPasses() {
        val r = ScalpingRiskEngine.evaluate(input())
        assertTrue("netRr=${r.levels!!.netRr}", r.valid)
        assertTrue(r.levels!!.netRr >= 1.15)
    }

    @Test
    fun highIndodaxFees_failMinNetRr_onSmallAtr() {
        val r = ScalpingRiskEngine.evaluate(input(fees = indodaxFees, atrPct = 1.0))
        assertNotNull(r.levels)
        assertFalse(r.valid)
        assertTrue(r.levels!!.netRr < 1.15)
        assertTrue(r.reasons.any { it.contains("WAIT") })
    }

    @Test
    fun resistanceClose_capsTp1_andFailsRr() {
        val price = 1_000_000.0
        val s = structure(price).copy(resistance = price * 1.004)
        val r = ScalpingRiskEngine.evaluate(input(price = price, structure = s))
        assertTrue(r.levels!!.takeProfit1 < price * 1.004)
        assertFalse(r.valid)
        assertTrue(r.reasons.any { it.contains("Resistance dekat") })
    }

    @Test
    fun idrAndUsdtScale_samePercentages() {
        val idr = ScalpingRiskEngine.evaluate(input(price = 1_000_000_000.0)).levels!!
        val usdt = ScalpingRiskEngine.evaluate(input(price = 0.5)).levels!!
        assertEquals(idr.riskPct, usdt.riskPct, 1e-6)
        assertEquals(idr.rewardPct, usdt.rewardPct, 1e-6)
        assertEquals(idr.netRr, usdt.netRr, 1e-6)
    }

    @Test
    fun slDistance_clampedToAtrBounds() {
        val price = 1_000_000.0
        // Support sangat jauh -> SL dibatasi 2x ATR
        val far = structure(price).copy(support = price * 0.80, lastSwingLow = price * 0.80)
        val l = ScalpingRiskEngine.evaluate(input(price = price, structure = far)).levels!!
        val atr = price * 0.01
        assertTrue(l.entryZone.high - l.stopLoss <= 2.0 * atr + 1e-6)

        // Tanpa struktur -> pakai ATR default
        val none = StructureSnapshot()
        val l2 = ScalpingRiskEngine.evaluate(input(price = price, structure = none)).levels!!
        assertTrue(l2.entryZone.high - l2.stopLoss >= 0.8 * atr - 1e-6)
    }

    @Test
    fun missingAtr_usesFallback_noCrash() {
        val r = ScalpingRiskEngine.evaluate(input(atrPct = 0.0))
        assertNotNull(r.levels)
        assertTrue(r.reasons.any { it.contains("ATR belum tersedia") })
    }

    @Test
    fun invalidPriceOrNoSetup_returnsNullLevels() {
        val a = ScalpingRiskEngine.evaluate(input(price = 0.0))
        assertNull(a.levels)
        assertFalse(a.valid)

        val b = ScalpingRiskEngine.evaluate(input(setup = ScalpSetupType.NONE))
        assertNull(b.levels)
        assertFalse(b.valid)
    }

    @Test
    fun feeNote_notEmpty() {
        val l = ScalpingRiskEngine.evaluate(input()).levels!!
        assertTrue(l.feeSlippageNote.contains("Fee"))
        assertTrue(l.riskPct > 0.0 && l.rewardPct > 0.0)
    }
}
