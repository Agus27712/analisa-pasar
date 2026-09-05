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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import agu.analys.ui.components.detail.sell.*
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
    onExecuteSell: ((Double, Boolean, Double, Double, Double, Double) -> Unit)?,
    onSetManualBuyPrice: ((Double, Double) -> Unit)? = null,
    spotPosition: agu.analys.trading.SpotPosition? = null,
    onSetTrailingStop: ((Boolean, Double) -> Unit)? = null,
    onResetTrailingTrigger: (() -> Unit)? = null,
    signal: agu.analys.model.AISignalState? = null,
    onSetAutoSellParams: ((Boolean, Double, Double, Double, Double) -> Unit)? = null,
    onDeployTrailingOrder: (() -> Unit)? = null,
    onCancelTrailingOrder: (() -> Unit)? = null,
    sellSignalState: agu.analys.model.SellSignalState = agu.analys.model.SellSignalState(),
    // Hoisted auto-sell states
    isAutoSellActive: Boolean = false,
    onAutoSellActiveChanged: (Boolean) -> Unit = {},
    tp1PriceInput: String = "",
    onTp1PriceChanged: (String) -> Unit = {},
    tp1PercentInput: String = "50",
    onTp1PercentChanged: (String) -> Unit = {},
    tp2PriceInput: String = "",
    onTp2PriceChanged: (String) -> Unit = {},
    tp2PercentInput: String = "50",
    onTp2PercentChanged: (String) -> Unit = {}
) {
    var customSellQtyInput by remember { mutableStateOf("") }
    var isCustomSellQtyOpen by remember { mutableStateOf(false) }
    var selectedSellPercent by remember { mutableIntStateOf(100) }

    val isTrailingActive = spotPosition?.isTrailingEnabled == true
    val trailingPercent = spotPosition?.trailingPercent ?: 2.0
    val peakPrice = spotPosition?.peakPrice ?: validPrice
    val trailingStopPrice = spotPosition?.trailingStopPrice ?: (peakPrice * (1.0 - trailingPercent / 100.0))
    val isTrailingTriggered = spotPosition?.isTrailingTriggered == true

    var showManualDialog by remember { mutableStateOf(false) }
    var showSellConfirmDialog by remember { mutableStateOf(false) }

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

    val showReadyBanner = sellSignalState.state == agu.analys.model.SellLifecycleState.READY_TO_SELL ||
                          sellSignalState.state == agu.analys.model.SellLifecycleState.TRAILING_TRIGGERED ||
                          sellSignalState.state == agu.analys.model.SellLifecycleState.STOP_LOSS_HIT

    Column {
        if (showReadyBanner) {
            val bannerColor = if (sellSignalState.state == agu.analys.model.SellLifecycleState.READY_TO_SELL) TvGreen else TvRed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(bannerColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .border(1.dp, bannerColor, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, tint = bannerColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(sellSignalState.reason, color = bannerColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        SellPositionHeader(
            isRealMode = isRealMode,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            availableCoin = availableCoin,
            effectiveBuyPrice = effectiveBuyPrice,
            onManualBuyClick = { showManualDialog = true }
        )

        Spacer(Modifier.height(8.dp))

        // Shortcut Persentase Jual
        Text(text = "PILIH JUMLAH KOIN DIJUAL:", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Black)
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
                        onSellQuantityChanged(availableCoin * (pct / 100.0))
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

        AnimatedVisibility(visible = isCustomSellQtyOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            OutlinedTextField(
                value = customSellQtyInput,
                onValueChange = { input ->
                    customSellQtyInput = input
                    val parsed = input.toDoubleOrNull()
                    if (parsed != null && parsed > 0.0) onSellQuantityChanged(parsed.coerceAtMost(availableCoin))
                },
                label = { Text("Jumlah Koin $baseAsset Dijual", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TvBlue, unfocusedBorderColor = TvBorder, focusedContainerColor = TvSurfaceVariant, unfocusedContainerColor = TvSurfaceVariant, focusedTextColor = TvTextPrimary, unfocusedTextColor = TvTextPrimary),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        SellCalculationCard(
            validPrice = validPrice,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            activeSellQty = activeSellQty,
            grossSellValueIdr = grossSellValueIdr,
            effectiveBuyPrice = effectiveBuyPrice,
            costBasisIdr = costBasisIdr,
            sellFeeIdr = sellFeeIdr,
            activeFeePct = activeFeePct,
            netReceivedSellIdr = netReceivedSellIdr,
            isProfitable = isProfitable,
            netProfitIdr = netProfitIdr,
            netProfitPct = netProfitPct,
            onManualBuyClick = { showManualDialog = true }
        )

        // AUTO TP1/TP2 untuk simulasi maupun real mode saat user buka switch & tap SIMPAN
        if (effectiveBuyPrice > 0.0 || availableCoin > 0.0) {
            Spacer(Modifier.height(8.dp))
            SellTrailingSection(
                isTrailingActive = isTrailingActive,
                onTrailingActiveChanged = { enabled -> onSetTrailingStop?.invoke(enabled, trailingPercent) },
                isTrailingTriggered = isTrailingTriggered,
                trailingPercent = trailingPercent,
                onSetTrailingPercent = { pct -> onSetTrailingStop?.invoke(true, pct) },
                peakPrice = peakPrice,
                trailingStopPrice = trailingStopPrice,
                quoteAsset = quoteAsset,
                lastTrailingOrderId = spotPosition?.lastTrailingOrderId,
                onDeployTrailingOrder = onDeployTrailingOrder,
                onCancelTrailingOrder = onCancelTrailingOrder,
                isRealMode = isRealMode
            )
        }

        val parsedTp1Price = PriceFormatter.parseCleanIdrDouble(tp1PriceInput)
        val parsedTp1Pct = PriceFormatter.parseCleanIdrDouble(tp1PercentInput).coerceIn(1.0, 99.0).takeIf { it > 0 } ?: 50.0
        val parsedTp2Price = PriceFormatter.parseCleanIdrDouble(tp2PriceInput)
        val parsedTp2Pct = PriceFormatter.parseCleanIdrDouble(tp2PercentInput).coerceIn(1.0, 99.0).takeIf { it > 0 } ?: 50.0

        val hasTwoTpOrders = isAutoSellActive && parsedTp1Price > 0.0 && parsedTp2Price > 0.0
        val hasSingleTpOrder = isAutoSellActive && (parsedTp1Price > 0.0 || parsedTp2Price > 0.0) && !hasTwoTpOrders

        val previewQty1 = if (hasTwoTpOrders) {
            ((activeSellQty * (parsedTp1Pct / 100.0)) * 100_000_000.0).toLong() / 100_000_000.0
        } else 0.0
        val previewQty2 = if (hasTwoTpOrders) activeSellQty - previewQty1 else 0.0

        Spacer(Modifier.height(12.dp))

        // TOMBOL EKSEKUSI JUAL
        val sellButtonLabel = when {
            hasTwoTpOrders -> "${if (isRealMode) "[REAL] " else "[SIMULASI] "}PASANG 2 ORDER TP (${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset)"
            hasSingleTpOrder -> "${if (isRealMode) "[REAL] " else "[SIMULASI] "}PASANG ORDER TP (${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset)"
            else -> "${if (isRealMode) "[REAL] " else "[SIMULASI] "}JUAL ${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset"
        }

        Button(
            onClick = {
                if (isRealMode) {
                    showSellConfirmDialog = true
                } else {
                    onExecuteSell?.invoke(activeSellQty, isAutoSellActive, parsedTp1Price, parsedTp1Pct, parsedTp2Price, parsedTp2Pct)
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TvRed, contentColor = Color.White),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = sellButtonLabel,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp
            )
        }

        if (isTrailingTriggered) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onResetTrailingTrigger?.invoke() },
                modifier = Modifier.fillMaxWidth().height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant, contentColor = TvTextPrimary),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvRed)
            ) {
                Text("RESET TRAILING TRIGGER", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    SellManualBuyDialog(
        show = showManualDialog,
        onDismiss = { showManualDialog = false },
        baseAsset = baseAsset,
        quoteAsset = quoteAsset,
        availableCoin = availableCoin,
        initialAvgBuy = avgBuyPrice,
        initialTotalCost = costBasisIdr,
        onSave = { price, total ->
            onSetManualBuyPrice?.invoke(price, total)
            showManualDialog = false
        }
    )

    if (showSellConfirmDialog) {
        val parsedTp1Price = PriceFormatter.parseCleanIdrDouble(tp1PriceInput)
        val parsedTp1Pct = PriceFormatter.parseCleanIdrDouble(tp1PercentInput).coerceIn(1.0, 99.0).takeIf { it > 0 } ?: 50.0
        val parsedTp2Price = PriceFormatter.parseCleanIdrDouble(tp2PriceInput)
        val parsedTp2Pct = PriceFormatter.parseCleanIdrDouble(tp2PercentInput).coerceIn(1.0, 99.0).takeIf { it > 0 } ?: 50.0

        val hasTwoTpOrders = isAutoSellActive && parsedTp1Price > 0.0 && parsedTp2Price > 0.0
        val previewQty1 = if (hasTwoTpOrders) {
            ((activeSellQty * (parsedTp1Pct / 100.0)) * 100_000_000.0).toLong() / 100_000_000.0
        } else 0.0
        val previewQty2 = if (hasTwoTpOrders) activeSellQty - previewQty1 else 0.0

        val confirmTitle = if (hasTwoTpOrders) "Konfirmasi 2 Order TP" else "Konfirmasi Jual Order"
        val confirmMsg = when {
            hasTwoTpOrders -> "Anda akan memasang 2 order jual Take Profit di Indodax:\n\n" +
                    "• TP 1: ${PriceFormatter.formatCryptoExact(previewQty1, 8)} $baseAsset (${String.format(Locale.US, "%.0f", parsedTp1Pct)}%) @ Rp ${PriceFormatter.formatIdrNumber(parsedTp1Price)}\n" +
                    "• TP 2: ${PriceFormatter.formatCryptoExact(previewQty2, 8)} $baseAsset (${String.format(Locale.US, "%.0f", parsedTp2Pct)}%) @ Rp ${PriceFormatter.formatIdrNumber(parsedTp2Price)}\n\n" +
                    "Total: ${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset (100% koin dialokasikan tanpa sisa/anti-dust).\n\nLanjutkan?"
            isAutoSellActive && parsedTp1Price > 0.0 -> "Anda akan memasang 1 order jual Limit TP1 di Indodax:\n\n" +
                    "• TP 1: ${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset @ Rp ${PriceFormatter.formatIdrNumber(parsedTp1Price)}\n\nLanjutkan?"
            isAutoSellActive && parsedTp2Price > 0.0 -> "Anda akan memasang 1 order jual Limit TP2 di Indodax:\n\n" +
                    "• TP 2: ${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset @ Rp ${PriceFormatter.formatIdrNumber(parsedTp2Price)}\n\nLanjutkan?"
            else -> "Anda akan memasang 1 order jual Limit di Indodax:\n\n" +
                    "• ${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset di harga pasar saat ini (Rp ${PriceFormatter.formatIdrNumber(validPrice)}).\n\nLanjutkan?"
        }

        AlertDialog(
            onDismissRequest = { showSellConfirmDialog = false },
            containerColor = TvSurface,
            titleContentColor = TvRed,
            title = { Text(confirmTitle, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    confirmMsg,
                    color = TvTextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onExecuteSell?.invoke(activeSellQty, isAutoSellActive, parsedTp1Price, parsedTp1Pct, parsedTp2Price, parsedTp2Pct)
                        showSellConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvRed)
                ) {
                    Text("IYA, PASANG ORDER", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSellConfirmDialog = false }) {
                    Text("BATAL", color = TvTextSecondary)
                }
            }
        )
    }
}
