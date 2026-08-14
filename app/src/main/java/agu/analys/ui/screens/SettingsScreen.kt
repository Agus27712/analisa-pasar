package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvCardBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.viewmodel.TradingViewModel

@Composable
fun SettingsScreen(
    viewModel: TradingViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var groqInput by remember { mutableStateOf(viewModel.getGroqApiKey()) }
    var geminiInput by remember { mutableStateOf(viewModel.getGeminiApiKey()) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var geminiSaved by remember { mutableStateOf(false) }
    var showLearning by remember { mutableStateOf(false) }

    if (showLearning) {
        LearningScreen(viewModel = viewModel, onBack = { showLearning = false }, modifier = modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF0F1115)).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = TvTextPrimary) }
            Text("Settings & Tutorial", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
        }

        Button(onClick = { showLearning = true }, colors = ButtonDefaults.buttonColors(containerColor = TvGreen), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Icon(Icons.Default.MenuBook, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Mode Belajar Trading", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("GROQ API KEY (AUDIT AI)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Audit AI menggunakan OpenAI GPT-OSS 20B yang di-host oleh Groq.", fontSize = 12.sp, color = TvTextSecondary, lineHeight = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = groqInput, onValueChange = { groqInput = it; saved = false }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("gsk_... paste key Groq", color = TvTextSecondary) }, leadingIcon = { Icon(Icons.Default.Key, null, tint = TvTextSecondary) }, trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TvTextSecondary) } }, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TvGreen, unfocusedBorderColor = Color(0x33FFFFFF), focusedTextColor = TvTextPrimary, unfocusedTextColor = TvTextPrimary, cursorColor = TvGreen, focusedContainerColor = Color(0xFF1A1D24), unfocusedContainerColor = Color(0xFF1A1D24)), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = { viewModel.saveGroqApiKey(groqInput); saved = true }, colors = ButtonDefaults.buttonColors(containerColor = TvGreen), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (saved) { Icon(Icons.Default.CheckCircle, null, tint = Color.Black, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(if (saved) "Tersimpan" else "Simpan Groq API Key", fontWeight = FontWeight.Bold, color = Color.Black)
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("GEMINI API KEY (RINGKASAN CHART 24J)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Summary pergerakan chart & verdik 24 jam.", fontSize = 12.sp, color = TvTextSecondary, lineHeight = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = geminiInput, onValueChange = { geminiInput = it; geminiSaved = false }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("AIzaSy... paste Gemini API key", color = TvTextSecondary) }, leadingIcon = { Icon(Icons.Default.Key, null, tint = TvTextSecondary) }, trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TvTextSecondary) } }, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF8E8CD8), unfocusedBorderColor = Color(0x33FFFFFF), focusedTextColor = TvTextPrimary, unfocusedTextColor = TvTextPrimary, cursorColor = Color(0xFF8E8CD8), focusedContainerColor = Color(0xFF1A1D24), unfocusedContainerColor = Color(0xFF1A1D24)), shape = RoundedCornerShape(12.dp))
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = { viewModel.saveGeminiApiKey(geminiInput); geminiSaved = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E8CD8)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (geminiSaved) { Icon(Icons.Default.CheckCircle, null, tint = Color.Black, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(if (geminiSaved) "Tersimpan" else "Simpan Gemini API Key", fontWeight = FontWeight.Bold, color = Color.Black)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Column(modifier = Modifier.fillMaxWidth().background(TvCardBackground, RoundedCornerShape(14.dp)).padding(16.dp)) {
            Text("SUMBER & AI", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvGreen, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(10.dp))
            TutorialStep("1", "BPS → scraping BRS publik untuk CPI/IHK nasional")
            TutorialStep("2", "Groq → OpenAI GPT-OSS 20B untuk audit AI")
            TutorialStep("3", "Gemini → ringkasan chart 24 jam")
            TutorialStep("4", "Snapshot BPS divalidasi dan di-cache sebelum dikirim ke AI")
            Spacer(Modifier.height(8.dp))
            Text("Market: INDODAX IDR. CPI/IHK: website resmi BPS. CPI hanya konteks AI, bukan perubahan skor trading.", fontSize = 12.sp, color = TvTextSecondary, lineHeight = 17.sp)
        }

        val context = LocalContext.current
        var repoInput by remember { mutableStateOf("user/nama-repo") }
        val releaseInfo by viewModel.githubReleaseInfo.collectAsState()
        val isChecking by viewModel.isCheckingUpdate.collectAsState()
        val downloadProgress by viewModel.updateDownloadProgress.collectAsState()

        Spacer(modifier = Modifier.height(16.dp))
        Column(modifier = Modifier.fillMaxWidth().background(TvCardBackground, RoundedCornerShape(14.dp)).padding(16.dp)) {
            Text("PEMBARUAN APLIKASI (GITHUB RELEASE)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvGreen, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Masukkan repositori GitHub (owner/repo) untuk mengunduh update APK terbaru langsung dari aplikasi.", fontSize = 12.sp, color = TvTextSecondary, lineHeight = 16.sp)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = repoInput,
                onValueChange = { repoInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("username/repository", color = TvTextSecondary) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TvGreen,
                    unfocusedBorderColor = Color(0x33FFFFFF),
                    focusedTextColor = TvTextPrimary,
                    unfocusedTextColor = TvTextPrimary,
                    cursorColor = TvGreen,
                    focusedContainerColor = Color(0xFF1A1D24),
                    unfocusedContainerColor = Color(0xFF1A1D24)
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.checkGitHubUpdate(repoInput.trim()) },
                enabled = !isChecking,
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isChecking) "Memeriksa Update..." else "Cek Update dari GitHub", fontWeight = FontWeight.Bold, color = Color.Black)
            }

            releaseInfo?.let { release ->
                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF101720), RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text("Versi Terbaru: ${release.tagName}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvGreen)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(release.releaseNotes, fontSize = 11.sp, color = TvTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))

                    val progress = downloadProgress
                    if (progress != null) {
                        if (progress in 0..99) {
                            Text("Mengunduh APK... $progress%", fontSize = 12.sp, color = TvGreen, fontWeight = FontWeight.Bold)
                        } else if (progress == 100) {
                            Text("Unduhan selesai. Membuka installer...", fontSize = 12.sp, color = TvGreen, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Gagal mengunduh APK.", fontSize = 12.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.downloadAndInstallUpdate(context, repoInput.trim()) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download & Install APK", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Column(modifier = Modifier.fillMaxWidth().background(TvCardBackground, RoundedCornerShape(14.dp)).padding(16.dp)) {
            Text("CATATAN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• Tidak ada data mock — sinyal menunggu candle nyata", fontSize = 12.sp, color = TvTextPrimary)
            Text("• Sinyal engine rule-based technical analysis, bukan jaminan profit", fontSize = 12.sp, color = TvTextPrimary)
            Text("• Jangan commit API key ke GitHub", fontSize = 12.sp, color = TvTextPrimary)
        }
    }
}

@Composable
private fun TutorialStep(num: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("$num.", fontWeight = FontWeight.Bold, color = TvGreen, fontSize = 13.sp, modifier = Modifier.width(20.dp))
        Text(text, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 18.sp)
    }
}
