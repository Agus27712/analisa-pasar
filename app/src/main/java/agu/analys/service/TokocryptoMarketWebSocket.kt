package agu.analys.service

import agu.analys.data.TokocryptoSymbolRepository
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
 * WebSocket Market Stream Resmi Tokocrypto:
 * Mendukung combined streams: Live Kline (dengan deteksi k.x closed), Live Trade, dan Live Mini Ticker.
 *
 * Host dipilih menurut symbol type (sesuai dokumentasi resmi Tokocrypto):
 * - Type 1 (MBX)    : wss://stream-cloud.tokocrypto.site/stream
 * - Type 2 (Next)   : wss://www.tokocrypto.com
 * - Type 3 (NextMe) : wss://stream-toko.2meta.app
 *
 * Tidak ada koneksi ke Binance. Satu koneksi hanya berlaku 24 jam, jadi saat server menutup
 * koneksi (onClosed) kita otomatis menyambung ulang ke host yang sama dengan backoff.
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

    @Volatile private var socket: WebSocket? = null
    // Naik setiap start()/stop(); listener dengan generation lama otomatis diabaikan (tanpa race saat socket baru dibuat)
    @Volatile private var generation = 0
    private var reconnectJob: Job? = null
    private var streamSymbol = ""
    private var streamHost = ""
    private var currentTimeframe = Timeframe.M1
    private var reconnectAttempt = 0
    private val lastMessageAt = AtomicLong(0L)

    private val tradeTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Jakarta"))

    /** Host stream resmi Tokocrypto berdasarkan symbol type. */
    private fun hostForSymbolType(type: Int): String = when (type) {
        2 -> "wss://www.tokocrypto.com"
        3 -> "wss://stream-toko.2meta.app"
        else -> "wss://stream-cloud.tokocrypto.site/stream"
    }

    fun start(symbol: String, timeframe: Timeframe = Timeframe.M1) {
        // Format stream: tanpa underscore, huruf kecil (BTC_USDT -> btcusdt)
        val nextSymbol = symbol.trim().replace("_", "").replace("/", "").replace("-", "").lowercase()
        if (nextSymbol.isBlank()) return
        stop(false)
        streamSymbol = nextSymbol
        // Cari tipe simbol lewat format BASE_QUOTE (BTC_USDT), kunci yang selalu ada di symbol map
        val symbolType = TokocryptoSymbolRepository.getSymbolType(TokocryptoMarketService.toTokocryptoPair(symbol))
        streamHost = hostForSymbolType(symbolType)
        currentTimeframe = timeframe
        reconnectAttempt = 0
        connect()
    }

    fun stop(notify: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        generation++
        val old = socket
        socket = null
        old?.close(1000, "Normal closure")
        streamSymbol = ""
        streamHost = ""
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
        if (streamSymbol.isBlank() || streamHost.isBlank()) return
        val interval = getIntervalCode(currentTimeframe)

        // Combined streams: miniTicker + kline + trade
        val streams = "${streamSymbol}@miniTicker/${streamSymbol}@kline_${interval}/${streamSymbol}@trade"
        val url = "$streamHost?streams=$streams"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 TokoStream/3.5")
            .build()
        val gen = generation
        socket = client.newWebSocket(request, Listener { gen == generation })
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

    private inner class Listener(private val isCurrent: () -> Boolean) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) {
                webSocket.close(1000, "Superseded")
                return
            }
            reconnectAttempt = 0
            lastMessageAt.set(System.currentTimeMillis())
            onConnected()
            Timber.i("TokocryptoMarketWS: Connected to $streamSymbol @ $streamHost")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            lastMessageAt.set(System.currentTimeMillis())
            try {
                val root = JSONObject(text)
                // Format combined stream payload: { "stream": "btcusdt@miniTicker", "data": { ... } }
                val streamName = root.optString("stream", "")
                val data = root.optJSONObject("data") ?: root

                when {
                    streamName.contains("@miniTicker") || streamName.contains("@ticker") ||
                        (data.has("c") && data.has("h") && data.has("l") && !data.has("k")) -> {
                        val sym = data.optString("s", streamSymbol).uppercase()
                        val lastPrice = data.optString("c", "0").toDoubleOrNull() ?: 0.0
                        if (lastPrice > 0) {
                            val high = data.optString("h", "0").toDoubleOrNull() ?: lastPrice
                            val low = data.optString("l", "0").toDoubleOrNull() ?: lastPrice
                            val quoteVol = data.optString("q", "0").toDoubleOrNull() ?: 0.0
                            // miniTicker tidak punya "P"; hitung dari harga open (o) jika ada
                            val open24 = data.optString("o", "0").toDoubleOrNull() ?: 0.0
                            val changePct = data.optString("P", "").toDoubleOrNull()
                                ?: if (open24 > 0) (lastPrice - open24) / open24 * 100.0 else 0.0
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
            if (!isCurrent()) return
            Timber.w(t, "TokocryptoMarketWS failure: ${t.message}")
            onDisconnected()
            reconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Server memulai penutupan (mis. batas 24 jam): balas close agar handshake selesai
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            Timber.i("TokocryptoMarketWS closed: $code $reason")
            onDisconnected()
            // Koneksi ditutup server (batas 24 jam) dan bukan oleh stop(): sambung ulang
            reconnect()
        }
    }
}