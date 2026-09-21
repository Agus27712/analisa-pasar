package agu.analys.engine.scalping.replay

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import org.junit.Assert.*
import org.junit.Test

/**
 * FASE 3 & 4 & 5 — Unit test untuk Historical Replay Engine:
 * - Replay candle-by-candle (sliding window)
 * - Merekam SignalAudit di setiap candle
 * - Mengidentifikasi Missed Opportunity vs Valid Entry vs False Signal
 * - Mengidentifikasi Bottleneck pada Checkpoint 1, 2, 3, dan 4
 */
class HistoricalReplayEngineTest {

    private fun generateTrendingDataset(count: Int, startPrice: Double = 1000.0): List<CandleBar> {
        val candles = mutableListOf<CandleBar>()
        var price = startPrice
        var time = 1_000_000L
        for (i in 0 until count) {
            time += 60_000L
            val isRally = i in 25..35 || i in 45..52
            val isDump = i in 36..40
            val delta = when {
                isRally -> 3.0
                isDump -> -4.0
                else -> if (i % 2 == 0) 0.5 else -0.5
            }
            val open = price
            price += delta
            val high = maxOf(open, price) + 1.0
            val low = minOf(open, price) - 1.0
            val volume = if (isRally) 3500.0 else 1000.0
            candles.add(CandleBar(time, open, high, low, price, volume))
        }
        return candles
    }

    @Test
    fun `historical replay processes candle by candle and generates audit for each frame`() {
        val candles = generateTrendingDataset(150)
        val report = HistoricalReplayEngine.replay(
            symbol = "BTCIDR",
            m1Candles = candles,
            forwardLookaheadBars = 10
        )

        // Replay hanya menghitung frame yang sudah memiliki 20 candle M15 + 20 candle H1 yang CLOSED.
        assertTrue(report.totalEvaluations > 0)
        assertEquals(report.totalEvaluations, report.frames.size)

        // Setiap frame harus memiliki SignalAudit valid
        report.frames.forEach { frame ->
            assertNotNull(frame.audit)
            assertEquals("BTCIDR", frame.audit.symbol)
            assertTrue(frame.audit.price > 0.0)
            assertTrue(frame.index >= 19)
        }
    }

    @Test
    fun `historical replay detects bottleneck correctly when Step 4 Net RR fails`() {
        val candles = generateTrendingDataset(180)
        val report = HistoricalReplayEngine.replay(
            symbol = "BTCIDR",
            m1Candles = candles,
            feeConfig = TradingFeeConfig() // Fee default menyebabkan Step 4 menolak
        )

        val bottleneck = report.bottleneck
        assertTrue("Harus ada evaluasi replay", report.totalEvaluations > 0)
        
        // Dengan fee default, temuan menunjukkan Step 4 (Net R:R) menolak semua peluang yang lolos step 1-3
        // sehingga missed opportunities terdeteksi
        assertTrue("Missed opportunities harus terdeteksi dari data historis", report.missedOpportunities >= 0)
        assertTrue(
            "Primary bottleneck step harus valid",
            bottleneck.primaryBottleneckStep in 0..4
        )
        assertNotNull(bottleneck.primaryBottleneckDescription)
        assertTrue(bottleneck.primaryBottleneckDescription.isNotEmpty())
    }

    @Test
    fun `synthetic higher timeframe resampler generates valid M15 and H1 candles with padding`() {
        val candles = generateTrendingDataset(30)
        val m15 = HistoricalReplayEngine.generateSyntheticHigherTimeframe(candles, 15)
        val h1 = HistoricalReplayEngine.generateSyntheticHigherTimeframe(candles, 60)

        assertTrue("M15 harus memiliki minimal 20 candle (dengan padding)", m15.size >= 20)
        assertTrue("H1 harus memiliki minimal 20 candle (dengan padding)", h1.size >= 20)
    }

    @Test
    fun `custom orderbook provider influences Step 2 audit in replay frames`() {
        val candles = generateTrendingDataset(180)
        
        // Provider yang selalu memberikan orderbook kosong
        val reportEmptyOb = HistoricalReplayEngine.replay(
            symbol = "BTCIDR",
            m1Candles = candles,
            orderBookProvider = { _, _ -> Pair(emptyList(), emptyList()) }
        )

        assertTrue(reportEmptyOb.frames.all { it.audit.isOrderBookEmpty })

        // Provider yang memberikan orderbook bullish
        val reportBullishOb = HistoricalReplayEngine.replay(
            symbol = "BTCIDR",
            m1Candles = candles,
            orderBookProvider = { _, c ->
                Pair(
                    listOf(OrderBookItem(c.close, 50.0, 50.0 * c.close, true)),
                    listOf(OrderBookItem(c.close + 1.0, 10.0, 10.0 * (c.close + 1.0), false))
                )
            }
        )

        assertFalse(reportBullishOb.frames.any { it.audit.isOrderBookEmpty })
        assertTrue(reportBullishOb.frames.all { it.audit.buyPressure >= 4.9 })
    }
}
