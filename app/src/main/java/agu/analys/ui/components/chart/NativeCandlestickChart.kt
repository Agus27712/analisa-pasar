package agu.analys.ui.components.chart

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CandleBar
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun NativeCandlestickChart(
    candles: List<CandleBar>,
    currentPrice: Double,
    showVolume: Boolean = true,
    showEma: Boolean = false,
    showBb: Boolean = false,
    showStochRsi: Boolean = false,
    entryPrice: Double = 0.0,
    targetPrice1: Double = 0.0,
    targetPrice2: Double = 0.0,
    stopLoss: Double = 0.0,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    var selectedCandleIndex by remember { mutableStateOf<Int?>(null) }
    var touchX by remember { mutableStateOf<Float?>(null) }

    val upColor = TvGreen
    val downColor = TvRed
    val blueColor = TvBlue
    val greenLightColor = TvGreenLight
    val gridColor = TvBorder.copy(alpha = 0.4f)
    val textMutedColor = TvTextMuted
    val textColor = TvTextPrimary
    val cardBg = TvCardBackground

    val displayCandles = remember(candles, currentPrice) {
        if (candles.isEmpty()) emptyList()
        else {
            val list = candles.toMutableList()
            if (currentPrice > 0.0) {
                val last = list.last()
                list[list.lastIndex] = last.copy(
                    high = max(last.high, currentPrice),
                    low = min(last.low, currentPrice),
                    close = currentPrice
                )
            }
            list.takeLast(60) // Show last 60 candles for clarity
        }
    }

    val activeCandle = selectedCandleIndex?.let { idx ->
        displayCandles.getOrNull(idx)
    } ?: displayCandles.lastOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
    ) {
        // Top OHLC Info Bar
        if (activeCandle != null) {
            val isGreen = activeCandle.close >= activeCandle.open
            val candleColor = if (isGreen) upColor else downColor
            val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
            val timeStr = remember(activeCandle.timestamp) {
                val ts = if (activeCandle.timestamp < 10000000000L) activeCandle.timestamp * 1000L else activeCandle.timestamp
                timeFormat.format(Date(ts))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("T: $timeStr", fontSize = 10.5.sp, color = textMutedColor)
                    Text("O: ${PriceFormatter.formatPrice(activeCandle.open, showSymbol = false, quoteAsset = quoteAsset)}", fontSize = 10.5.sp, color = textMutedColor)
                    Text("H: ${PriceFormatter.formatPrice(activeCandle.high, showSymbol = false, quoteAsset = quoteAsset)}", fontSize = 10.5.sp, color = upColor)
                    Text("L: ${PriceFormatter.formatPrice(activeCandle.low, showSymbol = false, quoteAsset = quoteAsset)}", fontSize = 10.5.sp, color = downColor)
                    Text("C: ${PriceFormatter.formatPrice(activeCandle.close, showSymbol = false, quoteAsset = quoteAsset)}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = candleColor)
                }
            }
        }

        if (displayCandles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Memuat data chart...",
                    fontSize = 12.sp,
                    color = TvTextSecondary
                )
            }
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(displayCandles) {
                        detectTapGestures(
                            onTap = { offset ->
                                touchX = offset.x
                                val candleWidth = size.width / displayCandles.size
                                val index = (offset.x / candleWidth).toInt().coerceIn(0, displayCandles.lastIndex)
                                selectedCandleIndex = index
                            }
                        )
                    }
                    .pointerInput(displayCandles) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                touchX = offset.x
                                val candleWidth = size.width / displayCandles.size
                                val index = (offset.x / candleWidth).toInt().coerceIn(0, displayCandles.lastIndex)
                                selectedCandleIndex = index
                            },
                            onDragEnd = {
                                touchX = null
                                selectedCandleIndex = null
                            },
                            onDragCancel = {
                                touchX = null
                                selectedCandleIndex = null
                            },
                            onDrag = { change, _ ->
                                touchX = change.position.x
                                val candleWidth = size.width / displayCandles.size
                                val index = (change.position.x / candleWidth).toInt().coerceIn(0, displayCandles.lastIndex)
                                selectedCandleIndex = index
                            }
                        )
                    }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val rightPricePadding = 56.dp.toPx()
                val bottomTimePadding = 18.dp.toPx()
                val chartWidth = canvasWidth - rightPricePadding
                val chartHeight = canvasHeight - bottomTimePadding

                val volumeHeightRatio = if (showVolume) 0.22f else 0f
                val priceAreaHeight = chartHeight * (1f - volumeHeightRatio)
                val volumeAreaHeight = chartHeight * volumeHeightRatio

                val count = displayCandles.size
                if (count == 0) return@Canvas

                var minPrice = displayCandles.minOf { it.low }
                var maxPrice = displayCandles.maxOf { it.high }

                // Include target lines in price scaling if visible
                if (entryPrice > 0.0) {
                    minPrice = min(minPrice, entryPrice)
                    maxPrice = max(maxPrice, entryPrice)
                }
                if (targetPrice1 > 0.0) {
                    minPrice = min(minPrice, targetPrice1)
                    maxPrice = max(maxPrice, targetPrice1)
                }
                if (stopLoss > 0.0) {
                    minPrice = min(minPrice, stopLoss)
                    maxPrice = max(maxPrice, stopLoss)
                }

                // Add 4% padding top & bottom
                val priceRange = (maxPrice - minPrice).coerceAtLeast(1.0)
                val paddedMin = minPrice - priceRange * 0.04
                val paddedMax = maxPrice + priceRange * 0.04
                val finalRange = paddedMax - paddedMin

                val maxVolume = displayCandles.maxOfOrNull { it.volume }?.coerceAtLeast(1.0) ?: 1.0

                fun getY(price: Double): Float {
                    val normalized = (paddedMax - price) / finalRange
                    return (normalized * priceAreaHeight).toFloat()
                }

                val candleSlotWidth = chartWidth / count
                val candleBodyWidth = (candleSlotWidth * 0.7f).coerceIn(2f, 14f)

                // Draw Grid Lines (Horizontal)
                val gridSteps = 4
                val textPaint = Paint().apply {
                    color = textMutedColor.toArgb()
                    textSize = 9.sp.toPx()
                    isAntiAlias = true
                }

                for (i in 0..gridSteps) {
                    val p = paddedMin + (finalRange * (i.toDouble() / gridSteps))
                    val y = getY(p)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(chartWidth, y),
                        strokeWidth = 1f
                    )
                    // Draw Price Label on the right axis
                    drawContext.canvas.nativeCanvas.drawText(
                        PriceFormatter.formatPrice(p, showSymbol = false, quoteAsset = quoteAsset),
                        chartWidth + 6.dp.toPx(),
                        y + 3.dp.toPx(),
                        textPaint
                    )
                }

                // Draw Candlesticks & Volumes
                displayCandles.forEachIndexed { i, candle ->
                    val centerX = i * candleSlotWidth + candleSlotWidth / 2f
                    val isUp = candle.close >= candle.open
                    val color = if (isUp) upColor else downColor

                    // Volume Bar
                    if (showVolume && volumeAreaHeight > 0) {
                        val volNormalized = (candle.volume / maxVolume).toFloat().coerceIn(0f, 1f)
                        val volBarHeight = volNormalized * (volumeAreaHeight - 4.dp.toPx())
                        val volTop = chartHeight - volBarHeight
                        drawRect(
                            color = color.copy(alpha = 0.28f),
                            topLeft = Offset(centerX - candleBodyWidth / 2f, volTop),
                            size = Size(candleBodyWidth, volBarHeight)
                        )
                    }

                    // Candle Wick
                    val highY = getY(candle.high)
                    val lowY = getY(candle.low)
                    drawLine(
                        color = color,
                        start = Offset(centerX, highY),
                        end = Offset(centerX, lowY),
                        strokeWidth = 1.5f
                    )

                    // Candle Body
                    val openY = getY(candle.open)
                    val closeY = getY(candle.close)
                    val bodyTop = min(openY, closeY)
                    val bodyHeight = max(abs(closeY - openY), 1.5f)

                    drawRect(
                        color = color,
                        topLeft = Offset(centerX - candleBodyWidth / 2f, bodyTop),
                        size = Size(candleBodyWidth, bodyHeight)
                    )
                }

                // Draw Target / Level Lines
                fun drawLevelLine(price: Double, lineColor: Color, label: String) {
                    if (price <= 0.0) return
                    val y = getY(price)
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(chartWidth, y),
                        strokeWidth = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                    )
                    val badgePaint = Paint().apply {
                        this.color = lineColor.toArgb()
                        textSize = 8.5.sp.toPx()
                        isFakeBoldText = true
                        isAntiAlias = true
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        chartWidth + 6.dp.toPx(),
                        y + 3.dp.toPx(),
                        badgePaint
                    )
                }

                drawLevelLine(entryPrice, blueColor, "ENTRY")
                drawLevelLine(targetPrice1, upColor, "TP1")
                drawLevelLine(targetPrice2, greenLightColor, "TP2")
                drawLevelLine(stopLoss, downColor, "SL")

                // Draw Current Price Dashed Line
                if (currentPrice > 0.0) {
                    val currentY = getY(currentPrice)
                    drawLine(
                        color = blueColor,
                        start = Offset(0f, currentY),
                        end = Offset(chartWidth, currentY),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                }

                // Draw Touch Crosshair
                touchX?.let { tx ->
                    if (tx in 0f..chartWidth) {
                        drawLine(
                            color = textColor.copy(alpha = 0.5f),
                            start = Offset(tx, 0f),
                            end = Offset(tx, chartHeight),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        )
                    }
                }
            }
        }
    }
}
