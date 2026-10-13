package agu.analys.engine.readiness

import agu.analys.config.StrategyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Uji penggantian baris pra-saring dengan keputusan engine.
 *
 * ## Kenapa ini harus "mengganti", bukan "menambah"
 *
 * Papan kesiapan memindai seluruh daftar pantau dan menghasilkan baris pra-saring untuk **semua**
 * horizon — termasuk horizon yang engine-nya sedang aktif. Kalau keputusan engine ditambahkan
 * tanpa menghapus baris itu, satu koin muncul **dua kali** di horizon yang sama dengan dua
 * kesimpulan berbeda. Itu persis kontradiksi "3/5 vs 1/5" yang dilaporkan pengguna, hanya
 * dipindahkan ke bentuk daftar.
 */
class ReadinessBoardOverlayTest {

    private fun row(
        symbol: String,
        horizon: TradeHorizon,
        state: ReadinessState = ReadinessState.WATCH,
        score: Int = 40,
        source: ReadinessSource = ReadinessSource.PRESCREEN
    ) = HorizonReadiness(
        symbol = symbol,
        horizon = horizon,
        state = state,
        score = score,
        headline = "headline $symbol ${horizon.name}",
        blocker = null,
        checks = emptyList(),
        plan = null,
        price = 1.0,
        source = source
    )

    private fun board(vararg rows: HorizonReadiness) = ReadinessBoard(generatedAt = 1L, rows = rows.toList())

    @Test
    fun engineRowReplacesPrescreenRowForSameSymbolAndHorizon() {
        val scan = board(
            row("BTC/IDR", TradeHorizon.SCALP, score = 60),
            row("ETH/IDR", TradeHorizon.SCALP, score = 55),
            row("BTC/IDR", TradeHorizon.SWING, score = 30)
        )
        val engine = row("BTC/IDR", TradeHorizon.SCALP, state = ReadinessState.READY, score = 100, source = ReadinessSource.ENGINE)

        val merged = scan.withEngineRows(listOf(engine))

        val scalpBtc = merged.rows.filter { it.symbol == "BTC/IDR" && it.horizon == TradeHorizon.SCALP }
        assertEquals("BTC SCALP tidak boleh muncul dua kali", 1, scalpBtc.size)
        assertEquals(ReadinessSource.ENGINE, scalpBtc.single().source)
        assertEquals(100, scalpBtc.single().score)
    }

    @Test
    fun otherSymbolsAndHorizonsAreUntouched() {
        val scan = board(
            row("BTC/IDR", TradeHorizon.SCALP, score = 60),
            row("ETH/IDR", TradeHorizon.SCALP, score = 55),
            row("BTC/IDR", TradeHorizon.SWING, score = 30)
        )
        val merged = scan.withEngineRows(
            listOf(row("BTC/IDR", TradeHorizon.SCALP, score = 100, source = ReadinessSource.ENGINE))
        )

        assertEquals(3, merged.rows.size)
        assertEquals(55, merged.forHorizon(TradeHorizon.SCALP).first { it.symbol == "ETH/IDR" }.score)
        assertFalse(merged.forHorizon(TradeHorizon.SWING).single().fromEngine)
    }

    @Test
    fun engineRowIsAddedWhenScanHasNoSuchSymbol() {
        // Pair yang sedang dibuka bisa belum masuk daftar pantau; keputusan engine tetap harus
        // terlihat, bukan hilang karena kebetulan tidak dipindai.
        val scan = board(row("ETH/IDR", TradeHorizon.SCALP))
        val merged = scan.withEngineRows(
            listOf(row("SOL/IDR", TradeHorizon.SCALP, state = ReadinessState.HOLDING, source = ReadinessSource.ENGINE))
        )

        assertEquals(2, merged.rows.size)
        assertEquals(1, merged.engineRows(TradeHorizon.SCALP).size)
        assertEquals(ReadinessState.HOLDING, merged.engineRows(TradeHorizon.SCALP).single().state)
    }

    @Test
    fun symbolMatchIsCaseInsensitive() {
        val scan = board(row("btc/idr", TradeHorizon.SCALP, score = 10))
        val merged = scan.withEngineRows(
            listOf(row("BTC/IDR", TradeHorizon.SCALP, score = 90, source = ReadinessSource.ENGINE))
        )
        assertEquals(1, merged.rows.size)
        assertEquals(90, merged.rows.single().score)
    }

    @Test
    fun emptyOverridesReturnTheSameBoard() {
        val scan = board(row("BTC/IDR", TradeHorizon.SCALP))
        val merged = scan.withEngineRows(emptyList())
        assertEquals(scan.rows, merged.rows)
    }

    @Test
    fun engineAndPrescreenRowsCanBeSeparated() {
        val scan = board(
            row("BTC/IDR", TradeHorizon.SCALP, source = ReadinessSource.ENGINE),
            row("ETH/IDR", TradeHorizon.SCALP)
        )
        assertEquals(listOf("BTC/IDR"), scan.engineRows(TradeHorizon.SCALP).map { it.symbol })
        assertEquals(listOf("ETH/IDR"), scan.prescreenRows(TradeHorizon.SCALP).map { it.symbol })
    }

    // ── Pemetaan horizon ↔ mode engine ──────────────────────────────────────

    @Test
    fun everyStrategyModeMapsToItsOwnHorizon() {
        StrategyMode.entries.forEach { mode ->
            val horizon = TradeHorizon.of(mode)
            assertEquals(
                "TradeHorizon.of($mode) harus mengembalikan horizon milik mode itu",
                mode,
                horizon.strategyMode
            )
        }
    }

    @Test
    fun horizonMappingIsOneToOne() {
        val horizons = StrategyMode.entries.map { TradeHorizon.of(it) }
        assertEquals("tidak boleh ada dua mode memakai horizon yang sama", horizons.size, horizons.distinct().size)
    }
}
