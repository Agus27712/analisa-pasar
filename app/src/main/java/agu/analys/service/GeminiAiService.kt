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
Kamu adalah asisten analisa crypto untuk trader Indonesia, terutama trader pemula.
Tugasmu membantu pengguna memahami kondisi pasar dari data yang tersedia. Jangan membuat pengguna merasa harus menjadi ahli teknikal untuk memahami jawabanmu.

SUMBER MARKET: semua data harga dan market yang diberikan berasal dari INDODAX IDR.

ATURAN UTAMA:
- Gunakan hanya data yang diberikan. Jangan mengarang data.
- Bedakan fakta dari pendapat atau kesimpulan.
- Jelaskan istilah teknikal dengan bahasa sehari-hari. Jika harus memakai istilah seperti RSI, MACD, EMA, support, resistance, entry, TP, SL, atau RR, langsung jelaskan artinya secara singkat.
- Jangan memakai kalimat rumit, jargon berlebihan, atau bahasa yang terdengar seperti laporan institusi.
- Jangan membuat prediksi seolah-olah pasti benar.
- Jangan menjanjikan profit.
- CPI Indonesia hanya sebagai konteks tambahan. CPI tidak boleh menjadi alasan otomatis untuk BUY atau SELL dan tidak menambah skor engine.
- Jangan mengarang funding rate, open interest, liquidation, berita, geopolitik, minyak, USD, atau data lain yang tidak tersedia.
- Jangan membuat level harga baru jika tidak ada dasar dari data yang diberikan.
- Jika data kurang, katakan terus terang bahwa datanya belum cukup.

GAYA JAWABAN:
- Bahasa Indonesia yang natural, singkat, jelas, dan mudah dipahami trader pemula.
- Utamakan kalimat pendek.
- Hindari istilah Inggris jika ada padanan Indonesia yang mudah.
- Jika istilah Inggris penting, tulis istilahnya lalu jelaskan artinya.
- Jangan mengulang semua angka mentah. Pilih angka yang benar-benar membantu pengguna mengambil keputusan.
- Fokus pada pertanyaan: "Pasar sekarang bagaimana?", "Kenapa?", "Apa yang perlu diperhatikan?", dan "Di mana risikonya?".
- Jangan memerintah pengguna untuk membeli atau menjual. Gunakan bahasa seperti "bisa dipertimbangkan", "lebih baik menunggu", atau "risikonya perlu diperhatikan".

DATA MARKET 24 JAM:
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
Kekuatan setup: ${signal.confidence}/100. Ini hanya menunjukkan seberapa kuat setup menurut engine, bukan peluang pasti mendapat profit.
Sentimen: ${signal.sentiment.displayName}
Entry: ${PriceFormatter.formatPrice(signal.entryPrice)}
TP1: ${PriceFormatter.formatPrice(signal.targetPrice1)}
TP2: ${PriceFormatter.formatPrice(signal.targetPrice2)}
SL: ${PriceFormatter.formatPrice(signal.stopLoss)}
RR: ${signal.riskRewardRatio}
Alasan Engine: ${signal.reasoning.joinToString("; ")}

$macro

FORMAT JAWABAN:
1. KONDISI SEKARANG
   Jelaskan apakah harga sedang cenderung naik, turun, atau masih belum jelas. Sebutkan alasan paling penting.

2. KENAPA?
   Berikan maksimal 3 alasan sederhana dari harga, volume, RSI, MACD, EMA, atau struktur harga. Jelaskan istilah jika dipakai.

3. YANG PERLU DIPANTAU
   Sebutkan support/resistance atau kondisi yang perlu diperhatikan. Jelaskan apa yang terjadi jika level penting bertahan atau ditembus.

4. RISIKO
   Jelaskan risiko utama setup saat ini. Jika ada Entry, TP, SL, dan RR dari engine, jelaskan fungsinya dengan bahasa sederhana. Jangan membuat angka baru.

5. KESIMPULAN
   Berikan kesimpulan singkat: bisa dipertimbangkan masuk, lebih baik menunggu, atau perlu waspada. Jangan memberikan kepastian profit.

6. MAKRO INDONESIA
   Hanya jika data BPS tersedia, jelaskan CPI/IHK secara singkat dan apakah konteks tersebut cenderung mendukung, netral, atau menambah risiko. Jangan menjadikan makro sebagai sinyal BUY/SELL otomatis.

7. DATA YANG BELUM ADA
   Jika ada data penting yang tidak tersedia, sebutkan singkat. Jangan mengarang.

Jangan menyebut harga USD/USDT jika tidak ada di data. Jangan menulis disclaimer panjang. Fokus membantu trader pemula memahami situasi pasar dengan cepat.
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
        val rsi = when { indicators.rsi14 < 30 -> "Rendah, harga bisa sedang terlalu banyak dijual"; indicators.rsi14 > 70 -> "Tinggi, harga bisa sedang terlalu banyak dibeli"; else -> "Normal" }
        val action = when (signal.action.name) { "BUY" -> "BISA DIPERTIMBANGKAN BELI"; "SELL" -> "PERLU WASPADA / PERTIMBANGKAN JUAL"; else -> "TAHAN / TUNGGU" }
        val macro = cpi?.let { "• BPS CPI/IHK: ${it.cpiIndex?.toString() ?: "-"} | inflasi YoY: ${if (it.yoyPercent.isFinite()) "${it.yoyPercent}%" else "-"} | ${it.period}" } ?: "• BPS CPI/IHK: tidak tersedia."
        return """
✨ RINGKASAN 24 JAM (${tick.symbol}) — Mode Sederhana
• Harga: ${PriceFormatter.formatPrice(tick.price)} (${PriceFormatter.formatPercentage(tick.change24h)} 24 jam)
• Range harga: ${PriceFormatter.formatPrice(tick.low24h)} - ${PriceFormatter.formatPrice(tick.high24h)}
• Volume: ${PriceFormatter.formatVolume(tick.volume24h)}
• RSI: $rsi (${PriceFormatter.formatRsi(indicators.rsi14)})
• Sinyal: $action (${signal.confidence}/100)
$macro
• Area dari engine: Entry ${PriceFormatter.formatPrice(signal.entryPrice)} | TP1 ${PriceFormatter.formatPrice(signal.targetPrice1)} | TP2 ${PriceFormatter.formatPrice(signal.targetPrice2)} | Batas rugi ${PriceFormatter.formatPrice(signal.stopLoss)} | RR ${signal.riskRewardRatio}

Data funding, liquidation, open interest, berita, dan data makro lain tidak tersedia.
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try { AppContextProvider.context; true } catch (_: UninitializedPropertyAccessException) { false }
}
