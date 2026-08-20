package agu.analys.engine.swing

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.indicators.CandlePatternDetector
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SwingEvalResult(val signal: AISignalState, val indicators: TechnicalIndicators)

object SwingEvaluator {
    fun evaluate(price: Double, history: List<CandleBar>, fees: TradingFeeConfig = TradingFeeConfig()): SwingEvalResult {
        if (price <= 0.0) {
            return SwingEvalResult(AISignalState(), TechnicalIndicators())
        }

        val minCandles = 15
        if (history.size < minCandles) {
            val defaultSl = price * 0.95
            val defaultTp1 = price * 1.08
            val defaultTp2 = price * 1.18
            val feeResult = FeeCalculator.roundTrip(price, defaultSl, defaultTp2, fees)
            val netRr = feeResult.netRr.coerceAtLeast(1.5)

            val mtfSnapshot = ScalpingMtfSnapshot(
                biasOk = false,
                biasDirection = "neutral",
                biasStatus = MtfLegStatus.WAITING,
                biasDetail = "Mengumpulkan data candle untuk analisis tren makro (tersedia ${history.size}/$minCandles)...",
                setupOk = false,
                setupStatus = MtfLegStatus.WAITING,
                setupDetail = "Menunggu riwayat candle untuk memetakan level support/resistance.",
                triggerOk = false,
                triggerStatus = MtfLegStatus.WAITING,
                triggerDetail = "Menunggu data volume dan momentum RSI/MACD.",
                entryPriceOk = false,
                entryPriceStatus = MtfLegStatus.WAITING,
                entryPriceDetail = "Area entry swing: Rp ${fmtPrice(price)} (Estimasi Net R:R 1:${fmt(netRr)}).",
                path = ScalpingPath.PULLBACK,
                statusTitle = "MENGUMPULKAN DATA",
                waitingFor = "Menunggu sinkronisasi data candle",
                entryCondition = "Memuat riwayat candle pasar untuk kalkulasi Swing."
            )

            return SwingEvalResult(
                AISignalState(
                    action = SignalAction.HOLD,
                    confidence = 35,
                    sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
                    entryPrice = price,
                    targetPrice1 = defaultTp1,
                    targetPrice2 = defaultTp2,
                    stopLoss = defaultSl,
                    riskRewardRatio = "1:${fmt(netRr)}",
                    reasoning = listOf(
                        "Data candle sedang disinkronkan (${history.size}/$minCandles candle).",
                        "Estimasi entry dan target swing awal telah disiapkan."
                    ),
                    timestamp = System.currentTimeMillis(),
                    scalpingStage = ScalpingStage.HOLD,
                    mtf = mtfSnapshot
                ),
                TechnicalIndicators()
            )
        }

        val closes = history.map { it.close }
        val rsi = IndicatorMath.rsi(history, min(14, history.size - 1))
        val ema20 = IndicatorMath.ema(closes, min(20, closes.size))
        val ema50 = IndicatorMath.ema(closes, min(50, closes.size))
        val macdSeries = IndicatorMath.macdSeries(closes, 12, 26, 9)
        val macd = macdSeries.lastOrNull()?.first ?: 0.0
        val macdSignal = macdSeries.lastOrNull()?.second ?: 0.0
        val macdHist = macd - macdSignal
        val bb = IndicatorMath.bollinger(closes, min(20, closes.size))
        val atr = IndicatorMath.atr(history, min(14, history.size - 1))
        val pattern = CandlePatternDetector.detect(history)
        val regime = MarketRegimeDetector.detect(price, ema20, ema50, macdHist, rsi, atr, bb.first, bb.second)
        val structure = MarketStructureAnalyzer.analyze(history)
        val momentumBase = closes[closes.lastIndex - min(10, closes.size - 1)]
        val momentum = if (momentumBase > 0.0) (price - momentumBase) / momentumBase else 0.0
        val ema200 = if (closes.size >= 200) IndicatorMath.ema(closes, 200) else Double.NaN
        val indicators = TechnicalIndicators(rsi, macd, macdSignal, macdHist, ema20, ema50, ema200, bb.second, bb.first, atr, momentum)

        // 1. Scoring & Analysis
        var buy = 0.0
        var sell = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Kondisi Pasar: $regime."

        // RSI evaluation
        when {
            rsi < 30 -> { buy += 20; reasons += "RSI ${fmt(rsi)} jenuh jual (peluang rebound swing)." }
            rsi in 30.0..50.0 -> { buy += 15; reasons += "RSI ${fmt(rsi)} di zona akumulasi sehat." }
            rsi in 50.0..68.0 -> { buy += 12; reasons += "RSI ${fmt(rsi)} momentum bullish terjaga." }
            rsi > 70 -> { sell += 20; reasons += "RSI ${fmt(rsi)} jenuh beli (waspada koreksi)." }
            else -> reasons += "RSI ${fmt(rsi)} netral."
        }

        // EMA Trend Alignment
        val emaBullish = price > ema20 && ema20 > ema50
        val emaModerateBullish = price > ema50 || price > ema20
        when {
            emaBullish -> { buy += 25; reasons += "EMA20 > EMA50 dan harga di atas EMA20 (Uptrend kuat)." }
            emaModerateBullish -> { buy += 15; reasons += "Harga bertahan di atas support rata-rata EMA." }
            price < ema20 && ema20 < ema50 -> { sell += 25; reasons += "EMA20 < EMA50 dan harga di bawah EMA20 (Downtrend)." }
            else -> reasons += "EMA berkonsolidasi, menunggu arah tren tegas."
        }

        // MACD Momentum
        when {
            macdHist > 0 -> { buy += 15; reasons += "MACD histogram positif (Momentum beli)." }
            macdHist < 0 -> { sell += 15; reasons += "MACD histogram negatif (Tekanan jual)." }
        }

        // Bollinger Bands & Candle Patterns
        if (price <= bb.first * 1.01) { buy += 10; reasons += "Harga menyentuh pita bawah Bollinger (Support demand)." }
        if (price >= bb.second * 0.99) { sell += 10; reasons += "Harga dekat pita atas Bollinger (Resistance supply)." }
        pattern?.let {
            if (it.contains("Bullish", true) || it.contains("Hammer", true) || it.contains("Morning", true)) {
                buy += 10; reasons += "Pola Candlestick: $it."
            } else if (it.contains("Bearish", true) || it.contains("Shooting", true) || it.contains("Evening", true)) {
                sell += 10; reasons += "Pola Candlestick: $it."
            }
        }

        // Volume analysis
        val avgVolume = history.takeLast(6).dropLast(1).map { it.volume }.average()
        val lastVolume = history.lastOrNull()?.volume ?: 0.0
        if (avgVolume > 0 && lastVolume >= avgVolume * 1.2) {
            val ratio = lastVolume / avgVolume
            if (history.last().close >= history.last().open) {
                buy += 12; reasons += "Volume beli ${fmt(ratio)}× di atas rata-rata."
            } else {
                sell += 12; reasons += "Volume jual ${fmt(ratio)}× di atas rata-rata."
            }
        }

        // Market structure
        val isBullishStructure = structure.trend.contains("Bull", true)
        if (structure.dataEnough) {
            when {
                isBullishStructure -> { buy += 15; reasons += "Struktur market bullish (Higher-High & Higher-Low)." }
                structure.trend.contains("Bear", true) -> { sell += 15; reasons += "Struktur market bearish (Lower-Low)." }
                else -> reasons += "Struktur market berkonsolidasi di rentang harga."
            }
        }

        // 2. Targets & Level Planning (Always calculated with precision)
        val effectiveAtr = if (atr.isFinite() && atr > 0.0) atr else (price * 0.035)
        val supportLevel = structure.support?.takeIf { it > 0.0 && it < price }
            ?: structure.lastSwingLow?.takeIf { it > 0.0 && it < price }
            ?: (price - effectiveAtr * 1.5)

        val resistanceLevel = structure.resistance?.takeIf { it > price }
            ?: structure.lastSwingHigh?.takeIf { it > price }
            ?: (price + effectiveAtr * 2.2)

        val calculatedSl = maxOf(
            supportLevel - (effectiveAtr * 0.25),
            price - (effectiveAtr * 1.5),
            price * 0.93
        ).coerceAtMost(price * 0.985)

        val calculatedTp1 = maxOf(
            resistanceLevel,
            price + (effectiveAtr * 2.0),
            price * 1.08
        )

        val calculatedTp2 = maxOf(
            calculatedTp1 * 1.09,
            price + (effectiveAtr * 3.5),
            price * 1.18
        )

        val feeResult = FeeCalculator.roundTrip(price, calculatedSl, calculatedTp2, fees)
        val netRr = feeResult.netRr.coerceAtLeast(1.5)
        val rrString = "1:${fmt(netRr)}"

        // 3. 4-Step Checkpoint Validation for SWING
        // Checkpoint 1: Tren Makro & Alignment EMA (1D/4H/1H)
        val step1Ok = (price >= ema50 * 0.98) || (ema20 > ema50) || isBullishStructure || (regime.contains("BULL", true))
        val step1Detail = if (step1Ok) {
            "Tren makro selaras positif di atas support EMA (Harga Rp ${fmtPrice(price)})."
        } else {
            "Memantau keselarasan tren rata-rata EMA (Harga menguji area Rp ${fmtPrice(ema20)})."
        }

        // Checkpoint 2: Struktur Market & Support Lantai (Higher Low)
        val step2Ok = (price >= supportLevel * 0.99) && (isBullishStructure || price > bb.first || structure.lastSwingLow != null)
        val step2Detail = if (step2Ok) {
            "Lantai support swing kokoh di Rp ${fmtPrice(supportLevel)} (Higher-Low terbentuk)."
        } else {
            "Memantau pertahanan area support swing Rp ${fmtPrice(supportLevel)}."
        }

        // Checkpoint 3: Momentum & Volume Inflow (RSI/MACD)
        val step3Ok = (rsi in 32.0..72.0) && (macdHist >= -0.0005 || lastVolume >= avgVolume * 0.95 || buy >= 30)
        val step3Detail = if (step3Ok) {
            "Momentum RSI (${fmt(rsi)}) & MACD ${if (macdHist >= 0) "Inflow Bullish" else "Konsolidasi Terjaga"}."
        } else {
            "Menunggu dorongan volume beli dan penguatan RSI (${fmt(rsi)})."
        }

        // Checkpoint 4: Risk/Reward Optimal & Toleransi Entry
        val step4Ok = (step1Ok && step2Ok && (step3Ok || netRr >= 1.5)) || (buy >= 50.0)
        val step4Detail = if (step4Ok) {
            "Zona entry ideal dengan Net R:R $rrString (TP1: +${fmt(((calculatedTp1 - price) / price) * 100)}%)."
        } else {
            "Menunggu konfirmasi harga masuk ke zona beli ideal dengan toleransi risiko optimal."
        }

        val completedSteps = listOf(step1Ok, step2Ok, step3Ok, step4Ok).count { it }

        // Action Decision
        val isQualifiedBuy = completedSteps >= 3 && buy >= 45.0 && buy > sell * 1.15
        val finalAction = if (isQualifiedBuy) SignalAction.BUY else SignalAction.HOLD
        val finalScore = when {
            completedSteps == 4 && isQualifiedBuy -> (78 + min(17, (buy * 0.2).toInt())).coerceIn(80, 95)
            completedSteps >= 3 -> (65 + min(12, (buy * 0.15).toInt())).coerceIn(65, 76)
            completedSteps == 2 -> 55
            else -> 42
        }

        val mtfSnapshot = ScalpingMtfSnapshot(
            biasOk = step1Ok,
            biasDirection = if (step1Ok) "bullish" else "neutral",
            biasStatus = if (step1Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            biasDetail = step1Detail,

            setupOk = step2Ok,
            setupStatus = if (step2Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            setupDetail = step2Detail,

            triggerOk = step3Ok,
            triggerStatus = if (step3Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            triggerDetail = step3Detail,

            entryPriceOk = step4Ok,
            entryPriceStatus = if (step4Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            entryPriceDetail = step4Detail,

            path = if (isBullishStructure || price >= resistanceLevel * 0.98) ScalpingPath.MOMENTUM_CONTINUATION else ScalpingPath.PULLBACK,
            statusTitle = if (completedSteps == 4) "SWING ENTRY READY" else "SWING ANALYZING ($completedSteps/4)",
            waitingFor = if (completedSteps == 4) "Siap eksekusi Swing Buy" else "Menunggu konfirmasi setup lengkap",
            entryCondition = if (isBullishStructure) "Struktur Swing Bullish Reclaim" else "Swing Support Accumulation"
        )

        return SwingEvalResult(
            AISignalState(
                action = finalAction,
                confidence = finalScore,
                sentiment = when (finalAction) {
                    SignalAction.BUY -> TrendSentiment.STRONG_BULLISH_CONTINUATION
                    SignalAction.SELL -> TrendSentiment.BEARISH_DISTRIBUTION
                    SignalAction.HOLD -> if (completedSteps >= 2) TrendSentiment.ACCUMULATION_SQUEEZE else TrendSentiment.NEUTRAL_CONSOLIDATION
                },
                entryPrice = price,
                targetPrice1 = calculatedTp1,
                targetPrice2 = calculatedTp2,
                stopLoss = calculatedSl,
                riskRewardRatio = rrString,
                reasoning = reasons.take(7),
                timestamp = System.currentTimeMillis(),
                patternDetected = pattern,
                scalpingStage = if (completedSteps == 4) ScalpingStage.ENTRY else if (completedSteps >= 2) ScalpingStage.WAIT_PULLBACK else ScalpingStage.HOLD,
                mtf = mtfSnapshot
            ),
            indicators
        )
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    private fun fmtPrice(v: Double) = String.format(java.util.Locale.US, "%,.0f", v)
}

