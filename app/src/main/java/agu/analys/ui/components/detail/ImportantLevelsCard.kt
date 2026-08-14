package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.AISignalState
import agu.analys.model.SignalAction
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import kotlin.math.abs

@Composable
fun ImportantLevelsCard(signal: AISignalState, structure: MarketStructureSnapshot, price: Double) {
    AnalysisCard {
        Text("LEVEL PENTING", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = InfoBlue, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(12.dp))

        val support = structure.support?.takeIf { it > 0 }
        val resistance = structure.resistance?.takeIf { it > 0 }

        // Support block — visual besar
        LevelBlock(
            title = "SUPPORT",
            accent = Color(0xFF32D74B),
            level = support,
            price = price,
            distancePct = structure.supportDistancePct,
            emptyReason = if (!structure.dataEnough)
                "Data candle belum cukup untuk hitung swing support."
            else
                "Swing low relevan belum teridentifikasi."
        )

        Spacer(Modifier.height(10.dp))

        // Resistance block
        LevelBlock(
            title = "RESISTANCE",
            accent = TvRed,
            level = resistance,
            price = price,
            distancePct = structure.resistanceDistancePct,
            emptyReason = if (!structure.dataEnough)
                "Data candle belum cukup untuk hitung swing resistance."
            else
                "Swing high relevan belum teridentifikasi.",
            isResistance = true
        )

        Spacer(Modifier.height(12.dp))
        AnalysisDivider()
        Spacer(Modifier.height(10.dp))

        val hasSetup = signal.action != SignalAction.HOLD && signal.entryPrice > 0
        if (hasSetup) {
            ImportantLevelRow(InfoBlue, "Entry Area", PriceFormatter.formatPrice(signal.entryPrice))
            ImportantLevelRow(
                Color(0xFF32D74B), "TP1",
                signal.targetPrice1.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "—"
            )
            ImportantLevelRow(
                Color(0xFF32D74B), "TP2",
                signal.targetPrice2.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "—"
            )
            ImportantLevelRow(
                TvRed, "Stop Loss",
                signal.stopLoss.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "—"
            )
            ImportantLevelRow(Color(0xFF9C27B0), "Risk / Reward", signal.riskRewardRatio.ifBlank { "—" })
        } else {
            Text(
                "ENTRY / TP / SL", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                color = TvTextSecondary, letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Setup belum valid — entry, TP, dan SL baru muncul setelah bias + setup + trigger searah.",
                fontSize = 13.sp, color = TvTextPrimary, lineHeight = 18.sp
            )
            if (price > 0 && support != null && resistance != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pantau reaksi harga di antara support dan resistance sebelum entry.",
                    fontSize = 12.sp, color = TvTextSecondary, lineHeight = 17.sp
                )
            }
        }

        if (structure.structureExplanation.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(structure.structureExplanation, fontSize = 11.sp, color = TvTextSecondary, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun LevelBlock(
    title: String,
    accent: Color,
    level: Double?,
    price: Double,
    distancePct: Double?,
    emptyReason: String,
    isResistance: Boolean = false
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = accent, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(6.dp))
        if (level != null) {
            Text(
                PriceFormatter.formatPrice(level),
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = TvTextPrimary
            )
            val dist = distancePct ?: if (price > 0) abs(price - level) / price * 100.0 else null
            if (dist != null) {
                Spacer(Modifier.height(4.dp))
                val posText = when {
                    !isResistance && price > level -> "+${fmtPct(dist)} di atas support"
                    !isResistance && price < level -> "-${fmtPct(dist)} di bawah support"
                    isResistance && price < level -> "${fmtPct(dist)} dari resistance"
                    isResistance && price > level -> "+${fmtPct(dist)} di atas resistance"
                    else -> "tepat di level"
                }
                Text(
                    posText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
        } else {
            Text("Belum tersedia", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
            Spacer(Modifier.height(2.dp))
            Text(emptyReason, fontSize = 12.sp, color = TvTextSecondary, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ImportantLevelRow(dotColor: Color, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 13.sp, color = TvTextSecondary, maxLines = 1)
        }
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
    }
}

private fun fmtPct(v: Double): String = String.format("%.1f%%", v)
