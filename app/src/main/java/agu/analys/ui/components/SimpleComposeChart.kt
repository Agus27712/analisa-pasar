package agu.analys.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CandleBar
import agu.analys.ui.theme.TvCardBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import kotlin.math.max
import kotlin.math.min

@Composable
fun SimpleComposeChart(
    prices: List<Double>,
    currentPrice: Double,
    isPositiveTrend: Boolean = true,
    candles: List<CandleBar> = emptyList(),
    modifier: Modifier = Modifier
) {
    val validCandles = candles.filter { it.open > 0 && it.high > 0 && it.low > 0 && it.close > 0 }
    val sourceLabel = if (validCandles.size >= 2) "INDODAX · ${validCandles.size} CANDLE" else "MENUNGGU CANDLE INDODAX"
    val themeColor = if (isPositiveTrend) TvGreen else TvRed
    val livePrice = currentPrice.takeIf { it > 0 }

    val initialVisible = min(60, max(20, validCandles.size)).toFloat()
    var visibleCount by remember { mutableFloatStateOf(initialVisible) }
    var startIndex by remember { mutableFloatStateOf(max(0, validCandles.size - initialVisible.toInt()).toFloat()) }

    Card(
        modifier = modifier.fillMaxWidth().border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp)).testTag("simple_compose_chart"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground)
    ) {
        Column(modifier = Modifier.padding(10.dp).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).background(themeColor, CircleShape))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("CANDLE · $sourceLabel", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PINCH / GESER", fontSize = 8.sp, color = TvTextSecondary)
                    livePrice?.let { Spacer(modifier = Modifier.width(8.dp)); Text("LIVE ${PriceFormatter.formatPrice(it)}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary) }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF121212))
                    .pointerInput(validCandles.size) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (validCandles.size < 2) return@detectTransformGestures
                            val left = 10f
                            val right = 70f
                            val chartWidth = (size.width - left - right).coerceAtLeast(1f)
                            val maxVisible = validCandles.size.toFloat()
                            val minVisible = min(20f, maxVisible)
                            val oldVisible = visibleCount.coerceIn(minVisible, maxVisible)
                            val fraction = ((centroid.x - left) / chartWidth).coerceIn(0f, 1f)
                            val focalIndex = startIndex + fraction * oldVisible
                            val newVisible = (oldVisible / zoom).coerceIn(minVisible, maxVisible)
                            visibleCount = newVisible
                            val zoomedStart = focalIndex - fraction * newVisible
                            val panCandles = -(pan.x / chartWidth) * newVisible
                            val maxStart = (validCandles.size - newVisible).coerceAtLeast(0f)
                            startIndex = (zoomedStart + panCandles).coerceIn(0f, maxStart)
                        }
                    }
                    .pointerInput(validCandles.size) { detectTapGestures(onDoubleTap = { visibleCount = initialVisible.coerceAtMost(validCandles.size.toFloat()); startIndex = (validCandles.size - visibleCount).coerceAtLeast(0f) }) }
            ) {
                val safeVisible = visibleCount.coerceIn(2f, validCandles.size.coerceAtLeast(2).toFloat())
                val safeStart = startIndex.coerceIn(0f, (validCandles.size - safeVisible).coerceAtLeast(0f))
                val from = safeStart.toInt().coerceIn(0, (validCandles.size - 1).coerceAtLeast(0))
                val to = (safeStart + safeVisible).toInt().coerceIn(from + 1, validCandles.size)
                val visible = validCandles.subList(from, to)

                // Chart overlays use the same visible INDODAX OHLC candles.
                val closes = visible.map { it.close }
                fun sma(index: Int, period: Int): Double? {
                    if (index + 1 < period) return null
                    return closes.subList(index - period + 1, index + 1).average()
                }
                fun emaSeries(period: Int): List<Double?> {
                    val out = MutableList<Double?>(closes.size) { null }
                    if (closes.size < period) return out
                    var ema = closes.take(period).average()
                    out[period - 1] = ema
                    val k = 2.0 / (period + 1.0)
                    for (i in period until closes.size) {
                        ema = closes[i] * k + ema * (1.0 - k)
                        out[i] = ema
                    }
                    return out
                }
                val ema20 = emaSeries(20)
                val ema50 = emaSeries(50)
                val ema200 = emaSeries(200)
                val bbMid = List<Double?>(closes.size) { i -> sma(i, 20) }
                val bbUpper = List<Double?>(closes.size) { i ->
                    val mid = bbMid[i] ?: return@List null
                    val start = i - 19
                    val mean = closes.subList(start, i + 1).average()
                    val variance = closes.subList(start, i + 1).map { (it - mean) * (it - mean) }.average()
                    mid + 2.0 * kotlin.math.sqrt(variance)
                }
                val bbLower = List<Double?>(closes.size) { i ->
                    val mid = bbMid[i] ?: return@List null
                    val start = i - 19
                    val mean = closes.subList(start, i + 1).average()
                    val variance = closes.subList(start, i + 1).map { (it - mean) * (it - mean) }.average()
                    mid - 2.0 * kotlin.math.sqrt(variance)
                }

                val rawMin = visible.minOfOrNull { it.low } ?: currentPrice
                val rawMax = visible.maxOfOrNull { it.high } ?: currentPrice
                val overlayValues = (ema20 + ema50 + ema200 + bbUpper + bbLower).filterNotNull()
                val minPrice = min(rawMin, overlayValues.minOrNull() ?: rawMin)
                val maxPrice = max(rawMax, overlayValues.maxOrNull() ?: rawMax)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (visible.size < 2 || maxPrice <= minPrice) return@Canvas
                    val left = 10f
                    val right = 70f
                    val top = 10f
                    val bottom = 16f
                    val chartWidth = (size.width - left - right).coerceAtLeast(1f)
                    val chartHeight = (size.height - top - bottom).coerceAtLeast(1f)
                    val range = maxPrice - minPrice
                    fun y(price: Double): Float = top + ((maxPrice - price) / range).toFloat() * chartHeight
                    val step = chartWidth / visible.size
                    val bodyWidth = (step * 0.58f).coerceIn(2f, 14f)
                    fun drawSeries(series: List<Double?>, alpha: Float = 0.9f) {
                        var previous: Offset? = null
                        series.forEachIndexed { i, value ->
                            if (value != null) {
                                val point = Offset(left + step * i + step / 2f, y(value))
                                previous?.let { drawLine(Color.White.copy(alpha = alpha), it, point, strokeWidth = 1.5f) }
                                previous = point
                            } else previous = null
                        }
                    }
                    visible.forEachIndexed { index, candle ->
                        val x = left + step * index + step / 2f
                        val openY = y(candle.open); val closeY = y(candle.close); val highY = y(candle.high); val lowY = y(candle.low)
                        val candleColor = if (candle.close >= candle.open) TvGreen else TvRed
                        drawLine(candleColor, Offset(x, highY), Offset(x, lowY), strokeWidth = 1.5f)
                        val bodyTop = min(openY, closeY)
                        val bodyBottom = max(openY, closeY).coerceAtLeast(bodyTop + 2f)
                        drawRect(candleColor, topLeft = Offset(x - bodyWidth / 2f, bodyTop), size = androidx.compose.ui.geometry.Size(bodyWidth, bodyBottom - bodyTop))
                    }
                    drawSeries(bbUpper, 0.55f)
                    drawSeries(bbMid, 0.45f)
                    drawSeries(bbLower, 0.55f)
                    drawSeries(ema20, 0.95f)
                    drawSeries(ema50, 0.95f)
                    drawSeries(ema200, 0.95f)
                    livePrice?.let { price -> if (price in minPrice..maxPrice) { val liveY = y(price); drawLine(Color.White.copy(alpha = 0.65f), Offset(left, liveY), Offset(left + chartWidth, liveY), strokeWidth = 1f) } }
                }
                Column(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(PriceFormatter.formatPrice(maxPrice), fontSize = 8.sp, color = TvGreen, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Text(PriceFormatter.formatPrice((maxPrice + minPrice) / 2.0), fontSize = 8.sp, color = TvTextSecondary, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Text(PriceFormatter.formatPrice(minPrice), fontSize = 8.sp, color = TvRed, maxLines = 1)
                }
                Text("EMA 20 · 50 · 200  |  BOLL 20,2", modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 5.dp), fontSize = 7.sp, color = TvTextSecondary)
                Text("Double tap = reset", modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 5.dp), fontSize = 7.sp, color = TvTextSecondary)
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text("Pinch untuk zoom · geser untuk melihat candle lama · double tap untuk kembali ke candle terbaru. Data OHLC dan indikator dihitung dari candle INDODAX.", fontSize = 8.sp, color = TvTextSecondary, lineHeight = 11.sp)
        }
    }
}
