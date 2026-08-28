package agu.analys.ui.components.chart

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
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

/**
 * Lightweight Charts (TradingView open-source) — data murni dari Indodax candles.
 * Dipakai di detail non-fullscreen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LightweightChartView(
    candles: List<CandleBar>,
    entryPrice: Double = 0.0,
    targetPrice1: Double = 0.0,
    targetPrice2: Double = 0.0,
    stopLoss: Double = 0.0,
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

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
        wv.evaluateJavascript("setCandles('$json')", null)
        wv.evaluateJavascript("setLevels('${levelsJson()}')", null)
    }

    LaunchedEffect(candles, entryPrice, targetPrice1, targetPrice2, stopLoss, pageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        pushData(wv)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#0d1117"))
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
                }
                loadUrl("file:///android_asset/chart/lightweight_chart.html")
                webView = this
            }
        },
        update = { wv ->
            webView = wv
            if (pageReady) pushData(wv)
        }
    )
}
