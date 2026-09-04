package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.TradingFeeConfig
import agu.analys.model.PositionContext
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun SellTargetLevelsSection(
    context: PositionContext,
    fees: TradingFeeConfig,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    val entry = context.entryPrice ?: 0.0
    val current = context.currentPrice ?: entry
    val sl = context.stopLoss
    val tp1 = context.tp1
    val tp2 = context.tp2
    val sellFeeRate = (fees.sellMakerPct / 100.0).coerceAtLeast(0.0)

    val tp1NetPct = if (entry > 0.0 && tp1 != null && tp1 > 0.0) {
        val gross = tp1 / entry
        val net = gross * (1.0 - sellFeeRate)
        (net - 1.0) * 100.0
    } else null

    val tp2NetPct = if (entry > 0.0 && tp2 != null && tp2 > 0.0) {
        val gross = tp2 / entry
        val net = gross * (1.0 - sellFeeRate)
        (net - 1.0) * 100.0
    } else null

    val slNetPct = if (entry > 0.0 && sl != null && sl > 0.0) {
        val gross = sl / entry
        val net = gross * (1.0 - sellFeeRate)
        (net - 1.0) * 100.0
    } else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "TARGET LEVEL & PROTEKSI POSISI",
            color = TvTextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Stop Loss Card
            LevelInfoCard(
                title = "Stop Loss",
                priceText = if (sl != null && sl > 0.0) PriceFormatter.formatPrice(sl, quoteAsset = quoteAsset) else "Tidak diset",
                pctText = if (slNetPct != null) PriceFormatter.formatPercentage(slNetPct, includePlusSign = true) else "--",
                color = TvRed,
                modifier = Modifier.weight(1f)
            )

            // Target TP1 Card
            LevelInfoCard(
                title = "Target TP1",
                priceText = if (tp1 != null && tp1 > 0.0) PriceFormatter.formatPrice(tp1, quoteAsset = quoteAsset) else "--",
                pctText = if (tp1NetPct != null) PriceFormatter.formatPercentage(tp1NetPct, includePlusSign = true) else "--",
                color = TvGreen,
                modifier = Modifier.weight(1f)
            )

            // Target TP2 Card
            LevelInfoCard(
                title = "Target TP2",
                priceText = if (tp2 != null && tp2 > 0.0) PriceFormatter.formatPrice(tp2, quoteAsset = quoteAsset) else "--",
                pctText = if (tp2NetPct != null) PriceFormatter.formatPercentage(tp2NetPct, includePlusSign = true) else "--",
                color = TvAmber,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LevelInfoCard(
    title: String,
    priceText: String,
    pctText: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(TvSurface, RoundedCornerShape(8.dp))
            .border(0.8.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            color = TvTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = priceText,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Net $pctText",
            color = if (pctText.startsWith("+")) TvGreen else if (pctText.startsWith("-")) TvRed else TvTextSecondary,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
