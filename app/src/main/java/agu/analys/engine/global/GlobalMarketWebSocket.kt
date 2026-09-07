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

    private val _coinTickerFlow = MutableStateFlow<BinanceCoinTicker?>(null)
    val coinTickerFlow: StateFlow<BinanceCoinTicker?> = _coinTickerFlow.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var binanceConnected = false
    private var activeCoinWs: WebSocket? = null
    private var currentSubscribedBase: String? = null

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

    fun subscribeCoin(baseAsset: String) {
        val normalized = baseAsset.trim().uppercase()
        if (normalized.isBlank()) return
        if (normalized == "USDT" || normalized == "USD") {
            _coinTickerFlow.value = BinanceCoinTicker(
                symbol = "USDTUSDT",
                baseAsset = "USDT",
                price = 1.0,
                changePct24h = 0.0,
                timestamp = System.currentTimeMillis(),
                isAvailable = true
            )
            return
        }

        if (currentSubscribedBase == normalized && _coinTickerFlow.value != null && 
            System.currentTimeMillis() - (_coinTickerFlow.value?.timestamp ?: 0L) < 15_000L) {
            return
        }

        currentSubscribedBase = normalized
        scope.launch {
            fetchCoinTickerRest(normalized)
            connectCoinWebSocket(normalized)
        }
    }

    private fun fetchCoinTickerRest(baseAsset: String) {
        val symbol = "${baseAsset.uppercase()}USDT"
        val urls = listOf(
            "https://data-api.binance.vision/api/v3/ticker/24hr?symbol=$symbol",
            "https://api.binance.com/api/v3/ticker/24hr?symbol=$symbol"
        )
        for (url in urls) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val price = json.optString("lastPrice", "0.0").toDoubleOrNull() ?: 0.0
                        val changePct = json.optString("priceChangePercent", "0.0").toDoubleOrNull() ?: 0.0
                        if (price > 0.0) {
                            _coinTickerFlow.value = BinanceCoinTicker(
                                symbol = symbol,
                                baseAsset = baseAsset.uppercase(),
                                price = price,
                                changePct24h = changePct,
                                timestamp = System.currentTimeMillis(),
                                isAvailable = true
                            )
                            return
                        }
                    } else if (response.code == 400 || response.code == 404) {
                        _coinTickerFlow.value = BinanceCoinTicker(
                            symbol = symbol,
                            baseAsset = baseAsset.uppercase(),
                            isAvailable = false,
                            timestamp = System.currentTimeMillis()
                        )
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w("GlobalMarketWS", "Error fetching REST ticker for $symbol: ${e.message}")
            }
        }
    }

    private fun connectCoinWebSocket(baseAsset: String) {
        try {
            activeCoinWs?.close(1000, "Switching coin")
        } catch (_: Exception) {}
        activeCoinWs = null

        val streamSymbol = "${baseAsset.lowercase()}usdt@ticker"
        val request = Request.Builder()
            .url("wss://data-stream.binance.vision/ws/$streamSymbol")
            .build()

        activeCoinWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val price = json.optString("c", "0.0").toDoubleOrNull() ?: 0.0
                    val changePct = json.optString("P", "0.0").toDoubleOrNull() ?: 0.0
                    if (price > 0.0) {
                        _coinTickerFlow.value = BinanceCoinTicker(
                            symbol = "${baseAsset.uppercase()}USDT",
                            baseAsset = baseAsset.uppercase(),
                            price = price,
                            changePct24h = changePct,
                            timestamp = System.currentTimeMillis(),
                            isAvailable = true
                        )
                    }
                } catch (e: Exception) {
                    Log.w("GlobalMarketWS", "Error parsing coin ticker: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("GlobalMarketWS", "Coin WS failure for $baseAsset: ${t.message}")
            }
        })
    }

    fun disconnect() {
        binanceWs?.close(1000, "App closed")
        binanceWs = null
        try {
            activeCoinWs?.close(1000, "App closed")
        } catch (_: Exception) {}
        activeCoinWs = null
        binanceConnected = false
        _isConnected.value = false
    }
}

data class BtcTickerData(
    val price: Double,
    val changePct: Double,
    val source: String
)
