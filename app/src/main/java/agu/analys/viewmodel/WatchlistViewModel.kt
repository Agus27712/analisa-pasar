package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import agu.analys.config.MarketDataSource
import agu.analys.util.AppPreferences
import agu.analys.util.MtfCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WatchlistViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private val _watchlist = MutableStateFlow<Set<String>>(emptySet())
    val watchlist: StateFlow<Set<String>> = _watchlist.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    var onWatchlistUpdated: (() -> Unit)? = null

    init {
        val defaultSym = getDefaultSymbol()
        val initialWatchlist = prefs.getWatchlist().ifEmpty {
            prefs.toggleWatchlist(defaultSym)
            setOf(defaultSym)
        }
        _watchlist.value = initialWatchlist
        _favorites.value = prefs.getFavorites()
        MtfCacheManager.updateQueues(initialWatchlist.toList(), emptyList(), prefs.marketDataSource.name)
    }

    fun getDefaultSymbol(source: MarketDataSource = prefs.marketDataSource): String {
        return if (source == MarketDataSource.TOKOCRYPTO) "BTCUSDT" else "BTCIDR"
    }

    fun reloadForDataSource(source: MarketDataSource) {
        val current = prefs.getWatchlist(source).ifEmpty {
            val def = getDefaultSymbol(source)
            setOf(def)
        }
        _watchlist.value = current
        _favorites.value = prefs.getFavorites(source)
        MtfCacheManager.updateQueues(current.toList(), emptyList(), source.name)
        onWatchlistUpdated?.invoke()
    }

    private fun normalize(symbol: String): String =
        symbol.uppercase().trim().replace("/", "").replace("_", "")

    fun toggleWatchlist(symbol: String) {
        val upper = normalize(symbol)
        if (upper.isBlank()) return
        prefs.toggleWatchlist(upper)
        val defaultSym = getDefaultSymbol()
        val current = prefs.getWatchlist().ifEmpty { setOf(defaultSym) }
        _watchlist.value = current
        MtfCacheManager.updateQueues(current.toList(), emptyList(), prefs.marketDataSource.name)
        onWatchlistUpdated?.invoke()
    }

    fun addToWatchlist(symbol: String) {
        val upper = normalize(symbol)
        if (upper.isBlank()) return
        val current = _watchlist.value.toMutableSet()
        current.add(upper)
        prefs.setWatchlist(current)
        _watchlist.value = current
        MtfCacheManager.updateQueues(current.toList(), emptyList(), prefs.marketDataSource.name)
        onWatchlistUpdated?.invoke()
    }

    fun removeFromWatchlist(symbol: String) {
        val upper = normalize(symbol)
        val current = _watchlist.value.toMutableSet()
        current.remove(upper)
        val defaultSym = getDefaultSymbol()
        val finalSet = if (current.isEmpty()) setOf(defaultSym) else current
        prefs.setWatchlist(finalSet)
        _watchlist.value = finalSet
        MtfCacheManager.updateQueues(finalSet.toList(), emptyList(), prefs.marketDataSource.name)
        onWatchlistUpdated?.invoke()
    }

    fun setCustomWatchlist(symbols: Collection<String>) {
        val upper = symbols.map { normalize(it) }.filter { it.isNotBlank() }.toSet()
        val defaultSym = getDefaultSymbol()
        val finalSet = if (upper.isEmpty()) setOf(defaultSym) else upper
        prefs.setWatchlist(finalSet)
        _watchlist.value = finalSet
        MtfCacheManager.updateQueues(finalSet.toList(), emptyList(), prefs.marketDataSource.name)
        onWatchlistUpdated?.invoke()
    }

    fun applyWatchlistPreset(presetType: String, source: MarketDataSource = prefs.marketDataSource) {
        val quote = if (source == MarketDataSource.TOKOCRYPTO) "USDT" else "IDR"
        val pairs = when (presetType.lowercase()) {
            "top10", "top_10" -> listOf("BTC", "ETH", "SOL", "BNB", "XRP", "ADA", "DOGE", "AVAX", "SUI", "NEAR").map { "$it$quote" }
            "scalp", "scalping", "gems" -> listOf("PEPE", "DOGE", "SHIB", "SUI", "SOL", "FLOKI", "BONK").map { "$it$quote" }
            "ai", "web3" -> listOf("NEAR", "RENDER", "FET", "GRT", "ICP", "FIL").map { "$it$quote" }
            "layer1", "l1" -> listOf("BTC", "ETH", "SOL", "ADA", "AVAX", "DOT", "SUI", "ATOM").map { "$it$quote" }
            else -> listOf("BTC", "ETH", "SOL", "DOGE").map { "$it$quote" }
        }
        setCustomWatchlist(pairs)
    }

    fun toggleFavorite(symbol: String) {
        val upper = normalize(symbol)
        if (upper.isBlank()) return
        prefs.toggleFavorite(upper)
        _favorites.value = prefs.getFavorites()
        onWatchlistUpdated?.invoke()
    }

    fun isFavorite(symbol: String): Boolean {
        return _favorites.value.contains(normalize(symbol))
    }

    fun isWatched(symbol: String): Boolean {
        return _watchlist.value.contains(normalize(symbol))
    }
}
