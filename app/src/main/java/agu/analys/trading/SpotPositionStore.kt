package agu.analys.trading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local spot-position state. A signal never means an order was executed.
 * The user explicitly confirms ownership after trading on Indodax via the
 * manual position form. Default is NO_POSITION because the app cannot read balances.
 *
 * Ownership changes are timestamped so signal history can reconstruct
 * the position state that existed when each signal was emitted.
 */
enum class SpotPositionState {
    NO_POSITION,
    HOLDING
}

data class SpotPosition(
    val state: SpotPositionState = SpotPositionState.NO_POSITION,
    val investedAmount: Double = 0.0,
    val entryPrice: Double = 0.0,
    val quantity: Double = 0.0,
    val openedAt: Long = 0L
) {
    val isHolding: Boolean get() = state == SpotPositionState.HOLDING
}

class SpotPositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(symbol: String): SpotPosition {
        val key = normalize(symbol)
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

    /** Reconstruct the position state at a historical timestamp. */
    fun getAt(symbol: String, timestamp: Long): SpotPosition {
        val key = normalize(symbol)
        val history = readHistory(key)
        if (history.length() == 0) {
            val current = get(symbol)
            if (current.isHolding && current.openedAt > 0L && current.openedAt <= timestamp) return current
            return SpotPosition()
        }

        var best: JSONObject? = null
        for (i in 0 until history.length()) {
            val event = history.optJSONObject(i) ?: continue
            val eventTime = event.optLong("timestamp", 0L)
            if (eventTime <= 0L || eventTime > timestamp) continue
            if (best == null || eventTime > best!!.optLong("timestamp", 0L)) best = event
        }

        if (best == null) return SpotPosition()
        val state = best.optString("state", SpotPositionState.NO_POSITION.name)
            .let { runCatching { SpotPositionState.valueOf(it) }.getOrDefault(SpotPositionState.NO_POSITION) }
        return SpotPosition(
            state = state,
            investedAmount = best.optDouble("investedAmount", 0.0),
            entryPrice = best.optDouble("entryPrice", 0.0),
            quantity = best.optDouble("quantity", 0.0),
            openedAt = best.optLong("openedAt", 0L)
        )
    }

    fun isHolding(symbol: String): Boolean = get(symbol).isHolding

    /**
     * Backward-compatible ownership update used by existing ViewModel call sites.
     * If a detailed position already exists, preserve its invested amount.
     */
    fun markBought(symbol: String, referenceEntryPrice: Double) {
        val current = get(symbol)
        markBought(symbol, current.investedAmount, referenceEntryPrice)
    }

    fun markBought(symbol: String, investedAmount: Double, entryPrice: Double) {
        val key = normalize(symbol)
        val openedAt = System.currentTimeMillis()
        val safeInvested = investedAmount.coerceAtLeast(0.0)
        val safeEntry = entryPrice.coerceAtLeast(0.0)
        val quantity = if (safeInvested > 0.0 && safeEntry > 0.0) safeInvested / safeEntry else 0.0
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            investedAmount = safeInvested,
            entryPrice = safeEntry,
            quantity = quantity,
            openedAt = openedAt
        )
        prefs.edit()
            .putString("${key}_state", SpotPositionState.HOLDING.name)
            .putString("${key}_invested", safeInvested.toString())
            .putString("${key}_entry", safeEntry.toString())
            .putString("${key}_quantity", quantity.toString())
            .putLong("${key}_opened_at", openedAt)
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    fun markSold(symbol: String) {
        val key = normalize(symbol)
        val changedAt = System.currentTimeMillis()
        val position = SpotPosition(state = SpotPositionState.NO_POSITION, openedAt = changedAt)
        prefs.edit()
            .putString("${key}_state", SpotPositionState.NO_POSITION.name)
            .remove("${key}_invested")
            .remove("${key}_entry")
            .remove("${key}_quantity")
            .remove("${key}_opened_at")
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
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
        history.put(event)
        while (history.length() > MAX_HISTORY_EVENTS) history.remove(0)
        return history.toString()
    }

    private fun normalize(symbol: String): String =
        symbol.uppercase().replace(Regex("[^A-Z0-9_]"), "_")

    companion object {
        private const val PREFS_NAME = "analysis_ui_spot_positions"
        private const val MAX_HISTORY_EVENTS = 50
    }
}
