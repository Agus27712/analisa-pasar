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
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Chart summary Gemini — output wajib Bahasa Indonesia (headline di-translate). */
object GeminiAiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val MODEL = "gemini-3.7-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    suspend fun generateChartSummary24h(
        apiKey: String,
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState
    ): String = withContext(Dispatchers.IO) {
        val effectiveKey = if (apiKey.isBlank()) "" else apiKey
        val cpi = if (safeContextReady) BpsMacroService(AppContextProvider.context).getLatest() else null
        val base = extractBase(tick.symbol)
        val headlines = runCatching { CryptoHeadlineService.snapshotForBase(base) }.getOrNull()
        val headlineBlock = headlines?.promptBlock() ?: "Headline: tidak tersedia."

        if (effectiveKey.isBlank()) return@withContext buildFallback(tick, indicators, signal, cpi, headlineBlock)

        val pairCtx = PairNarrative.forBase(base)
        val move = describeMove(tick.change24h)
        val rsiFormatted = if (indicators.rsi14.isFinite()) String.format(java.util.Locale.US, "%.1f", indicators.rsi14) else "—"
        val rsiExplanation = when {
            !indicators.rsi14.isFinite() -> "Data RSI belum cukup"
            indicators.rsi14 > 70 -> "**Jenuh Beli (Overbought)** ($rsiFormatted) — Tekanan beli sangat tinggi, waspada potensi koreksi / taking profit"
            indicators.rsi14 < 30 -> "**Jenuh Jual (Oversold)** ($rsiFormatted) — Tekanan jual klimaks, peluang technical rebound / pantulan harga"
            indicators.rsi14 >= 50 -> "**Netral Bullish** ($rsiFormatted) — Momentum beli aktif dalam batas wajar"
            else -> "**Netral Bearish** ($rsiFormatted) — Tekanan jual moderat dalam batas wajar"
        }
        val trendExplanation = when {
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 ->
                "**Struktur Bullish Kuat** (Harga di atas EMA 20 & EMA 50)"
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 ->
                "**Struktur Bearish Kuat** (Harga di bawah EMA 20 & EMA 50)"
            else -> "**Konsolidasi / Sideways** (EMA berhimpit atau fase transisi)"
        }
        val macdExplanation = when {
            !indicators.macdHist.isFinite() -> "Data MACD belum cukup"
            indicators.macdHist > 0 -> "**Histogram Positif (Bullish Momentum)** — Volume dorongan beli sedang menguat"
            indicators.macdHist < 0 -> "**Histogram Negatif (Bearish Momentum)** — Tekanan distribusi jual sedang dominan"
            else -> "**Netral** — Momentum berimbang"
        }
        val atrExplanation = if (indicators.atr.isFinite() && indicators.atr > 0) {
            "Rentang fluktuasi candle rata-rata: ${PriceFormatter.formatPrice(indicators.atr)}"
        } else "Volatilitas normal"

        val prompt = """
Kamu asisten quantitative & technical analyst spot Indodax. SELURUH jawaban WAJIB Bahasa Indonesia.
Keluarkan output dalam format Markdown yang rapi, terstruktur, gunakan poin bullet (-), teks tebal (**bold**), dan judul bab (###).
Jika ada kutipan berita Inggris, TERJEMAHKAN ke Bahasa Indonesia.
Maksimal ~250 kata.

Data Pasar Real-Time:
- Pair: ${tick.symbol} ($base)
- Identitas: ${pairCtx.label}
- Ekosistem: ${pairCtx.ecosystem}
- Pergerakan 24J: $move (${PriceFormatter.formatPercentage(tick.change24h)})
- Harga Terakhir: ${PriceFormatter.formatPrice(tick.price)} | Volume 24J: ${PriceFormatter.formatVolume(tick.volume24h)}
- Status Indikator:
  * RSI (14): $rsiExplanation
  * EMA Tren: $trendExplanation
  * MACD: $macdExplanation
  * ATR / Volatilitas: $atrExplanation
  * Engine Sinyal: ${signal.action.name} (Keyakinan: ${signal.confidence}/100)

$headlineBlock

Format Output (Wajib Markdown Terstruktur):
### 🔎 1. Profil & Ekosistem Aset
- **Aset**: ...
- **Korelasi**: ...

### 📊 2. Analisis Indikator Teknikal
- **RSI (14)**: [Jelaskan status apakah Jenuh Beli / Overbought, Jenuh Jual / Oversold, atau Netral beserta implikasi tradingnya]
- **Tren EMA & MACD**: [Jelaskan arah tren & momentum dorongan pasar]
- **Volatilitas**: [Kondisi volatilitas & batas risiko]

### 📰 3. Sentimen Pasar & Alasan Gerakan
- **Faktor Penggerak**: [Terjemahan & analisa berita/volume terhadap harga]

### 💡 4. Panduan Strategi & Action Plan
- **Sinyal Engine**: **${signal.action.name}** (Confidence: ${signal.confidence}/100)
- **Tindakan Disarankan**: [Strategi entry/exit, limit order maker, disiplin money management]
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
                    put("temperature", 0.35)
                    put("maxOutputTokens", 480)
                })
            }
            val request = Request.Builder()
                .url("$BASE_URL?key=$effectiveKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext buildFallback(tick, indicators, signal, cpi, headlineBlock)
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
                if (text.isNotBlank()) text.trim() else buildFallback(tick, indicators, signal, cpi, headlineBlock)
            }
        } catch (e: Exception) {
            Timber.e(e, "Gagal memanggil Gemini AI Service")
            buildFallback(tick, indicators, signal, cpi, headlineBlock)
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
        cpi: IndonesiaCpiData?,
        headlineBlock: String
    ): String {
        val base = extractBase(tick.symbol)
        val ctx = PairNarrative.forBase(base)
        val move = describeMove(tick.change24h)
        val rsiFormatted = if (indicators.rsi14.isFinite()) String.format(java.util.Locale.US, "%.1f", indicators.rsi14) else "—"
        val rsiStatus = when {
            !indicators.rsi14.isFinite() -> "Netral (Data belum cukup)"
            indicators.rsi14 > 70 -> "**Jenuh Beli (Overbought)** ($rsiFormatted) — Tekanan beli mencapai puncak, waspada aksi ambil untung"
            indicators.rsi14 < 30 -> "**Jenuh Jual (Oversold)** ($rsiFormatted) — Tekanan jual klimaks, peluang pantulan teknikal"
            indicators.rsi14 >= 50 -> "**Netral Bullish** ($rsiFormatted) — Tren beli stabil dalam batas aman"
            else -> "**Netral Bearish** ($rsiFormatted) — Tekanan jual moderat dalam batas aman"
        }
        val emaStatus = when {
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 ->
                "**Bullish Alignment** (Harga di atas EMA 20 & 50)"
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 ->
                "**Bearish Alignment** (Harga di bawah EMA 20 & 50)"
            else -> "**Konsolidasi / Sideways** (EMA berhimpit)"
        }
        val macdStatus = when {
            !indicators.macdHist.isFinite() -> "Netral"
            indicators.macdHist > 0 -> "**Histogram Positif (Bullish Momentum)**"
            indicators.macdHist < 0 -> "**Histogram Negatif (Bearish Momentum)**"
            else -> "Netral"
        }
        val atrStatus = if (indicators.atr.isFinite() && indicators.atr > 0) {
            PriceFormatter.formatPrice(indicators.atr)
        } else "Normal"

        return """
### 🔎 1. Profil & Ekosistem Aset
- **Aset**: ${ctx.label}
- **Karakteristik**: ${ctx.narrative}
- **Korelasi**: ${ctx.ecosystem}

### 📊 2. Analisis Indikator Teknikal
- **RSI (14)**: $rsiStatus
- **Struktur EMA**: $emaStatus
- **Momentum MACD**: $macdStatus
- **Rentang ATR**: $atrStatus (rentang fluktuasi candle untuk toleransi risiko)

### 📰 3. Sentimen Pasar & Alasan Gerakan
- **Pergerakan 24J**: $move (${PriceFormatter.formatPercentage(tick.change24h)}), Volume: ${PriceFormatter.formatVolume(tick.volume24h)}
- **Sentimen & Berita**:
$headlineBlock

### 💡 4. Panduan Strategi & Action Plan
- **Sinyal Engine**: **${signal.action.name}** (Confidence: ${signal.confidence}/100)
- **Tindakan**: Pantau konfirmasi arah BTC terlebih dahulu. Gunakan limit order maker 0.21%, hindari mengejar candle yang sudah bergerak jauh.
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try {
            AppContextProvider.context; true
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
}
