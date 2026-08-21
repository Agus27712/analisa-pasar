package agu.analys.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * INDODAX current Trade API integration.
 *
 * Important: INDODAX's current API is hybrid:
 * - Account / create trade / cancel order remain POST /tapi (HMAC-SHA512).
 * - Order History and Trade History use the dedicated Trade API 2.0 endpoints
 *   on https://tapi.indodax.com (HMAC-SHA512 over query string).
 *
 * Do not use the non-existent /api/v2/order or /api/v2/account endpoints.
 */
object IndodaxTradeApiV2 {
    private const val TAPI_URL = "https://indodax.com/tapi"
    private const val V2_BASE_URL = "https://tapi.indodax.com"
    private const val SERVER_TIME_URL = "https://indodax.com/api/server_time"
    private const val RECV_WINDOW_MS = 5_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun hmacSha512(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA512"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun formString(params: LinkedHashMap<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${it.value}" }

    private fun encodeQuery(params: LinkedHashMap<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${it.value}" }

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

    private suspend fun signedTapi(
        apiKey: String,
        secretKey: String,
        params: LinkedHashMap<String, String>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val body = formString(params)
        val sign = hmacSha512(secretKey, body)
        val request = Request.Builder()
            .url(TAPI_URL)
            .post(FormBody.Builder().apply {
                params.forEach { (key, value) -> add(key, value) }
            }.build())
            .header("Key", apiKey.trim())
            .header("Sign", sign)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(responseBody) }.getOrNull()
                if (!response.isSuccessful) {
                    return@withContext false to "INDODAX HTTP ${response.code}: ${responseBody.take(220)}"
                }
                if (json?.optInt("success", 0) == 1) {
                    true to responseBody
                } else {
                    false to mapLegacyError(json, responseBody)
                }
            }
        }.getOrElse { false to "INDODAX network error: ${it.localizedMessage ?: it.javaClass.simpleName}" }
    }

    private fun mapLegacyError(json: JSONObject?, fallback: String): String {
        val code = json?.optString("error_code", "").orEmpty()
        val error = json?.optString("error", fallback).orEmpty()
        return when (code) {
            "invalid_credentials" -> "Credential INDODAX invalid/expired. Pastikan API Key + Secret benar dan API key masih aktif."
            "invalid_nonce" -> "Nonce invalid. Gunakan timestamp millisecond dan jangan kirim request signed paralel dengan nonce lama."
            "invalid_timestamp" -> "Timestamp invalid. Jam perangkat/server terlalu jauh dari waktu INDODAX."
            "invalid_parameter" -> "Parameter order invalid: $error"
            "too_many_requests" -> "Terlalu banyak request trading. INDODAX memberi blok sementara pada pair/account."
            else -> "INDODAX error${if (code.isNotBlank()) " [$code]" else ""}: $error"
        }
    }

    suspend fun getAccount(apiKey: String, secretKey: String): Pair<Map<String, Double>?, String> {
        if (apiKey.isBlank() || secretKey.isBlank()) return null to "API Key / Secret Key kosong."
        val timestamp = serverTimeMs()
        val params = linkedMapOf(
            "method" to "getInfo",
            "timestamp" to timestamp.toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        val (ok, raw) = signedTapi(apiKey, secretKey, params)
        if (!ok) return null to raw

        return runCatching {
            val json = JSONObject(raw)
            val ret = json.optJSONObject("return") ?: return@runCatching null to "Response getInfo tidak memiliki return."
            val balance = ret.optJSONObject("balance") ?: JSONObject()
            val frozen = ret.optJSONObject("balance_hold") ?: ret.optJSONObject("frozen_balance") ?: JSONObject()
            val assets = mutableSetOf<String>()
            balance.keys().forEach { assets += it.lowercase() }
            frozen.keys().forEach { assets += it.lowercase() }
            val result = assets.associateWith { asset ->
                val available = balance.optString(asset, "0").toDoubleOrNull() ?: 0.0
                val locked = frozen.optString(asset, "0").toDoubleOrNull() ?: 0.0
                available + locked
            }.filterValues { it > 0.0 || it == 0.0 && it.toString() == "0.0" }
            result to "Saldo INDODAX berhasil diperbarui."
        }.getOrElse { null to "Gagal membaca response getInfo: ${it.localizedMessage}" }
    }

    /**
     * Creates a LIMIT order through the supported trade method.
     * For BUY LIMIT, INDODAX requires the base-asset quantity (e.g. btc),
     * not an IDR quote amount when order_type=limit.
     */
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
        if (price <= 0.0 || quantity <= 0.0) return false to "Harga dan quantity harus lebih dari 0."

        val pair = IndodaxMarketService.toPairId(symbol)
        val baseAsset = pair.substringBefore("_")
        val normalizedSide = side.lowercase()
        if (normalizedSide != "buy" && normalizedSide != "sell") return false to "Side harus BUY atau SELL."

        val params = linkedMapOf(
            "method" to "trade",
            "pair" to pair,
            "type" to normalizedSide,
            "price" to decimal(price),
            baseAsset to decimal(quantity),
            "order_type" to "limit",
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        clientOrderId?.takeIf { it.isNotBlank() }?.let { params["client_order_id"] = it.take(36) }
        params["smp_cancel"] = "MAKER"

        val (ok, raw) = signedTapi(apiKey, secretKey, params)
        if (!ok) return false to raw
        val json = JSONObject(raw)
        val ret = json.optJSONObject("return") ?: JSONObject()
        val orderId = ret.optString("order_id", "-")
        val clientId = ret.optString("client_order_id", clientOrderId ?: "-")
        return true to "Order $normalizedSide $pair berhasil. Order ID: $orderId ($clientId)"
    }

    /** Cancel uses the supported /tapi cancelOrder method. */
    suspend fun cancelOrder(
        apiKey: String,
        secretKey: String,
        symbol: String,
        orderId: String,
        side: String
    ): Pair<Boolean, String> {
        val params = linkedMapOf(
            "method" to "cancelOrder",
            "pair" to IndodaxMarketService.toPairId(symbol),
            "order_id" to orderId,
            "type" to side.lowercase(),
            "order_type" to "limit",
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        val (ok, raw) = signedTapi(apiKey, secretKey, params)
        return if (ok) true to "Order $orderId berhasil dibatalkan." else false to raw
    }

    /** Trade API 2.0 dedicated Order History endpoint. */
    suspend fun orderHistory(
        apiKey: String,
        secretKey: String,
        symbol: String,
        limit: Int = 100
    ): Pair<Boolean, String> = signedV2Get(
        apiKey,
        secretKey,
        "/api/v2/order/histories",
        linkedMapOf(
            "symbol" to IndodaxMarketService.toDepthPairId(symbol),
            "limit" to limit.coerceIn(10, 1000).toString(),
            "sort" to "desc",
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
    )

    /** Trade API 2.0 dedicated Trade History endpoint. */
    suspend fun myTrades(
        apiKey: String,
        secretKey: String,
        symbol: String,
        limit: Int = 100
    ): Pair<Boolean, String> = signedV2Get(
        apiKey,
        secretKey,
        "/api/v2/myTrades",
        linkedMapOf(
            "symbol" to IndodaxMarketService.toDepthPairId(symbol),
            "limit" to limit.coerceIn(10, 1000).toString(),
            "sort" to "desc",
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
    )

    private suspend fun signedV2Get(
        apiKey: String,
        secretKey: String,
        path: String,
        params: LinkedHashMap<String, String>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val query = encodeQuery(params)
        val sign = hmacSha512(secretKey, query)
        val request = Request.Builder()
            .url(V2_BASE_URL + path + "?" + query)
            .get()
            .header("X-APIKEY", apiKey.trim())
            .header("Sign", sign)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) true to body
                else false to "Trade API 2.0 HTTP ${response.code}: ${body.take(220)}"
            }
        }.getOrElse { false to "Trade API 2.0 network error: ${it.localizedMessage}" }
    }

    private fun decimal(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
