package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import kotlin.math.abs

/**
 * Compact market-structure card.
 * Its job is to answer one question quickly: where is price relative to the
 * nearest meaningful support/resistance? Entry/TP/SL stay in their own cards.
 */
@Composable
fun ImportantLevelsCard(signal: AISignalState, structure: MarketStructureSnapshot, price: Double) {
    AnalysisCard {
        Text(
            "LEVEL PENTING",
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            color = InfoBlue,
            letterSpacing = 0.8.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "Support dan resistance yang paling relevan dengan harga sekarang.",
            fontSize = 11.sp,
            color = TvTextSecondary,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))

        val support = structure.support?.takeIf { it > 0.0 }
        val resistance = structure.resistance?.takeIf { it > 0.0 }
        val validPrice = price > 0.0 && price.isFinite()

        if (validPrice && support != null && resistance != null && resistance > support) {
            PricePositionSummary(price, support, resistance)
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactLevelBlock(
                modifier = Modifier.weight(1f),
                title = "SUPPORT",
                accent = TvGreen,
                level = support,
                price = price,
                isResistance = false,
                emptyReason = if (!structure.dataEnough) "Data candle belum cukup." else "Swing low belum tersedia."
            )
            CompactLevelBlock(
                modifier = Modifier.weight(1f),
                title = "RESISTANCE",
                accent = TvRed,
                level = resistance,
                price = price,
                isResistance = true,
                emptyReason = if (!structure.dataEnough) "Data candle belum cukup." else "Swing high belum tersedia."
            )
        }

        if (structure.structureExplanation.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                structure.structureExplanation,
                fontSize = 10.sp,
                color = TvTextSecondary,
                lineHeight = 14.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun CompactLevelBlock(
    modifier: Modifier,
    title: String,
    accent: Color,
    level: Double?,
    price: Double,
    isResistance: Boolean,
    emptyReason: String
) {
    Column(
        modifier
            .background(accent.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = accent, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(4.dp))
        if (level != null && level.isFinite()) {
            Text(PriceFormatter.formatPrice(level), fontSize = 16.sp, fontWeight = FontWeight.Black, color = TvTextPrimary, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            val distance = abs(price - level) / price.coerceAtLeast(0.00000001) * 100.0
            val relation = when {
                !isResistance && price > level -> "${fmtPct(distance)} di atas"
                !isResistance -> "${fmtPct(distance)} di bawah"
                price < level -> "${fmtPct(distance)} menuju"
                else -> "sudah terlewati"
            }
            Text(relation, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
        } else {
            Text("Belum tersedia", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
            Text(emptyReason, fontSize = 9.sp, color = TvTextSecondary, lineHeight = 12.sp, maxLines = 2)
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
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0x0DFFFFFF), RoundedCornerShape(10.dp))
            .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("POSISI HARGA", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary)
                Spacer(Modifier.height(2.dp))
                Text(PriceFormatter.formatPrice(price), fontSize = 15.sp, fontWeight = FontWeight.Black, color = TvTextPrimary)
            }
            PositionBadge(label, accent)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))) {
            Box(
                Modifier
                    .fillMaxWidth(position.toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(accent, RoundedCornerShape(8.dp))
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(PriceFormatter.formatPrice(support), fontSize = 8.sp, color = TvGreen, maxLines = 1)
            Text(PriceFormatter.formatPrice(resistance), fontSize = 8.sp, color = TvRed, maxLines = 1)
        }
    }
}

@Composable
private fun PositionBadge(label: String, accent: Color) {
    Box(
        Modifier
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = accent, maxLines = 1)
    }
}

private fun fmtPct(value: Double): String = String.format("%.1f%%", value)
