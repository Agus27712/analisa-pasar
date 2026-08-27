package agu.analys.viewmodel

fun TradingViewModel.getGroqApiKey() = prefs.groqApiKey
fun TradingViewModel.saveGroqApiKey(key: String) { prefs.groqApiKey = key }
fun TradingViewModel.getGeminiApiKey() = prefs.geminiApiKey
fun TradingViewModel.saveGeminiApiKey(key: String) { prefs.geminiApiKey = key }

fun TradingViewModel.toggleWatchlist(symbol: String) {
    prefs.toggleWatchlist(symbol)
    _watchlist.value = prefs.getWatchlist()
}

fun TradingViewModel.isWatched(symbol: String) = prefs.isInWatchlist(symbol)
