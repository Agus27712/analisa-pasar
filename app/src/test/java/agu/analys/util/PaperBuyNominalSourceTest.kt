package agu.analys.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Uji sumber (source-scan) gerbang nominal beli paper — pola yang sama dengan
 * `ConfidenceTierRemovalTest`: keputusan yang hidup di kode ber-Compose tidak bisa
 * diuji perilakunya di JVM, jadi yang dijaga adalah BENTUK sumbernya.
 *
 * Keputusan owner 2026-10-26: form beli paper TANPA nominal default. Literal
 * `50000.0` dulu dipakai sebagai nominal kuotasi apa adanya — pada pair USDT
 * berarti $50.000 (pola sama dengan bug §18.2 workplan2: nominal IDR dianggap
 * kuotasi). Uji ini GAGAL kalau literal itu kembali, atau kalau gerbang
 * `resolveBuyNominalGate` dilepas dari tombol beli.
 */
class PaperBuyNominalSourceTest {

    private fun berkas(relatif: String): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    private fun kode(relatif: String): String =
        // Pindai kode saja: komentar // dibuang supaya uji tidak dipicu/kelabu-kan
        // oleh tulisan penjelas di komentar (berkas-berkas ini tidak memuat URL).
        berkas(relatif).readText().lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")

    @Test
    fun `kartu beli paper tidak boleh punya nominal default literal 50000`() {
        val kartu = kode("agu/analys/ui/components/detail/WaitingEntryRadarCard.kt")
        assertFalse(
            "Literal nominal default 50000.0 kembali di WaitingEntryRadarCard — " +
                "keputusan owner 2026-10-26: form beli paper TANPA default",
            kartu.contains("50000.0")
        )
        // Nominal wajib di-reset per pair, bukan state global tanpa key.
        assertTrue(
            "State nominal beli wajib ber-key (baseAsset, quoteAsset) agar reset saat pindah pair",
            kartu.contains("remember(baseAsset, quoteAsset) { mutableDoubleStateOf(0.0) }")
        )
    }

    @Test
    fun `tombol beli wajib melewati gerbang resolveBuyNominalGate`() {
        val bagian = kode("agu/analys/ui/components/detail/RadarBuySection.kt")
        assertTrue(
            "RadarBuySection wajib memakai resolveBuyNominalGate sebelum tombol beli",
            bagian.contains("resolveBuyNominalGate")
        )
        assertFalse(
            "coerceAtLeast(minNominalQuote) menaikkan nominal diam-diam — dilarang; " +
                "ukuran tak layak ditolak lewat gerbang dengan alasan terbaca",
            bagian.contains(".coerceAtLeast(minNominalQuote)")
        )
        assertTrue(
            "Tombol beli wajib dinonaktifkan bila gerbang nominal menolak",
            bagian.contains("!isNominalBlocked")
        )
    }

    @Test
    fun `dialog limit tidak boleh mengarang nominal awal dan saldo 0 bukan cukup`() {
        val dialog = kode("agu/analys/ui/components/detail/CustomBuyOrderDialog.kt")
        assertFalse(
            "Nominal awal dialog tidak boleh diarang (50000.0 / 10.0 USDT)",
            dialog.contains("else 50000.0") || dialog.contains("isUsdtQuote) 10.0")
        )
        assertFalse(
            "`availableIdr <= 0 ||` menganggap saldo kosong sebagai cukup — gerbang saldo jujur wajib",
            dialog.contains("availableIdr <= 0 ||")
        )
        assertTrue(
            "Gerbang saldo dialog wajib membandingkan nominal dengan saldo",
            dialog.contains("nominalIdr <= availableIdr")
        )
    }

    @Test
    fun `helper nominal default yang tak dipakai tidak boleh hidup kembali`() {
        val formatter = kode("agu/analys/util/PriceFormatter.kt")
        assertFalse(
            "PriceFormatter.defaultOrderNominal dihapus (kontrak palsu, preseden P7) — " +
                "form beli paper tidak punya nominal default",
            formatter.contains("defaultOrderNominal")
        )
    }

    @Test
    fun `default parameter 50000 tidak boleh kembali di seksi transaksi`() {
        val seksi = kode("agu/analys/ui/components/detail/RadarTransactionFeeSection.kt")
        assertFalse(
            "Default param selectedNominalIdr = 50000.0 dihapus — nilai mati yang menyesatkan",
            seksi.contains("selectedNominalIdr: Double = 50000.0")
        )
    }
}
