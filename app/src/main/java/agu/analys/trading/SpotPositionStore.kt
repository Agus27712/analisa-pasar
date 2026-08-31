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
            // HARD FLOOR: never below entry → Trailing Sell Limit (bukan Stop-Loss diskon)
            val raw = peak * (1.0 - trailingPct / 100.0)
            if (entry > 0.0) maxOf(raw, entry) else raw
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
