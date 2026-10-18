package agu.analys.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * Satu pembaruan ringkasan 24 jam dari kanal `market:summary-24h` Indodax.
 *
 * Bentuk baris yang dikirim server (terverifikasi 2026-10-17):
 * `[pair, epochDetik, last, low, high, open, vol_idr, vol_koin]`
 */
data class IndodaxSummaryTick(
    val pairId: String,
    val timestampMs: Long,
    val price: Double,
    val low24h: Double,
    val high24h: Double,
    val open24h: Double,
    val volumeIdr24h: Double,
    val volumeCoin24h: Double,
    val volumeCoinDelta: Double
) {
    val change24h: Double
        get() = if (open24h > 0.0) ((price / open24h) - 1.0) * 100.0 else Double.NaN
}

/**
 * Parser murni untuk feed ringkasan Indodax. Dipisah dari WebSocket supaya bisa diuji
 * dengan payload asli tanpa Android `Context`.
 *
 * Catatan: kanal `market:summary-24h` mengirim **semua** pair dalam satu aliran. Aplikasi
 * menyaring satu pair aktif; laju data terukur ±1 pesan/detik (±3 KB per 20 detik).
 */
object IndodaxSummaryFeed {

    const val CHANNEL = "market:summary-24h"

    /** Panjang tanda tangan HS256 base64url tanpa padding. */
    private const val SIGNATURE_LENGTH = 43

    /**
     * Token harus utuh: tiga segmen, dan tanda tangan tepat 43 karakter.
     * Token yang terpotong satu karakter pun ditolak server dengan `3002 invalid token`.
     */
    fun isValidTokenShape(token: String): Boolean {
        val parts = token.trim().split('.')
        return parts.size == 3 &&
            parts[0].isNotBlank() &&
            parts[1].isNotBlank() &&
            parts[2].length == SIGNATURE_LENGTH
    }

    /**
     * Mengambil baris untuk [expectedPairId] dari satu pesan.
     *
     * @param previousCumulativeVolume volume koin kumulatif pada pembacaan sebelumnya,
     * dipakai untuk menghitung delta volume menit berjalan.
     */
    fun parse(
        message: String,
        expectedPairId: String,
        previousCumulativeVolume: Double
    ): IndodaxSummaryTick? {
        if (expectedPairId.isBlank()) return null
        val root = runCatching { JSONObject(message) }.getOrNull() ?: return null
        val result = root.optJSONObject("result") ?: return null
        if (result.optString("channel") != CHANNEL) return null
        val payload = result.optJSONObject("data") ?: return null
        val rows = payload.optJSONArray("data") ?: return null

        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            if (row.length() < 8) continue
            if (!row.optString(0).equals(expectedPairId, ignoreCase = true)) continue

            val epochSec = row.optLong(1, 0L)
            val price = number(row, 2)
            if (epochSec <= 0L || price <= 0.0) continue

            val cumulative = number(row, 7)
            val delta = if (previousCumulativeVolume > 0.0 && cumulative >= previousCumulativeVolume) {
                cumulative - previousCumulativeVolume
            } else {
                0.0
            }

            return IndodaxSummaryTick(
                pairId = expectedPairId,
                timestampMs = epochSec * 1000L,
                price = price,
                low24h = number(row, 3),
                high24h = number(row, 4),
                open24h = number(row, 5),
                volumeIdr24h = number(row, 6),
                volumeCoin24h = cumulative,
                volumeCoinDelta = delta
            )
        }
        return null
    }

    private fun number(row: JSONArray, index: Int): Double = when (val value = row.opt(index)) {
        is Number -> value.toDouble()
        else -> value?.toString()?.toDoubleOrNull() ?: 0.0
    }
}
