package agu.analys.service

import android.content.Context
import android.text.Html
import agu.analys.model.IndonesiaCpiData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Public BPS macro pipeline without API credentials:
 * discovery -> resolve official BRS -> retrieve page -> validate -> cache.
 * CPI is context for AI only and never changes the on-device trading score.
 */
class BpsMacroService(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun getLatest(): IndonesiaCpiData? = withContext(Dispatchers.IO) {
        val cached = loadCache()
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt <= FRESH_CACHE_MS) return@withContext cached

        val articleUrl = discoverLatestBpsInflationUrl() ?: return@withContext cached
        val raw = retrieveArticle(articleUrl) ?: return@withContext cached
        val validated = validate(raw) ?: return@withContext cached
        saveCache(validated)
        validated
    }

    private data class RawResult(
        val period: String,
        val yoyPercent: Double?,
        val mtmPercent: Double?,
        val ytdPercent: Double?,
        val coreYoyPercent: Double?,
        val cpiIndex: Double?,
        val sourceUrl: String
    )

    private fun discoverLatestBpsInflationUrl(): String? {
        val query = URLEncoder.encode("inflasi Indonesia", "UTF-8")
        val url = "$ALLSTATS_URL?mfd=0000&q=$query&content=pressrelease&page=1&sort=terbaru&title=1"
        val html = requestText(url) ?: return null
        val pattern = Regex(
            "https?://(?:www\\.)?bps\\.go\\.id/(?:id/)?pressrelease/[^\\\"'<> ]+",
            RegexOption.IGNORE_CASE
        )
        return pattern.findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinct()
            .firstOrNull()
    }

    private fun retrieveArticle(url: String): RawResult? {
        val html = requestText(url) ?: return null
        val text = normalizeHtml(html)
        if (!text.contains("inflasi", ignoreCase = true) && !text.contains("consumer price", ignoreCase = true)) return null

        val yoy = firstPercent(text, listOf(
            Regex("(?:inflasi|inflation).*?([0-9]+(?:[.,][0-9]+)?)\\s*(?:persen|percent)", RegexOption.IGNORE_CASE)
        ))
        val cpi = firstNumber(text, listOf(
            Regex("(?:Indeks Harga Konsumen|IHK|Consumer Price Index|CPI).*?([0-9]{2,3}(?:[.,][0-9]{1,2})?)", RegexOption.IGNORE_CASE)
        ), 50.0, 300.0)
        val mtm = firstPercent(text, listOf(
            Regex("(?:month[- ]to[- ]month|month to month|m[- ]to[- ]m).*?([0-9]+(?:[.,][0-9]+)?)\\s*(?:persen|percent)", RegexOption.IGNORE_CASE)
        ))
        val ytd = firstPercent(text, listOf(
            Regex("(?:year[- ]to[- ]date|year to date|y[- ]to[- ]d).*?([0-9]+(?:[.,][0-9]+)?)\\s*(?:persen|percent)", RegexOption.IGNORE_CASE)
        ))
        val coreYoy = firstPercent(text, listOf(
            Regex("(?:inflasi|inflation).*?komponen inti.*?([0-9]+(?:[.,][0-9]+)?)\\s*(?:persen|percent)", RegexOption.IGNORE_CASE),
            Regex("(?:core component|core inflation).*?([0-9]+(?:[.,][0-9]+)?)\\s*(?:persen|percent)", RegexOption.IGNORE_CASE)
        ))
        val period = extractPeriod(text) ?: return null
        if (yoy == null && cpi == null) return null
        return RawResult(period, yoy, mtm, ytd, coreYoy, cpi, url)
    }

    private fun validate(raw: RawResult): IndonesiaCpiData? {
        val yoy = raw.yoyPercent?.takeIf { it.isFinite() && it in -20.0..50.0 }
        val mtm = raw.mtmPercent?.takeIf { it.isFinite() && it in -20.0..20.0 }
        val ytd = raw.ytdPercent?.takeIf { it.isFinite() && it in -20.0..50.0 }
        val core = raw.coreYoyPercent?.takeIf { it.isFinite() && it in -20.0..50.0 }
        val cpi = raw.cpiIndex?.takeIf { it.isFinite() && it in 50.0..300.0 }
        if (yoy == null && cpi == null) return null
        return IndonesiaCpiData(
            period = raw.period,
            yoyPercent = yoy ?: Double.NaN,
            mtmPercent = mtm,
            ytdPercent = ytd,
            coreYoyPercent = core,
            cpiIndex = cpi,
            source = "BPS official website",
            sourceUrl = raw.sourceUrl,
            fetchedAt = System.currentTimeMillis()
        )
    }

    private fun saveCache(data: IndonesiaCpiData) {
        prefs.edit().putString(KEY_DATA, JSONObject().apply {
            put("period", data.period)
            if (data.yoyPercent.isFinite()) put("yoy", data.yoyPercent)
            data.mtmPercent?.let { put("mtm", it) }
            data.ytdPercent?.let { put("ytd", it) }
            data.coreYoyPercent?.let { put("coreYoy", it) }
            data.cpiIndex?.let { put("cpiIndex", it) }
            put("source", data.source)
            put("sourceUrl", data.sourceUrl)
            put("fetchedAt", data.fetchedAt)
        }.toString()).apply()
    }

    private fun loadCache(): IndonesiaCpiData? {
        val raw = prefs.getString(KEY_DATA, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val fetchedAt = o.optLong("fetchedAt", 0L)
            if (fetchedAt <= 0L || System.currentTimeMillis() - fetchedAt > STALE_CACHE_MS) return null
            val yoy = o.optDouble("yoy", Double.NaN)
            val cpi = o.optDouble("cpiIndex", Double.NaN).takeIf { it.isFinite() }
            if (!yoy.isFinite() && cpi == null) return null
            IndonesiaCpiData(
                period = o.optString("period", "Cached"),
                yoyPercent = yoy,
                mtmPercent = o.optDouble("mtm", Double.NaN).takeIf { it.isFinite() },
                ytdPercent = o.optDouble("ytd", Double.NaN).takeIf { it.isFinite() },
                coreYoyPercent = o.optDouble("coreYoy", Double.NaN).takeIf { it.isFinite() },
                cpiIndex = cpi,
                source = o.optString("source", "BPS official website"),
                sourceUrl = o.optString("sourceUrl", BPS_PRESS_RELEASE_URL),
                fetchedAt = fetchedAt
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun requestText(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "Analysis-Ui/1.0")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string().orEmpty().takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }

    private fun normalizeHtml(html: String): String = Html.fromHtml(
        html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " "),
        Html.FROM_HTML_MODE_LEGACY
    ).toString().replace(Regex("\\s+"), " ").trim()

    private fun extractPeriod(text: String): String? {
        val patterns = listOf(
            Regex("(?:pada|bulan|in|on)\\s+(Januari|Februari|Maret|April|Mei|Juni|Juli|Agustus|September|Oktober|November|Desember)\\s+(20\\d{2})", RegexOption.IGNORE_CASE),
            Regex("(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(20\\d{2})", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.value }
    }

    private fun firstPercent(text: String, patterns: List<Regex>): Double? = patterns.firstNotNullOfOrNull { regex ->
        regex.find(text)?.groupValues?.lastOrNull()?.replace(',', '.')?.toDoubleOrNull()
    }

    private fun firstNumber(text: String, patterns: List<Regex>, min: Double, max: Double): Double? = patterns.firstNotNullOfOrNull { regex ->
        regex.find(text)?.groupValues?.lastOrNull()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in min..max }
    }

    companion object {
        private const val ALLSTATS_URL = "https://searchengine.web.bps.go.id/search"
        private const val BPS_PRESS_RELEASE_URL = "https://www.bps.go.id/id/pressrelease"
        private const val PREFS_NAME = "bps_macro_cache"
        private const val KEY_DATA = "latest_cpi"
        private const val FRESH_CACHE_MS = 24L * 60L * 60L * 1000L
        private const val STALE_CACHE_MS = 45L * 24L * 60L * 60L * 1000L
    }
}
