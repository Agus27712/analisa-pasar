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

/**
 * AI insight via Groq — fokus narasi pair, bukan dump matematis.
 * Model ringan biar cepat; max_tokens dibatasi.
 */
object GroqAiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    /** Cepat & cukup pintar untuk insight singkat. 120B terlalu lambat. */
    private const val MODEL = "llama-3.3-70b-versatile"
    private const val MAX_TOKENS = 420

    suspend fun generateDeepMarketAudit(
        apiKey: String,
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState
    ): String = withContext(Dispatchers.IO) {
        val cpi = if (safeContextReady) BpsMacroService(AppContextProvider.context).getLatest() else null
        if (apiKey.isBlank()) {
            return@withContext buildFallback(tick, indicators, signal, cpi) +
                "\n\n⚠️ Groq API key belum di-set. Buka Settings dan isi Groq API key."
        }

        val base = extractBase(tick.symbol)
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
Kamu asisten trading spot Indodax yang ngobrol santai tapi tajam.
Tugas: kasih INSIGHT soal pair yang dipantau user — bukan hafalan indikator.

Aturan:
- Bahasa Indonesia, singkat, manusiawi (bukan robot angka).
- Fokus: kenapa pair ini bergerak, identitas/asal-usul koin, hubungan ekosistem, risiko.
- Angka teknikal cuma pendukung 1 baris, jangan jadi isi utama.
- Jangan mengaku punya berita live real-time. Kalau spekulasi alasan naik/turun, bilang "kemungkinan" / "biasanya".
- Jangan menjamin profit. Max ~120 kata.
        """.trimIndent()

        val userPrompt = """
Pair: ${tick.symbol} (base: $base)
Identitas: ${pairCtx.label}
Ekosistem / hubungan: ${pairCtx.ecosystem}
Narasi umum: ${pairCtx.narrative}

Gerakan 24j: $move (${PriceFormatter.formatPercentage(tick.change24h)})
Harga: ${PriceFormatter.formatPrice(tick.price)} | Vol: ${PriceFormatter.formatVolume(tick.volume24h)}
Teknikal ringkas: $trendHint, $rsiHint, sinyal engine ${signal.action.name} (${signal.confidence}/100)

Jawab dengan format tepat ini:
1. 🔎 Apa ini: [1 kalimat identitas + kenapa relevan dipantau]
2. 📈 Kenapa gerak: [alasan paling masuk akal untuk arah 24j — korelasi BTC/ecosystem/volume/sentiment, pakai "kemungkinan"]
3. 🔗 Hubungan: [pair ini "ikut" apa — BTC, SOL eco, meme flow, dll]
4. 💡 Insight pantau: [1 saran praktis spot Indodax, tanpa dump angka TP/SL]
        """.trimIndent()

        try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("temperature", 0.45)
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
                if (!resp.isSuccessful) return@withContext buildFallback(tick, indicators, signal, cpi)
                val text = JSONObject(responseBody)
                    .optJSONArray("choices")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
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
        else -> "relatif flat / sideways"
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
        val trend = when {
            indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 -> "struktur chart lagi bullish"
            indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 -> "struktur chart lagi bearish"
            else -> "chart lagi konsolidasi"
        }
        return """
🔎 Apa ini: ${ctx.label}. ${ctx.narrative}

📈 Kenapa gerak: 24j $move (${PriceFormatter.formatPercentage(tick.change24h)}). $trend; volume ${PriceFormatter.formatVolume(tick.volume24h)}. Tanpa berita live, gerakan seperti ini biasanya ikut aliran ${ctx.ecosystem}.

🔗 Hubungan: ${ctx.ecosystem}

💡 Insight pantau: Pantau korelasi dengan BTC dulu. Sinyal engine ${signal.action.name} (${signal.confidence}/100) — pakai limit maker 0.21%, jangan FOMO candle tunggal.
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try {
            AppContextProvider.context; true
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
}

/** Konteks identitas pair — biar AI/fallback nggak cuma ngomong RSI. */
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
                "Naik sering karena hype ekosistem (meme, DeFi, atau narrative speed/cheap fees), bukan cuma chart."
            )
            "bnb" -> Ctx(
                "BNB — token ekosistem Binance / BNB Chain",
                "Ikut BTC + sentimen CEX/Binance",
                "Gerakan BNB sering terkait aktivitas on-chain BNB Chain dan berita exchange."
            )
            "xrp", "ripple" -> Ctx(
                "XRP — fokus pembayaran / cross-border",
                "Sensitif berita regulasi & kemitraan payment",
                "Bukan murni ‘ikut BTC’; sering loncat karena headline legal/partnership."
            )
            "ada", "cardano" -> Ctx(
                "Cardano — L1 research-driven",
                "Alt L1; gerak lebih lambat vs SOL/ETH",
                "Narrative-nya upgrade & adoption, jarang pure meme pump."
            )
            "doge", "shib", "pepe", "floki", "bonk", "wif", "bome" -> Ctx(
                "Meme coin — gerak didorong social & spekulasi",
                "Sangat sensitif BTC risk-on + hype Twitter/KOL",
                "Receh/meme biasanya ‘ikut angin’: volume sosial naik → volatilitas meledak, fundamental tipis."
            )
            "matic", "pol", "polygon" -> Ctx(
                "Polygon — scaling Ethereum",
                "Ikut ETH + narrative L2",
                "Gerak sering selaras ekosistem Ethereum, bukan independent."
            )
            "avax" -> Ctx(
                "Avalanche — L1 subnet / DeFi",
                "Alt L1 risk-on, korelasi BTC & DeFi season",
                "Naik biasanya saat risk appetite alt L1 meningkat."
            )
            "dot", "atom", "near", "sui", "apt", "sei", "tia" -> Ctx(
                "Alt L1 / modular chain",
                "Kompetisi L1; ikut siklus risk-on alt",
                "Gerakan sering soal narrative tech + rotasi modal dari BTC/ETH ke alt L1."
            )
            "link", "aave", "uni", "crv", "mkr", "ldo" -> Ctx(
                "Token DeFi / infrastruktur",
                "Ikut ETH + total value locked / fee narrative",
                "Naik saat DeFi season atau utility on-chain lagi ramai."
            )
            "rndr", "fet", "tao", "akt", "wld" -> Ctx(
                "Narrative AI / compute",
                "Sektor tematik AI; bisa lepas sementara dari BTC",
                "Gerak sering karena hype AI sector, bukan cuma teknikal pair."
            )
            "trx" -> Ctx(
                "TRON — fokus stablecoin & transfer murah",
                "On-chain USDT activity; kurang ‘hype L1’",
                "Stabilitas transfer & stablecoin flow lebih relevan dari meme narrative."
            )
            else -> Ctx(
                "Altcoin di pair IDR Indodax",
                "Umumnya ikut BTC; receh lebih volatil & sensitif volume lokal",
                "Cek dulu: ini ikut BTC, ikut sektor (L1/meme/DeFi), atau volume spekulatif Indodax saja."
            )
        }
    }
}
