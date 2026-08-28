package agu.analys.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import agu.analys.model.CandleBar
import agu.analys.ui.components.chart.LightweightChartView

/**
 * Chart entry-point for detail / non-fullscreen.
 * Delegates to Lightweight Charts with Indodax candle data (no synthetic prices).
 * Signature kept for backward compatibility with existing call sites.
 */
@Composable
fun SimpleComposeChart(
    prices: List<Double>,
    currentPrice: Double,
    isPositiveTrend: Boolean = true,
    candles: List<CandleBar> = emptyList(),
    entryPrice: Double = 0.0,
    targetPrice1: Double = 0.0,
    targetPrice2: Double = 0.0,
    stopLoss: Double = 0.0,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    LightweightChartView(
        candles = candles,
        entryPrice = entryPrice,
        targetPrice1 = targetPrice1,
        targetPrice2 = targetPrice2,
        stopLoss = stopLoss,
        modifier = modifier
    )
}
