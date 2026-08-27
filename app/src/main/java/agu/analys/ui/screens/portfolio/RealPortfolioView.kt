package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.ui.components.dashboard.AssetAvatar
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

enum class RealPortfolioTab(val title: String) {
    ASSETS("Aset"),
    OPEN_ORDERS("Antrean"),
    HISTORY("Riwayat")
}

/**
 * Tampilan khusus Portofolio Real (Indodax):
 * Memisahkan secara total data riil dari data simulasi.
 * Mengelola kartu saldo live, tab ber-Navigasi (Aset, Antrean, Riwayat), antrean order aktif dengan tombol cancel, riwayat transaksi, dan shortcut trade.
 */
@Composable
fun RealPortfolioView(
    isPinUnlocked: Boolean,
    realBalance: Map<String, Double>,
    realFreeBalance: Map<String, Double> = emptyMap(),
    realLockedBalance: Map<String, Double> = emptyMap(),
    realOpenOrders: List<RealOpenOrderEntity> = emptyList(),
    realTrades: List<RealTradeEntity> = emptyList(),
    realAvgBuyPrices: Map<String, Double> = emptyMap(),
    isFetchingRealBalance: Boolean,
    dashboardTicks: Map<String, MarketTick>,
    currentTick: MarketTick?,
    realTradeStatus: String? = null,
    onUnlockPin: () -> Unit,
    onRefreshRealBalance: () -> Unit,
    onCancelRealOrder: (String, String) -> Unit = { _, _ -> },
    onNavigateToDetail: (TradingPair) -> Unit,
    onSelectPair: (TradingPair) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedRealTab by remember { mutableStateOf(RealPortfolioTab.ASSETS) }

    val realIdr = realBalance["idr"] ?: 0.0
    val freeIdr = realFreeBalance["idr"] ?: realIdr
    val lockedIdr = realLockedBalance["idr"] ?: 0.0

    val realCoinItemsList = remember(realBalance, realFreeBalance, realLockedBalance, realAvgBuyPrices, dashboardTicks, currentTick) {
        realBalance.filter { it.key != "idr" && it.value > 0.00000001 }.entries.map { (coin, qty) ->
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
            val avgPrice = realAvgBuyPrices[coin] ?: 0.0
            val effectivePrice = if (price > 0.0) price else avgPrice
            val estVal = qty * effectivePrice
            val pnlIdr = if (avgPrice > 0.0) (effectivePrice - avgPrice) * qty else 0.0
            val pnlPct = if (avgPrice > 0.0) ((effectivePrice - avgPrice) / avgPrice) * 100.0 else 0.0

            val freeQty = realFreeBalance[coinLower] ?: qty
            val lockedQty = realLockedBalance[coinLower] ?: 0.0

            Pair(coinUpper, Triple(qty, freeQty, lockedQty)) to Pair(estVal, Triple(price, avgPrice, Pair(pnlIdr, pnlPct)))
        }.sortedByDescending { it.second.first }
    }
    
    val estTotalCryptoIdr = remember(realCoinItemsList) { realCoinItemsList.sumOf { it.second.first } }
    val totalRealPortfolioIdr = realIdr + estTotalCryptoIdr

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
                    colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
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
                    val bgColor = if (isError) TvRed.copy(alpha = 0.15f) else if (isSuccess) TvGreen.copy(alpha = 0.15f) else TvSurfaceVariant
                    val borderColor = if (isError) TvRed.copy(alpha = 0.5f) else if (isSuccess) TvGreen.copy(alpha = 0.5f) else TvBorder
                    val textColor = if (isError) TvRed else if (isSuccess) TvGreen else TvTextSecondary

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
                                tint = if (isError) TvRed else if (isSuccess) TvGreen else TvBlue,
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

            // UNLOCKED REAL PORTFOLIO SUMMARY CARD
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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

            // TAB SEGMENT: Aset | Antrean | Riwayat
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RealPortfolioTab.entries.forEach { tab ->
                        val isSelected = selectedRealTab == tab
                        val countText = when (tab) {
                            RealPortfolioTab.ASSETS -> if (realCoinItemsList.isNotEmpty()) " (${realCoinItemsList.size})" else ""
                            RealPortfolioTab.OPEN_ORDERS -> if (realOpenOrders.isNotEmpty()) " (${realOpenOrders.size})" else ""
                            RealPortfolioTab.HISTORY -> if (realTrades.isNotEmpty()) " (${realTrades.size})" else ""
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) TvCardBackground else Color.Transparent)
                                .clickable { selectedRealTab = tab }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${tab.title}$countText",
                                color = if (isSelected) TvBlue else TvTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // KONTEN TAB REAL PORTOFOLIO
            when (selectedRealTab) {
                RealPortfolioTab.ASSETS -> {
                    if (realCoinItemsList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TvCardBackground, RoundedCornerShape(10.dp))
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
                        items(realCoinItemsList) { itemData ->
                            val (coinUpper, qtyTriple) = itemData.first
                            val (qty, freeQty, lockedQty) = qtyTriple
                            val (estVal, details) = itemData.second
                            val (price, avgPrice, pnlPair) = details
                            val (pnlIdr, pnlPct) = pnlPair

                            val symbol = "${coinUpper}IDR"
                            val pair = TradingPair.fromCustomSymbol(symbol, "IDR")
                            
                            val pnlColor = if (pnlIdr > 0) TvGreen else if (pnlIdr < 0) TvRed else TvTextSecondary
                            val pnlPrefix = if (pnlIdr > 0) "+" else ""

                            Card(
                                modifier = Modifier.fillMaxWidth(),
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
                                                    Text("Free: $freeQty", color = TvGreen, fontSize = 9.5.sp)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Locked: $lockedQty", color = TvRed, fontSize = 9.5.sp)
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
                                                Text("Avg Buy Price", color = TvTextSecondary, fontSize = 9.sp)
                                                Text(PriceFormatter.formatPrice(avgPrice), color = TvTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("Current Price", color = TvTextSecondary, fontSize = 9.sp)
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
                    }
                }

                RealPortfolioTab.OPEN_ORDERS -> {
                    if (realOpenOrders.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TvCardBackground, RoundedCornerShape(10.dp))
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Tidak ada antrean order aktif di Indodax.",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        items(realOpenOrders) { order ->
                            val orderSymbolUpper = order.symbol.uppercase()
                            val orderSideColor = if (order.side.equals("BUY", true)) TvGreen else TvRed
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = orderSideColor.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = order.side,
                                                    color = orderSideColor,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = orderSymbolUpper.replace("IDR", "/IDR"),
                                                color = TvTextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "Harga: ${PriceFormatter.formatPrice(order.price)}",
                                            color = TvTextSecondary,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = "Jumlah: ${order.quantity} | Executed: ${order.executedQty}",
                                            color = TvTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }

                                    // Tombol Cancel Order Real
                                    Button(
                                        onClick = { onCancelRealOrder(order.symbol, order.orderId) },
                                        modifier = Modifier.height(34.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = TvRed),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Batalkan Order",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Cancel",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                RealPortfolioTab.HISTORY -> {
                    if (realTrades.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TvCardBackground, RoundedCornerShape(10.dp))
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Belum ada riwayat transaksi ter-cache (Mulai terkumpul seiring refresh/trade).",
                                    color = TvTextSecondary,
                                    fontSize = 10.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        items(realTrades) { trade ->
                            val symbolUpper = trade.symbol.uppercase()
                            val sideColor = if (trade.isBuyer) TvGreen else TvRed
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = sideColor.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = trade.side,
                                                    color = sideColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = symbolUpper.replace("IDR", "/IDR"),
                                                color = TvTextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        val dateFormat = remember { java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()) }
                                        val dateStr = remember(trade.time) { dateFormat.format(java.util.Date(trade.time)) }
                                        Text(
                                            text = dateStr,
                                            color = TvTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "Total: ${PriceFormatter.formatPrice(trade.amount)}",
                                            color = TvTextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "@ ${PriceFormatter.formatPrice(trade.price)} (${trade.qty})",
                                            color = TvTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
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
                    colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = TvBlue, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("INFORMASI DEPOSIT & WITHDRAW", color = TvBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
