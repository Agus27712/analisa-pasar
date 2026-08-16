package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import agu.analys.config.TradingFeeConfig
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

/**
 * Dialog rincian Biaya Transaksi identik dengan tampilan modal Indodax
 * yang bersumber dari konfigurasi fee di Settings pengguna.
 */
@Composable
fun RadarFeeDetailDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    fees: TradingFeeConfig,
    orderAmountIdr: Double,
    isMakerOrder: Boolean,
    coinSymbol: String = "BTC"
) {
    if (!isOpen) return

    val feePct = if (isMakerOrder) fees.buyMakerPct else fees.buyTakerPct
    val totalFeeIdr = orderAmountIdr * (feePct / 100.0)
    // Proporsi breakdown Indodax: Biaya Layanan (~92.5%) + CFX (~7.5%), Pajak 0/PPh
    val serviceFeeIdr = (totalFeeIdr * 0.925).coerceAtLeast(0.0)
    val cfxFeeIdr = (totalFeeIdr * 0.075).coerceAtLeast(0.0)
    val taxIdr = 0.0

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1722))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header: Title & Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Biaya Transaksi",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF1B2838), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Subtitle penjelasan
                Text(
                    text = "Biaya yang dikenakan pada aktivitas transaksi untuk menjamin keamanan trading Anda.",
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Spacer(Modifier.height(16.dp))

                // Container Rincian Biaya (Dark Card)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF14202E), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF1E3247), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Biaya Layanan
                    FeeRowItem(
                        label = "Biaya Layanan (${String.format("%.2f", feePct * 0.925)}%)",
                        value = "${PriceFormatter.formatIdrNumber(serviceFeeIdr)} IDR"
                    )

                    // Pajak
                    FeeRowItem(
                        label = "Pajak",
                        value = "0 IDR"
                    )

                    // Biaya CFX
                    FeeRowItem(
                        label = "Biaya CFX (${String.format("%.2f", feePct * 0.075)}%)",
                        value = "${PriceFormatter.formatIdrNumber(cfxFeeIdr)} IDR"
                    )

                    HorizontalDivider(
                        color = Color(0xFF22364E),
                        thickness = 0.8.dp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    // Biaya Transaksi Total
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Biaya Transaksi",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${PriceFormatter.formatIdrNumber(totalFeeIdr)} IDR",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Info Setting Asal
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF101E2E), RoundedCornerShape(10.dp))
                        .border(0.5.dp, Color(0xFF1D3B5C), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Dihitung dari Setting: ${if (isMakerOrder) "Limit (Maker) ${fees.buyMakerPct}%" else "Instant (Taker) ${fees.buyTakerPct}%"}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5A))
                ) {
                    Text(
                        text = "Mengerti",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FeeRowItem(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFFB0BEC5),
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
