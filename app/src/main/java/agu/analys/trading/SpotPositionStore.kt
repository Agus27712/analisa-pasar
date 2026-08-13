package agu.analys.trading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SpotPositionState {
    NO_POSITION,
    HOLDING
}

data class PurchaseLot(
    val investedAmount: Double,
    val entryPrice: Double,
    val quantity: Double,
    val boughtAt: Long
)

data class SpotPosition(
    val state: SpotPositionState = SpotPositionState.NO_POSITION,
    val investedAmount: Double = 0.0,
    val entryPrice: Double = 0.0,
    val quantity: Double = 0.0,
    val openedAt: Long = 0L,
    val purchases: List<PurchaseLot> = emptyList()
) {
    val isHolding: Boolean get() = state == SpotPositionState.HOLDING
    val purchaseCount: Int get() = purchases.size
}

class SpotPositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(symbol: String): SpotPosition {
        val key = normalize(symbol)
        val purchases = readPurchases(key)
        if (purchases.isNotEmpty()) return aggregate(purchases)

        val state = prefs.getString("${key}_state", SpotPositionState.NO_POSITION.name)
            ?.let { runCatching { SpotPositionState.valueOf(it) }.getOrDefault(SpotPositionState.NO_POSITION) }
            ?: SpotPositionState.NO_POSITION
        return SpotPosition(
            state = state,
            investedAmount = prefs.getString("${key}_invested", null)?.toDoubleOrNull() ?: 0.0,
            entryPrice = prefs.getString("${key}_entry", null)?.toDoubleOrNull() ?: 0.0,
            quantity = prefs.getString("${key}_quantity", null)?.toDoubleOrNull() ?: 0.0,
            openedAt = prefs.getLong("${key}_opened_at", 0L)
        )
    }

    fun getAt(symbol: String, timestamp: Long): SpotPosition {
        val key = normalize(symbol)
        val history = readHistory(key)
        if (history.length() == 0) return historicalFallback(symbol, timestamp)

        var best: JSONObject? = null
        for (i in 0 until history.length()) {
            val event = history.optJSONObject(i) ?: continue
            val eventTime = event.optLong("timestamp", 0L)
            if (eventTime <= 0L || eventTime > timestamp) continue
            if (best == null || eventTime > best!!.optLong("timestamp", 0L)) best = event
        }
        if (best == null) return SpotPosition()

        val state = runCatching {
            SpotPositionState.valueOf(best.optString("state", SpotPositionState.NO_POSITION.name))
        }.getOrDefault(SpotPositionState.NO_POSITION)
        return SpotPosition(
            state = state,
            investedAmount = best.optDouble("investedAmount", 0.0),
            entryPrice = best.optDouble("entryPrice", 0.0),
            quantity = best.optDouble("quantity", 0.0),
            openedAt = best.optLong("openedAt", 0L),
            purchases = purchasesFromJson(best.optJSONArray("purchases"))
        )
    }

    fun isHolding(symbol: String): Boolean = get(symbol).isHolding

    /** Adds a new buy to the existing position. It never overwrites previous buys. */
    fun markBought(symbol: String, investedAmount: Double, entryPrice: Double) {
        val key = normalize(symbol)
        val safeInvested = investedAmount.coerceAtLeast(0.0)
        val safeEntry = entryPrice.coerceAtLeast(0.0)
        if (safeInvested <= 0.0 || safeEntry <= 0.0) return

        val boughtAt = System.currentTimeMillis()
        val lot = PurchaseLot(
            investedAmount = safeInvested,
            entryPrice = safeEntry,
            quantity = safeInvested / safeEntry,
            boughtAt = boughtAt
        )
        val purchases = readPurchases(key).toMutableList().apply { add(lot) }
        val position = aggregate(purchases)
        savePosition(key, position, purchases)
    }

    /** Keeps compatibility with existing callers that only know the entry price. */
    fun markBought(symbol: String, referenceEntryPrice: Double) {
        val current = get(symbol)
        if (current.isHolding && referenceEntryPrice <= 0.0) return
        markBought(symbol, current.investedAmount.takeIf { it > 0.0 } ?: 0.0, referenceEntryPrice)
    }

    fun markSold(symbol: String) {
        val key = normalize(symbol)
        val current = get(symbol)
        if (!current.isHolding) return
        val changedAt = System.currentTimeMillis()
        val position = SpotPosition(state = SpotPositionState.NO_POSITION, openedAt = changedAt)
        prefs.edit()
            .putString("${key}_state", SpotPositionState.NO_POSITION.name)
            .remove("${key}_invested")
            .remove("${key}_entry")
            .remove("${key}_quantity")
            .remove("${key}_opened_at")
            .remove("${key}_purchases")
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    private fun aggregate(purchases: List<PurchaseLot>): SpotPosition {
        if (purchases.isEmpty()) return SpotPosition()
        val invested = purchases.sumOf { it.investedAmount }
        val quantity = purchases.sumOf { it.quantity }
        val averageEntry = if (quantity > 0.0) invested / quantity else 0.0
        return SpotPosition(
            state = SpotPositionState.HOLDING,
            investedAmount = invested,
            entryPrice = averageEntry,
            quantity = quantity,
            openedAt = purchases.minOf { it.boughtAt },
            purchases = purchases
        )
    }

    private fun savePosition(key: String, position: SpotPosition, purchases: List<PurchaseLot>) {
        prefs.edit()
            .putString("${key}_state", position.state.name)
            .putString("${key}_invested", position.investedAmount.toString())
            .putString("${key}_entry", position.entryPrice.toString())
            .putString("${key}_quantity", position.quantity.toString())
            .putLong("${key}_opened_at", position.openedAt)
            .putString("${key}_purchases", purchasesToJson(purchases).toString())
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    private fun historicalFallback(symbol: String, timestamp: Long): SpotPosition {
        val current = get(symbol)
        return if (current.isHolding && current.openedAt > 0L && current.openedAt <= timestamp) current else SpotPosition()
    }

    private fun readPurchases(key: String): List<PurchaseLot> {
        val raw = prefs.getString("${key}_purchases", null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { purchasesFromJson(JSONArray(raw)) }.getOrElse { emptyList() }
    }

    private fun purchasesToJson(purchases: List<PurchaseLot>): JSONArray = JSONArray().apply {
        purchases.forEach { lot ->
            put(JSONObject()
                .put("investedAmount", lot.investedAmount)
                .put("entryPrice", lot.entryPrice)
                .put("quantity", lot.quantity)
                .put("boughtAt", lot.boughtAt))
        }
    }

    private fun purchasesFromJson(array: JSONArray?): List<PurchaseLot> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val invested = item.optDouble("investedAmount", 0.0)
                val entry = item.optDouble("entryPrice", 0.0)
                if (invested <= 0.0 || entry <= 0.0) continue
                add(PurchaseLot(invested, entry, item.optDouble("quantity", invested / entry), item.optLong("boughtAt", 0L)))
            }
        }
    }

    private fun readHistory(key: String): JSONArray {
        val raw = prefs.getString("${key}_history", null).orEmpty()
        return if (raw.isBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun appendHistoryEvent(key: String, position: SpotPosition): String {
        val history = readHistory(key)
        val event = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("state", position.state.name)
            .put("investedAmount", position.investedAmount)
            .put("entryPrice", position.entryPrice)
            .put("quantity", position.quantity)
            .put("openedAt", position.openedAt)
            .put("purchases", purchasesToJson(position.purchases))
        history.put(event)
        while (history.length() > MAX_HISTORY_EVENTS) history.remove(0)
        return history.toString()
    }

    private fun normalize(symbol: String): String = symbol.uppercase().replace(Regex("[^A-Z0-9_]"), "_")

    companion object {
        private const val PREFS_NAME = "analysis_ui_spot_positions"
        private const val MAX_HISTORY_EVENTS = 50
    }
}
