package agu.analys.engine.indicators

import agu.analys.model.CandleBar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 
 * Pure indicator math — zero allocations in loops, primitive arrays for memory efficiency,
 * mathematically aligned with institutional trading standards.
 */
object IndicatorMath {

    fun rsi(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.size <= period) return 50.0

        var gain = 0.0
        var loss = 0.0

        for (i in 1..period) {
            val change = history[i].close - history[i - 1].close
            if (change >= 0.0) gain += change else loss -= change
        }

        var averageGain = gain / period
        var averageLoss = loss / period

        for (i in period + 1 until history.size) {
            val change = history[i].close - history[i - 1].close
            val currentGain = if (change > 0.0) change else 0.0
            val currentLoss = if (change < 0.0) -change else 0.0

            averageGain = ((averageGain * (period - 1)) + currentGain) / period
            averageLoss = ((averageLoss * (period - 1)) + currentLoss) / period
        }

        if (averageLoss == 0.0) return if (averageGain == 0.0) 50.0 else 100.0
        
        val rs = averageGain / averageLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    // Menggunakan DoubleArray alih-alih List<Double> untuk mencegah overhead Autoboxing
    fun ema(values: DoubleArray, period: Int): Double {
        if (period <= 0 || values.isEmpty()) return 0.0
        val start = max(0, values.size - period * 3)
        var ema = values[start]
        val multiplier = 2.0 / (period + 1.0)
        
        for (i in start + 1 until values.size) {
            ema = (values[i] - ema) * multiplier + ema
        }
        return ema
    }

    fun ema(values: List<Double>, period: Int): Double {
        if (period <= 0 || values.isEmpty()) return 0.0
        val start = max(0, values.size - period * 3)
        var ema = values[start]
        val multiplier = 2.0 / (period + 1.0)
        
        for (i in start + 1 until values.size) {
            ema = (values[i] - ema) * multiplier + ema
        }
        return ema
    }

    fun emaSeries(values: DoubleArray, period: Int): DoubleArray {
        if (period <= 0 || values.isEmpty()) return DoubleArray(0)
        
        val result = DoubleArray(values.size)
        val multiplier = 2.0 / (period + 1.0)
        var ema = values[0]
        result[0] = ema
        
        for (i in 1 until values.size) {
            ema = (values[i] - ema) * multiplier + ema
            result[i] = ema
        }
        return result
    }

    fun emaSeries(values: List<Double>, period: Int): DoubleArray {
        if (period <= 0 || values.isEmpty()) return DoubleArray(0)
        
        val result = DoubleArray(values.size)
        val multiplier = 2.0 / (period + 1.0)
        var ema = values[0]
        result[0] = ema
        
        for (i in 1 until values.size) {
            ema = (values[i] - ema) * multiplier + ema
            result[i] = ema
        }
        return result
    }

    /** 
     * Memakai custom class alih-alih List<Pair> untuk mencegah
     * membanjirnya objek Pair di Heap Memory saat backtesting & screening.
     */
    data class MacdResult(val macd: DoubleArray, val signal: DoubleArray) {
        val size: Int get() = min(macd.size, signal.size)
        val isEmpty: Boolean get() = size == 0
        fun isNotEmpty(): Boolean = size > 0

        val lastMacd: Double get() = if (macd.isNotEmpty()) macd.last() else 0.0
        val lastSignal: Double get() = if (signal.isNotEmpty()) signal.last() else 0.0

        fun last(): Pair<Double, Double> {
            if (isEmpty) throw NoSuchElementException("MacdResult is empty")
            return macd.last() to signal.last()
        }

        fun lastOrNull(): Pair<Double, Double>? {
            return if (isNotEmpty()) macd.last() to signal.last() else null
        }

        operator fun get(index: Int): Pair<Double, Double> {
            return macd[index] to signal[index]
        }
    }

    fun macdSeries(closes: DoubleArray, fastPeriod: Int, slowPeriod: Int, signalPeriod: Int): MacdResult {
        if (closes.isEmpty() || fastPeriod <= 0 || slowPeriod <= 0 || signalPeriod <= 0) {
            return MacdResult(DoubleArray(0), DoubleArray(0))
        }

        val emaFast = emaSeries(closes, fastPeriod)
        val emaSlow = emaSeries(closes, slowPeriod)
        
        val macdLine = DoubleArray(closes.size)
        for (i in closes.indices) {
            macdLine[i] = emaFast[i] - emaSlow[i]
        }
        
        val signalLine = emaSeries(macdLine, signalPeriod)
        return MacdResult(macdLine, signalLine)
    }

    fun macdSeries(closes: List<Double>, fastPeriod: Int, slowPeriod: Int, signalPeriod: Int): MacdResult {
        if (closes.isEmpty() || fastPeriod <= 0 || slowPeriod <= 0 || signalPeriod <= 0) {
            return MacdResult(DoubleArray(0), DoubleArray(0))
        }
        val arr = DoubleArray(closes.size) { closes[it] }
        return macdSeries(arr, fastPeriod, slowPeriod, signalPeriod)
    }

    /** Returns (lower, upper) Bollinger bands. Memory-optimized. O(N) eksekusi 2-pass. */
    @JvmName("bollingerCandles")
    fun bollinger(history: List<CandleBar>, period: Int): Pair<Double, Double> {
        if (period <= 0 || history.size < period) return 0.0 to 0.0
        
        val start = history.size - period
        var sum = 0.0
        
        for (i in start until history.size) {
            sum += history[i].close
        }
        val mean = sum / period
        
        var varianceSum = 0.0
        for (i in start until history.size) {
            val diff = history[i].close - mean
            varianceSum += diff * diff
        }
        
        val deviation = sqrt(varianceSum / period)
        return (mean - (2 * deviation)) to (mean + (2 * deviation))
    }

    fun bollinger(closes: DoubleArray, period: Int): Pair<Double, Double> {
        if (period <= 0 || closes.size < period) return 0.0 to 0.0
        
        val start = closes.size - period
        var sum = 0.0
        
        for (i in start until closes.size) {
            sum += closes[i]
        }
        val mean = sum / period
        
        var varianceSum = 0.0
        for (i in start until closes.size) {
            val diff = closes[i] - mean
            varianceSum += diff * diff
        }
        
        val deviation = sqrt(varianceSum / period)
        return (mean - (2 * deviation)) to (mean + (2 * deviation))
    }

    fun bollinger(closes: List<Double>, period: Int): Pair<Double, Double> {
        if (period <= 0 || closes.size < period) return 0.0 to 0.0
        
        val start = closes.size - period
        var sum = 0.0
        
        for (i in start until closes.size) {
            sum += closes[i]
        }
        val mean = sum / period
        
        var varianceSum = 0.0
        for (i in start until closes.size) {
            val diff = closes[i] - mean
            varianceSum += diff * diff
        }
        
        val deviation = sqrt(varianceSum / period)
        return (mean - (2 * deviation)) to (mean + (2 * deviation))
    }

    /** 
     * MATEMATIKA DIPERBAIKI: Menggunakan Wilder's Smoothing alih-alih Simple Average.
     * Untuk akurasi, ATR harus dihitung berurutan dari awal data (atau batas window tertentu).
     */
    fun atr(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.size <= 1) return 0.0
        if (history.size <= period) {
            // Jika candle belum mencapai periode penuh, hitung TR sederhana yang tersedia
            var sumTr = 0.0
            for (i in 1 until history.size) {
                val high = history[i].high
                val low = history[i].low
                val prevClose = history[i - 1].close
                val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
                sumTr += tr
            }
            return sumTr / (history.size - 1)
        }

        var sumTr = 0.0
        // 1. Fase Seed: Hitung TR untuk N periode pertama menggunakan SMA
        for (i in 1..period) {
            val high = history[i].high
            val low = history[i].low
            val prevClose = history[i - 1].close
            
            val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
            sumTr += tr
        }
        
        var currentAtr = sumTr / period // Ini ATR Seed

        // 2. Fase Wilder's Smoothing untuk sisa array (Identik dengan standar MT4 / TradingView)
        for (i in period + 1 until history.size) {
            val high = history[i].high
            val low = history[i].low
            val prevClose = history[i - 1].close
            
            val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
            currentAtr = ((currentAtr * (period - 1)) + tr) / period
        }
        
        return currentAtr
    }

    /** Dioptimasi tanpa menggunakan .takeLast() dan iterasi .sum() berulang */
    fun rollingVwap(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.isEmpty()) return 0.0
        
        val start = max(0, history.size - period)
        var sumPV = 0.0
        var sumV = 0.0
        
        for (i in start until history.size) {
            val candle = history[i]
            val typical = (candle.high + candle.low + candle.close) / 3.0
            sumPV += typical * candle.volume
            sumV += candle.volume
        }
        
        return if (sumV > 0.0) sumPV / sumV else history.last().close
    }
}
