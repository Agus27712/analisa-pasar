package agu.analys.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.engine.global.GlobalContextManager
import agu.analys.engine.intraday.IntradayEvaluator
import agu.analys.engine.scalping.SignalLifecycleManager
import agu.analys.engine.swing.SwingEvaluator
import agu.analys.model.MarketKey
import agu.analys.model.Timeframe
import agu.analys.service.IndodaxMarketService
import agu.analys.service.TokocryptoMarketService
import agu.analys.trading.SpotPositionStore
import agu.analys.util.AlertNotificationHelper
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker untuk memindai kandidat sinyal BUY secara periodik.
 * Menjalankan mode makro (SWING, OFFICE_DAILY) dengan routing bursa yang dinamis.
 */
class CandidateScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        if (!prefs.isNotificationsEnabled) {
            Timber.d("CandidateScanWorker: Notifikasi dinonaktifkan, lewati pekerjaan.")
            return Result.success()
        }

        val watchlist = prefs.getWatchlist()
        if (watchlist.isEmpty()) {
            return Result.success()
        }

        val currentExchange = prefs.marketDataSource.name
        val positionStore = SpotPositionStore(applicationContext)

        // 1. Filter koin: lewati koin yang sedang HOLDING (di akun real maupun simulasi untuk bursa aktif)
        val eligibleSymbols = watchlist.filter { rawSymbol ->
            val clean = rawSymbol.uppercase().replace("/", "").replace("-", "")
            !positionStore.get(clean, isReal = true, exchange = currentExchange).isHolding &&
                    !positionStore.get(clean, isReal = false, exchange = currentExchange).isHolding
        }

        if (eligibleSymbols.isEmpty()) {
            Timber.d("CandidateScanWorker: Semua koin watchlist sedang HOLDING atau kosong.")
            return Result.success()
        }

        agu.analys.util.AppLogManager.service(
            "CandidateScan",
            "Memulai pemindaian background ($currentExchange) untuk ${eligibleSymbols.size} koin watchlist..."
        )

        try {
            val isIndodax = prefs.marketDataSource == MarketDataSource.INDODAX
            val tickMap = mutableMapOf<String, Double>()

            if (isIndodax) {
                val allTicks = IndodaxMarketService.fetchAllMarketTicks()
                for ((sym, tick) in allTicks) {
                    val symUpper = sym.uppercase()
                    tickMap[symUpper] = tick.price
                    tickMap[symUpper.replace("/", "").replace("_", "")] = tick.price
                }
            } else {
                val ticks = TokocryptoMarketService.fetchTickers(eligibleSymbols)
                if (ticks.isNotEmpty()) {
                    for (t in ticks) {
                        tickMap[t.symbol.uppercase()] = t.price
                    }
                }
            }

            if (tickMap.isEmpty()) {
                return Result.success()
            }

            for (rawSymbol in eligibleSymbols) {
                val cleanSymbol = rawSymbol.uppercase().replace("/", "").replace("-", "")
                val compactSym = if (!isIndodax) TokocryptoMarketService.toTokocryptoSymbol(cleanSymbol) else cleanSymbol
                val price = tickMap[cleanSymbol] ?: tickMap[compactSym] ?: continue
                if (price <= 0.0) continue

                // Cek ulang holding status
                val isHolding = positionStore.get(cleanSymbol, isReal = true, exchange = currentExchange).isHolding ||
                        positionStore.get(cleanSymbol, isReal = false, exchange = currentExchange).isHolding
                if (isHolding) continue

                val marketKey = MarketKey.resolve(cleanSymbol, currentExchange)
                val quoteAsset = marketKey.quote

                // Fetch candle H1 untuk SWING & H4 panjang untuk INTRADAY
                val h1Candles = if (isIndodax) {
                    IndodaxMarketService.fetchCandles(cleanSymbol, Timeframe.H1, 45)
                } else {
                    TokocryptoMarketService.fetchCandles(cleanSymbol, Timeframe.H1, 45)
                }

                val h4Candles = if (isIndodax) {
                    IndodaxMarketService.fetchCandles(cleanSymbol, Timeframe.H4, 100)
                } else {
                    TokocryptoMarketService.fetchCandles(cleanSymbol, Timeframe.H4, 100)
                }

                if (h1Candles.size >= 20) {
                    val globalCtx = GlobalContextManager.context.value

                    // --- Evaluasi Mode SWING ---
                    val swingResult = SwingEvaluator.evaluate(
                        globalContext = globalCtx,
                        price = price,
                        history = h1Candles,
                        fees = prefs.tradingFees,
                        symbol = cleanSymbol
                    )
                    val trackedSwing = SignalLifecycleManager.process(
                        symbol = cleanSymbol,
                        currentPrice = price,
                        rawSignal = swingResult.signal,
                        mode = StrategyMode.SWING
                    )
                    if (trackedSwing.transition?.hasTriggeringTransition == true && prefs.isNotificationsEnabled) {
                        if (!positionStore.get(cleanSymbol, isReal = prefs.isRealBuyMode, exchange = currentExchange).isHolding) {
                            val priceStr = PriceFormatter.formatPrice(price, showSymbol = true, quoteAsset = quoteAsset)
                            agu.analys.util.AppLogManager.service(
                                "CandidateFound",
                                "🔔 [SWING - $currentExchange] Kandidat BUY terdeteksi untuk ${marketKey.formattedPair()} @ $priceStr! Mengirim notifikasi..."
                            )
                            AlertNotificationHelper.sendCandidateFoundNotification(
                                context = applicationContext,
                                marketKey = marketKey,
                                strategyMode = StrategyMode.SWING,
                                signal = trackedSwing.activeSignalState ?: swingResult.signal
                            )
                        }
                    }

                    // --- Evaluasi Mode INTRADAY dengan H4 ---
                    val candlesForIntraday = if (h4Candles.size >= 20) h4Candles else h1Candles
                    val intradayResult = IntradayEvaluator.evaluate(
                        globalContext = globalCtx,
                        price = price,
                        history = candlesForIntraday,
                        fees = prefs.tradingFees,
                        symbol = cleanSymbol
                    )
                    val trackedIntraday = SignalLifecycleManager.process(
                        symbol = cleanSymbol,
                        currentPrice = price,
                        rawSignal = intradayResult.signal,
                        mode = StrategyMode.OFFICE_DAILY
                    )
                    if (trackedIntraday.transition?.hasTriggeringTransition == true && prefs.isNotificationsEnabled) {
                        if (!positionStore.get(cleanSymbol, isReal = prefs.isRealBuyMode, exchange = currentExchange).isHolding) {
                            val priceStr = PriceFormatter.formatPrice(price, showSymbol = true, quoteAsset = quoteAsset)
                            agu.analys.util.AppLogManager.service(
                                "CandidateFound",
                                "🔔 [INTRADAY - $currentExchange] Kandidat BUY terdeteksi untuk ${marketKey.formattedPair()} @ $priceStr! Mengirim notifikasi..."
                            )
                            AlertNotificationHelper.sendCandidateFoundNotification(
                                context = applicationContext,
                                marketKey = marketKey,
                                strategyMode = StrategyMode.OFFICE_DAILY,
                                signal = trackedIntraday.activeSignalState ?: intradayResult.signal
                            )
                        }
                    }
                }
            }

            agu.analys.util.AppLogManager.service(
                "CandidateScan",
                "Pemindaian background ($currentExchange) selesai. ${eligibleSymbols.size} koin dievaluasi."
            )
            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "CandidateScanWorker: Terjadi kesalahan saat memindai kandidat")
            return Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "CandidateScanPeriodicWork"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<CandidateScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
