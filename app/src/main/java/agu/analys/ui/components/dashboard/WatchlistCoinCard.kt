package agu.analys.ui.components.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CandleBar
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.animation.AnimatedMetricText
import agu.analys.ui.animation.SmoothPriceText
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

/**
 * Compact Watchlist Card:
 * - Rank + Avatar + Symbol
 * - Harga + Trend 24h %
 * - MiniSparkline + Vol 24h
 * - Star favorite
 * Tanpa tombol Buka Chart / Lihat Rekomendasi (klik card = buka detail)
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
    holdingStatus: CoinHoldingStatus? = null,
    tradingFees: agu.analys.config.TradingFeeConfig = agu.analys.config.TradingFeeConfig(),
    onToggleFavorite: () -> Unit = {},
    onClick: () -> Unit
) {
    val change = tick?.change24h ?: Double.NaN
    val volume = tick?.volume24h ?: 0.0
    val isUsdt = pair.quoteAsset.equals("USDT", true) || pair.quoteAsset.equals("USD", true)

    val changeColor = when {
        change > 0 -> TvGreen
        change < 0 -> TvRed
        else -> TvTextSecondary
    }

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

    val activity = when {
        volumeScore >= 8 || (change.isFinite() && change >= 3.0) -> ActivityLevel.HIGH
        volumeScore >= 6 || (change.isFinite() && change >= 0.0) -> ActivityLevel.MEDIUM
        else -> ActivityLevel.LOW
    }

    val rankText = rank?.let { String.format("%02d", it) } ?: "··"
    val sparkColor = if (change >= 0) TvGreen else TvRed

    val isHolding = holdingStatus != null && holdingStatus.isHolding && holdingStatus.quantity > 0.00000001
    val badgeInfo: Triple<String, Color, Color>? = if (isHolding) {
        val currentPrice = tick?.price ?: 0.0
        val entryPrice = holdingStatus.entryPrice
        val sellFeeRate = (tradingFees.sellMakerPct / 100.0).coerceAtLeast(0.0)
        val grossSell = holdingStatus.quantity * currentPrice
        val netSell = grossSell * (1.0 - sellFeeRate)
        val costBasis = holdingStatus.quantity * entryPrice
        val netProfitIdr = netSell - costBasis
        val netProfitPct = if (costBasis > 0.0) (netProfitIdr / costBasis) * 100.0 else 0.0

        val rsiVal = if (recentCandles.size >= 15) agu.analys.engine.indicators.IndicatorMath.rsi(recentCandles, 14) else Double.NaN
        
        when {
            holdingStatus.tp1Price > 0.0 && currentPrice >= holdingStatus.tp1Price && netProfitPct > 0.0 -> Triple("🎯 TARGET TP1", TvOrange, TvOrange.copy(alpha = 0.18f))
            netProfitPct >= 5.0 -> Triple("🔥 PROFIT +5%", TvOrange, TvOrange.copy(alpha = 0.18f))
            tick != null && tick.high24h > 0 && currentPrice >= tick.high24h * 0.98 && netProfitPct >= 1.0 -> Triple("📈 NEAR 24H HIGH", TvOrange, TvOrange.copy(alpha = 0.18f))
            netProfitPct >= 2.5 || (holdingStatus.isTrailingTriggered && netProfitPct > 0.0) -> Triple("💰 READY PROFIT", TvGreen, TvGreen.copy(alpha = 0.18f))
            rsiVal.isFinite() && rsiVal >= 70.0 && netProfitPct > 0.0 -> Triple("⚠️ RSI OVERBOUGHT", TvRed, TvRed.copy(alpha = 0.18f))
            else -> null
        }
    } else null

    val pulseTransition = rememberInfiniteTransition(label = "pulse_card_${pair.symbol}")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha_${pair.symbol}"
    )

    val cardBorder = if (badgeInfo != null) {
        BorderStroke(1.2.dp, badgeInfo.second.copy(alpha = pulseAlpha))
    } else {
        BorderStroke(1.dp, DashboardColors.Border)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = cardBorder
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .background(TvSurfaceVariant, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = rankText,
                            color = TvAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    AssetAvatar(baseAsset = pair.baseAsset, iconUrl = pair.iconUrl, size = 28.dp)
                    Spacer(Modifier.width(5.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pair.baseAsset,
                                color = TvTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "/${pair.quoteAsset}",
                                color = TvTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CompactActivityChip(activity)
                            
                            if (badgeInfo != null) {
                                Box(
                                    modifier = Modifier
                                        .background(badgeInfo.second.copy(alpha = (0.12f + 0.12f * pulseAlpha)), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = badgeInfo.first,
                                        color = badgeInfo.second,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (tick != null && tick.price > 0) {
                            SmoothPriceText(
                                price = tick.price,
                                color = TvTextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Black,
                                quoteAsset = pair.quoteAsset
                            )
                            if (isUsdt && usdtIdrRate > 0) {
                                val idrEst = tick.price * usdtIdrRate
                                Text(
                                    text = "≈ ${PriceFormatter.formatPrice(idrEst, quoteAsset = "IDR")}",
                                    color = TvTextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        } else {
                            Text("—", color = TvTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        if (change.isFinite()) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (change >= 0) TvGreen.copy(alpha = 0.15f) else TvRed.copy(alpha = 0.15f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                AnimatedMetricText(
                                    value = PriceFormatter.formatPercentage(change),
                                    color = changeColor,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) TvAmber else TvTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniSparkline(
                    tick = tick,
                    modifier = Modifier
                        .width(84.dp)
                        .height(34.dp),
                    lineColor = sparkColor
                )

                Spacer(Modifier.width(8.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = when {
                            !change.isFinite() -> "Trend —"
                            change > 0 -> "Trend Naik"
                            change < 0 -> "Trend Turun"
                            else -> "Trend Sideways"
                        },
                        color = changeColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Vol ${PriceFormatter.formatVolume(volume, quoteAsset = pair.quoteAsset)}",
                        color = TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (tick != null && tick.high24h > 0 && tick.low24h > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "H ${PriceFormatter.formatPrice(tick.high24h, quoteAsset = pair.quoteAsset)}",
                            color = TvGreen.copy(alpha = 0.85f),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "L ${PriceFormatter.formatPrice(tick.low24h, quoteAsset = pair.quoteAsset)}",
                            color = TvRed.copy(alpha = 0.85f),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private enum class ActivityLevel { HIGH, MEDIUM, LOW, UNKNOWN }

@Composable
private fun CompactActivityChip(activity: ActivityLevel) {
    val (label, background, foreground) = when (activity) {
        ActivityLevel.HIGH -> Triple("Tinggi", TvGreen.copy(alpha = 0.15f), TvGreen)
        ActivityLevel.MEDIUM -> Triple("Sedang", TvAmber.copy(alpha = 0.15f), TvAmber)
        ActivityLevel.LOW -> Triple("Rendah", TvSurfaceVariant, TvTextSecondary)
        ActivityLevel.UNKNOWN -> Triple("—", TvSurfaceVariant, TvTextSecondary)
    }
    Box(
        Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
