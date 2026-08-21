package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    onExecuteSell: ((Double) -> Unit)?
) {
    var customSellQtyInput by remember { mutableStateOf("") }
    var isCustomSellQtyOpen by remember { mutableStateOf(false) }
    var selectedSellPercent by remember { mutableIntStateOf(100) }
    val focusManager = LocalFocusManager.current

    val activeSellQty = if (selectedSellQuantity > 0.0) selectedSellQuantity else availableCoin
    val grossSellValueIdr = activeSellQty * validPrice
    val sellFeeIdr = grossSellValueIdr * (activeFeePct / 100.0)
    val netReceivedSellIdr = (grossSellValueIdr - sellFeeIdr).coerceAtLeast(0.0)

    val effectiveBuyPrice = if (avgBuyPrice > 0.0) avgBuyPrice else validPrice
    val costBasisIdr = activeSellQty * effectiveBuyPrice
    val netProfitIdr = netReceivedSellIdr - costBasisIdr
    val netProfitPct = if (costBasisIdr > 0.0) (netProfitIdr / costBasisIdr) * 100.0 else 0.0
    val isProfitable = netProfitIdr >= 0.0

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "${PriceFormatter.formatCryptoExact(availableCoin, 8)} $baseAsset",
                        color = if (availableCoin > 0) Color(0xFF00E5FF) else TvTextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black
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
                        fontSize = 10.sp
                    )
                    Text(
                        text = if (avgBuyPrice > 0.0) "${PriceFormatter.formatIdrNumber(avgBuyPrice)} $quoteAsset" else "Belum Ada Posisi",
                        color = if (avgBuyPrice > 0.0) Color(0xFFFFD54F) else TvTextSecondary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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
                    label = if (pct == 100) "100% (All)" else "$pct%",
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
            if (avgBuyPrice > 0.0) {
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
                Text("Hasil Kas Diterima", color = Color(0xFFB0BEC5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${PriceFormatter.formatIdrNumber(netReceivedSellIdr)} $quoteAsset", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.height(4.dp))

            // Highlight HASIL KEUNTUNGAN / KERUGIAN BERSIH
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
                    Column {
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
                                fontWeight = FontWeight.Black
                            )
                        }
                        Text(
                            text = "Sudah dipotong seluruh fee transaksi",
                            color = TvTextSecondary,
                            fontSize = 9.sp
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${if (isProfitable) "+" else ""}${PriceFormatter.formatIdrNumber(netProfitIdr)} $quoteAsset",
                            color = if (isProfitable) TvGreen else TvRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "(${if (isProfitable) "+" else ""}${String.format(Locale.US, "%.2f", netProfitPct)}%)",
                            color = if (isProfitable) TvGreen else TvRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
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
                    fontSize = 12.sp
                )
            }
        }
    }
}
