package agu.analys.engine

import agu.analys.engine.scalping.ScalpingMtfEvaluator
import agu.analys.engine.swing.SwingEvaluator
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

/**
 * Thin orchestrator only:
 * - candle/tick buffers
 * - mode routing (swing vs scalping)
 * - MTF candle refresh for scalping
 *
 * Scoring lives in [SwingEvaluator] and [ScalpingMtfEvaluator].
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
        if (!isScalpingMode) runSwing()
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
                runScalping()
            }
        }
    }

    private fun runScalping() {
        val tick = currentTick ?: return
        val result = ScalpingMtfEvaluator.evaluate(tick.price, h1Candles, m15Candles, m1Candles) ?: return
        _indicators.value = result.indicators
        _signalState.value = result.signal
    }

    private fun runSwing() {
        if (isScalpingMode) {
            if (h1Candles.isNotEmpty() && m15Candles.isNotEmpty() && m1Candles.isNotEmpty()) runScalping()
            return
        }
        val tick = currentTick ?: return
        val history = synchronized(candles) { candles.toList() }
        val result = SwingEvaluator.evaluate(tick.price, history)
        _indicators.value = result.indicators
        _signalState.value = result.signal
    }
}
