package agu.analys.viewmodel

import agu.analys.engine.scalping.PaperExitStateMachine
import agu.analys.engine.scalping.ScalpExitMode
import agu.analys.engine.scalping.ScalpExitParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes registri runtime paper trade (P2).
 *
 * Yang dijaga di sini:
 * - key registri menormalkan simbol, tetapi simbol asli harus tetap bisa dipulihkan
 *   (dipakai untuk memantau harga posisi yang tidak sedang dibuka di layar);
 * - hitungan bar M1 hanya maju sekali per menit, sehingga evaluasi per-tick tidak
 *   mempercepat time-stop.
 */
class PaperAutoTradeRuntimeTest {

    @After
    fun tearDown() {
        PaperAutoTradeRuntime.clearAll()
    }

    private fun machine() = PaperExitStateMachine(
        entry = 100.0,
        stopLoss = 99.0,
        tp1 = 101.0,
        tp2 = 102.0,
        buyFeePct = 0.4044,
        sellFeePct = 0.4044,
        slippagePct = 0.08,
        params = ScalpExitParams(mode = ScalpExitMode.CLASSIC)
    )

    @Test
    fun `simbol asli tetap utuh walau key dinormalkan`() {
        PaperAutoTradeRuntime.put("tokocrypto", "BTC_IDR", machine())

        assertEquals(listOf("BTC_IDR"), PaperAutoTradeRuntime.activeSymbols("TOKOCRYPTO"))
        assertTrue(PaperAutoTradeRuntime.hasActivePosition("TOKOCRYPTO", "btc_idr"))
        assertNotNull(PaperAutoTradeRuntime.machineFor("TOKOCRYPTO", "BTC_IDR"))
        assertTrue(PaperAutoTradeRuntime.openedAt("TOKOCRYPTO", "BTC_IDR") > 0L)

        PaperAutoTradeRuntime.clear("TOKOCRYPTO", "BTC_IDR")
        assertTrue(PaperAutoTradeRuntime.activeSymbols("TOKOCRYPTO").isEmpty())
        assertNull(PaperAutoTradeRuntime.machineFor("TOKOCRYPTO", "BTC_IDR"))
    }

    @Test
    fun `posisi di bursa lain tidak ikut terbawa`() {
        PaperAutoTradeRuntime.put("TOKOCRYPTO", "BTC_IDR", machine())
        PaperAutoTradeRuntime.put("INDODAX", "ETH_IDR", machine())

        assertEquals(listOf("BTC_IDR"), PaperAutoTradeRuntime.activeSymbols("TOKOCRYPTO"))
        assertEquals(listOf("ETH_IDR"), PaperAutoTradeRuntime.activeSymbols("INDODAX"))
    }

    /**
     * Kepemilikan exit tunggal (P2): selama engine paper aktif, jalur auto-sell lama harus
     * mundur supaya tidak ada dua mesin yang menjual posisi simulasi yang sama.
     */
    @Test
    fun `engine paper memegang exit hanya saat auto-exit aktif dan posisinya ada`() {
        PaperAutoTradeRuntime.put("TOKOCRYPTO", "BTC_IDR", machine())

        assertTrue(
            "auto-exit aktif + posisi paper terbuka -> jalur lama mundur",
            PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "btc_idr", autoExitEnabled = true)
        )
        assertFalse(
            "auto-exit dimatikan -> kepemilikan kembali ke jalur lama, posisi tidak boleh menggantung",
            PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "btc_idr", autoExitEnabled = false)
        )
        assertFalse(
            "pair lain tanpa posisi paper tidak boleh ikut terkunci",
            PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "ETH_IDR", autoExitEnabled = true)
        )
        assertFalse(
            "bursa lain tidak ikut terkunci",
            PaperAutoTradeRuntime.ownsExit("INDODAX", "BTC_IDR", autoExitEnabled = true)
        )
    }

    @Test
    fun `kepemilikan exit dilepas setelah posisi paper ditutup`() {
        PaperAutoTradeRuntime.put("TOKOCRYPTO", "BTC_IDR", machine())
        assertTrue(PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "BTC_IDR", autoExitEnabled = true))

        PaperAutoTradeRuntime.clear("TOKOCRYPTO", "BTC_IDR")

        assertFalse(
            "setelah mesin dilepas, jalur auto-sell lama harus bisa jalan lagi",
            PaperAutoTradeRuntime.ownsExit("TOKOCRYPTO", "BTC_IDR", autoExitEnabled = true)
        )
    }

    @Test
    fun `hanya tick pertama di menit baru yang dihitung sebagai bar M1`() {
        val minuteStart = (System.currentTimeMillis() / 60_000L) * 60_000L
        PaperAutoTradeRuntime.put("TOKOCRYPTO", "BTC_IDR", machine())

        assertFalse(
            "tick di menit yang sama dengan saat posisi dibuka bukan bar baru",
            PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", minuteStart)
        )
        assertFalse(
            "tick 30 detik kemudian masih di menit yang sama",
            PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", minuteStart + 30_000L)
        )
        assertTrue(
            "menit berikutnya adalah bar M1 baru",
            PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", minuteStart + 60_000L)
        )
        assertFalse(
            "kembali ke menit yang sama tidak dihitung bar baru lagi",
            PaperAutoTradeRuntime.markBarBucket("TOKOCRYPTO", "BTC_IDR", minuteStart + 90_000L)
        )
    }
}
