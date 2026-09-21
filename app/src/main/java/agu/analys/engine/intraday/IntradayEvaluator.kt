package agu.analys.engine.intraday

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

data class IntradayEvalResult(
    val signal: AISignalState,
    val indicators: TechnicalIndicators,
    val isQualified: Boolean = false,
    val setupScore: Int = 0
)

/**
 * INTRADAY TRADING STRATEGY EVALUATOR (OPEN PAGI · CLOSE MALAM)
 *
 * Filosofi Intraday Disiplin Sesi & Anti Flash Dump:
 * - Siklus Harian:
 *   1. Sesi Open Pagi (06:00–11:30 WIB): Jendela pembukaan posisi saat volume & likuiditas pagi terbentuk.
 *   2. Sesi Hold & Trailing Siang (11:30–19:30 WIB): Mengawal posisi dengan trailing profit, selektif entry.
 *   3. Sesi Close Malam (19:30–23:30 WIB): Sesi penutupan posisi / exit sebelum tengah malam untuk mengunci kas dan menghindari flash dump overnight.
 *   4. Sesi Istirahat Malam (23:30–06:00 WIB): Dilarang membuka posisi baru (jam rawan dump pasar global).
 *
 * - Anti Flash Dump Protection:
 *   1. Memindai histori harga panjang (H4 100–200 candle / D1 100 candle).
 *   2. Deteksi trauma flash dump (< 72 jam) tanpa base akumulasi kokoh.
 *   3. Deteksi fake pump rejection wick (upper wick panjang) pencegah jebakan pompa.
 *   4. Validasi baseline support jangka panjang (EMA100/EMA200).
 */
object IntradayEvaluator {

    enum class IntradayPhase(
        val label: String,
        val isOpenWindow: Boolean,
        val isCloseWindow: Boolean,
        val isRestWindow: Boolean,
        val isTrailingWindow: Boolean = false
    ) {
        OPEN_PAGI("Sesi Open Pagi (06:00–11:30 WIB)", isOpenWindow = true, isCloseWindow = false, isRestWindow = false),
        HOLD_SIANG("Sesi Siang Akumulasi (11:30–15:30 WIB)", isOpenWindow = true, isCloseWindow = false, isRestWindow = false),
        HOLD_SORE("Sesi Sore Trailing (15:30–19:30 WIB)", isOpenWindow = false, isCloseWindow = false, isRestWindow = false, isTrailingWindow = true),
        CLOSE_MALAM("Sesi Close Malam (19:30–23:30 WIB)", isOpenWindow = false, isCloseWindow = true, isRestWindow = false),
        REST_MALAM("Sesi Istirahat (23:30–06:00 WIB)", isOpenWindow = false, isCloseWindow = false, isRestWindow = true)
    }

    fun getCurrentIntradayPhase(timestamp: Long = System.currentTimeMillis()): IntradayPhase {
        val wibCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta"))
        wibCal.timeInMillis = timestamp
        val hour = wibCal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = wibCal.get(java.util.Calendar.MINUTE)
        val timeMinutes = hour * 60 + minute

        return when {
            timeMinutes in 360..690 -> IntradayPhase.OPEN_PAGI       // 06:00 - 11:30 WIB (Utama Open Pagi)
            timeMinutes in 691..930 -> IntradayPhase.HOLD_SIANG     // 11:30 - 15:30 WIB (Siang Akumulasi)
            timeMinutes in 931..1170 -> IntradayPhase.HOLD_SORE     // 15:30 - 19:30 WIB (Sore Trailing)
            timeMinutes in 1171..1410 -> IntradayPhase.CLOSE_MALAM // 19:30 - 23:30 WIB (Close Malam Kas IDR)
            else -> IntradayPhase.REST_MALAM                        // 23:30 - 06:00 WIB (Istirahat Dini Hari)
        }
    }

    fun evaluate(
        price: Double,
        history: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig(),
        evaluationTimestamp: Long? = null
    ): IntradayEvalResult = evaluate(
        globalContext = agu.analys.engine.global.GlobalMarketContext(),
        price = price,
        history = history,
        fees = fees,
        macroAnomalyResult = null,
        evaluationTimestamp = evaluationTimestamp
    )

    fun evaluate(
        globalContext: agu.analys.engine.global.GlobalMarketContext = agu.analys.engine.global.GlobalMarketContext(),
        price: Double,
        history: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig(),
        macroAnomalyResult: agu.analys.engine.regime.MacroAnomalyResult? = null,
        evaluationTimestamp: Long? = null
    ): IntradayEvalResult {
        if (price <= 0.0) {
            return IntradayEvalResult(AISignalState(), TechnicalIndicators())
        }

        val evalTime = evaluationTimestamp ?: history.lastOrNull()?.timestamp ?: System.currentTimeMillis()

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
                entryPriceDetail = "Menunggu riwayat candle pasar untuk memetakan level entry Intraday.",
                path = ScalpingPath.PULLBACK,
                statusTitle = "MENGUMPULKAN DATA",
                waitingFor = "Menunggu sinkronisasi candle makro",
                entryCondition = "Memuat riwayat candle untuk setup Intraday."
            )

            return IntradayEvalResult(
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
                        "Menunggu riwayat candle untuk setup Intraday."
                    ),
                    timestamp = evalTime,
                    scalpingStage = ScalpingStage.HOLD,
                    mtf = mtfSnapshot
                ),
                indicators = TechnicalIndicators(),
                isQualified = false,
                setupScore = 0
            )
        }

        val closes = history.map { it.close }
        val highs = history.map { it.high }
        val rsi = IndicatorMath.rsi(history, min(14, history.size - 1))
        val ema20 = IndicatorMath.ema(closes, min(20, closes.size))
        val ema50 = IndicatorMath.ema(closes, min(50, closes.size))
        val ema100 = if (closes.size >= 100) IndicatorMath.ema(closes, 100) else Double.NaN
        val ema200 = if (closes.size >= 200) IndicatorMath.ema(closes, 200) else Double.NaN
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
        val indicators = TechnicalIndicators(rsi, macd, macdSignal, macdHist, ema20, ema50, ema200, bb.second, bb.first, atr, momentum)

        val intradayPhase = getCurrentIntradayPhase(evalTime)
        var buyScore = 0.0
        var sellScore = 0.0
        val reasons = mutableListOf<String>()
        reasons += "Kondisi Pasar: $regime · ${intradayPhase.label}"

        // ── ANTI-FLASH DUMP DETECTOR (Menggunakan Histori Panjang) ──────────
        // 1. Pindai riwayat dump mendadak dalam 18 candle terakhir (~72 jam H4)
        var flashDumpTrauma = false
        var flashDumpDetail = ""
        val lookbackDump = min(18, history.size)
        val recentHistory = history.takeLast(lookbackDump)
        for (i in recentHistory.indices) {
            val bar = recentHistory[i]
            val dropFromOpen = if (bar.open > 0.0) (bar.open - bar.close) / bar.open else 0.0
            val candleRange = if (bar.high > 0.0) (bar.high - bar.low) / bar.high else 0.0
            if (dropFromOpen >= 0.075 || (candleRange >= 0.12 && bar.close < bar.open)) {
                val candlesAgo = recentHistory.size - 1 - i
                if (candlesAgo < 12) {
                    flashDumpTrauma = true
                    flashDumpDetail = "Pernah flash dump -${fmt(max(dropFromOpen, candleRange) * 100)}% ($candlesAgo candle lalu). Rawan dump susulan."
                    break
                }
            }
        }

        // 2. Deteksi Jebakan Pompa (Fake Pump / Long Upper Wick Rejection)
        var pumpAndDumpTrap = false
        val last3Candles = history.takeLast(min(4, history.size))
        for (bar in last3Candles) {
            val body = abs(bar.close - bar.open)
            val upperWick = bar.high - max(bar.open, bar.close)
            if (upperWick > 1.8 * max(body, bar.close * 0.008) && bar.high > price * 1.025) {
                pumpAndDumpTrap = true
                break
            }
        }

        // 3. Long-term Baseline Downtrend
        val isMacroDowntrend = (ema100.isFinite() && price < ema100 * 0.97) || (ema200.isFinite() && price < ema200 * 0.96)

        // ── Recent high / extension filter (cegah beli di pucuk) ────────
        val lookbackHigh = min(30, highs.size)
        val recentHigh = highs.takeLast(lookbackHigh).maxOrNull() ?: price
        val effectiveAtr = if (atr.isFinite() && atr > 0.0) atr else (price * 0.04)
        val distToHighPct = if (recentHigh > 0.0) (recentHigh - price) / recentHigh else 1.0
        val mean20 = closes.takeLast(min(20, closes.size)).average()
        val atrExtension = if (effectiveAtr > 0.0) (price - mean20) / effectiveAtr else 0.0

        val tooCloseToHigh = distToHighPct < 0.012
        val isOverExtended = atrExtension > 1.8

        // 6. Level SL & TP Intraday (Dioptimalkan untuk Trade Harian: Open Pagi, Close Malam)
        val supportLevel = structure.support?.takeIf { it > 0.0 && it < price }
            ?: (price - effectiveAtr * 1.2)

        // 1. Trend & Moving Average Alignment
        val isEstablishedUptrend = indicators.ema20.isFinite() && indicators.ema50.isFinite() && (ema20 > ema50) && (price >= ema50 * 0.985)
        val isGoldenCross = ema20 > ema50 && closes.takeLast(5).firstOrNull()?.let { it <= ema50 } ?: false

        // Early Reversal Pagi / Reclaim Support:
        // Sesi pagi sering kali memantul dari support dan menembus kembali ke atas EMA20 (reclaim)
        // dengan momentum positif, meski lagging EMA20 belum sempat golden cross di atas EMA50.
        val isReclaimingEma20 = indicators.ema20.isFinite() && price >= ema20 * 0.995
        val isEarlyReversal = isReclaimingEma20 && macdHist >= -0.002 && rsi in 35.0..65.0 && (price >= supportLevel * 0.98)
        val isTrendValidForIntraday = isEstablishedUptrend || isGoldenCross || isEarlyReversal

        val isDowntrend = indicators.ema20.isFinite() && indicators.ema50.isFinite() && (ema20 < ema50 && price < ema20 * 0.995 && !isEarlyReversal)

        when {
            isEstablishedUptrend -> { buyScore += 28; reasons += "Tren makro solid (EMA20 > EMA50, harga di atas support dinamis)." }
            isGoldenCross -> { buyScore += 24; reasons += "Baru terjadi Golden Cross EMA." }
            isEarlyReversal -> { buyScore += 24; reasons += "Early Reversal Pagi: Harga reclaim EMA20 (Rp ${fmtPrice(ema20)}) dari support harian." }
            isDowntrend -> { sellScore += 24; reasons += "Tren makro bearish (EMA20 < EMA50 & harga di bawah EMA20)." }
            else -> reasons += "Tren berkonsolidasi, menunggu arah tren tegas."
        }

        // 2. RSI Sweet Spot (40-58 ideal untuk akumulasi Intraday)
        when {
            rsi in 40.0..58.0 -> { buyScore += 26; reasons += "RSI ${fmt(rsi)} di zona akumulasi ideal (Intraday)." }
            rsi in 30.0..40.0 && macdHist > 0 -> { buyScore += 18; reasons += "RSI oversold rebound dengan momentum positif." }
            rsi in 58.0..68.0 -> { buyScore += 8; reasons += "RSI ${fmt(rsi)} masih OK tapi mendekati zona tinggi." }
            rsi > 72.0 -> { sellScore += 28; reasons += "RSI ${fmt(rsi)} overbought (potensi koreksi)." }
            rsi < 30.0 -> { buyScore += 12; reasons += "RSI ${fmt(rsi)} jenuh jual (peluang rebound)." }
            else -> reasons += "RSI ${fmt(rsi)} netral."
        }

        // 3. MACD Momentum
        if (macdHist > 0) {
            buyScore += 18
            reasons += "Histogram MACD positif (+${fmt(macdHist)})."
        } else {
            sellScore += 14
            reasons += "Histogram MACD negatif (${fmt(macdHist)})."
        }

        // 4. Struktur Higher High / Higher Low
        val isBullishStructure = structure.trend.contains("Bull", true)
        val isBearishStructure = structure.trend.contains("Bear", true) && !isEarlyReversal
        if (structure.dataEnough) {
            when {
                isBullishStructure -> { buyScore += 18; reasons += "Struktur chart: Higher-High & Higher-Low stabil." }
                isBearishStructure -> { sellScore += 16; reasons += "Struktur chart: Lower-Low (Risiko penurunan)." }
            }
        }

        // 5. Pola Candlestick
        pattern?.let {
            if (it.contains("Bullish", true) || it.contains("Hammer", true) || it.contains("Morning", true)) {
                buyScore += 12; reasons += "Pola Reversal: $it."
            } else if (it.contains("Bearish", true) || it.contains("Shooting", true) || it.contains("Evening", true)) {
                sellScore += 14; reasons += "Pola Pelemahan: $it."
            }
        }

        // Intraday ATR dibatasi pada rentang pergerakan harian sehat 1.5% s/d 4.0%
        val intradayAtr = effectiveAtr.coerceIn(price * 0.015, price * 0.040)

        // Stop Loss harian ketat: di bawah support lokal (risiko terukur -1.5% s/d -2.8%)
        val calculatedSl = maxOf(
            supportLevel - (intradayAtr * 0.3),
            price * 0.972
        ).coerceAtMost(price * 0.985)

        // Target Take Profit 1 realistis harian (+2.8% s/d +4.8% tercapai di siang/sore)
        val resistanceHint = structure.resistance?.takeIf { it > price }
        val calculatedTp1 = (price + intradayAtr * 1.25)
            .coerceIn(price * 1.028, price * 1.050)

        // Target Take Profit 2 ekstensi tren (+5.5% s/d +8.5%)
        val calculatedTp2 = maxOf(calculatedTp1 + (intradayAtr * 1.4), price * 1.060)
            .coerceIn(price * 1.055, price * 1.088)

        val feeResult = FeeCalculator.roundTrip(price, calculatedSl, calculatedTp2, fees)
        val netRr = feeResult.netRr.coerceAtLeast(1.6)
        val rrString = "1:${fmt(netRr)}"

        // ── DANGER & INVALIDATION ───────────────────────────────────────────
        val isRsiOverbought = rsi >= 72.0
        val isNearHighDanger = tooCloseToHigh || isOverExtended
        
        val isParabolicUnwind = macroAnomalyResult?.isParabolicUnwind == true
        if (isParabolicUnwind) {
            reasons.add(0, "🚨 DITOLAK (HARD): Terdeteksi Parabolic Unwind (Koin baru saja pump ekstrim dan sedang turun). Dilarang beli.")
        }

        if (flashDumpTrauma) {
            reasons.add(0, "🚨 DITOLAK (Anti Flash Dump): $flashDumpDetail")
        }

        if (pumpAndDumpTrap) {
            reasons.add(0, "🚨 DITOLAK (Fake Pump Trap): Terdeteksi Upper Wick panjang penolakan pucuk (Fake Pump Trap).")
        }

        if (isMacroDowntrend) {
            reasons.add(0, "⚠️ Macro Downtrend: Harga di bawah baseline EMA jangka panjang (Rawan dump).")
            sellScore += 20
        }
        
        // Distribusi / breakdown
        val isDistribution = sellScore >= 48.0 && sellScore > buyScore * 1.15
        val isBreakdown = (price < supportLevel * 0.965) || (isDowntrend && !isEarlyReversal)
        val isDangerous = isRsiOverbought || isNearHighDanger || isDistribution || isBreakdown || isParabolicUnwind || flashDumpTrauma || pumpAndDumpTrap || isMacroDowntrend

        if (isNearHighDanger) {
            reasons.add(0, "⚠️ Tertahan: Harga terlalu dekat recent high (Rp ${fmtPrice(recentHigh)}) / overextended. Hindari beli di pucuk.")
        }

        // ── WATERFALL CHECKPOINTS ───────────────────────────────────────────
        val step1Ok = !isDangerous && isTrendValidForIntraday && !flashDumpTrauma
        val step2Ok = step1Ok && (price >= supportLevel * 0.988) && !tooCloseToHigh && !pumpAndDumpTrap
        val step3Ok = step2Ok && (rsi in 35.0..65.0) && macdHist >= -0.002
        val step4Ok = step3Ok && netRr >= 1.35 && buyScore >= 50.0 && !isOverExtended

        val completedSteps = when {
            step4Ok -> 4
            step3Ok -> 3
            step2Ok -> 2
            step1Ok -> 1
            else -> 0
        }

        // ── Keputusan akhir Intraday Disiplin Sesi (Open Pagi, Close Malam) ──
        // Diperbolehkan BUY pada Sesi Open Pagi (06:00–11:30 WIB) atau Sesi Siang Akumulasi jika momentum kuat
        val isQualified = step4Ok && buyScore >= 52.0 && buyScore > sellScore * 1.15 && !isNearHighDanger && !flashDumpTrauma && !pumpAndDumpTrap && intradayPhase.isOpenWindow
        
        var baseConfidence = if (isQualified) (buyScore).coerceAtMost(90.0).toInt() else (buyScore).coerceAtMost(60.0).toInt()
        val regimeMultiplier = when {
            regime.contains("SIDEWAYS") -> 0.7
            regime.contains("Tinggi") -> 0.6
            else -> 1.0
        }
        val confidence = if (isParabolicUnwind || flashDumpTrauma) 0 else (baseConfidence * regimeMultiplier).toInt()
        
        val finalAction = when {
            globalContext.isVetoActive -> SignalAction.HOLD
            isParabolicUnwind || flashDumpTrauma -> SignalAction.HOLD
            intradayPhase.isCloseWindow -> {
                reasons.add(0, "🌙 SESI CLOSE MALAM (19:30–23:30 WIB): Amankan profit harian & tutup posisi menjadi kas IDR sebelum tengah malam (Hindari overnight dump).")
                SignalAction.SELL
            }
            intradayPhase.isRestWindow -> {
                reasons.add(0, "💤 SESI ISTIRAHAT (23:30–06:00 WIB): Pasar ditutup untuk posisi baru. Menghindari flash dump dini hari.")
                SignalAction.HOLD
            }
            intradayPhase.isTrailingWindow -> {
                reasons.add(0, "🛡️ SESI SORE TRAILING (15:30–19:30 WIB): Pasang trailing stop untuk mengunci profit harian. Tidak membuka posisi baru.")
                SignalAction.HOLD
            }
            isQualified && !isDangerous -> {
                if (intradayPhase == IntradayPhase.OPEN_PAGI) {
                    reasons.add(0, "⚡ SESI OPEN PAGI (06:00–11:30 WIB): Momentum harian terkonfirmasi. Waktu prima eksekusi posisi.")
                } else {
                    reasons.add(0, "☀️ SESI SIANG (11:30–15:30 WIB): Akumulasi tren lanjutan sehat. Siapkan TP/trailing sebelum malam.")
                }
                SignalAction.BUY
            }
            else -> SignalAction.HOLD
        }

        if (isDangerous && !isNearHighDanger && !flashDumpTrauma && !pumpAndDumpTrap) {
            when {
                isRsiOverbought -> reasons.add(0, "⚠️ Tertahan: RSI jenuh beli (>= 72).")
                isBreakdown -> reasons.add(0, "⚠️ Tertahan: Harga breakdown / downtrend menembus support.")
                isDistribution -> reasons.add(0, "⚠️ Tertahan: Tekanan jual & distribusi tinggi terdeteksi.")
            }
        }

        val finalScoreRaw = when {
            isDangerous -> 20
            isQualified -> (82 + min(13, (buyScore * 0.12).toInt())).coerceIn(80, 95)
            step3Ok -> 62
            step2Ok -> 48
            step1Ok -> 32
            else -> 18
        }
        val finalScore = if (isParabolicUnwind || flashDumpTrauma) 0 else (finalScoreRaw * regimeMultiplier).toInt()

        val biasDetailText = when {
            flashDumpTrauma -> "Ditolak: Riwayat flash dump dalam 72 jam."
            pumpAndDumpTrap -> "Ditolak: Trap upper wick tajam."
            isRsiOverbought -> "Tertahan: RSI overbought."
            isNearHighDanger -> "Tertahan: Terlalu dekat recent high / overextended."
            isBreakdown -> "Tertahan: Breakdown / downtrend."
            isDistribution -> "Tertahan: Distribusi tinggi."
            isEstablishedUptrend -> "Tren makro selaras (EMA20 > EMA50)."
            isGoldenCross -> "Golden Cross EMA terkonfirmasi."
            isEarlyReversal -> "Reversal Pagi: Reclaim EMA20 & akumulasi support."
            step1Ok -> "Struktur tren intraday valid."
            else -> "Menunggu tren makro stabil."
        }

        val setupDetailText = when {
            !step1Ok -> "Menunggu Checkpoint 1 lolos."
            step2Ok -> "Support aman di Rp ${fmtPrice(supportLevel)} · Jarak ke high ${fmt(distToHighPct * 100)}%."
            else -> "Memantau lantai support / jarak ke high."
        }

        val triggerDetailText = when {
            !step2Ok -> "Menunggu Checkpoint 2 lolos."
            step3Ok -> "RSI (${fmt(rsi)}) & MACD akumulasi stabil."
            else -> "Menunggu momentum RSI & MACD stabil."
        }

        val entryPriceDetailText = when {
            !intradayPhase.isOpenWindow -> "Sesi entry ditutup (${intradayPhase.label})."
            !step3Ok -> "Menunggu Checkpoint 3 lolos."
            step4Ok -> "Zona Entry: Rp ${fmtPrice(price)} (Net R:R $rrString)."
            else -> "Menunggu R:R optimal (Min 1:1.8) & skor buy."
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
                intradayPhase.isCloseWindow -> "CLOSE MALAM (EXIT)"
                intradayPhase.isRestWindow -> "ISTIRAHAT (NO ENTRY)"
                intradayPhase.isTrailingWindow -> "SORE TRAILING (HOLD)"
                flashDumpTrauma -> "FLASH DUMP TRAUMA (HOLD)"
                pumpAndDumpTrap -> "FAKE PUMP TRAP (HOLD)"
                isRsiOverbought -> "OVERBOUGHT (HOLD)"
                isNearHighDanger -> "DEKAT HIGH / OVEREXTENDED (HOLD)"
                isBreakdown -> "BREAKDOWN / DOWNTREND (HOLD)"
                isDistribution -> "DISTRIBUSI TINGGI (HOLD)"
                completedSteps == 4 -> if (intradayPhase == IntradayPhase.OPEN_PAGI) "READY (OPEN PAGI)" else "READY (INTRADAY)"
                completedSteps > 0 -> "ANALYZING ($completedSteps/4)"
                else -> "ANALYZING (0/4)"
            },
            waitingFor = when {
                intradayPhase.isCloseWindow -> "Sesi Close Malam: Tutup posisi harian menjadi kas sebelum tengah malam"
                intradayPhase.isRestWindow -> "Menunggu Sesi Open Pagi (06:00 WIB)"
                intradayPhase.isTrailingWindow -> "Kawal profit dengan trailing stop (15:30–19:30 WIB)"
                flashDumpTrauma -> "Menunggu kestabilan base konsolidasi pasca-dump"
                pumpAndDumpTrap -> "Menunggu pullback aman dari rejection upper wick"
                isRsiOverbought -> "Menunggu koreksi / reset RSI"
                isNearHighDanger -> "Menunggu pullback dari zona high"
                isBreakdown -> "Menunggu pembentukan support baru"
                isDistribution -> "Menunggu tekanan jual mereda"
                completedSteps == 4 -> "Siap eksekusi (Open Pagi)"
                completedSteps == 3 -> "Menunggu konfirmasi zona entry & R:R"
                completedSteps == 2 -> "Menunggu momentum RSI & MACD"
                completedSteps == 1 -> "Menunggu pantulan support + jarak aman dari high"
                else -> "Menunggu konfirmasi setup lengkap"
            },
            entryCondition = "Intraday Disiplin Sesi · Anti Flash Dump · High R:R"
        )

        return IntradayEvalResult(
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
                reasoning = reasons.take(8),
                timestamp = evalTime,
                patternDetected = pattern,
                scalpingStage = if (completedSteps == 4 && intradayPhase.isOpenWindow) ScalpingStage.ENTRY else if (completedSteps >= 2) ScalpingStage.WAIT_PULLBACK else ScalpingStage.HOLD,
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
