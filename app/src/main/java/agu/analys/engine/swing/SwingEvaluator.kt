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

/**
 * Swing Evaluator — polished dengan 4 setup klasik di level penting:
 * 1. Rejection
 * 2. Breakout / Breakdown
 * 3. Retest
 * 4. Reclaim / Failed Breakout
 *
 * Prinsip: Sabar tunggu salah satu dari 4 setup muncul di Support/Resistance.
 * Tidak pakai istilah complicated. Fokus pada struktur + konfirmasi candle + volume.
 */
data class SwingEvalResult(val signal: AISignalState, val indicators: TechnicalIndicators)

private enum class SwingSetup {
    NONE,
    REJECTION,
    BREAKOUT,
    RETEST,
    RECLAIM_FAILED
}

object SwingEvaluator {

    fun evaluate(price: Double, history: List<CandleBar>, fees: TradingFeeConfig = TradingFeeConfig()): SwingEvalResult {
        if (price <= 0.0) {
            return SwingEvalResult(AISignalState(), TechnicalIndicators())
        }

        val minCandles = 20
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
                setupDetail = "Menunggu riwayat candle untuk memetakan level Support/Resistance.",
                triggerOk = false,
                triggerStatus = MtfLegStatus.WAITING,
                triggerDetail = "Menunggu data volume dan momentum.",
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
                    confidence = 30,
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

        // ── Indicators ──────────────────────────────────────────────────────
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
        val micro = MarketStructureAnalyzer.analyzeMicro(history)
        val momentumBase = closes[closes.lastIndex - min(10, closes.size - 1)]
        val momentum = if (momentumBase > 0.0) (price - momentumBase) / momentumBase else 0.0
        val ema200 = if (closes.size >= 200) IndicatorMath.ema(closes, 200) else Double.NaN
        val indicators = TechnicalIndicators(rsi, macd, macdSignal, macdHist, ema20, ema50, ema200, bb.second, bb.first, atr, momentum)

        val last = history.last()
        val prev = history[history.lastIndex - 1]
        val avgVolume = history.takeLast(8).dropLast(1).map { it.volume }.average().coerceAtLeast(1.0)
        val volumeRatio = last.volume / avgVolume
        val strongVolume = volumeRatio >= 1.25
        val body = abs(last.close - last.open)
        val range = (last.high - last.low).coerceAtLeast(1e-9)
        val bodyRatio = body / range
        val isBullCandle = last.close > last.open
        val isBearCandle = last.close < last.open
        val lowerWick = min(last.open, last.close) - last.low
        val upperWick = last.high - max(last.open, last.close)

        // ── Level penting (Support / Resistance) ────────────────────────────
        val effectiveAtr = if (atr.isFinite() && atr > 0.0) atr else (price * 0.03)
        val supportLevel = structure.support?.takeIf { it > 0.0 && it < price * 1.02 }
            ?: structure.lastSwingLow?.takeIf { it > 0.0 && it < price }
            ?: (price - effectiveAtr * 1.6)
        val resistanceLevel = structure.resistance?.takeIf { it > price * 0.98 }
            ?: structure.lastSwingHigh?.takeIf { it > price }
            ?: (price + effectiveAtr * 2.0)

        val nearSupport = abs(price - supportLevel) / price <= 0.012 || last.low <= supportLevel * 1.008
        val nearResistance = abs(price - resistanceLevel) / price <= 0.012 || last.high >= resistanceLevel * 0.992
        val brokeAboveResistance = last.close > resistanceLevel && prev.close <= resistanceLevel * 1.002
        val brokeBelowSupport = last.close < supportLevel && prev.close >= supportLevel * 0.998

        // ── Deteksi 4 Setup Klasik ──────────────────────────────────────────
        var detectedSetup = SwingSetup.NONE
        var setupScore = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Kondisi Pasar: $regime."
        reasons += "Level penting → Support Rp ${fmtPrice(supportLevel)} | Resistance Rp ${fmtPrice(resistanceLevel)}."

        // 1. REJECTION
        // Candle besar yang pantul dari level, minim wick ke arah pantulan, volume bagus
        val rejectionAtSupport = nearSupport && isBullCandle && bodyRatio >= 0.45 &&
            lowerWick <= body * 0.55 && (strongVolume || lowerWick > body * 0.3)
        val rejectionAtResistance = nearResistance && isBearCandle && bodyRatio >= 0.45 &&
            upperWick <= body * 0.55 && (strongVolume || upperWick > body * 0.3)

        // Micro sweep juga dihitung sebagai rejection kuat
        val sweepRejectionUp = micro.hasBullishSweep && nearSupport
        val sweepRejectionDown = micro.hasBearishSweep && nearResistance

        if (rejectionAtSupport || sweepRejectionUp) {
            detectedSetup = SwingSetup.REJECTION
            setupScore = if (strongVolume || sweepRejectionUp) 28.0 else 20.0
            reasons += "REJECTION di Support: candle pantul naik dari Rp ${fmtPrice(supportLevel)}${if (strongVolume) " + volume ${fmt(volumeRatio)}×" else ""}."
        } else if (rejectionAtResistance || sweepRejectionDown) {
            detectedSetup = SwingSetup.REJECTION
            setupScore = if (strongVolume || sweepRejectionDown) 26.0 else 18.0
            reasons += "REJECTION di Resistance: candle pantul turun dari Rp ${fmtPrice(resistanceLevel)}${if (strongVolume) " + volume ${fmt(volumeRatio)}×" else ""}."
        }

        // 2. BREAKOUT / BREAKDOWN
        // Candle nutup solid di luar level + volume
        val solidBreakout = brokeAboveResistance && isBullCandle && bodyRatio >= 0.40 && last.close >= resistanceLevel * 1.001
        val solidBreakdown = brokeBelowSupport && isBearCandle && bodyRatio >= 0.40 && last.close <= supportLevel * 0.999
        val bosBreakout = micro.hasBullishBOS && price > (structure.lastSwingHigh ?: resistanceLevel) * 0.998
        val bosBreakdown = micro.hasBearishBOS && price < (structure.lastSwingLow ?: supportLevel) * 1.002

        if (detectedSetup == SwingSetup.NONE) {
            if (solidBreakout || bosBreakout) {
                detectedSetup = SwingSetup.BREAKOUT
                setupScore = if (strongVolume) 30.0 else 22.0
                reasons += "BREAKOUT: harga tembus Resistance Rp ${fmtPrice(resistanceLevel)} dengan candle solid${if (strongVolume) " + volume ${fmt(volumeRatio)}×" else ""}."
            } else if (solidBreakdown || bosBreakdown) {
                detectedSetup = SwingSetup.BREAKOUT
                setupScore = if (strongVolume) 28.0 else 20.0
                reasons += "BREAKDOWN: harga tembus Support Rp ${fmtPrice(supportLevel)} dengan candle solid${if (strongVolume) " + volume ${fmt(volumeRatio)}×" else ""}."
            }
        }

        // 3. RETEST
        // Setelah breakout, harga balik ngetes level yang baru ditembus lalu ditolak lagi
        // Deteksi sederhana: harga dekat level yang sudah pernah di-break, + rejection candle
        val recentHighs = history.takeLast(12).map { it.high }
        val recentLows = history.takeLast(12).map { it.low }
        val hadBreakAbove = recentHighs.any { it > resistanceLevel * 1.005 } && price < resistanceLevel * 1.015
        val hadBreakBelow = recentLows.any { it < supportLevel * 0.995 } && price > supportLevel * 0.985

        if (detectedSetup == SwingSetup.NONE) {
            if (hadBreakAbove && nearResistance && (rejectionAtResistance || (isBearCandle && bodyRatio >= 0.35))) {
                // Ini sebenarnya failed retest / distribution — treat as rejection setelah break
                detectedSetup = SwingSetup.RETEST
                setupScore = 18.0
                reasons += "RETEST Resistance gagal: level Rp ${fmtPrice(resistanceLevel)} ditolak lagi setelah pernah tembus."
            } else if (hadBreakBelow && nearSupport && (rejectionAtSupport || (isBullCandle && bodyRatio >= 0.35))) {
                detectedSetup = SwingSetup.RETEST
                setupScore = 24.0
                reasons += "RETEST Support: harga balik ngetes Rp ${fmtPrice(supportLevel)} setelah breakdown lalu ditolak (support hold)."
            } else if (price > resistanceLevel * 0.995 && price < resistanceLevel * 1.025 && isBullCandle && strongVolume) {
                // Retest dari atas (resistance become support)
                detectedSetup = SwingSetup.RETEST
                setupScore = 26.0
                reasons += "RETEST: Resistance lama jadi Support. Harga ditolak naik dari area Rp ${fmtPrice(resistanceLevel)}."
            }
        }

        // 4. RECLAIM / FAILED BREAKOUT
        // Harga sempat tembus level lalu balik lagi ke sisi semula → sinyal kuat berlawanan
        val failedBreakout = last.high > resistanceLevel * 1.003 && last.close < resistanceLevel * 0.998 && isBearCandle
        val failedBreakdown = last.low < supportLevel * 0.997 && last.close > supportLevel * 1.002 && isBullCandle
        val reclaimFromBelow = micro.hasBullishSweep || (failedBreakdown && nearSupport)
        val reclaimFromAbove = micro.hasBearishSweep || (failedBreakout && nearResistance)

        if (detectedSetup == SwingSetup.NONE || setupScore < 20.0) {
            if (reclaimFromBelow || failedBreakdown) {
                detectedSetup = SwingSetup.RECLAIM_FAILED
                setupScore = max(setupScore, if (strongVolume) 28.0 else 22.0)
                reasons += "RECLAIM / FAILED BREAKDOWN: harga sempat breakdown Support lalu balik naik. Sinyal bullish kuat."
            } else if (reclaimFromAbove || failedBreakout) {
                detectedSetup = SwingSetup.RECLAIM_FAILED
                setupScore = max(setupScore, if (strongVolume) 26.0 else 20.0)
                reasons += "FAILED BREAKOUT: harga sempat tembus Resistance lalu balik turun. Sinyal bearish kuat."
            }
        }

        // ── Scoring tambahan (konteks) ──────────────────────────────────────
        var buy = setupScore
        var sell = 0.0

        // RSI
        when {
            rsi < 32 -> { buy += 14; reasons += "RSI ${fmt(rsi)} jenuh jual (peluang rebound swing)." }
            rsi in 32.0..48.0 -> { buy += 10; reasons += "RSI ${fmt(rsi)} di zona akumulasi sehat." }
            rsi in 48.0..65.0 -> { buy += 8; reasons += "RSI ${fmt(rsi)} momentum bullish terjaga." }
            rsi > 72 -> { sell += 18; reasons += "RSI ${fmt(rsi)} jenuh beli (waspada koreksi)." }
            else -> reasons += "RSI ${fmt(rsi)} netral."
        }

        // EMA alignment
        val emaBullish = ema20.isFinite() && ema50.isFinite() && ema20 > ema50 && price >= ema50 * 0.985
        val isReclaimEma = price > ema20 && price > ema50 && macdHist > 0
        val isBearishTrend = ema20.isFinite() && ema50.isFinite() && ema20 < ema50 && price < ema20

        when {
            emaBullish -> { buy += 16; reasons += "EMA20 > EMA50, harga di atas EMA (uptrend selaras)." }
            isReclaimEma -> { buy += 14; reasons += "Harga reclaim EMA20 & EMA50 dengan momentum positif." }
            isBearishTrend -> { sell += 18; reasons += "EMA20 < EMA50, harga di bawah EMA20 (downtrend makro)." }
            price > ema50 -> { buy += 6; reasons += "Harga bertahan di atas EMA50." }
            else -> reasons += "EMA berkonsolidasi, menunggu arah tegas."
        }

        // MACD
        when {
            macdHist > 0 -> { buy += 10; reasons += "MACD histogram positif (momentum beli)." }
            macdHist < 0 -> { sell += 10; reasons += "MACD histogram negatif (tekanan jual)." }
        }

        // Volume konfirmasi
        if (strongVolume) {
            if (isBullCandle) { buy += 10; reasons += "Volume beli ${fmt(volumeRatio)}× di atas rata-rata." }
            else { sell += 10; reasons += "Volume jual ${fmt(volumeRatio)}× di atas rata-rata." }
        }

        // Struktur market
        val isBullishStructure = structure.trend.contains("Bull", true)
        val isBearishStructure = structure.trend.contains("Bear", true)
        if (structure.dataEnough) {
            when {
                isBullishStructure -> { buy += 12; reasons += "Struktur market bullish (Higher-High & Higher-Low)." }
                isBearishStructure -> { sell += 12; reasons += "Struktur market bearish (Lower-Low)." }
                else -> reasons += "Struktur market range / transisi."
            }
        }

        // Pattern candle
        pattern?.let {
            if (it.contains("Bullish", true) || it.contains("Hammer", true) || it.contains("Morning", true)) {
                buy += 8; reasons += "Pola Candlestick: $it."
            } else if (it.contains("Bearish", true) || it.contains("Shooting", true) || it.contains("Evening", true)) {
                sell += 8; reasons += "Pola Candlestick: $it."
            }
        }

        // ── Target & SL ─────────────────────────────────────────────────────
        val calculatedSl = when (detectedSetup) {
            SwingSetup.REJECTION, SwingSetup.RETEST, SwingSetup.RECLAIM_FAILED -> {
                // SL di bawah low candle rejection / di luar support
                maxOf(
                    min(last.low, supportLevel) - effectiveAtr * 0.20,
                    price - effectiveAtr * 1.4,
                    price * 0.93
                ).coerceAtMost(price * 0.985)
            }
            SwingSetup.BREAKOUT -> {
                // SL sedikit di bawah candle breakout / level yang ditembus
                maxOf(
                    min(last.low, resistanceLevel) - effectiveAtr * 0.15,
                    price - effectiveAtr * 1.2,
                    price * 0.94
                ).coerceAtMost(price * 0.988)
            }
            else -> {
                maxOf(
                    supportLevel - effectiveAtr * 0.25,
                    price - effectiveAtr * 1.5,
                    price * 0.93
                ).coerceAtMost(price * 0.985)
            }
        }

        val calculatedTp1 = maxOf(
            resistanceLevel,
            price + effectiveAtr * 2.0,
            price * 1.07
        )
        val calculatedTp2 = maxOf(
            calculatedTp1 * 1.08,
            price + effectiveAtr * 3.4,
            price * 1.16
        )

        val feeResult = FeeCalculator.roundTrip(price, calculatedSl, calculatedTp2, fees)
        val netRr = feeResult.netRr.coerceAtLeast(1.4)
        val rrString = "1:${fmt(netRr)}"

        // ── 4-Step Checkpoint (UI tetap kompatibel) ─────────────────────────
        // Step 1: Bias tren makro
        val step1Ok = (emaBullish || isReclaimEma || detectedSetup == SwingSetup.RECLAIM_FAILED || detectedSetup == SwingSetup.BREAKOUT) &&
            !isBearishTrend
        val step1Detail = when {
            step1Ok && detectedSetup == SwingSetup.BREAKOUT -> "Bias bullish via Breakout level penting."
            step1Ok && detectedSetup == SwingSetup.RECLAIM_FAILED -> "Bias bullish via Reclaim / Failed Breakdown."
            step1Ok -> "Tren makro selaras positif (EMA20 > EMA50 di Rp ${fmtPrice(ema20)})."
            isBearishTrend -> "Tren makro downtrend (EMA20 < EMA50). Menunggu Reclaim EMA20."
            else -> "Memantau keselarasan tren EMA (harga menguji area Rp ${fmtPrice(ema20)})."
        }

        // Step 2: Setup di level penting (ada salah satu dari 4 setup)
        val step2Ok = step1Ok && detectedSetup != SwingSetup.NONE && (nearSupport || nearResistance || brokeAboveResistance || brokeBelowSupport || micro.hasBullishBOS || micro.hasBullishSweep)
        val step2Detail = when (detectedSetup) {
            SwingSetup.REJECTION -> "Setup REJECTION terdeteksi di level penting."
            SwingSetup.BREAKOUT -> "Setup BREAKOUT terdeteksi — momentum kuat."
            SwingSetup.RETEST -> "Setup RETEST terdeteksi — level dihormati lagi."
            SwingSetup.RECLAIM_FAILED -> "Setup RECLAIM / FAILED BREAK terdeteksi — sinyal kuat."
            else -> "Belum ada Rejection / Breakout / Retest / Reclaim yang valid di Support/Resistance."
        }

        // Step 3: Konfirmasi momentum + volume
        val isRsiBullish = rsi in 35.0..68.0 || (rsi in 28.0..38.0 && macdHist >= 0)
        val step3Ok = step2Ok && (isRsiBullish || macdHist >= 0 || strongVolume) && buy > sell
        val step3Detail = if (step3Ok) {
            "Momentum & volume mendukung (RSI ${fmt(rsi)}, Vol ${fmt(volumeRatio)}×)."
        } else if (macdHist < 0) {
            "MACD masih negatif (${fmt(macdHist)}). Menunggu penguatan momentum."
        } else {
            "Menunggu dorongan volume & penguatan RSI (${fmt(rsi)})."
        }

        // Step 4: R:R + skor setup
        val step4Ok = step1Ok && step2Ok && step3Ok && netRr >= 1.4 && buy >= 38.0
        val step4Detail = if (step4Ok) {
            "Zona entry ideal · Net R:R $rrString · Setup: ${detectedSetup.name.replace('_', ' ')}"
        } else if (!step1Ok || !step2Ok || !step3Ok) {
            "Menunggu konfirmasi Checkpoint 1-3 sebelum validasi entry."
        } else {
            "Menunggu R:R optimal atau skor setup lebih tinggi."
        }

        val completedSteps = listOf(step1Ok, step2Ok, step3Ok, step4Ok).count { it }

        // ── Keputusan akhir ─────────────────────────────────────────────────
        val isQualifiedBuy = completedSteps == 4 && buy >= 42.0 && buy > sell * 1.15 &&
            detectedSetup in listOf(SwingSetup.REJECTION, SwingSetup.BREAKOUT, SwingSetup.RETEST, SwingSetup.RECLAIM_FAILED) &&
            (detectedSetup != SwingSetup.REJECTION || !rejectionAtResistance) // rejection di resistance = bukan buy

        val isSellSignal = (rsi >= 72.0 || price >= calculatedTp1 * 0.995 || (sell >= 42.0 && sell > buy * 1.15) ||
            (detectedSetup == SwingSetup.REJECTION && rejectionAtResistance) ||
            (detectedSetup == SwingSetup.RECLAIM_FAILED && (reclaimFromAbove || failedBreakout)) ||
            (detectedSetup == SwingSetup.BREAKOUT && (solidBreakdown || bosBreakdown)))

        val finalAction = when {
            isSellSignal -> SignalAction.SELL
            isQualifiedBuy -> SignalAction.BUY
            else -> SignalAction.HOLD
        }

        if (isSellSignal) {
            reasons.add(0, when {
                price >= calculatedTp1 * 0.995 -> "🎯 Target TP1 tercapai di Rp ${fmtPrice(calculatedTp1)} — Amankan profit!"
                detectedSetup == SwingSetup.REJECTION && rejectionAtResistance -> "⚠️ Rejection di Resistance — rekomendasi take profit / hindari buy."
                detectedSetup == SwingSetup.RECLAIM_FAILED && (reclaimFromAbove || failedBreakout) -> "⚠️ Failed Breakout — sinyal bearish kuat."
                else -> "⚠️ Tekanan jual tinggi / RSI jenuh — rekomendasi take profit."
            })
        } else if (isQualifiedBuy) {
            reasons.add(0, "✅ Setup ${detectedSetup.name.replace('_', ' ')} valid — entry swing siap.")
        }

        val finalScore = when {
            isSellSignal -> (78 + min(17, (sell * 0.2).toInt())).coerceIn(78, 95)
            isQualifiedBuy -> (80 + min(15, (buy * 0.18).toInt())).coerceIn(80, 95)
            completedSteps == 3 -> 65
            completedSteps == 2 -> 50
            completedSteps == 1 -> 36
            else -> 24
        }

        val path = when (detectedSetup) {
            SwingSetup.BREAKOUT -> ScalpingPath.MOMENTUM_CONTINUATION
            SwingSetup.RETEST, SwingSetup.REJECTION, SwingSetup.RECLAIM_FAILED -> ScalpingPath.PULLBACK
            else -> if (isBullishStructure) ScalpingPath.MOMENTUM_CONTINUATION else ScalpingPath.PULLBACK
        }

        val mtfSnapshot = ScalpingMtfSnapshot(
            biasOk = step1Ok,
            biasDirection = if (step1Ok) "bullish" else if (isBearishTrend) "bearish" else "neutral",
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

            path = path,
            statusTitle = if (completedSteps == 4) "SWING ENTRY READY" else "SWING ANALYZING ($completedSteps/4)",
            waitingFor = if (completedSteps == 4) "Siap eksekusi Swing Buy" else "Menunggu konfirmasi setup lengkap",
            entryCondition = when (detectedSetup) {
                SwingSetup.REJECTION -> "Rejection di Support"
                SwingSetup.BREAKOUT -> "Breakout level penting"
                SwingSetup.RETEST -> "Retest level setelah break"
                SwingSetup.RECLAIM_FAILED -> "Reclaim / Failed Break"
                else -> "Menunggu 1 dari 4 setup di S/R"
            }
        )

        return SwingEvalResult(
            AISignalState(
                action = finalAction,
                confidence = finalScore,
                sentiment = when (finalAction) {
                    SignalAction.BUY -> when (detectedSetup) {
                        SwingSetup.BREAKOUT -> TrendSentiment.STRONG_BULLISH_CONTINUATION
                        SwingSetup.RECLAIM_FAILED, SwingSetup.REJECTION -> TrendSentiment.BULLISH_REVERSAL
                        else -> TrendSentiment.STRONG_BULLISH_CONTINUATION
                    }
                    SignalAction.SELL -> TrendSentiment.BEARISH_DISTRIBUTION
                    SignalAction.HOLD -> if (completedSteps >= 2) TrendSentiment.ACCUMULATION_SQUEEZE else TrendSentiment.NEUTRAL_CONSOLIDATION
                },
                entryPrice = price,
                targetPrice1 = calculatedTp1,
                targetPrice2 = calculatedTp2,
                stopLoss = calculatedSl,
                riskRewardRatio = rrString,
                reasoning = reasons.take(8),
                timestamp = System.currentTimeMillis(),
                patternDetected = pattern,
                scalpingStage = when {
                    completedSteps == 4 -> ScalpingStage.ENTRY
                    completedSteps >= 3 -> ScalpingStage.EARLY_ENTRY
                    completedSteps >= 2 -> ScalpingStage.WAIT_PULLBACK
                    else -> ScalpingStage.HOLD
                },
                mtf = mtfSnapshot,
                regimeDetected = regime
            ),
            indicators
        )
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    private fun fmtPrice(v: Double) = String.format(java.util.Locale.US, "%,.0f", v)
}

