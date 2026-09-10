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
    ): OfficeDailyEvalResult = evaluate(agu.analys.engine.global.GlobalMarketContext(), price, history, fees)

    fun evaluate(
        globalContext: agu.analys.engine.global.GlobalMarketContext = agu.analys.engine.global.GlobalMarketContext(),
        price: Double,
        history: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig()
    ): OfficeDailyEvalResult {
        if (price <= 0.0) {
            return OfficeDailyEvalResult(AISignalState(), TechnicalIndicators())
        }

        val minCandles = 20
        if (history.size < minCandles) {
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
                entryPriceDetail = "Menunggu riwayat candle pasar untuk memetakan level entry Office Daily.",
                path = ScalpingPath.PULLBACK,
                statusTitle = "MENGUMPULKAN DATA",
                waitingFor = "Menunggu sinkronisasi candle makro",
                entryCondition = "Memuat riwayat candle untuk setup Office Daily."
            )

            return OfficeDailyEvalResult(
                signal = AISignalState(
                    action = SignalAction.HOLD,
                    confidence = 0,
                    sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
                    entryPrice = price,
                    targetPrice1 = 0.0,
                    targetPrice2 = 0.0,
                    stopLoss = 0.0,
                    riskRewardRatio = "--",
                    reasoning = listOf(
                        "Data candle sedang disinkronkan (${history.size}/$minCandles candle).",
                        "Menunggu riwayat candle untuk setup Office Daily."
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

        // --- 1. DANGER & INVALIDATION CHECKS (DI ATAS) ---
        val isOverbought = rsi >= 74.0 || price >= calculatedTp1
        val isDistribution = sellScore >= 50.0 && sellScore > buyScore * 1.2
        val isBreakdown = isBearishStructure || isDowntrend || (price < supportLevel * 0.96)
        val isDangerous = isOverbought || isDistribution || isBreakdown

        // --- 2. WATERFALL CHECKPOINTS ---
        val step1Ok = !isDangerous && isUptrend && !isBearishStructure && !isDowntrend
        val step2Ok = step1Ok && (price >= supportLevel * 0.99)
        val step3Ok = step2Ok && (rsi in 38.0..65.0) && macdHist >= -0.001
        val step4Ok = step3Ok && netRr >= 1.7 && buyScore >= 50.0

        val completedSteps = when {
            step4Ok -> 4
            step3Ok -> 3
            step2Ok -> 2
            step1Ok -> 1
            else -> 0
        }

        // ── Keputusan akhir ────────────────────────────────
        val isQualified = step4Ok && buyScore >= 55.0 && buyScore > sellScore * 1.3
        val finalAction = when {
            isQualified && !isDangerous -> SignalAction.BUY
            else -> SignalAction.HOLD
        }

        if (isDangerous) {
            when {
                isOverbought -> reasons.add(0, if (price >= calculatedTp1) "⚠️ Tertahan: Target harga tercapai di Rp ${fmtPrice(calculatedTp1)} (Overextended)." else "⚠️ Tertahan: Indikator jenuh beli (Overbought RSI >= 74).")
                isBreakdown -> reasons.add(0, "⚠️ Tertahan: Harga koin sedang breakdown / downtrend menembus support.")
                isDistribution -> reasons.add(0, "⚠️ Tertahan: Tekanan jual dan distribusi tinggi terdeteksi.")
            }
        }

        val finalScore = when {
            isDangerous -> 0
            isQualified -> (80 + min(15, (buyScore * 0.15).toInt())).coerceIn(80, 95)
            step3Ok -> 68
            step2Ok -> 50
            step1Ok -> 35
            else -> 20
        }

        val biasDetailText = when {
            isOverbought -> "Tertahan: Harga koin sedang terlalu tinggi (Jenuh Beli/Overbought)."
            isBreakdown -> "Tertahan: Harga koin sedang turun menembus support (Breakdown)."
            isDistribution -> "Tertahan: Tekanan jual & sinyal distribusi tinggi."
            step1Ok -> "Tren makro harian selaras (EMA20 > EMA50)."
            else -> "Menunggu tren makro harian stabil."
        }

        val setupDetailText = when {
            !step1Ok -> "Menunggu Checkpoint 1 lolos."
            step2Ok -> "Support harian aman di Rp ${fmtPrice(supportLevel)}."
            else -> "Memantau lantai support (belum stabil)."
        }

        val triggerDetailText = when {
            !step2Ok -> "Menunggu Checkpoint 2 lolos."
            step3Ok -> "RSI (${fmt(rsi)}) & MACD akumulasi stabil."
            else -> "Menunggu momentum RSI & MACD stabil."
        }

        val entryPriceDetailText = when {
            !step3Ok -> "Menunggu Checkpoint 3 lolos."
            step4Ok -> "Zona Entry: Rp ${fmtPrice(price)} (Net R:R $rrString)."
            else -> "Menunggu R:R optimal (Min 1:1.7) & skor buy."
        }

        val mtfSnapshot = ScalpingMtfSnapshot(
            biasOk = step1Ok,
            biasDirection = if (step1Ok) "bullish" else "neutral",
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
            entryPriceDetail = entryPriceDetailText,

            path = if (isBullishStructure) ScalpingPath.MOMENTUM_CONTINUATION else ScalpingPath.PULLBACK,
            statusTitle = when {
                isOverbought -> "OVERBOUGHT / TARGET (HOLD)"
                isBreakdown -> "BREAKDOWN / DOWNTREND (HOLD)"
                isDistribution -> "DISTRIBUSI TINGGI (HOLD)"
                completedSteps == 4 -> "READY"
                completedSteps > 0 -> "ANALYZING ($completedSteps/4)"
                else -> "ANALYZING (0/4)"
            },
            waitingFor = when {
                isOverbought -> "Menunggu koreksi harga / reset RSI"
                isBreakdown -> "Menunggu pembentukan support baru"
                isDistribution -> "Menunggu tekanan jual mereda"
                completedSteps == 4 -> "Siap eksekusi"
                completedSteps == 3 -> "Menunggu konfirmasi zona entry & R:R"
                completedSteps == 2 -> "Menunggu momentum RSI & MACD"
                completedSteps == 1 -> "Menunggu pantulan support harian"
                else -> "Menunggu konfirmasi setup lengkap"
            },
            entryCondition = "Setup H4/1D Low Noise & High R:R"
        )

        return OfficeDailyEvalResult(
            signal = AISignalState(
                action = finalAction,
                confidence = finalScore,
                sentiment = when (finalAction) {
                    SignalAction.BUY -> TrendSentiment.STRONG_BULLISH_CONTINUATION
                    SignalAction.SELL -> TrendSentiment.BEARISH_DISTRIBUTION
                    SignalAction.HOLD -> if (isDangerous) TrendSentiment.BEARISH_DISTRIBUTION else if (completedSteps >= 2) TrendSentiment.ACCUMULATION_SQUEEZE else TrendSentiment.NEUTRAL_CONSOLIDATION
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

    private fun fmt(v: Double) = agu.analys.util.PriceFormatter.fmt(v)
    private fun fmtPrice(v: Double) = agu.analys.util.PriceFormatter.fmtPrice(v)
}
