package agu.analys.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * INDODAX Trade API 2.0 Integration.
 *
 * Exclusively uses Trade API V2:
 * - Base URL: https://api.indodax.com
 * - Headers: X-APIKEY, Sign (HMAC-SHA256)
 * - Endpoints: /api/v2/account, /api/v2/order, /api/v2/order/histories, /api/v2/myTrades
 */
object IndodaxTradeApiV2 {
    private const val V2_BASE_URL = "https://api.indodax.com"
    private const val SERVER_TIME_URL = "https://indodax.com/api/server_time"
    private const val RECV_WINDOW_MS = 10_000L

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

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

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

    private suspend fun signedV2Request(
        apiKey: String,
        secretKey: String,
        method: String,
        path: String,
        params: LinkedHashMap<String, String>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val payloadString = encodeQuery(params)
        val sign = hmacSha256(secretKey, payloadString)

        val requestBuilder = Request.Builder()
            .header("X-APIKEY", apiKey.trim())
            .header("Sign", sign)
            .header("Accept", "application/json")

        val fullUrl = "$V2_BASE_URL$path"

        when (method.uppercase()) {
            "GET" -> {
                requestBuilder.url("$fullUrl?$payloadString")
                requestBuilder.get()
            }
            "DELETE" -> {
                requestBuilder.url("$fullUrl?$payloadString")
                requestBuilder.delete()
            }
            "POST" -> {
                requestBuilder.url(fullUrl) // Params are in body for POST
                val formBody = FormBody.Builder()
                params.forEach { (key, value) -> formBody.add(key, value) }
                requestBuilder.post(formBody.build())
                requestBuilder.header("Content-Type", "application/x-www-form-urlencoded")
            }
        }

        runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(responseBody) }.getOrNull()
                
                if (response.isSuccessful && (json == null || !json.has("code"))) {
                    // API v2 returns the successful payload directly, often without a wrapper
                    true to responseBody
                } else {
                    false to mapV2Error(json, responseBody)
                }
            }
        }.getOrElse { false to "Trade API V2 network error: ${it.localizedMessage}" }
    }

    private fun mapV2Error(json: JSONObject?, fallback: String): String {
        val code = json?.optInt("code", 0) ?: 0
        val msg = json?.optString("msg", fallback).orEmpty()
        return when (code) {
            -1002 -> "Invalid credentials (-1002). Cek kembali API Key TAPIv2 Anda. Pastikan itu key V2, bukan V1."
            -1021 -> "Timestamp invalid (-1021). Waktu tidak sinkron."
            -1022 -> "Signature invalid (-1022). Secret Key salah / format tidak valid."
            -2015 -> "Akses Ditolak (-2015). Pastikan IP HP Anda tanpa VPN sudah di-whitelist di Indodax, dan API Key adalah tipe V2."
            -2014 -> "API Key tidak ditemukan di header (-2014)."
            else -> "Error V2 [$code]: $msg | Raw: $fallback"
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
            val balancesArr = json.optJSONArray("balances") ?: return@runCatching null to "Format account V2 tidak sesuai."
            val result = mutableMapOf<String, Double>()
            
            for (i in 0 until balancesArr.length()) {
                val item = balancesArr.optJSONObject(i) ?: continue
                val asset = item.optString("asset", "").lowercase()
                val free = item.optString("free", "0").toDoubleOrNull() ?: 0.0
                val locked = item.optString("locked", "0").toDoubleOrNull() ?: 0.0
                val total = free + locked
                if (total > 0.0 || total == 0.0 && total.toString() == "0.0") {
                    result[asset] = total
                }
            }
            result to "Saldo INDODAX berhasil diperbarui (API V2)."
        }.getOrElse { null to "Gagal membaca response V2 account: ${it.localizedMessage}" }
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
        if (price <= 0.0 || quantity <= 0.0) return false to "Harga dan quantity harus lebih dari 0."

        val formattedSymbol = IndodaxMarketService.toPairId(symbol).replace("_", "").uppercase() // BTCIDR
        val normalizedSide = side.uppercase() // BUY or SELL
        if (normalizedSide != "BUY" && normalizedSide != "SELL") return false to "Side harus BUY atau SELL."

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
        side: String // TAPI v1 needed side, v2 doesn't strictly need it but keeping compat
    ): Pair<Boolean, String> {
        val formattedSymbol = IndodaxMarketService.toPairId(symbol).replace("_", "").uppercase()
        val params = linkedMapOf(
            "symbol" to formattedSymbol,
            "orderId" to orderId,
            "timestamp" to serverTimeMs().toString(),
            "recvWindow" to RECV_WINDOW_MS.toString()
        )
        val (ok, raw) = signedV2Request(apiKey, secretKey, "DELETE", "/api/v2/order", params)
        return if (ok) true to "Order $orderId berhasil dibatalkan (API V2)." else false to raw
    }

    suspend fun orderHistory(
        apiKey: String,
        secretKey: String,
        symbol: String,
        limit: Int = 100
    ): Pair<Boolean, String> {
        val formattedSymbol = IndodaxMarketService.toPairId(symbol).replace("_", "").uppercase()
        return signedV2Request(
            apiKey,
            secretKey,
            "GET",
            "/api/v2/order/histories",
            linkedMapOf(
                "symbol" to formattedSymbol,
                "limit" to limit.coerceIn(10, 1000).toString(),
                "sort" to "desc",
                "timestamp" to serverTimeMs().toString(),
                "recvWindow" to RECV_WINDOW_MS.toString()
            )
        )
    }

    suspend fun myTrades(
        apiKey: String,
        secretKey: String,
        symbol: String,
        limit: Int = 100
    ): Pair<Boolean, String> {
        val formattedSymbol = IndodaxMarketService.toPairId(symbol).replace("_", "").uppercase()
        return signedV2Request(
            apiKey,
            secretKey,
            "GET",
            "/api/v2/myTrades",
            linkedMapOf(
                "symbol" to formattedSymbol,
                "limit" to limit.coerceIn(10, 1000).toString(),
                "sort" to "desc",
                "timestamp" to serverTimeMs().toString(),
                "recvWindow" to RECV_WINDOW_MS.toString()
            )
        )
    }

    @Suppress("DEPRECATION")
    suspend fun tradeHistoryLegacy(
        apiKey: String,
        secretKey: String,
        pair: String,
        count: Int = 1000
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            val nonce = System.currentTimeMillis().toString()
            val formattedPair = IndodaxMarketService.toPairId(pair).lowercase()
            
            val postBody = "method=tradeHistory&nonce=$nonce&pair=$formattedPair&count=$count"
            val sign = hmacSha512(secretKey, postBody)
            
            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val body = postBody.toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url("https://indodax.com/tapi")
                .header("Key", apiKey.trim())
                .header("Sign", sign)
                .post(body)
                .build()
                
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    true to responseBody
                } else {
                    false to responseBody
                }
            }
        }.getOrElse {
            false to (it.message ?: "Unknown error")
        }
    }

    private fun decimal(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

