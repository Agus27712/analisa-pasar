package agu.analys.ui.components.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.engine.scalping.SensitivityPreset
import agu.analys.ui.theme.*
import java.util.Locale

@Composable
fun ScalpingSensitivitySettings(
    config: ScalpingRuntimeConfig,
    activePreset: String,
    onConfigChange: (ScalpingRuntimeConfig, String) -> Unit,
    onPresetChange: (SensitivityPreset) -> Unit
) {
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = TvGreen, modifier = Modifier.size(18.dp))
            Text(
                "SENSITIFITAS ENGINE SCALPING",
                color = TvTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Mengatur ambang batas gerbang masuk (gate) scalp mode. Slider mengubah frekuensi sinyal, bukan menciptakan expectancy/edge. Konfig selaras tools/scalp_replay.py.",
            color = TvTextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(12.dp))

        // Preset Selector Row
        Text("PRESET SENSITIFITAS", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SensitivityPreset.entries.forEach { preset ->
                val isSelected = activePreset.equals(preset.name, ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) TvGreen.copy(alpha = 0.2f) else TvSurfaceVariant,
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, TvGreen) else null,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onPresetChange(preset) }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            preset.label,
                            color = if (isSelected) TvGreen else TvTextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Sliders List Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // 1. Min Score Long
                SensitivitySliderItem(
                    label = "Skor Min LONG",
                    jsonKey = "min_score_long",
                    value = config.minScoreLong.toFloat(),
                    range = 45f..80f,
                    steps = 35,
                    displayValue = "${config.minScoreLong}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minScoreLong = newVal.toInt()), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 2. Min Score Strong
                SensitivitySliderItem(
                    label = "Skor STRONG",
                    jsonKey = "min_score_strong",
                    value = config.minScoreStrong.toFloat(),
                    range = 60f..90f,
                    steps = 30,
                    displayValue = "${config.minScoreStrong}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minScoreStrong = newVal.toInt()), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 3. Min Score Retest
                SensitivitySliderItem(
                    label = "Skor Retest",
                    jsonKey = "min_score_breakout_retest",
                    value = config.minScoreBreakoutRetest.toFloat(),
                    range = 60f..90f,
                    steps = 30,
                    displayValue = "${config.minScoreBreakoutRetest}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minScoreBreakoutRetest = newVal.toInt()), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 4. Min Net R:R
                SensitivitySliderItem(
                    label = "Min Net R:R",
                    jsonKey = "min_net_rr",
                    value = config.minNetRr.toFloat(),
                    range = 0.8f..2.0f,
                    steps = 24,
                    displayValue = String.format(Locale.US, "%.2fR", config.minNetRr),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minNetRr = Math.round(newVal * 100.0) / 100.0), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 5. Target Net R:R
                SensitivitySliderItem(
                    label = "Target Net R:R",
                    jsonKey = "target_net_rr",
                    value = config.targetNetRr.toFloat(),
                    range = 1.0f..2.5f,
                    steps = 30,
                    displayValue = String.format(Locale.US, "%.2fR", config.targetNetRr),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(targetNetRr = Math.round(newVal * 100.0) / 100.0), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 6. RVOL Breakout
                SensitivitySliderItem(
                    label = "RVOL Breakout",
                    jsonKey = "rvol_breakout_min",
                    value = config.rvolBreakoutMin.toFloat(),
                    range = 1.0f..2.5f,
                    steps = 15,
                    displayValue = String.format(Locale.US, "%.1fx", config.rvolBreakoutMin),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(rvolBreakoutMin = Math.round(newVal * 10.0) / 10.0), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 7. RVOL Setup
                SensitivitySliderItem(
                    label = "RVOL Setup (Sweep/Recovery)",
                    jsonKey = "rvol_setup_min",
                    value = config.rvolSetupMin.toFloat(),
                    range = 0.8f..2.0f,
                    steps = 12,
                    displayValue = String.format(Locale.US, "%.1fx", config.rvolSetupMin),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(rvolSetupMin = Math.round(newVal * 10.0) / 10.0), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 8. RVOL Retest
                SensitivitySliderItem(
                    label = "RVOL Retest",
                    jsonKey = "rvol_retest_min",
                    value = config.rvolRetestMin.toFloat(),
                    range = 1.0f..2.5f,
                    steps = 15,
                    displayValue = String.format(Locale.US, "%.1fx", config.rvolRetestMin),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(rvolRetestMin = Math.round(newVal * 10.0) / 10.0), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 9. CHOP Hard Ranging
                SensitivitySliderItem(
                    label = "CHOP Hard Ranging",
                    jsonKey = "chop_hard_ranging",
                    value = config.chopHardRanging.toFloat(),
                    range = 55f..75f,
                    steps = 20,
                    displayValue = String.format(Locale.US, "%.0f", config.chopHardRanging),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(chopHardRanging = newVal.toDouble()), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 10. Jarak Max Retest %
                SensitivitySliderItem(
                    label = "Jarak Max Retest %",
                    jsonKey = "retest_max_dist_pct",
                    value = config.retestMaxDistPct.toFloat(),
                    range = 0.5f..2.5f,
                    steps = 20,
                    displayValue = String.format(Locale.US, "%.1f%%", config.retestMaxDistPct),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(retestMaxDistPct = Math.round(newVal * 10.0) / 10.0), "CUSTOM")
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 11. Strength Struktur Retest
                SensitivitySliderItem(
                    label = "Strength Struktur Retest",
                    jsonKey = "retest_min_structure_strength",
                    value = config.retestMinStructureStrength.toFloat(),
                    range = 40f..80f,
                    steps = 8,
                    displayValue = "${config.retestMinStructureStrength}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(retestMinStructureStrength = newVal.toInt()), "CUSTOM")
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Action Buttons: Export JSON & Import JSON & Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val jsonStr = config.toJsonString(2)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("scalp_config.json", jsonStr)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "JSON scalp_config berhasil disalin ke clipboard!", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export JSON", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = {
                    importText = ""
                    importError = null
                    showImportDialog = true
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import JSON", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            IconButton(
                onClick = {
                    onPresetChange(SensitivityPreset.DEFAULT)
                    Toast.makeText(context, "Threshold direset ke default ScalpingConfig", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                    .size(42.dp)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = "Reset Default", tint = TvTextSecondary)
            }
        }
    }

    if (showImportDialog) {
        Dialog(onDismissRequest = { showImportDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = TvSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "Import scalp_config.json",
                        color = TvTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tempel teks JSON konfigurasi scalping di bawah ini:",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = null
                        },
                        placeholder = { Text("{ \"min_score_long\": 60, ... }", color = TvTextSecondary.copy(alpha = 0.5f), fontSize = 11.sp) },
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvGreen,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary
                        )
                    )

                    if (importError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(importError ?: "", color = TvRed, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showImportDialog = false }) {
                            Text("Batal", color = TvTextSecondary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val parsed = ScalpingRuntimeConfig.fromJsonString(importText.trim())
                                if (parsed != null) {
                                    onConfigChange(parsed, "CUSTOM")
                                    showImportDialog = false
                                    Toast.makeText(context, "Konfigurasi scalping berhasil diimpor!", Toast.LENGTH_SHORT).show()
                                } else {
                                    importError = "Format JSON tidak valid atau parsing gagal."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                        ) {
                            Text("Terapkan", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SensitivitySliderItem(
    label: String,
    jsonKey: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(label, color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(jsonKey, color = TvTextSecondary, fontSize = 9.sp)
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = TvGreen.copy(alpha = 0.15f),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    displayValue,
                    color = TvGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = TvGreen,
                activeTrackColor = TvGreen,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
        )
    }
}
