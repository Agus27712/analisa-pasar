package agu.analys.util

import android.content.Context
import agu.analys.BuildConfig
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataSource
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig

/** Local user configuration. Runtime market data is deliberately kept elsewhere. */
class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var strategyMode: StrategyMode
        get() = runCatching {
            val name = prefs.getString(KEY_STRATEGY_MODE, null)
            if (name != null) StrategyMode.valueOf(name)
            else if (isScalpingMode) StrategyMode.SCALPING else StrategyMode.SECOND_WAVE
        }.getOrDefault(StrategyMode.SCALPING)
        set(value) {
            prefs.edit().putString(KEY_STRATEGY_MODE, value.name).apply()
            isScalpingMode = (value == StrategyMode.SCALPING)
        }

    var groqApiKey: String
        get() {
            val saved = prefs.getString(KEY_GROQ, "").orEmpty()
            return if (saved.isNotBlank()) saved else try { BuildConfig.GROQ_API_KEY } catch (_: Throwable) { "" }
        }
        set(value) = prefs.edit().putString(KEY_GROQ, value.trim()).apply()

    var geminiApiKey: String
        get() {
            val saved = prefs.getString(KEY_GEMINI, "").orEmpty()
            return if (saved.isNotBlank()) saved else try { BuildConfig.GEMINI_API_KEY } catch (_: Throwable) { "" }
        }
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    var aiProvider: AiProvider
        get() = runCatching { AiProvider.valueOf(prefs.getString(KEY_AI_PROVIDER, AiProvider.GROQ.name).orEmpty()) }.getOrDefault(AiProvider.GROQ)
        set(value) = prefs.edit().putString(KEY_AI_PROVIDER, value.name).apply()

    var marketDataSource: MarketDataSource
        get() = runCatching { MarketDataSource.valueOf(prefs.getString(KEY_MARKET_SOURCE, MarketDataSource.INDODAX.name).orEmpty()) }.getOrDefault(MarketDataSource.INDODAX)
        set(value) = prefs.edit().putString(KEY_MARKET_SOURCE, value.name).apply()

    var isScalpingMode: Boolean
        get() = prefs.getBoolean(KEY_SCALPING_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCALPING_MODE, value).apply()

    var scalpingSensitivity: ScalpingSensitivity
        get() = runCatching { ScalpingSensitivity.valueOf(prefs.getString(KEY_SCALPING_SENSITIVITY, ScalpingSensitivity.CONSERVATIVE.name).orEmpty()) }.getOrDefault(ScalpingSensitivity.CONSERVATIVE)
        set(value) = prefs.edit().putString(KEY_SCALPING_SENSITIVITY, value.name).apply()

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

    var updateRepo: String
        get() = prefs.getString(KEY_UPDATE_REPO, "agus27712/analisa-pasar").orEmpty().ifBlank { "agus27712/analisa-pasar" }
        set(value) = prefs.edit().putString(KEY_UPDATE_REPO, value.trim()).apply()

    var updateGitHubToken: String
        get() = prefs.getString(KEY_UPDATE_GH_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_UPDATE_GH_TOKEN, value.trim()).apply()

    fun getWatchlist(): Set<String> {
        val saved = prefs.getStringSet(KEY_WATCHLIST_INDODAX, null)
        if (saved != null && saved.isNotEmpty()) return saved.toSet()
        val legacy = prefs.getStringSet(KEY_WATCHLIST_LEGACY, null)
        if (legacy != null && legacy.isNotEmpty()) return legacy.toSet()
        return setOf("BTCIDR")
    }

    fun toggleWatchlist(symbol: String): Boolean {
        val set = getWatchlist().toMutableSet()
        val upper = symbol.uppercase()
        val added = if (set.remove(upper)) false else { set.add(upper); true }
        prefs.edit().putStringSet(KEY_WATCHLIST_INDODAX, set).apply()
        return added
    }

    fun isInWatchlist(symbol: String): Boolean =
        getWatchlist().contains(symbol.uppercase())

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
        private const val KEY_MARKET_SOURCE = "market_data_source"
        private const val KEY_STRATEGY_MODE = "strategy_mode"
        private const val KEY_SCALPING_MODE = "scalping_mode"
        private const val KEY_SCALPING_SENSITIVITY = "scalping_sensitivity"
        private const val KEY_WATCHLIST_LEGACY = "watchlist_symbols"
        private const val KEY_WATCHLIST_INDODAX = "watchlist_symbols_indodax"
        private const val KEY_LEARNING_COMPLETED = "learning_completed_lessons"
        private const val KEY_BUY_MAKER = "fee_buy_maker"
        private const val KEY_BUY_TAKER = "fee_buy_taker"
        private const val KEY_SELL_MAKER = "fee_sell_maker"
        private const val KEY_SELL_TAKER = "fee_sell_taker"
        private const val KEY_UPDATE_REPO = "github_update_repo"
        private const val KEY_UPDATE_GH_TOKEN = "github_update_token"
    }
}
