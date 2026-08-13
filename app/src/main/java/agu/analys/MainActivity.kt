package agu.analys

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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import agu.analys.model.AppScreen
import agu.analys.ui.screens.DashboardScreen
import agu.analys.ui.screens.DetailChartScreen
import agu.analys.ui.screens.LandscapeChartScreen
import agu.analys.ui.screens.LearningPathScreen
import agu.analys.ui.screens.SettingsScreen
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.theme.TradingViewAITheme
import agu.analys.viewmodel.TradingViewModel

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
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // System bars stay transparent while the root Compose canvas paints the
        // same TvBackground behind them. This removes the Android-system color seam.
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            TradingViewAITheme {
                val currentScreen by tradingViewModel.currentScreen.collectAsState()
                val rootModifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()

                BackHandler(enabled = currentScreen != AppScreen.DASHBOARD) {
                    tradingViewModel.goBack()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TvBackground)
                ) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            (slideInHorizontally(
                                initialOffsetX = { fullWidth -> fullWidth },
                                animationSpec = tween(300)
                            ) + fadeIn(animationSpec = tween(300))).togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> -fullWidth },
                                    animationSpec = tween(300)
                                ) + fadeOut(animationSpec = tween(300))
                            )
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
                            AppScreen.LANDSCAPE_CHART -> LandscapeChartScreen(
                                viewModel = tradingViewModel,
                                onBackToDetail = { tradingViewModel.closeLandscapeChart() }
                            )
                            AppScreen.SETTINGS -> SettingsScreen(
                                viewModel = tradingViewModel,
                                onBack = { tradingViewModel.goBack() }
                            )
                            AppScreen.LEARNING -> LearningPathScreen(
                                onBack = { tradingViewModel.goBack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
