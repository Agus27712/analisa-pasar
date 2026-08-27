package agu.analys.service

import agu.analys.AppContextProvider
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

/** Chart summary Gemini — insight pair, bukan dump RSI. */
object GeminiAiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val MODEL = "gemini-2.0-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    suspend fun generateChartSummary24h(
        apiKey: String,
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState
    ): String = withContext(Dispatchers.IO) {
        val effectiveKey = if (apiKey.isBlank()) "" else apiKey
        val cpi = if (safeContextReady) BpsMacroService(AppContextProvider.context).getLatest() else null
        if (effectiveKey.isBlank()) return@withContext buildFallback(tick, indicators, signal, cpi)

        val base = extractBase(tick.symbol)
        val pairCtx = PairNarrative.forBase(base)
        val move = describeMove(tick.change24h)
        val rsiHint = when {
            indicators.rsi14 < 30 -> "RSI oversold"
            indicators.rsi14 > 70 -> "RSI overbought"
            else -> "RSI netral"
        }
        val trendHint = when {
            indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 -> "struktur bullish"
            indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 -> "struktur bearish"
            else -> "sideways"
        }

        val prompt = """
Kamu asisten spot Indodax. Kasih insight singkat soal pair (bukan hafalan indikator).
Bahasa Indonesia santai, max ~100 kata. Jangan klaim berita live; pakai "kemungkinan".

Pair: ${tick.symbol} ($base)
Identitas: ${pairCtx.label}
Ekosistem: ${pairCtx.ecosystem}
Narasi: ${pairCtx.narrative}
Gerak 24j: $move (${PriceFormatter.formatPercentage(tick.change24h)})
Harga ${PriceFormatter.formatPrice(tick.price)} | Vol ${PriceFormatter.formatVolume(tick.volume24h)}
Teknikal: $trendHint, $rsiHint, engine ${signal.action.name}

Format:
1. 🔎 Apa ini: ...
2. 📈 Kenapa gerak: ...
3. 🔗 Hubungan: ...
4. 💡 Insight pantau: ...
        """.trimIndent()

        try {
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.45)
                    put("maxOutputTokens", 400)
                })
            }
            val request = Request.Builder()
                .url("$BASE_URL?key=$effectiveKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext buildFallback(tick, indicators, signal, cpi)
                val parts = JSONObject(responseBody)
                    .optJSONArray("candidates")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                val text = parts?.let { arr ->
                    (0 until arr.length()).joinToString("\n") { i ->
                        arr.getJSONObject(i).optString("text").orEmpty()
                    }
                }.orEmpty()
                if (text.isNotBlank()) text.trim() else buildFallback(tick, indicators, signal, cpi)
            }
        } catch (_: Exception) {
            buildFallback(tick, indicators, signal, cpi)
        }
    }

    private fun extractBase(symbol: String): String {
        val s = symbol.lowercase().replace("_", "")
        return when {
            s.endsWith("idr") -> s.removeSuffix("idr")
            s.endsWith("usdt") -> s.removeSuffix("usdt")
            else -> s
        }
    }

    private fun describeMove(change24h: Double): String = when {
        change24h >= 8 -> "naik tajam"
        change24h >= 3 -> "naik"
        change24h <= -8 -> "turun tajam"
        change24h <= -3 -> "turun"
        else -> "relatif flat"
    }

    private fun buildFallback(
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        cpi: IndonesiaCpiData?
    ): String {
        val base = extractBase(tick.symbol)
        val ctx = PairNarrative.forBase(base)
        val move = describeMove(tick.change24h)
        return """
🔎 Apa ini: ${ctx.label}. ${ctx.narrative}

📈 Kenapa gerak: 24j $move (${PriceFormatter.formatPercentage(tick.change24h)}), vol ${PriceFormatter.formatVolume(tick.volume24h)}. Kemungkinan ikut aliran ${ctx.ecosystem}.

🔗 Hubungan: ${ctx.ecosystem}

💡 Insight pantau: Cek dulu arah BTC. Engine ${signal.action.name} — eksekusi limit maker, jangan kejar candle.
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try {
            AppContextProvider.context; true
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
}
