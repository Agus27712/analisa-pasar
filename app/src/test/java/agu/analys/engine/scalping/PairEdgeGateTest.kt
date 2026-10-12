package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji gerbang kelayakan pair berbasis edge (P4.3).
 *
 * Angka yang diuji berasal dari `data/hasil/u3_toko_usdt_after.json`
 * (40 pair Tokocrypto USDT, 30 hari M1 riil).
 */
class PairEdgeGateTest {

    private val usdtFriction = PairEdgeGate.frictionRoundTripPct(0.4044, 0.4044, 0.08)

    @Test
    fun frictionRoundTrip_matchesFeeTruth() {
        // FEE_TRUTH.md §1.A: 0,4044% per sisi + 2× slippage 0,08% = 0,9688%
        assertEquals(0.9688, usdtFriction, 1e-9)
    }

    @Test
    fun btc_isIneligible_becauseMedianMoveSmallerThanFee() {
        val e = PairEdgeGate.evaluate("BTCUSDT", "USDT", usdtFriction)
        assertEquals(false, e.eligible)
        assertNotNull(e.ratio)
        assertTrue("rasio BTC harus di bawah 1", (e.ratio ?: 0.0) < 1.0)
        // MFE 0,5016% → wajib menangkap ~193% dari pergerakan median
        val capture = PairEdgeGate.captureRequiredPct(e.medianMfe7hPct!!, usdtFriction)
        assertEquals(193.1, capture!!, 0.5)
    }

    @Test
    fun zro_isEligible_becauseMedianMoveSeveralTimesFee() {
        val e = PairEdgeGate.evaluate("ZROUSDT", "USDT", usdtFriction)
        assertEquals(true, e.eligible)
        assertEquals(3.2242, e.medianMfe7hPct!!, 1e-9)
        assertTrue("rasio ZRO harus ≥ 3", (e.ratio ?: 0.0) >= 3.0)
    }

    @Test
    fun pairNameIsCaseInsensitive() {
        val lower = PairEdgeGate.evaluate("zrousdt", "USDT", usdtFriction)
        assertEquals("ZROUSDT", lower.pair)
        assertEquals(true, lower.eligible)
    }

    @Test
    fun unknownPair_isUnrated_notSilentlyEligible() {
        val e = PairEdgeGate.evaluate("FOOUSDT", "USDT", usdtFriction)
        assertNull("pair tanpa data harus null, bukan true/false", e.eligible)
        assertNull(e.ratio)
        assertTrue(e.reason.contains("Belum ada data", ignoreCase = true))
    }

    @Test
    fun idrQuote_hasNoTableYet_soUnrated() {
        assertTrue(PairEdgeGate.mfeTable("IDR").isEmpty())
        val e = PairEdgeGate.evaluate("ADAIDR", "IDR", 0.8144)
        assertNull(e.eligible)
    }

    @Test
    fun zeroFriction_cannotBeRated() {
        val e = PairEdgeGate.evaluate("ZROUSDT", "USDT", 0.0)
        assertNull(e.eligible)
        assertNull(e.ratio)
    }

    @Test
    fun threshold_isTwoTimesFriction() {
        assertEquals(2.0, PairEdgeGate.MIN_MFE_FRICTION_RATIO, 1e-12)
        // ZRO MFE 3,2242% → friksi 1,61% memberi rasio ≈ 2,00 → layak
        val justAbove = PairEdgeGate.evaluate("ZROUSDT", "USDT", 1.61)
        assertEquals(true, justAbove.eligible)
        // friksi 1,62% memberi rasio ≈ 1,99 → tidak layak
        val justBelow = PairEdgeGate.evaluate("ZROUSDT", "USDT", 1.62)
        assertEquals(false, justBelow.eligible)
    }

    @Test
    fun ineligibleList_isSortedWorstFirst_andExcludesEligible() {
        val bad = PairEdgeGate.ineligiblePairs("USDT", usdtFriction)
        assertTrue("harus ada pair tidak layak", bad.isNotEmpty())
        assertTrue("semua elemen harus eligible=false", bad.all { it.eligible == false })
        // urut naik berdasarkan rasio → yang pertama paling parah
        val ratios = bad.mapNotNull { it.ratio }
        assertEquals(ratios.sorted(), ratios)
        assertTrue(bad.size + PairEdgeGate.mfeTable("USDT").keys.count {
            PairEdgeGate.evaluate(it, "USDT", usdtFriction).eligible == true
        } == PairEdgeGate.mfeTable("USDT").size)
    }

    @Test
    fun stablecoinPairs_areDeeplyIneligible() {
        // USDC/USD1/RLUSD hampir tidak bergerak (MFE ≈ 0,01%) — mustahil menutup biaya.
        listOf("USDCUSDT", "USD1USDT", "RLUSDUSDT").forEach { p ->
            val e = PairEdgeGate.evaluate(p, "USDT", usdtFriction)
            assertEquals("$p seharusnya tidak layak", false, e.eligible)
            assertTrue("rasio $p harus < 0,1", (e.ratio ?: 1.0) < 0.1)
        }
    }

    @Test
    fun summarize_partitionsWholeTable() {
        val (ok, no, unknown) = PairEdgeGate.summarize("USDT", usdtFriction)
        assertEquals(0, unknown)
        assertEquals(PairEdgeGate.MFE_7H_USDT.size, ok + no)
        // Dari 40 pair, hanya sebagian kecil yang lolos gerbang edge.
        assertTrue("pair layak harus > 0", ok > 0)
        assertTrue("mayoritas pair tidak layak", no > ok)
    }

    @Test
    fun knownTableHasAllFortyPairs() {
        assertEquals(40, PairEdgeGate.MFE_7H_USDT.size)
    }

    @Test
    fun highFriction_disablesEverything() {
        // Friksi ekstrem (mis. 5% round-trip) → tidak ada pair yang layak.
        val (ok, no, unknown) = PairEdgeGate.summarize("USDT", 5.0)
        assertEquals(0, ok)
        assertEquals(0, unknown)
        assertEquals(PairEdgeGate.MFE_7H_USDT.size, no)
    }

    @Test
    fun reason_distinguishesImpossibleFromThinMargin() {
        // rasio < 1 → pergerakan median lebih kecil daripada biaya = mustahil
        val btc = PairEdgeGate.evaluate("BTCUSDT", "USDT", usdtFriction)
        assertTrue("BTC harus disebut mustahil", btc.reason.contains("mustahil", ignoreCase = true))

        // 1 ≤ rasio < 2 → mungkin secara teori, tapi margin tipis
        val ada = PairEdgeGate.evaluate("ADAUSDT", "USDT", usdtFriction)
        assertEquals(false, ada.eligible)
        assertTrue("ADA harus disebut tipis", ada.reason.contains("tipis", ignoreCase = true))
        assertFalse("ADA tidak boleh disebut mustahil", ada.reason.contains("mustahil", ignoreCase = true))
    }

    @Test
    fun evaluate_neverReturnsEligibleTrue_whenMfeBelowFriction() {
        PairEdgeGate.MFE_7H_USDT.forEach { (pair, mfe) ->
            val e = PairEdgeGate.evaluate(pair, "USDT", usdtFriction)
            if (mfe < usdtFriction) {
                assertFalse("$pair MFE=$mfe < friksi → harus tidak layak", e.eligible == true)
            }
        }
    }
}
