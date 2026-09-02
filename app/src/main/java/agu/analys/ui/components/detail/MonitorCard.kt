package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.AISignalState
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.util.PriceFormatter

@Composable
fun MonitorCard(
    signal: AISignalState,
    structure: MarketStructureSnapshot,
    price: Double,
    cached: Boolean,
    quoteAsset: String = "IDR"
) {
    AnalysisCard {
        SectionTitle("YANG PERLU DIPANTAU", Icons.Default.Info)
        Spacer(Modifier.height(8.dp))
        val support = structure.support
        val resistance = structure.resistance
        when {
            support != null && price > 0 && price < support ->
                Text(
                    "Harga di bawah support ${PriceFormatter.formatPrice(support, quoteAsset = quoteAsset)}. Tunggu konfirmasi struktur sebelum ambil posisi.",
                    fontSize = 13.5.sp, color = TvTextPrimary, lineHeight = 19.sp
                )
            support != null && price > 0 && price <= support * 1.01 ->
                Text(
                    "Harga dekat support ${PriceFormatter.formatPrice(support, quoteAsset = quoteAsset)}. Perhatikan apakah support bertahan atau jebol.",
                    fontSize = 13.5.sp, color = TvTextPrimary, lineHeight = 19.sp
                )
            resistance != null && price > 0 && price >= resistance * 0.99 ->
                Text(
                    "Harga dekat resistance ${PriceFormatter.formatPrice(resistance, quoteAsset = quoteAsset)}. Pantau breakout dengan volume atau rejection.",
                    fontSize = 13.5.sp, color = TvTextPrimary, lineHeight = 19.sp
                )
            else ->
                Text(
                    "Pantau tren, level support/resistance, dan volume. Jangan mengandalkan satu indikator saja.",
                    fontSize = 13.5.sp, color = TvTextPrimary, lineHeight = 19.sp
                )
        }
        
        val warningText = when {
            signal.action == SignalAction.HOLD && signal.scalpingStage == ScalpingStage.WAIT_PULLBACK ->
                "Belum entry bukan berarti dilarang beli — tunggu pullback bersih atau trigger momentum 1M."
            signal.action == SignalAction.HOLD && signal.scalpingStage == ScalpingStage.WATCH ->
                "Setup masih membentuk. Menunggu konfirmasi adalah keputusan yang valid."
            signal.action == SignalAction.HOLD ->
                "Belum ada setup cukup kuat. Menunggu juga bagian dari manajemen risiko."
            else -> null
        }

        if (warningText != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(WarningAmber.copy(alpha = 0.1f))
                    .border(1.dp, WarningAmber.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    warningText,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = WarningAmber,
                    lineHeight = 17.sp
                )
            }
        }

        if (cached) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Data sedang cache — jangan anggap pergerakan harga sebagai live penuh.",
                fontSize = 12.sp, color = WarningAmber, lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun DisclaimerCard() {
    AnalysisCard {
        IconTextRow(
            Icons.Default.Shield,
            "Ini bukan saran finansial. Analisa dapat berubah kapan saja — selalu pakai manajemen risiko sendiri.",
            WarningAmber
        )
    }
}
