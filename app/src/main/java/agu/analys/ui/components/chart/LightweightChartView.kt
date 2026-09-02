package agu.analys.ui.components.chart

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import agu.analys.model.CandleBar
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Lightweight Charts (TradingView open-source) — data murni dari Indodax candles.
 * Dipakai di detail non-fullscreen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LightweightChartView(
    candles: List<CandleBar>,
    currentPrice: Double = 0.0,
    showVolume: Boolean = true,
    showEma: Boolean = false,
    showBb: Boolean = false,
    showStochRsi: Boolean = false,
    entryPrice: Double = 0.0,
    targetPrice1: Double = 0.0,
    targetPrice2: Double = 0.0,
    stopLoss: Double = 0.0,
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var useNativeFallback by remember { mutableStateOf(false) }

    if (useNativeFallback) {
        NativeCandlestickChart(
            candles = candles,
            currentPrice = currentPrice,
            showVolume = showVolume,
            showEma = showEma,
            showBb = showBb,
            showStochRsi = showStochRsi,
            entryPrice = entryPrice,
            targetPrice1 = targetPrice1,
            targetPrice2 = targetPrice2,
            stopLoss = stopLoss,
            modifier = modifier
        )
        return
    }

    fun candlesToJson(list: List<CandleBar>): String {
        val arr = JSONArray()
        list.forEach { c ->
            if (c.open > 0 && c.high > 0 && c.low > 0 && c.close > 0) {
                arr.put(
                    JSONObject()
                        .put("t", c.timestamp)
                        .put("o", c.open)
                        .put("h", c.high)
                        .put("l", c.low)
                        .put("c", c.close)
                        .put("v", c.volume)
                )
            }
        }
        return arr.toString()
    }

    fun levelsJson(): String = JSONObject()
        .put("entry", entryPrice)
        .put("tp1", targetPrice1)
        .put("tp2", targetPrice2)
        .put("sl", stopLoss)
        .toString()

    fun pushData(wv: WebView) {
        try {
            val json = candlesToJson(candles)
            wv.evaluateJavascript("setCandles($json)", null)
            wv.evaluateJavascript("setLevels(${levelsJson()})", null)
        } catch (e: Throwable) {
            Timber.w(e, "Failed to evaluate Javascript on chart WebView")
        }
    }

    LaunchedEffect(candles, entryPrice, targetPrice1, targetPrice2, stopLoss, pageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        pushData(wv)
    }

    LaunchedEffect(showVolume, showEma, showBb, showStochRsi, pageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        try {
            val json = JSONObject()
                .put("volume", showVolume)
                .put("ema", showEma)
                .put("bb", showBb)
                .put("stoch", showStochRsi)
                .toString()
            wv.evaluateJavascript("setIndicators($json)", null)
        } catch (e: Throwable) {
            Timber.w(e, "Failed to update indicators on chart WebView")
        }
    }

    LaunchedEffect(currentPrice, pageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady || currentPrice <= 0.0 || candles.isEmpty()) return@LaunchedEffect
        try {
            val last = candles.last()
            val h = maxOf(last.high, currentPrice)
            val l = minOf(last.low, currentPrice)
            val json = JSONObject()
                .put("t", last.timestamp)
                .put("o", last.open)
                .put("h", h)
                .put("l", l)
                .put("c", currentPrice)
                .put("v", last.volume)
                .toString()
            wv.evaluateJavascript("updateLast($json)", null)
        } catch (e: Throwable) {
            Timber.w(e, "Failed to update last tick on chart WebView")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView?.destroy()
            } catch (_: Throwable) {}
            webView = null
            pageReady = false
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
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageReady = true
                        view?.let { pushData(it) }
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        Timber.w("WebView render process gone (crashed: %s)", detail?.didCrash())
                        pageReady = false
                        webView = null
                        useNativeFallback = true
                        return true // Do not terminate host application
                    }
                }
                loadUrl("file:///android_asset/chart/lightweight_chart.html")
                webView = this
            }
        },
        update = { wv ->
            webView = wv
            if (pageReady) pushData(wv)
        },
        onRelease = { wv ->
            try {
                wv.stopLoading()
                wv.destroy()
            } catch (_: Throwable) {}
        }
    )
}
