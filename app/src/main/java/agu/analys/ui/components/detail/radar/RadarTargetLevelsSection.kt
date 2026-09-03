package agu.analys.ui.components.detail.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.AISignalState
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun RadarTargetLevelsSection(
    signal: AISignalState,
    effectivePrice: Double,
    quoteAsset: String,
    completed: Int,
    isLevelPlanVisible: Boolean,
    onToggleLevelPlan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
            .border(0.5.dp, TvBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggleLevelPlan)
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (completed == 4) "🔥 STATUS: SIAP EKSEKUSI!" else "⚡ LEVEL PLAN ENTRY & TARGET",
                color = if (completed == 4) TvGreen else TvAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .basicMarquee()
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isLevelPlanVisible) "SEMBUNYIKAN" else "LIHAT PLAN",
                color = TvBlue,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        AnimatedVisibility(visible = isLevelPlanVisible) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                val refPrice = if (signal.entryPrice > 0.0) signal.entryPrice else effectivePrice
                val targetPrice1 = if (signal.targetPrice1 > 0.0) signal.targetPrice1 else if (refPrice > 0.0) refPrice * 1.08 else 0.0
                val targetPrice2 = if (signal.targetPrice2 > 0.0) signal.targetPrice2 else if (refPrice > 0.0) refPrice * 1.18 else 0.0
                val stopLoss = if (signal.stopLoss > 0.0) signal.stopLoss else if (refPrice > 0.0) refPrice * 0.95 else 0.0

                val tp1Gain = if (refPrice > 0.0 && targetPrice1 > 0.0) ((targetPrice1 - refPrice) / refPrice) * 100 else 0.0
                val tp2Gain = if (refPrice > 0.0 && targetPrice2 > 0.0) ((targetPrice2 - refPrice) / refPrice) * 100 else 0.0
                val slLoss = if (refPrice > 0.0 && stopLoss > 0.0) ((stopLoss - refPrice) / refPrice) * 100 else 0.0

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("• Entry Area", color = TvTextSecondary, fontSize = 11.sp)
                    Text(
                        PriceFormatter.formatPrice(refPrice, quoteAsset = quoteAsset),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("• Target TP1", color = TvTextSecondary, fontSize = 11.sp)
                    Text(
                        "${PriceFormatter.formatPrice(targetPrice1, quoteAsset = quoteAsset)} (${PriceFormatter.formatPercentage(tp1Gain, true)})",
                        color = TvGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("• Target TP2", color = TvTextSecondary, fontSize = 11.sp)
                    Text(
                        "${PriceFormatter.formatPrice(targetPrice2, quoteAsset = quoteAsset)} (${PriceFormatter.formatPercentage(tp2Gain, true)})",
                        color = TvGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("• Cut Loss (SL)", color = TvTextSecondary, fontSize = 11.sp)
                    Text(
                        "${PriceFormatter.formatPrice(stopLoss, quoteAsset = quoteAsset)} (${PriceFormatter.formatPercentage(slLoss, true)})",
                        color = TvRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
