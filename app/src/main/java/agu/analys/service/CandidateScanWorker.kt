package agu.analys.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import agu.analys.config.StrategyMode
import agu.analys.engine.global.GlobalContextManager
import agu.analys.engine.intraday.IntradayEvaluator
import agu.analys.engine.scalping.SignalLifecycleManager
import agu.analys.engine.swing.SwingEvaluator
import agu.analys.model.Timeframe
import agu.analys.service.TokocryptoMarketService
import agu.analys.trading.SpotPositionStore
import agu.analys.util.AlertNotificationHelper
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker untuk memindai kandidat sinyal BUY secara periodik.
 * Menjalankan mode makro (SWING, OFFICE_DAILY).
 * SCALPING secara sengaja dikecualikan demi efisiensi baterai dan relevansi time-to-live sinyal.
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

        val positionStore = SpotPositionStore(applicationContext)

        // 1. Filter koin: lewati koin yang sedang HOLDING (di akun real maupun simulasi)
        val eligibleSymbols = watchlist.filter { rawSymbol ->
            val clean = rawSymbol.uppercase().replace("/", "").replace("-", "")
            !positionStore.get(clean, isReal = true).isHolding && !positionStore.get(clean, isReal = false).isHolding
        }

        if (eligibleSymbols.isEmpty()) {
            Timber.d("CandidateScanWorker: Semua koin watchlist sedang HOLDING atau kosong.")
            return Result.success()
        }

        agu.analys.util.AppLogManager.service("CandidateScan", "Memulai pemindaian background untuk ${eligibleSymbols.size} koin watchlist...")

        try {
            // 2. Fetch tickers dalam 1 request batch HTTP Tokocrypto
            val ticks = TokocryptoMarketService.fetchTickers(eligibleSymbols)
            if (ticks.isEmpty()) {
                return Result.success()
            }

            val tickMap = ticks.associateBy { it.symbol.uppercase() }

            for (rawSymbol in eligibleSymbols) {
                val cleanSymbol = rawSymbol.uppercase().replace("/", "").replace("-", "")
                val compactSym = TokocryptoMarketService.toTokocryptoSymbol(cleanSymbol)
                val tick = tickMap[cleanSymbol] ?: tickMap[compactSym] ?: continue
                if (tick.price <= 0.0) continue

                // Cek ulang holding status
                val isHolding = positionStore.get(cleanSymbol, isReal = true).isHolding || positionStore.get(cleanSymbol, isReal = false).isHolding
                if (isHolding) continue

                // Fetch candle H1 untuk SWING & H4 panjang untuk INTRADAY
                val h1Candles = TokocryptoMarketService.fetchCandles(cleanSymbol, Timeframe.H1, 45)
                val h4Candles = TokocryptoMarketService.fetchCandles(cleanSymbol, Timeframe.H4, 100)

                if (h1Candles.size >= 20) {
                    val globalCtx = GlobalContextManager.context.value

                    val quoteAsset = PriceFormatter.extractQuote(cleanSymbol)

                    // --- Evaluasi Mode SWING ---
                    val swingResult = SwingEvaluator.evaluate(
                        globalContext = globalCtx,
                        price = tick.price,
                        history = h1Candles,
                        fees = prefs.tradingFees,
                        symbol = cleanSymbol
                    )
                    val trackedSwing = SignalLifecycleManager.process(
                        symbol = cleanSymbol,
                        currentPrice = tick.price,
                        rawSignal = swingResult.signal,
                        mode = StrategyMode.SWING
                    )
                    if (trackedSwing.transition?.hasTriggeringTransition == true && prefs.isNotificationsEnabled) {
                        if (!positionStore.get(cleanSymbol, isReal = prefs.isRealBuyMode).isHolding) {
                            val priceStr = PriceFormatter.formatPrice(tick.price, showSymbol = true, quoteAsset = quoteAsset)
                            agu.analys.util.AppLogManager.service("CandidateFound", "🔔 [SWING] Kandidat BUY terdeteksi untuk $cleanSymbol @ $priceStr! Mengirim notifikasi...")
                            AlertNotificationHelper.sendCandidateFoundNotification(
                                context = applicationContext,
                                symbol = cleanSymbol,
                                strategyMode = StrategyMode.SWING,
                                signal = trackedSwing.activeSignalState ?: swingResult.signal
                            )
                        }
                    }

                    // --- Evaluasi Mode INTRADAY dengan H4 ---
                    val candlesForIntraday = if (h4Candles.size >= 20) h4Candles else h1Candles
                    val intradayResult = IntradayEvaluator.evaluate(
                        globalContext = globalCtx,
                        price = tick.price,
                        history = candlesForIntraday,
                        fees = prefs.tradingFees,
                        symbol = cleanSymbol
                    )
                    val trackedIntraday = SignalLifecycleManager.process(
                        symbol = cleanSymbol,
                        currentPrice = tick.price,
                        rawSignal = intradayResult.signal,
                        mode = StrategyMode.OFFICE_DAILY
                    )
                    if (trackedIntraday.transition?.hasTriggeringTransition == true && prefs.isNotificationsEnabled) {
                        if (!positionStore.get(cleanSymbol, isReal = prefs.isRealBuyMode).isHolding) {
                            val priceStr = PriceFormatter.formatPrice(tick.price, showSymbol = true, quoteAsset = quoteAsset)
                            agu.analys.util.AppLogManager.service("CandidateFound", "🔔 [INTRADAY] Kandidat BUY terdeteksi untuk $cleanSymbol @ $priceStr! Mengirim notifikasi...")
                            AlertNotificationHelper.sendCandidateFoundNotification(
                                context = applicationContext,
                                symbol = cleanSymbol,
                                strategyMode = StrategyMode.OFFICE_DAILY,
                                signal = trackedIntraday.activeSignalState ?: intradayResult.signal
                            )
                        }
                    }
                }
            }

            agu.analys.util.AppLogManager.service("CandidateScan", "Pemindaian background selesai. ${eligibleSymbols.size} koin dievaluasi.")
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
                .setRequiresBatteryNotLow(true)
                .build()

            // Interval 15 menit (minimum OS), flex interval 5 menit
            val workRequest = PeriodicWorkRequestBuilder<CandidateScanWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
