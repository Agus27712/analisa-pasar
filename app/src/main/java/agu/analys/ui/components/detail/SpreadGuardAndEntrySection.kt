package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.scalping.EntryExecutionType
import agu.analys.engine.scalping.OrderBookAnalyzer
import agu.analys.engine.scalping.SpreadAnalysis
import agu.analys.model.OrderBookItem
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale

/**
 * Komponen UI untuk Rekomendasi Harga Entri Presisi, Analisis Toleransi Spread,
 * dan Proteksi SPREAD GUARD anti-slippage dengan format ribuan standar dan layout responsif anti-tumpuk.
 */
@Composable
fun SpreadGuardAndEntrySection(
    bids: List<OrderBookItem>,
    asks: List<OrderBookItem>,
    currentPrice: Double,
    tolerancePct: Double = 0.40,
    maxGuardPct: Double = 1.20,
    quoteAsset: String = "IDR",
    onApplyRecommendedPrice: ((Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val spreadAnalysis = remember(bids, asks, currentPrice, tolerancePct, maxGuardPct) {
        OrderBookAnalyzer.analyzeSpread(
            bids = bids,
            asks = asks,
            currentPrice = currentPrice,
            tolerancePct = tolerancePct,
            maxGuardPct = maxGuardPct
        )
    }

    val isGuardActive = spreadAnalysis.isSpreadGuardActive
    val executionType = spreadAnalysis.executionType

    val badgeColor = when (executionType) {
        EntryExecutionType.MARKET_TAKER -> TvGreen
        EntryExecutionType.LIMIT_MAKER -> Color(0xFFFFB300) // Amber / Kuning Emas
        EntryExecutionType.SPREAD_GUARD_VETO -> TvRed
        EntryExecutionType.NO_DEPTH -> TvTextSecondary
    }

    val cardBg = when (executionType) {
        EntryExecutionType.SPREAD_GUARD_VETO -> TvRed.copy(alpha = 0.08f)
        EntryExecutionType.LIMIT_MAKER -> Color(0xFFFFB300).copy(alpha = 0.06f)
        else -> TvCardBackground
    }

    val cardBorderColor = when (executionType) {
        EntryExecutionType.SPREAD_GUARD_VETO -> TvRed.copy(alpha = 0.5f)
        EntryExecutionType.LIMIT_MAKER -> Color(0xFFFFB300).copy(alpha = 0.4f)
        else -> TvBorder
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Header: Judul Proteksi & Badge Eksekusi
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = if (isGuardActive) Icons.Default.Warning else Icons.Default.Shield,
                        contentDescription = "Spread Guard",
                        tint = badgeColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isGuardActive) "SPREAD GUARD TERPICU" else "ANALISIS SPREAD & GUARD",
                        color = if (isGuardActive) TvRed else TvTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Badge Status Eksekusi
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .border(0.8.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = executionType.label,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            // 2. Order Book Best Bid & Best Ask Container (2 Kolom Lebar, Tidak Bertumpuk)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurface.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .border(0.5.dp, TvBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Baris 1: Best Bid vs Best Ask
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Best Bid (Maker)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Best Bid (Maker)",
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (spreadAnalysis.bestBid > 0) {
                                PriceFormatter.formatPrice(spreadAnalysis.bestBid, showSymbol = true, quoteAsset = quoteAsset)
                            } else "—",
                            color = TvGreen,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    // Best Ask (Taker)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "Best Ask (Taker)",
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (spreadAnalysis.bestAsk > 0) {
                                PriceFormatter.formatPrice(spreadAnalysis.bestAsk, showSymbol = true, quoteAsset = quoteAsset)
                            } else "—",
                            color = TvRed,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)

                // Baris 2: Spread Gap & Toleransi Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Spread Gap:",
                            color = TvTextSecondary,
                            fontSize = 10.5.sp
                        )
                        Text(
                            text = "${String.format(Locale.US, "%.2f", spreadAnalysis.spreadPct)}%",
                            color = badgeColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                        if (spreadAnalysis.spreadIdr > 0) {
                            Text(
                                text = "(${PriceFormatter.formatPrice(spreadAnalysis.spreadIdr, showSymbol = true, quoteAsset = quoteAsset)})",
                                color = TvTextSecondary,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Text(
                        text = if (isGuardActive) "Batas: ${String.format(Locale.US, "%.1f", maxGuardPct)}% ⚠️" else "Toleransi: ≤${String.format(Locale.US, "%.1f", tolerancePct)}%",
                        color = if (isGuardActive) TvRed else TvTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (isGuardActive) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // 3. Kotak Rekomendasi Harga Entri Presisi (Format Ribuan Bersih & Tombol Terapkan)
            Surface(
                color = TvSurface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.8.dp, if (isGuardActive) TvRed.copy(alpha = 0.3f) else TvBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Rekomendasi Entri:",
                                color = TvTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (executionType == EntryExecutionType.MARKET_TAKER) "Eksekusi Instan" else "Limit di Best Bid",
                                color = if (executionType == EntryExecutionType.MARKET_TAKER) TvGreen else Color(0xFFFFB300),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (spreadAnalysis.recommendedEntryPrice > 0) {
                                PriceFormatter.formatPrice(spreadAnalysis.recommendedEntryPrice, showSymbol = true, quoteAsset = quoteAsset)
                            } else "Rp —",
                            color = TvTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (onApplyRecommendedPrice != null && spreadAnalysis.recommendedEntryPrice > 0) {
                        Button(
                            onClick = { onApplyRecommendedPrice(spreadAnalysis.recommendedEntryPrice) },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGuardActive) Color(0xFFFFB300) else TvGreen,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = "Terapkan",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // 4. Rationale & Advice Text
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(13.dp).padding(top = 1.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = spreadAnalysis.advice,
                    color = if (isGuardActive) TvRed else TvTextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.5.sp,
                    fontWeight = if (isGuardActive) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
