package agu.analys.ui.components.detail

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.FeeCalculator
import agu.analys.model.AISignalState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter

@Composable
fun RecommendationCard(signal: AISignalState, scalping: Boolean) {
    val context = LocalContext.current
    val fees = AppPreferences(context).tradingFees
    val stage = signal.scalpingStage
    val buyReady = signal.action == SignalAction.BUY && signal.entryPrice > 0.0
    val feeResult = FeeCalculator.roundTrip(signal.entryPrice, signal.stopLoss, signal.targetPrice1, fees)
    val title = when {
        buyReady -> "BUY READY"
        scalping && stage == ScalpingStage.WAIT_PULLBACK -> "TUNGGU KONFIRMASI"
        scalping && stage == ScalpingStage.WATCH -> "WATCH"
        scalping -> "TAHAN / TUNGGU"
        signal.action == SignalAction.SELL -> "PERTIMBANGKAN JUAL"
        else -> "TAHAN / TUNGGU"
    }
    val color = if (buyReady) TvGreen else if (signal.action == SignalAction.SELL) TvRed else WarningAmber
    AnalysisCard {
        SectionTitle("REKOMENDASI EKSEKUSI", if (signal.action == SignalAction.SELL) Icons.Default.TrendingDown else if (buyReady) Icons.Default.TrendingUp else Icons.Default.Shield)
        Spacer(Modifier.height(7.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, color = color)
        Spacer(Modifier.height(5.dp))
        Text(if (buyReady) "Peluang entry BUY terdeteksi. Level tetap dipasang manual di Indodax." else "Belum ada setup BUY yang cukup valid untuk dieksekusi.", fontSize = 13.sp, color = TvTextPrimary, lineHeight = 19.sp)
        if (buyReady) {
            Spacer(Modifier.height(9.dp))
            CopyableLevel("Entry", signal.entryPrice, context)
            CopyableLevel("SL", signal.stopLoss, context)
            CopyableLevel("TP1", signal.targetPrice1, context)
            CopyableLevel("TP2", signal.targetPrice2, context)
            Spacer(Modifier.height(6.dp))
            Text("Gross R:R  ${signal.riskRewardRatio}", fontSize = 11.sp, color = TvTextSecondary)
            Text("Fee simulasi taker→taker  ${String.format("%.2f", feeResult.feePct)}%", fontSize = 11.sp, color = TvTextSecondary)
            Text("Net R:R setelah fee  ${if (feeResult.netRr > 0) String.format("1 : %.2f", feeResult.netRr) else "tidak layak"}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (feeResult.netRr >= 1.5) TvGreen else TvRed)
        }
        Spacer(Modifier.height(7.dp))
        Text("Kekuatan setup: ${signal.confidence}/100", fontSize = 11.sp, color = TvTextSecondary)
    }
}

@Composable
private fun CopyableLevel(label: String, price: Double, context: Context) {
    Row(Modifier.fillMaxWidth().clickable { copy(context, price) }.padding(vertical = 4.dp)) {
        Text(label, fontSize = 13.sp, color = TvTextSecondary, modifier = Modifier.width(55.dp))
        Text(PriceFormatter.formatPrice(price), fontSize = 13.sp, color = TvTextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.ContentCopy, "Salin", tint = TvTextSecondary, modifier = Modifier.size(15.dp))
    }
}

private fun copy(context: Context, price: Double) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Harga", PriceFormatter.formatPrice(price)))
}
