package agu.analys.engine.intraday

import agu.analys.config.FeeCalculator
import agu.analys.config.FeeSchedule
import agu.analys.config.StrategyMode
import agu.analys.engine.ModeSignalEvaluator
import agu.analys.engine.ModeSignalInput
import agu.analys.engine.TestData
import agu.analys.engine.intraday.replay.IntradayReplayEngine
import agu.analys.engine.intraday.replay.IntradayTradeOutcome
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Penjaga **Paket A + B** perbaikan mode INTRADAY:
 *
 * - **A (kebenaran angka biaya)**: net R:R dihitung dari TP1 tanpa `coerceAtLeast(1.6)`, gate memakai
 *   ambang dari [IntradayConfig], replay memakai `FeeSchedule.netProfitPct` (taker + slippage dua
 *   sisi), dan menang/kalah memakai **net** sehingga setiap trade masuk tepat satu klasifikasi.
 * - **B (jam sesi)**: selang fase setengah-terbuka `[mulai, selesai)`, dan evaluasi memakai jam
 *   sekarang (`nowMs`) — bukan timestamp buka candle.
 *
 * Kontrol negatif yang sudah diuji manual: mengembalikan `coerceAtLeast(1.6)` membuat
 * [netRrDariTp1_tidakDiClamp], mengembalikan `gross >= 0` di Close Malam membuat
 * [closeMalamMemakaiNet_bukanGross], dan memakai `history.last().timestamp` membuat
 * [evaluatorMemakaiJamSekarang_bukanTimestampCandle] gagal.
 */
class IntradayAccountingAndSessionTest {

    private val fees = FeeSchedule.configFor("INDODAX", "IDR")

    // ─────────────────────────────────────────────────────────────────────────
    // B — batas fase sesi (selang setengah-terbuka)
    // ─────────────────────────────────────────────────────────────────────────

    private fun wib(hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta")).apply {
            set(2026, Calendar.OCTOBER, 27, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun batasFaseSesi_setengahTerbuka() {
        assertEquals(IntradayEvaluator.IntradayPhase.OPEN_PAGI, IntradayEvaluator.getCurrentIntradayPhase(wib(6, 0)))
        assertEquals(IntradayEvaluator.IntradayPhase.OPEN_PAGI, IntradayEvaluator.getCurrentIntradayPhase(wib(11, 29)))
        assertEquals("11:30 milik Siang Akumulasi", IntradayEvaluator.IntradayPhase.HOLD_SIANG, IntradayEvaluator.getCurrentIntradayPhase(wib(11, 30)))
        assertEquals(IntradayEvaluator.IntradayPhase.HOLD_SIANG, IntradayEvaluator.getCurrentIntradayPhase(wib(15, 29)))
        assertEquals("15:30 milik Sore Trailing", IntradayEvaluator.IntradayPhase.HOLD_SORE, IntradayEvaluator.getCurrentIntradayPhase(wib(15, 30)))
        assertEquals(IntradayEvaluator.IntradayPhase.HOLD_SORE, IntradayEvaluator.getCurrentIntradayPhase(wib(19, 29)))
        assertEquals("19:30 milik Close Malam", IntradayEvaluator.IntradayPhase.CLOSE_MALAM, IntradayEvaluator.getCurrentIntradayPhase(wib(19, 30)))
        assertEquals(IntradayEvaluator.IntradayPhase.CLOSE_MALAM, IntradayEvaluator.getCurrentIntradayPhase(wib(23, 29)))
        assertEquals("23:30 milik Istirahat", IntradayEvaluator.IntradayPhase.REST_MALAM, IntradayEvaluator.getCurrentIntradayPhase(wib(23, 30)))
        assertEquals(IntradayEvaluator.IntradayPhase.REST_MALAM, IntradayEvaluator.getCurrentIntradayPhase(wib(5, 59)))
        assertEquals(IntradayEvaluator.IntradayPhase.OPEN_PAGI, IntradayEvaluator.getCurrentIntradayPhase(wib(6, 0)))
    }

    @Test
    fun setiapMenitMasukTepatSatuFase() {
        // Tidak ada menit yang hilang atau dihitung dua kali: 1440 menit terbagi habis.
        val counts = HashMap<IntradayEvaluator.IntradayPhase, Int>()
        for (menit in 0 until 24 * 60) {
            val phase = IntradayEvaluator.getCurrentIntradayPhase(wib(menit / 60, menit % 60))
            counts[phase] = (counts[phase] ?: 0) + 1
        }
        assertEquals(24 * 60, counts.values.sum())
        assertEquals(330, counts[IntradayEvaluator.IntradayPhase.OPEN_PAGI])   // 06:00–11:30
        assertEquals(240, counts[IntradayEvaluator.IntradayPhase.HOLD_SIANG]) // 11:30–15:30
        assertEquals(240, counts[IntradayEvaluator.IntradayPhase.HOLD_SORE])  // 15:30–19:30
        assertEquals(240, counts[IntradayEvaluator.IntradayPhase.CLOSE_MALAM])// 19:30–23:30
        assertEquals(390, counts[IntradayEvaluator.IntradayPhase.REST_MALAM]) // 23:30–06:00
    }

    @Test
    fun evaluatorMemakaiJamSekarang_bukanTimestampCandle() {
        // Candle terakhir dibuka 02:00 WIB (sesi istirahat), tetapi evaluasi terjadi 20:30 WIB.
        // Memakai timestamp candle akan menghasilkan HOLD "ISTIRAHAT", bukan SELL Close Malam.
        val candles = TestData.generateCandles(60, 1_400_000_000.0, 0.001).toMutableList()
        val candleOpenAt02 = candles.last().timestamp
        val nowAt2030 = wib(20, 30)

        val result = ModeSignalEvaluator.evaluate(
            ModeSignalInput(
                mode = StrategyMode.OFFICE_DAILY,
                symbol = "BTCIDR",
                price = candles.last().close,
                nowMs = nowAt2030,
                h4Candles = candles,
                fees = fees
            )
        )

        assertTrue("Evaluator harus mengembalikan sinyal", result != null)
        assertEquals(
            "Jam sinyal harus jam sekarang, bukan timestamp buka candle",
            nowAt2030,
            result!!.signal.timestamp
        )
        assertNotEquals(
            "Candle dibuka 02:00 WIB, jadi timestamp candle bukan patokan fase",
            candleOpenAt02,
            result.signal.timestamp
        )
        assertEquals(
            "Di 20:30 WIB (Close Malam) aksi wajib SELL walau candle dibuka dini hari",
            SignalAction.SELL,
            result.signal.action
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A — net R:R dari TP1, tanpa clamp
    // ─────────────────────────────────────────────────────────────────────────

    /** Riwayat "sehat" yang dipakai beberapa uji: tren naik lalu 5 bar turun ringan. */
    private fun riwayatSehat(): MutableList<CandleBar> {
        val history = TestData.generateCandles(200, 800.0, 0.001).toMutableList()
        val lastClose = history.last().close
        val time = history.last().timestamp
        for (i in 1..5) {
            val c = lastClose - (i * 1.5)
            history.add(CandleBar(time + (i * 3_600_000L), c + 1.0, c + 2.0, c - 2.0, c, 5000.0))
        }
        return history
    }

    @Test
    fun netRrDariTp1_tidakDiClamp() {
        val history = riwayatSehat()
        val price = history.last().close

        val result = IntradayEvaluator.evaluate(
            price = price,
            history = history,
            fees = fees,
            evaluationTimestamp = wib(9, 0)
        )

        val signal = result.signal
        val expected = FeeCalculator.roundTrip(
            entry = price,
            stopLoss = signal.stopLoss,
            takeProfit = signal.targetPrice1,
            fees = fees,
            useMaker = false,
            slippagePct = FeeSchedule.DEFAULT_SLIPPAGE_PCT
        ).netRr

        val reported = signal.riskRewardRatio.removePrefix("1:").toDouble()
        assertEquals("R:R yang tampil harus net R:R TP1", expected, reported, 0.02)
        assertTrue(
            "Net R:R TP1 geometri sekarang (±0,74) harus di bawah 1,6 — clamp lama menyembunyikannya",
            reported < 1.6
        )
        assertFalse(
            "Gate default 1,5 tidak boleh lolos dengan net R:R TP1 di bawah 1,5",
            signal.mtf.entryPriceOk
        )
    }

    /** Riwayat pullback sehat: uptrend, swing high, konsolidasi, lalu bar bounce (pola test06). */
    private fun riwayatPullback(): MutableList<CandleBar> {
        val out = mutableListOf<CandleBar>()
        val startCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta")).apply {
            set(2026, Calendar.SEPTEMBER, 10, 6, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseTime = startCal.timeInMillis
        var p = 1_400_000_000.0
        for (i in 0 until 20) {
            val t = baseTime + (i * 3_600_000L)
            val open = p
            val delta = if (i % 2 == 0) p * 0.002 else p * 0.0005
            out += CandleBar(t, open, open + p * 0.003, open - p * 0.001, open + delta, 15.0 + (i * 0.2))
            p = open + delta
        }
        val t21 = baseTime + (20 * 3_600_000L)
        val swingHigh = p * 1.015
        out += CandleBar(t21, p, swingHigh, p * 0.998, p * 1.008, 30.0)
        p *= 1.008

        val consolidationBase = swingHigh * 0.975
        for (i in 0 until 6) {
            val t = baseTime + ((21 + i) * 3_600_000L)
            val isEven = i % 2 == 0
            val open = consolidationBase + (if (isEven) 500_000.0 else -500_000.0)
            val close = consolidationBase + (if (isEven) -500_000.0 else 500_000.0)
            out += CandleBar(t, open, consolidationBase + 2_000_000.0, consolidationBase - 2_000_000.0, close, 18.0)
        }
        val entryCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta")).apply {
            timeInMillis = baseTime + (27 * 3_600_000L)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 30)
            set(Calendar.MILLISECOND, 0)
        }
        val bounceOpen = consolidationBase - 500_000.0
        val bounceClose = consolidationBase + 4_000_000.0
        out += CandleBar(
            timestamp = entryCal.timeInMillis,
            open = bounceOpen,
            high = bounceClose + 1_000_000.0,
            low = bounceOpen - 1_000_000.0,
            close = bounceClose,
            volume = 40.0
        )
        return out
    }

    @Test
    fun ambangGateMemakaiConfig_bukanLiteral() {
        val history = riwayatPullback()
        val price = history.last().close
        val waktu = history.last().timestamp

        // Ambang mustahil: Step4 wajib gagal walau Step1–3 lolos.
        val ketat = IntradayEvaluator.evaluate(
            price = price,
            history = history,
            fees = fees,
            evaluationTimestamp = waktu,
            config = IntradayConfig(minNetRrTp1 = 99.0)
        )
        assertFalse("Ambang 99,0 tidak boleh diloloskan", ketat.signal.mtf.entryPriceOk)

        // Ambang nol: bila Step1–3 lolos, Step4 hanya bergantung pada skor beli & overextended.
        val longgar = IntradayEvaluator.evaluate(
            price = price,
            history = history,
            fees = fees,
            evaluationTimestamp = waktu,
            config = IntradayConfig(minNetRrTp1 = 0.0)
        )
        println(
            "Ambang gate: step1=${longgar.signal.mtf.biasOk} step2=${longgar.signal.mtf.setupOk} " +
                "step3=${longgar.signal.mtf.triggerOk} step4=${longgar.signal.mtf.entryPriceOk} " +
                "rr=${longgar.signal.riskRewardRatio} action=${longgar.signal.action}"
        )

        // Waterfall: Step4 tidak boleh lolos bila Step3 belum lolos.
        assertEquals(
            "Step4 hanya boleh lolos setelah Step3 lolos",
            longgar.signal.mtf.triggerOk,
            longgar.signal.mtf.entryPriceOk
        )

        // Ambang bawaan (1,5) tetap memblokir geometri sekarang: net R:R TP1 di bawah 1,5.
        val bawaan = IntradayEvaluator.evaluate(
            price = price,
            history = history,
            fees = fees,
            evaluationTimestamp = waktu
        )
        val rrBawaan = bawaan.signal.riskRewardRatio.removePrefix("1:").toDouble()
        assertEquals(1.5, IntradayConfig.DEFAULT.minNetRrTp1, 1e-9)
        assertTrue("Net R:R TP1 ($rrBawaan) harus di bawah ambang bawaan 1,5", rrBawaan < 1.5)
        assertFalse("Gate bawaan tidak boleh lolos dengan R:R di bawah ambang", bawaan.signal.mtf.entryPriceOk)

        val sumber = bacaSumber("app/src/main/java/agu/analys/engine/intraday/IntradayEvaluator.kt")
        assertTrue(
            "Step4 wajib membaca ambang dari config, bukan literal",
            sumber.contains("netRrTp1 >= config.minNetRrTp1")
        )
        assertFalse(
            "Clamp R:R lama tidak boleh hidup lagi",
            sumber.contains("coerceAtLeast(1.6)")
        )
        assertTrue(
            "Gate R:R wajib dihitung dari TP1",
            sumber.contains("takeProfit = calculatedTp1")
        )
        assertTrue(
            "Intraday wajib menilai biaya dengan taker + slippage dari config",
            sumber.contains("useMaker = config.useMakerFee") && sumber.contains("slippagePct = config.slippagePct")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A — akuntansi replay: net, dan setiap trade dihitung tepat sekali
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun netPnlMemakaiFeeBursaPlusSlippage() {
        // Indodax IDR: gross +0,30% masih RUGI setelah fee taker dua sisi + slippage dua sisi.
        val net = IntradayReplayEngine.netPnlPct(
            entry = 100_000_000.0,
            exit = 100_300_000.0,
            feeConfig = fees
        )
        assertTrue("Gross +0,30% harus jadi net negatif (didapat $net)", net < 0.0)
        assertEquals(-0.49, net, 0.02)
        assertFalse("Trade gross positif ini tetap kalah", IntradayReplayEngine.isWin(net))
    }

    @Test
    fun closeMalamMemakaiNet_bukanGross() {
        // Persis kasus yang dulu salah: exit sesi +0,20% dinilai "menang" dari gross.
        val gross = 0.20
        assertTrue("Prasyarat kasus: gross positif", gross >= 0.0)
        val net = IntradayReplayEngine.netPnlPct(
            entry = 100_000_000.0,
            exit = 100_200_000.0,
            feeConfig = fees
        )
        assertTrue("Net kasus ini negatif ($net)", net < 0.0)
        assertEquals(
            IntradayTradeOutcome.FALSE_SIGNAL_CLOSE_MALAM,
            IntradayReplayEngine.classifyCloseMalam(net)
        )
        assertEquals(
            IntradayTradeOutcome.VALID_ENTRY_CLOSE_MALAM,
            IntradayReplayEngine.classifyCloseMalam(1.0)
        )
        assertEquals(0.0, IntradayReplayEngine.netPnlPct(0.0, 100.0, fees), 1e-9)
    }

    @Test
    fun ringkasanReplayMemakaiNetDanPartisiLengkap() {
        val sumber = bacaSumber(
            "app/src/main/java/agu/analys/engine/intraday/replay/IntradayReplayEngine.kt"
        )
        assertTrue(
            "validEntries wajib dihitung dari net, bukan dari peristiwa TP/SL",
            sumber.contains("val validEntries = trades.count { isWin(it.netPnlPct) }")
        )
        assertTrue(
            "falseSignals wajib sisa partisi, supaya setiap trade masuk tepat sekali",
            sumber.contains("val falseSignals = trades.size - validEntries")
        )
        assertTrue("Profit factor wajib net", sumber.contains("netWins") && sumber.contains("netLosses"))
        assertFalse(
            "Sisa akuntansi gross tidak boleh hidup lagi",
            sumber.contains("grossWins") || sumber.contains("grossLosses") || sumber.contains("totalFeePct")
        )
        assertTrue(
            "Exit Close Malam wajib diklasifikasi dari net",
            sumber.contains("classifyCloseMalam(netPnlPct)")
        )
    }

    private fun bacaSumber(relatif: String): String {
        val base = File(System.getProperty("user.dir"))
        val kandidat = listOf(
            File(base, relatif),
            File(base, "app/$relatif"),
            File(base.parentFile ?: base, relatif),
            File(base.parentFile ?: base, "app/$relatif")
        )
        val file = kandidat.first { it.exists() }
        return file.readText()
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
    }
}
