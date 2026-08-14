package agu.analys.engine.swing

import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.indicators.CandlePatternDetector
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.engine.regime.MarketRegimeDetector
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.TrendSentiment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SwingEvalResult(
    val signal: AISignalState,
    val indicators: TechnicalIndicators
)

/** Swing / long-mode scoring — pure, no StateFlow. */
object SwingEvaluator {

    fun evaluate(price: Double, history: List<CandleBar>): SwingEvalResult {
        val minCandles = 35
        if (history.size < minCandles) {
            return SwingEvalResult(
                signal = AISignalState(
                    action = SignalAction.HOLD,
                    confidence = 0,
                    sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
                    entryPrice = price,
                    targetPrice1 = 0.0,
                    targetPrice2 = 0.0,
                    stopLoss = 0.0,
                    riskRewardRatio = "Belum tersedia",
                    probabilityScore = 0.0,
                    reasoning = listOf(
                        "Data candle INDODAX nyata belum cukup untuk analisis penuh.",
                        "Dibutuhkan minimal $minCandles candle real.",
                        "Tidak ada data contoh/mock yang digunakan."
                    ),
                    timestamp = System.currentTimeMillis(),
                    scalpingStage = ScalpingStage.HOLD
                ),
                indicators = TechnicalIndicators()
            )
        }

        val closes = history.map { it.close }
        val rsi = IndicatorMath.rsi(history, 14)
        val emaFast = IndicatorMath.ema(closes, 20)
        val emaSlow = IndicatorMath.ema(closes, 50)
        val macdSeries = IndicatorMath.macdSeries(closes, 12, 26, 9)
        val macd = macdSeries.last().first
        val macdSignal = macdSeries.last().second
        val macdHist = macd - macdSignal
        val bb = IndicatorMath.bollinger(closes, 20)
        val atr = IndicatorMath.atr(history, 14)
        val pattern = CandlePatternDetector.detect(history)
        val regime = MarketRegimeDetector.detect(price, emaFast, emaSlow, macdHist, rsi, atr, bb.first, bb.second)
        val marketStructure = MarketStructureAnalyzer.analyze(history)
        val momentumLookback = min(10, closes.size - 1)
        val momentumBase = closes[closes.lastIndex - momentumLookback]
        val momentum = if (momentumBase > 0.0) (price - momentumBase) / momentumBase else 0.0
        val ema200 = if (closes.size >= 200) IndicatorMath.ema(closes, 200) else Double.NaN

        val indicators = TechnicalIndicators(
            rsi14 = rsi, macd = macd, macdSignal = macdSignal, macdHist = macdHist,
            ema20 = emaFast, ema50 = emaSlow, ema200 = ema200,
            bbUpper = bb.second, bbLower = bb.first, atr = atr, momentum = momentum
        )

        var buy = 0.0
        var sell = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Market regime: $regime."
        when {
            rsi < 30.0 -> { buy += 20.0; reasons += "RSI ${fmt(rsi)}: jenuh jual." }
            rsi > 70.0 -> { sell += 20.0; reasons += "RSI ${fmt(rsi)}: jenuh beli." }
            else -> reasons += "RSI ${fmt(rsi)}: netral."
        }
        when {
            price > emaFast && emaFast > emaSlow -> { buy += 25.0; reasons += "EMA20 > EMA50: struktur bullish." }
            price < emaFast && emaFast < emaSlow -> { sell += 25.0; reasons += "EMA20 < EMA50: struktur bearish." }
            else -> reasons += "EMA belum searah tegas."
        }
        when {
            macdHist > 0 && macd > macdSignal -> { buy += 20.0; reasons += "MACD histogram positif." }
            macdHist < 0 && macd < macdSignal -> { sell += 20.0; reasons += "MACD histogram negatif." }
            else -> reasons += "MACD netral."
        }
        when {
            price <= bb.first -> { buy += 10.0; reasons += "Harga di BB bawah." }
            price >= bb.second -> { sell += 10.0; reasons += "Harga di BB atas." }
            else -> reasons += "Harga di dalam BB."
        }
        pattern?.let {
            if (it.contains("Bullish", true) || it.contains("Hammer", true)) { buy += 15.0; reasons += "Candlestick $it." }
            else if (it.contains("Bearish", true) || it.contains("Shooting", true)) { sell += 15.0; reasons += "Candlestick $it." }
        }
        val avgVolume = history.takeLast(5).map { it.volume }.average()
        val lastVolume = history.last().volume
        if (avgVolume > 0.0 && lastVolume >= avgVolume * 1.6) {
            val ratio = lastVolume / avgVolume
            if (history.last().close >= history.last().open) { buy += 10.0; reasons += "Volume ${fmt(ratio)}×: lonjakan beli." }
            else { sell += 10.0; reasons += "Volume ${fmt(ratio)}×: lonjakan jual." }
        } else reasons += "Volume stabil."

        if (marketStructure.dataEnough) {
            when (marketStructure.trend) {
                "Bullish structure" -> { buy += 15.0; reasons += "Market structure bullish." }
                "Bearish structure" -> { sell += 15.0; reasons += "Market structure bearish." }
                else -> reasons += "Market structure konsolidasi."
            }
        } else reasons += "Market structure belum cukup."

        val structureBlocksBuy = marketStructure.dataEnough && marketStructure.trend == "Bearish structure"
        val structureBlocksSell = marketStructure.dataEnough && marketStructure.trend == "Bullish structure"
        val dominant = max(buy, sell)
        val conflict = abs(buy - sell) < 20.0
        val volatilityTooHigh = price > 0.0 && atr / price >= 0.08
        val minimumScore = if (regime == "HIGH VOLATILITY") 70.0 else 60.0
        val dominanceRatio = if (min(buy, sell) > 0.0) dominant / min(buy, sell) else Double.POSITIVE_INFINITY
        val weakDominance = dominanceRatio < 1.40
        val noTrade = regime == "SIDEWAYS / NO TRADE" || conflict || weakDominance || dominant < minimumScore || volatilityTooHigh
        val action = when {
            noTrade -> SignalAction.HOLD
            buy >= minimumScore && buy > sell * 1.40 && !structureBlocksBuy -> SignalAction.BUY
            sell >= minimumScore && sell > buy * 1.40 && !structureBlocksSell -> SignalAction.SELL
            else -> SignalAction.HOLD
        }
        val score = ((dominant / 115.0) * 100.0).toInt().coerceIn(0, 100)
        if (volatilityTooHigh) reasons += "NO TRADE: ATR terlalu tinggi."
        else if (action != SignalAction.HOLD) reasons += "Score $score/100."
        else reasons += "NO TRADE ZONE: konvergensi belum cukup."

        val rawStopDistance = atr * 1.5
        val rawTp1Distance = atr * 2.0
        val rawTp2Distance = atr * 3.5
        var sl = 0.0; var tp1 = 0.0; var tp2 = 0.0
        var stopDistance = 0.0; var tp1Distance = 0.0; var tp2Distance = 0.0
        when (action) {
            SignalAction.BUY -> {
                sl = price - rawStopDistance; tp1 = price + rawTp1Distance; tp2 = price + rawTp2Distance
                val swingLow = marketStructure.lastSwingLow ?: 0.0
                if (marketStructure.dataEnough && swingLow > 0.0 && swingLow < price) sl = min(sl, swingLow - atr * 0.25)
                val resistance = marketStructure.resistance ?: 0.0
                if (marketStructure.dataEnough && resistance > price) tp1 = min(tp1, resistance)
            }
            SignalAction.SELL -> {
                sl = price + rawStopDistance; tp1 = price - rawTp1Distance; tp2 = price - rawTp2Distance
                val swingHigh = marketStructure.lastSwingHigh ?: 0.0
                if (marketStructure.dataEnough && swingHigh > price) sl = max(sl, swingHigh + atr * 0.25)
                val support = marketStructure.support ?: 0.0
                if (marketStructure.dataEnough && support > 0.0 && support < price) tp1 = max(tp1, support)
            }
            SignalAction.HOLD -> Unit
        }
        if (action != SignalAction.HOLD) {
            stopDistance = abs(price - sl)
            tp1Distance = abs(tp1 - price)
            tp2Distance = abs(tp2 - price)
        }
        val levelsValid = when (action) {
            SignalAction.BUY -> stopDistance > 0.0 && stopDistance < price * 0.08 && tp1 > price && tp2 > tp1 && tp1Distance >= stopDistance && tp2Distance >= stopDistance * 1.5 && sl > 0.0
            SignalAction.SELL -> stopDistance > 0.0 && stopDistance < price * 0.08 && tp1 < price && tp2 < tp1 && tp1Distance >= stopDistance && tp2Distance >= stopDistance * 1.5
            SignalAction.HOLD -> false
        }
        val finalAction = if (action != SignalAction.HOLD && levelsValid) action else SignalAction.HOLD
        val finalScore = if (finalAction == SignalAction.HOLD) min(59, score) else score
        if (action != SignalAction.HOLD && finalAction == SignalAction.HOLD) reasons += "Setup dibatalkan: RR tidak layak."
        val rr = if (finalAction == SignalAction.HOLD || stopDistance <= 0.0) "Tidak ada posisi"
        else "TP1 1:${fmt(tp1Distance / stopDistance)} | TP2 1:${fmt(tp2Distance / stopDistance)}"
        val sentiment = when (finalAction) {
            SignalAction.BUY -> if (pattern?.contains("Engulfing", true) == true) TrendSentiment.BULLISH_REVERSAL else TrendSentiment.STRONG_BULLISH_CONTINUATION
            SignalAction.SELL -> if (pattern?.contains("Engulfing", true) == true) TrendSentiment.BEARISH_BREAKDOWN else TrendSentiment.BEARISH_DISTRIBUTION
            SignalAction.HOLD -> TrendSentiment.NEUTRAL_CONSOLIDATION
        }

        return SwingEvalResult(
            signal = AISignalState(
                action = finalAction,
                confidence = finalScore,
                sentiment = sentiment,
                entryPrice = price,
                targetPrice1 = if (finalAction == SignalAction.HOLD) 0.0 else tp1,
                targetPrice2 = if (finalAction == SignalAction.HOLD) 0.0 else tp2,
                stopLoss = if (finalAction == SignalAction.HOLD) 0.0 else sl,
                riskRewardRatio = rr,
                probabilityScore = 0.0,
                patternDetected = pattern,
                reasoning = reasons.take(6),
                timestamp = System.currentTimeMillis(),
                scalpingStage = ScalpingStage.HOLD
            ),
            indicators = indicators
        )
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
