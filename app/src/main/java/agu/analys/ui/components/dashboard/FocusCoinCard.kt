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

    val (signalColor, badgeBgColor, badgeBorderColor, badgeLabel) = when (signalType) {
        FocusSignalType.BUY -> {
            FocusSignalBadgeStyle(
                primaryGreen,
                primaryGreen.copy(alpha = 0.12f),
                primaryGreen.copy(alpha = 0.8f),
                "BUY"
            )
        }
        FocusSignalType.WATCH -> {
            FocusSignalBadgeStyle(
                Color(0xFFFF9800), // Amber oranye presisi sinyal Watch
                Color(0xFFFF9800).copy(alpha = 0.12f),
                Color(0xFFFF9800).copy(alpha = 0.8f),
                "WATCH"
            )
        }
        FocusSignalType.SCANNING -> {
            FocusSignalBadgeStyle(
                primaryCyan,
                primaryCyan.copy(alpha = 0.10f),
                primaryCyan.copy(alpha = 0.6f),
                "SCANNING"
            )
        }
        FocusSignalType.HOLD -> {
            FocusSignalBadgeStyle(
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

