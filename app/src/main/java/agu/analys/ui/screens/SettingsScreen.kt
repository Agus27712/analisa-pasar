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
import agu.analys.config.MarketDataSource
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
    val currentMarketSource by viewModel.marketDataSource.collectAsState()
    var selectedSource by remember(currentMarketSource) { mutableStateOf(currentMarketSource) }
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

        // SECTION: SUMBER DATA PASAR (EXCHANGE SOURCE)
        SectionHeader("SUMBER PASAR (EXCHANGE)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2196F3).copy(alpha = 0.12f)),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF2196F3))
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "INDODAX",
                        color = Color(0xFF2196F3),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(0xFF2196F3), CircleShape)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Indodax Public API · Kline WebSocket · Pasar Kripto Indonesia (Pair IDR)",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "✓ Data live dari Indodax (Real API endpoint indodax.com & WebSocket kline). Tanpa mock data.",
            color = TvGreen,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )

        Spacer(Modifier.height(16.dp))

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
        AiProviderSettingsCard(
            provider = provider,
            groqKey = groq,
            geminiKey = gemini,
            onProviderChange = { provider = it; saved = false },
            onKeyChange = {
                if (provider == AiProvider.GROQ) groq = it else gemini = it
                saved = false
            }
        )

        Spacer(Modifier.height(16.dp))

        // SECTION 3: PENGATURAN BIAYA TRADING (FEE EXCHANGE)
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
        AppMaintenanceCard(
            context = context,
            cacheCleared = cacheCleared,
            onClearCache = { cacheCleared = true },
            releaseInfo = releaseInfo,
            checkingUpdate = checkingUpdate,
            updateStatus = updateStatus,
            downloadProgress = downloadProgress,
            onCheckUpdate = { viewModel.checkGitHubUpdate(context) },
            onDownloadAndInstall = { viewModel.downloadAndInstallUpdate(context) }
        )

        Spacer(Modifier.height(18.dp))

        // Action Buttons: Simpan Perubahan & Batal
        Button(
            onClick = {
                if (selectedSource != prefs.marketDataSource) {
                    viewModel.setMarketDataSource(selectedSource)
                }
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
private fun ExchangeSourceCard(
    name: String,
    subtitle: String,
    quoteAsset: String,
    isSelected: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) accentColor.copy(alpha = 0.15f) else Color(0xFF101720)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) accentColor else Color(0xFF1E2836)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    color = if (isSelected) accentColor else TvTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(accentColor, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = TvTextSecondary,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Quote: $quoteAsset",
                color = if (isSelected) Color.White else TvTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
