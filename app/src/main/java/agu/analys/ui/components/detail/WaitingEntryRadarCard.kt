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
import agu.analys.trading.SpotPosition
import agu.analys.ui.components.detail.radar.RadarConfirmationChecklist
import agu.analys.ui.components.detail.radar.RadarHeaderSection
import agu.analys.ui.components.detail.radar.RadarTargetLevelsSection

/**
 * Radar & Progres Entry Interaktif dengan 4 Konfirmasi Bertahap (Progress Bar 1/4 -> 4/4)
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
    onExecuteSell: ((Double) -> Unit)? = null,
    onSetManualBuyPrice: ((Double, Double) -> Unit)? = null,
    spotPosition: SpotPosition? = null,
    sellSignalState: agu.analys.model.SellSignalState = agu.analys.model.SellSignalState(),
    onSetTrailingStop: ((Boolean, Double) -> Unit)? = null,
    onResetTrailingTrigger: (() -> Unit)? = null,
    onSetAutoSellParams: ((Boolean, Double, Double, Double, Double) -> Unit)? = null,
    onDeployTrailingOrder: (() -> Unit)? = null,
    onCancelTrailingOrder: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val mtf = signal.mtf
    val completed = remember(mtf) {
        listOf(mtf.biasStatus, mtf.setupStatus, mtf.triggerStatus, mtf.entryPriceStatus)
            .count { it.name == "OK" || it.name == "CONFIRMED" }
    }

    val effectivePrice = if (currentPrice > 0.0) currentPrice else if (signal.entryPrice > 0.0) signal.entryPrice else 0.0

    // Hoisted states for live transaction details
    var selectedNominal by remember { mutableDoubleStateOf(50000.0) }
    var selectedSellQty by remember { mutableDoubleStateOf(availableCoin) }
    var isMakerOrder by remember { mutableStateOf(true) }
    var isBuyMode by remember { mutableStateOf(true) }

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

    val titleHeader = when (strategyMode) {
        StrategyMode.SCALPING -> "⚡ SCALPING (${signal.confidence}%)"
        StrategyMode.SECOND_WAVE -> "🌊 SECOND-WAVE (${signal.confidence}%)"
        StrategyMode.SWING -> "🎯 SWING (${signal.confidence}%)"
        StrategyMode.OFFICE_DAILY -> "🏢 OFFICE-DAILY (${signal.confidence}%)"
    }

    var isChecklistVisible by remember { mutableStateOf(false) }
    var isLevelPlanVisible by remember { mutableStateOf(false) }

    AnalysisCard(modifier = modifier) {
        // Header dengan LED Indicator status
        RadarHeaderSection(
            titleHeader = titleHeader,
            completed = completed,
            onToggleChecklist = { isChecklistVisible = !isChecklistVisible }
        )

        Spacer(Modifier.height(10.dp))

        // 4 Checklist Konfirmasi (Hideable via badge)
        AnimatedVisibility(visible = isChecklistVisible) {
            RadarConfirmationChecklist(
                mtf = mtf,
                strategyMode = strategyMode
            )
        }

        if (isChecklistVisible) {
            Spacer(Modifier.height(10.dp))
        }

        // Global Progress Bar
        RadarLinearCheckpointStepper(
            mtf = mtf,
            completed = completed,
            pulseScale = pulseScale,
            strategyMode = strategyMode,
            confidence = signal.confidence
        )

        Spacer(Modifier.height(10.dp))

        // Target Levels Box (Hideable)
        RadarTargetLevelsSection(
            signal = signal,
            effectivePrice = effectivePrice,
            quoteAsset = quoteAsset,
            completed = completed,
            isLevelPlanVisible = isLevelPlanVisible,
            onToggleLevelPlan = { isLevelPlanVisible = !isLevelPlanVisible }
        )

        Spacer(Modifier.height(10.dp))

        // Estimasi Biaya Transaksi & Kalkulasi Net Profit / Loss Jual
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
