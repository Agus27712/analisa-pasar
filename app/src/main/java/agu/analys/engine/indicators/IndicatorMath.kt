package agu.analys.engine.indicators

import agu.analys.model.CandleBar
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.indicators.ATRIndicator
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 
 * Pure indicator math:
 * - TA4J Engine: Digunakan untuk perhitungan RSI, ATR, dan Bollinger Bands dengan BarSeries builder
 *   dan fallback matematis zero-allocation untuk stabilitas.
 * - High-Speed Array Engine: Digunakan untuk perhitungan EMA dan MACD streaming zero-allocation
 *   dengan metodologi eksponensial standar yang 100% selaras antara scalar (ema) dan series (emaSeries/macdSeries).
 */
object IndicatorMath {

    /**
     * Konversi List<CandleBar> menjadi TA4J BarSeries
     */
    fun toBarSeries(candles: List<CandleBar>, name: String = "series"): BarSeries {
        val series = BaseBarSeriesBuilder().withName(name).build()
        if (candles.isEmpty()) return series

        var lastTime = 0L
        for (c in candles) {
            val time = if (c.timestamp > lastTime) c.timestamp else lastTime + 1000L
            lastTime = time
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.of("UTC"))
            val open = if (c.open > 0) c.open else c.close
            val high = max(c.high, max(open, c.close))
            val low = min(c.low, min(open, c.close))
            val volume = max(0.0, c.volume)
            series.addBar(Duration.ofMinutes(1), zdt, open, high, low, c.close, volume)
        }
        return series
    }

    /**
     * Perhitungan RSI dengan TA4J RSIIndicator untuk akurasi maksimal.
     */
    fun rsi(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.size <= period) return 50.0
        return try {
            val series = toBarSeries(history)
            if (series.barCount <= period) return calculateRsiFallback(history, period)
            val closePrice = ClosePriceIndicator(series)
            val rsiIndicator = RSIIndicator(closePrice, period)
            val value = rsiIndicator.getValue(series.endIndex).doubleValue()
            if (value.isNaN() || value.isInfinite()) calculateRsiFallback(history, period) else value
        } catch (_: Exception) {
            calculateRsiFallback(history, period)
        }
    }

    private fun calculateRsiFallback(history: List<CandleBar>, period: Int): Double {
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
        val multiplier = 2.0 / (period + 1.0)
        var ema = values[0]
        
        for (i in 1 until values.size) {
            ema = (values[i] - ema) * multiplier + ema
        }
        return ema
    }

    fun ema(values: List<Double>, period: Int): Double {
        if (period <= 0 || values.isEmpty()) return 0.0
        val multiplier = 2.0 / (period + 1.0)
        var ema = values[0]
        
        for (i in 1 until values.size) {
            ema = (values[i] - ema) * multiplier + ema
        }
        return ema
    }

    @JvmName("emaCandles")
    fun ema(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.isEmpty()) return 0.0
        return try {
            val series = toBarSeries(history)
            val closePrice = ClosePriceIndicator(series)
            val emaIndicator = EMAIndicator(closePrice, period)
            val value = emaIndicator.getValue(series.endIndex).doubleValue()
            if (value.isNaN() || value.isInfinite()) ema(history.map { it.close }, period) else value
        } catch (_: Exception) {
            ema(history.map { it.close }, period)
        }
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

    /** Returns (lower, upper) Bollinger bands. Memory-optimized with TA4J calculation. */
    @JvmName("bollingerCandles")
    fun bollinger(history: List<CandleBar>, period: Int): Pair<Double, Double> {
        if (period <= 0 || history.size < period) return 0.0 to 0.0
        return try {
            val series = toBarSeries(history)
            val closePrice = ClosePriceIndicator(series)
            val sma = SMAIndicator(closePrice, period)
            val stdDev = StandardDeviationIndicator(closePrice, period)
            val upper = BollingerBandsUpperIndicator(BollingerBandsMiddleIndicator(sma), stdDev)
            val lower = BollingerBandsLowerIndicator(BollingerBandsMiddleIndicator(sma), stdDev)
            val upVal = upper.getValue(series.endIndex).doubleValue()
            val lowVal = lower.getValue(series.endIndex).doubleValue()
            if (upVal.isNaN() || lowVal.isNaN()) bollinger(history.map { it.close }, period) else lowVal to upVal
        } catch (_: Exception) {
            bollinger(history.map { it.close }, period)
        }
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
     * MATEMATIKA TA4J ATR: Menggunakan TA4J ATRIndicator (Wilder's Smoothing).
     */
    fun atr(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.size <= 1) return 0.0
        return try {
            val series = toBarSeries(history)
            val atrIndicator = ATRIndicator(series, period)
            val value = atrIndicator.getValue(series.endIndex).doubleValue()
            if (value.isNaN() || value.isInfinite()) calculateAtrFallback(history, period) else value
        } catch (_: Exception) {
            calculateAtrFallback(history, period)
        }
    }

    private fun calculateAtrFallback(history: List<CandleBar>, period: Int): Double {
        if (period <= 0 || history.size <= 1) return 0.0
        if (history.size <= period) {
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
        for (i in 1..period) {
            val high = history[i].high
            val low = history[i].low
            val prevClose = history[i - 1].close
            val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
            sumTr += tr
        }
        
        var currentAtr = sumTr / period

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

    data class DivergenceResult(
        val hasBullishDivergence: Boolean = false,
        val hasBearishDivergence: Boolean = false,
        val detail: String = ""
    )

    /**
     * Deteksi Bullish Divergence Klasik (Harga membuat Lower/Equal Low, RSI membuat Higher Low).
     */
    fun detectDivergence(history: List<CandleBar>, rsiPeriod: Int = 14, lookback: Int = 25): DivergenceResult {
        if (history.size < lookback + rsiPeriod) return DivergenceResult()
        val slice = history.takeLast(lookback + rsiPeriod)
        val rsiValues = mutableListOf<Double>()
        for (i in rsiPeriod until slice.size) {
            val sub = slice.subList(0, i + 1)
            rsiValues.add(rsi(sub, rsiPeriod))
        }
        if (rsiValues.size < 8) return DivergenceResult()
        val priceLows = slice.takeLast(rsiValues.size).map { it.low }

        val currentLow = priceLows.last()
        val currentRsi = rsiValues.last()

        val half = rsiValues.size / 2
        var prevLowIdx = -1
        var minLow = Double.MAX_VALUE
        for (i in 0 until half) {
            if (priceLows[i] < minLow) {
                minLow = priceLows[i]
                prevLowIdx = i
            }
        }

        if (prevLowIdx >= 0) {
            val prevLow = priceLows[prevLowIdx]
            val prevRsi = rsiValues[prevLowIdx]
            if (currentLow <= prevLow * 1.008 && currentRsi > prevRsi + 2.5 && currentRsi < 68.0) {
                return DivergenceResult(
                    hasBullishDivergence = true,
                    detail = "Bullish Divergence: Harga uji low tapi RSI naik dari ${prevRsi.toInt()} ke ${currentRsi.toInt()}"
                )
            }
        }
        return DivergenceResult()
    }
}
