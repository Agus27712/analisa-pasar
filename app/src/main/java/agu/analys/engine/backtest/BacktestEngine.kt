package agu.analys.engine.backtest

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import kotlin.math.max

data class BacktestTrade(
    val entryIndex: Int,
    val entryPrice: Double,
    val stopLoss: Double,
    val targetProfit: Double,
    var exitIndex: Int = -1,
    var exitPrice: Double = 0.0,
    var pnlPct: Double = 0.0,
    var isWin: Boolean = false,
    var exitReason: String = ""
)

data class BacktestResult(
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRatePct: Double,
    val profitFactor: Double,
    val netProfitPct: Double,
    val maxDrawdownPct: Double,
    val averageRr: Double,
    val expectancyPct: Double,
    val sampleSizeBars: Int,
    val trades: List<BacktestTrade>
)

object BacktestEngine {

    /**
     * Jalankan backtest pada deretan candle historis.
     * Menggunakan pemicu sederhana (misal breakout/pullback) & menyimulasikan eksekusi SL/TP realistis.
     */
    fun runBacktest(
        candles: List<CandleBar>,
        feeConfig: TradingFeeConfig = TradingFeeConfig(),
        slippagePct: Double = 0.08,
        minNetRr: Double = 1.2
    ): BacktestResult {
        if (candles.size < 30) {
            return BacktestResult(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, candles.size, emptyList())
        }

        val trades = mutableListOf<BacktestTrade>()
        var activeTrade: BacktestTrade? = null
        val totalCostPct = feeConfig.buyTakerPct + feeConfig.sellTakerPct + (2 * slippagePct)

        var peakEquity = 100.0
        var currentEquity = 100.0
        var maxDrawdown = 0.0
        var grossGains = 0.0
        var grossLosses = 0.0

        for (i in 20 until candles.size) {
            val currentBar = candles[i]

            // 1. Jika ada posisi aktif, periksa eksekusi SL atau TP
            if (activeTrade != null) {
                val trade = activeTrade
                val hitSl = currentBar.low <= trade.stopLoss
                val hitTp = currentBar.high >= trade.targetProfit

                if (hitSl || hitTp) {
                    val exitPrice = if (hitSl) trade.stopLoss else trade.targetProfit
                    val rawPnlPct = (exitPrice - trade.entryPrice) / trade.entryPrice * 100.0
                    val netPnlPct = rawPnlPct - totalCostPct

                    trade.exitIndex = i
                    trade.exitPrice = exitPrice
                    trade.pnlPct = netPnlPct
                    trade.isWin = netPnlPct > 0.0
                    trade.exitReason = if (hitTp) "TAKE_PROFIT" else "STOP_LOSS"

                    trades.add(trade)
                    activeTrade = null

                    // Calculation equity
                    currentEquity *= (1.0 + netPnlPct / 100.0)
                    if (currentEquity > peakEquity) {
                        peakEquity = currentEquity
                    } else {
                        val dd = (peakEquity - currentEquity) / peakEquity * 100.0
                        if (dd > maxDrawdown) maxDrawdown = dd
                    }

                    if (netPnlPct > 0.0) grossGains += netPnlPct else grossLosses += kotlin.math.abs(netPnlPct)
                }
                continue
            }

            // 2. Jika tidak ada posisi aktif, simulasikan kondisi sinyal entry
            val prevBar = candles[i - 1]
            val recent20 = candles.subList(i - 20, i)
            val ema20 = recent20.map { it.close }.average()
            val atr = recent20.takeLast(10).map { max(it.high - it.low, 0.0001) }.average()

            // Trigger sederhana: Close di atas EMA20 dan breakout High sebelumnya dengan volume mencukupi
            val isBullishTrigger = currentBar.close > ema20 && prevBar.close <= ema20 && currentBar.volume > 0
            if (isBullishTrigger && currentBar.close > 0.0) {
                val entryPrice = currentBar.close * (1.0 + slippagePct / 100.0)
                val stopLoss = max(entryPrice - (1.5 * atr), entryPrice * 0.985)
                val targetProfit = entryPrice + (2.2 * atr)

                val feeRes = FeeCalculator.roundTrip(entryPrice, stopLoss, targetProfit, feeConfig, false, slippagePct)
                if (feeRes.netRr >= minNetRr) {
                    activeTrade = BacktestTrade(
                        entryIndex = i,
                        entryPrice = entryPrice,
                        stopLoss = stopLoss,
                        targetProfit = targetProfit
                    )
                }
            }
        }

        val totalTrades = trades.size
        val winningTrades = trades.count { it.isWin }
        val losingTrades = totalTrades - winningTrades
        val winRate = if (totalTrades > 0) (winningTrades.toDouble() / totalTrades) * 100.0 else 0.0
        val profitFactor = if (grossLosses > 0.0) grossGains / grossLosses else if (grossGains > 0.0) 99.9 else 0.0
        val netProfitPct = currentEquity - 100.0
        val avgRr = if (totalTrades > 0) trades.map { kotlin.math.abs((it.targetProfit - it.entryPrice) / (it.entryPrice - it.stopLoss)) }.average() else 0.0
        val expectancy = if (totalTrades > 0) trades.map { it.pnlPct }.average() else 0.0

        return BacktestResult(
            totalTrades = totalTrades,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            winRatePct = winRate,
            profitFactor = profitFactor,
            netProfitPct = netProfitPct,
            maxDrawdownPct = maxDrawdown,
            averageRr = avgRr,
            expectancyPct = expectancy,
            sampleSizeBars = candles.size,
            trades = trades
        )
    }
}
