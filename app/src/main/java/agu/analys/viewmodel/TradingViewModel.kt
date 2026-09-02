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
import agu.analys.engine.secondwave.SecondWaveEvaluator
import agu.analys.model.AISignalState
import agu.analys.model.AppScreen
import agu.analys.model.CandleBar
import agu.analys.model.ChartStyle
import agu.analys.model.MarketConnectionState
import agu.analys.model.CoinHoldingStatus
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
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.trading.SimulationTradeStore
import agu.analys.trading.SimulationWallet
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import agu.analys.util.AppPreferences
import agu.analys.util.GitHubReleaseInfo
import agu.analys.util.MarketDataCache
import agu.analys.util.PriceFormatter
import agu.analys.database.AppDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

class TradingViewModel(application: Application) : AndroidViewModel(application) {
    val bridge = TradingViewBridge(viewModelScope)
    internal val engine = LearningTradingEngine(viewModelScope)
    internal val prefs = AppPreferences(application)
    private val marketCache = MarketDataCache(application)
    internal val positionStore = SpotPositionStore(application)
    internal val alertStore = agu.analys.trading.PriceAlertStore(application)
    internal val simulationStore = SimulationTradeStore(application)
    internal val simCoordinator = SimulationCoordinator(simulationStore)
    internal val realCoordinator = RealTradeCoordinator(viewModelScope, prefs)
    internal val updateCoordinator = AppUpdateCoordinator(viewModelScope)
    
    init {
        viewModelScope.launch {
            simCoordinator.lastFilledOrder.collect { filledOrder ->
                if (filledOrder != null && filledOrder.status == agu.analys.trading.SimulationOrderStatus.FILLED) {
                    if (filledOrder.side == SimulationOrderSide.SELL) {
                        positionStore.markSold(filledOrder.symbol)
                        positionCoordinator.setTrailing(filledOrder.symbol, enabled = false, 0.0, 0.0)
                        refreshSpotPosition()
                        checkAndStopTrailingServiceIfEmpty()
                    }
                }
            }
        }
    }
    
    internal val positionCoordinator = PositionCoordinator(
        positionStore = positionStore,
        alertStore = alertStore,
        onPositionChanged = { /* can add specific logic here if needed */ }
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

    private val _marketDataSource = MutableStateFlow(prefs.marketDataSource)
    val marketDataSource: StateFlow<MarketDataSource> = _marketDataSource.asStateFlow()

    internal val _currentScreen = MutableStateFlow(AppScreen.DASHBOARD)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    internal val _selectedPair = MutableStateFlow(TradingPair.popularPairsForSource(prefs.marketDataSource).first())
    val selectedPair: StateFlow<TradingPair> = _selectedPair.asStateFlow()

    private val _selectedTimeframe = MutableStateFlow(Timeframe.H4)
    val selectedTimeframe: StateFlow<Timeframe> = _selectedTimeframe.asStateFlow()

    private val _selectedChartStyle = MutableStateFlow(ChartStyle.CANDLES)
    val selectedChartStyle: StateFlow<ChartStyle> = _selectedChartStyle.asStateFlow()

    private val _useSimpleChart = MutableStateFlow(false)
    val useSimpleChart: StateFlow<Boolean> = _useSimpleChart.asStateFlow()

    val recentPrices: StateFlow<List<Double>> = marketDataCoordinator.recentPrices
    val recentCandles: StateFlow<List<CandleBar>> = marketDataCoordinator.recentCandles

    private val _isChartExpanded = MutableStateFlow(false)
    val isChartExpanded: StateFlow<Boolean> = _isChartExpanded.asStateFlow()

    val currentTick: StateFlow<MarketTick?> = marketDataCoordinator.currentTick
    val currentIndicators: StateFlow<TechnicalIndicators> = engine.indicators
    val aiSignalState: StateFlow<AISignalState> = engine.signalState

    val orderBookBids: StateFlow<List<OrderBookItem>> = marketDataCoordinator.orderBookBids
    val orderBookAsks: StateFlow<List<OrderBookItem>> = marketDataCoordinator.orderBookAsks
    val tradeStream: StateFlow<List<TradeStreamItem>> = marketDataCoordinator.tradeStream

    private val _signalHistory = MutableStateFlow<List<AISignalState>>(emptyList())
    val signalHistory: StateFlow<List<AISignalState>> = _signalHistory.asStateFlow()

    internal val _auditReportText = MutableStateFlow<String?>(null)
    val auditReportText: StateFlow<String?> = _auditReportText.asStateFlow()

    internal val _isAuditLoading = MutableStateFlow(false)
    val isAuditLoading: StateFlow<Boolean> = _isAuditLoading.asStateFlow()

    internal val _geminiSummaryText = MutableStateFlow<String?>(null)
    val geminiSummaryText: StateFlow<String?> = _geminiSummaryText.asStateFlow()

    internal val _isGeminiLoading = MutableStateFlow(false)
    val isGeminiLoading: StateFlow<Boolean> = _isGeminiLoading.asStateFlow()

    private val _worthCoins = MutableStateFlow<List<WorthCoinInfo>>(emptyList())
    val worthCoins: StateFlow<List<WorthCoinInfo>> = _worthCoins.asStateFlow()

    private val _hotCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val hotCoins: StateFlow<List<MarketTick>> = _hotCoins.asStateFlow()

    private val _gainersCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val gainersCoins: StateFlow<List<MarketTick>> = _gainersCoins.asStateFlow()

    private val _secondWaveCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val secondWaveCoins: StateFlow<List<MarketTick>> = _secondWaveCoins.asStateFlow()

    private val _topVolumeCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val topVolumeCoins: StateFlow<List<MarketTick>> = _topVolumeCoins.asStateFlow()

    private val _usdtIdrRate = MutableStateFlow(16450.0)
    val usdtIdrRate: StateFlow<Double> = _usdtIdrRate.asStateFlow()

    private val _strategyMode = MutableStateFlow(prefs.strategyMode)
    val strategyMode: StateFlow<StrategyMode> = _strategyMode.asStateFlow()

    private val _isScalpingMode = MutableStateFlow(prefs.isScalpingMode)
    val isScalpingMode: StateFlow<Boolean> = _isScalpingMode.asStateFlow()

    private val _scalpingSensitivity = MutableStateFlow(prefs.scalpingSensitivity)
    val scalpingSensitivity: StateFlow<ScalpingSensitivity> = _scalpingSensitivity.asStateFlow()

    private val _tradingFees = MutableStateFlow(prefs.tradingFees)
    val tradingFees: StateFlow<TradingFeeConfig> = _tradingFees.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(prefs.isDarkTheme)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _isNotificationsEnabled = MutableStateFlow(prefs.isNotificationsEnabled)
    val isNotificationsEnabled: StateFlow<Boolean> = _isNotificationsEnabled.asStateFlow()

    val isShowingCachedData: StateFlow<Boolean> = marketDataCoordinator.isShowingCachedData
    internal val _spotPosition = MutableStateFlow(SpotPosition())
    val spotPosition: StateFlow<SpotPosition> = positionCoordinator.spotPosition
    val priceAlerts: StateFlow<List<agu.analys.model.PriceAlert>> = positionCoordinator.priceAlerts

    private var dashboardPollJob: Job? = null
    private var trailingPollJob: Job? = null
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

    init {
        agu.analys.util.MtfCacheManager.updateQueues(_watchlist.value.toList(), emptyList())
        engine.strategyMode = prefs.strategyMode
        engine.isScalpingMode = prefs.isScalpingMode
        engine.scalpingSensitivity = prefs.scalpingSensitivity
        engine.tradingFees = prefs.tradingFees
        marketDataCoordinator.restoreFromCache(MarketDataSource.INDODAX)
        val initialPair = TradingPair.popularPairsForSource(prefs.marketDataSource).first()
        selectPair(initialPair)
        startDashboardPolling()
        startTrailingPolling()
        listenToEngineSignals()
        checkPublicIp()
    }

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
    
    val holdingStatuses: StateFlow<Map<String, agu.analys.model.CoinHoldingStatus>> = kotlinx.coroutines.flow.combine(
        marketDataCoordinator.dashboardTicks,
        simCoordinator.wallet,
        realIndodaxBalance,
        realAvgBuyPrices,
        positionCoordinator.positionVersion
    ) { _, _, _, _, _ ->
        val defaultQuote = prefs.marketDataSource.defaultQuoteAsset
        val basePairs = agu.analys.model.TradingPair.popularPairsForSource(prefs.marketDataSource)
        val watchPairs = _watchlist.value.map { agu.analys.model.TradingPair.fromCustomSymbol(it, defaultQuote) }
        val pairs = (basePairs + watchPairs).distinctBy { it.symbol }
        
        pairs.associate { pair ->
            pair.symbol to getHoldingStatus(pair)
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyMap())
    
    val isFetchingRealBalance: StateFlow<Boolean> = realCoordinator.isFetchingRealBalance
    val realTradeStatus: StateFlow<String> = realCoordinator.realTradeStatus
    val userPublicIp: StateFlow<String?> = realCoordinator.publicIp
    val failedPinAttempts: StateFlow<Int> = MutableStateFlow(prefs.failedPinAttempts).asStateFlow()

    private fun markMarketOffline(reason: String) {
        _connectionState.value = MarketConnectionState.ConnectionLost(reason = reason)
        _isShowingCachedData.value = true
    }

    fun executeCancelRealOrder(symbol: String, orderId: String, onResult: (Boolean, String) -> Unit) =
        realCoordinator.executeCancelRealOrder(symbol, orderId, onResult)

    fun checkPublicIp() = realCoordinator.checkPublicIp()
    fun clearSecurityAlert() { /* handle locally if needed */ }
    fun hasSecurityPin(): Boolean = prefs.hasSecurityPin()
    fun hasRealCredentialsConfigured(): Boolean = prefs.hasIndodaxCredentials()
    fun createSecurityPin(pin: String) { prefs.setSecurityPin(pin) }
    fun saveRealCredentialsAndPin(pin: String, apiKey: String, secretKey: String) {
        prefs.setSecurityPin(pin)
        prefs.indodaxApiKey = apiKey
        prefs.indodaxSecretKey = secretKey
    }
    fun wipeSecurityCredentials() { prefs.wipeAllRealSecurityData() }
    fun verifyPin(pin: String): Boolean = realCoordinator.verifyPin(pin)
    fun lockPin() = realCoordinator.lockPin()
    fun setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean = realCoordinator.setRealBuyMode(enabled, pin)
    fun fetchRealBalance() = realCoordinator.fetchRealBalance()
    fun refreshRealBalance() {
        viewModelScope.launch {
            val allTicks = IndodaxMarketService.fetchAllMarketTicks()
            if (allTicks.isNotEmpty()) marketDataCoordinator.updateDashboardTicks(allTicks)
        }
        fetchRealBalance()
    }
    fun executeRealTrade(pair: String, type: String, price: Long, amountIdr: Double, tp1: Double = 0.0, tp2: Double = 0.0, onResult: (Boolean, String) -> Unit) =
        realCoordinator.executeRealTrade(pair, type, price, amountIdr, tp1, tp2, onResult)

    val simulationWallet: StateFlow<SimulationWallet> = simCoordinator.wallet
    val simulationOpenOrders: StateFlow<List<SimulationOrder>> = simCoordinator.openOrders
    val simulationHistory: StateFlow<List<SimulationTradeHistoryItem>> = simCoordinator.history
    val lastFilledSimulationOrder: StateFlow<SimulationOrder?> = simCoordinator.lastFilledOrder

    fun refreshSimulationState() = simCoordinator.refresh()
    fun refreshSpotPosition() { positionCoordinator.refreshPosition(_selectedPair.value.symbol) }
    fun refreshPriceAlerts() { positionCoordinator.refreshAlerts(_selectedPair.value.symbol) }
    fun setOwnership(owned: Boolean, price: Double = 0.0, quantity: Double = 0.0, invested: Double = 0.0, isReal: Boolean = isRealBuyMode.value) {
        val symbol = _selectedPair.value.symbol
        positionCoordinator.setOwnership(symbol, owned, price, quantity, invested, isReal)
    }

    fun submitSimulationOrder(
        side: SimulationOrderSide,
        type: SimulationOrderType,
        price: Double,
        stopPrice: Double = 0.0,
        quantity: Double
    ): SimulationOrderResult = simCoordinator.submitOrder(
        pair = _selectedPair.value,
        currentPrice = marketDataCoordinator.currentTick.value?.price ?: price,
        side = side, type = type, price = price, stopPrice = stopPrice, quantity = quantity
    )

    fun cancelSimulationOrder(orderId: String): Boolean = simCoordinator.cancelOrder(orderId)
    fun cancelAllSimulationOrders(symbol: String? = null): Int = simCoordinator.cancelAllOrders(symbol)
    fun topUpSimulationBalance(amount: Double) = simCoordinator.topUpIdr(amount)
    fun resetSimulationAccount() = simCoordinator.resetAccount()

    fun getHoldingStatus(pair: TradingPair): CoinHoldingStatus {
        val baseLower = pair.baseAsset.lowercase()
        val baseUpper = pair.baseAsset.uppercase()
        val symbolNorm = pair.symbol.replace("_", "").uppercase()

        // 1. Check Real Indodax Balance
        val realBalances = realIndodaxBalance.value
        val realQty = realBalances[baseLower] ?: realBalances[baseUpper] ?: 0.0
        val hasRealBalance = realQty > 0.00000001 && baseUpper != "IDR"

        // 2. Check SpotPositionStore (Manual Spot / Real Tracking)
        val spotPos = positionStore.get(pair.symbol)
        if (spotPos.isHolding && spotPos.quantity > 0.00000001) {
            val effectiveIsReal = spotPos.isReal || (hasRealBalance && prefs.hasIndodaxCredentials())
            return CoinHoldingStatus(
                isHolding = true,
                quantity = spotPos.quantity,
                entryPrice = spotPos.entryPrice,
                isReal = effectiveIsReal,
                tp1Price = spotPos.tp1Price,
                tp2Price = spotPos.tp2Price,
                isTrailingTriggered = spotPos.isTrailingTriggered
            )
        }

        // 3. Check Real Indodax Balance fallback if spotPos not explicitly set
        if (hasRealBalance) {
            val realAvg = realAvgBuyPrices.value[symbolNorm]
                ?: realAvgBuyPrices.value[pair.symbol.uppercase()]
                ?: realAvgBuyPrices.value[baseUpper]
                ?: spotPos.entryPrice
            return CoinHoldingStatus(
                isHolding = true,
                quantity = realQty,
                entryPrice = realAvg,
                isReal = true,
                tp1Price = spotPos.tp1Price,
                tp2Price = spotPos.tp2Price,
                isTrailingTriggered = spotPos.isTrailingTriggered
            )
        }

        // 4. Check Simulation Wallet
        val simWallet = simulationWallet.value
        val simQty = simWallet.coinBalances[baseLower] ?: simWallet.coinBalances[baseUpper] ?: 0.0
        if (simQty > 0.00000001 && baseUpper != "IDR") {
            val simAvg = simWallet.avgBuyPrices[baseLower] ?: simWallet.avgBuyPrices[baseUpper] ?: 0.0
            return CoinHoldingStatus(
                isHolding = true,
                quantity = simQty,
                entryPrice = simAvg,
                isReal = false,
                tp1Price = spotPos.tp1Price,
                tp2Price = spotPos.tp2Price,
                isTrailingTriggered = spotPos.isTrailingTriggered
            )
        }

        return CoinHoldingStatus(isHolding = false)
    }

    fun setMarketDataSource(source: MarketDataSource) {
        _marketDataSource.value = source
        prefs.marketDataSource = source
        refreshWorthCoinsFromMarket()
    }

    fun setStrategyMode(mode: StrategyMode) {
        _strategyMode.value = mode
        prefs.strategyMode = mode
        val scalpingEnabled = mode == StrategyMode.SCALPING
        _isScalpingMode.value = scalpingEnabled
        prefs.isScalpingMode = scalpingEnabled
        engine.strategyMode = mode
        engine.isScalpingMode = scalpingEnabled
        engine.scalpingSensitivity = prefs.scalpingSensitivity
        engine.tradingFees = prefs.tradingFees
        
        if (mode == StrategyMode.SCALPING || mode == StrategyMode.SECOND_WAVE) {
            agu.analys.util.MtfCacheManager.setActiveSymbol(_selectedPair.value.symbol)
        }
        
        marketDataCoordinator.startMarketPolling(_selectedPair.value, _selectedTimeframe.value)
        val tick = marketDataCoordinator.currentTick.value
        if (tick != null) {
            engine.resetForOffline()
            engine.onTickUpdate(tick)
        }
        refreshWorthCoinsFromMarket()
    }

    fun setScalpingMode(enabled: Boolean) {
        setStrategyMode(if (enabled) StrategyMode.SCALPING else StrategyMode.SECOND_WAVE)
    }

    fun setScalpingSensitivity(sensitivity: ScalpingSensitivity) {
        if (_scalpingSensitivity.value == sensitivity) return
        _scalpingSensitivity.value = sensitivity
        prefs.scalpingSensitivity = sensitivity
        engine.scalpingSensitivity = sensitivity
        val tick = marketDataCoordinator.currentTick.value
        val candles = marketDataCoordinator.recentCandles.value
        if (tick != null && candles.isNotEmpty()) {
            engine.resetForOffline()
            engine.onTickUpdate(tick)
        }
    }

    fun updateTradingFees(fees: TradingFeeConfig) {
        prefs.tradingFees = fees
        _tradingFees.value = fees
        engine.tradingFees = fees
    }

    fun setDarkTheme(enabled: Boolean) {
        prefs.isDarkTheme = enabled
        _isDarkTheme.value = enabled
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.isNotificationsEnabled = enabled
        _isNotificationsEnabled.value = enabled
        if (enabled) {
            agu.analys.service.TradingForegroundService.startService(getApplication())
        } else {
            agu.analys.service.TradingForegroundService.stopService(getApplication())
        }
    }

    private var lastSavedSignalTimestamp = 0L
    internal val navigationStack = mutableListOf<AppScreen>()

    fun selectCustomSymbol(rawSymbol: String) {
        if (rawSymbol.isNotBlank()) selectPair(TradingPair.fromCustomSymbol(rawSymbol, "IDR"))
    }

    fun selectAndWatch(rawSymbol: String, addToWatchlist: Boolean = true) {
        if (rawSymbol.isBlank()) return
        val pair = TradingPair.fromCustomSymbol(rawSymbol, "IDR")
        selectPair(pair)
        if (addToWatchlist && !prefs.isInWatchlist(pair.symbol)) toggleWatchlist(pair.symbol)
    }

    fun setManualPositionPrice(symbol: String, entryPrice: Double, investedAmount: Double = 0.0, isReal: Boolean = isRealBuyMode.value) {
        positionCoordinator.setManualEntry(symbol, entryPrice, investedAmount, isReal)
    }

    fun setTrailingStop(enabled: Boolean, trailingPercent: Double) {
        val symbol = _selectedPair.value.symbol
        val currentP = currentTick.value?.price ?: spotPosition.value.entryPrice
        positionCoordinator.setTrailing(symbol, enabled, trailingPercent, currentP)
    }

    fun setAutoSellParams(
        enabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val symbol = _selectedPair.value.symbol
        positionCoordinator.setAutoSell(symbol, enabled, tp1Price, tp1Percent, tp2Price, tp2Percent)
        if (enabled) {
            val isReal = realCoordinator.isRealBuyEnabled.value
            if (isReal) {
                realCoordinator.executeRealAutoSellOnServer(symbol, tp1Price, tp1Percent, tp2Price, tp2Percent, onResult)
            } else {
                simCoordinator.placeSimulationAutoSellOrders(_selectedPair.value, tp1Price, tp1Percent, tp2Price, tp2Percent, onResult)
            }
        } else {
            onResult(true, "Auto TP dimatikan.")
        }
    }

    fun resetTrailingTrigger() {
        positionCoordinator.resetTrailing(_selectedPair.value.symbol)
    }

    fun addPriceAlert(alert: agu.analys.model.PriceAlert) { positionCoordinator.addAlert(alert, _selectedPair.value.symbol) }
    fun removePriceAlert(alertId: String) { positionCoordinator.removeAlert(alertId, _selectedPair.value.symbol) }
    fun togglePriceAlert(alertId: String) { positionCoordinator.toggleAlert(alertId, _selectedPair.value.symbol) }

    private fun startDashboardPolling() {
        dashboardPollJob?.cancel()
        dashboardPollJob = viewModelScope.launch {
            while (isActive) {
                refreshWorthCoinsFromMarket()
                delay(15_000L)
            }
        }
    }

    internal fun startTrailingPolling() {
        if (trailingPollJob?.isActive == true) return
        trailingPollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val activeSymbols = positionStore.getAllActiveTrailingSymbols()
                    if (activeSymbols.isNotEmpty()) {
                        val pairs = activeSymbols.map { TradingPair.fromCustomSymbol(it, "IDR").effectiveIndodaxPair() }
                        val ticks = IndodaxMarketService.fetchTickers(pairs)
                        for (tick in ticks) {
                            simCoordinator.onPriceTick(tick.symbol, tick.price, tick.high24h, tick.low24h)
                            checkAlertsAndTrailing(tick.symbol, tick.price)
                        }
                    } else {
                        checkAndStopTrailingServiceIfEmpty()
                    }
                } catch (e: Exception) {
                    // Ignore network error for this tick
                }
                delay(4000L)
            }
        }
    }

    internal fun checkAndStopTrailingServiceIfEmpty() {
        if (positionStore.getAllActiveTrailingSymbols().isEmpty()) {
            trailingPollJob?.cancel()
            trailingPollJob = null
            val intent = android.content.Intent(getApplication<android.app.Application>(), agu.analys.service.TradingForegroundService::class.java)
            intent.action = agu.analys.service.TradingForegroundService.ACTION_STOP
            getApplication<android.app.Application>().startService(intent)
        }
    }

    fun refreshWorthCoinsFromMarket() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val scalpingMode = _isScalpingMode.value
                val gainersJob = async { IndodaxMarketService.fetchScalpingGainersTicks(30, true) }
                val volJob = async { IndodaxMarketService.fetchTopVolumeTicks(30, true) }
                val pairs = (TradingPair.POPULAR_INDODAX_PAIRS + _watchlist.value.map {
                    TradingPair.fromCustomSymbol(it, "IDR")
                }).distinctBy { it.symbol }
                val ticks = IndodaxMarketService.fetchTickers(pairs.map { it.effectiveIndodaxPair() })
                val gainers = gainersJob.await()
                val topVol = volJob.await()
                if (gainers.isNotEmpty()) {
                    _gainersCoins.value = gainers.take(25)
                    _hotCoins.value = gainers.take(25)
                }
                if (topVol.isNotEmpty()) _topVolumeCoins.value = topVol.take(25)
                if (ticks.isEmpty() && gainers.isEmpty() && topVol.isEmpty()) {
                    if (_dashboardTicks.value.isEmpty() && _hotCoins.value.isEmpty()) {
                        markMarketOffline("Tidak ada respons market dari Indodax.")
                    } else {
                        _isShowingCachedData.value = true
                    }
                    return@launch
                }
                val allScanned = (gainers + topVol).distinctBy { it.symbol }
                val combinedTicks = ticks.associateBy { it.symbol } + allScanned.associateBy { it.symbol }
                _dashboardTicks.value = combinedTicks
                try {
                    val priceMap = combinedTicks.mapValues { it.value.price }
                    agu.analys.service.TradingForegroundService.updatePrices(getApplication(), priceMap)
                } catch (_: Exception) {}
                lastLiveTickAt = System.currentTimeMillis()
                _connectionState.value = MarketConnectionState.Connected
                _isShowingCachedData.value = false
                marketCache.saveDashboardTicks(MarketDataSource.INDODAX, combinedTicks)

                val secondWaveCandidates = combinedTicks.values
                    .filter { it.price > 0 && it.high24h > 0 && it.volume24h >= 1_000_000_000 }
                    .map { t -> t to SecondWaveEvaluator.evaluateFast(t, t.high24h, t.low24h) }
                    .sortedWith(
                        compareByDescending<Pair<MarketTick, agu.analys.engine.secondwave.FastSecondWaveScore>> { it.second.score }
                            .thenByDescending { it.first.volume24h }
                    )
                    .map { it.first }
                    .take(10)
                _secondWaveCoins.value = secondWaveCandidates.ifEmpty { gainers.take(25) }.take(25)

                val evaluatedPairs = (allScanned.map { TradingPair.fromCustomSymbol(it.symbol, "IDR") } + pairs).distinctBy { it.symbol }
                val worth = evaluatedPairs.mapNotNull { pair ->
                    val tick = combinedTicks[pair.symbol] ?: return@mapNotNull null
                    val rangePct = if (tick.low24h > 0) ((tick.high24h - tick.low24h) / tick.low24h) * 100.0 else 0.0
                    val volScore = when {
                        tick.volume24h >= 100_000_000_000 -> 30
                        tick.volume24h >= 10_000_000_000 -> 22
                        tick.volume24h >= 1_000_000_000 -> 14
                        else -> 6
                    }
                    val change24h = tick.change24h.takeIf { it.isFinite() } ?: 0.0
                    val momentumScore = when {
                        change24h >= 8 -> 40; change24h >= 3 -> 32; change24h > 0 -> 25
                        change24h >= -3 -> 12; change24h >= -8 -> 6; else -> 2
                    }
                    val score = (volScore + momentumScore + min(20, (rangePct * 1.5).toInt())).coerceIn(1, 99)
                    val rec = when {
                        change24h >= 5.0 -> "PUMP / MOMENTUM NAIK"
                        change24h > 0.0 -> "BERGERAK NAIK"
                        change24h >= -2.0 -> "LAYAK DIPANTAU"
                        change24h <= -8.0 -> "TEKANAN JUAL"
                        else -> "NETRAL / VOLATIL"
                    }
                    WorthCoinInfo(
                        pair = pair, worthScore = score,
                        isWorthIt = score >= 50 && change24h > 0,
                        recommendation = rec, potentialProfitPct = abs(change24h),
                        aiRationale = "${PriceFormatter.formatPrice(tick.price)} · Vol ${PriceFormatter.formatVolume(tick.volume24h)}"
                    )
                }.sortedWith(
                    if (scalpingMode) compareByDescending<WorthCoinInfo> {
                        combinedTicks[it.pair.symbol]?.change24h?.takeIf { c -> c.isFinite() } ?: -999.0
                    }.thenByDescending { it.worthScore }
                    else compareByDescending { it.worthScore }
                )
                _worthCoins.value = worth
                marketCache.saveWorthCoins(MarketDataSource.INDODAX, worth)
                recalculateDashboardBadges()
            } finally {
                _isRefreshing.value = false
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

    fun selectPair(pair: TradingPair) {
        _selectedPair.value = pair
        lastSavedSignalTimestamp = 0L
        positionCoordinator.refreshPosition(pair.symbol)
        positionCoordinator.refreshAlerts(pair.symbol)
        
        if (_strategyMode.value == StrategyMode.SCALPING || _strategyMode.value == StrategyMode.SECOND_WAVE) {
            agu.analys.util.MtfCacheManager.setActiveSymbol(pair.symbol)
        }
        
        val loaded = marketDataCoordinator.loadPairCache(pair.symbol, _selectedTimeframe.value)
        if (!loaded) marketDataCoordinator.clearPairData()
        marketDataCoordinator.startMarketPolling(pair, _selectedTimeframe.value)
    }

    fun toggleSimpleChart() { _useSimpleChart.value = !_useSimpleChart.value }
    fun selectTimeframe(tf: Timeframe) {
        if (_selectedTimeframe.value == tf) return
        _selectedTimeframe.value = tf
        selectPair(_selectedPair.value)
    }
    fun selectChartStyle(style: ChartStyle) { _selectedChartStyle.value = style }
    fun toggleChartExpanded() { _isChartExpanded.value = !_isChartExpanded.value }

    fun retryConnection() {
        marketDataCoordinator.startMarketPolling(_selectedPair.value, _selectedTimeframe.value)
        refreshWorthCoinsFromMarket()
        agu.analys.util.MtfCacheManager.setActiveSymbol(_selectedPair.value.symbol)
    }

    fun simulateDisconnect() {
        marketDataCoordinator.markOffline("Mode offline: koneksi dihentikan manual.")
    }

    val githubReleaseInfo: StateFlow<GitHubReleaseInfo?> = updateCoordinator.releaseInfo
    val updateCheckStatus: StateFlow<String?> = updateCoordinator.updateCheckStatus
    val isCheckingUpdate: StateFlow<Boolean> = updateCoordinator.isCheckingUpdate
    val updateDownloadProgress: StateFlow<Int?> = updateCoordinator.downloadProgress

    override fun onCleared() {
        marketDataCoordinator.stopPolling()
        super.onCleared()
    }
    
    fun toggleWatchlist(symbol: String) {
        prefs.toggleWatchlist(symbol)
        _watchlist.value = prefs.getWatchlist()
        agu.analys.util.MtfCacheManager.updateQueues(_watchlist.value.toList(), emptyList())
        recalculateDashboardBadges()
    }

    fun toggleFavorite(symbol: String) {
        prefs.toggleFavorite(symbol)
        _favorites.value = prefs.getFavorites()
        recalculateDashboardBadges()
    }

    fun isFavorite(symbol: String): Boolean =
        _favorites.value.contains(symbol.uppercase())

    fun recalculateDashboardBadges() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val defaultQuote = prefs.marketDataSource.defaultQuoteAsset
            val basePairs = TradingPair.popularPairsForSource(prefs.marketDataSource)
            val watchPairs = _watchlist.value.map { TradingPair.fromCustomSymbol(it, defaultQuote) }
            val favPairs = _favorites.value.map { TradingPair.fromCustomSymbol(it, defaultQuote) }
            val marketPairs = (_gainersCoins.value + _hotCoins.value + _topVolumeCoins.value + _secondWaveCoins.value)
                .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
            val allPairs = (marketPairs + basePairs + watchPairs + favPairs).distinctBy { it.symbol }.take(40)
            val ticks = _dashboardTicks.value
            val strategy = _strategyMode.value

            val resultMap = mutableMapOf<String, List<agu.analys.model.CoinBadge>>()
            for (pair in allPairs) {
                val tick = ticks[pair.symbol]
                if (tick != null) {
                    val badges = agu.analys.engine.badge.CoinBadgeEvaluator.evaluateBadges(
                        pair = pair,
                        tick = tick,
                        activeStrategy = strategy,
                        maxBadges = 1
                    )
                    if (badges.isNotEmpty()) {
                        resultMap[pair.symbol] = badges
                    }
                }
            }
            _coinBadges.value = resultMap
        }
    }
}
