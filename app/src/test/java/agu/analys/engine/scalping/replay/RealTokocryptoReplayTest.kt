package agu.analys.engine.scalping.replay

import agu.analys.config.MarketDataSource
import agu.analys.model.CandleBar
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uji replay kausal pada candle NYATA Tokocrypto (API resmi Tokocrypto, tanpa fallback Binance).
 *
 * Hanya jalan jika RUN_REAL_TOKOCRYPTO_REPLAY=true (dipakai workflow manual GitHub Actions
 * scope "real-tokocrypto-replay"). Selain itu test ini dilewati.
 *
 * Env opsional:
 * - REAL_PAIRS      : daftar pair dipisah koma, format BTCUSDT (default: 8 pair USDT utama)
 * - REAL_M1_PAGES   : jumlah halaman M1 @1000 candle (default 5 ≈ 3,5 hari)
 * - REAL_LOOKAHEAD  : time stop (bar M1) (default 30)
 * - REAL_SLIPPAGE_PCT : slippage per sisi pada SIMULASI biaya (default 0.08). Geometri SL/TP engine
 *                     tetap dihitung dengan slippage default engine (0.08) — ini uji sensitivitas biaya eksekusi.
 * - REAL_DATA_DIR   : folder CSV hasil tools/fetch_tokocrypto_klines.py (mode OFFLINE, tanpa internet).
 *                     Dipakai karena Tokocrypto memblokir IP luar negeri (HTTP 451) termasuk runner GitHub.
 *                     Path relatif dihitung dari root repo.
 *
 * Laporan ditulis ke app/build/real-data/real-tokocrypto-report.md
 */
class RealTokocryptoReplayTest {

    private val base = "https://www.tokocrypto.site/api/v3"
    private val notes = mutableListOf<String>()

    @Test
    fun `real Tokocrypto replay menghasilkan laporan kausal`() {
        assumeTrue(
            "Replay data nyata Tokocrypto hanya dijalankan oleh workflow manual",
            System.getenv("RUN_REAL_TOKOCRYPTO_REPLAY") == "true"
        )

        val pairs = (System.getenv("REAL_PAIRS")?.takeIf { it.isNotBlank() }
            ?: "BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT,DOGEUSDT,ADAUSDT,TRXUSDT")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val m1Pages = System.getenv("REAL_M1_PAGES")?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val lookahead = System.getenv("REAL_LOOKAHEAD")?.toIntOrNull()?.coerceIn(5, 120) ?: 30
        val fees = MarketDataSource.TOKOCRYPTO.defaultFeeConfig
        val dataDir = System.getenv("REAL_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { resolveDir(it) }
        if (dataDir != null) notes += "Mode OFFLINE: candle dibaca dari ${dataDir.path} (hasil unduhan dari jaringan Indonesia)."

        val slippage = System.getenv("REAL_SLIPPAGE_PCT")?.toDoubleOrNull()?.coerceIn(0.0, 1.0)
            ?: agu.analys.engine.scalping.ScalpingRiskEngine.DEFAULT_SLIPPAGE_PCT
        val analyzer = RealDataReplayAnalyzer(fees = fees, slippagePct = slippage, lookaheadBars = lookahead)
        val results = mutableListOf<RealDataReplayAnalyzer.PairResult>()
        val m1ByPair = linkedMapOf<String, List<CandleBar>>()
        var failure: Throwable? = null

        try {
            val now = System.currentTimeMillis()
            for (sym in pairs) {
                val m1: List<CandleBar>
                val m15: List<CandleBar>
                val h1: List<CandleBar>
                if (dataDir != null) {
                    m1 = loadCsv(File(dataDir, "${sym}_1m.csv"))
                    val ref = (m1.lastOrNull()?.timestamp ?: 0L) + 60_000L
                    m15 = loadCsv(File(dataDir, "${sym}_15m.csv")).filter { it.timestamp + 15 * 60_000L <= ref }
                    h1 = loadCsv(File(dataDir, "${sym}_1h.csv")).filter { it.timestamp + 60 * 60_000L <= ref }
                } else {
                    m1 = fetchKlines(sym, "1m", 60_000L, m1Pages, now)
                    m15 = fetchKlines(sym, "15m", 15 * 60_000L, 1, now)
                    h1 = fetchKlines(sym, "1h", 60 * 60_000L, 1, now)
                }
                println("[$sym] M1=${m1.size} M15=${m15.size} H1=${h1.size}")
                if (m1.size < 300 || m15.size < 20 || h1.size < 20) {
                    notes += "$sym dilewati: data kurang (M1=${m1.size}, M15=${m15.size}, H1=${h1.size})."
                    continue
                }
                m1ByPair[sym] = m1
                results += analyzer.analyzePair(sym, m1, m15, h1)
            }

            val allTrades = results.flatMap { it.trades }
            val baseline = analyzer.baseline(m1ByPair, allTrades)
            val report = analyzer.buildReport(
                title = "Replay data nyata Tokocrypto — engine scalping (provisional)",
                params = mapOf(
                    "Sumber" to (if (dataDir != null) "CSV hasil unduhan Tokocrypto (type 1), tanpa fallback Binance"
                        else "Tokocrypto (type 1 market data), tanpa fallback Binance"),
                    "Pair" to pairs.joinToString(", "),
                    "Halaman M1 (x1000)" to m1Pages.toString(),
                    "Time stop (bar M1)" to lookahead.toString(),
                    "Fee Tokocrypto (buy/sell taker)" to "${fees.buyTakerPct}% / ${fees.sellTakerPct}%",
                    "Slippage per sisi (simulasi biaya)" to "$slippage%",
                    "Slippage geometri engine" to "${agu.analys.engine.scalping.ScalpingRiskEngine.DEFAULT_SLIPPAGE_PCT}% (tetap)"
                ),
                pairs = results,
                baselineNet = baseline,
                fetchNotes = notes
            )
            writeReport(report)
            println(report)
            assertTrue(
                "Tidak ada pair yang datanya cukup. Lihat catatan: ${notes.joinToString(" | ")}",
                results.isNotEmpty()
            )
        } catch (t: Throwable) {
            failure = t
            if (!File(reportPath()).exists()) {
                writeReport("# Replay data nyata Tokocrypto — GAGAL\n\n- Error: ${t::class.simpleName}: ${t.message}\n" +
                    notes.joinToString("\n") { "- $it" })
            }
            throw t
        } finally {
            if (failure != null) println("Replay gagal: ${failure.message}")
        }
    }

    // ---------------------------------------------------------------------
    // Pengambilan klines (paginasi mundur lewat endTime)
    // ---------------------------------------------------------------------
    private fun fetchKlines(symbol: String, interval: String, intervalMs: Long, pages: Int, now: Long): List<CandleBar> {
        val byTime = linkedMapOf<Long, CandleBar>()
        var endTime: Long? = null
        for (page in 1..pages) {
            val url = "$base/klines?symbol=$symbol&interval=$interval&limit=1000" +
                (endTime?.let { "&endTime=$it" } ?: "")
            val body = httpGet(url, "$symbol $interval hal.$page") ?: break
            val rows = parseKlines(body)
            if (rows.isEmpty()) {
                notes += "$symbol $interval hal.$page: respons kosong/format tak dikenal (${body.take(120).replace("\n", " ")})"
                break
            }
            val before = byTime.size
            rows.forEach { byTime[it.timestamp] = it }
            if (byTime.size == before) break
            endTime = rows.minOf { it.timestamp } - 1
            Thread.sleep(150)
        }
        return byTime.values.sortedBy { it.timestamp }.filter { it.timestamp + intervalMs <= now }
    }

    private fun httpGet(url: String, label: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TokoClient/3.5")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                notes += "$label: HTTP $code ${body.take(100).replace("\n", " ")}"
                null
            } else body
        } catch (e: Exception) {
            notes += "$label: ${e::class.simpleName}: ${e.message}"
            null
        }
    }

    private val rowRegex = Regex(
        "\\[\\s*(\\d{10,})\\s*,\\s*\"?([-\\d.eE+]+)\"?\\s*,\\s*\"?([-\\d.eE+]+)\"?\\s*,\\s*\"?([-\\d.eE+]+)\"?\\s*,\\s*\"?([-\\d.eE+]+)\"?\\s*,\\s*\"?([-\\d.eE+]+)\"?"
    )

    private fun parseKlines(body: String): List<CandleBar> =
        rowRegex.findAll(body).mapNotNull { m ->
            val g = m.groupValues
            val ts = g[1].toLongOrNull() ?: return@mapNotNull null
            val o = g[2].toDoubleOrNull() ?: return@mapNotNull null
            val h = g[3].toDoubleOrNull() ?: return@mapNotNull null
            val l = g[4].toDoubleOrNull() ?: return@mapNotNull null
            val c = g[5].toDoubleOrNull() ?: return@mapNotNull null
            val v = g[6].toDoubleOrNull() ?: return@mapNotNull null
            if (c <= 0.0) null else CandleBar(timestamp = ts, open = o, high = h, low = l, close = c, volume = v)
        }.toList()

    private fun resolveDir(path: String): File {
        val f = File(path)
        if (f.isAbsolute) return f
        val root = System.getenv("GITHUB_WORKSPACE")?.let { File(it) }
            ?: File(System.getProperty("user.dir")).parentFile
        return File(root, path)
    }

    /** CSV: open_time_ms,open,high,low,close,volume (header di baris pertama). */
    private fun loadCsv(file: File): List<CandleBar> {
        if (!file.exists()) {
            notes += "File tidak ada: ${file.name}"
            return emptyList()
        }
        return file.readLines().drop(1).mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 6) return@mapNotNull null
            val ts = p[0].trim().toLongOrNull() ?: return@mapNotNull null
            val o = p[1].toDoubleOrNull() ?: return@mapNotNull null
            val h = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val l = p[3].toDoubleOrNull() ?: return@mapNotNull null
            val c = p[4].toDoubleOrNull() ?: return@mapNotNull null
            val v = p[5].toDoubleOrNull() ?: return@mapNotNull null
            if (c <= 0.0) null else CandleBar(timestamp = ts, open = o, high = h, low = l, close = c, volume = v)
        }.sortedBy { it.timestamp }
    }

    private fun reportPath() = File(System.getProperty("user.dir"), "build/real-data/real-tokocrypto-report.md").path

    private fun writeReport(text: String) {
        val f = File(reportPath())
        f.parentFile.mkdirs()
        f.writeText(text)
    }
}
