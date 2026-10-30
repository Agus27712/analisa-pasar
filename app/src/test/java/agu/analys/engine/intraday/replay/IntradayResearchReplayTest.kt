package agu.analys.engine.intraday.replay

import agu.analys.config.FeeSchedule
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.engine.intraday.IntradayExitConfig
import agu.analys.engine.intraday.IntradayExitMode
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * **Alat replay/ukur intraday (paket D butir 1–2)** — menjalankan replay satu konfigurasi pada
 * dataset riset ter-commit dan menulis laporan angka, bukan sekadar lulus/gagal.
 *
 * Bedanya dengan [IntradayMaxTp1SweepTest] (v7, H1, tanpa D1) dan
 * [IntradayMaxTp1SweepV8Test] (v8, H4, sweep cap):
 * - alat ini **satu konfigurasi** penuh per pair, jadi bisa dipakai untuk melaporkan angka
 *   keadaan sekarang (termasuk mode exit paket E) tanpa menyapu parameter;
 * - laporan memisahkan **`LOOKAHEAD_EXPIRY`** (data masih ada, trade belum tuntas) dari
 *   **`END_OF_DATA`** (jendela lookahead terpotong karena dataset habis) — paket D butir 2;
 * - setiap baris pair memuat `gross+ / net−` (trade yang tampak untung dari harga tetapi kalah
 *   setelah fee + slippage), sehingga klaim "net" bisa diperiksa;
 * - invarian akuntansi diperiksa di dalam alat: partisi trade lengkap dan setiap alasan keluar
 *   dihitung tepat sekali.
 *
 * Hanya jalan bila `RUN_INTRADAY_RESEARCH_REPLAY=true`; tanpa env itu test **dilewati** supaya
 * suite harian tetap cepat.
 *
 * ```
 * RUN_INTRADAY_RESEARCH_REPLAY=true ./gradlew :app:testDebugUnitTest \
 *   --tests "agu.analys.engine.intraday.replay.IntradayResearchReplayTest"
 * ```
 *
 * Variabel lingkungan (opsional):
 * - `INTRADAY_REPLAY_DATASET` (bawaan `data/tokocrypto_v8`), `INTRADAY_REPLAY_TF` (bawaan `4h`).
 * - `INTRADAY_REPLAY_CAP` (bawaan `5.0` = bawaan `IntradayConfig`).
 * - `INTRADAY_REPLAY_LOOKAHEAD_HOURS` (bawaan `24`), `INTRADAY_REPLAY_WARMUP` (bawaan `220`).
 * - `INTRADAY_REPLAY_EXIT_MODE` (`SESSION_CLOSE`/`TP1_BREAKEVEN`/`TRAIL_ATR`/`TIME_STOP`),
 *   `INTRADAY_REPLAY_TIME_STOP_BARS` (bawaan `6`), `INTRADAY_REPLAY_TRAIL_ATR_MULT` (bawaan `1.0`).
 * - `INTRADAY_REPLAY_PAIRS`, `INTRADAY_REPLAY_LIMIT`, `INTRADAY_REPLAY_THREADS`.
 *
 * Keluaran: `app/build/intraday-research-replay.md`.
 */
class IntradayResearchReplayTest {

    @Test
    fun `replay riset intraday satu konfigurasi`() {
        assumeTrue(
            "Alat replay riset hanya dijalankan bila diminta (RUN_INTRADAY_RESEARCH_REPLAY=true)",
            System.getenv("RUN_INTRADAY_RESEARCH_REPLAY") == "true"
        )

        val h = IntradayResearchHarness
        val namaDataset = h.env("INTRADAY_REPLAY_DATASET", "data/tokocrypto_v8")
        val tf = h.env("INTRADAY_REPLAY_TF", "4h").lowercase(Locale.ROOT)
        val cap = h.envDouble("INTRADAY_REPLAY_CAP", IntradayConfig.DEFAULT.maxTp1Pct)
        val jamLookahead = h.envDouble("INTRADAY_REPLAY_LOOKAHEAD_HOURS", 24.0)
        val lookaheadBar = h.lookaheadBar(tf, jamLookahead)
        val warmup = h.envInt("INTRADAY_REPLAY_WARMUP", 220)
        val threads = h.envInt("INTRADAY_REPLAY_THREADS", Runtime.getRuntime().availableProcessors())
        val limit = h.envInt("INTRADAY_REPLAY_LIMIT", 0)
        val filterPair = h.env("INTRADAY_REPLAY_PAIRS", "")
            .split(",").map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotBlank() }

        val mode = runCatching {
            IntradayExitMode.valueOf(h.env("INTRADAY_REPLAY_EXIT_MODE", "SESSION_CLOSE").uppercase(Locale.ROOT))
        }.getOrElse { error("INTRADAY_REPLAY_EXIT_MODE tidak dikenal: ${it.message}") }
        val exitConfig = IntradayExitConfig(
            mode = mode,
            timeStopBars = h.envInt("INTRADAY_REPLAY_TIME_STOP_BARS", 6),
            trailAtrMult = h.envDouble("INTRADAY_REPLAY_TRAIL_ATR_MULT", 1.0)
        )

        val data = h.muat(namaDataset, tf, filterPair, limit)
        val fee = FeeSchedule.configFor("TOKOCRYPTO", "USDT")
        val feeRoundTripPct = FeeSchedule.roundTripPctMultiplicative("TOKOCRYPTO", "USDT")
        val config = IntradayConfig(maxTp1Pct = cap)

        println(
            "Replay riset: dataset=$namaDataset tf=$tf pair=${data.pairs.size} cap=${h.fmt(cap)}% " +
                "exit=$mode lookahead=$lookaheadBar bar (${jamLookahead.toInt()} jam) warmup=$warmup"
        )

        val pool = Executors.newFixedThreadPool(maxOf(1, threads)) { r ->
            Thread(r, "intraday-replay-riset").apply { isDaemon = true }
        }
        val hasil = try {
            data.pairs.map { pair ->
                pool.submit<IntradayResearchHarness.PairHasil> {
                    h.replayPair(pair, fee, config, exitConfig, lookaheadBar, warmup)
                }
            }.map { it.get() }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }

        val total = h.agregat(hasil)
        val semuaTrade = hasil.flatMap { it.trades }

        // ── Invarian akuntansi (gagal = laporan tidak sah) ───────────────────
        val alasanDikenal = setOf(
            "TP1_HIT", "TP2_HIT", "STOP_LOSS_HIT", "TRAIL_STOP_HIT", "CLOSE_MALAM_SESSION_EXIT",
            "TIME_STOP", "AMBIGUOUS_SAME_BAR", "LOOKAHEAD_EXPIRY", "END_OF_DATA"
        )
        semuaTrade.forEach { t ->
            assertTrue("Alasan keluar tak dikenal: ${t.exitReason}", t.exitReason in alasanDikenal)
        }
        assertTrue(
            "Setiap trade harus masuk tepat satu alasan keluar",
            semuaTrade.size == hasil.sumOf { pair -> pair.perAlasan().values.sum() }
        )
        assertTrue(
            "Trade tidak tuntas + data habis harus sama dengan jumlah trade ber-alasan itu",
            total.unresolved + total.endOfData ==
                semuaTrade.count { it.exitReason == "LOOKAHEAD_EXPIRY" || it.exitReason == "END_OF_DATA" }
        )

        val laporan = buildString {
            appendLine("# Replay riset intraday — `$namaDataset` ($tf, ${hasil.size} pair)")
            appendLine()
            appendLine("Dihasilkan `IntradayResearchReplayTest` (alat ukur paket D). Fee Tokocrypto USDT")
            appendLine("all-in (round-trip **${h.fmt(feeRoundTripPct)}%**, slippage ${h.fmt(FeeSchedule.DEFAULT_SLIPPAGE_PCT)}%/sisi).")
            appendLine("Konfigurasi: `maxTp1Pct = ${h.fmt(cap)}%`, `minNetRrTp1 = ${h.fmt(config.minNetRrTp1)}`,")
            appendLine("`macdHistMinPct = ${h.fmt(config.macdHistMinPct)}`, exit **$mode**, lookahead **$lookaheadBar bar**")
            appendLine("(${jamLookahead.toInt()} jam), pemanasan $warmup bar, sesi Close Malam aktif.")
            appendLine()
            if (data.tanpaD1.isNotEmpty()) {
                appendLine("Tanpa candle D1 (veto anomali makro tidak aktif): ${data.tanpaD1.joinToString(", ")}")
                appendLine()
            }
            appendLine("## Per pair")
            appendLine()
            appendLine("| Pair | Bar | BUY | Trade | Menang | Win rate | Avg net/trade | Total net | Tidak tuntas | Data habis | Gross+ / net− |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
            hasil.sortedBy { it.pair }.forEach { r ->
                val t = h.agregat(listOf(r))
                appendLine(
                    "| ${r.pair} | ${r.bar} | ${r.buy} | ${t.trade} | ${t.menang} | " +
                        "${if (t.trade > 0) h.fmt(t.winRatePct) + "%" else "—"} | " +
                        "${if (t.trade > 0) h.fmt(t.avgNetPct) + "%" else "—"} | " +
                        "${if (t.trade > 0) h.fmt(t.totalNetPct) + "%" else "—"} | " +
                        "${t.unresolved} | ${t.endOfData} | ${t.grossPlusNetMinus} |"
                )
            }
            appendLine()
            appendLine("## Total")
            appendLine()
            appendLine("| Pair | BUY | Trade | Menang | Win rate | Avg net/trade | Total net | Tidak tuntas | Data habis | Gross+ / net− |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            appendLine(
                "| ${total.pair} | ${total.buy} | ${total.trade} | ${total.menang} | " +
                    "${if (total.trade > 0) h.fmt(total.winRatePct) + "%" else "—"} | " +
                    "${if (total.trade > 0) h.fmt(total.avgNetPct) + "%" else "—"} | " +
                    "${if (total.trade > 0) h.fmt(total.totalNetPct) + "%" else "—"} | " +
                    "${total.unresolved} | ${total.endOfData} | ${total.grossPlusNetMinus} |"
            )
            appendLine()
            appendLine("Pair yang menghasilkan BUY: ${total.pairBerBuy.ifBlank { "—" }}")
            appendLine()
            appendLine("## Distribusi alasan keluar")
            appendLine()
            appendLine("| Alasan | Jumlah |")
            appendLine("|---|---|")
            semuaTrade.groupingBy { it.exitReason }.eachCount().toSortedMap()
                .forEach { (alasan, jumlah) -> appendLine("| $alasan | $jumlah |") }
            appendLine()
            appendLine("## Batas pengukuran")
            appendLine()
            appendLine("1. Hanya USDT. Pair IDR (biaya all-in lebih murah) belum terukur di alat ini.")
            appendLine("2. Dataset v8 memuat pair pendek (PEPEUSDT 2.999 bar, BANKUSDT 2.098, CHIPUSDT 1.144);")
            appendLine("   fold/window per pair memakai batasnya sendiri, jadi jumlah bar tidak seragam.")
            appendLine("3. Candle D1 dipotong `timestamp <= waktu evaluasi` (perilaku engine & app); bar D1 hari")
            appendLine("   berjalan ikut terpakai. Ini batas yang sama dengan app, bukan diperbaiki diam-diam.")
            appendLine("4. Replay memakai jendela histori sampai 301 bar; app mengoper 200 candle H4.")
            appendLine("5. `END_OF_DATA` berarti jendela lookahead terpotong karena dataset habis — **bukan**")
            appendLine("   trade yang tidak tuntas. Keduanya tetap dihitung kalah (aturan A5).")
            appendLine("6. Angka ini satu window data; **bukan** bukti edge dan bukan dasar uang riil.")
        }

        val keluar = File(h.rootRepo(), "app/build/intraday-research-replay.md")
        keluar.parentFile?.mkdirs()
        keluar.writeText(laporan, Charsets.UTF_8)
        println(laporan)
        assertTrue("Laporan replay riset tidak ditulis", keluar.exists() && keluar.length() > 0)
    }
}
