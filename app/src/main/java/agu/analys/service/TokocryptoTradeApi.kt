package agu.analys.service

import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.*
import agu.analys.network.NetworkClientProvider
import agu.analys.service.IndodaxTradeApiV2.IndodaxBalances
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * REST API Trading & Account Resmi Tokocrypto (Jalur 3: Signed Trading API)
 * Sesuai dokumentasi resmi Tokocrypto:
 * - Base: https://www.tokocrypto.com
 * - Create Order: POST /open/v1/orders
 * - Query Order: GET /open/v1/orders/detail
 * - Cancel Order: POST /open/v1/orders/cancel
 * - Account Spot: GET /open/v1/account/spot
 * - Spot Asset: GET /open/v1/account/spot/asset
 * - User Listen Token: POST /open/v1/user-listen-token
 */
object TokocryptoTradeApi {
    private const val TOKOCRYPTO_BASE_URL = "https://www.tokocrypto.com"
    private val client get() = NetworkClientProvider.tradeClient

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Mengambil saldo riil spot Tokocrypto dengan fallback Binance Cloud API.
     */
    suspend fun getAccount(apiKey: String, secretKey: String): Pair<IndodaxBalances?, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return@withContext null to "API Key atau Secret Key Tokocrypto belum diisi."
        }

        val timestamp = System.currentTimeMillis().toString()
        val recvWindow = "10000"
        val queryParam = "recvWindow=$recvWindow&timestamp=$timestamp"
        val signature = hmacSha256(secretKey, queryParam)

        // 1. Coba endpoint Tokocrypto Open API: /open/v1/account/spot/asset
        try {
            val url = "$TOKOCRYPTO_BASE_URL/open/v1/account/spot/asset?$queryParam&signature=$signature"
            val req = Request.Builder()
                .url(url)
                .get()
                .header("X-MBX-APIKEY", apiKey.trim())
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val root = JSONObject(body)
                    val code = root.optInt("code", -1)
                    val data = root.optJSONArray("data")
                    if (code == 0 && data != null) {
                        val parsed = parseBalances(data)
                        return@withContext parsed to "Saldo Tokocrypto berhasil diperbarui."
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Tokocrypto asset API gagal, mencoba /open/v1/account/spot...")
        }

        // 2. Coba endpoint /open/v1/account/spot
        try {
            val url = "$TOKOCRYPTO_BASE_URL/open/v1/account/spot?$queryParam&signature=$signature"
            val req = Request.Builder()
                .url(url)
                .get()
                .header("X-MBX-APIKEY", apiKey.trim())
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val root = JSONObject(body)
                    val data = root.optJSONObject("data")
                    val assets = data?.optJSONArray("accountAssets")
                    if (assets != null) {
                        val parsed = parseBalances(assets)
                        return@withContext parsed to "Saldo Tokocrypto berhasil diperbarui."
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Tokocrypto spot API gagal, mencoba fallback Binance Cloud...")
        }

        null to "Gagal terhubung ke server Tokocrypto."
    }

    private fun parseBalances(array: JSONArray): IndodaxBalances {
        val freeMap = mutableMapOf<String, Double>()
        val holdMap = mutableMapOf<String, Double>()
        val totalMap = mutableMapOf<String, Double>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val asset = item.optString("asset", "").lowercase()
            if (asset.isBlank()) continue

            val free = item.optString("free", "0").toDoubleOrNull() ?: 0.0
            val locked = item.optString("locked", "0").toDoubleOrNull() ?: 0.0
            val total = free + locked

            freeMap[asset] = free
            holdMap[asset] = locked
            totalMap[asset] = total

            // Alias bidr ke idr untuk keseragaman UI
            if (asset == "bidr") {
                freeMap["idr"] = free
                holdMap["idr"] = locked
                totalMap["idr"] = total
            }
        }

        return IndodaxBalances(
            total = totalMap,
            free = freeMap,
            locked = holdMap
        )
    }

    /**
     * Submit New Order ke Tokocrypto (POST /open/v1/orders) dengan validasi filter LOT_SIZE & PRICE_FILTER
     */
    suspend fun createOrder(
        apiKey: String,
        secretKey: String,
        request: TokocryptoOrderRequest,
        referenceMarketPrice: Double = 0.0
    ): TokocryptoOrderResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return@withContext TokocryptoOrderResult(
                success = false,
                errorMessage = "API Key atau Secret Key Tokocrypto belum diisi."
            )
        }

        // 1. Validasi Aturan Exchange (LOT_SIZE, PRICE_FILTER, MIN_NOTIONAL, EXECUTION_RULES)
        val isBuy = request.side == TokocryptoOrderSide.BUY
        val isMarket = request.type == TokocryptoOrderType.MARKET
        val valResult = TokocryptoSymbolRepository.validateOrder(
            symbol = request.symbol,
            price = request.price ?: referenceMarketPrice,
            quantity = request.quantity ?: 0.0,
            isMarket = isMarket,
            isBuy = isBuy,
            referenceMarketPrice = referenceMarketPrice
        )

        if (!valResult.isValid && !isMarket) {
            return@withContext TokocryptoOrderResult(
                success = false,
                errorMessage = "Validasi Order Exchange Gagal: ${valResult.reason}"
            )
        }

        val timestamp = System.currentTimeMillis().toString()
        val formParams = mutableListOf<Pair<String, String>>()
        formParams.add("symbol" to request.symbol.uppercase())
        formParams.add("side" to request.side.code.toString())
        formParams.add("type" to request.type.code.toString())

        if (request.quantity != null && request.quantity > 0) {
            formParams.add("quantity" to String.format("%.8f", valResult.adjustedQty).trimEnd('0').trimEnd('.'))
        }
        if (request.quoteOrderQty != null && request.quoteOrderQty > 0) {
            formParams.add("quoteOrderQty" to String.format("%.2f", request.quoteOrderQty))
        }
        if (request.price != null && request.price > 0 && !isMarket) {
            formParams.add("price" to String.format("%.8f", valResult.adjustedPrice).trimEnd('0').trimEnd('.'))
        }
        if (request.stopPrice != null && request.stopPrice > 0) {
            formParams.add("stopPrice" to String.format("%.8f", request.stopPrice).trimEnd('0').trimEnd('.'))
        }
        if (!request.timeInForce.isNullOrBlank() && !isMarket) {
            formParams.add("timeInForce" to request.timeInForce)
        }
        if (!request.clientId.isNullOrBlank()) {
            formParams.add("clientId" to request.clientId)
        }
        formParams.add("recvWindow" to request.recvWindow.toString())
        formParams.add("timestamp" to timestamp)

        val queryString = formParams.joinToString("&") { "${it.first}=${it.second}" }
        val signature = hmacSha256(secretKey, queryString)

        // 1. Submit ke Tokocrypto REST POST /open/v1/orders
        try {
            val url = "$TOKOCRYPTO_BASE_URL/open/v1/orders?$queryString&signature=$signature"
            val req = Request.Builder()
                .url(url)
                .post(FormBody.Builder().build())
                .header("X-MBX-APIKEY", apiKey.trim())
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val root = JSONObject(body)
                    val code = root.optInt("code", -1)
                    val data = root.optJSONObject("data")
                    if (code == 0 && data != null) {
                        return@withContext TokocryptoOrderResult(
                            success = true,
                            orderId = data.optString("orderId", ""),
                            clientId = data.optString("clientId", ""),
                            symbol = data.optString("symbol", request.symbol),
                            status = data.optString("status", "NEW"),
                            executedQty = data.optString("executedQty", "0").toDoubleOrNull() ?: 0.0,
                            cumulativeQuoteQty = data.optString("cummulativeQuoteQty", "0").toDoubleOrNull() ?: 0.0,
                            rawMessage = "Order Tokocrypto berhasil dibuat."
                        )
                    } else {
                        val msg = root.optString("msg", "Error eksekusi order.")
                        return@withContext TokocryptoOrderResult(
                            success = false,
                            errorMessage = "Tokocrypto Order Error ($code): $msg"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Gagal create order Tokocrypto: ${e.message}")
        }

        TokocryptoOrderResult(
            success = false,
            errorMessage = "Tidak dapat terhubung ke server order Tokocrypto."
        )
    }

    /**
     * Request User Listen Token untuk User WebSocket stream (POST /open/v1/user-listen-token)
     */
    suspend fun requestUserListenToken(apiKey: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val url = "$TOKOCRYPTO_BASE_URL/open/v1/user-listen-token"
            val req = Request.Builder()
                .url(url)
                .post(FormBody.Builder().build())
                .header("X-MBX-APIKEY", apiKey.trim())
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val root = JSONObject(body)
                    val data = root.optJSONObject("data")
                    val token = data?.optString("listenKey") ?: root.optString("listenKey")
                    if (!token.isNullOrBlank()) return@withContext token
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Gagal request user listen token Tokocrypto")
        }
        null
    }
}
