package agu.analys.ui.components.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.model.MarketTick
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MarketRankingTab(val label: String, val badge: String) {
    WATCHLIST("📋 Watchlist", "📋 WATCHLIST"),
    FAVORITE("⭐ Favorit", "⭐ FAVORIT")
}

/**
 * Top Stat Header & Exchange Source & Mode & Redesigned Tabs:
 * - Title: Watchlist Indodax
 * - Refresh Button with 360-degree rotation animation on click
 * - 24H VOL | AVG VOL | STRATEGI MODE (SCALPING / 2ND-WAVE / SWING)
 * - ● Data realtime Indodax
 * - Responsive Tab bar: [⚡ Scalping | 🌊 2nd-Wave | ⭐ Favorit]
 */
@Composable
fun DashboardMockupHeader(
    allTicks: Map<String, MarketTick>,
    marketDataSource: MarketDataSource = MarketDataSource.INDODAX,
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    isConnected: Boolean,
    selectedTab: MarketRankingTab = MarketRankingTab.WATCHLIST,
    onSelectTab: (MarketRankingTab) -> Unit,
    onRefresh: () -> Unit,
    onMenuClick: () -> Unit = {},
    onAddAsset: () -> Unit = {}
) {
    val totalVolume = allTicks.values.sumOf { it.volume24h }
    val avgVolume = if (allTicks.isNotEmpty()) totalVolume / allTicks.size else 0.0
    val quoteAsset = marketDataSource.defaultQuoteAsset

    // Waktu realtime server/aplikasi berjalan terus saat terhubung (LIVE).
    // Jika koneksi terputus/lama tidak tersambung, waktu berhenti dan mencatat jam berapa terputusnya.
    var currentTime by remember {
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
                currentTime = sdf.format(Date())
                kotlinx.coroutines.delay(500L)
            }
        }
    }

    // Rotasi animasi untuk tombol refresh
    val rotation = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Top Nav: Judul Watchlist Indodax + Badge Sumber + Tombol Refresh Berputar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Watchlist Indodax",
                    color = TvTextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.3).sp
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .background(
                            Color(0xFF2196F3).copy(alpha = 0.12f),
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            0.8.dp,
                            Color(0xFF2196F3).copy(alpha = 0.35f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${marketDataSource.label.uppercase()} ($quoteAsset)",
                        color = Color(0xFF64B5F6),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (selectedTab == MarketRankingTab.WATCHLIST) {
                    IconButton(
                        onClick = onAddAsset,
                        modifier = Modifier
                            .size(36.dp)
                            .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Tambah ke Watchlist",
                            tint = TvBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            rotation.snapTo(0f)
                            rotation.animateTo(
                                targetValue = 360f,
                                animationSpec = tween(durationMillis = 600, easing = LinearEasing)
                            )
                        }
                        onRefresh()
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Data Pasar",
                        tint = TvTextPrimary,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(rotation.value)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 3 Stat Cards Responsive: 24H VOL | AVG VOL | STRATEGI
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Card 1: 24H VOL
            StatBox(
                label = "24H VOL",
                value = PriceFormatter.formatVolume(totalVolume, quoteAsset = quoteAsset),
                modifier = Modifier.weight(1f)
            )

            // Card 2: AVG 24H
            StatBox(
                label = "AVG VOL",
                value = PriceFormatter.formatVolume(avgVolume, quoteAsset = quoteAsset),
                modifier = Modifier.weight(1f)
            )

            // Card 3: STRATEGI Pill
            val (modeBg, modeBorder, modeColor, modeLabel) = when (strategyMode) {
                StrategyMode.SCALPING -> listOf(Color(0xFF123D2A), Color(0xFF1B5E38), TvGreen, "SCALPING")
                StrategyMode.SECOND_WAVE -> listOf(Color(0xFF0F3845), Color(0xFF155060), Color(0xFF00E5FF), "2ND-WAVE")
                StrategyMode.SWING -> listOf(Color(0xFF122840), Color(0xFF1E3A5F), Color(0xFF72B7FF), "SWING")
                StrategyMode.OFFICE_DAILY -> listOf(Color(0xFF1F2448), Color(0xFF3730A3), Color(0xFFA5B4FC), "OFFICE")
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(TvSurface, RoundedCornerShape(10.dp))
                    .border(0.8.dp, TvBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "STRATEGI",
                        color = TvTextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .background(modeBg as Color, RoundedCornerShape(5.dp))
                            .border(0.6.dp, modeBorder as Color, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = modeLabel as String,
                            color = modeColor as Color,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Live Status Row: ● Data Realtime Indodax    ● [HH:mm:ss]
        val ledColor = if (isConnected) TvGreen else TvRed
        val displayTime = if (isConnected) currentTime else (lastDisconnectTime ?: currentTime)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Dot LED solid agak besar tanpa pulse
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(ledColor.copy(alpha = 0.28f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(7.5.dp)
                            .background(ledColor, CircleShape)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "Data Realtime Indodax" else "Terputus",
                    color = if (isConnected) TvGreen else TvRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Widget waktu di kanan dengan dot LED dan format bersih (tanpa kata 'Server:')
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                    .border(0.8.dp, if (isConnected) TvBorder else TvRed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(9.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(ledColor.copy(alpha = 0.28f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(6.5.dp)
                            .background(ledColor, CircleShape)
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = displayTime,
                    color = if (isConnected) TvTextPrimary else TvRed,
                    fontSize = 11.5.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // REDESIGNED TAB BAR (Modern Pills with responsive sizes for Redmi Note 11)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(TvSurfaceVariant)
                .border(0.8.dp, TvBorder, RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            MarketRankingTab.values().forEach { tab ->
                val isSelected = selectedTab == tab
                val (tabActiveBg, tabActiveBorder, tabActiveTextColor) = when (tab) {
                    MarketRankingTab.WATCHLIST -> Triple(TvBlue.copy(alpha = 0.22f), TvBlue.copy(alpha = 0.85f), TvBlueSoft)
                    MarketRankingTab.FAVORITE -> Triple(TvAmber.copy(alpha = 0.22f), TvAmber.copy(alpha = 0.85f), TvAmber)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) tabActiveBg else Color.Transparent)
                        .then(
                            if (isSelected) Modifier.border(0.8.dp, tabActiveBorder, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        color = if (isSelected) tabActiveTextColor else TvTextSecondary,
                        fontSize = 12.5.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(TvSurface, RoundedCornerShape(10.dp))
            .border(0.8.dp, TvBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = TvTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                color = TvTextPrimary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}
