package agu.analys.service

import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.*
import agu.analys.network.NetworkClientProvider
import agu.analys.service.IndodaxTradeApiV2.IndodaxBalances
import agu.analys.util.AppLogManager
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

    private var serverTimeOffsetMs = 0L
    private var lastTimeSyncMs = 0L

    /**
     * Sinkronisasi selisih waktu antara jam perangkat lokal dengan server Tokocrypto.
     * Mencegah penolakan request dengan error "-1021: Timestamp for this request is outside of recvWindow".
     */
    suspend fun syncServerTime() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastTimeSyncMs < 300_000L && lastTimeSyncMs > 0L) return@withContext
        try {
            val req = Request.Builder()
                .url("$TOKOCRYPTO_BASE_URL/open/v1/common/time")
                .get()
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val serverTime = root.optLong("timestamp", 0L)
                    if (serverTime > 0L) {
                        serverTimeOffsetMs = serverTime - System.currentTimeMillis()
                        lastTimeSyncMs = System.currentTimeMillis()
                        Timber.d("Tokocrypto server time offset synchronized: ${serverTimeOffsetMs}ms")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w("Gagal sinkronisasi waktu server Tokocrypto: ${e.message}")
        }
    }

    private fun getAdjustedTimestamp(): String {
        return (System.currentTimeMillis() + serverTimeOffsetMs).toString()
    }

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
    private fun describeError(httpCode: Int, root: JSONObject?, rawBody: String = ""): String {
        val code = if (root != null && root.has("code")) root.optInt("code") else null
        val msg = root?.optString("msg", "")?.takeIf { it.isNotBlank() }
            ?: root?.optString("message", "")?.takeIf { it.isNotBlank() }
            ?: root?.optString("error", "")?.takeIf { it.isNotBlank() }
            ?: root?.optString("error_description", "")?.takeIf { it.isNotBlank() }
            ?: root?.optString("data", "")?.takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
            ?: if (rawBody.isNotBlank() && rawBody.length < 200) rawBody.trim() else null

        return buildString {
            append("HTTP $httpCode")
            if (code != null) append(", kode $code")
            if (!msg.isNullOrBlank()) append(": $msg")
        }
    }

    /**
     * Ekstraksi aset dari berbagai variasi respons JSON Tokocrypto:
     * - root.accountAssets
     * - root.balances
     * - data is JSONArray
     * - data.accountAssets
     * - data.balances
     * - data.assets / data.userAssets
     * - data sebagai Map aset
     */
    private fun extractAssets(root: JSONObject): JSONArray? {
        root.optJSONArray("accountAssets")?.let { return it }
        root.optJSONArray("balances")?.let { return it }
        root.optJSONArray("assets")?.let { return it }

        return when (val data = root.opt("data")) {
            is JSONArray -> data
            is JSONObject -> {
                data.optJSONArray("accountAssets")
                    ?: data.optJSONArray("balances")
                    ?: data.optJSONArray("assets")
                    ?: data.optJSONArray("userAssets")
                    ?: run {
                        // Jika data berupa Map objek aset (contoh: {"USDT": {"free": "10", "locked": "0"}})
                        val synthesized = JSONArray()
                        val keys = data.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val assetObj = data.optJSONObject(key)
                            if (assetObj != null) {
                                val item = JSONObject()
                                item.put("asset", key)
                                item.put("free", assetObj.opt("free") ?: assetObj.opt("available") ?: "0")
                                item.put("locked", assetObj.opt("locked") ?: assetObj.opt("freeze") ?: "0")
                                synthesized.put(item)
                            }
                        }
                        if (synthesized.length() > 0) synthesized else null
                    }
            }
            else -> null
        }
    }

    /**
     * Mengambil saldo riil spot Tokocrypto dengan pemindaian menyeluruh:
     * 1. GET /open/v1/account/spot (mengambil seluruh saldo akun spot).
     * 2. Targeted query ke /open/v1/account/spot/asset?asset=USDT jika saldo USDT perlu verifikasi ekstra.
     * 3. Dukungan multi-casing (usdt & USDT) agar UI dan ViewModel selalu membaca nilai akurat.
     */
    suspend fun getAccount(apiKey: String, secretKey: String): Pair<IndodaxBalances?, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return@withContext null to "API Key atau Secret Key Tokocrypto belum diisi."
        }

        syncServerTime()
        val timestamp = getAdjustedTimestamp()
        val recvWindow = "10000"
        val params = listOf(
            "recvWindow=$recvWindow",
            "timestamp=$timestamp"
        ).sorted()
        val queryParam = params.joinToString("&")
        val signature = hmacSha256(secretKey, queryParam)

        var apiError: String? = null
        var networkError: String? = null
        var parsedBalances: IndodaxBalances? = null

        for (path in ACCOUNT_ENDPOINTS) {
            try {
                val req = Request.Builder()
                    .url("$TOKOCRYPTO_BASE_URL$path?$queryParam&signature=$signature")
                    .get()
                    .header("X-MBX-APIKEY", apiKey.trim())
                    .header("Accept", "application/json")
                    .header("User-Agent", "AnalysApp/1.0 (Android; Tokocrypto Trade)")
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val root = runCatching { JSONObject(body) }.getOrNull()
                    val assets = root?.let { extractAssets(it) }
                    if (resp.isSuccessful && root != null && root.optInt("code", 0) == 0 && assets != null) {
                        parsedBalances = parseBalances(assets)
                        Timber.i("Tokocrypto $path berhasil: ${parsedBalances?.total?.size} aset terbaca.")
                    } else {
                        if (apiError == null) apiError = describeError(resp.code, root, body)
                        Timber.w("Tokocrypto $path gagal: $apiError | body: ${body.take(200)}")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Tokocrypto $path error koneksi: ${e.message}")
                networkError = e.message ?: e.javaClass.simpleName
            }
        }

        // Targeted query khusus untuk aset USDT bila belum terbaca atau bernilai 0 dari endpoint umum
        val hasUsdt = parsedBalances?.total?.get("usdt") != null && (parsedBalances?.total?.get("usdt") ?: 0.0) > 0.0
        if (parsedBalances == null || !hasUsdt) {
            try {
                val assetTimestamp = getAdjustedTimestamp()
                val assetParams = listOf(
                    "asset=USDT",
                    "recvWindow=$recvWindow",
                    "timestamp=$assetTimestamp"
                ).sorted()
                val assetQuery = assetParams.joinToString("&")
                val assetSig = hmacSha256(secretKey, assetQuery)
                val assetReq = Request.Builder()
                    .url("$TOKOCRYPTO_BASE_URL/open/v1/account/spot/asset?$assetQuery&signature=$assetSig")
                    .get()
                    .header("X-MBX-APIKEY", apiKey.trim())
                    .header("Accept", "application/json")
                    .header("User-Agent", "AnalysApp/1.0 (Android; Tokocrypto Trade)")
                    .build()

                client.newCall(assetReq).execute().use { assetResp ->
                    val assetBody = assetResp.body?.string().orEmpty()
                    val assetRoot = runCatching { JSONObject(assetBody) }.getOrNull()
                    if (assetResp.isSuccessful && assetRoot != null && assetRoot.optInt("code", 0) == 0) {
                        val assetData = assetRoot.opt("data")
                        val usdtItem = when (assetData) {
                            is JSONObject -> assetData
                            is JSONArray -> assetData.optJSONObject(0)
                            else -> null
                        }
                        if (usdtItem != null) {
                            val free = usdtItem.optString("free", "").toDoubleOrNull()
                                ?: usdtItem.optDouble("free", Double.NaN).takeIf { !it.isNaN() }
                                ?: usdtItem.optString("available", "").toDoubleOrNull()
                                ?: usdtItem.optDouble("available", 0.0)

                            val locked = usdtItem.optString("locked", "").toDoubleOrNull()
                                ?: usdtItem.optDouble("locked", Double.NaN).takeIf { !it.isNaN() }
                                ?: usdtItem.optString("freeze", "").toDoubleOrNull()
                                ?: usdtItem.optString("frozen", "").toDoubleOrNull()
                                ?: usdtItem.optDouble("locked", 0.0)

                            val total = free + locked

                            val currentFree = parsedBalances?.free?.toMutableMap() ?: mutableMapOf()
                            val currentLocked = parsedBalances?.locked?.toMutableMap() ?: mutableMapOf()
                            val currentTotal = parsedBalances?.total?.toMutableMap() ?: mutableMapOf()

                            currentFree["usdt"] = free
                            currentFree["USDT"] = free
                            currentLocked["usdt"] = locked
                            currentLocked["USDT"] = locked
                            currentTotal["usdt"] = total
                            currentTotal["USDT"] = total

                            parsedBalances = IndodaxBalances(
                                total = currentTotal,
                                free = currentFree,
                                locked = currentLocked
                            )
                            Timber.i("Tokocrypto spot/asset targeted USDT berhasil: free=$free, locked=$locked, total=$total")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w("Tokocrypto targeted spot/asset USDT check error: ${e.message}")
            }
        }

        if (parsedBalances != null) {
            return@withContext parsedBalances to "Saldo Tokocrypto berhasil diperbarui."
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
            val assetRaw = (item.optString("asset").takeIf { it.isNotBlank() }
                ?: item.optString("assetName").takeIf { it.isNotBlank() }
                ?: item.optString("coin").takeIf { it.isNotBlank() }
                ?: item.optString("currency", "")).trim()
            if (assetRaw.isBlank()) continue

            val assetLower = assetRaw.lowercase()
            val assetUpper = assetRaw.uppercase()

            val free = item.optString("free", "").toDoubleOrNull()
                ?: item.optDouble("free", Double.NaN).takeIf { !it.isNaN() }
                ?: item.optString("available", "").toDoubleOrNull()
                ?: item.optDouble("available", 0.0)

            val locked = item.optString("locked", "").toDoubleOrNull()
                ?: item.optDouble("locked", Double.NaN).takeIf { !it.isNaN() }
                ?: item.optString("freeze", "").toDoubleOrNull()
                ?: item.optString("frozen", "").toDoubleOrNull()
                ?: item.optDouble("locked", 0.0)

            val total = free + locked

            // Simpan baik huruf kecil maupun huruf besar untuk kompatibilitas mutlak
            freeMap[assetLower] = free
            freeMap[assetUpper] = free
            holdMap[assetLower] = locked
            holdMap[assetUpper] = locked
            totalMap[assetLower] = total
            totalMap[assetUpper] = total

            // Alias bidr ke idr untuk keseragaman UI
            if (assetLower == "bidr") {
                freeMap["idr"] = free
                freeMap["IDR"] = free
                holdMap["idr"] = locked
                holdMap["IDR"] = locked
                totalMap["idr"] = total
                totalMap["IDR"] = total
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
 * force refresh dari Tokocrypto/Binance discovery.
 */
if (!hasRequiredLotFilter) {
    TokocryptoSymbolRepository.ensureSymbolsLoaded(force = true)
    symbolInfo = TokocryptoSymbolRepository.getSymbolInfo(request.symbol)
}

if (symbolInfo == null) {
    return@withContext TokocryptoOrderResult(
        success = false,
        errorMessage =
            "Symbol ${request.symbol} tidak ditemukan di metadata Tokocrypto."
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

        syncServerTime()
        val timestamp = getAdjustedTimestamp()
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

        // Urutkan parameter secara alfabetis sebelum menghitung HMAC signature
        val queryString = formParams
            .sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second}" }
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
                    val orderId = data.optString("orderId", "")
                    val returnedClientId = data.optString("clientId", "")
                    val status = data.optString("status", "NEW")
                    val executedQty = data.optString("executedQty", "0").toDoubleOrNull() ?: 0.0
                    val cumQuote = data.optString("cummulativeQuoteQty", "0").toDoubleOrNull() ?: 0.0

                    AppLogManager.trade(
                        "TokocryptoOrderSuccess",
                        "✅ [ORDER TOKOCRYPTO BERHASIL] $tokoSymbol (${request.side} ${request.type})\n" +
                            "▶ Order ID: $orderId | Client ID: $returnedClientId | Status: $status\n" +
                            "▶ Executed Qty: $executedQty | Total Quote: $cumQuote"
                    )

                    return@withContext TokocryptoOrderResult(
                        success = true,
                        orderId = orderId,
                        clientId = returnedClientId,
                        symbol = data.optString("symbol", tokoSymbol),
                        status = status,
                        executedQty = executedQty,
                        cumulativeQuoteQty = cumQuote,
                        rawMessage = "Order Tokocrypto berhasil dibuat.",
                        httpCode = resp.code,
                        serverCode = code,
                        serverBody = body,
                        requestDebug = "symbol=$tokoSymbol | side=${request.side} | type=${request.type} | qty=$finalQuantity | price=${valResult.adjustedPrice}"
                    )
                }

                // Gagal: teruskan pesan error asli Tokocrypto (HTTP error maupun code != 0)
                val detail = describeError(resp.code, root, body)
                val serverMsg = root?.optString("msg")?.ifBlank { root.optString("message") } ?: detail
                val serverCodeVal = if (code != -1) code else null

                Timber.w("Order Tokocrypto ditolak ($tokoSymbol): $detail | Raw Body: $body")
                AppLogManager.trade(
                    "TokocryptoOrderRejected",
                    "⚠️ [ORDER TOKOCRYPTO DITOLAK SERVER]\n" +
                        "▶ Simbol: $tokoSymbol | Side: ${request.side} | Type: ${request.type}\n" +
                        "▶ Status HTTP: ${resp.code} | Kode Server: ${serverCodeVal ?: "N/A"}\n" +
                        "▶ Pesan Server: $serverMsg\n" +
                        "▶ Parameter Terkirim: $queryString\n" +
                        "▶ Respons Lengkap Server Tokocrypto: $body"
                )
                AppLogManager.error(
                    "TokocryptoTrade",
                    "Order ditolak server Tokocrypto ($tokoSymbol): HTTP ${resp.code}, kode $serverCodeVal: $serverMsg | $body"
                )

                return@withContext TokocryptoOrderResult(
                    success = false,
                    errorMessage = "Order ditolak Tokocrypto ($detail)",
                    httpCode = resp.code,
                    serverCode = serverCodeVal,
                    serverBody = body,
                    requestDebug = "symbol=$tokoSymbol | side=${request.side} | type=${request.type} | qty=$finalQuantity | price=${valResult.adjustedPrice} | query=$queryString"
                )
            }
        } catch (e: Exception) {
            val exMessage = e.message ?: e.javaClass.simpleName
            Timber.w(e, "Gagal create order Tokocrypto: $exMessage")
            AppLogManager.trade(
                "TokocryptoOrderException",
                "🚨 [KONEKSI ORDER TOKOCRYPTO GAGAL] $tokoSymbol | Error: $exMessage | URL: $TOKOCRYPTO_BASE_URL/open/v1/orders | Query: $queryString"
            )
            AppLogManager.error("TokocryptoTrade", "Koneksi order Tokocrypto gagal ($tokoSymbol): $exMessage", e)

            return@withContext TokocryptoOrderResult(
                success = false,
                errorMessage = "Gagal terhubung ke server order Tokocrypto ($exMessage). Status order tidak pasti, periksa di Tokocrypto.",
                httpCode = 0,
                serverCode = null,
                serverBody = exMessage,
                requestDebug = "query=$queryString"
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
                    AppLogManager.trade("TokocryptoCancelSuccess", "✅ [BATAL ORDER TOKOCRYPTO BERHASIL] Order $orderId ($tokoSymbol)")
                    return@withContext true to "Order $orderId berhasil dibatalkan."
                }
                val detail = describeError(resp.code, root, body)
                AppLogManager.trade(
                    "TokocryptoCancelRejected",
                    "⚠️ [BATAL ORDER TOKOCRYPTO DITOLAK] Order $orderId ($tokoSymbol)\n▶ Status HTTP: ${resp.code}\n▶ Respons Server: $body\n▶ Detail: $detail"
                )
                return@withContext false to "Gagal batal order Tokocrypto: $detail"
            }
        } catch (e: Exception) {
            val exMsg = e.message ?: e.javaClass.simpleName
            AppLogManager.trade("TokocryptoCancelException", "🚨 [KONEKSI BATAL ORDER GAGAL] Order $orderId ($tokoSymbol): $exMsg")
            return@withContext false to "Error koneksi Tokocrypto: $exMsg"
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
                val detail = describeError(resp.code, root, body)
                AppLogManager.trade(
                    "TokocryptoQueryFailed",
                    "⚠️ [QUERY ORDER TOKOCRYPTO GAGAL] Order $orderId ($tokoSymbol)\n▶ HTTP: ${resp.code} | Detail: $detail | Respons: $body"
                )
                return@withContext IndodaxTradeApiV2.OrderResult(false, "Query Order Gagal: $detail")
            }
        } catch (e: Exception) {
            val ex = e.message ?: e.javaClass.simpleName
            AppLogManager.trade("TokocryptoQueryException", "🚨 [QUERY ORDER EXCEPTION] Order $orderId ($tokoSymbol): $ex")
            return@withContext IndodaxTradeApiV2.OrderResult(false, "Error: $ex")
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