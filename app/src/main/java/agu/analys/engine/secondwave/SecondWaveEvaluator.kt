package agu.analys.engine.secondwave

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
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

/**
 * Tipe Entry Second-Wave Hunter:
 * - BASE_DIP: Pembelian di lantai akumulasi support saat volume jual kering.
 * - RECLAIM: Pembelian saat harga menembus kembali resistance lokal dengan konfirmasi volume beli baru.
 * - NONE: Belum memenuhi kriteria setup Second-Wave.
 */
enum class SecondWaveEntryType(val label: String, val badge: String) {
    BASE_DIP("Base-Dip Entry", "🛡️ BASE-DIP"),
    RECLAIM("Reclaim Entry", "🚀 RECLAIM"),
    NONE("No Setup", "⏳ WATCHING")
}

/**
 * Metrik terukur dari kalkulasi deterministik Second-Wave.
 */
data class SecondWaveMetrics(
    val priorHigh: Double = 0.0,
    val baseFloor: Double = 0.0,
    val reclaimLevel: Double = 0.0,
    val drawdownPct: Double = 0.0,
    val priorRunGainPct: Double = 0.0,
    val volumeDryUpRatio: Double = 0.0,
    val totalScore: Int = 0, // 0 - 12
    val entryType: SecondWaveEntryType = SecondWaveEntryType.NONE,
    val isQualified: Boolean = false,
    val summaryThesis: String = ""
)

data class SecondWaveEvalResult(
    val signal: AISignalState,
    val indicators: TechnicalIndicators,
    val metrics: SecondWaveMetrics
)

/**
 * SOLANA / CRYPTO SECOND-WAVE HUNTER ENGINE
 * Deterministic decision pipeline untuk mendeteksi koin mantan pemenang (runner)
 * yang mengalami koreksi sehat (50-85%), membentuk base akumulasi, volume kering,
 * dan bersiap meluncurkan gelombang kedua (Second-Wave).
 */
object SecondWaveEvaluator {

    /**
     * Evaluasi mendalam Multi-Timeframe (H4 / 1D Makro + 1H Setup + 15M Trigger).
     */
    fun evaluate(
        price: Double,
        macroCandles: List<CandleBar>, // H4 atau 1D (min 30 candles)
        h1Candles: List<CandleBar>,    // 1H (min 40 candles)
        m15Candles: List<CandleBar>,   // 15M (min 40 candles)
        fees: TradingFeeConfig = TradingFeeConfig()
    ): SecondWaveEvalResult {
        if (price <= 0.0 || macroCandles.size < 20 || h1Candles.size < 20 || m15Candles.size < 20) {
            return fallbackResult(price, "Data candle historis belum memadai untuk evaluasi Second-Wave.")
        }

        // STAGE 2: Prior Run Verification (Mencari Prior High & Prior Low dalam 60 candle makro)
        val macroHighs = macroCandles.map { it.high }
        val macroLows = macroCandles.map { it.low }
        val priorHigh = macroHighs.maxOrNull() ?: price
        val priorLowBeforeHigh = run {
            val highIdx = macroHighs.indexOf(priorHigh).coerceAtLeast(0)
            if (highIdx > 0) macroLows.take(highIdx).minOrNull() ?: (priorHigh * 0.5)
            else macroLows.minOrNull() ?: (priorHigh * 0.5)
        }
        val priorRunGainPct = if (priorLowBeforeHigh > 0) ((priorHigh - priorLowBeforeHigh) / priorLowBeforeHigh) * 100.0 else 0.0

        // Prior run score: 0 = no run (<20%), 1 = moderate (20-40%), 2 = strong (>40%)
        val priorRunScore = when {
            priorRunGainPct >= 40.0 -> 2
            priorRunGainPct >= 20.0 -> 1
            else -> 0
        }

        // STAGE 3: Pullback Quality (Drawdown = (prior_high - current_price) / prior_high)
        val drawdownPct = if (priorHigh > 0) ((priorHigh - price) / priorHigh) * 100.0 else 0.0
        val drawdownScore = when {
            drawdownPct in 50.0..75.0 -> 2 // Healthy second-wave sweet spot
            drawdownPct in 75.0..85.0 -> 1 // High risk/reward zone
            drawdownPct in 40.0..50.0 -> 1 // Reset awal
            else -> 0 // Terlalu dangkal (<40%) atau koin mati (>85%)
        }

        // STAGE 5: Structure Detection di Timeframe 1H
        // Base Compression & Higher Lows
        val h1Lows = h1Candles.takeLast(20).map { it.low }
        val h1Highs = h1Candles.takeLast(20).map { it.high }
        val baseFloor = h1Lows.minOrNull() ?: (price * 0.95)
        val localResistance = h1Highs.takeLast(10).maxOrNull() ?: (price * 1.05)

        val recentLows = h1Candles.takeLast(8).map { it.low }
        val olderLows = h1Candles.dropLast(8).takeLast(8).map { it.low }
        val minRecentLow = recentLows.minOrNull() ?: baseFloor
        val minOlderLow = olderLows.minOrNull() ?: baseFloor
        val hasHigherLow = minRecentLow >= minOlderLow * 0.99 // Membentuk Higher Low atau Double Bottom

        val closesH1 = h1Candles.map { it.close }
        val ema20H1 = IndicatorMath.ema(closesH1, 20)
        val ema50H1 = IndicatorMath.ema(closesH1, 50)
        val isBaseHolding = price >= baseFloor && (price >= ema20H1 * 0.985)

        val structureScore = when {
            hasHigherLow && isBaseHolding && price >= ema20H1 -> 2
            isBaseHolding || hasHigherLow -> 1
            else -> 0
        }

        // STAGE 6: Volume Behavior (Volume Dry-Up during pullback, then Renewed Buying)
        val peakVolume = macroCandles.takeLast(30).map { it.volume }.maxOrNull() ?: 1.0
        val avgRecentVolH1 = h1Candles.takeLast(10).map { it.volume }.average()
        val latestVolM15 = m15Candles.takeLast(3).map { it.volume }.average()
        val volumeDryUpRatio = if (peakVolume > 0) (avgRecentVolH1 / peakVolume) else 1.0
        val isVolumeDriedUp = volumeDryUpRatio <= 0.45 // Volume mengecil drastis saat koreksi
        val isVolumeReturning = latestVolM15 >= avgRecentVolH1 * 1.25 && m15Candles.last().close >= m15Candles.last().open

        val volumeScore = when {
            isVolumeDriedUp && isVolumeReturning -> 2
            isVolumeDriedUp || isVolumeReturning -> 1
            else -> 0
        }

        // STAGE 7: Flow & Accumulation (Candlestick absorptions & RSI reset)
        val rsi15m = IndicatorMath.rsi(m15Candles, 14)
        val rsi1h = IndicatorMath.rsi(h1Candles, 14)
        val macd15m = IndicatorMath.macdSeries(m15Candles.map { it.close }, 12, 26, 9).lastOrNull()
        val macdHist = (macd15m?.first ?: 0.0) - (macd15m?.second ?: 0.0)

        val flowScore = when {
            rsi1h in 40.0..62.0 && macdHist >= 0 && rsi15m >= 45.0 -> 2
            rsi1h in 35.0..68.0 || macdHist >= 0 -> 1
            else -> 0
        }

        // STAGE 4: Safety & Liquidity Score
        val safetyScore = if (drawdownPct <= 85.0 && price > 0 && baseFloor > 0) 2 else 0

        // TOTAL SCORE (0 - 12)
        val totalScore = priorRunScore + drawdownScore + structureScore + volumeScore + flowScore + safetyScore
        val isQualified = totalScore >= 8 && drawdownScore >= 1 && priorRunScore >= 1

        // ENTRY LOGIC: Base-Dip vs Reclaim
        val isNearBaseFloor = (price - baseFloor) / baseFloor <= 0.035 // Dalam rentang 3.5% dari lantai
        val isReclaimingResistance = price >= localResistance * 0.995 && isVolumeReturning

        val entryType = when {
            !isQualified -> SecondWaveEntryType.NONE
            isReclaimingResistance -> SecondWaveEntryType.RECLAIM
            isNearBaseFloor -> SecondWaveEntryType.BASE_DIP
            price > baseFloor -> SecondWaveEntryType.BASE_DIP
            else -> SecondWaveEntryType.NONE
        }

        // Target TP & Invalidation
        val stopLoss = baseFloor * 0.965 // SL di bawah lantai base (-3.5%)
        val tp1 = if (localResistance > price * 1.05) localResistance else price * 1.12 // TP1 +10% s/d +15%
        val tp2 = max(price * 1.25, priorHigh * 0.85) // TP2 +25% s/d +50%

        val feeResult = FeeCalculator.roundTrip(price, stopLoss, tp2, fees)
        val rrRatio = "1:${fmt(feeResult.netRr.coerceAtLeast(1.5))}"

        val reasons = mutableListOf<String>()
        reasons.add("🌊 Second-Wave Score: $totalScore/12 (${if (isQualified) "QUALIFIED SETUP" else "WATCHING"})")
        reasons.add("Prior High: Rp ${fmtPrice(priorHigh)} · Drawdown: ${fmt(drawdownPct)}%")
        reasons.add("Lantai Base Support: Rp ${fmtPrice(baseFloor)} · Reclaim Level: Rp ${fmtPrice(localResistance)}")
        if (isVolumeDriedUp) reasons.add("Volume koreksi sudah kering (Dry-Up terkonfirmasi).")
        if (isVolumeReturning) reasons.add("Volume beli 15M mulai melonjak masuk.")

        val thesis = when {
            entryType == SecondWaveEntryType.RECLAIM -> "Reclaim terkonfirmasi! Volume beli baru menembus resistance lokal Rp ${fmtPrice(localResistance)}."
            entryType == SecondWaveEntryType.BASE_DIP -> "Harga tertahan kokoh di lantai akumulasi Rp ${fmtPrice(baseFloor)}. Tekanan jual habis."
            else -> "Memantau pembentukan lantai base dan serapan volume akumulasi."
        }

        val step1Ok = priorRunScore >= 1 && drawdownScore >= 1
        val step2Ok = structureScore >= 1
        val step3Ok = volumeScore >= 1 || flowScore >= 1
        val step4Ok = isQualified && (entryType != SecondWaveEntryType.NONE)

        val mtfSnapshot = ScalpingMtfSnapshot(
            biasOk = step1Ok,
            biasDirection = if (step1Ok) "bullish" else "neutral",
            biasStatus = if (step1Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            biasDetail = "Prior Run +${fmt(priorRunGainPct)}% · Drawdown ${fmt(drawdownPct)}% (Zona ${if (drawdownPct in 50.0..85.0) "Ideal" else "Pantau"}).",

            setupOk = step2Ok,
            setupStatus = if (step2Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            setupDetail = "Lantai Base Rp ${fmtPrice(baseFloor)} ${if (hasHigherLow) "membentuk Higher-Low" else "sedang diuji"}.",

            triggerOk = step3Ok,
            triggerStatus = if (step3Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            triggerDetail = if (isVolumeReturning) "Volume beli baru 15M terkonfirmasi masuk." else "Menunggu lonjakan volume beli untuk konfirmasi.",

            entryPriceOk = step4Ok,
            entryPriceStatus = if (step4Ok) MtfLegStatus.OK else MtfLegStatus.WAITING,
            entryPriceDetail = "Zona Entry: ${entryType.label} (Area Rp ${fmtPrice(price)}).",

            path = if (entryType == SecondWaveEntryType.RECLAIM) ScalpingPath.MOMENTUM_CONTINUATION else ScalpingPath.PULLBACK,
            statusTitle = if (step4Ok) "SECOND-WAVE READY (${entryType.badge})" else "MENUNGGU BASE & VOLUME",
            waitingFor = if (step4Ok) "Siap eksekusi ${entryType.label}" else "Menunggu akumulasi lantai & volume",
            entryCondition = thesis
        )

        val action = if (isQualified && step4Ok) SignalAction.BUY else SignalAction.HOLD

        val signalState = AISignalState(
            action = action,
            confidence = (totalScore * 8.33).toInt().coerceIn(10, 95),
            sentiment = if (action == SignalAction.BUY) TrendSentiment.BULLISH_REVERSAL else TrendSentiment.ACCUMULATION_SQUEEZE,
            entryPrice = price,
            targetPrice1 = tp1,
            targetPrice2 = tp2,
            stopLoss = stopLoss,
            riskRewardRatio = rrRatio,
            reasoning = reasons,
            timestamp = System.currentTimeMillis(),
            scalpingStage = if (action == SignalAction.BUY) ScalpingStage.ENTRY else ScalpingStage.WAIT_PULLBACK,
            mtf = mtfSnapshot
        )

        val indicators = TechnicalIndicators(
            rsi14 = rsi15m,
            macdHist = macdHist,
            ema20 = ema20H1,
            ema50 = ema50H1,
            momentum = if (priorHigh > 0) ((price - baseFloor) / baseFloor) * 100.0 else 0.0
        )

        val metrics = SecondWaveMetrics(
            priorHigh = priorHigh,
            baseFloor = baseFloor,
            reclaimLevel = localResistance,
            drawdownPct = drawdownPct,
            priorRunGainPct = priorRunGainPct,
            volumeDryUpRatio = volumeDryUpRatio,
            totalScore = totalScore,
            entryType = entryType,
            isQualified = isQualified,
            summaryThesis = thesis
        )

        return SecondWaveEvalResult(signalState, indicators, metrics)
    }

    /**
     * Evaluasi cepat untuk Dashboard Scanner (memfilter koin Second-Wave potensial secara instan).
     */
    fun evaluateFast(
        tick: MarketTick,
        high24h: Double,
        low24h: Double
    ): FastSecondWaveScore {
        val price = tick.price
        if (price <= 0.0) return FastSecondWaveScore(0, false, 0.0, "Data tidak valid")

        val effectiveHigh = max(high24h, price)
        val effectiveLow = if (low24h > 0) low24h else price * 0.8
        val drawdownFromHigh = if (effectiveHigh > 0) ((effectiveHigh - price) / effectiveHigh) * 100.0 else 0.0
        val reboundFromLow = if (effectiveLow > 0) ((price - effectiveLow) / effectiveLow) * 100.0 else 0.0

        // Kriteria Fast Scanner:
        // 1. Koin punya rentang volatilitas (High vs Low > 15%)
        // 2. Koreksi dari High di antara 15% - 75%
        // 3. Sudah mulai memantul dari Low (Rebound 2% - 15%)
        var score = 0
        if (drawdownFromHigh in 15.0..70.0) score += 4
        if (reboundFromLow in 2.0..20.0) score += 4
        if (tick.volume24h >= 2_000_000_000) score += 2
        if (tick.change24h in -10.0..10.0) score += 2 // Konsolidasi di base

        val isCandidate = score >= 8
        val summary = when {
            score >= 10 -> "🌊 High Quality Base (Drawdown ${fmt(drawdownFromHigh)}% · Rebound +${fmt(reboundFromLow)}%)"
            score >= 8 -> "🌊 Potensi Second-Wave (Reclaim Base)"
            else -> "Konsolidasi normal"
        }

        return FastSecondWaveScore(
            score = score,
            isQualified = isCandidate,
            drawdownPct = drawdownFromHigh,
            summary = summary
        )
    }

    private fun fallbackResult(price: Double, reason: String): SecondWaveEvalResult {
        return SecondWaveEvalResult(
            signal = AISignalState(
                action = SignalAction.HOLD,
                confidence = 0,
                entryPrice = price,
                reasoning = listOf(reason),
                timestamp = System.currentTimeMillis(),
                scalpingStage = ScalpingStage.HOLD
            ),
            indicators = TechnicalIndicators(),
            metrics = SecondWaveMetrics(summaryThesis = reason)
        )
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun fmtPrice(v: Double) = if (v >= 1000) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%.2f", v)
}

data class FastSecondWaveScore(
    val score: Int,
    val isQualified: Boolean,
    val drawdownPct: Double,
    val summary: String
)
