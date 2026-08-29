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
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.ui.theme.*

@Composable
fun TradingModeSettings(
    strategyMode: StrategyMode,
    sensitivity: ScalpingSensitivity,
    onStrategyChange: (StrategyMode) -> Unit,
    onSensitivityChange: (ScalpingSensitivity) -> Unit
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
            tag = "BUY MODE",
            tagBg = TvGreen.copy(alpha = 0.15f),
            tagFg = TvGreen,
            isSelected = strategyMode == StrategyMode.SCALPING,
            desc = "Mencari peluang BUY jangka pendek (1M – 15M) dengan eksekusi cepat dan filter MTF.",
            bullets = listOf("Bias: 1H (Bullish)", "Setup: 15M", "Trigger: 1M", "Fokus: Quick Entry & Tight SL"),
            onClick = { onStrategyChange(StrategyMode.SCALPING) }
        )

        if (strategyMode == StrategyMode.SCALPING) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("SENSITIVITAS SCALPING", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvBlue)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SensitivityChoice(
                            label = "KONSERVATIF",
                            selected = sensitivity == ScalpingSensitivity.CONSERVATIVE,
                            activeBg = TvGreen.copy(alpha = 0.15f),
                            activeFg = TvGreen,
                            modifier = Modifier.weight(1f)
                        ) { onSensitivityChange(ScalpingSensitivity.CONSERVATIVE) }
                        SensitivityChoice(
                            label = "SEIMBANG",
                            selected = sensitivity == ScalpingSensitivity.BALANCED,
                            activeBg = TvBlue.copy(alpha = 0.15f),
                            activeFg = TvBlue,
                            modifier = Modifier.weight(1f)
                        ) { onSensitivityChange(ScalpingSensitivity.BALANCED) }
                        SensitivityChoice(
                            label = "AGRESIF",
                            selected = sensitivity == ScalpingSensitivity.AGGRESSIVE,
                            activeBg = TvAmber.copy(alpha = 0.15f),
                            activeFg = TvAmber,
                            modifier = Modifier.weight(1f)
                        ) { onSensitivityChange(ScalpingSensitivity.AGGRESSIVE) }
                        SensitivityChoice(
                            label = "AUTO (AI)",
                            selected = sensitivity == ScalpingSensitivity.DYNAMIC_AUTO,
                            activeBg = TvOrange.copy(alpha = 0.15f),
                            activeFg = TvOrange,
                            modifier = Modifier.weight(1f)
                        ) { onSensitivityChange(ScalpingSensitivity.DYNAMIC_AUTO) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (sensitivity) {
                            ScalpingSensitivity.CONSERVATIVE -> "Konservatif: Filter ketat MTF 1H+15M+1M, anti-false breakout, Net R:R ≥ 1.25."
                            ScalpingSensitivity.BALANCED -> "Seimbang (Rekomendasi): RSI 36–64, Walk-Forward, Net R:R ≥ 1.20."
                            ScalpingSensitivity.AGGRESSIVE -> "Agresif: Peluang lebih sering, RSI 35–68, volume 0.85x, quick pump."
                            ScalpingSensitivity.DYNAMIC_AUTO -> "Adaptif Otomatis: AI menyesuaikan threshold berdasarkan Rejim Pasar (Sideways/Volatile/Trending)."
                        },
                        fontSize = 10.sp,
                        color = when (sensitivity) {
                            ScalpingSensitivity.CONSERVATIVE -> TvGreen
                            ScalpingSensitivity.BALANCED -> TvBlue
                            ScalpingSensitivity.AGGRESSIVE -> TvAmber
                            ScalpingSensitivity.DYNAMIC_AUTO -> TvOrange
                        },
                        lineHeight = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        ModeOptionCard(
            title = "SECOND-WAVE",
            tag = "2ND-WAVE HUNTER",
            tagBg = TvBlue.copy(alpha = 0.15f),
            tagFg = TvBlue,
            isSelected = strategyMode == StrategyMode.SECOND_WAVE,
            desc = "Membidik pantulan gelombang kedua pada koin pasca pump dengan koreksi terukur dan konfirmasi reclaim.",
            bullets = listOf(
                "Timeframe: 15M (Eksekusi) & 1H (Struktur)",
                "Kriteria: Prior Run > 20% & Pullback Drawdown 50–85%",
                "Sinyal: Base-Dip & Reclaim Entry",
                "Target: TP1 (+10–15%) & TP2 (+25–50%+)"
            ),
            onClick = { onStrategyChange(StrategyMode.SECOND_WAVE) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TvTextPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.background(tagBg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(tag, color = tagFg, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.weight(1f))
                if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = tagFg, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(desc, color = TvTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
            Spacer(Modifier.height(8.dp))
            bullets.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    Box(Modifier.size(4.dp).background(tagFg, RoundedCornerShape(1.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(b, color = TvTextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun SensitivityChoice(
    label: String,
    selected: Boolean,
    activeBg: Color,
    activeFg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(if (selected) activeBg else TvCardBackground, RoundedCornerShape(6.dp))
            .border(1.dp, if (selected) activeFg else TvBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) activeFg else TvTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
