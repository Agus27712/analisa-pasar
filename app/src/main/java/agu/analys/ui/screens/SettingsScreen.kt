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
import agu.analys.config.StrategyMode
import agu.analys.util.MarketDataCache
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.ui.components.security.SecurityPinDialog
import agu.analys.ui.components.security.SetupRealApiDialog
import agu.analys.util.AppPreferences
import agu.analys.util.GitHubUpdater
import agu.analys.viewmodel.TradingViewModel

@Composable
fun SettingsScreen(viewModel: TradingViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val currentMarketSource by viewModel.marketDataSource.collectAsState()
    var selectedSource by remember(currentMarketSource) { mutableStateOf(currentMarketSource) }
    var strategyMode by remember { mutableStateOf(prefs.strategyMode) }
    var sensitivity by remember { mutableStateOf(prefs.scalpingSensitivity) }
    var provider by remember { mutableStateOf(prefs.aiProvider) }
    var groq by remember { mutableStateOf(prefs.groqApiKey) }
    var gemini by remember { mutableStateOf(prefs.geminiApiKey) }
    var buyMakerFee by remember { mutableStateOf(prefs.tradingFees.buyMakerPct.toString()) }
    var buyTakerFee by remember { mutableStateOf(prefs.tradingFees.buyTakerPct.toString()) }
    var sellMakerFee by remember { mutableStateOf(prefs.tradingFees.sellMakerPct.toString()) }
    var sellTakerFee by remember { mutableStateOf(prefs.tradingFees.sellTakerPct.toString()) }
    val isRealBuyMode by viewModel.isRealBuyMode.collectAsState()
    val isPinUnlocked by viewModel.isPinUnlocked.collectAsState()
    val userPublicIp by viewModel.userPublicIp.collectAsState()
    val failedPinAttempts by viewModel.failedPinAttempts.collectAsState()
    var hasPin by remember { mutableStateOf(viewModel.hasSecurityPin()) }

    var showSetupRealApiDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinDialogAction by remember { mutableStateOf(PinDialogAction.TOGGLE_REAL_BUY) }
    var pinDialogError by remember { mutableStateOf<String?>(null) }
    var pendingRealBuyToggle by remember { mutableStateOf(false) }
    var updateRepo by remember { mutableStateOf(prefs.updateRepo) }
    var updateToken by remember { mutableStateOf(prefs.updateGitHubToken) }
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

        // Card 1: SCALPING (BUY MODE)
        ModeOptionCard(
            title = "SCALPING",
            tag = "BUY MODE",
            tagBg = Color(0xFF123D2A),
            tagFg = TvGreen,
            isSelected = strategyMode == StrategyMode.SCALPING,
            desc = "Mencari peluang BUY jangka pendek (1M – 15M) dengan eksekusi cepat dan filter MTF.",
            bullets = listOf("Bias: 1H (Bullish)", "Setup: 15M", "Trigger: 1M", "Fokus: Quick Entry & Tight SL"),
            onClick = { strategyMode = StrategyMode.SCALPING; saved = false }
        )

        // Sensitivitas Scalping jika scalping dipilih
        if (strategyMode == StrategyMode.SCALPING) {
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            label = "SEIMBANG",
                            selected = sensitivity == ScalpingSensitivity.BALANCED,
                            activeBg = Color(0xFF132F4C),
                            activeFg = Color(0xFF6FB8FF),
                            modifier = Modifier.weight(1f)
                        ) {
                            sensitivity = ScalpingSensitivity.BALANCED
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
                        SensitivityChoice(
                            label = "AUTO (AI)",
                            selected = sensitivity == ScalpingSensitivity.DYNAMIC_AUTO,
                            activeBg = Color(0xFF2E1C4E),
                            activeFg = Color(0xFFB388FF),
                            modifier = Modifier.weight(1f)
                        ) {
                            sensitivity = ScalpingSensitivity.DYNAMIC_AUTO
                            saved = false
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (sensitivity) {
                            ScalpingSensitivity.CONSERVATIVE -> "Konservatif: Filter ketat MTF 1H+15M+1M, anti-false breakout, Net R:R ≥ 1.25."
                            ScalpingSensitivity.BALANCED -> "Seimbang (Rekomendasi): RSI 36–64, Walk-Forward, Net R:R ≥ 1.20."
                            ScalpingSensitivity.AGGRESSIVE -> "Agresif: Peluang lebih sering, RSI 35–68, volume 0.85x, quick pump."
                            ScalpingSensitivity.DYNAMIC_AUTO -> "Adaptif Otomatis: AI menyesuaikan threshold berdasarkan Rejim Pasar (Sideways/Volatile/Trending)."
                        },
                        fontSize = 10.sp,
                        color = when (sensitivity) {
                            ScalpingSensitivity.CONSERVATIVE -> TvGreen
                            ScalpingSensitivity.BALANCED -> Color(0xFF6FB8FF)
                            ScalpingSensitivity.AGGRESSIVE -> Color(0xFFFFB300)
                            ScalpingSensitivity.DYNAMIC_AUTO -> Color(0xFFB388FF)
                        },
                        lineHeight = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Card 2: SECOND-WAVE (2ND-WAVE HUNTER)
        ModeOptionCard(
            title = "SECOND-WAVE",
            tag = "2ND-WAVE HUNTER",
            tagBg = Color(0xFF0F3845),
            tagFg = Color(0xFF00E5FF),
            isSelected = strategyMode == StrategyMode.SECOND_WAVE,
            desc = "Membidik pantulan gelombang kedua pada koin pasca pump dengan koreksi terukur dan konfirmasi reclaim.",
            bullets = listOf(
                "Timeframe: 15M (Eksekusi) & 1H (Struktur)",
                "Kriteria: Prior Run > 20% & Pullback Drawdown 50–85%",
                "Sinyal: Base-Dip & Reclaim Entry",
                "Target: TP1 (+10–15%) & TP2 (+25–50%+)"
            ),
            onClick = { strategyMode = StrategyMode.SECOND_WAVE; saved = false }
        )

        Spacer(Modifier.height(10.dp))

        // Card 3: SWING (ANALISIS TREND)
        ModeOptionCard(
            title = "SWING",
            tag = "ANALISIS TREND",
            tagBg = Color(0xFF122840),
            tagFg = Color(0xFF72B7FF),
            isSelected = strategyMode == StrategyMode.SWING,
            desc = "Menganalisis trend jangka menengah (1H – 1D) untuk posisi swing yang lebih tenang.",
            bullets = listOf("Timeframe: 1H & 1D", "Analisis struktur trend (HH/HL/LH/LL)", "Fokus: Support / Resistance & Demand Zone"),
            onClick = { strategyMode = StrategyMode.SWING; saved = false }
        )

        Spacer(Modifier.height(16.dp))

        // SECTION: KEAMANAN & MODE BELI REAL
        SectionHeader("KEAMANAN & EKSEKUSI INDODAX")
        RealBuyModeAndSecurityCard(
            isRealBuyMode = isRealBuyMode,
            hasPin = hasPin,
            hasApiCredentials = prefs.hasIndodaxCredentials(),
            isPinUnlocked = isPinUnlocked,
            userPublicIp = userPublicIp,
            failedPinAttempts = failedPinAttempts,
            onToggleRealBuyMode = {
                if (!isRealBuyMode) {
                    if (!hasPin || !prefs.hasIndodaxCredentials()) {
                        showSetupRealApiDialog = true
                    } else {
                        pinDialogAction = PinDialogAction.TOGGLE_REAL_BUY
                        pinDialogError = null
                        pendingRealBuyToggle = true
                        showPinDialog = true
                    }
                } else {
                    viewModel.setRealBuyMode(false, "")
                    Toast.makeText(context, "Mode beralih ke SIMULASI.", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenSetupDialog = {
                showSetupRealApiDialog = true
            },
            onRequirePinUnlock = {
                pinDialogAction = PinDialogAction.UNLOCK_ONLY
                pinDialogError = null
                showPinDialog = true
            },
            onWipeCredentials = {
                viewModel.wipeSecurityCredentials()
                hasPin = false
                Toast.makeText(context, "Seluruh Kredensial API & PIN berhasil dihapus.", Toast.LENGTH_SHORT).show()
            },
            onCheckPublicIp = { viewModel.checkPublicIp() }
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
            updateRepo = updateRepo,
            onUpdateRepoChange = { updateRepo = it; saved = false },
            updateToken = updateToken,
            onUpdateTokenChange = { updateToken = it; saved = false },
            releaseInfo = releaseInfo,
            checkingUpdate = checkingUpdate,
            updateStatus = updateStatus,
            downloadProgress = downloadProgress,
            onCheckUpdate = { viewModel.checkGitHubUpdate(context, updateRepo, updateToken) },
            onDownloadAndInstall = { viewModel.downloadAndInstallUpdate(context, updateRepo, updateToken) }
        )

        Spacer(Modifier.height(18.dp))

        // Action Buttons: Simpan Perubahan & Batal
        Button(
            onClick = {
                if (selectedSource != prefs.marketDataSource) {
                    viewModel.setMarketDataSource(selectedSource)
                }
                prefs.strategyMode = strategyMode
                prefs.isScalpingMode = (strategyMode == StrategyMode.SCALPING)
                prefs.scalpingSensitivity = sensitivity
                prefs.aiProvider = provider
                prefs.groqApiKey = groq
                prefs.geminiApiKey = gemini
                prefs.updateRepo = updateRepo
                prefs.updateGitHubToken = updateToken
                val currentFees = prefs.tradingFees
                val updatedFees = currentFees.copy(
                    buyMakerPct = buyMakerFee.toDoubleOrNull() ?: currentFees.buyMakerPct,
                    buyTakerPct = buyTakerFee.toDoubleOrNull() ?: currentFees.buyTakerPct,
                    sellMakerPct = sellMakerFee.toDoubleOrNull() ?: currentFees.sellMakerPct,
                    sellTakerPct = sellTakerFee.toDoubleOrNull() ?: currentFees.sellTakerPct
                )
                prefs.tradingFees = updatedFees
                viewModel.updateTradingFees(updatedFees)
                viewModel.setStrategyMode(strategyMode)
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

    if (showPinDialog) {
        val title = when (pinDialogAction) {
            PinDialogAction.VERIFY_OLD_FOR_CHANGE -> "VERIFIKASI PIN SAAT INI"
            PinDialogAction.ENTER_NEW_PIN -> "MASUKKAN PIN BARU"
            PinDialogAction.CREATE_FIRST_PIN -> "ATUR PIN KEAMANAN BARU"
            PinDialogAction.TOGGLE_REAL_BUY -> "VERIFIKASI PIN KEAMANAN"
            PinDialogAction.UNLOCK_ONLY -> "VERIFIKASI PIN KEAMANAN"
        }
        val subtitle = when (pinDialogAction) {
            PinDialogAction.VERIFY_OLD_FOR_CHANGE -> "Masukkan PIN lama Anda terlebih dahulu untuk mengubah PIN."
            PinDialogAction.ENTER_NEW_PIN -> "Masukkan 6-digit PIN baru untuk memperbarui PIN Keamanan Anda."
            PinDialogAction.CREATE_FIRST_PIN -> "Masukkan 6-digit PIN untuk melindungi Mode Beli & Porto Real."
            PinDialogAction.TOGGLE_REAL_BUY -> "Masukkan PIN untuk mengaktifkan Mode Beli Real Indodax."
            PinDialogAction.UNLOCK_ONLY -> "Masukkan PIN untuk membuka akses Kredensial API & Mode Real."
        }
        val isSetupMode = pinDialogAction == PinDialogAction.ENTER_NEW_PIN || pinDialogAction == PinDialogAction.CREATE_FIRST_PIN

        SecurityPinDialog(
            title = title,
            subtitle = subtitle,
            isSetupMode = isSetupMode,
            errorMessage = pinDialogError,
            onPinSubmitted = { enteredPin ->
                when (pinDialogAction) {
                    PinDialogAction.VERIFY_OLD_FOR_CHANGE -> {
                        val ok = viewModel.verifyPin(enteredPin)
                        if (ok) {
                            pinDialogAction = PinDialogAction.ENTER_NEW_PIN
                            pinDialogError = null
                            Toast.makeText(context, "PIN Lama Terverifikasi! Silakan masukkan PIN Baru.", Toast.LENGTH_SHORT).show()
                        } else {
                            pinDialogError = "PIN Lama Salah. Silakan coba lagi."
                        }
                    }
                    PinDialogAction.ENTER_NEW_PIN -> {
                        if (enteredPin.length < 4) {
                            pinDialogError = "PIN minimal 4 digit (rekomendasi 6 digit)"
                        } else {
                            viewModel.createSecurityPin(enteredPin)
                            hasPin = true
                            showPinDialog = false
                            pinDialogError = null
                            Toast.makeText(context, "PIN Keamanan Berhasil Diperbarui!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    PinDialogAction.CREATE_FIRST_PIN -> {
                        if (enteredPin.length < 4) {
                            pinDialogError = "PIN minimal 4 digit (rekomendasi 6 digit)"
                        } else {
                            viewModel.createSecurityPin(enteredPin)
                            hasPin = true
                            showPinDialog = false
                            pinDialogError = null
                            if (pendingRealBuyToggle) {
                                viewModel.setRealBuyMode(true, enteredPin)
                                pendingRealBuyToggle = false
                            }
                            Toast.makeText(context, "PIN Keamanan Berhasil Dibuat!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    PinDialogAction.TOGGLE_REAL_BUY -> {
                        val ok = viewModel.setRealBuyMode(true, enteredPin)
                        if (ok) {
                            showPinDialog = false
                            pinDialogError = null
                            pendingRealBuyToggle = false
                            Toast.makeText(context, "Mode Beli Real (Indodax) DIAKTIFKAN!", Toast.LENGTH_SHORT).show()
                        } else {
                            pinDialogError = "PIN Keamanan Salah. Silakan coba lagi."
                        }
                    }
                    PinDialogAction.UNLOCK_ONLY -> {
                        val ok = viewModel.verifyPin(enteredPin)
                        if (ok) {
                            showPinDialog = false
                            pinDialogError = null
                            Toast.makeText(context, "PIN Terverifikasi! Kredensial API & Mode Real Terbuka.", Toast.LENGTH_SHORT).show()
                        } else {
                            pinDialogError = "PIN Keamanan Salah. Silakan coba lagi."
                        }
                    }
                }
            },
            onDismiss = {
                showPinDialog = false
                pendingRealBuyToggle = false
                pinDialogError = null
            }
        )
    }

    if (showSetupRealApiDialog) {
        SetupRealApiDialog(
            initialApiKey = prefs.indodaxApiKey,
            initialSecretKey = prefs.indodaxSecretKey,
            userPublicIp = userPublicIp,
            onCheckPublicIp = { viewModel.checkPublicIp() },
            onSaveAndActivate = { newPin, newApiKey, newSecretKey ->
                viewModel.saveRealCredentialsAndPin(newPin, newApiKey, newSecretKey)
                hasPin = true
                showSetupRealApiDialog = false
                Toast.makeText(context, "Kredensial disimpan! Mode Real (Indodax) DIAKTIFKAN.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                showSetupRealApiDialog = false
            }
        )
    }
}

enum class PinDialogAction {
    TOGGLE_REAL_BUY,
    CREATE_FIRST_PIN,
    VERIFY_OLD_FOR_CHANGE,
    ENTER_NEW_PIN,
    UNLOCK_ONLY
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
