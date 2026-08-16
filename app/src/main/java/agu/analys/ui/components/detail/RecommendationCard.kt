package agu.analys.ui.components.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter

/**
 * Rekomendasi eksekusi — BUY READY / SETUP BLOCK.
 * Memudahkan user menyalin nilai Entry, TP, SL secara individual dan langsung membuka Indodax.
 */
@Composable
fun RecommendationCard(
    signal: AISignalState,
    scalping: Boolean,
    onOpenIndodax: (() -> Unit)? = null
) {
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
            if (signal.action == SignalAction.SELL) Icons.AutoMirrored.Filled.TrendingDown
            else if (buyReady) Icons.AutoMirrored.Filled.TrendingUp
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
                if (buyReady) "Setup BUY terkonfirmasi. Tap baris angka untuk salin ke clipboard."
                else "Belum ada trigger entry BUY valid — sistem memantau secara real-time.",
                fontSize = 13.sp,
                color = TvTextPrimary
            )

            if (buyReady) {
                Spacer(Modifier.height(12.dp))
                LevelRow("Entry Area", PriceFormatter.formatPrice(signal.entryPrice), context, signal.entryPrice, "Entry Area")
                LevelRow("Stop Loss (SL)", PriceFormatter.formatPrice(signal.stopLoss), context, signal.stopLoss, "Stop Loss")
                LevelRow("Take Profit 1 (TP1)", PriceFormatter.formatPrice(signal.targetPrice1), context, signal.targetPrice1, "Take Profit 1")
                LevelRow("Take Profit 2 (TP2)", PriceFormatter.formatPrice(signal.targetPrice2), context, signal.targetPrice2, "Take Profit 2")
                
                Spacer(Modifier.height(10.dp))
                DividerSoft()
                Spacer(Modifier.height(8.dp))
                
                val netTp2 = feeTp2?.netRr ?: 0.0
                val netTp1 = feeTp1?.netRr ?: 0.0
                val feePct = feeTp2?.feePct ?: 0.0
                InfoLine("R:R Gross (estimasi)", signal.riskRewardRatio)
                InfoLine("Biaya Fee (estimasi)", String.format("%.2f%%", feePct))
                if (netTp2 > 0) {
                    InfoLine("Net R:R TP2", String.format("1 : %.2f", netTp2))
                }
                if (netTp1 > 0) {
                    InfoLine("Net R:R TP1", String.format("1 : %.2f", netTp1))
                }

                Spacer(Modifier.height(12.dp))

                // Tombol Buka Indodax Langsung dari Card Rekomendasi
                Button(
                    onClick = {
                        if (onOpenIndodax != null) onOpenIndodax()
                        else launchIndodaxApp(context)
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5))
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Buka App Indodax untuk Beli", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Skor keyakinan: ${signal.confidence}/100 · Selalu pasang stop loss sebelum mengeksekusi di Indodax.",
            fontSize = 11.sp,
            color = TvTextSecondary,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun LevelRow(
    label: String,
    display: String,
    context: Context,
    price: Double,
    toastLabel: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { copyValue(context, price, toastLabel) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = TvTextSecondary, modifier = Modifier.width(135.dp))
        Text(display, fontSize = 14.sp, color = TvTextPrimary, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .background(Color(0xFF1E2D3D), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ContentCopy, "Salin", tint = TvGreen, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy", color = TvGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
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

private fun copyValue(context: Context, price: Double, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val rawValue = if (price >= 1.0) price.toLong().toString() else price.toString()
    clipboard.setPrimaryClip(ClipData.newPlainText(label, rawValue))
    Toast.makeText(context, "Disalin: $label (Rp $rawValue)", Toast.LENGTH_SHORT).show()
}

private fun launchIndodaxApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("id.co.bitcoin")
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "Aplikasi Indodax belum terpasang di perangkat.", Toast.LENGTH_SHORT).show()
    }
}
