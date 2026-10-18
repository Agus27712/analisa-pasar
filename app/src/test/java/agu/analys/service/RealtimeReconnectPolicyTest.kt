package agu.analys.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **K8 + K9** — penjagaan tingkat sumber untuk kebijakan sambung-ulang kedua soket.
 *
 * - **K8:** hitungan percobaan tidak boleh lagi di-reset di `onOpen`; reset hanya setelah sesi
 *   terbukti stabil, dan jeda memakai [ReconnectBackoff] (jitter + plafon + jeda menyerah).
 * - **K9:** listener dari generasi lama (socket yang sudah dibuang) harus diabaikan, kalau tidak
 *   frame socket usang bisa mengacaukan tick pair yang sedang aktif.
 *
 * Kontrol negatif: kembalikan `reconnectAttempt = 0` ke dalam blok `onOpen` → uji
 * "percobaan tidak direset lagi di onOpen" GAGAL; hapus perbandingan `gen == generation` di
 * `connect()` → uji "listener generasi lama diabaikan" GAGAL.
 */
class RealtimeReconnectPolicyTest {

    private fun berkas(relatif: String): String {
        val base = File(System.getProperty("user.dir"))
        val kandidat = listOf(
            File(base, "src/main/java/agu/analys/$relatif"),
            File(base, "app/src/main/java/agu/analys/$relatif")
        )
        val file = kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${base.absolutePath}")
        // Buang komentar per baris supaya dokumentasi yang menyebut nama lama tidak ikut tertangkap.
        return file.readLines()
            .filterNot { baris ->
                val s = baris.trimStart()
                s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
            }
            .joinToString("\n")
    }

    private val indodaxKode: String by lazy { berkas("service/IndodaxMarketWebSocket.kt") }
    private val tokocryptoKode: String by lazy { berkas("service/TokocryptoMarketWebSocket.kt") }

    /** Blok dari [tandaTangan] sampai `override` berikutnya. */
    private fun blokOverride(kode: String, tandaTangan: String): String {
        val mulai = kode.indexOf(tandaTangan)
        require(mulai >= 0) { "tidak ditemukan: $tandaTangan" }
        val berikut = kode.indexOf("override fun ", mulai + tandaTangan.length)
        return kode.substring(mulai, if (berikut < 0) kode.length else berikut)
    }

    /** Badan sebuah fungsi anggota kelas, berhenti di anggota berikutnya. */
    private fun badanFungsi(kode: String, tandaTangan: String): String {
        val mulai = kode.indexOf(tandaTangan)
        require(mulai >= 0) { "tidak ditemukan: $tandaTangan" }
        val penanda = listOf("\n    suspend fun ", "\n    private fun ", "\n    fun ", "\n    /**", "\n    @")
        val batas = penanda
            .map { kode.indexOf(it, mulai + tandaTangan.length) }
            .filter { it >= 0 }
            .minOrNull() ?: kode.length
        return kode.substring(mulai, batas)
    }

    @Test
    fun `kedua socket memakai kebijakan backoff bersama`() {
        listOf("indodax" to indodaxKode, "tokocrypto" to tokocryptoKode).forEach { (nama, src) ->
            assertTrue("$nama harus memakai jeda berjitter", src.contains("ReconnectBackoff.delayMs("))
            assertTrue("$nama harus memakai batas percobaan", src.contains("ReconnectBackoff.shouldGiveUp("))
            assertTrue("$nama harus memakai jeda menyerah", src.contains("ReconnectBackoff.GIVE_UP_PAUSE_MS"))
        }
    }

    @Test
    fun `percobaan tidak direset lagi di onOpen`() {
        val onOpenToko = blokOverride(tokocryptoKode, "override fun onOpen(")
        assertFalse(
            "K8: reset di onOpen membuat server yang menutup segera dihajar tiap 600 ms",
            onOpenToko.contains("reconnectAttempt = 0")
        )
        val onOpenIndodax = blokOverride(indodaxKode, "override fun onOpen(")
        assertFalse(onOpenIndodax.contains("reconnectAttempt = 0"))
    }

    @Test
    fun `reset dilakukan setelah sesi terbukti stabil`() {
        val pesanToko = blokOverride(tokocryptoKode, "override fun onMessage(")
        assertTrue(pesanToko.contains("ReconnectBackoff.isStable("))
        assertTrue(pesanToko.contains("reconnectAttempt = 0"))

        val konsumsiIndodax = badanFungsi(indodaxKode, "private fun consume(")
        assertTrue(konsumsiIndodax.contains("ReconnectBackoff.isStable("))
        assertTrue(konsumsiIndodax.contains("reconnectAttempt = 0"))
    }

    @Test
    fun `listener generasi lama diabaikan`() {
        listOf("indodax" to indodaxKode, "tokocrypto" to tokocryptoKode).forEach { (nama, src) ->
            assertTrue("$nama harus menyimpan nomor generasi", src.contains("private var generation"))
            assertTrue("$nama harus menaikkan generasi saat ganti socket", src.contains("generation++"))
            assertTrue("$nama harus membandingkan generasi listener", src.contains("gen == generation"))
        }
    }

    @Test
    fun `jeda dasar dan plafon terdefinisi di kedua socket`() {
        listOf("indodax" to indodaxKode, "tokocrypto" to tokocryptoKode).forEach { (nama, src) ->
            assertTrue("$nama butuh BASE_RECONNECT_MS", src.contains("BASE_RECONNECT_MS"))
            assertTrue("$nama butuh MAX_RECONNECT_MS", src.contains("MAX_RECONNECT_MS"))
        }
    }
}
