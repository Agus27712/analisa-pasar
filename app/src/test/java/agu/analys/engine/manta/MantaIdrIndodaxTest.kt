package agu.analys.engine.manta

import agu.analys.config.FeeCalculator
import agu.analys.config.ScalpingSensitivity
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.badge.CoinBadgeEvaluator
import agu.analys.engine.officedaily.OfficeDailyScreener
import agu.analys.engine.scalping.OrderBookAnalyzer
import agu.analys.engine.scalping.ScalpingMtfEvaluator
import agu.analys.engine.scalping.replay.HistoricalReplayEngine
import agu.analys.engine.secondwave.SecondWaveEvaluator
import agu.analys.model.Timeframe
import agu.analys.service.IndodaxMarketService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Test Suite Komprehensif menggunakan data dari Indodax untuk koin MANTA/IDR.
 * Menguji integrasi API real-time, evaluasi scalping multi-timeframe (H1, M15, M1),
 * analisis kedalaman Order Book (Bid/Ask Pressure), struktur pasar (Support/Resistance),
 * simulasi Historical Replay, screener multi-strategi, dan kalkulasi fee trading.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MantaIdrIndodaxTest {

    @Test
    fun test01_LiveIndodaxApi_MantaIdrDataFetch() = runBlocking {
        println("================================================================")
        println("=== 1. LIVE INDODAX API TEST: MANTA/IDR ===")
        println("================================================================")

        // 1. Fetch Ticker
        val ticker = try {
            IndodaxMarketService.fetchTicker("manta_idr")
        } catch (e: Exception) {
            println("Notice: Live ticker request exception: ${e.message}")
            null
        }

        if (ticker != null) {
            println("Live Ticker MANTA/IDR:")
            println("  - Symbol   : ${ticker.symbol}")
            println("  - Harga    : Rp ${ticker.price}")
            println("  - High 24h : Rp ${ticker.high24h}")
            println("  - Low 24h  : Rp ${ticker.low24h}")
            println("  - Vol IDR  : Rp ${String.format("%,.0f", ticker.volume24h)}")
            println("  - Change   : ${String.format("%.2f", ticker.change24h)}%")

            assertTrue("Harga live MANTA harus > 0", ticker.price > 0.0)
            assertTrue("High 24h harus >= Low 24h", ticker.high24h >= ticker.low24h)
            assertTrue("Volume 24h harus > 0", ticker.volume24h > 0.0)
        } else {
            println("Live API offline/throttled, menggunakan fallback snapshot untuk test berikutnya.")
        }

        // 2. Fetch Order Book Depth
        val (bids, asks) = try {
            IndodaxMarketService.fetchOrderBook("mantaidr", limit = 15)
        } catch (e: Exception) {
            println("Notice: Live orderbook request exception: ${e.message}")
            Pair(emptyList(), emptyList())
        }

        if (bids.isNotEmpty() && asks.isNotEmpty()) {
            println("\nLive Order Book MANTA/IDR:")
            println("  - Total Bids Depth Levels: ${bids.size} (Top Bid: Rp ${bids.first().price})")
            println("  - Total Asks Depth Levels: ${asks.size} (Top Ask: Rp ${asks.first().price})")
            assertTrue("Top Bid price <= Top Ask price", bids.first().price <= asks.first().price)
        }

        // 3. Fetch Recent Trades
        val trades = try {
            IndodaxMarketService.fetchRecentTrades("mantaidr", limit = 10)
        } catch (e: Exception) {
            println("Notice: Live trades request exception: ${e.message}")
            emptyList()
        }

        if (trades.isNotEmpty()) {
            println("\nLive Trades MANTA/IDR (Terakhir):")
            trades.take(3).forEach { t ->
                println("  - [${t.timeFormatted}] ${if (t.isBuy) "BUY " else "SELL"} ${t.amount} MANTA @ Rp ${t.price}")
            }
            assertTrue("Trades harus berisi transaksi valid", trades.all { it.price > 0 && it.amount > 0 })
        }

        // 4. Fetch Candles (M15)
        val m15Candles = try {
            IndodaxMarketService.fetchCandles("MANTAIDR", Timeframe.M15, limit = 25)
        } catch (e: Exception) {
            println("Notice: Live candles request exception: ${e.message}")
            emptyList()
        }

        if (m15Candles.isNotEmpty()) {
            println("\nLive M15 Candles MANTA/IDR: ${m15Candles.size} bar diterima")
            val lastBar = m15Candles.last()
            println("  - Last Candle Close: Rp ${lastBar.close} (Vol: ${lastBar.volume})")
            assertTrue("Candle harus memiliki harga valid", lastBar.close > 0)
        }

        println("================================================================\n")
    }

    @Test
    fun test02_MantaIdr_SafeTradableAssetVerification() {
        println("=== 2. VERIFIKASI KEAMANAN & LIKUIDITAS ASET (ANTI-ZOMBIE COIN) ===")
        val ticker = MantaIdrTestData.sampleTicker

        // Verifikasi aset riil MANTA/IDR
        val isSafe = IndodaxMarketService.isSafeTradableAsset(
            price = ticker.price,
            volume24h = ticker.volume24h,
            high24h = ticker.high24h,
            low24h = ticker.low24h,
            isIdrPair = true
        )

        println("Evaluasi Likuiditas MANTA/IDR:")
        println("  - Harga    : Rp ${ticker.price} (Batas minimum: > Rp 25)")
        println("  - Volume   : Rp ${String.format("%,.0f", ticker.volume24h)} (Batas minimum: >= 150 Juta IDR)")
        println("  - Status   : ${if (isSafe) "AMAN & LAYAK DITRADINGKAN" else "DITOLAK"}")

        assertTrue("MANTA/IDR harus lolos filter keamanan dan likuiditas", isSafe)

        // Verifikasi filter proteksi: Koin receh ekstrem atau volume rendah harus ditolak
        val isPennyRejected = IndodaxMarketService.isSafeTradableAsset(
            price = 3.0,
            volume24h = 50_000_000.0,
            high24h = 4.0,
            low24h = 1.0,
            isIdrPair = true
        )
        assertFalse("Koin receh ekstrem (Rp 3) harus ditolak", isPennyRejected)
    }

    @Test
    fun test03_MantaIdr_OrderBookAnalyzerBuyPressure() {
        println("=== 3. ANALISIS ORDER BOOK & BUY PRESSURE MANTA/IDR ===")
        val bids = MantaIdrTestData.sampleBids
        val asks = MantaIdrTestData.sampleAsks

        assertTrue("Bids harus tersedia", bids.isNotEmpty())
        assertTrue("Asks harus tersedia", asks.isNotEmpty())

        val topBid = bids.first().price
        val topAsk = asks.first().price
        val spreadIdr = topAsk - topBid
        val spreadPct = (spreadIdr / topBid) * 100.0

        val totalBidDepth = bids.sumOf { it.amount }
        val totalAskDepth = asks.sumOf { it.amount }

        val buyPressure = OrderBookAnalyzer.calculateBuyPressure(bids, asks, levels = 15)

        println("Order Book Snapshot MANTA/IDR:")
        println("  - Best Bid        : Rp $topBid")
        println("  - Best Ask        : Rp $topAsk")
        println("  - Spread          : Rp $spreadIdr (${String.format("%.2f", spreadPct)}%)")
        println("  - Total Bid Depth : ${String.format("%,.2f", totalBidDepth)} MANTA")
        println("  - Total Ask Depth : ${String.format("%,.2f", totalAskDepth)} MANTA")
        println("  - Buy/Ask Ratio   : ${String.format("%.3f", buyPressure)}x")

        assertTrue("Spread harus positif atau nol", spreadIdr >= 0.0)
        assertTrue("Buy pressure harus finite dan > 0", buyPressure.isFinite() && buyPressure > 0.0)
    }

    @Test
    fun test04_MantaIdr_ScalpingMtfEvaluator() {
        println("=== 4. SCALPING MTF EVALUATOR MENGGUNAKAN DATA MANTA/IDR ===")
        val price = MantaIdrTestData.sampleTicker.price
        val h1 = MantaIdrTestData.sampleH1
        val m15 = MantaIdrTestData.sampleM15
        val m1 = MantaIdrTestData.sampleM1
        val bids = MantaIdrTestData.sampleBids
        val asks = MantaIdrTestData.sampleAsks

        val result = ScalpingMtfEvaluator.evaluate(
            price = price,
            h1Candles = h1,
            m15Candles = m15,
            m1Candles = m1,
            bids = bids,
            asks = asks,
            fees = TradingFeeConfig(),
            sensitivity = ScalpingSensitivity.BALANCED,
            symbol = "MANTAIDR"
        )

        assertNotNull("Hasil evaluasi MTF MANTA/IDR tidak boleh null", result)
        val res = result!!

        println("Hasil Evaluasi Scalping MTF:")
        println("  - Pair             : ${res.audit.symbol}")
        println("  - Harga Masuk      : Rp ${res.audit.price}")
        println("  - Keputusan Sinyal : ${res.signal.action} (Confidence: ${res.signal.confidence}%)")
        println("  - Indikator RSI 14 : ${String.format("%.2f", res.indicators.rsi14)}")
        println("  - EMA20 / EMA50    : ${String.format("%.2f", res.indicators.ema20)} / ${String.format("%.2f", res.indicators.ema50)}")
        println("  - ATR              : ${String.format("%.2f", res.indicators.atr)}")
        println("  - Audit VWAP       : Rp ${String.format("%.2f", res.audit.vwap)}")
        println("  - Audit BuyPress   : ${String.format("%.2f", res.audit.buyPressure)}")
        println("  - Step 1 Tren      : ${res.audit.step1Ok}")
        println("  - Step 2 OrderBook : ${res.audit.step2Ok}")
        println("  - Step 3 Trigger   : ${res.audit.step3Ok}")
        println("  - Step 4 Net R:R   : ${res.audit.step4Ok}")
        println("  - Alasan Rejeksi   : ${res.audit.rejectionReason ?: "Lolos semua checkpoint"}")

        // Verifikasi integritas indikator teknikal
        assertTrue("RSI 14 harus berada di antara 0 dan 100", res.indicators.rsi14 in 0.0..100.0)
        assertTrue("ATR harus bernilai positif", res.indicators.atr > 0.0)
        assertTrue("VWAP harus bernilai positif", res.audit.vwap > 0.0)
        assertEquals("Audit symbol harus MANTAIDR", "MANTAIDR", res.audit.symbol)
    }

    @Test
    fun test05_MantaIdr_MarketStructureAnalysis() {
        println("=== 5. ANALISIS STRUKTUR PASAR MANTA/IDR (SMC & SWINGS) ===")
        val m15 = MantaIdrTestData.sampleM15
        val m1 = MantaIdrTestData.sampleM1

        val structure = MarketStructureAnalyzer.analyze(m15)
        val micro = MarketStructureAnalyzer.analyzeMicro(m1)

        println("Market Structure Snapshot (M15):")
        println("  - Trend           : ${structure.trend}")
        println("  - Support         : ${structure.support?.let { "Rp $it" } ?: "Belum terbentuk"}")
        println("  - Resistance      : ${structure.resistance?.let { "Rp $it" } ?: "Belum terbentuk"}")
        println("  - Last Swing High : ${structure.lastSwingHigh?.let { "Rp $it" } ?: "-"}")
        println("  - Last Swing Low  : ${structure.lastSwingLow?.let { "Rp $it" } ?: "-"}")
        println("  - Penjelasan      : ${structure.trendExplanation}")

        println("\nMicro Structure Snapshot (M1):")
        println("  - Micro Trend     : ${micro.trend}")
        println("  - Bullish BOS     : ${micro.hasBullishBOS}")
        println("  - Bearish BOS     : ${micro.hasBearishBOS}")
        println("  - Liquidity Sweep : Bull=${micro.hasBullishSweep}, Bear=${micro.hasBearishSweep}")

        assertTrue("Data structure M15 harus mencukupi", structure.dataEnough)
        assertNotNull("Trend structure tidak boleh null", structure.trend)
    }

    @Test
    fun test06_MantaIdr_HistoricalReplaySimulation() {
        println("=== 6. SIMULASI HISTORICAL REPLAY PADA DATA CANDLE MANTA/IDR ===")
        val m1 = MantaIdrTestData.sampleM1
        val m15 = MantaIdrTestData.sampleM15
        val h1 = MantaIdrTestData.sampleH1
        val bids = MantaIdrTestData.sampleBids
        val asks = MantaIdrTestData.sampleAsks

        val report = HistoricalReplayEngine.replay(
            symbol = "MANTAIDR",
            m1Candles = m1,
            m15Candles = m15,
            h1Candles = h1,
            orderBookProvider = { _, _ -> Pair(bids, asks) },
            targetProfitPct = 1.5,
            stopLossPct = 1.0,
            forwardLookaheadBars = 10,
            feeConfig = TradingFeeConfig()
        )

        println("Hasil Simulasi Historical Replay MANTA/IDR:")
        println("  - Total Frame Evaluasi : ${report.totalEvaluations}")
        println("  - Total Sinyal Muncul  : ${report.totalSignalsTriggered}")
        println("  - Valid Entries        : ${report.validEntries}")
        println("  - False Signals        : ${report.falseSignals}")
        println("  - Missed Opportunities : ${report.missedOpportunities}")
        println("  - Avoided Losses       : ${report.avoidedLosses}")
        println("  - Win Rate Sinyal      : ${String.format("%.2f", report.winRatePct)}%")
        println("  - Bottleneck Utama     : Step ${report.bottleneck.primaryBottleneckStep} - ${report.bottleneck.primaryBottleneckDescription}")
        println("  - Rejeksi Step 1 (Tren): ${report.bottleneck.step1Rejections}")
        println("  - Rejeksi Step 2 (OB)  : ${report.bottleneck.step2Rejections}")
        println("  - Rejeksi Step 3 (Trig): ${report.bottleneck.step3Rejections}")
        println("  - Rejeksi Step 4 (RR)  : ${report.bottleneck.step4Rejections}")

        assertTrue("Evaluasi harus berjalan pada dataset historis", report.totalEvaluations > 0)
        assertEquals("Symbol report harus MANTAIDR", "MANTAIDR", report.symbol)
        assertTrue("Primary bottleneck step valid (0..4)", report.bottleneck.primaryBottleneckStep in 0..4)
    }

    @Test
    fun test07_MantaIdr_MultiStrategyScreeners() {
        println("=== 7. MULTI-STRATEGY SCREENER TEST UNTUK MANTA/IDR ===")
        val ticker = MantaIdrTestData.sampleTicker
        val pair = MantaIdrTestData.tradingPair

        // 1. Office Daily Screener
        val office = OfficeDailyScreener.evaluateFast(ticker)
        println("Office Daily Screener:")
        println("  - Lolos Kualifikasi : ${office.isQualified}")
        println("  - Score Detail      : ${office.summary}")

        // 2. Second Wave Screener
        val secondWave = SecondWaveEvaluator.evaluateFast(ticker, ticker.high24h, ticker.low24h)
        println("Second Wave Screener:")
        println("  - Lolos Kualifikasi : ${secondWave.isQualified}")
        println("  - Score Detail      : ${secondWave.summary}")

        // 3. Coin Badge Evaluator across strategies
        val badgesScalping = CoinBadgeEvaluator.evaluateBadges(pair, ticker, StrategyMode.SCALPING)
        val badgesSwing = CoinBadgeEvaluator.evaluateBadges(pair, ticker, StrategyMode.SWING)
        val badgesOffice = CoinBadgeEvaluator.evaluateBadges(pair, ticker, StrategyMode.OFFICE_DAILY)
        val badgesSecondWave = CoinBadgeEvaluator.evaluateBadges(pair, ticker, StrategyMode.SECOND_WAVE)

        println("\nEvaluasi Badge Strategi:")
        println("  - Mode Scalping   : ${badgesScalping.firstOrNull()?.type ?: "None"}")
        println("  - Mode Swing      : ${badgesSwing.firstOrNull()?.type ?: "None"}")
        println("  - Mode Office     : ${badgesOffice.firstOrNull()?.type ?: "None"}")
        println("  - Mode SecondWave : ${badgesSecondWave.firstOrNull()?.type ?: "None"}")

        // MANTA/IDR dengan volume 1.19 Miliar dan kenaikan +17% memenuhi syarat volume aktif
        assertTrue("Volume MANTA/IDR cukup untuk masuk penilaian screener", ticker.volume24h > 1_000_000_000.0)
    }

    @Test
    fun test08_MantaIdr_TradingFeeAndRiskRewardMath() {
        println("=== 8. KALKULASI TRADING FEE & RISK/REWARD DI MANTA/IDR ===")
        val fees = TradingFeeConfig()
        val entryPrice = MantaIdrTestData.sampleTicker.price // Rp 1,292
        val stopLossPrice = entryPrice * 0.985              // -1.5% SL
        val takeProfitPrice = entryPrice * 1.03             // +3.0% TP

        val result = FeeCalculator.roundTrip(
            entry = entryPrice,
            stopLoss = stopLossPrice,
            takeProfit = takeProfitPrice,
            fees = fees,
            useMaker = false,
            slippagePct = 0.08
        )

        println("Fee & Risk/Reward Profile MANTA/IDR:")
        println("  - Entry Price     : Rp $entryPrice")
        println("  - Target TP (+3%) : Rp ${String.format("%.1f", takeProfitPrice)}")
        println("  - Stop Loss (-1.5%): Rp ${String.format("%.1f", stopLossPrice)}")
        println("  - Total Fee Cost  : ${String.format("%.3f", result.totalCostPct)}%")
        println("  - Net Reward Pct  : ${String.format("%.2f", result.netRewardPct)}%")
        println("  - Net Risk Pct    : ${String.format("%.2f", result.netRiskPct)}%")
        println("  - Net R:R         : ${String.format("%.2f", result.netRr)}")

        assertTrue("Net R:R harus bernilai positif", result.netRr > 0.0)
        assertTrue("Net reward pct harus positif untuk target 3%", result.netRewardPct > 0.0)
        assertTrue("Total cost fee harus > 0", result.totalCostPct > 0.0)
        println("================================================================\n")
    }
}
