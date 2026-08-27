package agu.analys.util

import android.content.Context
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

    /** Real Buy Mode Switch. Requires PIN verification before turning ON. */
    var isRealBuyModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_REAL_BUY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_REAL_BUY_MODE, value).apply()

    /** Security PIN SHA-256 Hash */
    var securityPinHash: String
        get() = prefs.getString(KEY_SECURITY_PIN_HASH, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SECURITY_PIN_HASH, value).apply()

    var failedPinAttempts: Int
        get() = prefs.getInt(KEY_FAILED_PIN_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_PIN_ATTEMPTS, value).apply()

    fun recordFailedPinAttempt(): Int {
        val next = failedPinAttempts + 1
        failedPinAttempts = next
        return next
    }

    fun resetFailedPinAttempts() {
        failedPinAttempts = 0
    }

    fun setSecurityPin(pin: String) {
        securityPinHash = hashPin(pin)
        resetFailedPinAttempts()
    }

    fun verifySecurityPin(pin: String): Boolean {
        val current = securityPinHash
        if (current.isBlank()) return false
        return current == hashPin(pin)
    }

    fun hasSecurityPin(): Boolean = securityPinHash.isNotBlank()

    fun hasIndodaxCredentials(): Boolean = indodaxApiKey.isNotBlank() && indodaxSecretKey.isNotBlank()

    fun wipeAllRealSecurityData() {
        prefs.edit()
            .remove(KEY_SECURITY_PIN_HASH)
            .remove(KEY_INDODAX_API_KEY)
            .remove(KEY_INDODAX_SECRET_KEY)
            .putBoolean(KEY_REAL_BUY_MODE, false)
            .putInt(KEY_FAILED_PIN_ATTEMPTS, 0)
            .apply()
    }

    /** Indodax API Key (Encrypted in SharedPreferences) */
    var indodaxApiKey: String
        get() = decryptSecret(prefs.getString(KEY_INDODAX_API_KEY, "").orEmpty())
        set(value) = prefs.edit().putString(KEY_INDODAX_API_KEY, encryptSecret(value.trim())).apply()

    /** Indodax Secret Key (Encrypted in SharedPreferences) */
    var indodaxSecretKey: String
        get() = decryptSecret(prefs.getString(KEY_INDODAX_SECRET_KEY, "").orEmpty())
        set(value) = prefs.edit().putString(KEY_INDODAX_SECRET_KEY, encryptSecret(value.trim())).apply()

    /** Keys hanya dari prefs user — TIDAK fallback BuildConfig (hindari bocor di APK). */
    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GROQ, value.trim()).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "").orEmpty()
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
        get() = runCatching { ScalpingSensitivity.valueOf(prefs.getString(KEY_SCALPING_SENSITIVITY, ScalpingSensitivity.BALANCED.name).orEmpty()) }.getOrDefault(ScalpingSensitivity.BALANCED)
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
        get() = prefs.getString(KEY_UPDATE_REPO, "agus27712/analisa-pasarv2").orEmpty().ifBlank { "agus27712/analisa-pasarv2" }
        set(value) = prefs.edit().putString(KEY_UPDATE_REPO, value.trim()).apply()

    var updateGitHubToken: String
        get() = prefs.getString(KEY_UPDATE_GH_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_UPDATE_GH_TOKEN, value.trim()).apply()

    /** UI compact: kurangi info overload di detail/dashboard. Default true. */
    var compactUi: Boolean
        get() = prefs.getBoolean(KEY_COMPACT_UI, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPACT_UI, value).apply()

    var isDarkTheme: Boolean
        get() = prefs.getBoolean("is_dark_theme", true)
        set(value) = prefs.edit().putBoolean("is_dark_theme", value).apply()

    fun clearApiKeys() {
        prefs.edit()
            .remove(KEY_GROQ)
            .remove(KEY_GEMINI)
            .remove(KEY_INDODAX_API_KEY)
            .remove(KEY_INDODAX_SECRET_KEY)
            .remove(KEY_UPDATE_GH_TOKEN)
            .apply()
    }

    private fun hashPin(pin: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest("agu_analys_pin_salt_$pin".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun encryptSecret(plainText: String): String {
        if (plainText.isBlank()) return ""
        return try {
            val salt = "agu_analys_secret_salt_v1_key"
            val bytes = plainText.toByteArray(Charsets.UTF_8)
            val saltBytes = salt.toByteArray(Charsets.UTF_8)
            val encrypted = ByteArray(bytes.size)
            for (i in bytes.indices) {
                encrypted[i] = (bytes[i].toInt() xor saltBytes[i % saltBytes.size].toInt()).toByte()
            }
            android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            plainText
        }
    }

    private fun decryptSecret(cipherText: String): String {
        if (cipherText.isBlank()) return ""
        return try {
            val decoded = android.util.Base64.decode(cipherText, android.util.Base64.NO_WRAP)
            val salt = "agu_analys_secret_salt_v1_key"
            val saltBytes = salt.toByteArray(Charsets.UTF_8)
            val decrypted = ByteArray(decoded.size)
            for (i in decoded.indices) {
                decrypted[i] = (decoded[i].toInt() xor saltBytes[i % saltBytes.size].toInt()).toByte()
            }
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            cipherText
        }
    }

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
        private const val KEY_COMPACT_UI = "compact_ui"
        private const val KEY_REAL_BUY_MODE = "real_buy_mode_enabled"
        private const val KEY_SECURITY_PIN_HASH = "security_pin_hash"
        private const val KEY_FAILED_PIN_ATTEMPTS = "failed_pin_attempts_count"
        private const val KEY_INDODAX_API_KEY = "indodax_encrypted_api_key"
        private const val KEY_INDODAX_SECRET_KEY = "indodax_encrypted_secret_key"
    }
}
