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
        private const val KEY_LEARNING_COMPLETED = "learning_completed_lessons"
    }
}
