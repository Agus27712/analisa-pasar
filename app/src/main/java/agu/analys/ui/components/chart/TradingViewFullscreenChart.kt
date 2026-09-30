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
 * Fullscreen chart: Memuat grafik resmi sesuai dengan exchange aktif (Murni Terpisah):
 * 1. INDODAX: Memuat halaman chart resmi Indodax TradingView (https://indodax.com/chart/<SYMBOL>).
 * 2. TOKOCRYPTO: Memuat TradingView Advanced Real-Time Chart widget untuk Binance/Tokocrypto (BINANCE:<BASE>IDR / BINANCE:<BASE>USDT).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TradingViewFullscreenChart(
    pair: TradingPair,
    marketDataSource: MarketDataSource = MarketDataSource.TOKOCRYPTO,
    modifier: Modifier = Modifier
) {
    val isIndodax = marketDataSource == MarketDataSource.INDODAX

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

    val tokoTvSymbol = remember(pair) {
        pair.effectiveTradingViewSymbol()
    }

    val tokoHtmlContent = remember(tokoTvSymbol) {
        """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"/>
          <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            html, body { width: 100%; height: 100%; background: #0a0d14; overflow: hidden; }
            #tv_chart_container { width: 100%; height: 100%; }
          </style>
          <script type="text/javascript" src="https://s3.tradingview.com/tv.js"></script>
        </head>
        <body>
          <div id="tv_chart_container"></div>
          <script type="text/javascript">
            function initTV() {
              if (typeof TradingView === 'undefined') {
                setTimeout(initTV, 200);
                return;
              }
              try {
                new TradingView.widget({
                  "autosize": true,
                  "symbol": "$tokoTvSymbol",
                  "interval": "15",
                  "timezone": "Asia/Jakarta",
                  "theme": "dark",
                  "style": "1",
                  "locale": "id",
                  "toolbar_bg": "#0e131d",
                  "enable_publishing": false,
                  "allow_symbol_change": true,
                  "hide_side_toolbar": false,
                  "withdateranges": true,
                  "details": false,
                  "hotlist": false,
                  "calendar": false,
                  "studies": [
                    "MASimple@tv-basicstudies",
                    "RSI@tv-basicstudies"
                  ],
                  "container_id": "tv_chart_container"
                });
              } catch(e) {
                console.error("TV Init Error:", e);
              }
            }
            document.addEventListener('DOMContentLoaded', initTV);
            if (document.readyState === 'complete' || document.readyState === 'interactive') {
              initTV();
            }
          </script>
        </body>
        </html>
        """.trimIndent()
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
                            url.contains("binance.com") ||
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

                if (isIndodax) {
                    tag = indodaxChartUrl
                    loadUrl(indodaxChartUrl)
                } else {
                    tag = tokoTvSymbol
                    loadDataWithBaseURL("https://www.tradingview.com", tokoHtmlContent, "text/html", "UTF-8", null)
                }
            }
        },
        update = { wv ->
            if (isIndodax) {
                val currentTag = wv.tag as? String
                if (currentTag != indodaxChartUrl) {
                    wv.tag = indodaxChartUrl
                    wv.loadUrl(indodaxChartUrl)
                }
            } else {
                val currentTag = wv.tag as? String
                if (currentTag != tokoTvSymbol) {
                    wv.tag = tokoTvSymbol
                    wv.loadDataWithBaseURL("https://www.tradingview.com", tokoHtmlContent, "text/html", "UTF-8", null)
                }
            }
        }
    )
}
