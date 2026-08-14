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

@Composable
fun ImportantLevelsCard(signal: AISignalState, structure: MarketStructureSnapshot, price: Double) {
    AnalysisCard {
        Text("LEVEL PENTING", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = InfoBlue, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(10.dp))
        ImportantLevelRow(Color(0xFF32D74B), "Support Terdekat", structure.support?.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
        ImportantLevelRow(TvRed, "Resistance Terdekat", structure.resistance?.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
        Spacer(Modifier.height(8.dp))
        if (signal.action != SignalAction.HOLD && signal.entryPrice > 0) {
            ImportantLevelRow(InfoBlue, "Entry Area", PriceFormatter.formatPrice(signal.entryPrice))
            ImportantLevelRow(Color(0xFF32D74B), "Take Profit 1 (TP1)", signal.targetPrice1.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
            ImportantLevelRow(Color(0xFF32D74B), "Take Profit 2 (TP2)", signal.targetPrice2.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
            Spacer(Modifier.height(8.dp))
            ImportantLevelRow(TvRed, "Stop Loss (SL)", signal.stopLoss.takeIf { it > 0 }?.let { PriceFormatter.formatPrice(it) } ?: "Belum tersedia")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
            Spacer(Modifier.height(8.dp))
            ImportantLevelRow(Color(0xFF9C27B0), "Risk / Reward", signal.riskRewardRatio.ifBlank { "1 : 1,5" })
        } else {
            ImportantLevelRow(InfoBlue, "Entry Area", "Belum ada setup")
            ImportantLevelRow(Color(0xFF32D74B), "Take Profit 1 (TP1)", "Belum tersedia")
            ImportantLevelRow(Color(0xFF32D74B), "Take Profit 2 (TP2)", "Belum tersedia")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
            Spacer(Modifier.height(8.dp))
            ImportantLevelRow(TvRed, "Stop Loss (SL)", "Belum tersedia")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
            Spacer(Modifier.height(8.dp))
            ImportantLevelRow(Color(0xFF9C27B0), "Risk / Reward", "Belum tersedia")
            if (price > 0 && structure.support != null && structure.resistance != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pantau reaksi harga di antara support dan resistance sebelum menentukan entry.",
                    fontSize = 12.sp, color = TvTextSecondary, lineHeight = 18.sp
                )
            }
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
