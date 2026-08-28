package agu.analys.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import agu.analys.model.MarketConnectionState
import agu.analys.ui.components.MarketEmptyOrErrorState
import agu.analys.ui.components.chart.TradingViewFullscreenChart
import agu.analys.ui.theme.*
import agu.analys.viewmodel.TradingViewModel

@Composable
fun LandscapeChartScreen(
    viewModel: TradingViewModel,
    onBackToDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val pair by viewModel.selectedPair.collectAsState()
    val timeframe by viewModel.selectedTimeframe.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    // Force landscape + hide System UI (status bar + nav bar)
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowInsetsControllerCompat(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
    }

    BackHandler { onBackToDetail() }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (val state = connectionState) {
            is MarketConnectionState.ConnectionLost -> MarketEmptyOrErrorState(
                false, true, state.title, state.reason,
                { viewModel.retryConnection() }, Modifier.fillMaxSize()
            )
            is MarketConnectionState.Loading -> MarketEmptyOrErrorState(
                true, false, onRetry = { viewModel.retryConnection() },
                modifier = Modifier.fillMaxSize()
            )
            is MarketConnectionState.Connected -> {
                TradingViewFullscreenChart(
                    pair = pair,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Text(
            text = "‹  ${pair.baseAsset}/${pair.quoteAsset} · ${timeframe.label}  ·  Indodax TV",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 8.dp)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .clickable(onClick = onBackToDetail)
        )
    }
}
