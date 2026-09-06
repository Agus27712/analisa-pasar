package agu.analys.ui.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.PriceAlert
import agu.analys.model.PriceAlertType
import agu.analys.ui.components.detail.alert.ActiveAlertsTabContent
import agu.analys.ui.components.detail.alert.CreateAlertTabContent
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

@Composable
fun PriceAlertDialog(
    symbol: String,
    currentPrice: Double,
    quoteAsset: String,
    alerts: List<PriceAlert>,
    onAddAlert: (PriceAlert) -> Unit,
    onRemoveAlert: (String) -> Unit,
    onToggleAlert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedType by remember { mutableStateOf(PriceAlertType.PRICE_ABOVE) }
    var targetPriceInput by remember {
        mutableStateOf(if (currentPrice > 0) PriceFormatter.formatIdrNumber(currentPrice * 1.03) else "")
    }
    var noteInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1826),
        shape = RoundedCornerShape(16.dp),
        title = {
            PriceAlertDialogTitle(
                symbol = symbol,
                onDismiss = onDismiss
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
                PriceAlertTabNavigation(
                    selectedTab = selectedTab,
                    alertCount = alerts.size,
                    onTabSelected = { selectedTab = it }
                )

                Spacer(Modifier.height(10.dp))

                if (selectedTab == 0) {
                    CreateAlertTabContent(
                        symbol = symbol,
                        currentPrice = currentPrice,
                        quoteAsset = quoteAsset,
                        selectedType = selectedType,
                        onTypeChange = { selectedType = it },
                        targetPriceInput = targetPriceInput,
                        onTargetPriceChange = { targetPriceInput = it },
                        noteInput = noteInput,
                        onNoteChange = { noteInput = it },
                        focusManager = focusManager,
                        onSaveAlert = {
                            val targetP = PriceFormatter.parseCleanIdrDouble(targetPriceInput)
                            val alert = PriceAlert(
                                symbol = symbol,
                                type = selectedType,
                                targetPrice = targetP,
                                note = noteInput.trim()
                            )
                            onAddAlert(alert)
                            selectedTab = 1
                        }
                    )
                } else {
                    ActiveAlertsTabContent(
                        symbol = symbol,
                        alerts = alerts,
                        quoteAsset = quoteAsset,
                        onToggleAlert = onToggleAlert,
                        onRemoveAlert = onRemoveAlert
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup", color = Color(0xFFB0BEC5), fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun PriceAlertDialogTitle(
    symbol: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color(0xFF00E5FF).copy(alpha = 0.15f), CircleShape)
                    .border(1.dp, Color(0xFF00E5FF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "Alert & Notifikasi Pasar",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvTextPrimary
                )
                Text(
                    text = "$symbol • Real-Time Monitor",
                    fontSize = 11.sp,
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
        }
    }
}

@Composable
private fun PriceAlertTabNavigation(
    selectedTab: Int,
    alertCount: Int,
    onTabSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101F30), RoundedCornerShape(8.dp))
            .padding(3.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(
                    if (selectedTab == 0) Color(0xFF1A385A) else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
                .clickable { onTabSelected(0) }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+ Buat Notifikasi",
                color = if (selectedTab == 0) Color.White else TvTextSecondary,
                fontSize = 11.5.sp,
                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .background(
                    if (selectedTab == 1) Color(0xFF1A385A) else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
                .clickable { onTabSelected(1) }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Notifikasi Aktif",
                    color = if (selectedTab == 1) Color.White else TvTextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                )
                if (alertCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF00E5FF), RoundedCornerShape(10.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "$alertCount",
                            color = Color.Black,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}
