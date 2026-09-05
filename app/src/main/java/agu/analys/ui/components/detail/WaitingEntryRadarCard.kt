package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.model.AISignalState
import agu.analys.model.PositionContext
import agu.analys.model.SellCheckpointEvaluator
import agu.analys.model.SellLifecycleState
import agu.analys.model.SellSignalState
import agu.analys.model.TradingWorkflow
import agu.analys.model.resolveWorkflow
import agu.analys.trading.SpotPosition
import agu.analys.ui.components.detail.radar.RadarConfirmationChecklist
import agu.analys.ui.components.detail.radar.RadarHeaderSection
import agu.analys.ui.components.detail.radar.RadarTargetLevelsSection
import agu.analys.ui.components.detail.sell.SellCheckpointStepper
import agu.analys.ui.components.detail.sell.SellConfirmationChecklist
import agu.analys.ui.components.detail.sell.SellPositionOverviewCard
import agu.analys.ui.components.detail.sell.SellTargetLevelsSection
import agu.analys.ui.components.detail.sell.SellTpSlSection
import agu.analys.ui.theme.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import agu.analys.util.PriceFormatter
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close

/**
 * Radar & Progres Entry/Exit Interaktif dengan Single Source of Truth Position Context:
 * - NO POSITION: BUY Workflow (1. Bias, 2. Setup, 3. Trigger, 4. Entry)
 * - HAS POSITION: HOLD/SELL Workflow (1. Position Health, 2. Risk Protection, 3. Profit Target, 4. Sell Decision)
 */
@Composable
fun WaitingEntryRadarCard(
    signal: AISignalState,
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    scalping: Boolean = strategyMode == StrategyMode.SCALPING,
    fees: TradingFeeConfig = TradingFeeConfig(),
    currentPrice: Double = 0.0,
    baseAsset: String = "BTC",
    quoteAsset: String = "IDR",
    availableIdr: Double = 0.0,
    availableCoin: Double = 0.0,
    avgBuyPrice: Double = 0.0,
    isRealBuyMode: Boolean = false,
    onExecuteBuy: ((Double, Double, Double) -> Unit)? = null,
    onExecuteSell: ((Double, Boolean, Double, Double, Double, Double) -> Unit)? = null,
    onSetManualBuyPrice: ((Double, Double) -> Unit)? = null,
    spotPosition: SpotPosition? = null,
    sellSignalState: SellSignalState = SellSignalState(),
    positionContext: PositionContext = PositionContext(),
    workflow: TradingWorkflow = resolveWorkflow(positionContext),
    onSetTrailingStop: ((Boolean, Double) -> Unit)? = null,
    onResetTrailingTrigger: (() -> Unit)? = null,
    onSetAutoSellParams: ((Boolean, Double, Double, Double, Double) -> Unit)? = null,
    onDeployTrailingOrder: (() -> Unit)? = null,
    onCancelTrailingOrder: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val effectivePrice = if (currentPrice > 0.0) currentPrice else if (signal.entryPrice > 0.0) signal.entryPrice else 0.0

    val isHolding = workflow == TradingWorkflow.HOLD_SELL || positionContext.hasPosition

    val mtf = signal.mtf
    val completedBuySteps = remember(mtf) {
        listOf(mtf.biasStatus, mtf.setupStatus, mtf.triggerStatus, mtf.entryPriceStatus)
            .count { it.name == "OK" || it.name == "CONFIRMED" }
    }

    val completedSellSteps = remember(positionContext, sellSignalState) {
        if (!isHolding) 0 else {
            SellCheckpointEvaluator.evaluate(positionContext, sellSignalState, quoteAsset)
                .count { it.isOk }
        }
    }

    // Hoisted states for live transaction details
    var selectedNominal by remember { mutableDoubleStateOf(50000.0) }
    var selectedSellQty by remember { mutableDoubleStateOf(availableCoin) }
    var isMakerOrder by remember { mutableStateOf(true) }
    var isBuyMode by remember(isHolding) { mutableStateOf(!isHolding) }

    // Hoisted auto sell states for dialog and sell coordination
    var isAutoSellActive by remember { mutableStateOf(false) }
    var tp1PriceInput by remember { mutableStateOf("") }
    var tp1PercentInput by remember { mutableStateOf("50") }
    var tp2PriceInput by remember { mutableStateOf("") }
    var tp2PercentInput by remember { mutableStateOf("50") }

    var hasInitializedAutoSell by remember { mutableStateOf(false) }
    val currentPositionId = remember(baseAsset, quoteAsset) { "$baseAsset-$quoteAsset" }

    LaunchedEffect(spotPosition, signal, currentPositionId) {
        if (spotPosition != null && !hasInitializedAutoSell) {
            isAutoSellActive = spotPosition.isAutoSellEnabled
            tp1PriceInput = if (spotPosition.tp1Price > 0.0) String.format(Locale.US, "%.0f", spotPosition.tp1Price) 
                            else if (signal.targetPrice1 > 0.0) String.format(Locale.US, "%.0f", signal.targetPrice1) 
                            else ""
            
            tp2PriceInput = if (spotPosition.tp2Price > 0.0) String.format(Locale.US, "%.0f", spotPosition.tp2Price)
                            else if (signal.targetPrice2 > 0.0) String.format(Locale.US, "%.0f", signal.targetPrice2)
                            else ""

            tp1PercentInput = if (spotPosition.tp1Percent > 0) String.format(Locale.US, "%.0f", spotPosition.tp1Percent) else "50"
            tp2PercentInput = if (spotPosition.tp2Percent > 0) String.format(Locale.US, "%.0f", spotPosition.tp2Percent) else "50"
            
            hasInitializedAutoSell = true
        }
    }

    LaunchedEffect(currentPositionId) {
        hasInitializedAutoSell = false
    }

    var showTpSlPopup by remember { mutableStateOf(false) }

    // Animasi Pulse Radar
    val transition = rememberInfiniteTransition(label = "waiting_radar_pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radar_scale"
    )

    val buyTitleHeader = when (strategyMode) {
        StrategyMode.SCALPING -> "⚡ SCALPING BUY (${signal.confidence}%)"
        StrategyMode.SECOND_WAVE -> "🌊 SECOND-WAVE BUY (${signal.confidence}%)"
        StrategyMode.SWING -> "🎯 SWING BUY (${signal.confidence}%)"
        StrategyMode.OFFICE_DAILY -> "🏢 OFFICE-DAILY BUY (${signal.confidence}%)"
    }

    var isChecklistVisible by remember { mutableStateOf(false) }
    var isLevelPlanVisible by remember { mutableStateOf(false) }

    AnalysisCard(modifier = modifier) {
        if (workflow == TradingWorkflow.HOLD_SELL) {
            // ═════════════════════════════════════════════════════════════════════════
            // WORKFLOW: HOLD & SELL (Asset Active in Portfolio)
            // ═════════════════════════════════════════════════════════════════════════
            SellPositionOverviewCard(
                context = positionContext,
                quoteAsset = quoteAsset
            )

            Spacer(Modifier.height(10.dp))

            val (sellStatusText, sellStatusColor) = when (sellSignalState.state) {
                SellLifecycleState.READY_TO_SELL -> Pair("🎯 SIAP JUAL", TvGreen)
                SellLifecycleState.APPROACHING_TARGET -> Pair("⏳ DEKAT TP1", TvOrange)
                SellLifecycleState.TRAILING_TRIGGERED -> Pair("🚨 TRAILING STOP", TvOrange)
                SellLifecycleState.STOP_LOSS_HIT -> Pair("⚠️ CUT LOSS", TvRed)
                SellLifecycleState.MONITORING -> Pair("🛡️ POSISI AKTIF", TvBlue)
                SellLifecycleState.NOT_HOLDING -> Pair("WAITING", TvTextSecondary)
            }

            RadarHeaderSection(
                titleHeader = "RADAR POSISI & SINYAL JUAL",
                completed = completedSellSteps,
                onToggleChecklist = { isChecklistVisible = !isChecklistVisible },
                statusText = sellStatusText,
                statusColor = sellStatusColor
            )

            Spacer(Modifier.height(8.dp))

            AnimatedVisibility(visible = isChecklistVisible) {
                SellConfirmationChecklist(
                    context = positionContext,
                    sellSignal = sellSignalState,
                    quoteAsset = quoteAsset
                )
            }

            if (isChecklistVisible) {
                Spacer(Modifier.height(8.dp))
            }

            // Stepper Checkpoint SELL yang jujur & berbasis lifecycle nyata
            SellCheckpointStepper(
                context = positionContext,
                sellSignal = sellSignalState,
                quoteAsset = quoteAsset
            )

            Spacer(Modifier.height(10.dp))

            // Target Levels Posisi Aktif
            SellTargetLevelsSection(
                context = positionContext,
                fees = fees,
                quoteAsset = quoteAsset,
                onClick = { showTpSlPopup = true }
            )
        } else {
            // ═════════════════════════════════════════════════════════════════════════
            // WORKFLOW: BUY (Searching for Market Entry Opportunity)
            // ═════════════════════════════════════════════════════════════════════════
            RadarHeaderSection(
                titleHeader = buyTitleHeader,
                completed = completedBuySteps,
                onToggleChecklist = { isChecklistVisible = !isChecklistVisible }
            )

            Spacer(Modifier.height(10.dp))

            AnimatedVisibility(visible = isChecklistVisible) {
                RadarConfirmationChecklist(
                    mtf = mtf,
                    strategyMode = strategyMode
                )
            }

            if (isChecklistVisible) {
                Spacer(Modifier.height(10.dp))
            }

            // Original BUY Linear Checkpoint Stepper
            RadarLinearCheckpointStepper(
                mtf = mtf,
                completed = completedBuySteps,
                pulseScale = pulseScale,
                strategyMode = strategyMode,
                confidence = signal.confidence
            )

            Spacer(Modifier.height(10.dp))

            // Original BUY Target Levels
            RadarTargetLevelsSection(
                signal = signal,
                effectivePrice = effectivePrice,
                quoteAsset = quoteAsset,
                completed = completedBuySteps,
                isLevelPlanVisible = isLevelPlanVisible,
                onToggleLevelPlan = { isLevelPlanVisible = !isLevelPlanVisible }
            )
        }

        Spacer(Modifier.height(10.dp))

        // Estimasi Biaya Transaksi & Eksekusi Buy / Sell
        RadarTransactionFeeSection(
            fees = fees,
            currentPrice = effectivePrice,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            availableIdr = availableIdr,
            availableCoin = availableCoin,
            avgBuyPrice = avgBuyPrice,
            selectedNominalIdr = selectedNominal,
            onNominalIdrChanged = { selectedNominal = it },
            selectedSellQuantity = selectedSellQty,
            onSellQuantityChanged = { selectedSellQty = it },
            isMakerOrder = isMakerOrder,
            onOrderTypeChanged = { isMakerOrder = it },
            isBuyMode = isBuyMode,
            onBuyModeChanged = { isBuyMode = it },
            isRealMode = isRealBuyMode,
            onExecuteBuy = onExecuteBuy,
            onExecuteSell = onExecuteSell,
            onSetManualBuyPrice = onSetManualBuyPrice,
            spotPosition = spotPosition,
            sellSignalState = sellSignalState,
            onSetTrailingStop = onSetTrailingStop,
            onResetTrailingTrigger = onResetTrailingTrigger,
            signal = signal,
            onSetAutoSellParams = onSetAutoSellParams,
            onDeployTrailingOrder = onDeployTrailingOrder,
            onCancelTrailingOrder = onCancelTrailingOrder,
            isAutoSellActive = isAutoSellActive,
            onAutoSellActiveChanged = { isAutoSellActive = it },
            tp1PriceInput = tp1PriceInput,
            onTp1PriceChanged = { tp1PriceInput = it },
            tp1PercentInput = tp1PercentInput,
            onTp1PercentChanged = { tp1PercentInput = it },
            tp2PriceInput = tp2PriceInput,
            onTp2PriceChanged = { tp2PriceInput = it },
            tp2PercentInput = tp2PercentInput,
            onTp2PercentChanged = { tp2PercentInput = it }
        )

        Spacer(Modifier.height(10.dp))
    }

    if (showTpSlPopup) {
        Dialog(
            onDismissRequest = { showTpSlPopup = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = TvSurface,
                border = BorderStroke(1.dp, TvBorder),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(horizontal = 8.dp, vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ATUR AUTO TAKE PROFIT",
                            color = TvTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showTpSlPopup = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Tutup",
                                tint = TvTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    SellTpSlSection(
                        isRealMode = isRealBuyMode,
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
                            showTpSlPopup = false
                        }
                    )
                }
            }
        }
    }
}
