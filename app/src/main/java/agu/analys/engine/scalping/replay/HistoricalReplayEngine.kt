package agu.analys.engine.scalping.replay

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.scalping.ScalpingMtfEvaluator
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.model.SignalAction
import agu.analys.model.SignalAudit

/**
 * FASE 3 — Historical Replay Engine untuk Scalping Evaluator:
 * Menjalankan simulasi data historis candle-by-candle (sliding window) seolah-olah data
 * tiba satu per satu secara real-time.
 *
 * Merekam SignalAudit di setiap candle, lalu menghitung:
 * - FASE 4: Valid Entry, False Signal, Missed BUY Opportunity, dan Avoided Loss.
 * - FASE 5: Bottleneck Checkpoint (Step 1, Step 2, Step 3, atau Step 4).
 */
enum class ReplayTradeOutcome {
    VALID_ENTRY,     // Sinyal BUY muncul dan berhasil mencapai target profit sebelum SL
    FALSE_SIGNAL,    // Sinyal BUY muncul tetapi terkena stop loss sebelum target profit
    MISSED_BUY,      // Sinyal tertolak di salah satu checkpoint padahal harga rally mencapai TP tanpa menyentuh SL
    AVOIDED_LOSS     // Sinyal tertolak dengan tepat (harga turun/kena SL, modal terlindungi)
}

data class ReplayFrame(
    val index: Int,
    val candle: CandleBar,
    val audit: SignalAudit,
    val isMomentumCandidate: Boolean,
    val futureRalliedWithoutSl: Boolean,
    val outcome: ReplayTradeOutcome? = null
)

data class BottleneckAnalysis(
    val totalMomentumOpportunities: Int,
    val step1Rejections: Int,
    val step2Rejections: Int,
    val step3Rejections: Int,
    val step4Rejections: Int,
    val primaryBottleneckStep: Int,
    val primaryBottleneckDescription: String
)

data class HistoricalReplayReport(
    val symbol: String,
    val totalEvaluations: Int,
    val totalSignalsTriggered: Int,
    val validEntries: Int,
    val falseSignals: Int,
    val missedOpportunities: Int,
    val avoidedLosses: Int,
    val bottleneck: BottleneckAnalysis,
    val frames: List<ReplayFrame>
) {
    val winRatePct: Double
        get() = if (totalSignalsTriggered > 0) (validEntries.toDouble() / totalSignalsTriggered) * 100.0 else 0.0

    val captureRatePct: Double
        get() {
            val totalViable = validEntries + missedOpportunities
            return if (totalViable > 0) (validEntries.toDouble() / totalViable) * 100.0 else 0.0
        }
}

object HistoricalReplayEngine {

    /**
     * Memproses deretan candle historis satu per satu melalui ScalpingMtfEvaluator.
     */
    fun replay(
        symbol: String = "BTCIDR",
        m1Candles: List<CandleBar>,
        m15Candles: List<CandleBar> = emptyList(),
        h1Candles: List<CandleBar> = emptyList(),
        orderBookProvider: ((index: Int, candle: CandleBar) -> Pair<List<OrderBookItem>, List<OrderBookItem>>)? = null,
        targetProfitPct: Double = 1.5,
        stopLossPct: Double = 1.0,
        forwardLookaheadBars: Int = 15,
        feeConfig: TradingFeeConfig = TradingFeeConfig()
    ): HistoricalReplayReport {
        if (m1Candles.size < 25) {
            return HistoricalReplayReport(
                symbol = symbol,
                totalEvaluations = 0,
                totalSignalsTriggered = 0,
                validEntries = 0,
                falseSignals = 0,
                missedOpportunities = 0,
                avoidedLosses = 0,
                bottleneck = BottleneckAnalysis(0, 0, 0, 0, 0, 0, "Data tidak cukup"),
                frames = emptyList()
            )
        }

        // Siapkan M15 dan H1 pelengkap jika tidak disediakan
        val effectiveM15 = if (m15Candles.size >= 20) m15Candles else generateSyntheticHigherTimeframe(m1Candles, 15)
        val effectiveH1 = if (h1Candles.size >= 20) h1Candles else generateSyntheticHigherTimeframe(m1Candles, 60)

        val frames = mutableListOf<ReplayFrame>()
        var step1Rejections = 0
        var step2Rejections = 0
        var step3Rejections = 0
        var step4Rejections = 0
        var totalMomentumOpportunities = 0
        var validEntries = 0
        var falseSignals = 0
        var missedOpportunities = 0
        var avoidedLosses = 0

        val startIndex = 20
        val endIndex = m1Candles.size - 1

        for (i in startIndex..endIndex) {
            val window = m1Candles.subList(0, i + 1)
            val currentCandle = m1Candles[i]
            val currentPrice = currentCandle.close

            val (bids, asks) = if (orderBookProvider != null) {
                orderBookProvider(i, currentCandle)
            } else {
                // Default: jika candle hijau dengan volume tinggi, simulasikan orderbook netral-bullish tipis
                val isGreen = currentCandle.close > currentCandle.open
                val bidAmt = if (isGreen) 20.0 else 10.0
                val askAmt = 10.0
                Pair(
                    listOf(OrderBookItem(currentPrice * 0.999, bidAmt, bidAmt * currentPrice, isBid = true)),
                    listOf(OrderBookItem(currentPrice * 1.001, askAmt, askAmt * currentPrice, isBid = false))
                )
            }

            // Ambil snapshot M15 & H1 yang tersedia hingga timestamp candle saat ini
            val curTime = currentCandle.timestamp
            val m15Slice = effectiveM15.filter { it.timestamp <= curTime }.ifEmpty { effectiveM15.take(20) }
            val h1Slice = effectiveH1.filter { it.timestamp <= curTime }.ifEmpty { effectiveH1.take(20) }

            val evalResult = ScalpingMtfEvaluator.evaluate(
                price = currentPrice,
                h1Candles = if (h1Slice.size >= 20) h1Slice else effectiveH1.take(20),
                m15Candles = if (m15Slice.size >= 20) m15Slice else effectiveM15.take(20),
                m1Candles = window,
                bids = bids,
                asks = asks,
                fees = feeConfig,
                symbol = symbol
            )

            val audit = evalResult?.audit ?: SignalAudit(
                symbol = symbol,
                timestamp = currentCandle.timestamp,
                price = currentPrice,
                finalAction = "HOLD",
                rejectionReason = "INSUFFICIENT_DATA"
            )

            // Deteksi momentum kandidat:
            // Volume > 1.2x rata-rata 20 candle terakhir DAN candle hijau
            val recent20 = m1Candles.subList(maxOf(0, i - 20), i)
            val avgVol = if (recent20.isNotEmpty()) recent20.map { it.volume }.average() else currentCandle.volume
            val isVolumeSurge = currentCandle.volume >= avgVol * 1.2
            val isGreenBreakout = currentCandle.close > currentCandle.open && isVolumeSurge
            val isMomentumCandidate = isGreenBreakout || (currentCandle.close > (recent20.maxOfOrNull { it.high } ?: currentPrice))

            // Evaluasi pergerakan forward (masa depan)
            val maxLookahead = minOf(m1Candles.size - 1, i + forwardLookaheadBars)
            var futureRalliedWithoutSl = false
            var hitSl = false

            val tpPrice = currentPrice * (1.0 + targetProfitPct / 100.0)
            val slPrice = currentPrice * (1.0 - stopLossPct / 100.0)

            if (i < m1Candles.size - 1) {
                for (f in (i + 1)..maxLookahead) {
                    val futureBar = m1Candles[f]
                    if (futureBar.low <= slPrice) {
                        hitSl = true
                        break
                    }
                    if (futureBar.high >= tpPrice) {
                        futureRalliedWithoutSl = true
                        break
                    }
                }
            }

            // Klasifikasi hasil (Outcome)
            val outcome = when {
                audit.finalAction == "BUY" -> {
                    if (futureRalliedWithoutSl) {
                        validEntries++
                        ReplayTradeOutcome.VALID_ENTRY
                    } else {
                        falseSignals++
                        ReplayTradeOutcome.FALSE_SIGNAL
                    }
                }
                isMomentumCandidate && futureRalliedWithoutSl -> {
                    missedOpportunities++
                    ReplayTradeOutcome.MISSED_BUY
                }
                isMomentumCandidate && hitSl -> {
                    avoidedLosses++
                    ReplayTradeOutcome.AVOIDED_LOSS
                }
                else -> null
            }

            // Hitung statistik bottleneck jika ini adalah peluang momentum yang gagal buy
            if (isMomentumCandidate && audit.finalAction != "BUY") {
                totalMomentumOpportunities++
                when {
                    !audit.step1Ok -> step1Rejections++
                    !audit.step2Ok -> step2Rejections++
                    !audit.step3Ok -> step3Rejections++
                    !audit.step4Ok -> step4Rejections++
                }
            } else if (isMomentumCandidate && audit.finalAction == "BUY") {
                totalMomentumOpportunities++
            }

            frames.add(
                ReplayFrame(
                    index = i,
                    candle = currentCandle,
                    audit = audit,
                    isMomentumCandidate = isMomentumCandidate,
                    futureRalliedWithoutSl = futureRalliedWithoutSl,
                    outcome = outcome
                )
            )
        }

        // Tentukan bottleneck utama
        val maxRejections = maxOf(step1Rejections, step2Rejections, step3Rejections, step4Rejections)
        val primaryStep = when (maxRejections) {
            0 -> 0
            step4Rejections -> 4
            step2Rejections -> 2
            step3Rejections -> 3
            else -> 1
        }

        val primaryDesc = when (primaryStep) {
            4 -> "STEP 4 (Net R:R) menolak $step4Rejections/$totalMomentumOpportunities peluang momentum (Net R:R >= 1.05 tidak tercapai dengan struktur fee saat ini)."
            2 -> "STEP 2 (Order Book) menolak $step2Rejections/$totalMomentumOpportunities peluang momentum (Buy pressure di bawah threshold atau orderbook kosong)."
            3 -> "STEP 3 (VWAP/VSA Trigger) menolak $step3Rejections/$totalMomentumOpportunities peluang momentum (Harga di bawah VWAP atau volume breakout belum terpenuhi)."
            1 -> "STEP 1 (Market Bias / Noise) menolak $step1Rejections/$totalMomentumOpportunities peluang momentum (Terhalang resistance M15 atau volatilitas ekstrim)."
            else -> "Tidak ada bottleneck yang dominan."
        }

        val bottleneck = BottleneckAnalysis(
            totalMomentumOpportunities = totalMomentumOpportunities,
            step1Rejections = step1Rejections,
            step2Rejections = step2Rejections,
            step3Rejections = step3Rejections,
            step4Rejections = step4Rejections,
            primaryBottleneckStep = primaryStep,
            primaryBottleneckDescription = primaryDesc
        )

        return HistoricalReplayReport(
            symbol = symbol,
            totalEvaluations = frames.size,
            totalSignalsTriggered = validEntries + falseSignals,
            validEntries = validEntries,
            falseSignals = falseSignals,
            missedOpportunities = missedOpportunities,
            avoidedLosses = avoidedLosses,
            bottleneck = bottleneck,
            frames = frames
        )
    }

    /**
     * Membangun candle timeframe lebih tinggi (M15 / H1) dari candle M1 untuk pengujian mandiri.
     */
    fun generateSyntheticHigherTimeframe(m1Candles: List<CandleBar>, intervalMinutes: Int): List<CandleBar> {
        val intervalMs = intervalMinutes * 60_000L
        val grouped = m1Candles.groupBy { it.timestamp / intervalMs }.values.map { group ->
            CandleBar(
                timestamp = group.first().timestamp,
                open = group.first().open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = group.last().close,
                volume = group.sumOf { it.volume }
            )
        }.sortedBy { it.timestamp }

        // Jika jumlah candle hasil group masih kurang dari 20, tambahkan padding flat candle di masa lalu
        if (grouped.size < 20) {
            val first = grouped.firstOrNull() ?: m1Candles.first()
            val padding = mutableListOf<CandleBar>()
            val needed = 20 - grouped.size
            for (k in needed downTo 1) {
                padding.add(
                    CandleBar(
                        timestamp = first.timestamp - (k * intervalMs),
                        open = first.open,
                        high = first.open * 1.002,
                        low = first.open * 0.998,
                        close = first.open,
                        volume = 1000.0
                    )
                )
            }
            return padding + grouped
        }
        return grouped
    }
}
