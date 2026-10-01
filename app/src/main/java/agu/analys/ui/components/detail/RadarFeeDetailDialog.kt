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
 * Dialog rincian Biaya Transaksi
 * yang bersumber dari konfigurasi fee di Settings pengguna.
 */
@Composable
fun RadarFeeDetailDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    fees: TradingFeeConfig,
    orderAmountIdr: Double,
    isMakerOrder: Boolean,
    coinSymbol: String,
    quoteAsset: String = "IDR",
    isBuyMode: Boolean = true
) {
    if (!isOpen) return

    val feePct = if (isBuyMode) {
        if (isMakerOrder) fees.buyMakerPct else fees.buyTakerPct
    } else {
        if (isMakerOrder) fees.sellMakerPct else fees.sellTakerPct
    }
    val totalFeeIdr = orderAmountIdr * (feePct / 100.0)
    // Proporsi breakdown regulasi kripto & Bappebti:
    // Pajak: Jual = PPh 22 Final (0.10%), Beli = PPN (0.11%)
    val taxPct = if (isBuyMode) 0.11 else 0.10
    val taxIdr = (orderAmountIdr * (taxPct / 100.0)).coerceAtMost(totalFeeIdr)
    val cfxPct = 0.04
    val cfxFeeIdr = (orderAmountIdr * (cfxPct / 100.0)).coerceAtMost((totalFeeIdr - taxIdr).coerceAtLeast(0.0))
    val serviceFeeIdr = (totalFeeIdr - taxIdr - cfxFeeIdr).coerceAtLeast(0.0)
    val servicePct = (serviceFeeIdr / orderAmountIdr.coerceAtLeast(1.0)) * 100.0

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF0D1826),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3247)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Bar: Judul + Tombol Tutup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Rincian Biaya Transaksi",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.2.sp
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color(0xFF1A2B3D), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = Color(0xFFB0BEC5),
                            modifier = Modifier.size(16.dp)
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
                    // Biaya Layanan Bursa
                    FeeRowItem(
                        label = "Layanan Bursa (${String.format(java.util.Locale.US, "%.2f", servicePct)}%)",
                        value = PriceFormatter.formatPrice(serviceFeeIdr, quoteAsset = quoteAsset)
                    )

                    // Pajak Kripto Resmi
                    FeeRowItem(
                        label = if (isBuyMode) "Pajak PPN (0.11%)" else "Pajak PPh 22 Final (0.10%)",
                        value = PriceFormatter.formatPrice(taxIdr, quoteAsset = quoteAsset)
                    )

                    // Biaya CFX & Kliring
                    FeeRowItem(
                        label = "Kliring CFX (${String.format(java.util.Locale.US, "%.2f", cfxPct)}%)",
                        value = PriceFormatter.formatPrice(cfxFeeIdr, quoteAsset = quoteAsset)
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
                            text = PriceFormatter.formatPrice(totalFeeIdr, quoteAsset = quoteAsset),
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
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Persentase fee (${String.format(java.util.Locale.US, "%.2f", feePct)}%) disinkronkan dari menu Pengaturan (Settings > Fee Bursa).",
                        color = Color(0xFF81D4FA),
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }

                Spacer(Modifier.height(18.dp))

                // Tombol Mengerti / Selesai
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = "Mengerti",
                        color = Color(0xFF0B141E),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = value,
            color = Color(0xFFECEFF1),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
