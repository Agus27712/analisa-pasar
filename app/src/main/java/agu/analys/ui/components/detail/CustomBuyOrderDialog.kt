package agu.analys.ui.components.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale
import kotlin.math.abs

@Composable
fun CustomBuyOrderDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    validPrice: Double,
    baseAsset: String,
    quoteAsset: String,
    availableIdr: Double,
    initialNominalIdr: Double,
    activeFeePct: Double,
    isRealMode: Boolean,
    initialTp1: Double = 0.0,
    initialTp2: Double = 0.0,
    onConfirmBuy: (nominalIdr: Double, buyPrice: Double, tp1Price: Double, tp2Price: Double) -> Unit
) {
    if (!show) return

    val focusManager = LocalFocusManager.current

    fun formatPriceForInput(p: Double): String {
        if (p <= 0.0) return ""
        return if (p < 1.0) {
            String.format(Locale.US, "%.8f", p).trimEnd('0').trimEnd('.')
        } else if (p < 100.0) {
            String.format(Locale.US, "%.2f", p)
        } else {
            PriceFormatter.formatIdrNumber(p)
        }
    }

    var buyPriceInput by remember(show, validPrice) {
        mutableStateOf(formatPriceForInput(if (validPrice > 0) validPrice else 0.0))
    }
    var nominalInput by remember(show, initialNominalIdr) {
        val initAmount = if (initialNominalIdr >= 10000.0) initialNominalIdr else 50000.0
        mutableStateOf(PriceFormatter.formatIdrNumber(initAmount))
    }

    var isAutoLimitSellEnabled by remember(show) { mutableStateOf(false) }
    var tp1Input by remember(show, initialTp1, validPrice) {
        val tp1 = if (initialTp1 > validPrice) initialTp1 else validPrice * 1.03
        mutableStateOf(formatPriceForInput(tp1))
    }
    var tp2Input by remember(show, initialTp2, validPrice) {
        val tp2 = if (initialTp2 > validPrice) initialTp2 else validPrice * 1.06
        mutableStateOf(formatPriceForInput(tp2))
    }

    val targetPrice = PriceFormatter.parseCleanIdrDouble(buyPriceInput)
    val nominalIdr = PriceFormatter.parseCleanIdrDouble(nominalInput)

    val isMakerOrder = validPrice > 0 && targetPrice > 0 && targetPrice < validPrice
    val effectiveFeePct = if (isMakerOrder) 0.0 else activeFeePct
    val feeIdr = nominalIdr * (effectiveFeePct / 100.0)
    val netIdr = (nominalIdr - feeIdr).coerceAtLeast(0.0)
    val estimatedCoinQty = if (targetPrice > 0) netIdr / targetPrice else 0.0

    val diffPct = if (validPrice > 0 && targetPrice > 0) {
        ((targetPrice - validPrice) / validPrice) * 100.0
    } else {
        0.0
    }

    val isPriceValid = targetPrice > 0
    val isNominalValid = nominalIdr >= 10000.0
    val isBalanceSufficient = availableIdr <= 0 || nominalIdr <= availableIdr
    val canSubmit = isPriceValid && isNominalValid && isBalanceSufficient

    fun applyPriceDiscount(pct: Double) {
        if (validPrice <= 0.0) return
        val calculated = if (validPrice < 1.0) {
            validPrice * (1.0 + pct / 100.0)
        } else if (validPrice < 100.0) {
            String.format(Locale.US, "%.2f", validPrice * (1.0 + pct / 100.0)).toDoubleOrNull() ?: validPrice
        } else {
            kotlin.math.round(validPrice * (1.0 + pct / 100.0))
        }
        buyPriceInput = formatPriceForInput(calculated)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TvSurface,
        titleContentColor = TvTextPrimary,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ShoppingCart,
                        contentDescription = null,
                        tint = TvGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Order Beli $baseAsset / $quoteAsset",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = TvTextPrimary
                        )
                        Text(
                            text = if (isRealMode) "Real Indodax TAPI v2" else "Simulasi Trading Spot",
                            fontSize = 10.sp,
                            color = if (isRealMode) TvAmber else TvBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Info Saldo & Harga Pasar Saat Ini
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                        .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Harga Pasar Saat Ini:",
                                fontSize = 10.sp,
                                color = TvTextSecondary
                            )
                            Text(
                                text = "${PriceFormatter.formatIdrNumber(validPrice)} $quoteAsset",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TvTextPrimary
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Saldo IDR Tersedia:",
                                fontSize = 10.sp,
                                color = TvTextSecondary
                            )
                            Text(
                                text = "${PriceFormatter.formatIdrNumber(availableIdr)} $quoteAsset",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (availableIdr > 0) TvGreen else TvTextSecondary
                            )
                        }
                    }
                }

                // 1. INPUT HARGA BELI (MANUAL)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "HARGA BELI TARGET ($quoteAsset):",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TvTextPrimary
                        )
                        if (targetPrice > 0 && validPrice > 0) {
                            val badgeText = when {
                                abs(diffPct) < 0.05 -> "Harga Pasar"
                                diffPct < 0 -> "${String.format(Locale.US, "%.2f", diffPct)}% (Maker)"
                                else -> "+${String.format(Locale.US, "%.2f", diffPct)}% (Taker)"
                            }
                            val badgeColor = when {
                                diffPct < 0 -> TvGreen
                                abs(diffPct) < 0.05 -> TvBlue
                                else -> TvOrange
                            }
                            Text(
                                text = badgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }
                    }

                    OutlinedTextField(
                        value = buyPriceInput,
                        onValueChange = { input ->
                            buyPriceInput = input.filter { it.isDigit() || it == '.' || it == ',' }
                        },
                        placeholder = { Text("Contoh: 13950", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvGreen,
                            unfocusedBorderColor = TvBorder,
                            focusedContainerColor = TvSurfaceVariant,
                            unfocusedContainerColor = TvSurfaceVariant,
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Tombol Pintas Harga (Quick Chips)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        QuickPriceChip(
                            label = "Pasar",
                            selected = targetPrice > 0 && abs(targetPrice - validPrice) < 0.5,
                            onClick = {
                                buyPriceInput = formatPriceForInput(validPrice)
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickPriceChip(
                            label = "-0.2%",
                            selected = false,
                            onClick = {
                                applyPriceDiscount(-0.2)
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickPriceChip(
                            label = "-0.5%",
                            selected = false,
                            onClick = {
                                applyPriceDiscount(-0.5)
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickPriceChip(
                            label = "-1.0%",
                            selected = false,
                            onClick = {
                                applyPriceDiscount(-1.0)
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickPriceChip(
                            label = "-2.0%",
                            selected = false,
                            onClick = {
                                applyPriceDiscount(-2.0)
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 2. INPUT NOMINAL MODAL (IDR)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "MODAL PEMBELIAN ($quoteAsset):",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TvTextPrimary
                    )

                    OutlinedTextField(
                        value = nominalInput,
                        onValueChange = { input ->
                            nominalInput = input.filter { it.isDigit() }
                        },
                        placeholder = { Text("Min. 10.000 (Cth: 80000)", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
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
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Tombol Pintas Saldo (25%, 50%, 75%, 100%)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val percentages = listOf(25, 50, 75, 100)
                        percentages.forEach { pct ->
                            val amount = if (pct == 100) availableIdr else (availableIdr * (pct / 100.0)).toLong().toDouble()
                            QuickPriceChip(
                                label = "$pct%",
                                selected = nominalIdr > 0 && abs(nominalIdr - amount) < 100,
                                onClick = {
                                    if (amount >= 10000.0) {
                                        nominalInput = PriceFormatter.formatIdrNumber(amount)
                                    } else if (availableIdr >= 10000.0) {
                                        nominalInput = PriceFormatter.formatIdrNumber(10000.0)
                                    }
                                    focusManager.clearFocus()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Status Maker / Taker Info Box
                if (targetPrice > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isMakerOrder) TvGreen.copy(alpha = 0.1f) else TvBlue.copy(alpha = 0.1f),
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (isMakerOrder) TvGreen.copy(alpha = 0.4f) else TvBlue.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isMakerOrder) Icons.Default.Verified else Icons.Default.FlashOn,
                                contentDescription = null,
                                tint = if (isMakerOrder) TvGreen else TvBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isMakerOrder) {
                                    "Maker Order: Antre di Bid pasar. Fee 0.0% (Hemat Biaya Transaksi)"
                                } else {
                                    "Taker Order: Dieksekusi langsung. Estimasi Fee ${String.format(Locale.US, "%.2f", activeFeePct)}%"
                                },
                                fontSize = 10.sp,
                                color = if (isMakerOrder) TvGreen else TvBlue,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // 3. RINGKASAN KALKULASI HASIL
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvCardBackground, RoundedCornerShape(10.dp))
                        .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total Modal", fontSize = 11.sp, color = TvTextSecondary)
                        Text(
                            "${PriceFormatter.formatIdrNumber(nominalIdr)} $quoteAsset",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TvTextPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Biaya Fee (${String.format(Locale.US, "%.2f", effectiveFeePct)}%)", fontSize = 11.sp, color = TvTextSecondary)
                        Text(
                            if (isMakerOrder) "Rp 0 (Maker)" else "- ${PriceFormatter.formatIdrNumber(feeIdr)} $quoteAsset",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMakerOrder) TvGreen else TvRed
                        )
                    }

                    HorizontalDivider(color = TvBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Estimasi Koin",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = TvTextSecondary
                        )
                        Text(
                            text = PriceFormatter.formatCryptoExact(estimatedCoinQty, 4),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TvGreen
                        )
                    }
                }

                // 4. SPLIT AUTO LIMIT SELL (OPTIONAL TP 1 & TP 2)
                if (isRealMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                            .border(1.dp, TvBorder, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isAutoLimitSellEnabled,
                                    onCheckedChange = { isAutoLimitSellEnabled = it },
                                    colors = CheckboxDefaults.colors(checkedColor = TvGreen, uncheckedColor = TvTextSecondary),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Auto Pasang Limit Sell TP Setelah Beli Match",
                                    fontSize = 10.sp,
                                    color = TvTextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (isAutoLimitSellEnabled) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedTextField(
                                        value = tp1Input,
                                        onValueChange = { tp1Input = it.filter { c -> c.isDigit() || c == '.' } },
                                        label = { Text("TP 1 (50%)", fontSize = 9.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = TvGreen,
                                            unfocusedBorderColor = TvBorder,
                                            focusedTextColor = TvTextPrimary,
                                            unfocusedTextColor = TvTextPrimary
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = tp2Input,
                                        onValueChange = { tp2Input = it.filter { c -> c.isDigit() || c == '.' } },
                                        label = { Text("TP 2 (50%)", fontSize = 9.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = TvGreen,
                                            unfocusedBorderColor = TvBorder,
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

                // Error validation warning
                if (!isNominalValid && nominalInput.isNotBlank()) {
                    Text(
                        text = "Minimal modal pembelian adalah Rp 10.000 (aturan Indodax).",
                        color = TvRed,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (!isBalanceSufficient) {
                    Text(
                        text = "Saldo IDR tidak mencukupi untuk nominal ini.",
                        color = TvRed,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tp1 = if (isAutoLimitSellEnabled) PriceFormatter.parseCleanIdrDouble(tp1Input) else 0.0
                    val tp2 = if (isAutoLimitSellEnabled) PriceFormatter.parseCleanIdrDouble(tp2Input) else 0.0
                    onConfirmBuy(nominalIdr, targetPrice, tp1, tp2)
                    onDismiss()
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TvGreen,
                    contentColor = Color.Black,
                    disabledContainerColor = TvBorder,
                    disabledContentColor = TvTextMuted
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isRealMode) {
                        "[REAL] KIRIM ORDER BELI"
                    } else {
                        "[SIM] PASANG BELI"
                    },
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Batal",
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    )
}

@Composable
private fun QuickPriceChip(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = if (selected) TvGreen.copy(alpha = 0.2f) else TvSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) TvGreen else TvBorder)
    ) {
        Box(
            modifier = Modifier.padding(vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected) TvGreen else TvTextSecondary,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}
