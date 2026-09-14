package agu.analys.trading

import agu.analys.model.AISignalState
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.TechnicalIndicators
import agu.analys.data.OrderBookDepthCache
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Snapshot komprehensif metrik teknikal dan sinyal saat eksekusi order (Beli / Jual).
 * Dipisahkan per-kategori agar mudah dibaca di UI dan diverifikasi oleh LLM (Gemini, Claude, Grok, ChatGPT).
 */
data class TradeSignalSnapshot(
    val strategyMode: String = "SCALPING",
    // 1. Kategori Order Book & Likuiditas
    val bidRatioPct: Double? = null,
    val askRatioPct: Double? = null,
    val orderBookPressure: Int? = null,
    val spreadPct: Double? = null,
    val volume24h: Double? = null,
    val priceChange24h: Double? = null,

    // 2. Kategori Momentum & Osilator
    val rsi14: Double? = null,
    val macd: Double? = null,
    val macdSignal: Double? = null,
    val macdHist: Double? = null,
    val momentum: Double? = null,

    // 3. Kategori Tren & Rata-rata Bergerak
    val ema20: Double? = null,
    val ema50: Double? = null,
    val ema200: Double? = null,
    val trendStatus: String? = null,

    // 4. Kategori Volatilitas & Bollinger Bands
    val bbUpper: Double? = null,
    val bbMiddle: Double? = null,
    val bbLower: Double? = null,
    val bbWidthPct: Double? = null,
    val atr: Double? = null,

    // 5. Kategori Keputusan AI Evaluator & Market Regime
    val confidenceScore: Int? = null,
    val marketRegime: String? = null,
    val patternDetected: String? = null,
    val reasons: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("strategyMode", strategyMode)

        // Category 1
        bidRatioPct?.let { obj.put("bidRatioPct", it) }
        askRatioPct?.let { obj.put("askRatioPct", it) }
        orderBookPressure?.let { obj.put("orderBookPressure", it) }
        spreadPct?.let { obj.put("spreadPct", it) }
        volume24h?.let { obj.put("volume24h", it) }
        priceChange24h?.let { obj.put("priceChange24h", it) }

        // Category 2
        rsi14?.let { obj.put("rsi14", it) }
        macd?.let { obj.put("macd", it) }
        macdSignal?.let { obj.put("macdSignal", it) }
        macdHist?.let { obj.put("macdHist", it) }
        momentum?.let { obj.put("momentum", it) }

        // Category 3
        ema20?.let { obj.put("ema20", it) }
        ema50?.let { obj.put("ema50", it) }
        ema200?.let { obj.put("ema200", it) }
        trendStatus?.let { obj.put("trendStatus", it) }

        // Category 4
        bbUpper?.let { obj.put("bbUpper", it) }
        bbMiddle?.let { obj.put("bbMiddle", it) }
        bbLower?.let { obj.put("bbLower", it) }
        bbWidthPct?.let { obj.put("bbWidthPct", it) }
        atr?.let { obj.put("atr", it) }

        // Category 5
        confidenceScore?.let { obj.put("confidenceScore", it) }
        marketRegime?.let { obj.put("marketRegime", it) }
        patternDetected?.let { obj.put("patternDetected", it) }

        if (reasons.isNotEmpty()) {
            val arr = JSONArray()
            reasons.forEach { arr.put(it) }
            obj.put("reasons", arr)
        }

        return obj
    }

    companion object {
        fun fromJson(json: JSONObject?): TradeSignalSnapshot? {
            if (json == null) return null
            val reasonsList = mutableListOf<String>()
            val rArr = json.optJSONArray("reasons")
            if (rArr != null) {
                for (i in 0 until rArr.length()) {
                    reasonsList.add(rArr.optString(i))
                }
            }

            return TradeSignalSnapshot(
                strategyMode = json.optString("strategyMode", "SCALPING"),
                bidRatioPct = if (json.has("bidRatioPct")) json.optDouble("bidRatioPct") else null,
                askRatioPct = if (json.has("askRatioPct")) json.optDouble("askRatioPct") else null,
                orderBookPressure = if (json.has("orderBookPressure")) json.optInt("orderBookPressure") else null,
                spreadPct = if (json.has("spreadPct")) json.optDouble("spreadPct") else null,
                volume24h = if (json.has("volume24h")) json.optDouble("volume24h") else null,
                priceChange24h = if (json.has("priceChange24h")) json.optDouble("priceChange24h") else null,
                rsi14 = if (json.has("rsi14")) json.optDouble("rsi14") else null,
                macd = if (json.has("macd")) json.optDouble("macd") else null,
                macdSignal = if (json.has("macdSignal")) json.optDouble("macdSignal") else null,
                macdHist = if (json.has("macdHist")) json.optDouble("macdHist") else null,
                momentum = if (json.has("momentum")) json.optDouble("momentum") else null,
                ema20 = if (json.has("ema20")) json.optDouble("ema20") else null,
                ema50 = if (json.has("ema50")) json.optDouble("ema50") else null,
                ema200 = if (json.has("ema200")) json.optDouble("ema200") else null,
                trendStatus = json.optString("trendStatus").takeIf { it.isNotBlank() },
                bbUpper = if (json.has("bbUpper")) json.optDouble("bbUpper") else null,
                bbMiddle = if (json.has("bbMiddle")) json.optDouble("bbMiddle") else null,
                bbLower = if (json.has("bbLower")) json.optDouble("bbLower") else null,
                bbWidthPct = if (json.has("bbWidthPct")) json.optDouble("bbWidthPct") else null,
                atr = if (json.has("atr")) json.optDouble("atr") else null,
                confidenceScore = if (json.has("confidenceScore")) json.optInt("confidenceScore") else null,
                marketRegime = json.optString("marketRegime").takeIf { it.isNotBlank() },
                patternDetected = json.optString("patternDetected").takeIf { it.isNotBlank() },
                reasons = reasonsList
            )
        }

        fun fromJsonString(str: String?): TradeSignalSnapshot? {
            if (str.isNullOrBlank()) return null
            return try {
                fromJson(JSONObject(str))
            } catch (_: Exception) {
                null
            }
        }

        fun capture(
            symbol: String,
            strategyMode: String,
            tick: MarketTick?,
            indicators: TechnicalIndicators?,
            signal: AISignalState?,
            bids: List<OrderBookItem> = emptyList(),
            asks: List<OrderBookItem> = emptyList()
        ): TradeSignalSnapshot {
            // Calculate Order Book Ratio
            val obPair = OrderBookDepthCache.getOrderBook(symbol)
            val effectiveBids = if (bids.isNotEmpty()) bids else (obPair?.first ?: emptyList())
            val effectiveAsks = if (asks.isNotEmpty()) asks else (obPair?.second ?: emptyList())

            val totalBidVol = effectiveBids.sumOf { it.amount }
            val totalAskVol = effectiveAsks.sumOf { it.amount }
            val totalVol = totalBidVol + totalAskVol

            val bidRatio = if (totalVol > 0.0) (totalBidVol / totalVol) * 100.0 else null
            val askRatio = if (totalVol > 0.0) (totalAskVol / totalVol) * 100.0 else null
            val obPressure = OrderBookDepthCache.calculatePressure(symbol)

            val spread = if (effectiveBids.isNotEmpty() && effectiveAsks.isNotEmpty()) {
                val bestBid = effectiveBids.first().price
                val bestAsk = effectiveAsks.first().price
                if (bestBid > 0.0) ((bestAsk - bestBid) / bestBid) * 100.0 else null
            } else null

            // Trend status calculation
            val curPrice = tick?.price ?: 0.0
            val ema20 = indicators?.ema20?.takeIf { !it.isNaN() && it > 0.0 }
            val ema50 = indicators?.ema50?.takeIf { !it.isNaN() && it > 0.0 }
            val ema200 = indicators?.ema200?.takeIf { !it.isNaN() && it > 0.0 }

            val trend = when {
                ema20 != null && ema50 != null && curPrice > ema20 && ema20 > ema50 -> "BULLISH_UPTREND"
                ema20 != null && ema50 != null && curPrice < ema20 && ema20 < ema50 -> "BEARISH_DOWNTREND"
                ema20 != null && curPrice >= ema20 -> "BULLISH_PULLBACK"
                ema20 != null && curPrice < ema20 -> "BEARISH_REJECTION"
                else -> "SIDEWAYS_CONSOLIDATION"
            }

            // Bollinger Bandwidth calculation
            val bbUpper = indicators?.bbUpper?.takeIf { !it.isNaN() && it > 0.0 }
            val bbLower = indicators?.bbLower?.takeIf { !it.isNaN() && it > 0.0 }
            val bbMiddle = if (bbUpper != null && bbLower != null) (bbUpper + bbLower) / 2.0 else ema20
            val bbWidthPct = if (bbUpper != null && bbLower != null && bbMiddle != null && bbMiddle > 0.0) {
                ((bbUpper - bbLower) / bbMiddle) * 100.0
            } else null

            return TradeSignalSnapshot(
                strategyMode = strategyMode,
                bidRatioPct = bidRatio,
                askRatioPct = askRatio,
                orderBookPressure = obPressure,
                spreadPct = spread,
                volume24h = tick?.volume24h,
                priceChange24h = tick?.change24h,
                rsi14 = indicators?.rsi14?.takeIf { !it.isNaN() },
                macd = indicators?.macd?.takeIf { !it.isNaN() },
                macdSignal = indicators?.macdSignal?.takeIf { !it.isNaN() },
                macdHist = indicators?.macdHist?.takeIf { !it.isNaN() },
                momentum = indicators?.momentum?.takeIf { !it.isNaN() },
                ema20 = ema20,
                ema50 = ema50,
                ema200 = ema200,
                trendStatus = trend,
                bbUpper = bbUpper,
                bbMiddle = bbMiddle,
                bbLower = bbLower,
                bbWidthPct = bbWidthPct,
                atr = indicators?.atr?.takeIf { !it.isNaN() },
                confidenceScore = signal?.confidence?.takeIf { it > 0 },
                marketRegime = signal?.regimeDetected?.takeIf { it.isNotBlank() } ?: "NORMAL_LIQUIDITY",
                patternDetected = signal?.patternDetected?.takeIf { it.isNotBlank() },
                reasons = signal?.reasoning?.ifEmpty { listOf("Technical signal match strategy mode $strategyMode") } ?: emptyList()
            )
        }
    }
}
