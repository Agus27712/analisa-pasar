package agu.analys.engine.intraday.replay

import agu.analys.config.FeeSchedule
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Penjaga **paket D butir 2–3** di atas dataset riset ter-commit (`data/tokocrypto_v7`,
 * `DOGEUSDT_1h.csv`, 999 bar): laporan replay wajib memisahkan
 * - `LOOKAHEAD_EXPIRY` — trade belum tuntas, **data masih ada**;
 * - `END_OF_DATA` — jendela lookahead terpotong karena **dataset habis**.
 *
 * Keduanya tetap dihitung **kalah** (aturan A5). Yang dijaga di sini bukan labelnya saja, tetapi
 * tiga hal yang bisa rusak tanpa terlihat:
 * 1. label ↔ indeks keluar konsisten (END_OF_DATA hanya di bar terakhir);
 * 2. penghitung laporan sama dengan isi daftar trade (tidak ada trade yang hilang dari hitungan);
 * 3. angka "net" nyata: ada trade yang **gross positif tetapi net negatif** di dataset ini.
 *
 * Uji ini juga berfungsi sebagai kontrol negatif perilaku lama: kalau kode kembali membuang trade
 * yang tidak tuntas, atau menilai menang dari gross, asersi partisi/net gagal.
 */
class IntradayReplayWindowAccountingTest {

    private val feesUsdt = FeeSchedule.configFor("TOKOCRYPTO", "USDT")

    private fun muatCandleH1Riset(pair: String): List<CandleBar> {
        val kandidat = listOf(
            File("data/tokocrypto_v7/${pair}_1h.csv"),
            File("../data/tokocrypto_v7/${pair}_1h.csv"),
            File(System.getProperty("user.dir") ?: ".", "data/tokocrypto_v7/${pair}_1h.csv"),
            File(System.getProperty("user.dir") ?: ".", "../data/tokocrypto_v7/${pair}_1h.csv")
        )
        val berkas = kandidat.firstOrNull { it.isFile }
            ?: error("Dataset riset ${pair}_1h.csv tidak ditemukan dari ${File(".").absolutePath}")
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

    @Test
    fun akhirDataDipisahkanDariTidakTuntas_danKeduanyaDihitungKalah() {
        val candles = muatCandleH1Riset("DOGEUSDT")
        val report = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = candles,
            feeConfig = feesUsdt,
            config = IntradayConfig(minNetRrTp1 = 0.0) // ambang 0,0 supaya dataset ini punya trade
        )

        assertTrue("Dataset harus menghasilkan trade", report.trades.isNotEmpty())
        val indeksTerakhir = candles.size - 1

        // 1. Label ↔ indeks keluar konsisten.
        report.trades.forEach { t ->
            when (t.exitReason) {
                "END_OF_DATA" -> assertEquals(
                    "END_OF_DATA hanya sah bila jendela lookahead berakhir di bar terakhir dataset",
                    indeksTerakhir,
                    t.exitIndex
                )
                "LOOKAHEAD_EXPIRY" -> assertTrue(
                    "LOOKAHEAD_EXPIRY berarti data masih ada setelah bar keluar (exitIndex ${t.exitIndex})",
                    t.exitIndex < indeksTerakhir
                )
            }
        }

        // 2. Penghitung laporan = isi daftar trade.
        val endOfData = report.trades.count { it.exitReason == "END_OF_DATA" }
        val tidakTuntas = report.trades.count { it.exitReason == "LOOKAHEAD_EXPIRY" }
        assertEquals("endOfDataOutcomes harus sama dengan jumlah trade END_OF_DATA", endOfData, report.endOfDataOutcomes)
        assertEquals("unresolvedOutcomes harus sama dengan jumlah trade LOOKAHEAD_EXPIRY", tidakTuntas, report.unresolvedOutcomes)
        assertEquals("unresolvedTotal = tidak tuntas + data habis", endOfData + tidakTuntas, report.unresolvedTotal)
        assertEquals(
            "Penghitung peristiwa lain juga harus cocok dengan daftar trade",
            report.trades.count { it.exitReason == "AMBIGUOUS_SAME_BAR" },
            report.ambiguousOutcomes
        )

        // 3. Partisi lengkap dan menang/kalah dari net — bukan dari peristiwa TP/SL.
        assertEquals(
            "validEntries + falseSignals harus sama dengan jumlah trade",
            report.trades.size,
            report.validEntries + report.falseSignals
        )
        assertEquals(
            "falseSignals harus berisi semua trade net negatif, termasuk yang tidak tuntas",
            report.trades.count { it.netPnlPct < 0.0 },
            report.falseSignals
        )

        // 4. Angka net nyata: ada trade yang tampak untung dari harga tetapi kalah setelah biaya.
        val grossPlusNetMinus = report.trades.filter { it.grossPnlPct > 0.0 && it.netPnlPct < 0.0 }
        assertTrue(
            "Dataset riset ini harus memuat trade gross positif / net negatif; tanpa itu klaim " +
                "'net' tidak teruji di kasusnya",
            grossPlusNetMinus.isNotEmpty()
        )

        // 5. `END_OF_DATA` dibuktikan dengan memotong dataset tepat setelah entry. Histori yang
        //    dibaca evaluator tidak berubah (bar entry tetap sama), jadi sinyalnya identik; yang
        //    berubah hanya jendela lookahead yang kini habis di bar terakhir.
        val kandidat = report.trades.firstOrNull { t ->
            t.exitIndex > t.entryIndex + 1 && t.entryIndex + 2 <= candles.size
        }
        assertTrue(
            "Butuh trade yang bar setelah entry-nya tidak langsung keluar, untuk menguji END_OF_DATA",
            kandidat != null
        )
        val dipotong = candles.subList(0, kandidat!!.entryIndex + 2)
        val reportPotong = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = dipotong,
            feeConfig = feesUsdt,
            config = IntradayConfig(minNetRrTp1 = 0.0)
        )
        val tradePotong = reportPotong.trades.firstOrNull { it.entryIndex == kandidat.entryIndex }
        assertTrue("Entry yang sama harus tetap muncul setelah dataset dipotong", tradePotong != null)
        assertEquals(
            "Jendela lookahead yang terpotong di bar terakhir dataset harus berlabel END_OF_DATA",
            "END_OF_DATA",
            tradePotong!!.exitReason
        )
        assertEquals(
            "END_OF_DATA harus berakhir di bar terakhir dataset yang dipotong",
            dipotong.size - 1,
            tradePotong.exitIndex
        )
        assertEquals("Penghitung END_OF_DATA harus ikut naik", 1, reportPotong.endOfDataOutcomes)
        assertEquals("Tidak boleh ada LOOKAHEAD_EXPIRY di dataset terpotong ini", 0, reportPotong.unresolvedOutcomes)
        assertEquals("unresolvedTotal tetap menghitung trade data habis sebagai kalah", 1, reportPotong.unresolvedTotal)
    }

    @Test
    fun modeExitBawaanTidakMengubahAngkaReplayLama() {
        // Paket E menambahkan mesin exit. Mode bawaan SESSION_CLOSE wajib mereproduksi angka
        // paket A–D: membandingkan replay dengan dan tanpa parameter exitConfig.
        val candles = muatCandleH1Riset("DOGEUSDT")
        val config = IntradayConfig(minNetRrTp1 = 0.0)

        val bawaan = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = candles,
            feeConfig = feesUsdt,
            config = config
        )
        val eksplisit = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = candles,
            feeConfig = feesUsdt,
            config = config,
            exitConfig = agu.analys.engine.intraday.IntradayExitConfig.DEFAULT
        )

        assertEquals("Jumlah trade harus sama", bawaan.trades.size, eksplisit.trades.size)
        assertEquals("Total net harus sama", bawaan.totalNetPnlPct, eksplisit.totalNetPnlPct, 1e-9)
        assertEquals("Win rate harus sama", bawaan.winRatePct, eksplisit.winRatePct, 1e-9)
        // Angka terukur dataset ter-commit `DOGEUSDT_1h.csv` (999 bar) pada 30 Okt 2026 dengan
        // ambang R:R 0,0: 11 BUY, 7 trade. Kalau dataset/config berubah, ukur ulang.
        assertEquals("Sinyal BUY harus tetap 11 (angka terukur dataset ini)", 11, bawaan.totalSignalsTriggered)
        assertEquals("Trade harus tetap 7 (angka terukur dataset ini)", 7, bawaan.trades.size)
        bawaan.trades.zip(eksplisit.trades).forEach { (a, b) ->
            assertEquals("Alasan keluar harus sama", a.exitReason, b.exitReason)
            assertEquals("Harga keluar harus sama", a.exitPrice, b.exitPrice, 1e-9)
            assertEquals("Net per trade harus sama", a.netPnlPct, b.netPnlPct, 1e-9)
        }
    }

    @Test
    fun modeExitNonBawaanBenarBenarDipakaiReplay() {
        // Kalau `exitConfig` hanya diterima tetapi tidak diteruskan ke mesin exit, mode TIME_STOP
        // akan menghasilkan angka identik dengan SESSION_CLOSE dan tidak ada satu pun
        // `TIME_STOP`. Uji ini yang membuktikan penyambungannya, bukan nama parameternya.
        val candles = muatCandleH1Riset("DOGEUSDT")
        val config = IntradayConfig(minNetRrTp1 = 0.0)

        val cepatKeluar = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = candles,
            feeConfig = feesUsdt,
            config = config,
            exitConfig = agu.analys.engine.intraday.IntradayExitConfig(
                mode = agu.analys.engine.intraday.IntradayExitMode.TIME_STOP,
                timeStopBars = 1
            )
        )
        val bawaan = IntradayReplayEngine.replay(
            symbol = "DOGEUSDT",
            candles = candles,
            feeConfig = feesUsdt,
            config = config
        )

        assertTrue(
            "Mode TIME_STOP harus menghasilkan exit TIME_STOP di replay",
            cepatKeluar.trades.any { it.exitReason == "TIME_STOP" }
        )
        assertEquals(
            "Penghitung timeStopOutcomes harus sama dengan jumlah trade TIME_STOP",
            cepatKeluar.trades.count { it.exitReason == "TIME_STOP" },
            cepatKeluar.timeStopOutcomes
        )
        assertEquals(
            "Outcome TIME_STOP tidak boleh dilaporkan sebagai UNRESOLVED",
            cepatKeluar.timeStopOutcomes,
            cepatKeluar.trades.count { it.outcome == agu.analys.engine.intraday.replay.IntradayTradeOutcome.TIME_STOP_EXIT }
        )
        assertTrue(
            "Exit yang lebih cepat harus mengubah hasil net (mode exit benar-benar dipakai)",
            kotlin.math.abs(cepatKeluar.totalNetPnlPct - bawaan.totalNetPnlPct) > 1e-9
        )
    }
}
