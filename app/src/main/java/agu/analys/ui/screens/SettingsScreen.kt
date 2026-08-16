package agu.analys.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.BuildConfig
import agu.analys.config.AiProvider
import agu.analys.config.ScalpingSensitivity
import agu.analys.util.MarketDataCache
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.AppPreferences
import agu.analys.util.GitHubUpdater
import agu.analys.viewmodel.TradingViewModel

@Composable
fun SettingsScreen(viewModel: TradingViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var scalping by remember { mutableStateOf(prefs.isScalpingMode) }
    var sensitivity by remember { mutableStateOf(prefs.scalpingSensitivity) }
    var provider by remember { mutableStateOf(prefs.aiProvider) }
    var groq by remember { mutableStateOf(prefs.groqApiKey) }
    var gemini by remember { mutableStateOf(prefs.geminiApiKey) }
    var buyMakerFee by remember { mutableStateOf(prefs.tradingFees.buyMakerPct.toString()) }
    var buyTakerFee by remember { mutableStateOf(prefs.tradingFees.buyTakerPct.toString()) }
    var sellMakerFee by remember { mutableStateOf(prefs.tradingFees.sellMakerPct.toString()) }
    var sellTakerFee by remember { mutableStateOf(prefs.tradingFees.sellTakerPct.toString()) }
    var saved by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }

    val completedLessons = remember { prefs.getCompletedLearningLessons() }
    val releaseInfo by viewModel.githubReleaseInfo.collectAsState()
    val checkingUpdate by viewModel.isCheckingUpdate.collectAsState()
    val updateStatus by viewModel.updateCheckStatus.collectAsState()
    val downloadProgress by viewModel.updateDownloadProgress.collectAsState()

    Column(
        modifier
            .fillMaxSize()
            .background(TvBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Top Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "Kembali",
                    tint = TvTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Pengaturan",
                color = TvTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(14.dp))

        // SECTION: MODE PEMBELAJARAN (READING MODE)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.openLearning() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101E2E)),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF72B7FF))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color(0xFF1E3A5F), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = Color(0xFF72B7FF),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "MODE BELAJAR ANALISIS PASAR",
                        color = Color(0xFF72B7FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "11 Materi: Candlestick, Support & Resistance, Structure HH/HL, Indikator, Risk Management & Plan.",
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Progress: ${completedLessons.size}/11 Materi selesai · Ketuk untuk membaca",
                        color = TvGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF72B7FF), modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // SECTION 1: MODE ANALISIS TRADING
        SectionHeader("MODE ANALISIS TRADING")
        Text(
            "Sesuaikan strategi perhitungan engine sinyal dan timeframe aktif.",
            color = TvTextSecondary,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))

        // Card SCALPING (BUY MODE)
        ModeOptionCard(
            title = "SCALPING",
            tag = "BUY MODE",
            tagBg = Color(0xFF123D2A),
            tagFg = TvGreen,
            isSelected = scalping,
            desc = "Mencari peluang BUY jangka pendek (1M – 15M) dengan eksekusi cepat dan filter MTF.",
            bullets = listOf("Bias: 1H (Bullish)", "Setup: 15M", "Trigger: 1M", "Fokus: Quick Entry & Tight SL"),
            onClick = { scalping = true; saved = false }
        )

        // Sensitivitas Scalping jika scalping dipilih
        if (scalping) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("SENSITIVITAS SCALPING", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF72B7FF))
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SensitivityChoice(
                            label = "KONSERVATIF",
                            selected = sensitivity == ScalpingSensitivity.CONSERVATIVE,
                            activeBg = Color(0xFF123D2A),
                            activeFg = TvGreen,
                            modifier = Modifier.weight(1f)
                        ) {
                            sensitivity = ScalpingSensitivity.CONSERVATIVE
                            saved = false
                        }
                        SensitivityChoice(
                            label = "AGRESIF",
                            selected = sensitivity == ScalpingSensitivity.AGGRESSIVE,
                            activeBg = Color(0xFF3D3212),
                            activeFg = Color(0xFFFFB300),
                            modifier = Modifier.weight(1f)
                        ) {
                            sensitivity = ScalpingSensitivity.AGGRESSIVE
                            saved = false
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (sensitivity == ScalpingSensitivity.CONSERVATIVE)
                            "Konservatif: Filter ketat MTF 1H+15M+1M, anti-false breakout, Net R:R ≥ 1.2."
                        else
                            "Agresif: Peluang lebih sering, RSI 35–66, volume 0.85x, tangkap quick pump lebih awal.",
                        fontSize = 10.sp,
                        color = if (sensitivity == ScalpingSensitivity.CONSERVATIVE) TvGreen else Color(0xFFFFB300),
                        lineHeight = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Card SWING (ANALISIS TREND)
        ModeOptionCard(
            title = "SWING",
            tag = "ANALISIS TREND",
            tagBg = Color(0xFF122840),
            tagFg = Color(0xFF72B7FF),
            isSelected = !scalping,
            desc = "Menganalisis trend jangka menengah (1H – 1D) untuk posisi swing yang lebih tenang.",
            bullets = listOf("Timeframe: 1H & 1D", "Analisis struktur trend (HH/HL/LH/LL)", "Fokus: Support / Resistance & Demand Zone"),
            onClick = { scalping = false; saved = false }
        )

        Spacer(Modifier.height(16.dp))

        // SECTION 2: INTEGRASI AI ASSISTANT
        SectionHeader("INTEGRASI AI ASSISTANT")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Pilih Model AI", color = TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SensitivityChoice(
                        label = "GROQ (LLaMA 3.3)",
                        selected = provider == AiProvider.GROQ,
                        activeBg = Color(0xFF122840),
                        activeFg = Color(0xFF72B7FF),
                        modifier = Modifier.weight(1f)
                    ) {
                        provider = AiProvider.GROQ
                        saved = false
                    }
                    SensitivityChoice(
                        label = "GEMINI 2.5",
                        selected = provider == AiProvider.GEMINI,
                        activeBg = Color(0xFF123D2A),
                        activeFg = TvGreen,
                        modifier = Modifier.weight(1f)
                    ) {
                        provider = AiProvider.GEMINI
                        saved = false
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("API Key ${provider.name}", color = TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = if (provider == AiProvider.GROQ) groq else gemini,
                    onValueChange = {
                        if (provider == AiProvider.GROQ) groq = it else gemini = it
                        saved = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Masukkan API Key opsional...", color = TvTextSecondary, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvGreen,
                        unfocusedBorderColor = Color(0xFF2A3540),
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text("Kunci disimpan aman secara lokal di perangkat Anda.", color = TvTextSecondary, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // SECTION 3: PENGATURAN BIAYA TRADING (FEE INDODAX)
        SectionHeader("BIAYA TRADING (NET R:R & RADAR STATUS)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Digunakan untuk menghitung estimasi biaya transaksi di Card Radar Live dan Net Risk-to-Reward riil.",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Beli Maker (%) - Limit", color = TvTextSecondary, fontSize = 10.sp)
                        Spacer(Modifier.height(3.dp))
                        OutlinedTextField(
                            value = buyMakerFee,
                            onValueChange = { buyMakerFee = it; saved = false },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TvGreen,
                                unfocusedBorderColor = Color(0xFF2A3540),
                                focusedTextColor = TvTextPrimary,
                                unfocusedTextColor = TvTextPrimary
                            )
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Beli Taker (%) - Instant", color = TvTextSecondary, fontSize = 10.sp)
                        Spacer(Modifier.height(3.dp))
                        OutlinedTextField(
                            value = buyTakerFee,
                            onValueChange = { buyTakerFee = it; saved = false },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TvGreen,
                                unfocusedBorderColor = Color(0xFF2A3540),
                                focusedTextColor = TvTextPrimary,
                                unfocusedTextColor = TvTextPrimary
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Jual Maker (%) - Limit", color = TvTextSecondary, fontSize = 10.sp)
                        Spacer(Modifier.height(3.dp))
                        OutlinedTextField(
                            value = sellMakerFee,
                            onValueChange = { sellMakerFee = it; saved = false },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TvGreen,
                                unfocusedBorderColor = Color(0xFF2A3540),
                                focusedTextColor = TvTextPrimary,
                                unfocusedTextColor = TvTextPrimary
                            )
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Jual Taker (%) - Instant", color = TvTextSecondary, fontSize = 10.sp)
                        Spacer(Modifier.height(3.dp))
                        OutlinedTextField(
                            value = sellTakerFee,
                            onValueChange = { sellTakerFee = it; saved = false },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TvGreen,
                                unfocusedBorderColor = Color(0xFF2A3540),
                                focusedTextColor = TvTextPrimary,
                                unfocusedTextColor = TvTextPrimary
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // SECTION 4: PEMELIHARAAN & PEMBARUAN APLIKASI
        SectionHeader("PEMELIHARAAN & UPDATE")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
        ) {
            Column(Modifier.padding(14.dp)) {
                // Bersihkan Cache
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            MarketDataCache(context).clearAll()
                            cacheCleared = true
                            Toast.makeText(context, "Cache offline pasar berhasil dibersihkan", Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Bersihkan Cache Data Pasar", color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Hapus data candle dan tick tersimpan lokal", color = TvTextSecondary, fontSize = 10.sp)
                    }
                    Text(
                        if (cacheCleared) "0,00 MB (Bersih)" else "Bersihkan >",
                        color = if (cacheCleared) TvGreen else Color(0xFF72B7FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(10.dp))
                Divider(color = Color(0xFF1E2836), thickness = 0.5.dp)
                Spacer(Modifier.height(10.dp))

                // Pembaruan Aplikasi (GitHub Updater)
                Text(
                    "Pembaruan Aplikasi",
                    color = TvTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Versi terpasang: v${BuildConfig.VERSION_NAME}",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )

                if (updateStatus != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        updateStatus!!,
                        color = if (releaseInfo != null) TvGreen else Color(0xFFFFB300),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.checkGitHubUpdate(context) },
                        enabled = !checkingUpdate,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2836))
                    ) {
                        if (checkingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TvGreen, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(Icons.Default.SystemUpdate, null, modifier = Modifier.size(16.dp), tint = TvGreen)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (checkingUpdate) "Memeriksa..." else "Cek Update",
                            color = TvTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    if (releaseInfo != null) {
                        Button(
                            onClick = { viewModel.downloadAndInstallUpdate(context) },
                            modifier = Modifier.weight(1.2f).height(42.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                        ) {
                            Text(
                                if (downloadProgress == null) "Unduh & Install"
                                else if (downloadProgress == 100) "Membuka APK..."
                                else "Unduh ($downloadProgress%)",
                                color = Color.Black,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { GitHubUpdater.openGitHubReleasesPage(context, GitHubUpdater.DEFAULT_REPO) },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF72B7FF)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
                        ) {
                            Text("Buka GitHub", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Action Buttons: Simpan Perubahan & Batal
        Button(
            onClick = {
                prefs.isScalpingMode = scalping
                prefs.scalpingSensitivity = sensitivity
                prefs.aiProvider = provider
                prefs.groqApiKey = groq
                prefs.geminiApiKey = gemini
                val currentFees = prefs.tradingFees
                val updatedFees = currentFees.copy(
                    buyMakerPct = buyMakerFee.toDoubleOrNull() ?: currentFees.buyMakerPct,
                    buyTakerPct = buyTakerFee.toDoubleOrNull() ?: currentFees.buyTakerPct,
                    sellMakerPct = sellMakerFee.toDoubleOrNull() ?: currentFees.sellMakerPct,
                    sellTakerPct = sellTakerFee.toDoubleOrNull() ?: currentFees.sellTakerPct
                )
                prefs.tradingFees = updatedFees
                viewModel.updateTradingFees(updatedFees)
                viewModel.setScalpingMode(scalping)
                viewModel.setScalpingSensitivity(sensitivity)
                saved = true
                Toast.makeText(context, "Pengaturan berhasil disimpan", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
            shape = RoundedCornerShape(10.dp)
        ) {
            if (saved) Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp), tint = Color.Black)
            Spacer(Modifier.width(6.dp))
            Text(
                if (saved) "Tersimpan" else "Simpan Perubahan",
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TvTextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
        ) {
            Text("Batal", color = TvTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF72B7FF),
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun ModeOptionCard(
    title: String,
    tag: String,
    tagBg: Color,
    tagFg: Color,
    isSelected: Boolean,
    desc: String,
    bullets: List<String>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
        border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) tagFg else Color(0xFF1E2836)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = TvTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(tagBg, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(tag, color = tagFg, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }

                // Radio Circle
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(
                            if (isSelected) tagFg else Color.Transparent,
                            CircleShape
                        )
                        .border(1.5.dp, if (isSelected) tagFg else Color(0xFF455A64), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(Modifier.size(8.dp).background(Color.Black, CircleShape))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(desc, color = TvTextSecondary, fontSize = 12.sp, lineHeight = 16.sp)

            Spacer(Modifier.height(8.dp))
            bullets.forEach { bullet ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text("•", color = tagFg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(bullet, color = TvTextPrimary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SensitivityChoice(
    label: String,
    selected: Boolean,
    activeBg: Color,
    activeFg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(if (selected) activeBg else Color(0xFF1E2836), RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) activeFg else Color(0xFF2A3540), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) activeFg else TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
