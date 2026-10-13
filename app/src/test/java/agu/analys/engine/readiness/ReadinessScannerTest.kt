package agu.analys.engine.readiness

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji pemindai kesiapan.
 *
 * Yang dijaga di sini adalah **biaya pemindaian** dan **kejujuran hasil**: pemindai tidak boleh
 * membaca timeframe yang sama berkali-kali, dan tidak boleh menyimpulkan apa pun dari data yang
 * tidak ada.
 */
class ReadinessScannerTest {

    private fun series(count: Int, start: Double = 1_000.0, stepPct: Double = 0.05): List<CandleBar> {
        val out = ArrayList<CandleBar>(count)
        var price = start
        for (i in 0 until count) {
            val open = price
            val close = open * (1.0 + stepPct / 100.0)
            out += CandleBar(
                timestamp = i.toLong() * 60_000L,
                open = open,
                high = maxOf(open, close) * 1.005,
                low = minOf(open, close) * 0.995,
                close = close,
                volume = 1_000.0
            )
            price = close
        }
        return out
    }

    @Test
    fun `memindai semua horizon untuk setiap simbol`() {
        val source = CandleSource { _, _ -> series(90) }
        val board = ReadinessScanner.scan(
            symbols = listOf("BTC_USDT", "ETH_USDT"),
            source = source,
            fees = TradingFeeConfig.tokocryptoUsdt()
        )

        assertEquals(2 * TradeHorizon.entries.size, board.rows.size)
        assertEquals(TradeHorizon.entries.size, board.forSymbol("BTC_USDT").size)
        assertEquals(TradeHorizon.entries.size, board.forSymbol("ETH_USDT").size)
        assertFalse(board.isEmpty)
    }

    @Test
    fun `membaca setiap timeframe sekali per simbol`() {
        val calls = mutableListOf<Pair<String, Timeframe>>()
        val source = CandleSource { symbol, tf ->
            calls += symbol to tf
            series(90)
        }

        ReadinessScanner.scan(
            symbols = listOf("BTC_USDT", "ETH_USDT"),
            source = source,
            fees = TradingFeeConfig.tokocryptoUsdt()
        )

        // 3 horizon memakai gabungan {M1, M15, H1, H4, D1} = 5 timeframe unik.
        // 2 simbol × 5 = 10 pembacaan. Tanpa dedup akan menjadi 3 horizon × 3 tf × 2 = 18.
        assertEquals(10, calls.size)
        assertEquals(calls.size, calls.toSet().size)
    }

    @Test
    fun `sumber kosong menghasilkan NO_DATA bukan kesimpulan palsu`() {
        val source = CandleSource { _, _ -> null }
        val board = ReadinessScanner.scan(
            symbols = listOf("BTC_USDT"),
            source = source,
            fees = TradingFeeConfig.tokocryptoUsdt()
        )

        assertEquals(TradeHorizon.entries.size, board.rows.size)
        assertTrue(board.rows.all { it.state == ReadinessState.NO_DATA })
        assertTrue(board.rows.all { it.plan == null })
        assertTrue(board.readyOf(TradeHorizon.SCALP).isEmpty())
    }

    @Test
    fun `simbol kosong dilewati`() {
        val source = CandleSource { _, _ -> series(90) }
        val board = ReadinessScanner.scan(
            symbols = listOf("BTC_USDT", "   ", ""),
            source = source,
            fees = TradingFeeConfig.tokocryptoUsdt()
        )
        assertEquals(TradeHorizon.entries.size, board.rows.size)
    }

    @Test
    fun `batas baris dihormati`() {
        val source = CandleSource { _, _ -> series(90) }
        val board = ReadinessScanner.scan(
            symbols = List(50) { "COIN${it}_USDT" },
            source = source,
            fees = TradingFeeConfig.tokocryptoUsdt(),
            maxRows = 20
        )
        assertEquals(20, board.rows.size)
    }

    @Test
    fun `harga live dipakai bila tersedia`() {
        val source = CandleSource { _, _ -> series(90, start = 1_000.0) }
        val board = ReadinessScanner.scan(
            symbols = listOf("BTC_USDT"),
            source = source,
            fees = TradingFeeConfig.tokocryptoUsdt(),
            priceOf = { 2_500.0 }
        )
        assertTrue(board.rows.all { it.price == 2_500.0 })
    }

    @Test
    fun `horizon dapat dibatasi`() {
        val source = CandleSource { _, _ -> series(90) }
        val board = ReadinessScanner.scan(
            symbols = listOf("BTC_USDT"),
            source = source,
            fees = TradingFeeConfig.tokocryptoUsdt(),
            horizons = listOf(TradeHorizon.SWING)
        )
        assertEquals(1, board.rows.size)
        assertEquals(TradeHorizon.SWING, board.rows.first().horizon)
    }

    @Test
    fun `urutan horizon pada forSymbol mengikuti definisi`() {
        val source = CandleSource { _, _ -> series(90) }
        val board = ReadinessScanner.scan(
            symbols = listOf("BTC_USDT"),
            source = source,
            fees = TradingFeeConfig.tokocryptoUsdt()
        )
        assertEquals(
            TradeHorizon.entries.toList(),
            board.forSymbol("BTC_USDT").map { it.horizon }
        )
    }

    @Test
    fun `daftar kosong menghasilkan papan kosong`() {
        val board = ReadinessScanner.scan(
            symbols = emptyList(),
            source = CandleSource { _, _ -> series(90) },
            fees = TradingFeeConfig.tokocryptoUsdt()
        )
        assertTrue(board.isEmpty)
    }

    @Test
    fun `countOf dan readyOf konsisten dengan baris`() {
        val source = CandleSource { _, _ -> series(90) }
        val board = ReadinessScanner.scan(
            symbols = listOf("BTC_USDT"),
            source = source,
            fees = TradingFeeConfig.tokocryptoUsdt()
        )
        val noData = board.countOf(TradeHorizon.SCALP, ReadinessState.NO_DATA)
        assertEquals(board.forHorizon(TradeHorizon.SCALP).count { it.state == ReadinessState.NO_DATA }, noData)
        assertEquals(
            board.forHorizon(TradeHorizon.SCALP).count { it.state == ReadinessState.READY },
            board.readyOf(TradeHorizon.SCALP).size
        )
    }
}
