package agu.analys.service

import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Realtime Tokocrypto & Binance Cloud WebSocket Stream:
 * Mendengarkan 24hrTicker dan Kline updates secara instan tanpa delay polling.
 */
class TokocryptoMarketWebSocket(
    private val scope: CoroutineScope,
    private val onTick: (MarketTick) -> Unit,
    private val onCandle: (CandleBar) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val client get() = NetworkClientProvider.webSocketClient

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var streamSymbol = ""
    private var reconnectAttempt = 0
    private val lastMessageAt = AtomicLong(0L)

    private val WS_HOSTS = listOf(
        "wss://stream.binance.com:9443/ws",
        "wss://data-stream.binance.vision/ws"
    )

    fun start(symbol: String) {
        val nextSymbol = TokocryptoMarketService.toBinanceSymbol(symbol).lowercase()
        if (nextSymbol.isBlank()) return
        stop(false)
        streamSymbol = nextSymbol
        reconnectAttempt = 0
        connect()
    }

    fun stop(notify: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "switch pair")
        socket = null
        streamSymbol = ""
        if (notify) onDisconnected()
    }

    fun close() = stop(false)

    fun isStale(staleMs: Long = 25_000L): Boolean {
        val last = lastMessageAt.get()
        if (last <= 0L) return false
        return System.currentTimeMillis() - last > staleMs
    }

    private fun connect() {
        if (streamSymbol.isBlank()) return
        val host = WS_HOSTS[reconnectAttempt % WS_HOSTS.size]
        val url = "$host/${streamSymbol}@ticker"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AnalysisPasar/2.1 (Android; Tokocrypto SSOT)")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun reconnect() {
        if (streamSymbol.isBlank() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val waitMs = min(12_000L, (800L * (1L shl min(reconnectAttempt, 4))))
            reconnectAttempt++
            delay(waitMs)
            if (isActive && streamSymbol.isNotBlank()) connect()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            lastMessageAt.set(System.currentTimeMillis())
            onConnected()
            Timber.i("TokocryptoWS: Connected to $streamSymbol")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastMessageAt.set(System.currentTimeMillis())
            try {
                val obj = JSONObject(text)
                val event = obj.optString("e", "")
                if (event == "24hrTicker") {
                    val sym = obj.optString("s", "")
                    val last = obj.optString("c", "0").toDoubleOrNull() ?: 0.0
                    val high = obj.optString("h", "0").toDoubleOrNull() ?: last
                    val low = obj.optString("l", "0").toDoubleOrNull() ?: last
                    val quoteVol = obj.optString("q", "0").toDoubleOrNull() ?: 0.0
                    val changePct = obj.optString("P", "0").toDoubleOrNull() ?: 0.0

                    if (last > 0.0) {
                        onTick(
                            MarketTick(
                                symbol = sym,
                                price = last,
                                high24h = high,
                                low24h = low,
                                volume24h = quoteVol,
                                change24h = changePct,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                } else if (event == "kline") {
                    val k = obj.optJSONObject("k")
                    if (k != null) {
                        val openTime = k.optLong("t", 0L)
                        val o = k.optString("o", "0").toDoubleOrNull() ?: 0.0
                        val h = k.optString("h", "0").toDoubleOrNull() ?: 0.0
                        val l = k.optString("l", "0").toDoubleOrNull() ?: 0.0
                        val c = k.optString("c", "0").toDoubleOrNull() ?: 0.0
                        val v = k.optString("v", "0").toDoubleOrNull() ?: 0.0
                        if (openTime > 0 && c > 0) {
                            onCandle(
                                CandleBar(
                                    timestamp = openTime / 1000L,
                                    open = o,
                                    high = h,
                                    low = l,
                                    close = c,
                                    volume = v
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "TokocryptoWS: Error parsing message: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.w("TokocryptoWS: Failure: ${t.message}")
            onDisconnected()
            reconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDisconnected()
        }
    }
}
