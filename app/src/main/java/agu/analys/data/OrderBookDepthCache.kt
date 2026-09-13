package agu.analys.data

import agu.analys.model.OrderBookItem
import agu.analys.service.IndodaxMarketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache depth / orderbook real-time untuk menghitung rasio Orderbook Pressure (Bids vs Asks).
 * Menghindari duplicate fetch dan menjaga rate limit Indodax.
 */
object OrderBookDepthCache {

    private val cache = ConcurrentHashMap<String, Pair<List<OrderBookItem>, List<OrderBookItem>>>()
    private val lastFetchTime = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val _depthVersion = MutableStateFlow(0L)
    val depthVersion: StateFlow<Long> = _depthVersion.asStateFlow()

    fun getOrderBook(symbol: String): Pair<List<OrderBookItem>, List<OrderBookItem>>? {
        val norm = normalizeSymbol(symbol)
        return cache[norm]
    }

    fun updateOrderBook(symbol: String, bids: List<OrderBookItem>, asks: List<OrderBookItem>) {
        if (bids.isEmpty() && asks.isEmpty()) return
        val norm = normalizeSymbol(symbol)
        cache[norm] = bids to asks
        lastFetchTime[norm] = System.currentTimeMillis()
        _depthVersion.value = System.currentTimeMillis()
    }

    suspend fun fetchIfNeeded(symbol: String, force: Boolean = false) {
        val norm = normalizeSymbol(symbol)
        val now = System.currentTimeMillis()
        val last = lastFetchTime[norm] ?: 0L

        if (!force && cache.containsKey(norm) && (now - last < 30_000L)) {
            return
        }

        if (!inFlight.add(norm)) return

        try {
            withContext(Dispatchers.IO) {
                val depth = IndodaxMarketService.fetchOrderBook(norm, limit = 12)
                if (depth.first.isNotEmpty() || depth.second.isNotEmpty()) {
                    cache[norm] = depth
                    lastFetchTime[norm] = System.currentTimeMillis()
                    _depthVersion.value = System.currentTimeMillis()
                }
            }
        } catch (_: Exception) {
        } finally {
            inFlight.remove(norm)
        }
    }

    fun calculatePressure(symbol: String): Int? {
        val norm = normalizeSymbol(symbol)
        val pair = cache[norm] ?: return null
        val bids = pair.first
        val asks = pair.second
        if (bids.isEmpty() && asks.isEmpty()) return null

        val totalBids = bids.sumOf { it.amount }
        val totalAsks = asks.sumOf { it.amount }
        val sum = totalBids + totalAsks
        if (sum <= 0.0) return 0

        return (((totalBids - totalAsks) / sum) * 100.0).toInt().coerceIn(-100, 100)
    }

    private fun normalizeSymbol(symbol: String): String {
        return symbol.replace("/", "").replace("_", "").lowercase()
    }
}
