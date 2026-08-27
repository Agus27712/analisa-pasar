package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import agu.analys.ui.theme.*
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
    signal: agu.analys.model.AISignalState? = null,
    onExecuteBuy: ((Double, Double, Double) -> Unit)?
) {
    var customNominalInput by remember { mutableStateOf("") }
    var isCustomNominalOpen by remember { mutableStateOf(false) }
    var isRiskCalculatorOpen by remember { mutableStateOf(false) }
    var selectedRiskPct by remember { mutableDoubleStateOf(2.0) } // Default risk 2% per trade
    var selectedSlTolerancePct by remember { mutableDoubleStateOf(2.5) } // Default SL tolerance 2.5%

    // Auto Limit Sell Server Settings (TP Direct to Server)
    var isAutoLimitSellEnabled by remember { mutableStateOf(isRealMode) }
    val defaultTpPrice1 = remember(validPrice, signal) {
        if (signal != null && signal.targetPrice1 > validPrice) signal.targetPrice1 else validPrice * 1.03
    }
    val defaultTpPrice2 = remember(validPrice, signal) {
        if (signal != null && signal.targetPrice2 > validPrice) signal.targetPrice2 else validPrice * 1.06
    }
    var tp1PriceInput by remember(defaultTpPrice1) { mutableStateOf(String.format("%.0f", defaultTpPrice1)) }
    var tp2PriceInput by remember(defaultTpPrice2) { mutableStateOf(String.format("%.0f", defaultTpPrice2)) }
    
    val tp1Price = tp1PriceInput.toDoubleOrNull() ?: defaultTpPrice1
    val tp2Price = tp2PriceInput.toDoubleOrNull() ?: defaultTpPrice2

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
                .background(TvCardBackground, RoundedCornerShape(10.dp))
                .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
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
                        color = TvTextSecondary,
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

        // Auto Limit Sell (Server Indodax Direct Order) Card
        if (isRealMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                    .border(1.dp, TvGreen.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isAutoLimitSellEnabled,
                                onCheckedChange = { isAutoLimitSellEnabled = it },
                                colors = CheckboxDefaults.colors(checkedColor = TvGreen, uncheckedColor = TvTextSecondary),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "🚀 SPLIT AUTO LIMIT SELL SERVER (INDODAX)",
                                color = TvGreen,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isAutoLimitSellEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Setelah BUY ACC, sistem langsung pasang 2 Limit Sell otomatis (Masing-masing 50% Qty koin) di Server Indodax tanpa perlu HP standby:",
                                color = TvTextSecondary,
                                fontSize = 9.5.sp
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = tp1PriceInput,
                                    onValueChange = { input ->
                                        tp1PriceInput = input.filter { it.isDigit() || it == '.' }
                                    },
                                    label = { Text("Target TP 1 (50% Qty)", fontSize = 9.5.sp) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = TvGreen,
                                        unfocusedBorderColor = TvBorder,
                                        focusedContainerColor = TvSurfaceVariant,
                                        unfocusedContainerColor = TvSurfaceVariant,
                                        focusedTextColor = TvTextPrimary,
                                        unfocusedTextColor = TvTextPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedTextField(
                                    value = tp2PriceInput,
                                    onValueChange = { input ->
                                        tp2PriceInput = input.filter { it.isDigit() || it == '.' }
                                    },
                                    label = { Text("Target TP 2 (50% Qty)", fontSize = 9.5.sp) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = TvGreen,
                                        unfocusedBorderColor = TvBorder,
                                        focusedContainerColor = TvSurfaceVariant,
                                        unfocusedContainerColor = TvSurfaceVariant,
                                        focusedTextColor = TvTextPrimary,
                                        unfocusedTextColor = TvTextPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        // Toggle Expandable Risk Management & Position Sizing Calculator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(TvCardBackground, RoundedCornerShape(8.dp))
                .border(1.dp, TvBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🛡️ POSITION SIZING (RISK MGMT)",
                        color = TvBlue,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black
                    )

                    TextButton(
                        onClick = { isRiskCalculatorOpen = !isRiskCalculatorOpen },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = if (isRiskCalculatorOpen) "Close ▲" else "Calculate ▼",
                            color = TvBlue,
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
                            text = "Control maximum loss per trade based on risk budget:",
                            color = Color(0xFFB0BEC5),
                            fontSize = 9.5.sp
                        )

                        // Selector Risk %
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("1. Risk / Trade:", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(1.0, 2.0, 3.0, 5.0).forEach { r ->
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (selectedRiskPct == r) TvBlue else TvSurfaceVariant,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                0.5.dp,
                                                if (selectedRiskPct == r) TvBlue else TvBorder,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { selectedRiskPct = r }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "$r%",
                                            color = if (selectedRiskPct == r) Color.Black else TvTextPrimary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
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
                            Text("2. Stop Loss (SL):", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(1.5, 2.5, 3.5, 5.0).forEach { sl ->
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (selectedSlTolerancePct == sl) TvRed else TvSurfaceVariant,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                0.5.dp,
                                                if (selectedSlTolerancePct == sl) TvRed else TvBorder,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { selectedSlTolerancePct = sl }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "-$sl%",
                                            color = if (selectedSlTolerancePct == sl) Color.White else TvTextPrimary,
                                            fontSize = 10.sp,
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
                                .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                                .border(0.5.dp, TvBorder, RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Max Loss Tolerated:", color = TvTextSecondary, fontSize = 9.5.sp)
                                    Text("Rp ${PriceFormatter.formatIdrNumber(maxRiskAmountIdr)} ($selectedRiskPct% Capital)", color = TvRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Suggested Position Size:", color = TvBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("Rp ${PriceFormatter.formatIdrNumber(calculatedPositionSizeIdr)}", color = TvBlue, fontSize = 11.sp, fontWeight = FontWeight.Black)
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
                            colors = ButtonDefaults.buttonColors(containerColor = TvBlue, contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().height(34.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("✓ Apply Size (${PriceFormatter.formatIdrNumber(calculatedPositionSizeIdr)} IDR)", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Quick Nominal Selector
        Text(
            text = "PILIH NOMINAL PEMBELIAN (IDR):",
            color = TvTextSecondary,
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
                        focusedBorderColor = TvBlue,
                        unfocusedBorderColor = TvBorder,
                        focusedContainerColor = TvSurfaceVariant,
                        unfocusedContainerColor = TvSurfaceVariant,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
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
                .background(TvCardBackground, RoundedCornerShape(10.dp))
                .border(0.5.dp, TvBorder, RoundedCornerShape(10.dp))
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

            HorizontalDivider(color = TvBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

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
                onClick = {
                    val sell1 = if (isAutoLimitSellEnabled) tp1Price else 0.0
                    val sell2 = if (isAutoLimitSellEnabled) tp2Price else 0.0
                    onExecuteBuy(grossBuyOrderAmount, sell1, sell2)
                },
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
