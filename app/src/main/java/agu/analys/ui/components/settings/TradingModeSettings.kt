package agu.analys.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.StrategyMode
import agu.analys.ui.theme.*

@Composable
fun TradingModeSettings(
    strategyMode: StrategyMode,
    onStrategyChange: (StrategyMode) -> Unit
) {
    Column {
        SectionHeader("MODE ANALISIS TRADING")
        Text(
            "Sesuaikan strategi perhitungan engine sinyal and timeframe aktif.",
            color = TvTextSecondary,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))

        ModeOptionCard(
            title = "SCALPING",
            tag = "AGGRESSIVE SCALPING",
            tagBg = TvGreen.copy(alpha = 0.15f),
            tagFg = TvGreen,
            isSelected = strategyMode == StrategyMode.SCALPING,
            desc = "Mencari peluang BUY jangka pendek (1M – 15M) secara agresif dengan eksekusi cepat dan filter MTF.",
            bullets = listOf("Bias: 1H (Bullish)", "Setup: 15M", "Trigger: 1M", "Fokus: Quick Entry & Tight SL"),
            onClick = { onStrategyChange(StrategyMode.SCALPING) }
        )

        Spacer(Modifier.height(10.dp))

        ModeOptionCard(
            title = "SWING",
            tag = "ANALISIS TREND",
            tagBg = TvBlue.copy(alpha = 0.15f),
            tagFg = TvBlue,
            isSelected = strategyMode == StrategyMode.SWING,
            desc = "Menganalisis trend jangka menengah (1H – 1D) untuk posisi swing yang lebih tenang.",
            bullets = listOf("Timeframe: 1H & 1D", "Analisis struktur trend (HH/HL/LH/LL)", "Fokus: Support / Resistance & Demand Zone"),
            onClick = { onStrategyChange(StrategyMode.SWING) }
        )

        Spacer(Modifier.height(10.dp))

        ModeOptionCard(
            title = "INTRADAY",
            tag = "OPEN PAGI · CLOSE MALAM",
            tagBg = Color(0xFFA5B4FC).copy(alpha = 0.18f),
            tagFg = Color(0xFFA5B4FC),
            isSelected = strategyMode == StrategyMode.OFFICE_DAILY,
            desc = "Trading harian disiplin sesi (Open Pagi 06:00-11:30 WIB, Close Malam 19:30-23:30 WIB). Menggunakan histori harga panjang & filter anti-flash dump.",
            bullets = listOf(
                "Siklus: Open Pagi (06:00–11:30) & Close Malam (19:30–23:30 WIB)",
                "Anti Flash Dump: Analisis histori H4 (200 candle) & D1 (100 candle)",
                "Deteksi Trap: Memblokir jebakan fake pump wick & drop mendadak",
                "Kunci Profit: Exit sebelum tengah malam, lindungi kas dari overnight dump"
            ),
            onClick = { onStrategyChange(StrategyMode.OFFICE_DAILY) }
        )
    }
}

@Composable
fun ModeOptionCard(
    title: String,
    tag: String,
    tagBg: Color,
    tagFg: Color,
    isSelected: Boolean,
    desc: String,
    bullets: List<String>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) TvSurfaceVariant else TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) tagFg else TvBorder
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isSelected) TvTextPrimary else TvTextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(tagBg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(tag, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = tagFg)
                    }
                }
                if (isSelected) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Terpilih", tint = tagFg, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(desc, fontSize = 11.sp, color = TvTextSecondary, lineHeight = 15.sp)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                bullets.forEach { bullet ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(4.dp)
                                .background(if (isSelected) tagFg else TvTextSecondary, RoundedCornerShape(50))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(bullet, fontSize = 10.sp, color = if (isSelected) TvTextPrimary else TvTextSecondary)
                    }
                }
            }
        }
    }
}
