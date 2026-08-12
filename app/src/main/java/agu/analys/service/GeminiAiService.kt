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
        if (effectiveKey.isBlank()) return@withContext buildFallback(tick, indicators, signal, cpi) + "\n\n⚠️ Gemini API Key belum di-set. Buka Settings → masukkan Gemini API key Anda untuk analisis AI penuh."
        val macro = cpi?.let {
            """DATA MAKRO INDONESIA TERVALIDASI — BPS
Periode: ${it.period}
CPI/IHK Index: ${it.cpiIndex?.toString() ?: "tidak tersedia"}
Inflasi IHK YoY: ${if (it.yoyPercent.isFinite()) "${it.yoyPercent}%" else "tidak tersedia"}
Target inflasi: ${it.inflationTargetCenterPercent}% ± ${it.inflationTargetBandPercent}%
Sumber: ${it.source}
Jangan mengarang field yang tidak tersedia.""".trimIndent()
        } ?: "DATA MAKRO INDONESIA: tidak tersedia. Jangan mengarang CPI/IHK."
        val prompt = """
Bertindak sebagai Senior Crypto Quant & Technical Analyst untuk trader Indonesia.
SUMBER MARKET: seluruh data market berasal dari INDODAX IDR.

ATURAN KERAS:
- Gunakan hanya data yang diberikan.
- Bedakan fakta dan interpretasi.
- CPI Indonesia adalah konteks makro saja, BUKAN pemicu BUY/SELL otomatis dan bukan tambahan skor engine.
- Jangan mengarang funding rate, open interest, liquidation, berita, geopolitik, minyak, USD, atau data lain yang tidak tersedia.
- Jangan menjanjikan profit.

MARKET 24 JAM:
Pair: ${tick.symbol}
Harga: ${PriceFormatter.formatPrice(tick.price)}
Perubahan 24j: ${PriceFormatter.formatPercentage(tick.change24h)}
High 24j: ${PriceFormatter.formatPrice(tick.high24h)}
Low 24j: ${PriceFormatter.formatPrice(tick.low24h)}
Volume 24j: ${PriceFormatter.formatVolume(tick.volume24h)}

INDIKATOR:
RSI14: ${PriceFormatter.formatRsi(indicators.rsi14)}
MACD Histogram: ${PriceFormatter.formatIndicatorVal(indicators.macdHist, 4)}
EMA20: ${PriceFormatter.formatPrice(indicators.ema20)}
EMA50: ${PriceFormatter.formatPrice(indicators.ema50)}
EMA200: ${PriceFormatter.formatPrice(indicators.ema200)}
ATR: ${PriceFormatter.formatIndicatorVal(indicators.atr, 4)}

SINYAL ENGINE:
Aksi: ${signal.action.name}
Kekuatan setup: ${signal.confidence}/100, bukan probabilitas profit
Sentimen: ${signal.sentiment.displayName}
Entry: ${PriceFormatter.formatPrice(signal.entryPrice)}
TP1: ${PriceFormatter.formatPrice(signal.targetPrice1)}
TP2: ${PriceFormatter.formatPrice(signal.targetPrice2)}
SL: ${PriceFormatter.formatPrice(signal.stopLoss)}
RR: ${signal.riskRewardRatio}
Alasan Engine: ${signal.reasoning.joinToString("; ")}

$macro

FORMAT:
1. PENJELASAN CHART: fakta harga, volume, RSI, MACD, EMA.
2. MAKRO INDONESIA: jelaskan CPI/IHK hanya dari data BPS yang tersedia; bandingkan dengan koridor target jika YoY tersedia.
3. SKENARIO: bullish/bearish/sideways, dengan pemicu dan invalidasi.
4. RISK MANAGEMENT: audit level engine, jangan membuat level baru tanpa dasar.
5. VERDIK: apakah makro memperkuat, netral, atau menambah risiko terhadap setup teknikal, dan kenapa.
6. DATA YANG TIDAK TERSEDIA: singkat.

Bahasa Indonesia, profesional, tegas, edukatif. Jangan menyebut harga USD/USDT.
        """.trimIndent()
        try {
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply { put(JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) }) }) })
                put("generationConfig", JSONObject().apply { put("temperature", 0.2) })
            }
            val request = Request.Builder().url("$BASE_URL?key=$effectiveKey").addHeader("Content-Type", "application/json").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext buildFallback(tick, indicators, signal, cpi) + "\n\n⚠️ Gemini API Error HTTP ${resp.code}: ${responseBody.take(180)}"
                val parts = JSONObject(responseBody).optJSONArray("candidates")?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val text = parts?.let { arr -> (0 until arr.length()).joinToString("\n") { i -> arr.getJSONObject(i).optString("text").orEmpty() } }.orEmpty()
                if (text.isNotBlank()) text else buildFallback(tick, indicators, signal, cpi)
            }
        } catch (e: Exception) { buildFallback(tick, indicators, signal, cpi) + "\n\n⚠️ Gagal memanggil Gemini: ${e.message}" }
    }

    private fun buildFallback(tick: MarketTick, indicators: TechnicalIndicators, signal: AISignalState, cpi: IndonesiaCpiData?): String {
        val rsi = when { indicators.rsi14 < 30 -> "Oversold"; indicators.rsi14 > 70 -> "Overbought"; else -> "Netral" }
        val action = when (signal.action.name) { "BUY" -> "LAYAK BELI (BUY)"; "SELL" -> "LAYAK JUAL (SELL)"; else -> "TAHAN (HOLD) / WAIT & SEE" }
        val macro = cpi?.let { "• BPS CPI/IHK: ${it.cpiIndex?.toString() ?: "-"} | inflasi YoY: ${if (it.yoyPercent.isFinite()) "${it.yoyPercent}%" else "-"} | ${it.period}" } ?: "• BPS CPI/IHK: tidak tersedia."
        return """
✨ GEMINI 24H CHART SUMMARY (${tick.symbol}) — Fallback
• Harga: ${PriceFormatter.formatPrice(tick.price)} (${PriceFormatter.formatPercentage(tick.change24h)} 24j)
• Range: ${PriceFormatter.formatPrice(tick.low24h)} - ${PriceFormatter.formatPrice(tick.high24h)}
• Volume: ${PriceFormatter.formatVolume(tick.volume24h)}
• RSI: $rsi (${PriceFormatter.formatRsi(indicators.rsi14)}) | MACD Hist: ${PriceFormatter.formatIndicatorVal(indicators.macdHist, 4)}
• Sinyal: $action (${signal.confidence}/100)
$macro
• Entry: ${PriceFormatter.formatPrice(signal.entryPrice)} | TP1: ${PriceFormatter.formatPrice(signal.targetPrice1)} | TP2: ${PriceFormatter.formatPrice(signal.targetPrice2)} | SL: ${PriceFormatter.formatPrice(signal.stopLoss)} | RR: ${signal.riskRewardRatio}

Funding, liquidation, open interest, berita, dan data makro lain tidak tersedia.
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try { AppContextProvider.context; true } catch (_: UninitializedPropertyAccessException) { false }
}
