package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import agu.analys.trading.SimulationWallet
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun SimulationTopUpModal(
    wallet: SimulationWallet,
    onTopUp: (Double) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val quickAmounts = listOf(1_000_000.0, 5_000_000.0, 10_000_000.0, 50_000_000.0)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = "Wallet",
                            tint = TvGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Kelola Modal Simulasi",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.LightGray)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Current Balance Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(text = "Total Saldo IDR Tersedia", color = TvTextSecondary, fontSize = 11.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Rp ${PriceFormatter.formatPrice(wallet.getAvailableIdr())}",
                            color = TvGreen,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (wallet.lockedIdr > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Tertahan di Open Orders: Rp ${PriceFormatter.formatPrice(wallet.lockedIdr)}",
                                color = TvOrange,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "Tambah Modal Virtual (Top Up):",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(8.dp))

                // Quick Top Up Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickAmounts.take(2).forEach { amount ->
                        Button(
                            onClick = {
                                onTopUp(amount)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF162032)),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "+Rp ${formatShort(amount)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickAmounts.takeLast(2).forEach { amount ->
                        Button(
                            onClick = {
                                onTopUp(amount)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF162032)),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "+Rp ${formatShort(amount)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Reset Button
                OutlinedButton(
                    onClick = {
                        onReset()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvRed),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(TvRed.copy(alpha = 0.5f))),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset",
                        tint = TvRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Reset Akun & Saldo ke Rp 10.000.000",
                        color = TvRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun formatShort(amount: Double): String {
    return when {
        amount >= 1_000_000 -> "${(amount / 1_000_000).toInt()} Jt"
        amount >= 1_000 -> "${(amount / 1_000).toInt()} Rb"
        else -> amount.toInt().toString()
    }
}
