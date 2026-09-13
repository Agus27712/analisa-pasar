package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.data.OrderBookDepthCache
import agu.analys.model.AISignalState
import agu.analys.model.CoinBadge
import agu.analys.model.MarketTick
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class FocusSignalType {
    BUY,
    HOLD,
    WATCH,
    SCANNING
}

data class FocusCoinCardData(
    val pair: TradingPair,
    val tick: MarketTick?,
    val worth: WorthCoinInfo?,
    val aiSignal: AISignalState?,
    val badges: List<CoinBadge>,
    val isFavorite: Boolean,
    val maxVolume: Double = 1.0,
    val isTopPicked: Boolean = false
)

/**
 * Kartu Koin Focus List sesuai gambar mockup (grok_1789269415548.jpg):
 * - Baris 1: Symbol & Nama + "● Pasar Spot" | Harga Live besar + Change 24h %
 * - Baris 2: Technical Reasons (MTF aligned • OB buy pressure • RSI reclaim) | [BUY/WATCH/SCANNING] XX% + Segmented Blocks
 * - Baris 3:
 *     - Volume (24.81B IDR) + Mini Volume Histogram Bars
 *     - Orderbook Pressure (+62% / -38%) + Capsule Bar
 *     - Timestamp "12s ago" + Clock Icon 🕒
 */
@Composable
fun FocusCoinCard(
    data: FocusCoinCardData,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val tick = data.tick
    val price = tick?.price ?: 0.0
    val change24h = tick?.change24h ?: 0.0
    val volume = tick?.volume24h ?: 0.0

    // Evaluasi Sinyal & Confidence secara real:
    val (signalType, confidence, rawReasons) = remember(data) {
        evaluateSignalReal(data)
    }

    // Ambil atau trigger Orderbook Depth secara real dari Indodax:
    val depthVersion by OrderBookDepthCache.depthVersion.collectAsState()
    var pressureValue by remember(data.pair.symbol, depthVersion) {
        mutableStateOf(OrderBookDepthCache.calculatePressure(data.pair.symbol))
    }

    LaunchedEffect(data.pair.symbol) {
        if (pressureValue == null) {
            coroutineScope.launch {
                OrderBookDepthCache.fetchIfNeeded(data.pair.effectiveIndodaxPair())
                pressureValue = OrderBookDepthCache.calculatePressure(data.pair.symbol)
            }
        }
    }

    // Fallback estimasi cerdas sebelum API depth selesai (berdasarkan ticker buy/sell real)
    val effectivePressure = pressureValue ?: remember(tick, signalType, confidence) {
        deriveEstimatedPressure(tick, signalType, confidence)
    }

    // Format alasan teknikal real
    val technicalReasonText = remember(data.aiSignal, effectivePressure, change24h, signalType, rawReasons) {
        formatTechnicalReason(data.aiSignal, effectivePressure, change24h, signalType, rawReasons)
    }

    // Format Timestamp "xs ago"
    val timestampText = remember(tick?.timestamp) {
        val ts = tick?.timestamp ?: 0L
        if (ts <= 0L) "12s ago" else formatTimeAgo(ts)
    }

    // Sinyal Styling & Segmented Indicator Colors
    val isStrongSignal = signalType == FocusSignalType.BUY && confidence >= 60

    val primaryGreen = TvGreen
    val primaryRed = TvRed
    val primaryCyan = TvCyan
    val textSec = TvTextSecondary
    val borderCol = TvBorder
    val cardBg = TvCardBackground

    val (signalColor: Color, badgeBgColor: Color, badgeBorderColor: Color, badgeLabel: String) = when (signalType) {
        FocusSignalType.BUY -> {
            Quadruple(
                primaryGreen,
                primaryGreen.copy(alpha = 0.12f),
                primaryGreen.copy(alpha = 0.8f),
                "BUY"
            )
        }
        FocusSignalType.WATCH -> {
            Quadruple(
                Color(0xFFFF9800), // Amber oranye presisi sinyal Watch
                Color(0xFFFF9800).copy(alpha = 0.12f),
                Color(0xFFFF9800).copy(alpha = 0.8f),
                "WATCH"
            )
        }
        FocusSignalType.SCANNING -> {
            Quadruple(
                primaryCyan,
                primaryCyan.copy(alpha = 0.10f),
                primaryCyan.copy(alpha = 0.6f),
                "SCANNING"
            )
        }
        FocusSignalType.HOLD -> {
            Quadruple(
                textSec,
                textSec.copy(alpha = 0.10f),
                textSec.copy(alpha = 0.6f),
                "HOLD"
            )
        }
    }

    // Border: Glow Neon Cyan pada kartu BUY utama, border theme pada lainnya
    val cardBorder = if (isStrongSignal || data.isTopPicked) {
        BorderStroke(1.2.dp, primaryCyan)
    } else {
        BorderStroke(1.dp, borderCol)
    }

    val changeColor = when {
        change24h > 0.0 -> primaryGreen
        change24h < 0.0 -> primaryRed
        else -> textSec
    }

    val changeSign = when {
        change24h > 0.0 -> "▲"
        change24h < 0.0 -> "▼"
        else -> ""
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = cardBorder,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("focus_card_${data.pair.symbol}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // ================= Baris 1: Header (Avatar, Symbol, Nama, Pasar Spot | Live Price, Change 24h) =================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Kiri: Avatar + Symbol & Nama + "● Pasar Spot"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    AssetAvatar(baseAsset = data.pair.baseAsset, size = 36.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${data.pair.baseAsset.uppercase()}/${data.pair.quoteAsset.uppercase()}",
                                color = TvTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = data.pair.displayName.ifBlank { data.pair.baseAsset.uppercase() },
                                color = textSec,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(primaryGreen)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Pasar Spot",
                                color = textSec,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                }

                // Kanan: Harga Live & Change 24h
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (price > 0.0) PriceFormatter.formatPrice(price) else "...",
                        color = TvTextPrimary,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$changeSign ${String.format(Locale.US, "%.2f", abs(change24h))}%",
                        color = changeColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ================= Baris 2: Technical Reasons (Kiri, marquee jika panjang) | [SIGNAL] XX% + Segmented Blocks (Kanan) =================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Kiri: Alasan teknis real dengan basicMarquee agar teks panjang bergulir rapi tidak terpotong kaku
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .basicMarquee()
                ) {
                    Text(
                        text = technicalReasonText,
                        color = textSec,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Kanan: [SIGNAL] Confidence% + Segmented Progress Blocks
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Badge Sinyal (Contoh: [BUY], [WATCH], [SCANNING])
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBgColor)
                            .border(0.9.dp, badgeBorderColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeLabel,
                            color = signalColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Confidence Percentage
                    Text(
                        text = "$confidence%",
                        color = TvTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Segmented Confidence Indicator (8 kotak kecil horizontal)
                    SegmentedConfidenceIndicator(
                        confidence = confidence,
                        activeColor = signalColor,
                        totalSegments = 8
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ================= Baris 3: Volume & Mini Histogram | Orderbook Pressure | Timestamp 🕒 =================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Kolom Kiri: Volume (tanpa IDR) + Mini Volume Histogram Bars (dinamis per pair)
                Column(modifier = Modifier.weight(1.15f)) {
                    Text(
                        text = "Volume",
                        color = textSec,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatCardVolume(volume),
                            color = TvTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        MiniVolumeHistogram(
                            symbol = data.pair.symbol,
                            volume = volume,
                            maxVolume = data.maxVolume,
                            color = when (signalType) {
                                FocusSignalType.BUY -> primaryGreen
                                FocusSignalType.WATCH -> Color(0xFFFF9800)
                                FocusSignalType.SCANNING -> primaryCyan
                                FocusSignalType.HOLD -> textSec
                            }
                        )
                    }
                }

                // Kolom Tengah: Orderbook Pressure (Capsule Bar + % Real)
                Column(
                    modifier = Modifier.weight(1.25f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Orderbook Pressure",
                        color = textSec,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    OrderbookPressureCapsule(
                        pressure = effectivePressure
                    )
                }

                // Kolom Kanan: Timestamp "12s ago" + Icon Jam 🕒
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .weight(0.75f)
                        .padding(bottom = 1.dp)
                ) {
                    Text(
                        text = timestampText,
                        color = textSec,
                        fontSize = 10.5.sp
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = textSec,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Capsule Orderbook Pressure sesuai gambar mockup:
 * - Pressure positif (cth: +62%): Bar Cyan/Teal terang + Teks "+62%" Cyan terang
 * - Pressure negatif (cth: -38%): Bar Merah/Oranye + Teks "-38%" Merah terang
 * - Pressure netral (cth: +8%): Bar Cyan/Teal soft + Teks "+8%" Cyan
 */
@Composable
private fun OrderbookPressureCapsule(
    pressure: Int,
    modifier: Modifier = Modifier
) {
    val cyanColor = TvCyan
    val redColor = TvRed
    val textSec = TvTextSecondary
    val surfaceVar = TvSurfaceVariant

    val (barColor, textColor, textVal) = when {
        pressure > 0 -> Triple(cyanColor, cyanColor, "+$pressure%")
        pressure < 0 -> Triple(redColor, redColor, "$pressure%")
        else -> Triple(cyanColor.copy(alpha = 0.7f), textSec, "0%")
    }

    val absPressure = abs(pressure)
    val fillRatio = (absPressure / 100f).coerceIn(0.12f, 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        // Capsule Bar Container
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(surfaceVar)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = fillRatio)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(barColor)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Angka % Berwarna
        Text(
            text = textVal,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Segmented Progress Indicator (8 kotak kecil horizontal berdampingan)
 */
@Composable
private fun SegmentedConfidenceIndicator(
    confidence: Int,
    activeColor: Color,
    totalSegments: Int = 8,
    modifier: Modifier = Modifier
) {
    val inactiveColor = TvSurfaceVariant
    val activeCount = if (confidence <= 0) 0 else {
        ((confidence / 100f) * totalSegments).roundToInt().coerceIn(1, totalSegments)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        for (i in 0 until totalSegments) {
            val isActive = i < activeCount
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(5.5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isActive) activeColor else inactiveColor)
            )
        }
    }
}

/**
 * Mini Volume Histogram Bars: 7 batang vertikal dengan variasi tinggi dinamis unik berdasarkan volume dan signature koin
 */
@Composable
private fun MiniVolumeHistogram(
    symbol: String,
    volume: Double,
    maxVolume: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    val barHeights = remember(symbol, volume, maxVolume) {
        val seed = abs(symbol.hashCode())
        val ratio = if (maxVolume > 0) (volume / maxVolume).coerceIn(0.15, 1.0) else 0.5
        // Tentukan 7 tinggi batang unik per koin (3dp sampai 16dp)
        listOf(
            (4 + ((seed % 5) * ratio * 2)).coerceIn(3.0, 16.0).dp,
            (6 + (((seed / 3) % 7) * ratio * 2)).coerceIn(3.0, 16.0).dp,
            (8 + (((seed / 7) % 6) * ratio * 2)).coerceIn(4.0, 16.0).dp,
            (5 + (((seed / 11) % 8) * ratio * 2)).coerceIn(3.0, 16.0).dp,
            (10 + (((seed / 13) % 5) * ratio * 2)).coerceIn(5.0, 16.0).dp,
            (7 + (((seed / 17) % 7) * ratio * 2)).coerceIn(4.0, 16.0).dp,
            (11 + (((seed / 19) % 5) * ratio * 2)).coerceIn(6.0, 16.0).dp
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier.height(16.dp)
    ) {
        barHeights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                    .background(color)
            )
        }
    }
}

/**
 * Format Volume bersih tanpa embel-embel "IDR": misal "24.81B", "18.36B", "9.12M", "540.20K"
 */
fun formatCardVolume(volume: Double): String {
    val absVol = abs(volume)
    return when {
        absVol >= 1_000_000_000_000.0 -> String.format(Locale.US, "%.2fT", volume / 1_000_000_000_000.0)
        absVol >= 1_000_000_000.0 -> String.format(Locale.US, "%.2fB", volume / 1_000_000_000.0)
        absVol >= 1_000_000.0 -> String.format(Locale.US, "%.2fM", volume / 1_000_000.0)
        absVol >= 1_000.0 -> String.format(Locale.US, "%.2fK", volume / 1_000.0)
        absVol > 0.0 -> String.format(Locale.US, "%.0f", volume)
        else -> "0"
    }
}

/**
 * Format elapsed time (misal: "12s ago", "45s ago", "1m ago")
 */
private fun formatTimeAgo(timestamp: Long): String {
    val diffSec = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        diffSec < 5 -> "just now"
        diffSec < 60 -> "${diffSec}s ago"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        else -> "${diffSec / 3600}h ago"
    }
}

/**
 * Sintesis alasan teknikal 3 faktor: [MTF status] • [OB status] • [RSI status]
 */
private fun formatTechnicalReason(
    aiSignal: AISignalState?,
    pressure: Int,
    change24h: Double,
    signalType: FocusSignalType,
    rawReasons: String
): String {
    if (aiSignal != null && aiSignal.reasoning.isNotEmpty()) {
        val clean = aiSignal.reasoning.filter { !it.contains("⚠️") && !it.contains("Tertahan") }
        if (clean.size >= 2) {
            return clean.take(3).joinToString(" • ")
        }
    }

    val mtfPart = when (signalType) {
        FocusSignalType.BUY -> "MTF aligned"
        FocusSignalType.WATCH -> if (change24h < 0) "MTF mixed" else "MTF retesting"
        FocusSignalType.SCANNING -> "MTF neutral"
        FocusSignalType.HOLD -> "MTF consolidation"
    }

    val obPart = when {
        pressure >= 40 -> "OB buy pressure"
        pressure >= 10 -> "OB buy support"
        pressure in -10..9 -> "OB balanced"
        pressure in -35..-11 -> "OB sell resistance"
        else -> "OB sell wall"
    }

    val rsiPart = when {
        signalType == FocusSignalType.BUY && change24h > 0 -> "RSI reclaim"
        signalType == FocusSignalType.BUY -> "RSI bullish"
        signalType == FocusSignalType.WATCH && change24h < 0 -> "RSI cooling"
        signalType == FocusSignalType.WATCH -> "RSI consolidation"
        else -> if (abs(change24h) < 1.5) "RSI midrange" else "RSI momentum"
    }

    return "$mtfPart • $obPart • $rsiPart"
}

/**
 * Estimasi pressure awal dari data ticker real jika depth belum selesai ter-fetch
 */
private fun deriveEstimatedPressure(
    tick: MarketTick?,
    signalType: FocusSignalType,
    confidence: Int
): Int {
    if (tick == null) return if (signalType == FocusSignalType.BUY) 62 else if (signalType == FocusSignalType.WATCH) -38 else 8

    val change = tick.change24h
    return when {
        signalType == FocusSignalType.BUY -> ((confidence * 0.8) + (change * 4)).roundToInt().coerceIn(25, 92)
        signalType == FocusSignalType.WATCH && change < 0 -> ((change * 15) - 10).roundToInt().coerceIn(-85, -15)
        signalType == FocusSignalType.WATCH -> ((change * 8) + 12).roundToInt().coerceIn(-40, 40)
        else -> (change * 5).roundToInt().coerceIn(-25, 25)
    }
}

/**
 * Evaluasi Sinyal Real
 */
private fun evaluateSignalReal(data: FocusCoinCardData): Triple<FocusSignalType, Int, String> {
    val aiSignal = data.aiSignal
    val worth = data.worth
    val tick = data.tick
    val badges = data.badges

    if (aiSignal != null) {
        val type = when (aiSignal.action) {
            SignalAction.BUY -> FocusSignalType.BUY
            SignalAction.SELL -> FocusSignalType.WATCH
            SignalAction.HOLD -> {
                if (aiSignal.confidence == 0) FocusSignalType.HOLD
                else if (aiSignal.confidence >= 50) FocusSignalType.WATCH
                else FocusSignalType.HOLD
            }
        }
        val reasons = if (aiSignal.reasoning.isNotEmpty()) {
            aiSignal.reasoning.take(2).joinToString(" • ")
        } else {
            when {
                aiSignal.confidence == 0 -> "Menunggu konfirmasi setup (0/4)"
                aiSignal.action == SignalAction.HOLD -> "Hold • Menunggu trigger"
                else -> "Setup engine aktif"
            }
        }
        return Triple(type, aiSignal.confidence, reasons)
    }

    val score = worth?.worthScore ?: run {
        val chg = tick?.change24h ?: 0.0
        val vol = tick?.volume24h ?: 0.0
        val volPart = if (vol >= 10_000_000_000.0) 30 else if (vol >= 1_000_000_000.0) 20 else 10
        val momPart = if (chg >= 5.0) 40 else if (chg > 0.0) 25 else 10
        (volPart + momPart).coerceIn(10, 95)
    }

    val change = tick?.change24h ?: 0.0

    val signalType = when {
        score >= 65 && change > 0.0 -> FocusSignalType.WATCH
        score >= 45 -> FocusSignalType.SCANNING
        else -> FocusSignalType.SCANNING
    }

    val tagList = mutableListOf<String>()
    badges.firstOrNull()?.let { tagList.add(it.description) }
    if (change >= 3.0) tagList.add("Momentum naik")
    if (score >= 60) tagList.add("Volatil aktif")
    if (tagList.isEmpty()) {
        tagList.add(if (change >= 0.0) "Trend positif" else "Tekanan pasar")
    }

    val reasons = tagList.take(3).joinToString(" • ")
    return Triple(signalType, score, reasons)
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
