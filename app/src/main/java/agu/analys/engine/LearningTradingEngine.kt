package agu.analys.engine

import agu.analys.config.StrategyMode
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
import agu.analys.service.TokocryptoMarketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin orchestrator: realtime buffers + mode routing. Scoring stays in dedicated evaluators.
 *
 * Modified to support aggressive scalping, removing second wave and trenching.
 */
class LearningTradingEngine(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
    var strategyMode: StrategyMode = StrategyMode.SCALPING
    var isScalpingMode: Boolean
        get() = strategyMode == StrategyMode.SCALPING
        set(value) {
            if (value) {
                strategyMode = StrategyMode.SCALPING
            } else if (strategyMode == StrategyMode.SCALPING) {
                strategyMode = StrategyMode.SWING
            }
        }
    var tradingFees = TradingFeeConfig()

    private val candlesH1 = mutableListOf<CandleBar>()
    private val candlesH4 = mutableListOf<CandleBar>()
    private val candles1D = mutableListOf<CandleBar>()
    private var currentTick: MarketTick? = null
    private var mtfRefreshJob: Job? = null
    private var lastMtfRefresh = 0L
    private var mtfSymbol = ""
    private var h4Candles: List<CandleBar> = emptyList()
    private var h1Candles: List<CandleBar> = emptyList()
    private var m15Candles: List<CandleBar> = emptyList()
    private var m1Candles: List<CandleBar> = emptyList()
    private val _signalState = MutableStateFlow(AISignalState())
    val signalState: StateFlow<AISignalState> = _signalState.asStateFlow()
    private val _indicators = MutableStateFlow(TechnicalIndicators())
    val indicators: StateFlow<TechnicalIndicators> = _indicators.asStateFlow()

    var onCandidateSignalTransition: ((agu.analys.engine.scalping.SignalTransition) -> Unit)? = null

    var currentFormingVolume: Double = 0.0

    var currentOrderBookBids: List<agu.analys.model.OrderBookItem> = emptyList()
    var currentOrderBookAsks: List<agu.analys.model.OrderBookItem> = emptyList()

    fun onOrderBookUpdate(bids: List<agu.analys.model.OrderBookItem>, asks: List<agu.analys.model.OrderBookItem>) {
        currentOrderBookBids = bids
        currentOrderBookAsks = asks
    }

    fun onTickUpdate(tick: MarketTick) {
        if (tick.price <= 0.0) return
        currentTick = tick

        when (strategyMode) {
            StrategyMode.SCALPING -> {
                if (m1Candles.isNotEmpty()) runScalping()
            }
            StrategyMode.SWING -> {
                val hasCandles = synchronized(candlesH1) { candlesH1.isNotEmpty() }
                if (hasCandles) runSwing()
            }
            StrategyMode.OFFICE_DAILY -> {
                val hasCandles = synchronized(candlesH4) { candlesH4.isNotEmpty() }
                if (hasCandles) runIntraday()
            }
        }

        refreshScalpingTimeframesIfDue(tick.symbol)
    }

    fun onCandleUpdate(candle: CandleBar) {
        if (candle.open <= 0.0 || candle.high <= 0.0 || candle.low <= 0.0 || candle.close <= 0.0) return

        when (strategyMode) {
            StrategyMode.SCALPING -> {
                if (candle.timestamp >= (m1Candles.lastOrNull()?.timestamp ?: 0L)) {
                    val updated = (m1Candles + candle).distinctBy { it.timestamp }.sortedBy { it.timestamp }.takeLast(250)
                    m1Candles = updated
                    runScalping()
                }
            }
            else -> {}
        }
    }

    fun resetForOffline(preserveState: Boolean = false, lastKnownPrice: Double = 0.0, cachedCandles: List<CandleBar> = emptyList()) {
        currentTick = null
        mtfRefreshJob?.cancel()
        mtfRefreshJob = null
        lastMtfRefresh = 0L
        mtfSymbol = ""
        h4Candles = emptyList(); h1Candles = emptyList(); m15Candles = emptyList(); m1Candles = emptyList()
        synchronized(candlesH1) { candlesH1.clear() }
        synchronized(candlesH4) { candlesH4.clear() }
        synchronized(candles1D) { candles1D.clear() }
        if (preserveState) return

        val priceText = if (lastKnownPrice > 0.0) agu.analys.util.PriceFormatter.formatPrice(lastKnownPrice, showSymbol = true) else "Terakhir Disimpan"
        _indicators.value = TechnicalIndicators()
        _signalState.value = AISignalState(
            action = SignalAction.HOLD,
            confidence = 0,
            sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
            reasoning = listOf(
                "MODE OFFLINE: Terputus dari Server Tokocrypto.",
                "Snapshot Harga Terakhir: $priceText",
                "Sinyal LIVE ditangguhkan untuk keamanan modal."
            ),
            timestamp = System.currentTimeMillis(),
            scalpingStage = ScalpingStage.HOLD,
            isOfflineMode = true
        )
    }

    private fun isMatchingSymbol(symA: String, symB: String): Boolean {
        val a = symA.trim().uppercase().replace("_", "").replace("/", "").replace("-", "")
        val b = symB.trim().uppercase().replace("_", "").replace("/", "").replace("-", "")
        return a.isNotBlank() && a == b
    }

    private fun refreshScalpingTimeframesIfDue(symbol: String) {
        if (symbol.isBlank()) return
        val now = System.currentTimeMillis()
        val intervalMs = when (strategyMode) {
            StrategyMode.SCALPING -> 10_000L
            StrategyMode.OFFICE_DAILY -> 60_000L
            StrategyMode.SWING -> 30_000L
        }
        if (now - lastMtfRefresh < intervalMs && isMatchingSymbol(mtfSymbol, symbol)) return
        if (mtfRefreshJob?.isActive == true) return
        lastMtfRefresh = now; mtfSymbol = symbol
        mtfRefreshJob = scope.launch {
            when (strategyMode) {
                StrategyMode.SCALPING -> {
                    val activeEx = agu.analys.util.MtfCacheManager.activeExchange
                    agu.analys.util.MtfCacheManager.setActiveSymbol(symbol, activeEx)

                    var h1 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.H1, activeEx) ?: emptyList()
                    var m15 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M15, activeEx) ?: emptyList()
                    var m1 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M1, activeEx) ?: emptyList()

                    var attempts = 0
                    while ((h1.size < 20 || m15.size < 20 || m1.size < 20) && attempts < 5 && isActive) {
                        attempts++
                        kotlinx.coroutines.delay(250)
                        h1 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.H1, activeEx) ?: emptyList()
                        m15 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M15, activeEx) ?: emptyList()
                        m1 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M1, activeEx) ?: emptyList()
                    }

                    if (h1.size >= 20 && m15.size >= 20 && m1.size >= 20 && isMatchingSymbol(currentTick?.symbol ?: "", symbol)) {
                        h1Candles = h1.dropLast(1); m15Candles = m15.dropLast(1)
                        m1Candles = m1.dropLast(1)
                        runScalping()
                    } else if (h1.size < 20 || m15.size < 20 || m1.size < 20) {
                        lastMtfRefresh = 0L
                    }
                }
                StrategyMode.SWING -> {
                    val activeEx = agu.analys.util.MtfCacheManager.activeExchange
                    val h1Job = async {
                        if (activeEx.equals("INDODAX", true)) IndodaxMarketService.fetchCandles(symbol, Timeframe.H1, 200)
                        else TokocryptoMarketService.fetchCandles(symbol, Timeframe.H1, 200)
                    }
                    val d1Job = async {
                        if (activeEx.equals("INDODAX", true)) IndodaxMarketService.fetchCandles(symbol, Timeframe.D1, 100)
                        else TokocryptoMarketService.fetchCandles(symbol, Timeframe.D1, 100)
                    }
                    val h1 = h1Job.await()
                    val d1 = d1Job.await()
                    if (h1.isNotEmpty() && isMatchingSymbol(currentTick?.symbol ?: "", symbol)) {
                        val closedH1 = h1.dropLast(1)
                        synchronized(candlesH1) {
                            candlesH1.clear()
                            candlesH1.addAll(closedH1)
                        }
                        synchronized(candles1D) {
                            candles1D.clear()
                            candles1D.addAll(d1)
                        }
                        runSwing()
                    }
                }
                StrategyMode.OFFICE_DAILY -> {
                    val activeEx = agu.analys.util.MtfCacheManager.activeExchange
                    val h4Job = async {
                        if (activeEx.equals("INDODAX", true)) IndodaxMarketService.fetchCandles(symbol, Timeframe.H4, 200)
                        else TokocryptoMarketService.fetchCandles(symbol, Timeframe.H4, 200)
                    }
                    val d1Job = async {
                        if (activeEx.equals("INDODAX", true)) IndodaxMarketService.fetchCandles(symbol, Timeframe.D1, 100)
                        else TokocryptoMarketService.fetchCandles(symbol, Timeframe.D1, 100)
                    }
                    val h4 = h4Job.await()
                    val d1 = d1Job.await()
                    if (h4.isNotEmpty() && isMatchingSymbol(currentTick?.symbol ?: "", symbol)) {
                        val closedH4 = h4.dropLast(1)
                        synchronized(candlesH4) {
                            candlesH4.clear()
                            candlesH4.addAll(closedH4)
                        }
                        synchronized(candles1D) {
                            candles1D.clear()
                            candles1D.addAll(d1)
                        }
                        runIntraday()
                    }
                }
            }
        }
    }

    private fun runScalping() {
        val tick = currentTick ?: return
        if (h1Candles.size < 20 || m15Candles.size < 20 || m1Candles.size < 20) {
            val need = "H1 ${h1Candles.size}/20 · M15 ${m15Candles.size}/20 · M1 ${m1Candles.size}/20"
            _signalState.value = AISignalState(
                action = SignalAction.HOLD,
                confidence = 15,
                marketSymbol = tick.symbol,
                entryPrice = tick.price,
                reasoning = listOf("Menunggu data MTF scalping ($need)."),
                timestamp = System.currentTimeMillis(),
                scalpingStage = ScalpingStage.WATCH,
                isOfflineMode = false
            )
            return
        }

        val liveM1 = agu.analys.util.CandleTimeUtil.synthesizeRealtimeCandles(m1Candles, tick, Timeframe.M1)
        val liveM15 = agu.analys.util.CandleTimeUtil.synthesizeRealtimeCandles(m15Candles, tick, Timeframe.M15)

        val result = ScalpingMtfEvaluator.evaluate(
            globalContext = agu.analys.engine.global.GlobalMarketContext(),
            price = tick.price,
            h1Candles = h1Candles,
            m15Candles = liveM15,
            m1Candles = liveM1,
            formingVolume = currentFormingVolume,
            bids = currentOrderBookBids,
            asks = currentOrderBookAsks,
            fees = tradingFees,
            symbol = tick.symbol
        ) ?: return

        val tracked = agu.analys.engine.scalping.SignalLifecycleManager.process(tick.symbol, tick.price, result.signal, StrategyMode.SCALPING)
        val finalSignal = (tracked.activeSignalState ?: result.signal).copy(
            marketSymbol = tick.symbol,
            lifecycleState = tracked.state
        )

        _indicators.value = result.indicators
        _signalState.value = finalSignal
        tracked.transition?.let { trans ->
            if (trans.previousState != trans.newState || trans.hasTriggeringTransition) {
                val actEmoji = when (trans.signal.action) {
                    agu.analys.model.SignalAction.BUY -> "🟢 BUY"
                    agu.analys.model.SignalAction.SELL -> "🔴 SELL"
                    agu.analys.model.SignalAction.HOLD -> "⚪ HOLD"
                }
                val reason = trans.signal.reasoning.firstOrNull() ?: "-"
                agu.analys.util.AppLogManager.aiEngine(
                    "SignalEngine",
                    "[${trans.symbol}] Transisi SCALPING: ${trans.previousState} ➔ ${trans.newState} | Sinyal: $actEmoji (Confidence: ${trans.signal.confidence}%) | $reason"
                )
            }
            if (trans.hasTriggeringTransition) {
                onCandidateSignalTransition?.invoke(trans)
            }
        }
    }

    private fun runSwing() {
        if (strategyMode != StrategyMode.SWING) return
        val tick = currentTick ?: return
        val history = synchronized(candlesH1) { candlesH1.toList() }
        val dailyHistory = synchronized(candles1D) { candles1D.toList() }
        if (history.isEmpty()) return

        val anomalyResult = agu.analys.engine.regime.MacroAnomalyDetector.evaluate(dailyHistory, tick.price)
        val result = agu.analys.engine.swing.SwingEvaluator.evaluate(
            globalContext = agu.analys.engine.global.GlobalContextManager.context.value,
            price = tick.price,
            history = history,
            fees = tradingFees,
            macroAnomalyResult = anomalyResult,
            symbol = tick.symbol
        )

        val tracked = agu.analys.engine.scalping.SignalLifecycleManager.process(tick.symbol, tick.price, result.signal, StrategyMode.SWING)
        val finalSignal = (tracked.activeSignalState ?: result.signal).copy(
            marketSymbol = tick.symbol,
            lifecycleState = tracked.state
        )

        _indicators.value = result.indicators
        _signalState.value = finalSignal
        tracked.transition?.let { trans ->
            if (trans.previousState != trans.newState || trans.hasTriggeringTransition) {
                val actEmoji = when (trans.signal.action) {
                    agu.analys.model.SignalAction.BUY -> "🟢 BUY"
                    agu.analys.model.SignalAction.SELL -> "🔴 SELL"
                    agu.analys.model.SignalAction.HOLD -> "⚪ HOLD"
                }
                val reason = trans.signal.reasoning.firstOrNull() ?: "-"
                agu.analys.util.AppLogManager.aiEngine(
                    "SignalEngine",
                    "[${trans.symbol}] Transisi SWING: ${trans.previousState} ➔ ${trans.newState} | Sinyal: $actEmoji (Confidence: ${trans.signal.confidence}%) | $reason"
                )
            }
            if (trans.hasTriggeringTransition) {
                onCandidateSignalTransition?.invoke(trans)
            }
        }
    }

    private fun runIntraday() {
        if (strategyMode != StrategyMode.OFFICE_DAILY) return
        val tick = currentTick ?: return
        val history = synchronized(candlesH4) { candlesH4.toList() }
        val dailyHistory = synchronized(candles1D) { candles1D.toList() }
        if (history.isEmpty()) return

        val anomalyResult = agu.analys.engine.regime.MacroAnomalyDetector.evaluate(dailyHistory, tick.price)
        val result = agu.analys.engine.intraday.IntradayEvaluator.evaluate(
            agu.analys.engine.global.GlobalContextManager.context.value,
            tick.price,
            history,
            tradingFees,
            anomalyResult,
            symbol = tick.symbol
        )

        val tracked = agu.analys.engine.scalping.SignalLifecycleManager.process(tick.symbol, tick.price, result.signal, StrategyMode.OFFICE_DAILY)
        val finalSignal = (tracked.activeSignalState ?: result.signal).copy(
            marketSymbol = tick.symbol,
            lifecycleState = tracked.state
        )

        _indicators.value = result.indicators
        _signalState.value = finalSignal
        tracked.transition?.let { trans ->
            if (trans.previousState != trans.newState || trans.hasTriggeringTransition) {
                val actEmoji = when (trans.signal.action) {
                    agu.analys.model.SignalAction.BUY -> "🟢 BUY"
                    agu.analys.model.SignalAction.SELL -> "🔴 SELL"
                    agu.analys.model.SignalAction.HOLD -> "⚪ HOLD"
                }
                val reason = trans.signal.reasoning.firstOrNull() ?: "-"
                agu.analys.util.AppLogManager.aiEngine(
                    "SignalEngine",
                    "[${trans.symbol}] Transisi INTRADAY: ${trans.previousState} ➔ ${trans.newState} | Sinyal: $actEmoji (Confidence: ${trans.signal.confidence}%) | $reason"
                )
            }
            if (trans.hasTriggeringTransition) {
                onCandidateSignalTransition?.invoke(trans)
            }
        }
    }
}
