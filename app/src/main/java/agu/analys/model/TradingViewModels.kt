package agu.analys.model

enum class SignalAction { BUY, SELL, HOLD }

enum class ScalpingStage(val displayName: String) {
    HOLD("TAHAN"),
    WATCH("WATCH"),
    WAIT_PULLBACK("TUNGGU PULLBACK"),
    ENTRY("ENTRY"),
    STRONG_ENTRY("ENTRY KUAT")
}

enum class TrendSentiment(val displayName: String) {
    STRONG_BULLISH_CONTINUATION("Kelanjutan Bullish Kuat"),
    BULLISH_REVERSAL("Pembalikan Arah Bullish"),
    ACCUMULATION_SQUEEZE("Tunggu Pullback"),
    NEUTRAL_CONSOLIDATION("Konsolidasi Netral"),
    BEARISH_DISTRIBUTION("Distribusi Bearish"),
    BEARISH_BREAKDOWN("Breakdown Bearish"),
    EXTREME_OVERSOLD("Pantulan Jenuh Jual (Oversold)")
}

enum class AppScreen { DASHBOARD, DETAIL, LANDSCAPE_CHART, SETTINGS, LEARNING }

data class WorthCoinInfo(val pair: TradingPair, val worthScore: Int, val isWorthIt: Boolean, val recommendation: String, val potentialProfitPct: Double, val aiRationale: String)
data class MarketTick(val symbol: String, val price: Double, val high24h: Double, val low24h: Double, val volume24h: Double, val change24h: Double, val timestamp: Long = System.currentTimeMillis())
data class CandleBar(val timestamp: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

data class IndonesiaCpiData(
    val period: String,
    val yoyPercent: Double = Double.NaN,
    val mtmPercent: Double? = null,
    val ytdPercent: Double? = null,
    val coreYoyPercent: Double? = null,
    val cpiIndex: Double? = null,
    val inflationTargetCenterPercent: Double = 2.5,
    val inflationTargetBandPercent: Double = 1.0,
    val source: String = "BPS WebAPI",
    val sourceUrl: String = "https://webapi.bps.go.id/documentation",
    val fetchedAt: Long = System.currentTimeMillis()
)

data class TechnicalIndicators(
    val rsi14: Double = Double.NaN,
    val macd: Double = Double.NaN,
    val macdSignal: Double = Double.NaN,
    val macdHist: Double = Double.NaN,
    val ema20: Double = Double.NaN,
    val ema50: Double = Double.NaN,
    val ema200: Double = Double.NaN,
    val bbUpper: Double = Double.NaN,
    val bbLower: Double = Double.NaN,
    val atr: Double = Double.NaN,
    val momentum: Double = Double.NaN
)

data class AISignalState(
    val action: SignalAction = SignalAction.HOLD,
    val confidence: Int = 0,
    val sentiment: TrendSentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
    val entryPrice: Double = 0.0,
    val targetPrice1: Double = 0.0,
    val targetPrice2: Double = 0.0,
    val stopLoss: Double = 0.0,
    val riskRewardRatio: String = "Belum tersedia",
    val probabilityScore: Double = 0.0,
    val patternDetected: String? = null,
    val reasoning: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val marketSymbol: String = "",
    val scalpingStage: ScalpingStage = ScalpingStage.HOLD
)

data class TradingPair(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val displayName: String,
    val initialPrice: Double = 0.0,
    val iconUrl: String = "",
    val indodaxPair: String = ""
) {
    companion object {
        val POPULAR_PAIRS = listOf(
            TradingPair("BTCIDR", "BTC", "IDR", "Bitcoin / IDR", indodaxPair = "btc_idr"),
            TradingPair("ETHIDR", "ETH", "IDR", "Ethereum / IDR", indodaxPair = "eth_idr"),
            TradingPair("SOLIDR", "SOL", "IDR", "Solana / IDR", indodaxPair = "sol_idr"),
            TradingPair("BNBIDR", "BNB", "IDR", "BNB / IDR", indodaxPair = "bnb_idr"),
            TradingPair("XRPIDR", "XRP", "IDR", "XRP / IDR", indodaxPair = "xrp_idr"),
            TradingPair("DOGEIDR", "DOGE", "IDR", "Dogecoin / IDR", indodaxPair = "doge_idr"),
            TradingPair("PEPEIDR", "PEPE", "IDR", "Pepe / IDR", indodaxPair = "pepe_idr"),
            TradingPair("MYXIDR", "MYX", "IDR", "MYX Finance / IDR", indodaxPair = "myx_idr")
        )

        fun fromCustomSymbol(raw: String): TradingPair {
            val cleaned = raw.trim().uppercase().replace(" ", "").replace("/", "").replace("-", "").replace("_", "")
            val base = when {
                cleaned.endsWith("USDT") -> cleaned.removeSuffix("USDT")
                cleaned.endsWith("IDR") -> cleaned.removeSuffix("IDR")
                cleaned.endsWith("USD") -> cleaned.removeSuffix("USD")
                else -> cleaned
            }.ifEmpty { "BTC" }
            val symbol = "${base}IDR"
            val known = POPULAR_PAIRS.find { it.symbol == symbol || it.baseAsset == base }
            if (known != null) return known
            return TradingPair(symbol, base, "IDR", "$base / IDR", indodaxPair = "${base.lowercase()}_idr")
        }
    }

    fun effectiveIndodaxPair(): String = if (indodaxPair.isNotBlank()) indodaxPair else "${baseAsset.lowercase()}_idr"
}

enum class Timeframe(val code: String, val label: String) {
    M1("1", "1m"), M5("5", "5m"), M15("15", "15m"), H1("60", "1h"), H4("240", "4h"), D1("D", "1d")
}

enum class ChartStyle(val label: String) { CANDLES("Candlesticks"), LINE("Line"), AREA("Area") }
data class OrderBookItem(val price: Double, val amount: Double, val total: Double, val isBid: Boolean)
data class TradeStreamItem(val id: String, val price: Double, val amount: Double, val timeFormatted: String, val isBuy: Boolean)