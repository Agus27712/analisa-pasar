package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.StrategyMode
import agu.analys.model.ConfluenceCheckpoint
import agu.analys.model.OrderBookItem
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 6-Checkpoint Confluence Stepper untuk Spot Trading (High-Probability Setup).
 * Menyajikan 6 pilar konfluensi:
 * 1. MTF (Market Structure)
 * 2. AoV (Area of Value)
 * 3. VOL (Volume Validasi)
 * 4. TRG (Price Action Trigger)
 * 5. MOM (Momentum / Div)
 * 6. RR (Risk to Reward >= 1:2)
 *
 * Desain segmented, interaktif, rapi, dan dinamis berdasarkan data pasar real-time.
 */
@Composable
fun RadarLinearCheckpointStepper(
    mtf: ScalpingMtfSnapshot,
    completed: Int,
    pulseScale: Float = 1f,
    strategyMode: StrategyMode = StrategyMode.SWING,
    confidence: Int = 0,
    orderBookBids: List<OrderBookItem> = emptyList(),
    orderBookAsks: List<OrderBookItem> = emptyList(),
    modifier: Modifier = Modifier
) {
    val checkpoints = remember(mtf) { mtf.resolvedCheckpoints() }
    val totalCount = checkpoints.size.coerceAtLeast(6)
    val passedCount = remember(checkpoints) { checkpoints.count { it.isOk } }

    // Checkpoint aktif yang sedang dilihat (default ke checkpoint pertama yang belum lolos, atau ke-0 jika semua lolos)
    val firstUnpassedIdx = remember(checkpoints) {
        val idx = checkpoints.indexOfFirst { !it.isOk }
        if (idx >= 0) idx else 0
    }
    var selectedIndex by remember(mtf) { mutableIntStateOf(firstUnpassedIdx) }

    // Perhitungan Bid & Ask Flow
    val totalBids = remember(orderBookBids) { orderBookBids.sumOf { it.amount } }
    val totalAsks = remember(orderBookAsks) { orderBookAsks.sumOf { it.amount } }
    val totalVolume = totalBids + totalAsks
    val bidPct = remember(totalBids, totalAsks) {
        if (totalVolume > 0) (totalBids / totalVolume) * 100.0 else 50.0
    }
    val askPct = remember(totalBids, totalAsks) {
        if (totalVolume > 0) (totalAsks / totalVolume) * 100.0 else 50.0
    }
    val ratio = remember(totalBids, totalAsks) {
        if (totalAsks > 0) totalBids / totalAsks else if (totalBids > 0) 9.99 else 1.0
    }
    val ratioSign = if (ratio >= 1.0) ">" else "<"
    val ratioValueStr = String.format(Locale.US, "%.2f", ratio)

    // Progress dinamis: 6 checkpoints (50%) + Confidence (30%) + Orderbook Flow (20%)
    val dynamicEntryProgress = remember(passedCount, confidence, bidPct) {
        val stepWeight = (passedCount / 6.0) * 50.0
        val confWeight = (confidence.coerceIn(0, 100) / 100.0) * 30.0
        val bidWeight = (bidPct.coerceIn(0.0, 100.0) / 100.0) * 20.0
        val combined = (stepWeight + confWeight + bidWeight).roundToInt()
        if (passedCount == 6) {
            combined.coerceAtLeast(88).coerceIn(0, 100)
        } else {
            combined.coerceIn(0, 99)
        }
    }

    val animProgress by animateFloatAsState(
        targetValue = (dynamicEntryProgress / 100f).coerceIn(0f, 1f),
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "dynamic_entry_progress"
    )

    val progressColor = when {
        passedCount == 6 -> TvGreen
        passedCount >= 4 -> TvBlue
        else -> TvAmber
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── 1. HEADER: Kesiapan Konfluensi & Persentase ───────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (passedCount == 6) TvGreen else TvBlue)
                )
                Text(
                    text = "Konfluensi ($passedCount/6 Lolos)",
                    color = TvTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (passedCount == 6) "HIGH-PROBABILITY SETUP" else "FILTERING...",
                    color = if (passedCount == 6) TvGreen else TvTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$dynamicEntryProgress%",
                    color = progressColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // ── 2. LOADING BAR DENGAN 6 SEGMEN ──────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(TvSurfaceVariant)
        ) {
            // Fill Bar dengan Gradient Halus & Dinamis
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animProgress)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        when {
                            passedCount == 6 -> Brush.horizontalGradient(
                                listOf(Color(0xFF00B0FF), Color(0xFF00E676), TvGreen)
                            )
                            passedCount >= 4 -> Brush.horizontalGradient(
                                listOf(TvBlue.copy(alpha = 0.85f), Color(0xFF00E5FF))
                            )
                            else -> Brush.horizontalGradient(
                                listOf(TvAmber.copy(alpha = 0.85f), TvBlue.copy(alpha = 0.75f))
                            )
                        }
                    )
            )

            // 5 Garis Pembatas (Membagi 6 segmen checkpoint yang jelas)
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(5) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .background(TvBackground.copy(alpha = 0.75f))
                    )
                }
            }
        }

        // ── 3. 6 SEGMENTED CHECKPOINT TABS (INTERAKTIF & RESPONSIF) ─────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            checkpoints.forEachIndexed { index, cp ->
                val isSelected = index == selectedIndex
                val tabBg = when {
                    isSelected && cp.isOk -> TvGreen.copy(alpha = 0.22f)
                    isSelected -> TvBlue.copy(alpha = 0.22f)
                    cp.isOk -> TvGreen.copy(alpha = 0.10f)
                    else -> TvSurfaceVariant.copy(alpha = 0.45f)
                }
                val tabBorder = when {
                    isSelected && cp.isOk -> TvGreen
                    isSelected -> TvBlue
                    cp.isOk -> TvGreen.copy(alpha = 0.40f)
                    else -> Color.Transparent
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tabBg)
                        .border(1.dp, tabBorder, RoundedCornerShape(6.dp))
                        .clickable { selectedIndex = index }
                        .padding(vertical = 5.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (cp.isOk) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = TvGreen,
                                    modifier = Modifier.size(9.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.HourglassEmpty,
                                    contentDescription = null,
                                    tint = if (isSelected) TvBlue else TvTextSecondary,
                                    modifier = Modifier.size(9.dp)
                                )
                            }
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "${cp.number}",
                                color = if (cp.isOk) TvGreen else if (isSelected) TvBlue else TvTextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Text(
                            text = cp.code,
                            color = if (cp.isOk) TvGreen else if (isSelected) TvTextPrimary else TvTextSecondary,
                            fontSize = 8.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // ── 4. KETERANGAN CHECKPOINT AKTIF YANG DIPILIH (INFORMATIF & DINAMIS) ──
        AnimatedContent(
            targetState = selectedIndex,
            transitionSpec = {
                (slideInVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) { height -> height / 4 } + fadeIn(animationSpec = tween(200)))
                    .togetherWith(slideOutVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { height -> -height / 4 } + fadeOut(animationSpec = tween(180)))
            },
            label = "confluence_checkpoint_detail"
        ) { targetIdx ->
            val cp = checkpoints.getOrNull(targetIdx) ?: checkpoints.first()
            val isCurrentPassed = cp.isOk

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvSurface)
                    .border(
                        1.dp,
                        if (isCurrentPassed) TvGreen.copy(alpha = 0.35f) else TvBlue.copy(alpha = 0.30f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Header Baris Detail Checkpoint
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (isCurrentPassed) TvGreen.copy(alpha = 0.15f) else TvBlue.copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "CHECKPOINT ${cp.number}/6",
                                    color = if (isCurrentPassed) TvGreen else TvBlue,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.3.sp
                                )
                            }

                            Text(
                                text = cp.label,
                                color = TvTextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Status Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isCurrentPassed) TvGreen.copy(alpha = 0.15f) else TvAmber.copy(alpha = 0.15f)
                                )
                                .border(
                                    0.5.dp,
                                    if (isCurrentPassed) TvGreen.copy(alpha = 0.4f) else TvAmber.copy(alpha = 0.4f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isCurrentPassed) "LOLOS ✓" else "MENUNGGU ⏳",
                                color = if (isCurrentPassed) TvGreen else TvAmber,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Metric Pill (Data Real-time)
                    if (cp.metricValue.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Kalkulasi:",
                                color = TvTextSecondary,
                                fontSize = 10.sp
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(TvSurfaceVariant)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                val displayedMetric = if (cp.code == "VOL" && totalVolume > 0 && cp.metricValue.contains("·")) {
                                    val volPrefix = cp.metricValue.substringBefore("·").trim()
                                    "$volPrefix · Bid ${String.format(Locale.US, "%.0f", bidPct)}%"
                                } else {
                                    cp.metricValue
                                }
                                Text(
                                    text = displayedMetric,
                                    color = if (isCurrentPassed) TvGreen else TvTextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Teks Penjelasan Detail
                    Text(
                        text = cp.detail,
                        color = if (isCurrentPassed) TvTextPrimary else TvTextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.5.sp
                    )
                }
            }
        }

        // ── 5. ALIRAN BID & ASK (ORDERBOOK PRESSURE) ────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Bid ${String.format(Locale.US, "%.1f", bidPct)}%",
                    color = TvGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "vs",
                    color = TvTextSecondary,
                    fontSize = 9.5.sp
                )
                Text(
                    text = "Ask ${String.format(Locale.US, "%.1f", askPct)}%",
                    color = TvRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (ratio >= 1.0) TvGreen.copy(alpha = 0.12f) else TvRed.copy(alpha = 0.12f)
                    )
                    .border(
                        0.5.dp,
                        if (ratio >= 1.0) TvGreen.copy(alpha = 0.35f) else TvRed.copy(alpha = 0.35f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Tekanan: ($ratioSign ${ratioValueStr}x)",
                    color = if (ratio >= 1.0) TvGreen else TvRed,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
