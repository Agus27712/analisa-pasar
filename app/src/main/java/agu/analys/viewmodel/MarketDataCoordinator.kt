package agu.analys.viewmodel

import agu.analys.config.MarketDataSource
import agu.analys.engine.LearningTradingEngine
import agu.analys.model.CandleBar
import agu.analys.model.MarketConnectionState
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
import agu.analys.model.TradingPair
import agu.analys.service.IndodaxMarketService
import agu.analys.service.IndodaxMarketWebSocket
import agu.analys.util.AppPreferences
import agu.analys.util.MarketDataCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MarketDataCoordinator(
    private val scope: CoroutineScope,
    private val prefs: AppPreferences,
    private val marketCache: MarketDataCache,
    private val engine: LearningTradingEngine,
    private val simCoordinator: SimulationCoordinator,
    private val onPriceUpdate: (String, Double, Double?) -> Unit
) {
    private val _connectionState = MutableStateFlow<MarketConnectionState>(MarketConnectionState.Loading)
    val connectionState: StateFlow<MarketConnectionState> = _connectionState.asStateFlow()

    private val _currentTick = MutableStateFlow<MarketTick?>(null)
    val currentTick: StateFlow<MarketTick?> = _currentTick.asStateFlow()

    private val _recentPrices = MutableStateFlow<List<Double>>(emptyList())
    val recentPrices: StateFlow<List<Double>> = _recentPrices.asStateFlow()

    private val _recentCandles = MutableStateFlow<List<CandleBar>>(emptyList())
    val recentCandles: StateFlow<List<CandleBar>> = _recentCandles.asStateFlow()

    private val _orderBookBids = MutableStateFlow<List<OrderBookItem>>(emptyList())
    val orderBookBids: StateFlow<List<OrderBookItem>> = _orderBookBids.asStateFlow()

    private val _orderBookAsks = MutableStateFlow<List<OrderBookItem>>(emptyList())
    val orderBookAsks: StateFlow<List<OrderBookItem>> = _orderBookAsks.asStateFlow()

    private val _tradeStream = MutableStateFlow<List<TradeStreamItem>>(emptyList())
    val tradeStream: StateFlow<List<TradeStreamItem>> = _tradeStream.asStateFlow()

    private val _dashboardTicks = MutableStateFlow<Map<String, MarketTick>>(emptyMap())
    val dashboardTicks: StateFlow<Map<String, MarketTick>> = _dashboardTicks.asStateFlow()

    private val _isShowingCachedData = MutableStateFlow(false)
    val isShowingCachedData: StateFlow<Boolean> = _isShowingCachedData.asStateFlow()

    private var lastLiveTickAt = 0L
    private var wsLive = false
    private var lastCandleRefresh = 0L
    private var lastDepthRefresh = 0L
    private var marketPollJob: Job? = null
    private var dashboardPollJob: Job? = null

    private val indodaxWebSocket = IndodaxMarketWebSocket(
        scope = scope,
        onTick = { handleWebSocketTick(it) },
        onCandle = { candle -> 
            engine.currentFormingVolume = candle.volume
            engine.onCandleUpdate(candle)
        },
        onConnected = {
            wsLive = true
            lastLiveTickAt = System.currentTimeMillis()
            _connectionState.value = MarketConnectionState.Connected
            _isShowingCachedData.value = false
        },
        onDisconnected = {
            wsLive = false
            val recentRest = System.currentTimeMillis() - lastLiveTickAt < 12_000L
            if (!recentRest && _currentTick.value == null) {
                _connectionState.value = MarketConnectionState.ConnectionLost("Realtime terputus. REST fallback...")
            }
        }
    )

    private fun handleWebSocketTick(tick: MarketTick) {
        val selected = _currentTick.value?.symbol ?: return
        if (!tick.symbol.equals(selected, true) && !tick.symbol.equals(selected.replace("_", ""), true)) return

        lastLiveTickAt = System.currentTimeMillis()
        wsLive = true
        if (_connectionState.value !is MarketConnectionState.Connected) {
            _connectionState.value = MarketConnectionState.Connected
            _isShowingCachedData.value = false
        }
        val previous = _currentTick.value
        val normalized = tick.copy(
            symbol = selected,
            high24h = previous?.high24h ?: tick.price,
            low24h = previous?.low24h ?: tick.price,
            volume24h = previous?.volume24h ?: 0.0,
            change24h = previous?.change24h ?: Double.NaN
        )
        _currentTick.value = normalized
        engine.onTickUpdate(normalized)
        updateRecentPrices(normalized.price)
        simCoordinator.onPriceTick(normalized.symbol, normalized.price, normalized.high24h, normalized.low24h)
        onPriceUpdate(normalized.symbol, normalized.price, engine.indicators.value.rsi14.takeIf { it.isFinite() })
    }

    private fun updateRecentPrices(price: Double) {
        val prices = _recentPrices.value.toMutableList().apply { add(price) }
        if (prices.size > 50) prices.removeAt(0)
        _recentPrices.value = prices
    }

    fun restoreFromCache(source: MarketDataSource) {
        val ticks = marketCache.loadDashboardTicks(source)
        if (ticks.isNotEmpty()) {
            _dashboardTicks.value = ticks
            _isShowingCachedData.value = true
        }
    }

    fun loadPairCache(symbol: String, timeframe: Timeframe): Boolean {
        val (cachedTick, cachedCandles) = marketCache.loadPairSnapshot(symbol, timeframe)
        if (cachedTick != null || cachedCandles.isNotEmpty()) {
            if (cachedTick != null) _currentTick.value = cachedTick
            if (cachedCandles.isNotEmpty()) {
                _recentCandles.value = cachedCandles
                engine.resetForOffline()
                cachedTick?.let { engine.onTickUpdate(it) }
            }
            _isShowingCachedData.value = true
            return true
        }
        return false
    }

    fun startMarketPolling(pair: TradingPair, timeframe: Timeframe) {
        marketPollJob?.cancel()
        indodaxWebSocket.start(pair.symbol)
        marketPollJob = scope.launch {
            if (_currentTick.value == null) _connectionState.value = MarketConnectionState.Loading
            var failCount = 0
            lastCandleRefresh = 0L
            lastDepthRefresh = 0L
            while (isActive) {
                if (indodaxWebSocket.isStale(25_000L)) indodaxWebSocket.start(pair.symbol)
                val prev = _currentTick.value?.price ?: 0.0
                val tick = IndodaxMarketService.fetchTicker(pair.effectiveIndodaxPair(), prevPrice = prev)
                if (tick != null && tick.price > 0) {
                    failCount = 0
                    lastLiveTickAt = System.currentTimeMillis()
                    _connectionState.value = MarketConnectionState.Connected
                    _isShowingCachedData.value = false
                    val normalizedTick = tick.copy(symbol = pair.symbol)
                    val preferWs = wsLive && System.currentTimeMillis() - lastLiveTickAt < 2_000L
                    if (!preferWs || _currentTick.value == null) {
                        _currentTick.value = normalizedTick
                        engine.onTickUpdate(normalizedTick)
                    }
                    updateRecentPrices(normalizedTick.price)
                    _dashboardTicks.value = _dashboardTicks.value.toMutableMap().apply { put(pair.symbol, normalizedTick) }
                    simCoordinator.onPriceTick(normalizedTick.symbol, normalizedTick.price, normalizedTick.high24h, normalizedTick.low24h)
                    onPriceUpdate(normalizedTick.symbol, normalizedTick.price, engine.indicators.value.rsi14.takeIf { it.isFinite() })
                    
                    val now = System.currentTimeMillis()
                    if (now - lastCandleRefresh >= 15_000L) {
                        val candles = IndodaxMarketService.fetchCandles(pair.effectiveIndodaxPair(), timeframe, 300)
                        if (candles.size >= 30) {
                            _recentCandles.value = candles
                            engine.resetForOffline(preserveState = true)
                            engine.onTickUpdate(normalizedTick)
                            lastCandleRefresh = now
                            marketCache.savePairSnapshot(pair.symbol, timeframe, normalizedTick, candles)
                        }
                    }
                    if (now - lastDepthRefresh >= 5_000L) {
                        val depth = async { IndodaxMarketService.fetchOrderBook(pair.effectiveIndodaxPair()) }
                        val trades = async { IndodaxMarketService.fetchRecentTrades(pair.effectiveIndodaxPair()) }
                        val (bids, asks) = depth.await()
                        val newTrades = trades.await()
                        if (bids.isNotEmpty()) _orderBookBids.value = bids
                        if (asks.isNotEmpty()) _orderBookAsks.value = asks
                        if (bids.isNotEmpty() || asks.isNotEmpty()) engine.onOrderBookUpdate(bids, asks)
                        if (newTrades.isNotEmpty()) _tradeStream.value = newTrades
                        lastDepthRefresh = now
                    }
                } else {
                    failCount++
                    if (failCount >= 4 && System.currentTimeMillis() - lastLiveTickAt > 20_000L) {
                        _isShowingCachedData.value = true
                        _connectionState.value = MarketConnectionState.ConnectionLost("Koneksi Indodax lemah. Pakai cache.")
                    }
                    delay(4000L); continue
                }
                delay(3000L)
            }
        }
    }

    fun stopPolling() {
        marketPollJob?.cancel()
        indodaxWebSocket.stop(false)
    }

    fun startDashboardPolling(onDashboardUpdate: (Map<String, MarketTick>) -> Unit) {
        dashboardPollJob?.cancel()
        dashboardPollJob = scope.launch {
            while (isActive) {
                onDashboardUpdate(_dashboardTicks.value)
                delay(15_000L)
            }
        }
    }

    fun updateDashboardTicks(ticks: Map<String, MarketTick>) {
        _dashboardTicks.value = _dashboardTicks.value + ticks
    }

    fun markOffline(reason: String) {
        marketPollJob?.cancel()
        val lastPrice = _currentTick.value?.price ?: 0.0
        val lastCandles = _recentCandles.value
        if (_dashboardTicks.value.isEmpty()) {
            _currentTick.value = null
            _recentPrices.value = emptyList()
            _recentCandles.value = emptyList()
            _orderBookBids.value = emptyList()
            _orderBookAsks.value = emptyList()
            _tradeStream.value = emptyList()
            engine.resetForOffline(false, lastPrice, lastCandles)
        } else engine.resetForOffline(false, lastPrice, lastCandles)
        _isShowingCachedData.value = _dashboardTicks.value.isNotEmpty() || _currentTick.value != null
        _connectionState.value = MarketConnectionState.ConnectionLost(reason)
    }

    fun clearPairData() {
        _currentTick.value = null
        _recentPrices.value = emptyList()
        _recentCandles.value = emptyList()
        _orderBookBids.value = emptyList()
        _orderBookAsks.value = emptyList()
        _tradeStream.value = emptyList()
    }
}
