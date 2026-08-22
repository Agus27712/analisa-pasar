package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.StrategyMode
import agu.analys.model.MarketConnectionState
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextSecondary

/**
 * Bar status koneksi sticky (tidak ikut scroll).
 * Compact untuk Redmi Note 11 / layar kecil.
 */
@Composable
fun StickyConnectionBar(
    connection: MarketConnectionState,
    strategyMode: StrategyMode,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val live = connection is MarketConnectionState.Connected
    val loading = connection is MarketConnectionState.Loading
    val lost = connection is MarketConnectionState.ConnectionLost

    val statusLabel = when {
        live -> "LIVE"
        loading -> "SYNC"
        else -> "OFF"
    }
    val statusColor = when {
        live -> TvGreen
        loading -> Color(0xFFFFB300)
        else -> Color(0xFFFF5252)
    }
    val barBg = when {
        live -> Color(0xFF0A1F14)
        loading -> Color(0xFF1F1A0A)
        else -> Color(0xFF1F0A0A)
    }

    val modeLabel = when (strategyMode) {
        StrategyMode.SCALPING -> "SCALP"
        StrategyMode.SECOND_WAVE -> "2ND"
        StrategyMode.SWING -> "SWING"
    }
    val modeColor = when (strategyMode) {
        StrategyMode.SCALPING -> TvGreen
        StrategyMode.SECOND_WAVE -> Color(0xFF00E5FF)
        StrategyMode.SWING -> Color(0xFF72B7FF)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(barBg)
            .border(1.dp, statusColor.copy(alpha = 0.25f), RoundedCornerShape(0.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OnlineStatusIndicator(isOnline = live)
            Text(
                text = statusLabel,
                color = statusColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.6.sp
            )
            if (lost) {
                Text(
                    text = "· reconnect...",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(modeColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .border(1.dp, modeColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = modeLabel,
                    color = modeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
            }
            if (lost && onRetry != null) {
                Text(
                    text = "RETRY",
                    color = Color(0xFFFF5252),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .clickable { onRetry() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}
