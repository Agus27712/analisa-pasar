package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job

import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.bridge.TradingViewBridge
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.LearningTradingEngine
import agu.analys.model.*
import agu.analys.trading.*
import agu.analys.util.*
import agu.analys.database.AppDatabase
import agu.analys.database.SignalLogEntity
import agu.analys.database.SignalLogRepository
import agu.analys.database.TradeHistoryRecordEntity
import agu.analys.database.TradeHistoryRecorder
import agu.analys.ui.theme.*
import agu.analys.ui.animation.*

/**
 * TradingViewModel acts as the unified delegation Facade / Mediator.
 * It coordinates and forwards state flows and calls directly to the 7 specialized, UI-driven ViewModels:
 * SettingsViewModel, MarketViewModel, WatchlistViewModel, OrderViewModel, PortfolioViewModel, SignalLogViewModel, and AiNewsViewModel.
 *
 * This design achieves clean separation of concerns and reduces the main viewmodel class to under 400 lines,
 * while maintaining 100% backward compatibility with existing screen files.
 */
class TradingViewModel(application: Application) : AndroidViewModel(application) {

    // ═════════════════════════════════════════════════════════════════════════
    // 1. SUB-VIEWMODELS (UI-DRIVEN FEATURES)
    // ═════════════════════════════════════════════════════════════════════════
    val marketViewModel = MarketViewModel(application)
    val watchlistViewModel = WatchlistViewModel(application)
    val aiNewsViewModel = AiNewsViewModel(application)
    val settingsViewModel = SettingsViewModel(application)
    val portfolioViewModel = PortfolioViewModel(application)
    val signalLogViewModel = SignalLogViewModel(application)

    // ═════════════════════════════════════════════════════════════════════════
    // 2. CORE COORDINATORS & SHARED SERVICES
    // ═════════════════════════════════════════════════════════════════════════
    val bridge = TradingViewBridge(viewModelScope)
    internal val engine = LearningTradingEngine(viewModelScope)
    internal val prefs = AppPreferences(application)
    internal val marketCache = MarketDataCache(application)
    internal val positionStore = SpotPositionStore(application)
    internal val alertStore = PriceAlertStore(application)
    internal val simulationStore = SimulationTradeStore(application)

    internal val simCoordinator = SimulationCoordinator(
        store = simulationStore,
        onOrderFilled = { order -> syncSimulationTradeToPositionStore(order) }
    )
    internal val realCoordinator = RealTradeCoordinator(
        scope = viewModelScope,
        prefs = prefs,
        getLatestTick = { symbol -> marketDataCoordinator.dashboardTicks.value[symbol] },
        onBalanceAndAvgUpdated = { balances, avgPrices -> 
            this@TradingViewModel.syncRealBalancesToPositionStore(balances, avgPrices) 
        },
        onRealTradeExecuted = { pair, type, price, quantity, tp1, tp2 ->
            handleRealTradeExecution(pair, type, price, quantity, tp1, tp2)
        }
    )
    val orderViewModel = OrderViewModel(
        application = application,
        customSimCoordinator = simCoordinator,
        customRealCoordinator = realCoordinator
    )
    internal val updateCoordinator = AppUpdateCoordinator(viewModelScope)
    
    internal val signalLogRepository = SignalLogRepository(
        dao = AppDatabase.getInstance().signalLogDao(),
        scope = viewModelScope
    )
    internal val tradeHistoryRecorder = TradeHistoryRecorder(
        dao = AppDatabase.getInstance().tradeHistoryRecordDao(),
        scope = viewModelScope
    )

    internal val marketDataCoordinator = MarketDataCoordinator(
        scope = viewModelScope,
        prefs = prefs,
        marketCache = marketCache,
        engine = engine,
        simCoordinator = simCoordinator,
        onPriceUpdate = { symbol, price, rsi ->
            checkAlertsAndTrailing(symbol, price, rsi ?: 0.0)
            signalLogRepository.processPriceTick(symbol, price)
            tradeHistoryRecorder.processPriceTick(symbol, price)
        }
    )

    internal val positionCoordinator = PositionCoordinator(
        positionStore = positionStore,
        alertStore = alertStore,
        isRealProvider = { isRealBuyMode.value },
        onPositionChanged = { updateForegroundServiceState() }
    )

    // ═════════════════════════════════════════════════════════════════════════
    // 3. STATE FLOWS (DELEGATED DIRECTLY TO SPECIALIZED VIEWMODELS)
    // ═════════════════════════════════════════════════════════════════════════
    
    // Preferences & Theme Delegation
    val isDarkTheme: StateFlow<Boolean> = settingsViewModel.isDarkTheme
    val themeStyle: StateFlow<ThemeStyle> = settingsViewModel.themeStyle
    val accentColorPreset: StateFlow<AccentColorPreset> = settingsViewModel.accentColorPreset
    val candleColorStyle: StateFlow<CandleColorStyle> = settingsViewModel.candleColorStyle
    val animationSpeed: StateFlow<AnimationSpeed> = settingsViewModel.animationSpeed
    val priceAnimationMode: StateFlow<PriceAnimationMode> = settingsViewModel.priceAnimationMode
    val isPriceTickPulseEnabled: StateFlow<Boolean> = settingsViewModel.isPriceTickPulseEnabled
    val isSmoothChartEnabled: StateFlow<Boolean> = settingsViewModel.isSmoothChartEnabled
    val isNotificationsEnabled: StateFlow<Boolean> = settingsViewModel.isNotificationsEnabled
    val isNotifyCandidateBuyEnabled: StateFlow<Boolean> = settingsViewModel.isNotifyCandidateBuyEnabled
    val isNotifyPriceAlertsEnabled: StateFlow<Boolean> = settingsViewModel.isNotifyPriceAlertsEnabled
    val isNotifyTrailingStopEnabled: StateFlow<Boolean> = settingsViewModel.isNotifyTrailingStopEnabled
    val isNotifyEmergencyExitEnabled: StateFlow<Boolean> = settingsViewModel.isNotifyEmergencyExitEnabled
    val isRealSimSyncEnabled: StateFlow<Boolean> = settingsViewModel.isRealSimSyncEnabled
    val marketDataSource: StateFlow<MarketDataSource> = settingsViewModel.marketDataSource

    internal val _selectedPair: StateFlow<TradingPair> get() = marketViewModel.selectedPair

    val spotPosition: StateFlow<SpotPosition> = positionCoordinator.spotPosition
    val positionVersion: StateFlow<Long> = positionCoordinator.positionVersion
    val priceAlerts: StateFlow<List<PriceAlert>> = positionCoordinator.priceAlerts
    val mtfState: StateFlow<Map<String, Map<Timeframe, MtfStatus>>> = agu.analys.util.MtfCacheManager.mtfState

    // Local settings states for quick engine integration
    private val _strategyMode = MutableStateFlow(prefs.strategyMode)
    val strategyMode: StateFlow<StrategyMode> = _strategyMode.asStateFlow()

    private val _isScalpingMode = MutableStateFlow(prefs.isScalpingMode)
    val isScalpingMode: StateFlow<Boolean> = _isScalpingMode.asStateFlow()

    private val _tradingFees = MutableStateFlow(prefs.tradingFees)
    val tradingFees: StateFlow<TradingFeeConfig> = _tradingFees.asStateFlow()

    val useSimpleChart: StateFlow<Boolean> = marketViewModel.useSimpleChart
    val selectedChartStyle: StateFlow<ChartStyle> = marketViewModel.selectedChartStyle

    // Navigation Delegation
    internal val _currentScreen = MutableStateFlow(AppScreen.DASHBOARD)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()
    internal val navigationStack = mutableListOf<AppScreen>()

    // Market Ticker & Candlestick Delegation
    val selectedPair: StateFlow<TradingPair> = marketViewModel.selectedPair
    val selectedTimeframe: StateFlow<Timeframe> = marketViewModel.selectedTimeframe
    val connectionState: StateFlow<MarketConnectionState> = marketViewModel.connectionState
    val isShowingCachedData: StateFlow<Boolean> = marketViewModel.isShowingCachedData
    val isRefreshing: StateFlow<Boolean> = marketViewModel.isRefreshing
    val dashboardTicks: StateFlow<Map<String, MarketTick>> = marketViewModel.dashboardTicks
    val worthCoins: StateFlow<List<WorthCoinInfo>> = marketViewModel.worthCoins
    val hotCoins: StateFlow<List<MarketTick>> = marketViewModel.hotCoins
    val gainersCoins: StateFlow<List<MarketTick>> = marketViewModel.gainersCoins
    val losersCoins: StateFlow<List<MarketTick>> = marketViewModel.losersCoins
    val topVolumeCoins: StateFlow<List<MarketTick>> = marketViewModel.topVolumeCoins
    val usdtIdrRate: StateFlow<Double> = marketViewModel.usdtIdrRate
    val coinBadges: StateFlow<Map<String, List<CoinBadge>>> = marketViewModel.coinBadges

    // Market details and real-time streams
    val recentPrices: StateFlow<List<Double>> = marketDataCoordinator.recentPrices
    val recentCandles: StateFlow<List<CandleBar>> = marketDataCoordinator.recentCandles
    val isChartExpanded: StateFlow<Boolean> = marketViewModel.isChartExpanded
    val currentTick: StateFlow<MarketTick?> = marketDataCoordinator.currentTick
    val uiPriceThrottleMs: StateFlow<Long> = marketDataCoordinator.uiPriceThrottleMs
    val currentIndicators: StateFlow<TechnicalIndicators> = engine.indicators
    val aiSignalState: StateFlow<AISignalState> = engine.signalState
    val orderBookBids: StateFlow<List<OrderBookItem>> = marketDataCoordinator.orderBookBids
    val orderBookAsks: StateFlow<List<OrderBookItem>> = marketDataCoordinator.orderBookAsks
    val tradeStream: StateFlow<List<TradeStreamItem>> = marketDataCoordinator.tradeStream

    // Watchlist & Favorites Delegation
    val watchlist: StateFlow<Set<String>> = watchlistViewModel.watchlist
    val favorites: StateFlow<Set<String>> = watchlistViewModel.favorites

    // Simulation Portfolio Delegation
    val simulationWallet: StateFlow<SimulationWallet> = orderViewModel.simulationWallet
    val simulationOpenOrders: StateFlow<List<SimulationOrder>> = orderViewModel.simulationOpenOrders
    val simulationHistory: StateFlow<List<SimulationTradeHistoryItem>> = orderViewModel.simulationHistory
    val lastFilledSimulationOrder: StateFlow<SimulationOrder?> = orderViewModel.lastFilledSimulationOrder

    // Real Balance & Trades Delegation
    val isRealBuyMode: StateFlow<Boolean> = orderViewModel.isRealBuyMode
    val isPinUnlocked: StateFlow<Boolean> = orderViewModel.isPinUnlocked
    val realIndodaxBalance: StateFlow<Map<String, Double>> = orderViewModel.realIndodaxBalance
    val realFreeBalance: StateFlow<Map<String, Double>> = orderViewModel.realFreeBalance
    val realLockedBalance: StateFlow<Map<String, Double>> = orderViewModel.realLockedBalance
    val realOpenOrders: StateFlow<List<RealOpenOrderEntity>> = orderViewModel.realOpenOrders
    val realTrades: StateFlow<List<RealTradeEntity>> = orderViewModel.realTrades
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = orderViewModel.realAvgBuyPrices
    val isFetchingRealBalance: StateFlow<Boolean> = orderViewModel.isFetchingRealBalance
    val realTradeStatus: StateFlow<String> = orderViewModel.realTradeStatus
    val userPublicIp: StateFlow<String?> = orderViewModel.userPublicIp
    val failedPinAttempts: StateFlow<Int> = orderViewModel.failedPinAttempts

    // AI states Delegation
    val auditReportText: StateFlow<String?> = aiNewsViewModel.auditReportText
    val isAuditLoading: StateFlow<Boolean> = aiNewsViewModel.isAuditLoading
    val geminiSummaryText: StateFlow<String?> = aiNewsViewModel.geminiSummaryText
    val isGeminiLoading: StateFlow<Boolean> = aiNewsViewModel.isGeminiLoading

    internal val _signalHistory = MutableStateFlow<List<AISignalState>>(emptyList())
    val signalHistory: StateFlow<List<AISignalState>> = _signalHistory.asStateFlow()

    // History Records & AI summaries
    val allSignalLogs: StateFlow<List<SignalLogEntity>> = signalLogViewModel.allSignalLogs
    val signalReliabilitySummary: StateFlow<SignalReliabilitySummary> = signalLogViewModel.signalReliabilitySummary
    val tradeHistoryRecords: StateFlow<List<TradeHistoryRecordEntity>> = portfolioViewModel.tradeHistoryRecords
    val newsScreenerState: StateFlow<NewsScreenerUiState> = aiNewsViewModel.newsScreenerState

    // Global Market and contextual states
    val globalContext: StateFlow<agu.analys.engine.global.GlobalMarketContext> = agu.analys.engine.global.GlobalContextManager.context
    
    val holdingStatuses: StateFlow<Map<String, CoinHoldingStatus>> = kotlinx.coroutines.flow.combine(
        watchlist, favorites, positionCoordinator.spotPosition, isRealBuyMode, positionCoordinator.positionVersion
    ) { w, f, pos, isReal, _ ->
        val storedSymbols = positionStore.getAllStoredSymbols(isReal)
        (w + f + listOf(pos.symbol) + storedSymbols).distinct().associateWith { sym ->
            val p = positionStore.get(sym, isReal)
            CoinHoldingStatus(p.isHolding, p.entryPrice, p.quantity, p.isReal)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val positionContext: StateFlow<PositionContext> = kotlinx.coroutines.flow.combine(
        positionCoordinator.spotPosition, marketDataCoordinator.currentTick
    ) { pos, tick ->
        PositionContext(
            hasPosition = pos.isHolding, symbol = pos.symbol, entryPrice = pos.entryPrice,
            quantity = pos.quantity, currentPrice = tick?.price ?: pos.entryPrice, isReal = pos.isReal
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PositionContext())

    val tradingWorkflow: StateFlow<TradingWorkflow> = positionContext.map { resolveWorkflow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TradingWorkflow.BUY)

    val sellSignalState: StateFlow<SellSignalState> = kotlinx.coroutines.flow.combine(
        positionContext, marketDataCoordinator.recentCandles
    ) { ctx, _ ->
        val entry = ctx.entryPrice ?: 0.0
        val current = ctx.currentPrice ?: 0.0
        val profit = if (entry > 0.0) ((current - entry) / entry) * 100.0 else 0.0
        SellSignalState(
            state = if (ctx.hasPosition && profit >= 1.5) SellLifecycleState.READY_TO_SELL else SellLifecycleState.NOT_HOLDING,
            reason = if (ctx.hasPosition) "Target Profit tercapai!" else "Belum memegang posisi",
            netProfitPct = profit,
            updatedAt = System.currentTimeMillis()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SellSignalState())

    // Update States
    val githubReleaseInfo: StateFlow<GitHubReleaseInfo?> = updateCoordinator.releaseInfo
    val updateCheckStatus: StateFlow<String?> = updateCoordinator.updateCheckStatus
    val isCheckingUpdate: StateFlow<Boolean> = updateCoordinator.isCheckingUpdate
    val updateDownloadProgress: StateFlow<Int?> = updateCoordinator.downloadProgress

    // Polling handles
    internal var trailingPollJob: Job? = null
    internal var lastSavedSignalTimestamp = 0L

    init {
        initSubscriptionsAndPolling()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. MAIN DELEGATED LOGIC METHODS
    // ═════════════════════════════════════════════════════════════════════════

    fun setMarketDataSource(source: MarketDataSource) = settingsViewModel.setMarketDataSource(source)

    fun setStrategyMode(mode: StrategyMode) {
        _strategyMode.value = mode
        prefs.strategyMode = mode
        val scalpingEnabled = mode == StrategyMode.SCALPING
        _isScalpingMode.value = scalpingEnabled
        prefs.isScalpingMode = scalpingEnabled
        engine.strategyMode = mode
        engine.isScalpingMode = scalpingEnabled
        engine.tradingFees = prefs.tradingFees
        
        if (mode == StrategyMode.SCALPING) {
            MtfCacheManager.setActiveSymbol(selectedPair.value.symbol)
        }
        
        marketDataCoordinator.startMarketPolling(selectedPair.value, selectedTimeframe.value)
        val tick = marketDataCoordinator.currentTick.value
        if (tick != null) {
            engine.resetForOffline()
            engine.onTickUpdate(tick)
        }
        refreshWorthCoinsFromMarket()
    }

    fun setScalpingMode(enabled: Boolean) {
        setStrategyMode(if (enabled) StrategyMode.SCALPING else StrategyMode.SWING)
    }

    fun updateTradingFees(fees: TradingFeeConfig) {
        settingsViewModel.setTradingFees(fees)
        _tradingFees.value = fees
        engine.tradingFees = fees
    }

    fun setDarkTheme(enabled: Boolean) = settingsViewModel.setDarkTheme(enabled)
    fun setThemeStyle(style: ThemeStyle) = settingsViewModel.setThemeStyle(style)
    fun setAccentColorPreset(preset: AccentColorPreset) = settingsViewModel.setAccentColorPreset(preset)
    fun setCandleColorStyle(style: CandleColorStyle) = settingsViewModel.setCandleColorStyle(style)
    fun setAnimationSpeed(speed: AnimationSpeed) = settingsViewModel.setAnimationSpeed(speed)
    fun setPriceAnimationMode(mode: PriceAnimationMode) = settingsViewModel.setPriceAnimationMode(mode)
    fun setPriceTickPulseEnabled(enabled: Boolean) = settingsViewModel.setPriceTickPulseEnabled(enabled)
    fun setSmoothChartEnabled(enabled: Boolean) = settingsViewModel.setSmoothChartEnabled(enabled)
    fun setNotifyCandidateBuyEnabled(enabled: Boolean) = settingsViewModel.setNotifyCandidateBuyEnabled(enabled)
    fun setNotifyPriceAlertsEnabled(enabled: Boolean) = settingsViewModel.setNotifyPriceAlertsEnabled(enabled)
    fun setNotifyTrailingStopEnabled(enabled: Boolean) = settingsViewModel.setNotifyTrailingStopEnabled(enabled)
    fun setNotifyEmergencyExitEnabled(enabled: Boolean) = settingsViewModel.setNotifyEmergencyExitEnabled(enabled)

    fun runNewsAiScreener(forceRefresh: Boolean = false) = aiNewsViewModel.fetchAiNewsScreening(forceRefresh = forceRefresh)
    fun clearNewsScreenerState() = aiNewsViewModel.clearNewsScreenerState()
    fun setNotificationsEnabled(enabled: Boolean) = settingsViewModel.setNotificationsEnabled(enabled)
    fun setRealSimSyncEnabled(enabled: Boolean) = settingsViewModel.setRealSimSyncEnabled(enabled)

    fun getH1Candles(symbol: String): List<CandleBar> = marketViewModel.getH1Candles(symbol)

    fun ensureH1Candles(symbol: String) = marketViewModel.ensureH1Candles(symbol)

    fun selectPair(pair: TradingPair) {
        marketViewModel.selectPair(pair)
        lastSavedSignalTimestamp = 0L
        positionCoordinator.setSelectedSymbol(pair.symbol)
        if (strategyMode.value == StrategyMode.SCALPING) {
            MtfCacheManager.setActiveSymbol(pair.symbol)
        }
        val loaded = marketDataCoordinator.loadPairCache(pair.symbol, selectedTimeframe.value)
        if (!loaded) marketDataCoordinator.clearPairData(pair.symbol)
        marketDataCoordinator.startMarketPolling(pair, selectedTimeframe.value)
        agu.analys.engine.global.GlobalContextManager.subscribeCoin(pair.baseAsset)
    }

    fun toggleSimpleChart() = marketViewModel.toggleSimpleChart()

    fun selectTimeframe(tf: Timeframe) {
        if (selectedTimeframe.value == tf) return
        marketViewModel.selectTimeframe(tf)
        selectPair(selectedPair.value)
    }

    fun selectChartStyle(style: ChartStyle) = marketViewModel.selectChartStyle(style)

    fun toggleChartExpanded() = marketViewModel.toggleChartExpanded()

    fun setUiPriceThrottleMs(ms: Long) {
        marketViewModel.setUiPriceThrottleMs(ms)
        marketDataCoordinator.setPriceFeedThrottleMs(ms)
    }

    fun retryConnection() {
        marketDataCoordinator.startMarketPolling(selectedPair.value, selectedTimeframe.value)
        refreshWorthCoinsFromMarket()
        MtfCacheManager.setActiveSymbol(selectedPair.value.symbol)
    }

    fun simulateDisconnect() = marketDataCoordinator.markOffline("Mode offline manual.")

    fun toggleWatchlist(symbol: String) = watchlistViewModel.toggleWatchlist(symbol)

    fun addToWatchlist(symbol: String) = watchlistViewModel.addToWatchlist(symbol)

    fun removeFromWatchlist(symbol: String) = watchlistViewModel.removeFromWatchlist(symbol)

    fun isWatched(symbol: String): Boolean = watchlistViewModel.isWatched(symbol)

    fun setCustomWatchlist(symbols: Collection<String>) = watchlistViewModel.setCustomWatchlist(symbols)

    fun applyWatchlistPreset(presetType: String) = watchlistViewModel.applyWatchlistPreset(presetType)

    fun toggleFavorite(symbol: String) = watchlistViewModel.toggleFavorite(symbol)

    fun isFavorite(symbol: String): Boolean = watchlistViewModel.isFavorite(symbol)

    fun selectCustomSymbol(rawSymbol: String) {
        marketViewModel.selectCustomSymbol(rawSymbol)?.let { selectPair(it) }
    }

    fun selectAndWatch(rawSymbol: String, addToWatchlist: Boolean = true) {
        if (rawSymbol.isBlank()) return
        val pair = TradingPair.fromCustomSymbol(rawSymbol, "IDR")
        selectPair(pair)
        if (addToWatchlist && !watchlistViewModel.isWatched(pair.symbol)) {
            watchlistViewModel.addToWatchlist(pair.symbol)
        }
    }

    fun recalculateDashboardBadges() {
        marketViewModel.recalculateDashboardBadges(
            watchlistSymbols = watchlist.value,
            favoritesSymbols = favorites.value,
            activeStrategy = strategyMode.value
        )
    }

    fun refreshWorthCoinsFromMarket() {
        marketViewModel.refreshWorthCoinsFromMarket(
            watchlistSymbols = watchlist.value,
            favoritesSymbols = favorites.value,
            activeStrategy = strategyMode.value
        )
    }

    fun startDashboardPolling() {
        marketViewModel.startDashboardPolling(
            watchlistSymbols = watchlist.value,
            favoritesSymbols = favorites.value,
            activeStrategy = strategyMode.value
        )
    }

    fun stopDashboardPolling() {
        marketViewModel.stopDashboardPolling()
    }

    fun onAppResume() {
        marketDataCoordinator.startMarketPolling(selectedPair.value, selectedTimeframe.value)
        refreshWorthCoinsFromMarket()
        startDashboardPolling()
        MtfCacheManager.setActiveSymbol(selectedPair.value.symbol)
        if (prefs.hasIndodaxCredentials()) {
            syncRealBalancesToPositionStore()
        }
    }

    fun requestDeepAiAudit() {
        val tick = currentTick.value ?: return
        if (connectionState.value !is MarketConnectionState.Connected || isAuditLoading.value || isGeminiLoading.value) return
        aiNewsViewModel.requestDeepAiAudit(
            tick = tick,
            indicators = currentIndicators.value,
            signal = aiSignalState.value
        )
    }

    fun clearAuditReport() = aiNewsViewModel.clearAuditReport()

    fun requestGeminiChartSummary() {
        val tick = currentTick.value ?: return
        if (connectionState.value !is MarketConnectionState.Connected || isAuditLoading.value || isGeminiLoading.value) return
        aiNewsViewModel.requestGeminiChartSummary(
            tick = tick,
            indicators = currentIndicators.value,
            signal = aiSignalState.value
        )
    }

    fun clearGeminiSummary() = aiNewsViewModel.clearGeminiSummary()

    fun refreshSpotPosition() {
        positionCoordinator.refreshPosition(selectedPair.value.symbol)
    }

    fun getPositionFor(symbol: String, isReal: Boolean = isRealBuyMode.value): SpotPosition =
        positionCoordinator.getPosition(symbol, isReal)

    fun isMatchingSymbol(s1: String, s2: String): Boolean = positionCoordinator.isSameSymbol(s1, s2)

    fun getEngineSignal(symbol: String): AISignalState? =
        if (isMatchingSymbol(symbol, selectedPair.value.symbol)) engine.signalState.value else null

    fun getSignalLogsForSymbol(symbol: String): kotlinx.coroutines.flow.Flow<List<SignalLogEntity>> =
        signalLogRepository.getLogsBySymbolFlow(symbol)

    fun seedSampleSignalLogs() = signalLogViewModel.seedSampleLogsIfEmpty()
    fun deleteSignalLog(id: Long) = signalLogViewModel.deleteLog(id)
    fun clearAllSignalLogs() = signalLogViewModel.clearAllLogs()
    fun resolveSignalLogManually(id: Long, isWin: Boolean, exitPrice: Double? = null, pnlPct: Double? = null, note: String = "") {
        signalLogViewModel.resolveLogManually(id, isWin, exitPrice, pnlPct, note)
    }
    fun refreshSignalLogs() = signalLogViewModel.refreshSignalLogs()

    fun seedSampleTradeJourneys() = portfolioViewModel.seedSampleTradeJourneys()
    fun deleteTradeHistoryRecord(id: Long) = portfolioViewModel.deleteTradeHistoryRecord(id)
    fun clearAllTradeHistoryRecords() = portfolioViewModel.clearAllTradeHistoryRecords()

    override fun onCleared() {
        marketViewModel.stopDashboardPolling()
        marketDataCoordinator.stopPolling()
        super.onCleared()
    }
}
