package agu.analys.util

import android.content.Context

/** Small local preference store for user configuration and learning progress. */
class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GROQ, value.trim()).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    fun getWatchlist(): Set<String> =
        prefs.getStringSet(KEY_WATCHLIST, emptySet())?.toSet() ?: emptySet()

    fun toggleWatchlist(symbol: String): Boolean {
        val set = getWatchlist().toMutableSet()
        val upper = symbol.uppercase()
        val added = if (set.remove(upper)) false else {
            set.add(upper)
            true
        }
        prefs.edit().putStringSet(KEY_WATCHLIST, set).apply()
        return added
    }

    fun isInWatchlist(symbol: String): Boolean = getWatchlist().contains(symbol.uppercase())

    /**
     * Manual ownership state for the currently held asset on Indodax.
     * Default is false because the app cannot read the user's private balance.
     */
    fun getOwnedSymbols(): Set<String> =
        prefs.getStringSet(KEY_OWNED_SYMBOLS, emptySet())?.toSet() ?: emptySet()

    fun isAssetOwned(symbol: String): Boolean =
        getOwnedSymbols().contains(symbol.uppercase())

    fun setAssetOwned(symbol: String, owned: Boolean) {
        val upper = symbol.uppercase()
        val set = getOwnedSymbols().toMutableSet()
        if (owned) set.add(upper) else set.remove(upper)
        prefs.edit().putStringSet(KEY_OWNED_SYMBOLS, set).apply()
    }

    fun getCompletedLearningLessons(): Set<Int> =
        prefs.getStringSet(KEY_LEARNING_COMPLETED, emptySet())
            ?.mapNotNull(String::toIntOrNull)
            ?.toSet()
            ?: emptySet()

    fun setLearningLessonCompleted(index: Int, completed: Boolean) {
        val set = getCompletedLearningLessons().toMutableSet()
        if (completed) set.add(index) else set.remove(index)
        prefs.edit().putStringSet(KEY_LEARNING_COMPLETED, set.map(Int::toString).toSet()).apply()
    }

    companion object {
        private const val PREFS_NAME = "krypto_analysis_prefs"
        private const val KEY_GROQ = "groq_api_key"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_WATCHLIST = "watchlist_symbols"
        private const val KEY_OWNED_SYMBOLS = "owned_asset_symbols"
        private const val KEY_LEARNING_COMPLETED = "learning_completed_lessons"
    }
}
