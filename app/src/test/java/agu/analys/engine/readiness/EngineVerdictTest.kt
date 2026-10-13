package agu.analys.engine.readiness

import agu.analys.config.StrategyMode
import agu.analys.engine.scalping.ScalpReadiness
import agu.analys.engine.scalping.ScalpReadinessState
import agu.analys.model.AISignalState
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji jembatan SSOT [EngineVerdict].
 *
 * ## Invarian yang dijaga
 *
 * Laporan lapangan 13 Okt: halaman detail koin menampilkan **3/5 syarat lolos** pada kartu
 * `KESIAPAN HORIZON` sementara kartu `SCALP DESK` di bawahnya menampilkan **1/5 lolos**. Dua angka
 * untuk satu pertanyaan, tanpa cara bagi pembaca untuk tahu mana yang benar.
 *
 * Uji di bawah mengunci perbaikan itu pada tingkat yang paling murah dibuktikan:
 *
 * 1. Baris engine memakai **daftar syarat yang sama persis** dengan `ScalpReadiness` — label,
 *    urutan, dan nilai `ok` identik, bukan sekadar "mirip".
 * 2. Skornya dihitung penghitung yang sama dengan pra-saring, sehingga tidak ada dua skor.
 * 3. Status `HOLDING` punya statusnya sendiri — tidak pernah terbaca "SIAP".
 */
class EngineVerdictTest {

    // ── Helper ──────────────────────────────────────────────────────────────

    /**
     * MTF dengan jumlah kaki lolos yang bisa diatur.
     *
     * Sengaja tidak memakai "semua lolos" sebagai default: kasus yang dilaporkan justru saat
     * sebagian besar gagal, karena di situlah dua daftar syarat berbeda menghasilkan angka berbeda.
     */
    private fun mtf(
        bias: Boolean = false,
        orderbook: Boolean = false,
        trigger: Boolean = false,
        entryPrice: Boolean = false
    ) = ScalpingMtfSnapshot(
        biasOk = bias,
        biasDetail = "Bias detail.",
        setupOk = orderbook,
        setupDetail = "Orderbook detail.",
        triggerOk = trigger,
        triggerDetail = "Trigger detail.",
        entryPriceOk = entryPrice,
        entryPriceDetail = "Net RR detail.",
        statusTitle = "STATUS",
        waitingFor = "Menunggu"
    )

    private fun signal(
        setup: String = "BREAKOUT",
        action: SignalAction = SignalAction.HOLD,
        mtf: ScalpingMtfSnapshot = mtf(),
        entry: Double = 0.0,
        sl: Double = 0.0,
        tp1: Double = 0.0,
        tp2: Double = 0.0
    ) = AISignalState(
        action = action,
        scalpingStage = ScalpingStage.WATCH,
        scalpingSetup = setup,
        mtf = mtf,
        entryPrice = entry,
        stopLoss = sl,
        targetPrice1 = tp1,
        targetPrice2 = tp2
    )

    private fun verdict(
        signal: AISignalState,
        mode: StrategyMode = StrategyMode.SCALPING,
        planInCardElsewhere: Boolean = false
    ): HorizonReadiness {
        val readiness = ScalpReadiness.resolve(signal)
        return EngineVerdict.of(
            mode = mode,
            symbol = "BTC/IDR",
            signal = signal,
            readiness = readiness,
            price = 100_000.0,
            planInCardElsewhere = planInCardElsewhere
        )
    }

    // ── 1. Identitas dengan engine ──────────────────────────────────────────

    @Test
    fun checkpointsAreIdenticalToEngineSnapshot() {
        val s = signal(mtf = mtf(bias = true, orderbook = false, trigger = false, entryPrice = false))
        val readiness = ScalpReadiness.resolve(s)
        val row = verdict(s)

        assertEquals(
            "jumlah syarat harus sama dengan kartu engine",
            readiness.checkpoints.size,
            row.checks.size
        )
        readiness.checkpoints.forEachIndexed { i, cp ->
            assertEquals("label syarat #${i + 1}", cp.label, row.checks[i].label)
            assertEquals("nilai ok syarat #${i + 1}", cp.ok, row.checks[i].ok)
            assertEquals("detail syarat #${i + 1}", cp.detail, row.checks[i].detail)
        }
    }

    /**
     * Regresi inti: hanya 1 dari 5 checkpoint lolos harus tetap terbaca **1/5**.
     *
     * Sebelum perbaikan, pra-saring menghitung horizon SCALP dengan daftar syaratnya sendiri
     * (tren H1, momentum M15, dorongan M1, volatilitas, ekonomi) sehingga koin yang sama bisa
     * terbaca 3/5 — sementara engine berkata 1/5.
     */
    @Test
    fun oneOfFiveStaysOneOfFive() {
        // Hanya "Setup Scalping" (pola ada) yang lolos; tiga kaki engine lain gagal.
        val s = signal(setup = "BREAKOUT", mtf = mtf())
        val row = verdict(s)

        assertEquals(5, row.totalChecks)
        assertEquals(1, row.passedChecks)
        assertEquals(ReadinessState.WATCH, row.state)
        assertTrue("setup hadir, jadi checkpoint #2 lolos", row.checks[1].ok)
        assertFalse("checkpoint #1 (bias) harus gagal", row.checks[0].ok)
    }

    @Test
    fun scoreUsesTheSameWeightedScorerAsPrescreen() {
        val s = signal(mtf = mtf(bias = true, orderbook = true, trigger = false, entryPrice = false))
        val row = verdict(s)
        assertEquals(
            HorizonReadinessEngine.weightedScore(row.checks),
            row.score
        )
    }

    // ── 2. Pemetaan status ──────────────────────────────────────────────────

    @Test
    fun readyEngineStateMapsToReady() {
        val s = signal(
            action = SignalAction.BUY,
            mtf = mtf(bias = true, orderbook = true, trigger = true, entryPrice = true),
            // Level dari engine diperlukan supaya barisnya benar-benar actionable: READY tanpa
            // rencana bukan kandidat eksekusi, dan itu memang disengaja.
            entry = 100_000.0,
            sl = 95_000.0,
            tp1 = 103_000.0,
            tp2 = 110_000.0
        )
        val readiness = ScalpReadiness.resolve(s)
        assertEquals(ScalpReadinessState.READY, readiness.state)

        val row = verdict(s)
        assertEquals(ReadinessState.READY, row.state)
        assertEquals(5, row.passedChecks)
        assertTrue(row.actionable)
    }

    /**
     * READY **tanpa** level dari engine tidak boleh dianggap actionable.
     *
     * Status boleh "siap", tetapi tanpa entry/SL/TP tidak ada yang bisa dieksekusi — dan papan
     * yang menawarkannya sebagai kandidat akan mendorong pengguna menebak level sendiri.
     */
    @Test
    fun readyWithoutEngineLevelsIsNotActionable() {
        val s = signal(
            action = SignalAction.BUY,
            mtf = mtf(bias = true, orderbook = true, trigger = true, entryPrice = true)
        )
        val row = verdict(s)
        assertEquals(ReadinessState.READY, row.state)
        assertNull(row.plan)
        assertFalse(row.actionable)
    }

    @Test
    fun blockedEngineStateMapsToBlocked() {
        val s = signal(
            mtf = ScalpingMtfSnapshot(
                biasOk = true, setupOk = true, triggerOk = true, entryPriceOk = true,
                extremeVolatility = true
            )
        )
        assertEquals(ScalpReadinessState.BLOCKED, ScalpReadiness.resolve(s).state)
        assertEquals(ReadinessState.BLOCKED, verdict(s).state)
    }

    /**
     * `HOLDING` tidak boleh menyamar jadi `READY`.
     *
     * Posisi terbuka bukan "siap masuk" — dan kartu engine menampilkan `POSISI AKTIF`. Kalau
     * papan menerjemahkannya ke `SIAP`, dua kartu di layar yang sama akan berbeda lagi.
     */
    @Test
    fun holdingHasItsOwnStateAndNeverLooksReady() {
        val s = signal(
            action = SignalAction.BUY,
            mtf = mtf(bias = true, orderbook = true, trigger = true, entryPrice = true)
        )
        val readiness = ScalpReadiness.resolve(s, hasPaperPosition = true)
        assertEquals(ScalpReadinessState.HOLDING, readiness.state)

        val row = EngineVerdict.of(
            mode = StrategyMode.SCALPING,
            symbol = "BTC/IDR",
            signal = s,
            readiness = readiness,
            price = 100_000.0
        )
        assertEquals(ReadinessState.HOLDING, row.state)
        assertEquals(ReadinessState.HOLDING.label, "POSISI AKTIF")
        assertFalse("posisi terbuka bukan kandidat entry", row.actionable)
        assertTrue("HOLDING harus diurutkan paling atas", ReadinessState.HOLDING.rank > ReadinessState.READY.rank)
    }

    // ── 3. Sumber & rencana ─────────────────────────────────────────────────

    @Test
    fun rowIsAlwaysMarkedAsEngine() {
        val row = verdict(signal())
        assertEquals(ReadinessSource.ENGINE, row.source)
        assertTrue(row.fromEngine)
    }

    @Test
    fun planHiddenWhenEngineCardIsOnTheSameScreen() {
        val s = signal(
            action = SignalAction.BUY,
            mtf = mtf(bias = true, orderbook = true, trigger = true, entryPrice = true),
            entry = 100_000.0,
            sl = 95_000.0,
            tp1 = 103_000.0,
            tp2 = 110_000.0
        )
        val row = verdict(s, planInCardElsewhere = true)
        assertNull("rencana tidak boleh disalin bila kartu engine tampil di layar yang sama", row.plan)
        assertEquals(EngineVerdict.NOTE_PLAN_ELSEWHERE, row.sourceNote)
    }

    @Test
    fun planComesFromEnginePricesNotFromRecomputation() {
        val s = signal(
            action = SignalAction.BUY,
            mtf = mtf(bias = true, orderbook = true, trigger = true, entryPrice = true),
            entry = 100_000.0,
            sl = 95_000.0,
            tp1 = 103_000.0,
            tp2 = 110_000.0
        )
        val plan = verdict(s).plan
        requireNotNull(plan)

        assertEquals(100_000.0, plan.entry, 1e-9)
        assertEquals(95_000.0, plan.stopLoss, 1e-9)
        assertEquals(103_000.0, plan.takeProfit1, 1e-9)
        assertEquals(110_000.0, plan.takeProfit2, 1e-9)
        assertEquals("risiko = (entry - SL) / entry", 5.0, plan.riskPct, 1e-9)
        assertEquals("imbalan = (TP2 - entry) / entry", 10.0, plan.rewardPct, 1e-9)
        assertEquals("TP2 = 2R", 2.0, plan.tp2R, 1e-9)
    }

    @Test
    fun planIsNullWhenEngineHasNoLevels() {
        // Engine belum menetapkan SL/TP (mis. masih WATCH) — lebih baik kosong daripada karangan.
        assertNull(verdict(signal(entry = 100_000.0, sl = 0.0, tp2 = 110_000.0)).plan)
        assertNull(verdict(signal(entry = 100_000.0, sl = 105_000.0, tp2 = 110_000.0)).plan)
        assertNull(verdict(signal(entry = 100_000.0, sl = 95_000.0, tp2 = 90_000.0)).plan)
    }

    // ── 4. Horizon mengikuti engine aktif ───────────────────────────────────

    @Test
    fun horizonFollowsActiveEngine() {
        assertEquals(TradeHorizon.SCALP, verdict(signal(), mode = StrategyMode.SCALPING).horizon)
        assertEquals(TradeHorizon.INTRADAY, verdict(signal(), mode = StrategyMode.OFFICE_DAILY).horizon)
        assertEquals(TradeHorizon.SWING, verdict(signal(), mode = StrategyMode.SWING).horizon)
    }
}
