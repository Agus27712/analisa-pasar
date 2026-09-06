package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.TradingPair
import agu.analys.ui.components.dashboard.AssetAvatar
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun RealPortfolioAssetItem(
    coinUpper: String,
    qty: Double,
    freeQty: Double,
    lockedQty: Double,
    estVal: Double,
    price: Double,
    avgPrice: Double,
    pnlIdr: Double,
    pnlPct: Double,
    onSelectPair: (TradingPair) -> Unit,
    onNavigateToDetail: (TradingPair) -> Unit,
    modifier: Modifier = Modifier
) {
    val symbol = "${coinUpper}IDR"
    val pair = TradingPair.fromCustomSymbol(symbol, "IDR")
    val pnlColor = if (pnlIdr > 0) TvGreen else if (pnlIdr < 0) TvRed else TvTextSecondary
    val pnlPrefix = if (pnlIdr > 0) "+" else ""

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetAvatar(baseAsset = coinUpper, size = 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(coinUpper, color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Total: $qty $coinUpper", color = TvTextSecondary, fontSize = 11.sp)
                        Row {
                            Text("Tersedia: $freeQty", color = TvGreen, fontSize = 9.5.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Terkunci: $lockedQty", color = TvRed, fontSize = 9.5.sp)
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        PriceFormatter.formatPrice(estVal),
                        color = TvTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (avgPrice > 0.0) {
                        Text(
                            "$pnlPrefix${PriceFormatter.formatPrice(pnlIdr)} ($pnlPrefix${String.format("%.2f", pnlPct)}%)",
                            color = pnlColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            if (price > 0) "@ ${PriceFormatter.formatPrice(price)}" else "Ticker Menunggu",
                            color = TvBlue,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            
            if (avgPrice > 0.0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Rata-rata Harga Beli", color = TvTextSecondary, fontSize = 9.sp)
                        Text(PriceFormatter.formatPrice(avgPrice), color = TvTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Harga Saat Ini", color = TvTextSecondary, fontSize = 9.sp)
                        Text(if (price > 0) PriceFormatter.formatPrice(price) else "-", color = TvBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Action Buttons for Real Coin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onSelectPair(pair)
                        onNavigateToDetail(pair)
                    },
                    modifier = Modifier.weight(1f).height(32.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvBlue),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.ShowChart, null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Chart", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        onSelectPair(pair)
                        onNavigateToDetail(pair)
                    },
                    modifier = Modifier.weight(1f).height(32.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvRed),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Jual / Trade", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}
