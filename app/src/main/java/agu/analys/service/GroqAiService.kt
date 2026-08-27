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

/** AI insight via Groq — narasi pair + headline (output wajib Bahasa Indonesia). */
object GroqAiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "llama-3.3-70b-versatile"
    private const val MAX_TOKENS = 520

    suspend fun generateDeepMarketAudit(
        apiKey: String,
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState
    ): String = withContext(Dispatchers.IO) {
        val cpi = if (safeContextReady) BpsMacroService(AppContextProvider.context).getLatest() else null
        val base = extractBase(tick.symbol)
        val headlines = runCatching { CryptoHeadlineService.snapshotForBase(base) }.getOrNull()
        val headlineBlock = headlines?.promptBlock() ?: "Headline: tidak tersedia."

        if (apiKey.isBlank()) {
            return@withContext buildFallback(tick, indicators, signal, cpi, headlineBlock) +
                "\n\n⚠️ Groq API key belum di-set. Buka Settings dan isi Groq API key."
        }

        val pairCtx = PairNarrative.forBase(base)
        val move = describeMove(tick.change24h)
        val rsiHint = when {
            indicators.rsi14 < 30 -> "RSI oversold"
            indicators.rsi14 > 70 -> "RSI overbought"
            else -> "RSI netral"
        }
        val trendHint = when {
            indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 -> "struktur bullish (harga di atas EMA)"
            indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 -> "struktur bearish (harga di bawah EMA)"
            else -> "sideways / konsolidasi"
        }

        val systemPrompt = """
Kamu asisten trading spot Indodax.
SELURUH jawaban WAJIB Bahasa Indonesia (termasuk kutipan headline).
Kalau headline sumbernya bahasa Inggris, TERJEMAHKAN ke Indonesia dulu, baru hubungkan ke pergerakan harga.
Jangan biarkan kalimat Inggris utuh di jawaban user.
Fokus insight, bukan dump angka. Max ~140 kata. Jangan jamin profit.
        """.trimIndent()

        val userPrompt = """
Pair: ${tick.symbol} (base: $base)
Identitas: ${pairCtx.label}
Ekosistem / hubungan: ${pairCtx.ecosystem}
Narasi umum: ${pairCtx.narrative}

Gerakan 24j: $move (${PriceFormatter.formatPercentage(tick.change24h)})
Harga: ${PriceFormatter.formatPrice(tick.price)} | Vol: ${PriceFormatter.formatVolume(tick.volume24h)}
Teknikal ringkas: $trendHint, $rsiHint, sinyal engine ${signal.action.name} (${signal.confidence}/100)

$headlineBlock

Jawab format ini (semua Bahasa Indonesia):
1. 🔎 Apa ini: ...
2. 📰 Headline & alasan gerak: [terjemahkan headline, lalu hubungkan ke arah harga]
3. 🔗 Hubungan: ...
4. 💡 Insight pantau: ...
        """.trimIndent()

        try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("temperature", 0.35)
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
                .url(BASE_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext buildFallback(tick, indicators, signal, cpi, headlineBlock)
                val text = JSONObject(responseBody)
                    .optJSONArray("choices")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                if (text.isNotBlank()) text.trim() else buildFallback(tick, indicators, signal, cpi, headlineBlock)
            }
        } catch (_: Exception) {
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
        else -> "relatif flat / sideways"
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
        val trend = when {
            indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 -> "struktur chart lagi bullish"
            indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 -> "struktur chart lagi bearish"
            else -> "chart lagi konsolidasi"
        }
        return """
🔎 Apa ini: ${ctx.label}. ${ctx.narrative}

📰 Headline & alasan gerak:
$headlineBlock
(Pergerakan 24 jam: $move / ${PriceFormatter.formatPercentage(tick.change24h)}. $trend; volume ${PriceFormatter.formatVolume(tick.volume24h)}. Jika headline masih Inggris, intinya hubungkan ke arah harga di atas.)

🔗 Hubungan: ${ctx.ecosystem}

💡 Insight pantau: Pantau BTC dulu. Sinyal engine ${signal.action.name} (${signal.confidence}/100) — pakai limit maker 0.21%, jangan FOMO satu candle.
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try {
            AppContextProvider.context; true
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
}

internal object PairNarrative {
    data class Ctx(val label: String, val ecosystem: String, val narrative: String)

    fun forBase(base: String): Ctx {
        val b = base.lowercase()
        return when (b) {
            "btc", "bitcoin" -> Ctx(
                "Bitcoin — aset kripto utama / digital gold",
                "Acuan seluruh market; altcoin sering ikut arah BTC",
                "Kalau BTC gerak kuat, hampir semua pair IDR ikut bergoyang."
            )
            "eth", "ethereum" -> Ctx(
                "Ethereum — L1 smart contract terbesar",
                "Ikut BTC + aliran DeFi/L2 (Arbitrum, Base, dll)",
                "ETH sering jadi jembatan risk-on setelah BTC stabil."
            )
            "sol", "solana" -> Ctx(
                "Solana — L1 cepat, ekosistem meme/DeFi/NFT aktif",
                "Cenderung risk-on; sensitif sentimen meme + throughput network",
                "Naik sering karena hype ekosistem (meme, DeFi, atau narrative biaya murah/cepat), bukan cuma chart."
            )
            "bnb" -> Ctx(
                "BNB — token ekosistem Binance / BNB Chain",
                "Ikut BTC + sentimen CEX/Binance",
                "Gerakan BNB sering terkait aktivitas on-chain BNB Chain dan berita exchange."
            )
            "xrp", "ripple" -> Ctx(
                "XRP — fokus pembayaran / lintas negara",
                "Sensitif berita regulasi & kemitraan payment",
                "Bukan murni ikut BTC; sering loncat karena headline legal/partnership."
            )
            "ada", "cardano" -> Ctx(
                "Cardano — L1 berbasis riset",
                "Alt L1; gerak lebih lambat vs SOL/ETH",
                "Narasinya upgrade & adopsi, jarang pure meme pump."
            )
            "doge", "shib", "pepe", "floki", "bonk", "wif", "bome" -> Ctx(
                "Koin meme — didorong sosial & spekulasi",
                "Sangat sensitif risk-on BTC + hype medsos/KOL",
                "Receh/meme biasanya ikut angin: volume sosial naik → volatilitas meledak."
            )
            "matic", "pol", "polygon" -> Ctx(
                "Polygon — scaling Ethereum",
                "Ikut ETH + narasi L2",
                "Gerak sering selaras ekosistem Ethereum."
            )
            "avax" -> Ctx(
                "Avalanche — L1 subnet / DeFi",
                "Alt L1 risk-on, korelasi BTC & musim DeFi",
                "Naik biasanya saat risk appetite alt L1 meningkat."
            )
            "dot", "atom", "near", "sui", "apt", "sei", "tia" -> Ctx(
                "Alt L1 / rantai modular",
                "Kompetisi L1; ikut siklus risk-on alt",
                "Sering soal narasi tech + rotasi modal dari BTC/ETH."
            )
            "link", "aave", "uni", "crv", "mkr", "ldo" -> Ctx(
                "Token DeFi / infrastruktur",
                "Ikut ETH + TVL / fee on-chain",
                "Naik saat musim DeFi atau utilitas on-chain ramai."
            )
            "rndr", "fet", "tao", "akt", "wld" -> Ctx(
                "Narasi AI / compute",
                "Sektor tematik AI; bisa lepas sementara dari BTC",
                "Gerak sering karena hype sektor AI, bukan cuma teknikal pair."
            )
            "trx" -> Ctx(
                "TRON — fokus stablecoin & transfer murah",
                "Aktivitas on-chain USDT",
                "Stabilitas transfer & aliran stablecoin lebih relevan dari hype meme."
            )
            else -> Ctx(
                "Altcoin di pair IDR Indodax",
                "Umumnya ikut BTC; receh lebih volatil & sensitif volume lokal",
                "Cek dulu: ikut BTC, ikut sektor, atau spekulasi volume Indodax saja."
            )
        }
    }
}
