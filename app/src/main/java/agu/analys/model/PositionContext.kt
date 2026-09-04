package agu.analys.model

import agu.analys.config.TradingFeeConfig
import agu.analys.trading.SpotPosition
import agu.analys.util.PriceFormatter
import java.util.Locale

/**
 * Workflow trading eksplisit berdasarkan konteks kepemilikan aset (Position Awareness).
 * NO POSITION -> BUY WORKFLOW
 * HAS POSITION -> HOLD / SELL WORKFLOW
 */
enum class TradingWorkflow {
    BUY,
    HOLD_SELL
}

/**
 * Status semantik untuk setiap checkpoint trading (baik pada BUY maupun SELL).
 */
enum class CheckpointStatus {
    LOCKED,
    MONITORING,
    ACTIVE,
    COMPLETED,
    WARNING,
    READY
}

/**
 * Single canonical source of truth untuk konteks posisi koin aktif.
 * Berlaku identik baik untuk transaksi real maupun simulasi tanpa data dummy.
 */
data class PositionContext(
    val hasPosition: Boolean = false,
    val symbol: String = "",
    val entryPrice: Double? = null,
    val quantity: Double? = null,
    val costBasis: Double? = null,
    val currentPrice: Double? = null,
    val grossValue: Double? = null,
    val netValue: Double? = null,
    val floatingProfitPct: Double? = null,
    val floatingProfitNet: Double? = null,
    val stopLoss: Double? = null,
    val tp1: Double? = null,
    val tp2: Double? = null,
    val trailingActive: Boolean = false,
    val isTrailingTriggered: Boolean = false,
    val isReal: Boolean = false
) {
    companion object {
        fun create(
            symbol: String,
            spotPosition: SpotPosition?,
            holdingStatus: CoinHoldingStatus?,
            currentPrice: Double,
            fees: TradingFeeConfig
        ): PositionContext {
            val isHolding = (spotPosition != null && spotPosition.isHolding && spotPosition.quantity > 0.00000001) ||
                    (holdingStatus != null && holdingStatus.isHolding && holdingStatus.quantity > 0.00000001)

            if (!isHolding) {
                return PositionContext(
                    hasPosition = false,
                    symbol = symbol,
                    currentPrice = if (currentPrice > 0.0) currentPrice else null
                )
            }

            val qty = when {
                spotPosition != null && spotPosition.isHolding && spotPosition.quantity > 0.00000001 -> spotPosition.quantity
                holdingStatus != null && holdingStatus.quantity > 0.00000001 -> holdingStatus.quantity
                else -> 0.0
            }

            val entry = when {
                spotPosition != null && spotPosition.isHolding && spotPosition.entryPrice > 0.0 -> spotPosition.entryPrice
                holdingStatus != null && holdingStatus.entryPrice > 0.0 -> holdingStatus.entryPrice
                else -> null
            }

            val tp1 = when {
                spotPosition != null && spotPosition.tp1Price > 0.0 -> spotPosition.tp1Price
                holdingStatus != null && holdingStatus.tp1Price > 0.0 -> holdingStatus.tp1Price
                else -> null
            }

            val tp2 = when {
                spotPosition != null && spotPosition.tp2Price > 0.0 -> spotPosition.tp2Price
                holdingStatus != null && holdingStatus.tp2Price > 0.0 -> holdingStatus.tp2Price
                else -> null
            }

            val sl = when {
                spotPosition != null && spotPosition.stopLossPrice > 0.0 -> spotPosition.stopLossPrice
                holdingStatus != null && holdingStatus.stopLossPrice > 0.0 -> holdingStatus.stopLossPrice
                else -> null
            }

            val trailingActive = spotPosition?.isTrailingEnabled == true || holdingStatus?.isTrailingEnabled == true
            val isTrailingTrig = spotPosition?.isTrailingTriggered == true || holdingStatus?.isTrailingTriggered == true
            val isReal = spotPosition?.isReal == true || holdingStatus?.isReal == true

            val validPrice = if (currentPrice > 0.0) currentPrice else entry
            val cost = if (entry != null && entry > 0.0) qty * entry else null
            val sellFeeRate = (fees.sellMakerPct / 100.0).coerceAtLeast(0.0)
            val gross = if (validPrice != null) qty * validPrice else null
            val netVal = if (gross != null) gross * (1.0 - sellFeeRate) else null
            val netPnl = if (cost != null && netVal != null) netVal - cost else null
            val pnlPct = if (cost != null && cost > 0.0 && netPnl != null) (netPnl / cost) * 100.0 else null

            return PositionContext(
                hasPosition = true,
                symbol = symbol,
                entryPrice = entry,
                quantity = qty,
                costBasis = cost,
                currentPrice = validPrice,
                grossValue = gross,
                netValue = netVal,
                floatingProfitPct = pnlPct,
                floatingProfitNet = netPnl,
                stopLoss = sl,
                tp1 = tp1,
                tp2 = tp2,
                trailingActive = trailingActive,
                isTrailingTriggered = isTrailingTrig,
                isReal = isReal
            )
        }
    }
}

/**
 * Resolver workflow trading:
 * NO POSITION -> BUY
 * HAS POSITION -> HOLD / SELL
 */
fun resolveWorkflow(positionContext: PositionContext): TradingWorkflow {
    return if (positionContext.hasPosition) {
        TradingWorkflow.HOLD_SELL
    } else {
        TradingWorkflow.BUY
    }
}

/**
 * Item checkpoint terstruktur untuk visualisasi workflow.
 */
data class TradingCheckpointItem(
    val number: Int,
    val tabLabel: String,
    val title: String,
    val status: CheckpointStatus,
    val isOk: Boolean,
    val detail: String
)

/**
 * Evaluator deterministik untuk 4 Checkpoint SELL terpisah:
 * 1. Position Health
 * 2. Risk Protection
 * 3. Profit Target
 * 4. Sell Decision
 */
object SellCheckpointEvaluator {
    fun evaluate(
        context: PositionContext,
        sellSignal: SellSignalState,
        quoteAsset: String = "IDR"
    ): List<TradingCheckpointItem> {
        if (!context.hasPosition) return emptyList()

        val entry = context.entryPrice
        val current = context.currentPrice ?: (entry ?: 0.0)
        val pnlPct = context.floatingProfitPct
        val cost = context.costBasis
        val sl = context.stopLoss
        val tp1 = context.tp1

        // 1. Position Health
        val healthStatus = if (entry != null && entry > 0.0 && context.quantity != null && context.quantity > 0.0) {
            CheckpointStatus.COMPLETED
        } else {
            CheckpointStatus.ACTIVE
        }
        val pnlFormatted = if (pnlPct != null) PriceFormatter.formatPercentage(pnlPct, includePlusSign = true) else "N/A"
        val healthDetail = if (entry != null && entry > 0.0 && cost != null) {
            "Entri: ${PriceFormatter.formatPrice(entry, quoteAsset = quoteAsset)} · Modal: ${PriceFormatter.formatPrice(cost, quoteAsset = quoteAsset)} (PnL $pnlFormatted)"
        } else {
            "Kuantitas: ${context.quantity ?: 0.0} · Entri riil belum tercatat"
        }
        val item1 = TradingCheckpointItem(
            number = 1,
            tabLabel = "1. Posisi",
            title = "1. Position Health · Posisi Aktif",
            status = healthStatus,
            isOk = healthStatus == CheckpointStatus.COMPLETED,
            detail = healthDetail
        )

        // 2. Risk Protection (Stop Loss & Trailing Stop)
        val isSlHit = (sl != null && sl > 0.0 && current <= sl) || sellSignal.state == SellLifecycleState.STOP_LOSS_HIT
        val isTrailingHit = context.isTrailingTriggered || sellSignal.state == SellLifecycleState.TRAILING_TRIGGERED
        val riskStatus = when {
            isSlHit || isTrailingHit -> CheckpointStatus.WARNING
            context.trailingActive -> CheckpointStatus.COMPLETED
            sl != null && sl > 0.0 -> CheckpointStatus.ACTIVE
            else -> CheckpointStatus.MONITORING
        }
        val riskDetail = when {
            isSlHit -> "Stop Loss Tersentuh di ${PriceFormatter.formatPrice(sl ?: 0.0, quoteAsset = quoteAsset)}! Risiko terpicu."
            isTrailingHit -> "Trailing Stop Terpicu! Amankan posisi Anda."
            context.trailingActive -> "Trailing Stop Aktif melindungi keuntungan posisi."
            sl != null && sl > 0.0 -> "Batas Stop Loss terjaga di ${PriceFormatter.formatPrice(sl, quoteAsset = quoteAsset)}."
            else -> "Risiko terpantau normal. Siap cut loss bila perlu."
        }
        val item2 = TradingCheckpointItem(
            number = 2,
            tabLabel = "2. Proteksi",
            title = "2. Risk Protection · Batas Risiko & SL",
            status = riskStatus,
            isOk = riskStatus == CheckpointStatus.COMPLETED || riskStatus == CheckpointStatus.ACTIVE,
            detail = riskDetail
        )

        // 3. Profit Target
        val isTpReached = (tp1 != null && tp1 > 0.0 && current >= tp1) ||
                (sellSignal.state == SellLifecycleState.READY_TO_SELL && (pnlPct ?: 0.0) > 0.0)
        val isApproaching = (tp1 != null && tp1 > 0.0 && current >= tp1 * 0.98 && current < tp1) ||
                sellSignal.state == SellLifecycleState.APPROACHING_TARGET
        val targetStatus = when {
            isTpReached -> CheckpointStatus.COMPLETED
            isApproaching -> CheckpointStatus.READY
            (pnlPct ?: 0.0) > 0.0 -> CheckpointStatus.ACTIVE
            else -> CheckpointStatus.MONITORING
        }
        val targetDetail = when {
            isTpReached -> "Target TP1 ${PriceFormatter.formatPrice(tp1 ?: 0.0, quoteAsset = quoteAsset)} Tercapai ($pnlFormatted)!"
            isApproaching -> "Mendekati target TP1 di ${PriceFormatter.formatPrice(tp1 ?: 0.0, quoteAsset = quoteAsset)}."
            (pnlPct ?: 0.0) > 0.0 -> "Floating Profit $pnlFormatted. Bergerak menuju target."
            tp1 != null && tp1 > 0.0 -> "Target TP1 di ${PriceFormatter.formatPrice(tp1, quoteAsset = quoteAsset)}. Memantau pergerakan."
            else -> "Memantau momentum target profit..."
        }
        val item3 = TradingCheckpointItem(
            number = 3,
            tabLabel = "3. Target",
            title = "3. Profit Target · Evaluasi TP",
            status = targetStatus,
            isOk = targetStatus == CheckpointStatus.COMPLETED || targetStatus == CheckpointStatus.READY,
            detail = targetDetail
        )

        // 4. Sell Decision
        val decisionStatus = when (sellSignal.state) {
            SellLifecycleState.READY_TO_SELL -> CheckpointStatus.READY
            SellLifecycleState.STOP_LOSS_HIT -> CheckpointStatus.WARNING
            SellLifecycleState.TRAILING_TRIGGERED -> CheckpointStatus.READY
            SellLifecycleState.APPROACHING_TARGET -> CheckpointStatus.ACTIVE
            SellLifecycleState.MONITORING -> CheckpointStatus.MONITORING
            SellLifecycleState.NOT_HOLDING -> CheckpointStatus.LOCKED
        }
        val decisionDetail = when (sellSignal.state) {
            SellLifecycleState.READY_TO_SELL -> "SIAP JUAL: ${sellSignal.reason} ($pnlFormatted)"
            SellLifecycleState.STOP_LOSS_HIT -> "CUT LOSS: ${sellSignal.reason}"
            SellLifecycleState.TRAILING_TRIGGERED -> "TRAILING EXIT: ${sellSignal.reason}"
            SellLifecycleState.APPROACHING_TARGET -> "MENDEKATI EXIT: Siapkan rencana jual."
            SellLifecycleState.MONITORING -> "MEMANTAU: Pertahankan posisi sesuai rencana trading."
            SellLifecycleState.NOT_HOLDING -> "Tidak ada posisi aktif."
        }
        val item4 = TradingCheckpointItem(
            number = 4,
            tabLabel = "4. Keputusan",
            title = "4. Sell Decision · Rekomendasi Eksekusi",
            status = decisionStatus,
            isOk = decisionStatus == CheckpointStatus.READY || decisionStatus == CheckpointStatus.WARNING,
            detail = decisionDetail
        )

        return listOf(item1, item2, item3, item4)
    }
}
