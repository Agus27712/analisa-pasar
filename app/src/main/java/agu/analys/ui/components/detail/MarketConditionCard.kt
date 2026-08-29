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
import agu.analys.config.StrategyMode
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.AISignalState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.util.MtfStatus
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun MarketConditionCard(
    structure: MarketStructureSnapshot,
    indicators: TechnicalIndicators,
    signal: AISignalState,
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    scalping: Boolean = strategyMode == StrategyMode.SCALPING,
    onRetry: (() -> Unit)? = null,
    mtfState: Map<agu.analys.model.Timeframe, agu.analys.util.MtfStatus> = emptyMap()
) {
    if (strategyMode == StrategyMode.SCALPING || (strategyMode == StrategyMode.SECOND_WAVE && scalping)) {
        val stage = signal.scalpingStage
        val color = when (stage) {
            ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY, ScalpingStage.EARLY_ENTRY -> if (signal.action == SignalAction.SELL) TvRed else TvGreen
            ScalpingStage.WAIT_PULLBACK, ScalpingStage.WATCH -> WarningAmber
            ScalpingStage.HOLD -> TvTextSecondary
        }
        val title = when (stage) {
            ScalpingStage.ENTRY -> if (signal.action == SignalAction.SELL) "SHORT ENTRY" else "LONG ENTRY"
            ScalpingStage.STRONG_ENTRY -> if (signal.action == SignalAction.SELL) "SHORT ENTRY KUAT" else "LONG ENTRY KUAT"
            ScalpingStage.EARLY_ENTRY -> if (signal.action == SignalAction.SELL) "EARLY SHORT" else "EARLY BUY"
            ScalpingStage.WAIT_PULLBACK -> "MOMENTUM / PULLBACK"
            ScalpingStage.WATCH -> "WATCH"
            ScalpingStage.HOLD -> "TAHAN / TUNGGU"
        }
        val mtf = signal.reasoning.filter { it.startsWith("1H:") || it.startsWith("15M:") || it.startsWith("1M:") }
        val loadedTfs = mutableListOf<String>()
        if (mtf.any { it.startsWith("1H:") }) loadedTfs.add("1H")
        if (mtf.any { it.startsWith("15M:") }) loadedTfs.add("15M")
        if (mtf.any { it.startsWith("1M:") }) loadedTfs.add("1M")

        AnalysisCard {
            SectionTitle(if (strategyMode == StrategyMode.SECOND_WAVE) "KONDISI SECOND-WAVE" else "KONDISI SCALPING", Icons.Default.TrendingUp)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(color, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(title, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(7.dp))
            Text(
                when (stage) {
                    ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY ->
                        "Bias 1H, setup 15M, dan trigger 1M sudah searah."
                    ScalpingStage.EARLY_ENTRY ->
                        "Breakout 1M terdeteksi lebih awal."
                    ScalpingStage.WAIT_PULLBACK ->
                        "Bias masih mendukung. Ada dua jalur: tunggu pullback bersih, atau konfirmasi momentum continuation di 1M."
                    ScalpingStage.WATCH ->
                        "Setup mulai terbentuk, tetapi trigger entry belum cukup kuat."
                    ScalpingStage.HOLD ->
                        "Belum ada setup scalping yang cukup jelas."
                },
                color = TvTextPrimary, fontSize = 14.sp, lineHeight = 20.sp
            )
            Spacer(Modifier.height(9.dp))
            if (mtf.isNotEmpty()) {
                mtf.forEach { Text(it, color = TvTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            // Sumber kebenaran status data = mtfState (cache), bukan signal.reasoning
            val readyFromCache = if (mtfState.isNotEmpty()) {
                listOf(Timeframe.H1, Timeframe.M15, Timeframe.M1).count { mtfState[it] == MtfStatus.READY }
            } else loadedTfs.size
            if (readyFromCache < 3) {
                Spacer(Modifier.height(8.dp))
                val syncingLabels = listOf(Timeframe.H1 to "1H", Timeframe.M15 to "15M", Timeframe.M1 to "1M")
                    .filter { (tf, _) -> mtfState[tf] != MtfStatus.READY }
                    .map { it.second }
                MtfIncompleteContent(
                    loadedTimeframes = if (mtfState.isNotEmpty()) {
                        listOf(Timeframe.H1 to "1H", Timeframe.M15 to "15M", Timeframe.M1 to "1M")
                            .filter { (tf, _) -> mtfState[tf] == MtfStatus.READY }
                            .map { it.second }
                    } else loadedTfs,
                    message = when {
                        mtfState.isEmpty() && mtf.isEmpty() ->
                            "Data MTF belum lengkap. Sedang menyelaraskan riwayat candle..."
                        syncingLabels.isNotEmpty() ->
                            "Menyelaraskan ${syncingLabels.joinToString(", ")} (${readyFromCache}/3 timeframe siap)."
                        else ->
                            "Sebagian data MTF belum lengkap (${readyFromCache}/3 timeframe siap)."
                    },
                    onRetry = onRetry,
                    mtfState = mtfState
                )
            }
        }
    } else {
        val bullishStructure = structure.trend.contains("Bull", true)
        val bearishStructure = structure.trend.contains("Bear", true)
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
                title = "STRUKTUR CENDERUNG NAIK"; color = TvGreen
                detail = "Struktur pasar dan indikator teknikal makro mendukung kenaikan harga."
            }
            bearishScore >= 2 && bearishScore > bullishScore -> {
                title = "STRUKTUR CENDERUNG TURUN"; color = TvRed
                detail = "Struktur pasar dan indikator teknikal makro menunjukkan tekanan turun."
            }
            else -> {
                title = "KONSOLIDASI / CAMPURAN"; color = WarningAmber
                detail = "Struktur pasar dan indikator makro sedang berkonsolidasi di area rentang harga."
            }
        }
        AnalysisCard {
            SectionTitle(if (strategyMode == StrategyMode.SWING) "KONDISI SWING TRADING" else "KONDISI PASAR", Icons.Default.TrendingUp)
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
                "RSI ${formatIndicator(indicators.rsi14)}  •  EMA20/50 ${emaRelation(indicators)}  •  Hist MACD ${formatIndicator(indicators.macdHist)}",
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
