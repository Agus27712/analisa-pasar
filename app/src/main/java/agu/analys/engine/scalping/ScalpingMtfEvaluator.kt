package agu.analys.engine.scalping

import agu.analys.engine.MarketStructureAnalyzer
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

/** Result of multi-timeframe scalping evaluation. */
data class ScalpingMtfResult(
    val signal: AISignalState,
    val indicators: TechnicalIndicators
)

/**
 * 1H = bias, 15M = setup, 1M = trigger.
 * Pure scoring — no network, no StateFlow.
 * Thresholds TIDAK diubah; hanya expose structured snapshot untuk UI.
 */
object ScalpingMtfEvaluator {

    fun evaluate(
        price: Double,
        h1Candles: List<CandleBar>,
        m15Candles: List<CandleBar>,
        m1Candles: List<CandleBar>
    ): ScalpingMtfResult? {
        val h1 = FrameAnalyzer.analyze(
            h1Candles,
            rsiPeriod = 14, fastPeriod = 9, slowPeriod = 21,
            macdFast = 12, macdSlow = 26, macdSignal = 9
        ) ?: return null
        val m15 = FrameAnalyzer.analyze(m15Candles) ?: return null
        val m1 = FrameAnalyzer.analyze(m1Candles) ?: return null
        val atr = m1.atr
        if (atr <= 0.0 || price <= 0.0) return null

        val biasLong = h1.bullishEma && h1.structureEnough && h1.structureTrend == "Bullish structure" && h1.bullishMomentum
        val biasShort = h1.bearishEma && h1.structureEnough && h1.structureTrend == "Bearish structure" && h1.bearishMomentum
        val setupLong = m15.bullishEma && m15.structureEnough && m15.structureTrend == "Bullish structure"
        val setupShort = m15.bearishEma && m15.structureEnough && m15.structureTrend == "Bearish structure"
        val triggerLong = m1.bullishEma && m1.bullishMomentum && m1.rsi in 50.0..75.0 &&
            (m1.volumeRatio >= 1.20 || m1.breakoutUp || m1.retestUp)
        val triggerShort = m1.bearishEma && m1.bearishMomentum && m1.rsi in 25.0..50.0 &&
            (m1.volumeRatio >= 1.20 || m1.breakoutDown || m1.retestDown)
        val extendedLong = h1.rsi > 75.0 || m15.rsi > 75.0
        val extendedShort = h1.rsi < 25.0 || m15.rsi < 25.0
        val extremeVolatility = atr / price > 0.04

        var longScore = 0
        var shortScore = 0
        val reasons = mutableListOf<String>()
        reasons += "[SCALPING MTF] 1H = bias, 15M = setup, 1M = trigger."
        reasons += "1H: ${if (biasLong) "bullish" else if (biasShort) "bearish" else "mixed"}, RSI ${fmt(h1.rsi)}."
        reasons += "15M: ${if (setupLong) "bullish setup" else if (setupShort) "bearish setup" else "pullback/mixed"}, RSI ${fmt(m15.rsi)}."
        reasons += "1M: ${if (triggerLong) "long trigger" else if (triggerShort) "short trigger" else "belum trigger"}, RSI ${fmt(m1.rsi)}, vol ${fmt(m1.volumeRatio)}×."

        if (biasLong) longScore += 25
        if (biasShort) shortScore += 25
        if (setupLong) longScore += 25
        if (setupShort) shortScore += 25
        if (triggerLong) longScore += 30
        if (triggerShort) shortScore += 30
        if (m1.volumeRatio >= 1.20 && m1.bullishMomentum) longScore += 10
        if (m1.volumeRatio >= 1.20 && m1.bearishMomentum) shortScore += 10
        if (m1.rsi in 50.0..70.0) longScore += 10
        if (m1.rsi in 30.0..50.0) shortScore += 10
        if (m1.breakoutUp || m1.retestUp) longScore += 10
        if (m1.breakoutDown || m1.retestDown) shortScore += 10

        val dominantScore = max(longScore, shortScore).coerceIn(0, 100)
        val directionalBias = when {
            biasLong -> SignalAction.BUY
            biasShort -> SignalAction.SELL
            else -> SignalAction.HOLD
        }
        val entryAction = when {
            biasLong && setupLong && triggerLong && !extremeVolatility -> SignalAction.BUY
            biasShort && setupShort && triggerShort && !extremeVolatility -> SignalAction.SELL
            else -> SignalAction.HOLD
        }

        val stage = when {
            entryAction == SignalAction.BUY && longScore >= 70 -> ScalpingStage.STRONG_ENTRY
            entryAction == SignalAction.SELL && shortScore >= 70 -> ScalpingStage.STRONG_ENTRY
            entryAction == SignalAction.BUY || entryAction == SignalAction.SELL -> ScalpingStage.ENTRY
            biasLong && (extendedLong || setupLong && !triggerLong || !setupLong && m1.bearishMomentum) -> ScalpingStage.WAIT_PULLBACK
            biasShort && (extendedShort || setupShort && !triggerShort || !setupShort && m1.bullishMomentum) -> ScalpingStage.WAIT_PULLBACK
            directionalBias != SignalAction.HOLD -> ScalpingStage.WATCH
            dominantScore >= 45 -> ScalpingStage.WATCH
            else -> ScalpingStage.HOLD
        }

        if (extendedLong) reasons += "RSI 1H/15M panas: tunggu pullback."
        if (extendedShort) reasons += "RSI 1H/15M sangat rendah: tunggu pullback."
        if (extremeVolatility) reasons += "ATR 1M > 4%: entry ditahan."

        // —— Structured snapshot (expose existing booleans, no new thresholds) ——
        val biasDir = when {
            biasLong -> "bullish"
            biasShort -> "bearish"
            else -> "mixed"
        }
        val biasStatus = when {
            biasLong || biasShort -> MtfLegStatus.OK
            else -> MtfLegStatus.FAIL
        }
        val setupStatus = when {
            setupLong || setupShort -> MtfLegStatus.OK
            biasLong || biasShort -> MtfLegStatus.PARTIAL // bias ada, setup belum penuh = pullback/mixed
            else -> MtfLegStatus.FAIL
        }
        val triggerStatus = when {
            triggerLong || triggerShort -> MtfLegStatus.OK
            biasLong || biasShort || setupLong || setupShort -> MtfLegStatus.WAITING
            else -> MtfLegStatus.FAIL
        }

        val path = when (stage) {
            ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY -> ScalpingPath.ENTRY_READY
            ScalpingStage.WAIT_PULLBACK -> when {
                (biasLong || biasShort) && (setupLong || setupShort) && !triggerLong && !triggerShort ->
                    ScalpingPath.BOTH // bias+setup OK, tinggal trigger → pullback ATAU continuation
                extendedLong || extendedShort -> ScalpingPath.PULLBACK
                else -> ScalpingPath.PULLBACK
            }
            ScalpingStage.WATCH -> ScalpingPath.MOMENTUM_CONTINUATION
            ScalpingStage.HOLD -> ScalpingPath.NONE
        }

        val statusTitle = when (stage) {
            ScalpingStage.STRONG_ENTRY -> if (entryAction == SignalAction.SELL) "SHORT ENTRY KUAT" else "ENTRY KUAT"
            ScalpingStage.ENTRY -> if (entryAction == SignalAction.SELL) "SHORT ENTRY" else "ENTRY"
            ScalpingStage.WAIT_PULLBACK -> when {
                path == ScalpingPath.BOTH && biasLong -> "BULLISH MOMENTUM · MENUNGGU KONFIRMASI"
                path == ScalpingPath.BOTH && biasShort -> "BEARISH MOMENTUM · MENUNGGU KONFIRMASI"
                else -> "MENUNGGU PULLBACK"
            }
            ScalpingStage.WATCH -> "MENUNGGU KONFIRMASI"
            ScalpingStage.HOLD -> "BELUM TERSEDIA"
        }

        val waitingFor = when (stage) {
            ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY ->
                "Tidak ada yang ditunggu — kondisi entry terpenuhi."
            ScalpingStage.WAIT_PULLBACK -> when (path) {
                ScalpingPath.BOTH ->
                    "Dua jalur terbuka: pullback bersih ke area setup, atau trigger momentum 1M (volume/breakout/retest)."
                else ->
                    "Harga extended / setup belum rapi — tunggu koreksi ke area setup 15M."
            }
            ScalpingStage.WATCH ->
                "Bias atau setup mulai terbentuk. Trigger 1M belum cukup kuat."
            ScalpingStage.HOLD ->
                "Menunggu struktur 1H (bias) dan setup 15M terbentuk."
        }

        val entryCondition = when {
            extremeVolatility -> "ATR 1M terlalu tinggi (>4%). Tunggu volatilitas mereda."
            stage == ScalpingStage.ENTRY || stage == ScalpingStage.STRONG_ENTRY ->
                "Bias 1H + setup 15M + trigger 1M sudah searah."
            else ->
                "Butuh bias 1H + setup 15M + trigger 1M searah, volume/breakout valid, ATR tidak ekstrem."
        }

        val mtf = ScalpingMtfSnapshot(
            biasOk = biasLong || biasShort,
            biasDirection = biasDir,
            biasStatus = biasStatus,
            biasDetail = "RSI ${fmt(h1.rsi)} · ${if (biasLong) "bullish" else if (biasShort) "bearish" else "mixed"}",
            setupOk = setupLong || setupShort,
            setupStatus = setupStatus,
            setupDetail = "RSI ${fmt(m15.rsi)} · ${if (setupLong) "bullish setup" else if (setupShort) "bearish setup" else "pullback/mixed"}",
            triggerOk = triggerLong || triggerShort,
            triggerStatus = triggerStatus,
            triggerDetail = "RSI ${fmt(m1.rsi)} · vol ${fmt(m1.volumeRatio)}× · ${if (triggerLong) "long trigger" else if (triggerShort) "short trigger" else "belum trigger"}",
            path = path,
            statusTitle = statusTitle,
            waitingFor = waitingFor,
            entryCondition = entryCondition,
            extended = extendedLong || extendedShort,
            extremeVolatility = extremeVolatility
        )

        var sl = 0.0
        var tp1 = 0.0
        var tp2 = 0.0
        var stopDistance = 0.0
        var tp1Distance = 0.0
        var tp2Distance = 0.0
        var rr = "Belum ada posisi"

        if (entryAction != SignalAction.HOLD) {
            val rawStop = atr * 0.9
            val rawTp1 = atr * 1.4
            val rawTp2 = atr * 2.2
            val structure = MarketStructureAnalyzer.analyze(m1Candles.takeLast(min(40, m1Candles.size)))
            if (entryAction == SignalAction.BUY) {
                sl = price - rawStop
                tp1 = price + rawTp1
                tp2 = price + rawTp2
                val swingLow = structure.lastSwingLow ?: 0.0
                if (structure.dataEnough && swingLow > 0.0 && swingLow < price) sl = min(sl, swingLow - atr * 0.20)
                val resistance = structure.resistance ?: 0.0
                if (structure.dataEnough && resistance > price) tp1 = min(tp1, resistance)
            } else {
                sl = price + rawStop
                tp1 = price - rawTp1
                tp2 = price - rawTp2
                val swingHigh = structure.lastSwingHigh ?: 0.0
                if (structure.dataEnough && swingHigh > price) sl = max(sl, swingHigh + atr * 0.20)
                val support = structure.support ?: 0.0
                if (structure.dataEnough && support > 0.0 && support < price) tp1 = max(tp1, support)
            }
            stopDistance = abs(price - sl)
            tp1Distance = abs(tp1 - price)
            tp2Distance = abs(tp2 - price)
            val valid = stopDistance > 0.0 && stopDistance < price * 0.10 &&
                tp1Distance >= stopDistance && tp2Distance >= stopDistance * 1.5 &&
                if (entryAction == SignalAction.BUY) tp1 > price && tp2 > tp1 else tp1 < price && tp2 < tp1
            if (!valid) {
                reasons += "Entry dibatalkan: RR tidak layak."
                val watchMtf = mtf.copy(
                    statusTitle = "MENUNGGU KONFIRMASI",
                    path = ScalpingPath.MOMENTUM_CONTINUATION,
                    waitingFor = "Risk/reward belum layak. Tunggu setup dengan RR lebih baik.",
                    entryCondition = "Entry dibatalkan engine karena RR tidak valid."
                )
                return ScalpingMtfResult(
                    signal = holdState(
                        ScalpingStage.WATCH, dominantScore, price,
                        reasons + "STATUS: ${ScalpingStage.WATCH.displayName}.",
                        "Risk/reward tidak layak", watchMtf
                    ),
                    indicators = m1Indicators(m1)
                )
            }
            rr = "TP1 1:${fmt(tp1Distance / stopDistance)} | TP2 1:${fmt(tp2Distance / stopDistance)}"
        }

        val finalAction = if (stage == ScalpingStage.ENTRY || stage == ScalpingStage.STRONG_ENTRY) entryAction else SignalAction.HOLD
        val sentiment = when {
            finalAction == SignalAction.BUY -> TrendSentiment.STRONG_BULLISH_CONTINUATION
            finalAction == SignalAction.SELL -> TrendSentiment.BEARISH_DISTRIBUTION
            biasLong -> TrendSentiment.ACCUMULATION_SQUEEZE
            biasShort -> TrendSentiment.BEARISH_DISTRIBUTION
            else -> TrendSentiment.NEUTRAL_CONSOLIDATION
        }
        val actionScore = when {
            finalAction == SignalAction.BUY -> longScore
            finalAction == SignalAction.SELL -> shortScore
            else -> dominantScore
        }.coerceIn(0, 100)
        reasons += "STATUS: ${stage.displayName}."

        val signal = AISignalState(
            action = finalAction,
            confidence = actionScore,
            sentiment = sentiment,
            entryPrice = price,
            targetPrice1 = if (finalAction == SignalAction.HOLD) 0.0 else tp1,
            targetPrice2 = if (finalAction == SignalAction.HOLD) 0.0 else tp2,
            stopLoss = if (finalAction == SignalAction.HOLD) 0.0 else sl,
            riskRewardRatio = rr,
            probabilityScore = 0.0,
            patternDetected = null,
            reasoning = reasons.take(9),
            timestamp = System.currentTimeMillis(),
            scalpingStage = stage,
            mtf = mtf
        )
        return ScalpingMtfResult(signal = signal, indicators = m1Indicators(m1))
    }

    private fun m1Indicators(m1: FrameSignal): TechnicalIndicators {
        val momentum = if (m1.candles.size > 4) {
            val base = m1.candles[m1.candles.lastIndex - 4].close
            if (base > 0) (m1.price - base) / base else 0.0
        } else 0.0
        return TechnicalIndicators(
            rsi14 = m1.rsi,
            macd = m1.macdHist,
            macdSignal = 0.0,
            macdHist = m1.macdHist,
            ema20 = m1.emaFast,
            ema50 = m1.emaSlow,
            ema200 = Double.NaN,
            bbUpper = Double.NaN,
            bbLower = Double.NaN,
            atr = m1.atr,
            momentum = momentum
        )
    }

    private fun holdState(
        stage: ScalpingStage,
        score: Int,
        price: Double,
        reasons: List<String>,
        rr: String,
        mtf: ScalpingMtfSnapshot = ScalpingMtfSnapshot()
    ) = AISignalState(
        action = SignalAction.HOLD,
        confidence = score.coerceIn(0, 100),
        sentiment = if (stage == ScalpingStage.WAIT_PULLBACK) TrendSentiment.ACCUMULATION_SQUEEZE else TrendSentiment.NEUTRAL_CONSOLIDATION,
        entryPrice = price,
        targetPrice1 = 0.0,
        targetPrice2 = 0.0,
        stopLoss = 0.0,
        riskRewardRatio = rr,
        probabilityScore = 0.0,
        reasoning = reasons.take(9),
        timestamp = System.currentTimeMillis(),
        scalpingStage = stage,
        mtf = mtf
    )

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
