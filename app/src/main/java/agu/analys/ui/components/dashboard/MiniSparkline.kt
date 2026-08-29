package agu.analys.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import agu.analys.model.MarketTick
import kotlin.math.abs

/**
 * Mini sparkline dari field tick real: price, high24h, low24h, change24h.
 * Bukan random walk — path disintesis dari range 24H Indodax.
 */
@Composable
fun MiniSparkline(
    tick: MarketTick?,
    modifier: Modifier = Modifier,
    lineColor: Color
) {
    val points = sparkPoints(tick)
    Canvas(modifier = modifier.fillMaxSize()) {
        if (points.size < 2) return@Canvas
        val minV = points.minOrNull() ?: return@Canvas
        val maxV = points.maxOrNull() ?: return@Canvas
        val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
        val w = size.width
        val h = size.height
        val pad = 2f
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = pad + (w - 2 * pad) * i / (points.size - 1).toFloat()
            val y = pad + (h - 2 * pad) * (1f - ((v - minV) / range).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.6f, cap = StrokeCap.Round)
        )
        val last = points.last()
        val lastX = pad + (w - 2 * pad)
        val lastY = pad + (h - 2 * pad) * (1f - ((last - minV) / range).toFloat())
        drawCircle(color = lineColor, radius = 3.8f, center = Offset(lastX, lastY))
    }
}

/**
 * Bangun 6 titik dari data 24H nyata:
 * open≈price/(1+chg), low, mid, high, near-close, price.
 */
private fun sparkPoints(tick: MarketTick?): List<Double> {
    if (tick == null || tick.price <= 0) return emptyList()
    val price = tick.price
    val high = tick.high24h.takeIf { it > 0 } ?: price
    val low = tick.low24h.takeIf { it > 0 } ?: price
    val chg = tick.change24h.takeIf { it.isFinite() } ?: 0.0
    val open = if (abs(chg) < 99.0) price / (1.0 + chg / 100.0) else price
    val mid = (high + low) / 2.0
    // Urutan kasar intraday dari open → ekstrem → close (semua dari field real)
    return listOf(
        open,
        open * 0.4 + mid * 0.6,
        if (chg >= 0) low else high,
        mid,
        if (chg >= 0) high else low,
        price
    ).map { it.coerceIn(minOf(low, open, price), maxOf(high, open, price)) }
}
