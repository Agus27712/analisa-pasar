package agu.analys.util

import agu.analys.model.CandleBar
import agu.analys.model.Timeframe
import agu.analys.service.IndodaxMarketService
import agu.analys.service.TokocryptoMarketService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MtfStatus {
    SYNCING, READY, UPDATING, ERROR
}

/**
 * Multi-Timeframe Cache Manager:
 * Mengelola prefetch dan cache in-memory untuk kline (M1, M15, H1, H4) secara terisolasi per exchange
 * dari Tokocrypto / Indodax API secara terpisah.
 */
object MtfCacheManager {
    // In-memory cache for fast lookup. Map<ExchangeScopedKey, Map<Timeframe, List<CandleBar>>>
    private val cache = mutableMapOf<String, MutableMap<Timeframe, List<CandleBar>>>()

    // Observable status for UI
    private val _mtfState = MutableStateFlow<Map<String, Map<Timeframe, MtfStatus>>>(emptyMap())
    val mtfState: StateFlow<Map<String, Map<Timeframe, MtfStatus>>> = _mtfState

    private val rateLimitMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var activeTier1Symbol: String? = null
    private var activeExchange: String = "TOKOCRYPTO"
    private var tier1Job: Job? = null
    private var backgroundJob: Job? = null

    private var watchlist = listOf<String>()
    private var historyList = listOf<String>()

    private fun buildKey(symbol: String, exchange: String): String {
        return "${exchange.trim().uppercase()}_${symbol.trim().uppercase()}"
    }

    fun updateQueues(newWatchlist: List<String>, newHistory: List<String>, exchange: String = "TOKOCRYPTO") {
        watchlist = newWatchlist
        historyList = newHistory
        activeExchange = exchange.uppercase()
        startBackgroundWorkerIfNeeded()
    }

    /**
     * Set active symbol (Tier 1). Cancels any ongoing Tier 1 fetch for a different symbol.
     */
    fun setActiveSymbol(symbol: String, exchange: String = "TOKOCRYPTO") {
        if (symbol.isBlank()) return
        val normalized = symbol.trim().uppercase()
        val ex = exchange.trim().uppercase()
        activeExchange = ex
        val scopedKey = buildKey(normalized, ex)
        if (activeTier1Symbol == scopedKey) return
        activeTier1Symbol = scopedKey
        tier1Job?.cancel()
        tier1Job = scope.launch {
            prefetchSymbol(normalized, ex, isTier1 = true)
        }
    }

    /**
     * Request specific timeframe retry.
     */
    fun retryTimeframe(symbol: String, tf: Timeframe, exchange: String = "TOKOCRYPTO") {
        val normalized = symbol.trim().uppercase()
        val ex = exchange.trim().uppercase()
        scope.launch {
            safeFetch(normalized, tf, ex, isTier1 = true)
        }
    }

    fun getCachedCandles(symbol: String, timeframe: Timeframe, exchange: String = "TOKOCRYPTO"): List<CandleBar>? {
        val normalized = symbol.trim().uppercase()
        val ex = exchange.trim().uppercase()
        val scopedKey = buildKey(normalized, ex)
        val compactSym = TokocryptoMarketService.toTokocryptoSymbol(normalized)
        val tokoPair = TokocryptoMarketService.toTokocryptoPair(normalized)

        return cache[scopedKey]?.get(timeframe)
            ?: cache[buildKey(compactSym, ex)]?.get(timeframe)
            ?: cache[buildKey(tokoPair, ex)]?.get(timeframe)
            ?: cache[normalized]?.get(timeframe) // fallback
    }

    fun isCacheValid(timeframe: Timeframe, candles: List<CandleBar>?): Boolean {
        if (candles.isNullOrEmpty() || candles.size < 20) return false
        val rawTimestamp = candles.last().timestamp
        val lastTimestampMs = if (rawTimestamp < 10_000_000_000L) rawTimestamp * 1000L else rawTimestamp
        val ageMs = System.currentTimeMillis() - lastTimestampMs
        return when (timeframe) {
            Timeframe.M1 -> ageMs <= 180 * 1000L
            Timeframe.M5 -> ageMs <= 10 * 60 * 1000L
            Timeframe.M15 -> ageMs <= 30 * 60 * 1000L
            Timeframe.H1 -> ageMs <= 120 * 60 * 1000L
            Timeframe.H4 -> ageMs <= 480 * 60 * 1000L
            Timeframe.D1 -> ageMs <= 2880 * 60 * 1000L
        }
    }

    private fun startBackgroundWorkerIfNeeded() {
        if (backgroundJob?.isActive == true) return
        backgroundJob = scope.launch {
            while (isActive) {
                val candidate = findNextBackgroundCandidate()
                if (candidate != null) {
                    prefetchSymbol(candidate, activeExchange, isTier1 = false)
                }
                delay(1500) // Small breather between symbols
            }
        }
    }

    private fun findNextBackgroundCandidate(): String? {
        // Priority 1: Watchlist
        for (symbol in watchlist) {
            val norm = symbol.trim().uppercase()
            val scopedKey = buildKey(norm, activeExchange)
            if (scopedKey == activeTier1Symbol) continue
            if (needsRefresh(norm, activeExchange)) return norm
        }
        // Priority 2: History
        for (symbol in historyList) {
            val norm = symbol.trim().uppercase()
            val scopedKey = buildKey(norm, activeExchange)
            if (scopedKey == activeTier1Symbol) continue
            if (needsRefresh(norm, activeExchange)) return norm
        }
        return null
    }

    private fun needsRefresh(symbol: String, exchange: String): Boolean {
        val norm = symbol.trim().uppercase()
        val scopedKey = buildKey(norm, exchange)
        val symbolCache = cache[scopedKey] ?: cache[norm] ?: return true
        val tfs = listOf(Timeframe.H4, Timeframe.H1, Timeframe.M15, Timeframe.M1)
        for (tf in tfs) {
            if (!isCacheValid(tf, symbolCache[tf])) return true
        }
        return false
    }

    private suspend fun prefetchSymbol(symbol: String, exchange: String, isTier1: Boolean) {
        val norm = symbol.trim().uppercase()
        val tfs = listOf(Timeframe.H1, Timeframe.M15, Timeframe.M1, Timeframe.H4)
        for (tf in tfs) {
            if (!scope.isActive) break
            val currentCandles = getCachedCandles(norm, tf, exchange)
            if (!isCacheValid(tf, currentCandles)) {
                updateStatus(norm, tf, if (currentCandles?.isNotEmpty() == true) MtfStatus.UPDATING else MtfStatus.SYNCING, exchange)
                safeFetch(norm, tf, exchange, isTier1)
            } else {
                updateStatus(norm, tf, MtfStatus.READY, exchange)
            }
        }
    }

    private suspend fun safeFetch(symbol: String, tf: Timeframe, exchange: String, isTier1: Boolean) {
        val norm = symbol.trim().uppercase()
        val scopedKey = buildKey(norm, exchange)

        // Rate limiting throttle
        rateLimitMutex.withLock {
            delay(100L) // Ensure 100ms spacing between MTF API calls
        }

        val limit = when (tf) {
            Timeframe.H4 -> 100
            Timeframe.H1 -> 150
            Timeframe.M15 -> 200
            Timeframe.M1 -> 250
            else -> 100
        }

        // Fetch exclusively from respective market data service
        val fetched = if (exchange.equals("INDODAX", true)) {
            IndodaxMarketService.fetchCandles(norm, tf, limit = limit)
        } else {
            TokocryptoMarketService.fetchCandles(norm, tf, limit = limit)
        }

        if (fetched.isNotEmpty()) {
            val symbolMap = cache.getOrPut(scopedKey) { mutableMapOf() }
            symbolMap[tf] = fetched
            // Legacy mirror
            cache.getOrPut(norm) { mutableMapOf() }[tf] = fetched

            // Also alias under clean compact Tokocrypto symbol and pair for fast retrieval
            val compactSym = TokocryptoMarketService.toTokocryptoSymbol(norm)
            if (compactSym != norm) {
                cache.getOrPut(buildKey(compactSym, exchange)) { mutableMapOf() }[tf] = fetched
            }
            updateStatus(norm, tf, if (isCacheValid(tf, fetched)) MtfStatus.READY else MtfStatus.SYNCING, exchange)
        } else {
            val existing = getCachedCandles(norm, tf, exchange)
            if (existing.isNullOrEmpty()) {
                updateStatus(norm, tf, MtfStatus.ERROR, exchange)
            }
        }
    }

    fun clear() {
        tier1Job?.cancel()
        tier1Job = null
        backgroundJob?.cancel()
        backgroundJob = null
        activeTier1Symbol = null
        cache.clear()
        _mtfState.value = emptyMap()
    }

    private fun updateStatus(symbol: String, tf: Timeframe, status: MtfStatus, exchange: String = "TOKOCRYPTO") {
        val norm = symbol.trim().uppercase()
        val current = _mtfState.value.toMutableMap()
        val symbolStatuses = current.getOrPut(norm) { mutableMapOf() }.toMutableMap()
        symbolStatuses[tf] = status
        current[norm] = symbolStatuses
        _mtfState.value = current
    }
}
