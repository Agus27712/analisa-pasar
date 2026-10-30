package agu.analys.engine

import agu.analys.config.FeeSchedule
import agu.analys.config.StrategyMode
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.engine.intraday.IntradayRuntimeConfig
import agu.analys.model.CandleBar
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Penjaga **sambungan** slider intraday (paket G, 2026-10-30).
 *
 * `ModeSignalEvaluator` adalah satu-satunya pintu `StrategyMode → evaluator`. Slider intraday di
 * UI menyimpan nilainya di [IntradayRuntimeConfig] dan hanya berguna kalau pintu ini membacanya.
 * Uji ini memakai dataset ter-commit `data/tokocrypto_v8/DOGEUSDT_4h.csv` + D1 dan membandingkan
 * **output sinyal** dari dua config berbeda, jadi ia gagal bila sambungannya dilepas (mis. field
 * `intradayConfig` diabaikan dan evaluator kembali memakai `IntradayConfig.DEFAULT`).
 *
 * Memakai `nowMs` di sesi **Open Pagi** karena mode intraday hanya boleh entry di jendela itu.
 */
class ModeSignalEvaluatorIntradayConfigTest {

    private fun muatCandle(namaBerkas: String): List<CandleBar> {
        val kandidat = listOf(
            File("data/tokocrypto_v8/$namaBerkas"),
            File("../data/tokocrypto_v8/$namaBerkas"),
            File(System.getProperty("user.dir") ?: ".", "data/tokocrypto_v8/$namaBerkas"),
            File(System.getProperty("user.dir") ?: ".", "../data/tokocrypto_v8/$namaBerkas")
        )
        val berkas = kandidat.firstOrNull { it.isFile }
            ?: error("Dataset riset $namaBerkas tidak ditemukan dari ${File(".").absolutePath}")
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

    /**
     * Bar ke-731 dataset DOGEUSDT_4h: tutup 2024-11-17 07:00 WIB — sesi Open Pagi, dan pada bar
     * ini `flashDumpTraumaCandles` 12 vs 16 memberi keputusan berbeda (dump terakhir 15 candle
     * lalu: 12 → tidak trauma, 16 → trauma).
     */
    private val indeksBarUji = 731

    private fun evaluasi(config: IntradayConfig): Pair<String, String> {
        val h4 = muatCandle("DOGEUSDT_4h.csv")
        val d1 = muatCandle("DOGEUSDT_1d.csv")
        val bar = h4[indeksBarUji]
        val result = ModeSignalEvaluator.evaluate(
            ModeSignalInput(
                mode = StrategyMode.OFFICE_DAILY,
                symbol = "DOGEUSDT",
                price = bar.close,
                nowMs = bar.timestamp + 4 * 60 * 60 * 1000L,
                h4Candles = h4.take(indeksBarUji + 1),
                d1Candles = d1.filter { it.timestamp <= bar.timestamp },
                fees = FeeSchedule.configFor("TOKOCRYPTO", "USDT"),
                intradayConfig = config
            )
        )
        assertNotNull("Evaluasi intraday harus mengembalikan sinyal pada data ini", result)
        return result!!.signal.action.name to result.signal.reasoning.joinToString(" | ")
    }

    /**
     * Kontrol perilaku: config trauma 12 vs 16 pada bar yang sama harus memberi hasil berbeda.
     * Kalau `ModeSignalEvaluator` berhenti meneruskan `intradayConfig`, kedua evaluasi kembali
     * identik (keduanya memakai DEFAULT) dan uji ini gagal.
     */
    @Test
    fun knobTraumaDariConfigBenarBenarDipakaiPintuEvaluator() {
        val bawaan = evaluasi(IntradayRuntimeConfig.DEFAULT)
        val ketat = evaluasi(IntradayConfig.DEFAULT.copy(flashDumpTraumaCandles = 16))

        org.junit.Assert.assertNotEquals(
            "Dua config berbeda harus menghasilkan keputusan/reasoning berbeda. " +
                "bawaan='${bawaan.first}' ketat='${ketat.first}'",
            bawaan.second,
            ketat.second
        )
        org.junit.Assert.assertTrue(
            "Config ketat (trauma 16 candle) harus menyebut alasan flash dump; alasan: ${ketat.second}",
            ketat.second.contains("flash dump", ignoreCase = true)
        )
        org.junit.Assert.assertFalse(
            "Config bawaan (trauma 12 candle) tidak boleh menolak karena flash dump pada bar ini; " +
                "alasan: ${bawaan.second}",
            bawaan.second.contains("Anti Flash Dump", ignoreCase = true)
        )
    }
}