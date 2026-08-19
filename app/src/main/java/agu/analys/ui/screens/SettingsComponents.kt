package agu.analys.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.BuildConfig
import agu.analys.config.AiProvider
import agu.analys.util.GitHubReleaseInfo
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.GitHubUpdater
import agu.analys.util.MarketDataCache

@Composable
fun SectionHeader(title: String) {
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
fun ModeOptionCard(
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
fun SensitivityChoice(
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

@Composable
fun AiProviderSettingsCard(
    provider: AiProvider,
    groqKey: String,
    geminiKey: String,
    onProviderChange: (AiProvider) -> Unit,
    onKeyChange: (String) -> Unit
) {
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
                    onProviderChange(AiProvider.GROQ)
                }
                SensitivityChoice(
                    label = "GEMINI 2.5",
                    selected = provider == AiProvider.GEMINI,
                    activeBg = Color(0xFF123D2A),
                    activeFg = TvGreen,
                    modifier = Modifier.weight(1f)
                ) {
                    onProviderChange(AiProvider.GEMINI)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("API Key ${provider.name}", color = TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = if (provider == AiProvider.GROQ) groqKey else geminiKey,
                onValueChange = onKeyChange,
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
}

@Composable
fun AppMaintenanceCard(
    context: Context,
    cacheCleared: Boolean,
    onClearCache: () -> Unit,
    updateRepo: String,
    onUpdateRepoChange: (String) -> Unit,
    updateToken: String,
    onUpdateTokenChange: (String) -> Unit,
    releaseInfo: GitHubReleaseInfo?,
    checkingUpdate: Boolean,
    updateStatus: String?,
    downloadProgress: Int?,
    onCheckUpdate: () -> Unit,
    onDownloadAndInstall: () -> Unit
) {
    var showAdvancedRepoSettings by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2836))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        MarketDataCache(context).clearAll()
                        onClearCache()
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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
                }

                Text(
                    text = if (showAdvancedRepoSettings) "Tutup Konfigurasi" else "Ubah Repo / Token",
                    color = Color(0xFF72B7FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { showAdvancedRepoSettings = !showAdvancedRepoSettings }
                        .padding(4.dp)
                )
            }

            // Input Konfigurasi Repository & Token
            if (showAdvancedRepoSettings) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B1015), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text("REPOSITORY GITHUB RILIS APK", color = Color(0xFF72B7FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = updateRepo,
                        onValueChange = onUpdateRepoChange,
                        singleLine = true,
                        placeholder = { Text("username/repository-rilis", fontSize = 11.sp, color = TvTextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvGreen,
                            unfocusedBorderColor = Color(0xFF2A3540),
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("GITHUB TOKEN / PAT (OPSIONAL)", color = Color(0xFF72B7FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("Wajib diisi jika repository rilis berstatus Private.", color = TvTextSecondary, fontSize = 9.5.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = updateToken,
                        onValueChange = onUpdateTokenChange,
                        singleLine = true,
                        placeholder = { Text("ghp_xxxx atau github_pat_xxxx", fontSize = 11.sp, color = TvTextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvGreen,
                            unfocusedBorderColor = Color(0xFF2A3540),
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (updateStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    updateStatus,
                    color = if (releaseInfo != null) TvGreen else Color(0xFFFFB300),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (releaseInfo != null && releaseInfo.releaseNotes.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B141C), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        "Catatan Rilis ${releaseInfo.tagName}:",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        releaseInfo.releaseNotes.take(300) + if (releaseInfo.releaseNotes.length > 300) "..." else "",
                        color = TvTextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCheckUpdate,
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
                        onClick = onDownloadAndInstall,
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
                        onClick = { GitHubUpdater.openGitHubReleasesPage(context, updateRepo) },
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
}
