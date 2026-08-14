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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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

    val displaySymbol = symbol.removeSuffix("IDR").removeSuffix("USDT").uppercase()

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
            containerColor = Color(0xFF0F1720),
            titleContentColor = TvTextPrimary,
            textContentColor = TvTextSecondary,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountBalanceWallet, null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text("ATUR POSISI SAYA", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color(0xFF4FC3F7))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A2A3A), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Default.Info, null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(
                            "Masukkan data pembelian Anda. Aplikasi akan menghitung jumlah koin dan profit/loss secara otomatis.",
                            fontSize = 12.sp,
                            color = TvTextSecondary,
                            lineHeight = 17.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Punya $displaySymbol", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                        Switch(
                            checked = true,
                            onCheckedChange = { },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4FC3F7))
                        )
                    }

                    OutlinedTextField(
                        value = investedInput,
                        onValueChange = { investedInput = normalizeDecimalInput(it) },
                        modifier = Modifier.fillMaxWidth().testTag("position_invested_input"),
                        label = { Text("Nilai Pembelian (IDR)", fontSize = 12.sp) },
                        placeholder = { Text("100.000", color = TvTextSecondary.copy(alpha = 0.5f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4FC3F7),
                            unfocusedBorderColor = Color(0xFF2A3540),
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            focusedLabelColor = Color(0xFF4FC3F7),
                            unfocusedLabelColor = TvTextSecondary,
                            cursorColor = Color(0xFF4FC3F7),
                            focusedContainerColor = Color(0xFF0D1420),
                            unfocusedContainerColor = Color(0xFF0D1420)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    OutlinedTextField(
                        value = entryInput,
                        onValueChange = { entryInput = normalizeDecimalInput(it) },
                        modifier = Modifier.fillMaxWidth().testTag("position_entry_input"),
                        label = { Text("Harga Beli per $displaySymbol (IDR)", fontSize = 12.sp) },
                        placeholder = { Text("1.800.000.000", color = TvTextSecondary.copy(alpha = 0.5f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4FC3F7),
                            unfocusedBorderColor = Color(0xFF2A3540),
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary,
                            focusedLabelColor = Color(0xFF4FC3F7),
                            unfocusedLabelColor = TvTextSecondary,
                            cursorColor = Color(0xFF4FC3F7),
                            focusedContainerColor = Color(0xFF0D1420),
                            unfocusedContainerColor = Color(0xFF0D1420)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    val amount = investedInput.toDoubleOrNull() ?: 0.0
                    val price = entryInput.toDoubleOrNull() ?: 0.0
                    if (amount > 0.0 && price > 0.0) {
                        val quantity = amount / price
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF13253A), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Jumlah $displaySymbol (otomatis)", fontSize = 11.sp, color = TvTextSecondary)
                                Text("${formatPositionQuantity(quantity)} $displaySymbol", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                            }
                            if (currentPrice > 0.0) {
                                val valueNow = quantity * currentPrice
                                val pnl = valueNow - amount
                                val pct = pnl / amount * 100.0
                                Spacer(Modifier.height(6.dp))
                                Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Estimasi Nilai Sekarang", fontSize = 11.sp, color = TvTextSecondary)
                                    Text(PriceFormatter.formatPrice(valueNow), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Estimasi P/L", fontSize = 11.sp, color = TvTextSecondary)
                                    Text(
                                        "${formatSignedMoney(pnl)} (${formatSignedPct(pct)})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (pnl >= 0) TvGreen else TvRed
                                    )
                                }
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
                        store.markBought(symbol, investedInput.toDouble(), parseDecimal(entryInput)!!)
                        showDialog = false
                        onPositionChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3),
                        disabledContainerColor = Color(0xFF2A3540)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("SIMPAN POSISI", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 14.sp)
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
            .background(Color(0xFF0D1722), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF1A3347), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("POSISI SAYA", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary, letterSpacing = 0.6.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (position.isHolding) "Punya $displaySymbol" else "Belum punya",
                    fontSize = 11.sp,
                    color = TvTextSecondary
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
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
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4FC3F7),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF3A3A3A)
                    ),
                    modifier = Modifier.testTag("asset_ownership_switch")
                )
            }
        }

        if (position.isHolding) {
            Spacer(Modifier.height(12.dp))
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Modal Investasi", PriceFormatter.formatPrice(position.investedAmount), Modifier.weight(1f))
                PositionValue("Harga Sekarang", PriceFormatter.formatPrice(currentPrice), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Harga Beli", PriceFormatter.formatPrice(position.entryPrice), Modifier.weight(1f))
                PositionValue("Nilai Sekarang", PriceFormatter.formatPrice(currentValue), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Jumlah $displaySymbol", formatPositionQuantity(position.quantity), Modifier.weight(1f))
                PositionValue("P/L", "${formatSignedMoney(profitLoss)} (${formatSignedPct(profitPercent)})", Modifier.weight(1f), profitColor)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A1218), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, tint = TvTextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("Data diperbarui otomatis setiap perubahan harga.", fontSize = 10.sp, color = TvTextSecondary)
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
            ) { Text("Ubah Data Pembelian", fontSize = 11.sp, color = TvTextPrimary) }
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                "Masukkan data pembelian kamu biar bisa liat estimasi P/L real-time.",
                fontSize = 11.sp,
                color = TvTextSecondary,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                shape = RoundedCornerShape(12.dp)
            ) { Text("ATUR POSISI", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White) }
        }
    }
}

@Composable
private fun PositionValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TvTextPrimary) {
    Column(modifier) {
        Text(label, fontSize = 9.sp, color = TvTextSecondary)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 2)
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
    if (normalized.count { it == '.' } > 1) return normalized.dropLast(1)
    return normalized.filter { it.isDigit() || it == '.' }
}

private fun parseDecimal(value: String): Double? =
    value.replace(',', '.').toDoubleOrNull()
