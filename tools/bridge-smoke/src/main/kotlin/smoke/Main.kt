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
        repeat(50) {
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
        check(get("/").contains("agent-bridge-v2"))
        check(get("/signals/latest").contains("latest"))
        check(get("/portfolio").contains("portfolio"))
        check(get("/signals/history?limit=1") == "[{\"id\":2}]")
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
        val stream = client.send(request("/events"), HttpResponse.BodyHandlers.ofInputStream())
        check(stream.statusCode() == 200)
        check(stream.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
        sse = stream.body()
        val reader = sse.bufferedReader()
        fun line(): String = CompletableFuture.supplyAsync { reader.readLine() }.get(10, TimeUnit.SECONDS)
        check(line() == "event: ping")
        check(line().startsWith("data: "))
        check(line().isEmpty())
        runBlocking { AgentEventBus.emitSignal(bridge.snapshot.copy(symbol = "PUSH")) }
        check(messages.poll(10, TimeUnit.SECONDS)?.contains("PUSH") == true)
        check(line() == "event: signal")
        check(line().contains("PUSH"))
        ws.abort(); ws = null
        sse.close(); sse = null
        server.stop()
        check(!server.isRunning)
        server.start()
        awaitHealth()
        println("PASS: actual AgentHttpServer HTTP, file/history, WebSocket snapshot/push, SSE ping/push, stop/restart")
    } finally {
        ws?.abort()
        sse?.close()
        server.stop()
        bridge.getDir().deleteRecursively()
    }
}
