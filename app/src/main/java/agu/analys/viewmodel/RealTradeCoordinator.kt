package agu.analys.viewmodel

import agu.analys.config.MarketDataSource
import agu.analys.database.AppDatabase
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.database.TradeHistoryRecordEntity
import agu.analys.service.IndodaxTradeApiV2
import agu.analys.service.TokocryptoTradeApi
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber
import kotlin.math.min

class RealTradeCoordinator(
    private val scope: CoroutineScope,
    private val prefs: AppPreferences,
    private val getLatestTick: (String) -> agu.analys.model.MarketTick?,
    private val onBalanceAndAvgUpdated: ((balances: Map<String, Double>, avgPrices: Map<String, Double>) -> Unit)? = null,
    private val onRealTradeExecuted: ((pair: String, type: String, price: Double, quantity: Double, tp1: Double, tp2: Double) -> Unit)? = null
) {
    private val MAX_HISTORY_ASSETS = 15
    private val INTER_REQUEST_DELAY_MS = 1500L
    private val REFRESH_COOLDOWN_MS = 30000L

    private val securityManager = RealTradeSecurityManager(scope, prefs)
    private val executor = RealTradeExecutor(
        getLatestTick = getLatestTick,
        scope = scope,
        prefs = prefs,
        onStatusUpdate = { _realTradeStatus.value = it },
        onRateLimit = { markRateLimited(it) },
        isRateLimited = { isRateLimitedNow() },
        refreshBalance = { 
            lastFetchTimeMs = 0L
            fetchRealBalance()
        },
        onRealTradeSuccess = { pair, type, price, qty, tp1, tp2 ->
            _realIndodaxBalance.value = prefs.getSavedRealBalance(prefs.marketDataSource.name)
            _realAvgBuyPrices.value = prefs.getSavedRealAvgBuyPrices(prefs.marketDataSource.name)
            onRealTradeExecuted?.invoke(pair, type, price, qty, tp1, tp2)
        }
    )

    private val _realIndodaxBalance = MutableStateFlow<Map<String, Double>>(prefs.getSavedRealBalance(prefs.marketDataSource.name))
    val realIndodaxBalance: StateFlow<Map<String, Double>> = _realIndodaxBalance.asStateFlow()

    private val _realFreeBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realFreeBalance: StateFlow<Map<String, Double>> = _realFreeBalance.asStateFlow()

    private val _realLockedBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realLockedBalance: StateFlow<Map<String, Double>> = _realLockedBalance.asStateFlow()

    private val _realAvgBuyPrices = MutableStateFlow<Map<String, Double>>(prefs.getSavedRealAvgBuyPrices(prefs.marketDataSource.name))
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = _realAvgBuyPrices.asStateFlow()

    private val _realAvgBuyPartial = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val realAvgBuyPartial: StateFlow<Map<String, Boolean>> = _realAvgBuyPartial.asStateFlow()

    private val _realTradeStatus = MutableStateFlow("Siap (${prefs.marketDataSource.label} API)")
    val realTradeStatus: StateFlow<String> = _realTradeStatus.asStateFlow()

    private val _isFetchingRealBalance = MutableStateFlow(false)
    val isFetchingRealBalance: StateFlow<Boolean> = _isFetchingRealBalance.asStateFlow()

    private var lastFetchTimeMs = 0L
    private var rateLimitedUntilMs = 0L

    init {
        scope.launch(Dispatchers.IO) {
            val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
            val hasCreds = if (isToko) prefs.hasTokocryptoCredentials() else prefs.hasIndodaxCredentials()
            if (hasCreds) {
                fetchRealBalance(force = true)
            }
        }
    }

    /**
     * Saldo real sesuai mata uang kuotasi pair yang sedang diperdagangkan.
     * - Pair USDT (BTCUSDT, ETHUSDT, ...) -> saldo USDT di exchange.
     * - Pair IDR  (BTCIDR, ETHIDR, ...)   -> saldo IDR di exchange.
     *
     * Mengembalikan 0.0 bila aset tersebut tidak ada di akun.
     */
    fun realBalanceForQuote(quoteAsset: String): Double {
        val map = _realIndodaxBalance.value
        return balanceOfQuote(map, quoteAsset)
    }

    /** Saldo real bebas (free) untuk kuotasi tertentu. */
    fun realFreeBalanceForQuote(quoteAsset: String): Double {
        val map = if (_realFreeBalance.value.isNotEmpty()) _realFreeBalance.value else _realIndodaxBalance.value
        return balanceOfQuote(map, quoteAsset)
    }

    /** Saldo real terkunci (locked) untuk kuotasi tertentu. */
    fun realLockedBalanceForQuote(quoteAsset: String): Double {
        val map = _realLockedBalance.value
        return balanceOfQuote(map, quoteAsset)
    }

    private fun balanceOfQuote(map: Map<String, Double>, quoteAsset: String): Double {
        val effectiveMap = if (map.isNotEmpty()) map else prefs.getSavedRealBalance(prefs.marketDataSource.name)
        if (effectiveMap.isEmpty()) return 0.0
        val q = quoteAsset.trim().uppercase()
        val isUsdt = q == "USDT" || q == "USD" || q == "USDC" || q == "BUSD"
        val keys = if (isUsdt) listOf("usdt", "USDT", "usd", "USD", "usdc", "USDC", "busd", "BUSD") else listOf("idr", "IDR", "idrt", "IDRT", "bidr", "BIDR")
        for (k in keys) {
            val v = effectiveMap[k]
            if (v != null && v > 0.0) return v
        }
        // Fallback: cari tidak case-sensitive
        for ((k, v) in effectiveMap) {
            if (k.equals(quoteAsset, ignoreCase = true) && v > 0.0) return v
        }
        return 0.0
    }

    fun updateAvgBuyPrice(coin: String, newAvgPrice: Double) {
        val asset = baseFromPair(coin)
        val current = _realAvgBuyPrices.value.toMutableMap()
        current[asset.lowercase()] = newAvgPrice
        current[asset.uppercase()] = newAvgPrice
        current["${asset.lowercase()}idr"] = newAvgPrice
        current["${asset.uppercase()}IDR"] = newAvgPrice
        _realAvgBuyPrices.value = current
        prefs.saveRealAvgBuyPrices(current, prefs.marketDataSource.name)
    }

    // Security delegation
    val isRealBuyEnabled: StateFlow<Boolean> = securityManager.isRealBuyEnabled
    val publicIp: StateFlow<String?> = securityManager.publicIp
    val isPinRequired: StateFlow<Boolean> = securityManager.isPinRequired
    val isPinUnlocked: StateFlow<Boolean> = securityManager.isPinUnlocked
    fun checkPublicIp() = securityManager.checkPublicIp()
    fun verifyPin(pin: String) = securityManager.verifyPin(pin)
    fun lockPin() = securityManager.lockPin()
    fun setRealBuyMode(enabled: Boolean, pin: String? = null) = securityManager.setRealBuyMode(enabled, pin)

    // Executor delegation
    fun executeCancelRealOrder(symbol: String, orderId: String, onResult: (Boolean, String) -> Unit) =
        executor.executeCancelOrder(symbol, orderId, onResult)
    fun executeRealTrade(p: String, t: String, pr: Double, a: Double, tp1: Double, tp2: Double, exchange: String? = null, cb: (Boolean, String) -> Unit) =
        executor.executeTrade(p, t, pr, a, tp1, tp2, exchange, cb)
    fun executeRealSellOrders(
        pair: String,
        totalQuantity: Double,
        marketPrice: Double,
        isAutoTpEnabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        onResult: (Boolean, String) -> Unit
    ) = executor.executeRealSellOrders(pair, totalQuantity, marketPrice, isAutoTpEnabled, tp1Price, tp1Percent, tp2Price, tp2Percent, onResult)
    fun executeRealAutoSellOnServer(p: String, tp1P: Double, tp1Pct: Double, tp2P: Double, tp2Pct: Double, cb: (Boolean, String) -> Unit) =
        executor.executeAutoSellOnServer(p, tp1P, tp1Pct, tp2P, tp2Pct, cb)

    private fun looksLikeRateLimit(msg: String): Boolean {
        return msg.contains("429") || msg.lowercase().contains("rate limit") || msg.lowercase().contains("too many requests")
    }

    private fun markRateLimited(msg: String) {
        rateLimitedUntilMs = System.currentTimeMillis() + 120_000L
        _realTradeStatus.value = "Rate-limit terdeteksi. $msg. Menunggu 2 menit."
    }

    private fun isRateLimitedNow(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs

    private fun baseFromPair(pair: String): String {
        val s = pair.lowercase().replace("_", "")
        return when {
            s.endsWith("idr") -> s.removeSuffix("idr")
            s.endsWith("usdt") -> s.removeSuffix("usdt")
            else -> s
        }
    }

    private fun buildHistoryCandidates(balance: Map<String, Double>): List<Pair<String, Double>> {
        val active = balance.filter { it.key != "idr" && it.value > 0.00000001 }
        prefs.rememberHistoryBases(active.keys)
        val ordered = linkedSetOf<String>()
        active.keys.forEach { ordered.add(it) }
        prefs.getRecentHistoryBases().forEach { ordered.add(it) }
        prefs.getWatchlist().forEach { sym ->
            val b = baseFromPair(sym)
            if (b.isNotBlank()) ordered.add(b)
        }
        return ordered.take(MAX_HISTORY_ASSETS).map { it to (balance[it] ?: 0.0) }
    }

    fun fetchRealBalance(force: Boolean = false) {
        val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
        val apiKey = if (isToko) prefs.tokocryptoApiKey else prefs.indodaxApiKey
        val secretKey = if (isToko) prefs.tokocryptoSecretKey else prefs.indodaxSecretKey
        val sourceLabel = prefs.marketDataSource.label

        if (apiKey.isBlank() || secretKey.isBlank()) {
            _realTradeStatus.value = "Kredensial API $sourceLabel belum diisi."
            return
        }
        if (_isFetchingRealBalance.value) return
        val now = System.currentTimeMillis()
        if (isRateLimitedNow()) {
            val waitSec = ((rateLimitedUntilMs - now) / 1000L).coerceAtLeast(1)
            _realTradeStatus.value = "Rate-limit aktif. Tunggu ~${waitSec}s lagi."
            return
        }
        if (!force && now - lastFetchTimeMs < REFRESH_COOLDOWN_MS && _realIndodaxBalance.value.isNotEmpty()) {
            _realTradeStatus.value = "Cache aktif (cooldown ${REFRESH_COOLDOWN_MS / 1000}s)."
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isFetchingRealBalance.value = true
            lastFetchTimeMs = now
            _realTradeStatus.value = "Memperbarui saldo $sourceLabel..."
            val (balances, message) = if (isToko) {
                TokocryptoTradeApi.getAccount(apiKey, secretKey)
            } else {
                IndodaxTradeApiV2.getAccount(apiKey, secretKey)
            }
            if (looksLikeRateLimit(message)) {
                markRateLimited(message); _isFetchingRealBalance.value = false; return@launch
            }
            _realTradeStatus.value = message
            if (balances != null) {
                _realIndodaxBalance.value = balances.total
                _realFreeBalance.value = balances.free
                _realLockedBalance.value = balances.locked
                prefs.saveRealBalance(balances.total, prefs.marketDataSource.name)
                onBalanceAndAvgUpdated?.invoke(balances.total, _realAvgBuyPrices.value)
                delay(INTER_REQUEST_DELAY_MS)
                if (fetchRealOpenOrdersSafe(apiKey, secretKey, balances, isToko)) {
                    delay(INTER_REQUEST_DELAY_MS)
                    fetchTradesAndAvgSafe(apiKey, secretKey, balances.total, isToko)
                }
            }
            _isFetchingRealBalance.value = false
        }
    }

    private suspend fun fetchRealOpenOrdersSafe(
        apiKey: String,
        secretKey: String,
        balances: IndodaxTradeApiV2.IndodaxBalances,
        isToko: Boolean
    ): Boolean {
        return try {
            val db = AppDatabase.getInstance().realTradeDao()
            val exchangeName = if (isToko) "TOKOCRYPTO" else "INDODAX"
            val entityMap = mutableMapOf<String, RealOpenOrderEntity>()

            if (!isToko) {
                val (okAll, rawAll) = IndodaxTradeApiV2.openOrders(apiKey, secretKey)
                if (!okAll && looksLikeRateLimit(rawAll)) { markRateLimited(rawAll); return false }
                if (okAll) parseOrdersToMap(rawAll, entityMap, exchangeName)
            } else {
                val (okAll, rawAll) = TokocryptoTradeApi.openOrders(apiKey, secretKey)
                if (!okAll && looksLikeRateLimit(rawAll)) { markRateLimited(rawAll); return false }
                if (okAll) parseOrdersToMap(rawAll, entityMap, exchangeName)
            }

            val candidates = linkedSetOf<String>()
            balances.locked.filter { it.key != "idr" && it.key != "usdt" && it.value > 0.0 }.keys.forEach { candidates.add(it) }
            prefs.getRecentHistoryBases().forEach { candidates.add(it) }
            prefs.getWatchlist().forEach { candidates.add(baseFromPair(it)) }

            for (base in candidates.take(15)) {
                if (base.isBlank()) continue
                delay(300)
                if (!isToko) {
                    val (okSym, rawSym) = IndodaxTradeApiV2.openOrders(apiKey, secretKey, "${base}idr")
                    if (!okSym && looksLikeRateLimit(rawSym)) { markRateLimited(rawSym); return false }
                    if (okSym) parseOrdersToMap(rawSym, entityMap, exchangeName)
                } else {
                    val symUsdt = "${base}_USDT".uppercase()
                    val (okSym, rawSym) = TokocryptoTradeApi.openOrders(apiKey, secretKey, symUsdt)
                    if (!okSym && looksLikeRateLimit(rawSym)) { markRateLimited(rawSym); return false }
                    if (okSym) parseOrdersToMap(rawSym, entityMap, exchangeName)
                }
            }

            db.clearOpenOrdersByExchange(exchangeName)
            if (entityMap.isNotEmpty()) {
                db.insertOpenOrders(entityMap.values.toList())
            }
            true
        } catch (e: Exception) {
            _realTradeStatus.value = "Gagal open orders: ${e.localizedMessage}"
            true
        }
    }

    private fun parseOrdersToMap(raw: String, map: MutableMap<String, RealOpenOrderEntity>, exchange: String) {
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val oId = obj.optString("orderId", "").ifBlank { obj.optString("id", "") }
                if (oId.isNotBlank()) {
                    val sideRaw = obj.optString("side", "")
                    val side = if (sideRaw == "0" || sideRaw.equals("BUY", true)) "BUY" else "SELL"
                    val typeRaw = obj.optString("type", "LIMIT")
                    val type = if (typeRaw == "1" || typeRaw.equals("LIMIT", true)) "LIMIT" else if (typeRaw == "2" || typeRaw.equals("MARKET", true)) "MARKET" else typeRaw.uppercase()
                    
                    map[oId] = RealOpenOrderEntity(
                        orderId = oId,
                        symbol = obj.optString("symbol", "").lowercase(),
                        side = side,
                        type = type,
                        price = obj.optString("price", "0").toDoubleOrNull() ?: obj.optDouble("price", 0.0),
                        quantity = obj.optString("origQty", "0").toDoubleOrNull() ?: obj.optDouble("origQty", 0.0),
                        executedQty = obj.optString("executedQty", "0").toDoubleOrNull() ?: obj.optDouble("executedQty", 0.0),
                        status = obj.optString("status", "OPEN").uppercase(),
                        time = obj.optLong("time", 0L).takeIf { it > 0L } ?: obj.optLong("createTime", System.currentTimeMillis()),
                        exchange = exchange
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun fetchTradesAndAvgSafe(
        apiKey: String,
        secretKey: String,
        balance: Map<String, Double>,
        isToko: Boolean
    ) {
        val candidates = buildHistoryCandidates(balance)
        val exchangeName = if (isToko) "TOKOCRYPTO" else "INDODAX"
        if (candidates.isEmpty()) {
            _realTradeStatus.value = "Saldo diperbarui. Tidak ada pair untuk histori."
            onBalanceAndAvgUpdated?.invoke(balance, _realAvgBuyPrices.value)
            return
        }
        val db = AppDatabase.getInstance().realTradeDao()
        val accumulatedEntities = mutableListOf<RealTradeEntity>()
        val newAvg = _realAvgBuyPrices.value.toMutableMap()
        val newPartial = _realAvgBuyPartial.value.toMutableMap()
        var rateLimited = false
        var fetchErrors = 0
        var assetsWithTrades = 0

        for ((index, entry) in candidates.withIndex()) {
            if (index > 0) delay(INTER_REQUEST_DELAY_MS)
            val asset = entry.first
            val currentQty = entry.second

            if (!isToko) {
                val (ok, raw) = try {
                    IndodaxTradeApiV2.myTrades(apiKey, secretKey, "${asset}idr", limit = 200)
                } catch (e: Exception) { fetchErrors++; continue }
                if (!ok) {
                    if (looksLikeRateLimit(raw)) { markRateLimited(raw); rateLimited = true; break }
                    fetchErrors++; continue
                }
                prefs.rememberHistoryBase(asset)
                val trades = IndodaxTradeApiV2.parseTradesList(raw)
                if (trades.isEmpty()) continue
                assetsWithTrades++
                var accBuyQty = 0.0; var accBuyCost = 0.0
                for (trade in trades) {
                    val id = IndodaxTradeApiV2.tradeIdOf(trade)
                    val isBuyer = IndodaxTradeApiV2.isBuyerOf(trade)
                    val tP = IndodaxTradeApiV2.tradePriceOf(trade)
                    val tQ = IndodaxTradeApiV2.tradeQtyOf(trade)
                    if (id.isBlank() || tP <= 0.0 || tQ <= 0.0) continue
                    accumulatedEntities.add(
                        RealTradeEntity(
                            id = id,
                            symbol = "${asset}idr",
                            price = tP,
                            qty = tQ,
                            amount = tP * tQ,
                            time = IndodaxTradeApiV2.tradeTimeMs(trade),
                            side = if (isBuyer) "BUY" else "SELL",
                            isBuyer = isBuyer,
                            exchange = exchangeName
                        )
                    )
                    if (isBuyer && currentQty > 0.0 && accBuyQty < currentQty) {
                        val qtyToUse = minOf(tQ, currentQty - accBuyQty)
                        accBuyQty += qtyToUse; accBuyCost += qtyToUse * tP
                    }
                }
                if (accBuyQty > 0.0) {
                    val avgP = accBuyCost / accBuyQty
                    newAvg[asset.lowercase()] = avgP
                    newAvg[asset.uppercase()] = avgP
                    newAvg["${asset.lowercase()}idr"] = avgP
                    newAvg["${asset.uppercase()}IDR"] = avgP
                    newPartial[asset] = accBuyQty + 1e-12 < currentQty
                }
            } else {
                // Tokocrypto: prioritaskan USDT pair, lalu BIDR pair
                val tokoSymbol = "${asset}_USDT".uppercase()
                val (ok, raw) = try {
                    TokocryptoTradeApi.myTrades(apiKey, secretKey, tokoSymbol, limit = 100)
                } catch (e: Exception) { fetchErrors++; continue }
                if (!ok) {
                    if (looksLikeRateLimit(raw)) { markRateLimited(raw); rateLimited = true; break }
                    fetchErrors++; continue
                }
                prefs.rememberHistoryBase(asset)
                val trades = TokocryptoTradeApi.parseTradesList(raw)
                if (trades.isEmpty()) continue
                assetsWithTrades++
                var accBuyQty = 0.0; var accBuyCost = 0.0
                for (trade in trades) {
                    val id = TokocryptoTradeApi.tradeIdOf(trade)
                    val isBuyer = TokocryptoTradeApi.isBuyerOf(trade)
                    val tP = TokocryptoTradeApi.tradePriceOf(trade)
                    val tQ = TokocryptoTradeApi.tradeQtyOf(trade)
                    if (id.isBlank() || tP <= 0.0 || tQ <= 0.0) continue
                    accumulatedEntities.add(
                        RealTradeEntity(
                            id = id,
                            symbol = "${asset.uppercase()}USDT",
                            price = tP,
                            qty = tQ,
                            amount = tP * tQ,
                            time = TokocryptoTradeApi.tradeTimeMs(trade),
                            side = if (isBuyer) "BUY" else "SELL",
                            isBuyer = isBuyer,
                            exchange = exchangeName
                        )
                    )
                    if (isBuyer && currentQty > 0.0 && accBuyQty < currentQty) {
                        val qtyToUse = minOf(tQ, currentQty - accBuyQty)
                        accBuyQty += qtyToUse; accBuyCost += qtyToUse * tP
                    }
                }
                if (accBuyQty > 0.0) {
                    val avgP = accBuyCost / accBuyQty
                    newAvg[asset.lowercase()] = avgP
                    newAvg[asset.uppercase()] = avgP
                    newAvg["${asset.lowercase()}usdt"] = avgP
                    newAvg["${asset.uppercase()}USDT"] = avgP
                    newPartial[asset] = accBuyQty + 1e-12 < currentQty
                }
            }
        }

        if (accumulatedEntities.isNotEmpty()) {
            val enrichedEntities = mutableListOf<RealTradeEntity>()
            val historyDao = AppDatabase.getInstance().tradeHistoryRecordDao()
            for (ent in accumulatedEntities) {
                val existing = db.getTradesBySymbolAndExchange(ent.symbol, exchangeName).firstOrNull { it.id == ent.id }
                if (existing?.signalSnapshotJson != null) {
                    enrichedEntities.add(existing)
                    continue
                }
                val norm = ent.symbol.uppercase().replace("_", "")
                val alt = if (isToko) "${norm}USDT" else "${norm}IDR"
                val records: List<TradeHistoryRecordEntity> = historyDao.getRecordsForSymbolAndExchange(norm, alt, exchangeName)
                val matched: TradeHistoryRecordEntity? = records.firstOrNull { rec: TradeHistoryRecordEntity ->
                    val timeDiff = java.lang.Math.abs(rec.buyTime - ent.time)
                    val sellTimeDiff = java.lang.Math.abs((rec.sellTime ?: 0L) - ent.time)
                    (ent.isBuyer && (rec.buyPrice == ent.price || timeDiff < 600000L)) ||
                    (!ent.isBuyer && (rec.sellPrice == ent.price || sellTimeDiff < 600000L))
                } ?: records.firstOrNull()

                if (matched != null) {
                    enrichedEntities.add(
                        ent.copy(
                            strategyMode = if (ent.strategyMode != "MANUAL") ent.strategyMode else matched.strategyMode,
                            holdingDurationMs = ent.holdingDurationMs ?: matched.holdingDurationMs,
                            entryPrice = ent.entryPrice ?: matched.buyPrice,
                            entryTimestamp = ent.entryTimestamp ?: matched.buyTime,
                            pnlIdr = ent.pnlIdr ?: matched.pnlIdr,
                            pnlPercent = ent.pnlPercent ?: matched.pnlPercent,
                            isTrailingUsed = ent.isTrailingUsed || matched.isTrailingUsed,
                            trailingLockPrice = ent.trailingLockPrice ?: matched.trailingLockPrice,
                            signalSnapshotJson = ent.signalSnapshotJson ?: matched.signalSnapshotJson
                        )
                    )
                } else {
                    enrichedEntities.add(ent)
                }
            }
            db.insertTrades(enrichedEntities)
        }
        _realAvgBuyPrices.value = newAvg
        _realAvgBuyPartial.value = newPartial
        prefs.saveRealAvgBuyPrices(newAvg, prefs.marketDataSource.name)
        onBalanceAndAvgUpdated?.invoke(balance, newAvg)
        if (!rateLimited) _realTradeStatus.value = "Saldo diperbarui. ${accumulatedEntities.size} trade dari $assetsWithTrades pair."
    }
}