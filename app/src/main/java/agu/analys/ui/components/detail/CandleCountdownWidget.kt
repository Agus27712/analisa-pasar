package agu.analys.ui.components.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.Timeframe
import agu.analys.ui.theme.*
import agu.analys.util.CandleTimeUtil
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Komponen UI Penutupan Candle (Candle Close Countdown Timer & Confirmation Status)
 * dengan indikator progres visual, konfirmasi status Candle Closed, dan sintesis latency real-time.
 */
@Composable
fun CandleCountdownWidget(
    timeframe: Timeframe,
    candles: List<CandleBar>,
    tick: MarketTick?,
    modifier: Modifier = Modifier
) {
    var currentTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // Heartbeat ticker 1 detik untuk countdown real-time
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (isActive) {
                currentTimeMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    val countdownText = remember(timeframe, currentTimeMs) {
        CandleTimeUtil.formatCandleCountdown(timeframe, currentTimeMs)
    }

    val progress = remember(timeframe, currentTimeMs) {
        CandleTimeUtil.getCandleProgress(timeframe, currentTimeMs)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "candle_progress"
    )

    val lastCandle = candles.lastOrNull()
    val isLastClosed = lastCandle?.isClosed ?: true
    val tickTimestamp = tick?.timestamp ?: (lastCandle?.timestamp ?: 0L)
    val latencyText = remember(tickTimestamp, currentTimeMs) {
        CandleTimeUtil.formatLatency(tickTimestamp, currentTimeMs)
    }

    val progressColor = when {
        progress >= 0.85f -> TvRed // Mendekati penutupan bar
        progress >= 0.50f -> Color(0xFFFFB300) // Pertengahan bar
        else -> TvBlue
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurface, RoundedCornerShape(8.dp))
            .border(0.8.dp, TvBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Kiri: Countdown Penutupan Candle & Progres
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = "Penutupan Candle",
                tint = progressColor,
                modifier = Modifier.size(15.dp)
            )

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Candle ${timeframe.label}: ",
                        color = TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = countdownText,
                        color = TvTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.height(3.dp))

                // Progress Bar Penutupan Candle
                Box(modifier = Modifier.width(110.dp)) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = progressColor,
                        trackColor = TvCardBackground,
                        strokeCap = StrokeCap.Round
                    )
                }
            }
        }

        // Kanan: Realtime Latency & Confirmation Status Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Confirmation Chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isLastClosed) TvGreen.copy(alpha = 0.12f) else TvBlue.copy(alpha = 0.12f))
                    .border(0.6.dp, if (isLastClosed) TvGreen.copy(alpha = 0.4f) else TvBlue.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = if (isLastClosed) "Bar Closed ✅" else "Forming ⚡",
                    color = if (isLastClosed) TvGreen else TvBlue,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Fresh Latency Chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(TvCardBackground)
                    .border(0.6.dp, TvBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = latencyText,
                    color = if (latencyText.startsWith("⚡")) TvGreen else TvTextSecondary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
