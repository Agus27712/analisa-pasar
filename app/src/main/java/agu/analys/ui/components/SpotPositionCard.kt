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

@Composable
fun SpotPositionCard(
    symbol: String,
    signal: AISignalState,
    position: SpotPosition,
    onPositionChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember(context) { SpotPositionStore(context) }
    var showDialog by remember { mutableStateOf(false) }
    var investedInput by remember { mutableStateOf("") }
    var entryInput by remember { mutableStateOf("") }

    val recommendation = when {
        !position.isHolding && signal.action == SignalAction.BUY -> "SIAP BELI" to TvGreen
        !position.isHolding && signal.action == SignalAction.SELL -> "TIDAK ADA POSISI" to TvTextSecondary
        !position.isHolding -> "TUNGGU BUY" to TvAmber
        signal.action == SignalAction.SELL -> "SIAP JUAL" to TvRed
        else -> "TAHAN" to TvGreen
    }
    val subtitle = when {
        !position.isHolding && signal.action == SignalAction.BUY -> "Sinyal BUY muncul dan kamu belum memiliki coin."
        !position.isHolding && signal.action == SignalAction.SELL -> "SELL tidak dieksekusi karena belum ada coin yang dimiliki."
        !position.isHolding -> "Belum ada setup BUY yang cukup kuat."
        signal.action == SignalAction.SELL -> "Kamu memiliki posisi dan sinyal SELL muncul."
        signal.action == SignalAction.BUY -> "Kamu sudah punya coin. Jangan beli ulang hanya karena BUY."
        else -> "Kamu sudah punya coin. Tahan sampai ada alasan keluar."
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Posisi $symbol") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Masukkan nilai pembelian dan harga beli. Jumlah coin dihitung otomatis.", fontSize = 12.sp, color = TvTextSecondary)
                    OutlinedTextField(
                        value = investedInput,
                        onValueChange = { investedInput = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth().testTag("position_invested_input"),
                        label = { Text("Nilai pembelian (Rp)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = entryInput,
                        onValueChange = { entryInput = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth().testTag("position_entry_input"),
                        label = { Text("Harga beli per $symbol") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = investedInput.toDoubleOrNull()?.let { it > 0.0 } == true && entryInput.toDoubleOrNull()?.let { it > 0.0 } == true,
                    onClick = {
                        store.markBought(symbol, investedInput.toDouble(), entryInput.toDouble())
                        showDialog = false
                        onPositionChanged()
                    }
                ) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Batal") } }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().background(TvCardBackground, RoundedCornerShape(16.dp)).border(1.dp, recommendation.second.copy(alpha = 0.28f), RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("POSISI SAYA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = recommendation.second, letterSpacing = 1.sp)
                Text(recommendation.first, fontSize = 17.sp, fontWeight = FontWeight.Black, color = recommendation.second)
                Text(subtitle, fontSize = 10.sp, color = TvTextSecondary)
            }
            Switch(
                checked = position.state == SpotPositionState.HOLDING,
                onCheckedChange = { checked ->
                    if (checked) {
                        investedInput = if (position.investedAmount > 0) position.investedAmount.toLong().toString() else ""
                        entryInput = if (position.entryPrice > 0) position.entryPrice.toLong().toString() else ""
                        showDialog = true
                    } else {
                        store.markSold(symbol)
                        onPositionChanged()
                    }
                },
                modifier = Modifier.testTag("asset_ownership_switch")
            )
        }
        if (position.isHolding) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                PositionValue("Modal", PriceFormatter.formatPrice(position.investedAmount))
                PositionValue("Harga beli", PriceFormatter.formatPrice(position.entryPrice))
                PositionValue("Jumlah", formatPositionQuantity(position.quantity))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    investedInput = position.investedAmount.toLong().toString()
                    entryInput = position.entryPrice.toLong().toString()
                    showDialog = true
                },
                modifier = Modifier.fillMaxWidth().height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x1AFFFFFF)),
                shape = RoundedCornerShape(9.dp)
            ) { Text("Ubah posisi", fontSize = 10.sp, color = TvTextPrimary) }
        }
    }
}

@Composable
private fun PositionValue(label: String, value: String) {
    Column {
        Text(label, fontSize = 8.sp, color = TvTextSecondary)
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
    }
}

private fun formatPositionQuantity(value: Double): String = when {
    value <= 0.0 -> "-"
    value >= 1.0 -> String.format("%.6f", value).trimEnd('0').trimEnd('.')
    else -> String.format("%.10f", value).trimEnd('0').trimEnd('.')
}
