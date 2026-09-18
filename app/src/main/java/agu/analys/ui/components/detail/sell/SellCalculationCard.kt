package agu.analys.ui.components.detail.sell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    bestBidPrice: Double? = null,
    bestBidAmount: Double = 0.0,
    isMakerOrder: Boolean = false,
    customBuyPriceInput: String = "",
    onCustomBuyPriceInputChange: (String) -> Unit = {},
    onOpenFeeDetail: (() -> Unit)? = null
) {
    val displayBestPrice = bestBidPrice?.takeIf { it > 0.0 } ?: validPrice

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvCardBackground, RoundedCornerShape(14.dp))
            .border(1.dp, TvBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. KARTU HARGA TERBAIK INDODAX (BEST MARKET ORDER / BEST BID)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0C1929))
                .border(1.dp, TvBlue.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "BEST BID INDODAX",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                if (!isMakerOrder) TvGreen.copy(alpha = 0.2f) else TvBlue.copy(alpha = 0.2f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (!isMakerOrder) "INSTANT SELL" else "LIMIT SELL",
                            color = if (!isMakerOrder) TvGreen else TvBlue,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "Rp ${PriceFormatter.formatIdrNumber(displayBestPrice)}",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (bestBidAmount > 0.0) {
                            Text(
                                text = "Antrean beli teratas: ${PriceFormatter.formatCryptoExact(bestBidAmount, 4)} $baseAsset",
                                color = TvTextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    if (validPrice > 0.0 && validPrice != displayBestPrice) {
                        Text(
                            text = "Last: Rp ${PriceFormatter.formatIdrNumber(validPrice)}",
                            color = TvTextMuted,
                            fontSize = 10.5.sp
                        )
                    }
                }

                Text(
                    text = if (!isMakerOrder) {
                        "⚡ Market Order Instan langsung mencocokkan pembeli antrean teratas tanpa perlu antre di buku order."
                    } else {
                        "⏱ Limit Order akan antre di buku order pada harga yang ditentukan."
                    },
                    color = Color(0xFF8BA2B8),
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            }
        }

        // 2. RINCIAN PERHITUNGAN TRANSAKSI (HARGA TERJUAL, KOIN, GROSS)
        TransactionDetailRow(
            label = "Harga Eksekusi Terjual",
            value = "Rp ${PriceFormatter.formatIdrNumber(displayBestPrice)} / $baseAsset"
        )
        TransactionDetailRow(
            label = "Jumlah Koin Dijual",
            value = "${PriceFormatter.formatCryptoExact(activeSellQty, 8)} $baseAsset",
            subValue = "= Rp ${PriceFormatter.formatIdrNumber(grossSellValueIdr)} (Kotor)"
        )

        // POTONGAN FEE & PAJAK
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Potongan Biaya & Pajak (${String.format(Locale.US, "%.2f", activeFeePct)}%)",
                        color = TvTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (onOpenFeeDetail != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Rincian Biaya & Pajak",
                            tint = TvBlue,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onOpenFeeDetail() }
                        )
                    }
                }
                Text(
                    text = "PPh 22 Final (0.10%), CFX Kliring & Fee Bursa",
                    color = TvTextMuted,
                    fontSize = 9.5.sp
                )
            }
            Text(
                text = "- Rp ${PriceFormatter.formatIdrNumber(sellFeeIdr)}",
                color = TvRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        HorizontalDivider(color = TvBorder, thickness = 1.dp, modifier = Modifier.padding(vertical = 2.dp))

        // 3. HASIL BERSIH MASUK SALDO (NET PROCEEDS)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF131D2A))
                .border(1.dp, Color(0xFF22364E), RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HASIL BERSIH MASUK SALDO",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Kas IDR bersih yang langsung masuk dompet",
                        color = TvTextSecondary,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "Rp ${PriceFormatter.formatIdrNumber(netReceivedSellIdr)}",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // 4. ANALISIS UNTUNG ATAU RUGI (PROFIT / LOSS)
        if (effectiveBuyPrice > 0.0) {
            TransactionDetailRow(
                label = "Total Modal Pembelian",
                value = "Rp ${PriceFormatter.formatIdrNumber(costBasisIdr)}",
                subValue = "(@ Rp ${PriceFormatter.formatIdrNumber(effectiveBuyPrice)} / koin)"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isProfitable) TvGreen.copy(alpha = 0.12f) else TvRed.copy(alpha = 0.12f)
                    )
                    .border(
                        1.5.dp,
                        if (isProfitable) TvGreen.copy(alpha = 0.6f) else TvRed.copy(alpha = 0.6f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isProfitable) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                contentDescription = null,
                                tint = if (isProfitable) TvGreen else TvRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isProfitable) "JUAL UNTUNG (PROFIT)" else "JUAL RUGI (CUT LOSS)",
                                color = if (isProfitable) TvGreen else TvRed,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Text(
                            text = if (isProfitable) {
                                "Hasil bersih melebihi modal (sudah bersih fee & pajak)"
                            } else {
                                "Hasil bersih di bawah modal (termasuk fee & pajak)"
                            },
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${if (isProfitable) "+" else ""}Rp ${PriceFormatter.formatIdrNumber(netProfitIdr)}",
                            color = if (isProfitable) TvGreen else TvRed,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "${if (isProfitable) "+" else ""}${String.format(Locale.US, "%.2f", netProfitPct)}%",
                            color = if (isProfitable) TvGreen else TvRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // Jika modal belum tersimpan di portofolio, sediakan opsi input modal agar user bisa melihat perhitungan Untung/Rugi secara langsung
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                    .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Calculate,
                        contentDescription = null,
                        tint = TvBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Cek Untung / Rugi (Input Modal)",
                        color = TvTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Masukkan harga beli/modal koin Anda untuk melihat estimasi profit/rugi bersih penjualan:",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
                OutlinedTextField(
                    value = customBuyPriceInput,
                    onValueChange = onCustomBuyPriceInputChange,
                    placeholder = { Text("Contoh: 50000000 (Harga beli per koin)", fontSize = 11.sp, color = TvTextMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvBlue,
                        unfocusedBorderColor = TvBorder,
                        focusedContainerColor = TvSurface,
                        unfocusedContainerColor = TvSurface,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                )
            }
        }
    }
}

