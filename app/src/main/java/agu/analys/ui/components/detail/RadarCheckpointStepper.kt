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
import agu.analys.config.StrategyMode
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.ui.theme.*

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
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    modifier: Modifier = Modifier
) {
    val checkpoints = remember(mtf, strategyMode) {
        val isStep1Ok = mtf.biasStatus.name == "OK" || mtf.biasOk
        val isStep2Ok = mtf.setupStatus.name == "OK" || mtf.setupOk
        val isStep3Ok = mtf.triggerStatus.name == "OK" || mtf.triggerOk
        val isStep4Ok = mtf.entryPriceStatus.name == "OK" || mtf.entryPriceOk

        val (tab1, title1, def1Ok, def1Wait) = when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "1. Tren Makro",
                "1. Tren Makro · Keselarasan EMA",
                "Tren Makro Bullish Kuat (Harga bergerak di atas EMA 20/50).",
                "Memantau keselarasan tren dan keselarasan EMA makro..."
            )
            StrategyMode.SECOND_WAVE -> listOf(
                "1. Prior Run",
                "1. Prior Run · Drawdown Reset",
                "Prior run terkonfirmasi dan koreksi drawdown reset normal.",
                "Memantau prior run dan siklus reset drawdown 4H/1H..."
            )
            StrategyMode.SCALPING -> listOf(
                "1. Bias 1H",
                "1. Bias 1H · Tren Utama",
                "Tren 1 Jam Bullish Kuat (EMA 20/50/200 selaras naik).",
                "Memantau keselarasan tren pada timeframe 1 Jam..."
            )
        }

        val (tab2, title2, def2Ok, def2Wait) = when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "2. Struktur",
                "2. Struktur · Support Lantai",
                "Struktur market higher-low & support lantai swing bertahan.",
                "Menunggu pembentukan konsolidasi atau pantulan support swing..."
            )
            StrategyMode.SECOND_WAVE -> listOf(
                "2. Base Support",
                "2. Base Support · Akumulasi 1H",
                "Lantai base support terbentuk dan volume koreksi kering.",
                "Menunggu konfirmasi pembentukan base support 1H..."
            )
            StrategyMode.SCALPING -> listOf(
                "2. Setup 15M",
                "2. Setup 15M · Struktur Pasar",
                "Struktur 15M valid (Pullback ke support EMA / Golden Cross).",
                "Menunggu pembentukan konsolidasi atau pantulan support 15M..."
            )
        }

        val (tab3, title3, def3Ok, def3Wait) = when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "3. Momentum",
                "3. Momentum · RSI & MACD Inflow",
                "Momentum RSI & histogram MACD mendukung arah swing.",
                "Menunggu trigger momentum RSI dan konfirmasi volume swing..."
            )
            StrategyMode.SECOND_WAVE -> listOf(
                "3. Inflow 15M",
                "3. Inflow 15M · Smart Money",
                "Volume beli 15M masuk dan candle konfirmasi terbentuk.",
                "Menunggu smart inflow dan higher-low 15M..."
            )
            StrategyMode.SCALPING -> listOf(
                "3. Trigger 1M",
                "3. Trigger 1M · Momentum Sinyal",
                "Breakout volume 1M & momentum RSI/MACD terkonfirmasi aktif.",
                "Menunggu trigger lonjakan volume beli dan stochastic/MACD 1M..."
            )
        }

        val (tab4, title4, def4Ok, def4Wait) = when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "4. Risk:Reward",
                "4. Area Entry · Net R:R >= 1:1.5",
                "Harga berada di zona entry dengan Net R:R optimal.",
                "Menunggu harga bergerak masuk ke toleransi zona beli swing..."
            )
            StrategyMode.SECOND_WAVE -> listOf(
                "4. Entry Ready",
                "4. Area Entry · Reclaim / Dip",
                "Harga berada di zona ideal beli dengan risk/reward optimal.",
                "Menunggu harga bergerak masuk ke dalam toleransi zona beli ideal..."
            )
            StrategyMode.SCALPING -> listOf(
                "4. Area Entry",
                "4. Area Entry · Konfirmasi Harga",
                "Harga saat ini berada di zona ideal beli dengan risk/reward optimal.",
                "Menunggu harga bergerak masuk ke dalam toleransi zona beli ideal..."
            )
        }

        listOf(
            RadarCheckpointItem(
                number = 1,
                tabLabel = tab1,
                title = title1,
                isOk = isStep1Ok,
                detail = if (isStep1Ok) mtf.biasDetail.ifBlank { def1Ok } else mtf.biasDetail.ifBlank { def1Wait }
            ),
            RadarCheckpointItem(
                number = 2,
                tabLabel = tab2,
                title = title2,
                isOk = isStep2Ok,
                detail = if (isStep2Ok) mtf.setupDetail.ifBlank { def2Ok } else mtf.setupDetail.ifBlank { def2Wait }
            ),
            RadarCheckpointItem(
                number = 3,
                tabLabel = tab3,
                title = title3,
                isOk = isStep3Ok,
                detail = if (isStep3Ok) mtf.triggerDetail.ifBlank { def3Ok } else mtf.triggerDetail.ifBlank { def3Wait }
            ),
            RadarCheckpointItem(
                number = 4,
                tabLabel = tab4,
                title = title4,
                isOk = isStep4Ok,
                detail = if (isStep4Ok) mtf.entryPriceDetail.ifBlank { def4Ok } else mtf.entryPriceDetail.ifBlank { def4Wait }
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
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "$progressPercent%",
                color = if (completed == 4) TvGreen else TvBlue,
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
                .background(TvSurfaceVariant)
        ) {
            // Fill Bar with smooth gradient animation
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animGlobalProgress)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (completed == 4) Brush.horizontalGradient(listOf(Color(0xFF00C853), TvGreen))
                        else Brush.horizontalGradient(listOf(TvBlue.copy(alpha = 0.7f), TvBlue))
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
                            .background(TvBackground.copy(alpha = 0.7f))
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
                    .background(TvSurface)
                    .border(
                        1.dp,
                        when {
                            currentItem.isOk -> TvGreen.copy(alpha = 0.4f)
                            isCurrentScanning -> TvBlue.copy(alpha = 0.4f)
                            else -> TvBorder
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
                                            isCurrentScanning -> TvBlue.copy(alpha = 0.15f)
                                            else -> TvSurfaceVariant
                                        }
                                    )
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "STEP ${currentItem.number}/4",
                                    color = when {
                                        currentItem.isOk -> TvGreen
                                        isCurrentScanning -> TvBlue
                                        else -> TvTextSecondary
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
                                color = TvTextPrimary,
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
                                        currentItem.isOk -> TvGreen.copy(alpha = 0.15f)
                                        isCurrentScanning -> TvBlue.copy(alpha = 0.15f)
                                        else -> TvSurfaceVariant
                                    }
                                )
                                .border(
                                    0.5.dp,
                                    when {
                                        currentItem.isOk -> TvGreen.copy(alpha = 0.4f)
                                        isCurrentScanning -> TvBlue.copy(alpha = 0.4f)
                                        else -> TvBorder
                                    },
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when {
                                    currentItem.isOk -> "SIAP"
                                    isCurrentScanning -> "TUNGGU"
                                    else -> "TUNGGU"
                                },
                                color = when {
                                    currentItem.isOk -> TvGreen
                                    isCurrentScanning -> TvBlue
                                    else -> TvTextSecondary
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
                        color = if (currentItem.isOk) TvTextPrimary else TvTextSecondary,
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
