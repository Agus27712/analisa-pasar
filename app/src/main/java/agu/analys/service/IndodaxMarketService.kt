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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object IndodaxMarketService {
    private val client get() = NetworkClientProvider.marketClient

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("id", "ID"))
    private data class ChangeReference(val close: Double, val fetchedAt: Long)
    private val changeReferenceCache = mutableMapOf<String, ChangeReference>()
    private const val CHANGE_REFERENCE_CACHE_MS = 60_000L

    // --- Rate limit + retry ---
    private val rateMutex = Mutex()
    private val lastRequestAt = AtomicLong(0L)
    private const val MIN_INTERVAL_MS = 200L
    private const val MAX_RETRIES = 2

    fun toPairId(symbol: String): String {
        val s = symbol.trim().lowercase().replace("/", "_").replace("-", "_").replace(" ", "")
        return when {
            s.contains("_") -> s
            s.endsWith("idr") -> s.dropLast(3) + "_idr"
            s.endsWith("usdt") -> s.dropLast(4) + "_idr"
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

    /** GET dengan rate-limit + exponential backoff pada 429 / 5xx / network error. Selalu di Dispatchers.IO */
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
                            val backoff = (300L * (1 shl (attempt - 1))).coerceAtMost(1500L)
                            delay(backoff)
                        }
                        else -> {
                            println("Indodax HTTP $code for $url (Body: ${body?.take(150)})")
                            return@withContext null
                        }
                    }
                }
            } catch (e: Exception) {
                println("Indodax GET error ($url): ${e.javaClass.name} - ${e.message}")
                val backoff = (200L * (1 shl (attempt - 1))).coerceAtMost(1000L)
                delay(backoff)
            }
        }
        null
    }

    private suspend fun fetch24hChange(pair: String, last: Double): Double? {
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
            val body = get("https://indodax.com/tradingview/history_v2?from=$fromSec&symbol=$pairId&tf=60&to=$nowSec") ?: return null
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
            val cachedRef = changeReferenceCache[pair]
            val change = if (cachedRef != null && cachedRef.close > 0) {
                ((last - cachedRef.close) / cachedRef.close) * 100.0
            } else {
                fetch24hChange(pair, last)
            }
            MarketTick(
                symbol = pair.uppercase().replace("_", ""),
                price = last,
                high24h = t.optString("high", "0").toDoubleOrNull() ?: last,
                low24h = t.optString("low", "0").toDoubleOrNull() ?: last,
                volume24h = t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0,
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
            val root = JSONObject(body)
            val tickers = root.optJSONObject("tickers") ?: return@withContext emptyList()
            val prices24h = root.optJSONObject("prices_24h")
            val now = System.currentTimeMillis()

            pairIds.mapNotNull { raw ->
                val pair = toPairId(raw)
                val t = tickers.optJSONObject(pair) ?: return@mapNotNull null
                val last = t.optString("last", "0").toDoubleOrNull() ?: 0.0
                if (last <= 0) return@mapNotNull null

                var change24h: Double? = null
                val keyNoUnderscore = pair.replace("_", "").lowercase()
                val p24 = (prices24h?.optString(keyNoUnderscore, "0")?.toDoubleOrNull()
                    ?: prices24h?.optString(pair, "0")?.toDoubleOrNull()) ?: 0.0
                if (p24 > 0) {
                    change24h = ((last - p24) / p24) * 100.0
                    changeReferenceCache[pair] = ChangeReference(p24, now)
                } else {
                    val cached = changeReferenceCache[pair]
                    if (cached != null && cached.close > 0) {
                        change24h = ((last - cached.close) / cached.close) * 100.0
                    }
                }

                MarketTick(
                    symbol = pair.uppercase().replace("_", ""),
                    price = last,
                    high24h = t.optString("high", "0").toDoubleOrNull() ?: last,
                    low24h = t.optString("low", "0").toDoubleOrNull() ?: last,
                    volume24h = t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0,
                    change24h = change24h ?: Double.NaN,
                    timestamp = now
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Memfilter apakah aset aman, likuid, dan stabil untuk trading (bukan koin receh zombi / delisting trap).
     *
     * Kriteria Eliminasi Koin Receh & Berisiko Delisting:
     * 1. Harga receh ekstrem (Rp 1, 2, 3, 4, 5): Langsung dieliminasi karena pergerakannya semu
     *    (1 tick bernilai 20% s/d 100%, bid/ask kosong, rawan nyangkut permanen).
     * 2. Harga di bawah Rp 25 IDR: Wajib memiliki likuiditas volume 24 jam masif (>= 2 Miliar IDR)
     *    seperti token global (PEPE, SHIB) agar bukan koin zombi yang terancam delisting Indodax.
     * 3. Volume 24 jam minimal: >= 150 Juta IDR untuk pair IDR agar likuiditas beli/jual stabil.
     * 4. Kestabilan pergerakan 24 jam: Mencegah anomali spike semu dari harga Rp 1 ke Rp 2.
     *
     * Catatan: Jika koin sengaja ditambahkan ke Favorit/Watchlist oleh pengguna,
     * koin tersebut tetap diizinkan tampil.
     */
    fun isSafeTradableAsset(
        price: Double,
        volume24h: Double,
        high24h: Double,
        low24h: Double,
        isIdrPair: Boolean = true,
        isExplicitlyFavored: Boolean = false
    ): Boolean {
        if (isExplicitlyFavored) return true
        if (!price.isFinite() || price <= 0.0) return false

        if (isIdrPair) {
            // 1. Eliminasi mutlak koin harga receh ekstrem (Rp 1 s/d 5)
            if (price <= 5.0) return false

            // 2. Koin di bawah Rp 25 IDR harus terbukti likuid dan bervolume masif (>= 2 Miliar IDR)
            if (price < 25.0 && volume24h < 2_000_000_000.0) return false

            // 3. Batas minimal volume 24 jam yang sehat untuk pair IDR (min 150 Juta IDR)
            if (volume24h < 150_000_000.0) return false

            // 4. Kestabilan pergerakan: Mencegah koin mati berharga di bawah Rp 50 yang low 24h nya menyentuh Rp 1
            if (low24h <= 1.0 && price < 50.0) return false
        } else {
            // Untuk pair USDT/USD: Minimal volume ekuivalen ($10,000 USD)
            if (volume24h < 10_000.0 && volume24h > 0) return false
        }

        return true
    }

    data class MarketRankingsResult(
        val gainers: List<MarketTick> = emptyList(),
        val losers: List<MarketTick> = emptyList(),
        val topVolume: List<MarketTick> = emptyList(),
        val allTicks: Map<String, MarketTick> = emptyMap()
    )
    suspend fun fetchPairsMetadata(): List<agu.analys.model.PairPrecision> = withContext(Dispatchers.IO) {
        val body = get("https://indodax.com/api/pairs") ?: return@withContext emptyList()
        try {
            val jsonArray = org.json.JSONArray(body)
            val results = mutableListOf<agu.analys.model.PairPrecision>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                val symbol = item.optString("symbol", "")
                val base = item.optString("base_currency", "")
                val traded = item.optString("traded_currency", "")
                val qtyIncrement = item.optDouble("quantity_increment", 1.0)
                val qtyDecimals = Math.max(0, -Math.log10(qtyIncrement).toInt())
                val pricePrecision = item.optDouble("price_precision", 1.0)
                val priceDecimals = if (pricePrecision > 0) Math.max(0, -Math.log10(pricePrecision).toInt()) else 2
                results.add(
                    agu.analys.model.PairPrecision(
                        id = id,
                        symbol = symbol,
                        baseCurrency = base,
                        tradedCurrency = traded,
                        priceDecimals = priceDecimals,
                        quantityDecimals = qtyDecimals
                    )
                )
            }
            results
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Gagal fetch metadata pairs indodax")
            emptyList()
        }
    }


    suspend fun fetchMarketRankings(limit: Int = 30, excludeStable: Boolean = true): MarketRankingsResult = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/summaries") ?: return@withContext MarketRankingsResult()
            val root = JSONObject(body)
            val tickers = root.optJSONObject("tickers") ?: return@withContext MarketRankingsResult()
            val prices24h = root.optJSONObject("prices_24h")
            val stableBases = setOf("usdt", "usdc", "dai", "busd", "tusd", "idrt")
            val now = System.currentTimeMillis()
            val allTicksMap = mutableMapOf<String, MarketTick>()
            val tradableList = mutableListOf<MarketTick>()
            val keys = tickers.keys()

            while (keys.hasNext()) {
                val pair = keys.next()
                val t = tickers.optJSONObject(pair) ?: continue
                val last = t.optString("last", "0").toDoubleOrNull() ?: 0.0
                if (last <= 0.0) continue
                val volIdr = t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0
                val high = t.optString("high", "0").toDoubleOrNull() ?: last
                val low = t.optString("low", "0").toDoubleOrNull() ?: last
                val symbol = pair.uppercase().replace("_", "")

                var change: Double? = null
                val keyNoUnderscore = pair.replace("_", "").lowercase()
                val p24 = (prices24h?.optString(keyNoUnderscore, "0")?.toDoubleOrNull()
                    ?: prices24h?.optString(pair, "0")?.toDoubleOrNull()) ?: 0.0
                if (p24 > 0) {
                    change = ((last - p24) / p24) * 100.0
                    changeReferenceCache[pair] = ChangeReference(p24, now)
                } else {
                    val cached = changeReferenceCache[pair]
                    if (cached != null && cached.close > 0) {
                        change = ((last - cached.close) / cached.close) * 100.0
                    }
                }

                val tick = MarketTick(
                    symbol = symbol,
                    price = last,
                    high24h = high,
                    low24h = low,
                    volume24h = volIdr,
                    change24h = change ?: Double.NaN,
                    timestamp = now
                )

                allTicksMap[symbol] = tick
                allTicksMap[pair.uppercase()] = tick
                allTicksMap[pair.lowercase()] = tick
                val base = pair.removeSuffix("_idr").removeSuffix("idr").uppercase()
                allTicksMap["${base}IDR"] = tick

                val isIdr = pair.endsWith("_idr") || pair.endsWith("idr")
                val baseLower = base.lowercase()
                if (excludeStable && baseLower in stableBases) continue

                if (isIdr && isSafeTradableAsset(price = last, volume24h = volIdr, high24h = high, low24h = low, isIdrPair = true)) {
                    tradableList += tick
                }
            }

            val gainers = tradableList
                .filter { it.change24h.isFinite() && it.change24h > 0.0 }
                .sortedByDescending { it.change24h }
                .take(limit)

            val losers = tradableList
                .filter { it.change24h.isFinite() && it.change24h < 0.0 }
                .sortedBy { it.change24h }
                .take(limit)

            val topVolume = tradableList
                .sortedByDescending { it.volume24h }
                .take(limit)

            MarketRankingsResult(
                gainers = gainers,
                losers = losers,
                topVolume = topVolume,
                allTicks = allTicksMap
            )
        } catch (_: Exception) {
            MarketRankingsResult()
        }
    }

    suspend fun fetchTopVolumeTicks(limit: Int = 15, excludeStable: Boolean = true): List<MarketTick> = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/summaries") ?: return@withContext emptyList()
            val root = JSONObject(body)
            val tickers = root.optJSONObject("tickers") ?: return@withContext emptyList()
            val stableBases = setOf("usdt", "usdc", "dai", "busd", "tusd", "idrt")
            val list = mutableListOf<MarketTick>()
            val keys = tickers.keys()
            val prices24h = root.optJSONObject("prices_24h")
            val now = System.currentTimeMillis()

            while (keys.hasNext()) {
                val pair = keys.next()
                if (!pair.endsWith("_idr")) continue
                val base = pair.removeSuffix("_idr")
                if (excludeStable && base in stableBases) continue
                val t = tickers.optJSONObject(pair) ?: continue
                val last = t.optString("last", "0").toDoubleOrNull() ?: 0.0
                val volIdr = t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0
                val high = t.optString("high", "0").toDoubleOrNull() ?: last
                val low = t.optString("low", "0").toDoubleOrNull() ?: last

                // Eliminasi koin receh & illiquid
                if (!isSafeTradableAsset(price = last, volume24h = volIdr, high24h = high, low24h = low, isIdrPair = true)) {
                    continue
                }

                val symbol = pair.uppercase().replace("_", "")

                var change: Double? = null
                val keyNoUnderscore = pair.replace("_", "").lowercase()
                val p24 = (prices24h?.optString(keyNoUnderscore, "0")?.toDoubleOrNull()
                    ?: prices24h?.optString(pair, "0")?.toDoubleOrNull()) ?: 0.0
                if (p24 > 0) {
                    change = ((last - p24) / p24) * 100.0
                    changeReferenceCache[pair] = ChangeReference(p24, now)
                } else {
                    val cached = changeReferenceCache[pair]
                    if (cached != null && cached.close > 0) {
                        change = ((last - cached.close) / cached.close) * 100.0
                    }
                }

                list += MarketTick(
                    symbol = symbol,
                    price = last,
                    high24h = high,
                    low24h = low,
                    volume24h = volIdr,
                    change24h = change ?: Double.NaN,
                    timestamp = now
                )
            }
            list.sortedByDescending { it.volume24h }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchScalpingGainersTicks(limit: Int = 15, excludeStable: Boolean = true): List<MarketTick> = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/summaries") ?: return@withContext emptyList()
            val root = JSONObject(body)
            val tickers = root.optJSONObject("tickers") ?: return@withContext emptyList()
            val prices24h = root.optJSONObject("prices_24h")
            val stableBases = setOf("usdt", "usdc", "dai", "busd", "tusd", "idrt")
            val now = System.currentTimeMillis()
            val list = mutableListOf<MarketTick>()
            val keys = tickers.keys()

            while (keys.hasNext()) {
                val pair = keys.next()
                if (!pair.endsWith("_idr")) continue
                val base = pair.removeSuffix("_idr")
                if (excludeStable && base in stableBases) continue
                val t = tickers.optJSONObject(pair) ?: continue
                val last = t.optString("last", "0").toDoubleOrNull() ?: 0.0
                val volIdr = t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0
                val high = t.optString("high", "0").toDoubleOrNull() ?: last
                val low = t.optString("low", "0").toDoubleOrNull() ?: last

                // Eliminasi koin receh & illiquid
                if (!isSafeTradableAsset(price = last, volume24h = volIdr, high24h = high, low24h = low, isIdrPair = true)) {
                    continue
                }

                val symbol = pair.uppercase().replace("_", "")

                var change: Double? = null
                val keyNoUnderscore = pair.replace("_", "").lowercase()
                val p24 = (prices24h?.optString(keyNoUnderscore, "0")?.toDoubleOrNull()
                    ?: prices24h?.optString(pair, "0")?.toDoubleOrNull()) ?: 0.0
                if (p24 > 0) {
                    change = ((last - p24) / p24) * 100.0
                    changeReferenceCache[pair] = ChangeReference(p24, now)
                } else {
                    val cached = changeReferenceCache[pair]
                    if (cached != null && cached.close > 0) {
                        change = ((last - cached.close) / cached.close) * 100.0
                    }
                }

                val finalChange = change ?: Double.NaN
                if (finalChange.isFinite() && finalChange > 0.0) {
                    list += MarketTick(
                        symbol = symbol,
                        price = last,
                        high24h = high,
                        low24h = low,
                        volume24h = volIdr,
                        change24h = finalChange,
                        timestamp = now
                    )
                }
            }
            // Urutkan gainers berdasarkan kombinasi kenaikan persentase dan kestabilan likuiditas volume
            list.sortedWith(
                compareByDescending<MarketTick> { tick ->
                    val volScore = kotlin.math.ln((tick.volume24h / 10_000_000.0).coerceAtLeast(1.0))
                    tick.change24h * (1.0 + volScore * 0.3)
                }.thenByDescending { it.volume24h }
            ).take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchCandles(symbol: String, timeframe: Timeframe, limit: Int = 300, explicitFromSec: Long? = null, explicitToSec: Long? = null): List<CandleBar> = withContext(Dispatchers.IO) {
        try {
            val tf = timeframe.code
            val minutesPerCandle = when (tf) {
                "1" -> 1L; "5" -> 5L; "15" -> 15L; "60" -> 60L; "240" -> 240L; "D" -> 1440L; else -> 1L
            }
            val candleSeconds = minutesPerCandle * 60L
            val nowSec = explicitToSec ?: (System.currentTimeMillis() / 1000L)
            val requestCount = limit.coerceAtLeast(40) + 1
            val fromSec = explicitFromSec ?: (nowSec - (candleSeconds * requestCount))
            val apiTf = if (tf == "D") "1D" else tf
            val pairUpper = toDepthPairId(symbol).uppercase()
            val pairLower = toDepthPairId(symbol).lowercase()

            var body = get("https://indodax.com/tradingview/history_v2?from=$fromSec&symbol=$pairUpper&tf=$apiTf&to=$nowSec")
            if (body == null || body.trim().isEmpty() || body.trim() == "[]" || body.trim() == "{}") {
                body = get("https://indodax.com/tradingview/history_v2?from=$fromSec&symbol=$pairLower&tf=$apiTf&to=$nowSec")
            }
            if (body == null || body.trim().isEmpty() || body.trim() == "[]" || body.trim() == "{}") {
                body = get("https://indodax.com/tradingview/history?from=$fromSec&symbol=$pairUpper&resolution=$apiTf&to=$nowSec")
            }
            if (body == null || body.trim().isEmpty()) return@withContext emptyList()

            val trimmed = body.trim()
            val result = mutableListOf<CandleBar>()

            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    val open = row.optDouble("Open", 0.0)
                    val high = row.optDouble("High", 0.0)
                    val low = row.optDouble("Low", 0.0)
                    val close = row.optDouble("Close", 0.0)
                    if (open <= 0 || high <= 0 || low <= 0 || close <= 0) continue
                    val timeSec = row.optLong("Time", 0L)
                    if (timeSec <= 0) continue
                    result += CandleBar(
                        timeSec * 1000L, open, high, low, close,
                        row.optString("Volume", "0").toDoubleOrNull() ?: row.optDouble("Volume", 0.0)
                    )
                }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val tArr = obj.optJSONArray("t")
                val oArr = obj.optJSONArray("o")
                val hArr = obj.optJSONArray("h")
                val lArr = obj.optJSONArray("l")
                val cArr = obj.optJSONArray("c")
                val vArr = obj.optJSONArray("v")
                if (tArr != null && oArr != null && hArr != null && lArr != null && cArr != null) {
                    val len = minOf(tArr.length(), oArr.length(), hArr.length(), lArr.length(), cArr.length())
                    for (i in 0 until len) {
                        val tSec = tArr.optLong(i, 0L)
                        val o = oArr.optDouble(i, 0.0)
                        val h = hArr.optDouble(i, 0.0)
                        val l = lArr.optDouble(i, 0.0)
                        val c = cArr.optDouble(i, 0.0)
                        val v = vArr?.optDouble(i, 0.0) ?: 0.0
                        if (tSec <= 0 || o <= 0 || h <= 0 || l <= 0 || c <= 0) continue
                        result += CandleBar(tSec * 1000L, o, h, l, c, v)
                    }
                }
            }

            result.sortedBy { it.timestamp }.takeLast(limit)
        } catch (e: Exception) {
            println("fetchCandles exception for $symbol ${timeframe.label}: ${e.javaClass.name} - ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchOrderBook(symbol: String, limit: Int = 12): Pair<List<OrderBookItem>, List<OrderBookItem>> =
        withContext(Dispatchers.IO) {
            try {
                val body = get("https://indodax.com/api/depth/${toDepthPairId(symbol)}")
                    ?: return@withContext emptyList<OrderBookItem>() to emptyList()
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
                        t.optString("tid", ts.toString()),
                        t.optString("price", "0").toDoubleOrNull() ?: 0.0,
                        t.optString("amount", "0").toDoubleOrNull() ?: 0.0,
                        timeFormat.format(Date(ts)),
                        t.optString("type", "buy").equals("buy", true)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchAllMarketTicks(): Map<String, MarketTick> = withContext(Dispatchers.IO) {
        try {
            val body = get("https://indodax.com/api/summaries") ?: return@withContext emptyMap()
            val root = JSONObject(body)
            val tickers = root.optJSONObject("tickers") ?: return@withContext emptyMap()
            val prices24h = root.optJSONObject("prices_24h")
            val now = System.currentTimeMillis()
            val map = mutableMapOf<String, MarketTick>()
            val keys = tickers.keys()
            while (keys.hasNext()) {
                val pair = keys.next()
                val t = tickers.optJSONObject(pair) ?: continue
                val last = t.optString("last", "0").toDoubleOrNull() ?: 0.0
                if (last <= 0) continue
                val symbol = pair.uppercase().replace("_", "")
                val volIdr = t.optString("vol_idr", "0").toDoubleOrNull() ?: 0.0
                var change: Double? = null
                val keyNoUnderscore = pair.replace("_", "").lowercase()
                val p24 = (prices24h?.optString(keyNoUnderscore, "0")?.toDoubleOrNull()
                    ?: prices24h?.optString(pair, "0")?.toDoubleOrNull()) ?: 0.0
                if (p24 > 0) {
                    change = ((last - p24) / p24) * 100.0
                }
                val tick = MarketTick(
                    symbol = symbol,
                    price = last,
                    high24h = t.optString("high", "0").toDoubleOrNull() ?: last,
                    low24h = t.optString("low", "0").toDoubleOrNull() ?: last,
                    volume24h = volIdr,
                    change24h = change ?: Double.NaN,
                    timestamp = now
                )
                map[symbol] = tick
                map[pair.uppercase()] = tick
                map[pair.lowercase()] = tick
                val base = pair.removeSuffix("_idr").removeSuffix("idr").uppercase()
                map["${base}IDR"] = tick
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // --- UTILITIES ---
    suspend fun fetchPublicIp(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.ipify.org")
                .header("Accept", "text/plain")
                .build()
            client.newCall(request).execute().use { resp ->
                resp.body?.string()?.trim() ?: "Gagal mendapatkan IP"
            }
        } catch (_: Exception) {
            "Gagal mengecek IP"
        }
    }
}
