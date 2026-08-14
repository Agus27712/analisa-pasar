package agu.analys.engine

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
import kotlin.math.sqrt

/** Real-data technical-analysis engine for learning. It never invents market data. */
class LearningTradingEngine(
    @Suppress("UNUSED_PARAMETER") private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
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

    fun resetForOffline() {
        currentTick = null
        mtfRefreshJob?.cancel()
        mtfRefreshJob = null
        lastMtfRefresh = 0L
        mtfSymbol = ""
        h1Candles = emptyList()
        m15Candles = emptyList()
        m1Candles = emptyList()
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

    private fun analyzeFrame(history: List<CandleBar>, rsiPeriod: Int = 7, fastPeriod: Int = 5, slowPeriod: Int = 13, macdFast: Int = 5, macdSlow: Int = 12, macdSignal: Int = 4): FrameSignal? {
        if (history.size < maxOf(30, slowPeriod + 5)) return null
        val closes = history.map { it.close }
        val price = closes.last()
        val rsi = calculateRsi(history, rsiPeriod)
        val emaFast = calculateEma(closes, fastPeriod)
        val emaSlow = calculateEma(closes, slowPeriod)
        val macdSeries = calculateMacdSeries(closes, macdFast, macdSlow, macdSignal)
        val macdHist = macdSeries.last().first - macdSeries.last().second
        val atr = calculateAtr(history, 7)
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
        val momentumCoolingLong = m1.rsi in 45.0..55.0 && !m1.bearishMomentum
        val momentumCoolingShort = m1.rsi in 45.0..55.0 && !m1.bullishMomentum
        val extendedLong = h1.rsi > 75.0 || m15.rsi > 75.0
        val extendedShort = h1.rsi < 25.0 || m15.rsi < 25.0
        val extremeVolatility = atr / price > 0.04

        var longScore = 0
        var shortScore = 0
        val reasons = mutableListOf<String>()
        reasons += "[SCALPING MTF] 1H = bias, 15M = setup, 1M = trigger."
        reasons += "1H: ${if (biasLong) "bullish" else if (biasShort) "bearish" else "mixed"}, RSI ${format(h1.rsi)}."
        reasons += "15M: ${if (setupLong) "bullish setup" else if (setupShort) "bearish setup" else "pullback/mixed"}, RSI ${format(m15.rsi)}, MACD ${if (m15.bullishMomentum) "bullish" else if (m15.bearishMomentum) "bearish" else "netral"}."
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

        if (extendedLong) reasons += "RSI 1H/15M sudah panas: jangan kejar harga; tunggu pullback."
        if (extendedShort) reasons += "RSI 1H/15M sudah sangat rendah: jangan kejar short; tunggu pullback."
        if (extremeVolatility) reasons += "ATR 1M > 4%: volatilitas ekstrem, entry baru ditahan."
        if (m15.bearishMomentum && biasLong) reasons += "Trend besar bullish tetapi momentum 15M sedang pullback."
        if (m15.bullishMomentum && biasShort) reasons += "Trend besar bearish tetapi momentum 15M sedang pullback."

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
            val valid = stopDistance > 0.0 && stopDistance < price * 0.10 && tp1Distance >= stopDistance && tp2Distance >= stopDistance * 1.5 && if (entryAction == SignalAction.BUY) tp1 > price && tp2 > tp1 else tp1 < price && tp2 < tp1
            if (!valid) {
                reasons += "Entry dibatalkan: TP/SL tidak memenuhi RR minimum 1R / 1.5R."
                rr = "Risk/reward tidak layak"
                _signalState.value = buildScalpingState(ScalpingStage.WATCH, SignalAction.HOLD, dominantScore, price, 0.0, 0.0, 0.0, rr, reasons)
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
        if (stage == ScalpingStage.ENTRY || stage == ScalpingStage.STRONG_ENTRY) reasons += "Trigger 1M sudah searah dengan bias 1H dan setup 15M. Entry hanya berlaku selama kondisi ini masih valid."
        else if (stage == ScalpingStage.WAIT_PULLBACK) reasons += "Belum entry. Tunggu pullback selesai lalu tunggu trigger 1M kembali searah."
        else if (stage == ScalpingStage.WATCH) reasons += "Trend/bias mulai terbentuk, tetapi trigger belum cukup untuk entry."

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

    private fun buildScalpingState(stage: ScalpingStage, action: SignalAction, score: Int, price: Double, tp1: Double, tp2: Double, sl: Double, rr: String, reasons: List<String>): AISignalState {
        return AISignalState(
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
    }

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
                reasoning = listOf("Data candle INDODAX nyata belum cukup untuk analisis penuh.", "Dibutuhkan minimal $minCandles candle real.", "Tidak ada data contoh/mock yang digunakan."),
                timestamp = System.currentTimeMillis(),
                scalpingStage = ScalpingStage.HOLD
            )
            return
        }

        val closes = history.map { it.close }
        val rsiPeriod = 14
        val rsi = calculateRsi(history, rsiPeriod)
        val fastEmaPeriod = 20
        val slowEmaPeriod = 50
        val emaFast = calculateEma(closes, fastEmaPeriod)
        val emaSlow = calculateEma(closes, slowEmaPeriod)
        val macdFast = 12
        val macdSlow = 26
        val macdSignalPeriod = 9
        val macdSeries = calculateMacdSeries(closes, macdFast, macdSlow, macdSignalPeriod)
        val macd = macdSeries.last().first
        val macdSignal = macdSeries.last().second
        val macdHist = macd - macdSignal
        val bbPeriod = 20
        val bb = calculateBollinger(closes, bbPeriod)
        val atrPeriod = 14
        val atr = calculateAtr(history, atrPeriod)
        val pattern = detectPattern(history)
        val regime = detectMarketRegime(price, emaFast, emaSlow, macdHist, rsi, atr, bb)
        val marketStructure = MarketStructureAnalyzer.analyze(history)
        val momentumLookback = min(10, closes.size - 1)
        val momentumBase = closes[closes.lastIndex - momentumLookback]
        val momentum = if (momentumBase > 0.0) (price - momentumBase) / momentumBase else 0.0
        val ema200 = if (closes.size >= 200) calculateEma(closes, 200) else Double.NaN

        _indicators.value = TechnicalIndicators(rsi14 = rsi, macd = macd, macdSignal = macdSignal, macdHist = macdHist, ema20 = emaFast, ema50 = emaSlow, ema200 = ema200, bbUpper = bb.second, bbLower = bb.first, atr = atr, momentum = momentum)

        var buy = 0.0
        var sell = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Market regime: $regime."
        when {
            rsi < 30.0 -> { buy += 20.0; reasons += "RSI ${format(rsi)}: jenuh jual; pantulan cepat diamati." }
            rsi > 70.0 -> { sell += 20.0; reasons += "RSI ${format(rsi)}: jenuh beli; risiko koreksi cepat." }
            else -> reasons += "RSI ${format(rsi)}: netral."
        }
        when {
            price > emaFast && emaFast > emaSlow -> { buy += 25.0; reasons += "EMA20 > EMA50 & harga di atas keduanya: struktur bullish." }
            price < emaFast && emaFast < emaSlow -> { sell += 25.0; reasons += "EMA20 < EMA50 & harga di bawah keduanya: struktur bearish." }
            else -> reasons += "EMA belum searah tegas."
        }
        when {
            macdHist > 0 && macd > macdSignal -> { buy += 20.0; reasons += "MACD histogram positif: momentum naik." }
            macdHist < 0 && macd < macdSignal -> { sell += 20.0; reasons += "MACD histogram negatif: momentum turun." }
            else -> reasons += "MACD netral."
        }
        when {
            price <= bb.first -> { buy += 10.0; reasons += "Harga di Bollinger Band bawah: area pantulan." }
            price >= bb.second -> { sell += 10.0; reasons += "Harga di Bollinger Band atas: area koreksi." }
            else -> reasons += "Harga di dalam Bollinger Band."
        }
        pattern?.let { if (it.contains("Bullish", true) || it.contains("Hammer", true)) { buy += 15.0; reasons += "Candlestick $it: sinyal bullish." } else if (it.contains("Bearish", true) || it.contains("Shooting", true)) { sell += 15.0; reasons += "Candlestick $it: sinyal bearish." } }
        val avgVolume = history.takeLast(5).map { it.volume }.average()
        val lastVolume = history.last().volume
        if (avgVolume > 0.0 && lastVolume >= avgVolume * 1.6) { val ratio = lastVolume / avgVolume; if (history.last().close >= history.last().open) { buy += 10.0; reasons += "Volume ${format(ratio)}× rata-rata: lonjakan beli." } else { sell += 10.0; reasons += "Volume ${format(ratio)}× rata-rata: lonjakan jual." } } else reasons += "Volume stabil."
        if (marketStructure.dataEnough) { when (marketStructure.trend) { "Bullish structure" -> { buy += 15.0; reasons += "Market structure bullish." }; "Bearish structure" -> { sell += 15.0; reasons += "Market structure bearish." }; else -> reasons += "Market structure konsolidasi." } } else reasons += "Market structure belum cukup."
        val structureBlocksBuy = marketStructure.dataEnough && marketStructure.trend == "Bearish structure"
        val structureBlocksSell = marketStructure.dataEnough && marketStructure.trend == "Bullish structure"
        val dominant = max(buy, sell)
        val conflict = abs(buy - sell) < 20.0
        val volatilityTooHigh = price > 0.0 && atr / price >= 0.08
        val minimumScore = if (regime == "HIGH VOLATILITY") 70.0 else 60.0
        val dominanceRatio = if (min(buy, sell) > 0.0) dominant / min(buy, sell) else Double.POSITIVE_INFINITY
        val weakDominance = dominanceRatio < 1.40
        val noTrade = regime == "SIDEWAYS / NO TRADE" || conflict || weakDominance || dominant < minimumScore || volatilityTooHigh
        val action = when { noTrade -> SignalAction.HOLD; buy >= minimumScore && buy > sell * 1.40 && !structureBlocksBuy -> SignalAction.BUY; sell >= minimumScore && sell > buy * 1.40 && !structureBlocksSell -> SignalAction.SELL; else -> SignalAction.HOLD }
        val score = ((dominant / 115.0) * 100.0).toInt().coerceIn(0, 100)
        if (volatilityTooHigh) reasons += "NO TRADE: Volatilitas ATR terlalu tinggi untuk mode aktif." else if (regime == "HIGH VOLATILITY") reasons += "NO TRADE ZONE: Pasar sangat volatil." else if (structureBlocksBuy && buy >= minimumScore) reasons += "NO TRADE: Bertentangan dengan market structure." else if (structureBlocksSell && sell >= minimumScore) reasons += "NO TRADE: Bertentangan dengan market structure." else reasons += "NO TRADE ZONE: Konvergensi belum cukup."
        if (action != SignalAction.HOLD) reasons[reasons.lastIndex] = "Score ${score}/100 = kekuatan setup."
        val rawStopDistance = atr * 1.5
        val rawTp1Distance = atr * 2.0
        val rawTp2Distance = atr * 3.5
        var sl = 0.0; var tp1 = 0.0; var tp2 = 0.0
        var stopDistance = 0.0; var tp1Distance = 0.0; var tp2Distance = 0.0
        when (action) {
            SignalAction.BUY -> { sl = price - rawStopDistance; tp1 = price + rawTp1Distance; tp2 = price + rawTp2Distance; val swingLow = marketStructure.lastSwingLow ?: 0.0; if (marketStructure.dataEnough && swingLow > 0.0 && swingLow < price) sl = min(sl, swingLow - atr * 0.25); val resistance = marketStructure.resistance ?: 0.0; if (marketStructure.dataEnough && resistance > price) tp1 = min(tp1, resistance) }
            SignalAction.SELL -> { sl = price + rawStopDistance; tp1 = price - rawTp1Distance; tp2 = price - rawTp2Distance; val swingHigh = marketStructure.lastSwingHigh ?: 0.0; if (marketStructure.dataEnough && swingHigh > price) sl = max(sl, swingHigh + atr * 0.25); val support = marketStructure.support ?: 0.0; if (marketStructure.dataEnough && support > 0.0 && support < price) tp1 = max(tp1, support) }
            SignalAction.HOLD -> Unit
        }
        if (action != SignalAction.HOLD) { stopDistance = abs(price - sl); tp1Distance = abs(tp1 - price); tp2Distance = abs(tp2 - price) }
        val levelsValid = when (action) { SignalAction.BUY -> stopDistance > 0.0 && stopDistance < price * 0.08 && tp1 > price && tp2 > tp1 && tp1Distance >= stopDistance && tp2Distance >= stopDistance * 1.5 && sl > 0.0; SignalAction.SELL -> stopDistance > 0.0 && stopDistance < price * 0.08 && tp1 < price && tp2 < tp1 && tp1Distance >= stopDistance && tp2Distance >= stopDistance * 1.5; SignalAction.HOLD -> false }
        val finalAction = if (action != SignalAction.HOLD && levelsValid) action else SignalAction.HOLD
        val finalScore = if (finalAction == SignalAction.HOLD) min(59, score) else score
        if (action != SignalAction.HOLD && finalAction == SignalAction.HOLD) reasons += "Setup dibatalkan: TP/SL tidak memiliki RR minimum atau level risiko terlalu lebar."
        val rr = if (finalAction == SignalAction.HOLD || stopDistance <= 0.0) "Tidak ada posisi" else "TP1 1:${format(tp1Distance / stopDistance)} | TP2 1:${format(tp2Distance / stopDistance)}"
        val sentiment = when (finalAction) { SignalAction.BUY -> if (pattern?.contains("Engulfing", true) == true) TrendSentiment.BULLISH_REVERSAL else TrendSentiment.STRONG_BULLISH_CONTINUATION; SignalAction.SELL -> if (pattern?.contains("Engulfing", true) == true) TrendSentiment.BEARISH_BREAKDOWN else TrendSentiment.BEARISH_DISTRIBUTION; SignalAction.HOLD -> TrendSentiment.NEUTRAL_CONSOLIDATION }
        _signalState.value = AISignalState(action = finalAction, confidence = finalScore, sentiment = sentiment, entryPrice = price, targetPrice1 = if (finalAction == SignalAction.HOLD) 0.0 else tp1, targetPrice2 = if (finalAction == SignalAction.HOLD) 0.0 else tp2, stopLoss = if (finalAction == SignalAction.HOLD) 0.0 else sl, riskRewardRatio = rr, probabilityScore = 0.0, patternDetected = pattern, reasoning = reasons.take(6), timestamp = System.currentTimeMillis(), scalpingStage = ScalpingStage.HOLD)
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
        for (i in 1..period) { val change = history[i].close - history[i - 1].close; if (change >= 0.0) gain += change else loss += -change }
        var averageGain = gain / period
        var averageLoss = loss / period
        for (i in period + 1 until history.size) { val change = history[i].close - history[i - 1].close; val currentGain = max(change, 0.0); val currentLoss = max(-change, 0.0); averageGain = ((averageGain * (period - 1)) + currentGain) / period; averageLoss = ((averageLoss * (period - 1)) + currentLoss) / period }
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

    private fun calculateMacdSeries(closes: List<Double>, fastPeriod: Int, slowPeriod: Int, signalPeriod: Int): List<Pair<Double, Double>> {
        val emaFast = calculateEmaSeries(closes, fastPeriod)
        val emaSlow = calculateEmaSeries(closes, slowPeriod)
        val macd = emaFast.indices.map { emaFast[it] - emaSlow[it] }
        val signal = calculateEmaSeries(macd, signalPeriod)
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
        val trs = (start until history.size).map { i -> max(history[i].high - history[i].low, max(abs(history[i].high - history[i - 1].close), abs(history[i].low - history[i - 1].close))) }
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