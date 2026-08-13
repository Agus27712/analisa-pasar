package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.SignalAction
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvCardBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.AppPreferences

@Composable
fun PositionOwnershipCard(
    symbol: String,
    signalAction: SignalAction,
    preferences: AppPreferences,
    modifier: Modifier = Modifier
) {
    var owned by remember(symbol) { mutableStateOf(preferences.isAssetOwned(symbol)) }

    val status = when (signalAction) {
        SignalAction.BUY -> if (owned) "SUDAH PUNYA" else "SIAP BELI"
        SignalAction.HOLD -> if (owned) "TETAP PEGANG" else "TUNGGU BUY"
        SignalAction.SELL -> if (owned) "SIAP JUAL" else "TIDAK ADA POSISI"
    }
    val statusColor = when (signalAction) {
        SignalAction.BUY -> if (owned) TvGreen else TvGreen
        SignalAction.HOLD -> TvAmber
        SignalAction.SELL -> if (owned) TvRed else TvTextSecondary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvCardBackground, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "POSISI SAYA",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvGreen,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (owned) "Punya $symbol di Indodax" else "Belum punya $symbol di Indodax",
                    fontSize = 12.sp,
                    color = TvTextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Manual • default OFF • berlaku per koin",
                    fontSize = 9.sp,
                    color = TvTextSecondary
                )
            }
            Switch(
                checked = owned,
                onCheckedChange = { checked ->
                    owned = checked
                    preferences.setAssetOwned(symbol, checked)
                },
                thumbContent = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = TvGreen,
                    uncheckedThumbColor = TvTextSecondary,
                    uncheckedTrackColor = Color(0xFF26313B),
                    uncheckedBorderColor = Color(0xFF3A4652)
                )
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(statusColor.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("KONTEKS SINYAL", fontSize = 9.sp, color = TvTextSecondary)
                Text(status, fontSize = 13.sp, fontWeight = FontWeight.Black, color = statusColor)
            }
            Text(
                when {
                    signalAction == SignalAction.BUY && !owned -> "Boleh cari entry"
                    signalAction == SignalAction.BUY -> "Tidak perlu beli lagi"
                    signalAction == SignalAction.HOLD && owned -> "Jangan jual"
                    signalAction == SignalAction.HOLD -> "Belum ada posisi"
                    signalAction == SignalAction.SELL && owned -> "Eksekusi jual manual"
                    else -> "Abaikan SELL"
                },
                fontSize = 9.sp,
                color = TvTextSecondary
            )
        }
    }
}
