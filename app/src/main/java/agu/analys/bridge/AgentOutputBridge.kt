package agu.analys.bridge

import android.content.Context
import android.os.Environment
import agu.analys.util.AppLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class AgentOutputBridge(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jsonPretty = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonCompact = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val outputDir: File by lazy {
        try {
            val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val target = File(docDir, "analisa-pasar/output")
            if (target.exists() || target.mkdirs()) {
                target
            } else {
                val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "output")
                fallback.apply { mkdirs() }
            }
        } catch (_: Exception) {
            val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "output")
            fallback.apply { mkdirs() }
        }
    }

    private val latestRef = AtomicReference<AgentSignalOutput?>()
    private val prefs by lazy { agu.analys.util.AppPreferences(context.applicationContext) }

    /** Penjaga agar loop pendorong portofolio tidak berlipat saat server dinyalakan ulang. */
    private val portfolioPushStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Penjaga agar loop pendorong pemindaian pasar tidak berlipat saat server dinyalakan ulang. */
    private val marketScanPushStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val forceMarketScanTrigger = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED
    )

	fun isEnabled(): Boolean {
		return runCatching {
			prefs.isAgentOutputEnabled
		}.getOrDefault(false)
	}

    fun emitSignal(signal: AgentSignalOutput) {
        if (!isEnabled()) {
            AppLogManager.info(
                "AgentBridge",
                "SKIP emit (agent_output_enabled_false) ${signal.symbol}"
            )
            return
        }
        try {
            latestRef.set(signal)

            // 1. Simpan ke berkas lokal (signals.jsonl + latest.json) untuk fallback polling HTTP
            scope.launch {
                runCatching {
                    val line = jsonCompact.encodeToString(signal)
                    File(outputDir, "signals.jsonl").appendText(line + "\n")
                    File(outputDir, "latest.json").writeText(jsonPretty.encodeToString(signal))
                    maintainRolling(1000)
                }.onFailure { err ->
                    AppLogManager.error("AgentBridge", "Gagal simpan sinyal ke berkas lokal", err)
                }
            }

            // 2. Pancarkan realtime ke EventBus untuk WebSocket & SSE push
            scope.launch {
                AgentEventBus.emitSignal(signal)
            }

            AppLogManager.info("AgentBridge", "EMIT ${signal.symbol} ${signal.confidence}% -> file+ws")
        } catch (e: Exception) {
            AppLogManager.error("AgentBridge", "emitSignal failed", e)
        }
    }

    fun emitPortfolio(portfolio: AgentPortfolioOutput) {
        if (!isEnabled()) return
        try {
            scope.launch {
                runCatching {
                    File(outputDir, "portfolio.json").writeText(jsonPretty.encodeToString(portfolio))
                }.onFailure { err ->
                    AppLogManager.error("AgentBridge", "Gagal simpan portofolio ke berkas lokal", err)
                }
                AgentEventBus.emitPortfolio(portfolio)
            }
            AppLogManager.info("AgentBridge", "EMIT portfolio (${portfolio.exchange}) -> file+ws")
        } catch (e: Exception) {
            AppLogManager.error("AgentBridge", "emitPortfolio failed", e)
        }
    }

    /**
     * Pancarkan snapshot pemindaian seluruh pair bursa aktif ke file lokal dan agenfeed (WS + SSE).
     */
    fun emitMarketScan(scan: AgentMarketScanOutput) {
        if (!isEnabled()) return
        try {
            scope.launch {
                runCatching {
                    File(outputDir, "market_scan.json").writeText(jsonPretty.encodeToString(scan))
                }.onFailure { err ->
                    AppLogManager.error("AgentBridge", "Gagal simpan market_scan ke berkas lokal", err)
                }
                AgentEventBus.emitMarketScan(scan)
            }
            AppLogManager.info(
                "AgentBridge",
                "EMIT market_scan (${scan.exchange}, ${scan.totalPairsScanned} pairs, ${scan.bullishCount} bullish) -> file+ws"
            )
        } catch (e: Exception) {
            AppLogManager.error("AgentBridge", "emitMarketScan failed", e)
        }
    }

    /**
     * Pancarkan pembaruan pilihan 1 pair agen ke SSE/berkas lokal.
     */
    fun emitAgentSelection(selection: AgentPairSelection) {
        try {
            scope.launch {
                runCatching {
                    val file = File(outputDir, "agent_selection.json")
                    if (selection.cleared) {
                        file.delete()
                    } else {
                        file.writeText(jsonPretty.encodeToString(selection))
                    }
                }.onFailure { err ->
                    AppLogManager.error("AgentBridge", "Gagal simpan agent_selection ke berkas lokal", err)
                }
                AgentEventBus.emitAgentSelection(selection)
            }
        } catch (e: Exception) {
            AppLogManager.error("AgentBridge", "emitAgentSelection failed", e)
        }
    }

    /**
     * Dipanggil saat pengguna mengganti bursa aktif di aplikasi: bersihkan cache bursa lama
     * dan picu pemindaian ulang khusus bursa baru agar agenfeed tidak mencampur data bursa.
     */
    fun onActiveExchangeChanged(newExchange: String) {
        val normEx = newExchange.trim().uppercase()
        AgentMarketScanProvider.onExchangeChanged(normEx)
        scope.launch {
            runCatching {
                File(outputDir, "market_scan.json").delete()
                File(outputDir, "agent_selection.json").delete()
            }
            AgentEventBus.emitMarketScan(
                AgentMarketScanOutput(
                    ts = System.currentTimeMillis(),
                    available = false,
                    exchange = normEx,
                    error = "Bursa aktif dialihkan ke $normEx, sedang memindai ulang..."
                )
            )
            AgentEventBus.emitAgentSelection(
                AgentPairSelection(
                    symbol = "",
                    exchange = normEx,
                    reason = null,
                    selectedAt = System.currentTimeMillis(),
                    cleared = true
                )
            )
        }
        forceMarketScanTrigger.trySend(Unit)
    }

    fun getLatest(): AgentSignalOutput? = latestRef.get()

    /**
     * Mulai pendorong snapshot portofolio (2026-11-06, idempoten — hanya loop pertama yang
     * jalan). Dipanggil saat server bridge menyala; loop berhenti sendiri bila sakelar
     * mati karena setiap putaran memeriksa [isEnabled].
     *
     * Kenapa tiap [AgentPortfolioPusher.EVAL_INTERVAL_MS] dan bukan tiap tick harga:
     * snapshot dibangun dari cache posisi/harga yang sudah diperbarui di tempat lain;
     * push tiap detik hanya membanjiri agen dengan angka yang hampir sama. Perubahan isi
     * (atau heartbeat 5 menit) sudah cukup.
     */
    fun startPortfolioPush() {
        if (!portfolioPushStarted.compareAndSet(false, true)) return
        scope.launch {
            var previousSignature: String? = null
            var lastEmitMs = 0L
            while (isActive) {
                runCatching {
                    if (isEnabled()) {
                        val snapshot = AgentTradingCommandHandler.buildPortfolioSnapshot(context)
                        if (snapshot != null) {
                            val signature = AgentPortfolioPusher.signature(snapshot)
                            val now = System.currentTimeMillis()
                            if (AgentPortfolioPusher.shouldEmit(previousSignature, signature, now, lastEmitMs)) {
                                emitPortfolio(snapshot)
                                previousSignature = signature
                                lastEmitMs = now
                            }
                        }
                    }
                }.onFailure { err ->
                    AppLogManager.error("AgentBridge", "Gagal membangun snapshot portofolio", err)
                }
                delay(AgentPortfolioPusher.EVAL_INTERVAL_MS)
            }
        }
        AppLogManager.info("AgentBridge", "Portofolio push dimulai (interval ${AgentPortfolioPusher.EVAL_INTERVAL_MS} ms)")
    }

    /**
     * Mulai pendorong pemindaian seluruh pair ke agenfeed (idempoten, khusus bursa aktif).
     */
    fun startMarketScanPush() {
        if (!marketScanPushStarted.compareAndSet(false, true)) return
        scope.launch {
            var previousSignature: String? = null
            var lastEmittedExchange: String? = null
            var lastEmitMs = 0L
            while (isActive) {
                runCatching {
                    if (isEnabled()) {
                        val activeExAtStart = prefs.marketDataSource.name
                        val scan = AgentMarketScanProvider.scanActiveExchange(
                            context = context,
                            forceRefresh = true
                        )
                        val activeExNow = prefs.marketDataSource.name
                        if (scan.available && scan.exchange == activeExAtStart && activeExNow == activeExAtStart) {
                            if (lastEmittedExchange != scan.exchange) {
                                previousSignature = null
                            }
                            val sig = AgentMarketScannerPusher.signature(scan)
                            val now = System.currentTimeMillis()
                            if (AgentMarketScannerPusher.shouldEmit(previousSignature, sig, now, lastEmitMs)) {
                                emitMarketScan(scan)
                                previousSignature = sig
                                lastEmittedExchange = scan.exchange
                                lastEmitMs = now
                            }
                        }
                    }
                }.onFailure { err ->
                    AppLogManager.error("AgentBridge", "Gagal memindai seluruh pair untuk agenfeed", err)
                }
                kotlinx.coroutines.withTimeoutOrNull(AgentMarketScannerPusher.SCAN_INTERVAL_MS) {
                    forceMarketScanTrigger.receive()
                }
            }
        }
        AppLogManager.info("AgentBridge", "Market scan push dimulai (interval ${AgentMarketScannerPusher.SCAN_INTERVAL_MS} ms)")
    }

    fun getDir(): File = outputDir

    private fun maintainRolling(max: Int) {
        try {
            val f = File(outputDir, "signals.jsonl")
            if (!f.exists()) return
            val lines = f.readLines()
            if (lines.size > max) {
                f.writeText(lines.takeLast(max).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile private var INSTANCE: AgentOutputBridge? = null

        fun getInstance(ctx: Context): AgentOutputBridge =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentOutputBridge(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
