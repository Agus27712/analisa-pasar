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
    onExecuteSell: ((Double) -> Unit)?,
    onSetManualBuyPrice: ((Double, Double) -> Unit)? = null,
    spotPosition: agu.analys.trading.SpotPosition? = null,
    onSetTrailingStop: ((Boolean, Double) -> Unit)? = null,
    onResetTrailingTrigger: (() -> Unit)? = null,
    signal: agu.analys.model.AISignalState? = null,
    onSetAutoSellParams: ((Boolean, Double, Double, Double, Double) -> Unit)? = null,
    onDeployTrailingOrder: (() -> Unit)? = null,
    onCancelTrailingOrder: (() -> Unit)? = null,
    sellSignalState: agu.analys.model.SellSignalState = agu.analys.model.SellSignalState()
) {
    var customSellQtyInput by remember { mutableStateOf("") }
    var isCustomSellQtyOpen by remember { mutableStateOf(false) }
    var selectedSellPercent by remember { mutableIntStateOf(100) }

    var isAutoSellActive by remember { mutableStateOf(false) }
    var tp1PriceInput by remember { mutableStateOf("") }
    var tp1PercentInput by remember { mutableStateOf("50") }
    var tp2PriceInput by remember { mutableStateOf("") }
    var tp2PercentInput by remember { mutableStateOf("50") }

    // Flag to prevent LaunchedEffect from overwriting user typing
    var hasInitialized by remember { mutableStateOf(false) }
    val currentPositionId = remember(baseAsset, quoteAsset) { "$baseAsset-$quoteAsset" }

    LaunchedEffect(spotPosition, signal, currentPositionId) {
        if (spotPosition != null && !hasInitialized) {
            isAutoSellActive = spotPosition.isAutoSellEnabled
            tp1PriceInput = if (spotPosition.tp1Price > 0.0) String.format(Locale.US, "%.0f", spotPosition.tp1Price) 
                            else if (signal != null && signal.targetPrice1 > 0.0) String.format(Locale.US, "%.0f", signal.targetPrice1) 
                            else ""
            
            tp2PriceInput = if (spotPosition.tp2Price > 0.0) String.format(Locale.US, "%.0f", spotPosition.tp2Price)
                            else if (signal != null && signal.targetPrice2 > 0.0) String.format(Locale.US, "%.0f", signal.targetPrice2)
                            else ""

            tp1PercentInput = if (spotPosition.tp1Percent > 0) String.format(Locale.US, "%.0f", spotPosition.tp1Percent) else "50"
            tp2PercentInput = if (spotPosition.tp2Percent > 0) String.format(Locale.US, "%.0f", spotPosition.tp2Percent) else "50"
            
            hasInitialized = true
        }
    }

    // Reset initialization when changing pairs
    LaunchedEffect(currentPositionId) {
        hasInitialized = false
    }

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
            SellTpSlSection(
                isRealMode = isRealMode,
                isAutoSellActive = isAutoSellActive,
                onAutoSellActiveChanged = { enabled ->
                    isAutoSellActive = enabled
                    if (!enabled) {
                        onSetAutoSellParams?.invoke(
                            false,
                            0.0,
                            50.0,
                            0.0,
                            50.0
                        )
                    }
                },
                tp1Price = tp1PriceInput,
                onTp1PriceChanged = { tp1PriceInput = it },
                tp1Percent = tp1PercentInput,
                onTp1PercentChanged = { tp1PercentInput = it },
                tp2Price = tp2PriceInput,
                onTp2PriceChanged = { tp2PriceInput = it },
                tp2Percent = tp2PercentInput,
                onTp2PercentChanged = { tp2PercentInput = it },
                quoteAsset = quoteAsset,
                onSaveParams = {
                    onSetAutoSellParams?.invoke(
                        true,
                        PriceFormatter.parseCleanIdrDouble(tp1PriceInput),
                        PriceFormatter.parseCleanIdrDouble(tp1PercentInput).coerceIn(1.0, 100.0),
                        PriceFormatter.parseCleanIdrDouble(tp2PriceInput),
                        PriceFormatter.parseCleanIdrDouble(tp2PercentInput).coerceIn(1.0, 100.0)
                    )
                }
            )

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

        Spacer(Modifier.height(12.dp))

        // TOMBOL EKSEKUSI JUAL MARKET (INSTAN)
        Button(
            onClick = {
                if (isRealMode) showSellConfirmDialog = true
                else onExecuteSell?.invoke(activeSellQty)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TvRed, contentColor = Color.White),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = "${if (isRealMode) "[REAL] " else "[SIMULASI] "}JUAL ${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset",
                fontWeight = FontWeight.Black,
                fontSize = 13.5.sp
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
        AlertDialog(
            onDismissRequest = { showSellConfirmDialog = false },
            containerColor = TvSurface,
            titleContentColor = TvRed,
            title = { Text("Konfirmasi Jual Market", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Anda akan menjual ${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset secara INSTAN di harga pasar Indodax (sekitar ${PriceFormatter.formatIdrNumber(validPrice)}).\n\nLanjutkan?",
                    color = TvTextPrimary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onExecuteSell?.invoke(activeSellQty)
                        showSellConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvRed)
                ) {
                    Text("IYA, JUAL SEKARANG", fontWeight = FontWeight.Bold)
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
