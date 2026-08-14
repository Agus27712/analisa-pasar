package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.AISignalState
import agu.analys.model.SignalAction
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvCardBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import kotlin.math.abs

@Composable
fun SpotPositionCard(
    symbol: String,
    signal: AISignalState,
    position: SpotPosition,
    currentPrice: Double = 0.0,
    onPositionChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember(context) { SpotPositionStore(context) }
    var showDialog by remember { mutableStateOf(false) }
    var investedInput by remember { mutableStateOf("") }
    var entryInput by remember { mutableStateOf("") }

    // Tampilkan base asset saja (BTC dari BTCIDR)
    val displaySymbol = symbol.removeSuffix("IDR").removeSuffix("USDT").uppercase()

    val recommendation = when {
        !position.isHolding && signal.action == SignalAction.BUY -> "PERTIMBANGKAN BELI"
        position.isHolding && signal.action == SignalAction.SELL -> "PERTIMBANGKAN JUAL"
        position.isHolding -> "TAHAN"
        else -> "TUNGGU"
    }
    val recommendationColor = when {
        signal.action == SignalAction.BUY -> TvGreen
        signal.action == SignalAction.SELL -> TvRed
        else -> TvAmber
    }

    val currentValue = if (position.isHolding && currentPrice > 0.0) position.quantity * currentPrice else 0.0
    val profitLoss = if (position.isHolding) currentValue - position.investedAmount else 0.0
    val profitPercent = if (position.isHolding && position.investedAmount > 0.0) profitLoss / position.investedAmount * 100.0 else 0.0
    val profitColor = when {
        profitLoss > 0.0 -> TvGreen
        profitLoss < 0.0 -> TvRed
        else -> TvTextSecondary
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Color(0xFF121A24),
            titleContentColor = TvTextPrimary,
            textContentColor = TvTextSecondary,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = TvGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text(
                        "Atur Posisi $displaySymbol",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Masukkan data pembelian kamu. Jumlah koin & P/L dihitung otomatis dari harga market Indodax.",
                        fontSize = 13.sp,
                        color = TvTextSecondary,
                        lineHeight = 18.sp
                    )

                    OutlinedTextField(
                        value = investedInput,
                        onValueChange = { investedInput = normalizeDecimalInput(it) },
                        modifier = Modifier.fillMaxWidth().testTag("position_invested_input"),
                        label = { Text("Nilai Pembelian (IDR)", fontSize = 13.sp) },
                        placeholder = { Text("Contoh: 500000", color = TvTextSecondary.copy(alpha = 0.6f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvGreen,
                            unfocusedBorderColor = Color(0xFF2A3540),
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            focusedLabelColor = TvGreen,
                            unfocusedLabelColor = TvTextSecondary,
                            cursorColor = TvGreen,
                            focusedContainerColor = Color(0xFF0D1420),
                            unfocusedContainerColor = Color(0xFF0D1420)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = entryInput,
                        onValueChange = { entryInput = normalizeDecimalInput(it) },
                        modifier = Modifier.fillMaxWidth().testTag("position_entry_input"),
                        label = { Text("Harga Beli per $displaySymbol (IDR)", fontSize = 13.sp) },
                        placeholder = { Text("Contoh: 850000000", color = TvTextSecondary.copy(alpha = 0.6f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvGreen,
                            unfocusedBorderColor = Color(0xFF2A3540),
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            focusedLabelColor = TvGreen,
                            unfocusedLabelColor = TvTextSecondary,
                            cursorColor = TvGreen,
                            focusedContainerColor = Color(0xFF0D1420),
                            unfocusedContainerColor = Color(0xFF0D1420)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    val amount = investedInput.toDoubleOrNull() ?: 0.0
                    val price = entryInput.toDoubleOrNull() ?: 0.0
                    if (amount > 0.0 && price > 0.0) {
                        val quantity = amount / price
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvGreen.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                                .border(1.dp, TvGreen.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = TvGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text(
                                    "Preview",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TvGreen
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Jumlah $displaySymbol: ${formatPositionQuantity(quantity)}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TvTextPrimary
                            )
                            if (currentPrice > 0.0) {
                                val valueNow = quantity * currentPrice
                                val pnl = valueNow - amount
                                val pct = pnl / amount * 100.0
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Nilai sekarang: ${PriceFormatter.formatPrice(valueNow)}",
                                    fontSize = 12.sp,
                                    color = TvTextSecondary
                                )
                                Text(
                                    "P/L ${formatSignedMoney(pnl)} (${formatSignedPct(pct)})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (pnl >= 0) TvGreen else TvRed
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = investedInput.toDoubleOrNull()?.let { it > 0.0 } == true &&
                            entryInput.toDoubleOrNull()?.let { it > 0.0 } == true,
                    onClick = {
                        store.markBought(
                            symbol,
                            investedInput.toDouble(),
                            parseDecimal(entryInput)!!
                        )
                        showDialog = false
                        onPositionChanged()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TvGreen,
                        disabledContainerColor = Color(0xFF2A3540)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Simpan Posisi", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Batal", color = TvTextSecondary)
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvCardBackground, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF183247), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("POSISI SAYA", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary, letterSpacing = 0.7.sp)
                Text(recommendation, fontSize = 18.sp, fontWeight = FontWeight.Black, color = recommendationColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (position.isHolding) "Punya $displaySymbol" else "Belum punya",
                    fontSize = 10.sp,
                    color = TvTextSecondary
                )
                Spacer(Modifier.padding(horizontal = 2.dp))
                Switch(
                    checked = position.isHolding,
                    onCheckedChange = { checked ->
                        if (checked) {
                            investedInput = if (position.investedAmount > 0) position.investedAmount.toString() else ""
                            entryInput = if (position.entryPrice > 0) position.entryPrice.toString() else ""
                            showDialog = true
                        } else {
                            store.markSold(symbol)
                            onPositionChanged()
                        }
                    },
                    modifier = Modifier.testTag("asset_ownership_switch")
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Masukkan data pembelian kamu. Jumlah koin dan P/L dihitung dari harga market Indodax yang sedang tampil.",
            fontSize = 10.sp,
            color = TvTextSecondary
        )

        if (position.isHolding) {
            Spacer(Modifier.height(12.dp))
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Modal Investasi", PriceFormatter.formatPrice(position.investedAmount), Modifier.weight(1f))
                PositionValue("Harga Sekarang", PriceFormatter.formatPrice(currentPrice), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Harga Beli", PriceFormatter.formatPrice(position.entryPrice), Modifier.weight(1f))
                PositionValue("Nilai Sekarang", PriceFormatter.formatPrice(currentValue), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Jumlah $displaySymbol", formatPositionQuantity(position.quantity), Modifier.weight(1f))
                PositionValue("P/L", "${formatSignedMoney(profitLoss)} (${formatSignedPct(profitPercent)})", Modifier.weight(1f), profitColor)
            }
            Spacer(Modifier.height(10.dp))
            Column(modifier.fillMaxWidth().background(profitColor.copy(alpha = 0.10f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text("ESTIMASI P/L", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = profitColor)
                Text(formatSignedMoney(profitLoss), fontSize = 17.sp, fontWeight = FontWeight.Black, color = profitColor)
                Text("Nilai sekarang: ${PriceFormatter.formatPrice(currentValue)}", fontSize = 9.sp, color = TvTextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    investedInput = position.investedAmount.toString()
                    entryInput = position.entryPrice.toString()
                    showDialog = true
                },
                modifier = Modifier.fillMaxWidth().height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x1AFFFFFF)),
                shape = RoundedCornerShape(9.dp)
            ) { Text("Ubah Data Pembelian", fontSize = 10.sp, color = TvTextPrimary) }
        } else {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth().height(38.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5)),
                shape = RoundedCornerShape(10.dp)
            ) { Text("ATUR POSISI", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White) }
        }
    }
}

@Composable
private fun PositionValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TvTextPrimary) {
    Column(modifier) {
        Text(label, fontSize = 8.sp, color = TvTextSecondary)
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 2)
    }
}

private fun formatPositionQuantity(value: Double): String = when {
    value <= 0.0 -> "-"
    value >= 1.0 -> String.format("%.10f", value).trimEnd('0').trimEnd('.')
    else -> String.format("%.16f", value).trimEnd('0').trimEnd('.')
}

private fun formatSignedMoney(value: Double): String =
    (if (value >= 0) "+" else "-") + PriceFormatter.formatPrice(abs(value))

private fun formatSignedPct(value: Double): String =
    (if (value >= 0) "+" else "-") + PriceFormatter.formatPercentage(abs(value), includePlusSign = false)

private fun normalizeDecimalInput(value: String): String {
    val normalized = value.replace(',', '.')
    if (normalized.count { it == '.' } > 1) {
        return normalized.dropLast(1)
    }
    return normalized.filter { it.isDigit() || it == '.' }
}

private fun parseDecimal(value: String): Double? =
    value.replace(',', '.').toDoubleOrNull()
