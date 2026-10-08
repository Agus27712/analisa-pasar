package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.config.MarketDataSource
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.DetailUiState
import agu.analys.model.DetailUiStateFactory
import agu.analys.model.MarketConnectionState
import agu.analys.model.PositionContext
import agu.analys.model.resolveWorkflow
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionState
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

/**
 * Builds aggregated [DetailUiState] so DetailChartScreen can collect ONE flow
 * instead of 25+ collectAsStateWithLifecycle calls.
 *
 * UI-layer throttling (on top of PriceFeedThrottler):
 *  - Tick sampled ~300ms — Tokocrypto WS can flood 10–50 ticks/sec
 *  - Order book sampled ~400ms — depth updates are even hotter than ticks
 *  - Market structure cached on last CLOSED candle only (forming candle ignored)
 */
internal fun TradingViewModel.createDetailUiState(): StateFlow<DetailUiState> {
    // Extra UI gate beyond PriceFeedThrottler (default 100ms).
    // Detail screen does not need sub-100ms redraws; 300ms still feels live.
    val tickForUi = currentTick
        .sample(DETAIL_TICK_SAMPLE_MS)
        .distinctUntilChanged { old, new ->
            old?.price == new?.price &&
                old?.change24h == new?.change24h &&
                old?.volume24h == new?.volume24h &&
                old?.symbol == new?.symbol
        }

    val bidsForUi = orderBookBids
        .sample(DETAIL_DEPTH_SAMPLE_MS)
        .distinctUntilChanged()

    val asksForUi = orderBookAsks
        .sample(DETAIL_DEPTH_SAMPLE_MS)
        .distinctUntilChanged()

    // Candles: only propagate when closed-candle fingerprint changes.
    // SynthesizeRealtime still updates _recentCandles every tick for the chart engine,
    // but DetailUiState should not recompute structure on every forming-candle twitch.
    val candlesForUi = recentCandles
        .map { candles -> candles to closedCandleFingerprint(candles) }
        .distinctUntilChanged { a, b -> a.second == b.second }
        .map { it.first }

    val marketSlice = combine(
        selectedPair,
        tickForUi,
        candlesForUi,
        currentIndicators,
        aiSignalState
    ) { pair, tick, candles, indicators, signal ->
        MarketSlice(pair, tick, candles, indicators, signal)
    }

    val positionSlice = combine(
        spotPosition,
        positionVersion,
        positionContext,
        sellSignalState,
        isRealBuyMode
    ) { spot, version, ctx, sell, isReal ->
        PositionSlice(spot, version, ctx, sell, isReal)
    }

    val balanceSlice = combine(
        simulationWallet,
        realIndodaxBalance,
        realAvgBuyPrices,
        tradingFees,
        marketDataSource
    ) { wallet, realBal, avgPrices, fees, source ->
        BalanceSlice(wallet, realBal, avgPrices, fees, source)
    }

    val metaSlice = combine(
        favorites,
        priceAlerts,
        signalHistory,
        connectionState,
        selectedTimeframe
    ) { favs, alerts, history, conn, tf ->
        MetaSlice(favs, alerts, history, conn, tf)
    }

    val modeSlice = combine(
        isScalpingMode,
        strategyMode,
        bidsForUi,
        asksForUi
    ) { scalping, mode, bids, asks ->
        ModeSlice(scalping, mode, bids, asks)
    }

    return combine(
        marketSlice,
        positionSlice,
        balanceSlice,
        metaSlice,
        modeSlice
    ) { market, position, balance, meta, mode ->
        val ai = AiSlice(
            audit = auditReportText.value,
            gemini = geminiSummaryText.value,
            loadAudit = isAuditLoading.value,
            loadGemini = isGeminiLoading.value
        )
        buildDetailUiState(market, position, balance, meta, mode, ai)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DetailUiState.empty()
    )
}

private const val DETAIL_TICK_SAMPLE_MS = 300L
private const val DETAIL_DEPTH_SAMPLE_MS = 400L

/** Fingerprint based on last *closed* candle so forming-candle price noise is ignored. */
private fun closedCandleFingerprint(candles: List<agu.analys.model.CandleBar>): String {
    if (candles.isEmpty()) return "0"
    val lastClosed = candles.asReversed().firstOrNull { it.isClosed } ?: candles.last()
    return "${candles.size}|${lastClosed.timestamp}|${lastClosed.close}|${lastClosed.volume}"
}

/** Module-level structure cache — avoids MarketStructureAnalyzer on every UI sample. */
private object StructureCache {
    @Volatile var key: String = ""
    @Volatile var value: MarketStructureSnapshot? = null

    fun getOrAnalyze(candles: List<agu.analys.model.CandleBar>): MarketStructureSnapshot {
        val key = closedCandleFingerprint(candles)
        val hit = value
        if (key == this.key && hit != null) return hit
        val snap = if (candles.isEmpty()) {
            MarketStructureAnalyzer.analyze(emptyList())
        } else {
            MarketStructureAnalyzer.analyze(candles)
        }
        this.key = key
        this.value = snap
        return snap
    }
}

private data class MarketSlice(
    val pair: agu.analys.model.TradingPair,
    val tick: agu.analys.model.MarketTick?,
    val candles: List<agu.analys.model.CandleBar>,
    val indicators: agu.analys.model.TechnicalIndicators,
    val signal: agu.analys.model.AISignalState
)

private data class PositionSlice(
    val spot: SpotPosition,
    val version: Long,
    val context: PositionContext,
    val sell: agu.analys.model.SellSignalState,
    val isReal: Boolean
)

private data class BalanceSlice(
    val wallet: agu.analys.trading.SimulationWallet,
    val realBal: Map<String, Double>,
    val avgPrices: Map<String, Double>,
    val fees: agu.analys.config.TradingFeeConfig,
    val source: MarketDataSource
)

private data class MetaSlice(
    val favorites: Set<String>,
    val alerts: List<agu.analys.model.PriceAlert>,
    val history: List<agu.analys.model.AISignalState>,
    val connection: MarketConnectionState,
    val timeframe: agu.analys.model.Timeframe
)

private data class ModeSlice(
    val isScalping: Boolean,
    val strategyMode: agu.analys.config.StrategyMode,
    val bids: List<agu.analys.model.OrderBookItem>,
    val asks: List<agu.analys.model.OrderBookItem>
)

private data class AiSlice(
    val audit: String?,
    val gemini: String?,
    val loadAudit: Boolean,
    val loadGemini: Boolean
)

private fun TradingViewModel.buildDetailUiState(
    market: MarketSlice,
    position: PositionSlice,
    balance: BalanceSlice,
    meta: MetaSlice,
    mode: ModeSlice,
    ai: AiSlice
): DetailUiState {
    val pair = market.pair
    val tick = market.tick
    val displayPrice = DetailUiStateFactory.resolveDisplayPrice(tick, market.candles)
    val change24h = tick?.change24h?.takeUnless { it.isNaN() } ?: 0.0
    val volume24h = tick?.volume24h ?: 0.0
    val activityText = DetailUiStateFactory.resolveActivityLabel(
        isUsdtQuote = PriceFormatter.isUsdtQuote(pair.quoteAsset),
        volume24h = volume24h,
        change24h = change24h
    )

    @Suppress("UNUSED_VARIABLE")
    val versionBump = position.version
    val currentPosition = if (
        isMatchingSymbol(position.spot.symbol, pair.symbol) &&
        position.spot.isReal == position.isReal
    ) {
        position.spot
    } else {
        getPositionFor(pair.symbol, position.isReal)
    }

    val holding = getHoldingStatus(pair, position.isReal)
    val effectivePositionContext = if (displayPrice > 0.0) {
        PositionContext.create(
            symbol = pair.symbol,
            spotPosition = currentPosition,
            holdingStatus = holding,
            currentPrice = displayPrice,
            fees = balance.fees,
            currentModeIsReal = position.isReal
        )
    } else {
        position.context
    }

    val effectiveDisplayPosition = if (effectivePositionContext.hasPosition) {
        val entryP = effectivePositionContext.entryPrice ?: currentPosition.entryPrice
        val qty = effectivePositionContext.quantity ?: currentPosition.quantity
        currentPosition.copy(
            symbol = pair.symbol,
            state = SpotPositionState.HOLDING,
            entryPrice = entryP,
            quantity = qty,
            investedAmount = if (currentPosition.investedAmount > 0.0)
                currentPosition.investedAmount else (entryP * qty),
            isReal = position.isReal
        )
    } else currentPosition

    val effectiveSellSignal = if (effectivePositionContext.hasPosition) {
        agu.analys.engine.sell.SellSignalEvaluator.evaluate(
            context = effectivePositionContext,
            indicators = market.indicators,
            tradingFees = balance.fees,
            high24h = tick?.high24h ?: 0.0
        )
    } else position.sell

    val availableQuote = DetailUiStateFactory.resolveAvailableQuote(
        quoteAsset = pair.quoteAsset,
        isReal = position.isReal,
        realFreeForQuote = { realFreeBalanceForQuote(it) },
        realTotalForQuote = { realBalanceForQuote(it) },
        realBalanceMap = balance.realBal,
        savedBalanceMap = prefs.getSavedRealBalance(),
        simUsdt = balance.wallet.getAvailableUsdt(),
        simIdr = balance.wallet.getAvailableIdr()
    )

    val availableCoin = if (position.isReal) {
        balance.realBal[pair.baseAsset.lowercase()]
            ?: balance.realBal[pair.baseAsset.uppercase()]
            ?: 0.0
    } else {
        balance.wallet.getAvailableCoin(pair.baseAsset)
    }

    val avgBuyPrice = if (position.isReal) {
        val api = balance.avgPrices[pair.baseAsset.lowercase()]
            ?: balance.avgPrices[pair.baseAsset.uppercase()]
            ?: 0.0
        if (api > 0.0) api else currentPosition.entryPrice
    } else {
        val api = balance.wallet.avgBuyPrices[pair.baseAsset.uppercase()] ?: 0.0
        if (api > 0.0) api else currentPosition.entryPrice
    }

    val marketStructure = StructureCache.getOrAnalyze(market.candles)

    val providerUsesGroq = try {
        prefs.aiProvider == agu.analys.config.AiProvider.GROQ
    } catch (_: Throwable) {
        true
    }
    val aiText = if (providerUsesGroq) ai.audit.orEmpty() else ai.gemini.orEmpty()

    return DetailUiState(
        pair = pair,
        marketDataSource = balance.source,
        selectedTimeframe = meta.timeframe,
        isConnected = meta.connection is MarketConnectionState.Connected,
        isRealBuyMode = position.isReal,
        isScalping = mode.isScalping,
        strategyMode = mode.strategyMode,
        tradingFees = balance.fees,
        displayPrice = displayPrice,
        change24h = change24h,
        volume24h = volume24h,
        activityText = activityText,
        tick = tick,
        candles = market.candles,
        indicators = market.indicators,
        signal = market.signal,
        orderBookBids = mode.bids,
        orderBookAsks = mode.asks,
        currentPosition = currentPosition,
        effectivePositionContext = effectivePositionContext,
        effectiveDisplayPosition = effectiveDisplayPosition,
        effectiveSellSignal = effectiveSellSignal,
        workflow = resolveWorkflow(effectivePositionContext),
        availableQuote = availableQuote,
        availableCoin = availableCoin,
        avgBuyPrice = avgBuyPrice,
        isFavorite = meta.favorites.contains(pair.symbol.uppercase()) || meta.favorites.contains(pair.symbol),
        priceAlerts = meta.alerts,
        signalHistory = meta.history,
        marketStructure = marketStructure,
        aiReportText = aiText,
        isAiLoading = ai.loadAudit || ai.loadGemini
    )
}
