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
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

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

    /**
     * Uji perilaku: ambang gerbang R:R **dibaca dari [IntradayConfig]**, bukan literal di dalam
     * `IntradayEvaluator` (pengganti pindai sumber lama).
     *
     * Bukti: pada dataset riset yang sama, `minNetRrTp1 = 0,0` memunculkan sinyal BUY (Step1–3
     * memang lolos di beberapa bar), sedangkan `minNetRrTp1 = 99,0` mematikan semuanya. Jika kode
     * memakai literal sebagai ganti config, kedua lari menghasilkan nol sinyal dan asersi pertama
     * GAGAL — ambang 0,0 tidak mungkin memunculkan sinyal lewat literal.
     */
    @Test
    fun ambangGateMemakaiConfig_bukanLiteral() {
        val candles = muatCandleH1Riset("DOGEUSDT")

        val longgar = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = candles,
            feeConfig = feesUsdt,
            config = IntradayConfig(minNetRrTp1 = 0.0)
        )
        assertTrue(
            "Ambang 0,0 harus mengizinkan sinyal BUY di dataset riset ini (dapat " +
                "${longgar.totalSignalsTriggered})",
            longgar.totalSignalsTriggered > 0
        )

        val ketat = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = candles,
            feeConfig = feesUsdt,
            config = IntradayConfig(minNetRrTp1 = 99.0)
        )
        assertEquals(
            "Ambang mustahil 99,0 harus mematikan seluruh sinyal di dataset yang sama",
            0,
            ketat.totalSignalsTriggered
        )
    }

    /**
     * Uji perilaku: **fee maker** (`useMakerFee`) dan **slippage** memang dioper dari
     * [IntradayConfig] ke hitungan net R:R (pengganti pindai sumber).
     *
     * Paket C: geometri R-derived menjadikan net R:R yang tampil ≈ ambang config pada **kedua**
     * basis — TP1 di-solve agar memenuhi ambang, jadi perbedaan biaya pindah ke **level TP1**
     * (maker + slippage lebih murah → TP1 lebih dekat), bukan ke angka R:R. Bukti config dipakai:
     * R:R tampil cocok dengan `FeeCalculator.roundTrip` atas config itu, DAN TP1 maker berbeda
     * dari TP1 taker.
     */
    @Test
    fun feeMakerDanSlippageDariConfig_ikutMengubahNetRr() {
        val candles = muatCandleH1Riset("DOGEUSDT")
        val bar = pilihBarRrMemenuhiAmbang(candles)

        val evalMaker = IntradayEvaluator.evaluate(
            price = bar.harga,
            history = bar.window,
            fees = feesUsdt,
            evaluationTimestamp = bar.evalTime,
            config = IntradayConfig(useMakerFee = true, slippagePct = 0.30)
        )
        val signal = evalMaker.signal
        val expected = netRrTanpaPipih(
            entry = bar.harga,
            stopLoss = signal.stopLoss,
            takeProfit = signal.targetPrice1,
            fees = feesUsdt,
            slippagePct = 0.30,
            useMaker = true
        )
        val reported = signal.riskRewardRatio.removePrefix("1:").toDouble()
        assertEquals(
            "R:R yang tampil harus memakai fee maker + slippage 0,30 sesuai config",
            expected,
            reported,
            1e-9
        )

        val evalTaker = IntradayEvaluator.evaluate(
            price = bar.harga,
            history = bar.window,
            fees = feesUsdt,
            evaluationTimestamp = bar.evalTime,
            config = IntradayConfig()
        )
        val reportedTaker = evalTaker.signal.riskRewardRatio.removePrefix("1:").toDouble()
        assertEquals(
            "Dengan geometri R-derived pada bar tanpa batas, kedua basis harus sama-sama " +
                "memenuhi ambang config (maker $reported vs taker $reportedTaker)",
            IntradayConfig.DEFAULT.minNetRrTp1,
            reportedTaker,
            0.02
        )

        // Perbedaan biaya yang benar-benar terisolasi ada di **level solve** murni — pada sinyal,
        // TP1 = max(TP1-ATR, TP1-dari-R) bisa mengikat di level ATR untuk salah satu basis, jadi
        // TP1 sinyal antar basis tidak selalu terurut. (Catatan: Tokocrypto USDT maker = taker =
        // 0,4044%, jadi di bursa itu beda arah hanya datang dari slippage.)
        // (a) Fee maker dipakai: pada Tokocrypto IDR (maker 0,1222 < taker 0,2222), impas net
        //     maker lebih kecil → TP1-dari-R maker lebih dekat.
        val feeTokoIdr = FeeSchedule.configFor("TOKOCRYPTO", "IDR")
        val gIdrMaker = IntradayGeometry.resolve(
            price = 100.0, supportLevel = null, resistanceLevel = null,
            atr = 1.5, fees = feeTokoIdr, config = IntradayConfig(useMakerFee = true)
        )
        val gIdrTaker = IntradayGeometry.resolve(
            price = 100.0, supportLevel = null, resistanceLevel = null,
            atr = 1.5, fees = feeTokoIdr, config = IntradayConfig()
        )
        assertTrue(
            "Impas net maker Toko IDR (${gIdrMaker.impasNetPct}) harus lebih kecil dari taker " +
                "(${gIdrTaker.impasNetPct}) — kalau tidak, useMakerFee tidak dipakai geometri",
            gIdrMaker.impasNetPct < gIdrTaker.impasNetPct
        )
        assertTrue(
            "Biaya maker lebih murah → TP1-dari-R maker harus lebih dekat: " +
                "maker ${gIdrMaker.takeProfit1} < taker ${gIdrTaker.takeProfit1}",
            gIdrMaker.takeProfit1 < gIdrTaker.takeProfit1
        )
        // (b) Slippage dari config: 0,30/sisi lebih mahal dari bawaan 0,08 → TP1 lebih jauh.
        val gSlip030 = IntradayGeometry.resolve(
            price = 100.0, supportLevel = null, resistanceLevel = null,
            atr = 1.5, fees = feesUsdt, config = IntradayConfig(slippagePct = 0.30)
        )
        val gSlipBawaan = IntradayGeometry.resolve(
            price = 100.0, supportLevel = null, resistanceLevel = null,
            atr = 1.5, fees = feesUsdt, config = IntradayConfig()
        )
        assertTrue(
            "Impas net slippage 0,30 (${gSlip030.impasNetPct}) harus lebih besar dari bawaan " +
                "(${gSlipBawaan.impasNetPct}) — kalau tidak, slippagePct tidak dipakai geometri",
            gSlip030.impasNetPct > gSlipBawaan.impasNetPct
        )
        assertTrue(
            "Slippage lebih besar → TP1-dari-R harus lebih jauh: " +
                "${gSlip030.takeProfit1} > ${gSlipBawaan.takeProfit1}",
            gSlip030.takeProfit1 > gSlipBawaan.takeProfit1
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A — akuntansi replay: net, dan setiap trade dihitung tepat sekali
    //     (diuji di atas dataset riset ter-commit, DOGEUSDT 1h)
    // ─────────────────────────────────────────────────────────────────────────

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
     * Uji perilaku pengganti pindai sumber "validEntries wajib net": ringkasan replay dihitung
     * ulang dari daftar trade yang dilaporkan sendiri, lalu dibandingkan dengan laporan itu.
     * Kontrak yang dijaga:
     *
     * 1. Partisi lengkap: `validEntries + falseSignals == trades.size` — tidak ada trade
     *    `AMBIGUOUS`/`UNRESOLVED` yang dihitung sebagian atau tidak sama sekali.
     * 2. Predikat menang/kalah adalah **net**: bila kode kembali menandai menang dari gross atau
     *    dari peristiwa TP/SL, asersi ini GAGAL.
     * 3. Kasus historis A2 harus nyata di dataset ini: ada exit Close Malam yang GROSS-nya
     *    positif tetapi dinilai kalah karena net-nya negatif.
     */
    @Test
    fun ringkasanReplayMemakaiNetDanPartisiLengkap() {
        val report = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = muatCandleH1Riset("DOGEUSDT"),
            feeConfig = feesUsdt,
            config = IntradayConfig(minNetRrTp1 = 0.0) // ambang 0,0 supaya dataset ini punya trade
        )

        assertTrue("Dataset harus menghasilkan trade untuk dinilai", report.trades.isNotEmpty())

        val validDariNet = report.trades.count { IntradayReplayEngine.isWin(it.netPnlPct) }
        assertEquals(
            "validEntries harus dihitung dari net (isWin), bukan dari peristiwa TP/SL",
            validDariNet,
            report.validEntries
        )
        assertEquals(
            "Partisi wajib lengkap: validEntries + falseSignals == trades.size",
            report.trades.size,
            report.validEntries + report.falseSignals
        )
        assertEquals(
            "Win rate harus validEntries / trades.size",
            report.validEntries.toDouble() / report.trades.size * 100.0,
            report.winRatePct,
            1e-9
        )

        val netWins = report.trades.filter { it.netPnlPct > 0.0 }.sumOf { it.netPnlPct }
        val netLosses = report.trades.filter { it.netPnlPct < 0.0 }.sumOf { abs(it.netPnlPct) }
        val profitFactorDariNet = if (netLosses > 0.0) netWins / netLosses else 0.0
        assertEquals(
            "Profit factor harus dari net, bukan gross",
            profitFactorDariNet,
            report.profitFactor,
            1e-9
        )

        // Kasus historis A2: exit Close Malam yang gross-nya positif TAPI net-nya negatif.
        val kasusGrossPlusNetMinus = report.trades.filter {
            it.outcome == IntradayTradeOutcome.FALSE_SIGNAL_CLOSE_MALAM && it.grossPnlPct > 0.0
        }
        assertTrue(
            "Dataset riset ini harus memuat kasus Close Malam gross positif / net negatif — " +
                "kalau tidak ada, asersi di atas tidak teruji di kasusnya",
            kasusGrossPlusNetMinus.isNotEmpty()
        )
        kasusGrossPlusNetMinus.forEach { trade ->
            assertTrue(
                "Close Malam gross ${trade.grossPnlPct}% harus net negatif (dapat ${trade.netPnlPct}%)",
                trade.netPnlPct < 0.0
            )
        }
    }

    /**
     * Penjaga **konsistensi label peristiwa dengan klasifikasi net** (review 28 Okt 2026).
     *
     * Label `VALID_ENTRY_*` di [IntradayTradeOutcome] memakai kata "VALID" karena peristiwanya
     * (TP kena), tetapi klasifikasi menang/kalah memakai net (`isWin`). Keduanya bisa bertentangan
     * bila geometrinya berubah: kalau lantai TP1 jatuh di bawah impas net, ada trade yang
     * berlabel VALID_ENTRY_TP tetapi dihitung kalah dari net — dan kalau lantai SL naik di atas
     * impas net, ada trade berlabel FALSE_SIGNAL_SL tetapi dihitung menang. Uji ini menjaga dua
     * sisi: perilaku di dataset nyata, dan invarian geometri di tiga preset tarif.
     */
    @Test
    fun labelPeristiwaDanKlasifikasiNet_tidakBisaBertentangan() {
        // (1) Dataset nyata: label VALID_ENTRY_* selalu net ≥ 0, FALSE_SIGNAL_* selalu net < 0.
        val report = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = muatCandleH1Riset("DOGEUSDT"),
            feeConfig = feesUsdt,
            config = IntradayConfig(minNetRrTp1 = 0.0)
        )
        report.trades.forEach { trade ->
            val labelMenang = trade.outcome.name.startsWith("VALID_ENTRY")
            assertEquals(
                "Label ${trade.outcome} harus setara dengan klasifikasi net ${trade.netPnlPct}",
                labelMenang,
                IntradayReplayEngine.isWin(trade.netPnlPct)
            )
        }

        // (2) Invarian geometri yang membuat keduanya memang tak mungkin bertentangan — diturunkan
        // dari config (paket C), bukan literal: impas net harus berada di ANTARA SL terapat
        // (lantai SL) dan TP1 terendah (1,25 × ATR lantai 1,5% = +1,875%) untuk tiga preset tarif
        // (Toko USDT, Toko IDR, Indodax IDR).
        val cfg = IntradayConfig.DEFAULT
        listOf(feesUsdt, feesIdr, FeeSchedule.configFor("TOKOCRYPTO", "IDR")).forEach { fees ->
            val impasNetPct = FeeSchedule.breakevenPrice(
                entry = 100.0,
                buyFeePct = fees.buyTakerPct,
                sellFeePct = fees.sellTakerPct,
                slippagePct = FeeSchedule.DEFAULT_SLIPPAGE_PCT
            ) - 100.0
            val lantaiSlPct = maxOf(
                cfg.minSlPct,
                cfg.minSlFrictionMultiple * impasNetPct
            )
            val tp1TerendahPct = cfg.atrFloorPct * cfg.tp1AtrMult
            val hargaImpas = 100.0 * (1.0 + impasNetPct / 100.0)
            val slTerapat = 100.0 * (1.0 - lantaiSlPct / 100.0)
            assertTrue(
                "Impas net ($impasNetPct%) harus di bawah TP1 terendah geometri (+$tp1TerendahPct%) — " +
                    "kalau tidak, trade berlabel VALID_ENTRY_TP bisa terhitung kalah dari net",
                impasNetPct < tp1TerendahPct
            )
            assertTrue(
                "Harga impas net ($hargaImpas) harus di atas SL terapat geometri ($slTerapat) — " +
                    "kalau tidak, trade berlabel FALSE_SIGNAL_SL bisa terhitung menang dari net",
                hargaImpas > slTerapat
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Perkakas
    // ─────────────────────────────────────────────────────────────────────────

    private data class BarTerpilih(
        val harga: Double,
        val window: List<CandleBar>,
        val evalTime: Long
    )

    /**
     * Cari bar candle yang lolos Step1–3 **dan** geometrinya bebas batas (net R:R TP1 memenuhi
     * ambang bawaan 1,5 — TP1-dari-R tidak terpotong resistance/batas atas) di dataset riset,
     * memakai jendela evaluasi yang sama dengan replay (300 bar terakhir + waktu tutup bar H1).
     *
     * Dipakai uji fee dari config; kegagalan menemukan bar berarti dataset tidak lagi menyediakan
     * kasusnya (ganti dataset, ukur ulang).
     */
    private fun pilihBarRrMemenuhiAmbang(candles: List<CandleBar>): BarTerpilih {
        val jendelaBar = 300
        val durasiBarH1 = 3_600_000L
        val ambang = IntradayConfig.DEFAULT.minNetRrTp1
        for (i in 20 until candles.size) {
            val evalTime = candles[i].timestamp + durasiBarH1
            val window = candles.subList(max(0, i - jendelaBar), i + 1)
            val result = IntradayEvaluator.evaluate(
                price = candles[i].close,
                history = window,
                fees = feesUsdt,
                evaluationTimestamp = evalTime,
                config = IntradayConfig()
            )
            if (!result.signal.mtf.triggerOk) continue
            val rr = result.signal.riskRewardRatio.removePrefix("1:").toDoubleOrNull() ?: 0.0
            if (rr >= ambang - 1e-9) {
                return BarTerpilih(harga = candles[i].close, window = window, evalTime = evalTime)
            }
        }
        error(
            "Dataset tidak lagi punya bar Step1-3 dengan geometri bebas batas (R:R >= $ambang) — " +
                "dataset perlu diperbarui atau uji fee config dirancang ulang"
        )
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

    /** Muat candle H1 dari dataset riset ter-commit `data/tokocrypto_v7`. */
    private fun muatCandleH1Riset(symbol: String): List<CandleBar> {
        val relatif = "data/tokocrypto_v7/${symbol}_1h.csv"
        val base = File(System.getProperty("user.dir") ?: ".")
        val berkas = listOf(
            base,
            File(base, "app"),
            base.parentFile ?: base,
            File(base.parentFile ?: base, "app")
        ).map { File(it, relatif) }.firstOrNull { it.exists() }
            ?: error("Berkas riset tidak ditemukan: $relatif (dicari dari $base)")
        return berkas.readLines().drop(1).filter { it.isNotBlank() }.map { baris ->
            val kolom = baris.split(",")
            CandleBar(
                timestamp = kolom[0].toLong(),
                open = kolom[1].toDouble(),
                high = kolom[2].toDouble(),
                low = kolom[3].toDouble(),
                close = kolom[4].toDouble(),
                volume = kolom[5].toDouble()
            )
        }
    }
}
