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
    val isTrailingTriggered: Boolean = false,
    val isAutoSellEnabled: Boolean = false,
    val tp1Price: Double = 0.0,
    val tp1Percent: Double = 50.0,
    val tp2Price: Double = 0.0,
    val tp2Percent: Double = 100.0,
    val stopLossPrice: Double = 0.0,
    val isTp1Triggered: Boolean = false,
    val isTp2Triggered: Boolean = false,
    val isSlTriggered: Boolean = false,
    val lastTrailingOrderId: String? = null,
    val lastOrderUpdateTime: Long = 0L
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

        val lastTrailingOrderId = prefs.getString("${key}_last_trailing_order_id", null)
        val lastOrderUpdateTime = prefs.getLong("${key}_last_order_update_time", 0L)

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
            isTrailingTriggered = isTriggered,
            isAutoSellEnabled = prefs.getBoolean("${key}_auto_sell_enabled", false),
            tp1Price = prefs.getString("${key}_tp1_price", null)?.toDoubleOrNull() ?: 0.0,
            tp1Percent = prefs.getString("${key}_tp1_percent", null)?.toDoubleOrNull() ?: 50.0,
            tp2Price = prefs.getString("${key}_tp2_price", null)?.toDoubleOrNull() ?: 0.0,
            tp2Percent = prefs.getString("${key}_tp2_percent", null)?.toDoubleOrNull() ?: 100.0,
            stopLossPrice = prefs.getString("${key}_stop_loss_price", null)?.toDoubleOrNull() ?: 0.0,
            isTp1Triggered = prefs.getBoolean("${key}_tp1_triggered", false),
            isTp2Triggered = prefs.getBoolean("${key}_tp2_triggered", false),
            isSlTriggered = prefs.getBoolean("${key}_sl_triggered", false),
            lastTrailingOrderId = lastTrailingOrderId,
            lastOrderUpdateTime = lastOrderUpdateTime
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
            .remove("${key}_auto_sell_enabled")
            .remove("${key}_tp1_price")
            .remove("${key}_tp1_percent")
            .remove("${key}_tp2_price")
            .remove("${key}_tp2_percent")
            .remove("${key}_stop_loss_price")
            .remove("${key}_tp1_triggered")
            .remove("${key}_tp2_triggered")
            .remove("${key}_sl_triggered")
            .remove("${key}_last_trailing_order_id")
            .remove("${key}_last_order_update_time")
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    fun setTrailingOrderIdAndUpdateTime(symbol: String, orderId: String?, updateTime: Long) {
        val key = normalize(symbol)
        prefs.edit()
            .putString("${key}_last_trailing_order_id", orderId)
            .putLong("${key}_last_order_update_time", updateTime)
            .apply()
    }

    fun setTrailingStop(symbol: String, enabled: Boolean, trailingPercent: Double, referencePrice: Double = 0.0) {
        val key = normalize(symbol)
        if (!enabled) {
            // Matikan trailing tanpa menyimpan peak lama (hindari false trigger saat ON lagi)
            prefs.edit()
                .putBoolean("${key}_trailing_enabled", false)
                .putBoolean("${key}_trailing_triggered", false)
                .remove("${key}_last_trailing_order_id")
                .remove("${key}_last_order_update_time")
                .apply()
            return
        }
        // Saat ON: selalu arm dari harga live (referencePrice), reset peak & triggered
        val current = get(symbol)
        val peak = when {
            referencePrice > 0.0 -> referencePrice
            current.entryPrice > 0.0 -> current.entryPrice
            current.peakPrice > 0.0 -> current.peakPrice
            else -> 0.0
        }
        prefs.edit()
            .putBoolean("${key}_trailing_enabled", true)
            .putString("${key}_trailing_pct", trailingPercent.coerceAtLeast(0.5).toString())
            .putString("${key}_peak", peak.toString())
            .putBoolean("${key}_trailing_triggered", false)
            .remove("${key}_last_trailing_order_id")
            .remove("${key}_last_order_update_time")
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

    fun setAutoSellParams(
        symbol: String,
        enabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        stopLossPrice: Double
    ) {
        val key = normalize(symbol)
        prefs.edit()
            .putBoolean("${key}_auto_sell_enabled", enabled)
            .putString("${key}_tp1_price", tp1Price.toString())
            .putString("${key}_tp1_percent", tp1Percent.toString())
            .putString("${key}_tp2_price", tp2Price.toString())
            .putString("${key}_tp2_percent", tp2Percent.toString())
            .putString("${key}_stop_loss_price", stopLossPrice.toString())
            .putBoolean("${key}_tp1_triggered", false)
            .putBoolean("${key}_tp2_triggered", false)
            .putBoolean("${key}_sl_triggered", false)
            .apply()
    }

    fun markTp1Triggered(symbol: String, triggered: Boolean = true) {
        val key = normalize(symbol)
        prefs.edit().putBoolean("${key}_tp1_triggered", triggered).apply()
    }

    fun markTp2Triggered(symbol: String, triggered: Boolean = true) {
        val key = normalize(symbol)
        prefs.edit().putBoolean("${key}_tp2_triggered", triggered).apply()
    }

    fun markSlTriggered(symbol: String, triggered: Boolean = true) {
        val key = normalize(symbol)
        prefs.edit().putBoolean("${key}_sl_triggered", triggered).apply()
    }

    fun deductQuantity(symbol: String, sellQty: Double) {
        val key = normalize(symbol)
        val current = get(symbol)
        if (current.isHolding) {
            val newQty = (current.quantity - sellQty).coerceAtLeast(0.0)
            if (newQty <= 0.0) {
                markSold(symbol)
            } else {
                val newInvested = (current.investedAmount * (newQty / current.quantity)).coerceAtLeast(0.0)
                prefs.edit()
                    .putString("${key}_quantity", newQty.toString())
                    .putString("${key}_invested", newInvested.toString())
                    .apply()
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
