package agu.analys.trading

import android.content.Context

/**
 * Local spot-position state. A signal never means an order was executed.
 * The user explicitly confirms execution after trading on Indodax.
 */
enum class SpotPositionState {
    NO_POSITION,
    HOLDING
}

data class SpotPosition(
    val state: SpotPositionState = SpotPositionState.NO_POSITION,
    val entryPrice: Double = 0.0,
    val openedAt: Long = 0L
)

class SpotPositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(symbol: String): SpotPosition {
        val key = normalize(symbol)
        val state = prefs.getString("${key}_state", SpotPositionState.NO_POSITION.name)
            ?.let { runCatching { SpotPositionState.valueOf(it) }.getOrDefault(SpotPositionState.NO_POSITION) }
            ?: SpotPositionState.NO_POSITION
        return SpotPosition(
            state = state,
            entryPrice = prefs.getString("${key}_entry", null)?.toDoubleOrNull() ?: 0.0,
            openedAt = prefs.getLong("${key}_opened_at", 0L)
        )
    }

    fun markBought(symbol: String, referenceEntryPrice: Double) {
        val key = normalize(symbol)
        prefs.edit()
            .putString("${key}_state", SpotPositionState.HOLDING.name)
            .putString("${key}_entry", referenceEntryPrice.toString())
            .putLong("${key}_opened_at", System.currentTimeMillis())
            .apply()
    }

    fun markSold(symbol: String) {
        val key = normalize(symbol)
        prefs.edit()
            .putString("${key}_state", SpotPositionState.NO_POSITION.name)
            .remove("${key}_entry")
            .remove("${key}_opened_at")
            .apply()
    }

    private fun normalize(symbol: String): String = symbol.uppercase().replace(Regex("[^A-Z0-9_]"), "_")

    companion object {
        private const val PREFS_NAME = "analysis_ui_spot_positions"
    }
}
