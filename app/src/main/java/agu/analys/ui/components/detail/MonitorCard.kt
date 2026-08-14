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
import agu.analys.model.SignalAction
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.util.PriceFormatter

@Composable
fun MonitorCard(
    signal: AISignalState,
    structure: MarketStructureSnapshot,
    price: Double,
    cached: Boolean
) {
    AnalysisCard {
        SectionTitle("YANG PERLU DIPANTAU", Icons.Default.Info)
        Spacer(Modifier.height(7.dp))
        val support = structure.support
        val resistance = structure.resistance
        when {
            support != null && price > 0 && price < support ->
                Text("Harga berada di bawah support ${PriceFormatter.formatPrice(support)}. Tunggu konfirmasi sebelum mengambil posisi.", fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp)
            support != null && price > 0 && price <= support * 1.01 ->
                Text("Harga sedang dekat support ${PriceFormatter.formatPrice(support)}. Perhatikan apakah support bertahan atau jebol.", fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp)
            resistance != null && price > 0 && price >= resistance * 0.99 ->
                Text("Harga sedang dekat resistance ${PriceFormatter.formatPrice(resistance)}. Perhatikan apakah level ini ditembus dengan volume.", fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp)
            else ->
                Text("Pantau perubahan tren, support/resistance, dan volume. Jangan hanya melihat satu indikator.", fontSize = 14.sp, color = TvTextPrimary, lineHeight = 20.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (signal.action == SignalAction.HOLD)
            Text("Saat ini belum ada setup kuat. Menunggu juga adalah keputusan.", fontSize = 13.sp, color = WarningAmber)
        if (cached)
            Text("Data sedang cache, jadi jangan anggap perubahan harga sebagai live.", fontSize = 13.sp, color = WarningAmber, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
fun DisclaimerCard() {
    AnalysisCard {
        IconTextRow(
            Icons.Default.Shield,
            "Ini bukan saran finansial. Analisa dapat berubah kapan saja, jadi tetap gunakan manajemen risiko.",
            WarningAmber
        )
    }
}
