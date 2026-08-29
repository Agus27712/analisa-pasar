package agu.analys.engine

import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.scalping.ScalpingMtfEvaluator
import agu.analys.engine.secondwave.SecondWaveEvaluator
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
    var scalpingSensitivity = ScalpingSensitivity.CONSERVATIVE
    var tradingFees = TradingFeeConfig()

    private val candles = mutableListOf<CandleBar>()
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

    var currentFormingVolume: Double = 0.0

    fun onTickUpdate(tick: MarketTick) {
        if (tick.price <= 0.0) return
        currentTick = tick

        when (strategyMode) {
            StrategyMode.SCALPING -> {
                if (m1Candles.isNotEmpty()) runScalping()
            }
            StrategyMode.SECOND_WAVE -> {
                if (m15Candles.isNotEmpty()) runSecondWave()
            }
            StrategyMode.SWING -> {
                val hasCandles = synchronized(candles) { candles.isNotEmpty() }
                if (hasCandles) runSwing()
            }
        }

        refreshScalpingTimeframesIfDue(tick.symbol)
    }

    private fun lastLastLow(currLow: Double, price: Double): Double = if (currLow <= 0.0) price else minOf(currLow, price)

    fun onCandleUpdate(candle: CandleBar) {
        if (candle.open <= 0.0 || candle.high <= 0.0 || candle.low <= 0.0 || candle.close <= 0.0) return
        synchronized(candles) {
            val index = candles.indexOfFirst { it.timestamp == candle.timestamp }
            if (index >= 0) candles[index] = candle else candles.add(candle)
            candles.sortBy { it.timestamp }
            while (candles.size > 500) candles.removeAt(0)
        }
        when (strategyMode) {
            StrategyMode.SCALPING -> {
                if (candle.timestamp >= (m1Candles.lastOrNull()?.timestamp ?: 0L)) {
                    val updated = (m1Candles + candle).distinctBy { it.timestamp }.sortedBy { it.timestamp }.takeLast(250)
                    m1Candles = updated
                    runScalping()
                }
            }
            StrategyMode.SECOND_WAVE -> {
                if (candle.timestamp >= (m15Candles.lastOrNull()?.timestamp ?: 0L)) {
                    val updated = (m15Candles + candle).distinctBy { it.timestamp }.sortedBy { it.timestamp }.takeLast(250)
                    m15Candles = updated
                    runSecondWave()
                }
            }
            StrategyMode.SWING -> runSwing()
        }
    }

    fun resetForOffline(preserveState: Boolean = false, lastKnownPrice: Double = 0.0, cachedCandles: List<CandleBar> = emptyList()) {
        currentTick = null
        mtfRefreshJob?.cancel()
        mtfRefreshJob = null
        lastMtfRefresh = 0L
        mtfSymbol = ""
        h4Candles = emptyList(); h1Candles = emptyList(); m15Candles = emptyList(); m1Candles = emptyList()
        synchronized(candles) { candles.clear() }
        if (preserveState) return

        val priceText = if (lastKnownPrice > 0.0) "Rp ${String.format(java.util.Locale.US, "%,.0f", lastKnownPrice)}" else "Terakhir Disimpan"
        _indicators.value = TechnicalIndicators()
        _signalState.value = AISignalState(
            action = SignalAction.HOLD,
            confidence = 0,
            sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
            reasoning = listOf(
                "MODE OFFLINE: Terputus dari Server Indodax.",
                "Snapshot Harga Terakhir: $priceText",
                "Sinyal LIVE ditangguhkan untuk keamanan modal.",
                "Periksa koneksi internet / status API Indodax."
            ),
            timestamp = System.currentTimeMillis(),
            scalpingStage = ScalpingStage.HOLD,
            isOfflineMode = true,
            offlineSnapshotTime = System.currentTimeMillis(),
            offlineReason = "Koneksi terputus — Sinyal live dihentikan."
        )
    }

    private fun refreshScalpingTimeframesIfDue(symbol: String) {
        if (symbol.isBlank()) return
        val now = System.currentTimeMillis()
        val intervalMs = if (strategyMode == StrategyMode.SCALPING) 10_000L else 20_000L
        if (now - lastMtfRefresh < intervalMs && mtfSymbol == symbol) return
        if (mtfRefreshJob?.isActive == true) return
        lastMtfRefresh = now; mtfSymbol = symbol
        mtfRefreshJob = scope.launch {
            when (strategyMode) {
                StrategyMode.SECOND_WAVE -> {
                    val h4Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.H4, 120) }
                    val h1Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.H1, 150) }
                    val m15Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.M15, 200) }
                    val h4 = h4Job.await(); val h1 = h1Job.await(); val m15 = m15Job.await()
                    if (h4.size >= 20 && h1.size >= 20 && m15.size >= 20 && currentTick?.symbol == symbol) {
                        h4Candles = h4.dropLast(1); h1Candles = h1.dropLast(1); m15Candles = m15.dropLast(1)
                        runSecondWave()
                    }
                }
                StrategyMode.SCALPING -> {
                    val h1Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.H1, 150) }
                    val m15Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.M15, 200) }
                    val m1Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.M1, 250) }
                    val h1 = h1Job.await(); val m15 = m15Job.await(); val m1 = m1Job.await()
                    if (h1.size >= 55 && m15.size >= 55 && m1.size >= 55 && currentTick?.symbol == symbol) {
                        h1Candles = h1.dropLast(1); m15Candles = m15.dropLast(1)
                        m1Candles = m1.dropLast(1)
                        runScalping()
                    }
                }
                StrategyMode.SWING -> {
                    val h1Job = async { IndodaxMarketService.fetchCandles(symbol, Timeframe.H1, 200) }
                    val h1 = h1Job.await()
                    if (h1.isNotEmpty() && currentTick?.symbol == symbol) {
                        val closedH1 = h1.dropLast(1)
                        synchronized(candles) {
                            if (candles.isEmpty() || candles.size < closedH1.size) {
                                candles.clear()
                                candles.addAll(closedH1)
                            }
                        }
                        runSwing()
                    }
                }
            }
        }
    }

    private fun runScalping() {
        val tick = currentTick ?: return
        if (h1Candles.size < 55 || m15Candles.size < 55 || m1Candles.size < 55) return
        val result = ScalpingMtfEvaluator.evaluate(tick.price, h1Candles, m15Candles, m1Candles, currentFormingVolume, tradingFees, scalpingSensitivity) ?: return
        
        // P2.2 Signal Lifecycle Tracking
        val tracked = agu.analys.engine.scalping.SignalLifecycleManager.process(tick.symbol, tick.price, result.signal)
        val finalSignal = (tracked.activeSignalState ?: result.signal).copy(
            lifecycleState = tracked.state
        )
        
        _indicators.value = result.indicators
        _signalState.value = finalSignal
    }

    private fun runSecondWave() {
        val tick = currentTick ?: return
        if (h4Candles.size < 20 || h1Candles.size < 20 || m15Candles.size < 20) return
        val result = SecondWaveEvaluator.evaluate(tick.price, h4Candles, h1Candles, m15Candles, tradingFees)
        _indicators.value = result.indicators
        _signalState.value = result.signal
    }

    private fun runSwing() {
        if (strategyMode != StrategyMode.SWING) return
        val tick = currentTick ?: return
        val history = synchronized(candles) { candles.toList() }
        val result = SwingEvaluator.evaluate(tick.price, history, tradingFees)
        _indicators.value = result.indicators
        _signalState.value = result.signal
    }
}
