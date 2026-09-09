package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import agu.analys.engine.global.GlobalMarketContext
import agu.analys.config.StrategyMode
import agu.analys.model.OrderBookItem
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale

@Composable
fun GlobalMarketShieldChip(
    context: GlobalMarketContext,
    symbol: String,
    isFavorite: Boolean,
    pairChange24h: Double,
    baseAsset: String = "",
    bids: List<OrderBookItem> = emptyList(),
    asks: List<OrderBookItem> = emptyList(),
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val totalBids = remember(bids) { bids.sumOf { it.amount } }
    val totalAsks = remember(asks) { asks.sumOf { it.amount } }
    val buyRatio = remember(totalBids, totalAsks) {
        if (totalBids + totalAsks > 0) totalBids / (totalBids + totalAsks) else 0.5
    }

    val auraColor = when {
        totalBids + totalAsks == 0.0 -> TvTextSecondary
        buyRatio >= 0.58 -> TvGreen
        buyRatio <= 0.42 -> TvRed
        else -> TvBlue
    }

    val statusLabel = when {
        totalBids + totalAsks == 0.0 -> "DEPTH SHIELD: OFF"
        buyRatio >= 0.58 -> "DEPTH SHIELD: STRONG"
        buyRatio <= 0.42 -> "DEPTH SHIELD: ALERT"
        else -> "DEPTH SHIELD: NORMAL"
    }

    val bidPct = (buyRatio * 100).toInt()
    val askPct = 100 - bidPct
    val orderbookText = "Bids: $bidPct% • Asks: $askPct%"

    val clickModifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier

    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .then(clickModifier)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = statusLabel,
            color = auraColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 12.sp,
            maxLines = 1
        )
        Text(
            text = orderbookText,
            color = TvTextSecondary,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
fun GlobalMarketShieldDialog(
    context: GlobalMarketContext,
    symbol: String,
    isFavorite: Boolean,
    baseAsset: String = "",
    bids: List<OrderBookItem> = emptyList(),
    asks: List<OrderBookItem> = emptyList(),
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    onDismiss: () -> Unit
) {
    val totalBids = remember(bids) { bids.sumOf { it.amount } }
    val totalAsks = remember(asks) { asks.sumOf { it.amount } }
    val buyRatio = remember(totalBids, totalAsks) {
        if (totalBids + totalAsks > 0) totalBids / (totalBids + totalAsks) else 0.5
    }

    val (icon, titleColor, statusText, statusDesc) = when {
        totalBids + totalAsks == 0.0 -> listOf(
            Icons.Default.Info,
            TvTextSecondary,
            "LIKUIDITAS DATA KOSONG",
            "Menunggu data order book / depth terisi oleh server Indodax."
        )
        buyRatio >= 0.58 -> listOf(
            Icons.Default.Security,
            TvGreen,
            "SUPPORT KUAT (BULLISH DEPTH)",
            "Dinding beli (Bid Wall) sangat dominan. Ada dukungan likuiditas yang siap menahan koreksi."
        )
        buyRatio <= 0.42 -> listOf(
            Icons.Default.Warning,
            TvRed,
            "TEKANAN TINGGI (BEARISH DEPTH)",
            "Dinding jual (Ask Wall) menumpuk tebal di atas. Potensi harga tertahan atau mengalami pullback."
        )
        else -> listOf(
            Icons.Default.Security,
            TvBlue,
            "LIKUIDITAS SEIMBANG (NETRAL DEPTH)",
            "Tekanan beli dan jual relatif seimbang. Harga cenderung konsolidasi / sideways jangka pendek."
        )
    }

    val maxBid = remember(bids) { bids.maxByOrNull { it.amount } }
    val maxAsk = remember(asks) { asks.maxByOrNull { it.amount } }

    val strategyGuide = remember(strategyMode) {
        when (strategyMode) {
            StrategyMode.SCALPING -> "Scalping sangat sensitif terhadap imbalance jangka pendek. Dukungan buy wall (bid > 55%) memberikan perlindungan instan untuk entri cepat 1-5 menit."
            StrategyMode.SECOND_WAVE -> "Konfirmasi wave kedua membutuhkan likuiditas tebal untuk menahan retracement. Bid wall yang kuat menyaring sinyal palsu agar entri lebih aman."
            StrategyMode.SWING -> "Aktivitas akumulasi jangka menengah biasanya ditandai dengan dinding bid di level harga psikologis. Hindari fomo jika ask wall terlalu tebal."
            StrategyMode.OFFICE_DAILY -> "Dinding beli (buy walls) tebal bertindak sebagai basis harga yang solid untuk melakukan akumulasi DCA secara teratur."
            StrategyMode.TRENCHING -> "Trading parit memanfaatkan liquidity pools di bid/ask untuk pasang jaring buy/sell limit secara presisi."
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TvBorder, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                            contentDescription = null,
                            tint = titleColor as Color,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "ORDERBOOK DEPTH SHIELD",
                            color = TvTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = TvTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Status banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background((titleColor as Color).copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .border(1.dp, (titleColor as Color).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = statusText as String,
                            color = titleColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = statusDesc as String,
                            color = TvTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Market Info rows
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvBackground, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Rasio Order Book", color = TvTextSecondary, fontSize = 11.sp)
                        val bidPct = (buyRatio * 100).toInt()
                        Text("Bids $bidPct% : Asks ${100 - bidPct}%", color = titleColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total Volume Bid (Beli)", color = TvTextSecondary, fontSize = 11.sp)
                        Text(
                            String.format(Locale.US, "%,.4f %s", totalBids, baseAsset.uppercase()),
                            color = TvGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total Volume Ask (Jual)", color = TvTextSecondary, fontSize = 11.sp)
                        Text(
                            String.format(Locale.US, "%,.4f %s", totalAsks, baseAsset.uppercase()),
                            color = TvRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (maxBid != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Dinding Beli Terbesar", color = TvTextSecondary, fontSize = 11.sp)
                            Text(
                                "${String.format(Locale.US, "%.3f", maxBid.amount)} @ ${PriceFormatter.formatPrice(maxBid.price)}",
                                color = TvGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (maxAsk != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Dinding Jual Terbesar", color = TvTextSecondary, fontSize = 11.sp)
                            Text(
                                "${String.format(Locale.US, "%.3f", maxAsk.amount)} @ ${PriceFormatter.formatPrice(maxAsk.price)}",
                                color = TvRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Strategy mode guidance
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            text = "💡 Panduan Mode ${strategyMode.name}",
                            color = TvTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = strategyGuide,
                            color = TvTextSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Tutup", color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun GlobalMarketShieldCard(
    bids: List<OrderBookItem> = emptyList(),
    asks: List<OrderBookItem> = emptyList(),
    strategyMode: StrategyMode = StrategyMode.SCALPING
) {
    val totalBids = remember(bids) { bids.sumOf { it.amount } }
    val totalAsks = remember(asks) { asks.sumOf { it.amount } }
    val buyRatio = remember(totalBids, totalAsks) {
        if (totalBids + totalAsks > 0) totalBids / (totalBids + totalAsks) else 0.5
    }

    val (icon, titleColor, statusText, statusDesc) = when {
        totalBids + totalAsks == 0.0 -> listOf(
            Icons.Default.Info,
            TvTextSecondary,
            "LIKUIDITAS KOSONG",
            "Menunggu data order book"
        )
        buyRatio >= 0.58 -> listOf(
            Icons.Default.Security,
            TvGreen,
            "SUPPORT KUAT (BULLISH)",
            "Dinding beli (Bid Wall) sangat dominan"
        )
        buyRatio <= 0.42 -> listOf(
            Icons.Default.Warning,
            TvRed,
            "TEKANAN TINGGI (BEARISH)",
            "Dinding jual (Ask Wall) menumpuk di atas"
        )
        else -> listOf(
            Icons.Default.Security,
            TvBlue,
            "SEIMBANG (NETRAL)",
            "Tekanan beli dan jual seimbang"
        )
    }

    AnalysisCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                contentDescription = null,
                tint = titleColor as Color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ORDERBOOK DEPTH SHIELD", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "• Mode ${strategyMode.name}",
                        color = TvTextSecondary.copy(alpha = 0.7f),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(statusText as String, color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Text(statusDesc as String, color = TvTextSecondary, fontSize = 10.sp)
            }
        }
    }
}
