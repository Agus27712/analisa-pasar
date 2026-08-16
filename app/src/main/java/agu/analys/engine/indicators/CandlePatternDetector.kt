package agu.analys.engine.indicators

import agu.analys.model.CandleBar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Candlestick pattern recognition — pure function, no state. */
object CandlePatternDetector {

    fun detect(history: List<CandleBar>): String? {
        if (history.size < 2) return null
        val previous = history[history.lastIndex - 1]
        val current = history.last()
        val prevBull = previous.close > previous.open
        val prevBear = previous.close < previous.open
        val currBull = current.close > current.open
        val currBear = current.close < current.open

        if (prevBear && currBull && current.open <= previous.close && current.close >= previous.open) {
            return "Bullish Engulfing"
        }
        if (prevBull && currBear && current.open >= previous.close && current.close <= previous.open) {
            return "Bearish Engulfing"
        }

        val body = abs(current.close - current.open)
        val lowerWick = min(current.open, current.close) - current.low
        val upperWick = current.high - max(current.open, current.close)
        if (body > 0 && lowerWick > body * 2 && upperWick < body) return "Hammer"
        if (body > 0 && upperWick > body * 2 && lowerWick < body) return "Shooting Star"
        return null
    }
}
