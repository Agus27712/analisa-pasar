package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.model.CandleBar
import agu.analys.model.ChartStyle
import agu.analys.model.CoinBadge
import agu.analys.model.MarketConnectionState
import agu.analys.model.MarketTick
import agu.analys.model.Timeframe
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.service.IndodaxMarketService
import agu.analys.util.AppPreferences
import agu.analys.util.MarketDataCache
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.Dispatchers
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

class MarketViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val marketCache = MarketDataCache(application)

    private val _selectedPair = MutableStateFlow(TradingPair.popularPairsForSource(prefs.marketDataSource).first())
    val selectedPair: StateFlow<TradingPair> = _selectedPair.asStateFlow()

    private val _selectedTimeframe = MutableStateFlow(Timeframe.H4)
    val selectedTimeframe: StateFlow<Timeframe> = _selectedTimeframe.asStateFlow()

    private val _connectionState = MutableStateFlow<MarketConnectionState>(MarketConnectionState.ConnectionLost())
    val connectionState: StateFlow<MarketConnectionState> = _connectionState.asStateFlow()

    private val _isShowingCachedData = MutableStateFlow(false)
    val isShowingCachedData: StateFlow<Boolean> = _isShowingCachedData.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _dashboardTicks = MutableStateFlow<Map<String, MarketTick>>(emptyMap())
    val dashboardTicks: StateFlow<Map<String, MarketTick>> = _dashboardTicks.asStateFlow()

    private val _worthCoins = MutableStateFlow<List<WorthCoinInfo>>(emptyList())
    val worthCoins: StateFlow<List<WorthCoinInfo>> = _worthCoins.asStateFlow()

    private val _hotCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val hotCoins: StateFlow<List<MarketTick>> = _hotCoins.asStateFlow()

    private val _gainersCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val gainersCoins: StateFlow<List<MarketTick>> = _gainersCoins.asStateFlow()

    private val _losersCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val losersCoins: StateFlow<List<MarketTick>> = _losersCoins.asStateFlow()

    private val _topVolumeCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val topVolumeCoins: StateFlow<List<MarketTick>> = _topVolumeCoins.asStateFlow()

    private val _coinBadges = MutableStateFlow<Map<String, List<CoinBadge>>>(emptyMap())
    val coinBadges: StateFlow<Map<String, List<CoinBadge>>> = _coinBadges.asStateFlow()

    private val _usdtIdrRate = MutableStateFlow(16450.0)
    val usdtIdrRate: StateFlow<Double> = _usdtIdrRate.asStateFlow()

    private val _useSimpleChart = MutableStateFlow(false)
    val useSimpleChart: StateFlow<Boolean> = _useSimpleChart.asStateFlow()

    private val _selectedChartStyle = MutableStateFlow(ChartStyle.CANDLES)
    val selectedChartStyle: StateFlow<ChartStyle> = _selectedChartStyle.asStateFlow()

    private val _isChartExpanded = MutableStateFlow(false)
    val isChartExpanded: StateFlow<Boolean> = _isChartExpanded.asStateFlow()

    private val _uiPriceThrottleMs = MutableStateFlow(prefs.priceFeedThrottleMs)
    val uiPriceThrottleMs: StateFlow<Long> = _uiPriceThrottleMs.asStateFlow()

    private var dashboardPollJob: Job? = null
    private var lastLiveTickAt = 0L

    fun selectPair(pair: TradingPair) {
        _selectedPair.value = pair
    }

    fun selectTimeframe(timeframe: Timeframe) {
        _selectedTimeframe.value = timeframe
    }

    fun selectCustomSymbol(rawSymbol: String): TradingPair? {
        val trimmed = rawSymbol.trim()
        if (trimmed.isNotBlank()) {
            val pair = TradingPair.fromCustomSymbol(trimmed, "IDR")
            selectPair(pair)
            return pair
        }
        return null
    }

    fun toggleSimpleChart() {
        _useSimpleChart.value = !_useSimpleChart.value
    }

    fun selectChartStyle(style: ChartStyle) {
        _selectedChartStyle.value = style
    }

    fun toggleChartExpanded() {
        _isChartExpanded.value = !_isChartExpanded.value
    }

    fun setUiPriceThrottleMs(ms: Long) {
        val coerced = ms.coerceAtLeast(0L)
        prefs.priceFeedThrottleMs = coerced
        _uiPriceThrottleMs.value = coerced
    }

    fun updateDashboardTicks(ticks: Map<String, MarketTick>) {
        _dashboardTicks.value = _dashboardTicks.value + ticks
    }

    fun getH1Candles(symbol: String): List<CandleBar> {
        val (_, candles) = marketCache.loadPairSnapshot(symbol, Timeframe.H1)
        return candles
    }

    fun ensureH1Candles(symbol: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val (_, cached) = marketCache.loadPairSnapshot(symbol, Timeframe.H1)
            if (cached.isEmpty()) {
                try {
                    val pairObj = TradingPair.fromCustomSymbol(symbol, "IDR")
                    val candles = IndodaxMarketService.fetchCandles(pairObj.effectiveIndodaxPair(), Timeframe.H1, 100)
                    if (candles.isNotEmpty()) {
                        marketCache.savePairSnapshot(symbol, Timeframe.H1, null, candles)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun startDashboardPolling(watchlistSymbols: Set<String> = emptySet(), favoritesSymbols: Set<String> = emptySet(), activeStrategy: StrategyMode = StrategyMode.SCALPING) {
        dashboardPollJob?.cancel()
        dashboardPollJob = viewModelScope.launch {
            while (isActive) {
                refreshWorthCoinsFromMarket(watchlistSymbols, favoritesSymbols, activeStrategy)
                delay(30_000L)
            }
        }
    }

    fun stopDashboardPolling() {
        dashboardPollJob?.cancel()
        dashboardPollJob = null
    }

    fun markMarketOffline(reason: String) {
        _connectionState.value = MarketConnectionState.ConnectionLost(reason = reason)
        _isShowingCachedData.value = true
    }

    fun refreshWorthCoinsFromMarket(watchlistSymbols: Set<String> = emptySet(), favoritesSymbols: Set<String> = emptySet(), activeStrategy: StrategyMode = StrategyMode.SCALPING) {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val scalpingMode = activeStrategy == StrategyMode.SCALPING
                val rankingsJob = async { IndodaxMarketService.fetchMarketRankings(35, true) }
                val pairs = (TradingPair.POPULAR_INDODAX_PAIRS + watchlistSymbols.map {
                    TradingPair.fromCustomSymbol(it, "IDR")
                }).distinctBy { it.symbol }
                val ticksJob = async { IndodaxMarketService.fetchTickers(pairs.map { it.effectiveIndodaxPair() }) }

                val rankings = rankingsJob.await()
                val ticks = ticksJob.await()

                val gainers = rankings.gainers
                val losers = rankings.losers
                val topVol = rankings.topVolume

                if (gainers.isNotEmpty()) {
                    _gainersCoins.value = gainers
                    _hotCoins.value = gainers
                }
                if (losers.isNotEmpty()) {
                    _losersCoins.value = losers
                }
                if (topVol.isNotEmpty()) {
                    _topVolumeCoins.value = topVol
                }

                if (ticks.isEmpty() && rankings.allTicks.isEmpty()) {
                    if (_dashboardTicks.value.isEmpty() && _hotCoins.value.isEmpty()) {
                        markMarketOffline("Tidak ada respons market dari Indodax.")
                    } else {
                        _isShowingCachedData.value = true
                    }
                    return@launch
                }

                val allScanned = (gainers + losers + topVol).distinctBy { it.symbol }
                val combinedTicks = ticks.associateBy { it.symbol } + rankings.allTicks
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
                    if (usdtTick != null && usdtTick.price > 0) {
                        _usdtIdrRate.value = usdtTick.price
                    }
                } catch (_: Exception) {}

                try {
                    val priceMap = combinedTicks.mapValues { it.value.price }
                    agu.analys.service.TradingForegroundService.updatePrices(getApplication(), priceMap)
                } catch (_: Exception) {}

                lastLiveTickAt = System.currentTimeMillis()
                _connectionState.value = MarketConnectionState.Connected
                _isShowingCachedData.value = false
                marketCache.saveDashboardTicks(MarketDataSource.INDODAX, combinedTicks)

                val evaluatedPairs = (allScanned.map { TradingPair.fromCustomSymbol(it.symbol, "IDR") } + pairs).distinctBy { it.symbol }
                val worth = evaluatedPairs.mapNotNull { pair ->
                    val tick = combinedTicks[pair.symbol] ?: return@mapNotNull null
                    val isUserExplicit = favoritesSymbols.contains(pair.symbol) || watchlistSymbols.contains(pair.symbol)
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
                recalculateDashboardBadges(watchlistSymbols, favoritesSymbols, activeStrategy)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun recalculateDashboardBadges(watchlistSymbols: Set<String> = emptySet(), favoritesSymbols: Set<String> = emptySet(), activeStrategy: StrategyMode = StrategyMode.SCALPING) {
        viewModelScope.launch(Dispatchers.Default) {
            val defaultQuote = prefs.marketDataSource.defaultQuoteAsset
            val basePairs = TradingPair.popularPairsForSource(prefs.marketDataSource)
            val watchPairs = watchlistSymbols.map { TradingPair.fromCustomSymbol(it, defaultQuote) }
            val favPairs = favoritesSymbols.map { TradingPair.fromCustomSymbol(it, defaultQuote) }
            val marketPairs = (_gainersCoins.value + _hotCoins.value + _topVolumeCoins.value)
                .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
            val allPairs = (marketPairs + basePairs + watchPairs + favPairs).distinctBy { it.symbol }.take(40)
            val ticks = _dashboardTicks.value

            val resultMap = mutableMapOf<String, List<CoinBadge>>()
            for (pair in allPairs) {
                val tick = ticks[pair.symbol]
                if (tick != null) {
                    val badges = agu.analys.engine.badge.CoinBadgeEvaluator.evaluateBadges(
                        pair = pair,
                        tick = tick,
                        activeStrategy = activeStrategy,
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
