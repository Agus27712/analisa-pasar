package agu.analys.util

import android.content.Context
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import org.json.JSONArray
import org.json.JSONObject

/** Persistent cache for last successful market data. Cached data is never treated as live data. */
class MarketDataCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var lastDashboardWriteAt = 0L
    private val lastPairWriteAt = mutableMapOf<String, Long>()

    fun saveDashboardTicks(ticks: Map<String, MarketTick>) {
        if (ticks.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastDashboardWriteAt < DASHBOARD_WRITE_INTERVAL_MS) return
        val arr = JSONArray(); ticks.values.forEach { arr.put(tickToJson(it)) }
        prefs.edit().putString(KEY_DASHBOARD_TICKS, arr.toString()).putLong(KEY_DASHBOARD_SAVED_AT, now).apply()
        lastDashboardWriteAt = now
    }

    fun loadDashboardTicks(): Map<String, MarketTick> {
        val raw = prefs.getString(KEY_DASHBOARD_TICKS, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            buildMap { for (i in 0 until arr.length()) jsonToTick(arr.getJSONObject(i))?.let { put(it.symbol, it) } }
        } catch (_: Exception) { emptyMap() }
    }

    fun saveWorthCoins(items: List<WorthCoinInfo>) {
        if (items.isEmpty()) return
        val arr = JSONArray(); items.forEach { w -> arr.put(JSONObject().put("symbol", w.pair.symbol).put("score", w.worthScore).put("isWorth", w.isWorthIt).put("rec", w.recommendation).put("potential", w.potentialProfitPct).put("rationale", w.aiRationale)) }
        prefs.edit().putString(KEY_WORTH_COINS, arr.toString()).putLong(KEY_WORTH_SAVED_AT, System.currentTimeMillis()).apply()
    }

    fun loadWorthCoins(): List<WorthCoinInfo> {
        val raw = prefs.getString(KEY_WORTH_COINS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i); val symbol = o.optString("symbol", "")
                    if (symbol.isNotBlank()) add(WorthCoinInfo(TradingPair.fromCustomSymbol(symbol), o.optInt("score", 0), o.optBoolean("isWorth", false), o.optString("rec", ""), o.optDouble("potential", 0.0), o.optString("rationale", "")))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun savePairSnapshot(symbol: String, tick: MarketTick?, candles: List<CandleBar>) {
        if (tick == null && candles.isEmpty()) return
        val normalized = symbol.uppercase(); val timeframe = inferTimeframe(candles); val key = KEY_PAIR_PREFIX + normalized + "_" + timeframe; val now = System.currentTimeMillis()
        if (now - (lastPairWriteAt[key] ?: 0L) < PAIR_WRITE_INTERVAL_MS) return
        val root = JSONObject(); if (tick != null) root.put("tick", tickToJson(tick))
        val cArr = JSONArray(); candles.takeLast(250).forEach { c -> cArr.put(JSONObject().put("t", c.timestamp).put("o", c.open).put("h", c.high).put("l", c.low).put("c", c.close).put("v", c.volume)) }
        root.put("candles", cArr).put("savedAt", now).put("timeframe", timeframe)
        prefs.edit().putString(key, root.toString()).apply(); lastPairWriteAt[key] = now
    }

    /** Loads the newest snapshot that is still fresh enough for its inferred timeframe. */
    fun loadPairSnapshot(symbol: String): Pair<MarketTick?, List<CandleBar>> {
        val prefix = KEY_PAIR_PREFIX + symbol.uppercase() + "_"
        val candidates = prefs.all.keys.filter { it.startsWith(prefix) }.mapNotNull { key ->
            prefs.getString(key, null)?.let { parseSnapshot(it) }
        }.sortedByDescending { it.savedAt }
        val chosen = candidates.firstOrNull { isFreshEnough(it) } ?: return null to emptyList()
        return chosen.tick to chosen.candles
    }

    fun dashboardCacheAgeMs(): Long { val at = prefs.getLong(KEY_DASHBOARD_SAVED_AT, 0L); return if (at <= 0) -1L else System.currentTimeMillis() - at }
    fun worthCacheAgeMs(): Long { val at = prefs.getLong(KEY_WORTH_SAVED_AT, 0L); return if (at <= 0) -1L else System.currentTimeMillis() - at }

    private data class Snapshot(val tick: MarketTick?, val candles: List<CandleBar>, val savedAt: Long)

    private fun parseSnapshot(raw: String): Snapshot? = try {
        val root = JSONObject(raw); val tick = root.optJSONObject("tick")?.let { jsonToTick(it) }; val cArr = root.optJSONArray("candles") ?: JSONArray()
        val candles = buildList { for (i in 0 until cArr.length()) { val o = cArr.getJSONObject(i); val close = o.optDouble("c", 0.0); if (close > 0) add(CandleBar(o.optLong("t", 0L), o.optDouble("o", close), o.optDouble("h", close), o.optDouble("l", close), close, o.optDouble("v", 0.0))) } }
        Snapshot(tick, candles, root.optLong("savedAt", 0L))
    } catch (_: Exception) { null }

    private fun isFreshEnough(snapshot: Snapshot): Boolean {
        if (snapshot.savedAt <= 0L || (snapshot.tick == null && snapshot.candles.isEmpty())) return false
        val age = System.currentTimeMillis() - snapshot.savedAt; val interval = candleIntervalMs(snapshot.candles)
        val maxAge = if (interval >= DAY_MS) 8L * DAY_MS else (interval * 6L).coerceIn(6L * 60L * 60L * 1000L, 48L * 60L * 60L * 1000L)
        return age <= maxAge
    }

    private fun inferTimeframe(candles: List<CandleBar>): String = when (val interval = candleIntervalMs(candles)) {
        in Long.MIN_VALUE..90_000L -> "1m"
        in 90_001L..420_000L -> "5m"
        in 420_001L..1_200_000L -> "15m"
        in 1_200_001L..5_400_000L -> "1h"
        in 5_400_001L..18_000_000L -> "4h"
        else -> "1d"
    }

    private fun candleIntervalMs(candles: List<CandleBar>): Long {
        if (candles.size < 2) return DAY_MS
        val times = candles.takeLast(6).map { it.timestamp }.sorted(); val diffs = times.zipWithNext().map { it.second - it.first }.filter { it > 0 }
        return diffs.sorted().getOrNull(diffs.size / 2) ?: DAY_MS
    }

    private fun tickToJson(t: MarketTick): JSONObject = JSONObject().put("symbol", t.symbol).put("price", t.price).put("high", t.high24h).put("low", t.low24h).put("vol", t.volume24h).put("change", if (t.change24h.isNaN()) JSONObject.NULL else t.change24h).put("ts", t.timestamp)

    private fun jsonToTick(o: JSONObject): MarketTick? {
        val price = o.optDouble("price", 0.0); if (price <= 0) return null; val raw = o.opt("change")
        val change = when (raw) { null, JSONObject.NULL -> Double.NaN; is Number -> raw.toDouble(); else -> o.optDouble("change", Double.NaN) }
        return MarketTick(o.optString("symbol", ""), price, o.optDouble("high", price), o.optDouble("low", price), o.optDouble("vol", 0.0), change, o.optLong("ts", System.currentTimeMillis()))
    }

    companion object {
        private const val PREFS_NAME = "krypto_market_cache"
        private const val KEY_DASHBOARD_TICKS = "dashboard_ticks_json"
        private const val KEY_DASHBOARD_SAVED_AT = "dashboard_saved_at"
        private const val KEY_WORTH_COINS = "worth_coins_json"
        private const val KEY_WORTH_SAVED_AT = "worth_coins_saved_at"
        private const val KEY_PAIR_PREFIX = "pair_"
        private const val DASHBOARD_WRITE_INTERVAL_MS = 15_000L
        private const val PAIR_WRITE_INTERVAL_MS = 15_000L
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
