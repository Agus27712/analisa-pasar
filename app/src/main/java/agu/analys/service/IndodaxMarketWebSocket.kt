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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Exchange-native realtime transport for scalping. REST remains the historical/bootstrap source. */
class IndodaxMarketWebSocket(
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
    private var pairId = ""
    private var reconnectAttempt = 0
    private var candle: CandleBar? = null
    private var lastSequence = -1L

    fun start(symbol: String) {
        val nextPair = IndodaxMarketService.toPairId(symbol).replace("_", "").lowercase()
        if (nextPair.isBlank()) return
        stop(false)
        pairId = nextPair
        reconnectAttempt = 0
        connect()
    }

    fun stop(notify: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "switch pair")
        socket = null
        pairId = ""
        candle = null
        lastSequence = -1L
        if (notify) onDisconnected()
    }

    fun close() = stop(false)

    private fun connect() {
        if (pairId.isBlank()) return
        val request = Request.Builder().url(WS_URL).header("User-Agent", "AnalysisPasar/1.0").build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun reconnect() {
        if (pairId.isBlank() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val waitMs = min(15_000L, 1_000L * (1L shl min(reconnectAttempt, 4)))
            reconnectAttempt++
            delay(waitMs)
            if (isActive && pairId.isNotBlank()) connect()
        }
    }

    private fun subscribe(ws: WebSocket) {
        ws.send(JSONObject().apply {
            put("params", JSONObject().put("token", STATIC_TOKEN))
            put("id", 1)
        }.toString())
        ws.send(JSONObject().apply {
            put("method", 1)
            put("params", JSONObject().put("channel", "chart:tick-$pairId"))
            put("id", 2)
        }.toString())
        ws.send(JSONObject().apply { put("method", 7); put("id", 3) }.toString())
    }

    private fun consume(message: String) {
        val root = runCatching { JSONObject(message) }.getOrNull() ?: return
        val result = root.optJSONObject("result") ?: return
        if (result.optString("channel") != "chart:tick-$pairId") return
        val payload = result.optJSONObject("data") ?: return
        val rows = payload.optJSONArray("data") ?: return
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            if (row.length() < 4) continue
            val epochSec = row.optLong(0, 0L)
            val sequence = row.optLong(1, -1L)
            val price = number(row, 2)
            val volume = number(row, 3)
            if (epochSec <= 0L || price <= 0.0) continue
            if (sequence >= 0L && sequence <= lastSequence) continue
            if (sequence >= 0L) lastSequence = sequence
            val timestamp = epochSec * 1000L
            onTick(MarketTick(pairId.uppercase(), price, price, price, 0.0, Double.NaN, timestamp))
            updateOneMinuteCandle(timestamp, price, volume)
        }
    }

    private fun updateOneMinuteCandle(timestamp: Long, price: Double, volume: Double) {
        val minute = timestamp - (timestamp % 60_000L)
        val current = candle
        val next = if (current == null || current.timestamp != minute) {
            current?.let(onCandle)
            CandleBar(minute, price, price, price, price, max(0.0, volume))
        } else {
            current.copy(
                high = max(current.high, price),
                low = min(current.low, price),
                close = price,
                volume = current.volume + max(0.0, volume)
            )
        }
        candle = next
        onCandle(next)
    }

    private fun number(row: JSONArray, index: Int): Double = when (val value = row.opt(index)) {
        is Number -> value.toDouble()
        else -> value?.toString()?.toDoubleOrNull() ?: 0.0
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            onConnected()
            subscribe(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = consume(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) socket = null
            onDisconnected()
            reconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) socket = null
            onDisconnected()
            if (code != 1000) reconnect()
        }
    }

    companion object {
        private const val WS_URL = "wss://ws3.indodax.com/ws/"
        private const val STATIC_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE5NDY2MTg0MTV9.UR1lBM6Eqh0yWz-PVirw1uPCxe60FdchR8eNVdsske"
    }
}
