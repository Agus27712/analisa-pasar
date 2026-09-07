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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var binanceWs: WebSocket? = null

    private val _btcTickerFlow = MutableStateFlow<BtcTickerData?>(null)
    val btcTickerFlow: StateFlow<BtcTickerData?> = _btcTickerFlow.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var binanceConnected = false

    // Endpoints Binance: utamakan data-stream.binance.vision karena domain binance.vision tidak masuk daftar blacklist Kominfo / ISP Indonesia
    private val binanceEndpoints = listOf(
        "wss://data-stream.binance.vision/ws/btcusdt@ticker",
        "wss://stream.binance.com:9443/ws/btcusdt@ticker",
        "wss://stream.binance.com/ws/btcusdt@ticker"
    )
    private var currentEndpointIndex = 0

    fun connect() {
        connectBinance()
    }

    private fun connectBinance() {
        if (binanceConnected) return
        val currentUrl = binanceEndpoints[currentEndpointIndex % binanceEndpoints.size]
        Log.d("GlobalMarketWS", "Connecting to Binance via $currentUrl")
        
        val request = Request.Builder().url(currentUrl).build()
        binanceWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("GlobalMarketWS", "Connected to Binance ($currentUrl)")
                binanceConnected = true
                _isConnected.value = true
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
                } catch (e: Exception) {
                    Log.w("GlobalMarketWS", "Error parsing Binance message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("GlobalMarketWS", "Binance WS closed: $reason")
                binanceConnected = false
                _isConnected.value = false
                rotateEndpointAndReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("GlobalMarketWS", "Binance WS failure ($currentUrl): ${t.message}")
                binanceConnected = false
                _isConnected.value = false
                rotateEndpointAndReconnect()
            }
        })
    }

    private fun rotateEndpointAndReconnect() {
        currentEndpointIndex = (currentEndpointIndex + 1) % binanceEndpoints.size
        scope.launch {
            delay(3000)
            connectBinance()
        }
    }

    fun disconnect() {
        binanceWs?.close(1000, "App closed")
        binanceWs = null
        binanceConnected = false
        _isConnected.value = false
    }
}

data class BtcTickerData(
    val price: Double,
    val changePct: Double,
    val source: String
)
