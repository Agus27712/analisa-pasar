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
    var showBuyDialog by remember { mutableStateOf(false) }
    var investedInput by remember { mutableStateOf("") }
    var entryInput by remember { mutableStateOf("") }

    val recommendation = when {
        !position.isHolding && signal.action == SignalAction.BUY -> "SIAP BELI" to TvGreen
        !position.isHolding && signal.action == SignalAction.SELL -> "BELUM PUNYA COIN" to TvTextSecondary
        !position.isHolding -> "TUNGGU BUY" to TvAmber
        signal.action == SignalAction.SELL -> "SIAP JUAL" to TvRed
        signal.action == SignalAction.BUY -> "SUDAH PUNYA COIN" to TvGreen
        else -> "TAHAN" to TvGreen
    }

    val subtitle = when {
        !position.isHolding && signal.action == SignalAction.BUY -> "Sinyal BUY muncul. Kamu belum punya $symbol."
        !position.isHolding && signal.action == SignalAction.SELL -> "Tidak ada coin yang perlu dijual."
        !position.isHolding -> "Belum punya $symbol. Tunggu sinyal BUY."
        signal.action == SignalAction.SELL -> "Kamu punya $symbol dan sinyal SELL muncul."
        signal.action == SignalAction.BUY -> "Sudah punya $symbol. BUY berarti bisa tambah posisi, bukan wajib beli."
        else -> "Sudah punya $symbol. Tahan sampai ada alasan untuk keluar."
    }

    if (showBuyDialog) {
        AlertDialog(
            onDismissRequest = { showBuyDialog = false },
            title = { Text(if (position.isHolding) "Tambah pembelian $symbol" else "Catat pembelian $symbol") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (position.isHolding) "Masukkan pembelian baru. Pembelian sebelumnya tetap disimpan dan harga rata-rata dihitung otomatis."
                        else "Masukkan uang yang kamu keluarkan dan harga beli. Jumlah coin dihitung otomatis.",
                        fontSize = 12.sp,
                        color = TvTextSecondary
                    )
                    OutlinedTextField(
                        value = investedInput,
                        onValueChange = { investedInput = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth().testTag("position_invested_input"),
                        label = { Text("Uang yang dibelikan (Rp)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = entryInput,
                        onValueChange = { entryInput = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth().testTag("position_entry_input"),
                        label = { Text("Harga beli 1 $symbol") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    if (investedInput.toDoubleOrNull()?.let { it > 0.0 } == true && entryInput.toDoubleOrNull()?.let { it > 0.0 } == true) {
                        val amount = investedInput.toDouble()
                        val price = entryInput.toDouble()
                        Text("Kamu mendapat sekitar ${formatPositionQuantity(amount / price)} $symbol", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvGreen)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = investedInput.toDoubleOrNull()?.let { it > 0.0 } == true && entryInput.toDoubleOrNull()?.let { it > 0.0 } == true,
                    onClick = {
                        store.markBought(symbol, investedInput.toDouble(), entryInput.toDouble())
                        showBuyDialog = false
                        investedInput = ""
                        entryInput = ""
                        onPositionChanged()
                    }
                ) { Text(if (position.isHolding) "Tambah" else "Simpan") }
            },
            dismissButton = { TextButton(onClick = { showBuyDialog = false }) { Text("Batal") } }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth()
            .background(TvCardBackground, RoundedCornerShape(16.dp))
            .border(1.dp, recommendation.second.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .padding(14.dp)
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
                        investedInput = ""
                        entryInput = ""
                        showBuyDialog = true
                    } else {
                        store.markSold(symbol)
                        onPositionChanged()
                    }
                },
                modifier = Modifier.testTag("asset_ownership_switch")
            )
        }

        if (position.isHolding) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                PositionValue("Total modal", PriceFormatter.formatPrice(position.investedAmount), Modifier.weight(1f))
                PositionValue("Aset", formatPositionQuantity(position.quantity) + " $symbol", Modifier.weight(1f))
                PositionValue("Rata-rata beli", PriceFormatter.formatPrice(position.entryPrice), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (position.purchaseCount == 1) "1 kali beli" else "${position.purchaseCount} kali beli · harga rata-rata dihitung dari total modal dan total aset",
                fontSize = 9.sp,
                color = TvTextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    investedInput = ""
                    entryInput = ""
                    showBuyDialog = true
                },
                modifier = Modifier.fillMaxWidth().height(38.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x1AFFFFFF)),
                shape = RoundedCornerShape(9.dp)
            ) { Text("+ Tambah pembelian", fontSize = 11.sp, color = TvTextPrimary) }
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
