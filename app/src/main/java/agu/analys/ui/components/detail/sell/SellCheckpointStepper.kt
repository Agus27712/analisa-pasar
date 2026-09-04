package agu.analys.ui.components.detail.sell

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CheckpointStatus
import agu.analys.model.PositionContext
import agu.analys.model.SellCheckpointEvaluator
import agu.analys.model.SellLifecycleState
import agu.analys.model.SellSignalState
import agu.analys.model.TradingCheckpointItem
import agu.analys.ui.theme.*

private data class SellPhaseInfo(
    val title: String,
    val color: Color,
    val badge: String,
    val description: String
)

@Composable
fun SellCheckpointStepper(
    context: PositionContext,
    sellSignal: SellSignalState,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    val items = remember(context, sellSignal, quoteAsset) {
        SellCheckpointEvaluator.evaluate(context, sellSignal, quoteAsset)
    }

    if (items.isEmpty()) return

    // Tentukan active tab: jika ada WARNING atau READY pada step tertentu, prioritaskan tab tersebut
    val defaultStep = remember(items) {
        val warningIdx = items.indexOfFirst { it.status == CheckpointStatus.WARNING }
        if (warningIdx != -1) warningIdx + 1
        else {
            val readyIdx = items.indexOfFirst { it.status == CheckpointStatus.READY }
            if (readyIdx != -1) readyIdx + 1
            else {
                val activeIdx = items.indexOfFirst { it.status == CheckpointStatus.ACTIVE }
                if (activeIdx != -1) activeIdx + 1 else 1
            }
        }
    }

    var selectedStep by remember(defaultStep) { mutableStateOf(defaultStep) }

    val phaseInfo = when (sellSignal.state) {
        SellLifecycleState.READY_TO_SELL -> SellPhaseInfo(
            title = "FASE EKSEKUSI: SIAP JUAL",
            color = TvGreen,
            badge = sellSignal.reason.ifEmpty { "Target Tercapai" },
            description = "Kondisi keluar terpenuhi optimal. Disarankan merealisasikan profit."
        )
        SellLifecycleState.APPROACHING_TARGET -> SellPhaseInfo(
            title = "FASE PERSIAPAN: DEKAT TARGET",
            color = TvOrange,
            badge = sellSignal.reason.ifEmpty { "Toleransi 2% ke TP1" },
            description = "Harga pasar mendekati target TP1. Bersiap ambil keputusan jual."
        )
        SellLifecycleState.TRAILING_TRIGGERED -> SellPhaseInfo(
            title = "FASE PROTEKSI: TRAILING STOP",
            color = TvOrange,
            badge = "Amankan Posisi",
            description = "Harga berbalik dari titik puncak. Sistem menyarankan pengamanan posisi."
        )
        SellLifecycleState.STOP_LOSS_HIT -> SellPhaseInfo(
            title = "FASE DEFENSIVE: STOP LOSS",
            color = TvRed,
            badge = "Cut Loss",
            description = "Batas risiko terlewati. Amankan sisa modal Anda segera."
        )
        SellLifecycleState.MONITORING -> SellPhaseInfo(
            title = "FASE PEMANTAUAN: POSISI TERJAGA",
            color = TvBlue,
            badge = "Terkendali",
            description = "Posisi aktif terpantau normal. Parameter risiko dan TP sedang dievaluasi."
        )
        SellLifecycleState.NOT_HOLDING -> SellPhaseInfo(
            title = "BELUM ADA POSISI",
            color = TvTextSecondary,
            badge = "Standby",
            description = "Tidak ada aset holding terdaftar untuk koin ini."
        )
    }

    val phaseIcon = when (sellSignal.state) {
        SellLifecycleState.READY_TO_SELL -> Icons.Default.CheckCircle
        SellLifecycleState.APPROACHING_TARGET -> Icons.Default.Info
        SellLifecycleState.TRAILING_TRIGGERED -> Icons.Default.Warning
        SellLifecycleState.STOP_LOSS_HIT -> Icons.Default.Warning
        SellLifecycleState.MONITORING -> Icons.Default.Shield
        SellLifecycleState.NOT_HOLDING -> Icons.Default.Info
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Banner Status Semantik Fase Posisi
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(phaseInfo.color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .border(1.dp, phaseInfo.color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = phaseIcon,
                    contentDescription = null,
                    tint = phaseInfo.color,
                    modifier = Modifier.size(15.dp)
                )
                Column {
                    Text(
                        text = phaseInfo.title,
                        color = phaseInfo.color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = phaseInfo.description,
                        color = TvTextSecondary,
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .background(phaseInfo.color.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .border(0.8.dp, phaseInfo.color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = phaseInfo.badge,
                    color = phaseInfo.color,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Subheader PILAR EVALUASI
        Text(
            text = "4 PILAR KESEHATAN POSISI",
            color = TvTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )

        // Tab Stepper baris atas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                val isSelected = item.number == selectedStep
                val (stepColor, stepBg) = when (item.status) {
                    CheckpointStatus.COMPLETED -> Pair(TvGreen, TvGreen.copy(alpha = 0.15f))
                    CheckpointStatus.READY -> Pair(TvOrange, TvOrange.copy(alpha = 0.15f))
                    CheckpointStatus.ACTIVE -> Pair(TvBlue, TvBlue.copy(alpha = 0.15f))
                    CheckpointStatus.WARNING -> Pair(TvRed, TvRed.copy(alpha = 0.2f))
                    CheckpointStatus.MONITORING -> Pair(TvTextSecondary, TvSurface)
                    CheckpointStatus.LOCKED -> Pair(TvTextSecondary, TvSurface)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) stepBg else TvSurface)
                        .border(
                            width = if (isSelected) 1.2.dp else 0.8.dp,
                            color = if (isSelected) stepColor else TvBorder,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { selectedStep = item.number }
                        .padding(vertical = 5.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.tabLabel,
                        color = if (isSelected) stepColor else TvTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Active Step Content
        val currentItem = items.find { it.number == selectedStep } ?: items.first()
        val (activeColor, _) = when (currentItem.status) {
            CheckpointStatus.COMPLETED -> Pair(TvGreen, TvGreen.copy(alpha = 0.15f))
            CheckpointStatus.READY -> Pair(TvOrange, TvOrange.copy(alpha = 0.15f))
            CheckpointStatus.ACTIVE -> Pair(TvBlue, TvBlue.copy(alpha = 0.15f))
            CheckpointStatus.WARNING -> Pair(TvRed, TvRed.copy(alpha = 0.2f))
            CheckpointStatus.MONITORING -> Pair(TvTextSecondary, TvSurface)
            CheckpointStatus.LOCKED -> Pair(TvTextSecondary, TvSurface)
        }

        AnimatedContent(
            targetState = currentItem,
            transitionSpec = {
                (slideInVertically { height -> height / 2 } + fadeIn(tween(200)))
                    .togetherWith(slideOutVertically { height -> -height / 2 } + fadeOut(tween(150)))
            },
            label = "sell_step_content"
        ) { target ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurface, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = target.title,
                        color = activeColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).basicMarquee()
                    )

                    Box(
                        modifier = Modifier
                            .background(activeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .border(0.8.dp, activeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = target.status.name,
                            color = activeColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Text(
                    text = target.detail,
                    color = TvTextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
