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
    var isRiskCalculatorOpen by remember { mutableStateOf(false) }
    var selectedRiskPct by remember { mutableDoubleStateOf(2.0) } // Default risk 2% per trade
    var selectedSlTolerancePct by remember { mutableDoubleStateOf(2.5) } // Default SL tolerance 2.5%

    val focusManager = LocalFocusManager.current

    // Kalkulasi Manajemen Risiko & Position Sizing
    val effectiveCapital = if (availableIdr > 0) availableIdr else 1000000.0 // Default 1jt jika saldo kosong untuk demo
    val maxRiskAmountIdr = effectiveCapital * (selectedRiskPct / 100.0)
    val calculatedPositionSizeIdr = if (selectedSlTolerancePct > 0) {
        (maxRiskAmountIdr / (selectedSlTolerancePct / 100.0)).coerceAtLeast(10000.0).coerceAtMost(if (availableIdr > 0) availableIdr else 100000000.0)
    } else 10000.0

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

        // Toggle Expandable Risk Management & Position Sizing Calculator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F2338), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🛡️ KALKULATOR RISIKO (POSITION SIZING)",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    TextButton(
                        onClick = { isRiskCalculatorOpen = !isRiskCalculatorOpen },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = if (isRiskCalculatorOpen) "Tutup ▲" else "Buka Hitung ▼",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isRiskCalculatorOpen,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Atur % risiko modal agar kerugian per transaksi terkontrol secara matematis:",
                            color = Color(0xFFB0BEC5),
                            fontSize = 9.5.sp
                        )

                        // Selector Risk %
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("1. Risiko Modal (Risk %):", color = Color(0xFF90A4AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(1.0, 2.0, 3.0, 5.0).forEach { r ->
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (selectedRiskPct == r) Color(0xFF00E5FF) else Color(0xFF16273B),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                0.5.dp,
                                                if (selectedRiskPct == r) Color(0xFF00E5FF) else Color(0xFF263C52),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "$r%",
                                            color = if (selectedRiskPct == r) Color.Black else Color.White,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(0.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Selector Stop Loss %
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("2. Jarak Stop Loss (SL %):", color = Color(0xFF90A4AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(1.5, 2.5, 3.5, 5.0).forEach { sl ->
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (selectedSlTolerancePct == sl) TvRed else Color(0xFF16273B),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                0.5.dp,
                                                if (selectedSlTolerancePct == sl) TvRed else Color(0xFF263C52),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "-$sl%",
                                            color = if (selectedSlTolerancePct == sl) Color.White else Color.White,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Ringkasan Formula
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0A1420), RoundedCornerShape(6.dp))
                                .border(0.5.dp, Color(0xFF162B40), RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Maksimal Rugi Ditanggung:", color = Color(0xFF78909C), fontSize = 9.5.sp)
                                    Text("Rp ${PriceFormatter.formatIdrNumber(maxRiskAmountIdr)} ($selectedRiskPct% Modal)", color = TvRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Ukuran Beli Ideal (Position Size):", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("Rp ${PriceFormatter.formatIdrNumber(calculatedPositionSizeIdr)}", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }

                        // Tombol Terapkan Position Size
                        Button(
                            onClick = {
                                onNominalIdrChanged(calculatedPositionSizeIdr)
                                isCustomNominalOpen = false
                                isRiskCalculatorOpen = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("✓ Gunakan Ukuran Posisi Ini (${PriceFormatter.formatIdrNumber(calculatedPositionSizeIdr)} IDR)", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
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
