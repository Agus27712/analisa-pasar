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
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextSecondary

/**
 * Komponen Modular: Estimasi & Simulasi Biaya Transaksi pada Card Radar Status Live.
 * Mendukung kalkulasi BUY (Nominal Rupiah & Estimasi Koin) serta SELL (Koin Dimiliki, Shortcut %,
 * Rincian Harga Beli vs Jual - Fee, dan Hasil Keuntungan Bersih).
 */
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
    onExecuteBuy: ((Double) -> Unit)? = null,
    onExecuteSell: ((Double) -> Unit)? = null,
    onSetManualBuyPrice: ((Double, Double) -> Unit)? = null,
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
            .background(Color(0xFF0C1622), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF1B2F44), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        // 1. Header Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ReceiptLong,
                    contentDescription = null,
                    tint = Color(0xFF4FC3F7),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isBuyMode) "Transaski & Biaya (buy)" else "Penjualan & Profit (sell)",
                    color = Color(0xFF4FC3F7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.4.sp
                )
            }

            // Info popup trigger (Rincian Fee)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showFeeDetailModal = true }
                    .background(Color(0xFF142436))
                    .border(0.5.dp, Color(0xFF26527C), RoundedCornerShape(12.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Rincian Biaya",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Rincian Fee",
                    color = Color(0xFF00E5FF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 2. Activity Toggle: BELI (BUY) vs JUAL (SELL)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF14202E), RoundedCornerShape(8.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isBuyMode) TvGreen.copy(alpha = 0.2f) else Color.Transparent)
                    .border(if (isBuyMode) 1.dp else 0.dp, if (isBuyMode) TvGreen else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable { onBuyModeChanged(true) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = if (isBuyMode) TvGreen else TvTextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Aktivitas: BELI (BUY)",
                        color = if (isBuyMode) TvGreen else TvTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!isBuyMode) TvRed.copy(alpha = 0.2f) else Color.Transparent)
                    .border(if (!isBuyMode) 1.dp else 0.dp, if (!isBuyMode) TvRed else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable {
                        onBuyModeChanged(false)
                        if (availableCoin > 0.0) {
                            onSellQuantityChanged(availableCoin)
                        }
                    }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = if (!isBuyMode) TvRed else TvTextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Aktivitas: JUAL (SELL)",
                        color = if (!isBuyMode) TvRed else TvTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 3. Order Type Selector: Limit Order (Maker) vs Instant Order (Taker)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF14202E), RoundedCornerShape(8.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val makerPct = if (isBuyMode) fees.buyMakerPct else fees.sellMakerPct
            val takerPct = if (isBuyMode) fees.buyTakerPct else fees.sellTakerPct
            OrderTypeTab(
                title = "Limit Order (Maker: $makerPct%)",
                isSelected = isMakerOrder,
                onClick = { onOrderTypeChanged(true) },
                modifier = Modifier.weight(1f)
            )
            OrderTypeTab(
                title = "Instant (Taker: $takerPct%)",
                isSelected = !isMakerOrder,
                onClick = { onOrderTypeChanged(false) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Subsections: Buy vs Sell
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
                onSetManualBuyPrice = onSetManualBuyPrice
            )
        }
    }

    // Modal popup rincian biaya transaksi
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
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Color(0xFF1E3A5A) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) Color(0xFF00E5FF) else TvTextSecondary,
            fontSize = 9.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
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
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF173650) else Color(0xFF101A24))
            .border(1.dp, if (selected) Color(0xFF00E5FF) else Color(0xFF1E2E3E), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF00E5FF) else TvTextSecondary,
            fontSize = 9.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun TransactionDetailRow(
    label: String,
    value: String,
    subValue: String? = null,
    valueColor: Color = Color.White
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF90A4AE),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
            if (subValue != null) {
                Text(
                    text = subValue,
                    color = TvTextSecondary,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
            }
        }
    }
}
