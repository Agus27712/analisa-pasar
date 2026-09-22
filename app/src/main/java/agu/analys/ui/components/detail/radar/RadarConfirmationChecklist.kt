package agu.analys.ui.components.detail.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.StrategyMode
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.ui.theme.*

@Composable
fun RadarConfirmationChecklist(
    mtf: ScalpingMtfSnapshot,
    strategyMode: StrategyMode,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val isStep1Ok = mtf.biasStatus.name == "OK" || mtf.biasOk
        val isStep2Ok = mtf.setupStatus.name == "OK" || mtf.setupOk
        val isStep3Ok = mtf.triggerStatus.name == "OK" || mtf.triggerOk
        val isStep4Ok = mtf.entryPriceStatus.name == "OK" || mtf.entryPriceOk

        val (step1Text, step2Text, step3Text, step4Text) = when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "1. Tren Makro & Alignment EMA (1D/4H/1H)",
                "2. Struktur Market & Support Lantai (Higher Low)",
                "3. Momentum & Volume Inflow (RSI/MACD)",
                "4. Risk/Reward Optimal & Toleransi Entry"
            )
            StrategyMode.OFFICE_DAILY -> listOf(
                "1. Sesi Open Pagi (06:00-11:30) & Tren EMA H4",
                "2. Anti Flash Dump (Base Aman & Tanpa Trauma Dump)",
                "3. Smart Inflow & RSI Akumulasi (40–58)",
                "4. Area Beli Terukur, Net R:R >= 1.8 & Close Malam"
            )
            StrategyMode.SCALPING -> listOf(
                "1. Trend & Bias 1H Valid (Bullish Alignment)",
                "2. Base Compression & Volume Kering (Valid 15M)",
                "3. Smart Inflow & Higher Low Terbentuk (1M)",
                "4. Trigger Reclaim Resistance 15M (Volume Masuk!)"
            )
        }

        RadarChecklistItem(1, step1Text, isStep1Ok, mtf.biasDetail)
        RadarChecklistItem(2, step2Text, isStep2Ok, mtf.setupDetail)
        RadarChecklistItem(3, step3Text, isStep3Ok, mtf.triggerDetail)
        RadarChecklistItem(4, step4Text, isStep4Ok, mtf.entryPriceDetail)
    }
}

@Composable
fun RadarChecklistItem(
    stepNumber: Int,
    label: String,
    isOk: Boolean,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(
                    if (isOk) TvGreen.copy(alpha = 0.2f) else TvSurfaceVariant,
                    CircleShape
                )
                .border(
                    1.dp,
                    if (isOk) TvGreen else TvBorder,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isOk) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "OK",
                    tint = TvGreen,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.HourglassEmpty,
                    contentDescription = "Pending",
                    tint = TvBlue,
                    modifier = Modifier.size(10.dp)
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (isOk) TvGreen else TvTextPrimary,
                fontSize = 11.sp,
                fontWeight = if (isOk) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    color = TvTextSecondary,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
            }
        }

        Box(
            modifier = Modifier
                .background(
                    if (isOk) TvGreen.copy(alpha = 0.15f) else TvSurfaceVariant,
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (isOk) "[✓] OK" else "[⚡ SCAN]",
                color = if (isOk) TvGreen else TvBlue,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
