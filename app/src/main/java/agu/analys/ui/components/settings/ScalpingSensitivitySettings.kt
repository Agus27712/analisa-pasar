package agu.analys.ui.components.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
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
import kotlin.math.roundToInt

data class SliderGuideInfo(
    val title: String,
    val jsonKey: String,
    val summary: String,
    val plusEffect: String,
    val minusEffect: String,
    val defaultVal: String
)

private val SLIDER_GUIDES = mapOf(
    "min_score_long" to SliderGuideInfo(
        title = "Skor Minimal Beli (LONG)",
        jsonKey = "min_score_long",
        summary = "Syarat nilai kelulusan minimal agar suatu koin direkomendasikan untuk dibeli (skala skor 0 sampai 100).",
        plusEffect = "Sinyal beli lebih jarang muncul, namun koin yang lolos memiliki kualitas teknikal dan konfluensi yang lebih prima.",
        minusEffect = "Sinyal beli lebih sering muncul, cocok saat pasar sedang ramai, namun risiko sinyal palsu sedikit meningkat.",
        defaultVal = "60 (Standar)"
    ),
    "min_score_strong" to SliderGuideInfo(
        title = "Skor Kategori Kuat (STRONG)",
        jsonKey = "min_score_strong",
        summary = "Ambang batas skor untuk memberi label 'STRONG ENTRY' dengan warna hijau mencolok.",
        plusEffect = "Memerlukan konfirmasi momentum dan volume yang sangat dominan sebelum sistem memberi label rekomendasi tertinggi.",
        minusEffect = "Lebih mudah melabeli sinyal sebagai rekomendasi kuat.",
        defaultVal = "75 (Standar)"
    ),
    "min_score_breakout_retest" to SliderGuideInfo(
        title = "Skor Khusus Retest Breakout",
        jsonKey = "min_score_breakout_retest",
        summary = "Ambang batas nilai khusus saat harga menembus batas atas (resisten) lalu memantul kembali untuk menguji support.",
        plusEffect = "Melindungi modal dari jebakan breakout palsu (fake breakout) yang sering menjebak trader pada koin bervolatilitas tinggi.",
        minusEffect = "Memperbolehkan masuk posisi pada pola retest yang lebih longgar.",
        defaultVal = "75 (Disarankan tinggi)"
    ),
    "min_net_rr" to SliderGuideInfo(
        title = "Rasio Profit vs Risiko Bersih (Net R:R)",
        jsonKey = "min_net_rr",
        summary = "Perbandingan potensi keuntungan dibanding risiko rugi (Risk-to-Reward) SETELAH dikurangi biaya fee bursa dan pajak all-in.",
        plusEffect = "Hanya menerima trade yang potensi cuannya jauh melampaui risiko cut-loss-nya. Menjaga akun agar tidak rugi tergerus fee.",
        minusEffect = "Menerima trade dengan ruang target tipis (waspada fee transaksi menggerus hasil akhir).",
        defaultVal = "1.15x (Setara 1:1.15)"
    ),
    "target_net_rr" to SliderGuideInfo(
        title = "Target Profit Bersih Ideal",
        jsonKey = "target_net_rr",
        summary = "Target keuntungan ideal yang dibidik untuk level Take Profit (TP2 / trailing baseline) di atas total friksi transaksi.",
        plusEffect = "Membidik target keuntungan lebih tebal per transaksi, membutuhkan pergerakan harga yang lebih panjang.",
        minusEffect = "Target keuntungan lebih cepat tersentuh, cocok untuk kondisi pergerakan harga yang pendek-pendek.",
        defaultVal = "1.25x (Setara 1:1.25)"
    ),
    "rvol_breakout_min" to SliderGuideInfo(
        title = "Lonjakan Volume Breakout (RVOL)",
        jsonKey = "rvol_breakout_min",
        summary = "Kelipatan volume transaksi saat harga menembus level resisten dibandingkan volume rata-rata 20 candle sebelumnya.",
        plusEffect = "Wajib ada dorongan volume transaksi besar (misal 1.8x lipat) agar penembusan dianggap valid dan bukan manipulasi volume tipis.",
        minusEffect = "Penembusan harga dengan volume biasa saja sudah dianggap sah.",
        defaultVal = "1.5x (Volume 50% di atas rata-rata)"
    ),
    "rvol_setup_min" to SliderGuideInfo(
        title = "Volume Pantulan Setup (RVOL)",
        jsonKey = "rvol_setup_min",
        summary = "Lonjakan volume minimal saat harga memantul dari dasar (support sweep atau pullback recovery).",
        plusEffect = "Memastikan adanya minat beli nyata dari pasar saat harga berada di area lantai support.",
        minusEffect = "Pantulan harga dengan volume relatif sedang tetap diizinkan membentuk setup.",
        defaultVal = "1.2x (Volume 20% di atas rata-rata)"
    ),
    "rvol_retest_min" to SliderGuideInfo(
        title = "Volume Konfirmasi Retest (RVOL)",
        jsonKey = "rvol_retest_min",
        summary = "Volume transaksi yang wajib hadir saat harga menguji kembali garis breakout.",
        plusEffect = "Mencegah masuk pada retest yang sepi pembeli (retest sepi rawan ambles kembali ke bawah).",
        minusEffect = "Toleran terhadap pantulan retest bervolume lebih rendah.",
        defaultVal = "1.5x (Ketelitian tinggi)"
    ),
    "chop_hard_ranging" to SliderGuideInfo(
        title = "Batas Pasar Galau (CHOP Hard Ranging)",
        jsonKey = "chop_hard_ranging",
        summary = "Indikator pasar mondar-mandir / sideways tanpa tren (Choppiness Index). Angka tinggi berarti pasar sangat tidak beraturan.",
        plusEffect = "Lebih cepat menahan/memblokir sinyal tren jika pasar terdeteksi mulai bergerak menyamping tanpa arah jelas.",
        minusEffect = "Tetap mengizinkan pencarian sinyal meskipun kondisi pasar sedang agak choppy.",
        defaultVal = "65 (Nilai 60-70 = zona sideways ketat)"
    ),
    "retest_max_dist_pct" to SliderGuideInfo(
        title = "Jarak Toleransi Retest (%)",
        jsonKey = "retest_max_dist_pct",
        summary = "Jarak maksimal (dalam persen) antara harga saat ini dengan garis support penembusan agar masih dianggap 'sedang retest'.",
        plusEffect = "Harga harus sangat dekat/presisi menyentuh garis support (misal selisih di bawah 1.0%).",
        minusEffect = "Memberikan ruang toleransi pantulan yang lebih lebar dari garis support.",
        defaultVal = "1.2% (Toleransi wajar kripto)"
    ),
    "retest_min_structure_strength" to SliderGuideInfo(
        title = "Kekokohan Garis Struktur (Strength)",
        jsonKey = "retest_min_structure_strength",
        summary = "Kualitas garis support/resisten (skor 0-100) berdasarkan seberapa sering garis tersebut berhasil menahan harga di masa lalu.",
        plusEffect = "Hanya mempercayai level-level penting yang sudah berkali-kali teruji kuat oleh pasar.",
        minusEffect = "Level support/resisten yang baru terbentuk sebentar pun dapat dijadikan patokan pantulan.",
        defaultVal = "60 (Struktur solid)"
    )
)

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
    var activeGuideKey by remember { mutableStateOf<String?>(null) }

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
            "Sesuaikan ambang batas keputusan engine. Tombol (+) dan (-) memudahkan fine-tuning tanpa perlu menyeret slider. Ketuk ikon (i) di tiap pengaturan untuk melihat penjelasan bahasa awam.",
            color = TvTextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(12.dp))

        // Preset Selector Row
        Text("PILIHAN PRESET", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                    step = 1f,
                    displayValue = "${config.minScoreLong}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minScoreLong = newVal.roundToInt()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "min_score_long" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 2. Min Score Strong
                SensitivitySliderItem(
                    label = "Skor STRONG",
                    jsonKey = "min_score_strong",
                    value = config.minScoreStrong.toFloat(),
                    range = 60f..90f,
                    step = 1f,
                    displayValue = "${config.minScoreStrong}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minScoreStrong = newVal.roundToInt()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "min_score_strong" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 3. Min Score Retest
                SensitivitySliderItem(
                    label = "Skor Retest",
                    jsonKey = "min_score_breakout_retest",
                    value = config.minScoreBreakoutRetest.toFloat(),
                    range = 60f..90f,
                    step = 1f,
                    displayValue = "${config.minScoreBreakoutRetest}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minScoreBreakoutRetest = newVal.roundToInt()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "min_score_breakout_retest" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 4. Min Net R:R
                SensitivitySliderItem(
                    label = "Min Net R:R",
                    jsonKey = "min_net_rr",
                    value = config.minNetRr.toFloat(),
                    range = 0.8f..2.0f,
                    step = 0.05f,
                    displayValue = String.format(Locale.US, "%.2fR", config.minNetRr),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 100.0).roundToInt() / 100.0
                        onConfigChange(config.copy(minNetRr = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "min_net_rr" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 5. Target Net R:R
                SensitivitySliderItem(
                    label = "Target Net R:R",
                    jsonKey = "target_net_rr",
                    value = config.targetNetRr.toFloat(),
                    range = 1.0f..2.5f,
                    step = 0.05f,
                    displayValue = String.format(Locale.US, "%.2fR", config.targetNetRr),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 100.0).roundToInt() / 100.0
                        onConfigChange(config.copy(targetNetRr = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "target_net_rr" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 6. RVOL Breakout
                SensitivitySliderItem(
                    label = "RVOL Breakout",
                    jsonKey = "rvol_breakout_min",
                    value = config.rvolBreakoutMin.toFloat(),
                    range = 1.0f..2.5f,
                    step = 0.1f,
                    displayValue = String.format(Locale.US, "%.1fx", config.rvolBreakoutMin),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 10.0).roundToInt() / 10.0
                        onConfigChange(config.copy(rvolBreakoutMin = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "rvol_breakout_min" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 7. RVOL Setup
                SensitivitySliderItem(
                    label = "RVOL Setup",
                    jsonKey = "rvol_setup_min",
                    value = config.rvolSetupMin.toFloat(),
                    range = 0.8f..2.0f,
                    step = 0.1f,
                    displayValue = String.format(Locale.US, "%.1fx", config.rvolSetupMin),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 10.0).roundToInt() / 10.0
                        onConfigChange(config.copy(rvolSetupMin = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "rvol_setup_min" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 8. RVOL Retest
                SensitivitySliderItem(
                    label = "RVOL Retest",
                    jsonKey = "rvol_retest_min",
                    value = config.rvolRetestMin.toFloat(),
                    range = 1.0f..2.5f,
                    step = 0.1f,
                    displayValue = String.format(Locale.US, "%.1fx", config.rvolRetestMin),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 10.0).roundToInt() / 10.0
                        onConfigChange(config.copy(rvolRetestMin = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "rvol_retest_min" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 9. CHOP Hard Ranging
                SensitivitySliderItem(
                    label = "CHOP Hard Ranging",
                    jsonKey = "chop_hard_ranging",
                    value = config.chopHardRanging.toFloat(),
                    range = 55f..75f,
                    step = 1f,
                    displayValue = String.format(Locale.US, "%.0f", config.chopHardRanging),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(chopHardRanging = newVal.toDouble()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "chop_hard_ranging" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 10. Jarak Max Retest %
                SensitivitySliderItem(
                    label = "Jarak Max Retest %",
                    jsonKey = "retest_max_dist_pct",
                    value = config.retestMaxDistPct.toFloat(),
                    range = 0.5f..2.5f,
                    step = 0.1f,
                    displayValue = String.format(Locale.US, "%.1f%%", config.retestMaxDistPct),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 10.0).roundToInt() / 10.0
                        onConfigChange(config.copy(retestMaxDistPct = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "retest_max_dist_pct" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 11. Strength Struktur Retest
                SensitivitySliderItem(
                    label = "Strength Struktur Retest",
                    jsonKey = "retest_min_structure_strength",
                    value = config.retestMinStructureStrength.toFloat(),
                    range = 40f..80f,
                    step = 5f,
                    displayValue = "${config.retestMinStructureStrength}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(retestMinStructureStrength = newVal.roundToInt()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "retest_min_structure_strength" }
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

    // Floating Info Popup (Dialog)
    activeGuideKey?.let { key ->
        val info = SLIDER_GUIDES[key]
        if (info != null) {
            Dialog(onDismissRequest = { activeGuideKey = null }) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = TvSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = TvCyan.copy(alpha = 0.15f),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = null,
                                            tint = TvCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        info.title,
                                        color = TvTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        info.jsonKey,
                                        color = TvTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            IconButton(
                                onClick = { activeGuideKey = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Penjelasan Sederhana
                        Text(
                            "PENJELASAN AWAM",
                            color = TvCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info.summary,
                            color = TvTextPrimary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        // Efek Jika Dinaikkan (+)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = TvGreen.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TvGreen.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🟢 Jika Dinaikkan (+)", color = TvGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(info.plusEffect, color = TvTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Efek Jika Diturunkan (-)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = TvAmber.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TvAmber.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🟡 Jika Diturunkan (-)", color = TvAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(info.minusEffect, color = TvTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Nilai Disarankan: ${info.defaultVal}",
                                color = TvTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Button(
                                onClick = { activeGuideKey = null },
                                colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Mengerti", color = TvTextPrimary, fontSize = 11.sp)
                            }
                        }
                    }
                }
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
    step: Float,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    onShowInfo: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(label, color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onShowInfo() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Penjelasan $label",
                            tint = TvCyan.copy(alpha = 0.8f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
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

        Spacer(Modifier.height(2.dp))

        // Row containing Minus [-], Slider, and Plus [+]
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Minus Button
            Surface(
                shape = CircleShape,
                color = TvSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable {
                        val nextVal = (value - step).coerceIn(range.start, range.endInclusive)
                        onValueChange(nextVal)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Kurangi $label",
                        tint = if (value > range.start) TvTextPrimary else TvTextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Slider in center
            val stepsCount = ((range.endInclusive - range.start) / step).roundToInt() - 1
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onValueChange,
                valueRange = range,
                steps = if (stepsCount > 0) stepsCount else 0,
                colors = SliderDefaults.colors(
                    thumbColor = TvGreen,
                    activeTrackColor = TvGreen,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
            )

            // Plus Button
            Surface(
                shape = CircleShape,
                color = TvSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable {
                        val nextVal = (value + step).coerceIn(range.start, range.endInclusive)
                        onValueChange(nextVal)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Tambah $label",
                        tint = if (value < range.endInclusive) TvTextPrimary else TvTextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
