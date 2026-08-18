package agu.analys.ui.components.detail

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
                    fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp
                )
            support != null && price > 0 && price <= support * 1.01 ->
                Text(
                    "Harga dekat support ${PriceFormatter.formatPrice(support, quoteAsset = quoteAsset)}. Perhatikan apakah support bertahan atau jebol.",
                    fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp
                )
            resistance != null && price > 0 && price >= resistance * 0.99 ->
                Text(
                    "Harga dekat resistance ${PriceFormatter.formatPrice(resistance, quoteAsset = quoteAsset)}. Pantau breakout dengan volume atau rejection.",
                    fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp
                )
            else ->
                Text(
                    "Pantau tren, level support/resistance, dan volume. Jangan mengandalkan satu indikator saja.",
                    fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp
                )
        }
        Spacer(Modifier.height(8.dp))
        when {
            signal.action == SignalAction.HOLD && signal.scalpingStage == ScalpingStage.WAIT_PULLBACK ->
                Text(
                    "Belum entry bukan berarti dilarang beli — tunggu pullback bersih atau trigger momentum 1M.",
                    fontSize = 13.sp, color = WarningAmber, lineHeight = 18.sp
                )
            signal.action == SignalAction.HOLD && signal.scalpingStage == ScalpingStage.WATCH ->
                Text(
                    "Setup masih membentuk. Menunggu konfirmasi adalah keputusan yang valid.",
                    fontSize = 13.sp, color = WarningAmber, lineHeight = 18.sp
                )
            signal.action == SignalAction.HOLD ->
                Text(
                    "Belum ada setup cukup kuat. Menunggu juga bagian dari manajemen risiko.",
                    fontSize = 13.sp, color = WarningAmber, lineHeight = 18.sp
                )
        }
        if (cached) {
            Text(
                "Data sedang cache — jangan anggap pergerakan harga sebagai live penuh.",
                fontSize = 13.sp, color = WarningAmber, modifier = Modifier.padding(top = 6.dp), lineHeight = 18.sp
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
