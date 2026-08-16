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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Tune
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
import agu.analys.config.TradingFeeConfig
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

/**
 * Komponen Modular: Estimasi & Simulasi Biaya Transaksi pada Card Radar Status Live.
 * Dihitung berdasarkan Setting fee pengguna (Maker/Taker) dan harga koin aktif (cth: BTC).
 */
@Composable
fun RadarTransactionFeeSection(
    fees: TradingFeeConfig,
    currentPrice: Double,
    baseAsset: String = "BTC",
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    var isMakerOrder by remember { mutableStateOf(true) } // true = Limit Order (Maker), false = Instant (Taker)
    var selectedNominal by remember { mutableDoubleStateOf(38028.0) } // Default contoh nominal beli user pada BTC
    var customNominalInput by remember { mutableStateOf("") }
    var isCustomInputOpen by remember { mutableStateOf(false) }
    var showFeeDetailModal by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val validPrice = if (currentPrice > 0.0) currentPrice else 1_367_959_000.0
    val activeFeePct = if (isMakerOrder) fees.buyMakerPct else fees.buyTakerPct

    // Perhitungan kalkulasi transaksi
    val grossOrderAmount = selectedNominal.coerceAtLeast(1000.0)
    val totalFeeIdr = grossOrderAmount * (activeFeePct / 100.0)
    val filledOrderAmount = (grossOrderAmount - (totalFeeIdr * 0.5)).coerceAtLeast(0.0)
    val netReceivedAmountIdr = (grossOrderAmount - totalFeeIdr).coerceAtLeast(0.0)

    val grossCryptoQty = grossOrderAmount / validPrice
    val netCryptoQty = netReceivedAmountIdr / validPrice

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0C1622), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF1B2F44), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        // Header Section
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
                    text = "BIAYA TRANSAKSI & ESTIMASI NET",
                    color = Color(0xFF4FC3F7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.4.sp
                )
            }

            // Info popup trigger (i)
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

        // Mode Pemilihan Order: Limit Order (Maker) vs Instant Order (Taker)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF14202E), RoundedCornerShape(8.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OrderTypeTab(
                title = "Limit Order (Maker: ${fees.buyMakerPct}%)",
                isSelected = isMakerOrder,
                onClick = { isMakerOrder = true },
                modifier = Modifier.weight(1f)
            )
            OrderTypeTab(
                title = "Instant (Taker: ${fees.buyTakerPct}%)",
                isSelected = !isMakerOrder,
                onClick = { isMakerOrder = false },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Quick Nominal Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickNominalChip(
                label = "38 Rb (BTC)",
                amount = 38028.0,
                selected = selectedNominal == 38028.0 && !isCustomInputOpen,
                onClick = {
                    selectedNominal = 38028.0
                    isCustomInputOpen = false
                },
                modifier = Modifier.weight(1.1f)
            )
            QuickNominalChip(
                label = "50 Rb",
                amount = 50000.0,
                selected = selectedNominal == 50000.0 && !isCustomInputOpen,
                onClick = {
                    selectedNominal = 50000.0
                    isCustomInputOpen = false
                },
                modifier = Modifier.weight(1f)
            )
            QuickNominalChip(
                label = "100 Rb",
                amount = 100000.0,
                selected = selectedNominal == 100000.0 && !isCustomInputOpen,
                onClick = {
                    selectedNominal = 100000.0
                    isCustomInputOpen = false
                },
                modifier = Modifier.weight(1f)
            )
            QuickNominalChip(
                label = "1 Jt",
                amount = 1000000.0,
                selected = selectedNominal == 1000000.0 && !isCustomInputOpen,
                onClick = {
                    selectedNominal = 1000000.0
                    isCustomInputOpen = false
                },
                modifier = Modifier.weight(1f)
            )
            QuickNominalChip(
                label = "Lainnya",
                amount = -1.0,
                selected = isCustomInputOpen,
                onClick = { isCustomInputOpen = !isCustomInputOpen },
                modifier = Modifier.weight(1.1f)
            )
        }

        // Custom Input Field
        AnimatedVisibility(
            visible = isCustomInputOpen,
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
                            selectedNominal = parsed
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

        // Table / Detail Transaksi (Persis format Indodax)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101C2A), RoundedCornerShape(10.dp))
                .border(0.5.dp, Color(0xFF1C3147), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TransactionDetailRow(
                label = "Harga Rata-rata",
                value = "${PriceFormatter.formatIdrNumber(validPrice)} $quoteAsset"
            )
            TransactionDetailRow(
                label = "Jumlah Order",
                value = "${PriceFormatter.formatIdrNumber(grossOrderAmount)} $quoteAsset",
                subValue = "= ${PriceFormatter.formatCryptoExact(grossCryptoQty, 8)} $baseAsset"
            )
            TransactionDetailRow(
                label = "Order Terisi",
                value = "${PriceFormatter.formatIdrNumber(filledOrderAmount)} $quoteAsset"
            )

            // Biaya Transaksi Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showFeeDetailModal = true }
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Biaya Transaksi",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Rincian",
                        tint = Color(0xFF90A4AE),
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = "- ${PriceFormatter.formatIdrNumber(totalFeeIdr)} $quoteAsset",
                    color = TvRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider(color = Color(0xFF1B2D40), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

            // Total Diterima Bersih
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total Diterima",
                    color = TvGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${PriceFormatter.formatCryptoExact(netCryptoQty, 8)} $baseAsset",
                        color = TvGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "= ${PriceFormatter.formatIdrNumber(netReceivedAmountIdr)} $quoteAsset",
                        color = TvTextSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }

    // Modal popup rincian biaya transaksi
    RadarFeeDetailDialog(
        isOpen = showFeeDetailModal,
        onDismiss = { showFeeDetailModal = false },
        fees = fees,
        orderAmountIdr = grossOrderAmount,
        isMakerOrder = isMakerOrder,
        coinSymbol = baseAsset
    )
}

@Composable
private fun OrderTypeTab(
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
private fun QuickNominalChip(
    label: String,
    amount: Double,
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
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF00E5FF) else TvTextSecondary,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun TransactionDetailRow(
    label: String,
    value: String,
    subValue: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF90A4AE),
            fontSize = 11.sp
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (subValue != null) {
                Text(
                    text = subValue,
                    color = TvTextSecondary,
                    fontSize = 9.5.sp
                )
            }
        }
    }
}
