package agu.analys.ui.components.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.model.TradingPair
import agu.analys.ui.animation.AnimatedPercentageBadge
import agu.analys.ui.animation.SmoothPriceText
import agu.analys.ui.components.dashboard.AssetAvatar
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun DetailTopBar(
    pair: TradingPair,
    isFavorite: Boolean,
    onNavigateToDashboard: () -> Unit,
    onOpenSimulation: () -> Unit,
    onOpenLearning: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onOpenLandscapeChart: () -> Unit,
    activeAlertCount: Int = 0,
    onOpenAlerts: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateToDashboard) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Kembali",
                tint = TvTextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        AssetAvatar(baseAsset = pair.baseAsset, iconUrl = pair.iconUrl, size = 36.dp)
        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
        ) {
            Text(
                text = "${pair.baseAsset}/${pair.quoteAsset}",
                color = TvTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = getCoinFullName(pair.baseAsset),
                color = TvTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((-4).dp)
        ) {
            if (onOpenAlerts != null) {
                Box(contentAlignment = Alignment.TopEnd) {
                    IconButton(
                        onClick = onOpenAlerts,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (activeAlertCount > 0) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                            contentDescription = "Alert Pasar",
                            tint = if (activeAlertCount > 0) Color(0xFF00E5FF) else TvTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (activeAlertCount > 0) {
                        Box(
                            modifier = Modifier
                                .offset(x = (-2).dp, y = 2.dp)
                                .background(Color(0xFF00E5FF), RoundedCornerShape(10.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "$activeAlertCount",
                                color = Color.Black,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onOpenSimulation,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                    contentDescription = "Simulasi Trade",
                    tint = TvGreen,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenLearning,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = "Mode Belajar",
                    tint = Color(0xFF72B7FF),
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onToggleWatchlist,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) Color(0xFFFFB300) else TvTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenLandscapeChart,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CropRotate,
                    contentDescription = "Landscape",
                    tint = TvGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun DetailPriceHeader(
    price: Double,
    change24h: Double,
    activityText: String,
    activityColor: Color,
    quoteAsset: String = "IDR"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            if (price > 0) {
                SmoothPriceText(
                    price = price,
                    color = TvTextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    quoteAsset = quoteAsset
                )
            } else {
                val placeholder = if (quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)) "$ —" else "Rp —"
                Text(placeholder, color = TvTextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(activityColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .border(1.dp, activityColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = activityText,
                    color = activityColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AnimatedPercentageBadge(percentage = change24h)
    }
}

fun getCoinFullName(symbol: String): String = when (symbol.uppercase()) {
    "BTC" -> "Bitcoin"
    "ETH" -> "Ethereum"
    "SOL" -> "Solana"
    "XRP" -> "Ripple"
    "DOGE" -> "Dogecoin"
    "ADA" -> "Cardano"
    "BNB" -> "BNB"
    "USDT" -> "Tether"
    "BIDR" -> "Binance IDR"
    "PEPE" -> "Pepe"
    "SHIB" -> "Shiba Inu"
    "SUI" -> "Sui Network"
    "AVAX" -> "Avalanche"
    "DOT" -> "Polkadot"
    "NEAR" -> "Near Protocol"
    "LINK" -> "Chainlink"
    "TRX" -> "TRON"
    "RED" -> "RedStone"
    "EDEN" -> "OpenEden"
    "GPS" -> "GoPlus Security"
    "CARV" -> "CARV"
    "COMP" -> "Compound"
    "POL" -> "Polygon Ecosystem"
    "EGLD" -> "MultiversX"
    "HEMI" -> "Hemi Network"
    "SOON" -> "SOON"
    "KOM" -> "Kommunitas"
    "MORPHO" -> "Morpho"
    "IO" -> "io.net"
    "ONDO" -> "Ondo Finance"
    "TIA" -> "Celestia"
    "SEI" -> "Sei Network"
    "RENDER" -> "Render"
    "FET" -> "Artificial Superintelligence"
    "INJ" -> "Injective"
    "TAO" -> "Bittensor"
    "WIF" -> "dogwifhat"
    "BONK" -> "Bonk"
    "FLOKI" -> "Floki"
    "JUP" -> "Jupiter"
    "PYTH" -> "Pyth Network"
    "STRK" -> "Starknet"
    "ARB" -> "Arbitrum"
    "OP" -> "Optimism"
    "KAS" -> "Kaspa"
    "PENDLE" -> "Pendle"
    "JTO" -> "Jito"
    "ENA" -> "Ethena"
    "W" -> "Wormhole"
    else -> symbol
}

fun openExchange(context: Context, source: MarketDataSource = MarketDataSource.INDODAX) {
    val packageCandidates = listOf("id.co.bitcoin")
    val appName = "Indodax"

    var launchIntent: Intent? = null
    for (pkg in packageCandidates) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            launchIntent = intent
            break
        }
    }

    if (launchIntent != null) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    } else {
        val primaryPackage = packageCandidates.first()
        val playStoreUri = Uri.parse("market://details?id=$primaryPackage")
        val webStoreUri = Uri.parse("https://play.google.com/store/apps/details?id=$primaryPackage")
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, playStoreUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
        } catch (_: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, webStoreUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (_: Exception) {
                Toast.makeText(context, "Aplikasi $appName belum terpasang di HP ini.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

fun openIndodax(context: Context) = openExchange(context, MarketDataSource.INDODAX)
