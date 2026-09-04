package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Stop Loss Row
            LevelInfoRow(
                title = "Stop Loss",
                priceText = if (sl != null && sl > 0.0) PriceFormatter.formatPrice(sl, quoteAsset = quoteAsset) else "Tidak diset",
                pctText = if (slNetPct != null) PriceFormatter.formatPercentage(slNetPct, includePlusSign = true) else "--",
                color = TvRed
            )

            // Target TP1 Row
            LevelInfoRow(
                title = "Target TP1",
                priceText = if (tp1 != null && tp1 > 0.0) PriceFormatter.formatPrice(tp1, quoteAsset = quoteAsset) else "--",
                pctText = if (tp1NetPct != null) PriceFormatter.formatPercentage(tp1NetPct, includePlusSign = true) else "--",
                color = TvGreen
            )

            // Target TP2 Row
            LevelInfoRow(
                title = "Target TP2",
                priceText = if (tp2 != null && tp2 > 0.0) PriceFormatter.formatPrice(tp2, quoteAsset = quoteAsset) else "--",
                pctText = if (tp2NetPct != null) PriceFormatter.formatPercentage(tp2NetPct, includePlusSign = true) else "--",
                color = TvAmber
            )
        }
    }
}

@Composable
private fun LevelInfoRow(
    title: String,
    priceText: String,
    pctText: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurface, RoundedCornerShape(8.dp))
            .border(0.8.dp, color.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, RoundedCornerShape(3.dp))
            )
            Text(
                text = title,
                color = TvTextSecondary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }

        Spacer(Modifier.width(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(2f, fill = false)
        ) {
            Text(
                text = priceText,
                color = color,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .basicMarquee()
            )

            Box(
                modifier = Modifier
                    .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Net $pctText",
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

