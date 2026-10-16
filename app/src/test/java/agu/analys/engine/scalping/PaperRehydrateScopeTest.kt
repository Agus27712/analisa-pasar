package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — cakupan bursa rehidrasi.
 *
 * Keputusan ini dulu tertanam di dalam `rehydratePaperAutoExit` (ekstensi ViewModel yang tidak bisa
 * diuji) dan hanya membaca bursa aktif, sehingga posisi paper di bursa lain kehilangan mesin exitnya
 * selamanya setelah restart.
 */
class PaperRehydrateScopeTest {

    @Test
    fun `semua bursa yang punya posisi paper ikut, bukan hanya bursa aktif`() {
        val scope = PaperRehydrateScope.decide(
            storedExchanges = listOf("INDODAX", "TOKOCRYPTO"),
            activeExchange = "TOKOCRYPTO"
        )

        assertEquals(listOf("TOKOCRYPTO", "INDODAX"), scope)
        assertTrue("bursa non-aktif tidak boleh hilang dari cakupan", scope.contains("INDODAX"))
    }

    @Test
    fun `bursa aktif didahulukan`() {
        val scope = PaperRehydrateScope.decide(
            storedExchanges = listOf("INDODAX", "TOKOCRYPTO"),
            activeExchange = "INDODAX"
        )

        assertEquals("INDODAX", scope.first())
        assertEquals(listOf("INDODAX", "TOKOCRYPTO"), scope)
    }

    @Test
    fun `sisanya deterministik - urut alfabetis`() {
        val a = PaperRehydrateScope.decide(listOf("TOKOCRYPTO", "INDODAX", "BINANCE"), "BINANCE")
        val b = PaperRehydrateScope.decide(listOf("BINANCE", "TOKOCRYPTO", "INDODAX"), "BINANCE")

        assertEquals(listOf("BINANCE", "INDODAX", "TOKOCRYPTO"), a)
        assertEquals("urutan tidak boleh bergantung urutan masukan", a, b)
    }

    @Test
    fun `normalisasi spasi, huruf besar, dan duplikat`() {
        val scope = PaperRehydrateScope.decide(
            storedExchanges = listOf("  tokocrypto ", "TOKOCRYPTO", "indodax", ""),
            activeExchange = "tokocrypto"
        )

        assertEquals(listOf("TOKOCRYPTO", "INDODAX"), scope)
    }

    @Test
    fun `tidak ada posisi paper berarti tidak ada yang direhidrasi`() {
        assertTrue(PaperRehydrateScope.decide(emptyList(), "TOKOCRYPTO").isEmpty())
        assertTrue(PaperRehydrateScope.decide(listOf("", "   "), "TOKOCRYPTO").isEmpty())
    }

    @Test
    fun `bursa aktif yang tidak punya posisi paper tidak ditambahkan`() {
        val scope = PaperRehydrateScope.decide(listOf("INDODAX"), "TOKOCRYPTO")

        assertEquals(listOf("INDODAX"), scope)
        assertTrue("jangan mengarang bursa tanpa posisi", "TOKOCRYPTO" !in scope)
    }

    @Test
    fun `bursa aktif kosong tetap mengembalikan semua bursa tersimpan`() {
        val scope = PaperRehydrateScope.decide(listOf("INDODAX", "TOKOCRYPTO"), "")

        assertEquals(listOf("INDODAX", "TOKOCRYPTO"), scope)
    }
}
