package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale

@Composable
fun SellTrailingSection(
    isTrailingActive: Boolean,
    onTrailingActiveChanged: (Boolean) -> Unit,
    isTrailingTriggered: Boolean,
    trailingPercent: Double,
    onSetTrailingPercent: (Double) -> Unit,
    peakPrice: Double,
    trailingStopPrice: Double,
    quoteAsset: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isTrailingTriggered) TvRed.copy(alpha = 0.15f) else if (isTrailingActive) TvBlue.copy(alpha = 0.1f) else TvSurfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (isTrailingTriggered) TvRed else if (isTrailingActive) TvBlue else TvBorder,
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔒 TRAILING STOP LOSS",
                        color = if (isTrailingTriggered) TvRed else if (isTrailingActive) TvBlue else TvTextSecondary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Switch(
                    checked = isTrailingActive,
                    onCheckedChange = onTrailingActiveChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TvBlue,
                        checkedTrackColor = TvBlue.copy(alpha = 0.4f),
                        uncheckedThumbColor = TvTextSecondary,
                        uncheckedTrackColor = TvSurfaceVariant
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }

            if (isTrailingActive) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Jarak Trailing (Dari Peak):", color = TvTextSecondary, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1.5, 2.0, 3.0, 5.0).forEach { pct ->
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (trailingPercent == pct) TvBlue else TvSurfaceVariant,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (trailingPercent == pct) TvBlue else TvBorder,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable { onSetTrailingPercent(pct) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$pct%",
                                        color = if (trailingPercent == pct) Color.Black else TvTextPrimary,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Harga Puncak Tercatat:", color = TvTextSecondary, fontSize = 9.5.sp)
                            Text("${PriceFormatter.formatIdrNumber(peakPrice)} $quoteAsset", color = TvAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Garis Stop Loss Dinamis:", color = TvBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("${PriceFormatter.formatIdrNumber(trailingStopPrice)} $quoteAsset", color = if (isTrailingTriggered) TvRed else TvBlue, fontSize = 10.5.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            } else {
                Text(
                    text = "Aktifkan untuk mengunci profit otomatis: stop loss naik mengikuti kenaikan harga puncak.",
                    color = TvTextSecondary,
                    fontSize = 9.5.sp
                )
            }
        }
    }
}
