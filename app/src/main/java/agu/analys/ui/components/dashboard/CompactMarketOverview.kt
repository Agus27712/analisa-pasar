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
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MarketRankingTab(val label: String, val badge: String) {
    SCALPING_FAST("⚡ Scalping Agresif", "⚡ CEPAT"),
    SECOND_WAVE("🌊 Second-Wave", "🌊 2ND-WAVE"),
    WATCHLIST("⭐ Watchlist", "⭐ FAVORIT")
}

/**
 * Top Stat Header & Exchange Source & Mode & Redesigned Tabs:
 * - Title: Watchlist Indodax
 * - Refresh Button with 360-degree rotation animation on click
 * - 24H VOL | AVG 24H | STRATEGY MODE (SCALPING / 2ND-WAVE / SWING - Info Only)
 * - ● Data realtime Indodax
 * - Redesigned Tab bar: [⚡ Scalping Agresif | 🌊 Second-Wave | ⭐ Watchlist]
 */
@Composable
fun DashboardMockupHeader(
    allTicks: Map<String, MarketTick>,
    marketDataSource: MarketDataSource = MarketDataSource.INDODAX,
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    isConnected: Boolean,
    selectedTab: MarketRankingTab = MarketRankingTab.SCALPING_FAST,
    onSelectTab: (MarketRankingTab) -> Unit,
    onRefresh: () -> Unit,
    onMenuClick: () -> Unit = {},
    onAddAsset: () -> Unit = {}
) {
    val totalVolume = allTicks.values.sumOf { it.volume24h }
    val avgVolume = if (allTicks.isNotEmpty()) totalVolume / allTicks.size else 0.0
    val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    val quoteAsset = marketDataSource.defaultQuoteAsset

    // Rotasi animasi untuk tombol refresh
    val rotation = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
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
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .background(
                            Color(0xFF2196F3).copy(alpha = 0.15f),
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            0.8.dp,
                            Color(0xFF2196F3).copy(alpha = 0.4f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${marketDataSource.label.uppercase()} ($quoteAsset)",
                        color = Color(0xFF64B5F6),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedTab == MarketRankingTab.WATCHLIST) {
                    IconButton(onClick = onAddAsset, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Tambah ke Watchlist",
                            tint = Color(0xFF72B7FF),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(2.dp))
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
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Data Pasar",
                        tint = TvTextPrimary,
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(rotation.value)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 3 Stat Cards: 24H VOL | AVG 24H | STRATEGI (Info Only)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Card 1: 24H VOL
            StatBox(
                label = "24H VOL (${quoteAsset})",
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
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF101720), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF1E2836), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "STRATEGI",
                        color = TvTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .background(modeBg as Color, RoundedCornerShape(6.dp))
                            .border(0.8.dp, modeBorder as Color, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = modeLabel as String,
                            color = modeColor as Color,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Live Status Row: ● Data realtime Indodax    Update: 09:41:30
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(if (isConnected) TvGreen else TvRed, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "Data realtime Indodax" else "Koneksi offline / cache",
                    color = if (isConnected) TvGreen else TvRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "Update: $currentTime",
                color = TvTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(10.dp))

        // REDESIGNED TAB BAR (Kapsul Modern & Elegan dengan Kontras Jelas)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0C141F))
                .border(1.dp, Color(0xFF1A2636), RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MarketRankingTab.values().forEach { tab ->
                val isSelected = selectedTab == tab
                val (tabActiveBg, tabActiveBorder, tabActiveTextColor) = when (tab) {
                    MarketRankingTab.SCALPING_FAST -> Triple(Color(0xFF103322), TvGreen.copy(alpha = 0.7f), TvGreen)
                    MarketRankingTab.SECOND_WAVE -> Triple(Color(0xFF0D2F3A), Color(0xFF00E5FF).copy(alpha = 0.7f), Color(0xFF00E5FF))
                    MarketRankingTab.WATCHLIST -> Triple(Color(0xFF332B10), Color(0xFFFFB300).copy(alpha = 0.7f), Color(0xFFFFB300))
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) tabActiveBg else Color.Transparent)
                        .then(
                            if (isSelected) Modifier.border(1.dp, tabActiveBorder, RoundedCornerShape(9.dp))
                            else Modifier
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        color = if (isSelected) tabActiveTextColor else TvTextSecondary,
                        fontSize = 11.5.sp,
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
            .background(Color(0xFF101720), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF1E2836), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = TvTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
