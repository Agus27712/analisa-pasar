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
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Realtime Indodax WS. REST tetap bootstrap + fallback di ViewModel.
 * Reconnect: 0.8s → max 12s (lebih responsif).
 */
class IndodaxMarketWebSocket(
    private val scope: CoroutineScope,
    private val onTick: (MarketTick) -> Unit,
    private val onCandle: (CandleBar) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val client get() = NetworkClientProvider.webSocketClient

    @Volatile private var socket: WebSocket? = null
    // Naik setiap stop()/reconnectNow(); listener dengan generasi lama diabaikan (K9).
    @Volatile private var generation = 0
    private var reconnectJob: Job? = null
    private var pairId = ""
    private var reconnectAttempt = 0
    private var candle: CandleBar? = null
    private var lastTimestampMs = 0L
    private var lastPrice = 0.0
    private var cumulativeVolume = 0.0
    private val lastMessageAt = AtomicLong(0L)
    private val connectedAtMs = AtomicLong(0L)

    fun start(symbol: String) {
        val nextPair = IndodaxMarketService.toPairId(symbol).replace("_", "").lowercase()
        if (nextPair.isBlank()) return
        stop(false)
        pairId = nextPair
        reconnectAttempt = 0
        connect()
    }

    /**
     * Menyambung ulang **tanpa** membuang histori: pair, candle menit berjalan, harga dan volume
     * terakhir tetap dipertahankan. Dipakai saat socket terdeteksi usang (**K7**) — dulu
     * pemanggil memanggil `start()` yang menjalankan `stop(false)`, sehingga candle hilang tiap
     * kali pair sedang sepi.
     */
    fun reconnectNow() {
        if (pairId.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = null
        generation++
        val old = socket
        socket = null
        old?.close(1000, "reconnect")
        lastMessageAt.set(0L)
        connectedAtMs.set(0L)
        connect()
    }

    /** Menyambung ulang hanya bila [RealtimeSocketHealth] menilai socket perlu disambung ulang. */
    fun reconnectIfStale() {
        if (!RealtimeSocketHealth.needsRestart(socket != null, connectedAtMs.get(), lastMessageAt.get(), System.currentTimeMillis())) return
        reconnectNow()
    }

    fun stop(notify: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        generation++
        val old = socket
        socket = null
        old?.close(1000, "switch pair")
        pairId = ""
        candle = null
        lastTimestampMs = 0L
        lastPrice = 0.0
        cumulativeVolume = 0.0
        lastMessageAt.set(0L)
        connectedAtMs.set(0L)
        if (notify) onDisconnected()
    }

    fun close() = stop(false)

    /** True bila socket perlu disambung ulang menurut [RealtimeSocketHealth]. */
    fun isStale(): Boolean = RealtimeSocketHealth.needsRestart(
        socketOpen = socket != null,
        connectedAtMs = connectedAtMs.get(),
        lastMessageAtMs = lastMessageAt.get(),
        nowMs = System.currentTimeMillis()
    )

    private fun connect() {
        if (pairId.isBlank()) return
        if (!IndodaxSummaryFeed.isValidTokenShape(STATIC_TOKEN)) {
            Timber.e("Token WebSocket Indodax tidak utuh; sambungan dibatalkan")
            onDisconnected()
            return
        }
        val request = Request.Builder()
            .url(WS_URL)
            .header("User-Agent", "AnalysisPasar/2.1")
            .header("Origin", "https://indodax.com")
            .build()
        val gen = generation
        socket = client.newWebSocket(request, Listener { gen == generation })
    }

    private fun reconnect() {
        if (pairId.isBlank() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val attempt = reconnectAttempt + 1
            val giveUp = ReconnectBackoff.shouldGiveUp(attempt)
            val waitMs = if (giveUp) {
                ReconnectBackoff.GIVE_UP_PAUSE_MS
            } else {
                ReconnectBackoff.delayMs(attempt, BASE_RECONNECT_MS, MAX_RECONNECT_MS, Random.nextDouble())
            }
            reconnectAttempt = if (giveUp) 0 else attempt
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
            put("params", JSONObject().put("channel", IndodaxSummaryFeed.CHANNEL))
            put("id", 2)
        }.toString())
        ws.send(JSONObject().apply { put("method", 7); put("id", 3) }.toString())
    }

    private fun consume(message: String) {
        // Kanal ringkasan mengirim semua pair; trafik apa pun menandakan socket masih hidup.
        lastMessageAt.set(System.currentTimeMillis())
        // K8 — hitungan percobaan hanya di-reset setelah sesi terbukti stabil, bukan di onOpen.
        if (reconnectAttempt != 0 &&
            ReconnectBackoff.isStable(connectedAtMs.get(), System.currentTimeMillis())
        ) {
            reconnectAttempt = 0
        }
        val tick = IndodaxSummaryFeed.parse(message, pairId, cumulativeVolume) ?: return

        // Pesan bisa datang tidak berurutan; jangan mundur, dan jangan ulang baris yang sama.
        if (tick.timestampMs < lastTimestampMs) return
        if (tick.timestampMs == lastTimestampMs && tick.price == lastPrice) return

        cumulativeVolume = tick.volumeCoin24h
        lastTimestampMs = tick.timestampMs
        lastPrice = tick.price

        onTick(
            MarketTick(
                symbol = pairId.uppercase(),
                price = tick.price,
                high24h = tick.high24h,
                low24h = tick.low24h,
                volume24h = tick.volumeIdr24h,
                change24h = tick.change24h,
                timestamp = tick.timestampMs
            )
        )
        updateOneMinuteCandle(tick.timestampMs, tick.price, tick.volumeCoinDelta)
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

    private inner class Listener(private val isCurrent: () -> Boolean) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) {
                webSocket.close(1000, "Superseded")
                return
            }
            connectedAtMs.set(System.currentTimeMillis())
            onConnected()
            subscribe(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            consume(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) return
            if (socket === webSocket) socket = null
            onDisconnected()
            reconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            if (socket === webSocket) socket = null
            onDisconnected()
            if (code != 1000) reconnect()
        }
    }

    companion object {
        private const val WS_URL = "wss://ws3.indodax.com/ws/"
        private const val BASE_RECONNECT_MS = 800L
        private const val MAX_RECONNECT_MS = 12_000L

        /**
         * Token publik kanal `market:summary-24h`. Berlaku sampai 2031-09-08 (`exp`
         * 1946618415). Harus utuh 105 karakter — versi lama terpotong satu karakter
         * sehingga server menolak dengan `3002 invalid token`.
         */
        private const val STATIC_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE5NDY2MTg0MTV9.UR1lBM6Eqh0yWz-PVirw1uPCxe60FdchR8eNVdsskeo"
    }
}
