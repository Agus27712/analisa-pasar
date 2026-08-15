package agu.analys.ui.components.detail

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.AISignalState
import agu.analys.model.MtfLegStatus
import agu.analys.model.ScalpingPath
import agu.analys.model.ScalpingStage
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

/**
 * Progress menuju Entry — 100% dari ScalpingMtfSnapshot engine.
 * Tidak parse reasoning string. Tidak hitung ulang threshold.
 */
@Composable
fun ProgressEntryCard(signal: AISignalState, scalping: Boolean) {
    if (!scalping) {
        AnalysisCard {
            SectionTitle("PROGRESS MENUJU ENTRY", Icons.Default.Timeline)
            Spacer(Modifier.height(8.dp))
            Text(
                "Aktifkan Mode Scalping di atas untuk melihat checklist 1H → 15M → 1M.",
                fontSize = 13.sp, color = TvTextSecondary, lineHeight = 18.sp
            )
        }
        return
    }

    val mtf = signal.mtf
    val stage = signal.scalpingStage
    val statusColor = when (stage) {
        ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY -> TvGreen
        ScalpingStage.WAIT_PULLBACK, ScalpingStage.WATCH -> WarningAmber
        ScalpingStage.HOLD -> TvTextSecondary
    }
    val pathLabel = when (mtf.path) {
        ScalpingPath.ENTRY_READY -> "Jalur: breakout / trigger terkonsolidasi"
        ScalpingPath.BOTH -> "Jalur: pullback ATAU momentum continuation"
        ScalpingPath.PULLBACK -> "Jalur utama: pullback"
        ScalpingPath.MOMENTUM_CONTINUATION -> "Jalur: tunggu konfirmasi momentum"
        ScalpingPath.NONE -> "Jalur: belum terbentuk"
    }

    val completed = listOf(mtf.biasStatus, mtf.setupStatus, mtf.triggerStatus).count { it == MtfLegStatus.OK }
    val progressTarget = completed / 3f
    val progress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "entry_progress"
    )
    val stagePulse by animateFloatAsState(
        targetValue = if (completed in 1..2) 1.035f else 1f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "entry_stage_pulse"
    )

    AnalysisCard {
        Row(
            Modifier
                .fillMaxWidth()
                .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .scale(stagePulse),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("PROGRESS ENTRY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, letterSpacing = 0.8.sp)
                Text(
                    mtf.statusTitle.ifBlank { stage.displayName },
                    color = statusColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Kedekatan menuju entry", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(statusColor, RoundedCornerShape(8.dp))
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "$completed dari 3 tahap terpenuhi",
            fontSize = 10.sp,
            color = TvTextSecondary
        )

        Spacer(Modifier.height(8.dp))
        Text(pathLabel, fontSize = 12.sp, color = TvTextSecondary)

        Spacer(Modifier.height(12.dp))
        AnalysisDivider()
        Spacer(Modifier.height(12.dp))

        Text("CHECKLIST MTF", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(8.dp))
        MtfRow("1H  Bias", mtf.biasStatus, mtf.biasDetail.ifBlank { "Menunggu data 1H" })
        MtfRow("15M Setup", mtf.setupStatus, mtf.setupDetail.ifBlank { "Menunggu data 15M" })
        MtfRow("1M  Trigger", mtf.triggerStatus, mtf.triggerDetail.ifBlank { "Menunggu data 1M" })

        Spacer(Modifier.height(12.dp))
        AnalysisDivider()
        Spacer(Modifier.height(10.dp))

        Text("APA YANG DITUNGGU?", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            mtf.waitingFor.ifBlank { "Menunggu kondisi market lebih jelas." },
            fontSize = 13.sp, color = TvTextPrimary, lineHeight = 18.sp
        )

        Spacer(Modifier.height(8.dp))
        Text("SYARAT ENTRY VALID", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            mtf.entryCondition.ifBlank { "Bias + setup + trigger harus searah." },
            fontSize = 13.sp, color = TvTextPrimary, lineHeight = 18.sp
        )

        if (mtf.extended || mtf.extremeVolatility) {
            Spacer(Modifier.height(8.dp))
            val note = buildString {
                if (mtf.extended) append("RSI extended. ")
                if (mtf.extremeVolatility) append("Volatilitas ATR tinggi.")
            }
            Text(note.trim(), fontSize = 12.sp, color = WarningAmber, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Skor setup: ${signal.confidence}/100  ·  Engine: ${stage.displayName}",
            fontSize = 11.sp, color = TvTextSecondary
        )
    }
}

@Composable
private fun MtfRow(label: String, status: MtfLegStatus, detail: String) {
    val (mark, markColor, tag) = when (status) {
        MtfLegStatus.OK -> Triple("✅", TvGreen, "OK")
        MtfLegStatus.PARTIAL -> Triple("⚠️", WarningAmber, "PARTIAL")
        MtfLegStatus.WAITING -> Triple("⏳", WarningAmber, "WAIT")
        MtfLegStatus.FAIL -> Triple("❌", TvRed, "NO")
        MtfLegStatus.UNKNOWN -> Triple("—", TvTextSecondary, "—")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color(0x0AFFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(mark, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
            Text(detail, fontSize = 11.sp, color = TvTextSecondary, maxLines = 2)
        }
        Text(tag, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = markColor)
    }
}
