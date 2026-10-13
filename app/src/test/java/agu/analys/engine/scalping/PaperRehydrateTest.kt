package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji aturan rehidrasi state machine exit paper (P2).
 *
 * Bug yang dijaga di sini: setelah app restart, posisi paper **menggantung** karena
 * `PaperAutoTradeRuntime` hanya hidup di memori sementara `auto_sell_enabled` sudah
 * dimatikan saat engine mengambil alih exit.
 *
 * Kasus terpenting bukan "berhasil memulihkan", melainkan **kapan menolak memulihkan**:
 * `SpotPositionStore.setHolding` menulis SL sintetis `entry * 0.99`, jadi memulihkan
 * tanpa validasi akan membangun mesin dengan SL 1% padahal SL engine minimal sebesar
 * lantai friksi. Itu akan memalsukan stop-out di jurnal.
 */
class PaperRehydrateTest {

    // Friksi USDT Tokocrypto yang dipakai di seluruh proyek.
    private val buyFee = 0.2422
    private val sellFee = 0.2422
    private val slippage = 0.2422

    private val params = ScalpExitParams(
        mode = ScalpExitMode.CLASSIC,
        minEarlyProfitNetPct = 0.45,
        breakevenTriggerNetPct = 0.40,
        trailPct = 0.35,
        maxHoldBarsM1 = 90
    )

    private fun decide(
        entry: Double = 100.0,
        stopLoss: Double = 94.0,
        tp1: Double = 104.0,
        tp2: Double = 108.0,
        autoExitEnabled: Boolean = true
    ) = PaperRehydrate.decide(
        entry = entry,
        stopLoss = stopLoss,
        tp1 = tp1,
        tp2 = tp2,
        autoExitEnabled = autoExitEnabled,
        buyFeePct = buyFee,
        sellFeePct = sellFee,
        slippagePct = slippage,
        params = params
    )

    // ── Jalur pulih ─────────────────────────────────────────────────────────

    @Test
    fun `posisi valid dipulihkan dengan parameter yang tersimpan`() {
        val action = decide()
        assertTrue("harus Restore, dapat $action", action is PaperRehydrateAction.Restore)
        val m = (action as PaperRehydrateAction.Restore).machine
        assertEquals(100.0, m.entry, 1e-9)
        assertEquals(94.0, m.stopLoss, 1e-9)
        assertEquals(104.0, m.tp1, 1e-9)
        assertEquals(108.0, m.tp2, 1e-9)
        assertTrue("risiko harus valid", m.isRiskValid)
    }

    @Test
    fun `risiko mesin dipulihkan sama dengan jarak entry ke SL`() {
        val m = (decide() as PaperRehydrateAction.Restore).machine
        // (100 - 94) / 100 * 100 = 6.0%
        assertEquals(6.0, m.riskPctValue, 1e-9)
    }

    @Test
    fun `mesin dipulihkan mulai dari fase HOLDING tanpa progres`() {
        val m = (decide() as PaperRehydrateAction.Restore).machine
        assertEquals(PaperExitPhase.HOLDING, m.phase)
        assertEquals(0, m.barsHeld)
        assertEquals(false, m.isBeArmed)
        assertEquals(false, m.isPartialDone)
    }

    @Test
    fun `harga impas net berada di atas entry karena friksi`() {
        val m = (decide() as PaperRehydrateAction.Restore).machine
        assertTrue("BE net (${m.netBreakevenPrice}) harus > entry", m.netBreakevenPrice > m.entry)
    }

    @Test
    fun `tp1 nol jatuh ke entry dan tp2 nol jatuh ke entry kali 1_02`() {
        val m = (decide(tp1 = 0.0, tp2 = 0.0) as PaperRehydrateAction.Restore).machine
        assertEquals(100.0, m.tp1, 1e-9)
        assertEquals(102.0, m.tp2, 1e-9)
    }

    // ── Jalur tolak: ini inti perbaikannya ──────────────────────────────────

    @Test
    fun `auto-exit dimatikan diserahkan kembali ke jalur lama`() {
        val action = decide(autoExitEnabled = false)
        assertTrue("harus HandBackToLegacy, dapat $action", action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_AUTO_EXIT_OFF, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `SL nol ditolak - tidak boleh membangun mesin tanpa risiko`() {
        val action = decide(stopLoss = 0.0)
        assertTrue("harus HandBackToLegacy, dapat $action", action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_INVALID_SL, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `SL di atas entry ditolak - arah risiko terbalik`() {
        val action = decide(stopLoss = 101.0)
        assertTrue(action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_INVALID_SL, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `SL sama dengan entry ditolak - risiko nol`() {
        val action = decide(stopLoss = 100.0)
        assertTrue(action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_INVALID_SL, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `entry nol dilewati - tidak ada yang bisa dipulihkan`() {
        val action = decide(entry = 0.0)
        assertTrue("harus Skip, dapat $action", action is PaperRehydrateAction.Skip)
        assertEquals(PaperRehydrate.REASON_NO_ENTRY, (action as PaperRehydrateAction.Skip).reason)
    }

    @Test
    fun `entry negatif dilewati`() {
        assertTrue(decide(entry = -5.0) is PaperRehydrateAction.Skip)
    }

    @Test
    fun `entry NaN dilewati - bukan angka yang bisa dihitung`() {
        assertTrue(decide(entry = Double.NaN) is PaperRehydrateAction.Skip)
    }

    @Test
    fun `SL NaN ditolak`() {
        assertTrue(decide(stopLoss = Double.NaN) is PaperRehydrateAction.HandBackToLegacy)
    }

    @Test
    fun `SL Infinity ditolak`() {
        assertTrue(decide(stopLoss = Double.POSITIVE_INFINITY) is PaperRehydrateAction.HandBackToLegacy)
    }

    @Test
    fun `urutan prioritas - auto-exit mati menang atas SL tidak valid`() {
        // Kepemilikan exit ditentukan lebih dulu: kalau auto-exit mati, jalur lama yang
        // berhak, apa pun kondisi SL-nya.
        val action = decide(stopLoss = 0.0, autoExitEnabled = false)
        assertTrue(action is PaperRehydrateAction.HandBackToLegacy)
        assertEquals(PaperRehydrate.REASON_AUTO_EXIT_OFF, (action as PaperRehydrateAction.HandBackToLegacy).reason)
    }

    @Test
    fun `setiap tindakan membawa alasan yang bisa dibaca manusia`() {
        val handBack = decide(stopLoss = 0.0) as PaperRehydrateAction.HandBackToLegacy
        val skip = decide(entry = 0.0) as PaperRehydrateAction.Skip
        assertTrue(handBack.reason.isNotBlank())
        assertTrue(skip.reason.isNotBlank())
        assertNotNull(handBack.reason)
    }
}
