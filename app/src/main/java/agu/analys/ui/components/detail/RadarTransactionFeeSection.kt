package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.TradingFeeConfig
import agu.analys.ui.theme.*

@Composable
fun RadarTransactionFeeSection(
    fees: TradingFeeConfig,
    currentPrice: Double,
    baseAsset: String,
    quoteAsset: String,
    availableIdr: Double = 0.0,
    availableCoin: Double = 0.0,
    avgBuyPrice: Double = 0.0,
    selectedNominalIdr: Double = 50000.0,
    onNominalIdrChanged: (Double) -> Unit = {},
    selectedSellQuantity: Double = 0.0,
    onSellQuantityChanged: (Double) -> Unit = {},
    isMakerOrder: Boolean = true,
    onOrderTypeChanged: (Boolean) -> Unit = {},
    isBuyMode: Boolean = true,
    onBuyModeChanged: (Boolean) -> Unit = {},
    isRealMode: Boolean = false,
    onExecuteBuy: ((Double, Double, Double) -> Unit)? = null,
    onExecuteSell: ((Double) -> Unit)? = null,
    onSetManualBuyPrice: ((Double, Double) -> Unit)? = null,
    spotPosition: agu.analys.trading.SpotPosition? = null,
    onSetTrailingStop: ((Boolean, Double) -> Unit)? = null,
    onResetTrailingTrigger: (() -> Unit)? = null,
    signal: agu.analys.model.AISignalState? = null,
    onSetAutoSellParams: ((Boolean, Double, Double, Double, Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showFeeDetailModal by remember { mutableStateOf(false) }

    val validPrice = if (currentPrice > 0.0) currentPrice else 1.0
    val activeFeePct = if (isBuyMode) {
        if (isMakerOrder) fees.buyMakerPct else fees.buyTakerPct
    } else {
        if (isMakerOrder) fees.sellMakerPct else fees.sellTakerPct
    }

    val activeSellQty = if (selectedSellQuantity > 0.0) selectedSellQuantity else availableCoin
    val grossSellValueIdr = activeSellQty * validPrice

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(12.dp))
            .border(1.dp, TvBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ReceiptLong,
                    contentDescription = null,
                    tint = TvBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isBuyMode) "Transaksi & Biaya (Buy)" else "Penjualan & Profit (Sell)",
                    color = TvBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showFeeDetailModal = true }
                    .background(TvSurface)
                    .border(1.dp, TvBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Rincian Biaya",
                    tint = TvBlue,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier.width(4.dp))
                Text(
                    text = "Rincian Fee",
                    color = TvBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TvSurface, RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isBuyMode) TvGreen.copy(alpha = 0.18f) else Color.Transparent)
                    .border(if (isBuyMode) 1.dp else 0.dp, if (isBuyMode) TvGreen else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onBuyModeChanged(true) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = if (isBuyMode) TvGreen else TvTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier.width(4.dp))
                    Text(
                        text = "Beli",
                        color = if (isBuyMode) TvGreen else TvTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (!isBuyMode) TvRed.copy(alpha = 0.18f) else Color.Transparent)
                    .border(if (!isBuyMode) 1.dp else 0.dp, if (!isBuyMode) TvRed else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable {
                        onBuyModeChanged(false)
                        if (availableCoin > 0.0) onSellQuantityChanged(availableCoin)
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = if (!isBuyMode) TvRed else TvTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier.width(4.dp))
                    Text(
                        text = "Jual",
                        color = if (!isBuyMode) TvRed else TvTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TvSurface, RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val makerPct = if (isBuyMode) fees.buyMakerPct else fees.sellMakerPct
            val takerPct = if (isBuyMode) fees.buyTakerPct else fees.sellTakerPct
            OrderTypeTab(
                title = "Limit · Maker $makerPct%",
                isSelected = isMakerOrder,
                onClick = { onOrderTypeChanged(true) },
                modifier = Modifier.weight(1f)
            )
            OrderTypeTab(
                title = "Instant · Taker $takerPct%",
                isSelected = !isMakerOrder,
                onClick = { onOrderTypeChanged(false) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier.height(10.dp))

        if (isBuyMode) {
            RadarBuySection(
                validPrice = validPrice,
                baseAsset = baseAsset,
                quoteAsset = quoteAsset,
                availableIdr = availableIdr,
                selectedNominalIdr = selectedNominalIdr,
                onNominalIdrChanged = onNominalIdrChanged,
                activeFeePct = activeFeePct,
                isRealMode = isRealMode,
                signal = signal,
                onExecuteBuy = onExecuteBuy
            )
        } else {
            RadarSellSection(
                validPrice = validPrice,
                baseAsset = baseAsset,
                quoteAsset = quoteAsset,
                availableCoin = availableCoin,
                avgBuyPrice = avgBuyPrice,
                selectedSellQuantity = selectedSellQuantity,
                onSellQuantityChanged = onSellQuantityChanged,
                activeFeePct = activeFeePct,
                isRealMode = isRealMode,
                onExecuteSell = onExecuteSell,
                onSetManualBuyPrice = onSetManualBuyPrice,
                spotPosition = spotPosition,
                onSetTrailingStop = onSetTrailingStop,
                onResetTrailingTrigger = onResetTrailingTrigger,
                signal = signal,
                onSetAutoSellParams = onSetAutoSellParams
            )
        }
    }

    RadarFeeDetailDialog(
        isOpen = showFeeDetailModal,
        onDismiss = { showFeeDetailModal = false },
        fees = fees,
        orderAmountIdr = if (isBuyMode) selectedNominalIdr.coerceAtLeast(10000.0) else grossSellValueIdr,
        isMakerOrder = isMakerOrder,
        coinSymbol = baseAsset,
        isBuyMode = isBuyMode
    )
}

@Composable
fun OrderTypeTab(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) TvBlue.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) TvBlue else TvTextSecondary,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun QuickNominalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) TvBlue.copy(alpha = 0.15f) else TvSurface)
            .border(1.dp, if (selected) TvBlue else TvBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) TvBlue else TvTextSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun TransactionDetailRow(
    label: String,
    value: String,
    subValue: String? = null,
    valueColor: Color = Color.Unspecified
) {
    val resolvedValueColor = if (valueColor == Color.Unspecified) TvTextPrimary else valueColor
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TvTextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                color = resolvedValueColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
            if (subValue != null) {
                Text(
                    text = subValue,
                    color = TvTextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
            }
        }
    }
}
