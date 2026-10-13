package agu.analys.engine.readiness

/**
 * Status kesiapan satu (koin × horizon).
 *
 * [rank] dipakai UI untuk pengurutan; jangan mengandalkan urutan `ordinal`.
 */
enum class ReadinessState(val label: String, val rank: Int) {
    /** Semua syarat lolos — rencana trade tersedia dan ekonomis. */
    READY("SIAP", 4),

    /** Tinggal satu syarat, biasanya gate ekonomi/friksi. */
    WARMING("MEMANAS", 3),

    /** Ada sebagian dasar, belum cukup untuk masuk. */
    WATCH("PANTAU", 2),

    /** Ada penghalang keras (volatilitas ekstrem / trauma dump / harga extended). */
    BLOCKED("TERTAHAN", 1),

    /** Candle belum cukup — tidak ada kesimpulan yang boleh diambil. */
    NO_DATA("DATA KURANG", 0)
}

/**
 * Satu baris syarat kesiapan.
 *
 * @param label judul singkat syarat
 * @param ok lolos?
 * @param detail angka/alasan dari engine — UI **tidak** menghitung ulang
 */
data class ReadinessCheck(
    val label: String,
    val ok: Boolean,
    val detail: String
)

/**
 * Rencana trade long: beli → tahan → keluar.
 *
 * Semua angka sudah memperhitungkan fee & slippage lewat `FeeCalculator`, memakai tarif bursa
 * yang sama dengan engine scalping.
 *
 * @param exitRule aturan keluar dalam bahasa manusia (satu-satunya sumber teks exit untuk UI)
 */
data class TradePlan(
    val entry: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    /** Jarak SL dari entry, persen harga. */
    val riskPct: Double,
    /** Jarak TP2 dari entry, persen harga. */
    val rewardPct: Double,
    /** Net R:R di TP2 setelah fee + slippage. */
    val netRr: Double,
    /** Lantai risiko minimum akibat friksi round-trip, persen harga. */
    val frictionFloorPct: Double,
    /** Jarak TP2 diukur dalam satuan R. */
    val tp2R: Double,
    /** Aturan exit ringkas untuk horizon ini. */
    val exitRule: String
)

/**
 * Potret kesiapan satu koin pada satu horizon.
 *
 * @param symbol simbol apa adanya dari daftar pantau
 * @param horizon horizon yang dinilai
 * @param state status terpadu
 * @param score 0..100 — bobot gate ekonomi lebih besar
 * @param headline satu kalimat kesimpulan
 * @param blocker penghalang utama bila ada
 * @param checks daftar syarat berurutan
 * @param plan rencana trade; `null` bila belum ada kesimpulan yang layak ditampilkan
 * @param price harga acuan saat penilaian
 */
data class HorizonReadiness(
    val symbol: String,
    val horizon: TradeHorizon,
    val state: ReadinessState,
    val score: Int,
    val headline: String,
    val blocker: String?,
    val checks: List<ReadinessCheck>,
    val plan: TradePlan?,
    val price: Double
) {
    val passedChecks: Int get() = checks.count { it.ok }
    val totalChecks: Int get() = checks.size

    /** Boleh ditawarkan sebagai kandidat eksekusi? Hanya READY yang boleh. */
    val actionable: Boolean get() = state == ReadinessState.READY && plan != null
}

/**
 * Hasil pemindaian seluruh daftar pantau pada satu waktu.
 *
 * Urutan [rows] sengaja **tidak** dijamin — selalu pakai [forHorizon] / [ranked] supaya UI
 * tidak bergantung pada urutan masukan.
 */
data class ReadinessBoard(
    val generatedAt: Long,
    val rows: List<HorizonReadiness>
) {
    /** Baris satu horizon, terbaik lebih dulu. */
    fun forHorizon(horizon: TradeHorizon): List<HorizonReadiness> =
        rows.filter { it.horizon == horizon }
            .sortedWith(compareByDescending<HorizonReadiness> { it.state.rank }.thenByDescending { it.score })

    /** Baris satu koin (semua horizon), urut sesuai [TradeHorizon.entries]. */
    fun forSymbol(symbol: String): List<HorizonReadiness> =
        TradeHorizon.entries.mapNotNull { h ->
            rows.firstOrNull { it.horizon == h && it.symbol.equals(symbol, ignoreCase = true) }
        }

    fun countOf(horizon: TradeHorizon, state: ReadinessState): Int =
        rows.count { it.horizon == horizon && it.state == state }

    fun readyOf(horizon: TradeHorizon): List<HorizonReadiness> =
        forHorizon(horizon).filter { it.state == ReadinessState.READY }

    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        val EMPTY = ReadinessBoard(0L, emptyList())
    }
}
