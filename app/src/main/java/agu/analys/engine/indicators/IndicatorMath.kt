package agu.analys.engine.indicators

import agu.analys.model.CandleBar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure indicator math — no side effects, no market state. */
object IndicatorMath {

    fun rsi(history: List<CandleBar>, period: Int): Double {
        if (history.size <= period) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val change = history[i].close - history[i - 1].close
            if (change >= 0.0) gain += change else loss += -change
        }
        var averageGain = gain / period
        var averageLoss = loss / period
        for (i in period + 1 until history.size) {
            val change = history[i].close - history[i - 1].close
            val currentGain = max(change, 0.0)
            val currentLoss = max(-change, 0.0)
            averageGain = ((averageGain * (period - 1)) + currentGain) / period
            averageLoss = ((averageLoss * (period - 1)) + currentLoss) / period
        }
        if (averageLoss == 0.0) return if (averageGain == 0.0) 50.0 else 100.0
        val rs = averageGain / averageLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val start = max(0, values.size - period * 3)
        var ema = values[start]
        val multiplier = 2.0 / (period + 1.0)
        for (i in start + 1 until values.size) ema = (values[i] - ema) * multiplier + ema
        return ema
    }

    fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val multiplier = 2.0 / (period + 1.0)
        val result = MutableList(values.size) { 0.0 }
        var ema = values.first()
        result[0] = ema
        for (i in 1 until values.size) {
            ema = (values[i] - ema) * multiplier + ema
            result[i] = ema
        }
        return result
    }

    /** Returns list of (macdLine, signalLine). */
    fun macdSeries(
        closes: List<Double>,
        fastPeriod: Int,
        slowPeriod: Int,
        signalPeriod: Int
    ): List<Pair<Double, Double>> {
        val emaFast = emaSeries(closes, fastPeriod)
        val emaSlow = emaSeries(closes, slowPeriod)
        val macd = emaFast.indices.map { emaFast[it] - emaSlow[it] }
        val signal = emaSeries(macd, signalPeriod)
        return macd.indices.map { macd[it] to signal[it] }
    }

    /** Returns (lower, upper) Bollinger bands. */
    fun bollinger(closes: List<Double>, period: Int): Pair<Double, Double> {
        val values = closes.takeLast(period)
        if (values.isEmpty()) return 0.0 to 0.0
        val mean = values.average()
        val deviation = sqrt(values.map { (it - mean) * (it - mean) }.average())
        return mean - 2 * deviation to mean + 2 * deviation
    }

    fun atr(history: List<CandleBar>, period: Int): Double {
        if (history.size < 2) return 0.0
        val start = max(1, history.size - period)
        val trs = (start until history.size).map { i ->
            max(
                history[i].high - history[i].low,
                max(
                    abs(history[i].high - history[i - 1].close),
                    abs(history[i].low - history[i - 1].close)
                )
            )
        }
        return if (trs.isEmpty()) 0.0 else trs.average()
    }
}
