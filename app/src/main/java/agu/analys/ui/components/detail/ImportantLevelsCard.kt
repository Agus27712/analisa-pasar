package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.AISignalState
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import kotlin.math.abs

/**
 * Selalu bernilai informasi: posisi harga, support/resistance relevan, jarak.
 * Tanpa placeholder "Belum ada setup". Fallback = AREA OBSERVASI (bukan angka fiktif).
 */
@Composable
fun ImportantLevelsCard(
    @Suppress("UNUSED_PARAMETER") signal: AISignalState,
    structure: MarketStructureSnapshot,
    price: Double,
    quoteAsset: String = "IDR"
) {
    AnalysisCard {
        SectionTitle("LEVEL PENTING", androidx.compose.material.icons.Icons.Default.TrendingUp)
        Spacer(Modifier.height(10.dp))

        val support = structure.support?.takeIf { it > 0.0 && it.isFinite() }
        val resistance = structure.resistance?.takeIf { it > 0.0 && it.isFinite() }
        val validPrice = price > 0.0 && price.isFinite()

        // POSISI HARGA — selalu tampil jika harga valid
        if (validPrice) {
            Text("POSISI HARGA", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(4.dp))
            Text(PriceFormatter.formatPrice(price, quoteAsset = quoteAsset), fontSize = 20.sp, fontWeight = FontWeight.Black, color = TvTextPrimary)

            if (support != null && resistance != null && resistance > support) {
                Spacer(Modifier.height(6.dp))
                val range = resistance - support
                val position = ((price - support) / range).coerceIn(0.0, 1.0)
                val nearLabel = when {
                    position < 0.25 -> "Dekat Support"
                    position > 0.75 -> "Dekat Resistance"
                    else -> "Di tengah range"
                }
                val nearColor = when {
                    position < 0.25 -> TvGreen
                    position > 0.75 -> TvRed
                    else -> TvBlue
                }
                Text(nearLabel, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = nearColor)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).background(TvSurfaceVariant, RoundedCornerShape(8.dp)).border(0.5.dp, TvBorder, RoundedCornerShape(8.dp))) {
                    Box(
                        Modifier
                            .fillMaxWidth(position.toFloat())
                            .fillMaxHeight()
                            .background(nearColor, RoundedCornerShape(8.dp))
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AnalysisDivider()
            Spacer(Modifier.height(10.dp))
        }

        // SUPPORT / RESISTANCE atau AREA OBSERVASI
        when {
            support != null || resistance != null -> {
                if (support != null && validPrice) {
                    LevelLine(
                        title = "Support relevan",
                        level = support,
                        price = price,
                        accent = TvGreen,
                        isResistance = false,
                        quoteAsset = quoteAsset
                    )
                    Spacer(Modifier.height(8.dp))
                } else if (support == null) {
                    ObservasiLine(
                        title = "Support",
                        reason = if (!structure.dataEnough)
                            "Data candle belum cukup untuk swing support."
                        else
                            "Swing low relevan belum teridentifikasi — pantau ekstrem candle terbaru."
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (resistance != null && validPrice) {
                    LevelLine(
                        title = "Resistance relevan",
                        level = resistance,
                        price = price,
                        accent = TvRed,
                        isResistance = true,
                        quoteAsset = quoteAsset
                    )
                } else if (resistance == null) {
                    ObservasiLine(
                        title = "Resistance",
                        reason = if (!structure.dataEnough)
                            "Data candle belum cukup untuk swing resistance."
                        else
                            "Swing high relevan belum teridentifikasi — pantau ekstrem candle terbaru."
                    )
                }
            }
            else -> {
                // Tidak memaksakan Support/Resistance fiktif
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(InfoBlue.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .border(1.dp, InfoBlue.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text("AREA OBSERVASI", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = InfoBlue, letterSpacing = 0.6.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (!structure.dataEnough)
                            "Level berasal dari swing candle. Minimal data belum cukup — pantau ekstrem high/low candle terbaru sebagai area observasi, bukan support/resistance pasti."
                        else
                            "Swing support/resistance belum teridentifikasi. Gunakan ekstrem candle terbaru sebagai area observasi sampai struktur lebih jelas.",
                        fontSize = 13.sp,
                        color = TvTextPrimary,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        if (structure.structureExplanation.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(structure.structureExplanation, fontSize = 11.sp, color = TvTextSecondary, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun LevelLine(
    title: String,
    level: Double,
    price: Double,
    accent: Color,
    isResistance: Boolean,
    quoteAsset: String
) {
    val dist = abs(price - level) / price.coerceAtLeast(1e-9) * 100.0
    val relation = when {
        !isResistance && price > level -> "${fmtPct(dist)} di bawah harga"
        !isResistance -> "${fmtPct(dist)} di atas harga"
        price < level -> "${fmtPct(dist)} di atas harga"
        else -> "${fmtPct(dist)} di bawah harga (terlewati)"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = accent, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(4.dp))
        Text(PriceFormatter.formatPrice(level, quoteAsset = quoteAsset), fontSize = 17.sp, fontWeight = FontWeight.Black, color = TvTextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(relation, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}

@Composable
private fun ObservasiLine(title: String, reason: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text("AREA OBSERVASI · $title", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = TvTextSecondary, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(4.dp))
        Text(reason, fontSize = 12.sp, color = TvTextPrimary, lineHeight = 16.sp)
    }
}

private fun fmtPct(value: Double): String = String.format("%.2f%%", value)
