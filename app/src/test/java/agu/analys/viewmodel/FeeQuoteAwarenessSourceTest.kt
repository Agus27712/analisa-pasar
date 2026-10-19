package agu.analys.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Fee Truth v1.1 §0.2** — tarif harus mengikuti kuotasi pair, dan userati asimetris untuk IDR.
 *
 * Cacat yang dijaga: jalur evaluate memakai `prefs.tradingFees` (satu nilai global). Bawaan
 * Tokocrypto adalah preset **USDT** (0,4044% semua sisi), sehingga pair IDR (BTCIDR, ETHIDR,
 * XAUTIDR) dievaluasi dengan tarif USDT — lantai SL jadi 5,81% (harusnya 4,89%), TP jauh, dan
 * angka net R:R di engine berbeda dari jalur paper yang sudah sadar-kuotasi.
 *
 * Yang dijaga: **setiap call site yang tahu simbolnya** wajib meneruskan tarif per-simbol
 * (`prefs.feeConfigFor(symbol)`). Nilai kustom pengguna tetap dihormati karena `feeConfigFor`
 * memakai nilai kustom bila ada.
 *
 * Kontrol negatif: kembalikan satu call site ke `fees = prefs.tradingFees` / `fees = tradingFees`
 * → uji di bawah GAGAL.
 */
class FeeQuoteAwarenessSourceTest {

    private fun baca(relatif: String): String {
        val base = File(System.getProperty("user.dir"))
        val kandidat = listOf(
            File(base, relatif),
            File(base, "app/$relatif")
        )
        val file = kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${base.absolutePath}")
        return file.readLines()
            .filterNot { baris ->
                val s = baris.trimStart()
                s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
            }
            .joinToString("\n")
    }

    @Test
    fun `engine meneruskan tarif per simbol`() {
        val kode = baca("src/main/java/agu/analys/engine/LearningTradingEngine.kt")
        assertTrue(
            "engine harus punya resolver feeConfigForSymbol",
            kode.contains("feeConfigForSymbol")
        )
        assertFalse(
            "evaluate tidak boleh memakai fee global — pair IDR akan dinilai dengan tarif USDT",
            kode.contains("fees = tradingFees")
        )
    }

    @Test
    fun `worker latar meneruskan tarif per simbol`() {
        val kode = baca("src/main/java/agu/analys/service/CandidateScanWorker.kt")
        assertFalse(
            "worker harus memakai feeConfigFor(symbol), bukan fee global",
            kode.contains("prefs.tradingFees")
        )
        assertTrue(kode.contains("prefs.feeConfigFor(symbol)"))
    }

    @Test
    fun `kartu jual memakai tarif pair yang sedang dibuka`() {
        val kode = baca("src/main/java/agu/analys/viewmodel/TradingViewModel.kt")
        assertTrue(
            "sellSignalState harus memakai feeConfigFor(pair), bukan fee global",
            kode.contains("feeConfigFor")
        )
    }

    @Test
    fun `halaman detail memakai tarif pair yang sedang dibuka`() {
        val kode = baca("src/main/java/agu/analys/viewmodel/TradingViewModelDetailState.kt")
        assertTrue(
            "balanceSlice harus sadar kuotasi pair",
            kode.contains("pairAwareFees") && kode.contains("prefs.feeConfigFor")
        )
    }
}