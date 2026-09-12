package agu.analys.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.config.AiProvider
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.ui.components.security.SecurityPinDialog
import agu.analys.ui.components.security.SetupRealApiDialog
import agu.analys.ui.components.settings.*
import agu.analys.ui.theme.*
import agu.analys.util.AppPreferences
import agu.analys.viewmodel.*

enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accentColor: Color
) {
    TRADING(
        title = "Strategi & Trading",
        subtitle = "Mode sinyal, sensitivitas scalping, sumber pasar & fee transaksi",
        icon = Icons.Default.TrendingUp,
        accentColor = Color(0xFF3B82F6)
    ),
    WATCHLIST(
        title = "Pair Watchlist & Koin",
        subtitle = "Kustomisasi daftar pantau, cari koin, preset Top 10, Scalping & AI",
        icon = Icons.Default.FormatListBulleted,
        accentColor = Color(0xFF00BCD4)
    ),
    APPEARANCE(
        title = "Tampilan, Tema & Animasi",
        subtitle = "Palet tema (AMOLED/Dark/Light), warna aksen, candle & kecepatan animasi",
        icon = Icons.Default.Palette,
        accentColor = Color(0xFFFF9800)
    ),
    SECURITY(
        title = "Keamanan & Kredensial API",
        subtitle = "Mode Beli Real Indodax, PIN keamanan, API Key/Secret & IP Whitelist",
        icon = Icons.Default.Shield,
        accentColor = Color(0xFFEF4444)
    ),
    AI_ASSISTANT(
        title = "AI Assistant & Engine",
        subtitle = "Konfigurasi engine AI Groq (LLaMA-3) & Google Gemini",
        icon = Icons.Default.SmartToy,
        accentColor = Color(0xFF9C27B0)
    ),
    SYSTEM(
        title = "Sistem, Logcat & Pemeliharaan",
        subtitle = "Logcat diagnostik, pembersihan cache & update rilis GitHub",
        icon = Icons.Default.Settings,
        accentColor = Color(0xFF10B981)
    )
}

enum class PinDialogAction {
    TOGGLE_REAL_BUY,
    UNLOCK_ONLY
}

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

    // Android Hardware / Gesture Back Handler
    BackHandler(enabled = activeCategory != null) {
        activeCategory = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // TOP APP BAR (Native Android Settings Style)
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

        // MAIN CONTENT (Switch between Preference Groups vs Sub-Setting Screen)
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
                // ANDROID PREFERENCE MAIN SCREEN
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
                            subtitle = if (isNotificationsEnabled) "Push notifikasi sinyal aktif" else "Hemat daya & tanpa push notifikasi",
                            checked = isNotificationsEnabled,
                            onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                        )
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
                            subtitle = "Versi 3.4.0 (Build 66) · Periksa rilis GitHub & cache",
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
                    // Category Header Card
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

                    when (category) {
                        SettingsCategory.TRADING -> {
                            // 1. Trading Strategy Mode
                            TradingModeSettings(
                                strategyMode = strategyMode,
                                sensitivity = sensitivity,
                                onStrategyChange = { strategyMode = it; saved = false },
                                onSensitivityChange = { sensitivity = it; saved = false }
                            )

                            Spacer(Modifier.height(14.dp))

                            // 2. Exchange Market Source
                            SectionHeader("SUMBER PASAR (EXCHANGE)")
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = TvBlue.copy(alpha = 0.12f)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, TvBlue)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "INDODAX",
                                                color = TvBlue,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(TvGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("LIVE API", color = TvGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(TvGreen, CircleShape)
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

                            Spacer(Modifier.height(14.dp))

                            // 3. Trading Fees
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

                            Spacer(Modifier.height(14.dp))

                            // 4. Learning Path Module Card
                            SectionHeader("MODUL EDUKASI & ANALISIS")
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
                                            .size(40.dp)
                                            .background(TvBlue.copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.MenuBook,
                                            contentDescription = null,
                                            tint = TvBlue,
                                            modifier = Modifier.size(20.dp)
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
                                            "11 Materi: Candlestick, Support & Resistance, Structure HH/HL, Indikator, Risk Management.",
                                            color = TvTextPrimary,
                                            fontSize = 10.5.sp,
                                            lineHeight = 14.sp
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            "Progress: ${completedLessons.size}/11 Materi selesai · Ketuk untuk membuka",
                                            color = TvGreen,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint = TvBlue, modifier = Modifier.size(20.dp))
                                }
                            }
                        }

                        SettingsCategory.WATCHLIST -> {
                            WatchlistManagerSettings(
                                currentWatchlist = watchlistPairs,
                                dashboardTicks = dashboardTicks,
                                onAddPair = { sym -> viewModel.addToWatchlist(sym) },
                                onRemovePair = { sym -> viewModel.removeFromWatchlist(sym) },
                                onApplyPreset = { preset -> viewModel.applyWatchlistPreset(preset) },
                                onClearAll = { viewModel.setCustomWatchlist(listOf("BTCIDR")) }
                            )
                        }

                        SettingsCategory.APPEARANCE -> {
                            ThemeAndVisualSettings(
                                currentThemeStyle = currentThemeStyle,
                                currentAccentPreset = currentAccentPreset,
                                currentCandleStyle = currentCandleStyle,
                                currentAnimationSpeed = currentAnimationSpeed,
                                currentPriceAnimMode = currentPriceAnimationMode,
                                isPriceTickPulseEnabled = isPriceTickPulseEnabled,
                                isSmoothChartEnabled = isSmoothChartEnabled,
                                priceFeedThrottleMs = priceFeedThrottleMs,
                                onThemeStyleChange = { style -> viewModel.setThemeStyle(style) },
                                onAccentChange = { preset -> viewModel.setAccentColorPreset(preset) },
                                onCandleStyleChange = { candle -> viewModel.setCandleColorStyle(candle) },
                                onAnimationSpeedChange = { speed -> viewModel.setAnimationSpeed(speed) },
                                onPriceAnimModeChange = { mode -> viewModel.setPriceAnimationMode(mode) },
                                onPriceTickPulseChange = { pulse -> viewModel.setPriceTickPulseEnabled(pulse) },
                                onSmoothChartChange = { smooth -> viewModel.setSmoothChartEnabled(smooth) },
                                onThrottleChange = { ms ->
                                    priceFeedThrottleMs = ms
                                    viewModel.setUiPriceThrottleMs(ms)
                                    saved = false
                                }
                            )
                        }

                        SettingsCategory.SECURITY -> {
                            // 1. Real Buy Mode & PIN Security
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
                        }

                        SettingsCategory.AI_ASSISTANT -> {
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
                        }

                        SettingsCategory.SYSTEM -> {
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
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // Bottom Save Button
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

@Composable
fun AndroidSettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = TvTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun AndroidPreferenceItem(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconBackground, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = TvTextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TvTextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun AndroidPreferenceSwitchItem(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconBackground, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = TvTextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = TvGreen,
                uncheckedThumbColor = TvTextSecondary,
                uncheckedTrackColor = TvSurface
            )
        )
    }
}
