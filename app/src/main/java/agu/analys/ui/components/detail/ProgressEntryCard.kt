package agu.analys.ui.components.detail

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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

/** Progress menuju Entry. Presentation only, seluruh status berasal dari engine. */
@Composable
fun ProgressEntryCard(signal: AISignalState, scalping: Boolean) {
    if (!scalping) {
        AnalysisCard {
            SectionTitle("PROGRESS MENUJU ENTRY", Icons.Default.Timeline)
            Spacer(Modifier.height(6.dp))
            Text("Aktifkan Mode Scalping untuk checklist 1H Bias → 15M Setup → 1M Trigger.", fontSize = 13.sp, color = TvTextSecondary, lineHeight = 18.sp)
        }
        return
    }

    val mtf = signal.mtf
    val stage = signal.scalpingStage
    val completed = listOf(mtf.biasStatus, mtf.setupStatus, mtf.triggerStatus, mtf.entryPriceStatus).count { it == MtfLegStatus.OK }

    val semanticStatus = when {
        completed == 4 || stage == ScalpingStage.ENTRY || stage == ScalpingStage.STRONG_ENTRY -> "ENTRY READY"
        completed == 3 -> "TRIGGER TERVALIDASI"
        completed == 2 -> "SETUP TERBENTUK"
        completed == 1 && mtf.biasStatus == MtfLegStatus.OK -> "MENUNGGU SETUP"
        completed == 1 -> "MENUNGGU KONFIRMASI"
        stage == ScalpingStage.WAIT_PULLBACK || stage == ScalpingStage.WATCH -> "MENUNGGU KONFIRMASI"
        else -> "BELUM TERSEDIA"
    }
    val displayTitle = mtf.statusTitle.ifBlank { semanticStatus }
    val statusColor = when {
        completed == 4 || stage == ScalpingStage.ENTRY || stage == ScalpingStage.STRONG_ENTRY -> TvGreen
        completed >= 1 || stage == ScalpingStage.WAIT_PULLBACK || stage == ScalpingStage.WATCH -> WarningAmber
        else -> TvTextSecondary
    }
    val pathLabel = when (mtf.path) {
        ScalpingPath.ENTRY_READY -> "Jalur: breakout / trigger terkonsolidasi"
        ScalpingPath.BOTH -> "Jalur: pullback ATAU momentum continuation"
        ScalpingPath.PULLBACK -> "Jalur utama: pullback"
        ScalpingPath.MOMENTUM_CONTINUATION -> "Jalur: tunggu konfirmasi momentum"
        ScalpingPath.NONE -> "Jalur: belum terbentuk"
    }

    val progress by animateFloatAsState(completed / 4f, tween(400, easing = FastOutSlowInEasing), label = "entry_progress")
    val idleTransition = rememberInfiniteTransition(label = "entry_monitoring")
    val idlePulse by idleTransition.animateFloat(0.75f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "entry_monitoring_pulse")

    AnalysisCard {
        Row(
            Modifier.fillMaxWidth()
                .background(statusColor.copy(alpha = 0.10f), RoundedCornerShape(9.dp))
                .border(1.dp, statusColor.copy(alpha = 0.30f), RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(9.dp).scale(if (completed == 0) idlePulse else 1f).background(statusColor, CircleShape))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("PROGRESS ENTRY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, letterSpacing = 0.7.sp)
                Text(displayTitle, color = statusColor, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(semanticStatus, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
            }
            Text("$completed/4", fontSize = 18.sp, fontWeight = FontWeight.Black, color = statusColor)
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Kedekatan menuju entry", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary)
            Spacer(Modifier.weight(1f))
            Text("$completed/4", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))) {
            if (completed == 0) {
                Box(Modifier.fillMaxSize().background(statusColor.copy(alpha = 0.14f * idlePulse), RoundedCornerShape(8.dp)))
            } else {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(statusColor, RoundedCornerShape(8.dp)))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            when (completed) {
                0 -> "Engine memantau, belum ada tahap terpenuhi"
                1 -> "1 tahap terpenuhi (1H Bias)"
                2 -> "2 tahap terpenuhi (1H Bias + 15M Setup)"
                3 -> "3 tahap terpenuhi (1M Trigger Aktif · Siapkan Indodax)"
                else -> "4/4 Kondisi terpenuhi · Siap eksekusi BUY"
            }, fontSize = 11.sp, color = TvTextSecondary
        )
        Spacer(Modifier.height(6.dp))
        Text(pathLabel, fontSize = 11.sp, color = TvTextSecondary)

        Spacer(Modifier.height(9.dp))
        AnalysisDivider()
        Spacer(Modifier.height(9.dp))
        Text("CHECKLIST MTF & EKSEKUSI", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(5.dp))
        MtfRow("1. 1H Bias", mtf.biasStatus, mtf.biasDetail.ifBlank { "Menunggu data 1H" })
        MtfRow("2. 15M Setup", mtf.setupStatus, mtf.setupDetail.ifBlank { "Menunggu data 15M" })
        MtfRow("3. 1M Trigger", mtf.triggerStatus, mtf.triggerDetail.ifBlank { "Menunggu data 1M" })
        MtfRow("4. Area Entry", mtf.entryPriceStatus, mtf.entryPriceDetail.ifBlank { "Siapkan harga entri di Indodax" })

        Spacer(Modifier.height(9.dp))
        AnalysisDivider()
        Spacer(Modifier.height(8.dp))
        Text("APA YANG DITUNGGU?", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(3.dp))
        Text(mtf.waitingFor.ifBlank { "Menunggu kondisi market lebih jelas." }, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 18.sp)
        Spacer(Modifier.height(7.dp))
        Text("SYARAT ENTRY VALID", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(3.dp))
        Text(mtf.entryCondition.ifBlank { "Bias + setup + trigger harus searah." }, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 18.sp)

        if (mtf.extended || mtf.extremeVolatility) {
            Spacer(Modifier.height(6.dp))
            val note = buildString {
                if (mtf.extended) append("RSI extended. ")
                if (mtf.extremeVolatility) append("Volatilitas ATR tinggi.")
            }
            Text(note.trim(), fontSize = 11.sp, color = WarningAmber, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(7.dp))
        Text("Skor setup: ${signal.confidence}  ·  Engine: ${stage.displayName}", fontSize = 10.sp, color = TvTextSecondary)
    }
}

@Composable
private fun MtfRow(label: String, status: MtfLegStatus, detail: String) {
    val (mark, markColor, tag) = when (status) {
        MtfLegStatus.OK -> Triple("✓", TvGreen, "OK")
        MtfLegStatus.PARTIAL -> Triple("!", WarningAmber, "PARTIAL")
        MtfLegStatus.WAITING -> Triple("•", WarningAmber, "WAIT")
        MtfLegStatus.FAIL -> Triple("×", TvRed, "NO")
        MtfLegStatus.UNKNOWN -> Triple("·", TvTextSecondary, "—")
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Color(0x0AFFFFFF), RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp).background(markColor.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Text(mark, fontSize = 13.sp, fontWeight = FontWeight.Black, color = markColor)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
            Text(detail, fontSize = 10.sp, color = TvTextSecondary, maxLines = 1)
        }
        Text(tag, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = markColor)
    }
}
