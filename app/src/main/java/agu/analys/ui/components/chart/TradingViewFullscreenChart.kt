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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import agu.analys.config.MarketDataSource
import agu.analys.model.TradingPair

/**
 * Fullscreen chart khusus INDODAX: memuat halaman chart resmi Indodax (https://indodax.com/chart/<SYMBOL>).
 * Untuk Tokocrypto, layar landscape memakai LightweightChartView dari candle Tokocrypto sendiri.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TradingViewFullscreenChart(
    pair: TradingPair,
    marketDataSource: MarketDataSource = MarketDataSource.TOKOCRYPTO,
    modifier: Modifier = Modifier
) {

    val indodaxChartSymbol = remember(pair) {
        val raw = pair.symbol.replace("_", "").replace("/", "").uppercase()
        when {
            raw.endsWith("BIDR") -> raw.removeSuffix("BIDR") + "IDR"
            raw.endsWith("IDR") -> raw
            raw.endsWith("USDT") -> raw
            else -> "${pair.baseAsset.uppercase()}IDR"
        }
    }
    val indodaxChartUrl = remember(indodaxChartSymbol) {
        "https://indodax.com/chart/$indodaxChartSymbol"
    }

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
                // Software layer prevents MESA GPU / rendernode crash in virtualized emulator environments
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                setBackgroundColor(Color.parseColor("#0A0D14"))
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
                settings.allowContentAccess = true
                settings.allowFileAccess = true
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
                            url.contains("tokocrypto.com") ||
                            url.startsWith("about:") ||
                            url.startsWith("data:"))
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        view?.let {
                            (it.parent as? ViewGroup)?.removeView(it)
                            it.destroy()
                        }
                        return true
                    }
                }

                tag = indodaxChartUrl
                loadUrl(indodaxChartUrl)
            }
        },
        update = { wv ->
            val currentTag = wv.tag as? String
            if (currentTag != indodaxChartUrl) {
                wv.tag = indodaxChartUrl
                wv.loadUrl(indodaxChartUrl)
            }
        }
    )
}
