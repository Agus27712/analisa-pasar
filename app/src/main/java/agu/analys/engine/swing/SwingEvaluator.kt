package agu.analys.engine.swing

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
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

data class SwingEvalResult(val signal: AISignalState, val indicators: TechnicalIndicators)

object SwingEvaluator {
    fun evaluate(price: Double, history: List<CandleBar>, fees: TradingFeeConfig = TradingFeeConfig()): SwingEvalResult {
        val minCandles = 55
        if (history.size < minCandles) return SwingEvalResult(
            AISignalState(action = SignalAction.HOLD, confidence = 0, entryPrice = price, reasoning = listOf("Data candle INDODAX nyata belum cukup untuk analisis penuh.", "Dibutuhkan minimal $minCandles candle real."), timestamp = System.currentTimeMillis(), scalpingStage = ScalpingStage.HOLD),
            TechnicalIndicators()
        )

        val closes = history.map { it.close }
        val rsi = IndicatorMath.rsi(history, 14)
        val ema20 = IndicatorMath.ema(closes, 20)
        val ema50 = IndicatorMath.ema(closes, 50)
        val macdSeries = IndicatorMath.macdSeries(closes, 12, 26, 9)
        val macd = macdSeries.last().first; val macdSignal = macdSeries.last().second; val macdHist = macd - macdSignal
        val bb = IndicatorMath.bollinger(closes, 20)
        val atr = IndicatorMath.atr(history, 14)
        val pattern = CandlePatternDetector.detect(history)
        val regime = MarketRegimeDetector.detect(price, ema20, ema50, macdHist, rsi, atr, bb.first, bb.second)
        val structure = MarketStructureAnalyzer.analyze(history)
        val momentumBase = closes[closes.lastIndex - min(10, closes.size - 1)]
        val momentum = if (momentumBase > 0.0) (price - momentumBase) / momentumBase else 0.0
        val ema200 = if (closes.size >= 200) IndicatorMath.ema(closes, 200) else Double.NaN
        val indicators = TechnicalIndicators(rsi, macd, macdSignal, macdHist, ema20, ema50, ema200, bb.second, bb.first, atr, momentum)

        var buy = 0.0; var sell = 0.0; val reasons = mutableListOf<String>()
        reasons += "Regime: $regime."
        when { rsi < 30 -> { buy += 20; reasons += "RSI ${fmt(rsi)} jenuh jual." }; rsi > 70 -> { sell += 20; reasons += "RSI ${fmt(rsi)} jenuh beli." }; else -> reasons += "RSI ${fmt(rsi)} netral." }
        when { price > ema20 && ema20 > ema50 -> { buy += 25; reasons += "EMA20 > EMA50 dan harga di atas EMA20." }; price < ema20 && ema20 < ema50 -> { sell += 25; reasons += "EMA20 < EMA50 dan harga di bawah EMA20." }; else -> reasons += "EMA belum searah tegas." }
        when { macdHist > 0 -> { buy += 15; reasons += "MACD momentum positif." }; macdHist < 0 -> { sell += 15; reasons += "MACD momentum negatif." } }
        when { price <= bb.first -> { buy += 10; reasons += "Harga dekat BB bawah." }; price >= bb.second -> { sell += 10; reasons += "Harga dekat BB atas." } }
        pattern?.let { if (it.contains("Bullish", true) || it.contains("Hammer", true)) { buy += 10; reasons += "Candlestick $it." } else if (it.contains("Bearish", true) || it.contains("Shooting", true)) { sell += 10; reasons += "Candlestick $it." } }
        val avgVolume = history.takeLast(6).dropLast(1).map { it.volume }.average(); val lastVolume = history.last().volume
        if (avgVolume > 0 && lastVolume >= avgVolume * 1.4) { val ratio = lastVolume / avgVolume; if (history.last().close >= history.last().open) { buy += 10; reasons += "Volume ${fmt(ratio)}× mendukung beli." } else { sell += 10; reasons += "Volume ${fmt(ratio)}× mendukung jual." } }
        if (structure.dataEnough) when (structure.trend) { "Bullish structure" -> { buy += 15; reasons += "Market structure bullish." }; "Bearish structure" -> { sell += 15; reasons += "Market structure bearish." }; else -> reasons += "Market structure konsolidasi." }

        val dominant = max(buy, sell); val conflict = abs(buy - sell) < 15.0
        val volatilityTooHigh = price > 0.0 && atr / price >= 0.10
        val minimumScore = 55.0
        val action = when {
            regime == "SIDEWAYS / NO TRADE" || conflict || volatilityTooHigh -> SignalAction.HOLD
            buy >= minimumScore && buy > sell * 1.25 -> SignalAction.BUY
            sell >= minimumScore && sell > buy * 1.25 -> SignalAction.SELL
            else -> SignalAction.HOLD
        }

        val rawStopDistance = atr * 1.5; val rawTp1Distance = atr * 2.0; val rawTp2Distance = atr * 3.5
        var sl = 0.0; var tp1 = 0.0; var tp2 = 0.0
        when (action) {
            SignalAction.BUY -> { sl = price - rawStopDistance; tp1 = price + rawTp1Distance; tp2 = price + rawTp2Distance; structure.lastSwingLow?.takeIf { it > 0 && it < price }?.let { sl = min(sl, it - atr * .25) }; structure.resistance?.takeIf { it > price }?.let { tp1 = min(tp1, it) } }
            SignalAction.SELL -> { sl = price + rawStopDistance; tp1 = price - rawTp1Distance; tp2 = price - rawTp2Distance; structure.lastSwingHigh?.takeIf { it > price }?.let { sl = max(sl, it + atr * .25) }; structure.support?.takeIf { it > 0 && it < price }?.let { tp1 = max(tp1, it) } }
            SignalAction.HOLD -> Unit
        }
        val fee = if (action != SignalAction.HOLD) FeeCalculator.roundTrip(price, sl, tp2, fees) else FeeCalculator.Result(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val finalAction = if (action != SignalAction.HOLD && fee.netRr >= 1.5 && fee.netRewardPct > 0) action else SignalAction.HOLD
        if (volatilityTooHigh) reasons += "Entry ditahan: volatilitas terlalu tinggi."
        if (action != SignalAction.HOLD && finalAction == SignalAction.HOLD) reasons += "Entry ditahan: net R:R setelah fee belum mencapai 1:1.5."
        val finalScore = if (finalAction == SignalAction.HOLD) min(59, ((dominant / 105.0) * 100).toInt()) else ((dominant / 105.0) * 100).toInt().coerceIn(0, 100)
        val rr = if (finalAction == SignalAction.HOLD) "Belum tersedia" else "Net R:R 1:${fmt(fee.netRr)}"

        return SwingEvalResult(AISignalState(
            action = finalAction, confidence = finalScore, sentiment = when (finalAction) { SignalAction.BUY -> TrendSentiment.STRONG_BULLISH_CONTINUATION; SignalAction.SELL -> TrendSentiment.BEARISH_DISTRIBUTION; SignalAction.HOLD -> TrendSentiment.NEUTRAL_CONSOLIDATION },
            entryPrice = price, targetPrice1 = if (finalAction == SignalAction.HOLD) 0.0 else tp1, targetPrice2 = if (finalAction == SignalAction.HOLD) 0.0 else tp2, stopLoss = if (finalAction == SignalAction.HOLD) 0.0 else sl,
            riskRewardRatio = rr, reasoning = reasons.take(7), timestamp = System.currentTimeMillis(), patternDetected = pattern, scalpingStage = ScalpingStage.HOLD
        ), indicators)
    }
    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
