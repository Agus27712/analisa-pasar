package agu.analys.engine.scalping

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dump keputusan ENTRY per bar dari ScalpingMtfEvaluator (Kotlin) untuk dibandingkan
 * dengan tools/parity_dump_entries.py (Python). Test ini TIDAK meng-assert kecocokan;
 * baris PARITY|... dicetak ke stdout dan diambil dari XML test report (system-out).
 *
 * Aturan window HARUS sama dengan parity_dump_entries.py:
 *   m1w  = 250 M1 terakhir s/d bar ini; m15w/h1w = 60 bar terakhir yang CLOSED.
 * Fee Tokocrypto 0,10/0,10 eksplisit (default TradingFeeConfig = 0,21/0,42 bukan Tokocrypto).
 */
class ScalpParityEntryTest {

    private val start = 300
    private val count = 2000

    @Test
    fun dumpEntryDecisionsXrpV3() {
        val root = System.getenv("GITHUB_WORKSPACE")?.let { File(it) }
            ?: File(System.getProperty("user.dir")).parentFile
        val dir = File(root, "data/tokocrypto_v3")
        val m1 = loadCsv(File(dir, "XRPUSDT_1m.csv"))
        val m15 = loadCsv(File(dir, "XRPUSDT_15m.csv"))
        val h1 = loadCsv(File(dir, "XRPUSDT_1h.csv"))
        assertTrue("data M1 kurang", m1.size > start + count)

        val fees = TradingFeeConfig(
            buyMakerPct = 0.10, buyTakerPct = 0.10,
            sellMakerPct = 0.10, sellTakerPct = 0.10
        )
        val m15Ts = m15.map { it.timestamp }
        val h1Ts = h1.map { it.timestamp }

        for (i in start until start + count) {
            val c = m1[i]
            val t = c.timestamp
            val m1w = m1.subList(maxOf(0, i - 249), i + 1)
            val m15w = closedLast(m15, m15Ts, t, 15 * 60_000L, 60)
            val h1w = closedLast(h1, h1Ts, t, 60 * 60_000L, 60)
            val r = ScalpingMtfEvaluator.evaluate(
                price = c.close,
                h1Candles = h1w,
                m15Candles = m15w,
                m1Candles = m1w,
                fees = fees,
                symbol = "XRPUSDT",
                diagnosticIgnoreOrderBookWhenUnavailable = true
            )
            if (r == null) {
                println("PARITY|$t|NULL")
                continue
            }
            val ready = if (r.scalping.direction.name == "LONG") 1 else 0
            val risk = r.scalping.risk
            println(
                "PARITY|$t|$ready|${r.scalping.setup.name}|${r.scalping.score.total}|" +
                    "${fmt(risk?.entryZone?.high)}|${fmt(risk?.stopLoss)}|" +
                    "${fmt(risk?.takeProfit1)}|${fmt(risk?.takeProfit2)}"
            )
        }
    }

    private fun fmt(v: Double?): String = String.format(java.util.Locale.US, "%.6f", v ?: 0.0)

    /** Bar yang sudah CLOSED: t + span <= tNow, ambil [n] terakhir. */
    private fun closedLast(bars: List<CandleBar>, ts: List<Long>, tNow: Long, spanMs: Long, n: Int): List<CandleBar> {
        var lo = 0
        var hi = ts.size
        val limit = tNow - spanMs
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] <= limit) lo = mid + 1 else hi = mid
        }
        return bars.subList(maxOf(0, lo - n), lo)
    }

    private fun loadCsv(file: File): List<CandleBar> {
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
}
