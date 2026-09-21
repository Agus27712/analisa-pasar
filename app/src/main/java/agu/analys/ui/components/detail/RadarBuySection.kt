package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.engine.global.RiskBasedPositionSizer
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
    onExecuteBuy: ((Double, Double, Double, Double) -> Unit)?,
    buyCooldownRemainingMs: Long = 0L,
    buyCooldownTotalMs: Long = 0L,
    buyCooldownReason: String? = null,
    orderBookBids: List<agu.analys.model.OrderBookItem> = emptyList(),
    orderBookAsks: List<agu.analys.model.OrderBookItem> = emptyList()
) {
    var customNominalInput by remember { mutableStateOf("") }
    var isCustomNominalOpen by remember { mutableStateOf(false) }
    var showBuyOrderDialog by remember { mutableStateOf(false) }
    var customTargetBuyPrice by remember { mutableStateOf(0.0) }

    // Auto Limit Sell Server Settings (TP Direct to Server)
    var isAutoLimitSellEnabled by remember { mutableStateOf(false) }
    val defaultTpPrice1 = remember(validPrice, signal) {
        if (signal != null && signal.targetPrice1 > validPrice) signal.targetPrice1 else validPrice * 1.03
    }
    
    val recommendedRiskSize = remember(availableIdr, validPrice, signal) {
        if (signal != null && signal.stopLoss > 0.0 && signal.stopLoss < validPrice) {
            val confidenceFactor = (signal.confidence.toDouble() / 100.0).coerceIn(0.1, 1.0)
            RiskBasedPositionSizer.calculateRecommendedSize(
                accountBalance = availableIdr,
                entryPrice = validPrice,
                stopLossPrice = signal.stopLoss,
                confidenceMultiplier = confidenceFactor
            )
        } else {
            0.0
        }
    }
    val defaultTpPrice2 = remember(validPrice, signal) {
        if (signal != null && signal.targetPrice2 > validPrice) signal.targetPrice2 else validPrice * 1.06
    }
    var tp1PriceInput by remember(defaultTpPrice1) { mutableStateOf(String.format("%.0f", defaultTpPrice1)) }
    var tp2PriceInput by remember(defaultTpPrice2) { mutableStateOf(String.format("%.0f", defaultTpPrice2)) }
    
    val tp1Price = tp1PriceInput.toDoubleOrNull() ?: defaultTpPrice1
    val tp2Price = tp2PriceInput.toDoubleOrNull() ?: defaultTpPrice2

    val focusManager = LocalFocusManager.current

    val effectiveBuyPrice = if (customTargetBuyPrice > 0.0) customTargetBuyPrice else validPrice
    val grossBuyOrderAmount = selectedNominalIdr.coerceAtLeast(10000.0)
    val isMakerOrder = validPrice > 0 && effectiveBuyPrice < validPrice
    val effectiveFeePct = if (isMakerOrder) 0.0 else activeFeePct
    val buyFeeIdr = grossBuyOrderAmount * (effectiveFeePct / 100.0)
    val netBuyAmountIdr = (grossBuyOrderAmount - buyFeeIdr).coerceAtLeast(0.0)
    val estimatedBuyCoinQty = if (effectiveBuyPrice > 0) netBuyAmountIdr / effectiveBuyPrice else 0.0

    Column {
        if (orderBookBids.isNotEmpty() || orderBookAsks.isNotEmpty()) {
            SpreadGuardAndEntrySection(
                bids = orderBookBids,
                asks = orderBookAsks,
                currentPrice = validPrice,
                quoteAsset = quoteAsset,
                onApplyRecommendedPrice = { recPrice ->
                    customTargetBuyPrice = recPrice
                }
            )
            Spacer(Modifier.height(8.dp))
        }

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
                                text = "SPLIT AUTO LIMIT SELL",
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
                                text = "Setelah BUY OK, sistem langsung pasang 2 Limit Sell otomatis (Masing-masing 50% Qty koin):",
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
                                    label = { Text("TP 1 (50% Qty)", fontSize = 9.5.sp) },
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
                                    label = { Text("TP 2 (50% Qty)", fontSize = 9.5.sp) },
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

        Spacer(Modifier.height(8.dp))

        // Quick Nominal Selector

        Spacer(Modifier.height(8.dp))

        // Quick Nominal Selector
        Text(
            text = "PILIH JUMLAH SALDO DIGUNAKAN:",
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
            if (recommendedRiskSize >= 10000.0) {
                QuickNominalChip(
                    label = "2% Risk",
                    selected = !isCustomNominalOpen && selectedNominalIdr > 0 && Math.abs(selectedNominalIdr - recommendedRiskSize) < 100,
                    onClick = {
                        onNominalIdrChanged(recommendedRiskSize.toLong().toDouble())
                        isCustomNominalOpen = false
                    },
                    modifier = Modifier.weight(1.2f)
                )
            }
            val percentages = listOf(25, 50, 75, 100)
            percentages.forEach { pct ->
                val calculatedAmount = if (availableIdr > 0) (availableIdr * (pct / 100.0)).toLong().toDouble() else 0.0
                QuickNominalChip(
                    label = "$pct%",
                    selected = !isCustomNominalOpen && selectedNominalIdr > 0 && 
                              (Math.abs(selectedNominalIdr - calculatedAmount) < 100 || (pct == 100 && selectedNominalIdr == availableIdr)),
                    onClick = {
                        val amount = if (pct == 100) availableIdr else calculatedAmount
                        onNominalIdrChanged(amount.toLong().toDouble()) // Ensure integer for IDR
                        isCustomNominalOpen = false
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (customTargetBuyPrice > 0.0 && customTargetBuyPrice != validPrice) "Harga Beli (Limit)" else "Harga Beli (Pasar)",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        modifier = Modifier.clickable { showBuyOrderDialog = true },
                        shape = RoundedCornerShape(4.dp),
                        color = TvGreen.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, TvGreen)
                    ) {
                        Text(
                            text = "Atur Limit",
                            color = TvGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "${PriceFormatter.formatIdrNumber(effectiveBuyPrice)} $quoteAsset",
                    color = if (customTargetBuyPrice > 0.0 && customTargetBuyPrice != validPrice) TvGreen else TvTextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            TransactionDetailRow(
                label = "Nominal Order",
                value = "${PriceFormatter.formatIdrNumber(grossBuyOrderAmount)} $quoteAsset"
            )
            TransactionDetailRow(
                label = "Biaya Fee (${String.format("%.2f", effectiveFeePct)}%)",
                value = if (isMakerOrder) "Rp 0 (Maker)" else "- ${PriceFormatter.formatIdrNumber(buyFeeIdr)} $quoteAsset",
                valueColor = if (isMakerOrder) TvGreen else TvRed
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

        // Banner Timer Countdown Presisi (Detik & Milidetik)
        AnimatedVisibility(
            visible = buyCooldownRemainingMs > 0,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val remainingSec = buyCooldownRemainingMs / 1000.0
            val progress = if (buyCooldownTotalMs > 0) (buyCooldownRemainingMs.toFloat() / buyCooldownTotalMs.toFloat()).coerceIn(0f, 1f) else 0f
            val isEngineReady = signal?.let { it.mtf.entryPriceStatus.name == "OK" || it.mtf.entryPriceOk || it.action == agu.analys.model.SignalAction.BUY } ?: true

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TvAmber.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, TvAmber.copy(alpha = 0.55f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = TvAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "PENYESUAIAN KUOTASI PASAR",
                                color = TvAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = TvSurface
                        ) {
                            Text(
                                text = String.format(java.util.Locale.US, "%.2fs", remainingSec),
                                color = TvAmber,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = buyCooldownReason ?: "Harga pasar bergerak atau data harga tertunda. Menunggu kuotasi harga fresh...",
                        color = TvTextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = TvAmber,
                        trackColor = TvSurfaceVariant
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEngineReady) "Status Sinyal: READY BUY" else "Status Sinyal: MENUNGGU SETUP",
                            color = if (isEngineReady) TvGreen else TvTextMuted,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isEngineReady) "Siap-siap tekan Beli..." else "Menunggu kuotasi baru...",
                            color = TvTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Tombol Eksekusi Beli Terintegrasi
        if (onExecuteBuy != null) {
            Spacer(Modifier.height(10.dp))
            val isCoolingDown = buyCooldownRemainingMs > 0
            val remainingSec = buyCooldownRemainingMs / 1000.0
            val isEngineReady = signal?.let { it.mtf.entryPriceStatus.name == "OK" || it.mtf.entryPriceOk || it.action == agu.analys.model.SignalAction.BUY } ?: true

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (!isCoolingDown) {
                            val buyPrice = if (customTargetBuyPrice > 0.0) customTargetBuyPrice else validPrice
                            val tp1 = if (isAutoLimitSellEnabled) defaultTpPrice1 else 0.0
                            val tp2 = if (isAutoLimitSellEnabled) defaultTpPrice2 else 0.0
                            onExecuteBuy.invoke(grossBuyOrderAmount, buyPrice, tp1, tp2)
                        }
                    },
                    enabled = !isCoolingDown,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCoolingDown) TvSurfaceVariant else if (isEngineReady) TvGreen else TvBorder,
                        contentColor = if (isCoolingDown) TvAmber else if (isEngineReady) Color.Black else TvTextSecondary,
                        disabledContainerColor = TvSurfaceVariant,
                        disabledContentColor = TvAmber
                    )
                ) {
                    if (isCoolingDown) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = TvAmber
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "MENUNGGU (${String.format(java.util.Locale.US, "%.2fs", remainingSec)})",
                            fontWeight = FontWeight.Black,
                            fontSize = 11.5.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isRealMode) {
                                "[REAL] BELI (${PriceFormatter.formatIdrNumber(grossBuyOrderAmount)})"
                            } else {
                                "[SIM] BELI (${PriceFormatter.formatIdrNumber(grossBuyOrderAmount)})"
                            },
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }

                OutlinedButton(
                    onClick = { showBuyOrderDialog = true },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, TvBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = TvSurfaceVariant,
                        contentColor = TvTextSecondary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(
                        text = "Atur Limit",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (showBuyOrderDialog) {
            CustomBuyOrderDialog(
                show = showBuyOrderDialog,
                onDismiss = { showBuyOrderDialog = false },
                validPrice = validPrice,
                baseAsset = baseAsset,
                quoteAsset = quoteAsset,
                availableIdr = availableIdr,
                initialNominalIdr = grossBuyOrderAmount,
                activeFeePct = activeFeePct,
                isRealMode = isRealMode,
                initialTp1 = defaultTpPrice1,
                initialTp2 = defaultTpPrice2,
                recommendedRiskSize = recommendedRiskSize,
                buyCooldownRemainingMs = buyCooldownRemainingMs,
                buyCooldownReason = buyCooldownReason,
                onConfirmBuy = { nominal, targetBuyPrice, tp1, tp2 ->
                    customTargetBuyPrice = targetBuyPrice
                    onNominalIdrChanged(nominal)
                    onExecuteBuy?.invoke(nominal, targetBuyPrice, tp1, tp2)
                }
            )
        }
    }
}
