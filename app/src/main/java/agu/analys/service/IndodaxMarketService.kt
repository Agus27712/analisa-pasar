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

    /** GET dengan rate-limit + exponential backoff pada 429 / 5xx / network error. */
    private suspend fun get(url: String): String? {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                throttle()
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "KryptoAnalysis/1.2.6 (Android)")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { response ->
                    val code = response.code
                    val body = response.body?.string()
                    when {
                        response.isSuccessful -> return body
                        code == 429 || code in 500..599 -> {
                            val backoff = (400L * (1 shl (attempt - 1))).coerceAtMost(4000L)
                            delay(backoff)
                        }
                        else -> return null // 4xx lain: jangan retry
                    }
                }
            } catch (_: Exception) {
                val backoff = (300L * (1 shl (attempt - 1))).coerceAtMost(3000L)
                delay(backoff)
            }
        }
        return null
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
                if (last <= 0 || volIdr <= 0) continue
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
                    high24h = t.optString("high", "0").toDoubleOrNull() ?: last,
                    low24h = t.optString("low", "0").toDoubleOrNull() ?: last,
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
                if (last <= 0 || volIdr < 1_000_000.0) continue
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
                        high24h = t.optString("high", "0").toDoubleOrNull() ?: last,
                        low24h = t.optString("low", "0").toDoubleOrNull() ?: last,
                        volume24h = volIdr,
                        change24h = finalChange,
                        timestamp = now
                    )
                }
            }
            list.sortedByDescending { it.change24h }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchCandles(symbol: String, timeframe: Timeframe, limit: Int = 300): List<CandleBar> = withContext(Dispatchers.IO) {
        try {
            val tf = timeframe.code
            val minutesPerCandle = when (tf) {
                "1" -> 1L; "5" -> 5L; "15" -> 15L; "60" -> 60L; "240" -> 240L; "D" -> 1440L; else -> 1L
            }
            val candleSeconds = minutesPerCandle * 60L
            val nowSec = System.currentTimeMillis() / 1000L
            val currentCandleStart = nowSec - (nowSec % candleSeconds)
            val requestCount = limit.coerceAtLeast(40) + 1
            val fromSec = nowSec - (candleSeconds * requestCount)
            val apiTf = if (tf == "D") "1D" else tf
            val pair = toDepthPairId(symbol).uppercase()
            val body = get("https://indodax.com/tradingview/history_v2?from=$fromSec&symbol=$pair&tf=$apiTf&to=$nowSec")
                ?: return@withContext emptyList()
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
                    timeSec * 1000L, open, high, low, close,
                    row.optString("Volume", "0").toDoubleOrNull() ?: 0.0
                )
            }
            result.sortedBy { it.timestamp }.takeLast(limit)
        } catch (_: Exception) {
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

    // --- INDODAX TRADE API V2 ONLY (no TAPI V1 fallback) ---
    // Docs: https://github.com/btcid/indodax-official-api-docs/blob/master/INDODAX-TradeAPI-2.md
    // Wajib: API Key TAPIv2 khusus (key klasik V1 TIDAK bisa dipakai di sini)

    private const val TAPI_V2_BASE = "https://api.indodax.com"
    private const val RECV_WINDOW_MS = 10_000L

    /** HMAC-SHA512 (Official Indodax Trade API V2) */
    private fun signHmacSha512(data: String, secretKey: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        val secretKeySpec = javax.crypto.spec.SecretKeySpec(secretKey.trim().toByteArray(Charsets.UTF_8), "HmacSHA512")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Query string terurut alfabet (wajib agar signature V2 valid). */
    private fun buildSortedQuery(params: Map<String, String>): String =
        params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    /**
     * Ambil saldo akun — Trade API V2 ONLY.
     * GET /api/v2/account
     */
    suspend fun fetchAccountBalanceDetails(apiKey: String, secretKey: String): Pair<Map<String, Double>?, String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanSecret = secretKey.trim()
        if (cleanKey.isBlank() || cleanSecret.isBlank()) {
            return@withContext Pair(null, "API Key atau Secret Key belum diisi.")
        }

        try {
            val timestamp = System.currentTimeMillis()
            val params = mapOf(
                "omitZeroBalances" to "false",
                "recvWindow" to RECV_WINDOW_MS.toString(),
                "timestamp" to timestamp.toString()
            )
            val query = buildSortedQuery(params)
            val sign = signHmacSha512(query, cleanSecret)

            val request = Request.Builder()
                .url("$TAPI_V2_BASE/api/v2/account?$query")
                .get()
                .header("X-APIKEY", cleanKey)
                .header("Sign", sign)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    if (!json.has("code") || json.optInt("code", 0) == 0) {
                        val balancesArr = json.optJSONArray("balances")
                        if (balancesArr != null) {
                            val map = mutableMapOf<String, Double>()
                            for (i in 0 until balancesArr.length()) {
                                val item = balancesArr.optJSONObject(i) ?: continue
                                val asset = item.optString("asset", "").lowercase()
                                val free = item.optString("free", "0").toDoubleOrNull() ?: 0.0
                                val locked = item.optString("locked", "0").toDoubleOrNull() ?: 0.0
                                val total = free + locked
                                if (total > 0.00000001 || asset == "idr") {
                                    map[asset] = total
                                }
                            }
                            val coinCount = map.count { it.key != "idr" && it.value > 0.00000001 }
                            return@withContext Pair(
                                map,
                                "Koneksi Indodax Trade API V2 Berhasil (${coinCount} koin terdeteksi)."
                            )
                        }
                        return@withContext Pair(null, "V2 response OK tapi balances kosong/null.")
                    }
                    val code = json.optInt("code")
                    val msg = json.optString("msg", "")
                    val hint = when (code) {
                        -1002 -> " Key/secret invalid atau bukan key TAPIv2. Buat key baru di indodax.com/trade_api."
                        -1021 -> " Timestamp di luar recvWindow. Cek jam HP."
                        -1022 -> " Signature invalid. Pastikan Secret Key benar."
                        -2015 -> " Access denied: IP belum whitelist / permission kurang / key bukan TAPIv2."
                        else -> ""
                    }
                    return@withContext Pair(null, "Trade API V2 Error code=$code $msg.$hint")
                }

                val hint = when (response.code) {
                    401 -> " 401 = key klasik tidak valid di V2. Wajib API Key TAPIv2 khusus + Secret + IP whitelist."
                    403 -> " 403 = IP belum di-whitelist atau permission View belum aktif."
                    else -> ""
                }
                return@withContext Pair(
                    null,
                    "Trade API V2 HTTP ${response.code}: ${(body?.take(150) ?: response.message)}.$hint"
                )
            }
        } catch (e: Exception) {
            return@withContext Pair(null, "Gagal terhubung Trade API V2: ${e.localizedMessage}")
        }
    }

    suspend fun fetchPublicIp(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://api.ipify.org").build()
            client.newCall(request).execute().use { resp ->
                resp.body?.string()?.trim() ?: "Gagal mendapatkan IP"
            }
        } catch (_: Exception) {
            "Gagal mengecek IP"
        }
    }

    suspend fun fetchAccountBalance(apiKey: String, secretKey: String): Map<String, Double>? {
        return fetchAccountBalanceDetails(apiKey, secretKey).first
    }

    /**
     * Place order — Trade API V2 ONLY.
     * POST /api/v2/order
     */
    suspend fun placeTradeOrder(
        apiKey: String,
        secretKey: String,
        pair: String,
        type: String, // "buy" or "sell"
        price: Long,
        amountIdr: Double
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanSecret = secretKey.trim()
        if (cleanKey.isBlank() || cleanSecret.isBlank()) {
            return@withContext false to "API Key atau Secret Key Indodax belum diisi."
        }

        try {
            val symbol = toDepthPairId(pair).uppercase()
            val sideUpper = type.uppercase()
            val timestamp = System.currentTimeMillis()

            val params = mutableMapOf(
                "symbol" to symbol,
                "side" to sideUpper,
                "type" to "LIMIT",
                "price" to price.toString(),
                "timestamp" to timestamp.toString(),
                "recvWindow" to RECV_WINDOW_MS.toString()
            )

            if (sideUpper == "BUY") {
                params["quoteOrderQty"] = amountIdr.toLong().coerceAtLeast(10000L).toString()
            } else {
                params["quantity"] = amountIdr.toString()
            }

            val sortedQuery = buildSortedQuery(params)
            val sign = signHmacSha512(sortedQuery, cleanSecret)

            val formBuilder = okhttp3.FormBody.Builder()
            params.toSortedMap().forEach { (k, v) -> formBuilder.add(k, v) }

            val request = Request.Builder()
                .url("$TAPI_V2_BASE/api/v2/order")
                .post(formBuilder.build())
                .header("X-APIKEY", cleanKey)
                .header("Sign", sign)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    if (!json.has("code") || json.optInt("code", 0) == 0) {
                        val orderId = json.optLong("orderId", 0L)
                        val clientOrderId = json.optString("clientOrderId", "-")
                        return@withContext true to "Order V2 $sideUpper $symbol berhasil! Order ID: $orderId ($clientOrderId)"
                    }
                    return@withContext false to "Trade API V2 Error: ${json.optString("msg", "code ${json.optInt("code")}")}"
                }
                val hint = if (response.code == 401) " Pakai API Key TAPIv2 (bukan key klasik) + IP whitelist." else ""
                return@withContext false to "Trade API V2 HTTP ${response.code}: ${body?.take(150) ?: response.message}.$hint"
            }
        } catch (e: Exception) {
            return@withContext false to "Gagal menghubungi Trade API V2: ${e.localizedMessage}"
        }
    }
}
