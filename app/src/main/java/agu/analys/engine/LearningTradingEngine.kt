package agu.analys.engine

import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.TrendSentiment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Real-data technical-analysis engine for learning. It never invents market data. */
class LearningTradingEngine(
    @Suppress("UNUSED_PARAMETER") private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val candles = mutableListOf<CandleBar>()
    private var currentTick: MarketTick? = null
    private val _signalState = MutableStateFlow(AISignalState())
    val signalState: StateFlow<AISignalState> = _signalState.asStateFlow()
    private val _indicators = MutableStateFlow(TechnicalIndicators())
    val indicators: StateFlow<TechnicalIndicators> = _indicators.asStateFlow()

    fun onTickUpdate(tick: MarketTick) {
        if (tick.price <= 0.0) return
        currentTick = tick
        // A live tick must never recalculate the technical signal. Indicators and the
        // signal are evaluated from the latest completed candle snapshot only.
    }

    /**
     * Accept one completed candle and evaluate once.
     *
     * Closed candles are immutable for the signal engine. If an upstream source sends
     * the same timestamp again with different OHLC values, the replacement is ignored
     * rather than allowing historical signal repainting.
     */
    fun onCandleUpdate(candle: CandleBar) {
        if (!isValidCandle(candle)) return
        synchronized(candles) {
            val existing = candles.indexOfFirst { it.timestamp == candle.timestamp }
            if (existing >= 0) return
            candles.add(candle)
            candles.sortBy { it.timestamp }
            while (candles.size > 250) candles.removeAt(0)
        }
        evaluate()
    }

    /**
     * Replace the complete completed-candle snapshot and evaluate only once.
     * This is used after a historical refresh so intermediate historical candles
     * cannot briefly emit stale BUY/SELL states to the UI/history collector.
     */
    fun replaceCompletedCandles(completedCandles: List<CandleBar>) {
        val normalized = completedCandles
            .filter(::isValidCandle)
            .groupBy { it.timestamp }
            .values
            .map { it.first() }
            .sortedBy { it.timestamp }
            .takeLast(250)

        synchronized(candles) {
            candles.clear()
            candles.addAll(normalized)
        }
        evaluate()
    }

    fun resetForOffline() {
        currentTick = null
        synchronized(candles) { candles.clear() }
        _indicators.value = TechnicalIndicators()
        _signalState.value = AISignalState(
            action = SignalAction.HOLD,
            confidence = 0,
            sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
            entryPrice = 0.0,
            targetPrice1 = 0.0,
            targetPrice2 = 0.0,
            stopLoss = 0.0,
            riskRewardRatio = "Tidak tersedia",
            probabilityScore = 0.0,
            reasoning = listOf("OFFLINE: analisis dihentikan.", "Tidak ada harga/candle live yang dapat dipercaya.", "Sambungkan internet untuk menerima data market baru."),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun isValidCandle(candle: CandleBar): Boolean =
        candle.timestamp > 0L &&
            candle.open > 0.0 && candle.high > 0.0 && candle.low > 0.0 && candle.close > 0.0 &&
            candle.high >= max(candle.open, candle.close) &&
            candle.low <= min(candle.open, candle.close)

    private fun evaluate() {
        val tick = currentTick ?: return
        val price = tick.price
        val history = synchronized(candles) { candles.toList() }
        if (history.size < 35) {
            _indicators.value = TechnicalIndicators()
            _signalState.value = AISignalState(
                action = SignalAction.HOLD,
                confidence = 0,
                sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
                entryPrice = price,
                targetPrice1 = 0.0,
                targetPrice2 = 0.0,
                stopLoss = 0.0,
                riskRewardRatio = "Belum tersedia",
                probabilityScore = 0.0,
                reasoning = listOf("Data candle INDODAX nyata belum cukup untuk analisis penuh.", "Dibutuhkan minimal 35 candle untuk indikator dasar.", "Tidak ada data contoh yang digunakan sebagai pengganti data pasar."),
                timestamp = System.currentTimeMillis()
            )
            return
        }

        val closes = history.map { it.close }
        val rsi = calculateRsi(history, 14)
        val ema20 = calculateEma(closes, 20)
        val ema50 = calculateEma(closes, 50)
        val macdSeries = calculateMacdSeries(closes)
        val macd = macdSeries.last().first
        val macdSignal = macdSeries.last().second
        val macdHist = macd - macdSignal
        val bb = calculateBollinger(closes, 20)
        val atr = calculateAtr(history, 14)
        val pattern = detectPattern(history)
        val regime = detectMarketRegime(price, ema20, ema50, macdHist, rsi, atr, bb)
        val marketStructure = MarketStructureAnalyzer.analyze(history)
        val momentumLookback = min(10, closes.size - 1)
        val momentumBase = closes[closes.lastIndex - momentumLookback]
        val momentum = if (momentumBase > 0.0) (price - momentumBase) / momentumBase else 0.0

        _indicators.value = TechnicalIndicators(
            rsi14 = rsi,
            macd = macd,
            macdSignal = macdSignal,
            macdHist = macdHist,
            ema20 = ema20,
            ema50 = ema50,
            ema200 = calculateEma(closes, 200),
            bbUpper = bb.second,
            bbLower = bb.first,
            atr = atr,
            momentum = momentum
        )

        var buy = 0.0
        var sell = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Market regime: $regime."

        when {
            rsi < 30.0 -> { buy += 20.0; reasons += "RSI ${format(rsi)}: jenuh jual; pantulan perlu konfirmasi." }
            rsi > 70.0 -> { sell += 20.0; reasons += "RSI ${format(rsi)}: jenuh beli; risiko koreksi meningkat." }
            else -> reasons += "RSI ${format(rsi)}: netral, belum memberi sinyal ekstrem."
        }

        when {
            price > ema20 && ema20 > ema50 -> { buy += 25.0; reasons += "EMA20 > EMA50 dan harga di atas keduanya: struktur bullish." }
            price < ema20 && ema20 < ema50 -> { sell += 25.0; reasons += "EMA20 < EMA50 dan harga di bawah keduanya: struktur bearish." }
            else -> reasons += "EMA20/EMA50 belum searah: tren belum terkonfirmasi."
        }

        when {
            macdHist > 0 && macd > macdSignal -> { buy += 20.0; reasons += "MACD histogram positif: momentum naik lebih dominan." }
            macdHist < 0 && macd < macdSignal -> { sell += 20.0; reasons += "MACD histogram negatif: momentum turun lebih dominan." }
            else -> reasons += "MACD belum memberi konfirmasi momentum yang tegas."
        }

        when {
            price <= bb.first -> { buy += 10.0; reasons += "Harga dekat/di bawah Bollinger Band bawah: area pantulan yang perlu diamati." }
            price >= bb.second -> { sell += 10.0; reasons += "Harga dekat/di atas Bollinger Band atas: area koreksi yang perlu diamati." }
            else -> reasons += "Harga masih berada di dalam Bollinger Band."
        }

        pattern?.let {
            if (it.contains("Bullish", true) || it.contains("Hammer", true)) { buy += 15.0; reasons += "Candlestick $it: konfirmasi bullish tambahan, bukan jaminan." }
            else if (it.contains("Bearish", true) || it.contains("Shooting", true)) { sell += 15.0; reasons += "Candlestick $it: konfirmasi bearish tambahan, bukan jaminan." }
        }

        val avgVolume = history.takeLast(5).map { it.volume }.average()
        val lastVolume = history.last().volume
        if (avgVolume > 0.0 && lastVolume >= avgVolume * 1.8) {
            val ratio = lastVolume / avgVolume
            if (history.last().close >= history.last().open) { buy += 10.0; reasons += "Volume ${format(ratio)}× rata-rata 5 candle: aktivitas naik meningkat." }
            else { sell += 10.0; reasons += "Volume ${format(ratio)}× rata-rata 5 candle: aktivitas jual meningkat." }
        } else reasons += "Volume belum memberi lonjakan konfirmasi."

        if (marketStructure.dataEnough) {
            when (marketStructure.trend) {
                "Bullish structure" -> { buy += 15.0; reasons += "Market structure bullish: Higher High + Higher Low." }
                "Bearish structure" -> { sell += 15.0; reasons += "Market structure bearish: Lower High + Lower Low." }
                else -> reasons += "Market structure range/transisi: belum ada HH/HL atau LH/LL yang konsisten."
            }
        } else reasons += "Market structure belum tersedia: candle swing belum cukup."

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

        val maxRawScore = 115.0
        val score = ((dominant / maxRawScore) * 100.0).toInt().coerceIn(0, 100)
        when {
            volatilityTooHigh -> reasons += "NO TRADE: ATR sangat besar terhadap harga; level risiko tidak aman untuk dipaksakan."
            regime == "HIGH VOLATILITY" -> reasons += "NO TRADE ZONE: volatilitas tinggi, butuh setup yang lebih kuat."
            structureBlocksBuy && buy >= minimumScore -> reasons += "NO TRADE: sinyal bullish bertentangan dengan market structure bearish."
            structureBlocksSell && sell >= minimumScore -> reasons += "NO TRADE: sinyal bearish bertentangan dengan market structure bullish."
            else -> reasons += "NO TRADE ZONE: faktor belum cukup selaras."
        }
        if (action != SignalAction.HOLD) reasons[reasons.lastIndex] = "Score ${score}/100 = kekuatan setup, bukan peluang profit."

        val rawStopDistance = atr * 1.5
        val rawTp1Distance = atr * 2.0
        val rawTp2Distance = atr * 3.5
        var sl = 0.0
        var tp1 = 0.0
        var tp2 = 0.0
        var stopDistance = 0.0
        var tp1Distance = 0.0
        var tp2Distance = 0.0

        when (action) {
            SignalAction.BUY -> {
                sl = price - rawStopDistance
                tp1 = price + rawTp1Distance
                tp2 = price + rawTp2Distance
                val swingLow = marketStructure.lastSwingLow ?: 0.0
                if (marketStructure.dataEnough && swingLow > 0.0 && swingLow < price) sl = min(sl, swingLow - atr * 0.25)
                val resistance = marketStructure.resistance ?: 0.0
                if (marketStructure.dataEnough && resistance > price) tp1 = min(tp1, resistance)
            }
            SignalAction.SELL -> {
                sl = price + rawStopDistance
                tp1 = price - rawTp1Distance
                tp2 = price - rawTp2Distance
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
        if (action != SignalAction.HOLD && finalAction == SignalAction.HOLD) reasons += "Setup dibatalkan: TP/SL tidak memiliki RR minimum atau level risiko terlalu lebar."

        val rr = if (finalAction == SignalAction.HOLD || stopDistance <= 0.0) "Tidak ada posisi" else "TP1 1:${format(tp1Distance / stopDistance)} | TP2 1:${format(tp2Distance / stopDistance)}"
        val finalReasoning = reasons.take(6).toMutableList()
        if (finalReasoning.size > 6) finalReasoning.removeAt(5)

        val sentiment = when (finalAction) {
            SignalAction.BUY -> if (pattern?.contains("Engulfing", true) == true) TrendSentiment.BULLISH_REVERSAL else TrendSentiment.STRONG_BULLISH_CONTINUATION
            SignalAction.SELL -> if (pattern?.contains("Engulfing", true) == true) TrendSentiment.BEARISH_BREAKDOWN else TrendSentiment.BEARISH_DISTRIBUTION
            SignalAction.HOLD -> TrendSentiment.NEUTRAL_CONSOLIDATION
        }

        _signalState.value = AISignalState(
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
            reasoning = finalReasoning,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun detectMarketRegime(price: Double, ema20: Double, ema50: Double, macdHist: Double, rsi: Double, atr: Double, bb: Pair<Double, Double>): String {
        val emaGapPct = if (ema50 != 0.0) abs(ema20 - ema50) / ema50 * 100.0 else 0.0
        val bbWidthPct = if (price != 0.0) (bb.second - bb.first) / price * 100.0 else 0.0
        val atrPct = if (price != 0.0) atr / price * 100.0 else 0.0
        return when {
            emaGapPct < 0.25 && abs(macdHist) < atr * 0.03 && rsi in 45.0..55.0 -> "SIDEWAYS / NO TRADE"
            atrPct > 4.0 || bbWidthPct > 12.0 -> "HIGH VOLATILITY"
            price > ema20 && ema20 > ema50 && macdHist >= 0.0 -> "TRENDING UP"
            price < ema20 && ema20 < ema50 && macdHist <= 0.0 -> "TRENDING DOWN"
            else -> "TRANSITION / MIXED"
        }
    }

    private fun calculateRsi(history: List<CandleBar>, period: Int): Double {
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

    private fun calculateEma(values: List<Double>, period: Int): Double {
        val start = max(0, values.size - period * 3)
        var ema = values[start]
        val multiplier = 2.0 / (period + 1.0)
        for (i in start + 1 until values.size) ema = (values[i] - ema) * multiplier + ema
        return ema
    }

    private fun calculateMacdSeries(closes: List<Double>): List<Pair<Double, Double>> {
        val ema12 = calculateEmaSeries(closes, 12)
        val ema26 = calculateEmaSeries(closes, 26)
        val macd = ema12.indices.map { ema12[it] - ema26[it] }
        val signal = calculateEmaSeries(macd, 9)
        return macd.indices.map { macd[it] to signal[it] }
    }

    private fun calculateEmaSeries(values: List<Double>, period: Int): List<Double> {
        val multiplier = 2.0 / (period + 1.0)
        val result = MutableList(values.size) { 0.0 }
        var ema = values.first()
        result[0] = ema
        for (i in 1 until values.size) { ema = (values[i] - ema) * multiplier + ema; result[i] = ema }
        return result
    }

    private fun calculateBollinger(closes: List<Double>, period: Int): Pair<Double, Double> {
        val values = closes.takeLast(period)
        val mean = values.average()
        val deviation = sqrt(values.map { (it - mean) * (it - mean) }.average())
        return mean - 2 * deviation to mean + 2 * deviation
    }

    private fun calculateAtr(history: List<CandleBar>, period: Int): Double {
        val start = max(1, history.size - period)
        val trs = (start until history.size).map { i ->
            max(history[i].high - history[i].low, max(abs(history[i].high - history[i - 1].close), abs(history[i].low - history[i - 1].close)))
        }
        return trs.average()
    }

    private fun detectPattern(history: List<CandleBar>): String? {
        val previous = history[history.lastIndex - 1]
        val current = history.last()
        val prevBull = previous.close > previous.open
        val prevBear = previous.close < previous.open
        val currBull = current.close > current.open
        val currBear = current.close < current.open
        if (prevBear && currBull && current.open <= previous.close && current.close >= previous.open) return "Bullish Engulfing"
        if (prevBull && currBear && current.open >= previous.close && current.close <= previous.open) return "Bearish Engulfing"
        val body = abs(current.close - current.open)
        val lowerWick = min(current.open, current.close) - current.low
        val upperWick = current.high - max(current.open, current.close)
        if (body > 0 && lowerWick > body * 2 && upperWick < body) return "Hammer"
        if (body > 0 && upperWick > body * 2 && lowerWick < body) return "Shooting Star"
        return null
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}
