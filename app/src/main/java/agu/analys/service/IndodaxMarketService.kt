package agu.analys.service

import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Real market data from INDODAX public APIs.
 * The learning engine must use one exchange/source consistently, so candles,
 * ticker, order book and trades all come from INDODAX IDR pairs.
 */
object IndodaxMarketService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("id", "ID"))

    private data class ChangeReference(val close: Double, val fetchedAt: Long)
    private val changeReferenceCache = mutableMapOf<String, ChangeReference>()
    private const val CHANGE_REFERENCE_CACHE_MS = 60_000L

    fun toPairId(symbol: String): String {
        val s = symbol.trim().lowercase()
            .replace("/", "_")
            .replace("-", "_")
            .replace(" ", "")
        return when {
            s.contains("_") -> s
            s.endsWith("idr") -> s.dropLast(3) + "_idr"
            s.endsWith("usdt") -> s.dropLast(4) + "_idr"
            else -> s + "_idr"
        }
    }

    fun toDepthPairId(symbol: String): String = toPairId(symbol).replace("_", "")

    private fun get(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "KryptoAnalysis/1.0 (Android)")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetch24hChange(pair: String, last: Double): Double? {
        val cached = changeReferenceCache[pair]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.fetchedAt < CHANGE_REFERENCE_CACHE_MS && cached.close > 0) {
            return ((last - cached.close) / cached.close) * 100.0
        }

        return try {
            val nowSec = now / 1000L
            val targetSec = nowSec - 24L * 60L * 60L
            val fromSec = targetSec - 3L * 60L * 60L
            val pairId = pair.replace("_", "").uppercase()
            val url = "https://indodax.com/tradingview/history_v2?from=$fromSec&symbol=$pairId&tf=60&to=$nowSec"
            val body = get(url) ?: return null
            val array = JSONArray(body)
            var referenceTime = 0L
            var referenceClose = 0.0

            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val time = row.optLong("Time", 0L)
                val close = row.optDouble("Close", 0.0)
                if (time > 0 && time <= targetSec && close > 0 && time >= referenceTime) {
                    referenceTime = time
                    referenceClose = close
                }
            }

            if (referenceClose <= 0) return null
            changeReferenceCache[pair] = ChangeReference(referenceClose, now)
            ((last - referenceClose) / referenceClose) * 100.0
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchTicker(symbol: String, prevPrice: Double = 0.0): MarketTick? = withContext(Dispatchers.IO) {
        try {
            val pair = toPairId(symbol)
            val body = get("https://indodax.com/api/ticker/$pair") ?: return@withContext null
            val t = JSONObject(body).optJSONObject("ticker") ?: return@withContext null
            val last = t.optString("last", "0").toDoubleOrNull() ?: 0.0
            if (last <= 0) return@withContext null
            val high = t.optString("high", "0").toDoubleOrNull() ?: last
            val low = t.optString("low", "0").toDoubleOrNull() ?: last
            val volIdr = t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0
            val change = fetch24hChange(pair, last)
            MarketTick(
                symbol = pair.uppercase().replace("_", ""),
                price = last,
                high24h = high,
                low24h = low,
                volume24h = volIdr,
                change24h = change ?: Double.NaN,
                timestamp = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
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
                val change = fetch24hChange(pair, last)
                MarketTick(
                    symbol = pair.uppercase().replace("_", ""),
                    price = last,
                    high24h = t.optString("high", "0").toDoubleOrNull() ?: last,
                    low24h = t.optString("low", "0").toDoubleOrNull() ?: last,
                    volume24h = t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0,
                    change24h = change ?: Double.NaN,
                    timestamp = System.currentTimeMillis()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Real OHLC history from INDODAX. Only completed candles are returned so
     * RSI, MACD, Bollinger, ATR, patterns and market structure cannot repaint
     * from the currently forming candle. The live ticker remains available
     * separately for current-price monitoring and entry calculation.
     *
     * Keep a 300-candle working set so EMA200 has enough warm-up history and
     * remains stable when incomplete candles are filtered out. The chart/viewport
     * may show fewer candles at once, but older candles remain available for
     * indicator calculation and interactive navigation.
     */
    suspend fun fetchCandles(symbol: String, timeframe: Timeframe, limit: Int = 300): List<CandleBar> = withContext(Dispatchers.IO) {
        try {
            val workingLimit = limit.coerceAtLeast(300)
            val tf = timeframe.code
            val minutesPerCandle = when (tf) {
                "1" -> 1L
                "5" -> 5L
                "15" -> 15L
                "60" -> 60L
                "240" -> 240L
                "D" -> 1440L
                else -> 1L
            }
            val candleSeconds = minutesPerCandle * 60L
            val nowSec = System.currentTimeMillis() / 1000L
            val currentCandleStart = nowSec - (nowSec % candleSeconds)
            // Request one extra candle so filtering the currently forming candle
            // still leaves up to `workingLimit` completed candles.
            val requestCount = workingLimit + 1
            val fromSec = nowSec - (candleSeconds * requestCount)
            val apiTf = if (tf == "D") "1D" else tf
            val pair = toDepthPairId(symbol).uppercase()
            val url = "https://indodax.com/tradingview/history_v2?from=$fromSec&symbol=$pair&tf=$apiTf&to=$nowSec"
            val body = get(url) ?: return@withContext emptyList()
            val array = JSONArray(body)
            val result = mutableListOf<CandleBar>()
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val open = row.optDouble("Open", 0.0)
                val high = row.optDouble("High", 0.0)
                val low = row.optDouble("Low", 0.0)
                val close = row.optDouble("Close", 0.0)
                if (open <= 0 || high <= 0 || low <= 0 || close <= 0) continue
                val timeSec = row.optLong("Time", 0L)
                if (timeSec <= 0 || timeSec >= currentCandleStart) continue
                result += CandleBar(
                    timestamp = timeSec * 1000L,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = row.optString("Volume", "0").toDoubleOrNull() ?: 0.0
                )
            }
            result.sortedBy { it.timestamp }.takeLast(workingLimit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchOrderBook(symbol: String, limit: Int = 12): Pair<List<OrderBookItem>, List<OrderBookItem>> = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/depth/${toDepthPairId(symbol)}") ?: return@withContext emptyList<OrderBookItem>() to emptyList()
            val j = JSONObject(body)
            if (j.has("error")) return@withContext emptyList<OrderBookItem>() to emptyList()
            val bids = mutableListOf<OrderBookItem>()
            val asks = mutableListOf<OrderBookItem>()
            var bidSum = 0.0
            var askSum = 0.0
            val bidArr = j.optJSONArray("buy") ?: JSONArray()
            for (i in 0 until minOf(limit, bidArr.length())) {
                val row = bidArr.optJSONArray(i) ?: continue
                val price = row.optString(0).toDoubleOrNull() ?: row.optDouble(0)
                val amount = row.optString(1).toDoubleOrNull() ?: row.optDouble(1)
                if (price <= 0 || amount <= 0) continue
                bidSum += amount
                bids.add(OrderBookItem(price, amount, bidSum, true))
            }
            val askArr = j.optJSONArray("sell") ?: JSONArray()
            for (i in 0 until minOf(limit, askArr.length())) {
                val row = askArr.optJSONArray(i) ?: continue
                val price = row.optString(0).toDoubleOrNull() ?: row.optDouble(0)
                val amount = row.optString(1).toDoubleOrNull() ?: row.optDouble(1)
                if (price <= 0 || amount <= 0) continue
                askSum += amount
                asks.add(OrderBookItem(price, amount, askSum, false))
            }
            bids to asks
        } catch (_: Exception) {
            emptyList<OrderBookItem>() to emptyList()
        }
    }

    suspend fun fetchRecentTrades(symbol: String, limit: Int = 15): List<TradeStreamItem> = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/trades/${toDepthPairId(symbol)}") ?: return@withContext emptyList()
            if (body.trimStart().startsWith("{")) return@withContext emptyList()
            val arr = JSONArray(body)
            val list = mutableListOf<TradeStreamItem>()
            for (i in 0 until minOf(limit, arr.length())) {
                val t = arr.getJSONObject(i)
                val tsSec = t.optLong("date", System.currentTimeMillis() / 1000)
                val ts = if (tsSec < 10_000_000_000L) tsSec * 1000 else tsSec
                list.add(
                    TradeStreamItem(
                        id = t.optString("tid", ts.toString()),
                        price = t.optString("price", "0").toDoubleOrNull() ?: 0.0,
                        amount = t.optString("amount", "0").toDoubleOrNull() ?: 0.0,
                        timeFormatted = timeFormat.format(Date(ts)),
                        isBuy = t.optString("type", "buy").equals("buy", true)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
