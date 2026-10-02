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
import java.math.BigDecimal
import java.math.RoundingMode
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * REST API Trading & Account Resmi Tokocrypto (Jalur 3: Signed Trading API)
 * Sesuai dokumentasi resmi Tokocrypto:
 * - Base: https://www.tokocrypto.com
 * - Create Order: POST /open/v1/orders  (symbol format: BTC_USDT / BTC_IDR)
 * - Query Order: GET /open/v1/orders/detail
 * - Cancel Order: POST /open/v1/orders/cancel
 * - Account Spot: GET /open/v1/account/spot
 * - Spot Asset: GET /open/v1/account/spot/asset
 * - User Listen Token: POST /open/v1/user-listen-token
 *
 * Semua request HANYA ke server Tokocrypto. Tidak ada fallback ke Binance:
 * API key Tokocrypto tidak berlaku di Binance dan tidak boleh dikirim ke sana.
 */
object TokocryptoTradeApi {
    private const val TOKOCRYPTO_BASE_URL = "https://www.tokocrypto.com"
    // /open/v1/account/spot/asset sengaja TIDAK dipakai: endpoint itu mewajibkan parameter `asset`
    // (saldo satu aset saja), sedangkan getAccount() butuh semua aset sekaligus.
    private val ACCOUNT_ENDPOINTS = listOf(
        "/open/v1/account/spot"
    )
    private val client get() = NetworkClientProvider.tradeClient

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Format angka untuk parameter API: selalu titik desimal (tidak tergantung bahasa HP),
     * tanpa notasi ilmiah, tanpa nol di belakang.
     * `String.format("%.8f")` memakai locale perangkat, jadi di HP berbahasa Indonesia
     * hasilnya "0,16" (koma) dan ditolak exchange.
     */
    private fun formatParam(value: Double, scale: Int, mode: RoundingMode): String =
        BigDecimal.valueOf(value).setScale(scale, mode).stripTrailingZeros().toPlainString()

    /**
     * Ringkas error dari respons Tokocrypto (HTTP error maupun code != 0),
     * misal "HTTP 400, kode -1022: Signature for this request is not valid".
     * Teks "HTTP 429" dipertahankan agar deteksi rate-limit di coordinator tetap jalan.
     */
    private fun describeError(httpCode: Int, root: JSONObject?): String {
        val code = if (root != null && root.has("code")) root.optInt("code") else null
        val msg = root?.optString("msg", "")?.takeIf { it.isNotBlank() }
            ?: root?.optString("message", "")?.takeIf { it.isNotBlank() }
        return buildString {
            append("HTTP $httpCode")
            if (code != null) append(", kode $code")
            if (!msg.isNullOrBlank()) append(": $msg")
        }
    }

    /** data bisa berupa array (spot/asset) atau object berisi accountAssets (account/spot). */
    private fun extractAssets(root: JSONObject): JSONArray? {
        return when (val data = root.opt("data")) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("accountAssets")
            else -> null
        }
    }

    /**
     * Mengambil saldo riil spot Tokocrypto.
     * Mencoba /open/v1/account/spot/asset lalu /open/v1/account/spot (keduanya Tokocrypto).
     * Jika gagal, pesan error asli dari Tokocrypto ikut dikembalikan.
     */
    suspend fun getAccount(apiKey: String, secretKey: String): Pair<IndodaxBalances?, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return@withContext null to "API Key atau Secret Key Tokocrypto belum diisi."
        }

        val timestamp = System.currentTimeMillis().toString()
        val recvWindow = "10000"
        val queryParam = "recvWindow=$recvWindow&timestamp=$timestamp"
        val signature = hmacSha256(secretKey, queryParam)

        var apiError: String? = null
        var networkError: String? = null

        for (path in ACCOUNT_ENDPOINTS) {
            try {
                val req = Request.Builder()
                    .url("$TOKOCRYPTO_BASE_URL$path?$queryParam&signature=$signature")
                    .get()
                    .header("X-MBX-APIKEY", apiKey.trim())
                    .header("Accept", "application/json")
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val root = runCatching { JSONObject(body) }.getOrNull()
                    val assets = root?.let { extractAssets(it) }
                    if (resp.isSuccessful && root != null && root.optInt("code", 0) == 0 && assets != null) {
                        return@withContext parseBalances(assets) to "Saldo Tokocrypto berhasil diperbarui."
                    }
                    // Simpan error endpoint pertama (paling relevan), jangan ditimpa endpoint berikutnya
                    if (apiError == null) apiError = describeError(resp.code, root)
                    Timber.w("Tokocrypto $path gagal: $apiError")
                }
            } catch (e: Exception) {
                Timber.w(e, "Tokocrypto $path error koneksi: ${e.message}")
                networkError = e.message ?: e.javaClass.simpleName
            }
        }

        val failure = when {
            apiError != null -> "Error Tokocrypto: $apiError"
            networkError != null -> "Gagal terhubung ke server Tokocrypto ($networkError)."
            else -> "Gagal terhubung ke server Tokocrypto."
        }
        null to failure
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
     * Submit New Order ke Tokocrypto (POST /open/v1/orders) dengan validasi filter LOT_SIZE & PRICE_FILTER.
     * Symbol dikirim dalam format Tokocrypto (BTC_USDT / BTC_IDR), apa pun format input-nya.
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
// =========================================================
// 1. Pastikan metadata symbol tersedia sebelum real order
// =========================================================
val isBuy = request.side == TokocryptoOrderSide.BUY
val isMarket = request.type == TokocryptoOrderType.MARKET

var symbolInfo =
    TokocryptoSymbolRepository.getSymbolInfo(request.symbol)

val hasRequiredLotFilter =
    symbolInfo != null &&
        (
            if (isMarket) {
                symbolInfo.marketLotSizeFilter != null ||
                    symbolInfo.lotSizeFilter != null
            } else {
                symbolInfo.lotSizeFilter != null
            }
        )

/*
 * Jika metadata belum lengkap:
 * force refresh sekali dari /open/v1/common/symbols.
 */
if (!hasRequiredLotFilter) {
    val loaded =
        TokocryptoSymbolRepository.ensureSymbolsLoaded(
            force = true
        )

    if (!loaded) {
        return@withContext TokocryptoOrderResult(
            success = false,
            errorMessage =
                "Metadata trading Tokocrypto gagal dimuat. " +
                "Order dibatalkan agar quantity mentah tidak dikirim."
        )
    }

    symbolInfo =
        TokocryptoSymbolRepository.getSymbolInfo(
            request.symbol
        )
}

if (symbolInfo == null) {
    return@withContext TokocryptoOrderResult(
        success = false,
        errorMessage =
            "Symbol ${request.symbol} tidak ditemukan " +
            "di metadata Tokocrypto."
    )
}

val lotFilter =
    if (isMarket) {
        symbolInfo.marketLotSizeFilter
            ?: symbolInfo.lotSizeFilter
    } else {
        symbolInfo.lotSizeFilter
    }

if (lotFilter == null) {
    return@withContext TokocryptoOrderResult(
        success = false,
        errorMessage =
            "Filter quantity untuk ${request.symbol} " +
            "tidak tersedia di Tokocrypto."
    )
}

// =========================================================
// 2. Validasi order
// =========================================================
val valResult =
    TokocryptoSymbolRepository.validateOrder(
        symbol = request.symbol,
        price = request.price
            ?: referenceMarketPrice,
        quantity = request.quantity ?: 0.0,
        isMarket = isMarket,
        isBuy = isBuy,
        referenceMarketPrice = referenceMarketPrice
    )

if (!valResult.isValid) {
    return@withContext TokocryptoOrderResult(
        success = false,
        errorMessage =
            "Validasi Order Tokocrypto Gagal: " +
            valResult.reason
    )
}
        // 2. Symbol format Tokocrypto: BASE_QUOTE (contoh BTC_USDT), bukan BTCUSDT
        val tokoSymbol = TokocryptoSymbolRepository.getSymbolInfo(request.symbol)
            ?.let { "${it.baseAsset}_${it.quoteAsset}" }
            ?: TokocryptoMarketService.toTokocryptoPair(request.symbol)

        val timestamp = System.currentTimeMillis().toString()
        val formParams = mutableListOf<Pair<String, String>>()
        val finalQuantity = BigDecimal.valueOf(valResult.adjustedQty)
        .stripTrailingZeros()
        .toPlainString()

        formParams.add("symbol" to tokoSymbol)
        formParams.add("side" to request.side.code.toString())
        formParams.add("type" to request.type.code.toString())

        if (request.quantity != null && request.quantity > 0) {
            formParams.add("quantity" to finalQuantity)
        }
        if (request.quoteOrderQty != null && request.quoteOrderQty > 0) {
            formParams.add("quoteOrderQty" to formatParam(request.quoteOrderQty, 2, RoundingMode.DOWN))
        }
        if (request.price != null && request.price > 0 && !isMarket) {
            formParams.add("price" to formatParam(valResult.adjustedPrice, 8, RoundingMode.HALF_UP))
        }
        if (request.stopPrice != null && request.stopPrice > 0) {
            formParams.add("stopPrice" to formatParam(request.stopPrice, 8, RoundingMode.HALF_UP))
        }
        if (!request.timeInForce.isNullOrBlank() && !isMarket) {
            // Dokumentasi Tokocrypto: timeInForce berupa kode angka (1=GTC, 2=IOC, 3=FOK, 4=GTX), bukan teks "GTC"
            val tifCode = when (request.timeInForce.trim().uppercase()) {
                "GTC" -> "1"
                "IOC" -> "2"
                "FOK" -> "3"
                "GTX" -> "4"
                else -> request.timeInForce.trim()
            }
            formParams.add("timeInForce" to tifCode)
        }
        // clientId kustom SENGAJA tidak dikirim: ID berformat teks ("agu-buy-1759...") ditolak Tokocrypto
        // dengan kode 3703 "Invalid client ID". Jika dikosongkan, Tokocrypto membuat ID sendiri dan
        // mengembalikannya di respons (data.clientId) beserta orderId.
        formParams.add("recvWindow" to request.recvWindow.toString())
        formParams.add("timestamp" to timestamp)

        val queryString = formParams.joinToString("&") { "${it.first}=${it.second}" }
        val signature = hmacSha256(secretKey, queryString)

        // 3. Submit ke Tokocrypto REST POST /open/v1/orders (satu-satunya jalur, tanpa fallback)
        try {
            val url = "$TOKOCRYPTO_BASE_URL/open/v1/orders?$queryString&signature=$signature"
            val req = Request.Builder()
                .url(url)
                .post(FormBody.Builder().build())
                .header("X-MBX-APIKEY", apiKey.trim())
                .header("Accept", "application/json")
                .build()

        Timber.i(
                "Tokocrypto REAL ORDER | " +
                "symbol=$tokoSymbol | " +
                "side=${request.side} | " +
                "type=${request.type} | " +
                "requestedQty=${request.quantity} | " +
                "stepSize=${lotFilter.stepSize} | " +
                "minQty=${lotFilter.minQty} | " +
                "maxQty=${lotFilter.maxQty} | " +
                "adjustedQty=${valResult.adjustedQty} | " +
                "sentQuantity=$finalQuantity | " +
                "price=${valResult.adjustedPrice}"
                )
                
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { JSONObject(body) }.getOrNull()
                val code = root?.optInt("code", -1) ?: -1
                val data = root?.optJSONObject("data")

                if (resp.isSuccessful && code == 0 && data != null) {
                    return@withContext TokocryptoOrderResult(
                        success = true,
                        orderId = data.optString("orderId", ""),
                        clientId = data.optString("clientId", ""),
                        symbol = data.optString("symbol", tokoSymbol),
                        status = data.optString("status", "NEW"),
                        executedQty = data.optString("executedQty", "0").toDoubleOrNull() ?: 0.0,
                        cumulativeQuoteQty = data.optString("cummulativeQuoteQty", "0").toDoubleOrNull() ?: 0.0,
                        rawMessage = "Order Tokocrypto berhasil dibuat."
                    )
                }

                // Gagal: teruskan pesan error asli Tokocrypto (HTTP error maupun code != 0)
                val detail = describeError(resp.code, root)
                Timber.w("Order Tokocrypto ditolak ($tokoSymbol): $detail")
                return@withContext TokocryptoOrderResult(
                    success = false,
                    errorMessage = "Tokocrypto Order Error ($detail)"
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Gagal create order Tokocrypto: ${e.message}")
            return@withContext TokocryptoOrderResult(
                success = false,
                errorMessage = "Tidak dapat terhubung ke server order Tokocrypto (${e.message ?: e.javaClass.simpleName}). " +
                    "Status order tidak pasti, cek di Tokocrypto sebelum mengulang."
            )
        }
    }

    /**
     * Membatalkan order Tokocrypto (POST /open/v1/orders/cancel)
     */
    suspend fun cancelOrder(
        apiKey: String,
        secretKey: String,
        symbol: String,
        orderId: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return@withContext false to "API Key atau Secret Key Tokocrypto belum diisi."
        }
        val tokoSymbol = TokocryptoSymbolRepository.getSymbolInfo(symbol)
            ?.let { "${it.baseAsset}_${it.quoteAsset}" }
            ?: TokocryptoMarketService.toTokocryptoPair(symbol)

        val timestamp = System.currentTimeMillis().toString()
        val queryParam = "orderId=$orderId&recvWindow=10000&symbol=$tokoSymbol&timestamp=$timestamp"
        val signature = hmacSha256(secretKey, queryParam)

        try {
            val url = "$TOKOCRYPTO_BASE_URL/open/v1/orders/cancel?$queryParam&signature=$signature"
            val req = Request.Builder()
                .url(url)
                .post(FormBody.Builder().build())
                .header("X-MBX-APIKEY", apiKey.trim())
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { JSONObject(body) }.getOrNull()
                val code = root?.optInt("code", -1) ?: -1
                if (resp.isSuccessful && code == 0) {
                    return@withContext true to "Order $orderId berhasil dibatalkan."
                }
                val detail = describeError(resp.code, root)
                return@withContext false to "Gagal batal order Tokocrypto: $detail"
            }
        } catch (e: Exception) {
            return@withContext false to "Error koneksi Tokocrypto: ${e.message}"
        }
    }

    /**
     * Mengambil detail status order Tokocrypto (GET /open/v1/orders/detail)
     */
    suspend fun getOrder(
        apiKey: String,
        secretKey: String,
        symbol: String,
        orderId: String?,
        clientOrderId: String?
    ): IndodaxTradeApiV2.OrderResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return@withContext IndodaxTradeApiV2.OrderResult(false, "API Key / Secret Tokocrypto kosong")
        }
        val tokoSymbol = TokocryptoSymbolRepository.getSymbolInfo(symbol)
            ?.let { "${it.baseAsset}_${it.quoteAsset}" }
            ?: TokocryptoMarketService.toTokocryptoPair(symbol)

        val timestamp = System.currentTimeMillis().toString()
        val params = mutableListOf<String>()
        if (!orderId.isNullOrBlank() && orderId != "0") params.add("orderId=$orderId")
        // clientId dari Tokocrypto hanya dipakai jika orderId tidak ada (ID buatan aplikasi tidak dikenali)
        val hasOrderId = !orderId.isNullOrBlank() && orderId != "0"
        if (!hasOrderId && !clientOrderId.isNullOrBlank()) params.add("clientId=$clientOrderId")
        params.add("recvWindow=10000")
        params.add("symbol=$tokoSymbol")
        params.add("timestamp=$timestamp")
        params.sort()
        val queryParam = params.joinToString("&")
        val signature = hmacSha256(secretKey, queryParam)

        try {
            val url = "$TOKOCRYPTO_BASE_URL/open/v1/orders/detail?$queryParam&signature=$signature"
            val req = Request.Builder()
                .url(url)
                .get()
                .header("X-MBX-APIKEY", apiKey.trim())
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { JSONObject(body) }.getOrNull()
                val code = root?.optInt("code", -1) ?: -1
                val data = root?.optJSONObject("data")

                if (resp.isSuccessful && code == 0 && data != null) {
                    val status = data.optString("status", "NEW")
                    val executedQty = data.optString("executedQty", "0").toDoubleOrNull() ?: 0.0
                    val origQty = data.optString("origQty", "0").toDoubleOrNull() ?: 0.0
                    val oId = data.optString("orderId", orderId.orEmpty())
                    val cId = data.optString("clientId", clientOrderId.orEmpty())
                    return@withContext IndodaxTradeApiV2.OrderResult(
                        success = true,
                        message = "Status: $status",
                        orderId = oId,
                        clientOrderId = cId,
                        executedQty = executedQty,
                        origQty = origQty,
                        status = status
                    )
                }
                val detail = describeError(resp.code, root)
                return@withContext IndodaxTradeApiV2.OrderResult(false, "Query Order Gagal: $detail")
            }
        } catch (e: Exception) {
            return@withContext IndodaxTradeApiV2.OrderResult(false, "Error: ${e.message}")
        }
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