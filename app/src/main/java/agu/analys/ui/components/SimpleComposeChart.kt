package agu.analys.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
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
    val minPrice = validCandles.minOfOrNull { it.low } ?: currentPrice
    val maxPrice = validCandles.maxOfOrNull { it.high } ?: currentPrice
    val themeColor = if (isPositiveTrend) TvGreen else TvRed
    val livePrice = currentPrice.takeIf { it > 0 }

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
                if (livePrice != null) {
                    Text("LIVE ${PriceFormatter.formatPrice(livePrice)}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF121212))) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (validCandles.size < 2 || maxPrice <= minPrice) return@Canvas
                    val left = 10f
                    val right = 70f
                    val top = 10f
                    val bottom = 16f
                    val chartWidth = (size.width - left - right).coerceAtLeast(1f)
                    val chartHeight = (size.height - top - bottom).coerceAtLeast(1f)
                    val range = maxPrice - minPrice
                    fun y(price: Double): Float = top + ((maxPrice - price) / range).toFloat() * chartHeight
                    val step = chartWidth / validCandles.size
                    val bodyWidth = (step * 0.58f).coerceIn(2f, 14f)

                    validCandles.forEachIndexed { index, candle ->
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
            Text(
                "OHLC dan indikator memakai candle INDODAX. LIVE adalah ticker terakhir; candle terakhir dapat berbeda karena candle berjalan/tertutup memiliki waktu sendiri.",
                fontSize = 8.sp,
                color = TvTextSecondary,
                lineHeight = 11.sp
            )
        }
    }
}