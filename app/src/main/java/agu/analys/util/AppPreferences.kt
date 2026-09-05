package agu.analys.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataSource
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import org.json.JSONObject

/** Local user configuration. Runtime market data is deliberately kept elsewhere. */
class AppPreferences(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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

    var isRealBuyModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_REAL_BUY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_REAL_BUY_MODE, value).apply()

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

    var isPinResetRequired: Boolean
        get() = prefs.getBoolean(KEY_PIN_RESET_REQUIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_PIN_RESET_REQUIRED, value).apply()

    fun resetFailedPinAttempts() {
        failedPinAttempts = 0
    }

    fun setSecurityPin(pin: String) {
        securityPinHash = hashPin(pin)
        isPinResetRequired = false
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
            .remove(KEY_RECENT_HISTORY_BASES)
            .remove(KEY_PIN_RESET_REQUIRED)
            .putBoolean(KEY_REAL_BUY_MODE, false)
            .putInt(KEY_FAILED_PIN_ATTEMPTS, 0)
            .apply()
    }

    var indodaxApiKey: String
        get() = prefs.getString(KEY_INDODAX_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_INDODAX_API_KEY, value.trim()).apply()

    var indodaxSecretKey: String
        get() = prefs.getString(KEY_INDODAX_SECRET_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_INDODAX_SECRET_KEY, value.trim()).apply()

    /**
     * Base asset (btc, sol, xrp, …) yang pernah punya saldo / di-trade.
     * Dipakai biar histori tetap di-fetch setelah koin dijual habis.
     */
    fun getRecentHistoryBases(): List<String> =
        prefs.getString(KEY_RECENT_HISTORY_BASES, "")
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != "idr" && it != "usdt" }
            .distinct()

    fun rememberHistoryBases(bases: Collection<String>) {
        if (bases.isEmpty()) return
        val merged = (bases.map { it.lowercase().trim() }.filter {
            it.isNotBlank() && it != "idr" && it != "usdt"
        } + getRecentHistoryBases()).distinct().take(12)
        prefs.edit().putString(KEY_RECENT_HISTORY_BASES, merged.joinToString(",")).apply()
    }

    fun rememberHistoryBase(base: String) {
        rememberHistoryBases(listOf(base))
    }

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ, "").orEmpty().ifEmpty { agu.analys.BuildConfig.GROQ_API_KEY }
        set(value) = prefs.edit().putString(KEY_GROQ, value.trim()).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "").orEmpty().ifEmpty { agu.analys.BuildConfig.GEMINI_API_KEY }
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
        get() = runCatching { ScalpingSensitivity.valueOf(prefs.getString(KEY_SCALPING_SENSITIVITY, ScalpingSensitivity.AGGRESSIVE.name).orEmpty()) }.getOrDefault(ScalpingSensitivity.AGGRESSIVE)
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
        get() = DEFAULT_UPDATE_REPO
        set(_) { /* hardcoded to Agus27712/analisa-pasar */ }

    var updateGitHubToken: String
        get() = ""
        set(_) { /* hardcoded empty for public repo */ }

    var compactUi: Boolean
        get() = prefs.getBoolean(KEY_COMPACT_UI, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPACT_UI, value).apply()

    var isDarkTheme: Boolean
        get() = prefs.getBoolean("is_dark_theme", true)
        set(value) = prefs.edit().putBoolean("is_dark_theme", value).apply()

    var isNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()

    fun clearApiKeys() {
        prefs.edit()
            .remove(KEY_GROQ)
            .remove(KEY_GEMINI)
            .remove(KEY_INDODAX_API_KEY)
            .remove(KEY_INDODAX_SECRET_KEY)
            .remove(KEY_UPDATE_GH_TOKEN)
            .apply()
    }

    private fun getInstallationSalt(): ByteArray {
        var saltStr = prefs.getString(KEY_INSTALLATION_SALT, null)
        if (saltStr.isNullOrBlank()) {
            val randomBytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(randomBytes)
            saltStr = android.util.Base64.encodeToString(randomBytes, android.util.Base64.NO_WRAP)

            val oldHash = prefs.getString(KEY_SECURITY_PIN_HASH, "")
            if (!oldHash.isNullOrBlank()) {
                prefs.edit()
                    .putBoolean(KEY_PIN_RESET_REQUIRED, true)
                    .remove(KEY_SECURITY_PIN_HASH)
                    .apply()
            }
            prefs.edit().putString(KEY_INSTALLATION_SALT, saltStr).apply()
        }
        return android.util.Base64.decode(saltStr, android.util.Base64.NO_WRAP)
    }

    private fun hashPin(pin: String): String {
        val salt = getInstallationSalt()
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return hash.joinToString("") { "%02x".format(it) }
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

    fun getFavorites(): Set<String> {
        val saved = prefs.getStringSet(KEY_FAVORITES_INDODAX, null)
        if (saved != null && saved.isNotEmpty()) return saved.toSet()
        return setOf("BTCIDR")
    }

    fun toggleFavorite(symbol: String): Boolean {
        val set = getFavorites().toMutableSet()
        val upper = symbol.uppercase()
        val added = if (set.remove(upper)) false else { set.add(upper); true }
        prefs.edit().putStringSet(KEY_FAVORITES_INDODAX, set).apply()
        return added
    }

    fun isFavorite(symbol: String): Boolean =
        getFavorites().contains(symbol.uppercase())

    fun setFavorite(symbol: String, isFav: Boolean) {
        val set = getFavorites().toMutableSet()
        val upper = symbol.uppercase()
        if (isFav) set.add(upper) else set.remove(upper)
        prefs.edit().putStringSet(KEY_FAVORITES_INDODAX, set).apply()
    }

    fun getCompletedLearningLessons(): Set<Int> = prefs.getStringSet(KEY_LEARNING_COMPLETED, emptySet())
        ?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()

    fun setLearningLessonCompleted(index: Int, completed: Boolean) {
        val set = getCompletedLearningLessons().toMutableSet()
        if (completed) set.add(index) else set.remove(index)
        prefs.edit().putStringSet(KEY_LEARNING_COMPLETED, set.map(Int::toString).toSet()).apply()
    }

    fun getSavedRealBalance(): Map<String, Double> {
        val jsonStr = prefs.getString("saved_real_balance", "") ?: ""
        if (jsonStr.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, Double>()
            json.keys().forEach { key ->
                map[key.lowercase()] = json.optDouble(key, 0.0)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveRealBalance(balances: Map<String, Double>) {
        try {
            val json = JSONObject()
            balances.forEach { (k, v) ->
                json.put(k.lowercase(), v)
            }
            prefs.edit().putString("saved_real_balance", json.toString()).apply()
        } catch (_: Exception) {}
    }

    companion object {
        const val DEFAULT_UPDATE_REPO = "Agus27712/analisa-pasar"
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
        private const val KEY_FAVORITES_INDODAX = "favorites_symbols_indodax"
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
        private const val KEY_RECENT_HISTORY_BASES = "recent_history_bases_v1"
        private const val KEY_INSTALLATION_SALT = "sec_installation_salt_v2"
        private const val KEY_PIN_RESET_REQUIRED = "sec_pin_reset_required"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled_v1"
    }
}
