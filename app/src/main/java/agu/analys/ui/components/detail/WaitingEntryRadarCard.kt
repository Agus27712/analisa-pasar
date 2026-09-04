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
import agu.analys.ui.theme.*
import androidx.compose.ui.graphics.Color

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
                quoteAsset = quoteAsset
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
            onCancelTrailingOrder = onCancelTrailingOrder
        )

        Spacer(Modifier.height(10.dp))
    }
}
