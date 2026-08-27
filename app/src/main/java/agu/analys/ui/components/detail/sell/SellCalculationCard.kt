package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import agu.analys.ui.components.detail.TransactionDetailRow
import java.util.Locale

@Composable
fun SellCalculationCard(
    validPrice: Double,
    baseAsset: String,
    quoteAsset: String,
    activeSellQty: Double,
    grossSellValueIdr: Double,
    effectiveBuyPrice: Double,
    costBasisIdr: Double,
    sellFeeIdr: Double,
    activeFeePct: Double,
    netReceivedSellIdr: Double,
    isProfitable: Boolean,
    netProfitIdr: Double,
    netProfitPct: Double,
    onManualBuyClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvCardBackground, RoundedCornerShape(10.dp))
            .border(0.5.dp, TvBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TransactionDetailRow(
            label = "Harga Jual Sekarang",
            value = "${PriceFormatter.formatIdrNumber(validPrice)} $quoteAsset"
        )
        TransactionDetailRow(
            label = "Jumlah Koin Dijual",
            value = "${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset",
            subValue = "= ${PriceFormatter.formatIdrNumber(grossSellValueIdr)} $quoteAsset (Kotor)"
        )
        if (effectiveBuyPrice > 0.0) {
            TransactionDetailRow(
                label = "Modal Pembelian",
                value = "${PriceFormatter.formatIdrNumber(costBasisIdr)} $quoteAsset",
                subValue = "(@ ${PriceFormatter.formatIdrNumber(effectiveBuyPrice)})"
            )
        }
        TransactionDetailRow(
            label = "Biaya Fee (${String.format(Locale.US, "%.2f", activeFeePct)}%)",
            value = "- ${PriceFormatter.formatIdrNumber(sellFeeIdr)} $quoteAsset",
            valueColor = TvRed
        )

        HorizontalDivider(color = TvBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

        // Diterima Bersih Kas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hasil Kas Diterima",
                color = TvTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${PriceFormatter.formatIdrNumber(netReceivedSellIdr)} $quoteAsset",
                color = TvTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(4.dp))

        // Highlight HASIL KEUNTUNGAN / KERUGIAN BERSIH
        if (effectiveBuyPrice > 0.0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isProfitable) TvGreen.copy(alpha = 0.15f) else TvRed.copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (isProfitable) TvGreen else TvRed,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isProfitable) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                contentDescription = null,
                                tint = if (isProfitable) TvGreen else TvRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isProfitable) "KEUNTUNGAN BERSIH (NET PROFIT)" else "KERUGIAN / CUT LOSS NET",
                                color = if (isProfitable) TvGreen else TvRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Sudah dipotong fee (${String.format(Locale.US, "%.2f", activeFeePct)}%)",
                            color = TvTextSecondary,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${if (isProfitable) "+" else ""}${PriceFormatter.formatIdrNumber(netProfitIdr)} $quoteAsset",
                            color = if (isProfitable) TvGreen else TvRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "(${if (isProfitable) "+" else ""}${String.format(Locale.US, "%.2f", netProfitPct)}%)",
                            color = if (isProfitable) TvGreen else TvRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            // Banner ajakan input manual (>7 Hari) jika harga beli belum ada
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                    .border(1.dp, TvAmber.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Histori >7 Hari Tidak Tersedia di API",
                            color = TvAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Set harga beli manual dari nota Anda agar Unrealized PnL (+/-) bisa dihitung.",
                            color = TvTextSecondary,
                            fontSize = 9.sp,
                            lineHeight = 12.sp
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    Button(
                        onClick = onManualBuyClick,
                        colors = ButtonDefaults.buttonColors(containerColor = TvAmber, contentColor = Color.Black),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("+ Input Manual", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
