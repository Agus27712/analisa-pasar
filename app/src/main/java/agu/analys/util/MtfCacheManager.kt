package agu.analys.util

import agu.analys.model.CandleBar
import agu.analys.model.Timeframe
import agu.analys.service.IndodaxMarketService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MtfStatus {
    SYNCING, READY, UPDATING, ERROR
}

object MtfCacheManager {
    // In-memory cache for fast lookup. Map<Symbol, Map<Timeframe, List<CandleBar>>>
    private val cache = mutableMapOf<String, MutableMap<Timeframe, List<CandleBar>>>()

    // Observable status for UI
    private val _mtfState = MutableStateFlow<Map<String, Map<Timeframe, MtfStatus>>>(emptyMap())
    val mtfState: StateFlow<Map<String, Map<Timeframe, MtfStatus>>> = _mtfState

    private val rateLimitMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var activeTier1Symbol: String? = null
    private var tier1Job: Job? = null
    private var backgroundJob: Job? = null

    private var watchlist = listOf<String>()
    private var historyList = listOf<String>()

    fun updateQueues(newWatchlist: List<String>, newHistory: List<String>) {
        watchlist = newWatchlist
        historyList = newHistory
        startBackgroundWorkerIfNeeded()
    }

    /**
     * Set active symbol (Tier 1). Cancels any ongoing Tier 1 fetch for a different symbol.
     */
    fun setActiveSymbol(symbol: String) {
        if (activeTier1Symbol == symbol) return
        activeTier1Symbol = symbol
        tier1Job?.cancel()
        tier1Job = scope.launch {
            prefetchSymbol(symbol, isTier1 = true)
        }
    }

    /**
     * Request specific timeframe retry.
     */
    fun retryTimeframe(symbol: String, tf: Timeframe) {
        scope.launch {
            safeFetch(symbol, tf, isTier1 = true)
        }
    }

    fun getCachedCandles(symbol: String, timeframe: Timeframe): List<CandleBar>? {
        return cache[symbol]?.get(timeframe)
    }

    fun isCacheValid(timeframe: Timeframe, candles: List<CandleBar>?): Boolean {
        if (candles.isNullOrEmpty() || candles.size < 20) return false
        val lastTimestamp = candles.last().timestamp
        val ageMs = System.currentTimeMillis() - lastTimestamp
        return when (timeframe) {
            Timeframe.M1 -> ageMs <= 5 * 60 * 1000L
            Timeframe.M15 -> ageMs <= 45 * 60 * 1000L
            Timeframe.H1 -> ageMs <= 180 * 60 * 1000L
            Timeframe.H4 -> ageMs <= 12 * 60 * 60 * 1000L
            else -> false
        }
    }

    private fun startBackgroundWorkerIfNeeded() {
        if (backgroundJob?.isActive == true) return
        backgroundJob = scope.launch {
            while (isActive) {
                val candidate = findNextBackgroundCandidate()
                if (candidate != null) {
                    prefetchSymbol(candidate, isTier1 = false)
                }
                delay(1500) // Small breather between symbols
            }
        }
    }

    private fun findNextBackgroundCandidate(): String? {
        // Priority 1: Watchlist
        for (symbol in watchlist) {
            if (symbol == activeTier1Symbol) continue
            if (needsRefresh(symbol)) return symbol
        }
        // Priority 2: History
        for (symbol in historyList) {
            if (symbol == activeTier1Symbol) continue
            if (needsRefresh(symbol)) return symbol
        }
        return null
    }

    private fun needsRefresh(symbol: String): Boolean {
        val symbolCache = cache[symbol] ?: return true
        val tfs = listOf(Timeframe.H4, Timeframe.H1, Timeframe.M15, Timeframe.M1)
        for (tf in tfs) {
            if (!isCacheValid(tf, symbolCache[tf])) return true
        }
        return false
    }

    private suspend fun prefetchSymbol(symbol: String, isTier1: Boolean) {
        val tfs = listOf(Timeframe.H4, Timeframe.H1, Timeframe.M15, Timeframe.M1)
        for (tf in tfs) {
            if (!scope.isActive) break
            val currentCandles = getCachedCandles(symbol, tf)
            if (!isCacheValid(tf, currentCandles)) {
                updateStatus(symbol, tf, if (currentCandles?.isNotEmpty() == true) MtfStatus.UPDATING else MtfStatus.SYNCING)
                safeFetch(symbol, tf, isTier1)
            } else {
                updateStatus(symbol, tf, MtfStatus.READY)
            }
        }
    }

    private suspend fun safeFetch(symbol: String, tf: Timeframe, isTier1: Boolean) {
        // Rate limiting throttle
        rateLimitMutex.withLock {
            delay(200L) // Ensure 200ms spacing between any API call
        }

        val limit = when (tf) {
            Timeframe.H4 -> 120
            Timeframe.H1 -> 150
            Timeframe.M15 -> 200
            Timeframe.M1 -> 250
            else -> 100
        }

        val existing = getCachedCandles(symbol, tf)
        val fetched = if (!existing.isNullOrEmpty() && existing.size >= 20) {
            // Incremental sync
            val lastTimeSec = existing.last().timestamp / 1000L
            val nowSec = System.currentTimeMillis() / 1000L
            val newCandles = IndodaxMarketService.fetchCandles(symbol, tf, limit = limit, explicitFromSec = lastTimeSec, explicitToSec = nowSec)
            mergeCandles(existing, newCandles).takeLast(limit)
        } else {
            // Full fetch
            IndodaxMarketService.fetchCandles(symbol, tf, limit = limit)
        }

        if (fetched.isNotEmpty()) {
            val symbolMap = cache.getOrPut(symbol) { mutableMapOf() }
            symbolMap[tf] = fetched
            updateStatus(symbol, tf, if (isCacheValid(tf, fetched)) MtfStatus.READY else MtfStatus.SYNCING)
        } else {
            updateStatus(symbol, tf, MtfStatus.ERROR)
        }
    }

    private fun mergeCandles(old: List<CandleBar>, new: List<CandleBar>): List<CandleBar> {
        if (new.isEmpty()) return old
        val map = old.associateBy { it.timestamp }.toMutableMap()
        for (c in new) {
            map[c.timestamp] = c
        }
        return map.values.sortedBy { it.timestamp }
    }

    private fun updateStatus(symbol: String, tf: Timeframe, status: MtfStatus) {
        val current = _mtfState.value.toMutableMap()
        val symbolStatuses = current.getOrPut(symbol) { mutableMapOf() }.toMutableMap()
        symbolStatuses[tf] = status
        current[symbol] = symbolStatuses
        _mtfState.value = current
    }
}
