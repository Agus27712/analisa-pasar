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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
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
import agu.analys.trading.SpotPositionState
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

    val recommendation = when {
        !position.isHolding && signal.action == SignalAction.BUY -> "WAKTUNYA BELI"
        !position.isHolding && signal.action == SignalAction.SELL -> "BELUM PUNYA COIN"
        !position.isHolding -> "TUNGGU"
        signal.action == SignalAction.SELL -> "PERTIMBANGKAN JUAL"
        signal.action == SignalAction.BUY -> "SUDAH PUNYA COIN"
        else -> "TAHAN"
    }
    val recommendationColor = when {
        !position.isHolding && signal.action == SignalAction.BUY -> TvGreen
        position.isHolding && signal.action == SignalAction.SELL -> TvRed
        else -> TvAmber
    }
    val subtitle = when {
        !position.isHolding && signal.action == SignalAction.BUY -> "Sinyal naik muncul dan kamu belum punya $symbol."
        !position.isHolding && signal.action == SignalAction.SELL -> "Tidak ada $symbol yang perlu dijual."
        !position.isHolding -> "Belum ada sinyal beli yang cukup kuat."
        signal.action == SignalAction.SELL -> "Kamu punya $symbol dan sinyal turun muncul."
        signal.action == SignalAction.BUY -> "Kamu sudah punya $symbol. BUY tidak berarti wajib beli lagi."
        else -> "Kamu sudah punya $symbol. Tunggu sinyal yang lebih jelas untuk keluar."
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
            title = { Text(if (position.isHolding) "Ubah posisi $symbol" else "Saya punya $symbol") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Isi berdasarkan transaksi yang kamu lakukan di Indodax. Aplikasi tidak membaca saldo akunmu.", fontSize = 12.sp, color = TvTextSecondary)
                    OutlinedTextField(value = investedInput, onValueChange = { investedInput = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth().testTag("position_invested_input"), label = { Text("Uang yang dibelikan (Rp)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = entryInput, onValueChange = { entryInput = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth().testTag("position_entry_input"), label = { Text("Harga beli $symbol") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    val amount = investedInput.toDoubleOrNull() ?: 0.0
                    val price = entryInput.toDoubleOrNull() ?: 0.0
                    if (amount > 0.0 && price > 0.0) Text("Jumlah $symbol: ${formatPositionQuantity(amount / price)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvGreen)
                }
            },
            confirmButton = {
                TextButton(enabled = investedInput.toDoubleOrNull()?.let { it > 0.0 } == true && entryInput.toDoubleOrNull()?.let { it > 0.0 } == true, onClick = {
                    store.markBought(symbol, investedInput.toDouble(), entryInput.toDouble())
                    showDialog = false
                    onPositionChanged()
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Batal") } }
        )
    }

    Column(modifier = modifier.fillMaxWidth().background(TvCardBackground, RoundedCornerShape(16.dp)).border(1.dp, recommendationColor.copy(alpha = 0.28f), RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("POSISI SAYA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = recommendationColor, letterSpacing = 1.sp)
                Text(recommendation, fontSize = 17.sp, fontWeight = FontWeight.Black, color = recommendationColor)
                Text(subtitle, fontSize = 10.sp, color = TvTextSecondary)
            }
            Switch(checked = position.state == SpotPositionState.HOLDING, onCheckedChange = { checked ->
                if (checked) {
                    investedInput = if (position.investedAmount > 0) position.investedAmount.toLong().toString() else ""
                    entryInput = if (position.entryPrice > 0) position.entryPrice.toLong().toString() else ""
                    showDialog = true
                } else {
                    store.markSold(symbol)
                    onPositionChanged()
                }
            }, modifier = Modifier.testTag("asset_ownership_switch"))
        }

        if (position.isHolding) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Uang masuk", PriceFormatter.formatPrice(position.investedAmount), Modifier.weight(1f))
                PositionValue("Jumlah $symbol", formatPositionQuantity(position.quantity), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PositionValue("Harga beli", PriceFormatter.formatPrice(position.entryPrice), Modifier.weight(1f))
                PositionValue("Harga sekarang", PriceFormatter.formatPrice(currentPrice), Modifier.weight(1f))
            }
            if (currentPrice > 0.0 && position.quantity > 0.0) {
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().background(profitColor.copy(alpha = 0.10f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("UNTUNG / RUGI SAAT INI", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = profitColor)
                    Text("${if (profitLoss >= 0) "+" else "-"}${PriceFormatter.formatPrice(abs(profitLoss))}  (${if (profitPercent >= 0) "+" else "-"}${PriceFormatter.formatPercentage(abs(profitPercent), includePlusSign = false)})", fontSize = 14.sp, fontWeight = FontWeight.Black, color = profitColor)
                    Text("Nilai sekarang: ${PriceFormatter.formatPrice(currentValue)}", fontSize = 9.sp, color = TvTextSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                investedInput = position.investedAmount.toLong().toString()
                entryInput = position.entryPrice.toLong().toString()
                showDialog = true
            }, modifier = Modifier.fillMaxWidth().height(36.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0x1AFFFFFF)), shape = RoundedCornerShape(9.dp)) {
                Text("Ubah data posisi", fontSize = 10.sp, color = TvTextPrimary)
            }
        }
    }
}

@Composable
private fun PositionValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 8.sp, color = TvTextSecondary)
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
    }
}

private fun formatPositionQuantity(value: Double): String = when {
    value <= 0.0 -> "-"
    value >= 1.0 -> String.format("%.6f", value).trimEnd('0').trimEnd('.')
    else -> String.format("%.10f", value).trimEnd('0').trimEnd('.')
}
