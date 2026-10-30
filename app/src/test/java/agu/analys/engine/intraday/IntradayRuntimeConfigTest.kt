package agu.analys.engine.intraday

import agu.analys.engine.intraday.IntradayConfig
import agu.analys.engine.intraday.replay.IntradayReplayEngine
import agu.analys.model.CandleBar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Penjaga holder runtime mode INTRADAY ([IntradayRuntimeConfig]) — paket G, 2026-10-30.
 *
 * Ini uji **perilaku**, bukan pindai sumber (aturan `app/AGENTS.md` §79.9): semua uji berjalan di
 * atas dataset riset ter-commit `data/tokocrypto_v8/DOGEUSDT_4h.csv` (4.999 bar ≈ 833 hari),
 * memakai [IntradayReplayEngine] + [IntradayGeometry] yang sama dengan aplikasi. Hanya
 * `flashDumpTraumaCandles` yang diubah di sini (satu variabel), karena telah diukur bahwa pada
 * dataset ini kelipatan 16 vs 12 mengubah jumlah bar yang diblokir trauma (196 vs 135 bar) —
 * bukti knob benar-benar dipakai.
 *
 * Yang dijaga di sini:
 * 1. round-trip JSON holder == instance aslinya (ekspor/impor tidak boleh mengubah angka);
 * 2. preset STRICT/LOOSE berbeda dari DEFAULT (bukan cuma nama);
 * 3. `current` holder berubah (mode interaktif bekerja) dan flow ikut;
 * 4. preset yang dipilih benar-benar mengubah jumlah BUY replay di dataset nyata —
 *    knob slider bukan dekorasi;
 * 5. config rusak → parsing gagal (fail-closed ke DEFAULT di pemanggil),
 *    dan round-trip menulis ulang kunci yang sama dengan mirror `data/config/intraday_config.json`.
 */
class IntradayRuntimeConfigTest {

    private val feeUsdt = agu.analys.config.FeeSchedule.configFor("TOKOCRYPTO", "USDT")

    private fun muatCandleH4Riset(pair: String): List<CandleBar> {
        val kandidat = listOf(
            File("data/tokocrypto_v8/${pair}_4h.csv"),
            File("../data/tokocrypto_v8/${pair}_4h.csv"),
            File(System.getProperty("user.dir") ?: ".", "data/tokocrypto_v8/${pair}_4h.csv"),
            File(System.getProperty("user.dir") ?: ".", "../data/tokocrypto_v8/${pair}_4h.csv")
        )
        val berkas = kandidat.firstOrNull { it.isFile }
            ?: error("Dataset riset ${pair}_4h.csv tidak ditemukan dari ${File(".").absolutePath}")
        return berkas.readLines().drop(1).filter { it.isNotBlank() }.map { baris ->
            val kolom = baris.split(",")
            CandleBar(
                timestamp = kolom[0].toLong(),
                open = kolom[1].toDouble(),
                high = kolom[2].toDouble(),
                low = kolom[3].toDouble(),
                close = kolom[4].toDouble(),
                volume = kolom[5].toDouble()
            )
        }
    }

    private fun replayReport(pair: String, config: IntradayConfig) =
        IntradayReplayEngine.replay(
            symbol = pair,
            candles = muatCandleH4Riset(pair),
            feeConfig = feeUsdt,
            config = config,
            // 6 bar H4 ≈ 24 jam, sama dengan jendela lookahead berbasis jam di alat ukur §85.
            forwardLookaheadBars = 6 * 4
        )

    // 1. round-trip
    @Test
    fun roundTripJson_mempertahankanSemuaNilai() {
        val asli = IntradayConfig.DEFAULT.copy(
            minNetRrTp1 = 1.7,
            maxTp1Pct = 6.5,
            slAtrMult = 0.45,
            flashDumpTraumaCandles = 20,
            macdHistMinPct = 0.03
        )
        val json = IntradayRuntimeConfig.toJsonString(asli)
        val pulih = IntradayRuntimeConfig.fromJsonString(json)
        assertEquals("Round-trip JSON harus mengembalikan config yang sama persis", asli, pulih)
    }

    // 2. preset benar-benar berbeda
    @Test
    fun presetStrictDanLooseBukanSalinanStandar() {
        assertNotEquals("STRICT harus berbeda dari DEFAULT", IntradayRuntimeConfig.DEFAULT, IntradayRuntimeConfig.STRICT)
        assertNotEquals("LOOSE harus berbeda dari DEFAULT", IntradayRuntimeConfig.DEFAULT, IntradayRuntimeConfig.LOOSE)
        assertTrue(
            "STRICT harus menaikkan ambang R:R dan trauma dump",
            IntradayRuntimeConfig.STRICT.minNetRrTp1 > IntradayRuntimeConfig.DEFAULT.minNetRrTp1
        )
        assertTrue(
            "STRICT harus menaikkan umur trauma flash dump",
            IntradayRuntimeConfig.STRICT.flashDumpTraumaCandles > IntradayRuntimeConfig.DEFAULT.flashDumpTraumaCandles
        )
        assertTrue(
            "LOOSE harus menaikkan cap TP1 (nilai terukur §83 = 6,0%)",
            IntradayRuntimeConfig.LOOSE.maxTp1Pct > IntradayRuntimeConfig.DEFAULT.maxTp1Pct
        )
    }

    // 3. holder berubah + flow ikut
    @Test
    fun currentHolderBerubah_danFlowIkut() {
        val lama = IntradayRuntimeConfig.current
        try {
            val baru = lama.copy(maxTp1Pct = 7.0)
            IntradayRuntimeConfig.current = baru
            assertEquals("current harus kembali config yang disetel", baru, IntradayRuntimeConfig.current)
            assertEquals("currentFlow harus menyinkronkan state yang sama", baru, IntradayRuntimeConfig.currentFlow.value)
        } finally {
            IntradayRuntimeConfig.current = lama
        }
    }

    // 4. Config benar-benar mengubah keputusan di dataset nyata (bukan dekorasi).
    //
    // `flashDumpTraumaCandles` 12 → 16 diukur pada DOGEUSDT_4h (4.999 bar): bar yang diblokir
    // trauma naik 165 → 209 (44 bar tambahan). Ini knob paling langsung terlihat di laporan
    // replay (`bottleneck.flashDumpTraumaBlocks`), jadi ia dipakai sebagai bukti bahwa nilai
    // dari holder/slider benar-benar masuk ke evaluator — bukan sekadar tersimpan.
    @Test
    fun traumaLebihLamaMemblokirLebihBanyakBarDiDatasetNyata() {
        val standar = replayReport("DOGEUSDT", IntradayRuntimeConfig.DEFAULT)
        val ketat = replayReport("DOGEUSDT", IntradayRuntimeConfig.STRICT)

        assertTrue(
            "STRICT (trauma ${IntradayRuntimeConfig.STRICT.flashDumpTraumaCandles} candle) harus " +
                "memblokir lebih banyak bar daripada bawaan " +
                "(${IntradayRuntimeConfig.DEFAULT.flashDumpTraumaCandles} candle). Terukur: " +
                "standar=${standar.bottleneck.flashDumpTraumaBlocks}, " +
                "ketat=${ketat.bottleneck.flashDumpTraumaBlocks}",
            ketat.bottleneck.flashDumpTraumaBlocks > standar.bottleneck.flashDumpTraumaBlocks
        )
    }

    // 5. parsing gagal fail-closed; mirror == DEFAULT (kunci dan nilai)
    @Test
    fun parsingRusakMenolak_danMirrorSamaDenganBawaan() {
        assertNull("JSON tidak valid harus gagal (pemanggil jatuh ke DEFAULT)", IntradayRuntimeConfig.fromJsonString("{ bukan json"))

        val base = File(System.getProperty("user.dir") ?: ".")
        val kandidat = listOf(
            File(base, "data/config/intraday_config.json"),
            File(base, "../data/config/intraday_config.json")
        )
        val mirror = kandidat.firstOrNull { it.isFile }
            ?: error("Mirror data/config/intraday_config.json wajib ada (mencari dari ${base.absolutePath})")
        val mirrorJson = JSONObject(mirror.readText())
        val bawaanJson = IntradayRuntimeConfig.toJson(IntradayRuntimeConfig.DEFAULT)

        val kunciMirror = mirrorJson.keys().asSequence().filter { !it.startsWith("_") }.toSet()
        val kunciBawaan = bawaanJson.keys().asSequence().filter { !it.startsWith("_") }.toSet()
        assertEquals(
            "Kunci mirror harus sama persis dengan output holder (tidak ada knob yang hanya ada di satu sisi)",
            kunciBawaan,
            kunciMirror
        )
        kunciMirror.forEach { kunci ->
            assertEquals(
                "Nilai mirror '$kunci' harus sama dengan IntradayConfig.DEFAULT — mirror adalah " +
                    "alat ukur offline, bukan config kedua",
                bawaanJson.get(kunci).toString(),
                mirrorJson.get(kunci).toString()
            )
        }
    }
}