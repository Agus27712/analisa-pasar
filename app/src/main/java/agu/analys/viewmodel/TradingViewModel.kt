package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.bridge.TradingViewBridge
import agu.analys.config.MarketDataSource
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.LearningTradingEngine
import agu.analys.model.AISignalState
import agu.analys.model.AppScreen
import agu.analys.model.CandleBar
import agu.analys.model.ChartStyle
import agu.analys.model.MarketConnectionState
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.PositionContext
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
import agu.analys.model.TradingPair
import agu.analys.model.TradingWorkflow
import agu.analys.model.WorthCoinInfo
import agu.analys.model.resolveWorkflow
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.trading.SimulationTradeStore
import agu.analys.trading.SimulationWallet
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import agu.analys.ui.animation.PriceAnimationMode
import agu.analys.ui.theme.AccentColorPreset
import agu.analys.ui.theme.AnimationSpeed
import agu.analys.ui.theme.CandleColorStyle
import agu.analys.ui.theme.ThemeStyle
import agu.analys.util.AppPreferences
import agu.analys.util.GitHubReleaseInfo
import agu.analys.util.MarketDataCache
import agu.analys.database.AppDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Main ViewModel orchestrating market data, trading engines, simulation, real Indodax trading,
 * and UI states through dedicated coordinators and modular extensions.
 */
class TradingViewModel(application: Application) : AndroidViewModel(application) {
    val bridge = TradingViewBridge(viewModelScope)
    internal val engine = LearningTradingEngine(viewModelScope)
    internal val prefs = AppPreferences(application)
    internal val marketCache = MarketDataCache(application)
    internal val positionStore = SpotPositionStore(application)
    internal val alertStore = agu.analys.trading.PriceAlertStore(application)
    internal val simulationStore = SimulationTradeStore(application)

    internal val simCoordinator = SimulationCoordinator(
        store = simulationStore,
        onOrderFilled = { order ->
            syncSimulationTradeToPositionStore(order)
        }
    )

    internal val realCoordinator = RealTradeCoordinator(
        scope = viewModelScope,
        prefs = prefs,
        onBalanceAndAvgUpdated = { balances, avgPrices ->
            syncRealBalancesToPositionStore(balances, avgPrices)
        },
        onRealTradeExecuted = { pair, type, price, quantity, tp1, tp2 ->
            syncRealTradeToSimulation(pair, type, price, quantity, tp1, tp2)
        }
    )

    internal val updateCoordinator = AppUpdateCoordinator(viewModelScope)

    internal val positionCoordinator = PositionCoordinator(
        positionStore = positionStore,
        alertStore = alertStore,
        isRealProvider = { isRealBuyMode.value },
        onPositionChanged = { /* handled reactive */ }
    )

    internal val batchSellCoordinator = BatchSellCoordinator(
        context = application,
        scope = viewModelScope,
        prefs = prefs,
        simCoordinator = simCoordinator,
        realCoordinator = realCoordinator,
        positionStore = positionStore,
        positionCoordinator = positionCoordinator
    )
    val batchExecutionState: StateFlow<agu.analys.model.BatchExecutionState> = batchSellCoordinator.executionState

    fun executeBatchSellReadyAssets(
        items: List<agu.analys.model.ReadySellCoinSummary>,
        isRealMode: Boolean,
        pin: String? = null,
        onCompleted: ((agu.analys.model.BatchResultSummary) -> Unit)? = null
    ) {
        batchSellCoordinator.executeBatchSell(items, isRealMode, pin, onCompleted)
    }

    fun resetBatchSellState() {
        batchSellCoordinator.resetState()
    }

    internal val marketDataCoordinator = MarketDataCoordinator(
        scope = viewModelScope,
        prefs = prefs,
        marketCache = marketCache,
        engine = engine,
        simCoordinator = simCoordinator,
        onPriceUpdate = { symbol, price, rsi -> 
            this@TradingViewModel.checkAlertsAndTrailing(symbol, price, rsi)
            agu.analys.service.TradingForegroundService.updatePrice(getApplication(), symbol, price)
        }
    )

    internal val _marketDataSource = MutableStateFlow(prefs.marketDataSource)
    val marketDataSource: StateFlow<MarketDataSource> = _marketDataSource.asStateFlow()

    val globalContext: StateFlow<agu.analys.engine.global.GlobalMarketContext> = agu.analys.engine.global.GlobalContextManager.context
    
    internal val _currentScreen = MutableStateFlow(AppScreen.DASHBOARD)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    internal val _selectedPair = MutableStateFlow(TradingPair.popularPairsForSource(prefs.marketDataSource).first())
    val selectedPair: StateFlow<TradingPair> = _selectedPair.asStateFlow()

    internal val _selectedTimeframe = MutableStateFlow(Timeframe.H4)
    val selectedTimeframe: StateFlow<Timeframe> = _selectedTimeframe.asStateFlow()

    internal val _selectedChartStyle = MutableStateFlow(ChartStyle.CANDLES)
    val selectedChartStyle: StateFlow<ChartStyle> = _selectedChartStyle.asStateFlow()

    internal val _useSimpleChart = MutableStateFlow(false)
    val useSimpleChart: StateFlow<Boolean> = _useSimpleChart.asStateFlow()

    val recentPrices: StateFlow<List<Double>> = marketDataCoordinator.recentPrices
    val recentCandles: StateFlow<List<CandleBar>> = marketDataCoordinator.recentCandles

    internal val _isChartExpanded = MutableStateFlow(false)
    val isChartExpanded: StateFlow<Boolean> = _isChartExpanded.asStateFlow()

    val currentTick: StateFlow<MarketTick?> = marketDataCoordinator.currentTick
    val uiPriceThrottleMs: StateFlow<Long> = marketDataCoordinator.uiPriceThrottleMs
    val currentIndicators: StateFlow<TechnicalIndicators> = engine.indicators
    val aiSignalState: StateFlow<AISignalState> = engine.signalState

    val orderBookBids: StateFlow<List<OrderBookItem>> = marketDataCoordinator.orderBookBids
    val orderBookAsks: StateFlow<List<OrderBookItem>> = marketDataCoordinator.orderBookAsks
    val tradeStream: StateFlow<List<TradeStreamItem>> = marketDataCoordinator.tradeStream

    internal val _signalHistory = MutableStateFlow<List<AISignalState>>(emptyList())
    val signalHistory: StateFlow<List<AISignalState>> = _signalHistory.asStateFlow()

    internal val _auditReportText = MutableStateFlow<String?>(null)
    val auditReportText: StateFlow<String?> = _auditReportText.asStateFlow()

    internal val _isAuditLoading = MutableStateFlow(false)
    val isAuditLoading: StateFlow<Boolean> = _isAuditLoading.asStateFlow()

    internal val _geminiSummaryText = MutableStateFlow<String?>(null)
    val geminiSummaryText: StateFlow<String?> = _geminiSummaryText.asStateFlow()

    internal val _isGeminiLoading = MutableStateFlow(false)
    val isGeminiLoading: StateFlow<Boolean> = _isGeminiLoading.asStateFlow()

    internal val _newsScreenerState = MutableStateFlow<agu.analys.model.NewsScreenerUiState>(agu.analys.model.NewsScreenerUiState.Idle)
    val newsScreenerState: StateFlow<agu.analys.model.NewsScreenerUiState> = _newsScreenerState.asStateFlow()

    internal val _worthCoins = MutableStateFlow<List<WorthCoinInfo>>(emptyList())
    val worthCoins: StateFlow<List<WorthCoinInfo>> = _worthCoins.asStateFlow()

    internal val _hotCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val hotCoins: StateFlow<List<MarketTick>> = _hotCoins.asStateFlow()

    internal val _gainersCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val gainersCoins: StateFlow<List<MarketTick>> = _gainersCoins.asStateFlow()

    internal val _losersCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val losersCoins: StateFlow<List<MarketTick>> = _losersCoins.asStateFlow()

    internal val _secondWaveCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val secondWaveCoins: StateFlow<List<MarketTick>> = _secondWaveCoins.asStateFlow()

    internal val _topVolumeCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val topVolumeCoins: StateFlow<List<MarketTick>> = _topVolumeCoins.asStateFlow()

    internal val _usdtIdrRate = MutableStateFlow(16450.0)
    val usdtIdrRate: StateFlow<Double> = _usdtIdrRate.asStateFlow()

    internal val _strategyMode = MutableStateFlow(prefs.strategyMode)
    val strategyMode: StateFlow<StrategyMode> = _strategyMode.asStateFlow()

    internal val _isScalpingMode = MutableStateFlow(prefs.isScalpingMode)
    val isScalpingMode: StateFlow<Boolean> = _isScalpingMode.asStateFlow()

    internal val _scalpingSensitivity = MutableStateFlow(prefs.scalpingSensitivity)
    val scalpingSensitivity: StateFlow<ScalpingSensitivity> = _scalpingSensitivity.asStateFlow()

    internal val _tradingFees = MutableStateFlow(prefs.tradingFees)
    val tradingFees: StateFlow<TradingFeeConfig> = _tradingFees.asStateFlow()

    internal val _isDarkTheme = MutableStateFlow(prefs.isDarkTheme)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    internal val _themeStyle = MutableStateFlow(prefs.themeStyle)
    val themeStyle: StateFlow<ThemeStyle> = _themeStyle.asStateFlow()

    internal val _accentColorPreset = MutableStateFlow(prefs.accentColorPreset)
    val accentColorPreset: StateFlow<AccentColorPreset> = _accentColorPreset.asStateFlow()

    internal val _candleColorStyle = MutableStateFlow(prefs.candleColorStyle)
    val candleColorStyle: StateFlow<CandleColorStyle> = _candleColorStyle.asStateFlow()

    internal val _animationSpeed = MutableStateFlow(prefs.animationSpeed)
    val animationSpeed: StateFlow<AnimationSpeed> = _animationSpeed.asStateFlow()

    internal val _priceAnimationMode = MutableStateFlow(prefs.priceAnimationMode)
    val priceAnimationMode: StateFlow<PriceAnimationMode> = _priceAnimationMode.asStateFlow()

    internal val _isPriceTickPulseEnabled = MutableStateFlow(prefs.isPriceTickPulseEnabled)
    val isPriceTickPulseEnabled: StateFlow<Boolean> = _isPriceTickPulseEnabled.asStateFlow()

    internal val _isSmoothChartEnabled = MutableStateFlow(prefs.isSmoothChartEnabled)
    val isSmoothChartEnabled: StateFlow<Boolean> = _isSmoothChartEnabled.asStateFlow()

    internal val _isNotificationsEnabled = MutableStateFlow(prefs.isNotificationsEnabled)
    val isNotificationsEnabled: StateFlow<Boolean> = _isNotificationsEnabled.asStateFlow()

    internal val _isNotifyCandidateBuyEnabled = MutableStateFlow(prefs.isNotifyCandidateBuyEnabled)
    val isNotifyCandidateBuyEnabled: StateFlow<Boolean> = _isNotifyCandidateBuyEnabled.asStateFlow()

    internal val _isNotifyPriceAlertsEnabled = MutableStateFlow(prefs.isNotifyPriceAlertsEnabled)
    val isNotifyPriceAlertsEnabled: StateFlow<Boolean> = _isNotifyPriceAlertsEnabled.asStateFlow()

    internal val _isNotifyTrailingStopEnabled = MutableStateFlow(prefs.isNotifyTrailingStopEnabled)
    val isNotifyTrailingStopEnabled: StateFlow<Boolean> = _isNotifyTrailingStopEnabled.asStateFlow()

    internal val _isRealSimSyncEnabled = MutableStateFlow(prefs.isRealSimSyncEnabled)
    val isRealSimSyncEnabled: StateFlow<Boolean> = _isRealSimSyncEnabled.asStateFlow()

    val isShowingCachedData: StateFlow<Boolean> = marketDataCoordinator.isShowingCachedData
    internal val _spotPosition = MutableStateFlow(SpotPosition())
    val spotPosition: StateFlow<SpotPosition> = positionCoordinator.spotPosition
    val positionVersion: StateFlow<Long> = positionCoordinator.positionVersion
    val priceAlerts: StateFlow<List<agu.analys.model.PriceAlert>> = positionCoordinator.priceAlerts

    internal var dashboardPollJob: Job? = null
    internal var trailingPollJob: Job? = null
    internal var lastLiveTickAt = 0L
    internal val _dashboardTicks = MutableStateFlow<Map<String, MarketTick>>(emptyMap())
    internal val _connectionState = MutableStateFlow<MarketConnectionState>(MarketConnectionState.ConnectionLost())
    internal val _isShowingCachedData = MutableStateFlow(false)
    internal val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val dashboardTicks: StateFlow<Map<String, MarketTick>> = marketDataCoordinator.dashboardTicks
    val connectionState: StateFlow<MarketConnectionState> = marketDataCoordinator.connectionState

    internal val _watchlist = MutableStateFlow(
        prefs.getWatchlist().let { set ->
            if (set.isEmpty()) {
                val defaultSymbol = "BTCIDR"
                prefs.toggleWatchlist(defaultSymbol)
                setOf(defaultSymbol)
            } else set
        }
    )
    val watchlist: StateFlow<Set<String>> = _watchlist.asStateFlow()

    internal val _favorites = MutableStateFlow(
        prefs.getFavorites().let { set ->
            if (set.isEmpty()) {
                val defaultSymbol = "BTCIDR"
                prefs.toggleFavorite(defaultSymbol)
                setOf(defaultSymbol)
            } else set
        }
    )
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    internal val _coinBadges = MutableStateFlow<Map<String, List<agu.analys.model.CoinBadge>>>(emptyMap())
    val coinBadges: StateFlow<Map<String, List<agu.analys.model.CoinBadge>>> = _coinBadges.asStateFlow()

    val mtfState = agu.analys.util.MtfCacheManager.mtfState

    val simulationWallet: StateFlow<SimulationWallet> = simCoordinator.wallet
    val simulationOpenOrders: StateFlow<List<SimulationOrder>> = simCoordinator.openOrders
    val simulationHistory: StateFlow<List<SimulationTradeHistoryItem>> = simCoordinator.history
    val lastFilledSimulationOrder: StateFlow<SimulationOrder?> = simCoordinator.lastFilledOrder

    val isRealBuyMode: StateFlow<Boolean> = realCoordinator.isRealBuyEnabled
    val isPinUnlocked: StateFlow<Boolean> = realCoordinator.isPinUnlocked
    val realIndodaxBalance: StateFlow<Map<String, Double>> = realCoordinator.realIndodaxBalance
    val realFreeBalance: StateFlow<Map<String, Double>> = realCoordinator.realFreeBalance
    val realLockedBalance: StateFlow<Map<String, Double>> = realCoordinator.realLockedBalance
    val realOpenOrders: StateFlow<List<RealOpenOrderEntity>> = AppDatabase.getInstance().realTradeDao().getOpenOrdersFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val realTrades: StateFlow<List<RealTradeEntity>> = AppDatabase.getInstance().realTradeDao().getAllTradesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = realCoordinator.realAvgBuyPrices
    
    val holdingStatuses: StateFlow<Map<String, CoinHoldingStatus>> = combine(
        simCoordinator.wallet,
        realIndodaxBalance,
        realAvgBuyPrices,
        realCoordinator.isRealBuyEnabled,
        positionCoordinator.positionVersion
    ) { wallet, realBal, _, isRealMode, _ ->
        val defaultQuote = prefs.marketDataSource.defaultQuoteAsset
        val basePairs = TradingPair.popularPairsForSource(prefs.marketDataSource)
        val watchPairs = _watchlist.value.map { TradingPair.fromCustomSymbol(it, defaultQuote) }
        val favPairs = _favorites.value.map { TradingPair.fromCustomSymbol(it, defaultQuote) }
        val simPairs = if (!isRealMode) {
            wallet.coinBalances.filter { it.value > 0.00000001 && !it.key.equals("IDR", true) && !it.key.equals("USDT", true) }
                .map { TradingPair.fromCustomSymbol(it.key, defaultQuote) }
        } else emptyList()
        val realPairs = if (isRealMode) {
            realBal.filter { it.value > 0.00000001 && !it.key.equals("IDR", true) && !it.key.equals("USDT", true) }
                .map { TradingPair.fromCustomSymbol(it.key, defaultQuote) }
        } else emptyList()
        val pairs = (basePairs + watchPairs + favPairs + simPairs + realPairs).distinctBy { it.symbol }
        
        pairs.associate { pair ->
            pair.symbol to getHoldingStatus(pair, isRealMode)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val positionContext: StateFlow<PositionContext> = combine(
        _selectedPair,
        spotPosition,
        currentTick,
        holdingStatuses,
        isRealBuyMode
    ) { pair, spotPos, tick, statuses, isRealMode ->
        val holding = statuses[pair.symbol] ?: getHoldingStatus(pair, isRealMode)
        val tp = tick?.price ?: 0.0
        val price = if (tp > 0.0 && tp.isFinite()) tp else 0.0
        PositionContext.create(
            symbol = pair.symbol,
            spotPosition = spotPos,
            holdingStatus = holding,
            currentPrice = price,
            fees = _tradingFees.value,
            currentModeIsReal = isRealMode
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PositionContext())

    val tradingWorkflow: StateFlow<TradingWorkflow> = positionContext
        .map { resolveWorkflow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TradingWorkflow.BUY)

    val sellSignalState: StateFlow<agu.analys.model.SellSignalState> = combine(
        positionContext,
        currentIndicators
    ) { posContext, indicators ->
        agu.analys.engine.sell.SellSignalEvaluator.evaluate(posContext, indicators, tradingFees.value)
    }
    .onEach { state ->
        val symbol = _selectedPair.value.symbol
        val isReal = isRealBuyMode.value
        val transition = agu.analys.engine.sell.SellSignalLifecycleManager.process(symbol, state, isReal)
        if (transition.hasTriggeringTransition && isNotificationsEnabled.value) {
            agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                notificationId = symbol.hashCode() + (if (isReal) 1000 else 2000),
                title = "Sinyal Jual ${if (isReal) "[REAL]" else "[SIMULASI]"}: $symbol",
                message = "${state.reason} - P/L: ${agu.analys.util.PriceFormatter.formatPercentage(state.netProfitPct, includePlusSign = true)}",
                symbol = symbol,
                onlyWhenBackground = true
            )
        }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), agu.analys.model.SellSignalState())
    
    val isFetchingRealBalance: StateFlow<Boolean> = realCoordinator.isFetchingRealBalance
    val realTradeStatus: StateFlow<String> = realCoordinator.realTradeStatus
    val userPublicIp: StateFlow<String?> = realCoordinator.publicIp
    val failedPinAttempts: StateFlow<Int> = MutableStateFlow(prefs.failedPinAttempts).asStateFlow()

    internal var lastSavedSignalTimestamp = 0L
    internal val navigationStack = mutableListOf<AppScreen>()

    val githubReleaseInfo: StateFlow<GitHubReleaseInfo?> = updateCoordinator.releaseInfo
    val updateCheckStatus: StateFlow<String?> = updateCoordinator.updateCheckStatus
    val isCheckingUpdate: StateFlow<Boolean> = updateCoordinator.isCheckingUpdate
    val updateDownloadProgress: StateFlow<Int?> = updateCoordinator.downloadProgress

    init {
        agu.analys.util.MtfCacheManager.updateQueues(_watchlist.value.toList(), emptyList())
        engine.strategyMode = prefs.strategyMode
        engine.isScalpingMode = prefs.isScalpingMode
        engine.scalpingSensitivity = prefs.scalpingSensitivity
        engine.tradingFees = prefs.tradingFees

        engine.onCandidateSignalTransition = { transition ->
            if (isNotificationsEnabled.value) {
                val position = positionStore.get(transition.symbol, isReal = isRealBuyMode.value)
                if (!position.isHolding) {
                    agu.analys.util.AlertNotificationHelper.sendCandidateFoundNotification(
                        context = getApplication(),
                        symbol = transition.symbol,
                        strategyMode = transition.mode,
                        signal = transition.signal
                    )
                }
            }
        }

        marketDataCoordinator.restoreFromCache(MarketDataSource.INDODAX)
        val initialPair = TradingPair.popularPairsForSource(prefs.marketDataSource).first()
        selectPair(initialPair)
        startDashboardPolling()
        startTrailingPolling()
        updateForegroundServiceState()
        listenToEngineSignals()
        checkPublicIp()

        // Sync initial cached real positions on startup immediately
        if (prefs.hasIndodaxCredentials()) {
            syncRealBalancesToPositionStore()
        }

        viewModelScope.launch {
            isRealBuyMode.collect {
                refreshSpotPosition()
                recalculateDashboardBadges()
            }
        }

        viewModelScope.launch {
            simCoordinator.lastFilledOrder.collect { filledOrder ->
                if (filledOrder != null && filledOrder.status == agu.analys.trading.SimulationOrderStatus.FILLED) {
                    if (filledOrder.side == SimulationOrderSide.SELL) {
                        positionStore.markSold(filledOrder.symbol, isReal = false)
                        positionCoordinator.setTrailing(filledOrder.symbol, enabled = false, 0.0, 0.0, isReal = false)
                        refreshSpotPosition()
                        checkAndStopTrailingServiceIfEmpty()
                    }
                }
            }
        }
    }

    private fun listenToEngineSignals() {
        viewModelScope.launch {
            engine.signalState.collect { signal ->
                val now = System.currentTimeMillis()
                if (signal.action != SignalAction.HOLD && now - lastSavedSignalTimestamp > 15000L) {
                    lastSavedSignalTimestamp = now
                    val list = _signalHistory.value.toMutableList()
                    list.add(0, signal.copy(marketSymbol = _selectedPair.value.symbol))
                    if (list.size > 30) list.removeAt(list.lastIndex)
                    _signalHistory.value = list
                }
            }
        }
    }

    override fun onCleared() {
        marketDataCoordinator.stopPolling()
        super.onCleared()
    }
}
