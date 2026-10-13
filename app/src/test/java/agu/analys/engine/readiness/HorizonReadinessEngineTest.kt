package agu.analys.engine.readiness

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.scalping.ScalpingConfig
import agu.analys.model.CandleBar
import agu.analys.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.sin

/**
 * Uji mesin kesiapan multi-horizon.
 *
 * Semua deret candle dibuat **deterministik** (tanpa `Math.random()`), karena uji yang hasilnya
 * berubah-ubah tidak membuktikan apa pun tentang perilaku engine.
 */
class HorizonReadinessEngineTest {

    private val usdtFees = TradingFeeConfig.tokocryptoUsdt()

    // ── Pembuat data ────────────────────────────────────────────────────────

    /**
     * Deret candle deterministik.
     *
     * @param trendPctPerBar kemiringan tren per bar, persen
     * @param wavePct amplitudo gelombang, persen — memberi bar turun supaya RSI tidak jenuh
     * @param rangePct tinggi-rendah candle, persen harga (menentukan ATR)
     * @param finalBarUpPct bila diisi, bar terakhir dipaksa naik sebesar ini (persen).
     *   Diperlukan karena gelombang sinus membuat bar terakhir bisa kebetulan turun,
     *   sehingga uji "momentum" dan "dorongan" gagal bukan karena engine salah.
     */
    private fun series(
        count: Int,
        start: Double = 1_000.0,
        trendPctPerBar: Double = 0.0,
        wavePct: Double = 0.0,
        wavePeriod: Int = 10,
        rangePct: Double = 1.0,
        volume: Double = 1_000.0,
        stepMs: Long = 60_000L,
        lastVolumeMultiplier: Double = 1.0,
        finalBarUpPct: Double? = null
    ): List<CandleBar> {
        val out = ArrayList<CandleBar>(count)
        var price = start
        for (i in 0 until count) {
            val open = price
            val wave = 1.0 + wavePct / 100.0 * sin(2.0 * PI * i / wavePeriod)
            val close = if (finalBarUpPct != null && i == count - 1) {
                open * (1.0 + finalBarUpPct / 100.0)
            } else {
                open * (1.0 + trendPctPerBar / 100.0) * wave
            }
            val half = rangePct / 200.0
            val high = maxOf(open, close) * (1.0 + half)
            val low = minOf(open, close) * (1.0 - half)
            val vol = if (i == count - 1) volume * lastVolumeMultiplier else volume
            out += CandleBar(
                timestamp = i.toLong() * stepMs,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = vol
            )
            price = close
        }
        return out
    }

    /** Deret dengan satu candle jatuh keras di akhir — untuk uji trauma dump. */
    private fun seriesWithDump(count: Int, start: Double, dumpPct: Double): List<CandleBar> {
        val base = series(count, start = start, trendPctPerBar = 0.05, wavePct = 0.3, rangePct = 1.0)
        val last = base.last()
        val dumped = last.copy(
            close = last.open * (1.0 + dumpPct / 100.0),
            low = last.open * (1.0 + dumpPct / 100.0) * 0.999,
            high = last.open * 1.001
        )
        return base.dropLast(1) + dumped
    }

    private fun wib(hour: Int, minute: Int = 0): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        cal.set(2026, Calendar.OCTOBER, 14, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Fixture "koin ideal" untuk scalp: tren naik, ATR di pita, volume naik di M1. */
    private fun scalpCandles(rangePctM15: Double = 1.0): Map<Timeframe, List<CandleBar>> = mapOf(
        Timeframe.H1 to series(
            90, trendPctPerBar = 0.08, wavePct = 0.6, wavePeriod = 12,
            rangePct = 1.2, finalBarUpPct = 0.3
        ),
        Timeframe.M15 to series(
            70, trendPctPerBar = 0.08, wavePct = 0.3, wavePeriod = 10,
            rangePct = rangePctM15, finalBarUpPct = 0.4
        ),
        Timeframe.M1 to series(
            60, trendPctPerBar = 0.02, wavePct = 0.2, wavePeriod = 9,
            rangePct = 0.3, lastVolumeMultiplier = 2.0, finalBarUpPct = 0.3
        )
    )

    private fun evaluate(
        horizon: TradeHorizon,
        candles: Map<Timeframe, List<CandleBar>>,
        price: Double = 0.0,
        now: Long = wib(9)
    ) = HorizonReadinessEngine.evaluate(
        HorizonReadinessEngine.Input(
            symbol = "BTC_USDT",
            horizon = horizon,
            candles = candles,
            price = price,
            fees = usdtFees,
            now = now
        )
    )

    private fun economy(result: HorizonReadiness) =
        result.checks.first { it.label == HorizonReadinessEngine.CHECK_ECONOMY }

    // ── Data kurang ─────────────────────────────────────────────────────────

    @Test
    fun `candle kosong menghasilkan NO_DATA tanpa rencana`() {
        val r = evaluate(TradeHorizon.SCALP, emptyMap())
        assertEquals(ReadinessState.NO_DATA, r.state)
        assertNull("Tidak boleh mengarang rencana dari data yang tidak ada", r.plan)
        assertTrue(r.checks.isEmpty())
        assertFalse(r.actionable)
        assertNotNull(r.blocker)
    }

    @Test
    fun `candle bias kurang dari minimum menghasilkan NO_DATA`() {
        val candles = scalpCandles().toMutableMap()
        candles[Timeframe.H1] = series(10, trendPctPerBar = 0.08)
        val r = evaluate(TradeHorizon.SCALP, candles)
        assertEquals(ReadinessState.NO_DATA, r.state)
        assertTrue(r.blocker!!.contains("1h"))
    }

    // ── Scalp ───────────────────────────────────────────────────────────────

    @Test
    fun `scalp siap ketika tren momentum volume dan ekonomi lolos`() {
        val r = evaluate(TradeHorizon.SCALP, scalpCandles())
        assertTrue(
            "Fixture harus lolos semua syarat, tapi gagal di: " +
                r.checks.filter { !it.ok }.joinToString { "${it.label} -> ${it.detail}" },
            r.state == ReadinessState.READY
        )
        assertTrue(r.actionable)
        assertNotNull(r.plan)
        assertEquals(100, r.score)
    }

    @Test
    fun `scalp menolak koin yang terlalu tenang untuk menutup biaya`() {
        // ATR ≈ 0,05% sementara biaya round-trip USDT ≈ 0,97% → rasio ≈ 0,27× (jauh di bawah 2×).
        val r = evaluate(TradeHorizon.SCALP, scalpCandles(rangePctM15 = 0.05))
        assertFalse("Gate ekonomi harus gagal untuk koin yang nyaris tidak bergerak", economy(r).ok)
        assertTrue(r.state != ReadinessState.READY)
        assertFalse(r.actionable)
        assertTrue(economy(r).detail.contains("terlalu tenang"))
    }

    @Test
    fun `scalp tertahan saat volatilitas ekstrem`() {
        val r = evaluate(TradeHorizon.SCALP, scalpCandles(rangePctM15 = 6.0))
        assertEquals(ReadinessState.BLOCKED, r.state)
        assertFalse(r.actionable)
        assertTrue(r.blocker!!.contains("Volatilitas ekstrem"))
    }

    @Test
    fun `scalp memakai lantai friksi pada rencana dan target net rr horizon`() {
        val r = evaluate(TradeHorizon.SCALP, scalpCandles())
        val plan = r.plan!!
        val costPct = usdtFees.buyTakerPct + usdtFees.sellTakerPct + 2 * ScalpingConfig.DEFAULT_SLIPPAGE_PCT
        val expectedFloor = ScalpingConfig.MIN_FRICTION_MULTIPLE * costPct
        assertEquals(expectedFloor, plan.frictionFloorPct, 1e-9)
        assertTrue("Risiko harus minimal selantai friksi", plan.riskPct >= expectedFloor - 1e-9)
        assertEquals(
            "Net R:R harus menyentuh target horizon",
            TradeHorizon.SCALP.targetNetRr,
            plan.netRr,
            0.02
        )
        assertTrue(plan.stopLoss < plan.entry)
        assertTrue(plan.takeProfit1 > plan.entry)
        assertTrue(plan.takeProfit2 > plan.takeProfit1)
    }

    @Test
    fun `aturan exit scalping menyebut mode yang sedang aktif`() {
        val r = evaluate(TradeHorizon.SCALP, scalpCandles())
        val rule = r.plan!!.exitRule
        assertTrue(rule.contains("Beli di area entry"))
        assertTrue("Aturan exit harus menyebut mode aktif", rule.contains("Mode "))
    }

    // ── Intraday ────────────────────────────────────────────────────────────

    private fun intradayCandles(): Map<Timeframe, List<CandleBar>> = mapOf(
        Timeframe.H4 to series(90, trendPctPerBar = 0.25, wavePct = 0.8, wavePeriod = 14, rangePct = 1.6),
        Timeframe.H1 to series(90, trendPctPerBar = 0.18, wavePct = 0.5, wavePeriod = 12, rangePct = 1.2),
        Timeframe.M15 to series(60, trendPctPerBar = 0.08, wavePct = 0.4, wavePeriod = 10, rangePct = 1.0)
    )

    @Test
    fun `intraday tertahan di luar sesi buka`() {
        // 01:00 WIB = sesi istirahat malam.
        val r = evaluate(TradeHorizon.INTRADAY, intradayCandles(), now = wib(1))
        val session = r.checks.first { it.label.startsWith("Sesi") }
        assertFalse("Dini hari tidak boleh membuka posisi", session.ok)
        assertTrue(r.state != ReadinessState.READY)
    }

    @Test
    fun `intraday siap di sesi buka dengan tren dan adx kuat`() {
        val r = evaluate(TradeHorizon.INTRADAY, intradayCandles(), now = wib(9))
        assertTrue(
            "Fixture harus lolos semua syarat, tapi gagal di: " +
                r.checks.filter { !it.ok }.joinToString { "${it.label} -> ${it.detail}" },
            r.state == ReadinessState.READY
        )
    }

    @Test
    fun `intraday gagal syarat saat ada trauma dump 72 jam`() {
        val candles = intradayCandles().toMutableMap()
        candles[Timeframe.M15] = seriesWithDump(60, start = 1_000.0, dumpPct = -14.0)
        val r = evaluate(TradeHorizon.INTRADAY, candles, now = wib(9))
        val dump = r.checks.first { it.label.contains("trauma") }
        assertFalse(dump.ok)
        assertTrue(r.state != ReadinessState.READY)
    }

    // ── Swing ───────────────────────────────────────────────────────────────

    private fun swingCandles(price: Double = 0.0): Map<Timeframe, List<CandleBar>> = mapOf(
        Timeframe.D1 to series(90, trendPctPerBar = 0.35, wavePct = 0.9, wavePeriod = 15, rangePct = 2.0),
        Timeframe.H4 to series(80, trendPctPerBar = 0.15, wavePct = 1.4, wavePeriod = 11, rangePct = 1.8),
        Timeframe.H1 to series(80, trendPctPerBar = 0.08, wavePct = 0.6, wavePeriod = 10, rangePct = 1.2)
    )

    @Test
    fun `swing tertahan ketika harga blow-off jauh di atas ema`() {
        val candles = swingCandles()
        val lastClose = candles[Timeframe.D1]!!.last().close
        // 25% di atas harga terakhir → di atas ambang blokir 15%.
        val r = evaluate(TradeHorizon.SWING, candles, price = lastClose * 1.25)
        assertEquals(ReadinessState.BLOCKED, r.state)
        assertTrue(r.blocker!!.contains("blow-off"))
    }

    @Test
    fun `swing memakai lantai friksi horizon yang lebih longgar dari scalp`() {
        val candles = swingCandles()
        val r = evaluate(TradeHorizon.SWING, candles, price = candles[Timeframe.D1]!!.last().close)
        val plan = r.plan!!
        val costPct = usdtFees.buyTakerPct + usdtFees.sellTakerPct + 2 * ScalpingConfig.DEFAULT_SLIPPAGE_PCT
        assertEquals(TradeHorizon.SWING.minFrictionMultiple * costPct, plan.frictionFloorPct, 1e-9)
        assertTrue(
            "Lantai swing harus lebih longgar dari scalp",
            TradeHorizon.SWING.frictionFloorPct(costPct) < TradeHorizon.SCALP.frictionFloorPct(costPct)
        )
        assertEquals(TradeHorizon.SWING.targetNetRr, plan.netRr, 0.02)
    }

    // ── Utilitas & invarian lintas-horizon ──────────────────────────────────

    @Test
    fun `estimasi gerak memakai skala akar bukan linier`() {
        assertEquals(1.0 * kotlin.math.sqrt(28.0), HorizonReadinessEngine.expectedTravelPct(1.0, 28), 1e-9)
        assertEquals(0.0, HorizonReadinessEngine.expectedTravelPct(0.0, 28), 1e-9)
        assertEquals(0.0, HorizonReadinessEngine.expectedTravelPct(1.0, 0), 1e-9)
        assertTrue(
            "Skala linier akan melebih-lebihkan kemampuan koin",
            HorizonReadinessEngine.expectedTravelPct(1.0, 28) < 1.0 * 28
        )
    }

    @Test
    fun `hanya READY yang boleh ditandai actionable`() {
        val siap = evaluate(TradeHorizon.SCALP, scalpCandles())
        val tenang = evaluate(TradeHorizon.SCALP, scalpCandles(rangePctM15 = 0.05))
        val kosong = evaluate(TradeHorizon.SCALP, emptyMap())
        assertTrue(siap.actionable)
        assertFalse(tenang.actionable)
        assertFalse(kosong.actionable)
    }

    @Test
    fun `skor memberi bobot ganda pada gate ekonomi`() {
        val r = evaluate(TradeHorizon.SCALP, scalpCandles())
        // 4 syarat biasa (bobot 1) + 1 gate ekonomi (bobot 2) = total bobot 6.
        assertEquals(5, r.totalChecks)
        assertEquals(100, r.score)

        // Koin tenang: volatilitas (0,05% di bawah pita 0,3%) DAN gate ekonomi gagal.
        // Bila semua syarat berbobot sama, 3 dari 5 lolos = 60. Dengan bobot ganda pada gate
        // ekonomi, hasilnya 3/6 = 50 — inilah yang membuktikan bobotnya benar-benar berlaku.
        val tenang = evaluate(TradeHorizon.SCALP, scalpCandles(rangePctM15 = 0.05))
        assertEquals(3, tenang.passedChecks)
        assertEquals(50, tenang.score)
        assertTrue(
            "Skor harus lebih rendah daripada bila semua syarat berbobot sama (3/5 = 60)",
            tenang.score < 60
        )
    }

    @Test
    fun `hanya scalp yang sudah divalidasi keluar-sampel`() {
        assertTrue(TradeHorizon.SCALP.validatedOutOfSample)
        assertFalse(TradeHorizon.INTRADAY.validatedOutOfSample)
        assertFalse(TradeHorizon.SWING.validatedOutOfSample)
    }

    /** Lantai friksi sebagai persen harga, dihitung dari tarif. */
    private fun TradeHorizon.frictionFloorPct(costPct: Double): Double = minFrictionMultiple * costPct
}
