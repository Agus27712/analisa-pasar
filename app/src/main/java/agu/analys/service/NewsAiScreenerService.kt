package agu.analys.service

import agu.analys.config.AiProvider
import agu.analys.model.MarketTick
import agu.analys.model.NewsArticle
import agu.analys.model.NewsScreenerResult
import agu.analys.model.ScreenerCoinPick
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

/**
 * Service AI Screener Berita (Standalone untuk Dashboard)
 * Menyeleksi koin berpotensi naik dari agregasi RSS feed, strictly divalidasi koin listing di Indodax.
 * Mendukung Groq (Qwen) dan Gemini 3.7 Flash.
 */
object NewsAiScreenerService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val GROQ_QWEN_MODEL = "qwen/qwen3.8-27b"
    private const val GEMINI_MODEL = "gemini-3.7-flash"
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
    private const val MAX_TOKENS = 757

    suspend fun screenCoinsFromNews(
        articles: List<NewsArticle>,
        indodaxValidBases: Set<String>,
        liveTicks: Map<String, MarketTick>,
        preferredProvider: AiProvider,
        groqApiKey: String,
        geminiApiKey: String
    ): NewsScreenerResult = withContext(Dispatchers.IO) {
        // Whitelist koin Indodax teratas & aktif untuk context AI
        val sampleWhitelist = indodaxValidBases
            .map { it.uppercase().trim() }
            .filter { it.isNotBlank() && it.length in 2..10 && it != "IDR" && it != "USDT" }
            .distinct()
            .sorted()
            .take(120)
            .joinToString(", ")

        val headlinesText = articles.mapIndexed { idx, art ->
            "${idx + 1}. [${art.source}] ${art.title}"
        }.joinToString("\n")

        // Gunakan secara mutlak asisten AI aktif dari halaman Pengaturan (hanya 1 AI)
        val (rawResponse, modelName, providerName) = when (preferredProvider) {
            AiProvider.GROQ -> {
                if (groqApiKey.isNotBlank()) {
                    val res = callGroqQwen(groqApiKey, sampleWhitelist, headlinesText)
                    Triple(res, GROQ_QWEN_MODEL, "Groq (Qwen 27B)")
                } else {
                    val fallback = buildLocalFallback(articles, indodaxValidBases, liveTicks, "Groq API Key belum diisi di Pengaturan")
                    Triple(fallback, "Heuristik", "Groq (API Key Kosong)")
                }
            }
            AiProvider.GEMINI -> {
                if (geminiApiKey.isNotBlank()) {
                    val res = callGeminiFlash(geminiApiKey, sampleWhitelist, headlinesText)
                    Triple(res, GEMINI_MODEL, "Gemini 3.7 Flash")
                } else {
                    val fallback = buildLocalFallback(articles, indodaxValidBases, liveTicks, "Gemini API Key belum diisi di Pengaturan")
                    Triple(fallback, "Heuristik", "Gemini (API Key Kosong)")
                }
            }
        }

        val parsedPicks = parseCoinPicks(rawResponse, indodaxValidBases, liveTicks)

        NewsScreenerResult(
            picks = parsedPicks,
            rawAnalysis = rawResponse,
            articlesAnalyzed = articles,
            providerUsed = providerName,
            modelUsed = modelName,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun callGroqQwen(
        apiKey: String,
        indodaxCoins: String,
        headlines: String
    ): String {
        val systemPrompt = """
Anda adalah Quantitative Crypto Screener & News Catalyst Analyst khusus pasar Indodax Spot Exchange.
Tugas Anda adalah menyeleksi 2 sampai 4 koin calon beli (bullish candidates) berdasarkan ringkasan berita/RSS terbaru.

ATURAN KETAT:
1. FILTER LISTING INDODAX: Hanya rekomendasikan koin yang valid ada di dalam [DAFTAR KOIN AKTIF INDODAX]. Koin di luar daftar ini wajib diabaikan total.
2. PURE SPOT MINDSET: Fokus hanya pada potensi kenaikan harga (upside/buy catalyst). Jangan berikan rekomendasi shorting/futures.
3. OUTPUT MAKSIMAL 757 TOKEN: Berikan analisis padat, tajam, edukatif, dan to the point tanpa basa-basi pembuka/penutup.
4. FORMAT OUTPUT PER KOIN:
🔥 [SIMBOL/IDR] (Contoh: SOL/IDR)
• Narasi/Sektor: [Sektor koin, misal: Layer-1 / DeFi / AI]
• Potensi Sentimen: [Sangat Kuat / Menengah]
• Katalis Utama: [1 kalimat jelas peristiwa/berita pemicu]
• Alasan Penguatan:
  - [Poin alasan 1]
  - [Poin alasan 2]
• Tingkat Validitas: [Tinggi / Sedang]
        """.trimIndent()

        val userPrompt = """
[DAFTAR KOIN AKTIF INDODAX]:
$indodaxCoins

[FEED BERITA TERKINI]:
$headlines

INSTRUKSI:
Analisis berita di atas secara mendalam. Identifikasi koin yang masuk dalam daftar Indodax yang memiliki sentimen positif terkuat dan berpotensi mengalami kenaikan harga spot. Sajikan sesuai format yang telah ditentukan (maksimal 757 token).
        """.trimIndent()

        return try {
            val payload = JSONObject().apply {
                put("model", GROQ_QWEN_MODEL)
                put("temperature", 0.30)
                put("max_tokens", MAX_TOKENS)
                put("reasoning_effort", "high")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                })
            }

            val request = Request.Builder()
                .url(GROQ_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Timber.e("Groq screener call failed: ${resp.code} - $body")
                    return "⚠️ Gagal memanggil Groq ($GROQ_QWEN_MODEL): HTTP ${resp.code}. Periksa Groq API Key di Settings."
                }
                val text = JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                if (text.isNotBlank()) text.trim() else "Tidak ada output dari model Groq."
            }
        } catch (e: Exception) {
            Timber.e(e, "Error executing Groq Qwen Screener")
            "⚠️ Kendala koneksi ke Groq API: ${e.localizedMessage ?: "Timeout"}"
        }
    }

    private fun callGeminiFlash(
        apiKey: String,
        indodaxCoins: String,
        headlines: String
    ): String {
        val systemInstruction = """
Anda adalah Quantitative Research Analyst & Crypto Market Intelligence untuk pasar Spot Indodax.
Tugas Anda: Membaca tumpukan feed berita crypto global, lalu menyaring hanya koin-koin yang listing di Indodax yang memiliki katalis kenaikan harga (bullish catalyst) terkuat.

ATURAN UTAMA:
1. FILTER WAJIB INDODAX: Anda HANYA BOLEH menyaring dan menampilkan koin yang terdaftar dalam daftar [DAFTAR VALID KOIN INDODAX SPOT]. Koin di luar daftar ini wajib diabaikan total.
2. SPOT PERSPECTIVE: Hanya cari katalis akumulasi/kenaikan harga (Spot Buy). Tidak ada shorting/futures.
3. DETEKSI NARASI & MAKRO: Hubungkan berita mikro koin dengan narasi besar (AI, RWA, Layer-1, aliran dana institusi).
4. PANJANG OUTPUT: Padat, maksimal 757 token. Tanpa salam pembuka dan penutup.
5. FORMAT OUTPUT PER KOIN:
🔥 [SIMBOL/IDR] (Contoh: SOL/IDR)
• Narasi/Sektor: [Sektor koin]
• Potensi Sentimen: [Sangat Kuat / Menengah]
• Katalis Utama: [1 kalimat jelas peristiwa/berita pemicu]
• Alasan Penguatan:
  - [Poin alasan 1]
  - [Poin alasan 2]
• Tingkat Validitas: [Tinggi / Sedang]
        """.trimIndent()

        val userPrompt = """
[DAFTAR VALID KOIN INDODAX SPOT]:
$indodaxCoins

[FEED AGREGASI BERITA TERKINI (RSS)]:
$headlines

TUGAS:
Saring seluruh berita di atas dan cocokkan dengan daftar koin Indodax. Pilih 2 sampai 4 koin kandidat terbaik yang berpotensi naik paling tinggi berdasarkan katalis berita.
        """.trimIndent()

        val combinedPrompt = """$systemInstruction

$userPrompt""".trimIndent()

        return try {
            val url = "$GEMINI_URL?key=$apiKey"
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", combinedPrompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.20)
                    put("maxOutputTokens", MAX_TOKENS)
                })
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Timber.e("Gemini screener call failed: ${resp.code} - $body")
                    return "⚠️ Gagal memanggil Gemini ($GEMINI_MODEL): HTTP ${resp.code}. Periksa Gemini API Key di Settings."
                }
                val candidates = JSONObject(body).optJSONArray("candidates")
                val text = candidates?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
                if (text.isNotBlank()) text.trim() else "Tidak ada output dari Gemini."
            }
        } catch (e: Exception) {
            Timber.e(e, "Error executing Gemini Screener")
            "⚠️ Kendala koneksi ke Gemini API: ${e.localizedMessage ?: "Timeout"}"
        }
    }

    private fun parseCoinPicks(
        rawText: String,
        indodaxBases: Set<String>,
        liveTicks: Map<String, MarketTick>
    ): List<ScreenerCoinPick> {
        val picks = mutableListOf<ScreenerCoinPick>()
        val blocks = rawText.split(Regex("(?:^|\n)(?=🔥|###|\\*\\*\\s*\\[?[A-Z0-9]{2,10}(?:/IDR)?\\]?)"))

        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.length < 30) continue

            // Ekstrak Simbol Koin
            val symbolMatch = Regex("(?:🔥|###)?\\s*\\[?([A-Z0-9]{2,10})(?:/IDR)?\\]?").find(trimmed)
            val base = symbolMatch?.groupValues?.getOrNull(1)?.uppercase() ?: continue

            // Validasi apakah benar listing di Indodax!
            val isValidIndodax = indodaxBases.any { it.equals(base, ignoreCase = true) }
            if (!isValidIndodax) continue

            val pairSymbol = "${base}IDR"
            val indodaxPair = "${base.lowercase()}_idr"

            // Ekstrak Narasi / Sektor
            val sectorMatch = Regex("[•\\-]\\s*(?:Narasi/Sektor|Sektor|Narasi):?\\s*(.+)", RegexOption.IGNORE_CASE).find(trimmed)
            val sector = sectorMatch?.groupValues?.getOrNull(1)?.trim() ?: "Spot Altcoin"

            // Ekstrak Sentimen
            val sentimentMatch = Regex("[•\\-]\\s*(?:Potensi Sentimen|Sentimen):?\\s*(.+)", RegexOption.IGNORE_CASE).find(trimmed)
            val sentiment = sentimentMatch?.groupValues?.getOrNull(1)?.trim() ?: "Sangat Kuat"

            // Ekstrak Katalis Utama
            val catalystMatch = Regex("[•\\-]\\s*(?:Katalis Utama|Katalis):?\\s*(.+)", RegexOption.IGNORE_CASE).find(trimmed)
            val catalyst = catalystMatch?.groupValues?.getOrNull(1)?.trim()
                ?: trimmed.lines().getOrNull(1)?.removePrefix("•")?.trim().orEmpty()

            // Ekstrak Alasan
            val reasons = mutableListOf<String>()
            val reasonLines = trimmed.lines().filter {
                it.trim().startsWith("-") || (it.trim().startsWith("•") && !it.contains("Narasi") && !it.contains("Sentimen") && !it.contains("Katalis") && !it.contains("Validitas"))
            }
            for (line in reasonLines.take(3)) {
                val cleanedLine = line.trim().removePrefix("-").removePrefix("•").trim()
                if (cleanedLine.length in 10..150) {
                    reasons.add(cleanedLine)
                }
            }
            if (reasons.isEmpty()) {
                reasons.add("Sentimen akumulasi positif berdasarkan sorotan berita terkini.")
            }

            // Ekstrak Validitas
            val validityMatch = Regex("[•\\-]\\s*(?:Tingkat Validitas|Validitas):?\\s*(.+)", RegexOption.IGNORE_CASE).find(trimmed)
            val validity = validityMatch?.groupValues?.getOrNull(1)?.trim() ?: "Tinggi"

            // Data Live Market Tick jika ada
            val tick = liveTicks[pairSymbol] ?: liveTicks[indodaxPair] ?: liveTicks[base]

            picks.add(
                ScreenerCoinPick(
                    baseSymbol = base,
                    pairSymbol = pairSymbol,
                    indodaxPair = indodaxPair,
                    sentimentGrade = sentiment,
                    sectorNarrative = sector,
                    mainCatalyst = catalyst,
                    reasons = reasons,
                    validityGrade = validity,
                    currentPrice = tick?.price ?: 0.0,
                    change24h = tick?.change24h ?: 0.0,
                    volume24h = tick?.volume24h ?: 0.0
                )
            )
        }

        return picks.distinctBy { it.baseSymbol }
    }

    private fun buildLocalFallback(
        articles: List<NewsArticle>,
        indodaxBases: Set<String>,
        liveTicks: Map<String, MarketTick>,
        hintMessage: String = "Masukkan API Key di Pengaturan"
    ): String {
        val matchedCoins = mutableSetOf<String>()
        for (art in articles) {
            val words = art.title.uppercase().split(Regex("[^A-Z0-9]"))
            for (word in words) {
                if (word.length in 3..8 && indodaxBases.contains(word)) {
                    matchedCoins.add(word)
                }
            }
        }

        val topCandidates = matchedCoins.take(3).ifEmpty { listOf("BTC", "SOL", "ETH") }

        return buildString {
            appendLine("### 📋 Hasil Screening Koin Berita Indodax")
            appendLine("*(ℹ️ $hintMessage)*\n")
            topCandidates.forEach { coin ->
                appendLine("🔥 [$coin/IDR]")
                appendLine("• Narasi/Sektor: Major Liquid Altcoin / Spot")
                appendLine("• Potensi Sentimen: Menengah")
                appendLine("• Katalis Utama: Disebutkan dalam feed berita pergerakan pasar crypto terkini.")
                appendLine("• Alasan Penguatan:")
                appendLine("  - Likuiditas tinggi di Indodax IDR dengan volume stabil.")
                appendLine("  - Potensi momentum mengikuti arah pergerakan pasar global.")
                appendLine("• Tingkat Validitas: Sedang\n")
            }
        }
    }
}
