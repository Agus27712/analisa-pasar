package agu.analys.engine.backtest

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar

data class WalkForwardReport(
    val inSampleWinRatePct: Double,
    val outOfSampleWinRatePct: Double,
    val inSampleProfitFactor: Double,
    val outOfSampleProfitFactor: Double,
    val walkForwardEfficiencyPct: Double, // Rasio Out-Of-Sample vs In-Sample
    val overallScore: Int, // 0 - 100 confidence score
    val isOverfitted: Boolean,
    val summaryMessage: String
)

object WalkForwardEvaluator {

    /**
     * Lakukan Walk-Forward Validation dengan membagi dataset historis menjadi:
     * - In-Sample (60% data awal)
     * - Out-Of-Sample (40% data akhir)
     * Ini mendeteksi apakah sinyal berpotensi Overfitting di market sideways / volatile.
     */
    fun validate(
        candles: List<CandleBar>,
        feeConfig: TradingFeeConfig = TradingFeeConfig()
    ): WalkForwardReport {
        if (candles.size < 60) {
            return WalkForwardReport(
                inSampleWinRatePct = 0.0,
                outOfSampleWinRatePct = 0.0,
                inSampleProfitFactor = 0.0,
                outOfSampleProfitFactor = 0.0,
                walkForwardEfficiencyPct = 0.0,
                overallScore = 50,
                isOverfitted = false,
                summaryMessage = "Data historis terbatas (${candles.size} candle) — butuh minimal 60 bar untuk walk-forward."
            )
        }

        val splitIndex = (candles.size * 0.60).toInt()
        val inSampleCandles = candles.subList(0, splitIndex)
        val outOfSampleCandles = candles.subList(splitIndex, candles.size)

        val inSampleRes = BacktestEngine.runBacktest(inSampleCandles, feeConfig)
        val outOfSampleRes = BacktestEngine.runBacktest(outOfSampleCandles, feeConfig)

        val isPf = if (inSampleRes.profitFactor > 0) inSampleRes.profitFactor else 1.0
        val oosPf = if (outOfSampleRes.profitFactor > 0) outOfSampleRes.profitFactor else 1.0

        val efficiency = (oosPf / isPf * 100.0).coerceIn(0.0, 200.0)
        val isOverfitted = efficiency < 50.0 || (inSampleRes.winRatePct - outOfSampleRes.winRatePct) > 25.0

        // Hitung overall confidence score
        var score = 60
        if (outOfSampleRes.winRatePct >= 50.0) score += 15
        if (outOfSampleRes.profitFactor >= 1.3) score += 15
        if (outOfSampleRes.maxDrawdownPct <= 10.0) score += 10
        if (isOverfitted) score -= 25

        score = score.coerceIn(10, 100)

        val message = when {
            isOverfitted -> "Peringatan Overfit: Performa Out-of-Sample turun >50% dibanding In-Sample. Sinyal diperketat."
            score >= 75 -> "Validasi Walk-Forward Sangat Kuat: Performa stabil di data In-Sample & Out-of-Sample."
            score >= 50 -> "Validasi Walk-Forward Cukup: Konsistensi sinyal moderat di histori terbaru."
            else -> "Validasi Walk-Forward Lemah: Volatilitas tinggi / market sideways mengurangi keandalan."
        }

        return WalkForwardReport(
            inSampleWinRatePct = inSampleRes.winRatePct,
            outOfSampleWinRatePct = outOfSampleRes.winRatePct,
            inSampleProfitFactor = inSampleRes.profitFactor,
            outOfSampleProfitFactor = outOfSampleRes.profitFactor,
            walkForwardEfficiencyPct = efficiency,
            overallScore = score,
            isOverfitted = isOverfitted,
            summaryMessage = message
        )
    }
}
