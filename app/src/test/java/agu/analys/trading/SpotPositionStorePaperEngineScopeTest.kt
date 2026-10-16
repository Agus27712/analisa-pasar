package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * P1 — enumerasi posisi paper **lintas bursa** di [SpotPositionStore].
 *
 * Sebelum perbaikan, satu-satunya cara menemukan posisi paper adalah
 * [SpotPositionStore.getPaperEngineSymbols], yang **mensyaratkan** nama bursa. Rehidrasi karena itu
 * hanya bisa melihat bursa yang sedang aktif, dan posisi di bursa lain berjalan tanpa mesin exit
 * selamanya.
 */
@RunWith(RobolectricTestRunner::class)
class SpotPositionStorePaperEngineScopeTest {

    private fun newStore(): SpotPositionStore =
        SpotPositionStore(RuntimeEnvironment.getApplication())

    private fun bukaPosisi(
        store: SpotPositionStore,
        symbol: String,
        exchange: String,
        owned: Boolean = true
    ) {
        store.markBought(symbol = symbol, entryPrice = 100.0, invested = 100.0, quantity = 1.0, exchange = exchange)
        store.setPaperEngineOwned(symbol = symbol, isReal = false, exchange = exchange, owned = owned)
    }

    @Test
    fun `posisi paper di dua bursa ditemukan sekaligus`() {
        val store = newStore()
        bukaPosisi(store, "BTCIDR", "TOKOCRYPTO")
        bukaPosisi(store, "ETHIDR", "INDODAX")

        val semua = store.getPaperEnginePositions()

        assertEquals(
            listOf(PaperEnginePosition("INDODAX", "ETHIDR"), PaperEnginePosition("TOKOCRYPTO", "BTCIDR")),
            semua
        )
    }

    @Test
    fun `penyaring per bursa tetap berperilaku seperti sebelumnya`() {
        val store = newStore()
        bukaPosisi(store, "BTCIDR", "TOKOCRYPTO")
        bukaPosisi(store, "ETHIDR", "INDODAX")

        assertEquals(listOf("BTCIDR"), store.getPaperEngineSymbols(exchange = "TOKOCRYPTO"))
        assertEquals(listOf("ETHIDR"), store.getPaperEngineSymbols(exchange = "INDODAX"))
    }

    @Test
    fun `posisi tanpa penanda paper tidak ikut terhitung`() {
        val store = newStore()
        bukaPosisi(store, "SOLIDR", "TOKOCRYPTO", owned = false)

        assertTrue(store.getPaperEnginePositions().isEmpty())
    }

    @Test
    fun `posisi yang sudah tidak HOLDING tidak ikut terhitung`() {
        val store = newStore()
        bukaPosisi(store, "XRPIDR", "TOKOCRYPTO")
        store.markSold("XRPIDR", exchange = "TOKOCRYPTO")

        assertTrue(store.getPaperEnginePositions().isEmpty())
    }

    @Test
    fun `penanda real tidak tercampur dengan simulasi`() {
        val store = newStore()
        store.markBought(symbol = "ADAIDR", entryPrice = 100.0, invested = 100.0, quantity = 1.0, isReal = true, exchange = "TOKOCRYPTO")
        store.setPaperEngineOwned(symbol = "ADAIDR", isReal = true, exchange = "TOKOCRYPTO", owned = true)
        bukaPosisi(store, "ADAIDR", "TOKOCRYPTO")

        assertEquals(
            "hanya partisi simulasi yang boleh muncul",
            listOf(PaperEnginePosition("TOKOCRYPTO", "ADAIDR")),
            store.getPaperEnginePositions(isReal = false)
        )
        assertEquals(
            listOf(PaperEnginePosition("TOKOCRYPTO", "ADAIDR")),
            store.getPaperEnginePositions(isReal = true)
        )
    }

    @Test
    fun `simbol hasil enumerasi bisa langsung dipakai untuk membaca posisinya`() {
        val store = newStore()
        bukaPosisi(store, "DOTIDR", "INDODAX")

        val pos = store.getPaperEnginePositions().single()

        assertTrue(
            "simbol harus kanonik agar get() menemukan posisinya",
            store.get(pos.symbol, isReal = false, exchange = pos.exchange).isHolding
        )
    }
}
