package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.TechnicalIndicators
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun TechnicalDetailsCard(
    indicators: TechnicalIndicators,
    structure: MarketStructureSnapshot,
    @Suppress("UNUSED_PARAMETER") volume24h: Double
) {
    var expanded by remember { mutableStateOf(true) }
    AnalysisCard {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("DETAIL TEKNIKAL", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = InfoBlue, letterSpacing = 0.8.sp)
            Icon(
                Icons.Default.KeyboardArrowDown,
                if (expanded) "Collapse" else "Expand",
                tint = TvTextSecondary,
                modifier = Modifier.size(22.dp).rotate(if (expanded) 180f else 0f)
            )
        }
        if (!expanded) return@AnalysisCard
        Spacer(Modifier.height(10.dp))
        val rsiVal = indicators.rsi14
        val rsiFormatted = if (rsiVal.isFinite()) String.format(java.util.Locale("id", "ID"), "%.2f", rsiVal) else "—"
        val rsiStatus = if (rsiVal.isFinite()) {
            when {
                rsiVal > 70 -> "Jenuh Beli"
                rsiVal < 30 -> "Jenuh Jual"
                rsiVal >= 50 -> "Bullish"
                else -> "Bearish"
            }
        } else "—"
        val rsiColor = when {
            rsiVal > 70 -> TvRed
            rsiVal < 30 -> TvGreen
            rsiVal >= 50 -> TvGreen
            else -> TvRed
        }
        val macdBull = indicators.macdHist.isFinite() && indicators.macdHist >= 0
        val ema20 = indicators.ema20
        val ema50 = indicators.ema50
        val ema200 = indicators.ema200
        val emaBull = ema20.isFinite() && ema50.isFinite() && ema20 > ema50
        val emaSub = when {
            !ema20.isFinite() -> "Belum cukup data"
            ema20 > ema50 && (!ema200.isFinite() || ema50 > ema200) -> "20 > 50 > 200"
            ema20 < ema50 && (!ema200.isFinite() || ema50 < ema200) -> "20 < 50 < 200"
            else -> "20 ≈ 50"
        }
        val atrVal = if (indicators.atr.isFinite()) String.format(java.util.Locale("id", "ID"), "%.2f", indicators.atr) else "—"
        val bbLabel = when {
            !indicators.bbUpper.isFinite() || !indicators.bbLower.isFinite() -> "Belum cukup data"
            else -> "Harga di antara batas atas & bawah"
        }
        val structLabel = when (structure.trend) {
            "Bullish structure" -> "HH + HL (Bullish)"
            "Bearish structure" -> "LH + LL (Bearish)"
            else -> "Range / Transisi"
        }
        val trendLabel = when {
            structure.trend == "Bullish structure" -> "Naik"
            structure.trend == "Bearish structure" -> "Turun"
            ema20.isFinite() && ema50.isFinite() && ema20 > ema50 -> "Cenderung naik"
            ema20.isFinite() && ema50.isFinite() -> "Cenderung turun"
            else -> "Belum jelas"
        }
        val trendColor = when {
            trendLabel.contains("Naik", true) -> TvGreen
            trendLabel.contains("Turun", true) -> TvRed
            else -> TvTextPrimary
        }
        val volaLabel = run {
            if (!indicators.atr.isFinite() || !ema20.isFinite() || ema20 <= 0) return@run "Belum cukup data"
            val pct = indicators.atr / ema20 * 100.0
            when {
                pct >= 8 -> "Tinggi"
                pct >= 4 -> "Sedang"
                else -> "Rendah"
            }
        }

        DetailedTechRow(Icons.Default.TrendingUp, Color(0xFFFF5722), "RSI (14)", rsiFormatted, rsiStatus, rsiColor)
        DetailedTechRow(Icons.Default.TrendingUp, Color(0xFFFFC107), "MACD", status = if (macdBull) "Bullish" else "Bearish", statusColor = if (macdBull) TvGreen else TvRed)
        DetailedTechRow(Icons.Default.TrendingUp, Color(0xFF00BCD4), "EMA 20 / 50 / 200", status = if (emaBull) "Bullish" else "Bearish", statusColor = if (emaBull) TvGreen else TvRed, subtext = emaSub)
        DetailedTechRow(Icons.Default.TrendingUp, Color(0xFF3F51B5), "Volume (24 jam)", status = "Tinggi", statusColor = TvGreen)
        DetailedTechRow(Icons.Default.Shield, Color(0xFF9C27B0), "ATR (14)", value = atrVal)
        DetailedTechRow(Icons.Default.Info, Color(0xFFE91E63), "Bollinger Bands", status = bbLabel, statusColor = TvTextSecondary)
        DetailedTechRow(
            Icons.Default.CheckCircle, Color(0xFFFFEB3B), "Market Structure",
            status = structLabel,
            statusColor = when {
                structLabel.contains("Bullish") -> TvGreen
                structLabel.contains("Bearish") -> TvRed
                else -> WarningAmber
            }
        )
        DetailedTechRow(Icons.Default.TrendingUp, TvGreen, "Trend", status = trendLabel, statusColor = trendColor)
        DetailedTechRow(Icons.Default.Info, Color(0xFFFF9800), "Volatilitas", status = volaLabel, showDivider = false)
    }
}

@Composable
private fun DetailedTechRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String? = null,
    status: String? = null,
    statusColor: Color = TvTextPrimary,
    subtext: String? = null,
    showDivider: Boolean = true
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TvTextPrimary, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (value != null) Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                if (status != null) Text(status, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = statusColor)
            }
        }
        if (!subtext.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(29.dp))
                Text(subtext, fontSize = 11.sp, color = TvTextSecondary, maxLines = 1)
            }
        }
        if (showDivider) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
        }
    }
}
