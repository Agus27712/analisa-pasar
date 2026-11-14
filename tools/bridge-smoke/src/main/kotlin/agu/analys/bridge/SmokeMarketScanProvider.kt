package agu.analys.bridge

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * Stub smoke untuk `AgentMarketScanProvider` produksi.
 *
 * Menjaga tanda tangan fungsi, aturan isolasi bursa aktif (`TOKOCRYPTO` vs `INDODAX`),
 * dan validasi input tunggal (menolak input kosong/multi-pair) agar identik dengan produksi.
 */
object AgentMarketScanProvider {

    @Volatile
    private var activeExchange: String = "TOKOCRYPTO"

    @Volatile
    private var selection: AgentPairSelection? = null

    fun currentSelection(): AgentPairSelection? = selection

    suspend fun scanActiveExchange(
        context: Context,
        requestedExchange: String? = null,
        quoteFilter: String? = null,
        limit: Int = 0,
        deepEvalCount: Int = 6,
        forceRefresh: Boolean = false
    ): AgentMarketScanOutput {
        if (!requestedExchange.isNullOrBlank() &&
            !requestedExchange.trim().equals(activeExchange, ignoreCase = true)
        ) {
            return AgentMarketScanOutput(
                ts = System.currentTimeMillis(),
                available = false,
                exchange = activeExchange,
                error = "Bursa aktif saat ini adalah $activeExchange; permintaan ke '$requestedExchange' ditolak."
            )
        }
        val items = listOf(
            AgentMarketPairItem(
                symbol = "BTCUSDT",
                baseAsset = "BTC",
                quoteAsset = "USDT",
                price = 68500.0,
                change24h = 3.25,
                high24h = 69000.0,
                low24h = 66500.0,
                volume24h = 15000000.0,
                volumeIdr = 240_000_000_000.0,
                rangePositionPct = 80.0,
                serverScore = 88.5,
                momentumTier = "STRONG_UP",
                reasonSummary = "SMOKE_SCAN_FIXTURE"
            )
        ).let { list ->
            val q = quoteFilter?.trim()?.uppercase()
            val byQ = if (!q.isNullOrEmpty() && q != "ALL") list.filter { it.quoteAsset == q } else list
            if (limit > 0) byQ.take(limit) else byQ
        }
        val candidates = if (deepEvalCount > 0) {
            items.take(deepEvalCount).mapIndexed { i, it ->
                AgentMarketScanCandidate(
                    rank = i + 1,
                    symbol = it.symbol,
                    baseAsset = it.baseAsset,
                    quoteAsset = it.quoteAsset,
                    exchange = activeExchange,
                    price = it.price,
                    change24h = it.change24h,
                    volume24h = it.volume24h,
                    volumeIdr = it.volumeIdr,
                    rangePositionPct = it.rangePositionPct,
                    serverScore = it.serverScore,
                    momentumTier = it.momentumTier,
                    reasonSummary = it.reasonSummary,
                    engineAnalysis = null
                )
            }
        } else {
            emptyList()
        }
        return AgentMarketScanOutput(
            ts = System.currentTimeMillis(),
            available = true,
            exchange = activeExchange,
            usdtIdrRate = 16000.0,
            totalPairsScanned = items.size,
            bullishCount = items.size,
            marketSummary = "SMOKE_SCAN_FIXTURE ($activeExchange)",
            topCandidates = candidates,
            pairs = items
        )
    }

    fun selectPair(
        @Suppress("UNUSED_PARAMETER") context: Context,
        rawSymbol: String,
        reason: String? = null
    ): AgentPairSelectionResponse {
        val trimmed = rawSymbol.trim().uppercase()
        if (trimmed.isEmpty() || trimmed.any { it == ',' || it == ';' || it == '[' || it == ']' || it.isWhitespace() }) {
            return AgentPairSelectionResponse(
                success = false,
                message = "Input pilihan agen harus tepat 1 simbol pair: '$rawSymbol'"
            )
        }
        val sel = AgentPairSelection(
            symbol = trimmed,
            exchange = activeExchange,
            reason = reason,
            selectedAt = System.currentTimeMillis(),
            cleared = false
        )
        selection = sel
        runBlocking { AgentEventBus.emitAgentSelection(sel) }
        return AgentPairSelectionResponse(
            success = true,
            message = "Pair pilihan agen disetel ke $trimmed ($activeExchange).",
            selection = sel
        )
    }

    fun clearPair(@Suppress("UNUSED_PARAMETER") context: Context): AgentPairSelectionResponse {
        selection = null
        val cleared = AgentPairSelection(
            symbol = "",
            exchange = activeExchange,
            cleared = true
        )
        runBlocking { AgentEventBus.emitAgentSelection(cleared) }
        return AgentPairSelectionResponse(
            success = true,
            message = "Pilihan pair agen dikosongkan.",
            selection = null
        )
    }
}
