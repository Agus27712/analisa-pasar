package agu.analys.ui.screens

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.MarketConnectionState
import agu.analys.ui.components.MarketEmptyOrErrorState
import agu.analys.ui.components.SimpleComposeChart
import agu.analys.viewmodel.TradingViewModel

@Composable
fun LandscapeChartScreen(viewModel: TradingViewModel, onBackToDetail: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pair by viewModel.selectedPair.collectAsState()
    val tick by viewModel.currentTick.collectAsState()
    val recentCandles by viewModel.recentCandles.collectAsState()
    val timeframe by viewModel.selectedTimeframe.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    BackHandler { onBackToDetail() }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0D0E12))) {
        when (val state = connectionState) {
            is MarketConnectionState.ConnectionLost -> MarketEmptyOrErrorState(false, true, state.title, state.reason, { viewModel.retryConnection() }, Modifier.fillMaxSize())
            is MarketConnectionState.Loading -> MarketEmptyOrErrorState(true, false, onRetry = { viewModel.retryConnection() }, modifier = Modifier.fillMaxSize())
            is MarketConnectionState.Connected -> SimpleComposeChart(
                prices = emptyList(),
                candles = recentCandles,
                currentPrice = tick?.price ?: pair.initialPrice,
                isPositiveTrend = (tick?.change24h ?: 0.0) >= 0,
                modifier = Modifier.fillMaxSize()
            )
        }

        androidx.compose.material3.Text(
            text = "‹  ${pair.baseAsset}/IDR · ${timeframe.label}",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable(onClick = onBackToDetail)
        )
    }
}