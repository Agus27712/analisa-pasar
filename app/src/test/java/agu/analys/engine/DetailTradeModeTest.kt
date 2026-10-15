package agu.analys.engine

import agu.analys.model.DetailTradeMode
import agu.analys.model.PositionContext
import agu.analys.model.TradingWorkflow
import agu.analys.model.resolveDetailTradeMode
import agu.analys.model.resolveWorkflow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uji **mode halaman detail koin** yang otoritatif.
 *
 * Sebelumnya halaman detail punya tab Beli/Jual manual, sehingga kartu beli dan jual bisa
 * tampil bersamaan dan pengguna tidak bisa membedakan sedang membeli atau menjual. Sekarang
 * mode ditentukan sepenuhnya oleh status kepemilikan aset:
 * **hold → SELL**, **tidak hold → BUY**.
 */
class DetailTradeModeTest {

    @Test
    fun `tidak hold menghasilkan mode beli`() {
        assertEquals(DetailTradeMode.BUY, resolveDetailTradeMode(isHolding = false))
    }

    @Test
    fun `sedang hold menghasilkan mode jual`() {
        assertEquals(DetailTradeMode.SELL, resolveDetailTradeMode(isHolding = true))
    }

    @Test
    fun `mode selaras dengan workflow`() {
        val adaPosisi = PositionContext(hasPosition = true)
        val tanpaPosisi = PositionContext(hasPosition = false)

        assertEquals(TradingWorkflow.HOLD_SELL, resolveWorkflow(adaPosisi))
        assertEquals(DetailTradeMode.SELL, resolveDetailTradeMode(adaPosisi.hasPosition))

        assertEquals(TradingWorkflow.BUY, resolveWorkflow(tanpaPosisi))
        assertEquals(DetailTradeMode.BUY, resolveDetailTradeMode(tanpaPosisi.hasPosition))
    }
}
