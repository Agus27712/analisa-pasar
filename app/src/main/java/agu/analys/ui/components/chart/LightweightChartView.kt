package agu.analys.ui.components.chart

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import agu.analys.model.CandleBar
import org.json.JSONArray
import org.json.JSONObject

private const val CHART_TAG = "LwcChart"
private const val CHART_URL = "file:///android_asset/chart/lightweight_chart.html"

/** Jembatan JS -> Android. Error dari halaman chart dicatat ke Logcat. */
internal class ChartBridge {
    @JavascriptInterface
    fun onError(message: String) {
        Log.w(CHART_TAG, message)
    }
}

/**
 * Lightweight Charts 5.2.1 (Apache-2.0) — data murni dari candle Tokocrypto.
 * Library di-bundle lokal di assets, tidak memakai CDN.
 * Dipakai di detail (portrait) dan layar penuh (landscape).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
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
    // Naik setiap kali WebView mati (render process crash) → Compose membuat WebView baru.
    var generation by remember { mutableStateOf(0) }

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
        val json = candlesToJson(candles)
        // JSON sebagai object literal — tidak ada masalah escaping string
        wv.evaluateJavascript("setCandles($json)", null)
        wv.evaluateJavascript("setLevels(${levelsJson()})", null)
    }

    LaunchedEffect(candles, entryPrice, targetPrice1, targetPrice2, stopLoss, pageReady, generation) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        pushData(wv)
    }

    LaunchedEffect(showVolume, showEma, showBb, showStochRsi, pageReady, generation) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val json = JSONObject()
            .put("volume", showVolume)
            .put("ema", showEma)
            .put("bb", showBb)
            .put("stoch", showStochRsi)
            .toString()
        wv.evaluateJavascript("setIndicators($json)", null)
    }

    LaunchedEffect(currentPrice, pageReady, generation) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady || currentPrice <= 0.0 || candles.isEmpty()) return@LaunchedEffect
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
    }

    key(generation) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Software layer mencegah crash MESA GPU di emulator. Di HP asli bisa dilepas nanti.
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    addJavascriptInterface(ChartBridge(), "ChartBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = true
                            view?.let { pushData(it) }
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            // Return true supaya app tidak crash. WebView dilepas lalu dibuat ulang.
                            view?.let {
                                (it.parent as? ViewGroup)?.removeView(it)
                                it.destroy()
                            }
                            webView = null
                            pageReady = false
                            generation++
                            return true
                        }
                    }
                    loadUrl(CHART_URL)
                    webView = this
                }
            },
            update = { wv ->
                webView = wv
                if (pageReady) pushData(wv)
            }
        )
    }
}
