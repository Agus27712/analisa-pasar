package agu.analys.engine.global

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GlobalMarketWebSocket {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var binanceWs: WebSocket? = null
    private var bybitWs: WebSocket? = null

    private val _btcTickerFlow = MutableStateFlow<BtcTickerData?>(null)
    val btcTickerFlow: StateFlow<BtcTickerData?> = _btcTickerFlow.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var binanceConnected = false
    private var bybitConnected = false

    private val binanceUrl = "wss://stream.binance.com/ws/btcusdt@ticker"
    private val bybitUrl = "wss://stream.bybit.com/v5/public/spot"

    fun connect() {
        connectBinance()
        connectBybit()
    }

    private fun connectBinance() {
        if (binanceConnected) return
        val request = Request.Builder().url(binanceUrl).build()
        binanceWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("GlobalMarketWS", "Connected to Binance")
                binanceConnected = true
                updateConnectionState()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val priceStr = json.optString("c", "")
                    val changePctStr = json.optString("P", "")
                    
                    val price = priceStr.toDoubleOrNull() ?: 0.0
                    if (price > 0) {
                        val changePct = changePctStr.toDoubleOrNull() ?: 0.0
                        _btcTickerFlow.value = BtcTickerData(price, changePct, "Binance")
                    }
                } catch (e: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                binanceConnected = false
                updateConnectionState()
                reconnectBinance()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                binanceConnected = false
                updateConnectionState()
                reconnectBinance()
            }
        })
    }

    private fun connectBybit() {
        if (bybitConnected) return
        val request = Request.Builder().url(bybitUrl).build()
        bybitWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("GlobalMarketWS", "Connected to Bybit")
                bybitConnected = true
                updateConnectionState()
                // Subscribe to BTCUSDT ticker
                webSocket.send("{\"op\": \"subscribe\", \"args\": [\"tickers.BTCUSDT\"]}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("topic") && json.getString("topic") == "tickers.BTCUSDT") {
                        val data = json.getJSONObject("data")
                        val priceStr = data.optString("lastPrice", "")
                        val change24hStr = data.optString("price24hPcnt", "")
                        
                        val price = priceStr.toDoubleOrNull() ?: 0.0
                        if (price > 0 && change24hStr.isNotEmpty()) {
                            val changePct = change24hStr.toDoubleOrNull()?.times(100.0) ?: 0.0
                            _btcTickerFlow.value = BtcTickerData(price, changePct, "Bybit")
                        }
                    }
                } catch (e: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                bybitConnected = false
                updateConnectionState()
                reconnectBybit()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                bybitConnected = false
                updateConnectionState()
                reconnectBybit()
            }
        })
    }

    private fun updateConnectionState() {
        _isConnected.value = binanceConnected || bybitConnected
    }

    private fun reconnectBinance() {
        scope.launch {
            delay(5000)
            connectBinance()
        }
    }

    private fun reconnectBybit() {
        scope.launch {
            delay(5000)
            connectBybit()
        }
    }

    fun disconnect() {
        binanceWs?.close(1000, "App closed")
        bybitWs?.close(1000, "App closed")
        binanceWs = null
        bybitWs = null
        binanceConnected = false
        bybitConnected = false
        updateConnectionState()
    }
}

data class BtcTickerData(
    val price: Double,
    val changePct: Double,
    val source: String
)
