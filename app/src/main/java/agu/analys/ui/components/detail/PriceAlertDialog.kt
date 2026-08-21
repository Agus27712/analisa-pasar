package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.PriceAlert
import agu.analys.model.PriceAlertType
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Tambah, 1: Daftar Alert
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
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                // Tab Navigasi
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
                            .clickable { selectedTab = 0 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+ Buat Alert Baru",
                            color = if (selectedTab == 0) Color.White else TvTextSecondary,
                            fontSize = 11.sp,
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
                            .clickable { selectedTab = 1 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Daftar Alert",
                                color = if (selectedTab == 1) Color.White else TvTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                            if (alerts.isNotEmpty()) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF00E5FF), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "${alerts.size}",
                                        color = Color.Black,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (selectedTab == 0) {
                    // TAB BUAT ALERT
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Info Harga Saat Ini
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF102033), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Harga Pasar Saat Ini:", color = TvTextSecondary, fontSize = 10.5.sp)
                            Text(
                                text = "${PriceFormatter.formatIdrNumber(currentPrice)} $quoteAsset",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Preset Cepat
                        Text(
                            text = "PRESET CEPAT 1-TAP:",
                            color = Color(0xFF90A4AE),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            QuickPresetButton(
                                label = "+3% TP",
                                color = TvGreen,
                                onClick = {
                                    selectedType = PriceAlertType.PRICE_ABOVE
                                    targetPriceInput = PriceFormatter.formatIdrNumber(currentPrice * 1.03)
                                    noteInput = "Take Profit Target +3%"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            QuickPresetButton(
                                label = "+5% TP",
                                color = TvGreen,
                                onClick = {
                                    selectedType = PriceAlertType.PRICE_ABOVE
                                    targetPriceInput = PriceFormatter.formatIdrNumber(currentPrice * 1.05)
                                    noteInput = "Take Profit Target +5%"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            QuickPresetButton(
                                label = "-2% SL",
                                color = TvRed,
                                onClick = {
                                    selectedType = PriceAlertType.PRICE_BELOW
                                    targetPriceInput = PriceFormatter.formatIdrNumber(currentPrice * 0.98)
                                    noteInput = "Stop Loss Level -2%"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            QuickPresetButton(
                                label = "-4% SL",
                                color = TvRed,
                                onClick = {
                                    selectedType = PriceAlertType.PRICE_BELOW
                                    targetPriceInput = PriceFormatter.formatIdrNumber(currentPrice * 0.96)
                                    noteInput = "Stop Loss Level -4%"
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            QuickPresetButton(
                                label = "RSI < 30 (Oversold)",
                                color = Color(0xFF00E5FF),
                                onClick = {
                                    selectedType = PriceAlertType.RSI_OVERSOLD
                                    noteInput = "RSI Oversold Potensi Rebound"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            QuickPresetButton(
                                label = "Second-Wave Reclaim",
                                color = Color(0xFFFFD54F),
                                onClick = {
                                    selectedType = PriceAlertType.SECOND_WAVE_RECLAIM
                                    noteInput = "Sinyal Reclaim Terkonfirmasi"
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Pilihan Kategori Alert
                        Text(
                            text = "KATEGORI ALERT:",
                            color = Color(0xFF90A4AE),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        PriceAlertType.values().forEach { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (selectedType == type) Color(0xFF162D47) else Color(0xFF101B29),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (selectedType == type) Color(0xFF00E5FF) else Color(0xFF1B2E42),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedType = type }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF00E5FF),
                                        unselectedColor = Color(0xFF546E7A)
                                    ),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = type.label,
                                        color = if (selectedType == type) Color.White else TvTextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = type.description,
                                        color = Color(0xFF78909C),
                                        fontSize = 9.5.sp
                                    )
                                }
                            }
                        }

                        // Target Harga Input (Hanya untuk Type PRICE_ABOVE atau PRICE_BELOW)
                        if (selectedType == PriceAlertType.PRICE_ABOVE || selectedType == PriceAlertType.PRICE_BELOW) {
                            OutlinedTextField(
                                value = targetPriceInput,
                                onValueChange = { targetPriceInput = it },
                                label = { Text("Target Harga ($quoteAsset)", fontSize = 11.sp) },
                                placeholder = { Text("Contoh: 1.450.000.000", fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next
                                ),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E5FF),
                                    unfocusedBorderColor = Color(0xFF263C52),
                                    focusedContainerColor = Color(0xFF101C2B),
                                    unfocusedContainerColor = Color(0xFF101C2B),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Catatan Tambahan
                        OutlinedTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            label = { Text("Catatan / Label Alert (Opsional)", fontSize = 11.sp) },
                            placeholder = { Text("Misal: TP 1 target resistance", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color(0xFF263C52),
                                focusedContainerColor = Color(0xFF101C2B),
                                unfocusedContainerColor = Color(0xFF101C2B),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Tombol Simpan
                        Button(
                            onClick = {
                                val targetP = PriceFormatter.parseCleanIdrDouble(targetPriceInput)
                                val alert = PriceAlert(
                                    symbol = symbol,
                                    type = selectedType,
                                    targetPrice = targetP,
                                    note = noteInput.trim()
                                )
                                onAddAlert(alert)
                                selectedTab = 1 // Pindah ke tab daftar
                            },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E5FF),
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Simpan & Aktifkan Alert", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                } else {
                    // TAB DAFTAR ALERT
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
                                    text = "Belum Ada Alert Aktif untuk $symbol",
                                    color = TvTextSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Buat alert baru untuk menerima notifikasi saat target tercapai.",
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
private fun QuickPresetButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AlertItemCard(
    alert: PriceAlert,
    quoteAsset: String,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(alert.createdAt))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (alert.isTriggered) Color(0xFF2A1C12) else if (alert.isEnabled) Color(0xFF102133) else Color(0xFF131B24),
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (alert.isTriggered) Color(0xFFFF9800) else if (alert.isEnabled) Color(0xFF1B3D63) else Color(0xFF1E2D3D),
                RoundedCornerShape(10.dp)
            )
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
                            .background(
                                when (alert.type) {
                                    PriceAlertType.PRICE_ABOVE -> TvGreen.copy(alpha = 0.2f)
                                    PriceAlertType.PRICE_BELOW -> TvRed.copy(alpha = 0.2f)
                                    PriceAlertType.RSI_OVERSOLD -> Color(0xFF00E5FF).copy(alpha = 0.2f)
                                    PriceAlertType.RSI_OVERBOUGHT -> Color(0xFFFF9800).copy(alpha = 0.2f)
                                    PriceAlertType.SECOND_WAVE_RECLAIM -> Color(0xFFFFD54F).copy(alpha = 0.2f)
                                },
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = alert.type.label,
                            color = when (alert.type) {
                                PriceAlertType.PRICE_ABOVE -> TvGreen
                                PriceAlertType.PRICE_BELOW -> TvRed
                                PriceAlertType.RSI_OVERSOLD -> Color(0xFF00E5FF)
                                PriceAlertType.RSI_OVERBOUGHT -> Color(0xFFFF9800)
                                PriceAlertType.SECOND_WAVE_RECLAIM -> Color(0xFFFFD54F)
                            },
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
