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
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import java.util.Locale

@Composable
fun RadarSellSection(
    validPrice: Double,
    baseAsset: String,
    quoteAsset: String,
    availableCoin: Double,
    avgBuyPrice: Double,
    selectedSellQuantity: Double,
    onSellQuantityChanged: (Double) -> Unit,
    activeFeePct: Double,
    isRealMode: Boolean,
    onExecuteSell: ((Double) -> Unit)?,
    onSetManualBuyPrice: ((Double, Double) -> Unit)? = null,
    spotPosition: agu.analys.trading.SpotPosition? = null,
    onSetTrailingStop: ((Boolean, Double) -> Unit)? = null,
    onResetTrailingTrigger: (() -> Unit)? = null,
    signal: agu.analys.model.AISignalState? = null,
    onSetAutoSellParams: ((Boolean, Double, Double, Double, Double, Double) -> Unit)? = null
) {
    var customSellQtyInput by remember { mutableStateOf("") }
    var isCustomSellQtyOpen by remember { mutableStateOf(false) }
    var selectedSellPercent by remember { mutableIntStateOf(100) }
    var isTrailingOptionsOpen by remember { mutableStateOf(false) }

    var isAutoSellActive by remember { mutableStateOf(spotPosition?.isAutoSellEnabled == true) }
    var tp1PriceInput by remember { mutableStateOf("") }
    var tp1PercentInput by remember { mutableStateOf("50") }
    var tp2PriceInput by remember { mutableStateOf("") }
    var tp2PercentInput by remember { mutableStateOf("100") }
    var stopLossPriceInput by remember { mutableStateOf("") }

    LaunchedEffect(spotPosition, signal) {
        if (spotPosition != null) {
            isAutoSellActive = spotPosition.isAutoSellEnabled
            tp1PriceInput = if (spotPosition.tp1Price > 0.0) {
                String.format(Locale.US, "%.0f", spotPosition.tp1Price)
            } else if (signal != null && signal.targetPrice1 > 0.0) {
                String.format(Locale.US, "%.0f", signal.targetPrice1)
            } else ""

            tp2PriceInput = if (spotPosition.tp2Price > 0.0) {
                String.format(Locale.US, "%.0f", spotPosition.tp2Price)
            } else if (signal != null && signal.targetPrice2 > 0.0) {
                String.format(Locale.US, "%.0f", signal.targetPrice2)
            } else ""

            stopLossPriceInput = if (spotPosition.stopLossPrice > 0.0) {
                String.format(Locale.US, "%.0f", spotPosition.stopLossPrice)
            } else if (signal != null && signal.stopLoss > 0.0) {
                String.format(Locale.US, "%.0f", signal.stopLoss)
            } else ""

            tp1PercentInput = String.format(Locale.US, "%.0f", spotPosition.tp1Percent)
            tp2PercentInput = String.format(Locale.US, "%.0f", spotPosition.tp2Percent)
        }
    }

    val isTrailingActive = spotPosition?.isTrailingEnabled == true
    val trailingPercent = spotPosition?.trailingPercent ?: 2.0
    val peakPrice = spotPosition?.peakPrice ?: validPrice
    val trailingStopPrice = spotPosition?.trailingStopPrice ?: (peakPrice * (1.0 - trailingPercent / 100.0))
    val isTrailingTriggered = spotPosition?.isTrailingTriggered == true

    // Dialog Input Manual (>7 Hari)
    var showManualDialog by remember { mutableStateOf(false) }
    var manualAvgBuyInput by remember { mutableStateOf("") }
    var manualTotalCostInput by remember { mutableStateOf("") }

    val focusManager = LocalFocusManager.current

    val activeSellQty = if (selectedSellQuantity > 0.0) selectedSellQuantity else availableCoin
    val grossSellValueIdr = activeSellQty * validPrice
    val sellFeeIdr = grossSellValueIdr * (activeFeePct / 100.0)
    val netReceivedSellIdr = (grossSellValueIdr - sellFeeIdr).coerceAtLeast(0.0)

    val effectiveBuyPrice = avgBuyPrice
    val costBasisIdr = activeSellQty * effectiveBuyPrice
    val netProfitIdr = if (effectiveBuyPrice > 0.0) netReceivedSellIdr - costBasisIdr else 0.0
    val netProfitPct = if (costBasisIdr > 0.0) (netProfitIdr / costBasisIdr) * 100.0 else 0.0
    val isProfitable = netProfitIdr >= 0.0

    // DIALOG MODAL INPUT HARGA BELI MANUAL (>7 HARI)
    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            containerColor = Color(0xFF0F1B2B),
            titleContentColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Set Harga Beli Manual (>7 Hari)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Gunakan ini untuk koin $baseAsset yang dibeli >7 hari lalu di Indodax. Isikan salah satu nilai di bawah dari nota Indodax Anda:",
                        color = Color(0xFFB0BEC5),
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
                            focusedBorderColor = Color(0xFFFFD54F),
                            unfocusedBorderColor = Color(0xFF263C52),
                            focusedContainerColor = Color(0xFF101C2B),
                            unfocusedContainerColor = Color(0xFF101C2B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
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
                            focusedBorderColor = Color(0xFFFFD54F),
                            unfocusedBorderColor = Color(0xFF263C52),
                            focusedContainerColor = Color(0xFF101C2B),
                            unfocusedContainerColor = Color(0xFF101C2B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (availableCoin > 0.0) {
                        Text(
                            text = "Kuantitas Koin ($baseAsset): ${PriceFormatter.formatCryptoExact(availableCoin, 8)}",
                            color = Color(0xFF00E5FF),
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
                            onSetManualBuyPrice?.invoke(finalPrice, finalTotal)
                            showManualDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F), contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Simpan Harga Beli", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Batal", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                }
            }
        )
    }

    Column {
        // Card Koin yang Dimiliki
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101E2E), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF1C3754), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Koin Dimiliki (${if (isRealMode) "Real Indodax" else "Simulasi"}):",
                            color = Color(0xFFB0BEC5),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = "${PriceFormatter.formatCryptoExact(availableCoin, 8)} $baseAsset",
                        color = if (availableCoin > 0) Color(0xFF00E5FF) else TvTextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Harga Beli Rata-Rata:",
                        color = Color(0xFF78909C),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (effectiveBuyPrice > 0.0) "${PriceFormatter.formatIdrNumber(effectiveBuyPrice)} $quoteAsset" else "Belum Ada Posisi",
                            color = if (effectiveBuyPrice > 0.0) Color(0xFFFFD54F) else TvTextSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.width(6.dp))

                        // Tombol Cepat Set / Ubah Manual
                        TextButton(
                            onClick = {
                                manualAvgBuyInput = if (effectiveBuyPrice > 0.0) String.format(Locale.US, "%.0f", effectiveBuyPrice) else ""
                                manualTotalCostInput = if (costBasisIdr > 0.0) String.format(Locale.US, "%.0f", costBasisIdr) else ""
                                showManualDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.height(22.dp)
                        ) {
                            Text(
                                text = if (effectiveBuyPrice > 0.0) "[Ubah Manual]" else "[+ Manual >7 Hari]",
                                color = Color(0xFFFFD54F),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Shortcut Persentase Jual
        Text(
            text = "PILIH JUMLAH KOIN DIJUAL:",
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
            listOf(25, 50, 75, 100).forEach { pct ->
                QuickNominalChip(
                    label = "$pct%",
                    selected = selectedSellPercent == pct && !isCustomSellQtyOpen,
                    onClick = {
                        selectedSellPercent = pct
                        isCustomSellQtyOpen = false
                        val qty = (availableCoin * (pct / 100.0))
                        onSellQuantityChanged(qty)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            QuickNominalChip(
                label = "Kustom",
                selected = isCustomSellQtyOpen,
                onClick = {
                    isCustomSellQtyOpen = !isCustomSellQtyOpen
                    selectedSellPercent = -1
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Input Kustom Koin
        AnimatedVisibility(
            visible = isCustomSellQtyOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = customSellQtyInput,
                    onValueChange = { input ->
                        customSellQtyInput = input
                        val parsed = input.toDoubleOrNull()
                        if (parsed != null && parsed > 0.0) {
                            onSellQuantityChanged(parsed.coerceAtMost(availableCoin.coerceAtLeast(parsed)))
                        }
                    },
                    label = { Text("Jumlah Koin $baseAsset Dijual", fontSize = 11.sp) },
                    placeholder = { Text("Contoh: 0.005", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
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

        // Card Kalkulasi Rincian Jual & Hasil Keuntungan Bersih
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101C2A), RoundedCornerShape(10.dp))
                .border(0.5.dp, Color(0xFF1C3147), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TransactionDetailRow(
                label = "Harga Jual Sekarang",
                value = "${PriceFormatter.formatIdrNumber(validPrice)} $quoteAsset"
            )
            TransactionDetailRow(
                label = "Jumlah Koin Dijual",
                value = "${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset",
                subValue = "= ${PriceFormatter.formatIdrNumber(grossSellValueIdr)} $quoteAsset (Kotor)"
            )
            if (effectiveBuyPrice > 0.0) {
                TransactionDetailRow(
                    label = "Modal Pembelian",
                    value = "${PriceFormatter.formatIdrNumber(costBasisIdr)} $quoteAsset",
                    subValue = "(@ ${PriceFormatter.formatIdrNumber(effectiveBuyPrice)})"
                )
            }
            TransactionDetailRow(
                label = "Biaya Fee (${String.format(Locale.US, "%.2f", activeFeePct)}%)",
                value = "- ${PriceFormatter.formatIdrNumber(sellFeeIdr)} $quoteAsset",
                valueColor = TvRed
            )

            HorizontalDivider(color = Color(0xFF1B2D40), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

            // Diterima Bersih Kas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hasil Kas Diterima",
                    color = Color(0xFFB0BEC5),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${PriceFormatter.formatIdrNumber(netReceivedSellIdr)} $quoteAsset",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(4.dp))

            // Highlight HASIL KEUNTUNGAN / KERUGIAN BERSIH
            if (effectiveBuyPrice > 0.0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isProfitable) TvGreen.copy(alpha = 0.15f) else TvRed.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            if (isProfitable) TvGreen else TvRed,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isProfitable) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                    contentDescription = null,
                                    tint = if (isProfitable) TvGreen else TvRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (isProfitable) "KEUNTUNGAN BERSIH (NET PROFIT)" else "KERUGIAN / CUT LOSS NET",
                                    color = if (isProfitable) TvGreen else TvRed,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "Sudah dipotong fee (${String.format(Locale.US, "%.2f", activeFeePct)}%)",
                                color = TvTextSecondary,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${if (isProfitable) "+" else ""}${PriceFormatter.formatIdrNumber(netProfitIdr)} $quoteAsset",
                                color = if (isProfitable) TvGreen else TvRed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "(${if (isProfitable) "+" else ""}${String.format(Locale.US, "%.2f", netProfitPct)}%)",
                                color = if (isProfitable) TvGreen else TvRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // Banner ajakan input manual (>7 Hari) jika harga beli belum ada
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF231B0C), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFFFD54F).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Histori >7 Hari Tidak Tersedia di API",
                                color = Color(0xFFFFD54F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Set harga beli manual dari nota Anda agar Unrealized PnL (+/-) bisa dihitung.",
                                color = Color(0xFFD1C4E9),
                                fontSize = 9.sp,
                                lineHeight = 12.sp
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        Button(
                            onClick = { showManualDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F), contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("+ Input Manual", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // --- TARGET TAKE PROFIT & STOP LOSS OTOMATIS CARD ---
        if (effectiveBuyPrice > 0.0 || availableCoin > 0.0) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isAutoSellActive) Color(0xFF0F1E24) else Color(0xFF101B26),
                        RoundedCornerShape(10.dp)
                    )
                    .border(
                        1.dp,
                        if (isAutoSellActive) TvGreen.copy(alpha = 0.8f) else Color(0xFF1E3247),
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
                                text = if (isRealMode) "🎯 AUTO TP1 & TP2 SERVER" else "🎯 AUTO TP1, TP2 & STOP LOSS",
                                color = if (isAutoSellActive) TvGreen else Color(0xFF90A4AE),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Switch(
                            checked = isAutoSellActive,
                            onCheckedChange = { enabled ->
                                isAutoSellActive = enabled
                                onSetAutoSellParams?.invoke(
                                    enabled,
                                    PriceFormatter.parseCleanIdrDouble(tp1PriceInput),
                                    PriceFormatter.parseCleanIdrDouble(tp1PercentInput).coerceIn(1.0, 100.0),
                                    PriceFormatter.parseCleanIdrDouble(tp2PriceInput),
                                    PriceFormatter.parseCleanIdrDouble(tp2PercentInput).coerceIn(1.0, 100.0),
                                    PriceFormatter.parseCleanIdrDouble(stopLossPriceInput)
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TvGreen,
                                checkedTrackColor = TvGreen.copy(alpha = 0.4f),
                                uncheckedThumbColor = Color(0xFF78909C),
                                uncheckedTrackColor = Color(0xFF1E2D3D)
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }

                    if (isAutoSellActive) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Row for TP1
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1.6f)) {
                                    Text("Harga TP 1 ($quoteAsset)", color = Color(0xFFB0BEC5), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(3.dp))
                                    OutlinedTextField(
                                        value = tp1PriceInput,
                                        onValueChange = { tp1PriceInput = it },
                                        placeholder = { Text("Harga TP 1", fontSize = 10.5.sp, color = Color(0xFF546E7A)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = TvGreen,
                                            unfocusedBorderColor = Color(0xFF263C52),
                                            focusedContainerColor = Color(0xFF0C131A),
                                            unfocusedContainerColor = Color(0xFF0C131A),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(42.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Porsi TP 1 %", color = Color(0xFFB0BEC5), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(3.dp))
                                    OutlinedTextField(
                                        value = tp1PercentInput,
                                        onValueChange = { tp1PercentInput = it },
                                        placeholder = { Text("50", fontSize = 10.5.sp, color = Color(0xFF546E7A)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = TvGreen,
                                            unfocusedBorderColor = Color(0xFF263C52),
                                            focusedContainerColor = Color(0xFF0C131A),
                                            unfocusedContainerColor = Color(0xFF0C131A),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(42.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White)
                                    )
                                }
                            }

                            // Row for TP2
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1.6f)) {
                                    Text("Harga TP 2 ($quoteAsset)", color = Color(0xFFB0BEC5), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(3.dp))
                                    OutlinedTextField(
                                        value = tp2PriceInput,
                                        onValueChange = { tp2PriceInput = it },
                                        placeholder = { Text("Harga TP 2", fontSize = 10.5.sp, color = Color(0xFF546E7A)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = TvGreen,
                                            unfocusedBorderColor = Color(0xFF263C52),
                                            focusedContainerColor = Color(0xFF0C131A),
                                            unfocusedContainerColor = Color(0xFF0C131A),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(42.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Porsi TP 2 %", color = Color(0xFFB0BEC5), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(3.dp))
                                    OutlinedTextField(
                                        value = tp2PercentInput,
                                        onValueChange = { tp2PercentInput = it },
                                        placeholder = { Text("100", fontSize = 10.5.sp, color = Color(0xFF546E7A)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = TvGreen,
                                            unfocusedBorderColor = Color(0xFF263C52),
                                            focusedContainerColor = Color(0xFF0C131A),
                                            unfocusedContainerColor = Color(0xFF0C131A),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(42.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White)
                                    )
                                }
                            }

                            // Stop Loss or Real Mode Notice
                            if (!isRealMode) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text("Harga Stop Loss ($quoteAsset)", color = Color(0xFFEF9A9A), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(3.dp))
                                    OutlinedTextField(
                                        value = stopLossPriceInput,
                                        onValueChange = { stopLossPriceInput = it },
                                        placeholder = { Text("Harga Stop Loss", fontSize = 10.5.sp, color = Color(0xFFEF9A9A).copy(alpha = 0.5f)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = TvRed,
                                            unfocusedBorderColor = Color(0xFF263C52),
                                            focusedContainerColor = Color(0xFF0C131A),
                                            unfocusedContainerColor = Color(0xFF0C131A),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(42.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White)
                                    )
                                }
                            } else {
                                // Red outline notice for Indodax API restriction
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF231012), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFFD32F2F).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "⚠️ Di Akun Riil, Stop Loss (SL) otomatis ditiadakan karena keterbatasan saldo terkunci di server Indodax. Gunakan TP1 & TP2 murni server agar aman.",
                                        color = Color(0xFFEF9A9A),
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp
                                    )
                                }
                            }

                            // Save button
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    onSetAutoSellParams?.invoke(
                                        true,
                                        PriceFormatter.parseCleanIdrDouble(tp1PriceInput),
                                        PriceFormatter.parseCleanIdrDouble(tp1PercentInput).coerceIn(1.0, 100.0),
                                        PriceFormatter.parseCleanIdrDouble(tp2PriceInput),
                                        PriceFormatter.parseCleanIdrDouble(tp2PercentInput).coerceIn(1.0, 100.0),
                                        if (isRealMode) 0.0 else PriceFormatter.parseCleanIdrDouble(stopLossPriceInput)
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = TvGreen, contentColor = Color.Black),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Text("Simpan Target Jual Otomatis", fontSize = 11.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    } else {
                        Text(
                            text = if (isRealMode) "Aktifkan untuk memasang target Take Profit 1 (50%) dan Take Profit 2 (50%) otomatis di server Indodax."
                                   else "Aktifkan untuk menjual secara otomatis pada target Take Profit 1 (parsial), Take Profit 2 (sisa), dan Stop Loss yang ditentukan.",
                            color = Color(0xFF78909C),
                            fontSize = 9.5.sp
                        )
                    }
                }
            }
        }

        // --- TRAILING STOP LOSS PROTECTION CARD ---
        if (effectiveBuyPrice > 0.0 || availableCoin > 0.0) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isTrailingTriggered) Color(0xFF331515) else if (isTrailingActive) Color(0xFF0D253A) else Color(0xFF101B26),
                        RoundedCornerShape(10.dp)
                    )
                    .border(
                        1.dp,
                        if (isTrailingTriggered) TvRed else if (isTrailingActive) Color(0xFF00E5FF) else Color(0xFF1E3247),
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
                                color = if (isTrailingTriggered) TvRed else if (isTrailingActive) Color(0xFF00E5FF) else Color(0xFF90A4AE),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Black
                            )
                            if (isTrailingTriggered) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(TvRed, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("TERPICU / EXIT NOW", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }

                        Switch(
                            checked = isTrailingActive,
                            onCheckedChange = { enabled ->
                                onSetTrailingStop?.invoke(enabled, trailingPercent)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.4f),
                                uncheckedThumbColor = Color(0xFF78909C),
                                uncheckedTrackColor = Color(0xFF1E2D3D)
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }

                    if (isTrailingActive) {
                        // Preset Persentase Trailing
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Jarak Trailing (Dari Peak):", color = Color(0xFFB0BEC5), fontSize = 10.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(1.5, 2.0, 3.0, 5.0).forEach { pct ->
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (trailingPercent == pct) Color(0xFF00E5FF) else Color(0xFF16273B),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                0.5.dp,
                                                if (trailingPercent == pct) Color(0xFF00E5FF) else Color(0xFF263C52),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { onSetTrailingStop?.invoke(true, pct) }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "$pct%",
                                            color = if (trailingPercent == pct) Color.Black else Color.White,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Info Real-time Trailing
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0A1624), RoundedCornerShape(6.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Harga Puncak Tercatat:", color = Color(0xFF78909C), fontSize = 9.5.sp)
                                Text("${PriceFormatter.formatIdrNumber(peakPrice)} $quoteAsset", color = Color(0xFFFFD54F), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Garis Stop Loss Dinamis:", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("${PriceFormatter.formatIdrNumber(trailingStopPrice)} $quoteAsset", color = if (isTrailingTriggered) TvRed else Color(0xFF00E5FF), fontSize = 10.5.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    } else {
                        Text(
                            text = "Aktifkan untuk mengunci profit otomatis: stop loss naik mengikuti kenaikan harga puncak.",
                            color = Color(0xFF78909C),
                            fontSize = 9.5.sp
                        )
                    }
                }
            }
        }

        // Tombol Eksekusi Jual Terintegrasi
        if (onExecuteSell != null) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onExecuteSell(activeSellQty) },
                enabled = availableCoin > 0.0 && activeSellQty > 0.0,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TvRed,
                    disabledContainerColor = TvRed.copy(alpha = 0.3f),
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (isRealMode) "[REAL] JUAL ${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset" else "[SIM] JUAL ${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
