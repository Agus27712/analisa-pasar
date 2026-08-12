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
    val dataEnough: Boolean
)

object MarketStructureAnalyzer {
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

        val support = swingLows.filter { it <= last }.maxOrNull() ?: swingLows.minOrNull()
        val resistance = swingHighs.filter { it >= last }.minOrNull() ?: swingHighs.maxOrNull()
        val supportDistance = support?.let { abs(last - it) / last * 100.0 }
        val resistanceDistance = resistance?.let { abs(resistance - last) / last * 100.0 }

        val trendExplanation = when (trend) {
            "Bullish structure" -> "Higher High + Higher Low: pembeli sedang mempertahankan struktur naik. Ini bukan sinyal BUY otomatis."
            "Bearish structure" -> "Lower High + Lower Low: penjual sedang mempertahankan struktur turun. Ini bukan sinyal SELL otomatis."
            else -> "Swing belum membentuk rangkaian HH/HL atau LH/LL yang konsisten. Anggap sebagai area transisi/range."
        }
        val structureExplanation = "Support/resistance diambil dari swing candle terbaru. Level adalah area observasi, bukan garis harga yang pasti."

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
            dataEnough = true
        )
    }
}
