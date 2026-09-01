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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.BuildConfig
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataSource
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.util.MarketDataCache
import agu.analys.ui.theme.*
import agu.analys.ui.components.security.SecurityPinDialog
import agu.analys.ui.components.security.SetupRealApiDialog
import agu.analys.ui.components.settings.*
import agu.analys.util.AppPreferences
import agu.analys.util.GitHubUpdater
import agu.analys.viewmodel.*

@Composable
fun SettingsScreen(viewModel: TradingViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val currentMarketSource by viewModel.marketDataSource.collectAsStateWithLifecycle()
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
    val isRealBuyMode by viewModel.isRealBuyMode.collectAsStateWithLifecycle()
    val isPinUnlocked by viewModel.isPinUnlocked.collectAsStateWithLifecycle()
    val userPublicIp by viewModel.userPublicIp.collectAsStateWithLifecycle()
    val failedPinAttempts by viewModel.failedPinAttempts.collectAsState(initial = 0)
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
    val releaseInfo by viewModel.githubReleaseInfo.collectAsStateWithLifecycle()
    val checkingUpdate by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateCheckStatus.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.updateDownloadProgress.collectAsStateWithLifecycle()

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

        // SECTION: TEMA APLIKASI
        val isDarkTheme by viewModel.isDarkTheme.collectAsState()
        SectionHeader("TEMA APLIKASI")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.NightsStay else Icons.Default.WbSunny,
                        contentDescription = "Tema",
                        tint = TvBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isDarkTheme) "Mode Gelap (Dark Mode)" else "Mode Terang (Light Mode)",
                            color = TvTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Ganti tampilan grafik & menu ke terang/gelap",
                            color = TvTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                Switch(
                    checked = isDarkTheme,
                    onCheckedChange = { viewModel.setDarkTheme(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = TvGreen,
                        uncheckedThumbColor = TvTextSecondary,
                        uncheckedTrackColor = TvSurfaceVariant
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // SECTION: NOTIFIKASI TRADING & SINYAL
        val isNotificationsEnabled by viewModel.isNotificationsEnabled.collectAsState()
        SectionHeader("NOTIFIKASI TRADING & SINYAL")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isNotificationsEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                        contentDescription = "Notifikasi",
                        tint = TvBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Notifikasi Harga & Sinyal Sell",
                            color = TvTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Matikan untuk hemat baterai & tanpa push alert",
                            color = TvTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                Switch(
                    checked = isNotificationsEnabled,
                    onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = TvGreen,
                        uncheckedThumbColor = TvTextSecondary,
                        uncheckedTrackColor = TvSurfaceVariant
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // SECTION: SUMBER DATA PASAR (EXCHANGE SOURCE)
        SectionHeader("SUMBER PASAR (EXCHANGE)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = TvBlue.copy(alpha = 0.15f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBlue)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "INDODAX",
                        color = TvBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(TvBlue, CircleShape)
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
            colors = CardDefaults.cardColors(containerColor = TvCardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(TvBlue.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = TvBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "MODE BELAJAR ANALISIS PASAR",
                        color = TvBlue,
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
                Icon(Icons.Default.ChevronRight, null, tint = TvBlue, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // SECTION 1: MODE ANALISIS TRADING
        TradingModeSettings(
            strategyMode = strategyMode,
            sensitivity = sensitivity,
            onStrategyChange = { strategyMode = it; saved = false },
            onSensitivityChange = { sensitivity = it; saved = false }
        )

        Spacer(Modifier.height(16.dp))

        // SECTION: KEAMANAN & MODE BELI REAL
        SectionHeader("KEAMANAN & EKSEKUSI INDODAX")
        RealBuyModeAndSecurityCard(
            isRealBuyMode = isRealBuyMode,
            hasPin = hasPin,
            hasApiCredentials = prefs.hasIndodaxCredentials(),
            isPinUnlocked = isPinUnlocked,
            userPublicIp = userPublicIp ?: "Detecting...",
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
        AiAssistantSettings(
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
        TradingFeeSettings(
            buyMaker = buyMakerFee,
            buyTaker = buyTakerFee,
            sellMaker = sellMakerFee,
            sellTaker = sellTakerFee,
            onBuyMakerChange = { buyMakerFee = it; saved = false },
            onBuyTakerChange = { buyTakerFee = it; saved = false },
            onSellMakerChange = { sellMakerFee = it; saved = false },
            onSellTakerChange = { sellTakerFee = it; saved = false }
        )

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
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
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
            userPublicIp = userPublicIp ?: "Detecting...",
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
            containerColor = if (isSelected) accentColor.copy(alpha = 0.15f) else TvCardBackground
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) accentColor else TvBorder
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
                color = if (isSelected) TvTextPrimary else TvTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
