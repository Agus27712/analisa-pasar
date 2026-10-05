package agu.analys.engine.scalping

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.engine.regime.MarketRegimeEngine
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.MtfLegStatus
import agu.analys.model.OrderBookItem
import agu.analys.model.ScalpSetupType
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.model.ScalpingPath
import agu.analys.model.ScalpingSignal
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.model.SignalAudit
import agu.analys.model.SignalDirection
import agu.analys.model.StructureBias
import agu.analys.model.StructureSnapshot
import agu.analys.model.TechnicalIndicators
import agu.analys.model.TrendSentiment
import agu.analys.util.PriceFormatter
import kotlin.math.abs

/**
 * Orchestrator scalping KriptoYoi (P1.4 + P2 wiring).
 *
 * Pipeline: Indicators → Structure → Regime → Setup → MTF Matrix → Score → Risk → Direction.
 * Dipetakan ke [AISignalState] (BUY ← LONG, HOLD ← WAIT / SHORT sementara) dan [SignalAudit].
 *
 * Wired ke modul P2:
 *  - [ScalpingConfig] untuk threshold skor / stale orderbook
 *  - [OrderBookAnalyzer.analyzeOrderFlow] untuk buy pressure + imbalance
 *  - [MtfConfluenceMatrix.buildPartial] untuk alignment label
 *  - [HistoricalEdgeStub] (pesan INSUFFICIENT, tanpa angka palsu)
 *  - Falling-knife guard (struktur M15 bearish + M1 dump)
 *
 * Mapping checkpoint:
 *  - step1: tidak berbahaya (noise/overbought/falling-knife) + ruang naik M15
 *  - step2: order book valid
 *  - step3: setup + trigger VWAP/reclaim M1
 *  - step4: risk valid (Net R:R) + skor ≥ gate setup (retest 75, lainnya MIN_SCORE_LONG)
 *
 * WAIT adalah output sah. Exchange-agnostic (IDR & USDT).
 */
object ScalpingMtfEvaluator {

    /** Skor minimal untuk arah LONG — selaras [ScalpingConfig.MIN_SCORE_LONG]. */
    const val MIN_SCORE_LONG = ScalpingConfig.MIN_SCORE_LONG

    /** Skor untuk stage STRONG_ENTRY — selaras [ScalpingConfig.MIN_SCORE_STRONG]. */
    const val STRONG_SCORE = ScalpingConfig.MIN_SCORE_STRONG

    private const val ORDERBOOK_STALE_MS = ScalpingConfig.ORDERBOOK_STALE_MS

    data class Result(
        val signal: AISignalState,
        val indicators: TechnicalIndicators,
        val audit: SignalAudit = SignalAudit(),
        val scalping: ScalpingSignal = ScalpingSignal()
    )

    fun evaluate(
        price: Double,
        h1Candles: List<CandleBar>,
        m15Candles: List<CandleBar>,
        m1Candles: List<CandleBar>,
        formingVolume: Double = 0.0,
        bids: List<OrderBookItem> = emptyList(),
        asks: List<OrderBookItem> = emptyList(),
        fees: TradingFeeConfig = TradingFeeConfig(),
        symbol: String = "",
        orderBookAgeMs: Long = 0L,
        diagnosticIgnoreOrderBookWhenUnavailable: Boolean = false
    ): Result? = evaluate(
        globalContext = agu.analys.engine.global.GlobalMarketContext(),
        price = price,
        h1Candles = h1Candles,
        m15Candles = m15Candles,
        m1Candles = m1Candles,
        formingVolume = formingVolume,
        bids = bids,
        asks = asks,
        fees = fees,
        symbol = symbol,
        orderBookAgeMs = orderBookAgeMs,
        diagnosticIgnoreOrderBookWhenUnavailable = diagnosticIgnoreOrderBookWhenUnavailable
    )

    fun evaluate(
        globalContext: agu.analys.engine.global.GlobalMarketContext = agu.analys.engine.global.GlobalMarketContext(),
        price: Double,
        h1Candles: List<CandleBar>,
        m15Candles: List<CandleBar>,
        m1Candles: List<CandleBar>,
        formingVolume: Double = 0.0,
        bids: List<OrderBookItem> = emptyList(),
        asks: List<OrderBookItem> = emptyList(),
        fees: TradingFeeConfig = TradingFeeConfig(),
        symbol: String = "",
        orderBookAgeMs: Long = 0L,
        diagnosticIgnoreOrderBookWhenUnavailable: Boolean = false
    ): Result? {
        if (price <= 0.0 || h1Candles.size < 20 || m15Candles.size < 20 || m1Candles.size < 20) return null

        val m15Ready = m15Candles.size >= 40
        val currency = if (symbol.uppercase().contains("USDT")) "$" else "Rp "

        val isOrderBookEmpty = bids.isEmpty() && asks.isEmpty()
        val isOrderBookStale = orderBookAgeMs > ORDERBOOK_STALE_MS
        val orderFlow = if (!isOrderBookEmpty) {
            OrderBookAnalyzer.analyzeOrderFlow(bids, asks, 15)
        } else {
            OrderFlowSnapshot()
        }
        val buyPressure = orderFlow.buyPressure
        val orderImbalance = orderFlow.orderImbalance
        val isOrderBookValid = if (diagnosticIgnoreOrderBookWhenUnavailable && isOrderBookEmpty) {
            true
        } else {
            !isOrderBookEmpty && !isOrderBookStale && buyPressure > 1.0
        }

        val last1M = m1Candles.last()
        val avgVol1M = m1Candles.takeLast(20).map { it.volume }.average()
        val formingVolValid = if (formingVolume > 0) formingVolume else last1M.volume

        val candleRange = last1M.high - last1M.low
        val candleBody = abs(last1M.close - last1M.open)
        val candleWick = candleRange - candleBody
        val closeInTopThird = (last1M.high - last1M.close) <= candleRange / 3.0
        val isVSABreakout = formingVolValid > avgVol1M * 1.2 && closeInTopThird

        val atr1M = IndicatorMath.atr(m1Candles, 14)
        val volPct = (atr1M / price) * 100.0
        val isExtremeVol = volPct >= 4.0
        val isMomentum = candleBody > candleWick && last1M.close > last1M.open
        val isDangerousNoise = isExtremeVol && !isMomentum

        val safeVwapCandles = if (m1Candles.size >= 60) m1Candles else m1Candles.takeLast(m1Candles.size)
        val vwap1M = IndicatorMath.rollingVwap(safeVwapCandles, minOf(60, safeVwapCandles.size))
        val rsi1M = IndicatorMath.rsi(m1Candles, minOf(14, m1Candles.size - 1))
        val isOverbought = rsi1M >= 85.0

        val m1Closes = DoubleArray(m1Candles.size) { m1Candles[it].close }
        val ema13 = IndicatorMath.ema(m1Closes, minOf(13, m1Closes.size))
        val ema21 = IndicatorMath.ema(m1Closes, minOf(21, m1Closes.size))
        val ema20 = IndicatorMath.ema(m1Closes, minOf(20, m1Closes.size))
        val ema50 = IndicatorMath.ema(m1Closes, minOf(50, m1Closes.size))
        val macd = IndicatorMath.macdSeries(m1Closes, 12, 26, 9).last()

        val isEma13And21Aligned = ema13 >= ema21 * 0.998
        val isEma13Bounce = price >= ema13 * 0.995 && price <= ema13 * 1.015
        val rvol = IndicatorMath.relativeVolume(m1Candles, 20)

        val struct15M = if (m15Ready) MarketStructureAnalyzer.analyze(m15Candles.takeLast(40)) else null
        val structure: StructureSnapshot = struct15M
            ?.let { MarketStructureAnalyzer.toStructureSnapshot(it) }
            ?: StructureSnapshot()

        val resistance = struct15M?.resistance ?: (price * 1.05)
        val isBreakoutAboveResistance = price >= resistance
        val isBreakoutWithVolume = price >= (resistance * 0.995) && isVSABreakout
        val isRoomClearToResistance = price < (resistance * 0.995)
        val hasRoomToGrow = isRoomClearToResistance || isBreakoutAboveResistance || isBreakoutWithVolume

        val regime = MarketRegimeEngine.detect(
            candles = m1Candles,
            price = price,
            structureBias = structure.bias,
            rvol = rvol,
            resistanceBroken = m15Ready && isBreakoutAboveResistance
        )

        val setupResult = ScalpSetupDetector.detect(
            price = price,
            structure = structure,
            regime = regime,
            rvol = rvol,
            buyPressure = buyPressure,
            m1Candles = m1Candles
        )
        val setup = setupResult.setup

        val mtfMatrix = MtfConfluenceMatrix.buildPartial(
            h1 = h1Candles,
            m15 = m15Candles,
            m1 = m1Candles
        )
        val mtfAligned = mtfMatrix.bullishCount
        val mtfKnown = mtfMatrix.knownCount.coerceAtLeast(1)
        val mtfLabel = mtfMatrix.alignmentLabel
        val score = SignalScoringEngine.score(
            SignalScoringEngine.Input(
                price = price,
                structure = structure,
                regime = regime,
                setup = setup,
                rvol = rvol,
                buyPressure = buyPressure,
                orderImbalance = orderImbalance,
                hasOrderBook = !isOrderBookEmpty,
                mtfAligned = mtfAligned,
                mtfTotal = mtfKnown,
                m1Candles = m1Candles
            )
        )

        val riskResult = ScalpingRiskEngine.evaluate(
            ScalpingRiskEngine.Input(
                price = price,
                setup = if (setup != ScalpSetupType.NONE) setup else ScalpSetupType.TREND_PULLBACK,
                structure = structure,
                regime = regime,
                fees = fees
            )
        )
        val riskLevels = riskResult.levels
        val riskValid = setup != ScalpSetupType.NONE && riskResult.valid
        val netRr = riskLevels?.netRr ?: 0.0

        val isFallingKnife = structure.bias == StructureBias.BEARISH &&
            m1Candles.size >= 5 &&
            m1Candles.last().close < m1Candles[m1Candles.size - 5].close * 0.992

        val isDangerous = isDangerousNoise || isOverbought || isFallingKnife

        val step1Ok = !isDangerous && hasRoomToGrow
        val step2Ok = step1Ok && isOrderBookValid
        val isVwapOrReclaimValid = price > vwap1M || isVSABreakout ||
            (rsi1M in 38.0..68.0 && last1M.close > last1M.open && last1M.close >= (vwap1M * 0.9985)) ||
            isEma13Bounce
        val step3Ok = step2Ok && setup != ScalpSetupType.NONE && isVwapOrReclaimValid
        // Gate skor per setup: BREAKOUT_RETEST butuh skor lebih tinggi (replay −EV di 60–74)
        val minScoreForLong = ScalpingConfig.minScoreForSetup(setup.name)
        val step4Ok = step3Ok && riskValid && score.total >= minScoreForLong

        val ready = step4Ok
        val strong = ready && score.total >= STRONG_SCORE
        val early = !ready && !isDangerous && step2Ok

        val direction = if (ready) SignalDirection.LONG else SignalDirection.WAIT
        val finalAction = if (direction == SignalDirection.LONG) SignalAction.BUY else SignalAction.HOLD

        val reasons = mutableListOf<String>()
        if (isDangerousNoise) reasons.add("⚠️ Tertahan: Volatilitas pasar sedang liar (Noise tinggi).")
        if (isOverbought) reasons.add("⚠️ Tertahan: Harga koin sedang terlalu tinggi (Jenuh Beli/Overbought RSI 1M ${fmt(rsi1M)}).")
        if (isFallingKnife) reasons.add("⚠️ Tertahan: Falling knife (struktur M15 bearish + M1 turun tajam).")
        if (!hasRoomToGrow) reasons.add("⚠️ Tertahan: Harga koin terlalu dekat resistance M15 (Ruang naik sempit).")
        reasons.add(
            if (setup == ScalpSetupType.NONE) "Setup: belum ada. ${setupResult.explanation}"
            else "Setup ${setup.name}: ${setupResult.explanation}"
        )
        reasons.add("Skor ${score.total}/100 (${score.category}) · MTF $mtfLabel · RVOL ${fmt(rvol)}x.")
        riskResult.reasons.lastOrNull()?.let { reasons.add(it) }

        val spreadAnalysis = OrderBookAnalyzer.analyzeSpread(bids, asks, price)
        if (spreadAnalysis.isSpreadGuardActive) {
            reasons.add("🛡️ SPREAD GUARD: Spread ${fmt(spreadAnalysis.spreadPct)}% terlalu lebar. Wajib Limit Maker di $currency${PriceFormatter.formatPrice(spreadAnalysis.recommendedEntryPrice, showSymbol = false)}, hindari Hajar Kanan.")
        } else if (spreadAnalysis.executionType == EntryExecutionType.LIMIT_MAKER) {
            reasons.add("💡 Rekomendasi: Antri Limit Maker di $currency${PriceFormatter.formatPrice(spreadAnalysis.recommendedEntryPrice, showSymbol = false)} untuk hemat fee.")
        }

        reasons.add("EMA 13/21: ${if (isEma13And21Aligned) "Uptrend Bounce (EMA13 > EMA21)" else "Cross / Neutral"}")
        reasons.add("VWAP 1M: ${fmt(vwap1M)}")
        if (isOrderBookEmpty) {
            reasons.add("Tekanan Beli: Diabaikan (Orderbook Kosong)")
        } else {
            reasons.add("Tekanan Beli (Orderbook): ${fmt(buyPressure)}x · imbalance ${fmt(orderImbalance)}")
        }
        if (isVSABreakout) reasons.add("VSA Breakout Terdeteksi! (Vol: ${fmt(formingVolValid / avgVol1M)}x)")
        if (finalAction == SignalAction.BUY && buyPressure < 0.7) {
            reasons.add("💡 Likuiditas bid/ask order book agak tipis (${fmt(buyPressure)}x), disarankan cicil bertahap.")
        }
        reasons.add("Risk Management: Gunakan alokasi 3-5% dari modal portofolio (Aturan Akademi Crypto).")

        when {
            strong -> reasons.add(0, "STRONG ENTRY: Skor ${score.total} + Net R:R 1:${fmt(netRr)}")
            ready -> reasons.add(0, "BUY READY: Skor ${score.total} + Net R:R 1:${fmt(netRr)}.")
            early -> reasons.add(0, "EARLY: kondisi awal terbentuk, tunggu setup & konfirmasi penuh.")
            isDangerous -> {}
            else -> reasons.add(0, "WAIT: belum ada setup valid. Menunggu momentum & orderbook.")
        }

        val stage = when {
            strong -> ScalpingStage.STRONG_ENTRY
            ready -> ScalpingStage.ENTRY
            early -> ScalpingStage.EARLY_ENTRY
            isDangerous -> ScalpingStage.HOLD
            else -> ScalpingStage.WATCH
        }

        val biasDetailText = when {
            isDangerousNoise -> "Tertahan: Volatilitas pasar sedang liar (Noise tinggi)."
            isFallingKnife -> "Tertahan: Falling knife (M15 bearish + M1 dump)."
            isOverbought -> "Tertahan: Harga koin sedang terlalu tinggi (Jenuh Beli/Overbought)."
            !hasRoomToGrow -> "Tertahan: Harga koin terlalu dekat resistance M15 (Ruang naik sempit)."
            step1Ok -> "Target H1/M15 aman (Ruang naik terbuka). MTF $mtfLabel."
            else -> "Memantau ruang gerak M15/H1."
        }
        val setupDetailText = when {
            !step1Ok -> "Menunggu Checkpoint 1 lolos."
            step2Ok -> "Orderbook Bid/Ask ratio ${fmt(buyPressure)}x."
            else -> "Menunggu tekanan beli (Bid/Ask > 1.0x)."
        }
        val triggerDetailText = when {
            !step2Ok -> "Menunggu Checkpoint 2 lolos."
            step3Ok -> "Setup ${setup.name} terdeteksi & trigger VWAP 1M (${fmt(vwap1M)}) terkonfirmasi."
            setup == ScalpSetupType.NONE -> "Menunggu setup scalping valid (sweep/retest/breakout/pullback)."
            else -> "Menunggu harga menembus VWAP 1M."
        }
        val entryDetailText = when {
            !step3Ok -> "Menunggu Checkpoint 3 lolos."
            step4Ok -> "Net RR: 1:${fmt(netRr)} (Valid), skor ${score.total}."
            !riskValid -> "Menunggu Net RR optimal (Min 1:${fmt(ScalpingRiskEngine.DEFAULT_MIN_NET_RR)})."
            else -> "Menunggu skor sinyal ≥ $minScoreForLong (${setup.name}, sekarang ${score.total})."
        }

        val confluence = agu.analys.engine.confluence.ConfluenceEvaluator.evaluate(
            price = price,
            macroCandles = m15Candles,
            microCandles = m1Candles,
            strategyMode = agu.analys.config.StrategyMode.SCALPING,
            orderBookBids = bids,
            orderBookAsks = asks,
            fees = fees
        )

        val mtf = ScalpingMtfSnapshot(
            biasOk = step1Ok,
            biasDirection = if (step1Ok) "ruang_naik" else "terhalang",
            biasStatus = if (step1Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            biasDetail = biasDetailText,
            setupOk = step2Ok,
            setupStatus = if (step2Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            setupDetail = setupDetailText,
            triggerOk = step3Ok,
            triggerStatus = if (step3Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            triggerDetail = triggerDetailText,
            entryPriceOk = step4Ok,
            entryPriceStatus = if (step4Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            entryPriceDetail = entryDetailText,
            path = if (ready) ScalpingPath.ENTRY_READY else ScalpingPath.NONE,
            statusTitle = when {
                isDangerousNoise -> "NOISE TINGGI (HOLD)"
                isFallingKnife -> "FALLING KNIFE (HOLD)"
                isOverbought -> "OVERBOUGHT (HOLD)"
                strong -> "STRONG ENTRY"
                ready -> "BUY READY"
                step3Ok -> "TRIGGER READY (3/4)"
                early -> "EARLY SETUP (2/4)"
                step1Ok -> "BIAS OK (1/4)"
                else -> "WATCHING (0/4)"
            },
            waitingFor = when {
                isDangerousNoise -> "Menunggu volatilitas stabil"
                isFallingKnife -> "Menunggu stabilisasi setelah dump"
                isOverbought -> "Menunggu koreksi / reset RSI 1M"
                !hasRoomToGrow -> "Menunggu breakout resistance M15"
                ready -> "Eksekusi"
                step3Ok -> "Konfirmasi Net R:R & skor"
                step2Ok -> "Setup scalping valid & VWAP"
                step1Ok -> "Tekanan Beli Orderbook"
                else -> "Menunggu setup lengkap"
            },
            entryCondition = "Setup valid · VWAP/VSA M1 · Orderbook > 1.0 · Skor ≥ gate setup",
            extended = rsi1M > 78.0,
            extremeVolatility = isDangerousNoise,
            checkpoints = confluence.checkpoints,
            completedCount = confluence.completedCount
        )

        val confidence = when {
            isDangerous -> 0
            ready -> score.total
            else -> minOf(
                score.total,
                when {
                    step3Ok -> 59
                    step2Ok -> 49
                    step1Ok -> 39
                    else -> 29
                }
            ).coerceAtLeast(10)
        }

        val signal = AISignalState(
            action = finalAction,
            confidence = confidence,
            sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
            entryPrice = price,
            targetPrice1 = riskLevels?.takeProfit1 ?: 0.0,
            targetPrice2 = riskLevels?.takeProfit2 ?: 0.0,
            stopLoss = riskLevels?.stopLoss ?: 0.0,
            riskRewardRatio = "1:${fmt(netRr)}",
            reasoning = reasons.take(8),
            timestamp = System.currentTimeMillis(),
            scalpingStage = stage,
            mtf = mtf,
            isOfflineMode = false,
            backtestWinRatePct = 0.0,
            backtestScore = 0,
            walkForwardEfficiencyPct = 0.0,
            regimeDetected = regime.regime.name
        )

        val rejectionReason = when {
            finalAction == SignalAction.BUY -> null
            !step1Ok -> when {
                isDangerousNoise -> "STEP1_DANGEROUS_NOISE"
                isOverbought -> "STEP1_OVERBOUGHT"
                isFallingKnife -> "STEP1_FALLING_KNIFE"
                !hasRoomToGrow -> "STEP1_NO_ROOM_TO_GROW"
                else -> "STEP1_BIAS"
            }
            !step2Ok -> when {
                isOrderBookEmpty -> "STEP2_ORDERBOOK_EMPTY"
                isOrderBookStale -> "STEP2_ORDERBOOK_STALE"
                else -> "STEP2_BUY_PRESSURE"
            }
            !step3Ok -> if (setup == ScalpSetupType.NONE) "STEP3_NO_SETUP" else "STEP3_TRIGGER"
            !step4Ok -> if (!riskValid) "STEP4_RR" else "STEP4_SCORE"
            isDangerous -> "DANGEROUS"
            else -> "WAITING_CONFIRMATION"
        }

        val now = System.currentTimeMillis()
        val audit = SignalAudit(
            symbol = symbol,
            timestamp = now,
            price = price,
            step1 = step1Ok,
            step2 = step2Ok,
            step3 = step3Ok,
            step4 = step4Ok,
            buyPressure = buyPressure,
            vwap = vwap1M,
            rsi = rsi1M,
            rr = netRr,
            finalAction = finalAction.name,
            rejectionReason = rejectionReason,
            isOrderBookEmpty = isOrderBookEmpty,
            orderBookAgeMs = orderBookAgeMs,
            orderBookDataAvailable = !isOrderBookEmpty,
            score = score.total,
            scoreCategory = score.category,
            setup = setup.name,
            direction = direction.name,
            regime = regime.regime.name
        )

        val edgeMsg = HistoricalEdgeStub.stubMessage(setup = setup, regime = regime.regime)
        val scalping = ScalpingSignal(
            direction = direction,
            setup = setup,
            score = score,
            regime = regime,
            structure = structure,
            risk = if (setup != ScalpSetupType.NONE) riskLevels else null,
            mtfAlignment = mtfLabel,
            rvol = rvol,
            buyPressure = buyPressure,
            orderImbalance = orderImbalance,
            reasoning = reasons.toList(),
            historicalEdgeStub = edgeMsg,
            timestamp = now
        )

        return Result(
            signal = signal,
            indicators = TechnicalIndicators(
                rsi14 = rsi1M,
                macd = macd.first,
                macdHist = macd.first - macd.second,
                ema20 = ema20,
                ema50 = ema50,
                atr = atr1M,
                momentum = buyPressure
            ),
            audit = audit,
            scalping = scalping
        )
    }

    private fun fmt(v: Double) = PriceFormatter.fmt(v)
}
