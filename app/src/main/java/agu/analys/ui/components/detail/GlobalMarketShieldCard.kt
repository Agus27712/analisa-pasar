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
import agu.analys.engine.global.GlobalRegime
import agu.analys.ui.theme.*

@Composable
fun GlobalMarketShieldChip(
    context: GlobalMarketContext,
    symbol: String,
    isFavorite: Boolean,
    pairChange24h: Double,
    baseAsset: String = "",
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val auraColor = when {
        !context.isConnected -> TvTextSecondary
        context.isVetoActive -> TvRed
        context.regime == GlobalRegime.BULLISH -> TvGreen
        context.regime == GlobalRegime.BEARISH -> TvAmber
        else -> TvBlue
    }

    val statusLabel = when {
        !context.isConnected -> "OFFLINE"
        context.isVetoActive -> "VETO FLASH CRASH"
        context.regime == GlobalRegime.BULLISH -> "BULLISH"
        context.regime == GlobalRegime.BEARISH -> "BEARISH"
        else -> "SIDEWAYS"
    }

    val resolvedBase = if (baseAsset.isNotBlank()) baseAsset.uppercase() else {
        if (symbol.endsWith("IDR", ignoreCase = true)) {
            symbol.substring(0, symbol.length - 3).uppercase()
        } else if (symbol.endsWith("USDT", ignoreCase = true)) {
            symbol.substring(0, symbol.length - 4).uppercase()
        } else symbol.uppercase()
    }

    val activeTicker = context.activeCoinTicker
    val binanceText = remember(activeTicker, resolvedBase, context.isConnected, context.btc24hChangePct) {
        if (!context.isConnected) {
            "Menghubungkan..."
        } else if (activeTicker != null && activeTicker.baseAsset.equals(resolvedBase, ignoreCase = true)) {
            if (activeTicker.isAvailable) {
                val sign = if (activeTicker.changePct24h >= 0) "+" else ""
                val formattedPct = String.format(java.util.Locale.US, "%.2f", activeTicker.changePct24h)
                "Binance $resolvedBase $sign$formattedPct%"
            } else {
                val sign = if (context.btc24hChangePct >= 0) "+" else ""
                val btcPct = String.format(java.util.Locale.US, "%.2f", context.btc24hChangePct)
                "Binance BTC $sign$btcPct%"
            }
        } else {
            val sign = if (context.btc24hChangePct >= 0) "+" else ""
            val btcPct = String.format(java.util.Locale.US, "%.2f", context.btc24hChangePct)
            "Binance $resolvedBase • BTC $sign$btcPct%"
        }
    }

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
            text = binanceText,
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
    onDismiss: () -> Unit
) {
    val (icon, titleColor, statusText, statusDesc) = when {
        !context.isConnected -> listOf(
            Icons.Default.Info,
            TvTextSecondary,
            "Menghubungkan...",
            "Mengambil data Global Market BTC"
        )
        context.isVetoActive -> listOf(
            Icons.Default.Warning,
            TvRed,
            "VETO (FLASH CRASH)",
            context.vetoReason ?: "Terdeteksi badai market global"
        )
        context.regime == GlobalRegime.BULLISH -> listOf(
            Icons.Default.Security,
            TvGreen,
            "AMAN (BULLISH)",
            "Kondisi global mendukung kenaikan harga altcoin"
        )
        context.regime == GlobalRegime.BEARISH -> listOf(
            Icons.Default.Security,
            TvAmber,
            "STANDBY (BEARISH)",
            "Market global sedang tertekan, tetap waspada & disiplin SL"
        )
        else -> listOf(
            Icons.Default.Security,
            TvBlue,
            "STANDBY (SIDEWAYS)",
            "Market global relatif stabil / netral"
        )
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
                            "GLOBAL MARKET SHIELD",
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
                    if (!isFavorite) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Mode Analisa", color = TvTextSecondary, fontSize = 11.sp)
                            Text("BTC (Non-Fav)", color = TvAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Data Source", color = TvTextSecondary, fontSize = 11.sp)
                        val sourceBadge = if (context.isConnected) {
                            if (context.dataSource.startsWith("Binance")) "Binance (Live Stream)" else "Indodax (Fallback)"
                        } else {
                            "Terputus"
                        }
                        Text(sourceBadge, color = if (context.isConnected) TvGreen else TvRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    if (context.btcPriceUsdt > 0.0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Harga BTC Global", color = TvTextSecondary, fontSize = 11.sp)
                            Text(
                                "$${String.format(java.util.Locale.US, "%,.2f", context.btcPriceUsdt)}",
                                color = TvTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Perubahan 24 Jam", color = TvTextSecondary, fontSize = 11.sp)
                            val sign = if (context.btc24hChangePct > 0) "+" else ""
                            Text(
                                "$sign${String.format(java.util.Locale.US, "%.2f", context.btc24hChangePct)}%",
                                color = if (context.btc24hChangePct >= 0) TvGreen else TvRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    val activeTicker = context.activeCoinTicker
                    if (activeTicker != null && activeTicker.isAvailable && activeTicker.price > 0.0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Binance ${activeTicker.baseAsset}", color = TvTextSecondary, fontSize = 11.sp)
                            val coinSign = if (activeTicker.changePct24h >= 0) "+" else ""
                            Text(
                                "$coinSign${String.format(java.util.Locale.US, "%.2f", activeTicker.changePct24h)}%",
                                color = if (activeTicker.changePct24h >= 0) TvGreen else TvRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Proteksi Badai/Crash", color = TvTextSecondary, fontSize = 11.sp)
                        Text(
                            if (context.isVetoActive) "DIBLOKIR (Veto)" else "Aman",
                            color = if (context.isVetoActive) TvRed else TvGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

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
fun GlobalMarketShieldCard(context: GlobalMarketContext) {
    val (icon, titleColor, statusText, statusDesc) = when {
        !context.isConnected -> listOf(
            Icons.Default.Info,
            TvTextSecondary,
            "Menghubungkan...",
            "Mengambil data Global Market"
        )
        context.isVetoActive -> listOf(
            Icons.Default.Warning,
            TvRed,
            "VETO (FLASH CRASH)",
            context.vetoReason ?: "Terdeteksi badai market global"
        )
        context.regime == GlobalRegime.BULLISH -> listOf(
            Icons.Default.Security,
            TvGreen,
            "AMAN (BULLISH)",
            "Kondisi global mendukung kenaikan"
        )
        context.regime == GlobalRegime.BEARISH -> listOf(
            Icons.Default.Security,
            TvAmber,
            "STANDBY (BEARISH)",
            "Global sedang turun, berhati-hati"
        )
        else -> listOf(
            Icons.Default.Security,
            TvBlue,
            "STANDBY (SIDEWAYS)",
            "Market global relatif stabil"
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
                    Text("GLOBAL MARKET SHIELD", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    val sourceBadge = if (context.isConnected) {
                        if (context.dataSource.startsWith("Binance")) "• Binance" else "• Indodax (Fallback)"
                    } else {
                        "• Terputus"
                    }
                    Text(
                        sourceBadge,
                        color = if (context.isConnected) TvTextSecondary.copy(alpha = 0.7f) else TvRed,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    if (context.btcPriceUsdt > 0.0) {
                        Text(
                            "BTC: $${String.format(java.util.Locale.US, "%,.2f", context.btcPriceUsdt)} (${if(context.btc24hChangePct > 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", context.btc24hChangePct)}%)",
                            color = if (context.btc24hChangePct >= 0) TvGreen else TvRed,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(statusText as String, color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Text(statusDesc as String, color = TvTextSecondary, fontSize = 10.sp)
            }
        }
    }
}
