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
    internal val engine = LearningTradingEngine(viewModelScope)
    internal val prefs = AppPreferences(application)
    private val marketCache = MarketDataCache(application)
    internal val positionStore = SpotPositionStore(application)
    internal val alertStore = agu.analys.trading.PriceAlertStore(application)
    internal val simulationStore = SimulationTradeStore(application)
    internal val simCoordinator = SimulationCoordinator(simulationStore)
    internal val realCoordinator = RealTradeCoordinator(viewModelScope, prefs)
    internal val updateCoordinator = AppUpdateCoordinator(viewModelScope)

    @Volatile private var lastLiveTickAt = 0L
    @Volatile private var wsLive = false

    // WebSocket Indodax — REST tetap fallback harga
    private val indodaxWebSocket = IndodaxMarketWebSocket(
        scope = viewModelScope,
        onTick = { tick -> handleWebSocketTick(tick) },
        onCandle = { candle -> handleWebSocketCandle(candle) },
        onConnected = {
            wsLive = true
            lastLiveTickAt = System.currentTimeMillis()
            _connectionState.value = MarketConnectionState.Connected
            _isShowingCachedData.value = false
        },
        onDisconnected = {
            wsLive = false
            // Jangan panik offline: REST polling masih jaga harga
            val recentRest = System.currentTimeMillis() - lastLiveTickAt < 12_000L
            if (!recentRest && _currentTick.value == null) {
                _connectionState.value = MarketConnectionState.ConnectionLost(
                    "Realtime terputus. Mencoba reconnect + REST fallback..."
                )
            }
        }
    )

    private fun handleWebSocketTick(tick: MarketTick) {
        if (tick.symbol != _selectedPair.value.symbol &&
            !tick.symbol.equals(_selectedPair.value.symbol, true)
        ) {
            // Terima juga format pair tanpa underscore
            val normalizedSym = _selectedPair.value.symbol
            if (!tick.symbol.equals(normalizedSym.replace("_", ""), true)) return
        }
        lastLiveTickAt = System.currentTimeMillis()
        wsLive = true
        if (_connectionState.value !is MarketConnectionState.Connected) {
            _connectionState.value = MarketConnectionState.Connected
            _isShowingCachedData.value = false
        }
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
        simCoordinator.onPriceTick(normalized.symbol, normalized.price, normalized.high24h, normalized.low24h)
        agu.analys.service.TradingForegroundService.updatePrice(getApplication(), normalized.symbol, normalized.price)
        checkAlertsAndTrailing(normalized.symbol, normalized.price, currentIndicators.value.rsi14.takeIf { it.isFinite() })
    }

    private fun handleWebSocketCandle(candle: CandleBar) {
        engine.currentFormingVolume = candle.volume
    }

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

    private val _recentPrices = MutableStateFlow<List<Double>>(emptyList())
    val recentPrices: StateFlow<List<Double>> = _recentPrices.asStateFlow()

    private val _recentCandles = MutableStateFlow<List<CandleBar>>(emptyList())
    val recentCandles: StateFlow<List<CandleBar>> = _recentCandles.asStateFlow()

    private val _isChartExpanded = MutableStateFlow(false)
    val isChartExpanded: StateFlow<Boolean> = _isChartExpanded.asStateFlow()

    internal val _currentTick = MutableStateFlow<MarketTick?>(null)
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

    private val _dashboardTicks = MutableStateFlow<Map<String, MarketTick>>(emptyMap())
    val dashboardTicks: StateFlow<Map<String, MarketTick>> = _dashboardTicks.asStateFlow()

    internal val _connectionState = MutableStateFlow<MarketConnectionState>(MarketConnectionState.Loading)
    val connectionState: StateFlow<MarketConnectionState> = _connectionState.asStateFlow()

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
    val mtfState = agu.analys.util.MtfCacheManager.mtfState

    init {
        agu.analys.util.MtfCacheManager.updateQueues(_watchlist.value.toList(), emptyList())
    }

    private val _isShowingCachedData = MutableStateFlow(false)
    val isShowingCachedData: StateFlow<Boolean> = _isShowingCachedData.asStateFlow()

    internal val _spotPosition = MutableStateFlow(SpotPosition())
    val spotPosition: StateFlow<SpotPosition> = _spotPosition.asStateFlow()

    internal val _priceAlerts = MutableStateFlow<List<agu.analys.model.PriceAlert>>(emptyList())
    val priceAlerts: StateFlow<List<agu.analys.model.PriceAlert>> = _priceAlerts.asStateFlow()

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

    val isRealBuyMode: StateFlow<Boolean> = realCoordinator.isRealBuyMode
    val isPinUnlocked: StateFlow<Boolean> = realCoordinator.isPinUnlocked
    val realIndodaxBalance: StateFlow<Map<String, Double>> = realCoordinator.realIndodaxBalance
    val realFreeBalance: StateFlow<Map<String, Double>> = realCoordinator.realFreeBalance
    val realLockedBalance: StateFlow<Map<String, Double>> = realCoordinator.realLockedBalance
    val realOpenOrders: StateFlow<List<RealOpenOrderEntity>> = realCoordinator.realOpenOrders
    val realTrades: StateFlow<List<RealTradeEntity>> = realCoordinator.realTrades
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = realCoordinator.realAvgBuyPrices
    val isFetchingRealBalance: StateFlow<Boolean> = realCoordinator.isFetchingRealBalance
    val realTradeStatus: StateFlow<String?> = realCoordinator.realTradeStatus
    val userPublicIp: StateFlow<String> = realCoordinator.userPublicIp
    val failedPinAttempts: StateFlow<Int> = realCoordinator.failedPinAttempts
    val securityAlertMessage: StateFlow<String?> = realCoordinator.securityAlertMessage

    fun executeCancelRealOrder(symbol: String, orderId: String, onResult: (Boolean, String) -> Unit) =
        realCoordinator.executeCancelRealOrder(symbol, orderId, onResult)

    fun checkPublicIp() = realCoordinator.checkPublicIp()
    fun clearSecurityAlert() = realCoordinator.clearSecurityAlert()
    fun hasSecurityPin(): Boolean = realCoordinator.hasSecurityPin()
    fun hasRealCredentialsConfigured(): Boolean = realCoordinator.hasRealCredentialsConfigured()
    fun createSecurityPin(pin: String) = realCoordinator.createSecurityPin(pin)
    fun saveRealCredentialsAndPin(pin: String, apiKey: String, secretKey: String): Boolean =
        realCoordinator.saveRealCredentialsAndPin(pin, apiKey, secretKey)
    fun wipeSecurityCredentials() = realCoordinator.wipeSecurityCredentials()
    fun verifyPin(pin: String): Boolean = realCoordinator.verifyPin(pin)
    fun lockPin() = realCoordinator.lockPin()
    fun setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean = realCoordinator.setRealBuyMode(enabled, pin)
    fun fetchRealBalance() {
        viewModelScope.launch {
            val allTicks = IndodaxMarketService.fetchAllMarketTicks()
            if (allTicks.isNotEmpty()) _dashboardTicks.value = _dashboardTicks.value + allTicks
        }
        realCoordinator.fetchRealBalance()
    }
    fun executeRealTrade(pair: String, type: String, price: Long, amountIdr: Double, autoLimitSellPrice1: Double = 0.0, autoLimitSellPrice2: Double = 0.0, onResult: (Boolean, String) -> Unit) =
        realCoordinator.executeRealTrade(pair, type, price, amountIdr, autoLimitSellPrice1, autoLimitSellPrice2, onResult)

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
    ): SimulationOrderResult = simCoordinator.submitOrder(
        pair = _selectedPair.value,
        currentPrice = _currentTick.value?.price ?: price,
        side = side, type = type, price = price, stopPrice = stopPrice, quantity = quantity
    )

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

    fun refreshSpotPosition() { _spotPosition.value = positionStore.get(_selectedPair.value.symbol) }

    fun setOwnership(owned: Boolean, referenceEntryPrice: Double = 0.0) {
        val symbol = _selectedPair.value.symbol
        if (owned) {
            positionStore.markBought(
                symbol,
                referenceEntryPrice.takeIf { it > 0.0 } ?: _spotPosition.value.entryPrice.takeIf { it > 0.0 } ?: 0.0
            )
        } else positionStore.markSold(symbol)
        refreshSpotPosition()
    }

    fun setManualPositionPrice(symbol: String, entryPrice: Double, investedAmount: Double = 0.0) {
        positionStore.setManualEntryPrice(symbol, entryPrice, investedAmount)
        refreshSpotPosition()
    }

    fun setTrailingStop(enabled: Boolean, trailingPercent: Double) {
        val symbol = _selectedPair.value.symbol
        val currentP = _currentTick.value?.price ?: _spotPosition.value.entryPrice
        positionStore.setTrailingStop(symbol, enabled, trailingPercent, currentP)
        refreshSpotPosition()
    }

    fun setAutoSellParams(
        enabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        stopLossPrice: Double
    ) {
        val symbol = _selectedPair.value.symbol
        positionStore.setAutoSellParams(symbol, enabled, tp1Price, tp1Percent, tp2Price, tp2Percent, stopLossPrice)
        refreshSpotPosition()

        if (enabled && isRealBuyMode.value) {
            realCoordinator.executeRealAutoSellOnServer(
                pair = symbol,
                tp1Price = tp1Price,
                tp1Percent = tp1Percent,
                tp2Price = tp2Price,
                tp2Percent = tp2Percent
            ) { success, msg ->
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        if (success) "Split TP Server Berhasil:\n$msg" else "Gagal pasang Split TP Server: $msg",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun resetTrailingTrigger() {
        positionStore.resetTrailingTrigger(_selectedPair.value.symbol)
        refreshSpotPosition()
    }

    fun refreshPriceAlerts() { _priceAlerts.value = alertStore.getAlertsForSymbol(_selectedPair.value.symbol) }
    fun addPriceAlert(alert: agu.analys.model.PriceAlert) { alertStore.addAlert(alert); refreshPriceAlerts() }
    fun removePriceAlert(alertId: String) { alertStore.removeAlert(alertId); refreshPriceAlerts() }
    fun togglePriceAlert(alertId: String) { alertStore.toggleAlert(alertId); refreshPriceAlerts() }

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
            val gainersJob = async { IndodaxMarketService.fetchScalpingGainersTicks(30, true) }
            val volJob = async { IndodaxMarketService.fetchTopVolumeTicks(30, true) }
            val pairs = (TradingPair.POPULAR_INDODAX_PAIRS + _watchlist.value.map {
                TradingPair.fromCustomSymbol(it, "IDR")
            }).distinctBy { it.symbol }
            val ticks = IndodaxMarketService.fetchTickers(pairs.map { it.effectiveIndodaxPair() })
            val gainers = gainersJob.await()
            val topVol = volJob.await()
            if (gainers.isNotEmpty()) {
                _gainersCoins.value = gainers.take(10)
                _hotCoins.value = gainers.take(10)
            }
            if (topVol.isNotEmpty()) _topVolumeCoins.value = topVol.take(10)
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
            marketCache.saveDashboardTicks(MarketDataSource.INDODAX, _dashboardTicks.value)

            val secondWaveCandidates = combinedTicks.values
                .filter { it.price > 0 && it.high24h > 0 && it.volume24h >= 1_000_000_000 }
                .map { t -> t to SecondWaveEvaluator.evaluateFast(t, t.high24h, t.low24h) }
                .sortedWith(
                    compareByDescending<Pair<MarketTick, agu.analys.engine.secondwave.FastSecondWaveScore>> { it.second.score }
                        .thenByDescending { it.first.volume24h }
                )
                .map { it.first }
                .take(10)
            _secondWaveCoins.value = secondWaveCandidates.ifEmpty { gainers.take(10) }

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
        refreshPriceAlerts()
        val (cachedTick, cachedCandles) = marketCache.loadPairSnapshot(pair.symbol, _selectedTimeframe.value)
        if (cachedTick != null || cachedCandles.isNotEmpty()) {
            if (cachedTick != null) _currentTick.value = cachedTick
            if (cachedCandles.isNotEmpty()) {
                _recentCandles.value = cachedCandles
                engine.resetForOffline()
                cachedTick?.let { engine.onTickUpdate(it) }
            }
            _isShowingCachedData.value = true
        } else clearLiveData()
        startMarketPolling(pair)
        // WS selalu on — harga lebih responsif di semua mode
        startActiveWebSocket(pair.symbol)
    }

    private fun startMarketPolling(pair: TradingPair) {
        marketPollJob?.cancel()
        marketPollJob = viewModelScope.launch {
            if (_currentTick.value == null) _connectionState.value = MarketConnectionState.Loading
            var failCount = 0
            lastCandleRefresh = 0L
            lastDepthRefresh = 0L

            while (isActive) {
                // Restart WS jika zombie (stale > 25s)
                if (indodaxWebSocket.isStale(25_000L)) {
                    startActiveWebSocket(pair.symbol)
                }

                val prev = _currentTick.value?.price ?: 0.0
                val tick = IndodaxMarketService.fetchTicker(pair.effectiveIndodaxPair(), prevPrice = prev)

                if (tick != null && tick.price > 0) {
                    failCount = 0
                    lastLiveTickAt = System.currentTimeMillis()
                    // REST sukses = tetap LIVE (meski WS putus)
                    _connectionState.value = MarketConnectionState.Connected
                    _isShowingCachedData.value = false

                    val normalizedTick = tick.copy(symbol = pair.symbol)
                    // Jangan override harga WS yang lebih fresh (< 2s)
                    val preferWs = wsLive && System.currentTimeMillis() - lastLiveTickAt < 2_000L
                    if (!preferWs || _currentTick.value == null) {
                        _currentTick.value = normalizedTick
                        engine.onTickUpdate(normalizedTick)
                    }

                    val hist = _recentPrices.value.toMutableList().apply { add(tick.price) }
                    if (hist.size > 50) hist.removeAt(0)
                    _recentPrices.value = hist
                    _dashboardTicks.value = _dashboardTicks.value.toMutableMap().apply { put(pair.symbol, normalizedTick) }
                    simCoordinator.onPriceTick(normalizedTick.symbol, normalizedTick.price, normalizedTick.high24h, normalizedTick.low24h)
                    checkAlertsAndTrailing(normalizedTick.symbol, normalizedTick.price, currentIndicators.value.rsi14.takeIf { it.isFinite() })

                    val now = System.currentTimeMillis()
                    if (now - lastCandleRefresh >= 15_000L) {
                        val candles = IndodaxMarketService.fetchCandles(pair.effectiveIndodaxPair(), _selectedTimeframe.value, 300)
                        if (candles.size >= 30) {
                            _recentCandles.value = candles
                            engine.resetForOffline(preserveState = true)
                            engine.onTickUpdate(normalizedTick)
                            lastCandleRefresh = now
                            marketCache.savePairSnapshot(pair.symbol, _selectedTimeframe.value, normalizedTick, candles)
                        }
                    }
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
                    if (failCount >= 4 && System.currentTimeMillis() - lastLiveTickAt > 20_000L) {
                        _isShowingCachedData.value = true
                        _connectionState.value = MarketConnectionState.ConnectionLost(
                            "Koneksi pasar Indodax lemah. Menampilkan cache."
                        )
                    }
                    delay(4000L)
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
        engine.resetForOffline(false, lastPrice, lastCandles)
    }

    private fun markMarketOffline(reason: String) {
        marketPollJob?.cancel()
        val lastPrice = _currentTick.value?.price ?: 0.0
        val lastCandles = _recentCandles.value
        if (_dashboardTicks.value.isEmpty() && _worthCoins.value.isEmpty()) clearLiveData()
        else engine.resetForOffline(false, lastPrice, lastCandles)
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

    fun retryConnection() {
        _connectionState.value = MarketConnectionState.Loading
        startMarketPolling(_selectedPair.value)
        startActiveWebSocket(_selectedPair.value.symbol)
        refreshWorthCoinsFromMarket()
        agu.analys.util.MtfCacheManager.setActiveSymbol(_selectedPair.value.symbol)
    }

    fun simulateDisconnect() {
        stopActiveWebSockets(false)
        markMarketOffline("Mode offline: koneksi dihentikan manual.")
    }

    val githubReleaseInfo: StateFlow<GitHubReleaseInfo?> = updateCoordinator.releaseInfo
    val updateCheckStatus: StateFlow<String?> = updateCoordinator.updateCheckStatus
    val isCheckingUpdate: StateFlow<Boolean> = updateCoordinator.isCheckingUpdate
    val updateDownloadProgress: StateFlow<Int?> = updateCoordinator.downloadProgress

    override fun onCleared() {
        stopActiveWebSockets(false)
        marketPollJob?.cancel()
        dashboardPollJob?.cancel()
        super.onCleared()
    }
}
