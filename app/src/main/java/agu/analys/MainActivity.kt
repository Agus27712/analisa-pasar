package agu.analys

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import agu.analys.model.AppScreen
import agu.analys.ui.animation.LocalPriceAnimationMode
import agu.analys.ui.screens.DashboardScreen
import agu.analys.ui.screens.DetailChartScreen
import agu.analys.ui.screens.LandscapeChartScreen
import agu.analys.ui.screens.LearningPathScreen
import agu.analys.ui.screens.PortfolioScreen
import agu.analys.ui.screens.SettingsScreen
import agu.analys.ui.screens.TradeSimulationScreen
import agu.analys.ui.theme.TradingViewAITheme
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.util.edgeSwipeBack
import agu.analys.viewmodel.*

class MainActivity : ComponentActivity() {
    private val tradingViewModel: TradingViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TradingViewModel(application) as T
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                timber.log.Timber.i("Notification permission granted by user on first launch")
            } else {
                timber.log.Timber.w("Notification permission denied by user")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextProvider.init(applicationContext)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize Notification Channels
        agu.analys.util.AlertNotificationHelper.createNotificationChannels(applicationContext)

        // Prompt for notification permission on Android 13+ upon first install/start
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleIntent(intent)

        setContent {
            val isDarkTheme by tradingViewModel.isDarkTheme.collectAsState()
            val themeStyle by tradingViewModel.themeStyle.collectAsState()
            val accentPreset by tradingViewModel.accentColorPreset.collectAsState()
            val candleStyle by tradingViewModel.candleColorStyle.collectAsState()
            val animSpeed by tradingViewModel.animationSpeed.collectAsState()
            val priceAnimMode by tradingViewModel.priceAnimationMode.collectAsState()

            TradingViewAITheme(
                isDarkTheme = isDarkTheme,
                themeStyle = themeStyle,
                accentPreset = accentPreset,
                candleStyle = candleStyle
            ) {
                CompositionLocalProvider(LocalPriceAnimationMode provides priceAnimMode) {
                    val currentScreen by tradingViewModel.currentScreen.collectAsState()
                val rootModifier = Modifier
                    .fillMaxSize()
                    .background(TvBackground)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .edgeSwipeBack(
                        enabled = currentScreen != AppScreen.DASHBOARD && currentScreen != AppScreen.LANDSCAPE_CHART,
                        onBack = { tradingViewModel.goBack() }
                    )

                BackHandler(enabled = currentScreen != AppScreen.DASHBOARD) {
                    tradingViewModel.goBack()
                }

                val duration = animSpeed.durationMs
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        if (duration <= 0) {
                            fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
                        } else {
                            (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(duration)) + fadeIn(tween(duration)))
                                .togetherWith(slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(duration)) + fadeOut(tween(duration)))
                        }
                    },
                    modifier = rootModifier,
                    label = "screen_transition"
                ) { screen ->
                    when (screen) {
                        AppScreen.DASHBOARD -> DashboardScreen(
                            viewModel = tradingViewModel,
                            onNavigateToDetail = { tradingViewModel.openCoinDetail(it) },
                            onOpenSettings = { tradingViewModel.openSettings() }
                        )
                        AppScreen.DETAIL -> DetailChartScreen(
                            viewModel = tradingViewModel,
                            onNavigateToDashboard = { tradingViewModel.goBack() },
                            onOpenLandscapeChart = { tradingViewModel.openLandscapeChart() }
                        )
                        AppScreen.PORTFOLIO -> PortfolioScreen(
                            viewModel = tradingViewModel,
                            onNavigateToDetail = { tradingViewModel.openCoinDetail(it) },
                            onNavigateToSimulation = { tradingViewModel.openSimulation(it) },
                            onOpenSettings = { tradingViewModel.openSettings() },
                            onBack = { tradingViewModel.goBack() }
                        )
                        AppScreen.SIMULATION_TRADE -> TradeSimulationScreen(
                            viewModel = tradingViewModel,
                            onOpenChart = { tradingViewModel.openCoinDetail(tradingViewModel.selectedPair.value) },
                            onNavigateToDashboard = { tradingViewModel.goBack() },
                            onOpenSettings = { tradingViewModel.openSettings() }
                        )
                        AppScreen.LANDSCAPE_CHART -> LandscapeChartScreen(
                            viewModel = tradingViewModel,
                            onBackToDetail = { tradingViewModel.closeLandscapeChart() }
                        )
                        AppScreen.SETTINGS -> SettingsScreen(
                            viewModel = tradingViewModel,
                            onBack = { tradingViewModel.goBack() }
                        )
                        AppScreen.LEARNING -> LearningPathScreen(
                            viewModel = tradingViewModel,
                            onOpenSettings = { tradingViewModel.openSettings() },
                            onBack = { tradingViewModel.goBack() }
                        )
                    }
                }
            }
        }
    }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val symbol = intent.getStringExtra("EXTRA_SYMBOL")
        
        if (action == "agu.analys.ACTION_EXECUTE_TRAILING_SELL") {
            val limitPrice = intent.getDoubleExtra("EXTRA_LIMIT_PRICE", 0.0)
            val qty = intent.getDoubleExtra("EXTRA_QUANTITY", 0.0)
            val isReal = intent.getBooleanExtra("EXTRA_IS_REAL", false)
            if (symbol != null && limitPrice > 0.0 && qty > 0.0) {
                tradingViewModel.executeTrailingSellLimitOrder(symbol, limitPrice, qty, isReal)
                tradingViewModel.openCoinDetail(agu.analys.model.TradingPair.fromCustomSymbol(symbol))
                
                // Clear action so it doesn't re-trigger on rotation
                intent.action = null
            }
        } else if (!symbol.isNullOrEmpty()) {
            tradingViewModel.openCoinDetail(agu.analys.model.TradingPair.fromCustomSymbol(symbol))
            intent.removeExtra("EXTRA_SYMBOL") // Consume
        }
    }
}