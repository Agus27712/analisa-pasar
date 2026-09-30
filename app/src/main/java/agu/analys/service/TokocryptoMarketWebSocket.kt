package agu.analys.service

import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * WebSocket Market Stream Resmi Tokocrypto (Jalur 2 Market Data):
 * Mendukung combined streams: Live Kline (dengan deteksi k.x closed), Live Trade, Live Ticker, dan Live Depth.
 *
 * Endpoint:
 * - Primary: wss://stream-cloud.tokocrypto.site/stream?streams=...
 * - Fallback: wss://stream.binance.com:9443/stream?streams=...
 */
class TokocryptoMarketWebSocket(
    private val scope: CoroutineScope,
    private val onTick: (MarketTick) -> Unit,
    private val onCandle: (CandleBar) -> Unit,
    private val onTrade: ((TradeStreamItem) -> Unit)? = null,
    private val onCandleClosed: ((CandleBar) -> Unit)? = null,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val client get() = NetworkClientProvider.webSocketClient

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var streamSymbol = ""
    private var currentTimeframe = Timeframe.M1
    private var reconnectAttempt = 0
    private val lastMessageAt = AtomicLong(0L)

    private val tradeTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Jakarta"))

    private val WS_HOSTS = listOf(
        "wss://stream-cloud.tokocrypto.site/stream",
        "wss://stream.binance.com:9443/stream",
        "wss://data-stream.binance.vision/stream"
    )

    fun start(symbol: String, timeframe: Timeframe = Timeframe.M1) {
        val nextSymbol = TokocryptoMarketService.toBinanceSymbol(symbol).lowercase()
        if (nextSymbol.isBlank()) return
        stop(false)
        streamSymbol = nextSymbol
        currentTimeframe = timeframe
        reconnectAttempt = 0
        connect()
    }

    fun stop(notify: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "Normal closure")
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

    private fun getIntervalCode(tf: Timeframe): String = when (tf) {
        Timeframe.M1 -> "1m"
        Timeframe.M5 -> "5m"
        Timeframe.M15 -> "15m"
        Timeframe.H1 -> "1h"
        Timeframe.H4 -> "4h"
        Timeframe.D1 -> "1d"
    }

    private fun connect() {
        if (streamSymbol.isBlank()) return
        val host = WS_HOSTS[reconnectAttempt % WS_HOSTS.size]
        val interval = getIntervalCode(currentTimeframe)

        // Combined streams: ticker + kline + trade
        val streams = "${streamSymbol}@ticker/${streamSymbol}@kline_${interval}/${streamSymbol}@trade"
        val url = "$host?streams=$streams"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 TokoStream/3.5")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun reconnect() {
        if (streamSymbol.isBlank() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val waitMs = min(12_000L, (600L * (1L shl min(reconnectAttempt, 4))))
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
            Timber.i("TokocryptoMarketWS: Connected to $streamSymbol")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastMessageAt.set(System.currentTimeMillis())
            try {
                val root = JSONObject(text)
                // Format combined stream payload: { "stream": "btcbidr@ticker", "data": { ... } }
                val streamName = root.optString("stream", "")
                val data = root.optJSONObject("data") ?: root

                when {
                    streamName.contains("@ticker") || data.has("c") && data.has("h") && data.has("l") && !data.has("k") -> {
                        val sym = data.optString("s", streamSymbol).uppercase()
                        val lastPrice = data.optString("c", "0").toDoubleOrNull() ?: 0.0
                        if (lastPrice > 0) {
                            val high = data.optString("h", "0").toDoubleOrNull() ?: lastPrice
                            val low = data.optString("l", "0").toDoubleOrNull() ?: lastPrice
                            val quoteVol = data.optString("q", "0").toDoubleOrNull() ?: 0.0
                            val changePct = data.optString("P", "0").toDoubleOrNull() ?: 0.0
                            val tick = MarketTick(
                                symbol = sym,
                                price = lastPrice,
                                high24h = high,
                                low24h = low,
                                volume24h = quoteVol,
                                change24h = changePct,
                                timestamp = System.currentTimeMillis()
                            )
                            onTick(tick)
                        }
                    }

                    streamName.contains("@kline") || data.has("k") -> {
                        val kObj = data.optJSONObject("k")
                        if (kObj != null) {
                            val openTime = kObj.optLong("t", 0L)
                            val open = kObj.optString("o", "0").toDoubleOrNull() ?: 0.0
                            val high = kObj.optString("h", "0").toDoubleOrNull() ?: 0.0
                            val low = kObj.optString("l", "0").toDoubleOrNull() ?: 0.0
                            val close = kObj.optString("c", "0").toDoubleOrNull() ?: 0.0
                            val volume = kObj.optString("v", "0").toDoubleOrNull() ?: 0.0
                            val isClosed = kObj.optBoolean("x", false)

                            if (openTime > 0 && close > 0) {
                                val candle = CandleBar(
                                    timestamp = openTime,
                                    open = open,
                                    high = high,
                                    low = low,
                                    close = close,
                                    volume = volume
                                )
                                onCandle(candle)
                                if (isClosed) {
                                    onCandleClosed?.invoke(candle)
                                }
                            }
                        }
                    }

                    streamName.contains("@trade") || (data.has("p") && data.has("q") && data.has("t")) -> {
                        val p = data.optString("p", "0").toDoubleOrNull() ?: 0.0
                        val q = data.optString("q", "0").toDoubleOrNull() ?: 0.0
                        val t = data.optLong("T", data.optLong("t", System.currentTimeMillis()))
                        val isBuyerMaker = data.optBoolean("m", false)
                        val id = data.optLong("t", 0L).toString()
                        if (p > 0 && q > 0) {
                            val trade = TradeStreamItem(
                                id = id,
                                price = p,
                                amount = q,
                                timeFormatted = tradeTimeFormatter.format(Instant.ofEpochMilli(t)),
                                isBuy = !isBuyerMaker
                            )
                            onTrade?.invoke(trade)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error parse Tokocrypto WS frame")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.w(t, "TokocryptoMarketWS failure: ${t.message}")
            onDisconnected()
            reconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.i("TokocryptoMarketWS closed: $code $reason")
            onDisconnected()
        }
    }
}
