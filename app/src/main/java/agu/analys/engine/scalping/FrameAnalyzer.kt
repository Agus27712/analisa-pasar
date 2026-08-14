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
        atrPeriod: Int = 7
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
        val avgVolume = history.takeLast(5).dropLast(1).map { it.volume }.average()
        val lastVolume = history.last().volume
        val volumeRatio = if (avgVolume > 0.0) lastVolume / avgVolume else 0.0
        val previous = history[history.lastIndex - 1]
        val last = history.last()
        val breakoutUp = last.close > previous.high && last.high >= previous.high
        val breakoutDown = last.close < previous.low && last.low <= previous.low
        val retestUp = last.low <= emaFast && last.close > emaFast && previous.close >= emaFast
        val retestDown = last.high >= emaFast && last.close < emaFast && previous.close <= emaFast
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
