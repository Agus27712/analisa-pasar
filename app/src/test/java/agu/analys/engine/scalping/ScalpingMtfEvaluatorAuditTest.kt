package agu.analys.engine.scalping

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

/**
 * FASE 2 & 7 — Unit test untuk kasus edge yang diaudit dan perbaikannya di Fase 6:
 *  1. Orderbook kosong -> ditahan sebagai HOLD (STEP2_ORDERBOOK_EMPTY), bukan lolos semu.
 *  2. Orderbook stale (>30s) -> ditolak sebagai HOLD (STEP2_ORDERBOOK_STALE).
 *  3. Momentum T0 vs T1 -> T0 ditahan (menunggu orderbook), T1 terpicu BUY setelah konfirmasi.
 *  4. Checkpoint 4 (Net R:R) -> terbukti secara aljabar mencapai Net R:R >= 1.05 dengan fee default.
 */
class ScalpingMtfEvaluatorAuditTest {

    // ---------------------------------------------------------------------
    // Data deterministik (BUKAN random) supaya hasil test selalu sama setiap run.
    // ---------------------------------------------------------------------

    private fun flatCandles(count: Int, price: Double): List<CandleBar> {
        var t = 0L
        return (0 until count).map {
            t += 60_000L
            CandleBar(t, price, price + 2.0, price - 2.0, price, 1000.0)
        }
    }

    /**
     * 17 candle zigzag (naik-turun tipis di sekitar 1000) + 2 candle konsolidasi
     * + 1 candle breakout volume besar. Dipilih supaya:
     *  - RSI 1M ~64 (sehat, tidak overbought >=80)
     *  - volatilitas rendah (~0.43%) -> tidak dianggap noise berbahaya
     *  - price > VWAP DAN VSA breakout terdeteksi (step3 lolos dari dua sisi)
     *  - m15 sengaja HANYA 20 candle (<40) supaya MarketStructureAnalyzer tidak
     *    dipakai dan hasRoomToGrow otomatis true (resistance = price*1.05 fallback)
     */
    private fun bullishM1Candles(): List<CandleBar> {
        val zigzag = listOf(1000.0, 999.0, 1001.0, 999.0, 1001.0, 999.0, 1001.0, 999.0, 1001.0, 999.0, 1001.0, 999.0, 1001.0, 999.0, 1001.0, 999.0, 1000.0)
        val candles = mutableListOf<CandleBar>()
        var t = 0L
        var prevClose = 1000.0
        for (c in zigzag) {
            t += 60_000L
            candles.add(CandleBar(t, prevClose, c + 2.0, c - 2.0, c, 1000.0))
            prevClose = c
        }
        t += 60_000L
        candles.add(CandleBar(t, 1000.0, 1001.0, 998.0, 999.0, 1500.0))       // konsolidasi 1
        t += 60_000L
        candles.add(CandleBar(t, 999.0, 1001.0, 998.0, 999.5, 1600.0))        // konsolidasi 2
        t += 60_000L
        candles.add(CandleBar(t, 999.5, 1010.0, 999.0, 1009.0, 6000.0))       // breakout volume besar
        return candles
    }

    private val h1 = flatCandles(20, 1000.0)
    private val m15 = flatCandles(20, 1000.0) // fallback resistance, hasRoomToGrow selalu true
    private val bullishBids = listOf(OrderBookItem(price = 1008.5, amount = 20.0, total = 20.0 * 1008.5, isBid = true))
    private val bullishAsks = listOf(OrderBookItem(price = 1009.5, amount = 10.0, total = 10.0 * 1009.5, isBid = false))

    // ---------------------------------------------------------------------
    // VERIFIKASI FASE 6: Checkpoint 4 (Net R:R >= 1.05) tercapai dengan fee default
    // ---------------------------------------------------------------------

    @Test
    fun `FASE 6 - Checkpoint4 NetRR sekarang dapat tercapai secara aljabar dengan fee default`() {
        val fees = TradingFeeConfig()
        val price = 1_000_000.0
        val totalCostPct = (fees.buyTakerPct + fees.sellTakerPct) + (2 * 0.08)
        val targetNetRr = 1.25

        var stopPct = 0.5
        while (stopPct <= 3.0) {
            val sl = price * (1.0 - stopPct / 100.0)
            val requiredGrossRewardPct = (targetNetRr * (stopPct + totalCostPct) + totalCostPct)
            val tp2 = price * (1.0 + maxOf(1.5, requiredGrossRewardPct) / 100.0)
            val feeResult = FeeCalculator.roundTrip(price, sl, tp2, fees, false, 0.08)
            assertTrue("Net R:R harus >= 1.05 untuk stopPct $stopPct", feeResult.netRr >= 1.05)
            stopPct += 0.25
        }
    }

    @Test
    fun `full pipeline - setup sebagus mungkin (step1-4 lolos) menghasilkan sinyal BUY`() {
        val price = bullishM1Candles().last().close
        val result = ScalpingMtfEvaluator.evaluate(
            price = price,
            h1Candles = h1,
            m15Candles = m15,
            m1Candles = bullishM1Candles(),
            bids = bullishBids,
            asks = bullishAsks,
            symbol = "BTCIDR"
        )

        assertNotNull(result)
        val audit = result!!.audit
        assertTrue("step1 (danger/room to grow) seharusnya lolos", audit.step1Ok)
        assertTrue("step2 (buy pressure orderbook nyata) seharusnya lolos", audit.step2Ok)
        assertTrue("step3 (VWAP/VSA) seharusnya lolos", audit.step3Ok)
        assertTrue("step4 (RR) sekarang LOLOS berkat rumus aljabar presisi", audit.step4Ok)
        assertEquals(SignalAction.BUY, result.signal.action)
        assertNull(audit.rejectionReason)
    }

    // ---------------------------------------------------------------------
    // Kasus edge #1: Orderbook kosong
    // ---------------------------------------------------------------------

    @Test
    fun `FASE 6 - orderbook kosong ditahan rapi di Checkpoint2 sebagai HOLD, bukan lolos semu`() {
        val price = bullishM1Candles().last().close
        val result = ScalpingMtfEvaluator.evaluate(
            price = price,
            h1Candles = h1,
            m15Candles = m15,
            m1Candles = bullishM1Candles(),
            bids = emptyList(),
            asks = emptyList(),
            symbol = "BTCIDR"
        )

        assertNotNull(result)
        val audit = result!!.audit
        assertTrue(audit.isOrderBookEmpty)
        assertFalse("step2 harus GAGAL saat orderbook kosong", audit.step2Ok)
        assertEquals("STEP2_ORDERBOOK_EMPTY", audit.rejectionReason)
        assertEquals(SignalAction.HOLD, result.signal.action)
    }

    // ---------------------------------------------------------------------
    // Kasus edge #2: Orderbook stale (basi)
    // ---------------------------------------------------------------------

    @Test
    fun `FASE 6 - orderbook stale 40 detik ditolak di Checkpoint2, orderbook fresh lolos`() {
        val price = bullishM1Candles().last().close

        val fresh = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(),
            bids = bullishBids, asks = bullishAsks, symbol = "BTCIDR", orderBookAgeMs = 0L
        )
        val stale = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(),
            bids = bullishBids, asks = bullishAsks, symbol = "BTCIDR", orderBookAgeMs = 40_000L
        )

        assertNotNull(fresh); assertNotNull(stale)
        assertEquals(0L, fresh!!.audit.orderBookAgeMs)
        assertEquals(40_000L, stale!!.audit.orderBookAgeMs)
        assertTrue("Orderbook fresh (<30s) harus lolos step2", fresh.audit.step2Ok)
        assertFalse("Orderbook stale (>30s) harus ditolak di step2", stale.audit.step2Ok)
        assertEquals("STEP2_ORDERBOOK_STALE", stale.audit.rejectionReason)
        assertEquals(SignalAction.BUY, fresh.signal.action)
        assertEquals(SignalAction.HOLD, stale.signal.action)
    }

    // ---------------------------------------------------------------------
    // Kasus edge #3: Momentum datang sebelum orderbook tersedia
    // ---------------------------------------------------------------------

    @Test
    fun `FASE 6 - breakout T0 tanpa orderbook dibedakan dengan jelas dari T1 dengan orderbook`() {
        val price = bullishM1Candles().last().close

        // T0: harga breakout, orderbook BELUM tersedia sama sekali
        val t0 = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(),
            bids = emptyList(), asks = emptyList(), symbol = "BTCIDR"
        )
        // T1: 1 detik kemudian, orderbook sudah tersedia dan bullish
        val t1 = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(),
            bids = bullishBids, asks = bullishAsks, symbol = "BTCIDR", orderBookAgeMs = 1000L
        )

        assertNotNull(t0); assertNotNull(t1)
        assertFalse("T0 tanpa orderbook harus ditahan di step2", t0!!.audit.step2Ok)
        assertTrue("T1 dengan orderbook bullish harus lolos step2", t1!!.audit.step2Ok)
        assertEquals(SignalAction.HOLD, t0.signal.action)
        assertEquals(SignalAction.BUY, t1.signal.action)
    }
}
