package agu.analys.ui.components.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.model.TradingPair
import agu.analys.ui.animation.AnimatedPercentageBadge
import agu.analys.ui.animation.FlipCardPriceText
import agu.analys.ui.components.dashboard.AssetAvatar
import agu.analys.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DetailTopBar(
    pair: TradingPair,
    onNavigateToDashboard: () -> Unit,
    isConnected: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Waktu realtime server/aplikasi berjalan terus setiap detik saat terhubung (LIVE).
    // Jika koneksi terputus/lama tidak tersambung, waktu berhenti dan mencatat jam berapa terputusnya.
    var currentTimeString by remember {
        mutableStateOf(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
    }
    var lastDisconnectTime by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isConnected) {
        if (!isConnected && lastDisconnectTime == null) {
            lastDisconnectTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        } else if (isConnected) {
            lastDisconnectTime = null
        }
    }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            while (true) {
                currentTimeString = sdf.format(Date())
                // Interval update 500ms agar pergantian detik selalu presisi dan mulus tanpa jeda
                delay(500L)
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onNavigateToDashboard,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Kembali",
                tint = TvTextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(4.dp))
        AssetAvatar(baseAsset = pair.baseAsset, iconUrl = pair.iconUrl, size = 36.dp)
        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "${pair.baseAsset}/${pair.quoteAsset}",
                color = TvTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = getCoinFullName(pair.baseAsset),
                color = TvTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }

        // Widget Waktu dengan Dot LED Merah/Hijau yang Kaku (tanpa pulse) di sebelah kiri
        val isLight = LocalAppColors.current == LightAppColors
        val ledColor = if (isConnected) TvGreen else (if (isLight) Color(0xFFD32F2F) else Color(0xFFFF3B30))
        val displayTime = if (isConnected) currentTimeString else (lastDisconnectTime ?: currentTimeString)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(TvSurface, RoundedCornerShape(8.dp))
                .border(0.8.dp, if (isConnected) TvBorder else (if (isLight) Color(0xFFFFCDD2) else Color(0xFF4A1A1A)), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            // Dot LED Merah/Hijau agak besar di sebelah kiri
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(11.dp)
            ) {
                // Ring luar statis
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .background(ledColor.copy(alpha = 0.28f), CircleShape)
                )
                // Inti lampu LED solid kaku
                Box(
                    modifier = Modifier
                        .size(8.5.dp)
                        .background(ledColor, CircleShape)
                )
            }
            Spacer(Modifier.width(6.dp))

            Text(
                text = displayTime,
                color = if (isConnected) TvTextPrimary else (if (isLight) Color(0xFFC62828) else Color(0xFFFF6B6B)),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
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
    val textPrimaryColor = TvTextPrimary
    val greenColor = TvGreen
    val redColor = TvRed

    var previousPrice by remember { mutableStateOf(price) }
    var priceTickColor by remember(textPrimaryColor) { mutableStateOf(textPrimaryColor) }

    LaunchedEffect(price) {
        if (!price.isFinite() || price <= 0.0) return@LaunchedEffect
        if (previousPrice > 0.0 && price != previousPrice) {
            priceTickColor = if (price > previousPrice) greenColor else redColor
            delay(1000L)
            priceTickColor = textPrimaryColor
        }
        previousPrice = price
    }

    val animatedColor by animateColorAsState(
        targetValue = priceTickColor,
        animationSpec = tween(durationMillis = 300),
        label = "price_color_anim"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            if (price > 0) {
                FlipCardPriceText(
                    price = price,
                    color = animatedColor,
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
