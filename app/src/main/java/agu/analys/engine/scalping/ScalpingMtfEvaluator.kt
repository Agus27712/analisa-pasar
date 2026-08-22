package agu.analys.engine.scalping

import agu.analys.config.FeeCalculator
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.backtest.WalkForwardEvaluator
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.engine.regime.MarketRegimeDetector
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.MtfLegStatus
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.model.ScalpingPath
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.TrendSentiment
import kotlin.math.max

/**
 * Scalping BUY-only dengan Evaluasi Adaptif Multi-Timeframe (1H, 15M, 1M).
 * Mencegah Overfit & False Signal di Market Sideways melalui Walk-Forward Validation & Market Regime Detection.
 */
object ScalpingMtfEvaluator {
    data class Result(val signal: AISignalState, val indicators: TechnicalIndicators)

    fun evaluate(
        price: Double,
        h1Candles: List<CandleBar>,
        m15Candles: List<CandleBar>,
        m1Candles: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig(),
        sensitivity: ScalpingSensitivity = ScalpingSensitivity.BALANCED
    ): Result? {
        if (price <= 0.0 || h1Candles.size < 55 || m15Candles.size < 55 || m1Candles.size < 55) return null

        val isAggressive = sensitivity == ScalpingSensitivity.AGGRESSIVE
        val h1 = analyze(h1Candles, isAggressive = isAggressive)
        val m15 = analyze(m15Candles, isAggressive = isAggressive)
        val m1 = analyze(m1Candles, isAggressive = isAggressive)

        // 1. Detect Market Regime
        val bbUpper1M = m1.ema20 + (2.0 * m1.atr)
        val bbLower1M = m1.ema20 - (2.0 * m1.atr)
        val regime = MarketRegimeDetector.detect(
            price = price,
            emaFast = m1.ema20,
            emaSlow = m1.ema50,
            macdHist = m1.macdHist,
            rsi = m1.rsi,
            atr = m1.atr,
            bbLower = bbLower1M,
            bbUpper = bbUpper1M
        )
        val isSidewaysRegime = regime.contains("SIDEWAYS")

        // 2. Walk-Forward Validation
        val wfReport = WalkForwardEvaluator.validate(m1Candles, fees)

        val structureH1 = MarketStructureAnalyzer.analyze(h1Candles.takeLast(60))
        val structure15 = MarketStructureAnalyzer.analyze(m15Candles.takeLast(60))

        val h1GoldenCross = goldenCross(h1Candles, 20, 50)
        val m15GoldenCross = goldenCross(m15Candles, 20, 50)

        // Dynamic thresholds based on sensitivity + market regime
        val minVolRatio = when {
            isSidewaysRegime -> 1.35
            isAggressive -> 0.85
            sensitivity == ScalpingSensitivity.CONSERVATIVE -> 1.10
            else -> 1.00
        }

        val minNetRr = when {
            isSidewaysRegime -> 1.35
            isAggressive -> 1.15
            sensitivity == ScalpingSensitivity.CONSERVATIVE -> 1.25
            else -> 1.20
        }

        val biasLong = if (isAggressive) {
            ((h1.ema20 > h1.ema50) || (h1.price > h1.ema20) || (h1.price > h1.ema50)) && h1.rsi > 42.0
        } else {
            (h1.ema20 >= h1.ema50 && h1.price >= h1.ema50 * 0.99) || (h1.price > h1.ema20 && h1.price > h1.ema50)
        }
        val biasStrong = biasLong && (structureH1.trend == "Bullish structure" || h1GoldenCross)

        val setupLong = if (isAggressive) {
            (m15.ema20 >= m15.ema50) || (m15.price >= m15.ema20 * 0.99) || (m15.price > m15.ema50)
        } else {
            (m15.ema20 >= m15.ema50 && m15.price >= m15.ema50 * 0.99) || (m15.price > m15.ema20)
        }
        val setupStrong = setupLong && (structure15.trend == "Bullish structure" || m15GoldenCross)

        val priceAboveEma20 = m1.price >= m1.ema20 * 0.998 || m1.price > m1.ema50
        val rsiEntryZone = if (isAggressive) m1.rsi in 32.0..72.0 else m1.rsi in 35.0..68.0
        val momentumLong = m1.macdHist >= -0.0001 || m1.price > m1.ema20 || m1.retestUp || m1.breakoutUp
        val volumeOk = m1.volumeRatio >= minVolRatio

        val triggerScore = when {
            isAggressive && m1.price > m1.ema20 && (volumeOk || m1.retestUp || m1.breakoutUp) -> 20
            isAggressive && m1.price > m1.ema20 -> 12
            isAggressive && m1.price > m1.ema50 -> 5
            else -> 0
        }
        val triggerLong = if (isAggressive) {
            (m1.price > m1.ema50 || m1.price > m1.ema20) && rsiEntryZone && (momentumLong || triggerScore >= 5)
        } else {
            priceAboveEma20 && rsiEntryZone && (momentumLong || volumeOk || m1.retestUp || m1.breakoutUp)
        }

        val extended = if (isAggressive) {
            h1.rsi > 78.0 || m15.rsi > 78.0 || m1.rsi > 75.0
        } else {
            h1.rsi > 75.0 || m15.rsi > 75.0 || m1.rsi > 72.0
        }
        val volatilityPct = if (price > 0) m1.atr / price * 100.0 else 100.0
        val extremeVolatility = volatilityPct >= 4.0

        var score = 0
        if (biasLong) score += 25
        if (biasStrong) score += 10
        if (setupLong) score += 20
        if (setupStrong) score += 8
        if (isAggressive) {
            score += triggerScore
            if (rsiEntryZone) score += 10
            if (momentumLong) score += 8
            if (m1.breakoutUp || m1.retestUp) score += 5
        } else {
            if (priceAboveEma20) score += 10
            if (rsiEntryZone) score += 10
            if (momentumLong) score += 8
            if (volumeOk) score += 7
            if (m1.breakoutUp || m1.retestUp) score += 5
        }
        if (h1GoldenCross) score += 5

        // Penalize score if in sideways regime to avoid overfit
        if (isSidewaysRegime) {
            score -= 15
        }

        // Penalize score if Walk-Forward validation shows overfitting
        if (wfReport.isOverfitted) {
            score -= 15
        }

        // --- Historical / data-quality gate (proxy validasi) ---
        val qualityPenalty = historicalQualityPenalty(
            h1Size = h1Candles.size,
            m15Size = m15Candles.size,
            m1Size = m1Candles.size,
            biasLong = biasLong,
            setupLong = setupLong,
            triggerLong = triggerLong,
            structureAligned = structureH1.trend == "Bullish structure" && structure15.trend == "Bullish structure",
            volumeOk = volumeOk
        )
        score = (score - qualityPenalty).coerceIn(0, 100)

        val rawSlPct = if (isAggressive) {
            ((m1.atr / price) * 100.0 * 1.5).coerceIn(0.60, 1.20)
        } else {
            ((m1.atr / price) * 100.0 * 0.85).coerceIn(0.35, 0.65)
        }
        val stop = price * rawSlPct / 100.0
        val entry = price
        val sl = price - stop
        val requiredNetRewardPct = (rawSlPct + fees.buyTakerPct + fees.sellTakerPct) * minNetRr
        val tp1Pct = max(0.9, rawSlPct * 0.95)
        val tp2Pct = max(1.6, requiredNetRewardPct)
        val tp1 = price * (1.0 + tp1Pct / 100.0)
        val tp2 = price * (1.0 + tp2Pct / 100.0)

        // Strict fee & slippage calculation
        val feeResult = FeeCalculator.roundTrip(entry, sl, tp2, fees, useMaker = false, slippagePct = 0.08)

        // Siap entry hanya jika quality gate lolos & net RR mencukupi setelah fee + slippage
        val qualityOk = qualityPenalty <= 18
        val feeOk = feeResult.netRr >= minNetRr
        val ready = biasLong && setupLong && triggerLong && !extended && !extremeVolatility &&
            feeOk && qualityOk && !isSidewaysRegime && !wfReport.isOverfitted

        val stage = when {
            ready && score >= (if (isAggressive) 68 else 72) -> ScalpingStage.STRONG_ENTRY
            ready -> ScalpingStage.ENTRY
            biasLong && extended -> ScalpingStage.WAIT_PULLBACK
            biasLong && !triggerLong -> ScalpingStage.WAIT_PULLBACK
            biasLong || setupLong -> ScalpingStage.WATCH
            else -> ScalpingStage.HOLD
        }
        val path = when (stage) {
            ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY -> ScalpingPath.ENTRY_READY
            ScalpingStage.WAIT_PULLBACK -> ScalpingPath.PULLBACK
            ScalpingStage.WATCH -> ScalpingPath.MOMENTUM_CONTINUATION
            ScalpingStage.HOLD -> ScalpingPath.NONE
        }

        val reasons = mutableListOf<String>()
        if (isAggressive) reasons += "[Mode Agresif]"
        if (isSidewaysRegime) reasons += "[Rejim Sideways — Sinyal Beli Ditahan]"
        if (wfReport.isOverfitted) reasons += "[Peringatan Walk-Forward Overfit]"
        reasons += "Rejim: $regime"
        reasons += "1H bias: ${if (biasLong) "bullish" else "belum bullish"}."
        reasons += "15M setup: ${if (setupLong) "searah" else "belum searah"}."
        reasons += "1M trigger: RSI ${fmt(m1.rsi)}, vol ${fmt(m1.volumeRatio)}× (min ${fmt(minVolRatio)}x)."
        if (!feeOk) reasons += "Net R:R (1:${fmt(feeResult.netRr)}) tergerus fee & slippage (min 1:${minNetRr})."
        else reasons += "Net R:R TP2 1:${fmt(feeResult.netRr)} (min $minNetRr)."
        if (qualityPenalty > 0) reasons += "Quality gate −$qualityPenalty."
        if (extended) reasons += "RSI extended — tunggu pullback."
        if (extremeVolatility) reasons += "ATR 1M ≥ 4%; entry ditahan."
        if (!ready && reasons.size < 6) reasons += "Belum BUY READY."

        val entryPriceOk = ready && price > 0.0
        val entryPriceStatus = when {
            entryPriceOk -> MtfLegStatus.OK
            biasLong && setupLong -> MtfLegStatus.WAITING
            biasLong -> MtfLegStatus.WAITING
            else -> MtfLegStatus.UNKNOWN
        }
        val entryPriceDetail = when {
            entryPriceOk -> "Harga ${fmt(entry)} · Siap BUY di Indodax"
            biasLong && setupLong -> "Siapkan ${fmt(entry)} · Menunggu trigger"
            biasLong -> "Pantau harga di Indodax"
            else -> "Menunggu konfirmasi TF"
        }

        val mtf = ScalpingMtfSnapshot(
            biasOk = biasLong,
            biasDirection = if (biasLong) "bullish" else "mixed",
            biasStatus = if (biasLong) MtfLegStatus.OK else MtfLegStatus.WAITING,
            biasDetail = "EMA20/50 · RSI ${fmt(h1.rsi)}" + if (biasStrong) " · kuat" else "",
            setupOk = setupLong,
            setupStatus = if (setupLong) MtfLegStatus.OK else if (biasLong) MtfLegStatus.WAITING else MtfLegStatus.FAIL,
            setupDetail = "EMA20/50 · RSI ${fmt(m15.rsi)}" + if (setupStrong) " · kuat" else "",
            triggerOk = triggerLong,
            triggerStatus = if (triggerLong) MtfLegStatus.OK else MtfLegStatus.WAITING,
            triggerDetail = "RSI ${fmt(m1.rsi)} · vol ${fmt(m1.volumeRatio)}×",
            entryPriceOk = entryPriceOk,
            entryPriceStatus = entryPriceStatus,
            entryPriceDetail = entryPriceDetail,
            path = path,
            statusTitle = when (stage) {
                ScalpingStage.STRONG_ENTRY -> "BUY READY · KUAT"
                ScalpingStage.ENTRY -> "BUY READY"
                ScalpingStage.WAIT_PULLBACK -> "MENUNGGU PULLBACK"
                ScalpingStage.WATCH -> "MENUNGGU KONFIRMASI"
                ScalpingStage.HOLD -> "BELUM TERSEDIA"
            },
            waitingFor = if (ready) "Harga di area entry — BUY di Indodax."
            else if (isSidewaysRegime) "Pasar Sideways — Tunggu breakout dengan volume tinggi."
            else if (isAggressive) "Tunggu bias+setup+trigger & quality gate."
            else "Tunggu bias 1H, setup 15M, trigger 1M & quality gate.",
            entryCondition = "1H+15M+1M searah + quality OK + net R:R ≥ $minNetRr.",
            extended = extended,
            extremeVolatility = extremeVolatility
        )

        val action = if (ready) SignalAction.BUY else SignalAction.HOLD
        val signal = AISignalState(
            action = action,
            confidence = score,
            sentiment = when {
                action == SignalAction.BUY -> TrendSentiment.STRONG_BULLISH_CONTINUATION
                biasLong -> TrendSentiment.ACCUMULATION_SQUEEZE
                else -> TrendSentiment.NEUTRAL_CONSOLIDATION
            },
            entryPrice = entry,
            targetPrice1 = if (action == SignalAction.BUY) tp1 else 0.0,
            targetPrice2 = if (action == SignalAction.BUY) tp2 else 0.0,
            stopLoss = if (action == SignalAction.BUY) sl else 0.0,
            riskRewardRatio = if (action == SignalAction.BUY) "Net R:R 1:${fmt(feeResult.netRr)}" else "Belum tersedia",
            reasoning = reasons.take(6),
            timestamp = System.currentTimeMillis(),
            scalpingStage = stage,
            mtf = mtf,
            isOfflineMode = false,
            backtestWinRatePct = wfReport.outOfSampleWinRatePct,
            backtestScore = wfReport.overallScore,
            walkForwardEfficiencyPct = wfReport.walkForwardEfficiencyPct,
            regimeDetected = regime
        )
        return Result(
            signal,
            TechnicalIndicators(
                rsi14 = m1.rsi,
                macd = m1.macdHist,
                macdHist = m1.macdHist,
                ema20 = m1.ema20,
                ema50 = m1.ema50,
                atr = m1.atr,
                momentum = m1.momentum
            )
        )
    }

    /**
     * Proxy validasi historis:
     * - history tipis → score turun
     * - MTF tidak sejalan → score turun
     * - volume lemah → score turun
     */
    private fun historicalQualityPenalty(
        h1Size: Int,
        m15Size: Int,
        m1Size: Int,
        biasLong: Boolean,
        setupLong: Boolean,
        triggerLong: Boolean,
        structureAligned: Boolean,
        volumeOk: Boolean
    ): Int {
        var p = 0
        if (h1Size < 80) p += 6
        if (m15Size < 80) p += 5
        if (m1Size < 100) p += 5
        val legsOk = listOf(biasLong, setupLong, triggerLong).count { it }
        if (legsOk == 1) p += 12
        else if (legsOk == 2) p += 6
        if (!structureAligned && biasLong) p += 4
        if (!volumeOk) p += 5
        return p.coerceAtMost(30)
    }

    private data class Frame(
        val price: Double,
        val rsi: Double,
        val ema20: Double,
        val ema50: Double,
        val macdHist: Double,
        val atr: Double,
        val volumeRatio: Double,
        val breakoutUp: Boolean,
        val retestUp: Boolean,
        val momentum: Double
    )

    private fun analyze(history: List<CandleBar>, isAggressive: Boolean = false): Frame {
        val closes = history.map { it.close }
        val price = closes.last()
        val ema20 = IndicatorMath.ema(closes, 20)
        val ema50 = IndicatorMath.ema(closes, 50)
        val macd = IndicatorMath.macdSeries(closes, 12, 26, 9).last()
        val atr = IndicatorMath.atr(history, 14)
        val rsi = IndicatorMath.rsi(history, 14)
        val windowCount = if (isAggressive) 16 else 6
        val actualWindow = minOf(windowCount, history.size)
        val avgVolume = history.takeLast(actualWindow).dropLast(1).map { it.volume }.average()
        val volumeRatio = if (avgVolume > 0) history.last().volume / avgVolume else 0.0
        val prev = history[history.lastIndex - 1]
        val breakoutUp = history.last().close > prev.high
        val retestUp = history.last().low <= ema20 && price > ema20
        val base = closes[closes.lastIndex - minOf(4, closes.lastIndex)]
        val momentum = if (base > 0) (price - base) / base else 0.0
        return Frame(price, rsi, ema20, ema50, macd.first - macd.second, atr, volumeRatio, breakoutUp, retestUp, momentum)
    }

    private fun goldenCross(history: List<CandleBar>, fast: Int, slow: Int): Boolean {
        if (history.size < slow + 2) return false
        val closes = history.map { it.close }
        val prevFast = IndicatorMath.ema(closes.dropLast(1), fast)
        val prevSlow = IndicatorMath.ema(closes.dropLast(1), slow)
        val nowFast = IndicatorMath.ema(closes, fast)
        val nowSlow = IndicatorMath.ema(closes, slow)
        return prevFast <= prevSlow && nowFast > nowSlow
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
