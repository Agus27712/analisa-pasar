package agu.analys.util

import android.content.Context
import agu.analys.config.AiProvider
import agu.analys.config.TradingFeeConfig

/** Local user configuration. Runtime market data is deliberately kept elsewhere. */
class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GROQ, value.trim()).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    var aiProvider: AiProvider
        get() = runCatching { AiProvider.valueOf(prefs.getString(KEY_AI_PROVIDER, AiProvider.GROQ.name).orEmpty()) }.getOrDefault(AiProvider.GROQ)
        set(value) = prefs.edit().putString(KEY_AI_PROVIDER, value.name).apply()

    var isScalpingMode: Boolean
        get() = prefs.getBoolean(KEY_SCALPING_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCALPING_MODE, value).apply()

    var tradingFees: TradingFeeConfig
        get() = TradingFeeConfig(
            buyMakerPct = prefs.getString(KEY_BUY_MAKER, "0.11")?.toDoubleOrNull() ?: 0.11,
            buyTakerPct = prefs.getString(KEY_BUY_TAKER, "0.21")?.toDoubleOrNull() ?: 0.21,
            sellMakerPct = prefs.getString(KEY_SELL_MAKER, "0.32")?.toDoubleOrNull() ?: 0.32,
            sellTakerPct = prefs.getString(KEY_SELL_TAKER, "0.42")?.toDoubleOrNull() ?: 0.42
        )
        set(value) = prefs.edit()
            .putString(KEY_BUY_MAKER, value.buyMakerPct.toString())
            .putString(KEY_BUY_TAKER, value.buyTakerPct.toString())
            .putString(KEY_SELL_MAKER, value.sellMakerPct.toString())
            .putString(KEY_SELL_TAKER, value.sellTakerPct.toString())
            .apply()

    fun getWatchlist(): Set<String> = prefs.getStringSet(KEY_WATCHLIST, emptySet())?.toSet() ?: emptySet()

    fun toggleWatchlist(symbol: String): Boolean {
        val set = getWatchlist().toMutableSet()
        val upper = symbol.uppercase()
        val added = if (set.remove(upper)) false else { set.add(upper); true }
        prefs.edit().putStringSet(KEY_WATCHLIST, set).apply()
        return added
    }

    fun isInWatchlist(symbol: String): Boolean = getWatchlist().contains(symbol.uppercase())

    fun getCompletedLearningLessons(): Set<Int> = prefs.getStringSet(KEY_LEARNING_COMPLETED, emptySet())
        ?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()

    fun setLearningLessonCompleted(index: Int, completed: Boolean) {
        val set = getCompletedLearningLessons().toMutableSet()
        if (completed) set.add(index) else set.remove(index)
        prefs.edit().putStringSet(KEY_LEARNING_COMPLETED, set.map(Int::toString).toSet()).apply()
    }

    companion object {
        private const val PREFS_NAME = "krypto_analysis_prefs"
        private const val KEY_GROQ = "groq_api_key"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_SCALPING_MODE = "scalping_mode"
        private const val KEY_WATCHLIST = "watchlist_symbols"
        private const val KEY_LEARNING_COMPLETED = "learning_completed_lessons"
        private const val KEY_BUY_MAKER = "fee_buy_maker"
        private const val KEY_BUY_TAKER = "fee_buy_taker"
        private const val KEY_SELL_MAKER = "fee_sell_maker"
        private const val KEY_SELL_TAKER = "fee_sell_taker"
    }
}
