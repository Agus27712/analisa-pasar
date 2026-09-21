package agu.analys.engine.scalping.replay

import agu.analys.model.CandleBar
import agu.analys.model.Timeframe
import agu.analys.service.IndodaxMarketService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Replay audit menggunakan snapshot candle REAL dari Indodax BTC/IDR.
 *
 * Catatan metodologi:
 * - M1/M15/H1 semuanya diambil dari Indodax.
 * - Hanya candle CLOSED yang digunakan.
 * - Historical orderbook depth tidak tersedia dari endpoint publik yang dipakai repo,
 *   sehingga Step 2 tidak difabrikasi. Untuk diagnosis Step 1/3/4, orderbook kosong
 *   di-bypass secara eksplisit dan report menandai hasil sebagai provisional.
 * - Dataset yang diambil disimpan sebagai artifact agar before/after berikutnya
 *   dapat memakai data yang sama persis.
 */
class RealBtcIndodaxReplayTest {

    @Test
    fun `real BTCIDR replay produces causal checkpoint audit`() = runBlocking {
        assumeTrue(
            "Live Indodax replay dijalankan hanya oleh workflow khusus",
            System.getenv("RUN_REAL_BTC_REPLAY") == "true"
        )
        val now = System.currentTimeMillis()

        val m1 = IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.M1, limit = 1500)
            .filter { it.timestamp + 60_000L <= now }

        val m15 = IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.M15, limit = 100)
            .filter { it.timestamp + 15 * 60_000L <= now }

        val h1 = IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.H1, limit = 60)
            .filter { it.timestamp + 60 * 60_000L <= now }

        assertTrue("Indodax M1 BTCIDR harus menyediakan cukup candle closed", m1.size >= 1200)
        assertTrue("Indodax M15 BTCIDR harus menyediakan minimal 20 candle closed", m15.size >= 20)
        assertTrue("Indodax H1 BTCIDR harus menyediakan minimal 20 candle closed", h1.size >= 20)

        val (liveBids, liveAsks) = IndodaxMarketService.fetchOrderBook("btcidr", limit = 15)

        val report = HistoricalReplayEngine.replay(
            symbol = "BTCIDR",
            m1Candles = m1,
            m15Candles = m15,
            h1Candles = h1,
            forwardLookaheadBars = 30,
            synthesizeMissingHigherTimeframes = false,
            diagnosticIgnoreOrderBookWhenUnavailable = true
        )

        assertTrue("Replay BTCIDR harus menghasilkan evaluasi", report.totalEvaluations > 0)

        val outDir = File("app/build/reports/scalping-replay")
        outDir.mkdirs()

        writeCandles(File(outDir, "btcidr-m1.csv"), m1)
        writeCandles(File(outDir, "btcidr-m15.csv"), m15)
        writeCandles(File(outDir, "btcidr-h1.csv"), h1)

        val firstTs = m1.firstOrNull()?.timestamp ?: 0L
        val lastTs = m1.lastOrNull()?.timestamp ?: 0L

        val reportText = buildString {
            appendLine("# BTCIDR Real Indodax Replay Audit")
            appendLine()
            appendLine("- Source: Indodax public market API via `IndodaxMarketService`")
            appendLine("- Generated at: ${Instant.ofEpochMilli(now)}")
            appendLine("- Dataset M1: ${m1.size} closed candles")
            appendLine("- Dataset M15: ${m15.size} closed candles")
            appendLine("- Dataset H1: ${h1.size} closed candles")
            appendLine("- M1 range: ${Instant.ofEpochMilli(firstTs)} .. ${Instant.ofEpochMilli(lastTs)}")
            appendLine("- Replay evaluations: ${report.totalEvaluations}")
            appendLine("- BUY signals (technical replay; historical orderbook unavailable): ${report.totalSignalsTriggered}")
            appendLine("- Valid entries: ${report.validEntries}")
            appendLine("- False signals: ${report.falseSignals}")
            appendLine("- Missed opportunities: ${report.missedOpportunities}")
            appendLine("- Avoided losses: ${report.avoidedLosses}")
            appendLine("- Unresolved outcomes: ${report.unresolvedOutcomes}")
            appendLine("- Ambiguous OHLC outcomes: ${report.ambiguousOutcomes}")
            appendLine("- Resolved signal outcome coverage: ${"%.2f".format(report.outcomeCoveragePct)}%")
            appendLine("- Resolved-signal win rate: ${"%.2f".format(report.winRatePct)}%")
            appendLine("- Capture rate: ${"%.2f".format(report.captureRatePct)}%")
            appendLine()
            appendLine("## Checkpoint distribution")
            appendLine()
            appendLine("| Checkpoint | Rejections | Share of rejections | Rejected candidates | Missed after checkpoint |")
            appendLine("|---|---:|---:|---:|---:|")
            appendLine("| Step 1 | ${report.bottleneck.step1Rejections} | ${"%.2f".format(report.bottleneck.step1RejectionSharePct)}% | ${"%.2f".format(report.bottleneck.step1RejectionPct)}% | ${report.bottleneck.missedAfterStep1} |")
            appendLine("| Step 2 | ${report.bottleneck.step2Rejections} | ${"%.2f".format(report.bottleneck.step2RejectionSharePct)}% | ${"%.2f".format(report.bottleneck.step2RejectionPct)}% | ${report.bottleneck.missedAfterStep2} |")
            appendLine("| Step 3 | ${report.bottleneck.step3Rejections} | ${"%.2f".format(report.bottleneck.step3RejectionSharePct)}% | ${"%.2f".format(report.bottleneck.step3RejectionPct)}% | ${report.bottleneck.missedAfterStep3} |")
            appendLine("| Step 4 | ${report.bottleneck.step4Rejections} | ${"%.2f".format(report.bottleneck.step4RejectionSharePct)}% | ${"%.2f".format(report.bottleneck.step4RejectionPct)}% | ${report.bottleneck.missedAfterStep4} |")
            appendLine()
            appendLine("## Order book data quality")
            appendLine()
            appendLine("- Replay orderbook mode: ${report.orderBookMode}")
            appendLine("- Historical orderbook available: ${report.orderBookDataAvailable}")
            appendLine("- Frames without historical orderbook: ${report.bottleneck.unmeasuredOrderBookFrames}")
            appendLine("- Current live orderbook snapshot available: ${liveBids.isNotEmpty() && liveAsks.isNotEmpty()}")
            if (liveBids.isNotEmpty() && liveAsks.isNotEmpty) {
                val bid = liveBids.first().price
                val ask = liveAsks.first().price
                val spreadPct = if (bid > 0.0) ((ask - bid) / bid) * 100.0 else 0.0
                appendLine("- Current spread snapshot: ${"%.6f".format(spreadPct)}%")
            }
            appendLine()
            appendLine("> Penting: Step 2 tidak boleh dianggap empiris pada replay historis ini karena Indodax public API yang dipakai aplikasi tidak menyediakan historical depth. Tidak ada orderbook sintetis yang digunakan.")
        }

        File(outDir, "btcidr-real-replay.md").writeText(reportText)
        println(reportText)
    }

    private fun writeCandles(file: File, candles: List<CandleBar>) {
        file.printWriter().use { out ->
            out.println("timestamp,open,high,low,close,volume")
            candles.forEach { c ->
                out.println("${c.timestamp},${c.open},${c.high},${c.low},${c.close},${c.volume}")
            }
        }
    }
}
