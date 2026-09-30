package agu.analys.service

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
 * WebSocket User Data Stream Resmi Tokocrypto (Jalur 3: User Data & Order Execution):
 * Menggunakan mekanismen token `user-listen-token` resmi dari Tokocrypto.
 * Mendengarkan update eksekusi order (fill/cancel), update balance, dan event akun.
 */
class TokocryptoUserWebSocket(
    private val scope: CoroutineScope,
    private val onAccountUpdate: (JSONObject) -> Unit,
    private val onOrderUpdate: (JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val client get() = NetworkClientProvider.webSocketClient
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentListenKey = ""
    private var reconnectAttempt = 0
    private val lastMessageAt = AtomicLong(0L)

    private val WS_HOSTS = listOf(
        "wss://stream-cloud.tokocrypto.site/ws",
        "wss://stream.binance.com:9443/ws"
    )

    fun start(listenKey: String) {
        if (listenKey.isBlank()) return
        stop(false)
        currentListenKey = listenKey
        reconnectAttempt = 0
        connect()
    }

    fun stop(notify: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "User stream stopped")
        socket = null
        currentListenKey = ""
        if (notify) onDisconnected()
    }

    private fun connect() {
        if (currentListenKey.isBlank()) return
        val host = WS_HOSTS[reconnectAttempt % WS_HOSTS.size]
        val url = "$host/$currentListenKey"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 TokoUserStream/3.5")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun reconnect() {
        if (currentListenKey.isBlank() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val waitMs = min(15_000L, (1000L * (1L shl min(reconnectAttempt, 4))))
            reconnectAttempt++
            delay(waitMs)
            if (isActive && currentListenKey.isNotBlank()) connect()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            lastMessageAt.set(System.currentTimeMillis())
            onConnected()
            Timber.i("TokocryptoUserWS: Connected to user stream")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastMessageAt.set(System.currentTimeMillis())
            try {
                val root = JSONObject(text)
                val eventType = root.optString("e", "")
                when (eventType) {
                    "outboundAccountPosition", "balanceUpdate", "ACCOUNT_UPDATE" -> {
                        onAccountUpdate(root)
                    }
                    "executionReport", "ORDER_TRADE_UPDATE" -> {
                        onOrderUpdate(root)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error parsing Tokocrypto User WS message")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.w(t, "TokocryptoUserWS failure: ${t.message}")
            onDisconnected()
            reconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.i("TokocryptoUserWS closed: $code $reason")
            onDisconnected()
        }
    }
}
