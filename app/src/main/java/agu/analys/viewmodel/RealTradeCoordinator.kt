package agu.analys.viewmodel

import agu.analys.database.AppDatabase
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.service.IndodaxTradeApiV2
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
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
    private val prefs: AppPreferences
) {
    private val MAX_HISTORY_ASSETS = 15
    private val INTER_REQUEST_DELAY_MS = 1500L
    private val REFRESH_COOLDOWN_MS = 30000L

    private val securityManager = RealTradeSecurityManager(scope, prefs)
    private val executor = RealTradeExecutor(
        scope = scope,
        prefs = prefs,
        onStatusUpdate = { _realTradeStatus.value = it },
        onRateLimit = { markRateLimited(it) },
        isRateLimited = { isRateLimitedNow() },
        refreshBalance = { 
            lastFetchTimeMs = 0L
            fetchRealBalance()
        }
    )

    private val _realIndodaxBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realIndodaxBalance: StateFlow<Map<String, Double>> = _realIndodaxBalance.asStateFlow()

    private val _realFreeBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realFreeBalance: StateFlow<Map<String, Double>> = _realFreeBalance.asStateFlow()

    private val _realLockedBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realLockedBalance: StateFlow<Map<String, Double>> = _realLockedBalance.asStateFlow()

    private val _realAvgBuyPrices = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = _realAvgBuyPrices.asStateFlow()

    private val _realAvgBuyPartial = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val realAvgBuyPartial: StateFlow<Map<String, Boolean>> = _realAvgBuyPartial.asStateFlow()

    private val _realTradeStatus = MutableStateFlow("Siap (Indodax V2 API)")
    val realTradeStatus: StateFlow<String> = _realTradeStatus.asStateFlow()

    private val _isFetchingRealBalance = MutableStateFlow(false)
    val isFetchingRealBalance: StateFlow<Boolean> = _isFetchingRealBalance.asStateFlow()

    private var lastFetchTimeMs = 0L
    private var rateLimitedUntilMs = 0L

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
    fun executeRealTrade(p: String, t: String, pr: Long, a: Double, tp1: Double, tp2: Double, cb: (Boolean, String) -> Unit) =
        executor.executeTrade(p, t, pr, a, tp1, tp2, cb)
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

    fun fetchRealBalance() {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            _realTradeStatus.value = "Kredensial API INDODAX belum diisi."
            return
        }
        if (_isFetchingRealBalance.value) return
        val now = System.currentTimeMillis()
        if (isRateLimitedNow()) {
            val waitSec = ((rateLimitedUntilMs - now) / 1000L).coerceAtLeast(1)
            _realTradeStatus.value = "Rate-limit aktif. Tunggu ~${waitSec}s lagi."
            return
        }
        if (now - lastFetchTimeMs < REFRESH_COOLDOWN_MS && _realIndodaxBalance.value.isNotEmpty()) {
            _realTradeStatus.value = "Cache aktif (cooldown ${REFRESH_COOLDOWN_MS / 1000}s)."
            return
        }
        scope.launch {
            _isFetchingRealBalance.value = true
            lastFetchTimeMs = now
            _realTradeStatus.value = "Memperbarui saldo INDODAX..."
            val (balances, message) = IndodaxTradeApiV2.getAccount(apiKey, secretKey)
            if (looksLikeRateLimit(message)) {
                markRateLimited(message); _isFetchingRealBalance.value = false; return@launch
            }
            _realTradeStatus.value = message
            if (balances != null) {
                _realIndodaxBalance.value = balances.total
                _realFreeBalance.value = balances.free
                _realLockedBalance.value = balances.locked
                prefs.saveRealBalance(balances.total)
                delay(INTER_REQUEST_DELAY_MS)
                if (fetchRealOpenOrdersSafe(apiKey, secretKey, balances)) {
                    delay(INTER_REQUEST_DELAY_MS)
                    fetchTradesAndAvgSafe(apiKey, secretKey, balances.total)
                }
            }
            _isFetchingRealBalance.value = false
        }
    }

    private suspend fun fetchRealOpenOrdersSafe(apiKey: String, secretKey: String, balances: IndodaxTradeApiV2.IndodaxBalances): Boolean {
        return try {
            val db = AppDatabase.getInstance().realTradeDao()
            val entityMap = mutableMapOf<String, RealOpenOrderEntity>()
            val (okAll, rawAll) = IndodaxTradeApiV2.openOrders(apiKey, secretKey)
            if (!okAll && looksLikeRateLimit(rawAll)) { markRateLimited(rawAll); return false }
            if (okAll) parseOrdersToMap(rawAll, entityMap)
            val candidates = linkedSetOf<String>()
            balances.locked.filter { it.key != "idr" && it.value > 0.0 }.keys.forEach { candidates.add(it) }
            prefs.getRecentHistoryBases().forEach { candidates.add(it) }
            prefs.getWatchlist().forEach { candidates.add(baseFromPair(it)) }
            for (base in candidates.take(15)) {
                if (base.isBlank()) continue
                delay(300)
                val (okSym, rawSym) = IndodaxTradeApiV2.openOrders(apiKey, secretKey, "${base}idr")
                if (!okSym && looksLikeRateLimit(rawSym)) { markRateLimited(rawSym); return false }
                if (okSym) parseOrdersToMap(rawSym, entityMap)
            }
            db.clearOpenOrders()
            if (entityMap.isNotEmpty()) db.insertOpenOrders(entityMap.values.toList())
            true
        } catch (e: Exception) {
            _realTradeStatus.value = "Gagal open orders: ${e.localizedMessage}"; true
        }
    }

    private fun parseOrdersToMap(raw: String, map: MutableMap<String, RealOpenOrderEntity>) {
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val oId = obj.optString("orderId", "")
                if (oId.isNotBlank()) {
                    map[oId] = RealOpenOrderEntity(
                        orderId = oId, symbol = obj.optString("symbol", "").lowercase(),
                        side = obj.optString("side", "").uppercase(), type = obj.optString("type", "LIMIT").uppercase(),
                        price = obj.optString("price", "0").toDoubleOrNull() ?: 0.0,
                        quantity = obj.optString("origQty", "0").toDoubleOrNull() ?: 0.0,
                        executedQty = obj.optString("executedQty", "0").toDoubleOrNull() ?: 0.0,
                        status = obj.optString("status", "OPEN").uppercase(),
                        time = obj.optLong("time", System.currentTimeMillis())
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun fetchTradesAndAvgSafe(apiKey: String, secretKey: String, balance: Map<String, Double>) {
        val candidates = buildHistoryCandidates(balance)
        if (candidates.isEmpty()) {
            _realTradeStatus.value = "Saldo diperbarui. Tidak ada pair untuk histori."
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
                accumulatedEntities.add(RealTradeEntity(id, "${asset}idr", tP, tQ, tP * tQ, IndodaxTradeApiV2.tradeTimeMs(trade), if (isBuyer) "BUY" else "SELL", isBuyer))
                if (isBuyer && currentQty > 0.0 && accBuyQty < currentQty) {
                    val qtyToUse = minOf(tQ, currentQty - accBuyQty)
                    accBuyQty += qtyToUse; accBuyCost += qtyToUse * tP
                }
            }
            if (accBuyQty > 0.0) {
                newAvg[asset] = accBuyCost / accBuyQty
                newPartial[asset] = accBuyQty + 1e-12 < currentQty
            }
        }
        if (accumulatedEntities.isNotEmpty()) db.insertTrades(accumulatedEntities)
        _realAvgBuyPrices.value = newAvg; _realAvgBuyPartial.value = newPartial
        if (!rateLimited) _realTradeStatus.value = "Saldo diperbarui. ${accumulatedEntities.size} trade dari $assetsWithTrades pair."
    }
}
