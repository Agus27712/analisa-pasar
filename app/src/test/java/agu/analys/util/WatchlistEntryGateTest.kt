package agu.analys.util

import agu.analys.config.MarketDataSource
import agu.analys.data.IndodaxSymbolRepository
import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.PairPrecision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **K13** — simbol hanya boleh masuk watchlist bila benar-benar ada di bursa aktif.
 *
 * Cacat lama: kolom "ketik simbol" menerima teks bebas, hasilnya langsung disimpan, dan
 * `TradingPair.fromCustomSymbol` mengarang metadata. Pair hantam `UVXYBUSDT` karena itu bisa
 * masuk watchlist dan ikut dievaluasi engine.
 *
 * **K14** — semua cabang dashboard memakai satu gate listing (`DashboardRanking.symbolListed`);
 * daftar tick disaring sekali di awal, bukan per cabang.
 *
 * Uji ini menjaga:
 *  1. keputusan gate murni (format, kuotasi, keberadaan) — fail-closed;
 *  2. `hasSymbol` pada kedua repository;
 *  3. `symbolListed` memakai repository yang benar per bursa;
 *  4. perkabelan: jalur tambah di ViewModel & UI tidak lagi punya fallback "simpan dulu",
 *     dan DashboardScreen menyaring `uniqueTicks` sekali di awal.
 *
 * Kontrol negatif: kembalikan fallback `?: typed` di UI → uji penjaga UI GAGAL; hapus
 * `canAddToWatchlist` dari `addToWatchlist` → uji penjaga ViewModel GAGAL; hapus filter
 * `symbolListed` pada `uniqueTicks` → uji penjaga dashboard GAGAL.
 */
class WatchlistEntryGateTest {

    private fun repoRoot(): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir")),
            File(System.getProperty("user.dir"), "..")
        )
        return kandidat.firstOrNull { File(it, "app/src/main/java/agu/analys").isDirectory }
            ?: error("akar repo tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    private fun source(rel: String): String =
        File(repoRoot(), "app/src/main/java/agu/analys/$rel").readText()

    /** Pengetahuan palsu: hanya simbol dalam [known] yang diakui gate. */
    private fun known(vararg known: String): (String) -> Boolean = { it in known }

    // ---------- K13: keputusan gate murni ----------

    @Test
    fun knownSymbol_accepted() {
        val verdict = WatchlistEntryGate.checkAdd(
            " btc_usdt ", MarketDataSource.TOKOCRYPTO, known("BTCUSDT", "BTCIDR")
        )
        assertTrue(verdict.accepted)
        assertEquals("BTCUSDT", verdict.normalized)
        assertEquals(null, verdict.rejection)
        assertEquals(null, verdict.message)
    }

    @Test
    fun ghostPair_rejected_notListed() {
        // Pair hantu pemicu audit: ada di gateway, tidak ada di Tokocrypto.
        val verdict = WatchlistEntryGate.checkAdd("UVXYBUSDT", MarketDataSource.TOKOCRYPTO) {
            it in setOf("BTCUSDT", "BTCIDR")
        }
        assertFalse(verdict.accepted)
        assertEquals(WatchlistEntryGate.Rejection.NOT_LISTED, verdict.rejection)
        assertNotNull(verdict.message)
    }

    @Test
    fun blank_rejected() {
        val verdict = WatchlistEntryGate.checkAdd("   ", MarketDataSource.TOKOCRYPTO) { true }
        assertFalse(verdict.accepted)
        assertEquals(WatchlistEntryGate.Rejection.BLANK, verdict.rejection)
    }

    @Test
    fun malformed_rejected() {
        // Karakter di luar huruf/angka.
        val verdict = WatchlistEntryGate.checkAdd("SOL US.T", MarketDataSource.TOKOCRYPTO) { true }
        assertFalse(verdict.accepted)
        assertEquals(WatchlistEntryGate.Rejection.MALFORMED, verdict.rejection)
    }

    @Test
    fun quoteOnlySymbol_malformed_notBase() {
        // "USDT" saja bukan simbol: tidak ada kode dasar.
        val verdict = WatchlistEntryGate.checkAdd("USDT", MarketDataSource.TOKOCRYPTO) { true }
        assertFalse(verdict.accepted)
        assertEquals(WatchlistEntryGate.Rejection.MALFORMED, verdict.rejection)
    }

    @Test
    fun wrongQuoteForSource_rejected() {
        // Kuotasi yang bukan milik bursa aktif.
        val toko = WatchlistEntryGate.checkAdd("BTCTHB", MarketDataSource.TOKOCRYPTO) { true }
        assertFalse(toko.accepted)
        assertEquals(WatchlistEntryGate.Rejection.WRONG_QUOTE, toko.rejection)

        // Indodax hanya pasar IDR: kuotasi kripto ditolak gate (pemetaan USDT→IDR tetap
        // jadi tanggung jawab PairRules.sanitizeSymbolForExchange sebelum gate dipanggil).
        val indodax = WatchlistEntryGate.checkAdd("BTCUSDT", MarketDataSource.INDODAX) { true }
        assertFalse(indodax.accepted)
        assertEquals(WatchlistEntryGate.Rejection.WRONG_QUOTE, indodax.rejection)
    }

    @Test
    fun messages_coverEveryRejection() {
        val cases = listOf(
            WatchlistEntryGate.Rejection.BLANK,
            WatchlistEntryGate.Rejection.MALFORMED,
            WatchlistEntryGate.Rejection.WRONG_QUOTE,
            WatchlistEntryGate.Rejection.NOT_LISTED
        )
        for (source in MarketDataSource.values()) {
            for (rejection in cases) {
                val verdict = when (rejection) {
                    WatchlistEntryGate.Rejection.BLANK ->
                        WatchlistEntryGate.checkAdd("", source) { true }
                    // Titik di dalam simbol: gagal pola untuk kedua bursa.
                    WatchlistEntryGate.Rejection.MALFORMED ->
                        WatchlistEntryGate.checkAdd("SOL.IDR", source) { true }
                    WatchlistEntryGate.Rejection.WRONG_QUOTE ->
                        WatchlistEntryGate.checkAdd("XXXTHB", source) { true }
                    WatchlistEntryGate.Rejection.NOT_LISTED ->
                        WatchlistEntryGate.checkAdd("XYZIDR", source) { false }
                }
                assertEquals(rejection, verdict.rejection)
                assertNotNull("pesan untuk $rejection/$source kosong", verdict.message)
            }
        }
    }

    @Test
    fun knownQuotes_matchExchangeRules() {
        assertEquals(listOf("IDR"), WatchlistEntryGate.knownQuotes(MarketDataSource.INDODAX))
        assertTrue(
            WatchlistEntryGate.knownQuotes(MarketDataSource.TOKOCRYPTO)
                .containsAll(listOf("USDT", "USDC", "BUSD", "IDR", "BIDR"))
        )
    }

    // ---------- K13: hasSymbol pada repository ----------

    @Test
    fun tokoRepository_hasSymbol_failClosedForUnknown() {
        assertTrue(TokocryptoSymbolRepository.hasSymbol("BTC_USDT"))
        assertTrue(TokocryptoSymbolRepository.hasSymbol("BTCIDR"))
        assertFalse(TokocryptoSymbolRepository.hasSymbol("UVXYBUSDT"))
        assertFalse(TokocryptoSymbolRepository.hasSymbol(""))
    }

    @Test
    fun indodaxRepository_hasSymbol_popularFallbackBeforeDiscovery() {
        // Sebelum discovery dimuat, batasnya daftar POPULAR_INDODAX_PAIRS (sama seperti
        // getAllTradingPairs), bukan "semua simbol lolos".
        assertTrue(IndodaxSymbolRepository.hasSymbol("BTCIDR"))
        assertTrue(IndodaxSymbolRepository.hasSymbol("sol_idr"))
        assertFalse(IndodaxSymbolRepository.hasSymbol("UVXYBUSDT"))
    }

    @Test
    fun indodaxRepository_hasSymbol_usesDiscoveredPairs() {
        IndodaxSymbolRepository.setPairsForTest(
            listOf(
                PairPrecision(
                    id = "sol_idr",
                    symbol = "SOLIDR",
                    baseCurrency = "SOL",
                    tradedCurrency = "idr",
                    priceDecimals = 2,
                    quantityDecimals = 6
                )
            )
        )
        try {
            assertTrue(IndodaxSymbolRepository.hasSymbol("SOLIDR"))
            assertTrue(IndodaxSymbolRepository.hasSymbol("sol_idr"))
            // Peta sudah terisi: pair POPULAR yang tidak ada di hasil discovery gugur.
            assertFalse(IndodaxSymbolRepository.hasSymbol("BTCIDR"))
            assertFalse(IndodaxSymbolRepository.hasSymbol("UVXYBUSDT"))
        } finally {
            IndodaxSymbolRepository.setPairsForTest(emptyList())
        }
    }

    // ---------- K14: symbolListed memakai repository bursa yang benar ----------

    @Test
    fun symbolListed_toko_requiresTradingStatus() {
        assertTrue(DashboardRanking.symbolListed(MarketDataSource.TOKOCRYPTO, "BTCIDR"))
        assertFalse(DashboardRanking.symbolListed(MarketDataSource.TOKOCRYPTO, "UVXYBUSDT"))

        assertTrue(TokocryptoSymbolRepository.setSpotTradingForTest("BTCIDR", false))
        try {
            assertFalse(DashboardRanking.symbolListed(MarketDataSource.TOKOCRYPTO, "BTCIDR"))
        } finally {
            assertTrue(TokocryptoSymbolRepository.setSpotTradingForTest("BTCIDR", true))
        }
    }

    @Test
    fun symbolListed_indodax_requiresKnownPair() {
        assertTrue(DashboardRanking.symbolListed(MarketDataSource.INDODAX, "BTCIDR"))
        assertTrue(DashboardRanking.symbolListed(MarketDataSource.INDODAX, "sol_idr"))
        assertFalse(DashboardRanking.symbolListed(MarketDataSource.INDODAX, "UVXYBUSDT"))
        assertFalse(DashboardRanking.symbolListed(MarketDataSource.INDODAX, "XYZIDR"))
    }

    // ---------- Penjaga perkabelan ----------

    @Test
    fun viewModel_addPaths_gated() {
        val src = source("viewmodel/WatchlistViewModel.kt")
        assertTrue("gate harus dipakai di addToWatchlist",
            src.substringAfter("fun addToWatchlist(")
                .substringBefore("fun removeFromWatchlist(")
                .contains("canAddToWatchlist"))
        val toggleBody = src.substringAfter("fun toggleWatchlist(")
            .substringBefore("fun addToWatchlist(")
        assertTrue("toggle hanya boleh menambah bila lolos gate",
            toggleBody.contains("alreadyWatched") && toggleBody.contains("canAddToWatchlist"))
        assertTrue("setCustomWatchlist wajib menyaring entri asing",
            src.substringAfter("fun setCustomWatchlist(")
                .substringBefore("fun applyWatchlistPreset(")
                .contains("canAddToWatchlist"))
    }

    @Test
    fun settingsUi_noFailOpenFallback() {
        val src = source("ui/components/settings/WatchlistManagerSettings.kt")
        assertTrue(src.contains("WatchlistEntryGate.checkAdd"))
        // Fallback lama: sanitizeSymbolForExchange(...) ?: typed — "simpan dulu, sahkan nanti".
        assertFalse(
            "fallback ?: typed masih ada (fail-open)",
            Regex("\\?:\\s*typed\\b").containsMatchIn(src)
        )
    }

    @Test
    fun dashboard_filtersUniqueTicksOnce_withSharedGate() {
        val src = source("ui/screens/DashboardScreen.kt")
        val uniqueBlock = src.substringAfter("val uniqueTicks = remember(")
            .substringBefore("val watchSymbols")
        assertTrue(
            "uniqueTicks harus disaring dengan symbolListed sekali di awal",
            uniqueBlock.contains("DashboardRanking.symbolListed")
        )
        assertTrue(
            "pairListed harus memakai gate bersama",
            src.substringAfter("fun pairListed(")
                .substringBefore("val popularHead")
                .contains("DashboardRanking.symbolListed")
        )
        assertTrue(
            "quick filter WATCHLIST wajib sama menyaringnya",
            src.substringAfter("DashboardQuickFilter.WATCHLIST ->")
                .substringBefore("val activeHoldingList")
                .contains("DashboardRanking.symbolListed")
        )
    }

    @Test
    fun repositories_exposeHasSymbol() {
        assertTrue(source("data/TokocryptoSymbolRepository.kt").contains("fun hasSymbol"))
        assertTrue(source("data/IndodaxSymbolRepository.kt").contains("fun hasSymbol"))
    }

    @After
    fun tearDown() {
        // Indodax repo adalah objek global: pastikan kembali ke kondisi pra-uji.
        IndodaxSymbolRepository.setPairsForTest(emptyList())
        TokocryptoSymbolRepository.setSpotTradingForTest("BTCIDR", true)
    }
}
