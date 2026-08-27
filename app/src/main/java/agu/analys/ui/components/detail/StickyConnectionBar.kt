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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.StrategyMode
import agu.analys.model.MarketConnectionState
import agu.analys.ui.theme.*

/**
 * Bar status koneksi in-line di bawah area Chart.
 * Menampilkan status LIVE/OFFLINE dan mode aktif (SCALPING / 2ND-WAVE / SWING).
 */
@Composable
fun LiveModeStatusBar(
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
        else -> "OFFLINE"
    }
    val isLight = LocalAppColors.current == LightAppColors
    val statusColor = when {
        live -> TvGreen
        loading -> if (isLight) Color(0xFFE65100) else Color(0xFFFFB300)
        else -> if (isLight) Color(0xFFC62828) else Color(0xFFFF5252)
    }
    val barBg = when {
        live -> if (isLight) Color(0xFFE8F5E9) else Color(0xFF0C1917)
        loading -> if (isLight) Color(0xFFFFF3E0) else Color(0xFF1F1A0A)
        else -> if (isLight) Color(0xFFFFEBEE) else Color(0xFF1F0A0A)
    }

    val modeLabel = when (strategyMode) {
        StrategyMode.SCALPING -> "SCALPING MODE"
        StrategyMode.SECOND_WAVE -> "2ND-WAVE MODE"
        StrategyMode.SWING -> "SWING MODE"
    }
    val modeColor = when (strategyMode) {
        StrategyMode.SCALPING -> TvGreen
        StrategyMode.SECOND_WAVE -> TvBlue
        StrategyMode.SWING -> if (isLight) Color(0xFF1565C0) else Color(0xFF72B7FF)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(barBg)
            .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
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
                    text = "· koneksi terputus",
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
                    .clip(RoundedCornerShape(5.dp))
                    .background(modeColor.copy(alpha = 0.15f))
                    .border(1.dp, modeColor.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
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

/**
 * Bar status melayang (floating / sticky) yang muncul di bagian atas ketika halaman di-scroll ke bawah.
 * Didesain ringkas (compact), selaras dengan lebar kartu tanpa kepanjangan.
 */
@Composable
fun StickyFloatingStatusBar(
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
        else -> "OFFLINE"
    }
    val isLight = LocalAppColors.current == LightAppColors
    val statusColor = when {
        live -> TvGreen
        loading -> if (isLight) Color(0xFFE65100) else Color(0xFFFFB300)
        else -> if (isLight) Color(0xFFC62828) else Color(0xFFFF5252)
    }
    val barBg = when {
        live -> if (isLight) Color(0xF2E8F5E9) else Color(0xF2091815)
        loading -> if (isLight) Color(0xF2FFF3E0) else Color(0xF21F1A0A)
        else -> if (isLight) Color(0xF2FFEBEE) else Color(0xF21F0A0A)
    }

    val modeLabel = when (strategyMode) {
        StrategyMode.SCALPING -> "SCALPING MODE"
        StrategyMode.SECOND_WAVE -> "2ND-WAVE MODE"
        StrategyMode.SWING -> "SWING MODE"
    }
    val modeColor = when (strategyMode) {
        StrategyMode.SCALPING -> TvGreen
        StrategyMode.SECOND_WAVE -> TvBlue
        StrategyMode.SWING -> if (isLight) Color(0xFF1565C0) else Color(0xFF72B7FF)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(barBg)
            .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
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
                    .clip(RoundedCornerShape(4.dp))
                    .background(modeColor.copy(alpha = 0.15f))
                    .border(1.dp, modeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
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
