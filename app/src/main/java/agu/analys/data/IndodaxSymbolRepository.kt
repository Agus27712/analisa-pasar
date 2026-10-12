package agu.analys.data

import agu.analys.model.PairPrecision
import agu.analys.model.TradingPair
import agu.analys.service.IndodaxMarketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Penemuan simbol **dinamis** untuk Indodax.
 *
 * Sebelum ini, pemilihan & penambahan pair Indodax hanya memakai daftar tetap 16 pair
 * (`TradingPair.POPULAR_INDODAX_PAIRS`), sehingga klaim "semua pair Indodax bisa dipakai"
 * tidak benar untuk pencarian maupun penambahan aset.
 *
 * Sumbernya endpoint resmi `https://indodax.com/api/pairs` (sudah dipakai
 * [IndodaxMarketService.fetchPairsMetadata] untuk presisi harga).
 *
 * **Batasan pasar:** Indodax hanya pasangan **IDR** (lihat `AGENTS.md`). Pair ber-kuotasi
 * selain IDR difilter keluar.
 */
object IndodaxSymbolRepository {

    private val pairsBySymbol = java.util.concurrent.ConcurrentHashMap<String, PairPrecision>()

    @Volatile
    private var lastAttemptMs: Long = 0L

    /** Jeda minimum antar percobaan fetch (ms) agar tidak membanjiri API. */
    private const val MIN_RETRY_INTERVAL_MS = 60_000L

    fun isReady(): Boolean = pairsBySymbol.isNotEmpty()

    /** Jumlah pair IDR yang sudah ditemukan. */
    fun size(): Int = pairsBySymbol.size

    /**
     * Pastikan daftar pair sudah termuat.
     *
     * @param force paksa ambil ulang walau sudah ada data.
     * @return true bila daftar tersedia (baru atau dari cache).
     */
    suspend fun ensurePairsLoaded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && pairsBySymbol.isNotEmpty()) return@withContext true
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptMs < MIN_RETRY_INTERVAL_MS) {
            return@withContext pairsBySymbol.isNotEmpty()
        }
        lastAttemptMs = now
        return@withContext try {
            val meta = IndodaxMarketService.fetchPairsMetadata()
            // Hanya kuotasi IDR yang sah di Indodax.
            val idrOnly = meta.filter { it.tradedCurrency.equals("idr", ignoreCase = true) }
            if (idrOnly.isNotEmpty()) {
                pairsBySymbol.clear()
                idrOnly.forEach { pairsBySymbol[it.symbol.uppercase()] = it }
                Timber.i("Indodax symbol discovery: ${pairsBySymbol.size} pair IDR termuat")
                true
            } else {
                Timber.w("Indodax symbol discovery: tidak ada pair IDR yang diterima")
                pairsBySymbol.isNotEmpty()
            }
        } catch (e: Exception) {
            Timber.e(e, "Indodax symbol discovery gagal")
            pairsBySymbol.isNotEmpty()
        }
    }

    /** Isi cache secara langsung (dipakai pengujian). */
    internal fun setPairsForTest(list: List<PairPrecision>) {
        pairsBySymbol.clear()
        list.filter { it.tradedCurrency.equals("idr", ignoreCase = true) }
            .forEach { pairsBySymbol[it.symbol.uppercase()] = it }
    }

    /** Bersihkan cache (dipakai saat berganti bursa / pengujian). */
    fun clear() {
        pairsBySymbol.clear()
        lastAttemptMs = 0L
    }

    private fun PairPrecision.toTradingPair(): TradingPair {
        val base = baseCurrency.ifBlank { symbol.removeSuffix("IDR").removeSuffix("idr") }
        return TradingPair(
            symbol = symbol.uppercase(),
            baseAsset = base.uppercase(),
            quoteAsset = "IDR",
            displayName = "${base.uppercase()} / IDR",
            indodaxPair = id.ifBlank { "${base.lowercase()}_idr" }
        )
    }

    /**
     * Seluruh pair Indodax yang aktif.
     *
     * Bila discovery belum pernah berhasil (mis. offline), kembalikan daftar bawaan
     * `POPULAR_INDODAX_PAIRS` supaya aplikasi tetap bisa dipakai.
     */
    fun getAllTradingPairs(quoteFilter: String? = null): List<TradingPair> {
        if (pairsBySymbol.isEmpty()) return TradingPair.POPULAR_INDODAX_PAIRS
        // Indodax hanya IDR; filter kuotasi selain IDR menghasilkan daftar kosong.
        if (!quoteFilter.isNullOrBlank() &&
            !quoteFilter.equals("ALL", true) &&
            !quoteFilter.equals("IDR", true)
        ) {
            return emptyList()
        }
        return pairsBySymbol.values
            .distinctBy { it.symbol.uppercase() }
            .sortedBy { it.symbol.uppercase() }
            .map { it.toTradingPair() }
    }

    /** Cari pair Indodax menurut kode dasar atau simbol. */
    fun searchPairs(query: String, quoteFilter: String? = null): List<TradingPair> {
        val q = query.trim()
        if (q.isBlank()) return getAllTradingPairs(quoteFilter)
        return getAllTradingPairs(quoteFilter).filter {
            it.baseAsset.contains(q, ignoreCase = true) ||
                it.symbol.contains(q, ignoreCase = true) ||
                it.displayName.contains(q, ignoreCase = true)
        }
    }

    /** Metadata presisi untuk satu simbol Indodax, bila diketahui. */
    fun getPairPrecision(rawSymbol: String): PairPrecision? =
        pairsBySymbol[rawSymbol.trim().uppercase().replace("_", "")]
}
