package agu.analys.trading

import android.content.Context

/**
 * Stores the user's manually entered position details per coin.
 * It never connects to Indodax and does not imply that a trade was executed.
 */
data class PositionDetails(
    val investedAmount: Double = 0.0,
    val entryPrice: Double = 0.0,
    val quantity: Double = 0.0
)

class PositionDetailsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(symbol: String): PositionDetails {
        val key = normalize(symbol)
        return PositionDetails(
            investedAmount = prefs.getString("${key}_invested", null)?.toDoubleOrNull() ?: 0.0,
            entryPrice = prefs.getString("${key}_entry", null)?.toDoubleOrNull() ?: 0.0,
            quantity = prefs.getString("${key}_quantity", null)?.toDoubleOrNull() ?: 0.0
        )
    }

    fun save(symbol: String, investedAmount: Double, entryPrice: Double) {
        val key = normalize(symbol)
        val safeInvested = investedAmount.coerceAtLeast(0.0)
        val safeEntry = entryPrice.coerceAtLeast(0.0)
        val quantity = if (safeInvested > 0.0 && safeEntry > 0.0) safeInvested / safeEntry else 0.0
        prefs.edit()
            .putString("${key}_invested", safeInvested.toString())
            .putString("${key}_entry", safeEntry.toString())
            .putString("${key}_quantity", quantity.toString())
            .apply()
    }

    fun clear(symbol: String) {
        val key = normalize(symbol)
        prefs.edit()
            .remove("${key}_invested")
            .remove("${key}_entry")
            .remove("${key}_quantity")
            .apply()
    }

    private fun normalize(symbol: String): String =
        symbol.uppercase().replace(Regex("[^A-Z0-9_]"), "_")

    companion object {
        private const val PREFS_NAME = "analysis_ui_position_details"
    }
}
