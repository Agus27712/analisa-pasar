package agu.analys.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J6 — catatan netral "RELIABILITAS SKOR KEYAKINAN" menggantikan klaim hardcoded
 * "skor keyakinan tinggi menunjukkan rasio kemenangan tertinggi dan drawdown terendah".
 *
 * Yang diuji: (1) tanpa data sama sekali tidak ada teks yang dicetak; (2) sampel < 10 sinyal
 * selesai menghasilkan catatan "belum cukup", bukan klaim; (3) sampel cukup menghasilkan
 * deskripsi yang menyebut basis (sinya selesai vs total, PnL bersih) dan jumlah sampel;
 * (4) tier kosong disebut sebagai fakta data (N11).
 */
class ConfidenceTierNoteTest {

    private fun summary(
        total: Int,
        completed: Int,
        high: Int = 0,
        medium: Int = 0,
        low: Int = 0
    ): SignalReliabilitySummary = SignalReliabilitySummary(
        totalLogs = total,
        completedLogs = completed,
        highConfidenceStats = ConfidenceTierStats("Tinggi (≥80%)", 80, 100, high, 0, 0, 0, 0.0, 0.0),
        mediumConfidenceStats = ConfidenceTierStats("Sedang (60-79%)", 60, 79, medium, 0, 0, 0, 0.0, 0.0),
        lowConfidenceStats = ConfidenceTierStats("Awal (<60%)", 0, 59, low, 0, 0, 0, 0.0, 0.0)
    )

    @Test
    fun `tanpa data tidak ada teks yang dicetak`() {
        assertNull(ConfidenceTierNoteResolver.resolve(summary(total = 0, completed = 0)))
    }

    @Test
    fun `summary default tidak ada data`() {
        assertNull(ConfidenceTierNoteResolver.resolve(SignalReliabilitySummary()))
    }

    @Test
    fun `sampel kurang dari minimum tidak layak dikutip`() {
        val note = ConfidenceTierNoteResolver.resolve(
            summary(total = 9, completed = 9, high = 1, medium = 3, low = 5)
        )
        assertNotNull(note)
        assertFalse(note!!.isReliable)
        assertTrue(note.text.contains("9"))
        assertTrue(note.text.contains("minimal ${ConfidenceTierNoteResolver.MIN_RESOLVED_SAMPLE}"))
    }

    @Test
    fun `sinyal semua tracking dihitung belum cukup`() {
        // total besar tapi nol selesai: win rate 0% bukan bukti, tabel belum bisa dikutip
        val note = ConfidenceTierNoteResolver.resolve(summary(total = 50, completed = 0, low = 50))
        assertNotNull(note)
        assertFalse(note!!.isReliable)
    }

    @Test
    fun `tepat di ambang layak dikutip`() {
        val note = ConfidenceTierNoteResolver.resolve(
            summary(total = 12, completed = 10, high = 2, medium = 3, low = 7)
        )
        assertNotNull(note)
        assertTrue(note!!.isReliable)
    }

    @Test
    fun `teks layak menyebut basis dan ukuran sampel`() {
        val note = ConfidenceTierNoteResolver.resolve(
            summary(total = 20, completed = 15, high = 1, medium = 4, low = 15)
        )
        assertNotNull(note)
        val text = note!!.text
        assertTrue(text.contains("15 sinyal selesai"))
        assertTrue(text.contains("20 total"))
        assertTrue(text.contains("fee & slippage"))
    }

    @Test
    fun `tier kosong disebut sebagai fakta data`() {
        val note = ConfidenceTierNoteResolver.resolve(
            summary(total = 12, completed = 11, high = 0, medium = 0, low = 12)
        )
        assertNotNull(note)
        assertTrue(note!!.text.contains("2 dari 3 tier belum terisi"))
        assertTrue(note.isReliable)
    }

    @Test
    fun `semua tier terisi tidak menyebut tier kosong`() {
        val note = ConfidenceTierNoteResolver.resolve(
            summary(total = 30, completed = 25, high = 3, medium = 10, low = 17)
        )
        assertNotNull(note)
        assertFalse(note!!.text.contains("tier belum terisi"))
    }

    @Test
    fun `hanya satu tier kosong disebut satu`() {
        val note = ConfidenceTierNoteResolver.resolve(
            summary(total = 20, completed = 15, high = 0, medium = 5, low = 15)
        )
        assertNotNull(note)
        assertTrue(note!!.text.contains("1 dari 3 tier belum terisi"))
    }

    @Test
    fun `teks tidak lagi memuat klaim historis hardcoded`() {
        val note = ConfidenceTierNoteResolver.resolve(
            summary(total = 40, completed = 30, high = 4, medium = 12, low = 24)
        )
        assertNotNull(note)
        val text = note!!.text.lowercase()
        // frasa klaim J6 yang lama: "rasio kemenangan tertinggi" / "drawdown terendah"
        assertFalse(text.contains("rasio kemenangan tertinggi"))
        assertFalse(text.contains("drawdown terendah"))
        assertFalse(text.contains("real-time"))
    }

    @Test
    fun `threshold sampel konsisten dengan SetupStats`() {
        assertEquals(SetupStats.MIN_RELIABLE_SAMPLE, ConfidenceTierNoteResolver.MIN_RESOLVED_SAMPLE)
    }
}
