package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.bridge.TradingViewBridge
import agu.analys.engine.LearningTradingEngine
import agu.analys.model.AISignalState
import agu.analys.model.AppScreen
import agu.analys.model.ChartStyle
import agu.analys.model.CandleBar
import agu.analys.model.MarketConnectionState
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.service.GeminiAiService
import agu.analys.service.GroqAiService
import agu.analys.service.IndodaxMarketService
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import agu.analys.util.AppPreferences
import agu.analys.util.MarketDataCache
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

class TradingViewModel(application: Application) : AndroidViewModel(application) {
    val bridge = TradingViewBridge(viewModelScope)
    private val engine = LearningTradingEngine(viewModelScope)
    private val prefs = AppPreferences(application)
    private val marketCache = MarketDataCache(application)
    private val positionStore = SpotPositionStore(application)

    private val _currentScreen = MutableStateFlow(AppScreen.DASHBOARD)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()
    private val _selectedPair = MutableStateFlow(TradingPair.POPULAR_PAIRS.first())
    val selectedPair: StateFlow<TradingPair> = _selectedPair.asStateFlow()
    private val _selectedTimeframe = MutableStateFlow(Timeframe.H4)
    val selectedTimeframe: StateFlow<Timeframe> = _selectedTimeframe.asStateFlow()
    private val _selectedChartStyle = MutableStateFlow(ChartStyle.CANDLES)
    val selectedChartStyle: StateFlow<ChartStyle> = _selectedChartStyle.asStateFlow()
    private val _useSimpleChart = MutableStateFlow(false)
    val useSimpleChart: StateFlow<Boolean> = _useSimpleChart.asStateFlow()
    private val _recentPrices = MutableStateFlow<List<Double>>(emptyList())
    val recentPrices: StateFlow<List<Double>> = _recentPrices.asStateFlow()
    private val _recentCandles = MutableStateFlow<List<CandleBar>>(emptyList())
    val recentCandles: StateFlow<List<CandleBar>> = _recentCandles.asStateFlow()
    private val _isChartExpanded = MutableStateFlow(false)
    val isChartExpanded: StateFlow<Boolean> = _isChartExpanded.asStateFlow()
    private val _currentTick = MutableStateFlow<MarketTick?>(null)
    val currentTick: StateFlow<MarketTick?> = _currentTick.asStateFlow()
    val currentIndicators: StateFlow<TechnicalIndicators> = engine.indicators
    val aiSignalState: StateFlow<AISignalState> = engine.signalState
    private val _orderBookBids = MutableStateFlow<List<OrderBookItem>>(emptyList())
    val orderBookBids: StateFlow<List<OrderBookItem>> = _orderBookBids.asStateFlow()
    private val _orderBookAsks = MutableStateFlow<List<OrderBookItem>>(emptyList())
    val orderBookAsks: StateFlow<List<OrderBookItem>> = _orderBookAsks.asStateFlow()
    private val _tradeStream = MutableStateFlow<List<TradeStreamItem>>(emptyList())
    val tradeStream: StateFlow<List<TradeStreamItem>> = _tradeStream.asStateFlow()
    private val _signalHistory = MutableStateFlow<List<AISignalState>>(emptyList())
    val signalHistory: StateFlow<List<AISignalState>> = _signalHistory.asStateFlow()
    private val _auditReportText = MutableStateFlow<String?>(null)
    val auditReportText: StateFlow<String?> = _auditReportText.asStateFlow()
    private val _isAuditLoading = MutableStateFlow(false)
    val isAuditLoading: StateFlow<Boolean> = _isAuditLoading.asStateFlow()
    private val _geminiSummaryText = MutableStateFlow<String?>(null)
    val geminiSummaryText: StateFlow<String?> = _geminiSummaryText.asStateFlow()
    private val _isGeminiLoading = MutableStateFlow(false)
    val isGeminiLoading: StateFlow<Boolean> = _isGeminiLoading.asStateFlow()
    private val _worthCoins = MutableStateFlow<List<WorthCoinInfo>>(emptyList())
    val worthCoins: StateFlow<List<WorthCoinInfo>> = _worthCoins.asStateFlow()
    private val _dashboardTicks = MutableStateFlow<Map<String, MarketTick>>(emptyMap())
    val dashboardTicks: StateFlow<Map<String, MarketTick>> = _dashboardTicks.asStateFlow()
    private val _connectionState = MutableStateFlow<MarketConnectionState>(MarketConnectionState.Loading)
    val connectionState: StateFlow<MarketConnectionState> = _connectionState.asStateFlow()
    private val _watchlist = MutableStateFlow(prefs.getWatchlist())
    val watchlist: StateFlow<Set<String>> = _watchlist.asStateFlow()
    private val _isShowingCachedData = MutableStateFlow(false)
    val isShowingCachedData: StateFlow<Boolean> = _isShowingCachedData.asStateFlow()
    private val _spotPosition = MutableStateFlow(SpotPosition())
    val spotPosition: StateFlow<SpotPosition> = _spotPosition.asStateFlow()

    private var lastSavedSignalTimestamp = 0L
    private var lastCandleRefresh = 0L
    private var lastDepthRefresh = 0L
    private var marketPollJob: Job? = null
    private var dashboardPollJob: Job? = null

    init { restoreFromCache(); selectPair(TradingPair.POPULAR_PAIRS.first()); startDashboardPolling(); listenToEngineSignals() }

    private fun restoreFromCache() {
        val ticks = marketCache.loadDashboardTicks(); val worth = marketCache.loadWorthCoins()
        if (ticks.isNotEmpty()) { _dashboardTicks.value = ticks; _isShowingCachedData.value = true }
        if (worth.isNotEmpty()) { _worthCoins.value = worth; _isShowingCachedData.value = true }
    }

    private val navigationStack = mutableListOf<AppScreen>()
    fun navigateTo(screen: AppScreen) { if (_currentScreen.value != screen) { if (_currentScreen.value != AppScreen.DASHBOARD) navigationStack.add(_currentScreen.value); _currentScreen.value = screen } }
    fun openCoinDetail(pair: TradingPair) { selectPair(pair); navigateTo(AppScreen.DETAIL) }
    fun openLandscapeChart() { navigateTo(AppScreen.LANDSCAPE_CHART) }
    fun closeLandscapeChart() { goBack() }
    fun openSettings() { navigateTo(AppScreen.SETTINGS) }
    fun goBack() { if (navigationStack.isNotEmpty()) _currentScreen.value = navigationStack.removeAt(navigationStack.size - 1) else if (_currentScreen.value != AppScreen.DASHBOARD) _currentScreen.value = AppScreen.DASHBOARD }
    fun getGroqApiKey(): String = prefs.groqApiKey
    fun saveGroqApiKey(key: String) { prefs.groqApiKey = key }
    fun getGeminiApiKey(): String = prefs.geminiApiKey
    fun saveGeminiApiKey(key: String) { prefs.geminiApiKey = key }
    fun toggleWatchlist(symbol: String) { prefs.toggleWatchlist(symbol); _watchlist.value = prefs.getWatchlist() }
    fun isWatched(symbol: String): Boolean = prefs.isInWatchlist(symbol)
    fun selectCustomSymbol(rawSymbol: String) { if (rawSymbol.isNotBlank()) selectPair(TradingPair.fromCustomSymbol(rawSymbol)) }
    fun selectAndWatch(rawSymbol: String, addToWatchlist: Boolean = true) { if (rawSymbol.isBlank()) return; val pair = TradingPair.fromCustomSymbol(rawSymbol); selectPair(pair); if (addToWatchlist && !prefs.isInWatchlist(pair.symbol)) toggleWatchlist(pair.symbol) }
    fun refreshSpotPosition() { _spotPosition.value = positionStore.get(_selectedPair.value.symbol) }
    fun setOwnership(owned: Boolean, referenceEntryPrice: Double = 0.0) { val symbol = _selectedPair.value.symbol; if (owned) positionStore.markBought(symbol, referenceEntryPrice.takeIf { it > 0.0 } ?: _spotPosition.value.entryPrice.takeIf { it > 0.0 } ?: 0.0) else positionStore.markSold(symbol); refreshSpotPosition() }

    private fun startDashboardPolling() { dashboardPollJob?.cancel(); dashboardPollJob = viewModelScope.launch { while (isActive) { refreshWorthCoinsFromMarket(); delay(15_000L) } } }

    fun refreshWorthCoinsFromMarket() {
        viewModelScope.launch {
            val pairs = (TradingPair.POPULAR_PAIRS + _watchlist.value.map { TradingPair.fromCustomSymbol(it) }).distinctBy { it.symbol }
            val ticks = IndodaxMarketService.fetchTickers(pairs.map { it.effectiveIndodaxPair() })
            if (ticks.isEmpty()) { if (_dashboardTicks.value.isEmpty()) markMarketOffline("Tidak ada respons market dari Indodax.") else { _isShowingCachedData.value = true; _connectionState.value = MarketConnectionState.ConnectionLost("Koneksi terputus. Menampilkan data cache terakhir.") }; return@launch }
            _dashboardTicks.value = ticks.associateBy { it.symbol }; _connectionState.value = MarketConnectionState.Connected; _isShowingCachedData.value = false; marketCache.saveDashboardTicks(_dashboardTicks.value)
            val tickMap = ticks.associateBy { it.symbol }
            val worth = pairs.mapNotNull { pair ->
                val tick = tickMap[pair.symbol] ?: return@mapNotNull null
                val rangePct = if (tick.low24h > 0) ((tick.high24h - tick.low24h) / tick.low24h) * 100.0 else 0.0
                val volScore = when { tick.volume24h >= 100_000_000_000 -> 30; tick.volume24h >= 10_000_000_000 -> 22; tick.volume24h >= 1_000_000_000 -> 14; else -> 6 }
                val mid = (tick.high24h + tick.low24h) / 2.0
                val changeEst = if (mid > 0) ((tick.price - mid) / mid) * 100.0 else 0.0
                val momentumScore = when { changeEst >= 3 -> 35; changeEst >= 0 -> 20; changeEst >= -3 -> 12; else -> 5 }
                val volaScore = min(20, (rangePct * 1.5).toInt()); val score = (volScore + momentumScore + volaScore).coerceIn(1, 99)
                val rec = when { score >= 80 && changeEst > 0 -> "MOMENTUM KUAT"; score >= 65 -> "LAYAK DIPANTAU"; changeEst <= -3 -> "TEKANAN JUAL"; else -> "NETRAL / KONSOLIDASI" }
                WorthCoinInfo(pair, score, score >= 65 && changeEst >= 0, rec, abs(changeEst), "${PriceFormatter.formatPrice(tick.price)} · Vol ${PriceFormatter.formatVolume(tick.volume24h)} · Range ${PriceFormatter.formatPercentage(rangePct, false)}")
            }.sortedByDescending { it.worthScore }
            _worthCoins.value = worth; marketCache.saveWorthCoins(worth)
        }
    }

    private fun listenToEngineSignals() { viewModelScope.launch { engine.signalState.collect { signal -> val now = System.currentTimeMillis(); if (signal.action != SignalAction.HOLD && now - lastSavedSignalTimestamp > 15000L) { lastSavedSignalTimestamp = now; val list = _signalHistory.value.toMutableList(); list.add(0, signal.copy(marketSymbol = _selectedPair.value.symbol)); if (list.size > 30) list.removeAt(list.lastIndex); _signalHistory.value = list } } } }

    fun selectPair(pair: TradingPair) {
        _selectedPair.value = pair; lastSavedSignalTimestamp = 0L; refreshSpotPosition()
        val (cachedTick, cachedCandles) = marketCache.loadPairSnapshot(pair.symbol, _selectedTimeframe.value)
        if (cachedTick != null || cachedCandles.isNotEmpty()) { if (cachedTick != null) _currentTick.value = cachedTick; if (cachedCandles.isNotEmpty()) { _recentCandles.value = cachedCandles; engine.resetForOffline(); cachedTick?.let { engine.onTickUpdate(it) }; cachedCandles.forEach { engine.onCandleUpdate(it) } }; _isShowingCachedData.value = true } else clearLiveData()
        startMarketPolling(pair)
    }

    private fun startMarketPolling(pair: TradingPair) {
        marketPollJob?.cancel(); marketPollJob = viewModelScope.launch {
            if (_currentTick.value == null) _connectionState.value = MarketConnectionState.Loading
            var failCount = 0; lastCandleRefresh = 0L; lastDepthRefresh = 0L; val pairId = pair.effectiveIndodaxPair()
            while (isActive) {
                val prev = _currentTick.value?.price ?: 0.0; val tick = IndodaxMarketService.fetchTicker(pairId, prevPrice = prev)
                if (tick != null && tick.price > 0) {
                    failCount = 0; _connectionState.value = MarketConnectionState.Connected; _isShowingCachedData.value = false
                    val normalizedTick = tick.copy(symbol = pair.symbol); _currentTick.value = normalizedTick; engine.onTickUpdate(normalizedTick)
                    val hist = _recentPrices.value.toMutableList(); hist.add(tick.price); if (hist.size > 50) hist.removeAt(0); _recentPrices.value = hist
                    val currentMap = _dashboardTicks.value; if (currentMap[pair.symbol] != normalizedTick) _dashboardTicks.value = currentMap.toMutableMap().apply { put(pair.symbol, normalizedTick) }
                    val now = System.currentTimeMillis()
                    val selectedTf = _selectedTimeframe.value
                    if (now - lastCandleRefresh >= 15_000L) {
                        val candles = IndodaxMarketService.fetchCandles(pairId, selectedTf, 300)
                        if (candles.size >= 35) { _recentCandles.value = candles; engine.resetForOffline(); engine.onTickUpdate(normalizedTick); candles.forEach { engine.onCandleUpdate(it) }; lastCandleRefresh = now; marketCache.savePairSnapshot(pair.symbol, selectedTf, normalizedTick, candles) } else if (_recentCandles.value.isNotEmpty()) marketCache.savePairSnapshot(pair.symbol, selectedTf, normalizedTick, _recentCandles.value)
                    } else if (_recentCandles.value.isNotEmpty()) marketCache.savePairSnapshot(pair.symbol, selectedTf, normalizedTick, _recentCandles.value)
                    if (now - lastDepthRefresh >= 5_000L) { val depth = async { IndodaxMarketService.fetchOrderBook(pairId) }; val trades = async { IndodaxMarketService.fetchRecentTrades(pairId) }; val (bids, asks) = depth.await(); val newTrades = trades.await(); if (bids.isNotEmpty() && bids != _orderBookBids.value) _orderBookBids.value = bids; if (asks.isNotEmpty() && asks != _orderBookAsks.value) _orderBookAsks.value = asks; if (newTrades.isNotEmpty() && newTrades != _tradeStream.value) _tradeStream.value = newTrades; lastDepthRefresh = now }
                } else { failCount++; if (failCount >= 2) { _isShowingCachedData.value = true; _connectionState.value = MarketConnectionState.ConnectionLost("Koneksi internet/market terputus. Menampilkan data cache terakhir."); break } }
                delay(3000L)
            }
        }
    }

    private fun clearLiveData() { _currentTick.value = null; _recentPrices.value = emptyList(); _recentCandles.value = emptyList(); _orderBookBids.value = emptyList(); _orderBookAsks.value = emptyList(); _tradeStream.value = emptyList(); engine.resetForOffline() }
    private fun markMarketOffline(reason: String) { marketPollJob?.cancel(); if (_dashboardTicks.value.isEmpty() && _worthCoins.value.isEmpty()) clearLiveData(); _isShowingCachedData.value = _dashboardTicks.value.isNotEmpty() || _currentTick.value != null; _connectionState.value = MarketConnectionState.ConnectionLost(reason) }
    fun toggleSimpleChart() { _useSimpleChart.value = !_useSimpleChart.value }
    fun selectTimeframe(tf: Timeframe) { if (_selectedTimeframe.value == tf) return; _selectedTimeframe.value = tf; selectPair(_selectedPair.value) }
    fun selectChartStyle(style: ChartStyle) { _selectedChartStyle.value = style }
    fun toggleChartExpanded() { _isChartExpanded.value = !_isChartExpanded.value }
    fun requestDeepAiAudit() { val tick = _currentTick.value ?: return; if (_connectionState.value !is MarketConnectionState.Connected) return; val indicators = currentIndicators.value; val signal = aiSignalState.value; viewModelScope.launch { _isAuditLoading.value = true; _auditReportText.value = null; _auditReportText.value = GroqAiService.generateDeepMarketAudit(prefs.groqApiKey, tick, indicators, signal); _isAuditLoading.value = false } }
    fun clearAuditReport() { _auditReportText.value = null }
    fun requestGeminiChartSummary() { val tick = _currentTick.value ?: return; if (_connectionState.value !is MarketConnectionState.Connected) return; val indicators = currentIndicators.value; val signal = aiSignalState.value; viewModelScope.launch { _isGeminiLoading.value = true; _geminiSummaryText.value = null; _geminiSummaryText.value = GeminiAiService.generateChartSummary24h(prefs.geminiApiKey, tick, indicators, signal); _isGeminiLoading.value = false } }
    fun clearGeminiSummary() { _geminiSummaryText.value = null }
    fun retryConnection() { _connectionState.value = MarketConnectionState.Loading; startMarketPolling(_selectedPair.value); refreshWorthCoinsFromMarket() }
    fun simulateDisconnect() { markMarketOffline("Mode offline: koneksi pasar dihentikan. Data cache terakhir tetap ditampilkan.") }
}
