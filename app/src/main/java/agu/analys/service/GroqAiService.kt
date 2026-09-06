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

/** AI insight via Groq — narasi pair + headline (output wajib Bahasa Indonesia). */
object GroqAiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "qwen/qwen3.8-27b"
    private val FALLBACK_MODELS = listOf("qwen/qwen3.8-27b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b")
    private const val MAX_TOKENS = 750

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
        val rsiFormatted = if (indicators.rsi14.isFinite()) String.format(java.util.Locale.US, "%.1f", indicators.rsi14) else "—"
        val rsiExplanation = when {
            !indicators.rsi14.isFinite() -> "Data RSI belum cukup"
            indicators.rsi14 > 70 -> "**Jenuh Beli (Overbought)** ($rsiFormatted) — Tekanan beli mencapai titik puncak, waspada potensi koreksi / taking profit"
            indicators.rsi14 < 30 -> "**Jenuh Jual (Oversold)** ($rsiFormatted) — Tekanan jual klimaks, peluang pantulan teknikal / rebound menguat"
            indicators.rsi14 >= 50 -> "**Netral Bullish** ($rsiFormatted) — Momentum beli aktif dan terukur"
            else -> "**Netral Bearish** ($rsiFormatted) — Tekanan jual moderat dalam batas aman"
        }
        val trendExplanation = when {
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 ->
                "**Struktur Bullish Kuat** (Harga berada di atas EMA 20 & EMA 50)"
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 ->
                "**Struktur Bearish Kuat** (Harga tertekan di bawah EMA 20 & EMA 50)"
            else -> "**Konsolidasi / Sideways** (EMA berhimpit)"
        }
        val macdExplanation = when {
            !indicators.macdHist.isFinite() -> "Data MACD belum cukup"
            indicators.macdHist > 0 -> "**Histogram Positif (Bullish Momentum)** — Volume dorongan beli aktif"
            indicators.macdHist < 0 -> "**Histogram Negatif (Bearish Momentum)** — Tekanan jual mendominasi"
            else -> "**Netral** — Momentum berimbang"
        }
        val atrExplanation = if (indicators.atr.isFinite() && indicators.atr > 0) {
            "Rentang fluktuasi candle rata-rata: ${PriceFormatter.formatPrice(indicators.atr)}"
        } else "Volatilitas normal"

        val systemPrompt = """
Kamu asisten quantitative & technical analyst spot Indodax.
SELURUH jawaban WAJIB Bahasa Indonesia (termasuk kutipan berita/headline).
Terjemahkan headline Inggris ke Bahasa Indonesia dulu, lalu hubungkan ke pergerakan harga.
Gunakan format Markdown terstruktur dengan poin-poin bullet (-), teks tebal (**bold**), dan judul bab (###).
Fokus insight tajam, edukatif, dan praktis. Maksimal ~1379 kata.
        """.trimIndent()

        val userPrompt = """
Pair: ${tick.symbol} (base: $base)
Identitas: ${pairCtx.label}
Ekosistem: ${pairCtx.ecosystem}
Narasi: ${pairCtx.narrative}

Gerakan 24J: $move (${PriceFormatter.formatPercentage(tick.change24h)})
Harga: ${PriceFormatter.formatPrice(tick.price)} | Vol: ${PriceFormatter.formatVolume(tick.volume24h)}
Status Indikator:
- RSI (14): $rsiExplanation
- Tren EMA: $trendExplanation
- MACD: $macdExplanation
- ATR & Volatilitas: $atrExplanation
- Sinyal Engine: ${signal.action.name} (${signal.confidence}/100)

$headlineBlock

Wajib susun jawaban dalam format Markdown berikut:
### 🔎 1. Profil & Ekosistem Aset
- **Aset**: ...
- **Karakteristik & Korelasi**: ...

### 📊 2. Analisis Indikator Teknikal
- **RSI (14)**: [Jelaskan status Jenuh Beli / Overbought, Jenuh Jual / Oversold, atau Netral beserta implikasinya]
- **Tren EMA & MACD**: [Jelaskan arah struktur tren dan dorongan momentum]
- **Volatilitas**: [Kondisi volatilitas & batas risiko]

### 📰 3. Sentimen Pasar & Alasan Gerakan
- **Faktor Penggerak**: [Terjemahan & analisa berita/volume terhadap harga]

### 💡 4. Panduan Strategi & Action Plan
- **Sinyal Engine**: **${signal.action.name}** (Confidence: ${signal.confidence}/100)
- **Rekomendasi**: [Entry, TP/SL, disiplin limit order maker]
        """.trimIndent()

        for (modelName in FALLBACK_MODELS) {
            try {
                val payload = JSONObject().apply {
                    put("model", modelName)
                    put("temperature", 0.35)
                    put("max_tokens", MAX_TOKENS)
                    put("top_p", 0.9)
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
                    if (resp.isSuccessful) {
                        val msgObj = JSONObject(responseBody)
                            .optJSONArray("choices")
                            ?.takeIf { it.length() > 0 }
                            ?.getJSONObject(0)
                            ?.optJSONObject("message")
                        var text = msgObj?.optString("content").orEmpty().trim()
                        if (text.isBlank()) {
                            val reasoning = msgObj?.optString("reasoning").orEmpty().ifEmpty {
                                msgObj?.optString("reasoning_content").orEmpty()
                            }
                            if (reasoning.isNotBlank()) text = reasoning.trim()
                        }
                        if (text.isNotBlank()) return@withContext text
                    } else {
                        Timber.w("Groq model $modelName returned ${resp.code}: $responseBody")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Groq model $modelName request error")
            }
        }

        buildFallback(tick, indicators, signal, cpi, headlineBlock)
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
        val rsiFormatted = if (indicators.rsi14.isFinite()) String.format(java.util.Locale.US, "%.1f", indicators.rsi14) else "—"
        val rsiStatus = when {
            !indicators.rsi14.isFinite() -> "Netral (Data belum cukup)"
            indicators.rsi14 > 70 -> "**Jenuh Beli (Overbought)** ($rsiFormatted) — Tekanan beli mencapai batas atas jangka pendek, waspada koreksi / pullback"
            indicators.rsi14 < 30 -> "**Jenuh Jual (Oversold)** ($rsiFormatted) — Tekanan jual klimaks, potensi pantulan teknikal / akumulasi"
            indicators.rsi14 >= 50 -> "**Netral Bullish** ($rsiFormatted) — Tren beli stabil dalam batas aman"
            else -> "**Netral Bearish** ($rsiFormatted) — Tekanan jual moderat dalam batas aman"
        }
        val emaStatus = when {
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 ->
                "**Bullish Alignment** (Harga berada di atas EMA 20 & 50)"
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 ->
                "**Bearish Alignment** (Harga tertekan di bawah EMA 20 & 50)"
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
- **Tindakan**: Pantau konfirmasi arah BTC terlebih dahulu. Gunakan limit order maker 0.21%, hindari FOMO atau mengejar candle.
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
