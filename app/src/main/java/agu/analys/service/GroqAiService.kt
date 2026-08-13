package agu.analys.service

import agu.analys.AppContextProvider
import agu.analys.model.AISignalState
import agu.analys.model.IndonesiaCpiData
import agu.analys.model.MarketTick
import agu.analys.model.TechnicalIndicators
import agu.analys.trading.SpotPosition
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** AI market audit via Groq. */
object GroqAiService {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "openai/gpt-oss-20b"

    suspend fun generateDeepMarketAudit(
        apiKey: String,
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        position: SpotPosition = SpotPosition()
    ): String = withContext(Dispatchers.IO) {
        val cpi = if (safeContextReady) BpsMacroService(AppContextProvider.context).getLatest() else null
        if (apiKey.isBlank()) return@withContext buildFallback(tick, indicators, signal, cpi, position) +
            "\n\n⚠️ Groq API key belum di-set. Buka Settings dan isi Groq API key."

        val prompt = buildPrompt(tick, indicators, signal, cpi, position)

        try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("temperature", 0.25)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
            }
            val request = Request.Builder().url(BASE_URL).addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext buildFallback(tick, indicators, signal, cpi, position) +
                    "\n\n⚠️ Groq API error HTTP ${resp.code}: ${responseBody.take(180)}"
                val text = JSONObject(responseBody).optJSONArray("choices")?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                if (text.isNotBlank()) text else buildFallback(tick, indicators, signal, cpi, position)
            }
        } catch (e: Exception) {
            buildFallback(tick, indicators, signal, cpi, position) + "\n\n⚠️ Gagal memanggil Groq: ${e.message}"
        }
    }

    private const val SYSTEM_PROMPT = """
Kamu tutor trading santai untuk pengguna Indodax Indonesia.
Bahasa sehari-hari, jujur, singkat (maks sekitar 12 baris).
Mulai dari kesimpulan praktis, baru alasan singkat.
Jangan menumpuk istilah teknis. Kalau sebut RSI/EMA/MACD, langsung artikan praktis dalam 1 kalimat.
Jangan mengarang data. Jangan janjikan profit.
Skor setup bukan peluang profit.
""".trimIndent()

    private fun buildPrompt(
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        cpi: IndonesiaCpiData?,
        position: SpotPosition
    ): String {
        val actionIndo = when (signal.action.name) {
            "BUY" -> "BELI"
            "SELL" -> "JUAL"
            else -> "TAHAN"
        }
        val pnlPct = position.pnlPercent(tick.price)
        val pnlIdr = position.pnlIdr(tick.price)
        val posBlock = if (position.isHolding) {
            buildString {
                appendLine("POSISI USER: SUDAH PUNYA COIN (HOLDING)")
                if (position.entryPrice > 0) appendLine("Harga beli: ${PriceFormatter.formatPrice(position.entryPrice)}")
                if (position.costIdr > 0) appendLine("Modal: ${PriceFormatter.formatPrice(position.costIdr)}")
                if (pnlPct != null) appendLine("PnL sekarang: ${PriceFormatter.formatPercentage(pnlPct)}")
                if (pnlIdr != null) appendLine("Untung/rugi kira-kira: ${PriceFormatter.formatPrice(pnlIdr)}")
                appendLine("Jelaskan dari sudut orang yang SUDAH pegang: tahan, partial, atau siap keluar. Jangan dorong beli ulang hanya karena sinyal BELI.")
            }
        } else {
            "POSISI USER: BELUM PUNYA COIN\nJelaskan dari sudut orang yang belum masuk: boleh menunggu atau mempertimbangkan masuk. Sinyal JUAL diabaikan karena tidak ada yang dijual."
        }
        val macroLine = cpi?.let {
            val yoy = if (it.yoyPercent.isFinite()) "${it.yoyPercent}%" else "-"
            "Makro RI (BPS ${it.period}): inflasi YoY $yoy — hanya konteks, bukan sinyal beli/jual."
        } ?: "Makro RI: data tidak tersedia."

        return """
Data market Indodax ${tick.symbol}:
Harga ${PriceFormatter.formatPrice(tick.price)} (${PriceFormatter.formatPercentage(tick.change24h)} 24j)
Volume ${PriceFormatter.formatVolume(tick.volume24h)}

Sinyal engine: $actionIndo · kekuatan setup ${signal.confidence}/100 (bukan % profit)
Entry engine ${PriceFormatter.formatPrice(signal.entryPrice)} | TP1 ${PriceFormatter.formatPrice(signal.targetPrice1)} | SL ${PriceFormatter.formatPrice(signal.stopLoss)}
Alasan engine: ${signal.reasoning.take(4).joinToString("; ")}

Indikator ringkas: RSI ${PriceFormatter.formatRsi(indicators.rsi14)}, MACD hist ${PriceFormatter.formatIndicatorVal(indicators.macdHist, 4)}

$posBlock

$macroLine

FORMAT JAWABAN:
1) INTI — 1–2 kalimat: apa yang masuk akal sekarang (tahan / siap beli / siap jual / diam)
2) KENAPA — 3 poin pendek bahasa sehari-hari
3) BATAS — kapan setup ini kurang relevan + ingat ini bukan jaminan
4) MAKRO — 1 kalimat saja (atau bilang netral)

Jangan ulang semua angka yang sudah di kartu. Fokus arti praktis untuk posisi user.
        """.trimIndent()
    }

    private fun buildFallback(
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        cpi: IndonesiaCpiData?,
        position: SpotPosition
    ): String {
        val action = when (signal.action.name) { "BUY" -> "BELI"; "SELL" -> "JUAL"; else -> "TAHAN" }
        val pos = if (position.isHolding) {
            val pnl = position.pnlPercent(tick.price)?.let { PriceFormatter.formatPercentage(it) } ?: "-"
            "Posisi: HOLDING · PnL $pnl"
        } else "Posisi: belum punya coin"
        return """
Inti: sinyal engine $action (${signal.confidence}/100). $pos
Harga ${PriceFormatter.formatPrice(tick.price)} (${PriceFormatter.formatPercentage(tick.change24h)} 24j).
RSI ${PriceFormatter.formatRsi(indicators.rsi14)}. Level engine: entry ${PriceFormatter.formatPrice(signal.entryPrice)}, TP1 ${PriceFormatter.formatPrice(signal.targetPrice1)}, SL ${PriceFormatter.formatPrice(signal.stopLoss)}.
Ini ringkasan lokal — isi Groq API key di Settings untuk penjelasan penuh.
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try { AppContextProvider.context; true } catch (_: UninitializedPropertyAccessException) { false }
}
