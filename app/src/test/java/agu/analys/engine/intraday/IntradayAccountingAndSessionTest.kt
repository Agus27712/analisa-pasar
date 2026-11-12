package agu.analys.engine.intraday

import agu.analys.config.FeeCalculator
import agu.analys.config.FeeSchedule
import agu.analys.config.TradingFeeConfig
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
import java.util.Calendar
import java.util.TimeZone

/**
 * Penjaga **Paket A + B** perbaikan mode INTRADAY — seluruhnya uji **perilaku**, tanpa uji yang
 * membaca isi berkas sumber. Uji paket **C** (geometri SL/TP + macdHist) ada di
 * [IntradayGeometryAndMacdHistTest]; dua uji di berkas ini yang menyentuh geometri lama sudah
 * ditulis ulang mengikuti geometri paket C. Versi lama memindai teks sumber (`sumber.contains("...")`): uji itu
 * hanya menebak dari nama variabel — nama berubah, uji gagal, sedangkan perilakunya tidak pernah
 * terbukti. Semuanya diganti dengan perilaku yang diuji langsung:
 *
 * - **A (kebenaran angka biaya)**: net R:R dihitung dari TP1 tanpa `coerceAtLeast(1.6)`; gate
 *   memakai ambang dari [IntradayConfig] (bukti: ambang 0,0 memunculkan sinyal BUY, ambang
 *   99,0 mematikan semuanya — kode yang memakai literal tidak bisa bersikap begitu); fee maker dan
 *   slippage dioper dari config (bukti: ubah keduanya, angka R:R yang tampil ikut berubah sesuai
 *   `FeeCalculator`); replay memakai `FeeSchedule.netProfitPct` (taker + slippage dua sisi); dan
 *   menang/kalah memakai **net** dengan partisi lengkap. Uji akuntansi berjalan di atas dataset
 *   riset nyata yang ter-commit (`data/tokocrypto_v7/DOGEUSDT_1h.csv`, 999 bar, 2026-09-15 s.d.
 *   2026-10-27) — jauh lebih kuat daripada memindai sumber, dan tidak bergantung jaringan.
 * - **B (jam sesi)**: selang fase setengah-terbuka `[mulai, selesai)`, dan evaluasi memakai jam
 *   sekarang (`nowMs`) — bukan timestamp buka candle.
 *
 * Catatan: angka absolut (mis. 25 trade pada ambang 0,0) milik berkas data yang ter-commit.
 * Invarian yang diuji tetap berlaku selama berkas data tidak diganti; kalau datasetnya
 * diperbarui, angkanya harus diukur ulang dari hasil uji.
 *
 * **2026-11-12 — 4 uji yang butuh dataset `data/tokocrypto_v7` dihapus** atas instruksi owner.
 * Dataset itu sudah dihapus dari repo (commit `88103ec`), jadi ujinya **gagal** hanya karena
 * berkas data tidak ada, bukan karena perilaku engine berubah. Uji yang tersisa tidak butuh
 * dataset (fixture `TestData` + perhitungan murni). Konsekuensi yang harus diketahui: penjaga
 * "replay memakai net dengan partisi lengkap" **tidak lagi ada** di repo ini — kalau akuntansi
 * intraday diubah, ia perlu penjaga baru (idealnya dataset `data/tokocrypto_v8`).
 */
class IntradayAccountingAndSessionTest {

    private val feesIdr = FeeSchedule.configFor("INDODAX", "IDR")
    private val feesUsdt = FeeSchedule.configFor("TOKOCRYPTO", "USDT")

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
                fees = feesIdr
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
    // A — net R:R dari TP1, tanpa clamp; fee maker & slippage dioper dari config
    // ─────────────────────────────────────────────────────────────────────────

    /** Riwayat sintetis yang dipakai beberapa uji: tren naik 200 bar lalu 5 bar turun ringan. */
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

        // Paket C: TP1 diturunkan dari SL+ATR+ambang, jadi dengan ruang cukup gate lolos dan
        // dengan ruang dipangkas (`maxTp1Pct` kecil) gate GAGAL jujur. Anti-clamp dijaga dua sisi:
        // angka yang tampil selalu rumus net R:R multiplikatif (tidak pernah di-clamp ≥1,6), dan
        // gate tidak pernah lolos saat R:R kurang. Dibandingkan dengan [netRrTanpaPipih] — bukan
        // `FeeCalculator.roundTrip` yang memipihkan net R:R negatif ke 0; geometri paket C
        // sengaja tidak memipihkan supaya ambang 0,0 tetap berarti "TP1 minimal impas net".
        val result = IntradayEvaluator.evaluate(
            price = price,
            history = history,
            fees = feesIdr,
            evaluationTimestamp = wib(9, 0),
            config = IntradayConfig(maxTp1Pct = 2.0) // ruang dipangkas di bawah kebutuhan R:R 1,5
        )

        val signal = result.signal
        val expected = netRrTanpaPipih(
            entry = price,
            stopLoss = signal.stopLoss,
            takeProfit = signal.targetPrice1,
            fees = feesIdr,
            slippagePct = FeeSchedule.DEFAULT_SLIPPAGE_PCT
        )

        val reported = signal.riskRewardRatio.removePrefix("1:").toDouble()
        // Teks R:R dibulatkan 2 desimal oleh PriceFormatter — toleransi setengah langkah terakhir.
        assertEquals("R:R yang tampil harus net R:R TP1", expected, reported, 0.005)
        assertTrue(
            "Dengan ruang +2,0%, net R:R harus di bawah 1,6 — clamp lama menyembunyikannya " +
                "(dapat $reported)",
            reported < 1.6
        )
        assertTrue(
            "Dengan ruang +2,0%, net R:R juga harus di bawah ambang 1,5 (dapat $reported)",
            reported < IntradayConfig.DEFAULT.minNetRrTp1
        )
        if (signal.mtf.triggerOk) {
            assertFalse(
                "Step4 harus gagal saat ruang TP1 tidak memenuhi ambang — gate tidak boleh lolos",
                signal.mtf.entryPriceOk
            )
        }
    }

    @Test
    fun netPnlMemakaiFeeBursaPlusSlippage() {
        // Indodax IDR: gross +0,30% masih RUGI setelah fee taker dua sisi + slippage dua sisi.
        val net = IntradayReplayEngine.netPnlPct(
            entry = 100_000_000.0,
            exit = 100_300_000.0,
            feeConfig = feesIdr
        )
        assertTrue("Gross +0,30% harus jadi net negatif (didapat $net)", net < 0.0)
        assertEquals(-0.49, net, 0.02)
        assertFalse("Trade gross positif ini tetap kalah", IntradayReplayEngine.isWin(net))
    }

    @Test
    fun closeMalamMemakaiNet_bukanGross() {
        // Persis kasus yang dulu salah: exit sesi +0,20% dinilai "menang" dari gross.
        val net = IntradayReplayEngine.netPnlPct(
            entry = 100_000_000.0,
            exit = 100_200_000.0,
            feeConfig = feesIdr
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
        assertEquals(0.0, IntradayReplayEngine.netPnlPct(0.0, 100.0, feesIdr), 1e-9)
    }

    /**
     * Cermin rumus net R:R multiplikatif **tanpa pipih** untuk uji: `FeeCalculator.roundTrip`
     * memipihkan net R:R negatif ke 0, sedangkan geometri paket C sengaja tidak (ambang 0,0
     * harus berarti "TP1 minimal impas net"). Satu tempat cermin di uji, dengan komentar sumber;
     * kode produksi tetap memakai `FeeCalculator`/[IntradayGeometry].
     */
    private fun netRrTanpaPipih(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        fees: TradingFeeConfig,
        slippagePct: Double,
        useMaker: Boolean = false
    ): Double {
        val buyFeePct = if (useMaker) fees.buyMakerPct else fees.buyTakerPct
        val sellFeePct = if (useMaker) fees.sellMakerPct else fees.sellTakerPct
        val buyCostFactor = 1.0 + (buyFeePct + slippagePct) / 100.0
        val sellNetFactor = (1.0 - (sellFeePct + slippagePct) / 100.0).coerceAtLeast(0.0)
        val netRiskPct = (1.0 - (stopLoss / entry) * sellNetFactor / buyCostFactor) * 100.0
        val netRewardPct = ((takeProfit / entry) * sellNetFactor / buyCostFactor - 1.0) * 100.0
        return if (netRiskPct > 0.0) netRewardPct / netRiskPct else 0.0
    }
}
