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

/**
 * Konfigurasi Tier / Smart Step Trailing Stop
 * Saat profit (peak vs entry) mencapai threshold minProfitPct, jarak trailing otomatis
 * mengencang ke trailingPercent.
 */
data class TrailingTier(
    val minProfitPct: Double,
    val trailingPercent: Double
)

data class SpotPosition(
    val state: SpotPositionState = SpotPositionState.NO_POSITION,
    val investedAmount: Double = 0.0,
    val entryPrice: Double = 0.0,
    val quantity: Double = 0.0,
    val openedAt: Long = 0L,
    val isReal: Boolean = false,
    val isTrailingEnabled: Boolean = false,
    val trailingPercent: Double = 0.0,
    val isTieredTrailingEnabled: Boolean = true,
    val tieredConfigJson: String = "",
    val activeTrailingPercent: Double = 0.0,
    val peakPrice: Double = 0.0,
    val trailingStopPrice: Double = 0.0,
    val stopLossPrice: Double = 0.0,
    val isTrailingTriggered: Boolean = false,
    val isAutoSellEnabled: Boolean = false,
    val tp1Price: Double = 0.0,
    val tp1Percent: Double = 50.0,
    val tp2Price: Double = 0.0,
    val tp2Percent: Double = 50.0,
    val isTp1Triggered: Boolean = false,
    val isTp2Triggered: Boolean = false,
    val lastTrailingOrderId: String? = null,
    val lastOrderUpdateTime: Long = 0L
) {
    val isHolding: Boolean get() = state == SpotPositionState.HOLDING
}

class SpotPositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "analysis_ui_spot_positions"
        private const val MAX_HISTORY_EVENTS = 50

        val DEFAULT_TIERS = listOf(
            TrailingTier(minProfitPct = 1.0, trailingPercent = 2.0),
            TrailingTier(minProfitPct = 3.0, trailingPercent = 1.5),
            TrailingTier(minProfitPct = 5.0, trailingPercent = 1.2),
            TrailingTier(minProfitPct = 10.0, trailingPercent = 1.0)
        )

        fun serializeTiers(tiers: List<TrailingTier>): String {
            val arr = JSONArray()
            for (t in tiers) {
                val obj = JSONObject()
                obj.put("profit", t.minProfitPct)
                obj.put("trailing", t.trailingPercent)
                arr.put(obj)
            }
            return arr.toString()
        }

        fun deserializeTiers(json: String?): List<TrailingTier> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val list = mutableListOf<TrailingTier>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        TrailingTier(
                            minProfitPct = obj.optDouble("profit", 0.0),
                            trailingPercent = obj.optDouble("trailing", 0.0)
                        )
                    )
                }
                list.sortedBy { it.minProfitPct }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Menghitung persentase trailing efektif berdasarkan profit saat ini (Smart Step Trailing).
     * Jika profit membesar melebihi tier tertentu, trailing percent mengencang otomatis.
     */
    fun resolveEffectiveTrailingPercent(
        peakPrice: Double,
        entryPrice: Double,
        baseTrailingPercent: Double,
        isTieredEnabled: Boolean = true,
        customTiersJson: String? = null
    ): Double {
        val safeBase = baseTrailingPercent.coerceAtLeast(0.1)
        if (!isTieredEnabled || entryPrice <= 0.0 || peakPrice <= entryPrice) {
            return safeBase
        }
        val profitPct = ((peakPrice - entryPrice) / entryPrice) * 100.0
        val tiers = if (!customTiersJson.isNullOrBlank()) {
            deserializeTiers(customTiersJson).ifEmpty { DEFAULT_TIERS }
        } else {
            DEFAULT_TIERS
        }

        // Cari tier tertinggi yang telah dicapai oleh profit
        val matchedTier = tiers.filter { profitPct >= it.minProfitPct }
            .maxByOrNull { it.minProfitPct }

        return matchedTier?.trailingPercent?.coerceAtLeast(0.1) ?: safeBase
    }

    fun calculateTrailingLimitPrice(peakPrice: Double, entryPrice: Double, trailingPercent: Double): Double {
        val rawStop = peakPrice * (1.0 - trailingPercent / 100.0)
        return if (entryPrice > 0.0) maxOf(rawStop, entryPrice) else rawStop
    }

    private fun getSafeString(key: String): String? {
        return try {
            prefs.getString(key, null)
        } catch (_: ClassCastException) {
            try {
                prefs.all[key]?.toString()
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getSafeBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return try {
            prefs.getBoolean(key, defaultValue)
        } catch (_: ClassCastException) {
            try {
                prefs.all[key]?.toString()?.toBooleanStrictOrNull() ?: defaultValue
            } catch (_: Exception) {
                defaultValue
            }
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun getSafeLong(key: String, defaultValue: Long = 0L): Long {
        return try {
            prefs.getLong(key, defaultValue)
        } catch (_: ClassCastException) {
            try {
                prefs.all[key]?.toString()?.toLongOrNull() ?: defaultValue
            } catch (_: Exception) {
                defaultValue
            }
        } catch (_: Exception) {
            defaultValue
        }
    }

    fun get(symbol: String): SpotPosition {
        val key = normalize(symbol)
        val state = getSafeString("${key}_state")
            ?.let { runCatching { SpotPositionState.valueOf(it) }.getOrDefault(SpotPositionState.NO_POSITION) }
            ?: SpotPositionState.NO_POSITION
        val entry = getSafeString("${key}_entry")?.toDoubleOrNull() ?: 0.0
        val peak = getSafeString("${key}_peak")?.toDoubleOrNull() ?: entry
        val trailingPct = getSafeString("${key}_trailing_pct")?.toDoubleOrNull() ?: 0.0
        val isTrailing = getSafeBoolean("${key}_trailing_enabled", false)
        val isTriggered = getSafeBoolean("${key}_trailing_triggered", false)
        val isTieredTrailing = getSafeBoolean("${key}_tiered_trailing_enabled", true)
        val tieredJson = getSafeString("${key}_tiered_config_json").orEmpty()
        val effectiveTrailingPct = if (isTrailing && peak > 0.0 && trailingPct > 0.0) {
            resolveEffectiveTrailingPercent(peak, entry, trailingPct, isTieredTrailing, tieredJson)
        } else {
            trailingPct
        }
        val trailingStop = if (isTrailing && peak > 0.0 && effectiveTrailingPct > 0.0) {
            calculateTrailingLimitPrice(peak, entry, effectiveTrailingPct)
        } else 0.0

        val lastTrailingOrderId = getSafeString("${key}_last_trailing_order_id")
        val lastOrderUpdateTime = getSafeLong("${key}_last_order_update_time", 0L)
        val stopLossPrice = getSafeString("${key}_stop_loss")?.toDoubleOrNull() ?: if (entry > 0.0) entry * 0.99 else 0.0

        return SpotPosition(
            state = state,
            investedAmount = getSafeString("${key}_invested")?.toDoubleOrNull() ?: 0.0,
            entryPrice = entry,
            quantity = getSafeString("${key}_quantity")?.toDoubleOrNull() ?: 0.0,
            openedAt = getSafeLong("${key}_opened_at", 0L),
            isReal = getSafeBoolean("${key}_is_real", false),
            isTrailingEnabled = isTrailing,
            trailingPercent = trailingPct,
            isTieredTrailingEnabled = isTieredTrailing,
            tieredConfigJson = tieredJson,
            activeTrailingPercent = effectiveTrailingPct,
            peakPrice = peak,
            trailingStopPrice = trailingStop,
            stopLossPrice = stopLossPrice,
            isTrailingTriggered = isTriggered,
            isAutoSellEnabled = getSafeBoolean("${key}_auto_sell_enabled", false),
            tp1Price = getSafeString("${key}_tp1_price")?.toDoubleOrNull() ?: 0.0,
            tp1Percent = getSafeString("${key}_tp1_percent")?.toDoubleOrNull() ?: 50.0,
            tp2Price = getSafeString("${key}_tp2_price")?.toDoubleOrNull() ?: 0.0,
            tp2Percent = getSafeString("${key}_tp2_percent")?.toDoubleOrNull() ?: 50.0,
            isTp1Triggered = getSafeBoolean("${key}_tp1_triggered", false),
            isTp2Triggered = getSafeBoolean("${key}_tp2_triggered", false),
            lastTrailingOrderId = lastTrailingOrderId,
            lastOrderUpdateTime = lastOrderUpdateTime
        )
    }

    /** Reconstruct the position state at a historical timestamp. */
    fun getAt(symbol: String, timestamp: Long): SpotPosition {
        val key = normalize(symbol)
        val history = readHistory(key)
        if (history.length() == 0) {
            val current = get(symbol)
            if (current.isHolding && current.openedAt > 0L && current.openedAt <= timestamp) return current
            return SpotPosition()
        }

        var best: JSONObject? = null
        for (i in 0 until history.length()) {
            val event = history.optJSONObject(i) ?: continue
            val eventTime = event.optLong("timestamp", 0L)
            if (eventTime <= 0L || eventTime > timestamp) continue
            if (best == null || eventTime > best!!.optLong("timestamp", 0L)) best = event
        }

        if (best == null) return SpotPosition()
        val state = best.optString("state", SpotPositionState.NO_POSITION.name)
            .let { runCatching { SpotPositionState.valueOf(it) }.getOrDefault(SpotPositionState.NO_POSITION) }
        return SpotPosition(
            state = state,
            investedAmount = best.optDouble("investedAmount", 0.0),
            entryPrice = best.optDouble("entryPrice", 0.0),
            quantity = best.optDouble("quantity", 0.0),
            openedAt = best.optLong("openedAt", 0L),
            isReal = best.optBoolean("isReal", false)
        )
    }

    fun setHolding(symbol: String, invested: Double, entry: Double, quantity: Double, isReal: Boolean = false) {
        val key = normalize(symbol)
        val changedAt = System.currentTimeMillis()
        val current = get(symbol)
        val openedAt = if (current.isHolding && current.openedAt > 0L) current.openedAt else changedAt
        val peak = if (current.isHolding && current.peakPrice > 0.0) current.peakPrice.coerceAtLeast(entry) else entry
        val stopLossPrice = if (current.stopLossPrice > 0.0) current.stopLossPrice else if (entry > 0.0) entry * 0.99 else 0.0
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            investedAmount = invested,
            entryPrice = entry,
            quantity = quantity,
            openedAt = openedAt,
            isReal = isReal,
            peakPrice = peak,
            stopLossPrice = stopLossPrice,
            isTrailingEnabled = current.isTrailingEnabled,
            trailingPercent = current.trailingPercent,
            isTieredTrailingEnabled = current.isTieredTrailingEnabled,
            tieredConfigJson = current.tieredConfigJson,
            activeTrailingPercent = current.activeTrailingPercent,
            isAutoSellEnabled = current.isAutoSellEnabled,
            tp1Price = current.tp1Price,
            tp1Percent = current.tp1Percent,
            tp2Price = current.tp2Price,
            tp2Percent = current.tp2Percent
        )
        prefs.edit()
            .putString("${key}_state", SpotPositionState.HOLDING.name)
            .putString("${key}_invested", invested.toString())
            .putString("${key}_entry", entry.toString())
            .putString("${key}_quantity", quantity.toString())
            .putLong("${key}_opened_at", openedAt)
            .putBoolean("${key}_is_real", isReal)
            .putString("${key}_peak", peak.toString())
            .putString("${key}_stop_loss", stopLossPrice.toString())
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    fun markBought(symbol: String, entryPrice: Double, invested: Double = 0.0, quantity: Double = 0.0, isReal: Boolean = false) {
        val finalInvested = if (invested > 0.0) invested else (if (quantity > 0.0 && entryPrice > 0.0) quantity * entryPrice else entryPrice)
        val finalQty = if (quantity > 0.0) quantity else (if (entryPrice > 0.0 && finalInvested > 0.0) finalInvested / entryPrice else (if (entryPrice > 0.0) 1.0 else 0.0))
        setHolding(symbol, invested = finalInvested, entry = entryPrice, quantity = finalQty, isReal = isReal)
    }

    fun markBought(symbol: String, invested: Double, entry: Double, isReal: Boolean = false) {
        val qty = if (entry > 0.0) invested / entry else 0.0
        setHolding(symbol, invested = invested, entry = entry, quantity = qty, isReal = isReal)
    }

    fun setManualEntryPrice(symbol: String, price: Double, amount: Double = 0.0, isReal: Boolean = false) {
        val qty = if (price > 0.0 && amount > 0.0) amount / price else (if (price > 0.0) 1.0 else 0.0)
        setHolding(symbol, invested = if (amount > 0.0) amount else price, entry = price, quantity = qty, isReal = isReal)
    }

    fun markSold(symbol: String) {
        val key = normalize(symbol)
        val current = get(symbol)
        if (!current.isHolding && current.entryPrice == 0.0 && current.investedAmount == 0.0 && current.quantity == 0.0) return
        val changedAt = System.currentTimeMillis()
        val position = SpotPosition(state = SpotPositionState.NO_POSITION, openedAt = changedAt)
        prefs.edit()
            .putString("${key}_state", SpotPositionState.NO_POSITION.name)
            .remove("${key}_invested")
            .remove("${key}_entry")
            .remove("${key}_quantity")
            .remove("${key}_opened_at")
            .remove("${key}_is_real")
            .remove("${key}_peak")
            .remove("${key}_trailing_pct")
            .remove("${key}_tiered_trailing_enabled")
            .remove("${key}_tiered_config_json")
            .remove("${key}_trailing_enabled")
            .remove("${key}_trailing_triggered")
            .remove("${key}_auto_sell_enabled")
            .remove("${key}_tp1_price")
            .remove("${key}_tp1_percent")
            .remove("${key}_tp2_price")
            .remove("${key}_tp2_percent")
            .remove("${key}_stop_loss")
            .remove("${key}_stop_loss_price")
            .remove("${key}_tp1_triggered")
            .remove("${key}_tp2_triggered")
            .remove("${key}_sl_triggered")
            .remove("${key}_last_trailing_order_id")
            .remove("${key}_last_order_update_time")
            .putString("${key}_history", appendHistoryEvent(key, position))
            .apply()
    }

    fun setTrailingOrderIdAndUpdateTime(symbol: String, orderId: String?, updateTime: Long) {
        val key = normalize(symbol)
        prefs.edit()
            .putString("${key}_last_trailing_order_id", orderId)
            .putLong("${key}_last_order_update_time", updateTime)
            .apply()
    }

    fun setTrailingStop(
        symbol: String,
        enabled: Boolean,
        trailingPercent: Double,
        referencePrice: Double = 0.0,
        isTieredEnabled: Boolean = true,
        customTiersJson: String? = null
    ) {
        val key = normalize(symbol)
        if (!enabled) {
            prefs.edit()
                .putBoolean("${key}_trailing_enabled", false)
                .putBoolean("${key}_trailing_triggered", false)
                .remove("${key}_last_trailing_order_id")
                .remove("${key}_last_order_update_time")
                .apply()
            return
        }
        val current = get(symbol)
        val peak = when {
            referencePrice > 0.0 -> referencePrice
            current.entryPrice > 0.0 -> current.entryPrice
            else -> 0.0
        }
        val editor = prefs.edit()
            .putBoolean("${key}_trailing_enabled", true)
            .putString("${key}_trailing_pct", trailingPercent.coerceAtLeast(0.1).toString())
            .putBoolean("${key}_tiered_trailing_enabled", isTieredEnabled)
            .putString("${key}_peak", peak.toString())
            .putBoolean("${key}_trailing_triggered", false)
            .remove("${key}_last_trailing_order_id")
            .remove("${key}_last_order_update_time")

        if (customTiersJson != null) {
            editor.putString("${key}_tiered_config_json", customTiersJson)
        }
        editor.apply()
    }

    /**
     * Updates peak price and checks if trailing stop is triggered.
     * Returns Pair<SpotPosition, Boolean(justTriggered)>
     */
    fun updateTrailingPrice(symbol: String, currentPrice: Double): Pair<SpotPosition, Boolean> {
        val current = get(symbol)
        if (!current.isHolding || !current.isTrailingEnabled || currentPrice <= 0.0) {
            return Pair(current, false)
        }

        val key = normalize(symbol)
        var newPeak = current.peakPrice.coerceAtLeast(current.entryPrice)
        var justTriggered = false

        // Rule 1: Peak price hanya di-update saat harga naik (tidak pernah turun)
        if (currentPrice > newPeak) {
            newPeak = currentPrice
            prefs.edit().putString("${key}_peak", newPeak.toString()).apply()
        }

        // Rule 2: Resolusi dynamic Smart Step Trailing percent berdasarkan profit tertinggi yang dicapai
        val effectivePct = resolveEffectiveTrailingPercent(
            peakPrice = newPeak,
            entryPrice = current.entryPrice,
            baseTrailingPercent = current.trailingPercent,
            isTieredEnabled = current.isTieredTrailingEnabled,
            customTiersJson = current.tieredConfigJson
        )

        // Rule 3: Hitung batas jual trailing dari peak menggunakan effective percentage
        val rawTrailingStop = newPeak * (1.0 - effectivePct / 100.0)
        val trailingStop = calculateTrailingLimitPrice(newPeak, current.entryPrice, effectivePct)
        
        // Rule 4: Pure Profit-Lock (Anti Cut-Loss): Trailing HANYA AKTIF jika batas jual sudah di atas harga beli (in-profit)
        // Jika harga langsung drop setelah beli atau belum naik melewati modal, trailing stop tidak mengorbankan modal di bawah entry
        val isEligibleForTrailing = current.entryPrice > 0.0 && rawTrailingStop > current.entryPrice

        // Rule 5: Saat harga turun menyentuh trailing price dan sudah memenuhi syarat profit-lock -> trigger (Noise filter: minimal 2 ticks di bawah garis)
        if (isEligibleForTrailing && currentPrice <= trailingStop && !current.isTrailingTriggered) {
            val ticksBelow = prefs.getInt("${key}_trailing_ticks_below", 0) + 1
            if (ticksBelow >= 2) {
                justTriggered = true
                prefs.edit()
                    .putBoolean("${key}_trailing_triggered", true)
                    .putInt("${key}_trailing_ticks_below", 0)
                    .apply()
            } else {
                prefs.edit().putInt("${key}_trailing_ticks_below", ticksBelow).apply()
            }
        } else {
            // Reset noise filter jika harga memantul ke atas batas trailing
            prefs.edit().putInt("${key}_trailing_ticks_below", 0).apply()
        }

        val updated = get(symbol)
        return Pair(updated, justTriggered)
    }

    fun resetTrailingTrigger(symbol: String) {
        val key = normalize(symbol)
        prefs.edit().putBoolean("${key}_trailing_triggered", false).apply()
    }

    fun setAutoSellParams(
        symbol: String,
        enabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double
    ) {
        val key = normalize(symbol)
        prefs.edit()
            .putBoolean("${key}_auto_sell_enabled", enabled)
            .putString("${key}_tp1_price", tp1Price.toString())
            .putString("${key}_tp1_percent", tp1Percent.toString())
            .putString("${key}_tp2_price", tp2Price.toString())
            .putString("${key}_tp2_percent", tp2Percent.toString())
            .putBoolean("${key}_tp1_triggered", false)
            .putBoolean("${key}_tp2_triggered", false)
            .apply()
    }

    fun markTp1Triggered(symbol: String) {
        prefs.edit().putBoolean("${normalize(symbol)}_tp1_triggered", true).apply()
    }

    fun markTp2Triggered(symbol: String) {
        prefs.edit().putBoolean("${normalize(symbol)}_tp2_triggered", true).apply()
    }

    fun getAllActiveTrailingSymbols(): List<String> {
        val result = mutableListOf<String>()
        val all = prefs.all
        for ((k, _) in all) {
            if (!k.endsWith("_state")) continue
            val prefix = k.removeSuffix("_state")
            val stateStr = prefs.getString(k, null) ?: continue
            val isTrailing = prefs.getBoolean("${prefix}_trailing_enabled", false)
            if (stateStr == SpotPositionState.HOLDING.name && isTrailing) {
                result.add(prefix)
            }
        }
        return result
    }

    fun hasAnyHolding(): Boolean {
        val all = prefs.all
        for ((k, _) in all) {
            if (!k.endsWith("_state")) continue
            val stateStr = prefs.getString(k, null) ?: continue
            if (stateStr == SpotPositionState.HOLDING.name) {
                val prefix = k.removeSuffix("_state")
                val isHoldingFlag = getSafeBoolean("${prefix}_holding", false)
                val qty = getSafeString("${prefix}_qty")?.toDoubleOrNull() ?: 0.0
                if (isHoldingFlag || qty > 0.0) return true
            }
        }
        return false
    }

    private fun readHistory(key: String): JSONArray {
        val raw = prefs.getString("${key}_history", null).orEmpty()
        return if (raw.isBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun appendHistoryEvent(key: String, position: SpotPosition): String {
        val history = readHistory(key)
        val event = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("state", position.state.name)
            .put("investedAmount", position.investedAmount)
            .put("entryPrice", position.entryPrice)
            .put("quantity", position.quantity)
            .put("openedAt", position.openedAt)
            .put("isReal", position.isReal)
        history.put(event)
        while (history.length() > MAX_HISTORY_EVENTS) history.remove(0)
        return history.toString()
    }

    private fun normalize(symbol: String): String =
        symbol.uppercase().replace(Regex("[^A-Z0-9_]"), "_")
}
