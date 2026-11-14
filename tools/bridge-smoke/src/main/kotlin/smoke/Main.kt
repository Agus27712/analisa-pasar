package smoke

import agu.analys.bridge.*
import android.content.Context
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

fun main() {
    val context = Context()
    val bridge = AgentOutputBridge.getInstance(context)
    File(bridge.getDir(), "latest.json").writeText("{\"fixture\":\"latest\"}")
    File(bridge.getDir(), "portfolio.json").writeText("{\"fixture\":\"portfolio\"}")
    File(bridge.getDir(), "signals.jsonl").writeText("{\"id\":1}\n{\"id\":2}\n")
    val server = AgentHttpServer.getInstance(context)
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    fun request(path: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:8765$path")).timeout(Duration.ofSeconds(10)).build()
    fun get(path: String): String {
        val response = client.send(request(path), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "$path: ${response.statusCode()}" }
        return response.body()
    }
    fun awaitHealth() {
        var failure: Exception? = null
        repeat(100) {
            try { check(get("/health").contains("\"ok\":true")); return }
            catch (e: Exception) { failure = e; Thread.sleep(100) }
        }
        throw IllegalStateException("Server did not become healthy", failure)
    }
    var ws: WebSocket? = null
    var sse: java.io.InputStream? = null
    try {
        server.start()
        awaitHealth()
        check(server.isRunning)
        check(get("/").contains("analisa-pasar-agent-bridge-v3"))
        check(get("/signals/latest").contains("latest"))
        check(get("/portfolio").contains("portfolio"))
        check(get("/signals/history?limit=1") == "[{\"id\":2}]")
        check(get("/api/v1/engine/analysis?symbol=BTCUSDT").contains("SMOKE_FIXTURE"))
        check(get("/api/v1/engine/analysis?symbol=BTCUSDT").contains("BULLISH_CONFLUENCE"))
        check(get("/api/v1/market/analysis?symbol=BTCUSDT").contains("SMOKE_FIXTURE"))
        check(get("/api/v1/market/scan").contains("SMOKE_SCAN_FIXTURE"))
        check(get("/api/v1/market/pairs").contains("BTCUSDT"))
        val mismatchReq = request("/api/v1/market/scan?exchange=INDODAX")
        val mismatchRes = client.send(mismatchReq, HttpResponse.BodyHandlers.ofString())
        check(mismatchRes.statusCode() == 400) { "expected 400 for cross-exchange request, got ${mismatchRes.statusCode()}" }

        fun postSelect(body: String, token: String?): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:8765/api/v1/agent/select-pair"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            if (token != null) builder.header(AgentHttpServer.HEADER_TOKEN, token)
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        val smokeToken = agu.analys.util.AppPreferences(context).agentBridgeToken
        check(postSelect("""{"symbol":"BTCUSDT"}""", null).statusCode() == 401)
        check(postSelect("""{"symbol":"BTCUSDT,ETHUSDT"}""", smokeToken).statusCode() == 400)
        val okSelect = postSelect("""{"symbol":"BTCUSDT","reason":"smoke"}""", smokeToken)
        check(okSelect.statusCode() == 200 && okSelect.body().contains("BTCUSDT"))
        check(get("/api/v1/agent/select-pair").contains("BTCUSDT"))

        val messages = LinkedBlockingQueue<String>()
        ws = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(
            URI("ws://127.0.0.1:8765/ws/signals"), object : WebSocket.Listener {
                private val text = StringBuilder()
                override fun onOpen(socket: WebSocket) { socket.request(1) }
                override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
                    text.append(data)
                    if (last) { messages.add(text.toString()); text.setLength(0) }
                    socket.request(1)
                    return null
                }
            }
        ).get(10, TimeUnit.SECONDS)
        check(messages.poll(10, TimeUnit.SECONDS)?.contains("SNAPSHOT") == true)

        val scanMessages = LinkedBlockingQueue<String>()
        var scanWs: WebSocket? = null
        val scanSnapshot = runBlocking { AgentMarketScanProvider.scanActiveExchange(context) }
        runBlocking { AgentEventBus.emitMarketScan(scanSnapshot) }
        scanWs = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(
            URI("ws://127.0.0.1:8765/ws/market-scan"), object : WebSocket.Listener {
                private val text = StringBuilder()
                override fun onOpen(socket: WebSocket) { socket.request(1) }
                override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
                    text.append(data)
                    if (last) { scanMessages.add(text.toString()); text.setLength(0) }
                    socket.request(1)
                    return null
                }
            }
        ).get(10, TimeUnit.SECONDS)
        check(scanMessages.poll(10, TimeUnit.SECONDS)?.contains("SMOKE_SCAN_FIXTURE") == true)

        val stream = client.send(request("/events"), HttpResponse.BodyHandlers.ofInputStream())
        check(stream.statusCode() == 200)
        check(stream.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
        sse = stream.body()
        val reader = sse.bufferedReader()
        fun line(): String = CompletableFuture.supplyAsync { reader.readLine() }.get(10, TimeUnit.SECONDS)
        fun awaitSseEvent(expectedEvent: String, expectedContains: String) {
            var currentEvent = ""
            repeat(60) {
                val l = line()
                when {
                    l.startsWith("event: ") -> currentEvent = l.removePrefix("event: ").trim()
                    l.startsWith("data: ") -> {
                        val payload = l.removePrefix("data: ")
                        if (currentEvent == expectedEvent && payload.contains(expectedContains)) return
                    }
                    l.isEmpty() -> currentEvent = ""
                }
            }
            error("SSE event '$expectedEvent' containing '$expectedContains' did not arrive")
        }
        check(line() == "event: ping")
        check(line().startsWith("data: "))
        check(line().isEmpty())
        runBlocking { AgentEventBus.emitSignal(bridge.snapshot.copy(symbol = "PUSH")) }
        check(messages.poll(10, TimeUnit.SECONDS)?.contains("PUSH") == true)
        awaitSseEvent("signal", "PUSH")
        runBlocking { AgentEventBus.emitMarketScan(scanSnapshot.copy(marketSummary = "PUSH_SCAN")) }
        check(scanMessages.poll(10, TimeUnit.SECONDS)?.contains("PUSH_SCAN") == true)
        awaitSseEvent("market_scan", "PUSH_SCAN")
        scanWs.abort()
        ws.abort(); ws = null
        sse.close(); sse = null
        val delReq = HttpRequest.newBuilder(URI("http://127.0.0.1:8765/api/v1/agent/select-pair"))
            .timeout(Duration.ofSeconds(10))
            .header(AgentHttpServer.HEADER_TOKEN, smokeToken)
            .DELETE()
            .build()
        check(client.send(delReq, HttpResponse.BodyHandlers.ofString()).statusCode() == 200)
        server.stop()
        check(!server.isRunning)
        runBlocking { server.awaitSettled(20_000) }
        server.start()
        awaitHealth()
        println("PASS: actual AgentHttpServer HTTP, file/history, engine analysis, market scan, agent select-pair, WebSocket snapshot/push, SSE ping/push, stop/restart")
    } finally {
        ws?.abort()
        sse?.close()
        server.stop()
        bridge.getDir().deleteRecursively()
    }
}
