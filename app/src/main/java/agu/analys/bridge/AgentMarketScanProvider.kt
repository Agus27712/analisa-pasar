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

    private const val CACHE_TTL_MS = 45_000L
    private const val DEFAULT_DEEP_EVAL = 6
    private const val MAX_DEEP_EVAL = 10

    private data class CachedScan(
        val ts: Long,
        val output: AgentMarketScanOutput
    )

    private val cacheByExchange = ConcurrentHashMap<String, CachedScan>()

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
        val current = _selection.value
        if (current != null && !current.exchange.equals(normEx, ignoreCase = true)) {
            _selection.value = null
        }
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
                    rankings.allTicks.values
                        .filter { DashboardRanking.isRankable(MarketDataSource.TOKOCRYPTO, it) }
                }
                MarketDataSource.INDODAX -> {
                    IndodaxSymbolRepository.ensurePairsLoaded(false)
                    IndodaxMarketService.fetchAllMarketTicks().values
                        .distinctBy { it.symbol.uppercase().replace("_", "") }
                        .filter {
                            DashboardRanking.symbolListed(MarketDataSource.INDODAX, it.symbol) &&
                                DashboardRanking.isRankable(MarketDataSource.INDODAX, it)
                        }
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
            // yang tidak overextended (atau peringkat teratas jika semua sedang netral).
            val deepTargets = scoredPairs
                .filter { it.momentumTier != "OVEREXTENDED" }
                .ifEmpty { scoredPairs }
                .take(DEFAULT_DEEP_EVAL)

            val sem = Semaphore(3)
            val evaluatedCandidates = coroutineScope {
                deepTargets.mapIndexed { idx, item ->
                    async {
                        sem.withPermit {
                            val analysis = runCatching {
                                AgentEngineAnalysisProvider.evaluateWithKnownTick(
                                    context = context,
                                    norm = item.symbol,
                                    source = source,
                                    prefetchedTick = tickBySymbol[item.symbol]
                                )
                            }.getOrNull()
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

            AgentMarketScanOutput(
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
