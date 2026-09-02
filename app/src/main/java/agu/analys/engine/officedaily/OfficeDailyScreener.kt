package agu.analys.engine.officedaily

import agu.analys.model.MarketTick
import java.util.Calendar
import java.util.TimeZone

data class FastOfficeDailyScore(
    val score: Int,
    val isQualified: Boolean,
    val summary: String,
    /** true jika sekarang di jendela screening pagi (~daily close) */
    val inScreeningWindow: Boolean = false
)

/**
 * Fast screen Office Daily — diselaraskan strategi "kantoran":
 * - Screening ideal ~jam 7 pagi WIB (setelah daily close)
 * - Cari koin yang SUDAH dalam tren naik / pullback sehat (bukan recovery dump)
 * - Trigger mindset: lanjut tren + candle bullish (detail di evaluator)
 * - Setup tidak harus ada setiap hari → filter ketat
 */
object OfficeDailyScreener {

    /** Jendela screening pagi WIB (setelah daily close crypto ~07:00). */
    fun isMorningScreeningWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        cal.timeInMillis = nowMs
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        // 06:00–09:30 WIB = zona screening utama
        return hour in 6..9
    }

    fun evaluateFast(tick: MarketTick?): FastOfficeDailyScore {
        if (tick == null || tick.price <= 0.0) {
            return FastOfficeDailyScore(0, false, "Data tidak valid")
        }

        val change = if (tick.change24h.isFinite()) tick.change24h else 0.0
        val volume = tick.volume24h
        val price = tick.price
        val high = tick.high24h
        val low = tick.low24h
        val rangePct = if (low > 0.0) ((high - low) / low) * 100.0 else 0.0
        val posInRange = if (high > low) ((price - low) / (high - low)).coerceIn(0.0, 1.0) else 0.5
        val inWindow = isMorningScreeningWindow()

        // ── Hard reject (bukan gaya Office Daily video) ─────────────────────
        // Dump tajam / recovery dari panic sell → bukan "yang sudah naik lanjut naik"
        if (change < -4.0) {
            return FastOfficeDailyScore(
                score = 0,
                isQualified = false,
                summary = "❌ Bukan Office Daily (tekanan 24h ${fmt(change)}%)",
                inScreeningWindow = inWindow
            )
        }
        // Volume terlalu kering → slippage / sulit dieksekusi santai
        if (volume < 300_000_000.0) {
            return FastOfficeDailyScore(
                score = 0,
                isQualified = false,
                summary = "❌ Likuiditas rendah untuk Office Daily",
                inScreeningWindow = inWindow
            )
        }
        // Range liar (noise ekstrem) → bukan setup santai
        if (rangePct > 28.0) {
            return FastOfficeDailyScore(
                score = 0,
                isQualified = false,
                summary = "❌ Range 24h terlalu liar (${fmt(rangePct)}%)",
                inScreeningWindow = inWindow
            )
        }

        var score = 0

        // 1. Likuiditas (IDR volume)
        when {
            volume >= 15_000_000_000.0 -> score += 4
            volume >= 5_000_000_000.0 -> score += 3
            volume >= 1_500_000_000.0 -> score += 2
            volume >= 300_000_000.0 -> score += 1
        }

        // 2. Karakter pergerakan 24h — prefer tren naik / pullback ringan
        // Video: "yang sudah naik, akan terus naik" + bukan dump recovery
        when {
            change in 2.0..12.0 -> score += 5          // tren naik sehat
            change in 0.0..2.0 -> score += 4           // konsolidasi di atas
            change in -2.0..0.0 -> score += 2          // pullback ringan (masih oke)
            change in 12.0..20.0 -> score += 2         // sudah extended, hati-hati
            change in -4.0..-2.0 -> score += 0         // lemah, hampir ditolak hard gate
            else -> score += 0
        }

        // 3. Posisi di range 24h
        // Dump recovery = harga nempel low → tolak
        // Ideal Office Daily: mid–upper setelah strength, atau pullback terkontrol
        when {
            posInRange >= 0.35 && posInRange <= 0.85 && change >= 0.0 -> score += 3
            posInRange >= 0.25 && posInRange <= 0.70 && change in -2.0..2.0 -> score += 2 // pullback sehat
            posInRange < 0.20 && change < 0.0 -> score -= 3 // nempel floor setelah turun
            posInRange > 0.92 && change >= 10.0 -> score -= 1 // kejepit di top parabolic
            else -> score += 1
        }

        // 4. Volatilitas terukur (bukan dead, bukan crazy)
        when {
            rangePct in 3.0..14.0 -> score += 2
            rangePct in 2.0..20.0 -> score += 1
            else -> score += 0
        }

        // 5. Soft boost jendela screening pagi (7 WIB / daily close)
        if (inWindow) score += 1

        // Threshold ketat: setup tidak setiap hari
        val isCandidate = score >= 9

        val summary = when {
            isCandidate && score >= 12 && inWindow ->
                "🏢 Office Daily READY (pagi · skor $score)"
            isCandidate && score >= 12 ->
                "🏢 High Conviction Office Daily (skor $score)"
            isCandidate && inWindow ->
                "🏢 Potensi Office Daily · screening pagi (skor $score)"
            isCandidate ->
                "🏢 Potensi Office Daily (tren sehat · skor $score)"
            score >= 7 ->
                "👀 Pantau Office Daily (belum qualify · skor $score)"
            else ->
                "— Belum cocok Office Daily"
        }

        return FastOfficeDailyScore(
            score = score,
            isQualified = isCandidate,
            summary = summary,
            inScreeningWindow = inWindow
        )
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
