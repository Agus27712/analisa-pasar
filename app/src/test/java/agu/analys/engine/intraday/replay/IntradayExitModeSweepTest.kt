package agu.analys.engine.intraday.replay

import agu.analys.config.FeeSchedule
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.engine.intraday.IntradayExitConfig
import agu.analys.engine.intraday.IntradayExitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * **Alat ukur exit intraday (paket E)** — menyapu mode keluar pada dataset riset ter-commit
 * memakai [IntradayReplayEngine] + [agu.analys.engine.intraday.IntradayExitEngine] yang sama
 * dengan aplikasi. Bukan skrip Python: rumus SL/TP/R:R dan mesin exit hidup di Kotlin.
 *
 * Aturan yang dipatuhi (lihat `app/AGENTS.md`):
 * - **Satu variabel berubah**: entry, fee, gate, dan geometri tetap; hanya mode exit.
 * - **Bandingkan net %, bukan R**; WR tinggi dengan net negatif bukan keberhasilan.
 * - **Kontrol `SESSION_CLOSE` wajib** ada di tabel yang sama. Bila kontrol itu tidak menghasilkan
 *   angka yang sama dengan alat replay paket D, hasil sweep tidak boleh dibaca.
 * - **Satu window data bukan bukti edge.** Laporan memuat potongan 60/40 dan walk-forward 4 fold.
 *
 * Hanya jalan bila `RUN_INTRADAY_EXIT_SWEEP=true`.
 *
 * ```
 * RUN_INTRADAY_EXIT_SWEEP=true ./gradlew :app:testDebugUnitTest \
 *   --tests "agu.analys.engine.intraday.replay.IntradayExitModeSweepTest"
 * ```
 *
 * Variabel lingkungan (opsional):
 * - `INTRADAY_EXIT_SWEEP_DATASET` (bawaan `data/tokocrypto_v8`), `..._TF` (bawaan `4h`).
 * - `INTRADAY_EXIT_SWEEP_CAP` (bawaan `8.0` — **config pengukuran**, bukan bawaan app 5,0).
 * - `..._LOOKAHEAD_HOURS` (bawaan `24`), `..._WARMUP` (bawaan `220`), `..._FOLDS` (bawaan `4`).
 * - `..._PAIRS`, `..._LIMIT`, `..._THREADS`.
 *
 * Keluaran: `app/build/intraday-exit-sweep.md`.
 */
class IntradayExitModeSweepTest {

    private data class Varian(
        val label: String,
        val config: IntradayExitConfig
    )

    @Test
    fun `sweep mode exit intraday`() {
        assumeTrue(
            "Sweep exit intraday hanya dijalankan bila diminta (RUN_INTRADAY_EXIT_SWEEP=true)",
            System.getenv("RUN_INTRADAY_EXIT_SWEEP") == "true"
        )

        val h = IntradayResearchHarness
        val namaDataset = h.env("INTRADAY_EXIT_SWEEP_DATASET", "data/tokocrypto_v8")
        val tf = h.env("INTRADAY_EXIT_SWEEP_TF", "4h").lowercase(Locale.ROOT)
        val cap = h.envDouble("INTRADAY_EXIT_SWEEP_CAP", 8.0)
        val jamLookahead = h.envDouble("INTRADAY_EXIT_SWEEP_LOOKAHEAD_HOURS", 24.0)
        val lookaheadBar = h.lookaheadBar(tf, jamLookahead)
        val warmup = h.envInt("INTRADAY_EXIT_SWEEP_WARMUP", 220)
        val jumlahFold = h.envInt("INTRADAY_EXIT_SWEEP_FOLDS", 4)
        val threads = h.envInt("INTRADAY_EXIT_SWEEP_THREADS", Runtime.getRuntime().availableProcessors())
        val limit = h.envInt("INTRADAY_EXIT_SWEEP_LIMIT", 0)
        val filterPair = h.env("INTRADAY_EXIT_SWEEP_PAIRS", "")
            .split(",").map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotBlank() }

        val data = h.muat(namaDataset, tf, filterPair, limit)
        val fee = FeeSchedule.configFor("TOKOCRYPTO", "USDT")
        val feeRoundTripPct = FeeSchedule.roundTripPctMultiplicative("TOKOCRYPTO", "USDT")
        val config = IntradayConfig(maxTp1Pct = cap)

        val varian = listOf(
            Varian("kontrol SESSION_CLOSE", IntradayExitConfig(IntradayExitMode.SESSION_CLOSE)),
            Varian("TP1 break-even", IntradayExitConfig(IntradayExitMode.TP1_BREAKEVEN)),
            Varian("trail ATR 1,0× (arm TP1)", IntradayExitConfig(IntradayExitMode.TRAIL_ATR, trailAtrMult = 1.0)),
            Varian("trail ATR 1,5× (arm TP1)", IntradayExitConfig(IntradayExitMode.TRAIL_ATR, trailAtrMult = 1.5)),
            Varian("trail ATR 1,0× (arm entry)", IntradayExitConfig(IntradayExitMode.TRAIL_ATR, trailAtrMult = 1.0, armTrailAfterTp1 = false)),
            Varian("time-stop 3 bar", IntradayExitConfig(IntradayExitMode.TIME_STOP, timeStopBars = 3)),
            Varian("time-stop 6 bar", IntradayExitConfig(IntradayExitMode.TIME_STOP, timeStopBars = 6)),
            Varian("time-stop 12 bar", IntradayExitConfig(IntradayExitMode.TIME_STOP, timeStopBars = 12))
        )

        println(
            "Sweep exit: dataset=$namaDataset tf=$tf pair=${data.pairs.size} cap=${h.fmt(cap)}% " +
                "lookahead=$lookaheadBar bar (${jamLookahead.toInt()} jam) warmup=$warmup fold=$jumlahFold"
        )

        val pool = Executors.newFixedThreadPool(maxOf(1, threads)) { r ->
            Thread(r, "intraday-exit-sweep").apply { isDaemon = true }
        }

        /** hasil[varian] = daftar PairHasil */
        val hasilPerVarian = LinkedHashMap<String, List<IntradayResearchHarness.PairHasil>>()
        try {
            // Kontrol dijalankan pada SELURUH pair. Entry tidak berubah antar varian, jadi pair
            // tanpa BUY di kontrol tidak mungkin punya trade di varian lain — varian berikutnya
            // hanya me-replay pair yang benar-benar bersinyal. Invarian BUY identik diperiksa di
            // bawah, supaya penghematan waktu ini tidak menjadi asumsi tersembunyi.
            val kontrolConfig = varian.first()
            val tugasKontrol = data.pairs.map { pair ->
                pool.submit<IntradayResearchHarness.PairHasil> {
                    h.replayPair(pair, fee, config, kontrolConfig.config, lookaheadBar, warmup)
                }
            }
            val hasilKontrol = tugasKontrol.map { it.get() }
            hasilPerVarian[kontrolConfig.label] = hasilKontrol
            val pairAktif = hasilKontrol.filter { it.buy > 0 }.map { it.pair }
            val tKontrol = h.agregat(hasilKontrol)
            println("${kontrolConfig.label}: BUY=${tKontrol.buy} trade=${tKontrol.trade} pairAktif=$pairAktif")

            for (v in varian.drop(1)) {
                val dataAktif = data.pairs.filter { it.pair in pairAktif }
                val tugas = dataAktif.map { pair ->
                    pool.submit<IntradayResearchHarness.PairHasil> {
                        h.replayPair(pair, fee, config, v.config, lookaheadBar, warmup)
                    }
                }
                hasilPerVarian[v.label] = tugas.map { it.get() }
                val t = h.agregat(hasilPerVarian.getValue(v.label))
                println("${v.label}: BUY=${t.buy} trade=${t.trade} avgNet=${h.fmt(t.avgNetPct)}%")
            }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }

        // Kontrol wajib ada dan seluruh trade punya alasan keluar yang dikenal.
        val kontrol = hasilPerVarian.getValue("kontrol SESSION_CLOSE")
        assertTrue("Kontrol SESSION_CLOSE tidak berjalan", kontrol.isNotEmpty())

        // Invarian: mode exit TIDAK boleh mengubah entry (BUY) satu bar pun.
        hasilPerVarian.forEach { (label, hasil) ->
            if (label == "kontrol SESSION_CLOSE") return@forEach
            hasil.forEach { p ->
                val k = kontrol.firstOrNull { it.pair == p.pair }
                assertTrue("Pair ${p.pair} tidak ada di kontrol", k != null)
                assertEquals(
                    "Mode exit '$label' mengubah sinyal BUY di ${p.pair} — entry harus tetap",
                    k!!.buyIndices,
                    p.buyIndices
                )
            }
        }
        hasilPerVarian.values.flatten().flatMap { it.trades }.forEach { t ->
            assertTrue(
                "Alasan keluar tak dikenal: ${t.exitReason}",
                t.exitReason in setOf(
                    "TP1_HIT", "TP2_HIT", "STOP_LOSS_HIT", "TRAIL_STOP_HIT",
                    "CLOSE_MALAM_SESSION_EXIT", "TIME_STOP", "AMBIGUOUS_SAME_BAR",
                    "LOOKAHEAD_EXPIRY", "END_OF_DATA"
                )
            )
        }

        // ── Irisan: window penuh, potongan 60/40, walk-forward ──────────────
        fun batas(pair: String, irisan: String): Pair<Int, Int> {
            val n = data.pairs.first { it.pair == pair }.utama.size
            return when {
                irisan == "window penuh" -> 0 to n
                irisan == "awal 60%" -> 0 to (n * 0.6).toInt()
                irisan == "akhir 40%" -> (n * 0.6).toInt() to n
                irisan.startsWith("fold ") -> {
                    val f = irisan.removePrefix("fold ").substringBefore('/').toInt() - 1
                    val lebar = (n - warmup).toDouble() / jumlahFold
                    val a = warmup + (lebar * f).toInt()
                    val b = if (f == jumlahFold - 1) n else warmup + (lebar * (f + 1)).toInt()
                    a to b
                }
                else -> error("irisan tidak dikenal: $irisan")
            }
        }

        val daftarIrisan = buildList {
            add("window penuh")
            add("awal 60%")
            add("akhir 40%")
            for (f in 1..jumlahFold) add("fold $f/$jumlahFold")
        }

        fun irisanHasil(hasil: List<IntradayResearchHarness.PairHasil>, nama: String): IntradayResearchHarness.Agregat {
            val tersaring = hasil.map { p ->
                val (a, b) = batas(p.pair, nama)
                p.copy(
                    buyIndices = p.buyIndices.filter { it >= a && it < b },
                    trades = p.trades.filter { it.entryIndex >= a && it.entryIndex < b }
                )
            }
            return h.agregat(tersaring)
        }

        val laporan = buildString {
            appendLine("# Sweep mode exit intraday — `$namaDataset` ($tf, ${data.pairs.size} pair)")
            appendLine()
            appendLine("Dihasilkan `IntradayExitModeSweepTest` (paket E). Entry, fee, gate, dan geometri")
            appendLine("**sama** di semua baris; hanya aturan keluar yang berubah. Fee Tokocrypto USDT all-in")
            appendLine("(round-trip **${h.fmt(feeRoundTripPct)}%**, slippage ${h.fmt(FeeSchedule.DEFAULT_SLIPPAGE_PCT)}%/sisi).")
            appendLine("`maxTp1Pct = ${h.fmt(cap)}%` adalah **config pengukuran**, bukan bawaan app")
            appendLine("(bawaan tetap ${h.fmt(IntradayConfig.DEFAULT.maxTp1Pct)}%). `minNetRrTp1 = ${h.fmt(config.minNetRrTp1)}`,")
            appendLine("lookahead **$lookaheadBar bar** (${jamLookahead.toInt()} jam), pemanasan $warmup bar.")
            appendLine()
            appendLine("## Window penuh")
            appendLine()
            appendLine("| Varian exit | BUY | Trade | Menang | Win rate | Avg net/trade | Total net | Tidak tuntas | Data habis | Gross+ / net− |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            hasilPerVarian.forEach { (label, hasil) ->
                val t = h.agregat(hasil)
                appendLine(
                    "| $label | ${t.buy} | ${t.trade} | ${t.menang} | " +
                        "${if (t.trade > 0) h.fmt(t.winRatePct) + "%" else "—"} | " +
                        "${if (t.trade > 0) h.fmt(t.avgNetPct) + "%" else "—"} | " +
                        "${if (t.trade > 0) h.fmt(t.totalNetPct) + "%" else "—"} | " +
                        "${t.unresolved} | ${t.endOfData} | ${t.grossPlusNetMinus} |"
                )
            }
            appendLine()
            appendLine("## Potongan window dan walk-forward (avg net/trade)")
            appendLine()
            appendLine("| Varian exit | Awal 60% | Akhir 40% | " +
                (1..jumlahFold).joinToString(" | ") { "Fold $it" } + " |")
            appendLine("|---|---|---|" + "---|".repeat(jumlahFold))
            hasilPerVarian.forEach { (label, hasil) ->
                val sel = daftarIrisan.filter { it != "window penuh" }.joinToString(" | ") { nama ->
                    val t = irisanHasil(hasil, nama)
                    if (t.trade > 0) "${h.fmt(t.avgNetPct)}% (${t.trade})" else "—"
                }
                appendLine("| $label | $sel |")
            }
            appendLine()
            appendLine("## Distribusi alasan keluar (window penuh)")
            appendLine()
            appendLine("| Varian exit | Alasan | Jumlah |")
            appendLine("|---|---|---|")
            hasilPerVarian.forEach { (label, hasil) ->
                hasil.flatMap { it.trades }.groupingBy { it.exitReason }.eachCount().toSortedMap()
                    .forEach { (alasan, jumlah) -> appendLine("| $label | $alasan | $jumlah |") }
            }
            appendLine()
            appendLine("## Batas pengukuran")
            appendLine()
            appendLine("1. Hanya USDT; pair IDR belum terukur. Satu window data (v8 H4) — bukan bukti edge.")
            appendLine("2. BUY tidak berubah antar varian: entry tidak tersentuh (diperiksa per pair).")
            appendLine("   Varian selain kontrol hanya me-replay pair yang punya BUY di kontrol; pair lain")
            appendLine("   tidak bisa menghasilkan trade karena entry-nya sama. Jumlah pair yang di-replay")
            appendLine("   tercatat di baris log run, bukan diasumsikan.")
            appendLine("3. `TIME_STOP` dihitung dalam **jumlah bar** (H4 = 4 jam/bar), bukan jam.")
            appendLine("4. `TRAIL_ATR` memakai ATR14 saat entry; stop hanya naik, tidak pernah turun.")
            appendLine("5. `TP1_BREAKEVEN` menahan posisi setelah TP1 dengan stop di harga impas net;")
            appendLine("   exit-nya muncul sebagai `TRAIL_STOP_HIT` (bisa untung, bisa rugi tipis).")
            appendLine("6. Kriteria kandidat ditetapkan sebelum hasil dibaca: net/trade > 0, lebih baik dari")
            appendLine("   kontrol di semua pair, CI 95% tidak menyentuh nol, sampel >= 60 trade/pair.")
            appendLine("   Tanpa itu, hasil ini **diagnostik** dan tidak boleh disambungkan ke app.")
        }

        val keluar = File(h.rootRepo(), "app/build/intraday-exit-sweep.md")
        keluar.parentFile?.mkdirs()
        keluar.writeText(laporan, Charsets.UTF_8)
        println(laporan)
        assertTrue("Laporan sweep exit tidak ditulis", keluar.exists() && keluar.length() > 0)
    }
}
