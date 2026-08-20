package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

@Composable
fun RadarBuySection(
    validPrice: Double,
    baseAsset: String,
    quoteAsset: String,
    availableIdr: Double,
    selectedNominalIdr: Double,
    onNominalIdrChanged: (Double) -> Unit,
    activeFeePct: Double,
    isRealMode: Boolean,
    onExecuteBuy: ((Double) -> Unit)?
) {
    var customNominalInput by remember { mutableStateOf("") }
    var isCustomNominalOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val grossBuyOrderAmount = selectedNominalIdr.coerceAtLeast(10000.0)
    val buyFeeIdr = grossBuyOrderAmount * (activeFeePct / 100.0)
    val netBuyAmountIdr = (grossBuyOrderAmount - buyFeeIdr).coerceAtLeast(0.0)
    val estimatedBuyCoinQty = netBuyAmountIdr / validPrice

    Column {
        // Header Saldo IDR
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101E2E), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF1C3754), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = TvGreen,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Saldo IDR (${if (isRealMode) "Real Indodax" else "Simulasi"}):",
                        color = Color(0xFFB0BEC5),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "${PriceFormatter.formatIdrNumber(availableIdr)} $quoteAsset",
                    color = if (availableIdr > 0) TvGreen else TvTextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Quick Nominal Selector
        Text(
            text = "PILIH NOMINAL PEMBELIAN (IDR):",
            color = Color(0xFF90A4AE),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickNominalChip(
                label = "10 Rb",
                selected = selectedNominalIdr == 10000.0 && !isCustomNominalOpen,
                onClick = {
                    onNominalIdrChanged(10000.0)
                    isCustomNominalOpen = false
                },
                modifier = Modifier.weight(1f)
            )
            QuickNominalChip(
                label = "50 Rb",
                selected = selectedNominalIdr == 50000.0 && !isCustomNominalOpen,
                onClick = {
                    onNominalIdrChanged(50000.0)
                    isCustomNominalOpen = false
                },
                modifier = Modifier.weight(1f)
            )
            QuickNominalChip(
                label = "100 Rb",
                selected = selectedNominalIdr == 100000.0 && !isCustomNominalOpen,
                onClick = {
                    onNominalIdrChanged(100000.0)
                    isCustomNominalOpen = false
                },
                modifier = Modifier.weight(1f)
            )
            QuickNominalChip(
                label = "1 Jt",
                selected = selectedNominalIdr == 1000000.0 && !isCustomNominalOpen,
                onClick = {
                    onNominalIdrChanged(1000000.0)
                    isCustomNominalOpen = false
                },
                modifier = Modifier.weight(1f)
            )
            QuickNominalChip(
                label = "Lainnya",
                selected = isCustomNominalOpen,
                onClick = { isCustomNominalOpen = !isCustomNominalOpen },
                modifier = Modifier.weight(1.1f)
            )
        }

        // Custom Input Field
        AnimatedVisibility(
            visible = isCustomNominalOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = customNominalInput,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }
                        customNominalInput = filtered
                        val parsed = filtered.toDoubleOrNull()
                        if (parsed != null && parsed > 0) {
                            onNominalIdrChanged(parsed)
                        }
                    },
                    label = { Text("Masukkan Nominal Pembelian (IDR)", fontSize = 11.sp) },
                    placeholder = { Text("Contoh: 250000", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4FC3F7),
                        unfocusedBorderColor = Color(0xFF263C52),
                        focusedContainerColor = Color(0xFF101C2B),
                        unfocusedContainerColor = Color(0xFF101C2B),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Table / Detail Transaksi
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101C2A), RoundedCornerShape(10.dp))
                .border(0.5.dp, Color(0xFF1C3147), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TransactionDetailRow(
                label = "Harga Pasar Beli",
                value = "${PriceFormatter.formatIdrNumber(validPrice)} $quoteAsset"
            )
            TransactionDetailRow(
                label = "Nominal Order",
                value = "${PriceFormatter.formatIdrNumber(grossBuyOrderAmount)} $quoteAsset"
            )
            TransactionDetailRow(
                label = "Biaya Fee (${String.format("%.2f", activeFeePct)}%)",
                value = "- ${PriceFormatter.formatIdrNumber(buyFeeIdr)} $quoteAsset",
                valueColor = TvRed
            )

            HorizontalDivider(color = Color(0xFF1B2D40), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

            // Total Diterima Bersih Koin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total Koin Diterima",
                    color = TvGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${PriceFormatter.formatCryptoExact(estimatedBuyCoinQty, 8)} $baseAsset",
                        color = TvGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "= ${PriceFormatter.formatIdrNumber(netBuyAmountIdr)} $quoteAsset Net",
                        color = TvTextSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Tombol Eksekusi Beli Terintegrasi
        if (onExecuteBuy != null) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onExecuteBuy(grossBuyOrderAmount) },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TvGreen,
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isRealMode) "[REAL] BELI (${PriceFormatter.formatIdrNumber(grossBuyOrderAmount)} IDR)" else "[SIM] BELI (${PriceFormatter.formatIdrNumber(grossBuyOrderAmount)} IDR)",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp
                )
            }
        }
    }
}
