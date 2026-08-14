package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import kotlin.math.abs

@Composable
fun ImportantLevelsCard(signal: AISignalState, structure: MarketStructureSnapshot, price: Double) {
    AnalysisCard {
        Text("LEVEL PENTING", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = InfoBlue, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(10.dp))

        // Support
        val support = structure.support?.takeIf { it > 0 }
        if (support != null) {
            val dist = structure.supportDistancePct
                ?: if (price > 0) abs(price - support) / price * 100.0 else null
            val pos = when {
                dist == null -> null
                price > support -> "+${fmtPct(dist)} di atas support"
                price < support -> "-${fmtPct(dist)} di bawah support"
                else -> "tepat di support"
            }
            ImportantLevelRow(Color(0xFF32D74B), "Support relevan", PriceFormatter.formatPrice(support))
            if (pos != null) {
                Text(pos, fontSize = 12.sp, color = TvTextSecondary, modifier = Modifier.padding(start = 18.dp, bottom = 4.dp))
            }
        } else {
            ImportantLevelRow(Color(0xFF32D74B), "Support relevan", "Belum tersedia")
            Text(
                if (!structure.dataEnough) "Data candle belum cukup untuk hitung swing support."
                else "Swing low relevan belum teridentifikasi di rentang candle saat ini.",
                fontSize = 12.sp, color = TvTextSecondary,
                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp)
            )
        }

        // Resistance
        val resistance = structure.resistance?.takeIf { it > 0 }
        if (resistance != null) {
            val dist = structure.resistanceDistancePct
                ?: if (price > 0) abs(resistance - price) / price * 100.0 else null
            val pos = when {
                dist == null -> null
                price < resistance -> "${fmtPct(dist)} dari resistance"
                price > resistance -> "+${fmtPct(dist)} di atas resistance"
                else -> "tepat di resistance"
            }
            ImportantLevelRow(TvRed, "Resistance relevan", PriceFormatter.formatPrice(resistance))
            if (pos != null) {
                Text(pos, fontSize = 12.sp, color = TvTextSecondary, modifier = Modifier.padding(start = 18.dp, bottom = 4.dp))
            }
        } else {
            ImportantLevelRow(TvRed, "Resistance relevan", "Belum tersedia")
            Text(
                if (!structure.dataEnough) "Data candle belum cukup untuk hitung swing resistance."
                else "Swing high relevan belum teridentifikasi di rentang candle saat ini.",
                fontSize = 12.sp, color = TvTextSecondary,
                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
        Spacer(Modifier.height(8.dp))

        val hasSetup = signal.action != SignalAction.HOLD && signal.entryPrice > 0
        if (hasSetup) {
            ImportantLevelRow(InfoBlue, "Entry Area", PriceFormatter.formatPrice(signal.entryPrice))
            ImportantLevelRow(
                Color(0xFF32D74B), "Take Profit 1 (TP1)",
                signal.targetPrice1.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum dihitung"
            )
            ImportantLevelRow(
                Color(0xFF32D74B), "Take Profit 2 (TP2)",
                signal.targetPrice2.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum dihitung"
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
            Spacer(Modifier.height(8.dp))
            ImportantLevelRow(
                TvRed, "Stop Loss (SL)",
                signal.stopLoss.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum dihitung"
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
            Spacer(Modifier.height(8.dp))
            ImportantLevelRow(Color(0xFF9C27B0), "Risk / Reward", signal.riskRewardRatio.ifBlank { "—" })
        } else {
            ImportantLevelRow(InfoBlue, "Entry Area", "Setup belum valid")
            Text(
                "Entry, TP, dan SL baru muncul setelah bias + setup + trigger searah.",
                fontSize = 12.sp, color = TvTextSecondary,
                modifier = Modifier.padding(start = 18.dp, bottom = 6.dp)
            )
            ImportantLevelRow(Color(0xFF32D74B), "Take Profit 1 (TP1)", "Menunggu setup")
            ImportantLevelRow(Color(0xFF32D74B), "Take Profit 2 (TP2)", "Menunggu setup")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
            Spacer(Modifier.height(8.dp))
            ImportantLevelRow(TvRed, "Stop Loss (SL)", "Menunggu setup")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
            Spacer(Modifier.height(8.dp))
            ImportantLevelRow(Color(0xFF9C27B0), "Risk / Reward", "Belum relevan")
            if (price > 0 && support != null && resistance != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pantau reaksi harga di antara support dan resistance sebelum entry valid.",
                    fontSize = 12.sp, color = TvTextSecondary, lineHeight = 18.sp
                )
            }
        }

        if (structure.structureExplanation.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(structure.structureExplanation, fontSize = 11.sp, color = TvTextSecondary, lineHeight = 16.sp)
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
