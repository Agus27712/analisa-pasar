package agu.analys.model

/**
 * Catatan netral di bawah tabel "RELIABILITAS SKOR KEYAKINAN".
 *
 * Pengganti klaim hardcoded (J6): *"Skor keyakinan tinggi (≥80%) menunjukkan rasio kemenangan
 * tertinggi dan drawdown terendah pada eksekusi real-time."* Kalimat itu melanggar aturan
 * "jangan menulis di UI bahwa sinyal punya winrate/expectancy historis tanpa data cukup":
 * tanpa syarat, tanpa jumlah sampel, dan (N11) mengutip tier yang nyaris mustahil terisi sinyal
 * scalping pra-entry sebagai keunggulan analitis.
 */
data class ConfidenceTierNote(
    /** Teks yang ditampilkan; menyebut basis perhitungan dan jumlah sampel. */
    val text: String,
    /** `true` bila sampel sudah cukup agar tabel bisa dipakai sebagai dasar simpulan. */
    val isReliable: Boolean
)

object ConfidenceTierNoteResolver {

    /**
     * Sampel minimum sinyal **selesai** (TP/SL tercapai, bukan TRACKING) sebelum tabel tier
     * layak dikutip. Sengaja sama dengan [SetupStats.MIN_RELIABLE_SAMPLE]: satu definisi
     * "sampel cukup" untuk seluruh layar statistik.
     */
    const val MIN_RESOLVED_SAMPLE = SetupStats.MIN_RELIABLE_SAMPLE

    /**
     * @return catatan untuk kartu tier, atau `null` bila belum ada data sama sekali —
     * jangan cetak klaim apa pun tanpa data (itu justru inti temuan J6).
     */
    fun resolve(summary: SignalReliabilitySummary): ConfidenceTierNote? {
        val total = summary.totalLogs
        if (total <= 0) return null

        val resolved = summary.completedLogs
        if (resolved < MIN_RESOLVED_SAMPLE) {
            return ConfidenceTierNote(
                isReliable = false,
                text = "Sampel belum cukup: $resolved sinyal selesai dari $total total " +
                    "(minimal $MIN_RESOLVED_SAMPLE). Angka di atas indikatif, belum bisa " +
                    "dipakai menyimpulkan."
            )
        }

        // N11 — tier kosong bukan bug, tapi pembaca tabel wajib tahu. Sinyal scalping pra-entry
        // di-cap ≤59 kecuali READY, jadi tier ≥80 mengukur "apakah sudah READY" lebih banyak
        // daripada kualitas berjenjang. Sebutkan tier yang kosong sebagai fakta data, bukan klaim.
        val tiers = listOf(
            summary.highConfidenceStats,
            summary.mediumConfidenceStats,
            summary.lowConfidenceStats
        )
        val emptyTiers = tiers.count { it.totalSignals == 0 }
        val tierFact = if (emptyTiers > 0) {
            " $emptyTiers dari ${tiers.size} tier belum terisi sinyal."
        } else {
            ""
        }
        return ConfidenceTierNote(
            isReliable = true,
            text = "Basis: $resolved sinyal selesai (TP/SL tercapai) dari $total total di bursa " +
                "ini. Win rate hanya dari sinyal yang sudah selesai; PnL bersih fee & slippage." +
                tierFact
        )
    }
}
