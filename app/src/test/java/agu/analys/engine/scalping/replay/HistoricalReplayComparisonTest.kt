package agu.analys.engine.scalping.replay

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import org.junit.Assert.*
import org.junit.Test

/**
 * FASE 7 & 8 — Re-Backtest & Komparasi Sebelum vs Sesudah:
 * Menjalankan Historical Replay pada dataset tren intraday,
 * membandingkan performa sinyal, bottleneck, valid entries, dan false signals.
 */
class HistoricalReplayComparisonTest {

    private fun generateMultiBreakoutDataset(count: Int = 120): List<CandleBar> {
        val candles = mutableListOf<CandleBar>()
        var price = 1000.0
        var time = 1_700_000_000_000L

        for (i in 0 until count) {
            time += 60_000L
            // Buat pola realistis: konsolidasi, breakout volume, rally, lalu koreksi
            val isBreakout1 = i in 25..33
            val isBreakout2 = i in 65..75
            val isDump = i in 85..92
            
            val delta = when {
                isBreakout1 -> 4.5
                isBreakout2 -> 5.0
                isDump -> -6.0
                else -> if (i % 2 == 0) 0.4 else -0.3
            }
            val open = price
            price += delta
            val high = maxOf(open, price) + 1.2
            val low = minOf(open, price) - 1.2
            val volume = when {
                isBreakout1 || isBreakout2 -> 5000.0
                isDump -> 4500.0
                else -> 800.0
            }
            candles.add(CandleBar(time, open, high, low, price, volume))
        }
        return candles
    }

    @Test
    fun `FASE 7 & 8 - Komparasi performa sinyal dan eliminasi bottleneck Net RR`() {
        val dataset = generateMultiBreakoutDataset(100)

        // Jalankan Historical Replay dengan feed order book sehat (Fase 7)
        val report = HistoricalReplayEngine.replay(
            symbol = "BTCIDR",
            m1Candles = dataset,
            targetProfitPct = 1.5,
            stopLossPct = 1.0,
            forwardLookaheadBars = 15,
            feeConfig = TradingFeeConfig()
        )

        // Verifikasi metrik empiris Fase 7:
        println("=== LAPORAN HISTORICAL REPLAY (FASE 7) ===")
        println("Total Evaluasi: ${report.totalEvaluations}")
        println("Sinyal BUY Terpicu: ${report.totalSignalsTriggered}")
        println("Valid Entries (Profit): ${report.validEntries}")
        println("False Signals (Cut Loss): ${report.falseSignals}")
        println("Missed Opportunities: ${report.missedOpportunities}")
        println("Avoided Losses (Modal Selamat): ${report.avoidedLosses}")
        println("Win Rate Sinyal: ${String.format("%.2f", report.winRatePct)}%")
        println("Peluang Momentum Teranalisis: ${report.bottleneck.totalMomentumOpportunities}")
        println("Step 1 Rejections: ${report.bottleneck.step1Rejections}")
        println("Step 2 Rejections: ${report.bottleneck.step2Rejections}")
        println("Step 3 Rejections: ${report.bottleneck.step3Rejections}")
        println("Step 4 Rejections: ${report.bottleneck.step4Rejections}")
        println("Diagnosis Bottleneck: ${report.bottleneck.primaryBottleneckDescription}")
        println("=========================================")

        // Pembuktian Fase 8:
        // 1. Sinyal BUY berhasil muncul (bukan 0 lagi karena bug Step 4 sudah diperbaiki)
        assertTrue("Sinyal BUY harus terpicu pada breakout yang valid", report.totalSignalsTriggered > 0)
        
        // 2. Terdapat valid entries yang menghasilkan profit
        assertTrue("Harus terdapat Valid Entry yang mencapai target profit", report.validEntries > 0)

        // 3. Win rate di atas 50%
        assertTrue("Win rate harus positif", report.winRatePct > 50.0)

        // 4. Step 4 (Net R:R) bukan lagi penolak 100% peluang
        assertNotEquals("Step 4 tidak boleh lagi memblokir semua peluang", report.bottleneck.totalMomentumOpportunities, report.bottleneck.step4Rejections)
    }

    @Test
    fun `FASE 7 - Verifikasi proteksi orderbook kosong dan stale pada historical replay`() {
        val dataset = generateMultiBreakoutDataset(80)

        // Replay dengan orderbook selalu kosong
        val reportEmpty = HistoricalReplayEngine.replay(
            symbol = "BTCIDR",
            m1Candles = dataset,
            orderBookProvider = { _, _ -> Pair(emptyList(), emptyList()) }
        )

        // Dengan perbaikan Fase 6, orderbook kosong tidak boleh memicu BUY
        assertEquals("Orderbook kosong tidak boleh memicu BUY apapun", 0, reportEmpty.totalSignalsTriggered)
        assertTrue("Semua penolakan orderbook kosong tercatat di Step 2", reportEmpty.bottleneck.step2Rejections > 0)
    }
}
