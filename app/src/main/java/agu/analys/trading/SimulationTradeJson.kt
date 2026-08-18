package agu.analys.trading

import org.json.JSONObject
import java.util.UUID

object SimulationTradeJson {
    fun orderToJson(order: SimulationOrder): JSONObject {
        val j = JSONObject()
        j.put("id", order.id)
        j.put("symbol", order.symbol)
        j.put("baseAsset", order.baseAsset)
        j.put("quoteAsset", order.quoteAsset)
        j.put("side", order.side.name)
        j.put("type", order.type.name)
        j.put("limitPrice", order.limitPrice)
        j.put("stopPrice", order.stopPrice)
        j.put("quantity", order.quantity)
        j.put("totalIdr", order.totalIdr)
        j.put("filledQuantity", order.filledQuantity)
        j.put("filledAvgPrice", order.filledAvgPrice)
        j.put("feeIdr", order.feeIdr)
        j.put("status", order.status.name)
        j.put("isStopTriggered", order.isStopTriggered)
        j.put("createdAt", order.createdAt)
        order.filledAt?.let { j.put("filledAt", it) }
        return j
    }

    fun orderFromJson(j: JSONObject): SimulationOrder {
        return SimulationOrder(
            id = j.optString("id", UUID.randomUUID().toString()),
            symbol = j.optString("symbol", ""),
            baseAsset = j.optString("baseAsset", ""),
            quoteAsset = j.optString("quoteAsset", "IDR"),
            side = runCatching { SimulationOrderSide.valueOf(j.optString("side", "BUY")) }.getOrDefault(SimulationOrderSide.BUY),
            type = runCatching { SimulationOrderType.valueOf(j.optString("type", "LIMIT")) }.getOrDefault(SimulationOrderType.LIMIT),
            limitPrice = j.optDouble("limitPrice", 0.0),
            stopPrice = j.optDouble("stopPrice", 0.0),
            quantity = j.optDouble("quantity", 0.0),
            totalIdr = j.optDouble("totalIdr", 0.0),
            filledQuantity = j.optDouble("filledQuantity", 0.0),
            filledAvgPrice = j.optDouble("filledAvgPrice", 0.0),
            feeIdr = j.optDouble("feeIdr", 0.0),
            status = runCatching { SimulationOrderStatus.valueOf(j.optString("status", "OPEN")) }.getOrDefault(SimulationOrderStatus.OPEN),
            isStopTriggered = j.optBoolean("isStopTriggered", false),
            createdAt = j.optLong("createdAt", System.currentTimeMillis()),
            filledAt = if (j.has("filledAt")) j.optLong("filledAt") else null
        )
    }

    fun historyToJson(h: SimulationTradeHistoryItem): JSONObject {
        val j = JSONObject()
        j.put("id", h.id)
        j.put("orderId", h.orderId)
        j.put("symbol", h.symbol)
        j.put("baseAsset", h.baseAsset)
        j.put("quoteAsset", h.quoteAsset)
        j.put("side", h.side.name)
        j.put("type", h.type.name)
        j.put("executionPrice", h.executionPrice)
        j.put("quantity", h.quantity)
        j.put("totalIdr", h.totalIdr)
        j.put("feeIdr", h.feeIdr)
        j.put("timestamp", h.timestamp)
        h.pnlIdr?.let { j.put("pnlIdr", it) }
        h.pnlPercent?.let { j.put("pnlPercent", it) }
        return j
    }

    fun historyFromJson(j: JSONObject): SimulationTradeHistoryItem {
        return SimulationTradeHistoryItem(
            id = j.optString("id", UUID.randomUUID().toString()),
            orderId = j.optString("orderId", ""),
            symbol = j.optString("symbol", ""),
            baseAsset = j.optString("baseAsset", ""),
            quoteAsset = j.optString("quoteAsset", "IDR"),
            side = runCatching { SimulationOrderSide.valueOf(j.optString("side", "BUY")) }.getOrDefault(SimulationOrderSide.BUY),
            type = runCatching { SimulationOrderType.valueOf(j.optString("type", "LIMIT")) }.getOrDefault(SimulationOrderType.LIMIT),
            executionPrice = j.optDouble("executionPrice", 0.0),
            quantity = j.optDouble("quantity", 0.0),
            totalIdr = j.optDouble("totalIdr", 0.0),
            feeIdr = j.optDouble("feeIdr", 0.0),
            timestamp = j.optLong("timestamp", System.currentTimeMillis()),
            pnlIdr = if (j.has("pnlIdr")) j.optDouble("pnlIdr") else null,
            pnlPercent = if (j.has("pnlPercent")) j.optDouble("pnlPercent") else null
        )
    }

    fun walletToJson(wallet: SimulationWallet): JSONObject {
        val json = JSONObject()
        json.put("idrBalance", wallet.idrBalance)
        json.put("lockedIdr", wallet.lockedIdr)

        val coinObj = JSONObject()
        wallet.coinBalances.forEach { (k, v) -> if (v > 0.0) coinObj.put(k.uppercase(), v) }
        json.put("coinBalances", coinObj)

        val lockedCoinObj = JSONObject()
        wallet.lockedCoinBalances.forEach { (k, v) -> if (v > 0.0) lockedCoinObj.put(k.uppercase(), v) }
        json.put("lockedCoinBalances", lockedCoinObj)

        val avgBuyObj = JSONObject()
        wallet.avgBuyPrices.forEach { (k, v) -> if (v > 0.0) avgBuyObj.put(k.uppercase(), v) }
        json.put("avgBuyPrices", avgBuyObj)

        return json
    }

    fun walletFromJson(raw: String?): SimulationWallet {
        if (raw.isNullOrBlank()) return SimulationWallet()
        return try {
            val json = JSONObject(raw)
            val idr = json.optDouble("idrBalance", 10_000_000.0)
            val lockedIdr = json.optDouble("lockedIdr", 0.0)

            val coinObj = json.optJSONObject("coinBalances")
            val coinMap = mutableMapOf<String, Double>()
            coinObj?.keys()?.forEach { k ->
                coinMap[k.uppercase()] = coinObj.optDouble(k, 0.0)
            }

            val lockedCoinObj = json.optJSONObject("lockedCoinBalances")
            val lockedCoinMap = mutableMapOf<String, Double>()
            lockedCoinObj?.keys()?.forEach { k ->
                lockedCoinMap[k.uppercase()] = lockedCoinObj.optDouble(k, 0.0)
            }

            val avgBuyObj = json.optJSONObject("avgBuyPrices")
            val avgBuyMap = mutableMapOf<String, Double>()
            avgBuyObj?.keys()?.forEach { k ->
                avgBuyMap[k.uppercase()] = avgBuyObj.optDouble(k, 0.0)
            }

            SimulationWallet(
                idrBalance = idr,
                lockedIdr = lockedIdr,
                coinBalances = coinMap,
                lockedCoinBalances = lockedCoinMap,
                avgBuyPrices = avgBuyMap
            )
        } catch (_: Exception) {
            SimulationWallet()
        }
    }
}
