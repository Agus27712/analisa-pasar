package agu.analys.engine.officedaily

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

data class OfficeDailyEvalResult(
    val signal: AISignalState,
    val indicators: TechnicalIndicators,
    val isQualified: Boolean = false,
    val setupScore: Int = 0
)

/**
 * OFFICE DAILY TRADING STRATEGY EVALUATOR
 * Strategi santai & presisi untuk pekerja kantoran:
 * - Timeframe: H4 / 1D Makro + H1 Pullback
 * - Disiplin: Trend Following, Base Accumulation, High R:R (>= 1.8:1), Low Noise.
 * - Tidak memerlukan pantauan chart konstan di jam kerja.
 */
object OfficeDailyEvaluator {

    fun evaluate(
        price: Double,
        history: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig()
    ): OfficeDailyEvalResult {
        if (price <= 0.0) {
            return OfficeDailyEvalResult(AISignalState(), TechnicalIndicators())
        }

        val minCandles = 20
        if (history.size < minCandles) {
            val defaultSl = price * 0.94
            val defaultTp1 = price * 1.10
            val defaultTp2 = price * 1.22
            val feeResult = FeeCalculator.roundTrip(price, defaultSl, defaultTp2, fees)
            val netRr = feeResult.netRr.coerceAtLeast(1.8)

            val mtfSnapshot = ScalpingMtfSnapshot(
                biasOk = false,
                biasDirection = "neutral",
                biasStatus = MtfLegStatus.WAITING,
                biasDetail = "Mengumpulkan candle makro (${history.size}/$minCandles)...",
                setupOk = false,
                setupStatus = MtfLegStatus.WAITING,
                setupDetail = "Menunggu data H4/1D untuk validasi tren yang stabil.",
                triggerOk = false,
                triggerStatus = MtfLegStatus.WAITING,
                triggerDetail = "Menunggu konfirmasi area beli aman.",
                entryPriceOk = false,
                entryPriceStatus = MtfLegStatus.WAITING,
                entryPriceDetail = "Area entry santai: Rp ${fmtPrice(price)} (Estimasi Net R:R 1:${fmt(netRr)}).",
                path = ScalpingPath.PULLBACK,
                statusTitle = "MENGUMPULKAN DATA",
                waitingFor = "Menunggu sinkronisasi candle makro",
                entryCondition = "Memuat riwayat candle untuk setup Office Daily."
            )

            return OfficeDailyEvalResult(
                signal = AISignalState(
                    action = SignalAction.HOLD,
                    confidence = 30,
                    sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
                    entryPrice = price,
                    targetPrice1 = defaultTp1,
                    targetPrice2 = defaultTp2,
                    stopLoss = defaultSl,
                    riskRewardRatio = "1:${fmt(netRr)}",
                    reasoning = listOf(
                        "Data candle sedang disinkronkan (${history.size}/$minCandles candle).",
                        "Estimasi target telah disiapkan."
                    ),
                    timestamp = System.currentTimeMillis(),
                    scalpingStage = ScalpingStage.HOLD,
                    mtf = mtfSnapshot
                ),
                indicators = TechnicalIndicators(),
                isQualified = false,
                setupScore = 0
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
        val ema200 = if (closes.size >= 200) IndicatorMath.ema(closes, 200) else Double.NaN
        val momentumBase = closes[closes.lastIndex - min(10, closes.size - 1)]
        val momentum = if (momentumBase > 0.0) (price - momentumBase) / momentumBase else 0.0
        val indicators = TechnicalIndicators(rsi, macd, macdSignal, macdHist, ema20, ema50, ema200, bb.second, bb.first, atr, momentum)

        var buyScore = 0.0
        var sellScore = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Kondisi Pasar: $regime (Office Daily Mode)."

        // 1. Trend & Moving Average Alignment
        val isUptrend = indicators.ema20.isFinite() && indicators.ema50.isFinite() && (ema20 > ema50) && (price >= ema50 * 0.985)
        val isGoldenCross = ema20 > ema50 && closes.takeLast(5).firstOrNull()?.let { it <= ema50 } ?: false
        val isDowntrend = indicators.ema20.isFinite() && indicators.ema50.isFinite() && (ema20 < ema50 && price < ema20)

        when {
            isUptrend -> { buyScore += 30; reasons += "Tren makro solid (EMA20 > EMA50, harga di atas support dinamis)." }
            isGoldenCross -> { buyScore += 25; reasons += "Baru terjadi Golden Cross EMA harian." }
            isDowntrend -> { sellScore += 30; reasons += "Tren makro bearish (EMA20 < EMA50). Hindari buy santai." }
            else -> reasons += "Tren berkonsolidasi, menunggu arah tren tegas."
        }

        // 2. RSI Sweet Spot (40-62 adalah zona akumulasi & pullback terbaik untuk swing santai)
        when {
            rsi in 42.0..62.0 -> { buyScore += 25; reasons += "RSI ${fmt(rsi)} berada di zona akumulasi ideal." }
            rsi in 30.0..42.0 && macdHist > 0 -> { buyScore += 20; reasons += "RSI oversold rebound dengan momentum positif." }
            rsi > 72.0 -> { sellScore += 25; reasons += "RSI ${fmt(rsi)} overbought (potensi koreksi harian)." }
            rsi < 30.0 -> { buyScore += 15; reasons += "RSI ${fmt(rsi)} jenuh jual (peluang rebound)." }
            else -> reasons += "RSI ${fmt(rsi)} netral."
        }

        // 3. MACD Momentum
        if (macdHist > 0) {
            buyScore += 20
            reasons += "Histogram MACD positif (+${fmt(macdHist)})."
        } else {
            sellScore += 15
            reasons += "Histogram MACD negatif (${fmt(macdHist)})."
        }

        // 4. Struktur Higher High / Higher Low
        val isBullishStructure = structure.trend.contains("Bull", true)
        val isBearishStructure = structure.trend.contains("Bear", true)
        if (structure.dataEnough) {
            when {
                isBullishStructure -> { buyScore += 20; reasons += "Struktur chart: Higher-High & Higher-Low stabil." }
                isBearishStructure -> { sellScore += 20; reasons += "Struktur chart: Lower-Low (Risiko penurunan)." }
            }
        }

        // 5. Pola Candlestick
        pattern?.let {
            if (it.contains("Bullish", true) || it.contains("Hammer", true) || it.contains("Morning", true)) {
                buyScore += 15; reasons += "Pola Reversal: $it."
            } else if (it.contains("Bearish", true) || it.contains("Shooting", true) || it.contains("Evening", true)) {
                sellScore += 15; reasons += "Pola Pelemahan: $it."
            }
        }

        // 6. Level SL & TP
        val effectiveAtr = if (atr.isFinite() && atr > 0.0) atr else (price * 0.04)
        val supportLevel = structure.support?.takeIf { it > 0.0 && it < price }
            ?: (price - effectiveAtr * 1.6)

        val calculatedSl = maxOf(
            supportLevel - (effectiveAtr * 0.3),
            price - (effectiveAtr * 1.8),
            price * 0.935
        ).coerceAtMost(price * 0.985)

        val calculatedTp1 = price * 1.085
        val calculatedTp2 = price * 1.185

        val feeResult = FeeCalculator.roundTrip(price, calculatedSl, calculatedTp2, fees)
        val netRr = feeResult.netRr.coerceAtLeast(1.8)
        val rrString = "1:${fmt(netRr)}"

        // 4 Checkpoints Office Daily
        val step1Ok = isUptrend && !isBearishStructure && !isDowntrend
        val step2Ok = step1Ok && (price >= supportLevel * 0.99)
        val step3Ok = step1Ok && (rsi in 38.0..65.0) && macdHist >= -0.001
        val step4Ok = step1Ok && step2Ok && step3Ok && netRr >= 1.7 && buyScore >= 50.0

        val completedSteps = listOf(step1Ok, step2Ok, step3Ok, step4Ok).count { it }
        val isQualified = completedSteps == 4 && buyScore >= 55.0 && buyScore > sellScore * 1.3
        val isSellSignal = rsi >= 74.0 || price >= calculatedTp1 || (sellScore >= 50.0 && sellScore > buyScore * 1.2)

        val finalAction = when {
            isSellSignal -> SignalAction.SELL
            isQualified -> SignalAction.BUY
            else -> SignalAction.HOLD
        }

        if (isSellSignal) {
            reasons.add(0, if (price >= calculatedTp1) "🎯 Target TP tercapai di Rp ${fmtPrice(calculatedTp1)} - Take Profit!" else "⚠️ Jenuh Beli / Sinyal Distribusi - Amankan Profit!")
        }

        val finalScore = when {
            isSellSignal -> (80 + min(15, (sellScore * 0.15).toInt())).coerceIn(80, 95)
            isQualified -> (80 + min(15, (buyScore * 0.15).toInt())).coerceIn(80, 95)
            completedSteps == 3 -> 68
            completedSteps == 2 -> 50
            completedSteps == 1 -> 35
            else -> 20
        }

        val mtfSnapshot = ScalpingMtfSnapshot(
            biasOk = step1Ok,
            biasDirection = if (step1Ok) "bullish" else "neutral",
            biasStatus = if (step1Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            biasDetail = if (step1Ok) "Tren makro harian selaras (EMA20 > EMA50)." else "Menunggu tren harian selaras.",

            setupOk = step2Ok,
            setupStatus = if (step2Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            setupDetail = if (step2Ok) "Support harian aman di Rp ${fmtPrice(supportLevel)}." else "Memantau lantai support.",

            triggerOk = step3Ok,
            triggerStatus = if (step3Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            triggerDetail = if (step3Ok) "RSI (${fmt(rsi)}) & MACD akumulasi stabil." else "Menunggu momentum stabil.",

            entryPriceOk = step4Ok,
            entryPriceStatus = if (step4Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            entryPriceDetail = "Zona Entry: Rp ${fmtPrice(price)} (Net R:R $rrString).",

            path = if (isBullishStructure) ScalpingPath.MOMENTUM_CONTINUATION else ScalpingPath.PULLBACK,
            statusTitle = if (completedSteps == 4) "READY" else "ANALYZING ($completedSteps/4)",
            waitingFor = if (completedSteps == 4) "Siap eksekusi" else "Menunggu konfirmasi setup lengkap",
            entryCondition = "Setup H4/1D Low Noise & High R:R"
        )

        return OfficeDailyEvalResult(
            signal = AISignalState(
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
            indicators = indicators,
            isQualified = isQualified,
            setupScore = completedSteps
        )
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    private fun fmtPrice(v: Double) = if (v >= 1000) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%.2f", v)
}
