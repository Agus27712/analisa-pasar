package agu.analys.ui.components.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Layout boundary for the detail-screen chart.
 *
 * Keep chart sizing/layout decisions here so DetailChartScreen does not need
 * to know the visual dimensions of the chart. Chart rendering and market logic
 * stay in SimpleComposeChart and the trading engine respectively.
 *
 * The first UI pass intentionally keeps this component small. Future chart
 * polish (larger area, smooth price movement, and subtle micro-animation)
 * should be implemented here without touching the Scalping/MTF engine.
 */
@Composable
fun ChartLayout(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 340.dp,
    content: @Composable (Modifier) -> Unit
) {
    content(
        modifier
            .fillMaxWidth()
            .height(height)
    )
}
