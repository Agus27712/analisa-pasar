package agu.analys.data

import agu.analys.model.*
import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Repository Single Source of Truth (SSOT) untuk Dynamic Symbol Discovery Tokocrypto.
 * Mengambil daftar symbol, filter trading (LOT_SIZE, PRICE_FILTER, MIN_NOTIONAL),
 * dan precision secara dinamis dari API resmi:
 * GET https://www.tokocrypto.com/open/v1/common/symbols
 *
 * Mencegah hardcoding pasangan koin dan mendukung deteksi Symbol Type 1 vs Type 3.
 */
object TokocryptoSymbolRepository {
    private val client get() = NetworkClientProvider.marketClient

    private val symbolsMap = ConcurrentHashMap<String, TokocryptoSymbolInfo>()
    private val executionRulesMap = ConcurrentHashMap<String, TokocryptoExecutionRules>()

    private val _symbolsState = MutableStateFlow<List<TokocryptoSymbolInfo>>(emptyList())
    val symbolsState: StateFlow<List<TokocryptoSymbolInfo>> = _symbolsState.asStateFlow()

    private val syncMutex = Mutex()
    private val isInitialized = AtomicBoolean(false)
    private val lastSyncTime = AtomicLong(0L)
    private const val SYNC_TTL_MS = 30 * 60 * 1000L // 30 menit refresh interval

    // Sumber utama: endpoint resmi Tokocrypto yang mengembalikan seluruh supported trading symbols.
    // MBX dan NextMe dipakai sebagai fallback resmi sesuai dokumentasi migrasi Tokocrypto.
    private val SYMBOLS_ENDPOINTS = listOf(
        "https://www.tokocrypto.com/open/v1/common/symbols",
        "https://www.tokocrypto.site/api/v3/exchangeInfo",
        "https://cloudme-toko.2meta.app/api/v1/exchangeInfo"
    )
    private const val TOKOCRYPTO_EXECUTION_RULES_URL = "https://www.tokocrypto.site/api/v3/executionRules"

    init {
        populateDefaultSymbols()
    }

    private fun populateDefaultSymbols() {
        val defaults = listOf(
            Triple("BTC", "IDR", Pair(1.0, 0.00001)),
            Triple("ETH", "IDR", Pair(1.0, 0.0001)),
            Triple("SOL", "IDR", Pair(1.0, 0.001)),
            Triple("DOGE", "IDR", Pair(1.0, 1.0)),
            Triple("XRP", "IDR", Pair(1.0, 0.1)),
            Triple("SUI", "IDR", Pair(1.0, 0.1)),
            Triple("ADA", "IDR", Pair(1.0, 0.1)),
            Triple("BNB", "IDR", Pair(1.0, 0.001)),
            Triple("SHIB", "IDR", Pair(1.0, 1.0)),
            Triple("NEAR", "IDR", Pair(1.0, 0.01)),
            Triple("AVAX", "IDR", Pair(1.0, 0.01)),
            Triple("PEPE", "IDR", Pair(1.0, 1.0)),
            Triple("TRX", "IDR", Pair(1.0, 0.1)),
            Triple("LINK", "IDR", Pair(1.0, 0.01)),
            Triple("RENDER", "IDR", Pair(1.0, 0.01)),
            Triple("FET", "IDR", Pair(1.0, 0.01)),
            Triple("FLOKI", "IDR", Pair(1.0, 1.0)),
            Triple("BONK", "IDR", Pair(1.0, 1.0)),

            Triple("BTC", "USDT", Pair(0.01, 0.00001)),
            Triple("ETH", "USDT", Pair(0.01, 0.0001)),
            Triple("SOL", "USDT", Pair(0.01, 0.01)),
            Triple("DOGE", "USDT", Pair(0.0001, 0.1)),
            Triple("XRP", "USDT", Pair(0.0001, 0.1)),
            Triple("SUI", "USDT", Pair(0.0001, 0.1)),
            Triple("ADA", "USDT", Pair(0.0001, 0.1)),
            Triple("BNB", "USDT", Pair(0.01, 0.001)),
            Triple("SHIB", "USDT", Pair(0.00000001, 1.0)),
            Triple("PEPE", "USDT", Pair(0.00000001, 1.0))
        )

        val minNotionalIdr = 20_000.0
        val minNotionalUsdt = 1.0

        for ((base, quote, precisions) in defaults) {
            val tickSize = precisions.first
            val stepSize = precisions.second
            val minNotional = if (quote == "IDR") minNotionalIdr else minNotionalUsdt

            val info = TokocryptoSymbolInfo(
                symbol = "${base}_$quote",
                baseAsset = base,
                quoteAsset = quote,
                symbolType = 1,
                basePrecision = 8,
                quotePrecision = 8,
                spotTradingEnable = true,
                defaultSelfTradePreventionMode = "NONE",
                priceFilter = TokocryptoPriceFilter(
                    minPrice = tickSize,
                    maxPrice = 1_000_000_000_000.0,
                    tickSize = tickSize
                ),
                lotSizeFilter = TokocryptoLotSizeFilter(
                    minQty = stepSize,
                    maxQty = 1_000_000_000.0,
                    stepSize = stepSize
                ),
                marketLotSizeFilter = TokocryptoLotSizeFilter(
                    minQty = stepSize,
                    maxQty = 1_000_000_000.0,
                    stepSize = stepSize
                ),
                minNotionalFilter = TokocryptoMinNotionalFilter(
                    minNotional = minNotional,
                    applyToMarket = true,
                    avgPriceMins = 5
                )
            )

            val rawSymbol = "${base}$quote"
            val underscore = "${base}_$quote"
            symbolsMap[rawSymbol] = info
            symbolsMap[underscore] = info
        }
        _symbolsState.value = symbolsMap.values.toList()
    }

    fun isReady(): Boolean = symbolsMap.isNotEmpty()

    suspend fun ensureSymbolsLoaded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && isInitialized.get() && (now - lastSyncTime.get() < SYNC_TTL_MS)) {
            return@withContext true
        }

        syncMutex.withLock {
            if (!force && isInitialized.get() && (now - lastSyncTime.get() < SYNC_TTL_MS)) {
                return@withLock true
            }

            // Fetch hanya dari endpoint resmi Tokocrypto
            val success = fetchFromTokocrypto()
            if (!success) {
                Timber.w("Tokocrypto symbol online discovery gagal, menggunakan metadata bawaan.")
            }

            isInitialized.set(true)
            lastSyncTime.set(System.currentTimeMillis())
            _symbolsState.value = symbolsMap.values.toList()

            // Fetch execution rules di background untuk price range guard
            fetchExecutionRulesQuietly()

            // Sukses jika symbolsMap tidak kosong (baik dari network maupun fallback defaults)
            symbolsMap.isNotEmpty()
        }
    }

    private suspend fun fetchFromTokocrypto(): Boolean {
        for (endpointUrl in SYMBOLS_ENDPOINTS) {
            try {
                val req = Request.Builder()
                    .url(endpointUrl)
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TokoClient/3.5")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || body.isBlank()) return@use

                    val root = JSONObject(body)
                    val code = root.optInt("code", 0)
                    val data = root.optJSONArray("data") ?: root.optJSONArray("symbols")

                    if ((code == 0 || resp.isSuccessful) && data != null && data.length() > 0) {
                        val newMap = parseTokocryptoSymbols(data)

                        // Replace saat sync berhasil agar symbol yang sudah delisting
                        // tidak tertinggal dari hasil sync sebelumnya.
                        if (newMap.isNotEmpty()) {
                            symbolsMap.clear()
                            symbolsMap.putAll(newMap)
                            Timber.i("TokocryptoSymbolRepository: Berhasil load ${newMap.size} aliases dari $endpointUrl")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w("Gagal fetch Tokocrypto symbols dari $endpointUrl: ${e.message}")
            }
        }
        return false
    }

    private fun parseTokocryptoSymbols(dataArray: JSONArray): Map<String, TokocryptoSymbolInfo> {
        val newMap = mutableMapOf<String, TokocryptoSymbolInfo>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val rawSymbol = item.optString("symbol", "").trim().uppercase()
            if (rawSymbol.isBlank()) continue

            var baseAsset = item.optString("baseAsset", "").trim().uppercase()
            var quoteAsset = item.optString("quoteAsset", "").trim().uppercase()

            if (baseAsset.isBlank() || quoteAsset.isBlank()) {
                val cleanSym = rawSymbol.replace("_", "").replace("-", "").replace("/", "")
                when {
                    cleanSym.endsWith("IDR") -> {
                        baseAsset = cleanSym.removeSuffix("IDR")
                        quoteAsset = "IDR"
                    }
                    cleanSym.endsWith("USDT") -> {
                        baseAsset = cleanSym.removeSuffix("USDT")
                        quoteAsset = "USDT"
                    }
                }
            }

            // Tokocrypto yang digunakan aplikasi ini hanya memakai quote USDT dan IDR.
            if (quoteAsset != "IDR" && quoteAsset != "USDT") continue

            val symbolType = item.optInt("symbolType", 1) // 1 = MBX, 3 = NextMe
            val basePrecision = item.optInt("basePrecision", item.optInt("baseAssetPrecision", 8))
            val quotePrecision = item.optInt("quotePrecision", item.optInt("quoteAssetPrecision", 8))
            
            val status = item.optString("status", "")
            val spotTradingEnable = if (status.isNotBlank()) status == "TRADING" else item.optBoolean("spotTradingEnable", true)
            val defaultStp = item.optString("defaultSelfTradePreventionMode", "NONE")

            var priceFilter: TokocryptoPriceFilter? = null
            var lotSizeFilter: TokocryptoLotSizeFilter? = null
            var marketLotSizeFilter: TokocryptoLotSizeFilter? = null
            var minNotionalFilter: TokocryptoMinNotionalFilter? = null

            val filtersArray = item.optJSONArray("filters")
            if (filtersArray != null) {
                for (f in 0 until filtersArray.length()) {
                    val fObj = filtersArray.optJSONObject(f) ?: continue
                    val filterType = fObj.optString("filterType", "")
                    when (filterType) {
                        "PRICE_FILTER" -> {
                            priceFilter = TokocryptoPriceFilter(
                                minPrice = fObj.optString("minPrice", "0").toDoubleOrNull() ?: 0.0,
                                maxPrice = fObj.optString("maxPrice", "0").toDoubleOrNull() ?: Double.MAX_VALUE,
                                tickSize = fObj.optString("tickSize", "0").toDoubleOrNull() ?: 0.0
                            )
                        }
                        "LOT_SIZE" -> {
                            lotSizeFilter = TokocryptoLotSizeFilter(
                                minQty = fObj.optString("minQty", "0").toDoubleOrNull() ?: 0.0,
                                maxQty = fObj.optString("maxQty", "0").toDoubleOrNull() ?: Double.MAX_VALUE,
                                stepSize = fObj.optString("stepSize", "0").toDoubleOrNull() ?: 0.0
                            )
                        }
                        "MARKET_LOT_SIZE" -> {
                            marketLotSizeFilter = TokocryptoLotSizeFilter(
                                minQty = fObj.optString("minQty", "0").toDoubleOrNull() ?: 0.0,
                                maxQty = fObj.optString("maxQty", "0").toDoubleOrNull() ?: Double.MAX_VALUE,
                                stepSize = fObj.optString("stepSize", "0").toDoubleOrNull() ?: 0.0
                            )
                        }
                        "NOTIONAL", "MIN_NOTIONAL" -> {
                            minNotionalFilter = TokocryptoMinNotionalFilter(
                                minNotional = fObj.optString("minNotional", "0").toDoubleOrNull() ?: 0.0,
                                applyToMarket = fObj.optBoolean("applyToMarket", true),
                                avgPriceMins = fObj.optInt("avgPriceMins", 5)
                            )
                        }
                    }
                }
            }

            val info = TokocryptoSymbolInfo(
                symbol = rawSymbol,
                baseAsset = baseAsset,
                quoteAsset = quoteAsset,
                symbolType = symbolType,
                basePrecision = basePrecision,
                quotePrecision = quotePrecision,
                spotTradingEnable = spotTradingEnable,
                defaultSelfTradePreventionMode = defaultStp,
                priceFilter = priceFilter,
                lotSizeFilter = lotSizeFilter,
                marketLotSizeFilter = marketLotSizeFilter,
                minNotionalFilter = minNotionalFilter
            )

            val noUnderscore = rawSymbol.replace("_", "").replace("-", "")
            val underscore = "${baseAsset}_$quoteAsset"

            newMap[rawSymbol] = info
            newMap[noUnderscore] = info
            newMap[underscore] = info
        }

        return newMap
    }


    private suspend fun fetchExecutionRulesQuietly() {
        try {
            val req = Request.Builder()
                .url(TOKOCRYPTO_EXECUTION_RULES_URL)
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val root = JSONObject(body)
                    val rulesArray = root.optJSONArray("data") ?: root.optJSONArray("symbols")
                    if (rulesArray != null) {
                        for (i in 0 until rulesArray.length()) {
                            val r = rulesArray.optJSONObject(i) ?: continue
                            val sym = r.optString("symbol", "").uppercase()
                            val rule = TokocryptoExecutionRules(
                                symbol = sym,
                                bidLimitMultUp = r.optString("bidLimitMultUp", "1.1").toDoubleOrNull() ?: 1.1,
                                bidLimitMultDown = r.optString("bidLimitMultDown", "0.9").toDoubleOrNull() ?: 0.9,
                                askLimitMultUp = r.optString("askLimitMultUp", "1.1").toDoubleOrNull() ?: 1.1,
                                askLimitMultDown = r.optString("askLimitMultDown", "0.9").toDoubleOrNull() ?: 0.9
                            )
                            executionRulesMap[sym] = rule
                            symbolsMap[sym]?.executionRules = rule
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun getSymbolInfo(rawSymbol: String): TokocryptoSymbolInfo? {
        val clean = rawSymbol.trim().uppercase()
        val direct = symbolsMap[clean]
        if (direct != null) return direct

        val noUnderscore = clean.replace("_", "").replace("/", "").replace("-", "")
        val directNoUnder = symbolsMap[noUnderscore]
        if (directNoUnder != null) return directNoUnder

        return null
    }

    fun getSymbolType(symbol: String): Int {
        return getSymbolInfo(symbol)?.symbolType ?: 1
    }

    fun getAllTradingPairs(quoteFilter: String? = null): List<TradingPair> {
        val uniqueSymbols = symbolsMap.values
            .filter {
                it.spotTradingEnable &&
                    (it.quoteAsset == "IDR" || it.quoteAsset == "USDT")
            }
            .distinctBy { it.symbol }
        val filtered = if (quoteFilter.isNullOrBlank() || quoteFilter.equals("ALL", true)) {
            uniqueSymbols
        } else {
            val q = quoteFilter.uppercase()
            uniqueSymbols.filter { it.quoteAsset.equals(q, true) }
        }
        return filtered.map { it.toTradingPair() }
    }

    fun searchSymbols(query: String, quoteFilter: String? = null): List<TradingPair> {
        val q = query.trim().uppercase()
        val all = getAllTradingPairs(quoteFilter)
        if (q.isBlank()) return all
        return all.filter {
            it.symbol.contains(q) || it.baseAsset.contains(q) || it.displayName.uppercase().contains(q)
        }
    }

/**
 * Memvalidasi order spot Tokocrypto berdasarkan filter trading resmi:
 * - LOT_SIZE
 * - MARKET_LOT_SIZE
 * - PRICE_FILTER
 * - MIN_NOTIONAL / NOTIONAL
 * - PRICE_RANGE execution rules
 *
 * PENTING:
 * Untuk real trading, metadata symbol wajib tersedia.
 * Tidak boleh fail-open menggunakan quantity mentah.
 */
fun validateOrder(
    symbol: String,
    price: Double,
    quantity: Double,
    isMarket: Boolean = false,
    isBuy: Boolean = true,
    referenceMarketPrice: Double = 0.0
): TokocryptoValidationResult {

    val info = getSymbolInfo(symbol)

    // Real order tidak boleh lolos tanpa metadata exchange.
    if (info == null) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = price,
            adjustedQty = quantity,
            reason = "Metadata symbol Tokocrypto belum tersedia untuk $symbol."
        )
    }

    if (!info.spotTradingEnable) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = price,
            adjustedQty = quantity,
            reason = "Spot trading untuk $symbol sedang tidak tersedia di Tokocrypto."
        )
    }

    if (!price.isFinite() || price <= 0.0) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = price,
            adjustedQty = quantity,
            reason = "Harga order tidak valid."
        )
    }

    if (!quantity.isFinite() || quantity <= 0.0) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = price,
            adjustedQty = quantity,
            reason = "Quantity order tidak valid."
        )
    }

    // =========================================================
    // 1. PRICE FILTER
    // =========================================================
    var adjPrice = price

    if (!isMarket) {
        val pf = info.priceFilter

        if (pf == null) {
            return TokocryptoValidationResult(
                isValid = false,
                adjustedPrice = price,
                adjustedQty = quantity,
                reason = "PRICE_FILTER $symbol belum tersedia."
            )
        }

        if (adjPrice < pf.minPrice) {
            return TokocryptoValidationResult(
                isValid = false,
                adjustedPrice = adjPrice,
                adjustedQty = quantity,
                reason = "Harga di bawah minimum Tokocrypto: ${pf.minPrice}"
            )
        }

        if (adjPrice > pf.maxPrice) {
            return TokocryptoValidationResult(
                isValid = false,
                adjustedPrice = adjPrice,
                adjustedQty = quantity,
                reason = "Harga di atas maksimum Tokocrypto: ${pf.maxPrice}"
            )
        }

        adjPrice = info.formatPrice(adjPrice)

        // Pastikan harga hasil pembulatan masih berada di range.
        if (adjPrice < pf.minPrice || adjPrice > pf.maxPrice) {
            return TokocryptoValidationResult(
                isValid = false,
                adjustedPrice = adjPrice,
                adjustedQty = quantity,
                reason = "Harga setelah normalisasi PRICE_FILTER tidak valid."
            )
        }

        // =====================================================
        // PRICE RANGE / EXECUTION RULE
        // =====================================================
        val exec = info.executionRules

        if (exec != null && referenceMarketPrice > 0.0) {
            if (isBuy) {
                val maxBuy =
                    referenceMarketPrice * exec.bidLimitMultUp

                val minBuy =
                    referenceMarketPrice * exec.bidLimitMultDown

                if (adjPrice > maxBuy) {
                    return TokocryptoValidationResult(
                        isValid = false,
                        adjustedPrice = adjPrice,
                        adjustedQty = quantity,
                        reason =
                            "Harga BELI melebihi guard exchange " +
                            "(maksimum: $maxBuy)."
                    )
                }

                if (adjPrice < minBuy) {
                    return TokocryptoValidationResult(
                        isValid = false,
                        adjustedPrice = adjPrice,
                        adjustedQty = quantity,
                        reason =
                            "Harga BELI di bawah guard exchange " +
                            "(minimum: $minBuy)."
                    )
                }
            } else {
                val maxSell =
                    referenceMarketPrice * exec.askLimitMultUp

                val minSell =
                    referenceMarketPrice * exec.askLimitMultDown

                if (adjPrice > maxSell) {
                    return TokocryptoValidationResult(
                        isValid = false,
                        adjustedPrice = adjPrice,
                        adjustedQty = quantity,
                        reason =
                            "Harga JUAL melebihi guard exchange " +
                            "(maksimum: $maxSell)."
                    )
                }

                if (adjPrice < minSell) {
                    return TokocryptoValidationResult(
                        isValid = false,
                        adjustedPrice = adjPrice,
                        adjustedQty = quantity,
                        reason =
                            "Harga JUAL di bawah guard exchange " +
                            "(minimum: $minSell)."
                    )
                }
            }
        }
    }

    // =========================================================
    // 2. LOT SIZE / MARKET LOT SIZE
    // =========================================================
    val lot = if (isMarket) {
        info.marketLotSizeFilter ?: info.lotSizeFilter
    } else {
        info.lotSizeFilter
    }

    if (lot == null) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = quantity,
            reason =
                "Filter quantity Tokocrypto belum tersedia " +
                "untuk $symbol."
        )
    }

    if (lot.stepSize <= 0.0) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = quantity,
            reason =
                "stepSize quantity Tokocrypto tidak valid untuk $symbol."
        )
    }

    if (quantity < lot.minQty) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = quantity,
            reason =
                "Quantity $quantity di bawah minimum " +
                "${lot.minQty}."
        )
    }

    if (quantity > lot.maxQty) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = quantity,
            reason =
                "Quantity $quantity melebihi maksimum " +
                "${lot.maxQty}."
        )
    }

    /*
     * Normalisasi quantity memakai BigDecimal.
     *
     * Contoh:
     * quantity = 1.2330456226880395
     * stepSize = 0.0001
     *
     * hasil:
     * 1.2330
     */
    val rawQty = BigDecimal.valueOf(quantity)
    val step = BigDecimal.valueOf(lot.stepSize)

    val steps = rawQty
        .divide(step, 0, RoundingMode.FLOOR)

    val normalizedQty = steps
        .multiply(step)
        .stripTrailingZeros()

    val normalizedQtyDouble =
        normalizedQty.toDouble()

    // Setelah rounding ke bawah, cek lagi minimum.
    if (normalizedQtyDouble < lot.minQty) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = normalizedQtyDouble,
            reason =
                "Quantity setelah normalisasi ($normalizedQty) " +
                "menjadi di bawah minimum ${lot.minQty}."
        )
    }

    if (normalizedQtyDouble > lot.maxQty) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = normalizedQtyDouble,
            reason =
                "Quantity setelah normalisasi ($normalizedQty) " +
                "melebihi maksimum ${lot.maxQty}."
        )
    }

    // Pastikan benar-benar kelipatan stepSize.
    val remainder = normalizedQty.remainder(step)

    if (remainder.compareTo(BigDecimal.ZERO) != 0) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = normalizedQtyDouble,
            reason =
                "Quantity hasil normalisasi tidak sesuai stepSize " +
                "${lot.stepSize}."
        )
    }

    // =========================================================
    // 3. MIN NOTIONAL
    // =========================================================
    val effectivePrice =
        if (isMarket && referenceMarketPrice > 0.0) {
            referenceMarketPrice
        } else {
            adjPrice
        }

    val notional =
        effectivePrice * normalizedQtyDouble

    val minNotional =
        info.minNotionalFilter?.minNotional ?: 0.0

    if (minNotional > 0.0 && notional < minNotional) {
        return TokocryptoValidationResult(
            isValid = false,
            adjustedPrice = adjPrice,
            adjustedQty = normalizedQtyDouble,
            reason =
                "Nilai order setelah normalisasi ($notional) " +
                "di bawah minimum notional $minNotional."
        )
    }

    return TokocryptoValidationResult(
        isValid = true,
        adjustedPrice = adjPrice,
        adjustedQty = normalizedQtyDouble,
        reason =
            "Order valid. Quantity dinormalisasi terhadap " +
            "stepSize=${lot.stepSize}."
    )
}
}
