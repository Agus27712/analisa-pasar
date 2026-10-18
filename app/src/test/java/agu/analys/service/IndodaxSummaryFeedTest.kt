package agu.analys.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * **K5** — WebSocket Indodax harus benar-benar mengalirkan harga.
 *
 * Tiga cacat yang ditutup di sini:
 *  1. token di kode terpotong **satu karakter** (104, seharusnya 105) → server menutup
 *     koneksi dengan `3002 invalid token`, bukan karena token kedaluwarsa;
 *  2. kanal `chart:tick-<pair>` tidak pernah menerbitkan data (hanya di-ack
 *     `recoverable:true`), kanal yang benar adalah `market:summary-24h`;
 *  3. parser lama mengharapkan baris `[epoch, seq, harga, volume]`, sedangkan server
 *     mengirim `[pair, epoch, last, low, high, open, vol_idr, vol_koin]`.
 *
 * Kontrol negatif: kembalikan token terpotong / kanal `chart:tick-` / parser 4 kolom
 * → uji terkait GAGAL.
 */
class IndodaxSummaryFeedTest {

    private fun wsSource(): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/agu/analys/service/IndodaxMarketWebSocket.kt"),
            File(System.getProperty("user.dir"), "app/src/main/java/agu/analys/service/IndodaxMarketWebSocket.kt")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("IndodaxMarketWebSocket.kt tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    private fun staticToken(): String {
        val match = Regex("STATIC_TOKEN\\s*=\\s*\"([^\"]+)\"")
            .find(wsSource().readText())
            ?: error("STATIC_TOKEN tidak ditemukan")
        return match.groupValues[1]
    }

    private fun expEpochSecond(token: String): Long {
        val payload = token.split('.')[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val json = String(Base64.getUrlDecoder().decode(padded))
        return JSONObject(json).getLong("exp")
    }

    private fun payload(vararg rows: String): String =
        """{"result":{"channel":"market:summary-24h","data":{"data":[${rows.joinToString(",")}],"offset":147527011}}}"""

    private val btcRow =
        """["btcidr",1792251000,1459280000,1454489000,1470242000,1462856000,"10482997980","7.17230292"]"""

    @Test
    fun `token di kode utuh dan masih berlaku`() {
        val token = staticToken()
        assertEquals("token harus 105 karakter", 105, token.length)
        assertTrue(IndodaxSummaryFeed.isValidTokenShape(token))
        assertTrue("token kedaluwarsa", expEpochSecond(token) > System.currentTimeMillis() / 1000 + 365L * 24 * 3600)
    }

    @Test
    fun `token terpotong satu karakter ditolak`() {
        val terpotong = staticToken().dropLast(1)
        assertEquals(104, terpotong.length)
        assertFalse(IndodaxSummaryFeed.isValidTokenShape(terpotong))
    }

    @Test
    fun `baris nyata diparse sesuai urutan kolom server`() {
        val tick = IndodaxSummaryFeed.parse(payload(btcRow), "btcidr", 0.0)!!
        assertEquals("btcidr", tick.pairId)
        assertEquals(1792251000000L, tick.timestampMs)
        assertEquals(1_459_280_000.0, tick.price, 0.0)
        assertEquals(1_454_489_000.0, tick.low24h, 0.0)
        assertEquals(1_470_242_000.0, tick.high24h, 0.0)
        assertEquals(1_462_856_000.0, tick.open24h, 0.0)
        assertEquals(10_482_997_980.0, tick.volumeIdr24h, 0.0)
        assertEquals(7.17230292, tick.volumeCoin24h, 1e-9)
        assertEquals(0.0, tick.volumeCoinDelta, 0.0)
        assertEquals(-0.2445, tick.change24h, 0.0001)
    }

    @Test
    fun `delta volume memakai selisih kumulatif`() {
        val tick = IndodaxSummaryFeed.parse(payload(btcRow), "btcidr", 7.0)!!
        assertEquals(0.17230292, tick.volumeCoinDelta, 1e-9)
    }

    @Test
    fun `volume kumulatif turun tidak menghasilkan delta negatif`() {
        val tick = IndodaxSummaryFeed.parse(payload(btcRow), "btcidr", 9.0)!!
        assertEquals(0.0, tick.volumeCoinDelta, 0.0)
    }

    @Test
    fun `pair lain dalam aliran yang sama diabaikan`() {
        val lain = """["ethidr",1792251001,50000000,49000000,51000000,49500000,"1000","2"]"""
        assertNull(IndodaxSummaryFeed.parse(payload(lain), "btcidr", 0.0))
    }

    @Test
    fun `kanal lama chart tick tidak lagi diterima`() {
        val pesanLama =
            """{"result":{"channel":"chart:tick-btcidr","data":{"data":[[1792251000,1,1459280000,0.5]]}}}"""
        assertNull(IndodaxSummaryFeed.parse(pesanLama, "btcidr", 0.0))
        assertFalse(
            "kanal chart:tick- tidak boleh dipakai lagi",
            wsSource().readText().contains("chart:tick-")
        )
    }

    @Test
    fun `baris format lama empat kolom ditolak`() {
        val barisLama = """[1792251000,1,1459280000,0.5]"""
        assertNull(IndodaxSummaryFeed.parse(payload(barisLama), "btcidr", 0.0))
    }

    @Test
    fun `pesan rusak atau tanpa result tidak melempar`() {
        assertNull(IndodaxSummaryFeed.parse("bukan json", "btcidr", 0.0))
        assertNull(IndodaxSummaryFeed.parse("""{"id":1}""", "btcidr", 0.0))
        assertNull(IndodaxSummaryFeed.parse(payload(btcRow), "", 0.0))
    }
}
