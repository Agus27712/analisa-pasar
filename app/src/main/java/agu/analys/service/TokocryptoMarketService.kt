package agu.analys.service

import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Service Market Tokocrypto dengan Single Source of Truth (SSOT) & fallback Binance Cloud API.
 * Sesuai aturan sistem: Data Grafik/harga memakai dari Tokocrypto fallback Binance dengan symbol perdagangan yang ada di Tokocrypto.
 */
object TokocryptoMarketService {
    private val client get() = NetworkClientProvider.marketClient

    private val tradeTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Jakarta"))

    // Rate limiter & endpoints
    private val rateMutex = Mutex()
    private val lastRequestAt = AtomicLong(0L)
    private const val MIN_INTERVAL_MS = 150L
    private const val MAX_RETRIES = 2

    private val BINANCE_HOSTS = listOf(
        "https://api.binance.com",
        "https://api.binance.me",
        "https://data-api.binance.vision"
    )

    private const val TOKOCRYPTO_BASE_URL = "https://www.tokocrypto.com/open/v1"

    fun toTokocryptoPair(symbol: String): String {
        val s = symbol.trim().uppercase().replace("/", "").replace("-", "")
        return when {
            s.contains("_") -> s
            s.endsWith("BIDR") -> s.removeSuffix("BIDR") + "_BIDR"
            s.endsWith("IDR") -> s.removeSuffix("IDR") + "_BIDR"
            s.endsWith("USDT") -> s.removeSuffix("USDT") + "_USDT"
            else -> "${s}_BIDR"
        }
    }

    fun toBinanceSymbol(symbol: String): String {
        val s = symbol.trim().uppercase().replace("/", "").replace("-", "").replace("_", "")
        return when {
            s.endsWith("IDR") && !s.endsWith("BIDR") -> s.removeSuffix("IDR") + "BIDR"
            else -> s
        }
    }

    private suspend fun throttle() {
        rateMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = MIN_INTERVAL_MS - (now - lastRequestAt.get())
            if (wait > 0) delay(wait)
            lastRequestAt.set(System.currentTimeMillis())
        }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                throttle()
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                client.newCall(req).execute().use { response ->
                    val code = response.code
                    val body = response.body?.string()
                    when {
                        response.isSuccessful -> return@withContext body
                        code == 429 || code in 500..599 -> {
                            val backoff = (250L * (1 shl (attempt - 1))).coerceAtMost(1200L)
                            delay(backoff)
                        }
                        else -> {
                            return@withContext null
                        }
                    }
                }
            } catch (_: Exception) {
                val backoff = (200L * (1 shl (attempt - 1))).coerceAtMost(1000L)
                delay(backoff)
            }
        }
        null
    }

    private suspend fun getWithBinanceFallback(endpointPath: String): String? {
        for (host in BINANCE_HOSTS) {
            val body = get("$host$endpointPath")
            if (!body.isNullOrBlank()) {
                return body
            }
        }
        return null
    }

    suspend fun fetchTicker(symbol: String, prevPrice: Double = 0.0): MarketTick? = withContext(Dispatchers.IO) {
        val binanceSymbol = toBinanceSymbol(symbol)
        try {
            // 1. Coba Binance Cloud endpoint untuk pair Tokocrypto (e.g. BTCBIDR)
            val jsonStr = getWithBinanceFallback("/api/v3/ticker/24hr?symbol=$binanceSymbol")
            if (!jsonStr.isNullOrBlank()) {
                val obj = JSONObject(jsonStr)
                val lastPrice = obj.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                if (lastPrice > 0.0) {
                    val high = obj.optString("highPrice", "0").toDoubleOrNull() ?: lastPrice
                    val low = obj.optString("lowPrice", "0").toDoubleOrNull() ?: lastPrice
                    val quoteVol = obj.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0
                    val changePct = obj.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0
                    return@withContext MarketTick(
                        symbol = binanceSymbol,
                        price = lastPrice,
                        high24h = high,
                        low24h = low,
                        volume24h = quoteVol,
                        change24h = changePct,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }

            // 2. Fallback: Tokocrypto Open API jika diperlukan
            val tokoPair = toTokocryptoPair(symbol)
            val tokoBody = get("$TOKOCRYPTO_BASE_URL/market/ticker?symbol=$tokoPair")
            if (!tokoBody.isNullOrBlank()) {
                val root = JSONObject(tokoBody)
                val data = root.optJSONObject("data")
                if (data != null) {
                    val last = data.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                    if (last > 0) {
                        return@withContext MarketTick(
                            symbol = binanceSymbol,
                            price = last,
                            high24h = data.optString("highPrice", "0").toDoubleOrNull() ?: last,
                            low24h = data.optString("lowPrice", "0").toDoubleOrNull() ?: last,
                            volume24h = data.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0,
                            change24h = data.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }

    suspend fun fetchTickers(symbols: List<String>): List<MarketTick> = withContext(Dispatchers.IO) {
        try {
            val jsonStr = getWithBinanceFallback("/api/v3/ticker/24hr") ?: return@withContext emptyList()
            val array = JSONArray(jsonStr)
            val symbolSet = symbols.map { toBinanceSymbol(it) }.toSet()
            val results = mutableListOf<MarketTick>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val sym = item.optString("symbol", "")
                if (symbolSet.contains(sym)) {
                    val last = item.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                    if (last > 0) {
                        results.add(
                            MarketTick(
                                symbol = sym,
                                price = last,
                                high24h = item.optString("highPrice", "0").toDoubleOrNull() ?: last,
                                low24h = item.optString("lowPrice", "0").toDoubleOrNull() ?: last,
                                volume24h = item.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0,
                                change24h = item.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchCandles(symbol: String, timeframe: Timeframe, limit: Int = 300): List<CandleBar> = withContext(Dispatchers.IO) {
        val binanceSymbol = toBinanceSymbol(symbol)
        val interval = when (timeframe) {
            Timeframe.M1 -> "1m"
            Timeframe.M5 -> "5m"
            Timeframe.M15 -> "15m"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1d"
        }

        try {
            val jsonStr = getWithBinanceFallback("/api/v3/klines?symbol=$binanceSymbol&interval=$interval&limit=${limit.coerceIn(10, 1000)}")
            if (!jsonStr.isNullOrBlank()) {
                val array = JSONArray(jsonStr)
                val candles = mutableListOf<CandleBar>()
                for (i in 0 until array.length()) {
                    val row = array.optJSONArray(i) ?: continue
                    val openTime = row.optLong(0, 0L)
                    val open = row.optString(1, "0").toDoubleOrNull() ?: 0.0
                    val high = row.optString(2, "0").toDoubleOrNull() ?: 0.0
                    val low = row.optString(3, "0").toDoubleOrNull() ?: 0.0
                    val close = row.optString(4, "0").toDoubleOrNull() ?: 0.0
                    val volume = row.optString(5, "0").toDoubleOrNull() ?: 0.0

                    if (openTime > 0 && close > 0) {
                        candles.add(
                            CandleBar(
                                timestamp = openTime / 1000L,
                                open = open,
                                high = high,
                                low = low,
                                close = close,
                                volume = volume
                            )
                        )
                    }
                }
                return@withContext candles
            }
        } catch (_: Exception) {}
        emptyList()
    }

    suspend fun fetchOrderBook(symbol: String, limit: Int = 20): Pair<List<OrderBookItem>, List<OrderBookItem>> = withContext(Dispatchers.IO) {
        val binanceSymbol = toBinanceSymbol(symbol)
        try {
            val jsonStr = getWithBinanceFallback("/api/v3/depth?symbol=$binanceSymbol&limit=${limit.coerceIn(5, 50)}")
            if (!jsonStr.isNullOrBlank()) {
                val obj = JSONObject(jsonStr)
                val rawBids = obj.optJSONArray("bids") ?: JSONArray()
                val rawAsks = obj.optJSONArray("asks") ?: JSONArray()

                var bidSum = 0.0
                val bids = mutableListOf<OrderBookItem>()
                for (i in 0 until rawBids.length()) {
                    val row = rawBids.optJSONArray(i) ?: continue
                    val p = row.optString(0, "0").toDoubleOrNull() ?: 0.0
                    val a = row.optString(1, "0").toDoubleOrNull() ?: 0.0
                    if (p > 0 && a > 0) {
                        bidSum += a
                        bids.add(OrderBookItem(price = p, amount = a, total = bidSum, isBid = true))
                    }
                }

                var askSum = 0.0
                val asks = mutableListOf<OrderBookItem>()
                for (i in 0 until rawAsks.length()) {
                    val row = rawAsks.optJSONArray(i) ?: continue
                    val p = row.optString(0, "0").toDoubleOrNull() ?: 0.0
                    val a = row.optString(1, "0").toDoubleOrNull() ?: 0.0
                    if (p > 0 && a > 0) {
                        askSum += a
                        asks.add(OrderBookItem(price = p, amount = a, total = askSum, isBid = false))
                    }
                }
                return@withContext Pair(bids, asks)
            }
        } catch (_: Exception) {}
        Pair(emptyList(), emptyList())
    }

    suspend fun fetchRecentTrades(symbol: String, limit: Int = 25): List<TradeStreamItem> = withContext(Dispatchers.IO) {
        val binanceSymbol = toBinanceSymbol(symbol)
        try {
            val jsonStr = getWithBinanceFallback("/api/v3/trades?symbol=$binanceSymbol&limit=${limit.coerceIn(5, 50)}")
            if (!jsonStr.isNullOrBlank()) {
                val array = JSONArray(jsonStr)
                val trades = mutableListOf<TradeStreamItem>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optLong("id", 0L).toString()
                    val p = obj.optString("price", "0").toDoubleOrNull() ?: 0.0
                    val q = obj.optString("qty", "0").toDoubleOrNull() ?: 0.0
                    val t = obj.optLong("time", System.currentTimeMillis())
                    val isBuyerMaker = obj.optBoolean("isBuyerMaker", false)
                    // If buyer is maker, aggressive taker is seller -> sell trade
                    val isBuy = !isBuyerMaker

                    if (p > 0 && q > 0) {
                        trades.add(
                            TradeStreamItem(
                                id = id,
                                price = p,
                                amount = q,
                                timeFormatted = tradeTimeFormatter.format(Instant.ofEpochMilli(t)),
                                isBuy = isBuy
                            )
                        )
                    }
                }
                return@withContext trades.reversed()
            }
        } catch (_: Exception) {}
        emptyList()
    }

    data class TokoRankingsResult(
        val gainers: List<MarketTick> = emptyList(),
        val losers: List<MarketTick> = emptyList(),
        val topVolume: List<MarketTick> = emptyList(),
        val allTicks: Map<String, MarketTick> = emptyMap()
    )

    suspend fun fetchMarketRankings(limit: Int = 35): TokoRankingsResult = withContext(Dispatchers.IO) {
        try {
            val jsonStr = getWithBinanceFallback("/api/v3/ticker/24hr") ?: return@withContext TokoRankingsResult()
            val array = JSONArray(jsonStr)
            val allTicks = mutableMapOf<String, MarketTick>()
            val candidates = mutableListOf<MarketTick>()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol", "")
                val isBidr = sym.endsWith("BIDR")
                val isUsdt = sym.endsWith("USDT")
                if (!isBidr && !isUsdt) continue

                val last = obj.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                if (last <= 0.0) continue

                val high = obj.optString("highPrice", "0").toDoubleOrNull() ?: last
                val low = obj.optString("lowPrice", "0").toDoubleOrNull() ?: last
                val quoteVol = obj.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0
                val change = obj.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0

                val tick = MarketTick(
                    symbol = sym,
                    price = last,
                    high24h = high,
                    low24h = low,
                    volume24h = quoteVol,
                    change24h = change,
                    timestamp = System.currentTimeMillis()
                )

                allTicks[sym] = tick
                if (sym.endsWith("BIDR")) {
                    val idrAlias = sym.removeSuffix("BIDR") + "IDR"
                    allTicks[idrAlias] = tick.copy(symbol = idrAlias)
                }

                if (isSafeTradableAsset(last, quoteVol, high, low, isBidr)) {
                    candidates.add(tick)
                }
            }

            val gainers = candidates.filter { it.change24h > 0 }.sortedByDescending { it.change24h }.take(limit)
            val losers = candidates.filter { it.change24h < 0 }.sortedBy { it.change24h }.take(limit)
            val topVol = candidates.sortedByDescending { it.volume24h }.take(limit)

            TokoRankingsResult(
                gainers = gainers,
                losers = losers,
                topVolume = topVol,
                allTicks = allTicks
            )
        } catch (_: Exception) {
            TokoRankingsResult()
        }
    }

    fun isSafeTradableAsset(
        price: Double,
        volume24h: Double,
        high24h: Double,
        low24h: Double,
        isBidrPair: Boolean = true,
        isExplicitlyFavored: Boolean = false
    ): Boolean {
        if (isExplicitlyFavored) return true
        if (!price.isFinite() || price <= 0.0) return false

        return if (isBidrPair) {
            // BIDR = IDR equivalent
            if (price <= 5.0) return false
            if (price < 25.0 && volume24h < 1_000_000_000.0) return false
            if (volume24h < 100_000_000.0) return false
            true
        } else {
            // USDT pair
            volume24h >= 10_000.0
        }
    }
}
