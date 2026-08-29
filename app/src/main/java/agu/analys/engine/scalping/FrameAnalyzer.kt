package agu.analys.engine.scalping

import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.model.CandleBar
import kotlin.math.min

/** Analyze one candle series into a [FrameSignal]. Pure function. */
object FrameAnalyzer {

    fun analyze(
        history: List<CandleBar>,
        rsiPeriod: Int = 7,
        fastPeriod: Int = 5,
        slowPeriod: Int = 13,
        macdFast: Int = 5,
        macdSlow: Int = 12,
        macdSignal: Int = 4,
        atrPeriod: Int = 7,
        isAggressive: Boolean = false
    ): FrameSignal? {
        if (history.size < maxOf(30, slowPeriod + 5)) return null
        val closes = history.map { it.close }
        val price = closes.last()
        val rsi = IndicatorMath.rsi(history, rsiPeriod)
        val emaFast = IndicatorMath.ema(closes, fastPeriod)
        val emaSlow = IndicatorMath.ema(closes, slowPeriod)
        val macdSeries = IndicatorMath.macdSeries(closes, macdFast, macdSlow, macdSignal)
        val macdHist = macdSeries.last().first - macdSeries.last().second
        val atr = IndicatorMath.atr(history, atrPeriod)
        val structureWindow = min(40, history.size)
        val structure = MarketStructureAnalyzer.analyze(history.takeLast(structureWindow))
        val windowCount = if (isAggressive) 16 else 6
        val actualWindow = min(windowCount, history.size)
        val avgVolume = history.takeLast(actualWindow).dropLast(1).map { it.volume }.average()
        val lastVolume = history.last().volume
        val volumeRatio = if (avgVolume > 0.0) lastVolume / avgVolume else 0.0
        val ttlWindow = kotlin.math.min(4, history.size)
        val recentCandles = history.takeLast(ttlWindow)
        var breakoutUp = false
        var breakoutDown = false
        var retestUp = false
        var retestDown = false
        for (i in 1 until recentCandles.size) {
            val curr = recentCandles[i]
            val prev = recentCandles[i-1]
            if (curr.close > prev.high && curr.high >= prev.high) {
                val invalidated = recentCandles.drop(i + 1).any { it.close < prev.low }
                if (!invalidated) breakoutUp = true
            }
            if (curr.close < prev.low && curr.low <= prev.low) {
                val invalidated = recentCandles.drop(i + 1).any { it.close > prev.high }
                if (!invalidated) breakoutDown = true
            }
            if (curr.low <= emaFast && curr.close > emaFast && prev.close >= emaFast) {
                val invalidated = recentCandles.drop(i + 1).any { it.close < emaFast * 0.999 }
                if (!invalidated) retestUp = true
            }
            if (curr.high >= emaFast && curr.close < emaFast && prev.close <= emaFast) {
                val invalidated = recentCandles.drop(i + 1).any { it.close > emaFast * 1.001 }
                if (!invalidated) retestDown = true
            }
        }
        return FrameSignal(
            candles = history,
            price = price,
            rsi = rsi,
            emaFast = emaFast,
            emaSlow = emaSlow,
            macdHist = macdHist,
            atr = atr,
            structureTrend = structure.trend,
            structureEnough = structure.dataEnough,
            volumeRatio = volumeRatio,
            bullishEma = price > emaFast && emaFast > emaSlow,
            bearishEma = price < emaFast && emaFast < emaSlow,
            bullishMomentum = macdHist > 0.0,
            bearishMomentum = macdHist < 0.0,
            breakoutUp = breakoutUp,
            breakoutDown = breakoutDown,
            retestUp = retestUp,
            retestDown = retestDown
        )
    }
}
