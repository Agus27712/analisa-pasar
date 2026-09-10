package agu.analys.engine.scalping

import agu.analys.config.ScalpingSensitivity
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.backtest.WalkForwardEvaluator
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.model.AISignalState
import agu.analys.model.MtfLegStatus
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.model.ScalpingPath
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.TrendSentiment
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.config.FeeCalculator
import kotlin.math.abs
import kotlin.math.max

object ScalpingMtfEvaluator {
    data class Result(val signal: AISignalState, val indicators: TechnicalIndicators)

    fun evaluate(
        price: Double,
        h1Candles: List<CandleBar>,
        m15Candles: List<CandleBar>,
        m1Candles: List<CandleBar>,
        formingVolume: Double = 0.0,
        bids: List<OrderBookItem> = emptyList(),
        asks: List<OrderBookItem> = emptyList(),
        fees: TradingFeeConfig = TradingFeeConfig(),
        sensitivity: ScalpingSensitivity = ScalpingSensitivity.BALANCED
    ): Result? = evaluate(agu.analys.engine.global.GlobalMarketContext(), price, h1Candles, m15Candles, m1Candles, formingVolume, bids, asks, fees, sensitivity)

    fun evaluate(globalContext: agu.analys.engine.global.GlobalMarketContext = agu.analys.engine.global.GlobalMarketContext(),
        price: Double,
        h1Candles: List<CandleBar>,
        m15Candles: List<CandleBar>,
        m1Candles: List<CandleBar>,
        formingVolume: Double = 0.0,
        bids: List<OrderBookItem> = emptyList(),
        asks: List<OrderBookItem> = emptyList(),
        fees: TradingFeeConfig = TradingFeeConfig(),
        sensitivity: ScalpingSensitivity = ScalpingSensitivity.BALANCED
    ): Result? {
        if (price <= 0.0 || h1Candles.size < 20 || m15Candles.size < 20 || m1Candles.size < 20) return null

        val isAggressive = sensitivity == ScalpingSensitivity.AGGRESSIVE || sensitivity == ScalpingSensitivity.DYNAMIC_AUTO
        val m15Ready = m15Candles.size >= 40

        // 1. Order Book Pressure & VSA (Volume Spread Analysis)
        val isOrderBookEmpty = bids.isEmpty() && asks.isEmpty()
        val buyPressure = if (!isOrderBookEmpty) OrderBookAnalyzer.calculateBuyPressure(bids, asks, 15) else 1.0
        
        val last1M = m1Candles.last()
        val avgVol1M = m1Candles.takeLast(20).map { it.volume }.average()
        val formingVolValid = if (formingVolume > 0) formingVolume else last1M.volume
        
        val candleRange = last1M.high - last1M.low
        val candleBody = abs(last1M.close - last1M.open)
        val candleWick = candleRange - candleBody
        val closeInTopThird = (last1M.high - last1M.close) <= candleRange / 3.0
        
        val isVSABreakout = formingVolValid > avgVol1M * (if (isAggressive) 1.2 else 1.5) && closeInTopThird
        
        // 2. Volatility Check (Differentiate Momentum vs Noise)
        val atr1M = IndicatorMath.atr(m1Candles, 14)
        val volPct = (atr1M / price) * 100.0
        val isExtremeVol = volPct >= 4.0
        val isMomentum = candleBody > candleWick && last1M.close > last1M.open
        val isDangerousNoise = isExtremeVol && !isMomentum

        // 3. VWAP & RSI Trigger (M1)
        val safeVwapCandles = if (m1Candles.size >= 60) m1Candles else m1Candles.takeLast(m1Candles.size)
        val vwap1M = IndicatorMath.rollingVwap(safeVwapCandles, minOf(60, safeVwapCandles.size))
        val rsi1M = IndicatorMath.rsi(m1Candles, minOf(14, m1Candles.size - 1))
        val isOverbought = rsi1M >= 80.0
        
        // 4. Macro Room to Grow (M15 / H1)
        val struct15M = if (m15Ready) MarketStructureAnalyzer.analyze(m15Candles.takeLast(40)) else null
        val resistance = struct15M?.resistance ?: (price * 1.05)
        val hasRoomToGrow = price < resistance * 0.995

        // Indicators for state
        val m1Closes = m1Candles.map { it.close }
        val ema20 = IndicatorMath.ema(m1Closes, 20)
        val ema50 = IndicatorMath.ema(m1Closes, 50)
        val macd = IndicatorMath.macdSeries(m1Closes, 12, 26, 9).last()

        // 5. Risk / Reward Calculation
        val stopPct = if (isAggressive) volPct * 1.2 else volPct * 0.8
        val sl = price * (1.0 - (stopPct.coerceIn(0.5, 3.0) / 100.0))
        
        val requiredNetRewardPct = ((price - sl)/price * 100.0 + fees.buyTakerPct + fees.sellTakerPct) * 1.2
        val tp1 = price * (1.0 + max(0.9, requiredNetRewardPct * 0.6) / 100.0)
        val tp2 = price * (1.0 + max(1.5, requiredNetRewardPct) / 100.0)
        
        val feeResult = FeeCalculator.roundTrip(price, sl, tp2, fees, false, 0.08)
        val rrOk = feeResult.netRr >= 1.05

        // --- 1. DANGER & INVALIDATION CHECKS (DI ATAS) ---
        val isDangerous = isDangerousNoise || isOverbought

        // --- 2. WATERFALL CHECKPOINTS ---
        val step1Ok = !isDangerous && hasRoomToGrow
        val step2Ok = step1Ok && (buyPressure > 1.0 || isOrderBookEmpty)
        val step3Ok = step2Ok && (price > vwap1M || isVSABreakout)
        val step4Ok = step3Ok && rrOk

        val completedSteps = when {
            step4Ok -> 4
            step3Ok -> 3
            step2Ok -> 2
            step1Ok -> 1
            else -> 0
        }

        val ready = step4Ok
        val strong = ready && (isVSABreakout || buyPressure >= 1.25 || (rsi1M in 45.0..68.0 && price > vwap1M))
        val early = !ready && !isDangerous && step2Ok

        val reasons = mutableListOf<String>()
        if (isDangerousNoise) reasons.add("⚠️ Tertahan: Volatilitas pasar sedang liar (Noise tinggi).")
        if (isOverbought) reasons.add("⚠️ Tertahan: Harga koin sedang terlalu tinggi (Jenuh Beli/Overbought RSI 1M ${fmt(rsi1M)}).")
        if (!hasRoomToGrow) reasons.add("⚠️ Tertahan: Harga koin terlalu dekat resistance M15 (Ruang naik sempit).")
        reasons.add("VWAP 1M: ${fmt(vwap1M)}")
        if (isOrderBookEmpty) {
            reasons.add("Tekanan Beli: Diabaikan (Orderbook Kosong)")
        } else {
            reasons.add("Tekanan Beli (Orderbook): ${fmt(buyPressure)}x")
        }
        if (isVSABreakout) reasons.add("VSA Breakout Terdeteksi! (Vol: ${fmt(formingVolValid / avgVol1M)}x)")

        when {
            strong -> reasons.add(0, "STRONG ENTRY: VSA/OB kuat + Net R:R 1:${fmt(feeResult.netRr)}")
            ready -> reasons.add(0, "BUY READY: Kondisi scalping valid (Net R:R 1:${fmt(feeResult.netRr)}).")
            early -> reasons.add(0, "EARLY: setup terbentuk, tunggu konfirmasi penuh.")
            isDangerous -> {}
            else -> reasons.add("Menunggu momentum VWAP & Orderbook.")
        }

        val finalAction = when {
            ready && !isDangerous -> SignalAction.BUY
            else -> SignalAction.HOLD
        }
        val stage = when {
            strong -> ScalpingStage.STRONG_ENTRY
            ready -> ScalpingStage.ENTRY
            early -> ScalpingStage.EARLY_ENTRY
            isDangerousNoise || isDangerous -> ScalpingStage.HOLD
            else -> ScalpingStage.WATCH
        }
        
        // --- ORDERBOOK DEPTH FILTER (REPLACED GLOBAL VETO) ---
        if (finalAction == SignalAction.BUY && buyPressure < 0.7) {
            reasons.add("💡 Likuiditas bid/ask order book agak tipis (${fmt(buyPressure)}x), disarankan cicil bertahap.")
        }
        // -----------------------------------------------------

        val biasDetailText = when {
            isDangerousNoise -> "Tertahan: Volatilitas pasar sedang liar (Noise tinggi)."
            isOverbought -> "Tertahan: Harga koin sedang terlalu tinggi (Jenuh Beli/Overbought)."
            !hasRoomToGrow -> "Tertahan: Harga koin terlalu dekat resistance M15 (Ruang naik sempit)."
            step1Ok -> "Target H1/M15 aman (Ruang naik terbuka)."
            else -> "Memantau ruang gerak M15/H1."
        }

        val setupDetailText = when {
            !step1Ok -> "Menunggu Checkpoint 1 lolos."
            step2Ok -> "Orderbook Bid/Ask ratio ${fmt(buyPressure)}x."
            else -> "Menunggu tekanan beli (Bid/Ask > 1.0x)."
        }

        val triggerDetailText = when {
            !step2Ok -> "Menunggu Checkpoint 2 lolos."
            step3Ok -> "Price > VWAP 1M (${fmt(vwap1M)}) & VSA terkonfirmasi."
            else -> "Menunggu harga menembus VWAP 1M."
        }

        val entryDetailText = when {
            !step3Ok -> "Menunggu Checkpoint 3 lolos."
            step4Ok -> "Net RR: 1:${fmt(feeResult.netRr)} (Valid)."
            else -> "Menunggu Net RR optimal (Min 1:1.05)."
        }

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
                isOverbought -> "Menunggu koreksi / reset RSI 1M"
                !hasRoomToGrow -> "Menunggu breakout resistance M15"
                ready -> "Eksekusi"
                step3Ok -> "Konfirmasi Net R:R"
                step2Ok -> "Momentum VWAP & VSA"
                step1Ok -> "Tekanan Beli Orderbook"
                else -> "Menunggu setup lengkap"
            },
            entryCondition = "M1 VSA/VWAP & Orderbook > 1.0",
            extended = rsi1M > 78.0,
            extremeVolatility = isDangerousNoise
        )

        val finalConfidence = when {
            isDangerous -> 0
            strong -> 92
            ready -> 85
            step3Ok -> 65
            early || step2Ok -> 50
            step1Ok -> 35
            else -> 20
        }

        val signal = AISignalState(
            action = finalAction,
            confidence = finalConfidence,
            sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
            entryPrice = price,
            targetPrice1 = tp1,
            targetPrice2 = tp2,
            stopLoss = sl,
            riskRewardRatio = "1:${fmt(feeResult.netRr)}",
            reasoning = reasons.take(6),
            timestamp = System.currentTimeMillis(),
            scalpingStage = stage,
            mtf = mtf,
            isOfflineMode = false,
            backtestWinRatePct = 0.0,
            backtestScore = 0,
            walkForwardEfficiencyPct = 0.0,
            regimeDetected = if (isExtremeVol) "Volatile" else "Normal"
        )

        return Result(
            signal,
            TechnicalIndicators(
                rsi14 = rsi1M,
                macd = macd.first,
                macdHist = macd.first - macd.second,
                ema20 = ema20,
                ema50 = ema50,
                atr = atr1M,
                momentum = buyPressure
            )
        )
    }

    private fun fmt(v: Double) = agu.analys.util.PriceFormatter.fmt(v)
}
