package agu.analys.service

import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * INDODAX Trade API 2.0 ONLY.
 * Fetch sengaja pelan: 1x account, myTrades max 1 window 7 hari.
 *
 * Arsitektur:
 * - Singleton Object: Untuk akses global yang stateless.
 * - Shared OkHttpClient: Menggunakan NetworkClientProvider.tradeClient untuk efisiensi resource.
 * - Thread Safety: Mutex & @Volatile pada sinkronisasi server_time offset.
 * - Robust URL Encoding: Query string di-encode standar RFC 3986/UTF-8.
 * - Resilient Read Endpoints: Exponential backoff retry pada error transient (429/5xx).
 * - Safe Write Endpoints: Single-shot tanpa auto-retry untuk mencegah duplicate order.
 * - HMAC-SHA256: Digunakan untuk signing request sesuai standar API V2 Indodax.
 * - Error Mapping: Mengubah kode error API menjadi pesan yang dapat dipahami user.
 */
object IndodaxTradeApiV2 {
    private const val V2_BASE_URL = "https://api.indodax.com"
    private const val SERVER_TIME_URL = "https://indodax.com/api/server_time"
    private const val RECV_WINDOW_MS = 10_000L
    /** Docs: interval startTime–endTime max 7 hari. */
    private const val MY_TRADES_MAX_RANGE_MS = 7L * 24 * 60 * 60 * 1000

    private val client get() = NetworkClientProvider.tradeClient

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(value: String): String =
        try {
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }

    private fun encodeQuery(params: LinkedHashMap<String, String>): String =
        params.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }

    fun toTradeSymbol(symbol: String): String =
        IndodaxMarketService.toPairId(symbol).replace("_", "").lowercase()

    fun toOrderSymbol(symbol: String): String =
        IndodaxMarketService.toPairId(symbol).replace("_", "").uppercase()

    @Volatile
    private var serverTimeOffset: Long? = null
    private val serverTimeMutex = Mutex()

    fun clearServerTimeOffset() {
        serverTimeOffset = null
    }

    private suspend fun fetchServerTimeOffset(): Long = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(SERVER_TIME_URL)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                val raw = when {
                    root.has("server_time") -> root.optLong("server_time", 0L)
                    root.has("serverTime") -> root.optLong("serverTime", 0L)
                    else -> 0L
                }
                val serverMs = if (raw <= 0L) System.currentTimeMillis()
                else if (raw < 1_000_000_000_000L) raw * 1000L else raw
                
                val offset = serverMs - System.currentTimeMillis()
                serverTimeOffset = offset
                offset
            }
        } catch (e: Exception) {
            Timber.e(e, "Gagal fetch server_time Indodax")
            0L
        }
    }

    private suspend fun serverTimeMs(): Long {
        val current = serverTimeOffset
        if (current != null) {
            return System.currentTimeMillis() + current
        }
        return serverTimeMutex.withLock {
            val existing = serverTimeOffset
            if (existing != null) {
                System.currentTimeMillis() + existing
            } else {
                val offset = fetchServerTimeOffset()
                System.currentTimeMillis() + offset
            }
        }
    }

    private suspend fun signedV2Request(
        apiKey: String,
        secretKey: String,
        method: String,
        path: String,
        params: LinkedHashMap<String, String>,
        maxRetries: Int = 0
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val methodUpper = method.uppercase()
        val isTradeOrWrite = methodUpper == "POST" || methodUpper == "DELETE" || path.contains("/order")
        
        var attempt = 0
        var lastErrorMsg = ""

        while (attempt <= maxRetries) {
            if (attempt > 0) {
                val backoffMs = (500L * (1L shl (attempt - 1))).coerceAtMost(3000L)
                Timber.w("Retrying signed request $path (attempt $attempt/$maxRetries) after ${backoffMs}ms backoff...")
                delay(backoffMs)
                // Refresh timestamp jika ada dalam parameter agar tetap valid dalam recvWindow
                if (params.containsKey("timestamp")) {
                    params["timestamp"] = serverTimeMs().toString()
                }
            }

            if (isTradeOrWrite) {
                agu.analys.util.RateLimiters.privateTrade.waitAndConsume()
            } else {
                agu.analys.util.RateLimiters.privateAccount.waitAndConsume()
            }

            val payloadString = encodeQuery(params)
            val sign = hmacSha256(secretKey, payloadString)
            val fullUrl = "$V2_BASE_URL$path"

            val request = when (methodUpper) {
                "GET" -> Request.Builder()
                    .url("$fullUrl?$payloadString")
                    .get()
                    .header("X-APIKEY", apiKey.trim())
                    .header("Sign", sign)
                    .header("Accept", "application/json")
                    .build()
                "DELETE" -> Request.Builder()
                    .url("$fullUrl?$payloadString")
                    .delete()
                    .header("X-APIKEY", apiKey.trim())
                    .header("Sign", sign)
                    .header("Accept", "application/json")
                    .build()
                "POST" -> {
                    val formBody = FormBody.Builder()
                    params.forEach { (key, value) -> formBody.add(key, value) }
                    Request.Builder()
                        .url(fullUrl)
                        .post(formBody.build())
                        .header("X-APIKEY", apiKey.trim())
                        .header("Sign", sign)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .build()
                }
                else -> return@withContext false to "Unsupported HTTP method: $method"
            }

            try {
                val (isSuccess, resultString, shouldRetry) = client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val json = try { JSONObject(responseBody) } catch (_: Exception) { null }
                    val hasErrorCode = json != null && json.has("code") && json.optInt("code", 0) != 0
                    val code = json?.optInt("code", 0) ?: 0

                    if (!response.isSuccessful || hasErrorCode) {
                        if (code == -1021 || responseBody.contains("Invalid Timestamp") || responseBody.contains("recvWindow")) {
                            Timber.w("Indodax API V2 Timestamp Invalid, clearing offset. body=$responseBody")
                            serverTimeOffset = null
                        }
                    }

                    if (response.isSuccessful && !hasErrorCode) {
                        Triple(true, responseBody, false)
                    } else {
                        val errorMsg = mapV2Error(json, responseBody.ifBlank { response.message })
                        val isTransient = response.code in listOf(429, 500, 502, 503, 504) || code == -1003
                        Timber.w("V2 Request Failed [HTTP ${response.code} / Code $code]: $path | $errorMsg")
                        Triple(false, errorMsg, isTransient)
                    }
                }

                if (isSuccess) {
                    return@withContext true to resultString
                } else {
                    lastErrorMsg = resultString
                    if (!shouldRetry || attempt >= maxRetries) {
                        return@withContext false to resultString
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Trade API V2 network error on $path (attempt $attempt/$maxRetries)")
                lastErrorMsg = "Trade API V2 network error: ${e.localizedMessage}"
                val isIoException = e is IOException
                if (!isIoException || attempt >= maxRetries) {
                    return@withContext false to lastErrorMsg
                }
            }

            attempt++
        }

        false to lastErrorMsg
    }

    private fun mapV2Error(json: JSONObject?, fallback: String): String {
        val code = json?.optInt("code", 0) ?: 0
        val msg = json?.optString("msg", fallback).orEmpty()
        return when (code) {
            -1002 -> "Invalid credentials (-1002). Cek API Key TAPIv2 (bukan V1)."
            -1021 -> "Timestamp invalid (-1021). Sinkronkan jam HP."
            -1022 -> "Signature invalid (-1022). Secret Key salah."
            -1121 -> "Invalid symbol (-1121)."
            -2015 -> if (msg.isNotBlank()) "Akses ditolak (-2015): $msg. Cek IP Whitelist, Permission Trade, atau jenis API Key V2." else "Akses ditolak (-2015). IP whitelist / permission / rate-limit sementara."
            -2014 -> "API Key tidak di header (-2014)."
            -1003 -> "Too many requests (-1003). Tunggu beberapa menit."
            else -> "Error V2 [$code]: $msg | $fallback"
        }
    }

    data class IndodaxBalances(
        val total: Map<String, Double>,
        val free: Map<String, Double>,
        val locked: Map<String, Double>
    )

    /** Hasil create / get order yang lebih structured. */
    data class OrderResult(
        val success: Boolean,
        val message: String,
        val orderId: String = "",
        val clientOrderId: String = "",
        val executedQty: Double = 0.0,
        val origQty: Double = 0.0,
        val status: String = ""
    )

    suspend fun getAccount(apiKey: String, secretKey: String): Pair<IndodaxBalances?, String> {
        if (apiKey.isBlank() || secretKey.isBlank()) return null to "API Key / Secret Key kosong."
        val timestamp = serverTimeMs()
        val params = linkedMapOf(
            "timestamp" to timestamp.toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        val (ok, raw) = signedV2Request(apiKey, secretKey, "GET", "/api/v2/account", params, maxRetries = 2)
        if (!ok) return null to raw

        return try {
            val json = JSONObject(raw)
            val balancesArr = json.optJSONArray("balances")
                ?: return null to "Format account V2 tidak sesuai."
            val totalMap = mutableMapOf<String, Double>()
            val freeMap = mutableMapOf<String, Double>()
            val lockedMap = mutableMapOf<String, Double>()
            for (i in 0 until balancesArr.length()) {
                val item = balancesArr.optJSONObject(i) ?: continue
                val asset = item.optString("asset", "").lowercase()
                if (asset.isBlank()) continue
                val free = item.optString("free", "0").toDoubleOrNull() ?: 0.0
                val locked = item.optString("locked", "0").toDoubleOrNull() ?: 0.0
                freeMap[asset] = free
                lockedMap[asset] = locked
                totalMap[asset] = free + locked
            }
            IndodaxBalances(totalMap, freeMap, lockedMap) to "Saldo INDODAX berhasil diperbarui (API V2)."
        } catch (e: Exception) {
            Timber.e(e, "Gagal parse account V2")
            null to "Gagal parse account V2: ${e.localizedMessage}"
        }
    }

    suspend fun openOrders(
        apiKey: String,
        secretKey: String,
        symbol: String? = null
    ): Pair<Boolean, String> {
        if (apiKey.isBlank() || secretKey.isBlank()) return false to "API Key / Secret Key kosong."
        val params = linkedMapOf<String, String>()
        symbol?.takeIf { it.isNotBlank() }?.let {
            params["symbol"] = toOrderSymbol(it)
        }
        params["timestamp"] = serverTimeMs().toString()
        params["recvWindow"] = RECV_WINDOW_MS.toString()
        return signedV2Request(apiKey, secretKey, "GET", "/api/v2/openOrders", params, maxRetries = 2)
    }

    /**
     * GET /api/v2/order — detail order by orderId atau clientOrderId.
     * Return executedQty + status (NEW / PARTIALLY_FILLED / FILLED / CANCELLED / ...).
     */
    suspend fun getOrder(
        apiKey: String,
        secretKey: String,
        symbol: String,
        orderId: String? = null,
        clientOrderId: String? = null
    ): OrderResult {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return OrderResult(false, "API Key / Secret Key kosong.")
        }
        if (orderId.isNullOrBlank() && clientOrderId.isNullOrBlank()) {
            return OrderResult(false, "orderId atau clientOrderId wajib diisi.")
        }

        val params = linkedMapOf(
            "symbol" to toOrderSymbol(symbol),
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        if (!orderId.isNullOrBlank()) {
            params["orderId"] = orderId
        } else if (!clientOrderId.isNullOrBlank()) {
            params["origClientOrderId"] = clientOrderId
        }

        val (ok, raw) = signedV2Request(apiKey, secretKey, "GET", "/api/v2/order", params, maxRetries = 2)
        if (!ok) return OrderResult(false, raw)

        return try {
            val json = JSONObject(raw)
            val oid = json.optString("orderId", json.optLong("orderId", 0L).toString())
            val cid = json.optString("clientOrderId", "")
            val status = json.optString("status", "").uppercase()
            val executed = json.optString("executedQty", "0").toDoubleOrNull() ?: 0.0
            val orig = json.optString("origQty", "0").toDoubleOrNull() ?: 0.0
            OrderResult(
                success = true,
                message = "Order $oid status=$status executed=$executed",
                orderId = oid,
                clientOrderId = cid,
                executedQty = executed,
                origQty = orig,
                status = status
            )
        } catch (e: Exception) {
            Timber.e(e, "Gagal parse getOrder")
            OrderResult(false, "Gagal parse getOrder: ${e.localizedMessage}")
        }
    }

    suspend fun createLimitOrder(
        apiKey: String,
        secretKey: String,
        symbol: String,
        side: String,
        price: Double,
        quantity: Double,
        clientOrderId: String? = null
    ): Pair<Boolean, String> {
        val result = createLimitOrderDetailed(apiKey, secretKey, symbol, side, price, quantity, clientOrderId)
        return result.success to result.message
    }

    /** Versi detailed: return OrderResult (orderId + status + executedQty). */
    suspend fun createLimitOrderDetailed(
        apiKey: String,
        secretKey: String,
        symbol: String,
        side: String,
        price: Double,
        quantity: Double,
        clientOrderId: String? = null
    ): OrderResult {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return OrderResult(false, "API Key / Secret Key kosong.")
        }
        if (price <= 0.0 || quantity <= 0.0) {
            return OrderResult(false, "Harga dan quantity harus > 0.")
        }

        val formattedSymbol = toOrderSymbol(symbol)
        val normalizedSide = side.uppercase()
        if (normalizedSide != "BUY" && normalizedSide != "SELL") {
            return OrderResult(false, "Side harus BUY atau SELL.")
        }

        val params = linkedMapOf(
            "symbol" to formattedSymbol,
            "side" to normalizedSide,
            "type" to "LIMIT",
            "price" to decimal(price, symbol, true),
            "quantity" to decimal(quantity, symbol, false),
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        clientOrderId?.takeIf { it.isNotBlank() }?.let { params["newClientOrderId"] = it.take(36) }

        val (ok, raw) = signedV2Request(apiKey, secretKey, "POST", "/api/v2/order", params, maxRetries = 0)
        if (!ok) return OrderResult(false, raw)

        return try {
            val json = JSONObject(raw)
            val orderId = json.optString("orderId", json.optLong("orderId", 0L).toString())
            val clientId = json.optString("clientOrderId", clientOrderId.orEmpty())
            val status = json.optString("status", "NEW").uppercase()
            val executed = json.optString("executedQty", "0").toDoubleOrNull() ?: 0.0
            val orig = json.optString("origQty", decimal(quantity, symbol, false)).toDoubleOrNull() ?: quantity
            OrderResult(
                success = true,
                message = "Order $normalizedSide $formattedSymbol berhasil. Order ID: $orderId ($clientId)",
                orderId = orderId,
                clientOrderId = clientId,
                executedQty = executed,
                origQty = orig,
                status = status
            )
        } catch (e: Exception) {
            Timber.e(e, "Gagal parse create order response")
            OrderResult(false, "Order terkirim tapi parse gagal: ${e.localizedMessage}")
        }
    }

    suspend fun createMarketOrderDetailed(
        apiKey: String,
        secretKey: String,
        symbol: String,
        side: String,
        quantity: Double,
        clientOrderId: String? = null
    ): OrderResult {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return OrderResult(false, "API Key / Secret Key kosong.")
        }
        if (quantity <= 0.0) {
            return OrderResult(false, "Quantity harus > 0.")
        }

        val formattedSymbol = toOrderSymbol(symbol)
        val normalizedSide = side.uppercase()
        if (normalizedSide != "BUY" && normalizedSide != "SELL") {
            return OrderResult(false, "Side harus BUY atau SELL.")
        }

        val params = linkedMapOf(
            "symbol" to formattedSymbol,
            "side" to normalizedSide,
            "type" to "MARKET",
            "quantity" to decimal(quantity, symbol, false),
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        clientOrderId?.takeIf { it.isNotBlank() }?.let { params["newClientOrderId"] = it.take(36) }

        val (ok, raw) = signedV2Request(apiKey, secretKey, "POST", "/api/v2/order", params, maxRetries = 0)
        if (!ok) return OrderResult(false, raw)

        return try {
            val json = JSONObject(raw)
            val orderId = json.optString("orderId", json.optLong("orderId", 0L).toString())
            val clientId = json.optString("clientOrderId", clientOrderId.orEmpty())
            val status = json.optString("status", "FILLED").uppercase()
            val executed = json.optString("executedQty", "0").toDoubleOrNull() ?: 0.0
            val orig = json.optString("origQty", decimal(quantity, symbol, false)).toDoubleOrNull() ?: quantity
            OrderResult(
                success = true,
                message = "Market Order $normalizedSide $formattedSymbol berhasil. Order ID: $orderId ($clientId)",
                orderId = orderId,
                clientOrderId = clientId,
                executedQty = executed,
                origQty = orig,
                status = status
            )
        } catch (e: Exception) {
            Timber.e(e, "Gagal parse market order response")
            OrderResult(false, "Market Order terkirim tapi parse gagal: ${e.localizedMessage}")
        }
    }

    suspend fun cancelOrder(
        apiKey: String,
        secretKey: String,
        symbol: String,
        orderId: String,
        side: String = ""
    ): Pair<Boolean, String> {
        val formattedSymbol = toOrderSymbol(symbol)
        val params = linkedMapOf(
            "symbol" to formattedSymbol,
            "orderId" to orderId,
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        val (ok, raw) = signedV2Request(apiKey, secretKey, "DELETE", "/api/v2/order", params, maxRetries = 0)
        return if (ok) true to "Order $orderId dibatalkan (V2)." else false to raw
    }

    suspend fun orderHistory(
        apiKey: String,
        secretKey: String,
        symbol: String,
        limit: Int = 100
    ): Pair<Boolean, String> {
        val formattedSymbol = toTradeSymbol(symbol)
        val end = serverTimeMs()
        val start = end - MY_TRADES_MAX_RANGE_MS
        return signedV2Request(
            apiKey,
            secretKey,
            "GET",
            "/api/v2/order/histories",
            linkedMapOf(
                "symbol" to formattedSymbol,
                "limit" to limit.coerceIn(10, 1000).toString(),
                "sort" to "desc",
                "startTime" to start.toString(),
                "endTime" to end.toString(),
                "timestamp" to end.toString(),
                "recvWindow" to RECV_WINDOW_MS.toString()
            ),
            maxRetries = 2
        )
    }

    /**
     * 1 request max 7 hari (sesuai docs).
     * Response resmi: { "data": [ { tradeId, price, qty, isBuyer, time, ... } ] }
     */
    suspend fun myTrades(
        apiKey: String,
        secretKey: String,
        symbol: String,
        limit: Int = 500,
        startTimeMs: Long? = null,
        endTimeMs: Long? = null
    ): Pair<Boolean, String> {
        val formattedSymbol = toTradeSymbol(symbol)
        val end = endTimeMs ?: serverTimeMs()
        val start = startTimeMs ?: (end - MY_TRADES_MAX_RANGE_MS)
        val params = linkedMapOf(
            "symbol" to formattedSymbol,
            "limit" to limit.coerceIn(10, 1000).toString(),
            "sort" to "desc",
            "startTime" to start.toString(),
            "endTime" to end.toString(),
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        return signedV2Request(apiKey, secretKey, "GET", "/api/v2/myTrades", params, maxRetries = 2)
    }

    suspend fun myTradesRecent(
        apiKey: String,
        secretKey: String,
        symbol: String,
        limit: Int = 500
    ): List<JSONObject> {
        val (ok, raw) = myTrades(apiKey, secretKey, symbol, limit)
        if (!ok) return emptyList()
        return parseTradesList(raw)
    }

    fun parseTradesArray(raw: String): JSONArray? {
        val list = parseTradesList(raw)
        if (list.isEmpty()) return null
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr
    }

    /** Parse fleksibel: array langsung, {data}, {trades}, angka/string field. */
    fun parseTradesList(raw: String): List<JSONObject> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            val arr: JSONArray? = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val obj = JSONObject(trimmed)
                    when {
                        obj.has("data") && !obj.isNull("data") -> obj.optJSONArray("data")
                        obj.has("trades") -> obj.optJSONArray("trades")
                        obj.has("return") -> obj.optJSONObject("return")?.optJSONArray("trades")
                        else -> null
                    }
                }
            }
            if (arr == null) return@runCatching emptyList()
            val out = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(it) }
            }
            out.sortedByDescending { tradeTimeMs(it) }
        }.getOrElse { emptyList() }
    }

    fun tradeIdOf(trade: JSONObject): String =
        sequenceOf("tradeId", "trade_id", "id", "tid", "orderId")
            .map { trade.optString(it, "") }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    fun tradePriceOf(trade: JSONObject): Double = jsonNumber(trade, "price")

    fun tradeQtyOf(trade: JSONObject): Double =
        jsonNumber(trade, "qty", "amount", "quantity", "filled")

    fun tradeTimeMs(trade: JSONObject): Long {
        val t = when {
            trade.has("time") -> trade.optLong("time", 0L)
            trade.has("trade_time") -> trade.optLong("trade_time", 0L)
            trade.has("timestamp") -> trade.optLong("timestamp", 0L)
            else -> 0L
        }
        return if (t in 1 until 1_000_000_000_000L) t * 1000L else t
    }

    fun isBuyerOf(trade: JSONObject): Boolean = when {
        trade.has("isBuyer") -> trade.optBoolean("isBuyer", false)
        else -> {
            val type = trade.optString("type", "")
            val side = trade.optString("side", "")
            type.equals("buy", true) || side.equals("BUY", true)
        }
    }

    private fun jsonNumber(obj: JSONObject, vararg keys: String): Double {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val d = obj.optDouble(k, Double.NaN)
            if (d.isFinite() && d != 0.0) return d
            if (d.isFinite() && d == 0.0) {
                // could be real zero; still accept if string parses
            }
            val s = obj.optString(k, "").replace(",", "").toDoubleOrNull()
            if (s != null && s.isFinite()) return s
            if (d.isFinite()) return d
        }
        return 0.0
    }

    private fun decimal(value: Double, symbol: String, isPrice: Boolean): String {
        val meta = agu.analys.util.MarketDataCache(agu.analys.AppContextProvider.context)
            .loadPairsMetadata()
            .find { it.symbol.equals(symbol.replace("_", ""), ignoreCase = true) }
        val decimals = if (isPrice) (meta?.priceDecimals ?: 0) else (meta?.quantityDecimals ?: 8)
        return java.math.BigDecimal.valueOf(value)
            .setScale(decimals, java.math.RoundingMode.DOWN)
            .toPlainString()
    }
}
