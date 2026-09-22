package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
        val initialWatchlist = prefs.getWatchlist().ifEmpty {
            val defaultSymbol = "BTCIDR"
            prefs.toggleWatchlist(defaultSymbol)
            setOf(defaultSymbol)
        }
        _watchlist.value = initialWatchlist
        _favorites.value = prefs.getFavorites()
        MtfCacheManager.updateQueues(initialWatchlist.toList(), emptyList())
    }

    private fun normalize(symbol: String): String =
        symbol.uppercase().trim().replace("/", "").replace("_", "")

    fun toggleWatchlist(symbol: String) {
        val upper = normalize(symbol)
        if (upper.isBlank()) return
        prefs.toggleWatchlist(upper)
        val current = prefs.getWatchlist().ifEmpty { setOf("BTCIDR") }
        _watchlist.value = current
        MtfCacheManager.updateQueues(current.toList(), emptyList())
        onWatchlistUpdated?.invoke()
    }

    fun addToWatchlist(symbol: String) {
        val upper = normalize(symbol)
        if (upper.isBlank()) return
        val current = _watchlist.value.toMutableSet()
        current.add(upper)
        prefs.setWatchlist(current)
        _watchlist.value = current
        MtfCacheManager.updateQueues(current.toList(), emptyList())
        onWatchlistUpdated?.invoke()
    }

    fun removeFromWatchlist(symbol: String) {
        val upper = normalize(symbol)
        val current = _watchlist.value.toMutableSet()
        current.remove(upper)
        val finalSet = if (current.isEmpty()) setOf("BTCIDR") else current
        prefs.setWatchlist(finalSet)
        _watchlist.value = finalSet
        MtfCacheManager.updateQueues(finalSet.toList(), emptyList())
        onWatchlistUpdated?.invoke()
    }

    fun setCustomWatchlist(symbols: Collection<String>) {
        val upper = symbols.map { normalize(it) }.filter { it.isNotBlank() }.toSet()
        val finalSet = if (upper.isEmpty()) setOf("BTCIDR") else upper
        prefs.setWatchlist(finalSet)
        _watchlist.value = finalSet
        MtfCacheManager.updateQueues(finalSet.toList(), emptyList())
        onWatchlistUpdated?.invoke()
    }

    fun applyWatchlistPreset(presetType: String) {
        val pairs = when (presetType.lowercase()) {
            "top10", "top_10" -> listOf("BTCIDR", "ETHIDR", "SOLIDR", "BNBIDR", "XRPIDR", "ADAIDR", "DOGEIDR", "AVAXIDR", "SUIIDR", "NEARIDR")
            "scalp", "scalping", "gems" -> listOf("PEPEIDR", "DOGEIDR", "SHIBIDR", "SUIIDR", "SOLIDR", "FLOKIIDR", "BONKIDR")
            "ai", "web3" -> listOf("NEARIDR", "RENDERIDR", "FETIDR", "GRTIDR", "ICICPDR", "FILIDR")
            "layer1", "l1" -> listOf("BTCIDR", "ETHIDR", "SOLIDR", "ADAIDR", "AVAXIDR", "DOTIDR", "SUIIDR", "ATOMIDR")
            else -> listOf("BTCIDR", "ETHIDR", "SOLIDR", "DOGEIDR")
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
