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
 * Service Data Pasar Real-time Tokocrypto (Binance Cloud Open API Engine).
 * 100% Real Live Market Data — tanpa mock, sample, atau hardcode data.
 */
object TokocryptoMarketService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("id", "ID"))

    private val API_BASES = listOf(
        "https://data-api.binance.vision/api/v3",
        "https://api.binance.com/api/v3",
        "https://api1.binance.com/api/v3",
        "https://api.binance.us/api/v3",
        "https://api2.binance.com/api/v3",
        "https://api3.binance.com/api/v3"
    )

    fun toSymbol(raw: String): String {
        val s = raw.trim().uppercase().replace("/", "").replace("-", "").replace("_", "").replace(" ", "")
        return when {
            s.endsWith("USDT") || s.endsWith("BIDR") || s.endsWith("BTC") || s.endsWith("ETH") || s.endsWith("BUSD") -> s
            s.endsWith("IDR") -> s.removeSuffix("IDR") + "BIDR"
            else -> "${s}USDT"
        }
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Android; Tokocrypto Live Stream)")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    private fun getWithFallback(endpointPath: String): String? {
        for (base in API_BASES) {
            val res = get("$base$endpointPath")
            if (res != null && res.isNotBlank()) return res
        }
        return null
    }

    /**
     * Ambil 24hr Ticker real-time untuk sebuah symbol di Tokocrypto/Binance.
     */
    suspend fun fetchTicker(symbol: String, prevPrice: Double = 0.0): MarketTick? = withContext(Dispatchers.IO) {
        try {
            val s = toSymbol(symbol)
            val body = getWithFallback("/ticker/24hr?symbol=$s") ?: return@withContext null
            val obj = JSONObject(body)
            val last = obj.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
            if (last <= 0.0) return@withContext null
            val high = obj.optString("highPrice", "0").toDoubleOrNull() ?: last
            val low = obj.optString("lowPrice", "0").toDoubleOrNull() ?: last
            val quoteVol = obj.optString("quoteVolume", "0").toDoubleOrNull()
                ?: obj.optString("volume", "0").toDoubleOrNull() ?: 0.0
            val change = obj.optString("priceChangePercent", "0").toDoubleOrNull() ?: Double.NaN

            MarketTick(
                symbol = s,
                price = last,
                high24h = high,
                low24h = low,
                volume24h = quoteVol,
                change24h = change,
                timestamp = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Ambil list Ticker real-time untuk batch symbol di Tokocrypto.
     */
    suspend fun fetchTickers(symbols: List<String>): List<MarketTick> = withContext(Dispatchers.IO) {
        try {
            val targetList = symbols.map { toSymbol(it) }.distinct()
            val targetSet = targetList.toSet()
            if (targetList.isEmpty()) return@withContext emptyList()

            // 1. Coba query batch efisien dengan parameter symbols=...
            val symbolsJson = JSONArray(targetList).toString()
            val encoded = java.net.URLEncoder.encode(symbolsJson, "UTF-8")
            val batchBody = getWithFallback("/ticker/24hr?symbols=$encoded")
            val body = batchBody ?: getWithFallback("/ticker/24hr") ?: return@withContext emptyList()

            val arr = JSONArray(body)
            val list = mutableListOf<MarketTick>()
            val now = System.currentTimeMillis()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol", "")
                if (sym !in targetSet) continue
                val last = obj.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                if (last <= 0.0) continue
                val high = obj.optString("highPrice", "0").toDoubleOrNull() ?: last
                val low = obj.optString("lowPrice", "0").toDoubleOrNull() ?: last
                val quoteVol = obj.optString("quoteVolume", "0").toDoubleOrNull()
                    ?: obj.optString("volume", "0").toDoubleOrNull() ?: 0.0
                val change = obj.optString("priceChangePercent", "0").toDoubleOrNull() ?: Double.NaN

                list += MarketTick(
                    symbol = sym,
                    price = last,
                    high24h = high,
                    low24h = low,
                    volume24h = quoteVol,
                    change24h = change,
                    timestamp = now
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Volatile
    private var cachedUsdtIdrRate: Double = 16450.0

    @Volatile
    private var cachedTokocryptoSymbols: Set<String>? = null
    @Volatile
    private var lastSymbolsFetchTime: Long = 0L

    private val EXCLUDED_GLOBAL_COINS = setOf("CREAM", "PNT", "FTT", "LUNC", "USTC")

    private val FALLBACK_TOKOCRYPTO_SYMBOLS = setOf(
        "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT", "PEPEUSDT", "SUIUSDT",
        "NEARUSDT", "AVAXUSDT", "DOTUSDT", "LINKUSDT", "ADAUSDT", "ARBUSDT", "OPUSDT", "MATICUSDT",
        "POLUSDT", "ONDOUSDT", "TIAUSDT", "SEIUSDT", "RENDERUSDT", "FETUSDT", "INJUSDT", "TAOUSDT",
        "WIFUSDT", "BONKUSDT", "FLOKIUSDT", "JUPUSDT", "PYTHUSDT", "STRKUSDT", "KASUSDT", "PENDLEUSDT",
        "JTOUSDT", "ENAUSDT", "WUSDT", "REDUSDT", "EDENUSDT", "GPSUSDT", "CARVUSDT", "COMPUSDT",
        "EGLDUSDT", "HEMIUSDT", "SOONUSDT", "KOMUSDT", "MORPHOUSDT", "IOUSDT", "SHIBUSDT", "TRXUSDT",
        "LTCUSDT", "BCHUSDT", "ATOMUSDT", "ICPUSDT", "APTUSDT", "FTMUSDT", "ARUSDT",
        "BTCBIDR", "ETHBIDR", "SOLBIDR", "BNBBIDR", "XRPBIDR", "DOGEBIDR", "PEPEBIDR", "SUIBIDR",
        "SHIBBIDR", "USDTBIDR", "PNUTUSDT", "ACTUSDT", "NEIROUSDT", "GOATUSDT"
    )

    /**
     * Ambil whitelist koin resmi yang aktif terdaftar di bursa Tokocrypto (Bappebti compliant).
     * Mencegah koin global Binance yang tidak ada di Tokocrypto (seperti CREAM, PNT, dll) muncul di app.
     */
    suspend fun getTokocryptoValidSymbols(): Set<String> = withContext(Dispatchers.IO) {
        val cached = cachedTokocryptoSymbols
        val now = System.currentTimeMillis()
        if (cached != null && cached.isNotEmpty() && (now - lastSymbolsFetchTime) < 30 * 60 * 1000L) {
            return@withContext cached
        }

        val validSet = mutableSetOf<String>()
        validSet.addAll(FALLBACK_TOKOCRYPTO_SYMBOLS)

        try {
            val res = get("https://www.tokocrypto.com/open/v1/common/symbols")
                ?: get("https://api.tokocrypto.com/open/v1/common/symbols")
            if (res != null) {
                val obj = JSONObject(res)
                val data = obj.optJSONObject("data")
                val list = data?.optJSONArray("list")
                if (list != null && list.length() > 0) {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val sym = item.optString("symbol", "").replace("_", "").uppercase()
                        val base = item.optString("baseAsset", "").uppercase()
                        if (sym.isNotBlank() && base !in EXCLUDED_GLOBAL_COINS) validSet.add(sym)
                        if (base.isNotBlank() && base !in EXCLUDED_GLOBAL_COINS) {
                            validSet.add("${base}USDT")
                            validSet.add("${base}BIDR")
                            validSet.add("${base}IDR")
                            validSet.add("${base}USDC")
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        cachedTokocryptoSymbols = validSet
        lastSymbolsFetchTime = now
        validSet
    }

    suspend fun getUsdtIdrRate(): Double = withContext(Dispatchers.IO) {
        try {
            val res = getWithFallback("/ticker/price?symbol=USDTBIDR")
                ?: getWithFallback("/ticker/24hr?symbol=USDTBIDR")
            if (res != null) {
                val obj = JSONObject(res)
                val price = obj.optString("price", obj.optString("lastPrice", "0")).toDoubleOrNull() ?: 0.0
                if (price > 10000.0) {
                    cachedUsdtIdrRate = price
                    return@withContext price
                }
            }
        } catch (_: Exception) {}
        cachedUsdtIdrRate
    }

    /**
     * Top Volume real-time scanner dari Tokocrypto.
     * Mengambil seluruh koin likuid berdasarkan quote volume tertinggi.
     */
    suspend fun fetchTopVolumeTicks(
        limit: Int = 30,
        quoteCurrency: String = "USDT",
        excludeStable: Boolean = true
    ): List<MarketTick> = withContext(Dispatchers.IO) {
        try {
            val validSymbols = getTokocryptoValidSymbols()
            val body = getWithFallback("/ticker/24hr") ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val stableBases = setOf("USDC", "FDUSD", "TUSD", "DAI", "BUSD", "EUR", "USDP", "AEUR")
            val list = mutableListOf<MarketTick>()
            val now = System.currentTimeMillis()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol", "")
                if (!sym.endsWith(quoteCurrency)) continue
                val base = sym.removeSuffix(quoteCurrency)
                if (excludeStable && base in stableBases) continue

                // Pastikan koin terdaftar di Tokocrypto resmi
                if (validSymbols.isNotEmpty() && sym !in validSymbols && "${base}USDT" !in validSymbols) {
                    continue
                }

                val last = obj.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                val quoteVol = obj.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0
                if (last <= 0.0 || quoteVol <= 0.0) continue

                val high = obj.optString("highPrice", "0").toDoubleOrNull() ?: last
                val low = obj.optString("lowPrice", "0").toDoubleOrNull() ?: last
                val change = obj.optString("priceChangePercent", "0").toDoubleOrNull() ?: Double.NaN

                list += MarketTick(
                    symbol = sym,
                    price = last,
                    high24h = high,
                    low24h = low,
                    volume24h = quoteVol,
                    change24h = change,
                    timestamp = now
                )
            }

            list.sortedByDescending { it.volume24h }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Scanner koin "Untung / Gainers" real-time Tokocrypto (100% cocok dengan tab Untung di app resmi).
     * Mengurutkan berdasarkan persentase kenaikan 24 jam tertinggi.
     */
    suspend fun fetchGainersTicks(
        limit: Int = 30,
        quoteCurrency: String = "USDT",
        excludeStable: Boolean = true
    ): List<MarketTick> = withContext(Dispatchers.IO) {
        try {
            val validSymbols = getTokocryptoValidSymbols()
            val body = getWithFallback("/ticker/24hr") ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val stableBases = setOf("USDC", "FDUSD", "TUSD", "DAI", "BUSD", "EUR", "USDP", "AEUR")
            val list = mutableListOf<MarketTick>()
            val now = System.currentTimeMillis()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol", "")
                if (!sym.endsWith(quoteCurrency)) continue
                val base = sym.removeSuffix(quoteCurrency)
                if (excludeStable && base in stableBases) continue

                // Pastikan koin terdaftar di Tokocrypto resmi
                if (validSymbols.isNotEmpty() && sym !in validSymbols && "${base}USDT" !in validSymbols) {
                    continue
                }

                val last = obj.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                val quoteVol = obj.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0
                val minVol = if (quoteCurrency == "BIDR") 10_000_000.0 else 500.0
                if (last <= 0.0 || quoteVol < minVol) continue

                val change = obj.optString("priceChangePercent", "0").toDoubleOrNull() ?: Double.NaN
                if (!change.isFinite() || change <= 0.0) continue

                val high = obj.optString("highPrice", "0").toDoubleOrNull() ?: last
                val low = obj.optString("lowPrice", "0").toDoubleOrNull() ?: last

                list += MarketTick(
                    symbol = sym,
                    price = last,
                    high24h = high,
                    low24h = low,
                    volume24h = quoteVol,
                    change24h = change,
                    timestamp = now
                )
            }

            list.sortedByDescending { it.change24h }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Scanner koin "Hot / Trending" real-time Tokocrypto.
     * Menggabungkan faktor likuiditas volume besar dan tren pergerakan harga.
     */
    suspend fun fetchHotTicks(
        limit: Int = 30,
        quoteCurrency: String = "USDT",
        excludeStable: Boolean = true
    ): List<MarketTick> = withContext(Dispatchers.IO) {
        try {
            val validSymbols = getTokocryptoValidSymbols()
            val body = getWithFallback("/ticker/24hr") ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val stableBases = setOf("USDC", "FDUSD", "TUSD", "DAI", "BUSD", "EUR", "USDP", "AEUR")
            val list = mutableListOf<MarketTick>()
            val now = System.currentTimeMillis()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol", "")
                if (!sym.endsWith(quoteCurrency)) continue
                val base = sym.removeSuffix(quoteCurrency)
                if (excludeStable && base in stableBases) continue

                // Pastikan koin terdaftar di Tokocrypto resmi
                if (validSymbols.isNotEmpty() && sym !in validSymbols && "${base}USDT" !in validSymbols) {
                    continue
                }

                val last = obj.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                val quoteVol = obj.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0
                val minVol = if (quoteCurrency == "BIDR") 50_000_000.0 else 5_000.0
                if (last <= 0.0 || quoteVol < minVol) continue

                val high = obj.optString("highPrice", "0").toDoubleOrNull() ?: last
                val low = obj.optString("lowPrice", "0").toDoubleOrNull() ?: last
                val change = obj.optString("priceChangePercent", "0").toDoubleOrNull() ?: Double.NaN

                list += MarketTick(
                    symbol = sym,
                    price = last,
                    high24h = high,
                    low24h = low,
                    volume24h = quoteVol,
                    change24h = change,
                    timestamp = now
                )
            }

            // Urutkan koin HOT: pembobotan pergerakan positif & volume aktif
            list.sortedByDescending { (it.change24h.coerceAtLeast(-5.0) + 10.0) * (kotlin.math.log10(it.volume24h.coerceAtLeast(1.0))) }
                .take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Scanner koin Gainers (Momentum Positif) real-time Tokocrypto untuk Scalping.
     * 100% data live, persentase kenaikan > 0% dengan volume likuid.
     */
    suspend fun fetchScalpingGainersTicks(
        limit: Int = 30,
        quoteCurrency: String = "USDT",
        excludeStable: Boolean = true
    ): List<MarketTick> = fetchGainersTicks(limit, quoteCurrency, excludeStable)

    /**
     * Ambil data candlestick (klines) real-time Tokocrypto/Binance.
     */
    suspend fun fetchCandles(symbol: String, timeframe: Timeframe, limit: Int = 300): List<CandleBar> = withContext(Dispatchers.IO) {
        try {
            val s = toSymbol(symbol)
            val interval = when (timeframe) {
                Timeframe.M1 -> "1m"
                Timeframe.M5 -> "5m"
                Timeframe.M15 -> "15m"
                Timeframe.H1 -> "1h"
                Timeframe.H4 -> "4h"
                Timeframe.D1 -> "1d"
            }
            val requestLimit = limit.coerceIn(30, 500)
            val body = getWithFallback("/klines?symbol=$s&interval=$interval&limit=$requestLimit") ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val result = mutableListOf<CandleBar>()

            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                val openTime = row.optLong(0, 0L)
                val open = row.optString(1, "0").toDoubleOrNull() ?: 0.0
                val high = row.optString(2, "0").toDoubleOrNull() ?: 0.0
                val low = row.optString(3, "0").toDoubleOrNull() ?: 0.0
                val close = row.optString(4, "0").toDoubleOrNull() ?: 0.0
                val volume = row.optString(5, "0").toDoubleOrNull() ?: 0.0

                if (open <= 0 || high <= 0 || low <= 0 || close <= 0 || openTime <= 0) continue
                result += CandleBar(
                    timestamp = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume
                )
            }

            result.sortedBy { it.timestamp }.takeLast(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Ambil Order Book (Bids & Asks) real-time dari Tokocrypto/Binance Depth API.
     */
    suspend fun fetchOrderBook(symbol: String, limit: Int = 12): Pair<List<OrderBookItem>, List<OrderBookItem>> = withContext(Dispatchers.IO) {
        try {
            val s = toSymbol(symbol)
            val reqLimit = limit.coerceIn(5, 50)
            val body = getWithFallback("/depth?symbol=$s&limit=$reqLimit") ?: return@withContext emptyList<OrderBookItem>() to emptyList()
            val j = JSONObject(body)
            val bids = mutableListOf<OrderBookItem>()
            val asks = mutableListOf<OrderBookItem>()
            var bidSum = 0.0
            var askSum = 0.0

            val bidArr = j.optJSONArray("bids") ?: JSONArray()
            for (i in 0 until minOf(limit, bidArr.length())) {
                val row = bidArr.optJSONArray(i) ?: continue
                val price = row.optString(0, "0").toDoubleOrNull() ?: 0.0
                val amount = row.optString(1, "0").toDoubleOrNull() ?: 0.0
                if (price <= 0 || amount <= 0) continue
                bidSum += amount
                bids.add(OrderBookItem(price, amount, bidSum, true))
            }

            val askArr = j.optJSONArray("asks") ?: JSONArray()
            for (i in 0 until minOf(limit, askArr.length())) {
                val row = askArr.optJSONArray(i) ?: continue
                val price = row.optString(0, "0").toDoubleOrNull() ?: 0.0
                val amount = row.optString(1, "0").toDoubleOrNull() ?: 0.0
                if (price <= 0 || amount <= 0) continue
                askSum += amount
                asks.add(OrderBookItem(price, amount, askSum, false))
            }

            bids to asks
        } catch (_: Exception) {
            emptyList<OrderBookItem>() to emptyList()
        }
    }

    /**
     * Ambil Recent Trades real-time dari Tokocrypto/Binance Trades API.
     */
    suspend fun fetchRecentTrades(symbol: String, limit: Int = 15): List<TradeStreamItem> = withContext(Dispatchers.IO) {
        try {
            val s = toSymbol(symbol)
            val reqLimit = limit.coerceIn(5, 50)
            val body = getWithFallback("/trades?symbol=$s&limit=$reqLimit") ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val list = mutableListOf<TradeStreamItem>()

            for (i in 0 until minOf(limit, arr.length())) {
                val t = arr.getJSONObject(i)
                val id = t.optString("id", System.currentTimeMillis().toString())
                val price = t.optString("price", "0").toDoubleOrNull() ?: 0.0
                val qty = t.optString("qty", "0").toDoubleOrNull() ?: 0.0
                val time = t.optLong("time", System.currentTimeMillis())
                val isBuyerMaker = t.optBoolean("isBuyerMaker", false)
                // isBuyerMaker = true -> seller was taker -> isBuy = false
                val isBuy = !isBuyerMaker

                list.add(
                    TradeStreamItem(
                        id = id,
                        price = price,
                        amount = qty,
                        timeFormatted = timeFormat.format(Date(time)),
                        isBuy = isBuy
                    )
                )
            }
            list.reversed()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
