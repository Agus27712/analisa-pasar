package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
import agu.analys.model.PositionContext
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
import agu.analys.model.TradingPair
import agu.analys.model.TradingWorkflow
import agu.analys.model.WorthCoinInfo
import agu.analys.model.resolveWorkflow
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
import agu.analys.database.SignalLogEntity
import agu.analys.database.SignalLogRepository
import agu.analys.database.TradeHistoryRecordEntity
import agu.analys.database.TradeHistoryRecorder
import agu.analys.model.SignalReliabilitySummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

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
    internal val realCoordinator: RealTradeCoordinator = RealTradeCoordinator(
        scope = viewModelScope,
        prefs = prefs,
        getLatestTick = { symbol -> marketDataCoordinator.dashboardTicks.value[symbol] },
        onBalanceAndAvgUpdated = { balances, avgPrices ->
            syncRealBalancesToPositionStore(balances, avgPrices)
        },
        onRealTradeExecuted = { pair, type, price, quantity, tp1, tp2 ->
            val symbol = pair.replace("_", "").uppercase()
            if (type.equals("sell", ignoreCase = true)) {
                positionStore.markSold(symbol, isReal = true)
                positionStore.markSold(pair, isReal = true)
                agu.analys.engine.sell.SellSignalLifecycleManager.reset(symbol, isReal = true)
                agu.analys.engine.sell.SellSignalLifecycleManager.reset(pair, isReal = true)
                positionCoordinator.markSoldAndClear(symbol, isReal = true)
                positionCoordinator.markSoldAndClear(pair, isReal = true)
                // Expire signal tracking logs for this coin (sudah terjual = log kadaluarsa)
                signalLogRepository.expireTrackingLogsForSymbol(symbol, "Posisi real sudah terjual (MarkSold)")
                tradeHistoryRecorder.recordSell(
                    symbol = symbol,
                    isReal = true,
                    sellPrice = price,
                    sellQuantity = quantity,
                    sellReason = if (tp1 > 0 || tp2 > 0) "TAKE_PROFIT" else "MARKET_SELL",
                    strategyMode = _strategyMode.value.name
                )
            } else if (type.equals("buy", ignoreCase = true)) {
                val snapshot = agu.analys.trading.TradeSignalSnapshot.capture(
                    symbol = symbol,
                    strategyMode = _strategyMode.value.name,
                    tick = marketDataCoordinator.dashboardTicks.value[symbol],
                    indicators = engine.indicators.value,
                    signal = engine.signalState.value
                )
                val snapshotJson = snapshot.toJson().toString()
                tradeHistoryRecorder.recordBuy(
                    symbol = symbol,
                    isReal = true,
                    strategyMode = _strategyMode.value.name,
                    buyPrice = price,
                    buyQuantity = quantity,
                    buyTotalIdr = price * quantity,
                    buyOrderType = "LIMIT",
                    snapshot = snapshot,
                    signalPrice = engine.signalState.value.entryPrice.takeIf { it > 0 } ?: price,
                    signalConfidence = engine.signalState.value.confidence,
                    targetPrice1 = tp1,
                    targetPrice2 = tp2,
                    stopLossPrice = engine.signalState.value.stopLoss
                )
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val localEntity = RealTradeEntity(
                            id = "local_${System.currentTimeMillis()}_${symbol}",
                            symbol = pair.lowercase().replace("_", ""),
                            price = price,
                            qty = quantity,
                            amount = price * quantity,
                            time = System.currentTimeMillis(),
                            side = "BUY",
                            isBuyer = true,
                            strategyMode = _strategyMode.value.name,
                            holdingDurationMs = 0L,
                            entryPrice = price,
                            entryTimestamp = System.currentTimeMillis(),
                            signalSnapshotJson = snapshotJson
                        )
                        AppDatabase.getInstance().realTradeDao().insertTrades(listOf(localEntity))
                    } catch (_: Exception) {}
                }
            }
            syncRealTradeToSimulation(pair, type, price, quantity, tp1, tp2)
            positionCoordinator.refreshPosition(_selectedPair.value.symbol)
            refreshSpotPosition()
        }
    )
    internal val updateCoordinator = AppUpdateCoordinator(viewModelScope)

    internal fun syncRealTradeToSimulation(
        pair: String,
        type: String,
        price: Double,
        quantity: Double,
        tp1: Double = 0.0,
        tp2: Double = 0.0
    ) {
        if (!prefs.isRealSimSyncEnabled) return
        val base = baseFromSymbolOrPair(pair)
        val symbol = "${base.uppercase()}IDR"
        val isBuy = type.equals("buy", ignoreCase = true)

        // 1. Mirror ke Riwayat Transaksi Simulasi dengan flag isRealMirror = true
        simCoordinator.recordMirroredRealTrade(
            symbol = symbol,
            baseAsset = base,
            quoteAsset = "IDR",
            side = if (isBuy) agu.analys.trading.SimulationOrderSide.BUY else agu.analys.trading.SimulationOrderSide.SELL,
            price = price,
            quantity = quantity
        )

        // 2. Sinkronkan ke SpotPositionStore agar engine tracking (Trailing Stop / TP / SL / Alert) aktif
        if (isBuy) {
            positionStore.markBought(
                symbol = symbol,
                entryPrice = price,
                quantity = quantity,
                isReal = true
            )
            if (tp1 > price || tp2 > price) {
                val currentPos = positionStore.get(symbol)
                positionStore.setAutoSellParams(
                    symbol = symbol,
                    enabled = true,
                    tp1Price = if (tp1 > 0.0) tp1 else currentPos.tp1Price,
                    tp1Percent = 50.0,
                    tp2Price = if (tp2 > 0.0) tp2 else currentPos.tp2Price,
                    tp2Percent = 50.0
                )
            }
        } else {
            val currentPos = positionStore.get(symbol)
            // Dust-safe: anggap full close jika sisa relatif/absolut sangat kecil
            val remainingQty = (currentPos.quantity - quantity).coerceAtLeast(0.0)
            val isDust = remainingQty <= 0.00000001 ||
                (currentPos.quantity > 0.0 && remainingQty / currentPos.quantity < 1e-6)
            if (isDust) {
                positionStore.markSold(symbol)
                agu.analys.engine.sell.SellSignalLifecycleManager.reset(symbol)
                signalLogRepository.expireTrackingLogsForSymbol(symbol, "Posisi real full close (MarkSold)")
            } else {
                positionStore.setHolding(
                    symbol = symbol,
                    invested = currentPos.entryPrice * remainingQty,
                    entry = currentPos.entryPrice,
                    quantity = remainingQty,
                    isReal = true
                )
            }
        }
        refreshSpotPosition()
    }

    internal fun syncSimulationTradeToPositionStore(order: agu.analys.trading.SimulationOrder) {
        val symbol = order.symbol
        if (order.side == agu.analys.trading.SimulationOrderSide.BUY) {
            val fillPrice = if (order.filledAvgPrice > 0.0) order.filledAvgPrice else order.limitPrice
            positionStore.markBought(
                symbol = symbol,
                entryPrice = fillPrice,
                quantity = order.quantity,
                isReal = false
            )
            val snapshot = agu.analys.trading.TradeSignalSnapshot.capture(
                symbol = symbol,
                strategyMode = _strategyMode.value.name,
                tick = marketDataCoordinator.dashboardTicks.value[symbol],
                indicators = engine.indicators.value,
                signal = engine.signalState.value
            )
            tradeHistoryRecorder.recordBuy(
                symbol = symbol,
                isReal = false,
                strategyMode = _strategyMode.value.name,
                buyPrice = fillPrice,
                buyQuantity = order.quantity,
                buyTotalIdr = fillPrice * order.quantity,
                buyOrderType = order.type.name,
                snapshot = snapshot,
                signalPrice = engine.signalState.value.entryPrice.takeIf { it > 0 } ?: fillPrice,
                signalConfidence = engine.signalState.value.confidence,
                targetPrice1 = engine.signalState.value.targetPrice1,
                targetPrice2 = engine.signalState.value.targetPrice2,
                stopLossPrice = engine.signalState.value.stopLoss
            )
        } else if (order.side == agu.analys.trading.SimulationOrderSide.SELL) {
            val currentPos = positionStore.get(symbol)
            val currentQty = currentPos.quantity
            val remainingQty = (currentQty - order.quantity).coerceAtLeast(0.0)
            val fillPrice = if (order.filledAvgPrice > 0.0) order.filledAvgPrice else order.limitPrice
            val sellReason = when (order.type) {
                agu.analys.trading.SimulationOrderType.STOP_LIMIT -> "TRAILING_STOP"
                agu.analys.trading.SimulationOrderType.MARKET -> "MARKET_SELL"
                else -> "LIMIT_SELL"
            }
            tradeHistoryRecorder.recordSell(
                symbol = symbol,
                isReal = false,
                sellPrice = fillPrice,
                sellQuantity = order.quantity,
                sellReason = sellReason,
                strategyMode = _strategyMode.value.name
            )
            // Dust-safe full close setelah market/trailing sell di simulasi
            val isDust = remainingQty <= 0.00000001 ||
                (currentQty > 0.0 && remainingQty / currentQty < 1e-6)
            if (isDust) {
                positionStore.markSold(symbol)
                agu.analys.engine.sell.SellSignalLifecycleManager.reset(symbol)
                signalLogRepository.expireTrackingLogsForSymbol(symbol, "Posisi sim full close (MarkSold)")
            } else {
                positionStore.setHolding(
                    symbol = symbol,
                    invested = currentPos.entryPrice * remainingQty,
                    entry = currentPos.entryPrice,
                    quantity = remainingQty,
                    isReal = false
                )
            }
        }
        refreshSpotPosition()
    }

    private fun baseFromSymbolOrPair(pair: String): String {
        val s = pair.lowercase().replace("_", "")
        return when {
            s.endsWith("idr") -> s.removeSuffix("idr")
            s.endsWith("usdt") -> s.removeSuffix("usdt")
            else -> s
        }
    }

    internal fun syncRealBalancesToPositionStore(
        balances: Map<String, Double> = realCoordinator.realIndodaxBalance.value,
        avgPrices: Map<String, Double> = realCoordinator.realAvgBuyPrices.value
    ) {
        if (!prefs.hasIndodaxCredentials() || !prefs.isRealSimSyncEnabled) return
        val popularAndCustom = (TradingPair.POPULAR_INDODAX_PAIRS.map { it.baseAsset.uppercase() } + balances.keys.map { it.uppercase() }).distinct()
        
        for (baseUpper in popularAndCustom) {
            if (baseUpper == "IDR" || baseUpper == "USDT") continue
            val baseLower = baseUpper.lowercase()
            val symbol = "${baseUpper}IDR"
            val qty = balances[baseLower] ?: balances[baseUpper] ?: 0.0
            val pos = positionStore.get(symbol)
            
            val avgPrice = avgPrices[symbol]
                ?: avgPrices[baseUpper]
                ?: avgPrices[baseLower]
                ?: avgPrices["${baseLower}idr"]
                ?: 0.0
            
            if (qty > 0.00000001) {
                if (!pos.isHolding) {
                    // Terdeteksi ada saldo real baru dari luar app -> auto-sync markBought
                    positionStore.markBought(
                        symbol = symbol,
                        entryPrice = avgPrice,
                        quantity = qty,
                        isReal = true
                    )
                } else if (pos.isReal) {
                    // Update kuantitas dan harga rata-rata jika belum disetel manual
                    val finalEntry = if (pos.entryPrice > 0.0) pos.entryPrice else avgPrice
                    positionStore.setHolding(
                        symbol = symbol,
                        invested = finalEntry * qty,
                        entry = finalEntry,
                        quantity = qty,
                        isReal = true
                    )
                }
            } else {
                // Saldo real sudah 0 di Indodax → tutup posisi + clear sell signal lifecycle
                // agar koin hilang dari Ready-to-Sell / Trailing badge
                if (pos.isHolding && pos.isReal) {
                    positionStore.markSold(symbol, isReal = true)
                    agu.analys.engine.sell.SellSignalLifecycleManager.reset(symbol, isReal = true)
                    positionCoordinator.markSoldAndClear(symbol, isReal = true)
                    signalLogRepository.expireTrackingLogsForSymbol(symbol, "Saldo real 0 di Indodax (MarkSold)")
                }
            }
        }
        // Bump positionVersion agar holdingStatuses & ProactiveProfit / ReadySell list recompose
        positionCoordinator.refreshPosition(_selectedPair.value.symbol)
        refreshSpotPosition()
    }
    
    internal val positionCoordinator: PositionCoordinator = PositionCoordinator(
        positionStore = positionStore,
        alertStore = alertStore,
        isRealProvider = { realCoordinator.isRealBuyEnabled.value },
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

    internal val signalLogRepository: SignalLogRepository = SignalLogRepository(
        dao = AppDatabase.getInstance().signalLogDao(),
        scope = viewModelScope
    )

    val tradeHistoryRecorder: TradeHistoryRecorder = TradeHistoryRecorder(
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
            this@TradingViewModel.checkAlertsAndTrailing(symbol, price, rsi)
            this@TradingViewModel.signalLogRepository.processPriceTick(symbol, price)
            this@TradingViewModel.tradeHistoryRecorder.processPriceTick(symbol, price)
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

    val allSignalLogs: StateFlow<List<SignalLogEntity>> = signalLogRepository.allLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tradeHistoryRecords: StateFlow<List<TradeHistoryRecordEntity>> = tradeHistoryRecorder.allRecordsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signalReliabilitySummary: StateFlow<SignalReliabilitySummary> = signalLogRepository.reliabilitySummaryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SignalReliabilitySummary())

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
    val themeStyle: StateFlow<agu.analys.ui.theme.ThemeStyle> = _themeStyle.asStateFlow()

    internal val _accentColorPreset = MutableStateFlow(prefs.accentColorPreset)
    val accentColorPreset: StateFlow<agu.analys.ui.theme.AccentColorPreset> = _accentColorPreset.asStateFlow()

    internal val _candleColorStyle = MutableStateFlow(prefs.candleColorStyle)
    val candleColorStyle: StateFlow<agu.analys.ui.theme.CandleColorStyle> = _candleColorStyle.asStateFlow()

    internal val _animationSpeed = MutableStateFlow(prefs.animationSpeed)
    val animationSpeed: StateFlow<agu.analys.ui.theme.AnimationSpeed> = _animationSpeed.asStateFlow()

    internal val _priceAnimationMode = MutableStateFlow(prefs.priceAnimationMode)
    val priceAnimationMode: StateFlow<agu.analys.ui.animation.PriceAnimationMode> = _priceAnimationMode.asStateFlow()

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

    internal val _isNotifyEmergencyExitEnabled = MutableStateFlow(prefs.isNotifyEmergencyExitEnabled)
    val isNotifyEmergencyExitEnabled: StateFlow<Boolean> = _isNotifyEmergencyExitEnabled.asStateFlow()

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
    
    val holdingStatuses: StateFlow<Map<String, agu.analys.model.CoinHoldingStatus>> = kotlinx.coroutines.flow.combine(
        simCoordinator.wallet,
        realIndodaxBalance,
        realAvgBuyPrices,
        realCoordinator.isRealBuyEnabled,
        positionCoordinator.positionVersion
    ) { wallet, realBal, _, isRealMode, _ ->
        val defaultQuote = prefs.marketDataSource.defaultQuoteAsset
        val basePairs = agu.analys.model.TradingPair.popularPairsForSource(prefs.marketDataSource)
        val watchPairs = _watchlist.value.map { agu.analys.model.TradingPair.fromCustomSymbol(it, defaultQuote) }
        val favPairs = _favorites.value.map { agu.analys.model.TradingPair.fromCustomSymbol(it, defaultQuote) }
        val simPairs = if (!isRealMode) {
            wallet.coinBalances.filter { it.value > 0.00000001 && !it.key.equals("IDR", true) && !it.key.equals("USDT", true) }
                .map { agu.analys.model.TradingPair.fromCustomSymbol(it.key, defaultQuote) }
        } else emptyList()
        val realPairs = if (isRealMode) {
            realBal.filter { it.value > 0.00000001 && !it.key.equals("IDR", true) && !it.key.equals("USDT", true) }
                .map { agu.analys.model.TradingPair.fromCustomSymbol(it.key, defaultQuote) }
        } else emptyList()
        val pairs = (basePairs + watchPairs + favPairs + simPairs + realPairs).distinctBy { it.symbol }
        
        pairs.associate { pair ->
            pair.symbol to getHoldingStatus(pair, isRealMode)
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyMap())

    val positionContext: StateFlow<PositionContext> = kotlinx.coroutines.flow.combine(
        _selectedPair,
        spotPosition,
        currentTick,
        tradingFees,
        holdingStatuses
    ) { pair, spotPos, tick, fees, statuses ->
        val holding = statuses[pair.symbol] ?: getHoldingStatus(pair)
        val price = tick?.price ?: 0.0
        PositionContext.create(
            symbol = pair.symbol,
            spotPosition = spotPos,
            holdingStatus = holding,
            currentPrice = price,
            fees = fees
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PositionContext())

    val tradingWorkflow: StateFlow<TradingWorkflow> = positionContext
        .map { resolveWorkflow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TradingWorkflow.BUY)

    val sellSignalState: StateFlow<agu.analys.model.SellSignalState> = kotlinx.coroutines.flow.combine(
        positionContext,
        currentIndicators
    ) { posContext, indicators ->
        val snapshot = agu.analys.engine.sell.TickHistoryTracker.getSnapshot(
            symbol = posContext.symbol,
            currentPrice = posContext.currentPrice ?: 0.0,
            peakPrice = posContext.peakPrice
        )
        agu.analys.engine.sell.SellSignalEvaluator.evaluate(
            context = posContext,
            indicators = indicators,
            tradingFees = tradingFees.value,
            riskSnapshot = snapshot
        )
    }
    .onEach { state ->
        val symbol = _selectedPair.value.symbol
        val isReal = positionContext.value.isReal
        val transition = agu.analys.engine.sell.SellSignalLifecycleManager.process(symbol, state, isReal = isReal)
        if (transition.hasTriggeringTransition && isNotificationsEnabled.value) {
            agu.analys.util.AlertNotificationHelper.sendPriceAlertNotification(
                context = getApplication(),
                notificationId = symbol.hashCode() + 1000,
                title = "Sinyal Jual: $symbol",
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
        viewModelScope.launch {
            realCoordinator.isRealBuyEnabled.collect {
                refreshSpotPosition()
            }
        }
        agu.analys.util.MtfCacheManager.updateQueues(_watchlist.value.toList(), emptyList())
        engine.strategyMode = prefs.strategyMode
        engine.isScalpingMode = prefs.isScalpingMode
        engine.scalpingSensitivity = prefs.scalpingSensitivity
        engine.tradingFees = prefs.tradingFees

        engine.onCandidateSignalTransition = { transition ->
            if (isNotificationsEnabled.value) {
                val position = positionStore.get(transition.symbol)
                if (!position.isHolding) {
                    agu.analys.util.AlertNotificationHelper.sendCandidateFoundNotification(
                        context = getApplication(),
                        symbol = transition.symbol,
                        strategyMode = transition.mode,
                        signal = transition.signal
                    )
                }
            }
            if (transition.signal.action != SignalAction.HOLD && transition.signal.entryPrice > 0.0) {
                signalLogRepository.recordSignal(
                    symbol = transition.symbol,
                    action = transition.signal.action.name,
                    strategyMode = transition.mode.name,
                    confidence = transition.signal.confidence,
                    sentiment = transition.signal.sentiment.name,
                    entryPrice = transition.signal.entryPrice,
                    targetPrice1 = transition.signal.targetPrice1,
                    targetPrice2 = transition.signal.targetPrice2,
                    stopLoss = transition.signal.stopLoss,
                    reasoning = transition.signal.reasoning.joinToString(" • "),
                    scalpingStage = transition.signal.scalpingStage.name
                )
            }
        }

                // Background fetch API pairs metadata
        viewModelScope.launch {
            val meta = agu.analys.service.IndodaxMarketService.fetchPairsMetadata()
            if (meta.isNotEmpty()) {
                marketCache.savePairsMetadata(meta)
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
            tradeHistoryRecorder.seedSampleTradeJourneysIfEmpty()
        }

        viewModelScope.launch {
            simCoordinator.lastFilledOrder.collect { filledOrder ->
                if (filledOrder != null && filledOrder.status == agu.analys.trading.SimulationOrderStatus.FILLED) {
                    if (filledOrder.side == SimulationOrderSide.SELL) {
                        // Full close path (termasuk trailing profit-lock): clear position + sell lifecycle
                        positionCoordinator.markSoldAndClear(filledOrder.symbol)
                        positionCoordinator.setTrailing(filledOrder.symbol, enabled = false, 0.0, 0.0)
                        signalLogRepository.expireTrackingLogsForSymbol(filledOrder.symbol, "Simulasi sell filled (MarkSold)")
                        checkAndStopTrailingServiceIfEmpty()
                    }
                }
            }
        }
    }

    private fun markMarketOffline(reason: String) {
        _connectionState.value = MarketConnectionState.ConnectionLost(reason = reason)
        _isShowingCachedData.value = true
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

    fun updateForegroundServiceState() {
        val hasActive = positionStore.getAllActiveTrailingSymbols().isNotEmpty() ||
                        positionStore.hasAnyHolding() ||
                        simulationStore.getWallet().coinBalances.any { it.value > 0.00000001 && it.key.uppercase() != "IDR" } ||
                        (prefs.hasIndodaxCredentials() && prefs.getSavedRealBalance().any { it.value > 0.00000001 && it.key.uppercase() != "IDR" })

        if (hasActive && isNotificationsEnabled.value) {
            agu.analys.service.TradingForegroundService.startService(getApplication())
        } else if (!hasActive) {
            agu.analys.service.TradingForegroundService.stopService(getApplication())
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.isNotificationsEnabled = enabled
        _isNotificationsEnabled.value = enabled
        updateForegroundServiceState()
    }

    fun setRealSimSyncEnabled(enabled: Boolean) {
        prefs.isRealSimSyncEnabled = enabled
        _isRealSimSyncEnabled.value = enabled
        refreshSpotPosition()
        simCoordinator.refresh()
        updateForegroundServiceState()
    }

    fun selectCustomSymbol(rawSymbol: String) {
        if (rawSymbol.isNotBlank()) selectPair(TradingPair.fromCustomSymbol(rawSymbol, "IDR"))
    }

    fun selectAndWatch(rawSymbol: String, addToWatchlist: Boolean = true) {
        if (rawSymbol.isBlank()) return
        val pair = TradingPair.fromCustomSymbol(rawSymbol, "IDR")
        selectPair(pair)
        if (addToWatchlist && !prefs.isInWatchlist(pair.symbol)) toggleWatchlist(pair.symbol)
    }

    private fun startDashboardPolling() {
        dashboardPollJob?.cancel()
        dashboardPollJob = viewModelScope.launch {
            while (isActive) {
                refreshWorthCoinsFromMarket()
                delay(30_000L)        // dari 15s → 30s
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
                        val pairs = activeSymbols.map { 
                            TradingPair.fromCustomSymbol(it, "IDR").effectiveIndodaxPair() 
                        }
                        val ticks = IndodaxMarketService.fetchTickers(pairs)
                        for (tick in ticks) {
                            simCoordinator.onPriceTick(tick.symbol, tick.price, tick.high24h, tick.low24h)
                            checkAlertsAndTrailing(tick.symbol, tick.price)
                            signalLogRepository.processPriceTick(tick.symbol, tick.price)
                            tradeHistoryRecorder.processPriceTick(tick.symbol, tick.price)
                        }
                        delay(10_000L)          // dari 4 detik → 10 detik
                    } else {
                        checkAndStopTrailingServiceIfEmpty()
                        delay(20_000L)          // idle lebih lama
                    }
                } catch (_: Exception) {
                    delay(12_000L)
                }
            }
        }
    }

    internal fun checkAndStopTrailingServiceIfEmpty() {
        updateForegroundServiceState()
        if (positionStore.getAllActiveTrailingSymbols().isEmpty()) {
            trailingPollJob?.cancel()
            trailingPollJob = null
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
                    val btcTick = combinedTicks["BTCIDR"] ?: combinedTicks["btc_idr"] ?: combinedTicks["BTC"]
                    val usdtTick = combinedTicks["USDTIDR"] ?: combinedTicks["usdt_idr"] ?: combinedTicks["USDT"]
                    if (btcTick != null && btcTick.price > 0) {
                        agu.analys.engine.global.GlobalContextManager.updateFallbackFromIndodax(
                            priceIdr = btcTick.price,
                            changePct = btcTick.change24h,
                            usdtRate = usdtTick?.price ?: 16200.0
                        )
                    }
                } catch (_: Exception) {}
                try {
                    val priceMap = combinedTicks.mapValues { it.value.price }
                    agu.analys.service.TradingForegroundService.updatePrices(getApplication(), priceMap)
                    signalLogRepository.processBatchPriceTicks(priceMap)
                    tradeHistoryRecorder.processBatchPriceTicks(priceMap)
                } catch (_: Exception) {}
                lastLiveTickAt = System.currentTimeMillis()
                _connectionState.value = MarketConnectionState.Connected
                _isShowingCachedData.value = false
                marketCache.saveDashboardTicks(MarketDataSource.INDODAX, combinedTicks)

                val secondWaveCandidates = combinedTicks.values
                    .filter { t ->
                        t.price > 0 && t.high24h > 0 && t.volume24h >= 1_000_000_000 &&
                            IndodaxMarketService.isSafeTradableAsset(t.price, t.volume24h, t.high24h, t.low24h, isIdrPair = true)
                    }
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
                    val isUserExplicit = isFavorite(pair.symbol) || _watchlist.value.contains(pair.symbol)
                    if (!IndodaxMarketService.isSafeTradableAsset(
                        price = tick.price,
                        volume24h = tick.volume24h,
                        high24h = tick.high24h,
                        low24h = tick.low24h,
                        isIdrPair = pair.quoteAsset.equals("IDR", ignoreCase = true),
                        isExplicitlyFavored = isUserExplicit
                    )) {
                        return@mapNotNull null
                    }
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

                    // Persist to Room Database SignalLogEntity
                    signalLogRepository.recordSignal(
                        symbol = _selectedPair.value.symbol,
                        action = signal.action.name,
                        strategyMode = _strategyMode.value.name,
                        confidence = signal.confidence,
                        sentiment = signal.sentiment.name,
                        entryPrice = if (signal.entryPrice > 0) signal.entryPrice else (currentTick.value?.price ?: 0.0),
                        targetPrice1 = signal.targetPrice1,
                        targetPrice2 = signal.targetPrice2,
                        stopLoss = signal.stopLoss,
                        reasoning = signal.reasoning.joinToString(" • "),
                        scalpingStage = signal.scalpingStage.name
                    )
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
        if (!loaded) marketDataCoordinator.clearPairData(pair.symbol)
        marketDataCoordinator.startMarketPolling(pair, _selectedTimeframe.value)
        agu.analys.engine.global.GlobalContextManager.subscribeCoin(pair.baseAsset)
    }

    fun toggleSimpleChart() { _useSimpleChart.value = !_useSimpleChart.value }
    fun selectTimeframe(tf: Timeframe) {
        if (_selectedTimeframe.value == tf) return
        _selectedTimeframe.value = tf
        selectPair(_selectedPair.value)
    }
    fun selectChartStyle(style: ChartStyle) { _selectedChartStyle.value = style }
    fun toggleChartExpanded() { _isChartExpanded.value = !_isChartExpanded.value }

    fun setUiPriceThrottleMs(ms: Long) {
        marketDataCoordinator.setPriceFeedThrottleMs(ms)
    }

    fun retryConnection() {
        marketDataCoordinator.startMarketPolling(_selectedPair.value, _selectedTimeframe.value)
        refreshWorthCoinsFromMarket()
        agu.analys.util.MtfCacheManager.setActiveSymbol(_selectedPair.value.symbol)
    }

    fun simulateDisconnect() {
        marketDataCoordinator.markOffline("Mode offline: koneksi dihentikan manual.")
    }

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

    fun getSignalLogsForSymbol(symbol: String): kotlinx.coroutines.flow.Flow<List<SignalLogEntity>> =
        signalLogRepository.getLogsBySymbolFlow(symbol)

    fun seedSampleSignalLogs() {
        viewModelScope.launch {
            signalLogRepository.seedSampleLogsIfEmpty()
        }
    }

    fun deleteSignalLog(id: Long) {
        viewModelScope.launch {
            signalLogRepository.deleteLog(id)
        }
    }

    fun clearAllSignalLogs() {
        viewModelScope.launch {
            signalLogRepository.clearAllLogs()
        }
    }

    fun resolveSignalLogManually(id: Long, isWin: Boolean, exitPrice: Double? = null, pnlPct: Double? = null, note: String = "") {
        viewModelScope.launch {
            signalLogRepository.resolveLogManually(id, isWin, exitPrice, pnlPct, note)
        }
    }

    /** Tombol Refresh di halaman Log & Akurasi Sinyal: konsolidasi duplicate + pastikan state sync */
    fun refreshSignalLogs() {
        signalLogRepository.refreshAndConsolidate()
    }

    fun seedSampleTradeJourneys() {
        viewModelScope.launch {
            tradeHistoryRecorder.seedSampleTradeJourneysIfEmpty()
        }
    }

    fun deleteTradeHistoryRecord(id: Long) {
        viewModelScope.launch {
            tradeHistoryRecorder.deleteRecord(id)
        }
    }

    fun clearAllTradeHistoryRecords() {
        viewModelScope.launch {
            tradeHistoryRecorder.clearAllRecords()
        }
    }
}
