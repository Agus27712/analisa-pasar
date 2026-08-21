package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import agu.analys.service.IndodaxMarketWebSocket
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
import agu.analys.util.GitHubUpdater
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
    private val simulationStore = SimulationTradeStore(application)
    private val simCoordinator = SimulationCoordinator(simulationStore)
    private val realCoordinator = RealTradeCoordinator(viewModelScope, prefs)
    private val updateCoordinator = AppUpdateCoordinator(viewModelScope)

    // WebSocket Indodax
    private val indodaxWebSocket = IndodaxMarketWebSocket(
        scope = viewModelScope,
        onTick = { tick ->
            handleWebSocketTick(tick)
        },
        onCandle = { candle ->
            handleWebSocketCandle(candle)
        },
        onConnected = {
            if (_isScalpingMode.value) {
                _connectionState.value = MarketConnectionState.Connected
                _isShowingCachedData.value = false
            }
        },
        onDisconnected = {
            if (_isScalpingMode.value) {
                _connectionState.value = MarketConnectionState.ConnectionLost("WebSocket realtime Indodax terputus. Menunggu koneksi kembali.")
            }
        }
    )

    private fun handleWebSocketTick(tick: MarketTick) {
        if (!_isScalpingMode.value || tick.symbol != _selectedPair.value.symbol) return
        val previous = _currentTick.value
        val normalized = tick.copy(
            symbol = _selectedPair.value.symbol,
            high24h = previous?.high24h ?: tick.price,
            low24h = previous?.low24h ?: tick.price,
            volume24h = previous?.volume24h ?: 0.0,
            change24h = previous?.change24h ?: Double.NaN
        )
        _currentTick.value = normalized
        engine.onTickUpdate(normalized)
        val prices = _recentPrices.value.toMutableList().apply { add(tick.price) }
        if (prices.size > 50) prices.removeAt(0)
        _recentPrices.value = prices

        // Trigger Realtime Simulation Matching Engine
        simCoordinator.onPriceTick(normalized.symbol, normalized.price, normalized.high24h, normalized.low24h)
    }

    private fun handleWebSocketCandle(candle: CandleBar) {
        if (!_isScalpingMode.value || candle.timestamp < (_recentCandles.value.firstOrNull()?.timestamp ?: 0L)) return
        val updated = (_recentCandles.value + candle).distinctBy { it.timestamp }.sortedBy { it.timestamp }.takeLast(300)
        _recentCandles.value = updated
        engine.onCandleUpdate(candle)
    }

    private val _marketDataSource = MutableStateFlow(prefs.marketDataSource)
    val marketDataSource: StateFlow<MarketDataSource> = _marketDataSource.asStateFlow()

    private val _currentScreen = MutableStateFlow(AppScreen.DASHBOARD)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _selectedPair = MutableStateFlow(TradingPair.popularPairsForSource(prefs.marketDataSource).first())
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

    private val _dashboardTicks = MutableStateFlow<Map<String, MarketTick>>(emptyMap())
    val dashboardTicks: StateFlow<Map<String, MarketTick>> = _dashboardTicks.asStateFlow()

    private val _connectionState = MutableStateFlow<MarketConnectionState>(MarketConnectionState.Loading)
    val connectionState: StateFlow<MarketConnectionState> = _connectionState.asStateFlow()

    private val _watchlist = MutableStateFlow(
        prefs.getWatchlist().let { set ->
            if (set.isEmpty()) {
                val defaultSymbol = "BTCIDR"
                prefs.toggleWatchlist(defaultSymbol)
                setOf(defaultSymbol)
            } else set
        }
    )
    val watchlist: StateFlow<Set<String>> = _watchlist.asStateFlow()

    private val _isShowingCachedData = MutableStateFlow(false)
    val isShowingCachedData: StateFlow<Boolean> = _isShowingCachedData.asStateFlow()

    private val _spotPosition = MutableStateFlow(SpotPosition())
    val spotPosition: StateFlow<SpotPosition> = _spotPosition.asStateFlow()

    private val _strategyMode = MutableStateFlow(prefs.strategyMode)
    val strategyMode: StateFlow<StrategyMode> = _strategyMode.asStateFlow()

    private val _isScalpingMode = MutableStateFlow(prefs.isScalpingMode)
    val isScalpingMode: StateFlow<Boolean> = _isScalpingMode.asStateFlow()

    private val _scalpingSensitivity = MutableStateFlow(prefs.scalpingSensitivity)
    val scalpingSensitivity: StateFlow<ScalpingSensitivity> = _scalpingSensitivity.asStateFlow()

    private val _tradingFees = MutableStateFlow(prefs.tradingFees)
    val tradingFees: StateFlow<TradingFeeConfig> = _tradingFees.asStateFlow()

    // --- REAL INDODAX TRADING (Delegated to RealTradeCoordinator) ---
    val isRealBuyMode: StateFlow<Boolean> = realCoordinator.isRealBuyMode
    val isPinUnlocked: StateFlow<Boolean> = realCoordinator.isPinUnlocked
    val realIndodaxBalance: StateFlow<Map<String, Double>> = realCoordinator.realIndodaxBalance
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = realCoordinator.realAvgBuyPrices
    val isFetchingRealBalance: StateFlow<Boolean> = realCoordinator.isFetchingRealBalance
    val realTradeStatus: StateFlow<String?> = realCoordinator.realTradeStatus
    val userPublicIp: StateFlow<String> = realCoordinator.userPublicIp
    val failedPinAttempts: StateFlow<Int> = realCoordinator.failedPinAttempts
    val securityAlertMessage: StateFlow<String?> = realCoordinator.securityAlertMessage

    fun checkPublicIp() = realCoordinator.checkPublicIp()
    fun clearSecurityAlert() = realCoordinator.clearSecurityAlert()
    fun hasSecurityPin(): Boolean = realCoordinator.hasSecurityPin()
    fun hasRealCredentialsConfigured(): Boolean = realCoordinator.hasRealCredentialsConfigured()
    fun createSecurityPin(pin: String) = realCoordinator.createSecurityPin(pin)
    fun saveRealCredentialsAndPin(pin: String, apiKey: String, secretKey: String): Boolean = realCoordinator.saveRealCredentialsAndPin(pin, apiKey, secretKey)
    fun wipeSecurityCredentials() = realCoordinator.wipeSecurityCredentials()
    fun verifyPin(pin: String): Boolean = realCoordinator.verifyPin(pin)
    fun lockPin() = realCoordinator.lockPin()
    fun setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean = realCoordinator.setRealBuyMode(enabled, pin)
    fun fetchRealBalance() {
        viewModelScope.launch {
            val allTicks = IndodaxMarketService.fetchAllMarketTicks()
            if (allTicks.isNotEmpty()) {
                _dashboardTicks.value = _dashboardTicks.value + allTicks
            }
        }
        realCoordinator.fetchRealBalance()
    }
    fun executeRealTrade(pair: String, type: String, price: Long, amountIdr: Double, onResult: (Boolean, String) -> Unit) =
        realCoordinator.executeRealTrade(pair, type, price, amountIdr, onResult)

    // Simulation Trading StateFlows (Delegated to SimulationCoordinator)
    val simulationWallet: StateFlow<SimulationWallet> = simCoordinator.wallet
    val simulationOpenOrders: StateFlow<List<SimulationOrder>> = simCoordinator.openOrders
    val simulationHistory: StateFlow<List<SimulationTradeHistoryItem>> = simCoordinator.history
    val lastFilledSimulationOrder: StateFlow<SimulationOrder?> = simCoordinator.lastFilledOrder

    fun refreshSimulationState() = simCoordinator.refresh()

    fun submitSimulationOrder(
        side: SimulationOrderSide,
        type: SimulationOrderType,
        price: Double,
        stopPrice: Double = 0.0,
        quantity: Double
    ): SimulationOrderResult {
        return simCoordinator.submitOrder(
            pair = _selectedPair.value,
            currentPrice = _currentTick.value?.price ?: price,
            side = side,
            type = type,
            price = price,
            stopPrice = stopPrice,
            quantity = quantity
        )
    }

    fun cancelSimulationOrder(orderId: String): Boolean = simCoordinator.cancelOrder(orderId)

    fun cancelAllSimulationOrders(symbol: String? = null): Int = simCoordinator.cancelAllOrders(symbol)

    fun topUpSimulationBalance(amount: Double) = simCoordinator.topUpIdr(amount)

    fun resetSimulationAccount() = simCoordinator.resetAccount()

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

        startActiveWebSocket(_selectedPair.value.symbol)

        val tick = _currentTick.value
        val candles = _recentCandles.value
        if (tick != null) {
            engine.resetForOffline()
            candles.forEach { engine.onCandleUpdate(it) }
            engine.onTickUpdate(tick)
        }
        refreshWorthCoinsFromMarket()
    }

    fun setScalpingMode(enabled: Boolean) {
        setStrategyMode(if (enabled) StrategyMode.SCALPING else StrategyMode.SECOND_WAVE)
    }

    private fun startActiveWebSocket(symbol: String) {
        indodaxWebSocket.start(symbol)
    }

    private fun stopActiveWebSockets(notify: Boolean = true) {
        indodaxWebSocket.stop(notify)
    }

    fun setScalpingSensitivity(sensitivity: ScalpingSensitivity) {
        if (_scalpingSensitivity.value == sensitivity) return
        _scalpingSensitivity.value = sensitivity
        prefs.scalpingSensitivity = sensitivity
        engine.scalpingSensitivity = sensitivity
        val tick = _currentTick.value
        val candles = _recentCandles.value
        if (tick != null && candles.isNotEmpty() && _isScalpingMode.value) {
            engine.resetForOffline()
            engine.onTickUpdate(tick)
            candles.forEach { engine.onCandleUpdate(it) }
        }
    }

    fun updateTradingFees(fees: TradingFeeConfig) {
        prefs.tradingFees = fees
        _tradingFees.value = fees
        engine.tradingFees = fees
    }

    private var lastSavedSignalTimestamp = 0L
    private var lastCandleRefresh = 0L
    private var lastDepthRefresh = 0L
    private var marketPollJob: Job? = null
    private var dashboardPollJob: Job? = null

    init {
        engine.strategyMode = _strategyMode.value
        engine.isScalpingMode = _isScalpingMode.value
        engine.scalpingSensitivity = _scalpingSensitivity.value
        engine.tradingFees = prefs.tradingFees
        restoreFromCache()
        val initialPair = TradingPair.popularPairsForSource(prefs.marketDataSource).first()
        selectPair(initialPair)
        startDashboardPolling()
        listenToEngineSignals()
        checkPublicIp()
    }

    private fun restoreFromCache() {
        val source = _marketDataSource.value
        val ticks = marketCache.loadDashboardTicks(source)
        val worth = marketCache.loadWorthCoins(source)
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

    fun openCoinDetail(pair: TradingPair) {
        selectPair(pair)
        navigateTo(AppScreen.DETAIL)
    }

    fun openSimulation(pair: TradingPair? = null) {
        if (pair != null) selectPair(pair)
        navigateTo(AppScreen.SIMULATION_TRADE)
    }

    fun openPortfolio() { navigateTo(AppScreen.PORTFOLIO) }

    fun openLandscapeChart() { navigateTo(AppScreen.LANDSCAPE_CHART) }
    fun closeLandscapeChart() { goBack() }
    fun openSettings() { navigateTo(AppScreen.SETTINGS) }
    fun openLearning() { navigateTo(AppScreen.LEARNING) }
    fun goBack() {
        if (navigationStack.isNotEmpty()) {
            _currentScreen.value = navigationStack.removeAt(navigationStack.size - 1)
        } else if (_currentScreen.value != AppScreen.DASHBOARD) {
            _currentScreen.value = AppScreen.DASHBOARD
        }
    }

    fun getGroqApiKey() = prefs.groqApiKey
    fun saveGroqApiKey(key: String) { prefs.groqApiKey = key }
    fun getGeminiApiKey() = prefs.geminiApiKey
    fun saveGeminiApiKey(key: String) { prefs.geminiApiKey = key }

    fun toggleWatchlist(symbol: String) {
        prefs.toggleWatchlist(symbol)
        _watchlist.value = prefs.getWatchlist()
    }

    fun isWatched(symbol: String) = prefs.isInWatchlist(symbol)

    fun selectCustomSymbol(rawSymbol: String) {
        if (rawSymbol.isNotBlank()) {
            selectPair(TradingPair.fromCustomSymbol(rawSymbol, "IDR"))
        }
    }

    fun selectAndWatch(rawSymbol: String, addToWatchlist: Boolean = true) {
        if (rawSymbol.isBlank()) return
        val pair = TradingPair.fromCustomSymbol(rawSymbol, "IDR")
        selectPair(pair)
        if (addToWatchlist && !prefs.isInWatchlist(pair.symbol)) toggleWatchlist(pair.symbol)
    }

    fun refreshSpotPosition() {
        _spotPosition.value = positionStore.get(_selectedPair.value.symbol)
    }

    fun setOwnership(owned: Boolean, referenceEntryPrice: Double = 0.0) {
        val symbol = _selectedPair.value.symbol
        if (owned) {
            positionStore.markBought(
                symbol,
                referenceEntryPrice.takeIf { it > 0.0 } ?: _spotPosition.value.entryPrice.takeIf { it > 0.0 } ?: 0.0
            )
        } else {
            positionStore.markSold(symbol)
        }
        refreshSpotPosition()
    }

    fun setManualPositionPrice(symbol: String, entryPrice: Double, investedAmount: Double = 0.0) {
        positionStore.setManualEntryPrice(symbol, entryPrice, investedAmount)
        refreshSpotPosition()
    }

    private fun startDashboardPolling() {
        dashboardPollJob?.cancel()
        dashboardPollJob = viewModelScope.launch {
            while (isActive) {
                refreshWorthCoinsFromMarket()
                delay(15_000L)
            }
        }
    }

    fun refreshWorthCoinsFromMarket() {
        viewModelScope.launch {
            val scalpingMode = _isScalpingMode.value

            // Fetch REAL Indodax Market Data
            val gainersJob = async {
                IndodaxMarketService.fetchScalpingGainersTicks(limit = 30, excludeStable = true)
            }
            val volJob = async {
                IndodaxMarketService.fetchTopVolumeTicks(limit = 30, excludeStable = true)
            }
            val pairs = (TradingPair.POPULAR_INDODAX_PAIRS + _watchlist.value.map { TradingPair.fromCustomSymbol(it, "IDR") }).distinctBy { it.symbol }
            val ticks = IndodaxMarketService.fetchTickers(pairs.map { it.effectiveIndodaxPair() })

            val gainers = gainersJob.await()
            val topVol = volJob.await()

            if (gainers.isNotEmpty()) {
                _gainersCoins.value = gainers.take(10)
                _hotCoins.value = gainers.take(10)
            }
            if (topVol.isNotEmpty()) {
                _topVolumeCoins.value = topVol.take(10)
            }

            if (ticks.isEmpty() && gainers.isEmpty() && topVol.isEmpty()) {
                if (_dashboardTicks.value.isEmpty() && _hotCoins.value.isEmpty()) markMarketOffline("Tidak ada respons market dari Indodax.")
                else {
                    _isShowingCachedData.value = true
                    _connectionState.value = MarketConnectionState.ConnectionLost("Koneksi terputus. Menampilkan data cache terakhir.")
                }
                return@launch
            }
            val allScanned = (gainers + topVol).distinctBy { it.symbol }
            val scannedMap = allScanned.associateBy { it.symbol }
            val combinedTicks = (ticks.associateBy { it.symbol } + scannedMap)
            _dashboardTicks.value = combinedTicks
            if (!_isScalpingMode.value || _connectionState.value !is MarketConnectionState.Connected) _connectionState.value = MarketConnectionState.Connected
            _isShowingCachedData.value = false
            marketCache.saveDashboardTicks(MarketDataSource.INDODAX, _dashboardTicks.value)

            // EVALUATE SECOND-WAVE HUNTER CANDIDATES (Top 4)
            val secondWaveCandidates = combinedTicks.values
                .filter { it.price > 0 && it.high24h > 0 && it.volume24h >= 1_000_000_000 }
                .map { tick ->
                    val fastScore = SecondWaveEvaluator.evaluateFast(tick, tick.high24h, tick.low24h)
                    tick to fastScore
                }
                .sortedWith(
                    compareByDescending<Pair<MarketTick, agu.analys.engine.secondwave.FastSecondWaveScore>> { it.second.score }
                        .thenByDescending { it.first.volume24h }
                )
                .map { it.first }
                .take(10)

            _secondWaveCoins.value = if (secondWaveCandidates.isNotEmpty()) secondWaveCandidates else gainers.take(10)

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
                    change24h >= 8 -> 40
                    change24h >= 3 -> 32
                    change24h > 0 -> 25
                    change24h >= -3 -> 12
                    change24h >= -8 -> 6
                    else -> 2
                }
                val volaScore = min(20, (rangePct * 1.5).toInt())
                val score = (volScore + momentumScore + volaScore).coerceIn(1, 99)
                val rec = when {
                    change24h >= 5.0 -> "PUMP / MOMENTUM NAIK"
                    change24h > 0.0 -> "BERGERAK NAIK"
                    change24h >= -2.0 -> "LAYAK DIPANTAU"
                    change24h <= -8.0 -> "TEKANAN JUAL"
                    else -> "NETRAL / VOLATIL"
                }
                WorthCoinInfo(
                    pair = pair,
                    worthScore = score,
                    isWorthIt = score >= 50 && change24h > 0,
                    recommendation = rec,
                    potentialProfitPct = abs(change24h),
                    aiRationale = "${PriceFormatter.formatPrice(tick.price)} · Vol ${PriceFormatter.formatVolume(tick.volume24h)} · +${PriceFormatter.formatPercentage(change24h, false)}"
                )
            }.sortedWith(
                if (scalpingMode) {
                    compareByDescending<WorthCoinInfo> { info ->
                        val tick = combinedTicks[info.pair.symbol]
                        tick?.change24h?.takeIf { it.isFinite() } ?: -999.0
                    }.thenByDescending { it.worthScore }
                } else {
                    compareByDescending { it.worthScore }
                }
            )
            _worthCoins.value = worth
            marketCache.saveWorthCoins(MarketDataSource.INDODAX, worth)
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
        refreshSpotPosition()

        val (cachedTick, cachedCandles) = marketCache.loadPairSnapshot(pair.symbol, _selectedTimeframe.value)
        if (cachedTick != null || cachedCandles.isNotEmpty()) {
            if (cachedTick != null) _currentTick.value = cachedTick
            if (cachedCandles.isNotEmpty()) {
                _recentCandles.value = cachedCandles
                engine.resetForOffline()
                cachedTick?.let { engine.onTickUpdate(it) }
                cachedCandles.forEach { engine.onCandleUpdate(it) }
            }
            _isShowingCachedData.value = true
        } else {
            clearLiveData()
        }

        startMarketPolling(pair)

        if (_isScalpingMode.value) {
            startActiveWebSocket(pair.symbol)
        } else {
            stopActiveWebSockets(false)
        }
    }

    private fun startMarketPolling(pair: TradingPair) {
        marketPollJob?.cancel()
        marketPollJob = viewModelScope.launch {
            if (_currentTick.value == null) _connectionState.value = MarketConnectionState.Loading
            var failCount = 0
            lastCandleRefresh = 0L
            lastDepthRefresh = 0L

            while (isActive) {
                val prev = _currentTick.value?.price ?: 0.0
                val tick = IndodaxMarketService.fetchTicker(pair.effectiveIndodaxPair(), prevPrice = prev)

                if (tick != null && tick.price > 0) {
                    failCount = 0
                    if (!_isScalpingMode.value) {
                        _connectionState.value = MarketConnectionState.Connected
                        _isShowingCachedData.value = false
                    }
                    val normalizedTick = tick.copy(symbol = pair.symbol)
                    _currentTick.value = normalizedTick
                    engine.onTickUpdate(normalizedTick)

                    val hist = _recentPrices.value.toMutableList().apply { add(tick.price) }
                    if (hist.size > 50) hist.removeAt(0)
                    _recentPrices.value = hist
                    _dashboardTicks.value = _dashboardTicks.value.toMutableMap().apply { put(pair.symbol, normalizedTick) }

                    // Trigger Realtime Simulation Matching Engine
                    simCoordinator.onPriceTick(normalizedTick.symbol, normalizedTick.price, normalizedTick.high24h, normalizedTick.low24h)

                    val now = System.currentTimeMillis()
                    val selectedTf = _selectedTimeframe.value

                    // Refresh Candles
                    if (now - lastCandleRefresh >= 15_000L) {
                        val candles = IndodaxMarketService.fetchCandles(pair.effectiveIndodaxPair(), selectedTf, 300)
                        if (candles.size >= 30) {
                            _recentCandles.value = candles
                            engine.resetForOffline(preserveState = true)
                            engine.onTickUpdate(normalizedTick)
                            candles.forEach { engine.onCandleUpdate(it) }
                            lastCandleRefresh = now
                            marketCache.savePairSnapshot(pair.symbol, selectedTf, normalizedTick, candles)
                        } else if (_recentCandles.value.isNotEmpty()) {
                            marketCache.savePairSnapshot(pair.symbol, selectedTf, normalizedTick, _recentCandles.value)
                        }
                    }

                    // Refresh Depth & Trades
                    if (now - lastDepthRefresh >= 5_000L) {
                        val depth = async { IndodaxMarketService.fetchOrderBook(pair.effectiveIndodaxPair()) }
                        val trades = async { IndodaxMarketService.fetchRecentTrades(pair.effectiveIndodaxPair()) }
                        val (bids, asks) = depth.await()
                        val newTrades = trades.await()
                        if (bids.isNotEmpty()) _orderBookBids.value = bids
                        if (asks.isNotEmpty()) _orderBookAsks.value = asks
                        if (newTrades.isNotEmpty()) _tradeStream.value = newTrades
                        lastDepthRefresh = now
                    }
                } else {
                    failCount++
                    if (failCount >= 2 && !_isScalpingMode.value) {
                        _isShowingCachedData.value = true
                        _connectionState.value = MarketConnectionState.ConnectionLost("Koneksi pasar Indodax terputus. Menampilkan data cache terakhir.")
                    }
                    delay(5000L)
                    continue
                }
                delay(3000L)
            }
        }
    }

    private fun clearLiveData() {
        val lastPrice = _currentTick.value?.price ?: 0.0
        val lastCandles = _recentCandles.value
        _currentTick.value = null
        _recentPrices.value = emptyList()
        _recentCandles.value = emptyList()
        _orderBookBids.value = emptyList()
        _orderBookAsks.value = emptyList()
        _tradeStream.value = emptyList()
        engine.resetForOffline(preserveState = false, lastKnownPrice = lastPrice, cachedCandles = lastCandles)
    }

    private fun markMarketOffline(reason: String) {
        marketPollJob?.cancel()
        val lastPrice = _currentTick.value?.price ?: 0.0
        val lastCandles = _recentCandles.value
        if (_dashboardTicks.value.isEmpty() && _worthCoins.value.isEmpty()) {
            clearLiveData()
        } else {
            engine.resetForOffline(preserveState = false, lastKnownPrice = lastPrice, cachedCandles = lastCandles)
        }
        _isShowingCachedData.value = _dashboardTicks.value.isNotEmpty() || _currentTick.value != null
        _connectionState.value = MarketConnectionState.ConnectionLost(reason)
    }

    fun toggleSimpleChart() { _useSimpleChart.value = !_useSimpleChart.value }
    fun selectTimeframe(tf: Timeframe) {
        if (_selectedTimeframe.value == tf) return
        _selectedTimeframe.value = tf
        selectPair(_selectedPair.value)
    }
    fun selectChartStyle(style: ChartStyle) { _selectedChartStyle.value = style }
    fun toggleChartExpanded() { _isChartExpanded.value = !_isChartExpanded.value }

    fun requestDeepAiAudit() {
        val tick = _currentTick.value ?: return
        if (_connectionState.value !is MarketConnectionState.Connected || _isAuditLoading.value || _isGeminiLoading.value) return
        val indicators = currentIndicators.value
        val signal = aiSignalState.value
        viewModelScope.launch {
            _isAuditLoading.value = true
            _auditReportText.value = null
            try {
                _auditReportText.value = GroqAiService.generateDeepMarketAudit(prefs.groqApiKey, tick, indicators, signal)
            } finally {
                _isAuditLoading.value = false
            }
        }
    }

    fun clearAuditReport() { _auditReportText.value = null }

    fun requestGeminiChartSummary() {
        val tick = _currentTick.value ?: return
        if (_connectionState.value !is MarketConnectionState.Connected || _isAuditLoading.value || _isGeminiLoading.value) return
        val indicators = currentIndicators.value
        val signal = aiSignalState.value
        viewModelScope.launch {
            _isGeminiLoading.value = true
            _geminiSummaryText.value = null
            try {
                _geminiSummaryText.value = GeminiAiService.generateChartSummary24h(prefs.geminiApiKey, tick, indicators, signal)
            } finally {
                _isGeminiLoading.value = false
            }
        }
    }

    fun clearGeminiSummary() { _geminiSummaryText.value = null }

    fun retryConnection() {
        _connectionState.value = MarketConnectionState.Loading
        startMarketPolling(_selectedPair.value)
        if (_isScalpingMode.value) {
            startActiveWebSocket(_selectedPair.value.symbol)
        }
        refreshWorthCoinsFromMarket()
    }

    fun simulateDisconnect() {
        stopActiveWebSockets(false)
        val exchangeName = _marketDataSource.value.label
        markMarketOffline("Mode offline: koneksi pasar $exchangeName dihentikan. Data cache terakhir tetap ditampilkan.")
    }

    // GitHub Updater (Delegated to AppUpdateCoordinator)
    val githubReleaseInfo: StateFlow<GitHubReleaseInfo?> = updateCoordinator.releaseInfo
    val updateCheckStatus: StateFlow<String?> = updateCoordinator.updateCheckStatus
    val isCheckingUpdate: StateFlow<Boolean> = updateCoordinator.isCheckingUpdate
    val updateDownloadProgress: StateFlow<Int?> = updateCoordinator.downloadProgress

    fun checkGitHubUpdate(context: android.content.Context, repo: String = prefs.updateRepo, token: String = prefs.updateGitHubToken) {
        updateCoordinator.checkUpdate(context, repo, token)
    }

    fun downloadAndInstallUpdate(context: android.content.Context, repo: String = prefs.updateRepo, token: String = prefs.updateGitHubToken) {
        updateCoordinator.downloadAndInstall(context, repo, token)
    }

    override fun onCleared() {
        stopActiveWebSockets(false)
        marketPollJob?.cancel()
        dashboardPollJob?.cancel()
        super.onCleared()
    }
}
