package agu.analys.ui.components.detail.sell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.trading.SpotPositionStore
import agu.analys.trading.TrailingTier
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun SellTrailingSection(
    isTrailingActive: Boolean,
    onTrailingActiveChanged: (Boolean) -> Unit,
    isTrailingTriggered: Boolean,
    trailingPercent: Double,
    onSetTrailingPercent: (Double) -> Unit,
    isTieredTrailingEnabled: Boolean = true,
    onToggleTieredTrailing: (Boolean) -> Unit = {},
    tieredConfigJson: String = "",
    onUpdateTieredConfig: (String) -> Unit = {},
    activeTrailingPercent: Double = trailingPercent,
    entryPrice: Double = 0.0,
    peakPrice: Double = 0.0,
    trailingStopPrice: Double = 0.0,
    quoteAsset: String = "IDR",
    lastTrailingOrderId: String? = null,
    onDeployTrailingOrder: (() -> Unit)? = null,
    onCancelTrailingOrder: (() -> Unit)? = null,
    isRealMode: Boolean = true
) {
    val hasDeployedOrder = !lastTrailingOrderId.isNullOrEmpty()
    val focusManager = LocalFocusManager.current

    var isCustomInputOpen by remember { mutableStateOf(false) }
    var customPercentInput by remember { mutableStateOf("") }
    var showTiersConfigDialog by remember { mutableStateOf(false) }

    val activeTiers = remember(tieredConfigJson) {
        SpotPositionStore.deserializeTiers(tieredConfigJson)
    }

    val maxProfitPct = if (entryPrice > 0.0 && peakPrice > entryPrice) {
        ((peakPrice - entryPrice) / entryPrice) * 100.0
    } else 0.0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isTrailingTriggered) TvRed.copy(alpha = 0.15f) else if (isTrailingActive) TvBlue.copy(alpha = 0.1f) else TvSurfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (isTrailingTriggered) TvRed else if (isTrailingActive) TvBlue else TvBorder,
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔒 Trailing Sell Limit",
                        color = if (isTrailingTriggered) TvRed else if (isTrailingActive) TvBlue else TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Switch(
                    checked = isTrailingActive,
                    onCheckedChange = { checked ->
                        if (!checked) {
                            onCancelTrailingOrder?.invoke()
                        } else {
                            onTrailingActiveChanged(true)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TvBlue,
                        checkedTrackColor = TvBlue.copy(alpha = 0.4f),
                        uncheckedThumbColor = TvTextSecondary,
                        uncheckedTrackColor = TvSurface
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }

            if (isTrailingActive) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Jarak Trailing Selector (Preset + Custom %)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Base Trailing %:",
                                color = TvTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            val isCustom = isCustomInputOpen || !listOf(1.0, 1.5, 2.0, 3.0, 5.0).contains(trailingPercent)
                            if (isCustom) {
                                Text(
                                    text = "Custom: $trailingPercent%",
                                    color = TvAmber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(1.0, 1.5, 2.0, 3.0, 5.0).forEach { pct ->
                                val isSelected = !isCustomInputOpen && trailingPercent == pct
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(28.dp)
                                        .background(
                                            if (isSelected) TvBlue.copy(alpha = 0.2f) else TvSurface,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) TvBlue else TvBorder,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable {
                                            isCustomInputOpen = false
                                            onSetTrailingPercent(pct)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$pct%",
                                        color = if (isSelected) TvBlue else TvTextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            // Custom % Chip
                            val isCustomSelected = isCustomInputOpen || !listOf(1.0, 1.5, 2.0, 3.0, 5.0).contains(trailingPercent)
                            Box(
                                modifier = Modifier
                                    .weight(1.35f)
                                    .height(28.dp)
                                    .background(
                                        if (isCustomSelected) TvAmber.copy(alpha = 0.2f) else TvSurface,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isCustomSelected) TvAmber else TvBorder,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable {
                                        isCustomInputOpen = !isCustomInputOpen
                                        if (isCustomInputOpen) {
                                            customPercentInput = trailingPercent.toString()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (!listOf(1.0, 1.5, 2.0, 3.0, 5.0).contains(trailingPercent)) "$trailingPercent%" else "Custom",
                                    color = if (isCustomSelected) TvAmber else TvTextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    // Input Field untuk Custom % Bebas
                    AnimatedVisibility(visible = isCustomInputOpen) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvSurface, RoundedCornerShape(6.dp))
                                .border(1.dp, TvAmber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Input Custom %:", color = TvTextSecondary, fontSize = 10.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(70.dp)
                                        .height(30.dp)
                                        .background(TvSurfaceVariant, RoundedCornerShape(4.dp))
                                        .border(1.dp, TvAmber, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    BasicTextField(
                                        value = customPercentInput,
                                        onValueChange = { customPercentInput = it.filter { c -> c.isDigit() || c == '.' } },
                                        textStyle = TextStyle(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TvTextPrimary
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Decimal,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                focusManager.clearFocus()
                                                val parsed = customPercentInput.toDoubleOrNull()
                                                if (parsed != null && parsed >= 0.1) {
                                                    onSetTrailingPercent(parsed)
                                                }
                                            }
                                        ),
                                        singleLine = true,
                                        cursorBrush = SolidColor(TvAmber),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        val parsed = customPercentInput.toDoubleOrNull()
                                        if (parsed != null && parsed >= 0.1) {
                                            onSetTrailingPercent(parsed)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvAmber),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Set", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }

                    // Smart Step Trailing (Tiered Trailing) Toggle & Details
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurface, RoundedCornerShape(6.dp))
                            .border(
                                1.dp,
                                if (isTieredTrailingEnabled) TvGreen.copy(alpha = 0.4f) else TvBorder,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "⚡ Smart Step Trailing",
                                    color = if (isTieredTrailingEnabled) TvGreen else TvTextSecondary,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Kencangkan jarak otomatis saat profit membesar",
                                    color = TvTextSecondary,
                                    fontSize = 9.sp
                                )
                            }
                            Switch(
                                checked = isTieredTrailingEnabled,
                                onCheckedChange = { onToggleTieredTrailing(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TvGreen,
                                    checkedTrackColor = TvGreen.copy(alpha = 0.4f),
                                    uncheckedThumbColor = TvTextSecondary,
                                    uncheckedTrackColor = TvSurfaceVariant
                                ),
                                modifier = Modifier.height(20.dp)
                            )
                        }

                        if (isTieredTrailingEnabled) {
                            HorizontalDivider(color = TvBorder.copy(alpha = 0.4f), thickness = 0.5.dp)

                            // Display current active tier status
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val currentTierLabel = when {
                                    activeTrailingPercent < trailingPercent -> "🔥 Tier Aktif (Mengencang ke $activeTrailingPercent%)"
                                    maxProfitPct >= 1.0 -> "🛡️ Base Tier ($activeTrailingPercent%)"
                                    else -> "⏳ Menunggu Profit > 0%"
                                }
                                Text(
                                    text = currentTierLabel,
                                    color = if (activeTrailingPercent < trailingPercent) TvGreen else TvAmber,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(TvSurfaceVariant)
                                        .border(0.8.dp, TvBlue.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                        .clickable { showTiersConfigDialog = true }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Atur Tingkat ⚙️",
                                        color = TvBlue,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Ringkasan Tier yang berlaku
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                activeTiers.sortedBy { it.minProfitPct }.forEach { tier ->
                                    val isTierHit = maxProfitPct >= tier.minProfitPct
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(
                                                if (isTierHit) TvGreen.copy(alpha = 0.15f) else TvSurfaceVariant,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (isTierHit) TvGreen else TvBorder,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "≥ +${tier.minProfitPct.toInt()}%",
                                                color = if (isTierHit) TvGreen else TvTextSecondary,
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "➔ ${tier.trailingPercent}%",
                                                color = if (isTierHit) TvGreen else TvTextPrimary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Info Peak & Dynamic Stop (Menampilkan active trailing % & proteksi cut-loss)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurface, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Harga Peak baru:", color = TvTextSecondary, fontSize = 9.5.sp)
                            Text("${PriceFormatter.formatIdrNumber(peakPrice)} $quoteAsset", color = TvAmber, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Jarak Trailing Aktif:", color = TvTextSecondary, fontSize = 9.5.sp)
                            Text(
                                text = "$activeTrailingPercent% ${if (isTieredTrailingEnabled && activeTrailingPercent < trailingPercent) "(Mengencang)" else "(Base)"}",
                                color = if (activeTrailingPercent < trailingPercent) TvGreen else TvBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Titik Jual Otomatis:", color = TvBlue, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "${PriceFormatter.formatIdrNumber(trailingStopPrice)} $quoteAsset",
                                color = if (isTrailingTriggered) TvRed else TvBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        if (entryPrice > 0.0 && trailingStopPrice <= entryPrice) {
                            Text(
                                text = "🛡️ Anti Cut-Loss: Trailing hanya lock profit di atas modal (Rp ${PriceFormatter.formatIdrNumber(entryPrice)})",
                                color = TvTextSecondary,
                                fontSize = 8.5.sp
                            )
                        }
                    }

                    // Status Integrasi Bursa / Eksekutor Sell
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasDeployedOrder) {
                            // Status Terpasang
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TvGreen.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isRealMode) "🟢 Trailing AKTIF (${activeTrailingPercent}%)\nAset akan dijual otomatis saat menyentuh batas aman."
                                           else "🟢 PEMANTAUAN SIMULASI AKTIF (${activeTrailingPercent}%)\nAset akan dijual otomatis saat menyentuh batas aman.",
                                    color = TvGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 13.sp
                                )
                            }
                            // Tombol Matikan Trailing
                            Button(
                                onClick = { onCancelTrailingOrder?.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    if (isRealMode) "Matikan Trailing" else "Matikan Trailing & Batal Sim",
                                    color = TvRed,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            // Status Belum Terpasang
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TvAmber.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isRealMode) "⚠️ Trailing Sell siap, belum diaktifkan."
                                           else "⚠️ Trailing Sell siap, belum diaktifkan (Sim).",
                                    color = TvAmber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Tombol Pasang Trailing
                            Button(
                                onClick = { onDeployTrailingOrder?.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    if (isRealMode) "Aktifkan Trailing Sell" else "Aktifkan Trailing Sell (Simu)",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog Konfigurasi Smart Step Trailing (Bertingkat)
    if (showTiersConfigDialog) {
        TieredTrailingConfigDialog(
            currentTiers = activeTiers,
            onDismiss = { showTiersConfigDialog = false },
            onSave = { updatedTiers ->
                onUpdateTieredConfig(SpotPositionStore.serializeTiers(updatedTiers))
                showTiersConfigDialog = false
            },
            onResetToDefault = {
                onUpdateTieredConfig(SpotPositionStore.serializeTiers(SpotPositionStore.DEFAULT_TIERS))
                showTiersConfigDialog = false
            }
        )
    }
}

@Composable
private fun TieredTrailingConfigDialog(
    currentTiers: List<TrailingTier>,
    onDismiss: () -> Unit,
    onSave: (List<TrailingTier>) -> Unit,
    onResetToDefault: () -> Unit
) {
    val sorted = if (currentTiers.isNotEmpty()) currentTiers.sortedBy { it.minProfitPct } else SpotPositionStore.DEFAULT_TIERS
    var step1Profit by remember { mutableStateOf(sorted.getOrNull(0)?.minProfitPct?.toString() ?: "1.0") }
    var step1Trailing by remember { mutableStateOf(sorted.getOrNull(0)?.trailingPercent?.toString() ?: "2.0") }

    var step2Profit by remember { mutableStateOf(sorted.getOrNull(1)?.minProfitPct?.toString() ?: "3.0") }
    var step2Trailing by remember { mutableStateOf(sorted.getOrNull(1)?.trailingPercent?.toString() ?: "1.5") }

    var step3Profit by remember { mutableStateOf(sorted.getOrNull(2)?.minProfitPct?.toString() ?: "5.0") }
    var step3Trailing by remember { mutableStateOf(sorted.getOrNull(2)?.trailingPercent?.toString() ?: "1.2") }

    var step4Profit by remember { mutableStateOf(sorted.getOrNull(3)?.minProfitPct?.toString() ?: "10.0") }
    var step4Trailing by remember { mutableStateOf(sorted.getOrNull(3)?.trailingPercent?.toString() ?: "1.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TvSurface,
        title = {
            Text(
                text = "⚡ Konfigurasi Smart Step Trailing",
                color = TvTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Saat profit melonjak mencapai ambang batas, persentase trailing otomatis mengencang untuk mengunci keuntungan maksimal.",
                    color = TvTextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )

                // Step 1
                TierInputRow(
                    label = "Tier 1",
                    profit = step1Profit,
                    onProfitChange = { step1Profit = it },
                    trailing = step1Trailing,
                    onTrailingChange = { step1Trailing = it }
                )

                // Step 2
                TierInputRow(
                    label = "Tier 2",
                    profit = step2Profit,
                    onProfitChange = { step2Profit = it },
                    trailing = step2Trailing,
                    onTrailingChange = { step2Trailing = it }
                )

                // Step 3
                TierInputRow(
                    label = "Tier 3",
                    profit = step3Profit,
                    onProfitChange = { step3Profit = it },
                    trailing = step3Trailing,
                    onTrailingChange = { step3Trailing = it }
                )

                // Step 4
                TierInputRow(
                    label = "Tier 4",
                    profit = step4Profit,
                    onProfitChange = { step4Profit = it },
                    trailing = step4Trailing,
                    onTrailingChange = { step4Trailing = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val t1P = step1Profit.toDoubleOrNull() ?: 1.0
                    val t1T = step1Trailing.toDoubleOrNull() ?: 2.0
                    val t2P = step2Profit.toDoubleOrNull() ?: 3.0
                    val t2T = step2Trailing.toDoubleOrNull() ?: 1.5
                    val t3P = step3Profit.toDoubleOrNull() ?: 5.0
                    val t3T = step3Trailing.toDoubleOrNull() ?: 1.2
                    val t4P = step4Profit.toDoubleOrNull() ?: 10.0
                    val t4T = step4Trailing.toDoubleOrNull() ?: 1.0

                    val list = listOf(
                        TrailingTier(t1P, t1T),
                        TrailingTier(t2P, t2T),
                        TrailingTier(t3P, t3T),
                        TrailingTier(t4P, t4T)
                    )
                    onSave(list)
                },
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
            ) {
                Text("Simpan", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onResetToDefault) {
                    Text("Reset Default", color = TvAmber, fontSize = 11.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Batal", color = TvTextSecondary, fontSize = 11.sp)
                }
            }
        }
    )
}

@Composable
private fun TierInputRow(
    label: String,
    profit: String,
    onProfitChange: (String) -> Unit,
    trailing: String,
    onTrailingChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TvBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Profit ≥", color = TvTextSecondary, fontSize = 10.sp)
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(30.dp)
                    .background(TvSurface, RoundedCornerShape(4.dp))
                    .border(1.dp, TvBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = profit,
                    onValueChange = { onProfitChange(it.filter { c -> c.isDigit() || c == '.' }) },
                    textStyle = TextStyle(fontSize = 11.sp, color = TvTextPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    cursorBrush = SolidColor(TvBlue),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("%", color = TvTextSecondary, fontSize = 10.sp)

            Spacer(Modifier.width(4.dp))

            Text("Trailing:", color = TvTextSecondary, fontSize = 10.sp)
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(30.dp)
                    .background(TvSurface, RoundedCornerShape(4.dp))
                    .border(1.dp, TvBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = trailing,
                    onValueChange = { onTrailingChange(it.filter { c -> c.isDigit() || c == '.' }) },
                    textStyle = TextStyle(fontSize = 11.sp, color = TvTextPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    cursorBrush = SolidColor(TvBlue),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("%", color = TvTextSecondary, fontSize = 10.sp)
        }
    }
}
