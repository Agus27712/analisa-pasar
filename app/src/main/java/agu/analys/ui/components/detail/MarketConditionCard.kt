package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
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
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun MarketConditionCard(
    structure: MarketStructureSnapshot,
    indicators: TechnicalIndicators,
    signal: AISignalState,
    scalping: Boolean
) {
    if (scalping) {
        val stage = signal.scalpingStage
        val color = when (stage) {
            ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY -> if (signal.action == SignalAction.SELL) TvRed else TvGreen
            ScalpingStage.WAIT_PULLBACK, ScalpingStage.WATCH -> WarningAmber
            ScalpingStage.HOLD -> TvTextSecondary
        }
        val title = when (stage) {
            ScalpingStage.ENTRY -> if (signal.action == SignalAction.SELL) "SHORT ENTRY" else "LONG ENTRY"
            ScalpingStage.STRONG_ENTRY -> if (signal.action == SignalAction.SELL) "SHORT ENTRY KUAT" else "LONG ENTRY KUAT"
            ScalpingStage.WAIT_PULLBACK -> "TUNGGU PULLBACK"
            ScalpingStage.WATCH -> "WATCH"
            ScalpingStage.HOLD -> "TAHAN / TUNGGU"
        }
        val mtf = signal.reasoning.filter { it.startsWith("1H:") || it.startsWith("15M:") || it.startsWith("1M:") }
        AnalysisCard {
            SectionTitle("KONDISI SCALPING", Icons.Default.TrendingUp)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(color, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(title, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(7.dp))
            Text(
                when (stage) {
                    ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY -> "Bias 1H, setup 15M, dan trigger 1M sudah searah."
                    ScalpingStage.WAIT_PULLBACK -> "Bias masih mendukung, tetapi entry sekarang berisiko mengejar harga."
                    ScalpingStage.WATCH -> "Setup mulai terbentuk, tetapi trigger entry belum cukup kuat."
                    ScalpingStage.HOLD -> "Belum ada setup scalping yang cukup jelas."
                },
                color = TvTextPrimary, fontSize = 14.sp, lineHeight = 20.sp
            )
            Spacer(Modifier.height(9.dp))
            mtf.forEach { Text(it, color = TvTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            if (mtf.isEmpty()) Text("Data MTF belum lengkap.", color = TvTextSecondary, fontSize = 12.sp)
        }
    } else {
        // Kondisi Pasar = structure + indicators ONLY (no AI signal action)
        val bullishStructure = structure.trend == "Bullish structure"
        val bearishStructure = structure.trend == "Bearish structure"
        val emaBullish = indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 > indicators.ema50
        val emaBearish = indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 < indicators.ema50
        val macdBullish = indicators.macdHist.isFinite() && indicators.macdHist > 0
        val macdBearish = indicators.macdHist.isFinite() && indicators.macdHist < 0
        val bullishScore = listOf(bullishStructure, emaBullish, macdBullish).count { it }
        val bearishScore = listOf(bearishStructure, emaBearish, macdBearish).count { it }
        val title: String
        val color: Color
        val detail: String
        when {
            bullishScore >= 2 && bullishScore > bearishScore -> {
                title = "CENDERUNG NAIK"; color = TvGreen
                detail = "Struktur pasar dan indikator lebih banyak mendukung kenaikan."
            }
            bearishScore >= 2 && bearishScore > bullishScore -> {
                title = "CENDERUNG TURUN"; color = TvRed
                detail = "Struktur pasar dan indikator lebih banyak menunjukkan tekanan turun."
            }
            else -> {
                title = "MASIH CAMPURAN"; color = WarningAmber
                detail = "Struktur pasar dan indikator belum cukup searah."
            }
        }
        AnalysisCard {
            SectionTitle("KONDISI PASAR", Icons.Default.TrendingUp)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(color, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(title, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(7.dp))
            Text(detail, color = TvTextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(9.dp))
            Text(
                "RSI ${formatIndicator(indicators.rsi14)}  •  EMA20/50 ${emaRelation(indicators)}",
                color = TvTextSecondary, fontSize = 12.sp
            )
        }
    }
}

private fun formatIndicator(value: Double): String =
    if (value.isFinite()) String.format("%.2f", value) else "—"

private fun emaRelation(i: TechnicalIndicators): String = when {
    !i.ema20.isFinite() || !i.ema50.isFinite() -> "belum cukup data"
    i.ema20 > i.ema50 -> "20 > 50"
    i.ema20 < i.ema50 -> "20 < 50"
    else -> "20 ≈ 50"
}
