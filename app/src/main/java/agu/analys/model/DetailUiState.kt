package agu.analys.model

import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.trading.SpotPosition
import agu.analys.util.PriceFormatter

/**
 * Aggregated UI state for coin detail screen.
 *
 * Goal: screen collects ONE StateFlow instead of 25+ flows → far fewer recompositions.
 *
 * Built in ViewModel via kotlinx.coroutines.flow.combine of market + position + wallet flows.
 *
 * Trading context:
 *  - Tokocrypto: USDT + IDR
 *  - Indodax: IDR only
 *  → [availableQuote] always follows [pair.quoteAsset]
 */
data class DetailUiState(
    // Identity / config
    val pair: TradingPair,
    val marketDataSource: MarketDataSource = MarketDataSource.INDODAX,
    val selectedTimeframe: Timeframe = Timeframe.H1,
    val isConnected: Boolean = false,
    val isRealBuyMode: Boolean = false,
    val isScalping: Boolean = false,
    val strategyMode: StrategyMode = StrategyMode.SCALPING,
    val tradingFees: TradingFeeConfig = TradingFeeConfig(),

    // Live market
    val displayPrice: Double = 0.0,
    val change24h: Double = 0.0,
    val volume24h: Double = 0.0,
    val activityText: String = "Aktivitas rendah",
    val tick: MarketTick? = null,
    val candles: List<CandleBar> = emptyList(),
    val indicators: TechnicalIndicators = TechnicalIndicators(),
    val signal: AISignalState = AISignalState(),
    val orderBookBids: List<OrderBookItem> = emptyList(),
    val orderBookAsks: List<OrderBookItem> = emptyList(),

    // Position
    val currentPosition: SpotPosition = SpotPosition(),
    val effectivePositionContext: PositionContext = PositionContext(),
    val effectiveDisplayPosition: SpotPosition = SpotPosition(),
    val effectiveSellSignal: SellSignalState = SellSignalState(),
    val workflow: TradingWorkflow = TradingWorkflow.BUY,

    // Balances — quote follows pair (USDT pair → USDT, IDR pair → IDR)
    val availableQuote: Double = 0.0,
    val availableCoin: Double = 0.0,
    val avgBuyPrice: Double = 0.0,

    // Meta
    val isFavorite: Boolean = false,
    val priceAlerts: List<PriceAlert> = emptyList(),
    val signalHistory: List<AISignalState> = emptyList(),
    val marketStructure: MarketStructureSnapshot? = null,

    // AI dialog
    val aiReportText: String = "",
    val isAiLoading: Boolean = false,
) {
    /** Alias lama untuk Radar (availableIdr = available quote whatever it is). */
    val availableIdr: Double get() = availableQuote

    val isHolding: Boolean get() = effectivePositionContext.hasPosition || currentPosition.isHolding

    companion object {
        val EMPTY_PAIR = TradingPair(
            symbol = "",
            baseAsset = "",
            quoteAsset = "IDR",
            displayName = ""
        )

        fun empty(): DetailUiState = DetailUiState(pair = EMPTY_PAIR)
    }
}

/**
 * Pure helpers for building [DetailUiState] outside composition scope.
 * Shared between ViewModel combine and unit tests.
 */
object DetailUiStateFactory {

    fun resolveActivityLabel(isUsdtQuote: Boolean, volume24h: Double, change24h: Double): String {
        return if (isUsdtQuote) {
            when {
                volume24h >= 100_000_000.0 || change24h >= 3.0 -> "Aktivitas tinggi"
                volume24h >= 5_000_000.0 || change24h >= 0.0 -> "Aktivitas sedang"
                else -> "Aktivitas rendah"
            }
        } else {
            when {
                volume24h >= 50_000_000_000 || change24h >= 3.0 -> "Aktivitas tinggi"
                volume24h >= 1_000_000_000 || change24h >= 0.0 -> "Aktivitas sedang"
                else -> "Aktivitas rendah"
            }
        }
    }

    fun resolveAvailableQuote(
        quoteAsset: String,
        isReal: Boolean,
        realFreeForQuote: (String) -> Double,
        realTotalForQuote: (String) -> Double,
        realBalanceMap: Map<String, Double>,
        savedBalanceMap: Map<String, Double>,
        simUsdt: Double,
        simIdr: Double
    ): Double {
        val isUsdt = PriceFormatter.isUsdtQuote(quoteAsset)
        return if (isUsdt) {
            if (isReal) {
                val free = realFreeForQuote("USDT")
                val total = realTotalForQuote("USDT")
                val fromMap = realBalanceMap.entries.firstOrNull { (k, _) ->
                    val key = k.lowercase()
                    key == "usdt" || key == "usd" || key == "usdc" || key == "busd"
                }?.value ?: 0.0
                val fromSaved = savedBalanceMap.entries.firstOrNull { (k, _) ->
                    val key = k.lowercase()
                    key == "usdt" || key == "usd" || key == "usdc" || key == "busd"
                }?.value ?: 0.0
                when {
                    free > 0.0 -> free
                    total > 0.0 -> total
                    fromMap > 0.0 -> fromMap
                    fromSaved > 0.0 -> fromSaved
                    else -> 0.0
                }
            } else simUsdt
        } else {
            if (isReal) {
                val free = realFreeForQuote("IDR")
                val total = realTotalForQuote("IDR")
                val fromMap = realBalanceMap["idr"] ?: realBalanceMap["IDR"] ?: 0.0
                val fromSaved = savedBalanceMap["idr"] ?: savedBalanceMap["IDR"] ?: 0.0
                when {
                    free > 0.0 -> free
                    total > 0.0 -> total
                    fromMap > 0.0 -> fromMap
                    fromSaved > 0.0 -> fromSaved
                    else -> 0.0
                }
            } else simIdr
        }
    }

    fun <T> resolveMtfForPair(
        mtfAll: Map<String, Map<Timeframe, T>>,
        pair: TradingPair
    ): Map<Timeframe, T> {
        val raw = pair.symbol
        val upper = raw.trim().uppercase()
        val clean = upper.replace("_", "").replace("/", "").replace("-", "")
        return mtfAll[raw]
            ?: mtfAll[upper]
            ?: mtfAll[clean]
            ?: mtfAll[pair.indodaxPair.uppercase()]
            ?: mtfAll[pair.tokocryptoPair.uppercase()]
            ?: emptyMap()
    }

    fun resolveDisplayPrice(tick: MarketTick?, candles: List<CandleBar>, lastKnown: Double = 0.0): Double {
        val live = tick?.price?.takeIf { it > 0.0 && it.isFinite() }
            ?: lastKnown.takeIf { it > 0.0 }
        if (live != null && live > 0.0) return live
        return candles.lastOrNull()?.close?.takeIf { it > 0.0 && it.isFinite() } ?: 0.0
    }
}
