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
        Spacer(Modifier.height(4.dp))
        Text(
            "Area yang paling relevan untuk membaca posisi harga saat ini.",
            fontSize = 11.sp,
            color = TvTextSecondary,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(12.dp))

        val support = structure.support?.takeIf { it > 0 }
        val resistance = structure.resistance?.takeIf { it > 0 }

        LevelBlock(
            title = "SUPPORT RELEVAN",
            accent = TvGreen,
            level = support,
            price = price,
            distancePct = structure.supportDistancePct,
            emptyReason = if (!structure.dataEnough)
                "Data candle belum cukup untuk menemukan swing low relevan."
            else
                "Swing low relevan belum teridentifikasi."
        )

        Spacer(Modifier.height(10.dp))

        LevelBlock(
            title = "RESISTANCE RELEVAN",
            accent = TvRed,
            level = resistance,
            price = price,
            distancePct = structure.resistanceDistancePct,
            emptyReason = if (!structure.dataEnough)
                "Data candle belum cukup untuk menemukan swing high relevan."
            else
                "Swing high relevan belum teridentifikasi.",
            isResistance = true
        )

        if (price > 0 && support != null && resistance != null && resistance > support) {
            Spacer(Modifier.height(10.dp))
            PricePositionSummary(price, support, resistance)
        }

        Spacer(Modifier.height(12.dp))
        AnalysisDivider()
        Spacer(Modifier.height(10.dp))

        val hasSetup = signal.action != SignalAction.HOLD && signal.entryPrice > 0
        if (hasSetup) {
            ImportantLevelRow(InfoBlue, "Entry Area", PriceFormatter.formatPrice(signal.entryPrice))
            ImportantLevelRow(TvGreen, "TP1", signal.targetPrice1.takeIf { it > 0 }?.let(PriceFormatter::formatPrice) ?: "—")
            ImportantLevelRow(TvGreen, "TP2", signal.targetPrice2.takeIf { it > 0 }?.let(PriceFormatter::formatPrice) ?: "—")
            ImportantLevelRow(TvRed, "Stop Loss", signal.stopLoss.takeIf { it > 0 }?.let(PriceFormatter::formatPrice) ?: "—")
            ImportantLevelRow(Color(0xFF9C27B0), "Risk / Reward", signal.riskRewardRatio.ifBlank { "—" })
        } else {
            Text("ENTRY / TP / SL", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Belum ada setup entry yang valid. Fokus dulu pada reaksi harga di level support/resistance.",
                fontSize = 13.sp,
                color = TvTextPrimary,
                lineHeight = 18.sp
            )
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = accent, letterSpacing = 0.8.sp)
            if (level != null && price > 0) {
                PositionBadge(positionLabel(level, price, isResistance), accent)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (level != null) {
            Text(PriceFormatter.formatPrice(level), fontSize = 20.sp, fontWeight = FontWeight.Black, color = TvTextPrimary)
            val dist = distancePct ?: abs(price - level) / price * 100.0
            Spacer(Modifier.height(4.dp))
            val relation = when {
                !isResistance && price > level -> "${fmtPct(dist)} di atas support"
                !isResistance -> "${fmtPct(dist)} di bawah support"
                price < level -> "${fmtPct(dist)} menuju resistance"
                else -> "${fmtPct(dist)} di atas resistance"
            }
            Text(relation, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
        } else {
            Text("Belum tersedia", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
            Spacer(Modifier.height(2.dp))
            Text(emptyReason, fontSize = 12.sp, color = TvTextSecondary, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun PricePositionSummary(price: Double, support: Double, resistance: Double) {
    val range = resistance - support
    val position = ((price - support) / range).coerceIn(0.0, 1.0)
    val label = when {
        position < 0.25 -> "DEKAT SUPPORT"
        position > 0.75 -> "DEKAT RESISTANCE"
        else -> "DI TENGAH RANGE"
    }
    val accent = when {
        position < 0.25 -> TvGreen
        position > 0.75 -> TvRed
        else -> InfoBlue
    }
    Column(Modifier.fillMaxWidth().background(Color(0x0DFFFFFF), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("POSISI HARGA", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary)
            PositionBadge(label, accent)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            "Harga ${PriceFormatter.formatPrice(price)} berada ${fmtPct(position * 100.0)} dari support menuju resistance.",
            fontSize = 11.sp,
            color = TvTextPrimary,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun PositionBadge(label: String, accent: Color) {
    Box(
        Modifier.background(accent.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = accent)
    }
}

private fun positionLabel(level: Double, price: Double, isResistance: Boolean): String = when {
    isResistance && price < level -> "DI BAWAH"
    isResistance -> "TERLEWATI"
    price > level -> "DI ATAS"
    else -> "DI BAWAH"
}

@Composable
private fun ImportantLevelRow(dotColor: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 13.sp, color = TvTextSecondary, maxLines = 1)
        }
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
    }
}

private fun fmtPct(v: Double): String = String.format("%.1f%%", v)
