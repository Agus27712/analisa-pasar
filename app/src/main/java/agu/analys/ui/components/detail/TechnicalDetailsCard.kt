package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import agu.analys.util.PriceFormatter

/**
 * Label diselaraskan dengan parameter engine:
 * - Scalping 1M default: RSI(7), EMA fast/slow (5/13), MACD hist, ATR(7)
 * - Field model ema20/ema50 = fast/slow dari FrameAnalyzer (bukan literal EMA 20/50)
 */
@Composable
fun TechnicalDetailsCard(
    indicators: TechnicalIndicators,
    structure: MarketStructureSnapshot,
    volume24h: Double,
    scalping: Boolean = false
) {
    var expanded by remember { mutableStateOf(true) }
    AnalysisCard {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("DETAIL TEKNIKAL", Icons.Default.Info)
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
        val rsiLabel = if (scalping) "RSI (frame)" else "RSI (14)"
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
        val macdVal = if (indicators.macdHist.isFinite())
            String.format(java.util.Locale.US, "%.4f", indicators.macdHist) else "—"

        // Engine scalping: ema20 field = EMA fast, ema50 field = EMA slow
        val emaFast = indicators.ema20
        val emaSlow = indicators.ema50
        val ema200 = indicators.ema200
        val emaBull = emaFast.isFinite() && emaSlow.isFinite() && emaFast > emaSlow
        val emaLabel = if (scalping) "EMA Fast / Slow" else "EMA 20 / 50 / 200"
        val emaSub = when {
            !emaFast.isFinite() || !emaSlow.isFinite() -> "Belum cukup data"
            scalping -> if (emaBull) "fast > slow" else "fast < slow"
            emaFast > emaSlow && (!ema200.isFinite() || emaSlow > ema200) -> "20 > 50 > 200"
            emaFast < emaSlow && (!ema200.isFinite() || emaSlow < ema200) -> "20 < 50 < 200"
            else -> "fast ≈ slow"
        }

        val atrVal = if (indicators.atr.isFinite())
            String.format(java.util.Locale("id", "ID"), "%.2f", indicators.atr) else "—"
        val atrLabel = if (scalping) "ATR (frame)" else "ATR (14)"

        val volStatus: String
        val volColor: Color
        val volSub: String
        if (volume24h > 0) {
            volStatus = PriceFormatter.formatPrice(volume24h)
            volColor = TvTextPrimary
            volSub = "volume 24 jam (IDR)"
        } else if (indicators.momentum.isFinite()) {
            volStatus = "—"
            volColor = TvTextSecondary
            volSub = "volume 24 jam belum tersedia"
        } else {
            volStatus = "—"
            volColor = TvTextSecondary
            volSub = "belum ada data volume"
        }

        val bbLabel = when {
            !indicators.bbUpper.isFinite() || !indicators.bbLower.isFinite() ->
                if (scalping) "Tidak dihitung di mode scalping" else "Belum cukup data"
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
            emaFast.isFinite() && emaSlow.isFinite() && emaFast > emaSlow -> "Cenderung naik"
            emaFast.isFinite() && emaSlow.isFinite() -> "Cenderung turun"
            else -> "Belum jelas"
        }
        val trendColor = when {
            trendLabel.contains("Naik", true) -> TvGreen
            trendLabel.contains("Turun", true) -> TvRed
            else -> TvTextPrimary
        }
        val volaLabel = run {
            if (!indicators.atr.isFinite() || !emaFast.isFinite() || emaFast <= 0) return@run "Belum cukup data"
            val pct = indicators.atr / emaFast * 100.0
            when {
                pct >= 8 -> "Tinggi (${String.format("%.1f", pct)}%)"
                pct >= 4 -> "Sedang (${String.format("%.1f", pct)}%)"
                else -> "Rendah (${String.format("%.1f", pct)}%)"
            }
        }

        if (scalping) {
            Text(
                "Parameter frame aktif (1M): RSI·EMA fast/slow·MACD hist·ATR — bukan label chart generik.",
                fontSize = 11.sp, color = TvTextSecondary, lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
        }

        DetailedTechRow(Icons.Default.TrendingUp, Color(0xFFFF5722), rsiLabel, rsiFormatted, rsiStatus, rsiColor)
        DetailedTechRow(
            Icons.Default.TrendingUp, Color(0xFFFFC107), "MACD Hist",
            value = macdVal,
            status = if (macdBull) "Bullish" else "Bearish",
            statusColor = if (macdBull) TvGreen else TvRed
        )
        DetailedTechRow(
            Icons.Default.TrendingUp, Color(0xFF00BCD4), emaLabel,
            status = if (emaBull) "Bullish" else "Bearish",
            statusColor = if (emaBull) TvGreen else TvRed,
            subtext = emaSub
        )
        DetailedTechRow(
            Icons.Default.TrendingUp, Color(0xFF3F51B5), "Volume 24 jam",
            value = volStatus,
            statusColor = volColor,
            subtext = volSub
        )
        DetailedTechRow(Icons.Default.Shield, Color(0xFF9C27B0), atrLabel, value = atrVal)
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
        DetailedTechRow(Icons.Default.Info, Color(0xFFFF9800), "Volatilitas (ATR%)", status = volaLabel, showDivider = false)
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
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(iconTint.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(9.dp))
                Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = TvTextPrimary, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (value != null) {
                    Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                }
                if (status != null) {
                    val isStatusColored = statusColor != TvTextPrimary && statusColor != TvTextSecondary
                    if (isStatusColored) {
                        Box(
                            modifier = Modifier
                                .background(statusColor.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(status, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = statusColor)
                        }
                    } else {
                        Text(status, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = statusColor)
                    }
                }
            }
        }
        if (!subtext.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(33.dp))
                Text(subtext, fontSize = 11.sp, color = TvTextSecondary, maxLines = 1)
            }
        }
        if (showDivider) {
            Spacer(Modifier.height(6.dp))
            AnalysisDivider()
        }
    }
}
