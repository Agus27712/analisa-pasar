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

    private val _uiPriceThrottleMs = MutableStateFlow(prefs.priceFeedThrottleMs)
    val uiPriceThrottleMs: StateFlow<Long> = _uiPriceThrottleMs.asStateFlow()

    private val uiPriceThrottler = agu.analys.util.PriceFeedThrottler(
        scope = scope,
        initialThrottleIntervalMs = prefs.priceFeedThrottleMs,
        onEmit = { throttledTick ->
            dispatchThrottledTick(throttledTick)
        }
    )

    fun setPriceFeedThrottleMs(ms: Long) {
        val coerced = ms.coerceAtLeast(0L)
        prefs.priceFeedThrottleMs = coerced
        uiPriceThrottler.throttleIntervalMs = coerced
        _uiPriceThrottleMs.value = coerced
    }

    private var currentActivePair: TradingPair? = null
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
        val currentPair = currentActivePair ?: return
        val selected = currentPair.symbol
        val cleanTick = tick.symbol.replace("/", "").replace("_", "").trim()
        val cleanSelected = selected.replace("/", "").replace("_", "").trim()
        val cleanIndodax = currentPair.effectiveIndodaxPair().replace("/", "").replace("_", "").trim()

        if (!cleanTick.equals(cleanSelected, true) && 
            !cleanTick.equals(cleanIndodax, true)) return

        lastLiveTickAt = System.currentTimeMillis()
        wsLive = true
        if (_connectionState.value !is MarketConnectionState.Connected) {
            _connectionState.value = MarketConnectionState.Connected
            _isShowingCachedData.value = false
        }
        val previous = _currentTick.value
        val normalized = tick.copy(
            symbol = selected,
            high24h = if (previous?.high24h != null && previous.high24h > 0) previous.high24h else tick.price,
            low24h = if (previous?.low24h != null && previous.low24h > 0) previous.low24h else tick.price,
            volume24h = previous?.volume24h ?: 0.0,
            change24h = previous?.change24h ?: 0.0
        )
        // Pass through configurable UI throttler to prevent main thread bottlenecks during volatility spikes
        uiPriceThrottler.submit(normalized)
    }

    private fun dispatchThrottledTick(tick: MarketTick) {
        _currentTick.value = tick
        engine.onTickUpdate(tick)
        updateRecentPrices(tick.price)
        simCoordinator.onPriceTick(tick.symbol, tick.price, tick.high24h, tick.low24h)
        onPriceUpdate(tick.symbol, tick.price, engine.indicators.value.rsi14.takeIf { it.isFinite() })
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
            // Hanya isi _currentTick jika belum ada live ticker untuk pair ini
            if (cachedTick != null && (_currentTick.value == null || _currentTick.value?.symbol != symbol)) {
                _currentTick.value = cachedTick
            }
            if (cachedCandles.isNotEmpty()) {
                _recentCandles.value = cachedCandles
                engine.resetForOffline()
                _currentTick.value?.let { engine.onTickUpdate(it) }
            }
            _isShowingCachedData.value = true
            return true
        }
        return false
    }

    fun startMarketPolling(pair: TradingPair, timeframe: Timeframe) {
        currentActivePair = pair
        marketPollJob?.cancel()
        uiPriceThrottler.reset()

        // 1. Prime harga instan dari dashboard cache jika ada
        val primeTick = _dashboardTicks.value[pair.symbol] 
            ?: _dashboardTicks.value[pair.effectiveIndodaxPair()]
            ?: _dashboardTicks.value[pair.symbol.uppercase()]
        if (primeTick != null && (_currentTick.value == null || _currentTick.value?.symbol != pair.symbol)) {
            val primed = primeTick.copy(symbol = pair.symbol)
            _connectionState.value = MarketConnectionState.Connected
            uiPriceThrottler.emitImmediate(primed)
        }

        indodaxWebSocket.start(pair.symbol)
        marketPollJob = scope.launch {
            if (_currentTick.value == null) _connectionState.value = MarketConnectionState.Loading
            var failCount = 0
            lastCandleRefresh = 0L
            lastDepthRefresh = 0L

            // 2. Immediate Parallel Bootstrap (REST Ticker & Candles Langsung dieksekusi detik pertama)
            launch {
                val prev = _currentTick.value?.price ?: 0.0
                val tick = IndodaxMarketService.fetchTicker(pair.effectiveIndodaxPair(), prevPrice = prev)
                if (tick != null && tick.price > 0 && currentActivePair?.symbol == pair.symbol) {
                    lastLiveTickAt = System.currentTimeMillis()
                    _connectionState.value = MarketConnectionState.Connected
                    _isShowingCachedData.value = false
                    val normalizedTick = tick.copy(symbol = pair.symbol)
                    _dashboardTicks.value = _dashboardTicks.value.toMutableMap().apply { put(pair.symbol, normalizedTick) }
                    uiPriceThrottler.emitImmediate(normalizedTick)
                }
            }

            while (isActive) {
                // Reconnect WS hanya jika benar-benar stale
                if (indodaxWebSocket.isStale(30_000L)) {
                    indodaxWebSocket.start(pair.symbol)
                }

                val now = System.currentTimeMillis()
                val wsFresh = wsLive && (now - lastLiveTickAt < 8_000L)

                // REST ticker hanya sebagai fallback jika WS tidak fresh
                if (!wsFresh) {
                    val prev = _currentTick.value?.price ?: 0.0
                    val tick = IndodaxMarketService.fetchTicker(pair.effectiveIndodaxPair(), prevPrice = prev)
                    if (tick != null && tick.price > 0 && currentActivePair?.symbol == pair.symbol) {
                        failCount = 0
                        lastLiveTickAt = now
                        _connectionState.value = MarketConnectionState.Connected
                        _isShowingCachedData.value = false
                        val normalizedTick = tick.copy(symbol = pair.symbol)
                        _dashboardTicks.value = _dashboardTicks.value.toMutableMap().apply { put(pair.symbol, normalizedTick) }
                        uiPriceThrottler.submit(normalizedTick)
                    } else {
                        failCount++
                        if (failCount >= 4 && now - lastLiveTickAt > 25_000L) {
                            _isShowingCachedData.value = true
                            _connectionState.value = MarketConnectionState.ConnectionLost("Koneksi Indodax lemah. Pakai cache.")
                        }
                    }
                }

                // Candle: 30 detik (cukup untuk chart)
                if (now - lastCandleRefresh >= 30_000L) {
                    val candles = IndodaxMarketService.fetchCandles(pair.effectiveIndodaxPair(), timeframe, 300)
                    if (candles.size >= 30 && currentActivePair?.symbol == pair.symbol) {
                        _recentCandles.value = candles
                        engine.resetForOffline(preserveState = true)
                        _currentTick.value?.let { engine.onTickUpdate(it) }
                        lastCandleRefresh = now
                        marketCache.savePairSnapshot(pair.symbol, timeframe, _currentTick.value, candles)
                    }
                }

                // Orderbook + trades: 20 detik
                if (now - lastDepthRefresh >= 20_000L) {
                    val depth = async { IndodaxMarketService.fetchOrderBook(pair.effectiveIndodaxPair()) }
                    val trades = async { IndodaxMarketService.fetchRecentTrades(pair.effectiveIndodaxPair()) }
                    val (bids, asks) = depth.await()
                    val newTrades = trades.await()
                    if (currentActivePair?.symbol == pair.symbol) {
                        if (bids.isNotEmpty()) _orderBookBids.value = bids
                        if (asks.isNotEmpty()) _orderBookAsks.value = asks
                        if (bids.isNotEmpty() || asks.isNotEmpty()) engine.onOrderBookUpdate(bids, asks)
                        if (newTrades.isNotEmpty()) _tradeStream.value = newTrades
                        lastDepthRefresh = now
                    }
                }

                // Interval loop utama: 6–8 detik cukup
                delay(if (wsFresh) 8000L else 5000L)
            }
        }
    }

    fun switchTimeframe(pair: TradingPair, timeframe: Timeframe) {
        currentActivePair = pair
        // 1. Muat candle snapshot dari cache untuk timeframe baru tanpa menyentuh live ticker
        val (_, cachedCandles) = marketCache.loadPairSnapshot(pair.symbol, timeframe)
        if (cachedCandles.isNotEmpty()) {
            _recentCandles.value = cachedCandles
            engine.resetForOffline(preserveState = true)
            _currentTick.value?.let { engine.onTickUpdate(it) }
        }

        // 2. Fetch candle terbaru untuk timeframe baru secara asynchronous
        scope.launch {
            val candles = IndodaxMarketService.fetchCandles(pair.effectiveIndodaxPair(), timeframe, 300)
            if (candles.isNotEmpty() && currentActivePair?.symbol == pair.symbol) {
                _recentCandles.value = candles
                engine.resetForOffline(preserveState = true)
                _currentTick.value?.let { engine.onTickUpdate(it) }
                lastCandleRefresh = System.currentTimeMillis()
                // Update snapshot cache untuk timeframe ini dengan ticker aktif saat ini
                marketCache.savePairSnapshot(pair.symbol, timeframe, _currentTick.value, candles)
            }
        }
    }

    fun stopPolling() {
        marketPollJob?.cancel()
        indodaxWebSocket.stop(false)
        uiPriceThrottler.reset()
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

    fun clearPairData(symbolToPrime: String? = null) {
        uiPriceThrottler.reset()
        val prime = if (!symbolToPrime.isNullOrBlank()) {
            _dashboardTicks.value[symbolToPrime] ?: _dashboardTicks.value[symbolToPrime.uppercase()]
        } else null
        _currentTick.value = prime
        _recentPrices.value = if (prime != null) listOf(prime.price) else emptyList()
        _recentCandles.value = emptyList()
        _orderBookBids.value = emptyList()
        _orderBookAsks.value = emptyList()
        _tradeStream.value = emptyList()
    }
}
