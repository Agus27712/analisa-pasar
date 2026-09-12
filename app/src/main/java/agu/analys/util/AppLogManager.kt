package agu.analys.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import agu.analys.AppContextProvider
import agu.analys.BuildConfig
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LogCategory(val displayName: String, val emoji: String) {
    ALL("Semua Log", "📋"),
    TRAILING("Trailing Stop", "🛡️"),
    TRADE("Trading & Order", "💼"),
    MARKET("Market & Data", "📈"),
    AI_ENGINE("AI & Sinyal", "🤖"),
    SERVICE("Background Service", "⚙️"),
    ERROR("Error & Warning", "⚠️"),
    SYSTEM_LOGCAT("Android Logcat", "📜")
}

data class AppLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val priority: Int,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val category: LogCategory = LogCategory.ALL
) {
    val levelName: String get() = when (priority) {
        Log.VERBOSE -> "VERBOSE"
        Log.DEBUG -> "DEBUG"
        Log.INFO -> "INFO"
        Log.WARN -> "WARN"
        Log.ERROR -> "ERROR"
        Log.ASSERT -> "ASSERT"
        else -> "LOG"
    }

    val formattedTime: String get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

object AppLogManager {
    private const val MAX_LOGS = 1200
    private val logBuffer = ConcurrentLinkedDeque<AppLogEntry>()

    val timberTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val safeTag = tag ?: "AnalysApp"
            val category = resolveCategory(safeTag, message)
            val entry = AppLogEntry(
                timestamp = System.currentTimeMillis(),
                priority = priority,
                tag = safeTag,
                message = message,
                throwable = t,
                category = category
            )
            logBuffer.add(entry)
            while (logBuffer.size > MAX_LOGS) {
                logBuffer.pollFirst()
            }
        }
    }

    private fun resolveCategory(tag: String, message: String): LogCategory {
        val lower = "$tag $message".lowercase()
        return when {
            lower.contains("trailing") || lower.contains("peak") || lower.contains("profit-lock") || lower.contains("step-tier") -> LogCategory.TRAILING
            lower.contains("order") || lower.contains("buy") || lower.contains("sell") || lower.contains("trade") || lower.contains("wallet") -> LogCategory.TRADE
            lower.contains("gemini") || lower.contains("groq") || lower.contains("prompt") || lower.contains("signal") || lower.contains("ai") -> LogCategory.AI_ENGINE
            lower.contains("service") || lower.contains("worker") || lower.contains("job") || lower.contains("notification") -> LogCategory.SERVICE
            lower.contains("ticker") || lower.contains("candle") || lower.contains("indodax") || lower.contains("market") || lower.contains("poll") -> LogCategory.MARKET
            else -> LogCategory.ALL
        }
    }

    fun log(category: LogCategory, priority: Int, tag: String, message: String, t: Throwable? = null) {
        val entry = AppLogEntry(
            timestamp = System.currentTimeMillis(),
            priority = priority,
            tag = tag,
            message = message,
            throwable = t,
            category = category
        )
        logBuffer.add(entry)
        while (logBuffer.size > MAX_LOGS) {
            logBuffer.pollFirst()
        }
    }

    fun getLogs(category: LogCategory = LogCategory.ALL, searchQuery: String = ""): List<AppLogEntry> {
        val all = logBuffer.toList()
        return all.filter { entry ->
            val matchesCategory = when (category) {
                LogCategory.ALL -> true
                LogCategory.ERROR -> entry.priority >= Log.WARN
                LogCategory.SYSTEM_LOGCAT -> true
                else -> entry.category == category || (entry.priority >= Log.WARN && category == LogCategory.ERROR)
            }
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                entry.message.contains(searchQuery, ignoreCase = true) ||
                        entry.tag.contains(searchQuery, ignoreCase = true)
            }
            matchesCategory && matchesSearch
        }.reversed()
    }

    fun clearLogs() {
        logBuffer.clear()
    }

    suspend fun fetchNativeLogcat(lines: Int = 400): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", lines.toString()))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { result.add(it) }
            }
            reader.close()
        } catch (e: Exception) {
            result.add("Gagal membaca logcat sistem: ${e.localizedMessage}")
        }
        result
    }

    fun buildDiagnosticStateDump(context: Context): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        val nowStr = sdf.format(Date())

        sb.appendLine("=======================================================")
        sb.appendLine("📊 DIAGNOSTIK STATUS APLIKASI LENGKAP & LOGCAT EXPORT")
        sb.appendLine("=======================================================")
        sb.appendLine("Waktu Ekspor : $nowStr")
        sb.appendLine("Versi Aplikasi: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Package ID    : ${context.packageName}")
        sb.appendLine("Foreground App: ${AppContextProvider.isAppInForeground}")
        sb.appendLine()

        val posStore = SpotPositionStore(context)
        val allSymbols = posStore.getAllStoredSymbols()
        val allPositions = posStore.getAllPositions()
        val activeTrailing = posStore.getAllActiveTrailingSymbols()

        sb.appendLine("-------------------------------------------------------")
        sb.appendLine("🛡️ STATUS TRAILING STOP & SPOT POSITIONS (${allPositions.size} Koin Terdata)")
        sb.appendLine("-------------------------------------------------------")
        sb.appendLine("Koin dengan Trailing Aktif: ${if (activeTrailing.isEmpty()) "TIDAK ADA" else activeTrailing.joinToString(", ")}")
        sb.appendLine()

        if (allPositions.isEmpty()) {
            sb.appendLine("Belum ada posisi atau data koin yang tersimpan di SpotPositionStore.")
        } else {
            allPositions.forEach { (sym, pos) ->
                sb.appendLine("▶ KOIN: $sym")
                sb.appendLine("   - Status Holding      : ${pos.state} (isHolding=${pos.isHolding})")
                sb.appendLine("   - Tipe Akun           : ${if (pos.isReal) "AKUN RIIL (INDODAX)" else "SIMULASI"}")
                sb.appendLine("   - Modal Terinvestasi  : Rp ${String.format(Locale.US, "%,.2f", pos.investedAmount)}")
                sb.appendLine("   - Harga Beli (Entry)  : Rp ${String.format(Locale.US, "%,.4f", pos.entryPrice)}")
                sb.appendLine("   - Kuantitas Aset      : ${pos.quantity}")
                sb.appendLine("   - Harga Tertinggi(Peak): Rp ${String.format(Locale.US, "%,.4f", pos.peakPrice)}")
                sb.appendLine("   - Trailing Enabled    : ${pos.isTrailingEnabled}")
                sb.appendLine("   - Base Trailing Pct   : ${pos.trailingPercent}%")
                sb.appendLine("   - Smart Tier Enabled  : ${pos.isTieredTrailingEnabled}")
                sb.appendLine("   - Active Trailing Pct : ${pos.activeTrailingPercent}%")
                sb.appendLine("   - Trailing Stop Price : Rp ${String.format(Locale.US, "%,.4f", pos.trailingStopPrice)}")
                sb.appendLine("   - Trailing Triggered  : ${pos.isTrailingTriggered}")
                sb.appendLine("   - Auto-Sell Target TP : TP1=Rp ${pos.tp1Price} (${pos.tp1Percent}%), TP2=Rp ${pos.tp2Price} (${pos.tp2Percent}%)")
                sb.appendLine("   - Status Trigger TP   : TP1=${pos.isTp1Triggered}, TP2=${pos.isTp2Triggered}")
                sb.appendLine("   - Last Order ID       : ${pos.lastTrailingOrderId ?: "NONE"}")
                sb.appendLine("   - Config Tier JSON    : ${pos.tieredConfigJson.ifBlank { "DEFAULT_TIERS" }}")
                sb.appendLine()
            }
        }

        sb.appendLine("-------------------------------------------------------")
        sb.appendLine("🗄️ RAW DATA PREFERENCES SPOT POSITION STORE (${posStore.dumpRawPrefs().size} Keys)")
        sb.appendLine("-------------------------------------------------------")
        posStore.dumpRawPrefs().toSortedMap().forEach { (k, v) ->
            sb.appendLine("$k = $v")
        }
        sb.appendLine()

        sb.appendLine("-------------------------------------------------------")
        sb.appendLine("📜 LOGCAT IN-MEMORY APPLICATION (${logBuffer.size} Baris Log)")
        sb.appendLine("-------------------------------------------------------")
        logBuffer.forEach { entry ->
            val exc = if (entry.throwable != null) " | Exception: ${Log.getStackTraceString(entry.throwable)}" else ""
            sb.appendLine("[${entry.formattedTime}] [${entry.levelName}] [${entry.category.name}] [${entry.tag}] ${entry.message}$exc")
        }

        return sb.toString()
    }

    fun copyToClipboard(context: Context, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Analys Diagnostic Log", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun exportAndShareLog(context: Context, content: String) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "diagnostik_log_$timeStamp.txt"
            val cacheDir = context.cacheDir
            val file = File(cacheDir, fileName)
            file.writeText(content)

            val uri: Uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                Uri.fromFile(file)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Export Logcat & Diagnostik Analys App - $timeStamp")
                putExtra(Intent.EXTRA_TEXT, content.take(3000) + "\n\n(Selengkapnya terlampir di file log)")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Bagikan Logcat & Diagnostik")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Fallback to text intent if file share fails
            val textIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Diagnostik Analys App")
                putExtra(Intent.EXTRA_TEXT, content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(textIntent, "Bagikan Logcat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
