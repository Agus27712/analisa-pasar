package agu.analys.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * INDODAX Trade API 2.0 ONLY.
 * Fetch sengaja pelan: 1x account, myTrades max 1 window 7 hari.
 * 
 * Arsitektur:
 * - Singleton Object: Untuk akses global yang stateless.
 * - OkHttp: Digunakan untuk request sinkron di dalam withContext(Dispatchers.IO).
 * - HMAC-SHA256: Digunakan untuk signing request sesuai standar API V2 Indodax.
 * - Error Mapping: Mengubah kode error API menjadi pesan yang dapat dipahami user.
 */
object IndodaxTradeApiV2 {
    private const val V2_BASE_URL = "https://api.indodax.com"
    private const val SERVER_TIME_URL = "https://indodax.com/api/server_time"
    private const val RECV_WINDOW_MS = 10_000L
    /** Docs: interval startTime–endTime max 7 hari. */
    private const val MY_TRADES_MAX_RANGE_MS = 7L * 24 * 60 * 60 * 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun encodeQuery(params: LinkedHashMap<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${it.value}" }

    fun toTradeSymbol(symbol: String): String =
        IndodaxMarketService.toPairId(symbol).replace("_", "").lowercase()

    fun toOrderSymbol(symbol: String): String =
        IndodaxMarketService.toPairId(symbol).replace("_", "").uppercase()

    private suspend fun serverTimeMs(): Long = withContext(Dispatchers.IO) {
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
                if (raw <= 0L) System.currentTimeMillis()
                else if (raw < 1_000_000_000_000L) raw * 1000L else raw
            }
        } catch (e: Exception) {
            Timber.e(e, "Gagal mengambil server time Indodax")
            System.currentTimeMillis()
        }
    }

    private suspend fun signedV2Request(
        apiKey: String,
        secretKey: String,
        method: String,
        path: String,
        params: LinkedHashMap<String, String>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val payloadString = encodeQuery(params)
        val sign = hmacSha256(secretKey, payloadString)
        val fullUrl = "$V2_BASE_URL$path"

        val request = when (method.uppercase()) {
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
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val json = try { JSONObject(responseBody) } catch (_: Exception) { null }
                val hasErrorCode = json != null && json.has("code") && json.optInt("code", 0) != 0
                if (response.isSuccessful && !hasErrorCode) {
                    true to responseBody
                } else {
                    val errorMsg = mapV2Error(json, responseBody.ifBlank { response.message })
                    Timber.w("V2 Request Failed: $path | $errorMsg")
                    false to errorMsg
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Trade API V2 network error")
            false to "Trade API V2 network error: ${e.localizedMessage}"
        }
    }

    private fun mapV2Error(json: JSONObject?, fallback: String): String {
        val code = json?.optInt("code", 0) ?: 0
        val msg = json?.optString("msg", fallback).orEmpty()
        return when (code) {
            -1002 -> "Invalid credentials (-1002). Cek API Key TAPIv2 (bukan V1)."
            -1021 -> "Timestamp invalid (-1021). Sinkronkan jam HP."
            -1022 -> "Signature invalid (-1022). Secret Key salah."
            -1121 -> "Invalid symbol (-1121)."
            -2015 -> "Akses ditolak (-2015). IP whitelist / permission / rate-limit sementara. Jangan spam refresh."
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

    suspend fun getAccount(apiKey: String, secretKey: String): Pair<IndodaxBalances?, String> {
        if (apiKey.isBlank() || secretKey.isBlank()) return null to "API Key / Secret Key kosong."
        val timestamp = serverTimeMs()
        val params = linkedMapOf(
            "timestamp" to timestamp.toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        val (ok, raw) = signedV2Request(apiKey, secretKey, "GET", "/api/v2/account", params)
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
        return signedV2Request(apiKey, secretKey, "GET", "/api/v2/openOrders", params)
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
        if (apiKey.isBlank() || secretKey.isBlank()) return false to "API Key / Secret Key kosong."
        if (price <= 0.0 || quantity <= 0.0) return false to "Harga dan quantity harus > 0."

        val formattedSymbol = toOrderSymbol(symbol)
        val normalizedSide = side.uppercase()
        if (normalizedSide != "BUY" && normalizedSide != "SELL") {
            return false to "Side harus BUY atau SELL."
        }

        val params = linkedMapOf(
            "symbol" to formattedSymbol,
            "side" to normalizedSide,
            "type" to "LIMIT",
            "price" to decimal(price),
            "quantity" to decimal(quantity),
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        clientOrderId?.takeIf { it.isNotBlank() }?.let { params["newClientOrderId"] = it.take(36) }

        val (ok, raw) = signedV2Request(apiKey, secretKey, "POST", "/api/v2/order", params)
        if (!ok) return false to raw

        val json = JSONObject(raw)
        val orderId = json.optLong("orderId", 0L)
        val clientId = json.optString("clientOrderId", "-")
        return true to "Order $normalizedSide $formattedSymbol berhasil. Order ID: $orderId ($clientId)"
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
        val (ok, raw) = signedV2Request(apiKey, secretKey, "DELETE", "/api/v2/order", params)
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
            )
        )
    }

    /**
     * 1 window max 7 hari (sesuai docs).
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
        return signedV2Request(apiKey, secretKey, "GET", "/api/v2/myTrades", params)
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

    private fun decimal(value: Double): String =
        java.math.BigDecimal.valueOf(value)
            .setScale(8, java.math.RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
}
