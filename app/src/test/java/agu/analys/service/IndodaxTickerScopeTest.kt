package agu.analys.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **K11 + K12** — lingkup ticker Indodax harus mengikuti pemanggilnya, bukan seluruh pasar.
 *
 * - **K11:** `CandidateScanWorker` hanya memindai pair watchlist, jadi tidak boleh memanggil
 *   `fetchAllMarketTicks()` yang mengunduh seluruh pasar tiap pemindaian.
 * - **K12:** `IndodaxMarketService.fetchTickers(list)` harus mengambil per-pair lewat
 *   `/api/ticker/<pair>`, bukan mengunduh `/api/summaries` lalu memfilternya di memori. Jalur
 *   dashboard penuh ([IndodaxMarketService.fetchAllMarketTicks]) tetap ada.
 *
 * Kontrol negatif: kembalikan `fetchAllMarketTicks()` di worker → uji "worker Indodax hanya
 * mengambil pair watchlist" GAGAL; kembalikan `fetchTickers` ke `/api/summaries` → uji
 * "fetchTickers Indodax per pair" GAGAL.
 */
class IndodaxTickerScopeTest {

    private fun berkas(relatif: String): String {
        val base = File(System.getProperty("user.dir"))
        val kandidat = listOf(
            File(base, "src/main/java/agu/analys/$relatif"),
            File(base, "app/src/main/java/agu/analys/$relatif")
        )
        val file = kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${base.absolutePath}")
        return file.readLines()
            .filterNot { baris ->
                val s = baris.trimStart()
                s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
            }
            .joinToString("\n")
    }

    private val workerKode: String by lazy { berkas("service/CandidateScanWorker.kt") }
    private val serviceKode: String by lazy { berkas("service/IndodaxMarketService.kt") }

    private fun badanFungsi(kode: String, tandaTangan: String): String {
        val mulai = kode.indexOf(tandaTangan)
        require(mulai >= 0) { "tidak ditemukan: $tandaTangan" }
        val penanda = listOf("\n    suspend fun ", "\n    private fun ", "\n    fun ")
        val batas = penanda
            .map { kode.indexOf(it, mulai + tandaTangan.length) }
            .filter { it >= 0 }
            .minOrNull() ?: kode.length
        return kode.substring(mulai, batas)
    }

    @Test
    fun `worker Indodax hanya mengambil pair watchlist`() {
        assertTrue(
            "worker wajib memakai fetchTickers agar lingkupnya = watchlist",
            workerKode.contains("IndodaxMarketService.fetchTickers(")
        )
        assertFalse(
            "K11: fetchAllMarketTicks menarik seluruh pasar tiap pemindaian",
            workerKode.contains("IndodaxMarketService.fetchAllMarketTicks(")
        )
    }

    @Test
    fun `fetchTickers Indodax per pair bukan seluruh pasar`() {
        val badan = badanFungsi(serviceKode, "suspend fun fetchTickers(")
        assertTrue("harus memakai endpoint per-pair", badan.contains("fetchTicker("))
        assertFalse(
            "K12: /api/summaries menarik seluruh pasar; itu milik fetchAllMarketTicks",
            badan.contains("summaries")
        )
    }

    @Test
    fun `jalur dashboard penuh tetap tersedia`() {
        assertTrue(
            "dashboard butuh daftar seluruh pasar; jangan ikut diubah",
            serviceKode.contains("suspend fun fetchAllMarketTicks(")
        )
    }
}
