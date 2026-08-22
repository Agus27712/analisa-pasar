package agu.analys.service

import agu.analys.AppContextProvider
import agu.analys.BuildConfig
import agu.analys.model.AISignalState
import agu.analys.model.IndonesiaCpiData
import agu.analys.model.MarketTick
import agu.analys.model.TechnicalIndicators
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

    suspend fun generateChartSummary24h(apiKey: String, tick: MarketTick, indicators: TechnicalIndicators, signal: AISignalState): String = withContext(Dispatchers.IO) {
        val effectiveKey = if (apiKey.isBlank()) "" else apiKey
        val cpi = if (safeContextReady) BpsMacroService(AppContextProvider.context).getLatest() else null
        if (effectiveKey.isBlank()) return@withContext buildFallback(tick, indicators, signal, cpi)
        val macro = cpi?.let {
            "BPS CPI: ${it.cpiIndex ?: "-"} | Inflasi YoY: ${if (it.yoyPercent.isFinite()) "${it.yoyPercent}%" else "-"}"
        } ?: "Makro BPS: Normal"
        val prompt = """
Kamu asisten analisa spot crypto Indodax IDR. Berikan ringkasan SANGAT PADAT & RINGKAS (maksimal 4 poin singkat):
Pair: ${tick.symbol}
Harga: ${PriceFormatter.formatPrice(tick.price)} (${PriceFormatter.formatPercentage(tick.change24h)} 24j)
Range: ${PriceFormatter.formatPrice(tick.low24h)} - ${PriceFormatter.formatPrice(tick.high24h)}
Volume: ${PriceFormatter.formatVolume(tick.volume24h)}
RSI 14: ${PriceFormatter.formatRsi(indicators.rsi14)} | MACD Hist: ${PriceFormatter.formatIndicatorVal(indicators.macdHist, 4)}
EMA 20/50: ${PriceFormatter.formatPrice(indicators.ema20)} / ${PriceFormatter.formatPrice(indicators.ema50)}
Sinyal Engine: ${signal.action.name} (${signal.confidence}/100)
Level: Entry ${PriceFormatter.formatPrice(signal.entryPrice)} | TP1 ${PriceFormatter.formatPrice(signal.targetPrice1)} | SL ${PriceFormatter.formatPrice(signal.stopLoss)} | RR ${signal.riskRewardRatio}
$macro

Format respon:
1. 🎯 Sinyal & Tren: [Aksi & tren saat ini]
2. 📊 Kondisi Indikator: [RSI, MACD, Volume]
3. 🛡️ Level Kunci: [Support/Resistance/SL]
4. 💡 Saran Eksekusi: [Saran eksekusi spot aman & fee maker 0.21%]
        """.trimIndent()
        try {
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply { put(JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) }) }) })
                put("generationConfig", JSONObject().apply { put("temperature", 0.2) })
            }
            val request = Request.Builder().url("$BASE_URL?key=$effectiveKey").addHeader("Content-Type", "application/json").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext buildFallback(tick, indicators, signal, cpi)
                val parts = JSONObject(responseBody).optJSONArray("candidates")?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val text = parts?.let { arr -> (0 until arr.length()).joinToString("\n") { i -> arr.getJSONObject(i).optString("text").orEmpty() } }.orEmpty()
                if (text.isNotBlank()) text.trim() else buildFallback(tick, indicators, signal, cpi)
            }
        } catch (e: Exception) {
            buildFallback(tick, indicators, signal, cpi)
        }
    }

    private fun buildFallback(tick: MarketTick, indicators: TechnicalIndicators, signal: AISignalState, cpi: IndonesiaCpiData?): String {
        val rsiStatus = when {
            indicators.rsi14 < 30 -> "Oversold (potensi pantulan)"
            indicators.rsi14 > 70 -> "Overbought (waspada koreksi)"
            else -> "Netral (${PriceFormatter.formatRsi(indicators.rsi14)})"
        }
        val trendStatus = when {
            indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 -> "Bullish Uptrend"
            indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 -> "Bearish Downtrend"
            else -> "Konsolidasi / Sideways"
        }
        val actionText = when (signal.action.name) {
            "BUY" -> "BELI (Setup Terkonfirmasi)"
            "SELL" -> "JUAL / AMBIL PROFIT"
            else -> "WAIT & SEE (Tunggu Momentum)"
        }
        val macroText = cpi?.let { "• BPS CPI: ${it.cpiIndex ?: "-"} | Inflasi YoY ${if (it.yoyPercent.isFinite()) "${it.yoyPercent}%" else "-"}" } ?: "• Makro BPS: Normal"

        return """
📌 RINGKASAN TEKNIKAL (${tick.symbol})

1. 🎯 Sinyal & Tren:
• Sinyal: $actionText (${signal.confidence}/100)
• Tren: $trendStatus

2. 📊 Indikator Kunci:
• RSI (14): $rsiStatus
• Volume 24j: ${PriceFormatter.formatVolume(tick.volume24h)}
• MACD Hist: ${PriceFormatter.formatIndicatorVal(indicators.macdHist, 4)}

3. 🛡️ Level Kritis (Plan):
• Entry Acuan: ${PriceFormatter.formatPrice(signal.entryPrice)}
• Target (TP1): ${PriceFormatter.formatPrice(signal.targetPrice1)}
• Batas Risiko (SL): ${PriceFormatter.formatPrice(signal.stopLoss)} (RR: ${signal.riskRewardRatio})

4. 💡 Saran Eksekusi:
• Gunakan limit order pasang antrean (Maker 0.21%) dan perhatikan money management.
$macroText
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try { AppContextProvider.context; true } catch (_: UninitializedPropertyAccessException) { false }
}
