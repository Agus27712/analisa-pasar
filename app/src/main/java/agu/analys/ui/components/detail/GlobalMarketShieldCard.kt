package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.global.GlobalMarketContext
import agu.analys.engine.global.GlobalRegime
import agu.analys.ui.theme.*

@Composable
fun GlobalMarketShieldCard(context: GlobalMarketContext) {
    val (icon, titleColor, statusText, statusDesc) = when {
        !context.isConnected -> listOf(
            Icons.Default.Info,
            TvTextSecondary,
            "Menghubungkan...",
            "Mengambil data Global Market"
        )
        context.isVetoActive -> listOf(
            Icons.Default.Warning,
            TvRed,
            "VETO AKTIF (FLASH CRASH)",
            context.vetoReason ?: "Terdeteksi badai market global"
        )
        context.regime == GlobalRegime.BULLISH -> listOf(
            Icons.Default.Security,
            TvGreen,
            "SHIELD AMAN (BULLISH)",
            "Kondisi global mendukung kenaikan"
        )
        context.regime == GlobalRegime.BEARISH -> listOf(
            Icons.Default.Security,
            TvAmber,
            "SHIELD STANDBY (BEARISH)",
            "Global sedang turun, berhati-hati"
        )
        else -> listOf(
            Icons.Default.Security,
            TvBlue,
            "SHIELD STANDBY (SIDEWAYS)",
            "Market global relatif stabil"
        )
    }

    AnalysisCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                contentDescription = null,
                tint = titleColor as Color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GLOBAL MARKET SHIELD", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    if (context.isConnected) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "• ${context.dataSource}",
                            color = TvTextSecondary.copy(alpha = 0.5f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "BTC: ${String.format(java.util.Locale.US, "%.2f", context.btcPriceUsdt)} (${if(context.btc24hChangePct > 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", context.btc24hChangePct)}%)",
                        color = if (context.btc24hChangePct >= 0) TvGreen else TvRed,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(statusText as String, color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Text(statusDesc as String, color = TvTextSecondary, fontSize = 10.sp)
            }
        }
    }
}
