package agu.analys.ui.components.detail

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.FeeCalculator
import agu.analys.model.AISignalState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.ui.animation.AnimatedMetricText
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter

/**
 * Rekomendasi eksekusi — mockup BUY READY block.
 * Level & fee dari engine real + AppPreferences fee.
 */
@Composable
fun RecommendationCard(signal: AISignalState, scalping: Boolean) {
    val context = LocalContext.current
    val fees = AppPreferences(context).tradingFees
    val stage = signal.scalpingStage
    val buyReady = signal.action == SignalAction.BUY && signal.entryPrice > 0.0
    val feeTp2 = if (buyReady) FeeCalculator.roundTrip(signal.entryPrice, signal.stopLoss, signal.targetPrice2, fees) else null
    val feeTp1 = if (buyReady) FeeCalculator.roundTrip(signal.entryPrice, signal.stopLoss, signal.targetPrice1, fees) else null

    val title = when {
        buyReady && stage == ScalpingStage.STRONG_ENTRY -> "BUY READY · KUAT"
        buyReady -> "BUY READY"
        scalping && stage == ScalpingStage.WAIT_PULLBACK -> "MENUNGGU PULLBACK"
        scalping && stage == ScalpingStage.WATCH -> "MENUNGGU KONFIRMASI"
        scalping -> "TAHAN / TUNGGU"
        signal.action == SignalAction.SELL -> "PERTIMBANGKAN JUAL"
        else -> "TAHAN / TUNGGU"
    }
    val color = when {
        buyReady -> TvGreen
        signal.action == SignalAction.SELL -> TvRed
        else -> WarningAmber
    }

    AnalysisCard {
        SectionTitle(
            if (scalping) "REKOMENDASI EKSEKUSI (SCALPING · BUY)" else "REKOMENDASI EKSEKUSI",
            if (signal.action == SignalAction.SELL) Icons.Default.TrendingDown
            else if (buyReady) Icons.Default.TrendingUp
            else Icons.Default.Shield
        )
        Spacer(Modifier.height(10.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Black, color = color)
            Spacer(Modifier.height(4.dp))
            Text(
                if (buyReady) "Peluang entry BUY terdeteksi"
                else "Belum ada setup BUY yang valid untuk dieksekusi",
                fontSize = 13.sp,
                color = TvTextPrimary
            )

            if (buyReady) {
                Spacer(Modifier.height(12.dp))
                LevelRow("Entry Area", PriceFormatter.formatPrice(signal.entryPrice), context, signal.entryPrice)
                LevelRow("Stop Loss", PriceFormatter.formatPrice(signal.stopLoss), context, signal.stopLoss)
                LevelRow("Take Profit 1", PriceFormatter.formatPrice(signal.targetPrice1), context, signal.targetPrice1)
                LevelRow("Take Profit 2", PriceFormatter.formatPrice(signal.targetPrice2), context, signal.targetPrice2)
                Spacer(Modifier.height(8.dp))
                DividerSoft()
                Spacer(Modifier.height(8.dp))
                val netTp2 = feeTp2?.netRr ?: 0.0
                val netTp1 = feeTp1?.netRr ?: 0.0
                val feePct = feeTp2?.feePct ?: 0.0
                InfoLine("R:R Estimasi (gross)", signal.riskRewardRatio)
                InfoLine("Fee Estimasi", String.format("%.2f%%", feePct))
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Net R:R (setelah fee)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                    AnimatedMetricText(
                        value = if (netTp2 > 0) String.format("1 : %.2f", netTp2) else "—",
                        color = if (netTp2 >= 1.2) TvGreen else TvRed,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                if (netTp1 > 0) {
                    Text(
                        "Net R:R TP1  1:${String.format("%.2f", netTp1)}",
                        fontSize = 11.sp,
                        color = TvTextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Kekuatan setup: ${signal.confidence}/100 · Bukan sinyal otomatis — kelola risiko di Indodax.",
            fontSize = 11.sp,
            color = TvTextSecondary,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun LevelRow(label: String, display: String, context: Context, price: Double) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { copy(context, price) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = TvTextSecondary, modifier = Modifier.width(110.dp))
        Text(display, fontSize = 13.sp, color = TvTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ContentCopy, "Salin", tint = TvTextSecondary, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = TvTextSecondary)
        Text(value, fontSize = 12.sp, color = TvTextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DividerSoft() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
}

private fun copy(context: Context, price: Double) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Harga", PriceFormatter.formatPrice(price)))
}
