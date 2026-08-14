package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.AISignalState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

/**
 * Progress menuju Entry — penerjemah status engine.
 * Tidak mengubah threshold / scoring; hanya menjelaskan kondisi MTF.
 */
@Composable
fun ProgressEntryCard(signal: AISignalState, scalping: Boolean) {
    if (!scalping) return

    val parsed = parseMtfFromReasoning(signal.reasoning)
    val stage = signal.scalpingStage

    val statusTitle: String
    val statusColor: Color
    val pathLabel: String
    val waitingFor: String
    val entryCondition: String

    when (stage) {
        ScalpingStage.STRONG_ENTRY, ScalpingStage.ENTRY -> {
            statusTitle = if (signal.action == SignalAction.SELL) "SHORT ENTRY" else "ENTRY"
            statusColor = if (signal.action == SignalAction.SELL) TvRed else TvGreen
            pathLabel = "Jalur: breakout / trigger terkonsolidasi"
            waitingFor = "Tidak ada yang ditunggu — kondisi entry terpenuhi."
            entryCondition = "Bias 1H + setup 15M + trigger 1M sudah searah."
        }
        ScalpingStage.WAIT_PULLBACK -> {
            val biasOk = parsed.bias == MtfState.OK
            val setupPartial = parsed.setup == MtfState.PARTIAL || parsed.setup == MtfState.OK
            if (biasOk && setupPartial) {
                statusTitle = if (parsed.biasDirection == "bearish") "BEARISH MOMENTUM" else "BULLISH MOMENTUM"
                statusColor = WarningAmber
                pathLabel = "Jalur: momentum continuation ATAU pullback"
                waitingFor = "Menunggu konfirmasi 1M (volume / breakout / retest) atau pullback yang bersih."
                entryCondition = "Trigger 1M harus valid (volume ≥1.2× atau breakout/retest + RSI di zona)."
            } else {
                statusTitle = "MENUNGGU PULLBACK"
                statusColor = WarningAmber
                pathLabel = "Jalur utama: pullback"
                waitingFor = "Harga extended / setup 15M belum rapi — tunggu koreksi ke area setup."
                entryCondition = "Setelah pullback: setup 15M + trigger 1M harus kembali searah bias 1H."
            }
        }
        ScalpingStage.WATCH -> {
            statusTitle = "MENUNGGU KONFIRMASI"
            statusColor = WarningAmber
            pathLabel = "Setup mulai terbentuk"
            waitingFor = "Bias atau setup ada, trigger 1M belum cukup kuat."
            entryCondition = "Butuh trigger 1M yang valid agar status naik ke ENTRY."
        }
        ScalpingStage.HOLD -> {
            statusTitle = "BELUM TERSEDIA"
            statusColor = TvTextSecondary
            pathLabel = "Belum ada bias/setup jelas"
            waitingFor = "Menunggu struktur 1H dan setup 15M terbentuk."
            entryCondition = "Minimal bias 1H + setup 15M harus ada sebelum trigger relevan."
        }
    }

    AnalysisCard {
        SectionTitle("PROGRESS MENUJU ENTRY", Icons.Default.Timeline)
        Spacer(Modifier.height(10.dp))

        // Status utama
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(statusTitle, color = statusColor, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(4.dp))
        Text(pathLabel, fontSize = 12.sp, color = TvTextSecondary)

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
        Spacer(Modifier.height(12.dp))

        // Checklist MTF
        Text("CHECKLIST MTF", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(8.dp))
        MtfRow("1H  Bias", parsed.bias, parsed.biasDetail)
        MtfRow("15M Setup", parsed.setup, parsed.setupDetail)
        MtfRow("1M  Trigger", parsed.trigger, parsed.triggerDetail)

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
        Spacer(Modifier.height(10.dp))

        Text("APA YANG DITUNGGU?", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(4.dp))
        Text(waitingFor, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 18.sp)

        Spacer(Modifier.height(8.dp))
        Text("SYARAT ENTRY VALID", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(4.dp))
        Text(entryCondition, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 18.sp)

        Spacer(Modifier.height(10.dp))
        Text(
            "Skor setup: ${signal.confidence}/100  ·  Status engine: ${stage.displayName}",
            fontSize = 11.sp, color = TvTextSecondary
        )
    }
}

@Composable
private fun MtfRow(label: String, state: MtfState, detail: String) {
    val (mark, markColor) = when (state) {
        MtfState.OK -> "✅" to TvGreen
        MtfState.PARTIAL -> "⚠️" to WarningAmber
        MtfState.WAITING -> "⏳" to WarningAmber
        MtfState.FAIL -> "❌" to TvRed
        MtfState.UNKNOWN -> "—” to TvTextSecondary
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
            if (detail.isNotBlank()) {
                Text(detail, fontSize = 11.sp, color = TvTextSecondary, maxLines = 2)
            }
        }
        Text(
            when (state) {
                MtfState.OK -> "OK"
                MtfState.PARTIAL -> "PARTIAL"
                MtfState.WAITING -> "WAIT"
                MtfState.FAIL -> "NO"
                MtfState.UNKNOWN -> "—"
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = markColor
        )
    }
}

private enum class MtfState { OK, PARTIAL, WAITING, FAIL, UNKNOWN }

private data class ParsedMtf(
    val bias: MtfState,
    val biasDetail: String,
    val biasDirection: String, // bullish / bearish / mixed
    val setup: MtfState,
    val setupDetail: String,
    val trigger: MtfState,
    val triggerDetail: String
)

/** Parse baris reasoning engine: "1H: ...", "15M: ...", "1M: ..." — tanpa ubah engine. */
private fun parseMtfFromReasoning(reasoning: List<String>): ParsedMtf {
    val h1 = reasoning.firstOrNull { it.startsWith("1H:") }.orEmpty()
    val m15 = reasoning.firstOrNull { it.startsWith("15M:") }.orEmpty()
    val m1 = reasoning.firstOrNull { it.startsWith("1M:") }.orEmpty()

    val biasDir = when {
        h1.contains("bullish", ignoreCase = true) -> "bullish"
        h1.contains("bearish", ignoreCase = true) -> "bearish"
        else -> "mixed"
    }
    val bias = when (biasDir) {
        "bullish", "bearish" -> MtfState.OK
        else -> MtfState.FAIL
    }

    val setup = when {
        m15.contains("bullish setup", ignoreCase = true) || m15.contains("bearish setup", ignoreCase = true) -> MtfState.OK
        m15.contains("pullback", ignoreCase = true) || m15.contains("mixed", ignoreCase = true) -> MtfState.PARTIAL
        m15.isBlank() -> MtfState.UNKNOWN
        else -> MtfState.FAIL
    }

    val trigger = when {
        m1.contains("long trigger", ignoreCase = true) || m1.contains("short trigger", ignoreCase = true) -> MtfState.OK
        m1.contains("belum trigger", ignoreCase = true) -> MtfState.WAITING
        m1.isBlank() -> MtfState.UNKNOWN
        else -> MtfState.WAITING
    }

    return ParsedMtf(
        bias = bias,
        biasDetail = h1.removePrefix("1H:").trim().ifBlank { "Data 1H belum lengkap" },
        biasDirection = biasDir,
        setup = setup,
        setupDetail = m15.removePrefix("15M:").trim().ifBlank { "Data 15M belum lengkap" },
        trigger = trigger,
        triggerDetail = m1.removePrefix("1M:").trim().ifBlank { "Data 1M belum lengkap" }
    )
}
