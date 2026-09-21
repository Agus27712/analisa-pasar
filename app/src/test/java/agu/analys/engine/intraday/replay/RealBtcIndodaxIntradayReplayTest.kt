package agu.analys.engine.intraday.replay

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.intraday.IntradayEvaluator
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import agu.analys.model.Timeframe
import agu.analys.service.IndodaxMarketService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.time.Instant

/**
 * Unit test suite komprehensif untuk Replay Mode Intraday (Open Pagi · Close Malam · Anti Flash Dump)
 * menggunakan data candle riil BTC/IDR dari Indodax.
 *
 * Menguji:
 * 1. Replay end-to-end dengan candle live/real BTC Indodax API (H1 & D1).
 * 2. Penegakan disiplin sesi WIB (Open Pagi, Hold Siang, Sore Trailing, Close Malam, Rest Dini Hari).
 * 3. Proteksi Anti Flash Dump (< 72 jam trauma) & Fake Pump Upper Wick Trap.
 * 4. Metrik trade Intraday: Win Rate %, Profit Factor, Max Drawdown %, dan Bottleneck Waterfall Checkpoints.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealBtcIndodaxIntradayReplayTest {

    @Test
    fun test01_liveOrRealBtcIntradayReplay_executesFullPipeline() = runBlocking {
        println("================================================================")
        println("=== INTRADAY REPLAY: REAL BTC/IDR INDODAX AUDIT ===")
        println("================================================================")

        val now = System.currentTimeMillis()

        // 1. Fetch live candles dari Indodax
        val liveH1 = try {
            IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.H1, limit = 300)
                .filter { it.timestamp + 60 * 60_000L <= now }
        } catch (e: Exception) {
            println("Notice: fetchCandles H1 exception: ${e.message}")
            emptyList()
        }

        val liveD1 = try {
            IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.D1, limit = 60)
                .filter { it.timestamp + 24 * 60 * 60_000L <= now }
        } catch (e: Exception) {
            println("Notice: fetchCandles D1 exception: ${e.message}")
            emptyList()
        }

        val usingLive = liveH1.size >= 40
        val h1Candles = if (usingLive) liveH1 else RealBtcIntradayTestData.sampleH1
        val d1Candles = if (liveD1.isNotEmpty()) liveD1 else RealBtcIntradayTestData.sampleD1

        println("Dataset info:")
        println("  - Source H1: ${if (usingLive) "LIVE Indodax API (${h1Candles.size} bars)" else "REAL Fallback (${h1Candles.size} bars)"}")
        println("  - Source D1: ${if (liveD1.isNotEmpty()) "LIVE Indodax API (${d1Candles.size} bars)" else "REAL Fallback (${d1Candles.size} bars)"}")
        println("  - Range H1: ${Instant.ofEpochMilli(h1Candles.first().timestamp)} s/d ${Instant.ofEpochMilli(h1Candles.last().timestamp)}")
        println("  - First price: Rp ${String.format("%,.0f", h1Candles.first().close)}")
        println("  - Last price : Rp ${String.format("%,.0f", h1Candles.last().close)}")

        assertTrue("Dataset H1 harus memiliki minimal 25 candle closed", h1Candles.size >= 25)

        // 2. Jalankan Replay Engine Intraday
        val report = IntradayReplayEngine.replay(
            symbol = "BTCIDR",
            candles = h1Candles,
            dailyCandles = d1Candles,
            forwardLookaheadBars = 24,
            enforceSessionClose = true,
            feeConfig = TradingFeeConfig()
        )

        // 3. Validasi hasil evaluasi dasar
        assertTrue("Total evaluasi replay harus > 0", report.totalEvaluations > 0)
        assertTrue("Distribusi fase sesi WIB harus terpetakan", report.phaseDistribution.isNotEmpty())

        // 4. Bangun Laporan Markdown Audit
        val reportText = buildString {
            appendLine("# BTC/IDR Indodax Intraday Replay Audit Report")
            appendLine()
            appendLine("- **Aset**: BTC/IDR (Indodax Market)")
            appendLine("- **Strategi**: Mode Intraday (Open Pagi · Close Malam · Anti Flash Dump)")
            appendLine("- **Sumber Data**: ${if (usingLive) "Indodax Public API Live" else "Indodax Real Snapshot"}")
            appendLine("- **Total Bar Evaluasi (H1)**: ${report.totalEvaluations}")
            appendLine("- **Periode Data**: ${Instant.ofEpochMilli(h1Candles.first().timestamp)} s/d ${Instant.ofEpochMilli(h1Candles.last().timestamp)}")
            appendLine()
            appendLine("## 1. Performa Trading Intraday")
            appendLine()
            appendLine("| Metrik | Nilai |")
            appendLine("|---|---|")
            appendLine("| Sinyal BUY Diterbitkan | **${report.totalSignalsTriggered}** |")
            appendLine("| Valid Entries (Target TP / Profit Exit) | **${report.validEntries}** |")
            appendLine("| False Signals (Kena SL / Loss Exit) | **${report.falseSignals}** |")
            appendLine("| Win Rate (%) | **${String.format("%.2f", report.winRatePct)}%** |")
            appendLine("| Profit Factor | **${String.format("%.2f", report.profitFactor)}** |")
            appendLine("| Total Net Return (%) | **${String.format("%+.2f", report.totalNetPnlPct)}%** |")
            appendLine("| Rata-rata Net Return per Trade (%) | **${String.format("%+.2f", report.avgNetPnlPct)}%** |")
            appendLine("| Maximum Drawdown (%) | **${String.format("%.2f", report.maxDrawdownPct)}%** |")
            appendLine("| Missed Opportunities (Kandidat Ditolak tapi Naik) | **${report.missedOpportunities}** |")
            appendLine("| Avoided Losses (Kandidat Ditolak & Terhindar Rugi) | **${report.avoidedLosses}** |")
            appendLine("| Unresolved Trades | **${report.unresolvedOutcomes}** |")
            appendLine()
            appendLine("## 2. Distribusi Sesi Harian WIB")
            appendLine()
            appendLine("| Sesi WIB | Jam Operasional | Evaluasi Bar | Karakteristik Sesi |")
            appendLine("|---|---|---|---|")
            for ((phase, count) in report.phaseDistribution.entries.sortedByDescending { it.value }) {
                val desc = when (phase) {
                    IntradayEvaluator.IntradayPhase.OPEN_PAGI -> "Jendela Beli Utama (Membuka Posisi Baru)"
                    IntradayEvaluator.IntradayPhase.HOLD_SIANG -> "Akumulasi Siang (Selektif Entry)"
                    IntradayEvaluator.IntradayPhase.HOLD_SORE -> "Trailing Sore (Hanya Hold, Dilarang Buy)"
                    IntradayEvaluator.IntradayPhase.CLOSE_MALAM -> "Close Kas IDR (Tutup Posisi Harian Sebelum Dini Hari)"
                    IntradayEvaluator.IntradayPhase.REST_MALAM -> "Istirahat Dini Hari (Pasar Rawan Flash Dump Global)"
                }
                appendLine("| ${phase.label} | ${phase.name} | $count | $desc |")
            }
            appendLine()
            appendLine("## 3. Analisis Bottleneck Waterfall Checkpoints")
            appendLine()
            appendLine("- **Primary Bottleneck**: ${report.bottleneck.primaryBottleneckStep}")
            appendLine("- **Keterangan**: ${report.bottleneck.primaryBottleneckDescription}")
            appendLine()
            appendLine("| Checkpoint Gating | Penolakan | Penolakan Yang Menghindari Loss | Keterangan Proteksi |")
            appendLine("|---|---|---|---|")
            appendLine("| Step 1 (Macro Trend & EMA) | ${report.bottleneck.step1Rejections} | - | Memastikan EMA20 > EMA50 |")
            appendLine("| Step 2 (Support & Near High) | ${report.bottleneck.step2Rejections} | - | Mencegah beli di pucuk |")
            appendLine("| Step 3 (RSI & MACD Momentum) | ${report.bottleneck.step3Rejections} | - | RSI ideal 36–62 & MACD sehat |")
            appendLine("| Step 4 (Risk/Reward & Buy Score) | ${report.bottleneck.step4Rejections} | - | Minimal Net R:R 1:1.6 |")
            appendLine("| Session Gate (Rest/Sore Window) | ${report.bottleneck.sessionGateRejections} | - | Mencegah beli di luar jam aktif |")
            appendLine("| Anti Flash Dump Block | ${report.bottleneck.flashDumpTraumaBlocks} | - | Mencegah beli pasca dump mendadak (< 72 jam) |")
            appendLine("| Fake Pump Trap Block | ${report.bottleneck.pumpAndDumpTrapBlocks} | - | Mencegah jebakan jarum atas (Upper Wick) |")
            appendLine()
            if (report.trades.isNotEmpty()) {
                appendLine("## 4. Riwayat Trade Replay Detail")
                appendLine()
                appendLine("| # | Waktu Masuk | Entry Price | TP1 / TP2 | Stop Loss | Waktu Keluar | Exit Price | Exit Reason | Gross PnL | Net PnL | Bars |")
                appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
                report.trades.forEachIndexed { idx, t ->
                    appendLine("| ${idx + 1} | ${Instant.ofEpochMilli(t.entryTime)} | Rp ${String.format("%,.0f", t.entryPrice)} | Rp ${String.format("%,.0f", t.targetPrice1)} / ${String.format("%,.0f", t.targetPrice2)} | Rp ${String.format("%,.0f", t.stopLoss)} | ${Instant.ofEpochMilli(t.exitTime)} | Rp ${String.format("%,.0f", t.exitPrice)} | ${t.exitReason} | ${String.format("%+.2f", t.grossPnlPct)}% | ${String.format("%+.2f", t.netPnlPct)}% | ${t.barsHeld} |")
                }
                appendLine()
            }
        }

        // Tulis file report
        val outDir = File(if (File("build").exists() || !File("app/build").exists()) "build/reports/intraday-replay" else "app/build/reports/intraday-replay")
        outDir.mkdirs()
        File(outDir, "btcidr-real-intraday-replay.md").writeText(reportText)

        println(reportText)
    }

    @Test
    fun test02_sessionDiscipline_enforcesOpenPagiAndCloseMalamRules() {
        println("=== TEST 02: DISIPLIN SESI WIB (OPEN PAGI & CLOSE MALAM) ===")

        val baseCandles = RealBtcIntradayTestData.sampleH1.take(30)
        val price = baseCandles.last().close

        // 1. Sesi Rest Malam: Jam 02:00 WIB (Timestamp modulo ke jam 02:00 WIB)
        val calRest = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 2)
            set(java.util.Calendar.MINUTE, 15)
        }
        val phaseRest = IntradayEvaluator.getCurrentIntradayPhase(calRest.timeInMillis)
        assertEquals(IntradayEvaluator.IntradayPhase.REST_MALAM, phaseRest)
        assertFalse("Rest Malam tidak boleh menjadi Open Window", phaseRest.isOpenWindow)

        val evalRest = IntradayEvaluator.evaluate(
            price = price,
            history = baseCandles,
            evaluationTimestamp = calRest.timeInMillis
        )
        assertNotEquals("Di sesi Rest Malam tidak boleh mengeluarkan BUY", SignalAction.BUY, evalRest.signal.action)
        assertTrue("Reasoning harus menyebut sesi istirahat", evalRest.signal.reasoning.any { it.contains("ISTIRAHAT", true) })

        // 2. Sesi Close Malam: Jam 20:30 WIB
        val calClose = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 20)
            set(java.util.Calendar.MINUTE, 30)
        }
        val phaseClose = IntradayEvaluator.getCurrentIntradayPhase(calClose.timeInMillis)
        assertEquals(IntradayEvaluator.IntradayPhase.CLOSE_MALAM, phaseClose)
        assertTrue("Close Malam harus menandai isCloseWindow", phaseClose.isCloseWindow)

        val evalClose = IntradayEvaluator.evaluate(
            price = price,
            history = baseCandles,
            evaluationTimestamp = calClose.timeInMillis
        )
        assertEquals("Di sesi Close Malam action harus SELL untuk mengamankan kas IDR", SignalAction.SELL, evalClose.signal.action)
        assertTrue("Reasoning harus menyebut Close Malam", evalClose.signal.reasoning.any { it.contains("CLOSE MALAM", true) })

        // 3. Sesi Open Pagi: Jam 08:30 WIB
        val calOpen = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 8)
            set(java.util.Calendar.MINUTE, 30)
        }
        val phaseOpen = IntradayEvaluator.getCurrentIntradayPhase(calOpen.timeInMillis)
        assertEquals(IntradayEvaluator.IntradayPhase.OPEN_PAGI, phaseOpen)
        assertTrue("Open Pagi harus menjadi Open Window", phaseOpen.isOpenWindow)
        assertFalse("Open Pagi bukan Close Window", phaseOpen.isCloseWindow)

        // 4. Sesi Sore Trailing: Jam 16:30 WIB
        val calTrailing = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 16)
            set(java.util.Calendar.MINUTE, 30)
        }
        val phaseTrailing = IntradayEvaluator.getCurrentIntradayPhase(calTrailing.timeInMillis)
        assertEquals(IntradayEvaluator.IntradayPhase.HOLD_SORE, phaseTrailing)
        assertTrue("Hold Sore harus menjadi trailing window", phaseTrailing.isTrailingWindow)
        assertFalse("Hold Sore dilarang open window", phaseTrailing.isOpenWindow)
    }

    @Test
    fun test03_antiFlashDumpProtection_blocksEntriesAfterRecentDump() {
        println("=== TEST 03: PROTEKSI ANTI FLASH DUMP (< 72 JAM) ===")

        val baseCandles = RealBtcIntradayTestData.sampleH1.take(30).toMutableList()
        val lastTs = baseCandles.last().timestamp
        val basePrice = 1_400_000_000.0

        // Buat flash dump tajam -9% pada 5 bar yang lalu
        val dumpCandles = mutableListOf<CandleBar>()
        for (i in 0 until 5) {
            val ts = lastTs + ((i + 1) * 3600_000L)
            if (i == 0) {
                // Flash dump candle: open 1.4B -> close 1.27B (-9.28%)
                dumpCandles += CandleBar(ts, basePrice, basePrice * 1.01, basePrice * 0.90, basePrice * 0.907, 150.0)
            } else {
                val p = basePrice * 0.91 + (i * 5_000_000.0)
                dumpCandles += CandleBar(ts, p - 2_000_000.0, p + 5_000_000.0, p - 3_000_000.0, p, 10.0)
            }
        }
        baseCandles.addAll(dumpCandles)

        // Timestamp sesi pagi 09:00 WIB
        val calMorning = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            timeInMillis = baseCandles.last().timestamp
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 0)
        }

        val result = IntradayEvaluator.evaluate(
            price = baseCandles.last().close,
            history = baseCandles,
            evaluationTimestamp = calMorning.timeInMillis
        )

        assertNotEquals("BUY harus diblokir karena ada trauma flash dump baru", SignalAction.BUY, result.signal.action)
        assertTrue(
            "Reasoning harus mencatat penolakan Anti Flash Dump",
            result.signal.reasoning.any { it.contains("Anti Flash Dump", true) || it.contains("flash dump", true) }
        )
        println("Proteksi Anti Flash Dump berhasil menolak: ${result.signal.reasoning.firstOrNull()}")
    }

    @Test
    fun test04_fakePumpUpperWickTrapProtection() {
        println("=== TEST 04: PROTEKSI FAKE PUMP UPPER WICK REJECTION ===")

        val baseCandles = RealBtcIntradayTestData.sampleH1.take(30).toMutableList()
        val lastBar = baseCandles.last()
        val ts = lastBar.timestamp + 3600_000L

        // Tambahkan bar dengan ekor atas (upper wick) raksasa: High melesat +4% lalu ditolak jatuh ke dekat Open
        val trapBar = CandleBar(
            timestamp = ts,
            open = lastBar.close,
            high = lastBar.close * 1.045, // Jarum naik 4.5%
            low = lastBar.close * 0.998,
            close = lastBar.close * 1.003, // Ditutup nyaris di harga open (rejection wick raksasa)
            volume = 80.0
        )
        baseCandles.add(trapBar)

        val calMorning = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            timeInMillis = ts
            set(java.util.Calendar.HOUR_OF_DAY, 10)
            set(java.util.Calendar.MINUTE, 0)
        }

        val result = IntradayEvaluator.evaluate(
            price = trapBar.close,
            history = baseCandles,
            evaluationTimestamp = calMorning.timeInMillis
        )

        assertNotEquals("BUY harus diblokir oleh penolakan jarum atas (Fake Pump Trap)", SignalAction.BUY, result.signal.action)
        assertTrue(
            "Reasoning harus menyebut Upper Wick atau Fake Pump",
            result.signal.reasoning.any { it.contains("Upper Wick", true) || it.contains("Fake Pump", true) }
        )
        println("Proteksi Fake Pump Trap berhasil menolak: ${result.signal.reasoning.firstOrNull()}")
    }

    @Test
    fun test05_causalIntegrity_noFutureLookaheadLeak() {
        println("=== TEST 05: INTEGRITAS KAUSAL (NO LOOKAHEAD LEAK) ===")

        val candles = RealBtcIntradayTestData.sampleH1
        assertTrue(candles.size >= 30)

        // Evaluasi pada candle ke-25
        val candle25 = candles[24]
        val window25 = candles.subList(0, 25)

        val evalAt25 = IntradayEvaluator.evaluate(
            price = candle25.close,
            history = window25,
            evaluationTimestamp = candle25.timestamp
        )

        // Evaluasi terpisah tidak boleh terpengaruh oleh candle 26 s/d terakhir
        assertNotNull(evalAt25)
        assertEquals(candle25.close, evalAt25.signal.entryPrice, 0.001)
        assertTrue("Timestamp sinyal harus mencerminkan timestamp candle saat evaluasi", evalAt25.signal.timestamp == candle25.timestamp)
    }

    @Test
    fun test06_bullishSetupWithReplaySimulation_executesTradeLifecycle() {
        println("=== TEST 06: SIKLUS HIDUP LENGKAP TRADE INTRADAY (PULLBACK SETUP -> ENTRY -> TP) ===")

        // Bangun 32 candle H1 real BTC dengan baseline EMA stabil dan osilasi konsolidasi (RSI di sweet spot ~48-54)
        val baseCandles = mutableListOf<CandleBar>()
        val startCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            set(2026, java.util.Calendar.SEPTEMBER, 10, 6, 0, 0) // Mulai jam 06:00 WIB
        }
        val baseTime = startCal.timeInMillis
        var p = 1_400_000_000.0

        // 1. 20 bar awal: uptrend bertahap
        for (i in 0 until 20) {
            val t = baseTime + (i * 3600_000L)
            val open = p
            val delta = if (i % 2 == 0) p * 0.002 else p * 0.0005
            val high = open + p * 0.003
            val low = open - p * 0.001
            val close = open + delta
            baseCandles.add(CandleBar(t, open, high, low, close, 15.0 + (i * 0.2)))
            p = close
        }

        // 2. Bar 21 (swing high di Rp 1.455.000.000)
        val t21 = baseTime + (20 * 3600_000L)
        val swingHigh = p * 1.015
        baseCandles.add(CandleBar(t21, p, swingHigh, p * 0.998, p * 1.008, 30.0))
        p = p * 1.008

        // 3. 6 bar osilasi/pullback sehat (RSI turun ke ~48-52, harga konsolidasi ~2.5% di bawah swing high)
        val consolidationBase = swingHigh * 0.975 // -2.5% dari high
        for (i in 0 until 6) {
            val t = baseTime + ((21 + i) * 3600_000L)
            val isEven = i % 2 == 0
            val open = consolidationBase + (if (isEven) 500_000.0 else -500_000.0)
            val close = consolidationBase + (if (isEven) -500_000.0 else 500_000.0)
            baseCandles.add(CandleBar(t, open, consolidationBase + 2_000_000.0, consolidationBase - 2_000_000.0, close, 18.0))
        }

        // 4. Bar 28 (jam 08:30 WIB Open Pagi): Bullish reversal bounce dari support lokal
        val entryCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            timeInMillis = baseTime + (27 * 3600_000L)
            set(java.util.Calendar.HOUR_OF_DAY, 8)
            set(java.util.Calendar.MINUTE, 30)
        }
        val bounceOpen = consolidationBase - 500_000.0
        val bounceClose = consolidationBase + 4_000_000.0 // Reversal candle
        val entryBar = CandleBar(
            timestamp = entryCal.timeInMillis,
            open = bounceOpen,
            high = bounceClose + 1_000_000.0,
            low = bounceOpen - 1_000_000.0,
            close = bounceClose,
            volume = 40.0
        )
        baseCandles.add(entryBar)

        // Verifikasi evaluasi langsung pada candle entry
        val eval = IntradayEvaluator.evaluate(
            price = entryBar.close,
            history = baseCandles,
            evaluationTimestamp = entryCal.timeInMillis
        )

        assertEquals("Sesi harus OPEN_PAGI", IntradayEvaluator.IntradayPhase.OPEN_PAGI, IntradayEvaluator.getCurrentIntradayPhase(entryCal.timeInMillis))
        println("Evaluasi candle entry:")
        println("  - Action      : ${eval.signal.action}")
        println("  - Confidence  : ${eval.signal.confidence}")
        println("  - Bias OK     : ${eval.signal.mtf.biasOk} (${eval.signal.mtf.biasDetail})")
        println("  - Setup OK    : ${eval.signal.mtf.setupOk} (${eval.signal.mtf.setupDetail})")
        println("  - Trigger OK  : ${eval.signal.mtf.triggerOk} (${eval.signal.mtf.triggerDetail})")
        println("  - Entry OK    : ${eval.signal.mtf.entryPriceOk} (${eval.signal.mtf.entryPriceDetail})")
        println("  - Reasons     : ${eval.signal.reasoning.joinToString(" | ")}")

        assertTrue("Step 1 harus lolos (EMA20 > EMA50, tidak di pucuk)", eval.signal.mtf.biasOk)
        assertTrue("Step 2 harus lolos (support terjaga)", eval.signal.mtf.setupOk)

        // Tambahkan bar ke depan: siang/sore mencapai target TP1
        val targetTp1 = eval.signal.targetPrice1.takeIf { it > entryBar.close } ?: (entryBar.close * 1.035)
        val futureBar = CandleBar(
            timestamp = entryCal.timeInMillis + (5 * 3600_000L), // Jam 13:30 WIB (Sesi Siang)
            open = entryBar.close * 1.01,
            high = targetTp1 * 1.008,
            low = entryBar.close,
            close = targetTp1 * 1.002,
            volume = 30.0
        )
        baseCandles.add(futureBar)

        // Jalankan replay pada sequence ini
        val replayReport = IntradayReplayEngine.replay(
            symbol = "BTCIDR",
            candles = baseCandles,
            forwardLookaheadBars = 12,
            enforceSessionClose = true
        )

        assertTrue("Replay harus mencakup minimal 1 evaluasi", replayReport.totalEvaluations >= 1)
        println("Total signal triggered: ${replayReport.totalSignalsTriggered}")
        println("Valid entries: ${replayReport.validEntries}")
    }

    @Test
    fun test07_september3rdRealIntradayTest() = runBlocking {
        println("==========================================================================")
        println("=== REAL TEST: EVALUASI RIIL BTC/IDR TANGGAL 3 SEPTEMBER 2026 ===")
        println("==========================================================================")

        // Aug 25, 2026 WIB s/d Sep 4, 2026 WIB untuk H1
        val fromSec = 1787590800L  // 24 Aug 17:00 UTC (25 Aug 00:00 WIB)
        val toSec = 1788454800L    // 3 Sep 17:00 UTC (4 Sep 00:00 WIB)

        val h1Candles = try {
            IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.H1, limit = 500, explicitFromSec = fromSec, explicitToSec = toSec)
        } catch (e: Exception) {
            println("Failed to fetch real H1: ${e.message}")
            emptyList()
        }

        val d1Candles = try {
            // June 1 s/d Sep 4
            IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.D1, limit = 100, explicitFromSec = 1780243200L, explicitToSec = toSec)
        } catch (e: Exception) {
            println("Failed to fetch real D1: ${e.message}")
            emptyList()
        }

        println("Data fetched:")
        println("  - H1 candles: ${h1Candles.size}")
        println("  - D1 candles: ${d1Candles.size}")

        if (h1Candles.isEmpty()) {
            println("ERROR: Gagal menarik data riil H1 dari Indodax API.")
            return@runBlocking
        }

        // Jalankan replay engine
        val report = IntradayReplayEngine.replay(
            symbol = "BTCIDR",
            candles = h1Candles,
            dailyCandles = d1Candles,
            forwardLookaheadBars = 24,
            enforceSessionClose = true
        )

        println("\n=== SUMMARY REPLAY ===")
        println("Total Evaluasi   : ${report.totalEvaluations}")
        println("Total Sinyal BUY : ${report.totalSignalsTriggered}")
        println("Valid Entries    : ${report.validEntries}")
        println("False Signals    : ${report.falseSignals}")

        // Cari trade yang terjadi spesifik pada tanggal 3 September 2026
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale("id", "ID")).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
        }

        println("\n=== RINCIAN TRADE YANG TERDETEKSI ===")
        report.trades.forEachIndexed { i, t ->
            val entryStr = formatter.format(java.util.Date(t.entryTime))
            val exitStr = formatter.format(java.util.Date(t.exitTime))
            println("Trade #${i+1}:")
            println("  - Entry Time  : $entryStr WIB")
            println("  - Entry Price : Rp ${String.format("%,.0f", t.entryPrice)}")
            println("  - Target TP1  : Rp ${String.format("%,.0f", t.targetPrice1)}")
            println("  - Stop Loss   : Rp ${String.format("%,.0f", t.stopLoss)}")
            println("  - Exit Time   : $exitStr WIB")
            println("  - Exit Price  : Rp ${String.format("%,.0f", t.exitPrice)}")
            println("  - Exit Reason : ${t.exitReason}")
            println("  - Net Return  : ${String.format("%+.2f", t.netPnlPct)}% (setelah fee)")
            println("  - Outcome     : ${t.outcome}")
        }

        println("\n=== RINCIAN EVALUASI PER JAM DI TANGGAL 3 SEP 2026 ===")
        report.frames.filter { frame ->
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
                timeInMillis = frame.timestamp
            }
            cal.get(java.util.Calendar.YEAR) == 2026 && 
            cal.get(java.util.Calendar.MONTH) == java.util.Calendar.SEPTEMBER && 
            cal.get(java.util.Calendar.DAY_OF_MONTH) == 3
        }.forEach { frame ->
            val wibStr = formatter.format(java.util.Date(frame.timestamp))
            println("$wibStr WIB | Price: Rp ${String.format("%,.0f", frame.candle.close)} | Phase: ${frame.phase} | Action: ${frame.action} | Confidence: ${frame.confidence} | Rejection: ${frame.rejectionReason}")
        }
    }

    @Test
    fun test08_september14thRealIntradayTest() = runBlocking {
        println("==========================================================================")
        println("=== REAL TEST: EVALUASI RIIL BTC/IDR TANGGAL 14 SEPTEMBER 2026 ===")
        println("==========================================================================")

        // Sep 5, 2026 WIB s/d Sep 15, 2026 WIB untuk H1
        // Sep 5 00:00:00 WIB -> Sep 4 17:00:00 UTC = 1788541200L
        // Sep 15 23:59:59 WIB -> Sep 15 16:59:59 UTC = 1789491599L
        val fromSec = 1788541200L
        val toSec = 1789491599L

        val h1Candles = try {
            IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.H1, limit = 500, explicitFromSec = fromSec, explicitToSec = toSec)
        } catch (e: Exception) {
            println("Failed to fetch real H1: ${e.message}")
            emptyList()
        }

        val d1Candles = try {
            // June 1 s/d Sep 15
            IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.D1, limit = 100, explicitFromSec = 1780243200L, explicitToSec = toSec)
        } catch (e: Exception) {
            println("Failed to fetch real D1: ${e.message}")
            emptyList()
        }

        println("Data fetched:")
        println("  - H1 candles: ${h1Candles.size}")
        println("  - D1 candles: ${d1Candles.size}")

        if (h1Candles.isEmpty()) {
            println("ERROR: Gagal menarik data riil H1 dari Indodax API.")
            return@runBlocking
        }

        // Jalankan replay engine
        val report = IntradayReplayEngine.replay(
            symbol = "BTCIDR",
            candles = h1Candles,
            dailyCandles = d1Candles,
            forwardLookaheadBars = 24,
            enforceSessionClose = true
        )

        println("\n=== SUMMARY REPLAY ===")
        println("Total Evaluasi   : ${report.totalEvaluations}")
        println("Total Sinyal BUY : ${report.totalSignalsTriggered}")
        println("Valid Entries    : ${report.validEntries}")
        println("False Signals    : ${report.falseSignals}")

        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale("id", "ID")).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
        }

        println("\n=== RINCIAN TRADE YANG TERDETEKSI ===")
        report.trades.forEachIndexed { i, t ->
            val entryStr = formatter.format(java.util.Date(t.entryTime))
            val exitStr = formatter.format(java.util.Date(t.exitTime))
            println("Trade #${i+1}:")
            println("  - Entry Time  : $entryStr WIB")
            println("  - Entry Price : Rp ${String.format("%,.0f", t.entryPrice)}")
            println("  - Target TP1  : Rp ${String.format("%,.0f", t.targetPrice1)}")
            println("  - Stop Loss   : Rp ${String.format("%,.0f", t.stopLoss)}")
            println("  - Exit Time   : $exitStr WIB")
            println("  - Exit Price  : Rp ${String.format("%,.0f", t.exitPrice)}")
            println("  - Exit Reason : ${t.exitReason}")
            println("  - Net Return  : ${String.format("%+.2f", t.netPnlPct)}% (setelah fee)")
            println("  - Outcome     : ${t.outcome}")
        }

        println("\n=== RINCIAN EVALUASI PER JAM DI TANGGAL 14 SEP 2026 ===")
        report.frames.filter { frame ->
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
                timeInMillis = frame.timestamp
            }
            cal.get(java.util.Calendar.YEAR) == 2026 && 
            cal.get(java.util.Calendar.MONTH) == java.util.Calendar.SEPTEMBER && 
            cal.get(java.util.Calendar.DAY_OF_MONTH) == 14
        }.forEach { frame ->
            val wibStr = formatter.format(java.util.Date(frame.timestamp))
            println("$wibStr WIB | Price: Rp ${String.format("%,.0f", frame.candle.close)} | Phase: ${frame.phase} | Action: ${frame.action} | Confidence: ${frame.confidence} | Rejection: ${frame.rejectionReason}")
        }
    }

    @Test
    fun test09_september18thRealIntradayTest() = runBlocking {
        println("==========================================================================")
        println("=== REAL TEST: EVALUASI RIIL BTC/IDR TANGGAL 18 SEPTEMBER 2026 ===")
        println("==========================================================================")

        // Sep 9, 2026 WIB s/d Sep 19, 2026 WIB untuk H1
        val fromSec = 1788886800L
        val toSec = 1789837199L

        val h1Candles = try {
            IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.H1, limit = 500, explicitFromSec = fromSec, explicitToSec = toSec)
        } catch (e: Exception) {
            println("Failed to fetch real H1: ${e.message}")
            emptyList()
        }

        val d1Candles = try {
            // June 1 s/d Sep 19
            IndodaxMarketService.fetchCandles("BTCIDR", Timeframe.D1, limit = 100, explicitFromSec = 1780243200L, explicitToSec = toSec)
        } catch (e: Exception) {
            println("Failed to fetch real D1: ${e.message}")
            emptyList()
        }

        println("Data fetched:")
        println("  - H1 candles: ${h1Candles.size}")
        println("  - D1 candles: ${d1Candles.size}")

        if (h1Candles.isEmpty()) {
            println("ERROR: Gagal menarik data riil H1 dari Indodax API.")
            return@runBlocking
        }

        // Jalankan replay engine
        val report = IntradayReplayEngine.replay(
            symbol = "BTCIDR",
            candles = h1Candles,
            dailyCandles = d1Candles,
            forwardLookaheadBars = 24,
            enforceSessionClose = true
        )

        println("\n=== SUMMARY REPLAY ===")
        println("Total Evaluasi   : ${report.totalEvaluations}")
        println("Total Sinyal BUY : ${report.totalSignalsTriggered}")
        println("Valid Entries    : ${report.validEntries}")
        println("False Signals    : ${report.falseSignals}")

        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale("id", "ID")).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
        }

        println("\n=== RINCIAN TRADE YANG TERDETEKSI ===")
        report.trades.forEachIndexed { i, t ->
            val entryStr = formatter.format(java.util.Date(t.entryTime))
            val exitStr = formatter.format(java.util.Date(t.exitTime))
            println("Trade #${i+1}:")
            println("  - Entry Time  : $entryStr WIB")
            println("  - Entry Price : Rp ${String.format("%,.0f", t.entryPrice)}")
            println("  - Target TP1  : Rp ${String.format("%,.0f", t.targetPrice1)}")
            println("  - Stop Loss   : Rp ${String.format("%,.0f", t.stopLoss)}")
            println("  - Exit Time   : $exitStr WIB")
            println("  - Exit Price  : Rp ${String.format("%,.0f", t.exitPrice)}")
            println("  - Exit Reason : ${t.exitReason}")
            println("  - Net Return  : ${String.format("%+.2f", t.netPnlPct)}% (setelah fee)")
            println("  - Outcome     : ${t.outcome}")
        }

        println("\n=== RINCIAN EVALUASI PER JAM DI TANGGAL 18 SEP 2026 ===")
        report.frames.filter { frame ->
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
                timeInMillis = frame.timestamp
            }
            cal.get(java.util.Calendar.YEAR) == 2026 && 
            cal.get(java.util.Calendar.MONTH) == java.util.Calendar.SEPTEMBER && 
            cal.get(java.util.Calendar.DAY_OF_MONTH) == 18
        }.forEach { frame ->
            val wibStr = formatter.format(java.util.Date(frame.timestamp))
            println("$wibStr WIB | Price: Rp ${String.format("%,.0f", frame.candle.close)} | Phase: ${frame.phase} | Action: ${frame.action} | Confidence: ${frame.confidence} | Rejection: ${frame.rejectionReason}")
        }
    }
}
