package agu.analys.ui.components.detail.alert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.PriceAlert
import agu.analys.model.PriceAlertType
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActiveAlertsTabContent(
    symbol: String,
    alerts: List<PriceAlert>,
    quoteAsset: String,
    onToggleAlert: (String) -> Unit,
    onRemoveAlert: (String) -> Unit
) {
    if (alerts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.NotificationsOff,
                    contentDescription = null,
                    tint = TvTextSecondary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No Active Alerts for $symbol",
                    color = TvTextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Create an alert to receive notifications when target is reached.",
                    color = Color(0xFF78909C),
                    fontSize = 10.sp
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(alerts, key = { it.id }) { alert ->
                AlertItemCard(
                    alert = alert,
                    quoteAsset = quoteAsset,
                    onToggle = { onToggleAlert(alert.id) },
                    onDelete = { onRemoveAlert(alert.id) }
                )
            }
        }
    }
}

@Composable
fun AlertItemCard(
    alert: PriceAlert,
    quoteAsset: String,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(alert.createdAt) {
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(alert.createdAt))
    }

    val typeColor = when (alert.type) {
        PriceAlertType.PRICE_ABOVE -> TvGreen
        PriceAlertType.PRICE_BELOW -> TvRed
        PriceAlertType.RSI_OVERSOLD -> Color(0xFF00E5FF)
        PriceAlertType.RSI_OVERBOUGHT -> Color(0xFFFF9800)
        PriceAlertType.SECOND_WAVE_RECLAIM -> Color(0xFFFFD54F)
    }

    val containerBg = if (alert.isTriggered) Color(0xFF2A1C12) else if (alert.isEnabled) Color(0xFF102133) else Color(0xFF131B24)
    val borderBg = if (alert.isTriggered) Color(0xFFFF9800) else if (alert.isEnabled) Color(0xFF1B3D63) else Color(0xFF1E2D3D)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerBg, RoundedCornerShape(10.dp))
            .border(1.dp, borderBg, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(typeColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = alert.type.label,
                            color = typeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (alert.isTriggered) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFF9800), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text("TERPICU", color = Color.Black, fontSize = 8.5.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                if (alert.targetPrice > 0.0) {
                    Text(
                        text = "Target: ${PriceFormatter.formatIdrNumber(alert.targetPrice)} $quoteAsset",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (alert.note.isNotBlank()) {
                    Text(
                        text = alert.note,
                        color = Color(0xFFB0BEC5),
                        fontSize = 10.sp
                    )
                }

                Text(
                    text = "Dibuat: $dateStr",
                    color = Color(0xFF78909C),
                    fontSize = 9.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = alert.isEnabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00E5FF),
                        checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.4f),
                        uncheckedThumbColor = Color(0xFF78909C),
                        uncheckedTrackColor = Color(0xFF1E2D3D)
                    ),
                    modifier = Modifier.height(24.dp)
                )

                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Hapus",
                        tint = TvRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
