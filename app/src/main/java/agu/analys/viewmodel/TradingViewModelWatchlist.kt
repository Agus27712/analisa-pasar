package agu.analys.viewmodel

import agu.analys.config.StrategyMode
import agu.analys.model.AISignalState
import agu.analys.trading.SpotPosition
import agu.analys.model.TradingPair

/**
 * Extension for TradingViewModel dealing with watchlist, favorites, pair selection,
 * and engine signal queries for symbols.
 */

fun TradingViewModel.addToWatchlist(symbol: String) {
    val upper = symbol.uppercase().trim().replace("/", "").replace("_", "")
    if (upper.isBlank()) return
    val current = _watchlist.value.toMutableSet()
    current.add(upper)
    prefs.setWatchlist(current)
    _watchlist.value = current
    agu.analys.util.MtfCacheManager.updateQueues(current.toList(), emptyList())
    recalculateDashboardBadges()
}

fun TradingViewModel.removeFromWatchlist(symbol: String) {
    val upper = symbol.uppercase().trim().replace("/", "").replace("_", "")
    val current = _watchlist.value.toMutableSet()
    current.remove(upper)
    val finalSet = if (current.isEmpty()) setOf("BTCIDR") else current
    prefs.setWatchlist(finalSet)
    _watchlist.value = finalSet
    agu.analys.util.MtfCacheManager.updateQueues(finalSet.toList(), emptyList())
    recalculateDashboardBadges()
}

fun TradingViewModel.toggleWatchlist(symbol: String) {
    prefs.toggleWatchlist(symbol)
    val newList = prefs.getWatchlist()
    _watchlist.value = newList
    agu.analys.util.MtfCacheManager.updateQueues(newList.toList(), emptyList())
    recalculateDashboardBadges()
}

fun TradingViewModel.isWatched(symbol: String): Boolean = prefs.isInWatchlist(symbol)

fun TradingViewModel.setCustomWatchlist(symbols: Collection<String>) {
    val upper = symbols.map { it.uppercase().trim().replace("/", "").replace("_", "") }.filter { it.isNotBlank() }.toSet()
    val finalSet = if (upper.isEmpty()) setOf("BTCIDR") else upper
    prefs.setWatchlist(finalSet)
    _watchlist.value = finalSet
    agu.analys.util.MtfCacheManager.updateQueues(finalSet.toList(), emptyList())
    recalculateDashboardBadges()
}

fun TradingViewModel.applyWatchlistPreset(presetType: String) {
    val pairs = when (presetType.lowercase()) {
        "top10", "top_10" -> listOf("BTCIDR", "ETHIDR", "SOLIDR", "BNBIDR", "XRPIDR", "ADAIDR", "DOGEIDR", "AVAXIDR", "SUIIDR", "NEARIDR")
        "scalp", "scalping", "gems" -> listOf("PEPEIDR", "DOGEIDR", "SHIBIDR", "SUIIDR", "SOLIDR", "FLOKIIDR", "BONKIDR")
        "ai", "web3" -> listOf("NEARIDR", "RENDERIDR", "FETIDR", "GRTIDR", "ICPIDR", "FILIDR")
        "layer1", "l1" -> listOf("BTCIDR", "ETHIDR", "SOLIDR", "ADAIDR", "AVAXIDR", "DOTIDR", "SUIIDR", "ATOMIDR")
        else -> listOf("BTCIDR", "ETHIDR", "SOLIDR", "DOGEIDR")
    }
    setCustomWatchlist(pairs)
}

fun TradingViewModel.toggleFavorite(symbol: String) {
    prefs.toggleFavorite(symbol)
    _favorites.value = prefs.getFavorites()
    recalculateDashboardBadges()
}

fun TradingViewModel.isFavorite(symbol: String): Boolean =
    _favorites.value.contains(symbol.uppercase().trim().replace("/", "").replace("_", ""))

fun TradingViewModel.selectCustomSymbol(rawSymbol: String) {
    if (rawSymbol.isNotBlank()) selectPair(TradingPair.fromCustomSymbol(rawSymbol, "IDR"))
}

fun TradingViewModel.selectAndWatch(rawSymbol: String, addToWatchlist: Boolean = true) {
    if (rawSymbol.isBlank()) return
    val pair = TradingPair.fromCustomSymbol(rawSymbol, "IDR")
    selectPair(pair)
    if (addToWatchlist && !prefs.isInWatchlist(pair.symbol)) toggleWatchlist(pair.symbol)
}

fun TradingViewModel.selectPair(pair: TradingPair) {
    _selectedPair.value = pair
    lastSavedSignalTimestamp = 0L
    positionCoordinator.setSelectedSymbol(pair.symbol)
    
    if (_strategyMode.value == StrategyMode.SCALPING || _strategyMode.value == StrategyMode.SECOND_WAVE) {
        agu.analys.util.MtfCacheManager.setActiveSymbol(pair.symbol)
    }
    
    val loaded = marketDataCoordinator.loadPairCache(pair.symbol, _selectedTimeframe.value)
    if (!loaded) marketDataCoordinator.clearPairData(pair.symbol)
    marketDataCoordinator.startMarketPolling(pair, _selectedTimeframe.value)
    agu.analys.engine.global.GlobalContextManager.subscribeCoin(pair.baseAsset)
}

fun TradingViewModel.getPositionFor(symbol: String, isReal: Boolean = isRealBuyMode.value): SpotPosition = positionCoordinator.getPosition(symbol, isReal)

fun TradingViewModel.isMatchingSymbol(s1: String, s2: String): Boolean = positionCoordinator.isSameSymbol(s1, s2)

fun TradingViewModel.getEngineSignal(symbol: String): AISignalState? {
    val activeSignal = aiSignalState.value
    if (isMatchingSymbol(symbol, activeSignal.marketSymbol) || isMatchingSymbol(symbol, _selectedPair.value.symbol)) {
        return activeSignal
    }
    return agu.analys.engine.scalping.SignalLifecycleManager.getSignal(symbol, _strategyMode.value)
}
