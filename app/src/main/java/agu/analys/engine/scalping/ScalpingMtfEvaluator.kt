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
        val triggerLong = (price > vwap1M || isVSABreakout) && (buyPressure > 1.05 || isOrderBookEmpty) && rsi1M < 80.0
        
        // 4. Macro Room to Grow (M15 / H1)
        val struct15M = if (m15Ready) MarketStructureAnalyzer.analyze(m15Candles.takeLast(40)) else null
        val resistance = struct15M?.resistance ?: (price * 1.05)
        val hasRoomToGrow = price < resistance * 0.995

        // Indicators for state
        val m1Closes = m1Candles.map { it.close }
        val ema20 = IndicatorMath.ema(m1Closes, 20)
        val ema50 = IndicatorMath.ema(m1Closes, 50)
        val macd = IndicatorMath.macdSeries(m1Closes, 12, 26, 9).last()

        val reasons = mutableListOf<String>()
        reasons.add("VWAP 1M: ${fmt(vwap1M)}")
        if (isOrderBookEmpty) {
            reasons.add("Tekanan Beli: Diabaikan (Orderbook Kosong)")
        } else {
            reasons.add("Tekanan Beli (Orderbook): ${fmt(buyPressure)}x")
        }
        if (isVSABreakout) reasons.add("VSA Breakout Terdeteksi! (Vol: ${fmt(formingVolValid / avgVol1M)}x)")
        if (isDangerousNoise) reasons.add("Noise liar/Choppy! Entry ditahan.")
        if (!hasRoomToGrow) reasons.add("Harga terlalu dekat resistance M15.")

        // 5. Risk / Reward & Action
        val stopPct = if (isAggressive) volPct * 1.2 else volPct * 0.8
        val sl = price * (1.0 - (stopPct.coerceIn(0.5, 3.0) / 100.0))
        
        val requiredNetRewardPct = ((price - sl)/price * 100.0 + fees.buyTakerPct + fees.sellTakerPct) * 1.2
        val tp1 = price * (1.0 + max(0.9, requiredNetRewardPct * 0.6) / 100.0)
        val tp2 = price * (1.0 + max(1.5, requiredNetRewardPct) / 100.0)
        
        val feeResult = FeeCalculator.roundTrip(price, sl, tp2, fees, false, 0.08)
        
        val rrOk = feeResult.netRr >= 1.05
        val ready = triggerLong && hasRoomToGrow && !isDangerousNoise && rrOk
        // Partial setup: ada trigger / room, belum full ready
        val early = !ready && !isDangerousNoise && (
            (triggerLong && hasRoomToGrow) ||
            (triggerLong && rrOk) ||
            (hasRoomToGrow && buyPressure > 1.1 && price > vwap1M && rsi1M < 75.0)
        )
        val strong = ready && (isVSABreakout || buyPressure >= 1.25 || (rsi1M in 45.0..68.0 && price > vwap1M))

        when {
            strong -> reasons.add("STRONG ENTRY: VSA/OB kuat + Net R:R 1:${fmt(feeResult.netRr)}")
            ready -> reasons.add("BUY READY: Kondisi scalping valid (Net R:R 1:${fmt(feeResult.netRr)}).")
            early -> reasons.add("EARLY: setup terbentuk, tunggu konfirmasi penuh.")
            triggerLong -> reasons.add("Trigger ON, belum qualify (RR/room/noise).")
            else -> reasons.add("Menunggu momentum VWAP & Orderbook.")
        }

        val action = if (ready) SignalAction.BUY else SignalAction.HOLD
        val stage = when {
            strong -> ScalpingStage.STRONG_ENTRY
            ready -> ScalpingStage.ENTRY
            early -> ScalpingStage.EARLY_ENTRY
            isDangerousNoise -> ScalpingStage.HOLD
            else -> ScalpingStage.WATCH
        }

        val mtf = ScalpingMtfSnapshot(
            biasOk = hasRoomToGrow,
            biasDirection = if (hasRoomToGrow) "ruang_naik" else "terhalang",
            biasStatus = if (hasRoomToGrow) MtfLegStatus.OK else MtfLegStatus.WAITING,
            biasDetail = "Target H1/M15 aman.",
            setupOk = buyPressure > 1.0,
            setupStatus = if (buyPressure > 1.0) MtfLegStatus.OK else MtfLegStatus.WAITING,
            setupDetail = "Orderbook Bid/Ask ratio ${fmt(buyPressure)}x",
            triggerOk = triggerLong,
            triggerStatus = if (triggerLong) MtfLegStatus.OK else MtfLegStatus.WAITING,
            triggerDetail = "Price > VWAP & RSI M1 ${fmt(rsi1M)}",
            entryPriceOk = ready,
            entryPriceStatus = if (ready) MtfLegStatus.OK else MtfLegStatus.WAITING,
            entryPriceDetail = "Net RR: 1:${fmt(feeResult.netRr)}",
            path = if (ready) ScalpingPath.ENTRY_READY else ScalpingPath.NONE,
            statusTitle = when {
                strong -> "STRONG ENTRY"
                ready -> "BUY READY"
                early -> "EARLY SETUP"
                else -> "WATCH"
            },
            waitingFor = when {
                ready -> "Eksekusi"
                early -> "Konfirmasi"
                else -> "Momentum"
            },
            entryCondition = "M1 VSA/VWAP & Orderbook > 1.0",
            extended = rsi1M > 78.0,
            extremeVolatility = isDangerousNoise
        )

        val signal = AISignalState(
            action = action,
            confidence = when { strong -> 92; ready -> 85; early -> 62; else -> 40 },
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

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
