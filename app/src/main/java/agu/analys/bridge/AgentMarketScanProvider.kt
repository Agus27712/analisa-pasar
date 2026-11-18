package agu.analys.bridge

import android.content.Context
import agu.analys.config.MarketDataSource
import agu.analys.data.IndodaxSymbolRepository
import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.MarketTick
import agu.analys.service.IndodaxMarketService
import agu.analys.service.TokocryptoMarketService
import agu.analys.util.AppPreferences
import agu.analys.util.DashboardRanking
import agu.analys.util.ExchangeRateManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Penyedia pemindaian seluruh pair pasar dan pemilihan 1 pair oleh agen.
 *
 * Aturan ketat yang dijaga di berkas ini:
 * 1. **Isolasi bursa aktif**: pemindaian hanya memproses bursa yang sedang aktif di
 *    `AppPreferences.marketDataSource` (TOKOCRYPTO saja atau INDODAX saja), tidak pernah
 *    mencampur dua bursa. Jika bursa aktif berubah di tengah pemindaian, hasil lama dibuang.
 * 2. **Pilihan agen tunggal (maksimal 1 pair)**: [selectPair] menolak input kosong/multi-pair
 *    dan memvalidasi pair terhadap listing bursa aktif. Pemilihan hanya menandai pair di
 *    Dashboard (`selection` StateFlow) tanpa mengubah/memindah layar Detail Coin.
 */
object AgentMarketScanProvider {

    private const val CACHE_TTL_MS = 20_000L
    private const val DEEP_ANALYSIS_CACHE_TTL_MS = 20_000L
    private const val DEFAULT_DEEP_EVAL = 6
    private const val MAX_DEEP_EVAL = 10

    private data class CachedScan(
        val ts: Long,
        val output: AgentMarketScanOutput
    )

    private data class CachedDeepAnalysis(
        val ts: Long,
        val analysis: AgentEngineAnalysis
    )

    private val cacheByExchange = ConcurrentHashMap<String, CachedScan>()
    private val deepAnalysisCache = ConcurrentHashMap<String, CachedDeepAnalysis>()

    private val _selection = MutableStateFlow<AgentPairSelection?>(null)
    val selection: StateFlow<AgentPairSelection?> = _selection.asStateFlow()

    @Volatile
    private var prefsCache: AppPreferences? = null

    private fun prefsFor(context: Context): AppPreferences {
        prefsCache?.let { return it }
        return synchronized(this) {
            prefsCache ?: AppPreferences(context.applicationContext).also { prefsCache = it }
        }
    }

    fun currentSelection(): AgentPairSelection? = _selection.value

    /**
     * Bersihkan cache bursa lama dan kosongkan pilihan agen jika bursa pilihan tidak cocok
     * dengan bursa aktif terbaru.
     */
    fun onExchangeChanged(newActiveExchange: String) {
        val normEx = newActiveExchange.trim().uppercase()
        cacheByExchange.keys.filter { it != normEx }.forEach { cacheByExchange.remove(it) }
        deepAnalysisCache.keys.filter { !it.startsWith("$normEx|") }.forEach { deepAnalysisCache.remove(it) }
        val current = _selection.value
        if (current != null && !current.exchange.equals(normEx, ignoreCase = true)) {
            _selection.value = null
        }
    }

    /** Hapus cache scan/analisis agar perubahan mode strategi segera tercermin pada push berikutnya. */
    fun invalidateScanCache() {
        cacheByExchange.clear()
        deepAnalysisCache.clear()
    }

    /**
     * Pindai seluruh pair **hanya pada bursa yang sedang aktif**.
     *
     * @param requestedExchange jika diisi dan berbeda dengan bursa aktif, permintaan ditolak
     *                          (`available = false`) agar data antar bursa tidak pernah tercampur.
     */
    suspend fun scanActiveExchange(
        context: Context,
        requestedExchange: String? = null,
        quoteFilter: String? = null,
        limit: Int = 0,
        deepEvalCount: Int = DEFAULT_DEEP_EVAL,
        forceRefresh: Boolean = false
    ): AgentMarketScanOutput {
        val prefs = prefsFor(context)
        val activeSource = prefs.marketDataSource
        val activeName = activeSource.name

        if (!requestedExchange.isNullOrBlank() &&
            !requestedExchange.trim().equals(activeName, ignoreCase = true)
        ) {
            return AgentMarketScanOutput(
                ts = System.currentTimeMillis(),
                available = false,
                exchange = activeName,
                error = "Bursa aktif saat ini adalah $activeName; permintaan ke '${requestedExchange.trim().uppercase()}' ditolak agar data antar bursa tidak tercampur."
            )
        }

        val now = System.currentTimeMillis()
        val cached = cacheByExchange[activeName]
        val fullScan = if (!forceRefresh && cached != null && now - cached.ts < CACHE_TTL_MS && cached.output.available) {
            cached.output
        } else {
            runFreshScan(context, activeSource).also { fresh ->
                if (fresh.available && prefs.marketDataSource == activeSource) {
                    cacheByExchange[activeName] = CachedScan(System.currentTimeMillis(), fresh)
                }
            }
        }

        if (!fullScan.available) return fullScan

        // Pastikan setelah scan selesai bursa aktif belum berpindah
        if (prefs.marketDataSource != activeSource) {
            return AgentMarketScanOutput(
                ts = System.currentTimeMillis(),
                available = false,
                exchange = prefs.marketDataSource.name,
                error = "Bursa aktif berpindah selama pemindaian; hasil $activeName dibuang."
            )
        }

        val qFilter = quoteFilter?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "ALL" }
        val filteredPairs = if (qFilter == null) {
            fullScan.pairs
        } else {
            fullScan.pairs.filter { it.quoteAsset.equals(qFilter, ignoreCase = true) }
        }
        val cappedPairs = if (limit > 0) filteredPairs.take(limit) else filteredPairs

        val requestedDeep = deepEvalCount.coerceIn(0, MAX_DEEP_EVAL)
        val filteredCandidates = if (qFilter == null) {
            fullScan.topCandidates
        } else {
            fullScan.topCandidates.filter { it.quoteAsset.equals(qFilter, ignoreCase = true) }
        }.take(requestedDeep)

        return fullScan.copy(
            topCandidates = filteredCandidates,
            pairs = cappedPairs
        )
    }

    /**
     * Pindai langsung di memori dari snapshot ticker live (`MarketDataCoordinator.dashboardTicks`)
     * tanpa melakukan request HTTP ticker seluruh pasar. Evaluasi Tahap 2 memakai ulang cache
     * analisis (<20 detik) agar responsif secara event-driven namun tetap hemat jaringan.
     */
    suspend fun scanFromLiveTicks(
        context: Context,
        expectedExchange: String,
        liveTicks: Collection<MarketTick>
    ): AgentMarketScanOutput {
        val prefs = prefsFor(context)
        val activeSource = prefs.marketDataSource
        if (!activeSource.name.equals(expectedExchange.trim(), ignoreCase = true)) {
            return AgentMarketScanOutput(
                ts = System.currentTimeMillis(),
                available = false,
                exchange = activeSource.name,
                error = "Tick live berasal dari ${expectedExchange.uppercase()}, sedangkan bursa aktif adalah ${activeSource.name}."
            )
        }
        return try {
            if (!ExchangeRateManager.isRateAvailable()) {
                ExchangeRateManager.refresh()
            }
            val usdtIdrRate = ExchangeRateManager.currentRate()
            val filteredTicks = filterRankableTicks(activeSource, liveTicks)
            buildScanFromTicks(context, activeSource, filteredTicks, usdtIdrRate).also { scan ->
                if (scan.available && prefs.marketDataSource == activeSource) {
                    cacheByExchange[activeSource.name] = CachedScan(System.currentTimeMillis(), scan)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AgentMarketScanOutput(
                ts = System.currentTimeMillis(),
                available = false,
                exchange = activeSource.name,
                error = "Pemindaian live ${activeSource.name} gagal: ${t.javaClass.simpleName}: ${t.message ?: "tanpa pesan"}"
            )
        }
    }

    private fun filterRankableTicks(source: MarketDataSource, rawTicks: Collection<MarketTick>): List<MarketTick> {
        return when (source) {
            MarketDataSource.TOKOCRYPTO -> rawTicks
                .distinctBy { it.symbol.uppercase().replace("_", "") }
                .filter { DashboardRanking.isRankable(MarketDataSource.TOKOCRYPTO, it) }
            MarketDataSource.INDODAX -> rawTicks
                .distinctBy { it.symbol.uppercase().replace("_", "") }
                .filter {
                    DashboardRanking.symbolListed(MarketDataSource.INDODAX, it.symbol) &&
                        DashboardRanking.isRankable(MarketDataSource.INDODAX, it)
                }
        }
    }

    private suspend fun runFreshScan(
        context: Context,
        source: MarketDataSource
    ): AgentMarketScanOutput {
        return try {
            val nowMs = System.currentTimeMillis()
            if (!ExchangeRateManager.isRateAvailable()) {
                ExchangeRateManager.refresh()
            }
            val usdtIdrRate = ExchangeRateManager.currentRate()

            val ticks: List<MarketTick> = when (source) {
                MarketDataSource.TOKOCRYPTO -> {
                    val rankings = TokocryptoMarketService.fetchMarketRankings(limit = 500)
                    filterRankableTicks(MarketDataSource.TOKOCRYPTO, rankings.allTicks.values)
                }
                MarketDataSource.INDODAX -> {
                    IndodaxSymbolRepository.ensurePairsLoaded(false)
                    filterRankableTicks(
                        MarketDataSource.INDODAX,
                        IndodaxMarketService.fetchAllMarketTicks().values
                    )
                }
            }

            if (ticks.isEmpty()) {
                return AgentMarketScanOutput(
                    ts = nowMs,
                    available = false,
                    exchange = source.name,
                    usdtIdrRate = usdtIdrRate,
                    error = "Data ticker pasar ${source.name} belum tersedia dari bursa."
                )
            }

            buildScanFromTicks(context, source, ticks, usdtIdrRate)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AgentMarketScanOutput(
                ts = System.currentTimeMillis(),
                available = false,
                exchange = source.name,
                error = "Pemindaian pasar ${source.name} gagal: ${t.javaClass.simpleName}: ${t.message ?: "tanpa pesan"}"
            )
        }
    }

    private suspend fun buildScanFromTicks(
        context: Context,
        source: MarketDataSource,
        ticks: List<MarketTick>,
        usdtIdrRate: Double
    ): AgentMarketScanOutput {
        val nowMs = System.currentTimeMillis()
        if (ticks.isEmpty()) {
            return AgentMarketScanOutput(
                ts = nowMs,
                available = false,
                exchange = source.name,
                usdtIdrRate = usdtIdrRate,
                error = "Data ticker pasar ${source.name} kosong."
            )
        }

        val tickBySymbol = ticks.associateBy {
            AgentEngineAnalysisMapper.normalizeSymbol(it.symbol) ?: it.symbol.uppercase()
        }
        val scoredPairs = AgentMarketScanner.scoreAllPairs(
            exchange = source.name,
            ticks = ticks,
            usdtIdrRate = usdtIdrRate
        )

        if (scoredPairs.isEmpty()) {
            return AgentMarketScanOutput(
                ts = nowMs,
                available = false,
                exchange = source.name,
                usdtIdrRate = usdtIdrRate,
                error = "Tidak ada pair layak yang lolos filter kelayakan ${source.name}."
            )
        }

        // Tahap 2: evaluasi mendalam `ModeSignalEvaluator` pada kandidat terbaik
        // yang tidak overextended (memanfaatkan cache 20 detik agar push live cepat & hemat API).
        val deepTargets = scoredPairs
            .filter { it.momentumTier != "OVEREXTENDED" }
            .ifEmpty { scoredPairs }
            .take(DEFAULT_DEEP_EVAL)

        val sem = Semaphore(3)
        val evaluatedCandidates = coroutineScope {
            deepTargets.mapIndexed { idx, item ->
                async {
                    val cacheKey = "${source.name}|${item.symbol}"
                    val cachedDeep = deepAnalysisCache[cacheKey]
                        ?.takeIf { System.currentTimeMillis() - it.ts < DEEP_ANALYSIS_CACHE_TTL_MS }
                        ?.analysis
                        ?.copy(price = item.price)

                    val analysis = cachedDeep ?: sem.withPermit {
                        runCatching {
                            AgentEngineAnalysisProvider.evaluateWithKnownTick(
                                context = context,
                                norm = item.symbol,
                                source = source,
                                prefetchedTick = tickBySymbol[item.symbol]
                            )
                        }.getOrNull()?.also { freshAnalysis ->
                            if (freshAnalysis.available) {
                                deepAnalysisCache[cacheKey] = CachedDeepAnalysis(
                                    ts = System.currentTimeMillis(),
                                    analysis = freshAnalysis
                                )
                            }
                        }
                    }

                    AgentMarketScanCandidate(
                        rank = idx + 1,
                        symbol = item.symbol,
                        baseAsset = item.baseAsset,
                        quoteAsset = item.quoteAsset,
                        exchange = source.name,
                        price = item.price,
                        change24h = item.change24h,
                        volume24h = item.volume24h,
                        volumeIdr = item.volumeIdr,
                        rangePositionPct = item.rangePositionPct,
                        serverScore = item.serverScore,
                        momentumTier = item.momentumTier,
                        reasonSummary = item.reasonSummary,
                        engineAnalysis = analysis
                    )
                }
            }.awaitAll()
        }

        val bullishCount = scoredPairs.count {
            it.momentumTier == "STRONG_UP" || it.momentumTier == "BUILDING_UP"
        }
        val topDesc = evaluatedCandidates.take(3).joinToString(", ") {
            val bias = it.engineAnalysis?.overallBias ?: it.momentumTier
            "${it.symbol} (skor ${it.serverScore}, $bias)"
        }
        val summary = "${source.name}: ${scoredPairs.size} pair dipindai, $bullishCount berbias naik. Teratas: $topDesc"

        return AgentMarketScanOutput(
            ts = System.currentTimeMillis(),
            available = true,
            exchange = source.name,
            usdtIdrRate = usdtIdrRate,
            totalPairsScanned = scoredPairs.size,
            bullishCount = bullishCount,
            marketSummary = summary,
            topCandidates = evaluatedCandidates,
            pairs = scoredPairs
        )
    }

    /**
     * Tetapkan **tepat 1 pair** pilihan agen untuk ditandai di Dashboard (tanpa memindah Detail Coin).
     */
    fun selectPair(
        context: Context,
        rawSymbol: String,
        reason: String? = null
    ): AgentPairSelectionResponse {
        val prefs = prefsFor(context)
        val activeSource = prefs.marketDataSource
        val norm = AgentMarketScanner.parseSingleSymbolInput(rawSymbol)
            ?: return AgentPairSelectionResponse(
                success = false,
                message = "Input pilihan agen harus tepat 1 simbol pair yang valid (tidak boleh kosong atau lebih dari 1 pair): '$rawSymbol'."
            )

        val quote = AgentEngineAnalysisMapper.quoteOf(norm)
        if (activeSource == MarketDataSource.INDODAX && quote != "IDR") {
            return AgentPairSelectionResponse(
                success = false,
                message = "Indodax hanya melayani pair IDR; '$norm' ditolak pada bursa aktif ${activeSource.name}."
            )
        }

        if (!DashboardRanking.symbolListed(activeSource, norm)) {
            return AgentPairSelectionResponse(
                success = false,
                message = "Pair '$norm' tidak terdaftar/aktif di bursa ${activeSource.name}."
            )
        }

        val selection = AgentPairSelection(
            symbol = norm,
            exchange = activeSource.name,
            reason = reason?.trim()?.takeIf { it.isNotBlank() },
            selectedAt = System.currentTimeMillis(),
            cleared = false
        )
        _selection.value = selection
        AgentOutputBridge.getInstance(context).emitAgentSelection(selection)
        return AgentPairSelectionResponse(
            success = true,
            message = "Pair pilihan agen disetel ke $norm (${activeSource.name}) dan ditandai di Dashboard.",
            selection = selection
        )
    }

    /** Hapus tanda pilihan pair agen dari Dashboard. */
    fun clearPair(context: Context): AgentPairSelectionResponse {
        val activeName = prefsFor(context).marketDataSource.name
        _selection.value = null
        val clearedEvent = AgentPairSelection(
            symbol = "",
            exchange = activeName,
            reason = null,
            selectedAt = System.currentTimeMillis(),
            cleared = true
        )
        AgentOutputBridge.getInstance(context).emitAgentSelection(clearedEvent)
        return AgentPairSelectionResponse(
            success = true,
            message = "Pilihan pair agen di Dashboard dikosongkan.",
            selection = null
        )
    }
}
