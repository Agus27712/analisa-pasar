package agu.analys.service

import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * WebSocket Real-time Tokocrypto (Binance Cloud Stream).
 * Mengalirkan update harga per tick (ticker stream) & formasi candle 1m secara instan.
 */
class TokocryptoMarketWebSocket(
    private val scope: CoroutineScope,
    private val onTick: (MarketTick) -> Unit,
    private val onCandle: (CandleBar) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var symbol = ""
    private var reconnectAttempt = 0

    companion object {
        private val WS_HOSTS = listOf(
            "wss://data-stream.binance.vision/stream?streams=",
            "wss://stream.binance.com:9443/stream?streams=",
            "wss://stream.binance.us:9443/stream?streams="
        )
    }

    fun start(rawSymbol: String) {
        val s = TokocryptoMarketService.toSymbol(rawSymbol).lowercase()
        if (s.isBlank()) return
        stop(false)
        symbol = s
        reconnectAttempt = 0
        connect()
    }

    fun stop(notify: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "switch pair")
        socket = null
        symbol = ""
        if (notify) onDisconnected()
    }

    fun close() = stop(false)

    private fun connect() {
        if (symbol.isBlank()) return
        val hostIndex = reconnectAttempt % WS_HOSTS.size
        val host = WS_HOSTS[hostIndex]
        val streamUrl = "$host${symbol}@ticker/${symbol}@kline_1m"
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "Mozilla/5.0 (Android; Tokocrypto Live Stream)")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun reconnect() {
        if (symbol.isBlank() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val waitMs = min(10_000L, 1_000L * (1L shl min(reconnectAttempt, 3)))
            reconnectAttempt++
            delay(waitMs)
            if (isActive && symbol.isNotBlank()) connect()
        }
    }

    private fun consume(message: String) {
        val root = runCatching { JSONObject(message) }.getOrNull() ?: return
        val payload = root.optJSONObject("data") ?: root
        val eventType = payload.optString("e", "")

        if (eventType == "24hrTicker") {
            val last = payload.optString("c", "0").toDoubleOrNull() ?: 0.0
            val high = payload.optString("h", "0").toDoubleOrNull() ?: last
            val low = payload.optString("l", "0").toDoubleOrNull() ?: last
            val quoteVol = payload.optString("q", "0").toDoubleOrNull()
                ?: payload.optString("v", "0").toDoubleOrNull() ?: 0.0
            val change = payload.optString("P", "0").toDoubleOrNull() ?: Double.NaN
            val sym = payload.optString("s", symbol.uppercase())

            if (last > 0.0) {
                onTick(
                    MarketTick(
                        symbol = sym,
                        price = last,
                        high24h = high,
                        low24h = low,
                        volume24h = quoteVol,
                        change24h = change,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } else if (eventType == "kline") {
            val k = payload.optJSONObject("k") ?: return
            val openTime = k.optLong("t", 0L)
            val open = k.optString("o", "0").toDoubleOrNull() ?: 0.0
            val high = k.optString("h", "0").toDoubleOrNull() ?: 0.0
            val low = k.optString("l", "0").toDoubleOrNull() ?: 0.0
            val close = k.optString("c", "0").toDoubleOrNull() ?: 0.0
            val volume = k.optString("v", "0").toDoubleOrNull() ?: 0.0

            if (open > 0 && high > 0 && low > 0 && close > 0 && openTime > 0) {
                onCandle(
                    CandleBar(
                        timestamp = openTime,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        volume = volume
                    )
                )
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            scope.launch { onConnected() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            consume(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch {
                onDisconnected()
                reconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { onDisconnected() }
        }
    }
}
