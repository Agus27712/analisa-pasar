package agu.analys.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D1 — **layar detail tidak boleh menulis balik pair terpilih.**
 *
 * Gejala yang dilaporkan owner: klik pair baru (mis. BTC) di dashboard, tetapi layar detail
 * menampilkan pair yang dibuka sebelumnya.
 *
 * Akarnya siklus tulis-balik, bukan data bursa:
 *
 * ```
 * selectedPair ──combine──> detailUiState ──pair──> DetailChartScreen
 *        ^                                                   │
 *        └──────── LaunchedEffect { selectPair(pair) } ───────┘
 * ```
 *
 * `DetailUiState.pair` DITURUNKAN dari `selectedPair`, jadi effect di layar detail hanya boleh
 * membaca. Kalau ia memanggil `selectPair(pair)`, emisi pertama yang masih membawa pair lama
 * (initialValue `DetailUiState.empty()` / cache `WhileSubscribed(5_000)` / urutan emisi `combine`)
 * menimpa pilihan yang baru saja diklik.
 *
 * Penjaga ini memeriksa berkas sumber karena `DetailChartScreen` adalah Composable Compose:
 * efek tersebut tidak bisa diuji dari JVM tanpa emulator Compose, sedangkan regresinya terlihat
 * jelas di teks sumber.
 */
class DetailPairSourceTest {

    private fun berkas(relatif: String): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    /** Buang komentar blok dan baris supaya catatan penjelasan tidak dianggap kode. */
    private fun kodeTanpaKomentar(relatif: String): String =
        berkas(relatif).readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `layar detail tidak memanggil selectPair`() {
        val layar = kodeTanpaKomentar("agu/analys/ui/screens/DetailChartScreen.kt")

        assertFalse(
            "D1: DetailChartScreen tidak boleh memanggil selectPair — detailUiState.pair " +
                "diturunkan dari selectedPair, jadi tulisan balik ini menimpa pair yang baru " +
                "diklik dengan pair lama (emisi pertama/initialValue/cache WhileSubscribed). " +
                "Pair dipilih di pemanggil lewat openCoinDetail sebelum navigasi.",
            layar.contains("selectPair(")
        )
    }

    @Test
    fun `layar detail masih punya efek subscribe koin yang sah`() {
        val layar = kodeTanpaKomentar("agu/analys/ui/screens/DetailChartScreen.kt")

        // Penjaga sisi lain: penghapusan efek tulis-balik tidak boleh berubah menjadi
        // "hapus semua efek". Subscribe GlobalContext per baseAsset tetap wajib ada.
        assertTrue(
            "D1: DisposableEffect(pair.baseAsset) dengan GlobalContextManager.subscribeCoin " +
                "harus tetap ada — ia yang memasok konteks global, bukan penulis pair.",
            layar.contains("DisposableEffect(pair.baseAsset)")
        )
        assertTrue(
            "D1: subscribeCoin harus tetap dipanggil dari layar detail",
            layar.contains("GlobalContextManager.subscribeCoin")
        )
        assertTrue(
            "D1: efek fetch saldo REAL masih sah dan harus tetap ada",
            layar.contains("LaunchedEffect(isRealBuyMode, pair.symbol)")
        )
    }

    @Test
    fun `pintu masuk detail tetap memilih pair sebelum navigasi`() {
        val navigasi = kodeTanpaKomentar("agu/analys/viewmodel/TradingViewModelNavigation.kt")

        // Invarian yang membuat penghapusan efek aman: satu-satunya jalur ke AppScreen.DETAIL
        // wajib menulis selectedPair lebih dulu.
        assertTrue(
            "D1: openCoinDetail wajib memanggil selectPair(pair) sebelum navigateTo(DETAIL) — " +
                "itu pengganti satu-satunya penulis pair untuk layar detail.",
            navigasi.contains("selectPair(pair)")
        )
        val posisiSelect = navigasi.indexOf("selectPair(pair)")
        val posisiNavigasi = navigasi.indexOf("navigateTo(AppScreen.DETAIL)")
        assertTrue(
            "D1: selectPair harus dipanggil SEBELUM navigateTo(AppScreen.DETAIL); " +
                "urutan terbalik membuat layar detail sempat merender pair lama.",
            posisiSelect in 0 until posisiNavigasi.coerceAtLeast(0)
        )
    }
}
