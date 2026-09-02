package agu.analys.ui.components.chart

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import agu.analys.model.TradingPair

/**
 * Fullscreen chart: load official Indodax TradingView chart page.
 * Data 100% Indodax (bukan feed lain) → gak ngaco.
 * URL: https://indodax.com/chart/BTCIDR
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TradingViewFullscreenChart(
    pair: TradingPair,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val chartSymbol = remember(pair) {
        val raw = pair.symbol.replace("_", "").uppercase()
        if (raw.isNotBlank()) raw else "${pair.baseAsset}${pair.quoteAsset}".uppercase()
    }
    val chartUrl = remember(chartSymbol) { "https://indodax.com/chart/$chartSymbol" }

    DisposableEffect(Unit) {
        onDispose { }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
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
                }
                loadUrl(chartUrl)
            }
        },
        update = { wv ->
            val current = wv.url.orEmpty()
            if (!current.contains(chartSymbol) && chartSymbol.isNotBlank()) {
                wv.loadUrl(chartUrl)
            }
        }
    )
}
