package agu.analys.engine

import agu.analys.engine.indicators.CandlePatternDetector
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.engine.regime.MarketRegimeDetector
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.model.TrendSentiment
import agu.analys.service.IndodaxMarketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrator for real-data technical analysis.
 * Indicator math lives in [IndicatorMath], patterns in [CandlePatternDetector],
 * regime in [MarketRegimeDetector]. This class only wires state + scoring.
 */
class LearningTradingEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    var isScalpingMode: Boolean = false
    private val candles = mutableListOf<CandleBar>()
    private var currentTick: MarketTick? = null
    private var mtfRefreshJob: Job? = null
    private var lastMtfRefresh = 0L
    private var mtfSymbol = ""
    private var h1Candles: List<CandleBar> = emptyList()
    private var m15Candles: List<CandleBar> = emptyList()
    private var m1Candles: List<CandleBar> = emptyList()
    private val _signalState = MutableStateFlow(AISignalState())
    val signalState: StateFlow<AISignalState> = _signalState.asStateFlow()
    private val _indicators = MutableStateFlow(TechnicalIndicators())
    val indicators: StateFlow<TechnicalIndicators> = _indicators.asStateFlow()

    fun onTickUpdate(tick: MarketTick) {
        if (tick.price <= 0.0) return
        currentTick = tick
        if (isScalpingMode) refreshScalpingTimeframesIfDue(tick.symbol)
    }

    fun onCandleUpdate(candle: CandleBar) {
        if (candle.open <= 0.0 || candle.high <= 0.0 || candle.low <= 0.0 || candle.close <= 0.0) return
        synchronized(candles) {
            val existing = candles.indexOfFirst { it.timestamp == candle.timestamp }
            if (existing >= 0) candles[existing] = candle else candles.add(candle)
            candles.sortBy { it.timestamp }
            while (candles.size > 250) candles.removeAt(0)
        }
        if (!isScalpingMode) evaluate()
    }

    fun resetForOffline(preserveState: Boolean = false) {
        currentTick = null
        mtfRefreshJob?.cancel()
        mtfRefreshJob = null
        lastMtfRefresh = 0L
        mtfSymbol = ""
        h1Candles = emptyList()
        m15Candles = emptyList()
        m1Candles = emptyList()
        synchronized(candles) { candles.clear() }
        if (preserveState) return
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
            reasoning = listOf(
                "OFFLINE: analisis dihentikan.",
                "Tidak ada harga/candle live yang dapat dipercaya.",
                "Sambungkan internet untuk menerima data market baru."
            ),
            timestamp = System.currentTimeMillis(),
            scalpingStage = ScalpingStage.HOLD
        )
    }

    private fun refreshScalpingTimeframesIfDue(symbol: String) {
        if (symbol.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastMtfRefresh < 15_000L && mtfSymbol == symbol) return
        if (mtfRefreshJob?.isActive == true) return
        lastMtfRefresh = now
        mtfSymbol = symbol
        mtfRefreshJob = scope.launch {
            val h1Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.H1, 120) }
            val m15Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.M15, 160) }
            val m1Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.M1, 180) }
            val h1 = h1Job.await()
            val m15 = m15Job.await()
            val m1 = m1Job.await()
            if (h1.size >= 30 && m15.size >= 30 && m1.size >= 30 && currentTick?.symbol == symbol) {
                h1Candles = h1
                m15Candles = m15
                m1Candles = m1
                evaluateScalpingMtf()
            }
        }
    }

    private data class FrameSignal(
        val candles: List<CandleBar>,
        val price: Double,
        val rsi: Double,
        val emaFast: Double,
        val emaSlow: Double,
        val macdHist: Double,
        val atr: Double,
        val structureTrend: String,
        val structureEnough: Boolean,
        val volumeRatio: Double,
        val bullishEma: Boolean,
        val bearishEma: Boolean,
        val bullishMomentum: Boolean,
        val bearishMomentum: Boolean,
        val breakoutUp: Boolean,
        val breakoutDown: Boolean,
        val retestUp: Boolean,
        val retestDown: Boolean
    )

    private fun analyzeFrame(
        history: List<CandleBar>,
        rsiPeriod: Int = 7,
        fastPeriod: Int = 5,
        slowPeriod: Int = 13,
        macdFast: Int = 5,
        macdSlow: Int = 12,
        macdSignal: Int = 4
    ): FrameSignal? {
        if (history.size < maxOf(30, slowPeriod + 5)) return null
        val closes = history.map { it.close }
        val price = closes.last()
        val rsi = IndicatorMath.rsi(history, rsiPeriod)
        val emaFast = IndicatorMath.ema(closes, fastPeriod)
        val emaSlow = IndicatorMath.ema(closes, slowPeriod)
        val macdSeries = IndicatorMath.macdSeries(closes, macdFast, macdSlow, macdSignal)
        val macdHist = macdSeries.last().first - macdSeries.last().second
        val atr = IndicatorMath.atr(history, 7)
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

    private fun evaluateScalpingMtf() {
        val tick = currentTick ?: return
        val h1 = analyzeFrame(h1Candles, rsiPeriod = 14, fastPeriod = 9, slowPeriod = 21, macdFast = 12, macdSlow = 26, macdSignal = 9) ?: return
        val m15 = analyzeFrame(m15Candles) ?: return
        val m1 = analyzeFrame(m1Candles) ?: return
        val price = tick.price
        val atr = m1.atr
        if (atr <= 0.0 || price <= 0.0) return

        val biasLong = h1.bullishEma && h1.structureEnough && h1.structureTrend == "Bullish structure" && h1.bullishMomentum
        val biasShort = h1.bearishEma && h1.structureEnough && h1.structureTrend == "Bearish structure" && h1.bearishMomentum
        val setupLong = m15.bullishEma && m15.structureEnough && m15.structureTrend == "Bullish structure"
        val setupShort = m15.bearishEma && m15.structureEnough && m15.structureTrend == "Bearish structure"
        val triggerLong = m1.bullishEma && m1.bullishMomentum && m1.rsi in 50.0..75.0 && (m1.volumeRatio >= 1.20 || m1.breakoutUp || m1.retestUp)
        val triggerShort = m1.bearishEma && m1.bearishMomentum && m1.rsi in 25.0..50.0 && (m1.volumeRatio >= 1.20 || m1.breakoutDown || m1.retestDown)
        val extendedLong = h1.rsi > 75.0 || m15.rsi > 75.0
        val extendedShort = h1.rsi < 25.0 || m15.rsi < 25.0
        val extremeVolatility = atr / price > 0.04

        var longScore = 0
        var shortScore = 0
        val reasons = mutableListOf<String>()
        reasons += "[SCALPING MTF] 1H = bias, 15M = setup, 1M = trigger."
        reasons += "1H: ${if (biasLong) "bullish" else if (biasShort) "bearish" else "mixed"}, RSI ${format(h1.rsi)}."
        reasons += "15M: ${if (setupLong) "bullish setup" else if (setupShort) "bearish setup" else "pullback/mixed"}, RSI ${format(m15.rsi)}."
        reasons += "1M: ${if (triggerLong) "long trigger" else if (triggerShort) "short trigger" else "belum trigger"}, RSI ${format(m1.rsi)}, volume ${format(m1.volumeRatio)}×."

        if (biasLong) longScore += 25
        if (biasShort) shortScore += 25
        if (setupLong) longScore += 25
        if (setupShort) shortScore += 25
        if (triggerLong) longScore += 30
        if (triggerShort) shortScore += 30
        if (m1.volumeRatio >= 1.20 && m1.bullishMomentum) longScore += 10
        if (m1.volumeRatio >= 1.20 && m1.bearishMomentum) shortScore += 10
        if (m1.rsi in 50.0..70.0) longScore += 10
        if (m1.rsi in 30.0..50.0) shortScore += 10
        if (m1.breakoutUp || m1.retestUp) longScore += 10
        if (m1.breakoutDown || m1.retestDown) shortScore += 10

        val dominantScore = max(longScore, shortScore).coerceIn(0, 100)
        val directionalBias = when {
            biasLong -> SignalAction.BUY
            biasShort -> SignalAction.SELL
            else -> SignalAction.HOLD
        }
        val entryAction = when {
            biasLong && setupLong && triggerLong && !extremeVolatility -> SignalAction.BUY
            biasShort && setupShort && triggerShort && !extremeVolatility -> SignalAction.SELL
            else -> SignalAction.HOLD
        }

        val stage = when {
            entryAction == SignalAction.BUY && longScore >= 70 -> ScalpingStage.STRONG_ENTRY
            entryAction == SignalAction.SELL && shortScore >= 70 -> ScalpingStage.STRONG_ENTRY
            entryAction == SignalAction.BUY || entryAction == SignalAction.SELL -> ScalpingStage.ENTRY
            biasLong && (extendedLong || setupLong && !triggerLong || !setupLong && m1.bearishMomentum) -> ScalpingStage.WAIT_PULLBACK
            biasShort && (extendedShort || setupShort && !triggerShort || !setupShort && m1.bullishMomentum) -> ScalpingStage.WAIT_PULLBACK
            directionalBias != SignalAction.HOLD -> ScalpingStage.WATCH
            dominantScore >= 45 -> ScalpingStage.WATCH
            else -> ScalpingStage.HOLD
        }

        if (extendedLong) reasons += "RSI 1H/15M sudah panas: tunggu pullback."
        if (extendedShort) reasons += "RSI 1H/15M sangat rendah: tunggu pullback."
        if (extremeVolatility) reasons += "ATR 1M > 4%: volatilitas ekstrem, entry ditahan."

        var sl = 0.0
        var tp1 = 0.0
        var tp2 = 0.0
        var stopDistance = 0.0
        var tp1Distance = 0.0
        var tp2Distance = 0.0
        var rr = "Belum ada posisi"

        if (entryAction != SignalAction.HOLD) {
            val rawStopDistance = atr * 0.9
            val rawTp1Distance = atr * 1.4
            val rawTp2Distance = atr * 2.2
            if (entryAction == SignalAction.BUY) {
                sl = price - rawStopDistance
                tp1 = price + rawTp1Distance
                tp2 = price + rawTp2Distance
                val structure = MarketStructureAnalyzer.analyze(m1Candles.takeLast(min(40, m1Candles.size)))
                val swingLow = structure.lastSwingLow ?: 0.0
                if (structure.dataEnough && swingLow > 0.0 && swingLow < price) sl = min(sl, swingLow - atr * 0.20)
                val resistance = structure.resistance ?: 0.0
                if (structure.dataEnough && resistance > price) tp1 = min(tp1, resistance)
            } else {
                sl = price + rawStopDistance
                tp1 = price - rawTp1Distance
                tp2 = price - rawTp2Distance
                val structure = MarketStructureAnalyzer.analyze(m1Candles.takeLast(min(40, m1Candles.size)))
                val swingHigh = structure.lastSwingHigh ?: 0.0
                if (structure.dataEnough && swingHigh > price) sl = max(sl, swingHigh + atr * 0.20)
                val support = structure.support ?: 0.0
                if (structure.dataEnough && support > 0.0 && support < price) tp1 = max(tp1, support)
            }
            stopDistance = abs(price - sl)
            tp1Distance = abs(tp1 - price)
            tp2Distance = abs(tp2 - price)
            val valid = stopDistance > 0.0 && stopDistance < price * 0.10 &&
                tp1Distance >= stopDistance && tp2Distance >= stopDistance * 1.5 &&
                if (entryAction == SignalAction.BUY) tp1 > price && tp2 > tp1 else tp1 < price && tp2 < tp1
            if (!valid) {
                reasons += "Entry dibatalkan: TP/SL tidak memenuhi RR minimum."
                _signalState.value = buildScalpingState(ScalpingStage.WATCH, SignalAction.HOLD, dominantScore, price, 0.0, 0.0, 0.0, "Risk/reward tidak layak", reasons)
                return
            }
            rr = "TP1 1:${format(tp1Distance / stopDistance)} | TP2 1:${format(tp2Distance / stopDistance)}"
        }

        val finalAction = if (stage == ScalpingStage.ENTRY || stage == ScalpingStage.STRONG_ENTRY) entryAction else SignalAction.HOLD
        val sentiment = when {
            finalAction == SignalAction.BUY -> TrendSentiment.STRONG_BULLISH_CONTINUATION
            finalAction == SignalAction.SELL -> TrendSentiment.BEARISH_DISTRIBUTION
            biasLong -> TrendSentiment.ACCUMULATION_SQUEEZE
            biasShort -> TrendSentiment.BEARISH_DISTRIBUTION
            else -> TrendSentiment.NEUTRAL_CONSOLIDATION
        }
        val actionScore = when {
            finalAction == SignalAction.BUY -> longScore
            finalAction == SignalAction.SELL -> shortScore
            else -> dominantScore
        }.coerceIn(0, 100)
        reasons += "STATUS: ${stage.displayName}."

        _indicators.value = TechnicalIndicators(
            rsi14 = m1.rsi,
            macd = m1.macdHist,
            macdSignal = 0.0,
            macdHist = m1.macdHist,
            ema20 = m1.emaFast,
            ema50 = m1.emaSlow,
            ema200 = Double.NaN,
            bbUpper = Double.NaN,
            bbLower = Double.NaN,
            atr = m1.atr,
            momentum = if (m1.candles.size > 4) {
                val base = m1.candles[m1.candles.lastIndex - 4].close
                if (base > 0) (m1.price - base) / base else 0.0
            } else 0.0
        )
        _signalState.value = AISignalState(
            action = finalAction,
            confidence = actionScore,
            sentiment = sentiment,
            entryPrice = price,
            targetPrice1 = if (finalAction == SignalAction.HOLD) 0.0 else tp1,
            targetPrice2 = if (finalAction == SignalAction.HOLD) 0.0 else tp2,
            stopLoss = if (finalAction == SignalAction.HOLD) 0.0 else sl,
            riskRewardRatio = rr,
            probabilityScore = 0.0,
            patternDetected = null,
            reasoning = reasons.take(9),
            timestamp = System.currentTimeMillis(),
            scalpingStage = stage
        )
    }

    private fun buildScalpingState(
        stage: ScalpingStage,
        action: SignalAction,
        score: Int,
        price: Double,
        tp1: Double,
        tp2: Double,
        sl: Double,
        rr: String,
        reasons: List<String>
    ): AISignalState = AISignalState(
        action = action,
        confidence = score.coerceIn(0, 100),
        sentiment = if (stage == ScalpingStage.WAIT_PULLBACK) TrendSentiment.ACCUMULATION_SQUEEZE else TrendSentiment.NEUTRAL_CONSOLIDATION,
        entryPrice = price,
        targetPrice1 = tp1,
        targetPrice2 = tp2,
        stopLoss = sl,
        riskRewardRatio = rr,
        probabilityScore = 0.0,
        reasoning = (reasons + "STATUS: ${stage.displayName}.").take(9),
        timestamp = System.currentTimeMillis(),
        scalpingStage = stage
    )

    private fun evaluate() {
        if (isScalpingMode) {
            if (h1Candles.isNotEmpty() && m15Candles.isNotEmpty() && m1Candles.isNotEmpty()) evaluateScalpingMtf()
            return
        }
        val tick = currentTick ?: return
        val price = tick.price
        val history = synchronized(candles) { candles.toList() }
        val minCandles = 35
        if (history.size < minCandles) {
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
                reasoning = listOf(
                    "Data candle INDODAX nyata belum cukup untuk analisis penuh.",
                    "Dibutuhkan minimal $minCandles candle real.",
                    "Tidak ada data contoh/mock yang digunakan."
                ),
                timestamp = System.currentTimeMillis(),
                scalpingStage = ScalpingStage.HOLD
            )
            return
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

        _indicators.value = TechnicalIndicators(
            rsi14 = rsi, macd = macd, macdSignal = macdSignal, macdHist = macdHist,
            ema20 = emaFast, ema50 = emaSlow, ema200 = ema200,
            bbUpper = bb.second, bbLower = bb.first, atr = atr, momentum = momentum
        )

        var buy = 0.0
        var sell = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Market regime: $regime."
        when {
            rsi < 30.0 -> { buy += 20.0; reasons += "RSI ${format(rsi)}: jenuh jual." }
            rsi > 70.0 -> { sell += 20.0; reasons += "RSI ${format(rsi)}: jenuh beli." }
            else -> reasons += "RSI ${format(rsi)}: netral."
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
            if (history.last().close >= history.last().open) { buy += 10.0; reasons += "Volume ${format(ratio)}×: lonjakan beli." }
            else { sell += 10.0; reasons += "Volume ${format(ratio)}×: lonjakan jual." }
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
        else "TP1 1:${format(tp1Distance / stopDistance)} | TP2 1:${format(tp2Distance / stopDistance)}"
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
            reasoning = reasons.take(6),
            timestamp = System.currentTimeMillis(),
            scalpingStage = ScalpingStage.HOLD
        )
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}
