package agu.analys.service

import java.util.concurrent.atomic.AtomicLong

/**
 * Gerbang jeda global untuk host data Tokocrypto pihak ketiga (`tokocrypto.site`,
 * `cloudme-toko.2meta.app`).
 *
 * ## Kenapa perlu
 *
 * Seluruh jalur baca pasar (ticker, kline, depth, trades, executionRules) memakai host
 * gateway yang sama. Sebelum gerbang ini, satu balasan `429` justru **memperbanyak**
 * permintaan:
 *
 * - `get()` mencoba ulang 2 kali dengan jeda 200–400 ms;
 * - `getWithFallback()` lalu menembak 2–3 URL cadangan;
 * - `MtfCacheManager.safeFetch()` mengulang sekali lagi setelah 200 ms;
 * - pemanggil level atas (`AgentMarketScanProvider`, `MarketViewModel`) mengulang siklus
 *   berikutnya beberapa detik kemudian.
 *
 * Hasilnya satu kuota habis berubah menjadi puluhan permintaan per detik ke host yang
 * sudah menolak — 429 bertahan jauh lebih lama daripada perlu.
 *
 * Gerbang ini membalik perilakunya: `429` **tidak** diulang, dan seluruh host ditutup
 * selama jendela jeda. Pemanggil yang lewat saat jeda langsung menerima `null` tanpa
 * satu pun koneksi keluar.
 *
 * Host resmi `www.tokocrypto.com` (signed trading API, daftar simbol) **tidak** terkena
 * gerbang ini — lihat [appliesTo].
 */
object TokocryptoRateGate {

    /** Jeda bawaan bila respons tidak menyertakan `Retry-After`. */
    const val DEFAULT_COOLDOWN_MS = 60_000L

    /** Plafon jeda: `Retry-After` yang tidak wajar tidak boleh mematikan pasar berjam-jam. */
    const val MAX_COOLDOWN_MS = 5 * 60_000L

    /** Host gateway yang dijaga. Host resmi `tokocrypto.com` sengaja tidak termasuk. */
    private val GATED_HOST_MARKERS = listOf("tokocrypto.site", "cloudme-toko.2meta.app")

    private val blockedUntil = AtomicLong(0L)

    /** Apakah [url] dilayani host yang dijaga gerbang ini. */
    fun appliesTo(url: String): Boolean {
        val lower = url.lowercase()
        return GATED_HOST_MARKERS.any { lower.contains(it) }
    }

    /** Apakah host sedang dalam jeda pada waktu [nowMs]. */
    fun shouldBlock(nowMs: Long): Boolean = nowMs < blockedUntil.get()

    /** Waktu (epoch ms) sampai kapan host ditutup; `0` berarti terbuka. */
    fun blockedUntilMs(): Long = blockedUntil.get()

    /**
     * Buka jeda karena `429`.
     *
     * @param retryAfterHeader nilai header `Retry-After` apa adanya (boleh `null`)
     * @return waktu berakhir jeda (epoch ms)
     */
    fun trip(retryAfterHeader: String?, nowMs: Long): Long {
        val cooldown = parseRetryAfterMs(retryAfterHeader, nowMs)
        val until = nowMs + cooldown
        // Jangan pernah memperpendek jeda yang sedang berjalan.
        var previous = blockedUntil.get()
        while (until > previous && !blockedUntil.compareAndSet(previous, until)) {
            previous = blockedUntil.get()
        }
        return maxOf(until, blockedUntil.get())
    }

    /** Lepas jeda segera. Dipakai uji; produksi membiarkan jeda kedaluwarsa sendiri. */
    fun reset() {
        blockedUntil.set(0L)
    }

    /**
     * Ubah `Retry-After` menjadi durasi jeda.
     *
     * Bentuk yang diterima: jumlah detik (`"120"`) atau tanggal HTTP
     * (`"Wed, 21 Oct 2026 07:28:00 GMT"`). Kosong/tidak terbaca → [DEFAULT_COOLDOWN_MS].
     * Hasil selalu di dalam `0..`[MAX_COOLDOWN_MS].
     */
    fun parseRetryAfterMs(
        header: String?,
        nowMs: Long,
        defaultCooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): Long {
        val raw = header?.trim().orEmpty()
        val parsed = when {
            raw.isEmpty() -> defaultCooldownMs
            raw.all { it.isDigit() } -> raw.toLongOrNull()?.let { it * 1000L } ?: defaultCooldownMs
            else -> httpDateMs(raw)?.let { it - nowMs } ?: defaultCooldownMs
        }
        return parsed.coerceIn(0L, MAX_COOLDOWN_MS)
    }

    private fun httpDateMs(value: String): Long? = runCatching {
        // `java.text` dipakai, bukan `java.time`: minSdk 24 dan modul ini tidak memakai
        // desugaring, sedangkan `java.time` baru ada di API 26.
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .parse(value)
            ?.time
    }.getOrNull()
}
