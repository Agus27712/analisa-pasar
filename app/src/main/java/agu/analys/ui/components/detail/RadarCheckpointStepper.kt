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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextSecondary

data class RadarCheckpointItem(
    val number: Int,
    val tabLabel: String,
    val title: String,
    val isOk: Boolean,
    val detail: String
)

@Composable
fun RadarLinearCheckpointStepper(
    mtf: ScalpingMtfSnapshot,
    completed: Int,
    pulseScale: Float,
    modifier: Modifier = Modifier
) {
    val checkpoints = remember(mtf) {
        listOf(
            RadarCheckpointItem(
                number = 1,
                tabLabel = "1. Bias 1H",
                title = "1. Bias 1H · Tren Utama",
                isOk = mtf.biasStatus.name == "OK" || mtf.biasOk,
                detail = if (mtf.biasStatus.name == "OK" || mtf.biasOk) {
                    mtf.biasDetail.ifBlank { "Tren 1 Jam Bullish Kuat (EMA 20/50/200 selaras naik)." }
                } else {
                    mtf.biasDetail.ifBlank { "Memantau keselarasan tren pada timeframe 1 Jam..." }
                }
            ),
            RadarCheckpointItem(
                number = 2,
                tabLabel = "2. Setup 15M",
                title = "2. Setup 15M · Struktur Pasar",
                isOk = mtf.setupStatus.name == "OK" || mtf.setupOk,
                detail = if (mtf.setupStatus.name == "OK" || mtf.setupOk) {
                    mtf.setupDetail.ifBlank { "Struktur 15M valid (Pullback ke support EMA / Golden Cross)." }
                } else {
                    mtf.setupDetail.ifBlank { "Menunggu pembentukan konsolidasi atau pantulan support 15M..." }
                }
            ),
            RadarCheckpointItem(
                number = 3,
                tabLabel = "3. Trigger 1M",
                title = "3. Trigger 1M · Momentum Sinyal",
                isOk = mtf.triggerStatus.name == "OK" || mtf.triggerOk,
                detail = if (mtf.triggerStatus.name == "OK" || mtf.triggerOk) {
                    mtf.triggerDetail.ifBlank { "Breakout volume 1M & momentum RSI/MACD terkonfirmasi aktif." }
                } else {
                    mtf.triggerDetail.ifBlank { "Menunggu trigger lonjakan volume beli dan stochastic/MACD 1M..." }
                }
            ),
            RadarCheckpointItem(
                number = 4,
                tabLabel = "4. Area Entry",
                title = "4. Area Entry · Konfirmasi Harga",
                isOk = mtf.entryPriceStatus.name == "OK" || mtf.entryPriceOk,
                detail = if (mtf.entryPriceStatus.name == "OK" || mtf.entryPriceOk) {
                    mtf.entryPriceDetail.ifBlank { "Harga saat ini berada di zona ideal beli dengan risk/reward optimal." }
                } else {
                    mtf.entryPriceDetail.ifBlank { "Menunggu harga bergerak masuk ke dalam toleransi zona beli ideal..." }
                }
            )
        )
    }

    // Checkpoint aktif saat ini (checkpoint pertama yang belum OK, atau ke-4 jika sudah semua)
    val activeCheckpointIndex = remember(checkpoints) {
        val idx = checkpoints.indexOfFirst { !it.isOk }
        if (idx >= 0) idx else 3
    }

    // 1 Linear Progress Bar Global (masing-masing checkpoint = 25%)
    val targetProgress = (completed.coerceIn(0, 4) / 4f)
    val animGlobalProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "global_linear_progress"
    )

    val progressPercent = (completed.coerceIn(0, 4) * 25)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Baris Header Progres Global (0% -> 25% -> 50% -> 75% -> 100%)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Progres Konfirmasi",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "($completed/4 Checkpoint)",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "$progressPercent%",
                color = if (completed == 4) TvGreen else Color(0xFF00E5FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // 1 Single Global Linear Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF142232))
        ) {
            // Fill Bar with smooth gradient animation
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animGlobalProgress)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (completed == 4) Brush.horizontalGradient(listOf(Color(0xFF00C853), TvGreen))
                        else Brush.horizontalGradient(listOf(Color(0xFF0288D1), Color(0xFF00E5FF)))
                    )
            )

            // Divider markers for 25%, 50%, 75%
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF0A121C).copy(alpha = 0.7f))
                    )
                }
            }
        }

        // Animated Card: transisi otomatis saat checkpoint terpenuhi & berpindah ke step selanjutnya
        AnimatedContent(
            targetState = activeCheckpointIndex,
            transitionSpec = {
                (slideInVertically(animationSpec = tween(350, easing = FastOutSlowInEasing)) { height -> height / 3 } + fadeIn(animationSpec = tween(300)))
                    .togetherWith(slideOutVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) { height -> -height / 3 } + fadeOut(animationSpec = tween(250)))
            },
            label = "checkpoint_detail_transition"
        ) { targetIdx ->
            val currentItem = checkpoints[targetIdx]
            val isCurrentScanning = !currentItem.isOk

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0E1A27))
                    .border(
                        1.dp,
                        when {
                            currentItem.isOk -> TvGreen.copy(alpha = 0.4f)
                            isCurrentScanning -> Color(0xFF00E5FF).copy(alpha = 0.4f)
                            else -> Color(0xFF1E334A)
                        },
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when {
                                            currentItem.isOk -> TvGreen.copy(alpha = 0.15f)
                                            isCurrentScanning -> Color(0xFF00E5FF).copy(alpha = 0.15f)
                                            else -> Color(0xFF1A2B3D)
                                        }
                                    )
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "STEP ${currentItem.number}/4",
                                    color = when {
                                        currentItem.isOk -> TvGreen
                                        isCurrentScanning -> Color(0xFF00E5FF)
                                        else -> Color(0xFF94A3B8)
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.4.sp,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = currentItem.title.substringAfter("· "),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Status Tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        currentItem.isOk -> Color(0xFF0F3A22)
                                        isCurrentScanning -> Color(0xFF0D324D)
                                        else -> Color(0xFF1A2634)
                                    }
                                )
                                .border(
                                    0.5.dp,
                                    when {
                                        currentItem.isOk -> TvGreen.copy(alpha = 0.6f)
                                        isCurrentScanning -> Color(0xFF00E5FF).copy(alpha = 0.6f)
                                        else -> Color(0xFF2C3E52)
                                    },
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when {
                                    currentItem.isOk -> "✓ SIAP"
                                    isCurrentScanning -> "⚡ TUNGGU"
                                    else -> "⏳ TUNGGU"
                                },
                                color = when {
                                    currentItem.isOk -> TvGreen
                                    isCurrentScanning -> Color(0xFF00E5FF)
                                    else -> Color(0xFF94A3B8)
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(Modifier.height(5.dp))

                    Text(
                        text = currentItem.detail,
                        color = if (currentItem.isOk) Color(0xFFE2E8F0) else TvTextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.5.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
