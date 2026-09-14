package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.trading.SpotPositionStore
import agu.analys.trading.TrailingTier
import agu.analys.ui.theme.*

@Composable
fun TieredTrailingConfigDialog(
    currentTiers: List<TrailingTier>,
    onDismiss: () -> Unit,
    onSave: (List<TrailingTier>) -> Unit,
    onResetToDefault: () -> Unit
) {
    val sorted = if (currentTiers.isNotEmpty()) currentTiers.sortedBy { it.minProfitPct } else SpotPositionStore.DEFAULT_TIERS
    var step1Profit by remember { mutableStateOf(sorted.getOrNull(0)?.minProfitPct?.toString() ?: "1.0") }
    var step1Trailing by remember { mutableStateOf(sorted.getOrNull(0)?.trailingPercent?.toString() ?: "2.0") }

    var step2Profit by remember { mutableStateOf(sorted.getOrNull(1)?.minProfitPct?.toString() ?: "3.0") }
    var step2Trailing by remember { mutableStateOf(sorted.getOrNull(1)?.trailingPercent?.toString() ?: "1.5") }

    var step3Profit by remember { mutableStateOf(sorted.getOrNull(2)?.minProfitPct?.toString() ?: "5.0") }
    var step3Trailing by remember { mutableStateOf(sorted.getOrNull(2)?.trailingPercent?.toString() ?: "1.2") }

    var step4Profit by remember { mutableStateOf(sorted.getOrNull(3)?.minProfitPct?.toString() ?: "10.0") }
    var step4Trailing by remember { mutableStateOf(sorted.getOrNull(3)?.trailingPercent?.toString() ?: "1.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TvSurface,
        title = {
            Text(
                text = "⚡ Konfigurasi Smart Step Trailing",
                color = TvTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Saat profit melonjak mencapai ambang batas, persentase trailing otomatis mengencang untuk mengunci keuntungan maksimal.",
                    color = TvTextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )

                TierInputRow(
                    label = "Tier 1",
                    profit = step1Profit,
                    onProfitChange = { step1Profit = it },
                    trailing = step1Trailing,
                    onTrailingChange = { step1Trailing = it }
                )

                TierInputRow(
                    label = "Tier 2",
                    profit = step2Profit,
                    onProfitChange = { step2Profit = it },
                    trailing = step2Trailing,
                    onTrailingChange = { step2Trailing = it }
                )

                TierInputRow(
                    label = "Tier 3",
                    profit = step3Profit,
                    onProfitChange = { step3Profit = it },
                    trailing = step3Trailing,
                    onTrailingChange = { step3Trailing = it }
                )

                TierInputRow(
                    label = "Tier 4",
                    profit = step4Profit,
                    onProfitChange = { step4Profit = it },
                    trailing = step4Trailing,
                    onTrailingChange = { step4Trailing = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val t1P = step1Profit.toDoubleOrNull() ?: 1.0
                    val t1T = step1Trailing.toDoubleOrNull() ?: 2.0
                    val t2P = step2Profit.toDoubleOrNull() ?: 3.0
                    val t2T = step2Trailing.toDoubleOrNull() ?: 1.5
                    val t3P = step3Profit.toDoubleOrNull() ?: 5.0
                    val t3T = step3Trailing.toDoubleOrNull() ?: 1.2
                    val t4P = step4Profit.toDoubleOrNull() ?: 10.0
                    val t4T = step4Trailing.toDoubleOrNull() ?: 1.0

                    val list = listOf(
                        TrailingTier(t1P, t1T),
                        TrailingTier(t2P, t2T),
                        TrailingTier(t3P, t3T),
                        TrailingTier(t4P, t4T)
                    )
                    onSave(list)
                },
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
            ) {
                Text("Simpan", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onResetToDefault) {
                    Text("Reset Default", color = TvAmber, fontSize = 11.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Batal", color = TvTextSecondary, fontSize = 11.sp)
                }
            }
        }
    )
}

@Composable
private fun TierInputRow(
    label: String,
    profit: String,
    onProfitChange: (String) -> Unit,
    trailing: String,
    onTrailingChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TvBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Profit ≥", color = TvTextSecondary, fontSize = 10.sp)
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(30.dp)
                    .background(TvSurface, RoundedCornerShape(4.dp))
                    .border(1.dp, TvBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = profit,
                    onValueChange = { onProfitChange(it.filter { c -> c.isDigit() || c == '.' }) },
                    textStyle = TextStyle(fontSize = 11.sp, color = TvTextPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    cursorBrush = SolidColor(TvBlue),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("%", color = TvTextSecondary, fontSize = 10.sp)

            Spacer(Modifier.width(4.dp))

            Text("Trailing:", color = TvTextSecondary, fontSize = 10.sp)
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(30.dp)
                    .background(TvSurface, RoundedCornerShape(4.dp))
                    .border(1.dp, TvBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = trailing,
                    onValueChange = { onTrailingChange(it.filter { c -> c.isDigit() || c == '.' }) },
                    textStyle = TextStyle(fontSize = 11.sp, color = TvTextPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    cursorBrush = SolidColor(TvBlue),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("%", color = TvTextSecondary, fontSize = 10.sp)
        }
    }
}
