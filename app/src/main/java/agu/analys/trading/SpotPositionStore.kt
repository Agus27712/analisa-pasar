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
    val openedAt: Long = 0L,
    val isTrailingEnabled: Boolean = false,
    val trailingPercent: Double = 0.0,
    val peakPrice: Double = 0.0,
    val trailingStopPrice: Double = 0.0,
    val isTrailingTriggered: Boolean = false
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
        val entry = prefs.getString("${key}_entry", null)?.toDoubleOrNull() ?: 0.0
        val peak = prefs.getString("${key}_peak", null)?.toDoubleOrNull() ?: entry
        val trailingPct = prefs.getString("${key}_trailing_pct", null)?.toDoubleOrNull() ?: 0.0
        val isTrailing = prefs.getBoolean("${key}_trailing_enabled", false)
        val isTriggered = prefs.getBoolean("${key}_trailing_triggered", false)
        val trailingStop = if (isTrailing && peak > 0.0 && trailingPct > 0.0) {
            peak * (1.0 - trailingPct / 100.0)
        } else 0.0

        return SpotPosition(
            state = state,
            investedAmount = prefs.getString("${key}_invested", null)?.toDoubleOrNull() ?: 0.0,
            entryPrice = entry,
            quantity = prefs.getString("${key}_quantity", null)?.toDoubleOrNull() ?: 0.0,
            openedAt = prefs.getLong("${key}_opened_at", 0L),
            isTrailingEnabled = isTrailing,
            trailingPercent = trailingPct,
            peakPrice = peak,
            trailingStopPrice = trailingStop,
            isTrailingTriggered = isTriggered
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

    fun setManualEntryPrice(symbol: String, entryPrice: Double, investedAmount: Double = 0.0) {
        val key = normalize(symbol)
        val safeEntry = entryPrice.coerceAtLeast(0.0)
        val current = get(symbol)
        val safeInvested = if (investedAmount > 0.0) {
            investedAmount
        } else if (current.quantity > 0.0 && safeEntry > 0.0) {
            current.quantity * safeEntry
        } else {
            current.investedAmount
        }
        val quantity = if (current.quantity > 0.0) {
            current.quantity
        } else if (safeInvested > 0.0 && safeEntry > 0.0) {
            safeInvested / safeEntry
        } else {
            0.0
        }
        val openedAt = if (current.openedAt > 0L) current.openedAt else System.currentTimeMillis()

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

    fun markBought(symbol: String, referenceEntryPrice: Double) {
        val current = get(symbol)
        if (current.isHolding && current.entryPrice == referenceEntryPrice) return
        markBought(symbol, current.investedAmount, referenceEntryPrice)
    }

    fun markBought(symbol: String, investedAmount: Double, entryPrice: Double) {
        val key = normalize(symbol)
        val openedAt = System.currentTimeMillis()
        val safeInvested = investedAmount.coerceAtLeast(0.0)
        val safeEntry = entryPrice.coerceAtLeast(0.0)
        val quantity = if (safeInvested > 0.0 && safeEntry > 0.0) safeInvested / safeEntry else 0.0
        val current = get(symbol)
        if (current.isHolding &&
            current.investedAmount == safeInvested &&
            current.entryPrice == safeEntry &&
            current.quantity == quantity
        ) return
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            investedAmount = safeInvested,
            entryPrice = safeEntry,
            quantity = quantity,
            openedAt = openedAt,
            peakPrice = safeEntry
        )
        prefs.edit()
            .putString("${key}_state", SpotPositionState.HOLDING.name)
            .putString("${key}_invested", safeInvested.toString())
            .putString("${key}_entry", safeEntry.toString())
            .putString("${key}_quantity", quantity.toString())
            .putString("${key}_peak", safeEntry.toString())
            .putBoolean("${key}_trailing_triggered", false)
            .putLong("${key}_opened_at", openedAt)
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    fun markSold(symbol: String) {
        val key = normalize(symbol)
        val current = get(symbol)
        if (!current.isHolding && current.entryPrice == 0.0 && current.investedAmount == 0.0 && current.quantity == 0.0) return
        val changedAt = System.currentTimeMillis()
        val position = SpotPosition(state = SpotPositionState.NO_POSITION, openedAt = changedAt)
        prefs.edit()
            .putString("${key}_state", SpotPositionState.NO_POSITION.name)
            .remove("${key}_invested")
            .remove("${key}_entry")
            .remove("${key}_quantity")
            .remove("${key}_opened_at")
            .remove("${key}_peak")
            .remove("${key}_trailing_pct")
            .remove("${key}_trailing_enabled")
            .remove("${key}_trailing_triggered")
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    fun setTrailingStop(symbol: String, enabled: Boolean, trailingPercent: Double, referencePrice: Double = 0.0) {
        val key = normalize(symbol)
        val current = get(symbol)
        val peak = if (current.peakPrice > 0.0) current.peakPrice else if (current.entryPrice > 0.0) current.entryPrice else referencePrice
        prefs.edit()
            .putBoolean("${key}_trailing_enabled", enabled)
            .putString("${key}_trailing_pct", trailingPercent.coerceAtLeast(0.5).toString())
            .putString("${key}_peak", peak.toString())
            .putBoolean("${key}_trailing_triggered", false)
            .apply()
    }

    /**
     * Updates peak price and checks if trailing stop is triggered.
     * Returns Pair<SpotPosition, Boolean(justTriggered)>
     */
    fun updateTrailingPrice(symbol: String, currentPrice: Double): Pair<SpotPosition, Boolean> {
        val current = get(symbol)
        if (!current.isHolding || !current.isTrailingEnabled || currentPrice <= 0.0) {
            return Pair(current, false)
        }

        val key = normalize(symbol)
        var newPeak = current.peakPrice.coerceAtLeast(current.entryPrice)
        var justTriggered = false

        if (currentPrice > newPeak) {
            newPeak = currentPrice
            prefs.edit().putString("${key}_peak", newPeak.toString()).apply()
        }

        val trailingStop = newPeak * (1.0 - current.trailingPercent / 100.0)
        if (currentPrice <= trailingStop && !current.isTrailingTriggered) {
            justTriggered = true
            prefs.edit().putBoolean("${key}_trailing_triggered", true).apply()
        }

        val updated = get(symbol)
        return Pair(updated, justTriggered)
    }

    fun resetTrailingTrigger(symbol: String) {
        val key = normalize(symbol)
        prefs.edit().putBoolean("${key}_trailing_triggered", false).apply()
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
