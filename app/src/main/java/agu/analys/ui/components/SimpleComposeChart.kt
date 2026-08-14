package agu.analys.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun SimpleComposeChart(
    prices: List<Double>,
    currentPrice: Double,
    isPositiveTrend: Boolean = true,
    candles: List<CandleBar> = emptyList(),
    modifier: Modifier = Modifier
) {
    val validCandles = remember(candles) {
        candles.filter { it.open > 0 && it.high > 0 && it.low > 0 && it.close > 0 }
    }
    val candleCount = validCandles.size
    val defaultVisible = minOf(80, candleCount.coerceAtLeast(2))
    var visibleCount by remember { mutableIntStateOf(defaultVisible) }
    var startIndex by remember { mutableIntStateOf((candleCount - defaultVisible).coerceAtLeast(0)) }
    var gestureZoom by remember { mutableFloatStateOf(1f) }

    var showEma20 by remember { mutableStateOf(true) }
    var showEma50 by remember { mutableStateOf(true) }
    var showEma200 by remember { mutableStateOf(false) }
    var showBb by remember { mutableStateOf(true) }

    LaunchedEffect(candleCount) {
        if (candleCount <= 0) {
            startIndex = 0
            visibleCount = 0
        } else {
            val target = visibleCount.coerceIn(20.coerceAtMost(candleCount), candleCount)
            visibleCount = target
            startIndex = (candleCount - target).coerceAtLeast(0)
        }
    }

    val endIndex = (startIndex + visibleCount).coerceIn(0, candleCount)
    val visible = if (startIndex < endIndex) validCandles.subList(startIndex, endIndex) else emptyList()

    val closePrices = remember(validCandles) { validCandles.map { it.close } }
    val ema20 = remember(closePrices) { emaSeries(closePrices, 20) }
    val ema50 = remember(closePrices) { emaSeries(closePrices, 50) }
    val ema200 = remember(closePrices) { emaSeries(closePrices, 200) }
    val bb = remember(closePrices) { bollingerSeries(closePrices, 20) }

    val minPrice = visible.minOfOrNull { minOf(it.low, bb.first.getOrNull(startIndex) ?: it.low) } ?: currentPrice
    val maxPrice = visible.maxOfOrNull { maxOf(it.high, bb.second.getOrNull(startIndex) ?: it.high) } ?: currentPrice
    val themeColor = if (isPositiveTrend) TvGreen else TvRed
    val livePrice = currentPrice.takeIf { it > 0 }

    Card(
        modifier = modifier.fillMaxWidth().border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground)
    ) {
        Column(modifier = Modifier.padding(10.dp).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).background(themeColor, CircleShape))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("GRAFIK HARGA · INDODAX", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
                }
                if (livePrice != null) Text("LIVE ${PriceFormatter.formatPrice(livePrice)}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IndicatorChip("EMA 20", Color(0xFFFFD54F), showEma20) { showEma20 = !showEma20 }
                IndicatorChip("EMA 50", Color(0xFF42A5F5), showEma50) { showEma50 = !showEma50 }
                IndicatorChip("EMA 200", Color(0xFFAB47BC), showEma200) { showEma200 = !showEma200 }
                IndicatorChip("BB", Color.White.copy(alpha = 0.7f), showBb) { showBb = !showBb }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF121212))
                    .pointerInput(candleCount) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (candleCount < 2) return@detectTransformGestures
                            val nextCount = (visibleCount / zoom.coerceIn(0.65f, 1.45f)).roundToInt().coerceIn(20.coerceAtMost(candleCount), candleCount)
                            val indexShift = (-pan.x / 14f).roundToInt()
                            val maxStart = (candleCount - nextCount).coerceAtLeast(0)
                            val nextStart = (startIndex + indexShift).coerceIn(0, maxStart)
                            visibleCount = nextCount
                            startIndex = nextStart
                            gestureZoom = (gestureZoom * zoom).coerceIn(0.5f, 4f)
                        }
                    }
            ) {
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
                    fun linePoint(value: Double, index: Int): Offset = Offset(left + step * index + step / 2f, y(value))
                    fun drawSeries(values: List<Double>, color: Color) {
                        var previous: Offset? = null
                        visible.indices.forEach { localIndex ->
                            val sourceIndex = startIndex + localIndex
                            val value = values.getOrNull(sourceIndex) ?: return@forEach
                            if (!value.isFinite() || value <= 0.0) return@forEach
                            val point = linePoint(value, localIndex)
                            previous?.let { drawLine(color, it, point, strokeWidth = 2f) }
                            previous = point
                        }
                    }

                    visible.forEachIndexed { index, candle ->
                        val x = left + step * index + step / 2f
                        val openY = y(candle.open)
                        val closeY = y(candle.close)
                        val highY = y(candle.high)
                        val lowY = y(candle.low)
                        val candleColor = if (candle.close >= candle.open) TvGreen else TvRed
                        drawLine(candleColor, Offset(x, highY), Offset(x, lowY), strokeWidth = 1.5f)
                        val bodyTop = minOf(openY, closeY)
                        val bodyBottom = maxOf(openY, closeY).coerceAtLeast(bodyTop + 2f)
                        drawRect(candleColor, topLeft = Offset(x - bodyWidth / 2f, bodyTop), size = androidx.compose.ui.geometry.Size(bodyWidth, bodyBottom - bodyTop))
                    }

                    if (showEma20) drawSeries(ema20, Color(0xFFFFD54F))
                    if (showEma50) drawSeries(ema50, Color(0xFF42A5F5))
                    if (showEma200) drawSeries(ema200, Color(0xFFAB47BC))
                    if (showBb) {
                        drawSeries(bb.first, Color.White.copy(alpha = 0.35f))
                        drawSeries(bb.second, Color.White.copy(alpha = 0.35f))
                        drawSeries(bb.third, Color.White.copy(alpha = 0.25f))
                    }

                    livePrice?.let { price ->
                        if (price in minPrice..maxPrice) {
                            val liveY = y(price)
                            drawLine(Color.White.copy(alpha = 0.65f), Offset(left, liveY), Offset(left + chartWidth, liveY), strokeWidth = 1f)
                        }
                    }
                }
                Column(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(PriceFormatter.formatPrice(maxPrice), fontSize = 8.sp, color = TvGreen, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Text(PriceFormatter.formatPrice((maxPrice + minPrice) / 2.0), fontSize = 8.sp, color = TvTextSecondary, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Text(PriceFormatter.formatPrice(minPrice), fontSize = 8.sp, color = TvRed, maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text("Geser untuk lihat candle • Cubit zoom • Tap chip untuk on/off indikator", fontSize = 8.sp, color = TvTextSecondary, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun IndicatorChip(label: String, color: Color, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) color.copy(alpha = 0.18f) else Color(0xFF1A1A1A)
    val border = if (active) color.copy(alpha = 0.7f) else Color(0x33FFFFFF)
    val textColor = if (active) color else TvTextSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textColor)
    }
}

private fun emaSeries(values: List<Double>, period: Int): List<Double> {
    if (values.isEmpty()) return emptyList()
    val result = MutableList(values.size) { Double.NaN }
    if (values.size < period) return result
    var ema = values.take(period).average()
    result[period - 1] = ema
    val multiplier = 2.0 / (period + 1)
    for (i in period until values.size) {
        ema = (values[i] - ema) * multiplier + ema
        result[i] = ema
    }
    return result
}

private fun bollingerSeries(values: List<Double>, period: Int): Triple<List<Double>, List<Double>, List<Double>> {
    val upper = MutableList(values.size) { Double.NaN }
    val middle = MutableList(values.size) { Double.NaN }
    val lower = MutableList(values.size) { Double.NaN }
    if (values.size < period) return Triple(upper, middle, lower)
    for (i in period - 1 until values.size) {
        val window = values.subList(i - period + 1, i + 1)
        val mean = window.average()
        val variance = window.sumOf { (it - mean) * (it - mean) } / period
        val deviation = sqrt(variance)
        middle[i] = mean
        upper[i] = mean + 2.0 * deviation
        lower[i] = mean - 2.0 * deviation
    }
    return Triple(upper, middle, lower)
}
