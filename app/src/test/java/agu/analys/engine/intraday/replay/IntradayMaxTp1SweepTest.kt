package agu.analys.engine.intraday.replay

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.model.CandleBar
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Alat ukur **sweep `maxTp1Pct` 3%–10%** pada dataset riset ter-commit `data/tokocrypto_v7`
 * (11 pair H1, 999 bar masing-masing), memakai jalur replay dan geometri yang **sama** dengan
 * aplikasi ([IntradayReplayEngine] + [agu.analys.engine.intraday.IntradayGeometry]).
 *
 * ⚠️ **Batas alat ini (dibaca sebelum mengutip angkanya, 2026-10-29).** Ini bukan lagi alat ukur
 * kanonik. `data/tokocrypto_v7` hanya 999 bar H1 (≈41 hari, 11 pair), **candle D1 tidak dioper**
 * (jadi veto anomali makro mati), dan `forwardLookaheadBars = 24` di H1/TF lain dipakai apa adanya
 * (di H4 = 96 jam). App sendiri menjalankan intraday di **H4** dengan `d1Candles` ikut dioper
 * (`CandidateScanWorker`). Untuk pengukuran baru pakai
 * **[IntradayMaxTp1SweepV8Test]** (`data/tokocrypto_v8`: 26 pair, H4 4.999 bar ≈ 833 hari, D1
 * dioper, lookahead berbasis jam). Berkas ini sengaja **tidak diubah perilakunya** supaya angka
 * §83 `workplan2.md` tetap bisa direproduksi persis.
 *
 * Kenapa ada di sini, bukan skrip Python terpisah: geometri TP1 dan gate net R:R hidup di Kotlin.
 * Menulis ulang rumusnya di Python berarti mengukur rumus lain, bukan yang dipakai aplikasi.
 *
 * Hanya jalan bila `RUN_INTRADAY_MAXTP1_SWEEP=true`. Tanpa env itu test **dilewati** supaya
 * suite harian tetap cepat (pola sama dengan `RealTokocryptoReplayTest`).
 *
 * Hasil ditulis ke `app/build/intraday-maxtp1-sweep.md`; baris progres ditambahkan ke
 * `app/build/intraday-maxtp1-progress.txt` selagi berjalan (replay 11 pair mahal, ±1 jam untuk
 * semua kombinasi). Angka di laporan itu yang boleh dikutip — jangan mengutip dari ingatan.
 *
 * Konfigurasi yang dipegang tetap selama sweep: ambang `minNetRrTp1 = 1,5` (keputusan owner,
 * tidak diturunkan), `macdHistMinPct = 0,0`, fee Tokocrypto USDT all-in, slippage bawaan.
 * Hanya `maxTp1Pct` yang berubah — satu variabel, supaya perbandingan sah.
 */
class IntradayMaxTp1SweepTest {

    /** Satu baris hasil: satu nilai `maxTp1Pct` pada satu potong data. */
    private data class Hasil(
        val label: String,
        val pct: Double,
        val buy: Int,
        val trade: Int,
        val menang: Int,
        val totalNetPct: Double,
        val pairBerBuy: String
    ) {
        val avgNetPct: Double get() = if (trade > 0) totalNetPct / trade else 0.0
        val winRatePct: Double get() = if (trade > 0) menang.toDouble() / trade * 100.0 else 0.0
    }

    @Test
    fun `sweep maxTp1Pct 3 sampai 10 persen`() {
        assumeTrue(
            "Sweep maxTp1Pct hanya dijalankan bila diminta (RUN_INTRADAY_MAXTP1_SWEEP=true)",
            System.getenv("RUN_INTRADAY_MAXTP1_SWEEP") == "true"
        )

        val dirData = cariDirData()
        val berkas = dirData.listFiles { f: File -> f.name.endsWith("_1h.csv") }!!
            .sortedBy { it.name }
        assertTrue(
            "Dataset harus berisi 11 pair H1, ditemukan ${berkas.size}",
            berkas.size == 11
        )
        val candlePerPair = berkas.associate { it.name.removeSuffix("_1h.csv") to muatCandle(it) }
        val jumlahBar = candlePerPair.values.map { it.size }.toSet()
        assertTrue("Panjang candle per pair harus seragam, ditemukan $jumlahBar", jumlahBar.size == 1)

        val fee = TradingFeeConfig.tokocryptoUsdt()
        val nilaiSweep = (3..10).map { it.toDouble() } // 3% … 10%, langkah 1% (permintaan owner)

        // Baris progres ditulis segera: replay 11 pair mahal, jadi hasil per konfigurasi harus
        // bisa dibaca tanpa menunggu seluruh sweep selesai.
        val progres = File(cariRootRepo(), "app/build/intraday-maxtp1-progress.txt").apply {
            parentFile?.mkdirs()
            writeText("")
        }

        // ── Sweep utama: seluruh window, 11 pair ────────────────────────────
        val utama = nilaiSweep.map { pct ->
            ukur("semua", pct, candlePerPair, fee).also { h ->
                progres.appendText(barisTabel(h) + "\n")
            }
        }
        val pairBerSinyal = utama.flatMap { h ->
            h.pairBerBuy.split(", ").filter { it.isNotBlank() }.map { it.substringBefore(' ') }
        }.distinct()
        progres.appendText("pair ber-sinyal: ${pairBerSinyal.joinToString(", ")}\n")

        // ── Uji ketahanan: dua potong (60% awal / 40% akhir) ────────────────
        // Satu window tidak cukup untuk memilih parameter (aturan docs/AGENTS.md). Diukur hanya
        // pada pair yang benar-benar menghasilkan sinyal: pair lain 0 BUY di semua batas atas.
        val candleSinyal = candlePerPair.filterKeys { it in pairBerSinyal }
        val belah = nilaiSweep.flatMap { pct ->
            listOf(
                ukur("awal 60%", pct, potong(candleSinyal, awal = true), fee),
                ukur("akhir 40%", pct, potong(candleSinyal, awal = false), fee)
            )
        }.onEach { h -> progres.appendText("potong ${barisTabel(h)}\n") }

        val laporan = buildString {
            appendLine("# Sweep maxTp1Pct 3%–10% — mode intraday")
            appendLine()
            appendLine("Dihasilkan `IntradayMaxTp1SweepTest` pada ${berkas.size} pair H1 dataset")
            appendLine("`data/tokocrypto_v7` (${jumlahBar.first()} bar per pair), fee Tokocrypto USDT all-in,")
            appendLine("slippage bawaan, ambang `minNetRrTp1 = 1,5`, `macdHistMinPct = 0,0`.")
            appendLine("Hanya `maxTp1Pct` yang berubah.")
            appendLine()
            appendLine("## Seluruh window (11 pair)")
            appendLine()
            appendLine("| maxTp1Pct | BUY | Trade | Menang | Win rate | Avg net/trade | Total net | Pair yang BUY |")
            appendLine("|---|---|---|---|---|---|---|---|")
            utama.forEach { appendLine(barisTabel(it)) }
            appendLine()
            appendLine("## Ketahanan antar potong window")
            appendLine()
            appendLine("Diukur pada pair yang menghasilkan sinyal (${pairBerSinyal.joinToString(", ")}),")
            appendLine("potongan berurutan: 60% awal dan 40% akhir data.")
            appendLine()
            appendLine("| maxTp1Pct | Potong | BUY | Trade | Avg net/trade | Pair yang BUY |")
            appendLine("|---|---|---|---|---|---|")
            belah.forEach { h ->
                appendLine(
                    "| ${fmt(h.pct)}% | ${h.label} | ${h.buy} | ${h.trade} | " +
                        "${if (h.trade > 0) fmt(h.avgNetPct) + "%" else "—"} | " +
                        "${h.pairBerBuy.ifBlank { "—" }} |"
                )
            }
        }

        val keluar = File(cariRootRepo(), "app/build/intraday-maxtp1-sweep.md")
        keluar.parentFile?.mkdirs()
        keluar.writeText(laporan, Charsets.UTF_8)
        println(laporan)
        assertTrue("Laporan sweep tidak ditulis", keluar.exists())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Perkakas
    // ─────────────────────────────────────────────────────────────────────────

    private fun ukur(
        label: String,
        pct: Double,
        candlePerPair: Map<String, List<CandleBar>>,
        fee: TradingFeeConfig
    ): Hasil {
        var buy = 0
        var trade = 0
        var menang = 0
        var totalNet = 0.0
        val pairBerBuy = mutableListOf<String>()

        candlePerPair.forEach { (pair, candle) ->
            if (candle.size < 25) return@forEach
            val rep = IntradayReplayEngine.replay(
                symbol = pair,
                candles = candle,
                feeConfig = fee,
                config = IntradayConfig(maxTp1Pct = pct)
            )
            if (rep.totalSignalsTriggered > 0) {
                pairBerBuy += "$pair ${rep.totalSignalsTriggered}"
            }
            buy += rep.totalSignalsTriggered
            trade += rep.trades.size
            menang += rep.trades.count { it.netPnlPct >= 0.0 }
            totalNet += rep.trades.sumOf { it.netPnlPct }
        }

        return Hasil(
            label = label,
            pct = pct,
            buy = buy,
            trade = trade,
            menang = menang,
            totalNetPct = totalNet,
            pairBerBuy = pairBerBuy.joinToString(", ")
        )
    }

    private fun barisTabel(h: Hasil): String =
        "| ${h.label} | ${fmt(h.pct)}% | ${h.buy} | ${h.trade} | ${h.menang} | " +
            "${if (h.trade > 0) fmt(h.winRatePct) + "%" else "—"} | " +
            "${if (h.trade > 0) fmt(h.avgNetPct) + "%" else "—"} | " +
            "${if (h.trade > 0) fmt(h.totalNetPct) + "%" else "—"} | " +
            "${h.pairBerBuy.ifBlank { "—" }} |"

    /** 60% awal / 40% akhir. Potongan tetap **berurutan**, bukan acak: replay harus kausal. */
    private fun potong(
        candlePerPair: Map<String, List<CandleBar>>,
        awal: Boolean
    ): Map<String, List<CandleBar>> = candlePerPair.mapValues { (_, candle) ->
        val batas = (candle.size * 0.6).toInt()
        if (awal) candle.take(batas) else candle.drop(batas)
    }

    private fun fmt(x: Double): String = String.format(Locale.US, "%.3f", x)

    private fun muatCandle(berkas: File): List<CandleBar> =
        berkas.readLines().drop(1).filter { it.isNotBlank() }.map { baris ->
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

    private fun cariRootRepo(): File {
        val base = File(System.getProperty("user.dir") ?: ".")
        return listOf(base, File(base, "app"), base.parentFile ?: base, File(base.parentFile ?: base, "app"))
            .firstOrNull { File(it, "data/tokocrypto_v7").isDirectory }
            ?: error("Root repo (berisi data/tokocrypto_v7) tidak ditemukan dari $base")
    }

    private fun cariDirData(): File = File(cariRootRepo(), "data/tokocrypto_v7")
}
