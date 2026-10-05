package agu.analys.util

/** Label tampilan (Bahasa Indonesia) untuk output pipeline scalping. */
object ScalpingLabels {
    fun setup(name: String): String = when (name.uppercase()) {
        "BREAKOUT" -> "Breakout"
        "BREAKOUT_RETEST" -> "Breakout + Retest"
        "LIQUIDITY_SWEEP" -> "Liquidity Sweep"
        "TREND_PULLBACK" -> "Trend Pullback"
        "NONE", "" -> "Belum ada setup"
        else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
