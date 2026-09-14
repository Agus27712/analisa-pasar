package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.engine.secondwave.SecondWaveEvaluator
import agu.analys.model.CandleBar
import agu.analys.model.MarketConnectionState
import agu.analys.model.MarketTick
import agu.analys.model.Timeframe
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.service.IndodaxMarketService
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Extension for TradingViewModel dealing with Market polling, rankings, worth coin screening,
 * second wave analysis, and badge calculation.
 */

fun TradingViewModel.markMarketOffline(reason: String) {
    _connectionState.value = MarketConnectionState.ConnectionLost(reason = reason)
    _isShowingCachedData.value = true
}

fun TradingViewModel.startDashboardPolling() {
    dashboardPollJob?.cancel()
    dashboardPollJob = viewModelScope.launch {
        while (isActive) {
            refreshWorthCoinsFromMarket()
            delay(30_000L)
        }
    }
}

fun TradingViewModel.startTrailingPolling() {
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
                    }
                    delay(10_000L)
                } else {
                    checkAndStopTrailingServiceIfEmpty()
                    delay(20_000L)
                }
            } catch (_: Exception) {
                delay(12_000L)
            }
        }
    }
}

fun TradingViewModel.checkAndStopTrailingServiceIfEmpty() {
    updateForegroundServiceState()
    if (positionStore.getAllActiveTrailingSymbols().isEmpty()) {
        trailingPollJob?.cancel()
        trailingPollJob = null
    }
}

fun TradingViewModel.refreshWorthCoinsFromMarket() {
    viewModelScope.launch {
        _isRefreshing.value = true
        try {
            val scalpingMode = _isScalpingMode.value
            val rankingsJob = async { IndodaxMarketService.fetchMarketRankings(35, true) }
            val pairs = (TradingPair.POPULAR_INDODAX_PAIRS + _watchlist.value.map {
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
            } catch (_: Exception) {}
            try {
                val priceMap = combinedTicks.mapValues { it.value.price }
                agu.analys.service.TradingForegroundService.updatePrices(getApplication(), priceMap)
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

fun TradingViewModel.getH1Candles(symbol: String): List<CandleBar> {
    if (isMatchingSymbol(symbol, _selectedPair.value.symbol) && _selectedTimeframe.value == Timeframe.H1) {
        val liveCandles = recentCandles.value
        if (liveCandles.isNotEmpty()) return liveCandles
    }
    val mtfCandles = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.H1)
    if (!mtfCandles.isNullOrEmpty()) return mtfCandles

    val (_, cached) = marketCache.loadPairSnapshot(symbol, Timeframe.H1)
    if (cached.isNotEmpty()) return cached

    return emptyList()
}

fun TradingViewModel.ensureH1Candles(symbol: String) {
    val current = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.H1)
    if (current.isNullOrEmpty()) {
        agu.analys.util.MtfCacheManager.retryTimeframe(symbol, Timeframe.H1)
    }
}

fun TradingViewModel.recalculateDashboardBadges() {
    viewModelScope.launch(Dispatchers.Default) {
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
