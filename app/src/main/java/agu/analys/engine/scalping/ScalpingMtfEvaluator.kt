package agu.analys.engine.scalping

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.indicators.IndicatorMath
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
 * Scalping BUY-only. 1H=bias, 15M=setup, 1M=trigger.
 * Threshold longgar: lebih sering peluang, tetap searah + anti-extended + fee-aware.
 */
object ScalpingMtfEvaluator {
    data class Result(val signal: AISignalState, val indicators: TechnicalIndicators)

    fun evaluate(
        price: Double,
        h1Candles: List<CandleBar>,
        m15Candles: List<CandleBar>,
        m1Candles: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig()
    ): Result? {
        if (price <= 0.0 || h1Candles.size < 55 || m15Candles.size < 55 || m1Candles.size < 55) return null
        val h1 = analyze(h1Candles)
        val m15 = analyze(m15Candles)
        val m1 = analyze(m1Candles)
        val structureH1 = MarketStructureAnalyzer.analyze(h1Candles.takeLast(60))
        val structure15 = MarketStructureAnalyzer.analyze(m15Candles.takeLast(60))

        val h1GoldenCross = goldenCross(h1Candles, 20, 50)
        val m15GoldenCross = goldenCross(m15Candles, 20, 50)

        // Bias: EMA searah + harga di atas EMA20 (struktur/GC = bonus skor, bukan wajib)
        val biasLong = h1.ema20 > h1.ema50 && h1.price > h1.ema20
        val biasStrong = biasLong && (structureH1.trend == "Bullish structure" || h1GoldenCross)

        // Setup: EMA 15M tidak bearish (EMA20 ≥ EMA50)
        val setupLong = m15.ema20 >= m15.ema50
        val setupStrong = setupLong && (structure15.trend == "Bullish structure" || m15GoldenCross)

        val priceAboveEma20 = m1.price > m1.ema20
        // RSI entry lebih lebar: 38–62 (sebelumnya 40–55)
        val rsiEntryZone = m1.rsi in 38.0..62.0
        val momentumLong = m1.macdHist > 0.0 || m1.price > m1.ema20
        val volumeOk = m1.volumeRatio >= 1.0
        val triggerLong = priceAboveEma20 && rsiEntryZone && (momentumLong || volumeOk || m1.retestUp || m1.breakoutUp)

        // Extended lebih longgar: izinkan momentum sampai RSI 72 di 1M
        val extended = h1.rsi > 75.0 || m15.rsi > 75.0 || m1.rsi > 72.0
        val volatilityPct = if (price > 0) m1.atr / price * 100.0 else 100.0
        val extremeVolatility = volatilityPct >= 4.0

        var score = 0
        if (biasLong) score += 25
        if (biasStrong) score += 10
        if (setupLong) score += 20
        if (setupStrong) score += 8
        if (priceAboveEma20) score += 10
        if (rsiEntryZone) score += 10
        if (momentumLong) score += 8
        if (volumeOk) score += 7
        if (m1.breakoutUp || m1.retestUp) score += 5
        if (h1GoldenCross) score += 5
        score = score.coerceIn(0, 100)

        val rawSlPct = ((m1.atr / price) * 100.0 * 0.85).coerceIn(0.30, 0.55)
        val stop = price * rawSlPct / 100.0
        val feePct = fees.buyTakerPct + fees.sellTakerPct
        val riskPct = rawSlPct + feePct
        // Minimum net R:R 1.2 (sebelumnya 1.5) — tetap fee-aware
        val minNetRr = 1.2
        val requiredNetRewardPct = riskPct * minNetRr
        val tp1Pct = max(0.9, feePct + rawSlPct * 0.9)
        val tp2Pct = max(1.6, feePct + requiredNetRewardPct)
        val entry = price
        val sl = price - stop
        val tp1 = price * (1.0 + tp1Pct / 100.0)
        val tp2 = price * (1.0 + tp2Pct / 100.0)
        val feeResult = FeeCalculator.roundTrip(entry, sl, tp2, fees)

        val ready = biasLong && setupLong && triggerLong && !extended && !extremeVolatility && feeResult.netRr >= minNetRr
        val stage = when {
            ready && score >= 72 -> ScalpingStage.STRONG_ENTRY
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
        reasons += "1H bias: ${if (biasLong) "bullish" else "belum bullish"} · EMA20 ${fmt(h1.ema20)} / EMA50 ${fmt(h1.ema50)}."
        reasons += "15M setup: ${if (setupLong) "searah" else "belum searah"} · EMA20 ${fmt(m15.ema20)} / EMA50 ${fmt(m15.ema50)}."
        reasons += "1M trigger: harga ${if (priceAboveEma20) "> EMA20" else "≤ EMA20"}, RSI ${fmt(m1.rsi)}, vol ${fmt(m1.volumeRatio)}×."
        if (h1GoldenCross || m15GoldenCross) reasons += "Golden cross EMA20/50 mendukung."
        if (extended) reasons += "RSI extended — tunggu pullback, jangan kejar."
        if (extremeVolatility) reasons += "ATR 1M ≥ 4%; entry ditahan."
        reasons += "Fee RT ${fmt(feePct)}%; net R:R TP2 1:${fmt(feeResult.netRr)} (min $minNetRr)."
        if (!ready) reasons += "Belum BUY READY — tunggu bias+setup+trigger searah."

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
            triggerDetail = "RSI ${fmt(m1.rsi)} · vs EMA20 · vol ${fmt(m1.volumeRatio)}×",
            path = path,
            statusTitle = when (stage) {
                ScalpingStage.STRONG_ENTRY -> "BUY READY · KUAT"
                ScalpingStage.ENTRY -> "BUY READY"
                ScalpingStage.WAIT_PULLBACK -> "MENUNGGU PULLBACK"
                ScalpingStage.WATCH -> "MENUNGGU KONFIRMASI"
                ScalpingStage.HOLD -> "BELUM TERSEDIA"
            },
            waitingFor = if (ready) "Tidak ada yang ditunggu."
            else "Tunggu bias 1H, setup 15M, trigger 1M (RSI 38–62, harga > EMA20).",
            entryCondition = "1H bias + 15M setup + 1M trigger + net R:R ≥ $minNetRr (fee-aware).",
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
            reasoning = reasons.take(9),
            timestamp = System.currentTimeMillis(),
            scalpingStage = stage,
            mtf = mtf
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

    private fun analyze(history: List<CandleBar>): Frame {
        val closes = history.map { it.close }
        val price = closes.last()
        val ema20 = IndicatorMath.ema(closes, 20)
        val ema50 = IndicatorMath.ema(closes, 50)
        val macd = IndicatorMath.macdSeries(closes, 12, 26, 9).last()
        val atr = IndicatorMath.atr(history, 14)
        val rsi = IndicatorMath.rsi(history, 14)
        val avgVolume = history.takeLast(6).dropLast(1).map { it.volume }.average()
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
