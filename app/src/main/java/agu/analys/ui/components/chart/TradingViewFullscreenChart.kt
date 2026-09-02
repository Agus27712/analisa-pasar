package agu.analys.ui.components.chart

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import agu.analys.model.TradingPair
import timber.log.Timber

/**
 * Fullscreen chart: load official Indodax TradingView chart page.
 * Data 100% Indodax (bukan feed lain) → gak ngaco.
 * URL: https://indodax.com/chart/BTCIDR
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TradingViewFullscreenChart(
    pair: TradingPair,
    candles: List<agu.analys.model.CandleBar> = emptyList(),
    currentPrice: Double = 0.0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val chartSymbol = remember(pair) {
        val raw = pair.symbol.replace("_", "").uppercase()
        if (raw.isNotBlank()) raw else "${pair.baseAsset}${pair.quoteAsset}".uppercase()
    }
    val chartUrl = remember(chartSymbol) { "https://indodax.com/chart/$chartSymbol" }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var useNativeFallback by remember { mutableStateOf(false) }

    if (useNativeFallback) {
        NativeCandlestickChart(
            candles = candles,
            currentPrice = currentPrice,
            showVolume = true,
            showEma = true,
            quoteAsset = pair.quoteAsset,
            modifier = modifier
        )
        return
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            } catch (_: Throwable) {}
            webViewRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Use software layer to prevent MESA rendernode GPU crash on virtualized emulators
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        return !(url.contains("indodax.com") ||
                            url.contains("tradingview.com") ||
                            url.contains("tvscdn.com") ||
                            url.startsWith("about:"))
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        Timber.w("TradingView fullscreen render process gone (crashed: %s)", detail?.didCrash())
                        webViewRef = null
                        useNativeFallback = true
                        return true // Prevent host process crash
                    }
                }
                loadUrl(chartUrl)
                webViewRef = this
            }
        },
        update = { wv ->
            webViewRef = wv
            val current = wv.url.orEmpty()
            if (!current.contains(chartSymbol) && chartSymbol.isNotBlank()) {
                wv.loadUrl(chartUrl)
            }
        },
        onRelease = { wv ->
            try {
                wv.stopLoading()
                wv.destroy()
            } catch (_: Throwable) {}
        }
    )
}
