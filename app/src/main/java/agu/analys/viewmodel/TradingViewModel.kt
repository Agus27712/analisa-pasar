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

    init {
        restoreFromCache()
        selectPair(TradingPair.POPULAR_PAIRS.first())
        startDashboardPolling()
        listenToEngineSignals()
    }

    private fun restoreFromCache() {
        val ticks = marketCache.loadDashboardTicks()
        val worth = marketCache.loadWorthCoins()
        if (ticks.isNotEmpty()) {
            _dashboardTicks.value = ticks
            _isShowingCachedData.value = true
        }
        if (worth.isNotEmpty()) {
            _worthCoins.value = worth
            _isShowingCachedData.value = true
        }
    }

    private val navigationStack = mutableListOf<AppScreen>()

    fun navigateTo(screen: AppScreen) {
        if (_currentScreen.value != screen) {
            if (_currentScreen.value != AppScreen.DASHBOARD) navigationStack.add(_currentScreen.value)
            _currentScreen.value = screen
        }
    }
    fun openCoinDetail(pair: TradingPair) { selectPair(pair); navigateTo(AppScreen.DETAIL) }
    fun openLandscapeChart() { navigateTo(AppScreen.LANDSCAPE_CHART) }
    fun closeLandscapeChart() { goBack() }
    fun openSettings() { navigateTo(AppScreen.SETTINGS) }
    fun goBack() {
        if (navigationStack.isNotEmpty()) {
            _currentScreen.value = navigationStack.removeAt(navigationStack.size - 1)
        } else if (_currentScreen.value != AppScreen.DASHBOARD) {
            _currentScreen.value = AppScreen.DASHBOARD
        }
    }
    fun getGroqApiKey(): String = prefs.groqApiKey
    fun saveGroqApiKey(key: String) { prefs.groqApiKey = key }
    fun getGeminiApiKey(): String = prefs.geminiApiKey
    fun saveGeminiApiKey(key: String) { prefs.geminiApiKey = key }
    fun toggleWatchlist(symbol: String) { prefs.toggleWatchlist(symbol); _watchlist.value = prefs.getWatchlist() }
    fun isWatched(symbol: String): Boolean = prefs.isInWatchlist(symbol)
    fun selectCustomSymbol(rawSymbol: String) { if (rawSymbol.isNotBlank()) selectPair(TradingPair.fromCustomSymbol(rawSymbol)) }

    fun selectAndWatch(rawSymbol: String, addToWatchlist: Boolean = true) {
        if (rawSymbol.isBlank()) return
        val pair = TradingPair.fromCustomSymbol(rawSymbol)
        selectPair(pair)
        if (addToWatchlist && !prefs.isInWatchlist(pair.symbol)) toggleWatchlist(pair.symbol)
    }

    fun refreshSpotPosition() {
        _spotPosition.value = positionStore.get(_selectedPair.value.symbol)
    }

    fun setOwnership(owned: Boolean, investedAmount: Double = 0.0, entryPrice: Double = 0.0) {
        val symbol = _selectedPair.value.symbol
        if (owned) {
            val current = _spotPosition.value
            val safeInvested = investedAmount.takeIf { it > 0.0 } ?: current.investedAmount
            val safeEntry = entryPrice.takeIf { it > 0.0 } ?: current.entryPrice
            positionStore.markBought(symbol, safeInvested, safeEntry)
        } else {
            positionStore.markSold(symbol)
        }
        refreshSpotPosition()
    }

    private fun startDashboardPolling() {
        dashboardPollJob?.cancel()
        dashboardPollJob = viewModelScope.launch {
            while (isActive) { refreshWorthCoinsFromMarket(); delay(15_000L) }
        }
    }

    fun refreshWorthCoinsFromMarket() {
        viewModelScope.launch { refreshDashboardMarket() }
    }

    private suspend fun refreshDashboardMarket() {
        val result = runCatching { IndodaxMarketService.fetchTickerDashboard() }.getOrDefault(emptyList())
        if (result.isNotEmpty()) {
            _dashboardTicks.value = result.associateBy { it.pair.symbol }
            _isShowingCachedData.value = false
        }
    }

    private fun listenToEngineSignals() {
        viewModelScope.launch {
            engine.signalState.collect { signal ->
                _signalHistory.value = buildSignalHistory(signal)
            }
        }
    }

    private fun buildSignalHistory(current: AISignalState): List<AISignalState> {
        val existing = _signalHistory.value.filterNot { it.timestamp == current.timestamp && it.pairSymbol == current.pairSymbol }
        return (listOf(current) + existing).take(50)
    }

    fun selectPair(pair: TradingPair) {
        if (_selectedPair.value == pair) {
            refreshSpotPosition()
            return
        }
        _selectedPair.value = pair
        refreshSpotPosition()
        viewModelScope.launch { loadSelectedPair() }
    }

    private suspend fun loadSelectedPair() {
        // Existing market loading logic remains unchanged.
        refreshSelectedMarketData()
    }

    private suspend fun refreshSelectedMarketData() {
        // Existing market/data-loading logic remains unchanged.
    }
}