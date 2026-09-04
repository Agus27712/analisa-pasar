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
    val isReal: Boolean = false,
    val isTrailingEnabled: Boolean = false,
    val trailingPercent: Double = 0.0,
    val peakPrice: Double = 0.0,
    val trailingStopPrice: Double = 0.0,
    val stopLossPrice: Double = 0.0,
    val isTrailingTriggered: Boolean = false,
    val isAutoSellEnabled: Boolean = false,
    val tp1Price: Double = 0.0,
    val tp1Percent: Double = 50.0,
    val tp2Price: Double = 0.0,
    val tp2Percent: Double = 100.0,
    val isTp1Triggered: Boolean = false,
    val isTp2Triggered: Boolean = false,
    val lastTrailingOrderId: String? = null,
    val lastOrderUpdateTime: Long = 0L
) {
    val isHolding: Boolean get() = state == SpotPositionState.HOLDING
}

class SpotPositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun calculateTrailingLimitPrice(peakPrice: Double, entryPrice: Double, trailingPercent: Double): Double {
        val rawStop = peakPrice * (1.0 - trailingPercent / 100.0)
        return maxOf(rawStop, entryPrice)
    }

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
            calculateTrailingLimitPrice(peak, entry, trailingPct)
        } else 0.0

        val lastTrailingOrderId = prefs.getString("${key}_last_trailing_order_id", null)
        val lastOrderUpdateTime = prefs.getLong("${key}_last_order_update_time", 0L)

        return SpotPosition(
            state = state,
            investedAmount = prefs.getString("${key}_invested", null)?.toDoubleOrNull() ?: 0.0,
            entryPrice = entry,
            quantity = prefs.getString("${key}_quantity", null)?.toDoubleOrNull() ?: 0.0,
            openedAt = prefs.getLong("${key}_opened_at", 0L),
            isReal = prefs.getBoolean("${key}_is_real", false),
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
            isTp1Triggered = prefs.getBoolean("${key}_tp1_triggered", false),
            isTp2Triggered = prefs.getBoolean("${key}_tp2_triggered", false),
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
            openedAt = best.optLong("openedAt", 0L),
            isReal = best.optBoolean("isReal", false)
        )
    }

    fun setHolding(symbol: String, invested: Double, entry: Double, quantity: Double, isReal: Boolean = false) {
        val key = normalize(symbol)
        val changedAt = System.currentTimeMillis()
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            investedAmount = invested,
            entryPrice = entry,
            quantity = quantity,
            openedAt = changedAt,
            isReal = isReal,
            peakPrice = entry
        )
        prefs.edit()
            .putString("${key}_state", SpotPositionState.HOLDING.name)
            .putString("${key}_invested", invested.toString())
            .putString("${key}_entry", entry.toString())
            .putString("${key}_quantity", quantity.toString())
            .putLong("${key}_opened_at", changedAt)
            .putBoolean("${key}_is_real", isReal)
            .putString("${key}_peak", entry.toString())
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    fun markBought(symbol: String, entryPrice: Double, invested: Double = 0.0, quantity: Double = 0.0, isReal: Boolean = false) {
        val finalInvested = if (invested > 0.0) invested else (if (quantity > 0.0 && entryPrice > 0.0) quantity * entryPrice else entryPrice)
        val finalQty = if (quantity > 0.0) quantity else (if (entryPrice > 0.0 && finalInvested > 0.0) finalInvested / entryPrice else (if (entryPrice > 0.0) 1.0 else 0.0))
        setHolding(symbol, invested = finalInvested, entry = entryPrice, quantity = finalQty, isReal = isReal)
    }

    fun markBought(symbol: String, invested: Double, entry: Double, isReal: Boolean = false) {
        val qty = if (entry > 0.0) invested / entry else 0.0
        setHolding(symbol, invested = invested, entry = entry, quantity = qty, isReal = isReal)
    }

    fun setManualEntryPrice(symbol: String, price: Double, amount: Double = 0.0, isReal: Boolean = false) {
        val qty = if (price > 0.0 && amount > 0.0) amount / price else (if (price > 0.0) 1.0 else 0.0)
        setHolding(symbol, invested = if (amount > 0.0) amount else price, entry = price, quantity = qty, isReal = isReal)
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
            .remove("${key}_is_real")
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
            prefs.edit()
                .putBoolean("${key}_trailing_enabled", false)
                .putBoolean("${key}_trailing_triggered", false)
                .remove("${key}_last_trailing_order_id")
                .remove("${key}_last_order_update_time")
                .apply()
            return
        }
        val current = get(symbol)
        val peak = when {
            referencePrice > 0.0 -> referencePrice
            current.entryPrice > 0.0 -> current.entryPrice
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

        // Rule 3: Peak price hanya di-update saat harga naik (tidak pernah turun)
        if (currentPrice > newPeak) {
            newPeak = currentPrice
            prefs.edit().putString("${key}_peak", newPeak.toString()).apply()
        }

        // Rule 1 & 2: Hard floor = entryPrice. Limit = max(peak * (1 - pct), entry)
        val trailingStop = calculateTrailingLimitPrice(newPeak, current.entryPrice, current.trailingPercent)
        
        // Rule 4: Saat harga turun menyentuh trailing price -> trigger
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
        tp2Percent: Double
    ) {
        val key = normalize(symbol)
        prefs.edit()
            .putBoolean("${key}_auto_sell_enabled", enabled)
            .putString("${key}_tp1_price", tp1Price.toString())
            .putString("${key}_tp1_percent", tp1Percent.toString())
            .putString("${key}_tp2_price", tp2Price.toString())
            .putString("${key}_tp2_percent", tp2Percent.toString())
            .putBoolean("${key}_tp1_triggered", false)
            .putBoolean("${key}_tp2_triggered", false)
            .apply()
    }

    fun markTp1Triggered(symbol: String) {
        prefs.edit().putBoolean("${normalize(symbol)}_tp1_triggered", true).apply()
    }

    fun markTp2Triggered(symbol: String) {
        prefs.edit().putBoolean("${normalize(symbol)}_tp2_triggered", true).apply()
    }

    fun getAllActiveTrailingSymbols(): List<String> {
        val result = mutableListOf<String>()
        val all = prefs.all
        for ((k, _) in all) {
            if (!k.endsWith("_state")) continue
            val prefix = k.removeSuffix("_state")
            val stateStr = prefs.getString(k, null) ?: continue
            val isTrailing = prefs.getBoolean("${prefix}_trailing_enabled", false)
            if (stateStr == SpotPositionState.HOLDING.name && isTrailing) {
                result.add(prefix)
            }
        }
        return result
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
            .put("isReal", position.isReal)
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
