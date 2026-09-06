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
    private const val GROQ_PRIMARY_MODEL = "qwen/qwen3.8-27b"
    private val GROQ_MODELS = listOf(
        "qwen/qwen3.8-27b",
        "openai/gpt-oss-20b",
        "openai/gpt-oss-120b",
        "qwen/qwen3.6-27b"
    )
    private const val GEMINI_MODEL = "gemini-3.6-flash"
    private val GEMINI_MODELS = listOf(
        "gemini-3.6-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash"
    )
    private const val MAX_TOKENS = 750

    suspend fun screenCoinsFromNews(
        articles: List<NewsArticle>,
        indodaxValidBases: Set<String>,
        liveTicks: Map<String, MarketTick>,
        preferredProvider: AiProvider,
        groqApiKey: String,
        geminiApiKey: String
    ): NewsScreenerResult = withContext(Dispatchers.IO) {
        // Kompresi token: Ambil maksimal 50 koin teraktif & 12 headline teratas agar tidak menabrak TPM rate limit (6000 TPM)
        val sampleWhitelist = indodaxValidBases
            .map { it.uppercase().trim() }
            .filter { it.isNotBlank() && it.length in 2..10 && it != "IDR" && it != "USDT" }
            .distinct()
            .sorted()
            .take(50)
            .joinToString(", ")

        val selectedArticles = articles.take(12)
        val headlinesText = selectedArticles.mapIndexed { idx, art ->
            "${idx + 1}. [${art.source}] ${art.title.take(120)}"
        }.joinToString("\n")

        var usedProvider = preferredProvider
        var usedModel = when (preferredProvider) {
            AiProvider.GROQ -> GROQ_PRIMARY_MODEL
            AiProvider.GEMINI -> GEMINI_MODEL
        }
        var providerLabel = when (preferredProvider) {
            AiProvider.GROQ -> "Groq (Qwen / GPT-OSS)"
            AiProvider.GEMINI -> "Gemini Flash"
        }

        var rawResponse = ""

        if (preferredProvider == AiProvider.GROQ) {
            if (groqApiKey.isNotBlank()) {
                val groqResult = callGroqWithFallback(groqApiKey, sampleWhitelist, headlinesText)
                rawResponse = groqResult.text
                usedModel = groqResult.modelUsed
                providerLabel = "Groq (${groqResult.modelUsed.substringAfterLast("/")})"

                // Jika Groq terkena HTTP 429 / Rate limit, coba failover ke Gemini jika ada key-nya
                if (groqResult.isRateLimited || groqResult.isFailed) {
                    if (geminiApiKey.isNotBlank()) {
                        Timber.w("Groq bermasalah/rate limited. Melakukan auto failover ke Gemini...")
                        val geminiRes = callGeminiFlash(geminiApiKey, sampleWhitelist, headlinesText)
                        if (!geminiRes.startsWith("⚠️")) {
                            rawResponse = geminiRes
                            usedModel = GEMINI_MODEL
                            providerLabel = "Gemini Flash (Auto Failover)"
                            usedProvider = AiProvider.GEMINI
                        }
                    }
                }
            } else {
                rawResponse = buildLocalFallback(articles, indodaxValidBases, liveTicks, "Groq API Key belum diisi di Pengaturan")
                usedModel = "Heuristik"
                providerLabel = "Groq (API Key Kosong)"
            }
        } else {
            // GEMINI
            if (geminiApiKey.isNotBlank()) {
                val geminiRes = callGeminiFlash(geminiApiKey, sampleWhitelist, headlinesText)
                rawResponse = geminiRes
                usedModel = GEMINI_MODEL
                providerLabel = "Gemini Flash"

                if (geminiRes.startsWith("⚠️") && groqApiKey.isNotBlank()) {
                    Timber.w("Gemini bermasalah. Melakukan auto failover ke Groq...")
                    val groqResult = callGroqWithFallback(groqApiKey, sampleWhitelist, headlinesText)
                    if (!groqResult.isFailed) {
                        rawResponse = groqResult.text
                        usedModel = groqResult.modelUsed
                        providerLabel = "Groq (${groqResult.modelUsed.substringAfterLast("/")}) (Failover)"
                        usedProvider = AiProvider.GROQ
                    }
                }
            } else {
                rawResponse = buildLocalFallback(articles, indodaxValidBases, liveTicks, "Gemini API Key belum diisi di Pengaturan")
                usedModel = "Heuristik"
                providerLabel = "Gemini (API Key Kosong)"
            }
        }

        var parsedPicks = parseCoinPicks(rawResponse, indodaxValidBases, liveTicks)

        // JAMINAN TIDAK KOSONG: Jika AI gagal atau mengembalikan error rate limit sehingga parsedPicks kosong,
        // segera aktifkan fallback heuristik berbasis feed RSS berita agar user tetap mendapatkan data!
        if (parsedPicks.isEmpty()) {
            val failureReason = when {
                rawResponse.contains("429") -> "Kuota TPM Groq terkena Rate Limit (HTTP 429). Beralih ke analisis heuristik berita Indodax."
                rawResponse.contains("API Key", ignoreCase = true) -> "API Key belum diisi di Pengaturan."
                rawResponse.startsWith("⚠️") -> rawResponse.take(150)
                else -> "Model AI tidak mendeteksi format koin. Mengaktifkan kurasi heuristik berita."
            }
            val fallbackText = buildLocalFallback(articles, indodaxValidBases, liveTicks, failureReason)
            parsedPicks = parseCoinPicks(fallbackText, indodaxValidBases, liveTicks)
            rawResponse = "$failureReason\n\n$fallbackText"
            usedModel = "Heuristik Fallback"
            providerLabel = "$providerLabel (Heuristik)"
        }

        NewsScreenerResult(
            picks = parsedPicks,
            rawAnalysis = rawResponse,
            articlesAnalyzed = articles,
            providerUsed = providerLabel,
            modelUsed = usedModel,
            timestampMs = System.currentTimeMillis()
        )
    }

    private data class GroqCallResult(
        val text: String,
        val modelUsed: String,
        val isRateLimited: Boolean = false,
        val isFailed: Boolean = false
    )

    private fun callGroqWithFallback(
        apiKey: String,
        indodaxCoins: String,
        headlines: String
    ): GroqCallResult {
        var lastError = ""
        var rateLimited = false

        for (model in GROQ_MODELS) {
            val result = executeGroqRequest(apiKey, model, indodaxCoins, headlines)
            if (result.isSuccess) {
                return GroqCallResult(
                    text = result.getOrNull().orEmpty(),
                    modelUsed = model,
                    isRateLimited = false,
                    isFailed = false
                )
            } else {
                val msg = result.exceptionOrNull()?.message.orEmpty()
                lastError = msg
                if (msg.contains("429")) {
                    rateLimited = true
                    Timber.w("Groq model $model terkena Rate Limit 429, mencoba model alternatif...")
                } else {
                    Timber.w("Groq model $model gagal: $msg, mencoba model alternatif...")
                }
            }
        }

        val friendlyError = if (rateLimited) {
            "⚠️ Groq API terkena Rate Limit (HTTP 429 - Kuota TPM Groq penuh). Menampilkan rekomendasi koin lokal."
        } else {
            "⚠️ Gagal memanggil Groq: $lastError"
        }

        return GroqCallResult(
            text = friendlyError,
            modelUsed = GROQ_PRIMARY_MODEL,
            isRateLimited = rateLimited,
            isFailed = true
        )
    }

    private fun executeGroqRequest(
        apiKey: String,
        modelName: String,
        indodaxCoins: String,
        headlines: String
    ): Result<String> {
        val systemPrompt = """
Anda adalah Quantitative Crypto Screener & News Catalyst Analyst khusus pasar Indodax Spot Exchange.
Tugas Anda adalah menyeleksi 2 sampai 4 koin calon beli (bullish candidates) berdasarkan ringkasan berita/RSS terbaru.

ATURAN KETAT:
1. FILTER LISTING INDODAX: Hanya rekomendasikan koin yang valid ada di dalam [DAFTAR KOIN AKTIF INDODAX]. Koin di luar daftar ini wajib diabaikan total.
2. PURE SPOT MINDSET: Fokus hanya pada potensi kenaikan harga (upside/buy catalyst). Jangan berikan rekomendasi shorting/futures.
3. OUTPUT PADAT DAN TUNTAS: Berikan analisis padat, tajam, edukatif, dan to the point tanpa basa-basi pembuka/penutup.
4. FORMAT OUTPUT PER KOIN WAJIB PERSIS SEPERTI INI:
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
Pilih 2 sampai 4 koin kandidat bullish terbaik yang ada di daftar Indodax berdasarkan katalis berita di atas. Sajikan persis sesuai format yang ditentukan.
        """.trimIndent()

        return try {
            val payload = JSONObject().apply {
                put("model", modelName)
                put("temperature", 0.25)
                put("max_tokens", MAX_TOKENS)
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
                    Timber.e("Groq call failed for $modelName: ${resp.code} - $body")
                    return Result.failure(Exception("HTTP ${resp.code}: $body"))
                }
                val choiceObj = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                val messageObj = choiceObj?.optJSONObject("message")
                var text = messageObj?.optString("content").orEmpty().trim()

                // Jika content kosong tetapi reasoning ada (kasus model reasoning kehabisan token), gunakan reasoning_content
                if (text.isBlank()) {
                    val reasoning = messageObj?.optString("reasoning").orEmpty().ifEmpty {
                        messageObj?.optString("reasoning_content").orEmpty()
                    }
                    if (reasoning.isNotBlank()) {
                        text = reasoning.trim()
                    }
                }

                if (text.isNotBlank()) {
                    Result.success(text)
                } else {
                    Result.failure(Exception("Output dari model $modelName kosong"))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception calling Groq with $modelName")
            Result.failure(e)
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

        for (model in GEMINI_MODELS) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
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
                        put("maxOutputTokens", 1200)
                    })
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val candidates = JSONObject(body).optJSONArray("candidates")
                        val text = candidates?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text")
                            .orEmpty()
                        if (text.isNotBlank()) return text.trim()
                    } else {
                        Timber.w("Gemini screener model $model failed: ${resp.code} - $body")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error executing Gemini Screener with $model")
            }
        }

        return "⚠️ Gagal memanggil Gemini API. Periksa kuota atau Gemini API Key di Settings."
    }

    private fun parseCoinPicks(
        rawText: String,
        indodaxBases: Set<String>,
        liveTicks: Map<String, MarketTick>
    ): List<ScreenerCoinPick> {
        val picks = mutableListOf<ScreenerCoinPick>()
        // Toleransi format luas: 🔥, ###, **, 1., 2., [SIMBOL/IDR]
        val blocks = rawText.split(Regex("(?:^|\n)(?=(?:🔥|###|\\*\\*|\\d+\\.)\\s*\\[?[A-Z0-9]{2,10}(?:/IDR)?\\]?)"))

        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.length < 25) continue

            // Ekstrak Simbol Koin
            val symbolMatch = Regex("(?:🔥|###|\\*\\*|\\d+\\.)?\\s*\\[?([A-Z0-9]{2,10})(?:/IDR)?\\]?").find(trimmed)
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

        // Secondary Pass: Jika formatting AI bebas (paragraf/list) dan lolos dari split utama
        if (picks.isEmpty() && !rawText.startsWith("⚠️") && rawText.length > 50) {
            val mentionMatches = Regex("(?:\\b|🔥|#)([A-Z0-9]{2,8})(?:/IDR|\\b)").findAll(rawText)
            for (match in mentionMatches) {
                val candidate = match.groupValues.getOrNull(1)?.uppercase() ?: continue
                if (indodaxBases.any { it.equals(candidate, ignoreCase = true) } && picks.none { it.baseSymbol == candidate }) {
                    val pairSymbol = "${candidate}IDR"
                    val indodaxPair = "${candidate.lowercase()}_idr"
                    val tick = liveTicks[pairSymbol] ?: liveTicks[indodaxPair] ?: liveTicks[candidate]
                    picks.add(
                        ScreenerCoinPick(
                            baseSymbol = candidate,
                            pairSymbol = pairSymbol,
                            indodaxPair = indodaxPair,
                            sentimentGrade = "Sangat Kuat",
                            sectorNarrative = "Spot Momentum",
                            mainCatalyst = "Kandidat katalis bullish berdasarkan analisis narasi berita global.",
                            reasons = listOf("Disebutkan dalam feed berita dengan sentimen positif.", "Likuiditas aktif pada pair Indodax IDR."),
                            validityGrade = "Tinggi",
                            currentPrice = tick?.price ?: 0.0,
                            change24h = tick?.change24h ?: 0.0,
                            volume24h = tick?.volume24h ?: 0.0
                        )
                    )
                    if (picks.size >= 4) break
                }
            }
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
