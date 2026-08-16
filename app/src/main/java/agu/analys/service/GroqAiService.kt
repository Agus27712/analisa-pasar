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

/** AI market audit via Groq. The model is OpenAI GPT-OSS hosted by Groq. */
object GroqAiService {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "openai/gpt-oss-20b"

    suspend fun generateDeepMarketAudit(apiKey: String, tick: MarketTick, indicators: TechnicalIndicators, signal: AISignalState): String = withContext(Dispatchers.IO) {
        val cpi = if (safeContextReady) BpsMacroService(AppContextProvider.context).getLatest() else null
        if (apiKey.isBlank()) return@withContext buildFallback(tick, indicators, signal, cpi) + "\n\n⚠️ Groq API key belum di-set. Buka Settings dan isi Groq API key."
        val macroBlock = macroText(cpi)
        val prompt = """
Kamu adalah tutor analisis teknikal kripto untuk trader Indonesia yang menggunakan INDODAX IDR.

ATURAN DATA:
- Gunakan hanya data market dan makro yang diberikan.
- Bedakan fakta dari interpretasi.
- CPI Indonesia hanya konteks makro, BUKAN pemicu BUY/SELL otomatis dan tidak menambah skor engine.
- Jangan mengarang funding rate, open interest, liquidation, berita, geopolitik, data AS, atau data lain yang tidak tersedia.
- Jangan menjanjikan profit.

DATA MARKET INDODAX/IDR:
Pair: ${tick.symbol}
Harga: ${PriceFormatter.formatPrice(tick.price)}
Perubahan 24 jam: ${PriceFormatter.formatPercentage(tick.change24h)}
High 24 jam: ${PriceFormatter.formatPrice(tick.high24h)}
Low 24 jam: ${PriceFormatter.formatPrice(tick.low24h)}
Volume 24 jam: ${PriceFormatter.formatVolume(tick.volume24h)}

INDIKATOR:
RSI14: ${PriceFormatter.formatRsi(indicators.rsi14)}
MACD Histogram: ${PriceFormatter.formatIndicatorVal(indicators.macdHist, 4)}
EMA20: ${PriceFormatter.formatPrice(indicators.ema20)}
EMA50: ${PriceFormatter.formatPrice(indicators.ema50)}
EMA200: ${PriceFormatter.formatPrice(indicators.ema200)}
ATR: ${PriceFormatter.formatIndicatorVal(indicators.atr, 4)}
Momentum: ${PriceFormatter.formatIndicatorVal(indicators.momentum, 4)}

SINYAL ENGINE:
Aksi: ${signal.action.name}
Confidence: ${signal.confidence}/100, bukan probabilitas profit
Entry: ${PriceFormatter.formatPrice(signal.entryPrice)}
TP1: ${PriceFormatter.formatPrice(signal.targetPrice1)}
TP2: ${PriceFormatter.formatPrice(signal.targetPrice2)}
SL: ${PriceFormatter.formatPrice(signal.stopLoss)}
RR: ${signal.riskRewardRatio}
Alasan: ${signal.reasoning.joinToString("; ")}

$macroBlock

FORMAT JAWABAN:
1. KONDISI MARKET
2. MAKRO INDONESIA
3. SKENARIO BULLISH/BEARISH/SIDEWAYS + pemicu dan invalidasi
4. RISK MANAGEMENT dari level engine
5. KESIMPULAN: apakah makro memperkuat, netral, atau menambah risiko terhadap setup teknikal
6. DATA YANG TIDAK TERSEDIA

Bahasa Indonesia, ringkas, edukatif, tanpa jargon yang tidak dijelaskan.
        """.trimIndent()

        try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("temperature", 0.2)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", "Kamu analis teknikal yang jujur dan data-grounded. Jangan mengarang data.") })
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
            }
            val request = Request.Builder().url(BASE_URL).addHeader("Authorization", "Bearer $apiKey").addHeader("Content-Type", "application/json").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext buildFallback(tick, indicators, signal, cpi) + "\n\n⚠️ Groq API error HTTP ${resp.code}: ${responseBody.take(180)}"
                val text = JSONObject(responseBody).optJSONArray("choices")?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                if (text.isNotBlank()) text else buildFallback(tick, indicators, signal, cpi)
            }
        } catch (e: Exception) {
            buildFallback(tick, indicators, signal, cpi) + "\n\n⚠️ Gagal memanggil Groq: ${e.message}"
        }
    }

    private fun macroText(cpi: IndonesiaCpiData?): String = cpi?.let {
        """DATA MAKRO INDONESIA TERVALIDASI — SUMBER BPS
Periode: ${it.period}
CPI/IHK Index: ${it.cpiIndex?.toString() ?: "tidak tersedia"}
Inflasi IHK YoY: ${if (it.yoyPercent.isFinite()) "${it.yoyPercent}%" else "tidak tersedia"}
Target inflasi: ${it.inflationTargetCenterPercent}% ± ${it.inflationTargetBandPercent}%
Sumber: ${it.source}
Status cache: ${if (System.currentTimeMillis() - it.fetchedAt <= 24L * 60L * 60L * 1000L) "fresh" else "cached/stale"}
JANGAN mengisi nilai yang tidak tersedia.""".trimIndent()
    } ?: "DATA MAKRO INDONESIA: tidak tersedia. Jangan menebak CPI/IHK."

    private fun buildFallback(tick: MarketTick, indicators: TechnicalIndicators, signal: AISignalState, cpi: IndonesiaCpiData?): String {
        val rsiText = when { indicators.rsi14 < 30 -> "oversold (${PriceFormatter.formatRsi(indicators.rsi14)})"; indicators.rsi14 > 70 -> "overbought (${PriceFormatter.formatRsi(indicators.rsi14)})"; else -> "netral (${PriceFormatter.formatRsi(indicators.rsi14)})" }
        val action = when (signal.action.name) { "BUY" -> "BELI"; "SELL" -> "JUAL"; else -> "TAHAN" }
        val macro = cpi?.let { "• BPS CPI/IHK: ${it.cpiIndex?.toString() ?: "-"} | inflasi YoY: ${if (it.yoyPercent.isFinite()) "${it.yoyPercent}%" else "-"} | ${it.period}" } ?: "• BPS CPI/IHK: tidak tersedia."
        return """
📊 LAPORAN AUDIT PASAR (${tick.symbol}) — Fallback

• Harga: ${PriceFormatter.formatPrice(tick.price)} (${PriceFormatter.formatPercentage(tick.change24h)} 24j)
• RSI: $rsiText | MACD hist: ${PriceFormatter.formatIndicatorVal(indicators.macdHist, 4)}
• Sinyal engine: $action (${signal.confidence}/100)
$macro
• Entry ${PriceFormatter.formatPrice(signal.entryPrice)} | TP1 ${PriceFormatter.formatPrice(signal.targetPrice1)} | SL ${PriceFormatter.formatPrice(signal.stopLoss)} | RR ${signal.riskRewardRatio}

Data funding, liquidation, open interest, berita, dan data makro lain tidak tersedia.
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try { AppContextProvider.context; true } catch (_: UninitializedPropertyAccessException) { false }
}
