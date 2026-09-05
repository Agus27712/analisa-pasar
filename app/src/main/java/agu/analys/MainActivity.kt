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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import agu.analys.model.AppScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextProvider.init(applicationContext)
        val prefs = agu.analys.util.AppPreferences(applicationContext)
        if (prefs.isNotificationsEnabled) {
            agu.analys.service.TradingForegroundService.startService(this)
        }
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        handleIntent(intent)

        setContent {
            val isDarkTheme by tradingViewModel.isDarkTheme.collectAsState()
            TradingViewAITheme(isDarkTheme = isDarkTheme) {
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

                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300)))
                            .togetherWith(slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(300)) + fadeOut(tween(300)))
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