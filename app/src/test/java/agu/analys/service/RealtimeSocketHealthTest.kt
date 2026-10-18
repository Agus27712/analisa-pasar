package agu.analys.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **K7** — socket realtime tidak boleh diputus hanya karena pair sedang sepi.
 *
 * Cacat lama: `MarketDataCoordinator` memanggil `websocket.start(symbol)` begitu 30 detik tanpa
 * frame data. `start()` menjalankan `stop(false)`, jadi koneksi yang sehat diputus, candle menit
 * berjalan hilang, lalu semuanya diulang tiap 30 detik.
 *
 * Uji ini menjaga tiga hal:
 *  1. ambang keputusan memakai [RealtimeSocketHealth] (menghitung siklus ping, bukan 30 detik);
 *  2. coordinator menyambung ulang lewat `reconnectNow()`, bukan `start()`;
 *  3. `reconnectNow()` tidak mengosongkan candle menit berjalan.
 *
 * Kontrol negatif: kembalikan `nowMs - anchor > 30_000L` pada `needsRestart` → uji "pair sepi"
 * GAGAL; kembalikan `start(pair.symbol)` di coordinator → uji penjaga pemanggil GAGAL;
 * tambahkan `candle = null` di `reconnectNow` → uji penjaga candle GAGAL.
 */
class RealtimeSocketHealthTest {

    private fun sources(): Map<String, String> {
        val base = File(System.getProperty("user.dir"))
        val relatif = mapOf(
            "coordinator" to "viewmodel/MarketDataCoordinator.kt",
            "indodax" to "service/IndodaxMarketWebSocket.kt",
            "tokocrypto" to "service/TokocryptoMarketWebSocket.kt"
        )
        return relatif.mapValues { (_, path) ->
            val kandidat = listOf(
                File(base, "src/main/java/agu/analys/$path"),
                File(base, "app/src/main/java/agu/analys/$path")
            )
            val file = kandidat.firstOrNull { it.exists() }
                ?: error("$path tidak ditemukan dari user.dir=${base.absolutePath}")
            file.readLines()
                .filterNot { line ->
                    val s = line.trimStart()
                    s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
                }
                .joinToString("\n")
        }
    }

    @Test
    fun `socket tertutup tidak memicu restart dari pengecekan usang`() {
        assertFalse(
            "socket tertutup urusan job reconnect, jangan menumpuk percobaan",
            RealtimeSocketHealth.needsRestart(
                socketOpen = false,
                connectedAtMs = 0L,
                lastMessageAtMs = 0L,
                nowMs = 10_000_000L
            )
        )
    }

    @Test
    fun `sesi baru yang belum menerima frame masih diberi kesempatan`() {
        val now = 1_000_000L
        assertFalse(
            RealtimeSocketHealth.needsRestart(
                socketOpen = true,
                connectedAtMs = now - 10_000L,
                lastMessageAtMs = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun `sesi baru tanpa frame sama sekali terdeteksi sebagai zombie`() {
        val now = 1_000_000L
        assertTrue(
            "socket terbuka tanpa satu frame pun harus disambung ulang",
            RealtimeSocketHealth.needsRestart(
                socketOpen = true,
                connectedAtMs = now - (RealtimeSocketHealth.HANDSHAKE_GRACE_MS + 1_000L),
                lastMessageAtMs = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun `pair sepi hampir satu menit tidak memicu restart`() {
        val now = 1_000_000L
        assertFalse(
            "45 detik tanpa frame dulu memicu restart (ambang 30 detik); itu sebabnya koneksi sehat terputus",
            RealtimeSocketHealth.needsRestart(
                socketOpen = true,
                connectedAtMs = now - 600_000L,
                lastMessageAtMs = now - 45_000L,
                nowMs = now
            )
        )
    }

    @Test
    fun `diam melewati ambang siklus ping memicu restart`() {
        val now = 1_000_000L
        assertFalse(
            RealtimeSocketHealth.needsRestart(
                socketOpen = true,
                connectedAtMs = now - 600_000L,
                lastMessageAtMs = now - (RealtimeSocketHealth.SILENCE_MS - 1_000L),
                nowMs = now
            )
        )
        assertTrue(
            RealtimeSocketHealth.needsRestart(
                socketOpen = true,
                connectedAtMs = now - 600_000L,
                lastMessageAtMs = now - (RealtimeSocketHealth.SILENCE_MS + 1_000L),
                nowMs = now
            )
        )
    }

    @Test
    fun `ambang diam memberi ruang bagi ping pong mendeteksi kematian`() {
        assertTrue(
            "ambang diam harus beberapa kali lipat interval ping, bukan sekadar 30 detik",
            RealtimeSocketHealth.SILENCE_MS >= 4 * RealtimeSocketHealth.PING_INTERVAL_MS
        )
    }

    @Test
    fun `coordinator menyambung ulang lewat reconnectNow bukan start`() {
        val src = sources()["coordinator"]!!
        assertTrue("coordinator harus memanggil reconnectIfStale", src.contains("reconnectIfStale()"))
        assertFalse(
            "start() di dalam pengecekan usang membuang candle dan konfigurasi stream",
            src.contains("isStale(30_000L)")
        )
        // `start()` tetap sah untuk ganti pair (awal siklus), tetapi tidak boleh dipakai di
        // dalam blok pengecekan usang: ia menjalankan stop() dan membuang candle.
        val blok = src.substring(src.indexOf("reconnectIfStale()"))
        val batas = blok.indexOf("\n            }").let { if (it < 0) blok.length else it }
        assertFalse(
            "blok pengecekan usang tidak boleh memanggil start()",
            blok.substring(0, batas).contains(".start(")
        )
    }

    @Test
    fun `reconnectNow tidak mengosongkan candle menit berjalan`() {
        val src = sources()["indodax"]!!
        val mulai = src.indexOf("fun reconnectNow()")
        assertTrue("reconnectNow harus ada", mulai >= 0)
        val selesai = src.indexOf("\n    fun ", mulai + 1).let { if (it < 0) src.length else it }
        val tubuh = src.substring(mulai, selesai)
        assertFalse(
            "reconnectNow tidak boleh membuang candle; histori menit berjalan harus tetap hidup",
            tubuh.contains("candle = null")
        )
        assertFalse(tubuh.contains("pairId = \"\""))
    }

    @Test
    fun `reconnectNow tokocrypto tidak mengosongkan konfigurasi stream`() {
        val src = sources()["tokocrypto"]!!
        val mulai = src.indexOf("fun reconnectNow()")
        assertTrue("reconnectNow harus ada", mulai >= 0)
        val selesai = src.indexOf("\n    fun ", mulai + 1).let { if (it < 0) src.length else it }
        val tubuh = src.substring(mulai, selesai)
        assertFalse(tubuh.contains("streamSymbol = \"\""))
        assertFalse(tubuh.contains("streamHost = \"\""))
    }
}
