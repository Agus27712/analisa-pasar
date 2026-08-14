package agu.analys.ui.components.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import agu.analys.util.PriceFormatter

@Composable
fun RecommendationCard(signal: AISignalState, scalping: Boolean) {
    if (scalping) {
        val stage = signal.scalpingStage
        val color = when (stage) {
            ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY ->
                if (signal.action == SignalAction.SELL) TvRed else TvGreen
            else -> WarningAmber
        }
        val title = when (stage) {
            ScalpingStage.ENTRY -> if (signal.action == SignalAction.SELL) "SHORT ENTRY" else "LONG ENTRY"
            ScalpingStage.STRONG_ENTRY -> if (signal.action == SignalAction.SELL) "SHORT ENTRY KUAT" else "LONG ENTRY KUAT"
            ScalpingStage.WAIT_PULLBACK -> "TUNGGU PULLBACK"
            ScalpingStage.WATCH -> "WATCH"
            ScalpingStage.HOLD -> "TAHAN / TUNGGU"
        }
        val description = when (stage) {
            ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY ->
                "Trigger 1M sudah searah dengan bias 1H dan setup 15M."
            ScalpingStage.WAIT_PULLBACK ->
                "Trend masih mendukung. Tunggu pullback selesai dan trigger 1M kembali searah."
            ScalpingStage.WATCH ->
                "Bias atau setup mulai terbentuk. Tunggu konfirmasi 1M sebelum masuk."
            ScalpingStage.HOLD ->
                "Belum ada setup scalping yang cukup kuat untuk entry."
        }
        AnalysisCard {
            SectionTitle(
                "REKOMENDASI",
                when {
                    signal.action == SignalAction.SELL -> Icons.Default.TrendingDown
                    stage == ScalpingStage.ENTRY || stage == ScalpingStage.STRONG_ENTRY -> Icons.Default.TrendingUp
                    else -> Icons.Default.Shield
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 23.sp, fontWeight = FontWeight.Black, color = color)
            Spacer(Modifier.height(5.dp))
            Text(description, fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp)
            if (stage == ScalpingStage.ENTRY || stage == ScalpingStage.STRONG_ENTRY) {
                Spacer(Modifier.height(10.dp))
                Text("Entry  ${PriceFormatter.formatPrice(signal.entryPrice)}", fontSize = 13.sp, color = TvTextPrimary)
                Text("SL     ${PriceFormatter.formatPrice(signal.stopLoss)}", fontSize = 13.sp, color = TvTextPrimary)
                Text("TP1    ${PriceFormatter.formatPrice(signal.targetPrice1)}", fontSize = 13.sp, color = TvTextPrimary)
                Text("TP2    ${PriceFormatter.formatPrice(signal.targetPrice2)}", fontSize = 13.sp, color = TvTextPrimary)
                Text(signal.riskRewardRatio, fontSize = 12.sp, color = TvTextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(9.dp))
            Text("Kekuatan setup: ${signal.confidence}/100", fontSize = 13.sp, color = TvTextSecondary)
        }
    } else {
        val color = when (signal.action) {
            SignalAction.BUY -> TvGreen
            SignalAction.SELL -> TvRed
            SignalAction.HOLD -> WarningAmber
        }
        val title = when (signal.action) {
            SignalAction.BUY -> "BISA PERTIMBANGKAN BELI"
            SignalAction.SELL -> "PERTIMBANGKAN JUAL"
            SignalAction.HOLD -> "TAHAN / TUNGGU"
        }
        val description = when (signal.action) {
            SignalAction.BUY -> "Tren cukup mendukung, tetapi tetap gunakan area masuk dan batas risiko."
            SignalAction.SELL -> "Tekanan turun lebih dominan. Jangan buru-buru masuk sebelum struktur membaik."
            SignalAction.HOLD -> "Belum ada alasan yang cukup kuat untuk masuk atau keluar sekarang."
        }
        AnalysisCard {
            SectionTitle(
                "REKOMENDASI",
                when (signal.action) {
                    SignalAction.BUY -> Icons.Default.TrendingUp
                    SignalAction.SELL -> Icons.Default.TrendingDown
                    SignalAction.HOLD -> Icons.Default.Shield
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 23.sp, fontWeight = FontWeight.Black, color = color)
            Spacer(Modifier.height(5.dp))
            Text(description, fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp)
            Spacer(Modifier.height(9.dp))
            Text("Kekuatan sinyal: ${signal.confidence}/100", fontSize = 13.sp, color = TvTextSecondary)
        }
    }
}
