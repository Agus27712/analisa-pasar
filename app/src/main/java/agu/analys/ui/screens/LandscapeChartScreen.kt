package agu.analys.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import agu.analys.config.MarketDataSource
import agu.analys.model.MarketConnectionState
import agu.analys.model.Timeframe
import agu.analys.ui.components.MarketEmptyOrErrorState
import agu.analys.ui.components.chart.LightweightChartView
import agu.analys.ui.components.chart.TradingViewFullscreenChart
import agu.analys.ui.theme.*
import agu.analys.viewmodel.*

/** Timeframe yang ditampilkan di layar penuh (M5 ikut, tidak ada di chip detail). */
private val FULLSCREEN_TIMEFRAMES = listOf(
    Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1
)

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
    val marketDataSource by viewModel.marketDataSource.collectAsState()
    val signal by viewModel.aiSignalState.collectAsState()
    val candles by viewModel.recentCandles.collectAsState()
    val tick by viewModel.currentTick.collectAsState()

    // State indikator layar penuh (Tokocrypto). Default sama dengan chart detail.
    var showVolume by remember { mutableStateOf(true) }
    var showEma by remember { mutableStateOf(true) }
    var showBb by remember { mutableStateOf(false) }
    var showStochRsi by remember { mutableStateOf(false) }

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

    val isTokocrypto = marketDataSource != MarketDataSource.INDODAX
    val isConnected = connectionState is MarketConnectionState.Connected

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
                if (!isTokocrypto) {
                    // Indodax: tetap memakai halaman chart resmi Indodax (tidak diubah)
                    TradingViewFullscreenChart(
                        pair = pair,
                        marketDataSource = marketDataSource,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Tokocrypto: candle server Tokocrypto, indikator & level sama dengan chart detail
                    LightweightChartView(
                        candles = candles,
                        currentPrice = tick?.price ?: 0.0,
                        showVolume = showVolume,
                        showEma = showEma,
                        showBb = showBb,
                        showStochRsi = showStochRsi,
                        entryPrice = signal.entryPrice,
                        targetPrice1 = signal.targetPrice1,
                        targetPrice2 = signal.targetPrice2,
                        stopLoss = signal.stopLoss,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Toolbar timeframe & indikator (hanya Tokocrypto, saat chart tampil)
        if (isTokocrypto && isConnected) {
            LandscapeChartToolbar(
                selectedTimeframe = timeframe,
                onSelectTimeframe = { viewModel.selectTimeframe(it) },
                showVolume = showVolume,
                onToggleVolume = { showVolume = !showVolume },
                showEma = showEma,
                onToggleEma = { showEma = !showEma },
                showBb = showBb,
                onToggleBb = { showBb = !showBb },
                showStochRsi = showStochRsi,
                onToggleStochRsi = { showStochRsi = !showStochRsi },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp, start = 64.dp, end = 12.dp)
            )
        }

        // Floating Back Button Overlay
        IconButton(
            onClick = onBackToDetail,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Kembali ke Detail",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun LandscapeChartToolbar(
    selectedTimeframe: Timeframe,
    onSelectTimeframe: (Timeframe) -> Unit,
    showVolume: Boolean,
    onToggleVolume: () -> Unit,
    showEma: Boolean,
    onToggleEma: () -> Unit,
    showBb: Boolean,
    onToggleBb: () -> Unit,
    showStochRsi: Boolean,
    onToggleStochRsi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent,
        labelColor = TvTextSecondary,
        selectedContainerColor = TvBlue.copy(alpha = 0.25f),
        selectedLabelColor = TvBlue
    )

    @Composable
    fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
            colors = chipColors,
            border = FilterChipDefaults.filterChipBorder(
                borderColor = TvBorder,
                enabled = true,
                selected = selected
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.height(28.dp)
        )
    }

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FULLSCREEN_TIMEFRAMES.forEach { tf ->
            ToolChip(tf.label, selectedTimeframe == tf) { onSelectTimeframe(tf) }
        }
        ToolChip("Vol", showVolume, onToggleVolume)
        ToolChip("EMA", showEma, onToggleEma)
        ToolChip("BB", showBb, onToggleBb)
        ToolChip("StochRSI", showStochRsi, onToggleStochRsi)
    }
}
