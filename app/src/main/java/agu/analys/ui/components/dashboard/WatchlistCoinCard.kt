package agu.analys.ui.components.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.animation.AnimatedMetricText
import agu.analys.ui.animation.SmoothPriceText
import agu.analys.ui.components.SimpleComposeChart
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

/**
 * Watchlist Card multi-exchange (Indodax / Tokocrypto):
 * - Nomor urut [01]
 * - Symbol & Nama Koin (BTC/USDT atau BTC/IDR)
 * - Star favorite icon
 * - Harga & 24h % (Format dinamis USD / IDR)
 * - Badge Aktivitas (Tinggi, Sedang, Rendah)
 * - Meter Volume & Momentum 10-segmen
 * - Tombol Buka Chart > dengan toggle show/hide chart inline
 */
@Composable
fun WatchlistCoinCard(
    pair: TradingPair,
    tick: MarketTick?,
    worth: WorthCoinInfo?,
    rank: Int?,
    isAuto: Boolean,
    isScalping: Boolean,
    isFavorite: Boolean = true,
    usdtIdrRate: Double = 16450.0,
    recentCandles: List<CandleBar> = emptyList(),
    onToggleFavorite: () -> Unit = {},
    onClick: () -> Unit
) {
    var isInlineChartExpanded by remember { mutableStateOf(false) }
    val change = tick?.change24h ?: Double.NaN
    val volume = tick?.volume24h ?: 0.0
    val isUsdt = pair.quoteAsset.equals("USDT", true) || pair.quoteAsset.equals("USD", true)

    val changeColor = when {
        change > 0 -> TvGreen
        change < 0 -> TvRed
        else -> TvTextSecondary
    }

    // Skor Volume & Momentum untuk 10-segment meter
    val volumeScore = if (isUsdt) {
        when {
            volume >= 100_000_000.0 -> 9
            volume >= 20_000_000.0 -> 8
            volume >= 5_000_000.0 -> 7
            volume >= 1_000_000.0 -> 6
            volume >= 200_000.0 -> 5
            volume > 0 -> 4
            else -> 2
        }
    } else {
        when {
            volume >= 100_000_000_000 -> 9
            volume >= 50_000_000_000 -> 8
            volume >= 10_000_000_000 -> 7
            volume >= 1_000_000_000 -> 6
            volume >= 200_000_000 -> 5
            volume > 0 -> 4
            else -> 2
        }
    }

    val momentumScore = when {
        change >= 6.0 -> 9
        change >= 3.0 -> 8
        change >= 1.0 -> 7
        change >= 0.0 -> 6
        change >= -2.0 -> 5
        else -> 4
    }

    val activity = when {
        volumeScore >= 8 || (change.isFinite() && change >= 3.0) -> ActivityLevel.HIGH
        volumeScore >= 6 || (change.isFinite() && change >= 0.0) -> ActivityLevel.MEDIUM
        else -> ActivityLevel.LOW
    }

    val meterColor = when (activity) {
        ActivityLevel.HIGH -> TvGreen
        ActivityLevel.MEDIUM -> Color(0xFFFFB300)
        ActivityLevel.LOW -> Color(0xFF78909C)
        ActivityLevel.UNKNOWN -> TvTextSecondary
    }

    val rankText = rank?.let { String.format("%02d", it) } ?: "··"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Baris 1: Layout Responsif Kiri-Kanan (Bebas Wrapping di Redmi Note 11)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Sisi Kiri: Rank + Avatar + Simbol Pair
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // Rank Pill
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1E2836), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = rankText,
                            color = Color(0xFFFFB300),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(Modifier.width(6.dp))
                    AssetAvatar(baseAsset = pair.baseAsset, iconUrl = pair.iconUrl, size = 32.dp)
                    Spacer(Modifier.width(6.dp))

                    // Symbol & Quote Badge
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pair.baseAsset,
                                color = TvTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "/${pair.quoteAsset}",
                                color = TvTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.height(1.dp))
                        ActivityChip(activity)
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Sisi Kanan: Harga, % Badge, & Star Favorit
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (tick != null && tick.price > 0) {
                            SmoothPriceText(
                                price = tick.price,
                                color = TvTextPrimary,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Black,
                                quoteAsset = pair.quoteAsset
                            )
                            if (isUsdt && usdtIdrRate > 0) {
                                val idrEst = tick.price * usdtIdrRate
                                Text(
                                    text = "≈ ${PriceFormatter.formatPrice(idrEst, quoteAsset = "IDR")}",
                                    color = TvTextSecondary,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        } else {
                            Text("—", color = TvTextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        if (change.isFinite()) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (change >= 0) TvGreen.copy(alpha = 0.15f) else TvRed.copy(alpha = 0.15f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                AnimatedMetricText(
                                    value = PriceFormatter.formatPercentage(change),
                                    color = changeColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    // Star Favorite Icon
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) Color(0xFFFFB300) else TvTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Baris 2 & 3: Meter Bars
            SegmentedMeterRow(
                label = "Volume",
                score = volumeScore,
                activeColor = meterColor
            )
            Spacer(Modifier.height(4.dp))
            SegmentedMeterRow(
                label = "Momentum",
                score = momentumScore,
                activeColor = meterColor
            )

            Spacer(Modifier.height(6.dp))

            // Baris 4: Buka Chart Toggle Link / Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { isInlineChartExpanded = !isInlineChartExpanded }
                    .padding(vertical = 3.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ShowChart,
                        contentDescription = null,
                        tint = TvGreen,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isInlineChartExpanded) "Tutup Chart" else "Buka Chart",
                        color = TvGreen,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TvGreen,
                        modifier = Modifier.size(13.dp)
                    )
                }

                Text(
                    text = "Lihat Rekomendasi >",
                    color = TvTextSecondary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Inline Mini Chart (Show/Hide feature)
            AnimatedVisibility(
                visible = isInlineChartExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF09101A))
                    ) {
                        SimpleComposeChart(
                            prices = emptyList(),
                            candles = recentCandles,
                            currentPrice = tick?.price ?: 0.0,
                            isPositiveTrend = (tick?.change24h ?: 0.0) >= 0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        )
                    }
                }
            }
        }
    }
}

private enum class ActivityLevel { HIGH, MEDIUM, LOW, UNKNOWN }

@Composable
private fun ActivityChip(activity: ActivityLevel) {
    val (label, background, foreground) = when (activity) {
        ActivityLevel.HIGH -> Triple("Aktivitas tinggi", Color(0xFF123D2A), TvGreen)
        ActivityLevel.MEDIUM -> Triple("Aktivitas sedang", Color(0xFF3D3212), Color(0xFFFFB300))
        ActivityLevel.LOW -> Triple("Aktivitas rendah", Color(0xFF1E2836), Color(0xFF90A4AE))
        ActivityLevel.UNKNOWN -> Triple("Aktivitas normal", Color(0xFF1E2836), TvTextSecondary)
    }

    Box(
        Modifier
            .background(background, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
