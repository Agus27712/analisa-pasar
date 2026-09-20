package agu.analys.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import agu.analys.config.AiProvider
import agu.analys.config.StrategyMode
import agu.analys.ui.components.security.SecurityPinDialog
import agu.analys.ui.components.security.SetupRealApiDialog
import agu.analys.ui.components.settings.*
import agu.analys.ui.theme.*
import agu.analys.util.AppPreferences
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

    val currentThemeStyle by viewModel.themeStyle.collectAsStateWithLifecycle()
    val currentAccentPreset by viewModel.accentColorPreset.collectAsStateWithLifecycle()
    val currentCandleStyle by viewModel.candleColorStyle.collectAsStateWithLifecycle()
    val currentAnimationSpeed by viewModel.animationSpeed.collectAsStateWithLifecycle()
    val currentPriceAnimationMode by viewModel.priceAnimationMode.collectAsStateWithLifecycle()
    val isPriceTickPulseEnabled by viewModel.isPriceTickPulseEnabled.collectAsStateWithLifecycle()
    val isSmoothChartEnabled by viewModel.isSmoothChartEnabled.collectAsStateWithLifecycle()
    val watchlistPairs by viewModel.watchlist.collectAsStateWithLifecycle()
    val dashboardTicks by viewModel.dashboardTicks.collectAsStateWithLifecycle()

    var activeCategory by remember { mutableStateOf<SettingsCategory?>(null) }

    var showSetupRealApiDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinDialogAction by remember { mutableStateOf(PinDialogAction.TOGGLE_REAL_BUY) }
    var pinDialogError by remember { mutableStateOf<String?>(null) }
    var pendingRealBuyToggle by remember { mutableStateOf(false) }
    var updateRepo by remember { mutableStateOf(prefs.updateRepo) }
    var updateToken by remember { mutableStateOf(prefs.updateGitHubToken) }
    var priceFeedThrottleMs by remember { mutableStateOf(prefs.priceFeedThrottleMs) }
    var saved by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }

    val completedLessons = remember { prefs.getCompletedLearningLessons() }
    val releaseInfo by viewModel.githubReleaseInfo.collectAsStateWithLifecycle()
    val checkingUpdate by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateCheckStatus.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.updateDownloadProgress.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val isNotificationsEnabled by viewModel.isNotificationsEnabled.collectAsState()
    val isNotifyCandidateBuyEnabled by viewModel.isNotifyCandidateBuyEnabled.collectAsState()
    val isNotifyPriceAlertsEnabled by viewModel.isNotifyPriceAlertsEnabled.collectAsState()
    val isNotifyTrailingStopEnabled by viewModel.isNotifyTrailingStopEnabled.collectAsState()
    val isNotifyEmergencyExitEnabled by viewModel.isNotifyEmergencyExitEnabled.collectAsState()

    fun saveAllSettings(showToast: Boolean = true) {
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
        prefs.priceFeedThrottleMs = priceFeedThrottleMs
        viewModel.setUiPriceThrottleMs(priceFeedThrottleMs)
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
        if (showToast) {
            Toast.makeText(context, "Pengaturan berhasil disimpan", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = activeCategory != null) {
        activeCategory = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // TOP APP BAR
        Surface(
            color = TvSurface,
            shadowElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (activeCategory != null) {
                            activeCategory = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali",
                            tint = TvTextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = activeCategory?.title ?: "Pengaturan",
                            color = TvTextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (activeCategory != null) "Sub-pengaturan kategori" else "Sistem, tema, watchlist & akun",
                            color = TvTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                if (activeCategory != null) {
                    Button(
                        onClick = { saveAllSettings(true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (saved) TvGreen.copy(alpha = 0.2f) else TvGreen
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            if (saved) Icons.Default.CheckCircle else Icons.Default.Save,
                            contentDescription = null,
                            tint = if (saved) TvGreen else Color.Black,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (saved) "Tersimpan" else "Simpan",
                            color = if (saved) TvGreen else Color.Black,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // MAIN CONTENT
        AnimatedContent(
            targetState = activeCategory,
            transitionSpec = {
                if (targetState != null) {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> -width } + fadeOut()
                } else {
                    slideInHorizontally { width -> -width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> width } + fadeOut()
                }
            },
            modifier = Modifier.weight(1f)
        ) { category ->
            if (category == null) {
                // OVERVIEW PREFERENCE GROUPS
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    // Quick Status Banner
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
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
                            Column {
                                Text(
                                    "RINGKASAN STATUS SISTEM",
                                    color = TvTextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (isRealBuyMode) TvRed else TvGreen, CircleShape)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (isRealBuyMode) "MODE REAL INDODAX" else "MODE SIMULASI MURNI",
                                        color = if (isRealBuyMode) TvRed else TvGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Tema: ${currentThemeStyle.displayName.substringBefore(" ")}", color = TvBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "${strategyMode.name} · ${watchlistPairs.size} Pair Watchlist",
                                    color = TvTextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 1: STRATEGI & TRADING
                    AndroidSettingsGroup(title = "TRADING & PASAR") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.TrendingUp,
                            iconTint = TvBlue,
                            iconBackground = TvBlue.copy(alpha = 0.15f),
                            title = "Strategi & Analisis Sinyal",
                            subtitle = "Mode ${strategyMode.name} · Sensitivitas ${sensitivity.name}",
                            onClick = { activeCategory = SettingsCategory.TRADING }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.FormatListBulleted,
                            iconTint = Color(0xFF00BCD4),
                            iconBackground = Color(0xFF00BCD4).copy(alpha = 0.15f),
                            title = "Pair Watchlist (Daftar Pantau)",
                            subtitle = "${watchlistPairs.size} Pair dipantau · Cari koin & preset cepat",
                            onClick = { activeCategory = SettingsCategory.WATCHLIST }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.ReceiptLong,
                            iconTint = Color(0xFFFFB300),
                            iconBackground = Color(0xFFFFB300).copy(alpha = 0.15f),
                            title = "Biaya Transaksi (Fee)",
                            subtitle = "Maker ${buyMakerFee}% · Taker ${buyTakerFee}%",
                            onClick = { activeCategory = SettingsCategory.TRADING }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.Assessment,
                            iconTint = TvGreen,
                            iconBackground = TvGreen.copy(alpha = 0.15f),
                            title = "Log Sinyal & Evaluasi Reliabilitas (Room DB)",
                            subtitle = "Rekaman sinyal, skor keyakinan & akurasi performa nyata",
                            onClick = { viewModel.openSignalLogs() }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.MenuBook,
                            iconTint = Color(0xFF00BCD4),
                            iconBackground = Color(0xFF00BCD4).copy(alpha = 0.15f),
                            title = "Modul Belajar Analisis Pasar",
                            subtitle = "${completedLessons.size}/11 Materi selesai dipelajari",
                            onClick = { viewModel.openLearning() }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 2: TAMPILAN, TEMA & ANIMASI
                    AndroidSettingsGroup(title = "TAMPILAN, TEMA & ANIMASI") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.Palette,
                            iconTint = Color(0xFFFF9800),
                            iconBackground = Color(0xFFFF9800).copy(alpha = 0.15f),
                            title = "Pilihan Warna & Tema (Theming)",
                            subtitle = "Tema: ${currentThemeStyle.displayName} · Aksen: ${currentAccentPreset.displayName}",
                            onClick = { activeCategory = SettingsCategory.APPEARANCE }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.MotionPhotosAuto,
                            iconTint = TvBlue,
                            iconBackground = TvBlue.copy(alpha = 0.15f),
                            title = "Pilihan Animasi & Motion",
                            subtitle = "Kecepatan: ${currentAnimationSpeed.displayName.substringBefore(" ")} · Efek Pulse & Chart",
                            onClick = { activeCategory = SettingsCategory.APPEARANCE }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceSwitchItem(
                            icon = if (isNotificationsEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                            iconTint = Color(0xFFFF7043),
                            iconBackground = Color(0xFFFF7043).copy(alpha = 0.15f),
                            title = "Notifikasi Trading & Sinyal",
                            subtitle = if (isNotificationsEnabled) "Push notifikasi aktif" else "Hemat daya & tanpa push notifikasi",
                            checked = isNotificationsEnabled,
                            onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                        )
                        if (isNotificationsEnabled) {
                            HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                            AndroidPreferenceItem(
                                icon = Icons.Default.Notifications,
                                iconTint = Color(0xFFFF7043),
                                iconBackground = Color(0xFFFF7043).copy(alpha = 0.15f),
                                title = "Kustomisasi Kategori Notifikasi",
                                subtitle = "Kelola suara, getaran, lencana, & prioritas saluran sistem",
                                onClick = { activeCategory = SettingsCategory.NOTIFICATIONS }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 3: KEAMANAN & AKUN
                    AndroidSettingsGroup(title = "KEAMANAN & API INDODAX") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.Shield,
                            iconTint = if (isRealBuyMode) TvRed else TvGreen,
                            iconBackground = (if (isRealBuyMode) TvRed else TvGreen).copy(alpha = 0.15f),
                            title = "Mode Beli Real & PIN Keamanan",
                            subtitle = if (isRealBuyMode)
                                "Mode Real Indodax Aktif · PIN Terpasang"
                            else
                                "Mode Simulasi Aktif · ${if (hasPin) "PIN Terproteksi" else "PIN Belum Dibuat"}",
                            onClick = { activeCategory = SettingsCategory.SECURITY }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 4: AI ASSISTANT
                    AndroidSettingsGroup(title = "KECERDASAN BUATAN (AI)") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.SmartToy,
                            iconTint = Color(0xFFAB47BC),
                            iconBackground = Color(0xFFAB47BC).copy(alpha = 0.15f),
                            title = "AI Assistant & Engine",
                            subtitle = "Provider: ${if (provider == AiProvider.GROQ) "Groq (LLaMA-3)" else "Google Gemini"} · Kelola API Key",
                            onClick = { activeCategory = SettingsCategory.AI_ASSISTANT }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 5: SISTEM & DIAGNOSTIK
                    AndroidSettingsGroup(title = "SISTEM & PEMELIHARAAN") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.Terminal,
                            iconTint = TvGreen,
                            iconBackground = TvGreen.copy(alpha = 0.15f),
                            title = "Logcat & Diagnostik Sistem",
                            subtitle = "Periksa log trailing, koin, error, filter & ekspor log",
                            onClick = { activeCategory = SettingsCategory.SYSTEM }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.SystemUpdate,
                            iconTint = TvBlue,
                            iconBackground = TvBlue.copy(alpha = 0.15f),
                            title = "Pembaruan & Versi Aplikasi",
                            subtitle = "Versi 3.4.1 (Build 67) · Periksa rilis GitHub & cache",
                            onClick = { activeCategory = SettingsCategory.SYSTEM }
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            } else {
                // SUB-SETTING DETAILED CATEGORY SCREEN
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(category.accentColor.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = category.icon,
                                    contentDescription = null,
                                    tint = category.accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = category.title,
                                    color = TvTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = category.subtitle,
                                    color = TvTextSecondary,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    SettingsCategoryDetailContent(
                        category = category,
                        context = context,
                        viewModel = viewModel,
                        strategyMode = strategyMode,
                        onStrategyModeChange = { strategyMode = it; saved = false },
                        sensitivity = sensitivity,
                        onSensitivityChange = { sensitivity = it; saved = false },
                        buyMakerFee = buyMakerFee,
                        onBuyMakerFeeChange = { buyMakerFee = it; saved = false },
                        buyTakerFee = buyTakerFee,
                        onBuyTakerFeeChange = { buyTakerFee = it; saved = false },
                        sellMakerFee = sellMakerFee,
                        onSellMakerFeeChange = { sellMakerFee = it; saved = false },
                        sellTakerFee = sellTakerFee,
                        onSellTakerFeeChange = { sellTakerFee = it; saved = false },
                        completedLessonsCount = completedLessons.size,
                        watchlistPairs = watchlistPairs,
                        dashboardTicks = dashboardTicks,
                        currentThemeStyle = currentThemeStyle,
                        currentAccentPreset = currentAccentPreset,
                        currentCandleStyle = currentCandleStyle,
                        currentAnimationSpeed = currentAnimationSpeed,
                        currentPriceAnimationMode = currentPriceAnimationMode,
                        isPriceTickPulseEnabled = isPriceTickPulseEnabled,
                        isSmoothChartEnabled = isSmoothChartEnabled,
                        priceFeedThrottleMs = priceFeedThrottleMs,
                        isDarkTheme = isDarkTheme,
                        onThrottleChange = { ms ->
                            priceFeedThrottleMs = ms
                            viewModel.setUiPriceThrottleMs(ms)
                            saved = false
                        },
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
                        onOpenSetupDialog = { showSetupRealApiDialog = true },
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
                        provider = provider,
                        onProviderChange = { provider = it; saved = false },
                        groq = groq,
                        onGroqChange = { groq = it; saved = false },
                        gemini = gemini,
                        onGeminiChange = { gemini = it; saved = false },
                        isNotifyPriceAlertsEnabled = isNotifyPriceAlertsEnabled,
                        isNotifyCandidateBuyEnabled = isNotifyCandidateBuyEnabled,
                        isNotifyTrailingStopEnabled = isNotifyTrailingStopEnabled,
                        isNotifyEmergencyExitEnabled = isNotifyEmergencyExitEnabled,
                        cacheCleared = cacheCleared,
                        onClearCache = { cacheCleared = true },
                        updateRepo = updateRepo,
                        onUpdateRepoChange = { updateRepo = it; saved = false },
                        updateToken = updateToken,
                        onUpdateTokenChange = { updateToken = it; saved = false },
                        releaseInfo = releaseInfo,
                        checkingUpdate = checkingUpdate,
                        updateStatus = updateStatus,
                        downloadProgress = downloadProgress
                    )

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = { saveAllSettings(true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (saved) Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp), tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (saved) "Pengaturan Tersimpan" else "Simpan Perubahan",
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { activeCategory = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvTextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Text("Kembali ke Daftar Pengaturan", color = TvTextSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }

    // DIALOGS
    if (showSetupRealApiDialog) {
        SetupRealApiDialog(
            initialApiKey = prefs.indodaxApiKey,
            initialSecretKey = prefs.indodaxSecretKey,
            userPublicIp = userPublicIp ?: "Detecting...",
            onCheckPublicIp = { viewModel.checkPublicIp() },
            onSaveAndActivate = { pin, apiKey, secretKey ->
                viewModel.saveRealCredentialsAndPin(pin, apiKey, secretKey)
                viewModel.setRealBuyMode(true, pin)
                hasPin = true
                showSetupRealApiDialog = false
                Toast.makeText(context, "Kredensial API & PIN berhasil disimpan! Mode REAL aktif.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSetupRealApiDialog = false }
        )
    }

    if (showPinDialog) {
        SecurityPinDialog(
            title = if (pinDialogAction == PinDialogAction.TOGGLE_REAL_BUY) "AKTIVASI MODE REAL INDODAX" else "BUKA SESI KEAMANAN",
            subtitle = "Masukkan 6-digit PIN Keamanan Anda.",
            errorMessage = pinDialogError,
            failedAttempts = failedPinAttempts,
            onPinSubmitted = { pin ->
                val isValid = viewModel.verifyPin(pin)
                if (isValid) {
                    showPinDialog = false
                    pinDialogError = null
                    if (pinDialogAction == PinDialogAction.TOGGLE_REAL_BUY && pendingRealBuyToggle) {
                        viewModel.setRealBuyMode(true, pin)
                        pendingRealBuyToggle = false
                        Toast.makeText(context, "Mode REAL INDODAX AKTIF!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "PIN Terverifikasi", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    pinDialogError = "PIN Salah (${failedPinAttempts + 1}/5 percobaan)"
                }
            },
            onDismiss = {
                showPinDialog = false
                pendingRealBuyToggle = false
                pinDialogError = null
            }
        )
    }
}
