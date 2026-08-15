package agu.analys.engine

import agu.analys.config.TradingFeeConfig
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

/** Thin orchestrator: realtime buffers + mode routing. Scoring stays in dedicated evaluators. */
class LearningTradingEngine(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
    var isScalpingMode = false
    var tradingFees = TradingFeeConfig()

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
            val index = candles.indexOfFirst { it.timestamp == candle.timestamp }
            if (index >= 0) candles[index] = candle else candles.add(candle)
            candles.sortBy { it.timestamp }
            while (candles.size > 250) candles.removeAt(0)
        }
        if (isScalpingMode && candle.timestamp >= (m1Candles.lastOrNull()?.timestamp ?: 0L)) {
            val updated = (m1Candles + candle).distinctBy { it.timestamp }.sortedBy { it.timestamp }.takeLast(180)
            m1Candles = updated
            runScalping()
        } else if (!isScalpingMode) runSwing()
    }

    fun resetForOffline(preserveState: Boolean = false) {
        currentTick = null
        mtfRefreshJob?.cancel()
        mtfRefreshJob = null
        lastMtfRefresh = 0L
        mtfSymbol = ""
        h1Candles = emptyList(); m15Candles = emptyList(); m1Candles = emptyList()
        synchronized(candles) { candles.clear() }
        if (preserveState) return
        _indicators.value = TechnicalIndicators()
        _signalState.value = AISignalState(
            action = SignalAction.HOLD,
            confidence = 0,
            sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
            reasoning = listOf("OFFLINE: analisis dihentikan.", "Tidak ada harga/candle live yang dapat dipercaya."),
            timestamp = System.currentTimeMillis(),
            scalpingStage = ScalpingStage.HOLD
        )
    }

    private fun refreshScalpingTimeframesIfDue(symbol: String) {
        if (symbol.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastMtfRefresh < 30_000L && mtfSymbol == symbol) return
        if (mtfRefreshJob?.isActive == true) return
        lastMtfRefresh = now; mtfSymbol = symbol
        mtfRefreshJob = scope.launch {
            val h1Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.H1, 120) }
            val m15Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.M15, 160) }
            val m1Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.M1, 180) }
            val h1 = h1Job.await(); val m15 = m15Job.await(); val m1 = m1Job.await()
            if (h1.size >= 55 && m15.size >= 55 && m1.size >= 55 && currentTick?.symbol == symbol) {
                h1Candles = h1; m15Candles = m15
                // REST is bootstrap; realtime WebSocket candles replace/update the latest 1M candle.
                m1Candles = m1
                runScalping()
            }
        }
    }

    private fun runScalping() {
        val tick = currentTick ?: return
        if (h1Candles.size < 55 || m15Candles.size < 55 || m1Candles.size < 55) return
        val result = ScalpingMtfEvaluator.evaluate(tick.price, h1Candles, m15Candles, m1Candles, tradingFees) ?: return
        _indicators.value = result.indicators
        _signalState.value = result.signal
    }

    private fun runSwing() {
        if (isScalpingMode) return
        val tick = currentTick ?: return
        val history = synchronized(candles) { candles.toList() }
        val result = SwingEvaluator.evaluate(tick.price, history, tradingFees)
        _indicators.value = result.indicators
        _signalState.value = result.signal
    }
}
