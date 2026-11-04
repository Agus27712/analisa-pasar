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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Keadaan server bridge. UI **wajib** membaca ini, bukan menebak dari nilai sakelar:
 * sakelar bisa hidup sementara server gagal menyala (port dipakai, kelas hilang, dsb.).
 */
sealed interface AgentBridgeServerState {
    data object Stopped : AgentBridgeServerState
    data object Starting : AgentBridgeServerState
    data object Running : AgentBridgeServerState
    data object Stopping : AgentBridgeServerState
    data class Failed(val reason: String) : AgentBridgeServerState
}

/**
 * Server baca-saja Agent Bridge V2 (HTTP polling + WebSocket + SSE).
 *
 * Tiga hal yang tidak boleh diubah tanpa alasan ukur:
 *
 * 1. **Server dinyalakan/dimatikan di luar main thread.** `embeddedServer(...).start()` membuka
 *    socket; mengerjakannya di main thread memicu `NetworkOnMainThreadException`/ANR saat sakelar
 *    di Pengaturan ditekan. Karena itu `start()`/`stop()` hanya menjadwalkan pekerjaan dan
 *    langsung kembali; hasilnya dilaporkan lewat [state].
 * 2. **Semua kegagalan ditangkap sebagai `Throwable`.** Kegagalan yang benar-benar pernah terjadi
 *    adalah `NoSuchMethodError` (konflik versi kotlinx-coroutines dengan mesin Ktor lama). Itu
 *    turunan `Error`, bukan `Exception`, sehingga `catch (e: Exception)` tidak menangkapnya dan
 *    aplikasi langsung tertutup saat sakelar dinyalakan. Sekarang kegagalan dilaporkan sebagai
 *    `Failed` dan sakelar dikembalikan ke posisi mati.
 * 3. **Tidak ada `java.time.Duration` di berkas ini.** `minSdk` aplikasi 24, sedangkan `java.time`
 *    baru ada di API 26; memakainya membuat sakelar ini menutup aplikasi di Android 7. Opsi
 *    WebSocket memakai `kotlin.time.Duration` dari Ktor 3.
 */
class AgentHttpServer(private val context: Context) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * Satu thread khusus untuk menyalakan/mematikan server, supaya urutan permintaan pengguna
     * tidak saling menyalip (mis. nyalakan lalu cepat matikan).
     */
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-bridge-lifecycle").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + lifecycleExecutor.asCoroutineDispatcher())

    private val _state = MutableStateFlow<AgentBridgeServerState>(AgentBridgeServerState.Stopped)
    val state: StateFlow<AgentBridgeServerState> = _state.asStateFlow()

    /** Naik setiap kali pengguna meminta berhenti; dipakai membatalkan penyalaan yang belum tuntas. */
    @Volatile
    private var generation = 0

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val bridge by lazy { AgentOutputBridge.getInstance(context) }

    @Synchronized
    fun start() {
        when (_state.value) {
            is AgentBridgeServerState.Running,
            is AgentBridgeServerState.Starting,
            is AgentBridgeServerState.Stopping -> return
            else -> Unit
        }
        val gen = ++generation
        _state.value = AgentBridgeServerState.Starting
        scope.launch { boot(gen) }
    }

    @Synchronized
    fun stop() {
        generation++
        val engine = server
        server = null
        if (engine == null) {
            _state.value = AgentBridgeServerState.Stopped
            return
        }
        _state.value = AgentBridgeServerState.Stopping
        scope.launch {
            runCatching { engine.stop(1_000, 2_000) }
            _state.value = AgentBridgeServerState.Stopped
            AppLogManager.info("AgentHttpServer", "Bridge V2 stopped")
        }
    }

    /** Menunggu keadaan akhir (Running/Failed/Stopped). Dipakai uji dan sakelar Pengaturan. */
    suspend fun awaitSettled(timeoutMillis: Long = 15_000): AgentBridgeServerState =
        withTimeoutOrNull(timeoutMillis) {
            state.first {
                it is AgentBridgeServerState.Running ||
                    it is AgentBridgeServerState.Failed ||
                    it is AgentBridgeServerState.Stopped
            }
        } ?: _state.value

    val isRunning: Boolean get() = _state.value is AgentBridgeServerState.Running

    private fun boot(gen: Int) {
        try {
            val engine = embeddedServer(CIO, port = PORT, host = HOST) { installRoutes() }
            engine.start(wait = false)
            if (gen != generation) {
                runCatching { engine.stop(0, 500) }
                return
            }
            if (!probeHealth()) {
                runCatching { engine.stop(0, 500) }
                _state.value = AgentBridgeServerState.Failed(
                    "Port $PORT tidak menjawab pemeriksaan kesehatan (kemungkinan dipakai aplikasi lain)"
                )
                return
            }
            server = engine
            _state.value = AgentBridgeServerState.Running
            AppLogManager.info("AgentHttpServer", "Bridge V2 started $HOST:$PORT (polling+ws+sse)")
        } catch (t: Throwable) {
            server = null
            val reason = "${t.javaClass.simpleName}: ${t.message ?: "tanpa pesan"}"
            _state.value = AgentBridgeServerState.Failed(reason)
            AppLogManager.error("AgentHttpServer", "Gagal menyalakan Bridge V2 — $reason", t)
        }
    }

    /**
     * Bukti nyata bahwa port melayani, bukan sekadar "objek server sudah dibuat".
     * Dijalankan di thread lifecycle (bukan main thread), jadi boleh memblokir.
     */
    private fun probeHealth(attempts: Int = 25, delayMs: Long = 120): Boolean {
        repeat(attempts) {
            val ok = runCatching {
                val conn = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 400
                conn.readTimeout = 400
                try {
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(delayMs)
        }
        return false
    }

    private fun Application.installRoutes() {
        install(WebSockets) {
            pingPeriod = PING_PERIOD
            timeout = PING_TIMEOUT
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        routing {
            get("/") {
                call.respondText(
                    """
                    {
                      "status":"ok",
                      "service":"analisa-pasar-agent-bridge-v2",
                      "polling": ["/signals/latest","/signals/history?limit=50","/portfolio","/health"],
                      "push": ["/ws/signals (WebSocket)", "/events (SSE)"]
                    }
                    """.trimIndent(),
                    ContentType.Application.Json
                )
            }
            get("/health") {
                call.respondText(
                    """{"ok":true,"ts":${System.currentTimeMillis()}}""",
                    ContentType.Application.Json
                )
            }
            // --- POLLING ENDPOINTS (fallback) ---
            get("/signals/latest") {
                val f = File(bridge.getDir(), "latest.json")
                if (f.exists()) call.respondFile(f)
                else call.respond(HttpStatusCode.NotFound, """{"error":"no signal yet"}""")
            }
            get("/signals/history") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val f = File(bridge.getDir(), "signals.jsonl")
                if (!f.exists()) {
                    call.respondText("[]", ContentType.Application.Json)
                    return@get
                }
                val arr = "[" + f.readLines().takeLast(limit).joinToString(",") + "]"
                call.respondText(arr, ContentType.Application.Json)
            }
            get("/portfolio") {
                val f = File(bridge.getDir(), "portfolio.json")
                if (f.exists()) call.respondFile(f)
                else call.respond(HttpStatusCode.NotFound, """{"error":"no portfolio"}""")
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
    }

    companion object {
        const val PORT = 8765
        const val HOST = "0.0.0.0"
        private val PING_PERIOD = 15.seconds
        private val PING_TIMEOUT = 15.seconds

        @Volatile
        private var INSTANCE: AgentHttpServer? = null

        fun getInstance(ctx: Context): AgentHttpServer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentHttpServer(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
