package agu.analys.service

import agu.analys.config.AiProvider
import agu.analys.model.MarketTick
import agu.analys.model.NewsArticle
import agu.analys.model.NewsScreenerResult
import agu.analys.model.ScreenerCoinPick
import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Service AI Screener Berita (Standalone untuk Dashboard)
 * Menyeleksi koin berpotensi naik dari agregasi RSS feed, strictly divalidasi koin listing di Indodax.
 * Mendukung Gemini Flash.
 */
object NewsAiScreenerService {
    private val client get() = NetworkClientProvider.aiClient

    private const val GEMINI_MODEL = "gemini-2.5-flash"
    private val GEMINI_MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-3.5-flash",
        "gemini-flash-latest",
        "gemini-3.1-pro-preview"
    )
    private const val MAX_TOKENS = 750

    suspend fun screenCoinsFromNews(
        articles: List<NewsArticle>,
        indodaxValidBases: Set<String>,
        liveTicks: Map<String, MarketTick>,
        preferredProvider: AiProvider = AiProvider.GEMINI,
        geminiApiKey: String
    ): NewsScreenerResult = withContext(Dispatchers.IO) {
        // Prioritaskan koin yang muncul di berita dan koin volume tertinggi, lalu lengkapi hingga 50 koin
        val validSet = indodaxValidBases
            .map { it.uppercase().trim() }
            .filter { it.isNotBlank() && it.length in 2..10 && it != "IDR" && it != "USDT" }
            .toSet()

        val mentionedInNews = linkedSetOf<String>()
        val articlesContent = articles.take(15).joinToString(" ") { it.title }.uppercase()
        val words = articlesContent.split(Regex("[^A-Z0-9]")).filter { it.length in 2..10 }
        for (w in words) {
            if (validSet.contains(w)) {
                mentionedInNews.add(w)
            }
        }

        val topVolumeBases = liveTicks.values
            .sortedByDescending { it.volume24h }
            .map { extractBaseSymbol(it.symbol) }
            .filter { validSet.contains(it) }

        val prioritizedWhitelist = linkedSetOf<String>()
        prioritizedWhitelist.addAll(mentionedInNews)
        prioritizedWhitelist.addAll(topVolumeBases)
        prioritizedWhitelist.addAll(validSet.sorted())

        val sampleWhitelist = prioritizedWhitelist
            .take(50)
            .joinToString(", ")

        val now = System.currentTimeMillis()
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 jam (1 hari)
        val freshArticles = articles
            .filter { (now - it.publishedAtMs) <= maxAgeMs }
            .sortedByDescending { it.publishedAtMs }
        val selectedArticles = if (freshArticles.isNotEmpty()) freshArticles.take(12) else articles.take(12)

        val headlinesText = selectedArticles.mapIndexed { idx, art ->
            "${idx + 1}. [${art.source} · ${art.getRelativeTime()}] ${art.title.take(120)}"
        }.joinToString("\n")

        val usedProvider = AiProvider.GEMINI
        var usedModel = GEMINI_MODEL
        var providerLabel = "Gemini Flash"
        var rawResponse = ""

        if (geminiApiKey.isNotBlank()) {
            val geminiRes = callGeminiFlash(geminiApiKey, sampleWhitelist, headlinesText)
            rawResponse = geminiRes
            usedModel = GEMINI_MODEL
            providerLabel = "Gemini Flash"
        } else {
            rawResponse = buildLocalFallback(articles, indodaxValidBases, liveTicks, "Gemini API Key belum diisi di Pengaturan")
            usedModel = "Heuristik"
            providerLabel = "Gemini (API Key Kosong)"
        }

        var parsedPicks = parseCoinPicks(rawResponse, indodaxValidBases, liveTicks)

        // JAMINAN TIDAK KOSONG: Jika AI gagal atau mengembalikan error rate limit sehingga parsedPicks kosong,
        // segera aktifkan fallback heuristik berbasis feed RSS berita agar user tetap mendapatkan data!
        if (parsedPicks.isEmpty()) {
            val failureReason = when {
                rawResponse.contains("429") -> "Model AI terkena Rate Limit (HTTP 429). Beralih ke analisis heuristik berita Indodax."
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

        agu.analys.util.AppLogManager.aiEngine(
            "NewsAiScreener",
            "✅ Analisis AI selesai via $providerLabel ($usedModel). Ditemukan ${parsedPicks.size} koin rekomendasi: ${parsedPicks.joinToString { it.baseSymbol }.ifBlank { "None" }}"
        )

        NewsScreenerResult(
            picks = parsedPicks,
            rawAnalysis = rawResponse,
            articlesAnalyzed = articles,
            providerUsed = providerLabel,
            modelUsed = usedModel,
            timestampMs = System.currentTimeMillis()
        )
    }


    private fun callGeminiFlash(
        apiKey: String,
        indodaxCoins: String,
        headlines: String
    ): String {
        val systemInstruction = """
Anda adalah Quantitative Research Analyst & Crypto Market Intelligence untuk pasar Spot Indodax.
Tugas Anda: Membaca tumpukan feed berita crypto global, lalu menyaring hanya koin-koin yang listing di Indodax yang memiliki katalis kenaikan harga (bullish catalyst) terkuat.

Panduan Evaluasi:
1. Filter Indodax: Hanya saring dan tampilkan koin yang terdaftar dalam daftar [DAFTAR VALID KOIN INDODAX SPOT]. Koin di luar daftar ini harus diabaikan.
2. Perspektif Spot Murni: Hanya cari katalis akumulasi/kenaikan harga (Spot Buy). Dilarang keras merekomendasikan shorting, margin, atau futures. Stop Loss harus selalu lebih rendah dari level entry.
3. Deteksi Narasi & Makro: Hubungkan berita mikro koin dengan narasi besar (AI, RWA, Layer-1, aliran dana institusi).
4. Panjang Output: Berikan analisis padat, tajam, dan tuntas hingga selesai tanpa salam pembuka atau penutup.
5. Format output per koin:
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

        for (model in GEMINI_MODELS) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val payload = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemInstruction) })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", userPrompt) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.55)
                        put("topP", 0.95)
                        put("maxOutputTokens", 4096)
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

    private suspend fun parseCoinPicks(
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
            var tick = liveTicks[pairSymbol]
                ?: liveTicks[indodaxPair]
                ?: liveTicks[base]
                ?: liveTicks["${base}IDR"]
                ?: liveTicks["${base.lowercase()}_idr"]
                ?: liveTicks[base.lowercase()]

            // Fallback fetch live ticker langsung dari Indodax jika belum ada di ticks map
            if (tick == null || tick.price <= 0.0) {
                try {
                    val fetched = IndodaxMarketService.fetchTicker(indodaxPair) ?: IndodaxMarketService.fetchTicker(pairSymbol)
                    if (fetched != null && fetched.price > 0.0) {
                        tick = fetched
                    }
                } catch (_: Exception) {}
            }

            val price = tick?.price ?: 0.0
            val change = if (tick != null && !tick.change24h.isNaN()) tick.change24h else 0.0
            val vol = tick?.volume24h ?: 0.0

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
                    currentPrice = price,
                    change24h = change,
                    volume24h = vol
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
                    var tick = liveTicks[pairSymbol]
                        ?: liveTicks[indodaxPair]
                        ?: liveTicks[candidate]
                        ?: liveTicks["${candidate}IDR"]
                        ?: liveTicks["${candidate.lowercase()}_idr"]
                        ?: liveTicks[candidate.lowercase()]

                    if (tick == null || tick.price <= 0.0) {
                        try {
                            val fetched = IndodaxMarketService.fetchTicker(indodaxPair) ?: IndodaxMarketService.fetchTicker(pairSymbol)
                            if (fetched != null && fetched.price > 0.0) {
                                tick = fetched
                            }
                        } catch (_: Exception) {}
                    }

                    val price = tick?.price ?: 0.0
                    val change = if (tick != null && !tick.change24h.isNaN()) tick.change24h else 0.0
                    val vol = tick?.volume24h ?: 0.0

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
                            currentPrice = price,
                            change24h = change,
                            volume24h = vol
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

    private fun extractBaseSymbol(symbol: String): String =
        symbol.uppercase()
            .removeSuffix("_IDR")
            .removeSuffix("_USDT")
            .removeSuffix("IDR")
            .removeSuffix("USDT")
            .trim()
}
