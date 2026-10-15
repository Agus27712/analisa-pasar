package agu.analys.engine

import agu.analys.model.DetailScalpPanel
import agu.analys.model.DetailTradeMode
import agu.analys.model.resolveDetailScalpPanel
import agu.analys.model.resolveDetailTradeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uji **panel scalping halaman detail koin** yang otoritatif.
 *
 * Sebelumnya `DetailChartScreen` merender `ScalpDeskCard` + `PaperTradeDeskCard` dengan syarat
 * `isScalping` saja, **tanpa melihat status kepemilikan koin**. Akibatnya frame yang sama muncul
 * entah pengguna sedang memegang koin atau tidak, sehingga mode beli dan mode jual terlihat
 * identik — padahal keduanya butuh informasi yang berbeda:
 *
 * - **tidak hold** → keputusan masuk ([DetailScalpPanel.SCALPING_DESK]);
 * - **sedang hold** → progres keluar ([DetailScalpPanel.EXIT_FSM]).
 *
 * Uji ini menjaga agar meja kerja entry tidak pernah muncul bersamaan dengan mode jual, dan agar
 * FSM exit tidak diam-diam muncul saat scalping sedang nonaktif.
 */
class DetailScalpPanelTest {

    @Test
    fun `tidak hold dengan scalping aktif menampilkan meja kerja entry`() {
        assertEquals(
            DetailScalpPanel.SCALPING_DESK,
            resolveDetailScalpPanel(isScalping = true, isHolding = false)
        )
    }

    @Test
    fun `sedang hold dengan scalping aktif menampilkan fsm exit`() {
        assertEquals(
            DetailScalpPanel.EXIT_FSM,
            resolveDetailScalpPanel(isScalping = true, isHolding = true)
        )
    }

    @Test
    fun `scalping nonaktif tidak menampilkan panel apa pun meski sedang hold`() {
        assertEquals(
            DetailScalpPanel.NONE,
            resolveDetailScalpPanel(isScalping = false, isHolding = true)
        )
    }

    @Test
    fun `scalping nonaktif tidak menampilkan panel apa pun saat tidak hold`() {
        assertEquals(
            DetailScalpPanel.NONE,
            resolveDetailScalpPanel(isScalping = false, isHolding = false)
        )
    }

    @Test
    fun `panel selaras dengan mode beli jual halaman detail`() {
        // Sedang hold: mode jual DAN progres exit — bukan meja kerja entry.
        assertEquals(DetailTradeMode.SELL, resolveDetailTradeMode(isHolding = true))
        assertEquals(
            DetailScalpPanel.EXIT_FSM,
            resolveDetailScalpPanel(isScalping = true, isHolding = true)
        )

        // Tidak hold: mode beli DAN meja kerja entry.
        assertEquals(DetailTradeMode.BUY, resolveDetailTradeMode(isHolding = false))
        assertEquals(
            DetailScalpPanel.SCALPING_DESK,
            resolveDetailScalpPanel(isScalping = true, isHolding = false)
        )
    }

    @Test
    fun `meja kerja entry tidak pernah tampil bersamaan dengan mode jual`() {
        // Invarian inti perbaikan ini: kombinasi (SELL, SCALPING_DESK) harus mustahil.
        for (isScalping in listOf(true, false)) {
            for (isHolding in listOf(true, false)) {
                val mode = resolveDetailTradeMode(isHolding)
                val panel = resolveDetailScalpPanel(isScalping, isHolding)
                val bertabrakan = mode == DetailTradeMode.SELL && panel == DetailScalpPanel.SCALPING_DESK
                assertEquals(
                    "kombinasi (scalping=$isScalping, hold=$isHolding) tidak boleh menampilkan meja entry di mode jual",
                    false,
                    bertabrakan
                )
            }
        }
    }
}
