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

/**
 * Repository Single Source of Truth (SSOT) untuk Dynamic Symbol Discovery Tokocrypto.
 * Mengambil daftar symbol, filter trading (LOT_SIZE, PRICE_FILTER, MIN_NOTIONAL),
 * dan precision secara dinamis dari API resmi:
 * GET https://www.tokocrypto.com/open/v1/common/symbols
 * Fallback: Binance Cloud Exchange Info.
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

    private const val TOKOCRYPTO_COMMON_SYMBOLS_URL = "https://www.tokocrypto.com/open/v1/common/symbols"
    private const val TOKOCRYPTO_EXECUTION_RULES_URL = "https://www.tokocrypto.site/api/v3/executionRules"
    private const val BINANCE_EXCHANGE_INFO_URL = "https://api.binance.com/api/v3/exchangeInfo"

    fun isReady(): Boolean = isInitialized.get() && symbolsMap.isNotEmpty()

    suspend fun ensureSymbolsLoaded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && isInitialized.get() && (now - lastSyncTime.get() < SYNC_TTL_MS)) {
            return@withContext true
        }

        syncMutex.withLock {
            if (!force && isInitialized.get() && (now - lastSyncTime.get() < SYNC_TTL_MS)) {
                return@withLock true
            }

            // 1. Coba fetch dari Tokocrypto /open/v1/common/symbols
            var success = fetchFromTokocrypto()
            if (!success) {
                Timber.w("Tokocrypto common symbols gagal, mencoba Binance Exchange Info fallback...")
                success = fetchFromBinanceFallback()
            }

            if (success) {
                isInitialized.set(true)
                lastSyncTime.set(System.currentTimeMillis())
                _symbolsState.value = symbolsMap.values.toList()

                // Fetch execution rules di background untuk price range guard
                fetchExecutionRulesQuietly()
            }
            success
        }
    }

    private suspend fun fetchFromTokocrypto(): Boolean {
        try {
            val req = Request.Builder()
                .url(TOKOCRYPTO_COMMON_SYMBOLS_URL)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TokoClient/3.5")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return false

                val root = JSONObject(body)
                val code = root.optInt("code", -1)
                val data = root.optJSONArray("data") ?: root.optJSONArray("symbols")

                if (code == 0 && data != null && data.length() > 0) {
                    parseTokocryptoSymbols(data)
                    Timber.i("TokocryptoSymbolRepository: Berhasil load ${symbolsMap.size} symbols dari Tokocrypto")
                    return true
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Gagal fetch Tokocrypto symbols: ${e.message}")
        }
        return false
    }

    private fun parseTokocryptoSymbols(dataArray: JSONArray) {
        val newMap = mutableMapOf<String, TokocryptoSymbolInfo>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol", "").trim().uppercase()
            if (symbol.isBlank()) continue

            val baseAsset = item.optString("baseAsset", "").trim().uppercase()
            val quoteAsset = item.optString("quoteAsset", "").trim().uppercase()

            // Eliminasi BIDR: hanya pair IDR dan USDT saja
            if (quoteAsset != "IDR" && quoteAsset != "USDT") continue
            if (baseAsset == "BIDR" || symbol.startsWith("BIDR") || symbol.contains("BIDR_") || symbol.contains("_BIDR")) continue

            val symbolType = item.optInt("symbolType", 1) // 1 = MBX, 3 = NextMe
            val basePrecision = item.optInt("basePrecision", 8)
            val quotePrecision = item.optInt("quotePrecision", 8)
            val spotTradingEnable = item.optBoolean("spotTradingEnable", true)
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
                symbol = symbol,
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

            newMap[symbol] = info
            // Masukkan variasi underscore e.g. BTC_BIDR
            val underscoreKey = "${baseAsset}_$quoteAsset"
            newMap[underscoreKey] = info
        }

        if (newMap.isNotEmpty()) {
            symbolsMap.clear()
            symbolsMap.putAll(newMap)
        }
    }

    private suspend fun fetchFromBinanceFallback(): Boolean {
        try {
            val req = Request.Builder()
                .url(BINANCE_EXCHANGE_INFO_URL)
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return false

                val root = JSONObject(body)
                val symbols = root.optJSONArray("symbols")
                if (symbols != null && symbols.length() > 0) {
                    val newMap = mutableMapOf<String, TokocryptoSymbolInfo>()
                    for (i in 0 until symbols.length()) {
                        val item = symbols.optJSONObject(i) ?: continue
                        val status = item.optString("status", "")
                        if (status != "TRADING") continue

                        val symbol = item.optString("symbol", "").trim().uppercase()
                        val baseAsset = item.optString("baseAsset", "").trim().uppercase()
                        val quoteAsset = item.optString("quoteAsset", "").trim().uppercase()

                        // Hanya simpan pair yang relevan untuk Tokocrypto (IDR dan USDT saja, eliminasi BIDR)
                        if (quoteAsset != "IDR" && quoteAsset != "USDT") continue
                        if (baseAsset == "BIDR" || symbol.startsWith("BIDR") || symbol.contains("BIDR_") || symbol.contains("_BIDR")) continue

                        val basePrecision = item.optInt("baseAssetPrecision", 8)
                        val quotePrecision = item.optInt("quotePrecision", 8)

                        var priceFilter: TokocryptoPriceFilter? = null
                        var lotSizeFilter: TokocryptoLotSizeFilter? = null
                        var minNotionalFilter: TokocryptoMinNotionalFilter? = null

                        val filters = item.optJSONArray("filters")
                        if (filters != null) {
                            for (f in 0 until filters.length()) {
                                val fObj = filters.optJSONObject(f) ?: continue
                                when (fObj.optString("filterType")) {
                                    "PRICE_FILTER" -> priceFilter = TokocryptoPriceFilter(
                                        minPrice = fObj.optString("minPrice", "0").toDoubleOrNull() ?: 0.0,
                                        maxPrice = fObj.optString("maxPrice", "0").toDoubleOrNull() ?: Double.MAX_VALUE,
                                        tickSize = fObj.optString("tickSize", "0").toDoubleOrNull() ?: 0.0
                                    )
                                    "LOT_SIZE" -> lotSizeFilter = TokocryptoLotSizeFilter(
                                        minQty = fObj.optString("minQty", "0").toDoubleOrNull() ?: 0.0,
                                        maxQty = fObj.optString("maxQty", "0").toDoubleOrNull() ?: Double.MAX_VALUE,
                                        stepSize = fObj.optString("stepSize", "0").toDoubleOrNull() ?: 0.0
                                    )
                                    "NOTIONAL", "MIN_NOTIONAL" -> minNotionalFilter = TokocryptoMinNotionalFilter(
                                        minNotional = fObj.optString("minNotional", "0").toDoubleOrNull() ?: 0.0
                                    )
                                }
                            }
                        }

                        val info = TokocryptoSymbolInfo(
                            symbol = symbol,
                            baseAsset = baseAsset,
                            quoteAsset = quoteAsset,
                            symbolType = 1,
                            basePrecision = basePrecision,
                            quotePrecision = quotePrecision,
                            spotTradingEnable = true,
                            priceFilter = priceFilter,
                            lotSizeFilter = lotSizeFilter,
                            minNotionalFilter = minNotionalFilter
                        )
                        newMap[symbol] = info
                        newMap["${baseAsset}_$quoteAsset"] = info
                    }

                    if (newMap.isNotEmpty()) {
                        symbolsMap.clear()
                        symbolsMap.putAll(newMap)
                        Timber.i("TokocryptoSymbolRepository: Fallback Binance loaded ${symbolsMap.size} symbols")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Gagal fetch fallback Binance symbols: ${e.message}")
        }
        return false
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

        // Jika ada input lama dengan BIDR, mapping ke IDR
        if (noUnderscore.endsWith("BIDR")) {
            val idrKey = noUnderscore.removeSuffix("BIDR") + "IDR"
            return symbolsMap[idrKey] ?: symbolsMap["${noUnderscore.removeSuffix("BIDR")}_IDR"]
        }
        return null
    }

    fun getSymbolType(symbol: String): Int {
        return getSymbolInfo(symbol)?.symbolType ?: 1
    }

    fun getAllTradingPairs(quoteFilter: String? = null): List<TradingPair> {
        val uniqueSymbols = symbolsMap.values
            .filter {
                (it.quoteAsset == "IDR" || it.quoteAsset == "USDT") &&
                    it.baseAsset != "BIDR" &&
                    !it.symbol.startsWith("BIDR") &&
                    !it.symbol.contains("_BIDR") &&
                    !it.symbol.contains("BIDR_")
            }
            .distinctBy { it.symbol }
        val filtered = if (quoteFilter.isNullOrBlank() || quoteFilter.equals("ALL", true)) {
            uniqueSymbols
        } else {
            val q = quoteFilter.uppercase().replace("BIDR", "IDR")
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
     * Memvalidasi order spot Tokocrypto berdasarkan filter trading resmi
     * (LOT_SIZE, PRICE_FILTER, MIN_NOTIONAL, dan PRICE_RANGE execution rules).
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
        if (info == null) {
            return TokocryptoValidationResult(
                isValid = price > 0 && quantity > 0,
                adjustedPrice = price,
                adjustedQty = quantity,
                reason = if (price > 0 && quantity > 0) "Symbol metadata belum termuat, lolos validasi dasar." else "Harga dan jumlah harus positif."
            )
        }

        // 1. Validasi & Penyesuaian Harga (PRICE_FILTER)
        var adjPrice = price
        if (!isMarket) {
            val pf = info.priceFilter
            if (pf != null) {
                if (adjPrice < pf.minPrice) {
                    return TokocryptoValidationResult(false, adjPrice, quantity, "Harga di bawah batas minimum exchange: ${pf.minPrice}")
                }
                if (adjPrice > pf.maxPrice) {
                    return TokocryptoValidationResult(false, adjPrice, quantity, "Harga di atas batas maksimum exchange: ${pf.maxPrice}")
                }
                adjPrice = info.formatPrice(adjPrice)
            }

            // Validasi Execution Rule (Price Range Multiplier)
            val exec = info.executionRules
            if (exec != null && referenceMarketPrice > 0.0) {
                if (isBuy) {
                    val maxBuy = referenceMarketPrice * exec.bidLimitMultUp
                    val minBuy = referenceMarketPrice * exec.bidLimitMultDown
                    if (adjPrice > maxBuy) {
                        return TokocryptoValidationResult(false, adjPrice, quantity, "Harga BELI melebihi batas guard exchange (Maks: ${maxBuy.toLong()})")
                    }
                } else {
                    val maxSell = referenceMarketPrice * exec.askLimitMultUp
                    val minSell = referenceMarketPrice * exec.askLimitMultDown
                    if (adjPrice < minSell) {
                        return TokocryptoValidationResult(false, adjPrice, quantity, "Harga JUAL di bawah batas guard exchange (Min: ${minSell.toLong()})")
                    }
                }
            }
        }

        // 2. Validasi & Penyesuaian Kuantitas (LOT_SIZE & MARKET_LOT_SIZE)
        var adjQty = quantity
        val lot = if (isMarket) (info.marketLotSizeFilter ?: info.lotSizeFilter) else info.lotSizeFilter
        if (lot != null) {
            if (adjQty < lot.minQty) {
                return TokocryptoValidationResult(false, adjPrice, adjQty, "Kuantitas di bawah minimum order LOT_SIZE: ${lot.minQty}")
            }
            if (adjQty > lot.maxQty) {
                return TokocryptoValidationResult(false, adjPrice, adjQty, "Kuantitas melebihi batas maksimum LOT_SIZE: ${lot.maxQty}")
            }
            adjQty = info.formatQuantity(adjQty)
        }

        // 3. Validasi Min Notional (Nilai Transaksi Minimum)
        val notional = if (isMarket && referenceMarketPrice > 0) referenceMarketPrice * adjQty else adjPrice * adjQty
        val minNotional = info.minNotionalFilter?.minNotional ?: 0.0
        if (minNotional > 0.0 && notional < minNotional) {
            return TokocryptoValidationResult(false, adjPrice, adjQty, "Nilai total order (${notional.toLong()}) di bawah Notional Minimum exchange: ${minNotional.toLong()}")
        }

        return TokocryptoValidationResult(
            isValid = true,
            adjustedPrice = adjPrice,
            adjustedQty = adjQty,
            reason = "Lolos validasi aturan exchange Tokocrypto."
        )
    }
}
