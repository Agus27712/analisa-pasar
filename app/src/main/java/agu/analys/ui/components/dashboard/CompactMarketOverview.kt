package agu.analys.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.model.MarketTick
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MarketRankingTab(val label: String) {
    UNTUNG("🔥 Untung"),
    HOT("⚡ Hot"),
    VOLUME("📊 24H Vol"),
    AI_PICKS("🎯 AI Rekomendasi"),
    WATCHLIST("⭐ Watchlist")
}

/**
 * Top Stat Header & Exchange Source & Mode & Tabs:
 * - EXCHANGE SWITCHER: [INDODAX | TOKOCRYPTO]
 * - 24H VOL | AVG 24H | MODE (SCALPING / SWING)
 * - ● Data realtime Tokocrypto (Binance Engine) / Indodax
 * - TABS: [🔥 Untung | ⚡ Hot | 📊 24H Vol | 🎯 AI Rekomendasi | ⭐ Watchlist]
 */
@Composable
fun DashboardMockupHeader(
    allTicks: Map<String, MarketTick>,
    marketDataSource: MarketDataSource = MarketDataSource.INDODAX,
    isScalpingMode: Boolean,
    isConnected: Boolean,
    selectedTab: MarketRankingTab = MarketRankingTab.UNTUNG,
    onSelectTab: (MarketRankingTab) -> Unit,
    onToggleMode: (Boolean) -> Unit = {},
    onRefresh: () -> Unit,
    onMenuClick: () -> Unit
) {
    val totalVolume = allTicks.values.sumOf { it.volume24h }
    val avgVolume = if (allTicks.isNotEmpty()) totalVolume / allTicks.size else 0.0
    val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    val quoteAsset = marketDataSource.defaultQuoteAsset

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // Top Nav: Menu, Title + Source Badge, Refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = TvTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Watchlist & Hotlist Pasar",
                    color = TvTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (marketDataSource == MarketDataSource.TOKOCRYPTO) Color(0xFF00C087).copy(alpha = 0.15f) else Color(0xFF2196F3).copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                0.8.dp,
                                if (marketDataSource == MarketDataSource.TOKOCRYPTO) Color(0xFF00C087).copy(alpha = 0.4f) else Color(0xFF2196F3).copy(alpha = 0.4f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "${marketDataSource.label.uppercase()} ($quoteAsset)",
                            color = if (marketDataSource == MarketDataSource.TOKOCRYPTO) Color(0xFF00E676) else Color(0xFF64B5F6),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = TvTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 3 Stat Cards: 24H VOL | AVG 24H | MODE
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

            // Card 3: MODE Pill
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF101720), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF1E2836), RoundedCornerShape(10.dp))
                    .clickable { onToggleMode(!isScalpingMode) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "MODE",
                        color = TvTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                if (isScalpingMode) Color(0xFF123D2A) else Color(0xFF122840),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isScalpingMode) "SCALPING" else "SWING",
                            color = if (isScalpingMode) TvGreen else Color(0xFF72B7FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Live Status Row: ● Data realtime Tokocrypto/Indodax    Update: 09:41:30
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
                val exchangeLabel = if (marketDataSource == MarketDataSource.TOKOCRYPTO) "Tokocrypto (Live Binance)" else "Indodax"
                Text(
                    text = if (isConnected) "Data realtime $exchangeLabel" else "Koneksi offline / cache",
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

        // Category Ranking Tabs
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(MarketRankingTab.values().size) { idx ->
                val tab = MarketRankingTab.values()[idx]
                val isSelected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) Color(0xFF123D2A) else Color(0xFF101720),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            if (isSelected) TvGreen else Color(0xFF1E2836),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        color = if (isSelected) TvGreen else TvTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
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
