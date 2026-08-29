package agu.analys.ui.components.detail

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Visual loading and incomplete state card for Multi-Timeframe (MTF) analysis data (1H, 15M, 1M).
 */
@Composable
fun MtfIncompleteStateCard(
    loadedTimeframes: List<String> = emptyList(),
    totalTimeframes: List<String> = listOf("1H", "15M", "1M"),
    message: String = "Data MTF belum lengkap. Sedang menyelaraskan riwayat candle...",
    onRetry: (() -> Unit)? = null
) {
    AnalysisCard {
        MtfIncompleteContent(loadedTimeframes, totalTimeframes, message, onRetry)
    }
}

/**
 * Reusable inner content for incomplete MTF state, safe to embed inside MarketConditionCard.
 */
@Composable
fun MtfIncompleteContent(
    loadedTimeframes: List<String> = emptyList(),
    totalTimeframes: List<String> = listOf("1H", "15M", "1M"),
    message: String = "Data MTF belum lengkap. Sedang menyelaraskan riwayat candle...",
    onRetry: (() -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "MtfLoadingRotate")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RotateAngle"
    )

    // Rate limit cooldown state (safe from server API spamming)
    var isCoolingDown by remember { mutableStateOf(false) }
    var cooldownSeconds by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionTitle("STATUS DATA MTF", Icons.Default.HourglassTop)
        
        // Rotating sync indicator badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(TvAmber.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .border(1.dp, TvAmber.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = "Syncing",
                tint = TvAmber,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(angle)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "${loadedTimeframes.size}/${totalTimeframes.size} TIMEFRAME",
                color = TvAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    Text(
        text = message,
        color = TvTextPrimary,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )

    Spacer(Modifier.height(12.dp))

    // Timeframe readiness pills
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        totalTimeframes.forEach { tf ->
            val isLoaded = loadedTimeframes.contains(tf)
            val badgeColor = if (isLoaded) TvGreen else TvTextMuted
            val bgColor = if (isLoaded) TvGreen.copy(alpha = 0.12f) else TvSurfaceVariant

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bgColor, RoundedCornerShape(8.dp))
                    .border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = tf,
                        color = if (isLoaded) TvGreenLight else TvTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (isLoaded) "READY" else "SYNCING",
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    if (onRetry != null) {
        Spacer(Modifier.height(12.dp))
        
        // Rate-limited Safe Retry Button
        Button(
            onClick = {
                if (!isCoolingDown) {
                    onRetry()
                    isCoolingDown = true
                    cooldownSeconds = 4 // 4 seconds rate limit protection cooldown
                    scope.launch {
                        for (i in 4 downTo 1) {
                            delay(1000L)
                            cooldownSeconds = i - 1
                        }
                        isCoolingDown = false
                    }
                }
            },
            enabled = !isCoolingDown,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = TvSurfaceVariant,
                disabledContainerColor = TvSurfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Retry",
                tint = if (isCoolingDown) TvTextMuted else TvAmber,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isCoolingDown) "Tunggu ${cooldownSeconds + 1}s (Anti-Limit Rate)..." else "Coba Ulang / Refresh MTF",
                color = if (isCoolingDown) TvTextMuted else TvAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


