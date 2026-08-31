package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun RealPortfolioSummaryCard(
    totalRealPortfolioIdr: Double,
    realIdr: Double,
    freeIdr: Double,
    lockedIdr: Double,
    estTotalCryptoIdr: Double,
    isFetchingRealBalance: Boolean,
    onRefreshRealBalance: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvGreen.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, null, tint = TvGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "SALDO REAL INDODAX",
                        color = TvGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                if (isFetchingRealBalance) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TvGreen, strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = onRefreshRealBalance,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = TvTextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = PriceFormatter.formatPrice(totalRealPortfolioIdr),
                color = TvTextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Estimasi Total Aset (Cash IDR + Koin Kripto)",
                color = TvTextSecondary,
                fontSize = 10.sp
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = TvBorder)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("SALDO CASH IDR", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text(PriceFormatter.formatPrice(realIdr), color = TvGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text("Free: ", color = TvTextSecondary, fontSize = 9.sp)
                        Text(PriceFormatter.formatPrice(freeIdr), color = TvGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("Locked: ", color = TvTextSecondary, fontSize = 9.sp)
                        Text(PriceFormatter.formatPrice(lockedIdr), color = TvRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("ESTIMASI KOIN", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text(PriceFormatter.formatPrice(estTotalCryptoIdr), color = TvBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
