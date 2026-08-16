package agu.analys.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.BuildConfig
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataConfiguration
import agu.analys.ui.theme.TvCardBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.AppPreferences
import agu.analys.util.MarketDataCache
import agu.analys.viewmodel.TradingViewModel

@Composable
fun SettingsScreen(viewModel: TradingViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var scalping by remember { mutableStateOf(prefs.isScalpingMode) }
    var provider by remember { mutableStateOf(prefs.aiProvider) }
    var groq by remember { mutableStateOf(prefs.groqApiKey) }
    var gemini by remember { mutableStateOf(prefs.geminiApiKey) }
    var fees by remember { mutableStateOf(prefs.tradingFees) }
    var saved by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    var showLearning by remember { mutableStateOf(false) }

    if (showLearning) {
        LearningScreen(viewModel = viewModel, onBack = { showLearning = false }, modifier = modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF0F1115)).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = TvTextPrimary)
            }
            Spacer(Modifier.width(2.dp))
            Column {
                Text("Pengaturan", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = TvTextPrimary)
                Text("Konfigurasi analisis dan data", fontSize = 10.sp, color = TvTextSecondary)
            }
        }
        Spacer(Modifier.height(14.dp))

        SectionLabel("MODE ANALISIS")
        Text("Pilih mode utama. Mode tidak mengubah sumber data market.", fontSize = 11.sp, color = TvTextSecondary)
        Spacer(Modifier.height(8.dp))

        ModeCard(
            title = "SCALPING", badge = "BUY MODE", selected = scalping, accent = TvGreen,
            bullets = listOf("Bias 1H", "Setup 15M", "Trigger 1M"),
            description = "BUY-only · entry jangka pendek"
        ) { scalping = true; saved = false }

        Spacer(Modifier.height(8.dp))
        ModeCard(
            title = "SWING", badge = "ANALISIS TREND", selected = !scalping, accent = Color(0xFF72B7FF),
            bullets = listOf("Timeframe lebih besar", "Struktur trend", "Posisi swing"),
            description = "Trend jangka menengah · bukan BUY-only"
        ) { scalping = false; saved = false }

        Spacer(Modifier.height(12.dp))
        SettingCard("FEE INDODAX · ALL-IN") {
            Text("Dipakai untuk menghitung net profit dan net R:R.", fontSize = 10.sp, color = TvTextSecondary)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeeField("BUY MAKER", fees.buyMakerPct, Modifier.weight(1f)) { fees = fees.copy(buyMakerPct = it); saved = false }
                FeeField("BUY TAKER", fees.buyTakerPct, Modifier.weight(1f)) { fees = fees.copy(buyTakerPct = it); saved = false }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeeField("SELL MAKER", fees.sellMakerPct, Modifier.weight(1f)) { fees = fees.copy(sellMakerPct = it); saved = false }
                FeeField("SELL TAKER", fees.sellTakerPct, Modifier.weight(1f)) { fees = fees.copy(sellTakerPct = it); saved = false }
            }
        }

        Spacer(Modifier.height(9.dp))
        SettingCard("AI API") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChoice("GROQ", provider == AiProvider.GROQ, Color(0xFF123D2A), TvGreen, Modifier.weight(1f)) { provider = AiProvider.GROQ; saved = false }
                ModeChoice("GEMINI", provider == AiProvider.GEMINI, Color(0xFF15304B), Color(0xFF72B7FF), Modifier.weight(1f)) { provider = AiProvider.GEMINI; saved = false }
            }
            Spacer(Modifier.height(7.dp))
            Text("Provider AI hanya pendukung analisis, bukan penentu market condition.", fontSize = 10.sp, color = TvTextSecondary)
            Spacer(Modifier.height(6.dp))
            if (provider == AiProvider.GROQ) {
                OutlinedTextField(value = groq, onValueChange = { groq = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Groq API Key") }, visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.Key, null) })
            } else {
                OutlinedTextField(value = gemini, onValueChange = { gemini = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Gemini API Key") }, visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.Key, null) })
            }
        }

        Spacer(Modifier.height(9.dp))
        SettingCard("LEARNING") {
            Text("Pelajari alasan engine tanpa mengubah sinyal.", fontSize = 11.sp, color = TvTextSecondary)
            Spacer(Modifier.height(6.dp))
            Button(onClick = { showLearning = true }, colors = ButtonDefaults.buttonColors(containerColor = TvCardBackground), modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 10.dp)) {
                Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Mode Belajar Trading", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(9.dp))
        SettingCard("DATA") {
            DataRow("Sumber Data", MarketDataConfiguration.activeSource.label)
            DataRow("Tipe Data", "Realtime · WebSocket")
            Text("Produksi hanya memakai data market Indodax real.", color = TvTextSecondary, fontSize = 9.sp)
        }

        Spacer(Modifier.height(9.dp))
        SettingCard("PEMBARUAN APK") {
            Text("Versi ${BuildConfig.VERSION_NAME}", color = TvTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            val releaseInfo by viewModel.githubReleaseInfo.collectAsState()
            val checking by viewModel.isCheckingUpdate.collectAsState()
            val progress by viewModel.updateDownloadProgress.collectAsState()
            Spacer(Modifier.height(6.dp))
            Button(onClick = { viewModel.checkGitHubUpdate(agu.analys.util.GitHubUpdater.DEFAULT_REPO) }, enabled = !checking, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = TvGreen)) {
                Icon(Icons.Default.SystemUpdate, null, tint = Color.Black, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (checking) "Memeriksa..." else "Cek Update", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            releaseInfo?.let { release ->
                Spacer(Modifier.height(6.dp))
                Text("Update tersedia · ${release.tagName}", color = TvGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (progress == null) {
                    Button(onClick = { viewModel.downloadAndInstallUpdate(context, agu.analys.util.GitHubUpdater.DEFAULT_REPO) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5))) { Text("Download & Install", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                } else {
                    Text(if (progress == 100) "Unduhan selesai, installer dibuka." else "Mengunduh... $progress%", color = TvGreen, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(9.dp))
        SettingCard("CACHE APLIKASI") {
            Text("Membersihkan cache market. Mode, fee, API key, dan watchlist tetap tersimpan.", color = TvTextSecondary, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
            Button(onClick = { MarketDataCache(context).clearAll(); cacheCleared = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3540))) {
                Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (cacheCleared) "Cache dibersihkan" else "Bersihkan Cache", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { prefs.isScalpingMode = scalping; prefs.aiProvider = provider; prefs.groqApiKey = groq; prefs.geminiApiKey = gemini; prefs.tradingFees = fees; viewModel.setScalpingMode(scalping); saved = true },
            modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = TvGreen), shape = RoundedCornerShape(11.dp)
        ) {
            if (saved) Icon(Icons.Default.CheckCircle, null, tint = Color.Black, Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp)); Text(if (saved) "Tersimpan" else "Simpan Perubahan", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable private fun SectionLabel(text: String) { Text(text, color = Color(0xFF72B7FF), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp) }

@Composable
private fun ModeCard(title: String, badge: String, selected: Boolean, accent: Color, bullets: List<String>, description: String, onClick: () -> Unit) {
    val border = if (selected) accent.copy(alpha = 0.55f) else Color(0xFF2A3540)
    val bg = if (selected) accent.copy(alpha = 0.09f) else Color(0xFF121820)
    Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(13.dp)).border(BorderStroke(1.3.dp, border), RoundedCornerShape(13.dp)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(7.dp))
                Box(Modifier.background(accent.copy(alpha = 0.16f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(badge, color = accent, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold) }
            }
            Spacer(Modifier.height(4.dp))
            Text(description, color = TvTextSecondary, fontSize = 10.sp)
            Spacer(Modifier.height(5.dp))
            Text(bullets.joinToString("  ·  "), color = TvTextSecondary, fontSize = 10.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(20.dp).border(2.dp, if (selected) accent else Color(0xFF3A4550), CircleShape).padding(4.dp).then(if (selected) Modifier.background(accent, CircleShape) else Modifier))
    }
}

@Composable private fun DataRow(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = TvTextSecondary, fontSize = 11.sp); Text(value, color = TvTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }

@Composable private fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), colors = CardDefaults.cardColors(containerColor = TvCardBackground)) { Column(Modifier.padding(12.dp)) { Text(title, color = TvGreen, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp); Spacer(Modifier.height(7.dp)); content() } } }

@Composable private fun ModeChoice(label: String, selected: Boolean, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) { Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = if (selected) bg else Color(0xFF1A2028)), shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(vertical = 9.dp)) { Text(label, color = if (selected) fg else TvTextSecondary, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp) } }

@Composable private fun FeeField(label: String, value: Double, modifier: Modifier = Modifier, onValue: (Double) -> Unit) { OutlinedTextField(value = String.format("%.2f", value), onValueChange = { it.replace(',', '.').toDoubleOrNull()?.let(onValue) }, modifier = modifier.padding(bottom = 5.dp), singleLine = true, label = { Text(label, fontSize = 10.sp) }, suffix = { Text("%", fontSize = 10.sp) }) }
