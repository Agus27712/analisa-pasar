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

object GeminiAiService {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    private const val MODEL = "gemini-3.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    suspend fun generateChartSummary24h(
        apiKey: String,
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        position: SpotPosition = SpotPosition()
    ): String = withContext(Dispatchers.IO) {
        val effectiveKey = if (apiKey.isBlank()) "" else apiKey
        val cpi = if (safeContextReady) BpsMacroService(AppContextProvider.context).getLatest() else null
        if (effectiveKey.isBlank()) return@withContext buildFallback(tick, indicators, signal, cpi, position) +
            "\n\n⚠️ Gemini API Key belum di-set. Buka Settings → masukkan Gemini API key."

        val prompt = buildPrompt(tick, indicators, signal, cpi, position)
        try {
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply { put("temperature", 0.25) })
            }
            val request = Request.Builder().url("$BASE_URL?key=$effectiveKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext buildFallback(tick, indicators, signal, cpi, position) +
                    "\n\n⚠️ Gemini API Error HTTP ${resp.code}: ${responseBody.take(180)}"
                val parts = JSONObject(responseBody).optJSONArray("candidates")?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val text = parts?.let { arr ->
                    (0 until arr.length()).joinToString("\n") { i -> arr.getJSONObject(i).optString("text").orEmpty() }
                }.orEmpty()
                if (text.isNotBlank()) text else buildFallback(tick, indicators, signal, cpi, position)
            }
        } catch (e: Exception) {
            buildFallback(tick, indicators, signal, cpi, position) + "\n\n⚠️ Gagal memanggil Gemini: ${e.message}"
        }
    }

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
        val posBlock = if (position.isHolding) {
            buildString {
                appendLine("User SUDAH PUNYA ${tick.symbol}.")
                if (position.entryPrice > 0) appendLine("Harga beli: ${PriceFormatter.formatPrice(position.entryPrice)}")
                if (position.costIdr > 0) appendLine("Modal: ${PriceFormatter.formatPrice(position.costIdr)}")
                if (pnlPct != null) appendLine("PnL: ${PriceFormatter.formatPercentage(pnlPct)}")
                appendLine("Ceritakan dari sudut orang yang sudah pegang (tahan / keluar), jangan dorong beli ulang.")
            }
        } else {
            "User BELUM punya coin. Fokus: tunggu atau pertimbangkan masuk. Sinyal jual tidak relevan."
        }
        val macro = cpi?.let {
            val yoy = if (it.yoyPercent.isFinite()) "${it.yoyPercent}%" else "-"
            "Makro RI BPS ${it.period}: YoY $yoy (konteks saja)."
        } ?: "Makro RI tidak tersedia."

        return """
Kamu tutor trading santai untuk Indodax. Bahasa sehari-hari, singkat (maks ~12 baris).
Mulai dari kesimpulan praktis. Minim jargon. Jangan mengarang data. Jangan janjikan profit.

24 jam ${tick.symbol}: harga ${PriceFormatter.formatPrice(tick.price)} (${PriceFormatter.formatPercentage(tick.change24h)}), range ${PriceFormatter.formatPrice(tick.low24h)}–${PriceFormatter.formatPrice(tick.high24h)}, vol ${PriceFormatter.formatVolume(tick.volume24h)}.
Sinyal engine: $actionIndo · setup ${signal.confidence}/100.
RSI ${PriceFormatter.formatRsi(indicators.rsi14)}, MACD hist ${PriceFormatter.formatIndicatorVal(indicators.macdHist, 4)}.

$posBlock
$macro

FORMAT:
1) INTI — kondisi 24 jam + apa yang masuk akal untuk posisi user
2) KENAPA — 3 poin pendek
3) BATAS — 1–2 kalimat
4) MAKRO — 1 kalimat
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
        val pos = if (position.isHolding) "HOLDING" else "belum punya coin"
        return """
Ringkasan lokal ${tick.symbol}: ${PriceFormatter.formatPrice(tick.price)} (${PriceFormatter.formatPercentage(tick.change24h)} 24j).
Sinyal $action (${signal.confidence}/100). Posisi: $pos.
RSI ${PriceFormatter.formatRsi(indicators.rsi14)}. Isi Gemini API key di Settings untuk penjelasan penuh.
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try { AppContextProvider.context; true } catch (_: UninitializedPropertyAccessException) { false }
}
