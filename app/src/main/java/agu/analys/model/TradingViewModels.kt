package agu.analys.model

enum class SignalAction { BUY, SELL, HOLD }

enum class ScalpingStage(val displayName: String) {
    HOLD("TAHAN"),
    WATCH("WATCH"),
    WAIT_PULLBACK("TUNGGU PULLBACK"),
    EARLY_ENTRY("AWAL ENTRY"),
    ENTRY("ENTRY"),
    STRONG_ENTRY("ENTRY KUAT")
}

enum class LifecycleState(val displayName: String) {
    IDLE("IDLE"),
    DETECTED("TERDETEKSI"),
    CONFIRMING("KONFIRMASI"),
    READY("SIAP ENTRY"),
    TRIGGERED("TERPICU"),
    EXPIRED("KEDALUWARSA"),
    INVALIDATED("BATAL")
}

/** Status satu leg MTF untuk UI — diisi engine, bukan dihitung ulang di Compose. */
enum class MtfLegStatus {
    OK,
    PARTIAL,
    WAITING,
    FAIL,
    UNKNOWN
}

enum class ScalpingPath {
    NONE,
    PULLBACK,
    MOMENTUM_CONTINUATION,
    BOTH,
    ENTRY_READY
}

/**
 * Structured MTF snapshot dari ScalpingMtfEvaluator.
 * UI hanya menampilkan — tidak menghitung ulang threshold.
 */
data class ScalpingMtfSnapshot(
    val biasOk: Boolean = false,
    val biasDirection: String = "mixed", // bullish | bearish | mixed
    val biasStatus: MtfLegStatus = MtfLegStatus.UNKNOWN,
    val biasDetail: String = "",
    val setupOk: Boolean = false,
    val setupStatus: MtfLegStatus = MtfLegStatus.UNKNOWN,
    val setupDetail: String = "",
    val triggerOk: Boolean = false,
    val triggerStatus: MtfLegStatus = MtfLegStatus.UNKNOWN,
    val triggerDetail: String = "",
    val entryPriceOk: Boolean = false,
    val entryPriceStatus: MtfLegStatus = MtfLegStatus.UNKNOWN,
    val entryPriceDetail: String = "",
    val path: ScalpingPath = ScalpingPath.NONE,
    val statusTitle: String = "BELUM TERSEDIA",
    val waitingFor: String = "",
    val entryCondition: String = "",
    val extended: Boolean = false,
    val extremeVolatility: Boolean = false
)

enum class TrendSentiment(val displayName: String) {
    STRONG_BULLISH_CONTINUATION("Kelanjutan Bullish Kuat"),
    BULLISH_REVERSAL("Pembalikan Arah Bullish"),
    ACCUMULATION_SQUEEZE("Tunggu Pullback"),
    NEUTRAL_CONSOLIDATION("Konsolidasi Netral"),
    BEARISH_DISTRIBUTION("Distribusi Bearish"),
    BEARISH_BREAKDOWN("Breakdown Bearish"),
    EXTREME_OVERSOLD("Pantulan Jenuh Jual (Oversold)")
}

enum class AppScreen { DASHBOARD, DETAIL, SIMULATION_TRADE, LANDSCAPE_CHART, SETTINGS, LEARNING, PORTFOLIO }

data class WorthCoinInfo(val pair: TradingPair, val worthScore: Int, val isWorthIt: Boolean, val recommendation: String, val potentialProfitPct: Double, val aiRationale: String)
data class CoinHoldingStatus(
    val isHolding: Boolean = false,
    val quantity: Double = 0.0,
    val entryPrice: Double = 0.0,
    val isReal: Boolean = false,
    val tp1Price: Double = 0.0,
    val tp2Price: Double = 0.0,
    val isTrailingTriggered: Boolean = false
)
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
    val scalpingStage: ScalpingStage = ScalpingStage.HOLD,
    val lifecycleState: LifecycleState = LifecycleState.IDLE,
    /** Structured MTF — diisi evaluator scalping. Default kosong untuk swing/offline. */
    val mtf: ScalpingMtfSnapshot = ScalpingMtfSnapshot(),
    /** Info mode offline & backtest walk-forward validation */
    val isOfflineMode: Boolean = false,
    val offlineSnapshotTime: Long = 0L,
    val offlineReason: String = "",
    val backtestWinRatePct: Double = 0.0,
    val backtestScore: Int = 0,
    val walkForwardEfficiencyPct: Double = 0.0,
    val regimeDetected: String = ""
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
        val POPULAR_INDODAX_PAIRS = listOf(
            TradingPair("BTCIDR", "BTC", "IDR", "Bitcoin / IDR", indodaxPair = "btc_idr"),
            TradingPair("ETHIDR", "ETH", "IDR", "Ethereum / IDR", indodaxPair = "eth_idr"),
            TradingPair("SOLIDR", "SOL", "IDR", "Solana / IDR", indodaxPair = "sol_idr"),
            TradingPair("BNBIDR", "BNB", "IDR", "BNB / IDR", indodaxPair = "bnb_idr"),
            TradingPair("XRPIDR", "XRP", "IDR", "XRP / IDR", indodaxPair = "xrp_idr"),
            TradingPair("DOGEIDR", "DOGE", "IDR", "Dogecoin / IDR", indodaxPair = "doge_idr"),
            TradingPair("PEPEIDR", "PEPE", "IDR", "Pepe / IDR", indodaxPair = "pepe_idr"),
            TradingPair("ADAIDR", "ADA", "IDR", "Cardano / IDR", indodaxPair = "ada_idr"),
            TradingPair("AVAXIDR", "AVAX", "IDR", "Avalanche / IDR", indodaxPair = "avax_idr"),
            TradingPair("SHIBIDR", "SHIB", "IDR", "Shiba Inu / IDR", indodaxPair = "shib_idr"),
            TradingPair("NEARIDR", "NEAR", "IDR", "NEAR / IDR", indodaxPair = "near_idr"),
            TradingPair("SUIIDR", "SUI", "IDR", "Sui / IDR", indodaxPair = "sui_idr"),
            TradingPair("DOTIDR", "DOT", "IDR", "Polkadot / IDR", indodaxPair = "dot_idr"),
            TradingPair("LTCIDR", "LTC", "IDR", "Litecoin / IDR", indodaxPair = "ltc_idr"),
            TradingPair("LINKIDR", "LINK", "IDR", "Chainlink / IDR", indodaxPair = "link_idr"),
            TradingPair("MYXIDR", "MYX", "IDR", "MYX Finance / IDR", indodaxPair = "myx_idr")
        )

        val POPULAR_PAIRS = POPULAR_INDODAX_PAIRS

        fun popularPairsForSource(source: agu.analys.config.MarketDataSource? = null): List<TradingPair> = POPULAR_INDODAX_PAIRS

        fun fromCustomSymbol(
            raw: String,
            defaultQuote: String = "IDR"
        ): TradingPair {
            val cleaned = raw.trim().uppercase().replace(" ", "").replace("/", "").replace("-", "").replace("_", "")
            val (base, quote) = when {
                cleaned.endsWith("IDR") -> cleaned.removeSuffix("IDR") to "IDR"
                cleaned.endsWith("USDT") -> cleaned.removeSuffix("USDT") to "USDT"
                cleaned.endsWith("USD") -> cleaned.removeSuffix("USD") to "USD"
                else -> cleaned to defaultQuote
            }
            val finalBase = base.ifEmpty { "BTC" }
            val symbol = "$finalBase$quote"
            val known = POPULAR_INDODAX_PAIRS.find { it.symbol == symbol || (it.baseAsset == finalBase && it.quoteAsset == quote) }
            if (known != null) return known
            return TradingPair(
                symbol = symbol,
                baseAsset = finalBase,
                quoteAsset = quote,
                displayName = "$finalBase / $quote",
                indodaxPair = "${finalBase.lowercase()}_${quote.lowercase()}"
            )
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
