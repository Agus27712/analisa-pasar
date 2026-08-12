package agu.analys.engine

import agu.analys.model.CandleBar
import kotlin.math.abs

/**
 * Learning-only market structure derived from real candles supplied by INDODAX.
 * It never creates or substitutes market values.
 */
data class MarketStructureSnapshot(
    val trend: String,
    val trendExplanation: String,
    val lastSwingHigh: Double?,
    val lastSwingLow: Double?,
    val support: Double?,
    val resistance: Double?,
    val supportDistancePct: Double?,
    val resistanceDistancePct: Double?,
    val structureExplanation: String,
    val dataEnough: Boolean,
    val nextSupport: Double? = null,
    val nextResistance: Double? = null
)

object MarketStructureAnalyzer {
    private const val LEVEL_CLUSTER_PCT = 0.0075

    fun analyze(candles: List<CandleBar>): MarketStructureSnapshot {
        if (candles.size < 12) {
            return MarketStructureSnapshot(
                trend = "Belum cukup data",
                trendExplanation = "Minimal 12 candle diperlukan untuk latihan membaca struktur pasar.",
                lastSwingHigh = null,
                lastSwingLow = null,
                support = null,
                resistance = null,
                supportDistancePct = null,
                resistanceDistancePct = null,
                structureExplanation = "Belum ada level yang ditampilkan agar aplikasi tidak mengarang level pasar.",
                dataEnough = false
            )
        }

        val recent = candles.takeLast(60)
        val swingHighs = mutableListOf<Double>()
        val swingLows = mutableListOf<Double>()
        for (i in 2 until recent.lastIndex - 1) {
            val c = recent[i]
            if (c.high >= recent[i - 1].high && c.high >= recent[i - 2].high &&
                c.high >= recent[i + 1].high && c.high >= recent[i + 2].high) swingHighs += c.high
            if (c.low <= recent[i - 1].low && c.low <= recent[i - 2].low &&
                c.low <= recent[i + 1].low && c.low <= recent[i + 2].low) swingLows += c.low
        }

        val last = recent.last().close
        val highs = swingHighs.takeLast(2)
        val lows = swingLows.takeLast(2)
        val trend = when {
            highs.size >= 2 && lows.size >= 2 && highs[1] > highs[0] && lows[1] > lows[0] -> "Bullish structure"
            highs.size >= 2 && lows.size >= 2 && highs[1] < highs[0] && lows[1] < lows[0] -> "Bearish structure"
            else -> "Range / transition"
        }

        val supportLevels = clusterLevels(swingLows.filter { it <= last }.sortedDescending())
        val resistanceLevels = clusterLevels(swingHighs.filter { it >= last }.sorted())
        val support = supportLevels.firstOrNull() ?: clusterLevels(swingLows.sortedDescending()).firstOrNull()
        val resistance = resistanceLevels.firstOrNull() ?: clusterLevels(swingHighs.sorted()).firstOrNull()
        val nextSupport = supportLevels.drop(1).firstOrNull()
        val nextResistance = resistanceLevels.drop(1).firstOrNull()
        val supportDistance = support?.let { abs(last - it) / last * 100.0 }
        val resistanceDistance = resistance?.let { abs(it - last) / last * 100.0 }

        val trendExplanation = when (trend) {
            "Bullish structure" -> "Higher High + Higher Low: pembeli sedang mempertahankan struktur naik. Ini bukan sinyal BUY otomatis."
            "Bearish structure" -> "Lower High + Lower Low: penjual sedang mempertahankan struktur turun. Ini bukan sinyal SELL otomatis."
            else -> "Swing belum membentuk rangkaian HH/HL atau LH/LL yang konsisten. Anggap sebagai area transisi/range."
        }
        val structureExplanation = "Support/resistance berasal dari swing terbaru yang dikelompokkan ke area harga berdekatan. Level adalah zona observasi, bukan garis harga pasti."

        return MarketStructureSnapshot(
            trend = trend,
            trendExplanation = trendExplanation,
            lastSwingHigh = swingHighs.lastOrNull(),
            lastSwingLow = swingLows.lastOrNull(),
            support = support,
            resistance = resistance,
            supportDistancePct = supportDistance,
            resistanceDistancePct = resistanceDistance,
            structureExplanation = structureExplanation,
            dataEnough = swingHighs.size >= 2 && swingLows.size >= 2,
            nextSupport = nextSupport,
            nextResistance = nextResistance
        )
    }

    private fun clusterLevels(levels: List<Double>): List<Double> {
        if (levels.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<Double>>()
        for (level in levels) {
            val cluster = clusters.firstOrNull { existing ->
                val center = existing.average()
                center > 0.0 && abs(level - center) / center <= LEVEL_CLUSTER_PCT
            }
            if (cluster != null) cluster += level else clusters += mutableListOf(level)
        }
        return clusters.map { it.average() }
    }
}
