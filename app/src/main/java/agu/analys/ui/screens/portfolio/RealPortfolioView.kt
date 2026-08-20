package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.ui.components.dashboard.AssetAvatar
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

/**
 * Tampilan khusus Portofolio Real (Indodax):
 * Memisahkan secara total data riil dari data simulasi.
 * Mengelola kartu saldo live, daftar aset koin kripto di akun Indodax, dan shortcut trade.
 */
@Composable
fun RealPortfolioView(
    isPinUnlocked: Boolean,
    realBalance: Map<String, Double>,
    isFetchingRealBalance: Boolean,
    dashboardTicks: Map<String, MarketTick>,
    currentTick: MarketTick?,
    realTradeStatus: String? = null,
    onUnlockPin: () -> Unit,
    onRefreshRealBalance: () -> Unit,
    onNavigateToDetail: (TradingPair) -> Unit,
    onSelectPair: (TradingPair) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isPinUnlocked) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1722)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(TvGreen.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = TvGreen,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "PORTOFOLIO REAL TERKUNCI",
                            color = TvTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Masukkan PIN Keamanan untuk melihat rincian saldo & aset Indodax riil Anda.",
                            color = TvTextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onUnlockPin,
                            colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(16.dp), tint = Color.Black)
                            Spacer(Modifier.width(6.dp))
                            Text("Buka Access Portofolio Real", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            // STATUS BANNER (IF ANY)
            if (!realTradeStatus.isNullOrBlank()) {
                item {
                    val isSuccess = realTradeStatus.contains("berhasil", ignoreCase = true) || realTradeStatus.contains("success", ignoreCase = true)
                    val isError = realTradeStatus.contains("error", ignoreCase = true) || realTradeStatus.contains("gagal", ignoreCase = true) || realTradeStatus.contains("invalid", ignoreCase = true)
                    val bgColor = if (isError) Color(0xFF2C1518) else if (isSuccess) Color(0xFF0F261C) else Color(0xFF131D2A)
                    val borderColor = if (isError) TvRed.copy(alpha = 0.5f) else if (isSuccess) TvGreen.copy(alpha = 0.5f) else Color(0xFF1E2836)
                    val textColor = if (isError) Color(0xFFFF8B8B) else if (isSuccess) Color(0xFF8BFFC7) else TvTextSecondary

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = bgColor),
                        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isError) TvRed else if (isSuccess) TvGreen else Color(0xFF72B7FF),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = realTradeStatus,
                                color = textColor,
                                fontSize = 10.5.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // UNLOCKED REAL PORTFOLIO
            item {
                val realIdr = realBalance["idr"] ?: 0.0
                val realCoinItems = remember(realBalance, dashboardTicks, currentTick) {
                    realBalance.filter { it.key != "idr" && it.value > 0.00000001 }.map { (coin, qty) ->
                        val coinUpper = coin.uppercase()
                        val coinLower = coin.lowercase()
                        val symbol = "${coinUpper}IDR"
                        val price = when {
                            symbol.equals(currentTick?.symbol, ignoreCase = true) -> currentTick?.price ?: 0.0
                            dashboardTicks.containsKey(symbol) -> dashboardTicks[symbol]?.price ?: 0.0
                            dashboardTicks.containsKey("${coinLower}_idr") -> dashboardTicks["${coinLower}_idr"]?.price ?: 0.0
                            dashboardTicks.containsKey(coinUpper) -> dashboardTicks[coinUpper]?.price ?: 0.0
                            else -> 0.0
                        }
                        val estIdr = qty * price
                        Pair(coinUpper, Pair(qty, estIdr))
                    }.sortedByDescending { it.second.second }
                }
                val estTotalCryptoIdr = realCoinItems.sumOf { it.second.second }
                val totalRealPortfolioIdr = realIdr + estTotalCryptoIdr

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1826)),
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
                        HorizontalDivider(color = Color(0xFF1E2836))
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("SALDO CASH IDR", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                Text(PriceFormatter.formatPrice(realIdr), color = TvGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("ESTIMASI KOIN", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                Text(PriceFormatter.formatPrice(estTotalCryptoIdr), color = Color(0xFF72B7FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // REAL HOLDINGS ITEM LIST
            item {
                Text(
                    "ASET KRIPTO DI INDODAX",
                    color = TvTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            val realCoinItemsList = realBalance.filter { it.key != "idr" && it.value > 0.00000001 }.entries.toList()
            if (realCoinItemsList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF101720), RoundedCornerShape(10.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Belum ada aset koin kripto terdeteksi di akun Indodax.",
                            color = TvTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                items(realCoinItemsList) { (coin, qty) ->
                    val coinUpper = coin.uppercase()
                    val symbol = "${coinUpper}IDR"
                    val price = when {
                        symbol.equals(currentTick?.symbol, ignoreCase = true) -> currentTick?.price ?: 0.0
                        dashboardTicks.containsKey(symbol) -> dashboardTicks[symbol]?.price ?: 0.0
                        else -> 0.0
                    }
                    val estVal = qty * price
                    val pair = TradingPair.fromCustomSymbol(symbol, "IDR")

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
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
                                        Text("Saldo: $qty $coinUpper", color = TvTextSecondary, fontSize = 11.sp)
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        PriceFormatter.formatPrice(estVal),
                                        color = TvTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        if (price > 0) "@ ${PriceFormatter.formatPrice(price)}" else "Ticker Menunggu",
                                        color = Color(0xFF72B7FF),
                                        fontSize = 10.sp
                                    )
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
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF72B7FF)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836)),
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
            }

            // TOP-UP & WITHDRAW NOTICE CARD FOR REAL MODE
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B2A)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = Color(0xFF72B7FF), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("INFORMASI DEPOSIT & WITHDRAW", color = Color(0xFF72B7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Akses API Indodax dikhususkan untuk Mode Trade saja. Untuk melakukan Top-Up Rupiah atau Penarikan Dana (Withdraw), silakan gunakan aplikasi atau website resmi Indodax.",
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}
