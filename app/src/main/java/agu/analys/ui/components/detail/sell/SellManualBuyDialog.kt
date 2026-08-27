package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale

@Composable
fun SellManualBuyDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    baseAsset: String,
    quoteAsset: String,
    availableCoin: Double,
    initialAvgBuy: Double,
    initialTotalCost: Double,
    onSave: (Double, Double) -> Unit
) {
    if (!show) return

    var manualAvgBuyInput by remember { mutableStateOf(if (initialAvgBuy > 0) PriceFormatter.formatIdrNumber(initialAvgBuy) else "") }
    var manualTotalCostInput by remember { mutableStateOf(if (initialTotalCost > 0) PriceFormatter.formatIdrNumber(initialTotalCost) else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TvSurface,
        titleContentColor = TvTextPrimary,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    tint = TvAmber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Set Harga Beli Manual (>7 Hari)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvTextPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Gunakan ini untuk koin $baseAsset yang dibeli >7 hari lalu di Indodax. Isikan salah satu nilai di bawah dari nota Indodax Anda:",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                OutlinedTextField(
                    value = manualAvgBuyInput,
                    onValueChange = { input ->
                        manualAvgBuyInput = input
                        val p = PriceFormatter.parseCleanIdrDouble(input)
                        if (p > 0.0 && availableCoin > 0.0) {
                            val calcTotal = p * availableCoin
                            manualTotalCostInput = PriceFormatter.formatIdrNumber(calcTotal)
                        }
                    },
                    label = { Text("Harga Rata-rata Beli ($quoteAsset)", fontSize = 11.sp) },
                    placeholder = { Text("Contoh: 1.367.959.000", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvAmber,
                        unfocusedBorderColor = TvBorder,
                        focusedContainerColor = TvSurfaceVariant,
                        unfocusedContainerColor = TvSurfaceVariant,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = manualTotalCostInput,
                    onValueChange = { input ->
                        manualTotalCostInput = input
                        val total = PriceFormatter.parseCleanIdrDouble(input)
                        if (total > 0.0 && availableCoin > 0.0) {
                            val calcPrice = total / availableCoin
                            manualAvgBuyInput = PriceFormatter.formatIdrNumber(calcPrice)
                        }
                    },
                    label = { Text("Total Order Terisi / Modal ($quoteAsset)", fontSize = 11.sp) },
                    placeholder = { Text("Contoh: 37.987", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvAmber,
                        unfocusedBorderColor = TvBorder,
                        focusedContainerColor = TvSurfaceVariant,
                        unfocusedContainerColor = TvSurfaceVariant,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (availableCoin > 0.0) {
                    Text(
                        text = "Kuantitas Koin ($baseAsset): ${PriceFormatter.formatCryptoExact(availableCoin, 8)}",
                        color = TvBlue,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedPrice = PriceFormatter.parseCleanIdrDouble(manualAvgBuyInput)
                    val parsedTotal = PriceFormatter.parseCleanIdrDouble(manualTotalCostInput)
                    val finalPrice = if (parsedPrice > 0.0) parsedPrice else if (parsedTotal > 0.0 && availableCoin > 0.0) parsedTotal / availableCoin else 0.0
                    val finalTotal = if (parsedTotal > 0.0) parsedTotal else finalPrice * availableCoin

                    if (finalPrice > 0.0) {
                        onSave(finalPrice, finalTotal)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TvAmber, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Simpan Harga Beli", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = TvTextSecondary, fontSize = 12.sp)
            }
        }
    )
}
