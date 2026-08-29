package agu.analys.trading

import android.content.Context
import agu.analys.model.PriceAlert
import agu.analys.model.PriceAlertType
import org.json.JSONArray
import org.json.JSONObject

class PriceAlertStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAllAlerts(): List<PriceAlert> {
        val raw = prefs.getString(KEY_ALERTS, "[]") ?: "[]"
        return parseAlerts(raw)
    }

    fun getAlertsForSymbol(symbol: String): List<PriceAlert> {
        val normalized = normalize(symbol)
        return getAllAlerts().filter { normalize(it.symbol) == normalized }
    }

    fun getActiveAlertsForSymbol(symbol: String): List<PriceAlert> {
        val normalized = normalize(symbol)
        return getAllAlerts().filter { normalize(it.symbol) == normalized && it.isEnabled && !it.isTriggered }
    }

    fun addAlert(alert: PriceAlert) {
        val list = getAllAlerts().toMutableList()
        list.removeAll { it.id == alert.id }
        list.add(0, alert)
        saveAlerts(list)
    }

    fun removeAlert(id: String) {
        val list = getAllAlerts().toMutableList()
        list.removeAll { it.id == id }
        saveAlerts(list)
    }

    fun toggleAlert(id: String) {
        val list = getAllAlerts().map {
            if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it
        }
        saveAlerts(list)
    }

    fun markTriggered(id: String) {
        val list = getAllAlerts().map {
            if (it.id == id) it.copy(isTriggered = true, triggeredAt = System.currentTimeMillis()) else it
        }
        saveAlerts(list)
    }

    fun checkAlerts(symbol: String, currentPrice: Double): List<PriceAlert> {
        val active = getActiveAlertsForSymbol(symbol)
        val triggered = mutableListOf<PriceAlert>()
        active.forEach { alert ->
            val isTriggered = when (alert.type) {
                PriceAlertType.PRICE_ABOVE -> currentPrice >= alert.targetPrice
                PriceAlertType.PRICE_BELOW -> currentPrice <= alert.targetPrice
                else -> false
            }
            if (isTriggered) {
                markTriggered(alert.id)
                triggered.add(alert.copy(isTriggered = true, triggeredAt = System.currentTimeMillis()))
            }
        }
        return triggered
    }

    fun resetTriggered(id: String) {
        val list = getAllAlerts().map {
            if (it.id == id) it.copy(isTriggered = false) else it
        }
        saveAlerts(list)
    }

    private fun saveAlerts(list: List<PriceAlert>) {
        val array = JSONArray()
        list.take(100).forEach { alert ->
            val obj = JSONObject().apply {
                put("id", alert.id)
                put("symbol", alert.symbol)
                put("type", alert.type.name)
                put("targetPrice", alert.targetPrice)
                put("targetRsi", alert.targetRsi)
                put("note", alert.note)
                put("isEnabled", alert.isEnabled)
                put("isTriggered", alert.isTriggered)
                put("triggeredAt", alert.triggeredAt)
                put("createdAt", alert.createdAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_ALERTS, array.toString()).apply()
    }

    private fun parseAlerts(jsonStr: String): List<PriceAlert> {
        val result = mutableListOf<PriceAlert>()
        runCatching {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val typeName = obj.optString("type", PriceAlertType.PRICE_ABOVE.name)
                val type = runCatching { PriceAlertType.valueOf(typeName) }.getOrDefault(PriceAlertType.PRICE_ABOVE)
                result.add(
                    PriceAlert(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        symbol = obj.optString("symbol", ""),
                        type = type,
                        targetPrice = obj.optDouble("targetPrice", 0.0),
                        targetRsi = obj.optDouble("targetRsi", 30.0),
                        note = obj.optString("note", ""),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        isTriggered = obj.optBoolean("isTriggered", false),
                        triggeredAt = obj.optLong("triggeredAt", 0L),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
        return result
    }

    private fun normalize(symbol: String): String =
        symbol.uppercase().replace(Regex("[^A-Z0-9]"), "")

    companion object {
        private const val PREFS_NAME = "agu_price_alerts_prefs"
        private const val KEY_ALERTS = "key_active_price_alerts"
    }
}
