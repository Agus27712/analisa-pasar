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
import agu.analys.model.MarketTick
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top Stat Header & Mode & Tabs sesuai Mockup:
 * - 24H VOL | AVG 24H | MODE (SCALPING / SWING)
 * - ● Data realtime Indodax    Update: 09:41:30
 * - AUTO WATCHLIST  |  MANUAL WATCHLIST
 */
@Composable
fun DashboardMockupHeader(
    allTicks: Map<String, MarketTick>,
    isScalpingMode: Boolean,
    isConnected: Boolean,
    isManualTab: Boolean,
    onToggleTab: (Boolean) -> Unit,
    onToggleMode: (Boolean) -> Unit = {},
    onRefresh: () -> Unit,
    onMenuClick: () -> Unit
) {
    val totalVolume = allTicks.values.sumOf { it.volume24h }
    val avgVolume = if (allTicks.isNotEmpty()) totalVolume / allTicks.size else 0.0
    val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // Top Nav: Menu, Title, Refresh
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

            Text(
                text = "Watchlist",
                color = TvTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = TvTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 3 Stat Cards: 24H VOL | AVG 24H | MODE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Card 1: 24H VOL
            StatBox(
                label = "24H VOL",
                value = formatTrillion(totalVolume),
                modifier = Modifier.weight(1f)
            )

            // Card 2: AVG 24H
            StatBox(
                label = "AVG 24H",
                value = formatTrillion(avgVolume),
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

        // Tabs: AUTO WATCHLIST | MANUAL WATCHLIST
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Auto Watchlist Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (!isManualTab) Color(0xFF123D2A).copy(alpha = 0.5f) else Color(0xFF101720),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (!isManualTab) TvGreen else Color(0xFF1E2836),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onToggleTab(false) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isScalpingMode) "TOP MOMENTUM (NAIK)" else "AUTO WATCHLIST",
                    color = if (!isManualTab) TvGreen else TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Manual Watchlist Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isManualTab) Color(0xFF123D2A).copy(alpha = 0.5f) else Color(0xFF101720),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (isManualTab) TvGreen else Color(0xFF1E2836),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onToggleTab(true) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "MANUAL WATCHLIST",
                    color = if (isManualTab) TvGreen else TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
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

private fun formatTrillion(vol: Double): String {
    if (vol <= 0) return "Rp 0 T"
    val inTrillion = vol / 1_000_000_000_000.0
    return if (inTrillion >= 1.0) {
        String.format(Locale.US, "Rp %.2f T", inTrillion)
    } else {
        val inBillion = vol / 1_000_000_000.0
        String.format(Locale.US, "Rp %.1f M", inBillion)
    }
}
