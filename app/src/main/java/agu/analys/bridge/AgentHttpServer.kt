package agu.analys.bridge

import android.content.Context
import agu.analys.util.AppLogManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.origin
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration

class AgentHttpServer(private val context: Context) {

    private var server: ApplicationEngine? = null
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val bridge by lazy { AgentOutputBridge.getInstance(context) }

    @Synchronized
    fun start() {
        if (server != null) return
        try {
            server = embeddedServer(CIO, port = 8765, host = "0.0.0.0") {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(15)
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                routing {
                    get("/") {
                        call.respondText("""
                        {
                          "status":"ok",
                          "service":"analisa-pasar-agent-bridge-v2",
                          "polling": ["/signals/latest","/signals/history?limit=50","/portfolio","/health"],
                          "push": ["/ws/signals (WebSocket)", "/events (SSE)"]
                        }
                        """.trimIndent(), ContentType.Application.Json)
                    }
                    get("/health") {
                        call.respondText("""{"ok":true,"ts":${System.currentTimeMillis()}}""", ContentType.Application.Json)
                    }
                    // --- POLLING ENDPOINTS (fallback) ---
                    get("/signals/latest") {
                        val f = File(bridge.getDir(), "latest.json")
                        if (f.exists()) call.respondFile(f) else call.respond(HttpStatusCode.NotFound, """{"error":"no signal yet"}""")
                    }
                    get("/signals/history") {
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                        val f = File(bridge.getDir(), "signals.jsonl")
                        if (!f.exists()) {
                            call.respondText("[]", ContentType.Application.Json); return@get
                        }
                        val arr = "[" + f.readLines().takeLast(limit).joinToString(",") + "]"
                        call.respondText(arr, ContentType.Application.Json)
                    }
                    get("/portfolio") {
                        val f = File(bridge.getDir(), "portfolio.json")
                        if (f.exists()) call.respondFile(f) else call.respond(HttpStatusCode.NotFound, """{"error":"no portfolio"}""")
                    }

                    // --- PUSH ENDPOINTS ---
                    // 1. WebSocket realtime
                    webSocket("/ws/signals") {
                        val remote = runCatching { call.request.origin.remoteHost }.getOrDefault("client")
                        AppLogManager.info("AgentWS", "Client connected: $remote")
                        try {
                            // Kirim snapshot sinyal terakhir jika ada
                            bridge.getLatest()?.let {
                                send(Frame.Text(json.encodeToString(it)))
                            }
                            // Push setiap ada sinyal baru
                            AgentEventBus.signals.collect { signal ->
                                send(Frame.Text(json.encodeToString(signal)))
                            }
                        } catch (e: Exception) {
                            AppLogManager.error("AgentWS", "WS error", e)
                        } finally {
                            AppLogManager.info("AgentWS", "Client disconnected")
                        }
                    }
                    // 2. Server-Sent Events (SSE) untuk client HTTP streaming (cukup curl -N)
                    get("/events") {
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.response.header(HttpHeaders.Connection, "keep-alive")
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            try {
                                write("event: ping\ndata: {\"ts\":${System.currentTimeMillis()}}\n\n")
                                flush()
                                AgentEventBus.signals.collect { signal ->
                                    val payload = json.encodeToString(signal)
                                    write("event: signal\n")
                                    write("data: $payload\n\n")
                                    flush()
                                }
                            } catch (e: Exception) {
                                AppLogManager.error("AgentSSE", "SSE error", e)
                            }
                        }
                    }
                }
            }.start(wait = false)
            AppLogManager.info("AgentHttpServer", "Bridge V2 started 0.0.0.0:8765 (polling+ws+sse)")
        } catch (e: Exception) {
            AppLogManager.error("AgentHttpServer", "Failed start", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop(1000, 2000)
            server = null
            AppLogManager.info("AgentHttpServer", "Bridge V2 stopped")
        } catch (e: Exception) {
            AppLogManager.error("AgentHttpServer", "Error stopping bridge server", e)
        }
    }

    val isRunning: Boolean get() = server != null

    companion object {
        @Volatile private var INSTANCE: AgentHttpServer? = null

        fun getInstance(ctx: Context): AgentHttpServer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentHttpServer(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
