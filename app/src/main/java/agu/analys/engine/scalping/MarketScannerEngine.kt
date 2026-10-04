package agu.analys.engine.scalping

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.model.ScalpSetupType
import agu.analys.model.SignalDirection

/**
 * P4 — Market Scanner (engine only, tanpa UI).
 *
 * Memanggil [ScalpingMtfEvaluator] per pair dan meranking hasil.
 * Exchange-agnostic (IDR / USDT). WAIT tetap valid — pair tanpa setup masuk ranking bawah.
 */
object MarketScannerEngine {

    data class PairInput(
        val symbol: String,
        val price: Double,
        val h1: List<CandleBar>,
        val m15: List<CandleBar>,
        val m1: List<CandleBar>,
        val bids: List<OrderBookItem> = emptyList(),
        val asks: List<OrderBookItem> = emptyList(),
        val orderBookAgeMs: Long = 0L,
        val formingVolume: Double = 0.0
    )

    data class ScanRow(
        val symbol: String,
        val direction: SignalDirection,
        val setup: ScalpSetupType,
        val score: Int,
        val scoreCategory: String,
        val regime: String,
        val mtfAlignment: String,
        val rvol: Double,
        val netRr: Double,
        val reasoningHead: String
    )

    data class ScanResult(
        val rows: List<ScanRow>,
        val scanned: Int,
        val longCandidates: Int,
        val note: String
    )

    fun scan(
        pairs: List<PairInput>,
        fees: TradingFeeConfig = TradingFeeConfig(),
        minScore: Int = ScalpingConfig.MIN_SCORE_LONG,
        onlyLongReady: Boolean = false
    ): ScanResult {
        val rows = mutableListOf<ScanRow>()
        for (p in pairs) {
            val eval = ScalpingMtfEvaluator.evaluate(
                price = p.price,
                h1Candles = p.h1,
                m15Candles = p.m15,
                m1Candles = p.m1,
                formingVolume = p.formingVolume,
                bids = p.bids,
                asks = p.asks,
                fees = fees,
                symbol = p.symbol,
                orderBookAgeMs = p.orderBookAgeMs
            ) ?: continue

            val s = eval.scalping
            val row = ScanRow(
                symbol = p.symbol,
                direction = s.direction,
                setup = s.setup,
                score = s.score.total,
                scoreCategory = s.score.category,
                regime = s.regime.regime.name,
                mtfAlignment = s.mtfAlignment,
                rvol = s.rvol,
                netRr = s.risk?.netRr ?: 0.0,
                reasoningHead = s.reasoning.firstOrNull().orEmpty()
            )
            if (onlyLongReady && row.direction != SignalDirection.LONG) continue
            if (onlyLongReady && row.score < minScore) continue
            rows += row
        }

        val sorted = rows.sortedWith(
            compareByDescending<ScanRow> { it.direction == SignalDirection.LONG }
                .thenByDescending { it.score }
                .thenByDescending { it.netRr }
        )

        val longCount = sorted.count { it.direction == SignalDirection.LONG }
        return ScanResult(
            rows = sorted,
            scanned = pairs.size,
            longCandidates = longCount,
            note = "Scanner engine: ${pairs.size} pair, $longCount LONG candidate (skor ≥ gate bila onlyLongReady)."
        )
    }
}
