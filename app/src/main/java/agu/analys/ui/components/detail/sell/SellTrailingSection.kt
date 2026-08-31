package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun SellTrailingSection(
    isTrailingActive: Boolean,
    onTrailingActiveChanged: (Boolean) -> Unit,
    isTrailingTriggered: Boolean,
    trailingPercent: Double,
    onSetTrailingPercent: (Double) -> Unit,
    peakPrice: Double,
    trailingStopPrice: Double,
    quoteAsset: String,
    lastTrailingOrderId: String? = null,
    onDeployTrailingOrder: (() -> Unit)? = null,
    onCancelTrailingOrder: (() -> Unit)? = null,
    isRealMode: Boolean = true
) {
    val hasDeployedOrder = !lastTrailingOrderId.isNullOrEmpty()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isTrailingTriggered) TvRed.copy(alpha = 0.15f) else if (isTrailingActive) TvBlue.copy(alpha = 0.1f) else TvSurfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (isTrailingTriggered) TvRed else if (isTrailingActive) TvBlue else TvBorder,
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔒 JARING PENGAMAN OTOMATIS",
                        color = if (isTrailingTriggered) TvRed else if (isTrailingActive) TvBlue else TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Switch(
                    checked = isTrailingActive,
                    onCheckedChange = { checked ->
                        if (!checked) {
                            onCancelTrailingOrder?.invoke()
                        } else {
                            onTrailingActiveChanged(true)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TvBlue,
                        checkedTrackColor = TvBlue.copy(alpha = 0.4f),
                        uncheckedThumbColor = TvTextSecondary,
                        uncheckedTrackColor = TvSurface
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }

            if (isTrailingActive) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Jarak Trailing Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Batas Toleransi Turun:", color = TvTextSecondary, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1.5, 2.0, 3.0, 5.0).forEach { pct ->
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (trailingPercent == pct) TvBlue.copy(alpha = 0.2f) else TvSurface,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (trailingPercent == pct) TvBlue else TvBorder,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable { onSetTrailingPercent(pct) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$pct%",
                                        color = if (trailingPercent == pct) TvBlue else TvTextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = if (trailingPercent == pct) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    // Info Peak & Dynamic Stop
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurface, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Harga Puncak Tercatat (Peak):", color = TvTextSecondary, fontSize = 9.5.sp)
                            Text("${PriceFormatter.formatIdrNumber(peakPrice)} $quoteAsset", color = TvAmber, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Titik Jual Otomatis:", color = TvBlue, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                            Text("${PriceFormatter.formatIdrNumber(trailingStopPrice)} $quoteAsset", color = if (isTrailingTriggered) TvRed else TvBlue, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }

                    // Status Integrasi Bursa / Eksekutor Sell
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasDeployedOrder) {
                            // Status Terpasang
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TvGreen.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isRealMode) "🟢 PEMANTAUAN OTOMATIS AKTIF\nAset akan dijual saat menyentuh batas aman."
                                           else "🟢 PEMANTAUAN SIMULASI AKTIF\nAset akan dijual saat menyentuh batas aman.",
                                    color = TvGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 13.sp
                                )
                            }
                            // Tombol Matikan Trailing
                            Button(
                                onClick = { onCancelTrailingOrder?.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    if (isRealMode) "Matikan Pemantauan" else "Matikan Pemantauan & Batal Sim",
                                    color = TvRed,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            // Status Belum Terpasang
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TvAmber.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isRealMode) "⚠️ Jaring pengaman siap, belum diaktifkan."
                                           else "⚠️ Jaring pengaman siap, belum diaktifkan (Simulasi).",
                                    color = TvAmber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Tombol Pasang Trailing
                            Button(
                                onClick = { onDeployTrailingOrder?.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    if (isRealMode) "Aktifkan Jaring Pengaman" else "Aktifkan Pengaman (Simulasi)",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
