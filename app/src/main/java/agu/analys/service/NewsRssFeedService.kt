package agu.analys.service

import agu.analys.model.NewsArticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Service agregasi RSS feed berita crypto global & Indonesia
 * Mengumpulkan headline katalis (upgrade, volume spike, partnership, breakout).
 */
object NewsRssFeedService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 menit
    private var cachedArticles: List<NewsArticle>? = null
    private var cacheTimestampMs: Long = 0L

    private val RSS_SOURCES = listOf(
        RssSource(
            name = "Decrypt",
            url = "https://decrypt.co/feed"
        ),
        RssSource(
            name = "BeInCrypto",
            url = "https://beincrypto.com/feed/"
        ),
        RssSource(
            name = "The Daily Hodl",
            url = "https://dailyhodl.com/feed/"
        ),
        RssSource(
            name = "Cointelegraph",
            url = "https://cointelegraph.com/rss/tag/market-analysis"
        ),
        RssSource(
            name = "U.Today",
            url = "https://u.today/rss"
        ),
        RssSource(
            name = "CoinDesk",
            url = "https://www.coindesk.com/arc/outboundfeeds/rss/"
        )
    )

    private data class RssSource(val name: String, val url: String)

    suspend fun fetchAggregatedNews(forceRefresh: Boolean = false): List<NewsArticle> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedArticles != null && (now - cacheTimestampMs) < CACHE_TTL_MS) {
            return@withContext cachedArticles.orEmpty()
        }

        val articlesBySource = coroutineScope {
            RSS_SOURCES.map { source ->
                async {
                    runCatching { fetchSingleRss(source) }.getOrElse {
                        Timber.w(it, "Gagal fetch RSS dari ${source.name}")
                        emptyList()
                    }
                }
            }.map { it.await() }
        }

        // Ambil secara berimbang dari setiap sumber (Round-Robin) agar tidak hanya 1 media saja
        val balancedList = mutableListOf<NewsArticle>()
        val maxPerSource = 5
        for (i in 0 until maxPerSource) {
            for (sourceList in articlesBySource) {
                if (i < sourceList.size) {
                    balancedList.add(sourceList[i])
                }
            }
        }

        val cleaned = deduplicateArticles(balancedList)
        if (cleaned.isNotEmpty()) {
            cachedArticles = cleaned
            cacheTimestampMs = now
        }
        cleaned.ifEmpty { cachedArticles.orEmpty() }
    }

    private fun fetchSingleRss(source: RssSource): List<NewsArticle> {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val xml = resp.body?.string().orEmpty()
            return parseRssXml(xml, source.name)
        }
    }

    private fun parseRssXml(xml: String, sourceName: String): List<NewsArticle> {
        val results = mutableListOf<NewsArticle>()
        val itemRegex = Regex("<item[\\s>]([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("<title><!\\[CDATA\\[(.*?)\\]\\]></title>|<title>(.*?)</title>", RegexOption.IGNORE_CASE)
        val linkRegex = Regex("<link><!\\[CDATA\\[(.*?)\\]\\]></link>|<link>(.*?)</link>", RegexOption.IGNORE_CASE)

        itemRegex.findAll(xml).take(8).forEach { match ->
            val block = match.groupValues.getOrNull(1).orEmpty()
            val tMatch = titleRegex.find(block)
            val lMatch = linkRegex.find(block)

            val rawTitle = tMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: tMatch?.groupValues?.getOrNull(2).orEmpty()
            val rawLink = lMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: lMatch?.groupValues?.getOrNull(2).orEmpty()

            val title = cleanTitle(rawTitle)
            if (title.length in 25..220 && !isSpammy(title)) {
                results.add(
                    NewsArticle(
                        title = title,
                        source = sourceName,
                        link = rawLink.trim()
                    )
                )
            }
        }
        return results
    }

    private fun deduplicateArticles(articles: List<NewsArticle>): List<NewsArticle> {
        val seen = mutableSetOf<String>()
        val output = mutableListOf<NewsArticle>()

        for (art in articles) {
            val key = art.title.lowercase().filter { it.isLetterOrDigit() }.take(40)
            if (key.length >= 15 && seen.add(key)) {
                output.add(art)
            }
            if (output.size >= 25) break
        }
        return output
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isSpammy(title: String): Boolean {
        val t = title.lowercase()
        val spamWords = listOf("casino", "slot", "togel", "judi", "porn", "giveaway", "airdrop claim", "free money")
        return spamWords.any { t.contains(it) }
    }
}
