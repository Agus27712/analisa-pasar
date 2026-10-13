package agu.analys.engine.readiness

/**
 * Status kesiapan satu (koin × horizon).
 *
 * [rank] dipakai UI untuk pengurutan; jangan mengandalkan urutan `ordinal`.
 */
enum class ReadinessState(val label: String, val rank: Int) {
    /**
     * Posisi simulasi sedang terbuka untuk pair ini.
     *
     * Hanya bisa muncul pada baris yang berasal dari engine ([ReadinessSource.ENGINE]): engine
     * paper memang memegang exit-nya. Pra-saring tidak pernah tahu soal posisi.
     */
    HOLDING("POSISI AKTIF", 5),

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
 * Dari mana kesimpulan sebuah baris berasal.
 *
 * ## Kenapa pemisahan ini wajib
 *
 * Sebelum ini papan kesiapan menilai sendiri horizon yang **sama** dengan engine yang sedang
 * aktif, dengan daftar syarat yang berbeda. Akibatnya pengguna melihat dua angka untuk satu
 * pertanyaan yang sama — misalnya halaman detail koin menampilkan "3/5 syarat lolos" pada kartu
 * kesiapan sementara kartu SCALP DESK di bawahnya menampilkan "1/5 lolos". Keduanya "benar"
 * menurut daftar syaratnya masing-masing, dan justru itu masalahnya: pembaca tidak punya cara
 * untuk tahu mana yang harus dipercaya.
 *
 * Aturannya sekarang satu arah:
 *
 * > **Horizon yang engine-nya aktif selalu dinilai engine itu sendiri** ([ENGINE]); horizon lain
 * > hanya boleh tampil sebagai [PRESCREEN] dan wajib menyebut dirinya begitu.
 */
enum class ReadinessSource(val label: String) {
    /** Dinilai engine yang sedang aktif. Angka & syaratnya identik dengan kartu engine. */
    ENGINE("ENGINE AKTIF"),

    /** Pra-saring biaya & tren. Berguna untuk menyaring daftar, **bukan** keputusan engine. */
    PRESCREEN("PRA-SARING")
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
 * @param source dari mana baris ini berasal — lihat [ReadinessSource]
 * @param sourceNote penjelasan tambahan, mis. penunjuk ke kartu yang memuat rencana trade
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
    val price: Double,
    val source: ReadinessSource = ReadinessSource.PRESCREEN,
    val sourceNote: String? = null
) {
    val passedChecks: Int get() = checks.count { it.ok }
    val totalChecks: Int get() = checks.size

    /** Apakah baris ini keputusan engine yang sedang aktif (bukan pra-saring)? */
    val fromEngine: Boolean get() = source == ReadinessSource.ENGINE

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

    /**
     * Baris yang benar-benar keputusan engine (bukan pra-saring).
     *
     * Dipakai UI untuk memisahkan dua kelompok pada horizon yang sama, sehingga angka
     * "pra-saring" tidak pernah terbaca sebagai penilaian engine.
     */
    fun engineRows(horizon: TradeHorizon): List<HorizonReadiness> =
        forHorizon(horizon).filter { it.fromEngine }

    /** Baris pra-saring saja — pasangan dari [engineRows]. */
    fun prescreenRows(horizon: TradeHorizon): List<HorizonReadiness> =
        forHorizon(horizon).filter { !it.fromEngine }

    /**
     * Ganti baris hasil pemindaian dengan keputusan engine untuk (simbol × horizon) yang sama.
     *
     * Sengaja **mengganti**, bukan menambah: menambahkan akan membuat satu koin muncul dua kali
     * dengan dua kesimpulan berbeda — persis kontradiksi yang sedang diperbaiki. Baris engine
     * yang tidak punya pasangan di papan tetap dimasukkan, supaya pair yang sedang dibuka selalu
     * terwakili walau di luar daftar pantau.
     */
    fun withEngineRows(overrides: List<HorizonReadiness>): ReadinessBoard {
        if (overrides.isEmpty()) return this
        val replaced = rows.filterNot { existing ->
            overrides.any { o ->
                o.horizon == existing.horizon && o.symbol.equals(existing.symbol, ignoreCase = true)
            }
        }
        return copy(rows = replaced + overrides)
    }

    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        val EMPTY = ReadinessBoard(0L, emptyList())
    }
}
