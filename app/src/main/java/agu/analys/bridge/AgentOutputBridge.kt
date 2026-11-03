package agu.analys.bridge

import android.content.Context
import android.os.Environment
import agu.analys.util.AppLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    fun isEnabled(): Boolean {
        val prefsCustom = context.getSharedPreferences("krypto_analysis_prefs", Context.MODE_PRIVATE)
        if (prefsCustom.getBoolean("agent_output_enabled", false)) return true
        val prefsDefault = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefsDefault.getBoolean("agent_output_enabled", false)
    }

    fun emitSignal(signal: AgentSignalOutput) {
        if (!isEnabled()) return
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

    fun getLatest(): AgentSignalOutput? = latestRef.get()

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
