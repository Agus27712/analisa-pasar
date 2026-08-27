package agu.analys.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * INDODAX Trade API 2.0 ONLY.
 * Fetch sengaja pelan: 1x account, myTrades max 1 window 7 hari.
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
        runCatching {
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
        }.getOrElse { System.currentTimeMillis() }
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

        runCatching {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(responseBody) }.getOrNull()
                val hasErrorCode = json != null && json.has("code") && json.optInt("code", 0) != 0
                if (response.isSuccessful && !hasErrorCode) {
                    true to responseBody
                } else {
                    false to mapV2Error(json, responseBody.ifBlank { response.message })
                }
            }
        }.getOrElse { false to "Trade API V2 network error: ${it.localizedMessage}" }
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

    suspend fun getAccount(apiKey: String, secretKey: String): Pair<Map<String, Double>?, String> {
        if (apiKey.isBlank() || secretKey.isBlank()) return null to "API Key / Secret Key kosong."
        val timestamp = serverTimeMs()
        val params = linkedMapOf(
            "timestamp" to timestamp.toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        val (ok, raw) = signedV2Request(apiKey, secretKey, "GET", "/api/v2/account", params)
        if (!ok) return null to raw

        return runCatching {
            val json = JSONObject(raw)
            val balancesArr = json.optJSONArray("balances")
                ?: return@runCatching null to "Format account V2 tidak sesuai."
            val result = mutableMapOf<String, Double>()
            for (i in 0 until balancesArr.length()) {
                val item = balancesArr.optJSONObject(i) ?: continue
                val asset = item.optString("asset", "").lowercase()
                if (asset.isBlank()) continue
                val free = item.optString("free", "0").toDoubleOrNull() ?: 0.0
                val locked = item.optString("locked", "0").toDoubleOrNull() ?: 0.0
                result[asset] = free + locked
            }
            result to "Saldo INDODAX berhasil diperbarui (API V2)."
        }.getOrElse { null to "Gagal parse account V2: ${it.localizedMessage}" }
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

    /** 1 request saja (7 hari). */
    suspend fun myTradesRecent(
        apiKey: String,
        secretKey: String,
        symbol: String,
        limit: Int = 500
    ): List<JSONObject> {
        val (ok, raw) = myTrades(apiKey, secretKey, symbol, limit)
        if (!ok) return emptyList()
        val arr = parseTradesArray(raw) ?: return emptyList()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(it) }
        }
        list.sortByDescending { it.optLong("time", 0L) }
        return list
    }

    fun parseTradesArray(raw: String): JSONArray? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val obj = JSONObject(trimmed)
                    when {
                        obj.has("data") -> obj.optJSONArray("data")
                        obj.has("trades") -> obj.optJSONArray("trades")
                        obj.has("return") -> obj.optJSONObject("return")?.optJSONArray("trades")
                        else -> null
                    }
                }
            }
        }.getOrNull()
    }

    private fun decimal(value: Double): String =
        java.math.BigDecimal.valueOf(value)
            .setScale(8, java.math.RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
}
