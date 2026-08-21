package agu.analys.service

import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object IndodaxMarketService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("id", "ID"))
    private data class ChangeReference(val close: Double, val fetchedAt: Long)
    private val changeReferenceCache = mutableMapOf<String, ChangeReference>()
    private const val CHANGE_REFERENCE_CACHE_MS = 60_000L

    // --- Rate limit + retry ---
    private val rateMutex = Mutex()
    private val lastRequestAt = AtomicLong(0L)
    private const val MIN_INTERVAL_MS = 250L // ~4 req/detik
    private const val MAX_RETRIES = 3

    fun toPairId(symbol: String): String {
        val s = symbol.trim().lowercase().replace("/", "_").replace("-", "_").replace(" ", "")
        return when {
            s.contains("_") -> s
            s.endsWith("idr") -> s.dropLast(3) + "_idr"
            s.endsWith("usdt") -> s.dropLast(4) + "_usdt"
            else -> s + "_idr"
        }
    }

    fun toDepthPairId(symbol: String): String = toPairId(symbol).replace("_", "")

    private suspend fun throttle() {
        rateMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = MIN_INTERVAL_MS - (now - lastRequestAt.get())
            if (wait > 0) delay(wait)
            lastRequestAt.set(System.currentTimeMillis())
        }
    }

    /** Public market helper methods remain unchanged below. Real trading is implemented in IndodaxTradeApiV2. */

    suspend fun fetchTicker(symbol: String, prevPrice: Double = 0.0): MarketTick? = withContext(Dispatchers.IO) {
        try {
            val pair = toPairId(symbol)
            val body = get("https://indodax.com/api/ticker/$pair") ?: return@withContext null
            val t = JSONObject(body).optJSONObject("ticker") ?: return@withContext null
            val last = t.optString("last", "0").toDoubleOrNull() ?: 0.0
            if (last <= 0) return@withContext null
            MarketTick(pair.uppercase().replace("_", ""), last,
                t.optString("high", "0").toDoubleOrNull() ?: last,
                t.optString("low", "0").toDoubleOrNull() ?: last,
                t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0,
                Double.NaN, System.currentTimeMillis())
        } catch (_: Exception) { null }
    }

    suspend fun fetchTickers(pairIds: List<String>): List<MarketTick> = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/summaries") ?: return@withContext emptyList()
            val tickers = JSONObject(body).optJSONObject("tickers") ?: return@withContext emptyList()
            pairIds.mapNotNull { raw ->
                val pair = toPairId(raw)
                val t = tickers.optJSONObject(pair) ?: return@mapNotNull null
                val last = t.optString("last", "0").toDoubleOrNull() ?: 0.0
                if (last <= 0) return@mapNotNull null
                MarketTick(pair.uppercase().replace("_", ""), last,
                    t.optString("high", "0").toDoubleOrNull() ?: last,
                    t.optString("low", "0").toDoubleOrNull() ?: last,
                    t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0,
                    Double.NaN, System.currentTimeMillis())
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun fetchTopVolumeTicks(limit: Int = 15, excludeStable: Boolean = true): List<MarketTick> = withContext(Dispatchers.IO) {
        fetchAllMarketTicks().values
            .filter { it.symbol.endsWith("IDR") && (!excludeStable || it.symbol.removeSuffix("IDR").lowercase() !in setOf("usdt", "usdc", "dai", "busd", "tusd", "idrt")) }
            .sortedByDescending { it.volume24h }.distinctBy { it.symbol }.take(limit)
    }

    suspend fun fetchScalpingGainersTicks(limit: Int = 15, excludeStable: Boolean = true): List<MarketTick> = withContext(Dispatchers.IO) {
        fetchAllMarketTicks().values
            .filter { it.symbol.endsWith("IDR") && it.volume24h >= 1_000_000.0 && it.change24h.isFinite() && it.change24h > 0.0 && (!excludeStable || it.symbol.removeSuffix("IDR").lowercase() !in setOf("usdt", "usdc", "dai", "busd", "tusd", "idrt")) }
            .sortedByDescending { it.change24h }.distinctBy { it.symbol }.take(limit)
    }

    suspend fun fetchCandles(symbol: String, timeframe: Timeframe, limit: Int = 300): List<CandleBar> = withContext(Dispatchers.IO) {
        try {
            val tf = timeframe.code
            val minutes = when (tf) { "1" -> 1L; "5" -> 5L; "15" -> 15L; "60" -> 60L; "240" -> 240L; "D" -> 1440L; else -> 1L }
            val candleSeconds = minutes * 60L
            val nowSec = System.currentTimeMillis() / 1000L
            val currentCandleStart = nowSec - (nowSec % candleSeconds)
            val fromSec = nowSec - candleSeconds * (limit.coerceAtLeast(40) + 1)
            val pair = toDepthPairId(symbol).uppercase()
            val body = get("https://indodax.com/tradingview/history_v2?from=$fromSec&symbol=$pair&tf=${if (tf == "D") "1D" else tf}&to=$nowSec") ?: return@withContext emptyList()
            val array = JSONArray(body)
            val result = mutableListOf<CandleBar>()
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val open = row.optDouble("Open", 0.0); val high = row.optDouble("High", 0.0)
                val low = row.optDouble("Low", 0.0); val close = row.optDouble("Close", 0.0)
                val timeSec = row.optLong("Time", 0L)
                if (open <= 0 || high <= 0 || low <= 0 || close <= 0 || timeSec <= 0 || timeSec >= currentCandleStart) continue
                result += CandleBar(timeSec * 1000L, open, high, low, close, row.optString("Volume", "0").toDoubleOrNull() ?: 0.0)
            }
            result.sortedBy { it.timestamp }.takeLast(limit)
        } catch (_: Exception) { emptyList() }
    }

    suspend fun fetchOrderBook(symbol: String, limit: Int = 12): Pair<List<OrderBookItem>, List<OrderBookItem>> = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/depth/${toDepthPairId(symbol)}") ?: return@withContext emptyList<OrderBookItem>() to emptyList()
            val j = JSONObject(body)
            if (j.has("error")) return@withContext emptyList<OrderBookItem>() to emptyList()
            val bids = mutableListOf<OrderBookItem>(); val asks = mutableListOf<OrderBookItem>()
            var bidSum = 0.0; var askSum = 0.0
            val bidArr = j.optJSONArray("buy") ?: JSONArray()
            for (i in 0 until minOf(limit, bidArr.length())) {
                val row = bidArr.optJSONArray(i) ?: continue
                val price = row.optString(0).toDoubleOrNull() ?: 0.0; val amount = row.optString(1).toDoubleOrNull() ?: 0.0
                if (price <= 0 || amount <= 0) continue
                bidSum += amount; bids += OrderBookItem(price, amount, bidSum, true)
            }
            val askArr = j.optJSONArray("sell") ?: JSONArray()
            for (i in 0 until minOf(limit, askArr.length())) {
                val row = askArr.optJSONArray(i) ?: continue
                val price = row.optString(0).toDoubleOrNull() ?: 0.0; val amount = row.optString(1).toDoubleOrNull() ?: 0.0
                if (price <= 0 || amount <= 0) continue
                askSum += amount; asks += OrderBookItem(price, amount, askSum, false)
            }
            bids to asks
        } catch (_: Exception) { emptyList<OrderBookItem>() to emptyList() }
    }

    suspend fun fetchRecentTrades(symbol: String, limit: Int = 15): List<TradeStreamItem> = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/trades/${toDepthPairId(symbol)}") ?: return@withContext emptyList()
            if (body.trimStart().startsWith("{")) return@withContext emptyList()
            val arr = JSONArray(body)
            buildList {
                for (i in 0 until minOf(limit, arr.length())) {
                    val t = arr.getJSONObject(i); val tsSec = t.optLong("date", System.currentTimeMillis() / 1000)
                    val ts = if (tsSec < 10_000_000_000L) tsSec * 1000 else tsSec
                    add(TradeStreamItem(t.optString("tid", ts.toString()), t.optString("price", "0").toDoubleOrNull() ?: 0.0, t.optString("amount", "0").toDoubleOrNull() ?: 0.0, timeFormat.format(Date(ts)), t.optString("type", "buy").equals("buy", true)))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun fetchAllMarketTicks(): Map<String, MarketTick> = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/summaries") ?: return@withContext emptyMap()
            val root = JSONObject(body); val tickers = root.optJSONObject("tickers") ?: return@withContext emptyMap()
            val prices24h = root.optJSONObject("prices_24h"); val now = System.currentTimeMillis()
            val map = mutableMapOf<String, MarketTick>(); val keys = tickers.keys()
            while (keys.hasNext()) {
                val pair = keys.next(); val t = tickers.optJSONObject(pair) ?: continue
                val last = t.optString("last", "0").toDoubleOrNull() ?: 0.0; if (last <= 0) continue
                val symbol = pair.uppercase().replace("_", "")
                val p24 = (prices24h?.optString(pair.replace("_", "").lowercase(), "0")?.toDoubleOrNull() ?: prices24h?.optString(pair, "0")?.toDoubleOrNull()) ?: 0.0
                val change = if (p24 > 0) ((last - p24) / p24) * 100.0 else Double.NaN
                val tick = MarketTick(symbol, last, t.optString("high", "0").toDoubleOrNull() ?: last, t.optString("low", "0").toDoubleOrNull() ?: last, t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0, change, now)
                map[symbol] = tick; map[pair.uppercase()] = tick; map[pair.lowercase()] = tick
            }
            map
        } catch (_: Exception) { emptyMap() }
    }

    suspend fun fetchPublicIp(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://api.ipify.org").build()
            client.newCall(request).execute().use { it.body?.string()?.trim() ?: "Gagal mendapatkan IP" }
        } catch (_: Exception) { "Gagal mengecek IP" }
    }

    private suspend fun get(url: String): String? {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                throttle()
                client.newCall(Request.Builder().url(url).get().header("Accept", "application/json").build()).execute().use { response ->
                    val body = response.body?.string()
                    when {
                        response.isSuccessful -> return body
                        response.code == 429 || response.code in 500..599 -> delay((400L * (1 shl (attempt - 1))).coerceAtMost(4000L))
                        else -> return null
                    }
                }
            } catch (_: Exception) { delay((300L * (1 shl (attempt - 1))).coerceAtMost(3000L)) }
        }
        return null
    }
}
