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
import agu.analys.engine.officedaily.OfficeDailyEvaluator
import agu.analys.engine.scalping.SignalLifecycleManager
import agu.analys.engine.secondwave.SecondWaveEvaluator
import agu.analys.engine.swing.SwingEvaluator
import agu.analys.model.Timeframe
import agu.analys.trading.SpotPositionStore
import agu.analys.util.AlertNotificationHelper
import agu.analys.util.AppPreferences
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker untuk memindai kandidat sinyal BUY secara periodik.
 * Menjalankan mode makro (SECOND_WAVE, SWING, OFFICE_DAILY).
 * SCALPING dan TRENCHING secara sengaja dikecualikan demi efisiensi baterai dan relevansi time-to-live sinyal.
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

        // 1. Filter koin: lewati koin yang sedang HOLDING
        val eligibleSymbols = watchlist.filter { rawSymbol ->
            val clean = rawSymbol.uppercase().replace("/", "").replace("-", "")
            !positionStore.get(clean).isHolding
        }

        if (eligibleSymbols.isEmpty()) {
            Timber.d("CandidateScanWorker: Semua koin watchlist sedang HOLDING atau kosong.")
            return Result.success()
        }

        try {
            // 2. Fetch tickers dalam 1 request batch HTTP (REST ringan via api/summaries)
            val pairIds = eligibleSymbols.map { IndodaxMarketService.toPairId(it) }
            val ticks = IndodaxMarketService.fetchTickers(pairIds)
            if (ticks.isEmpty()) {
                return Result.success()
            }

            val tickMap = ticks.associateBy { it.symbol.uppercase() }

            for (rawSymbol in eligibleSymbols) {
                val cleanSymbol = rawSymbol.uppercase().replace("/", "").replace("-", "")
                val tick = tickMap[cleanSymbol] ?: continue
                if (tick.price <= 0.0) continue

                // Lewati koin volume 24j yang tidak likuid (< Rp 500.000.000)
                if (tick.volume24h < 500_000_000.0) continue

                // Cek ulang holding status
                if (positionStore.get(cleanSymbol).isHolding) continue

                // Fetch candle H1 untuk SWING & OFFICE_DAILY
                val h1Candles = IndodaxMarketService.fetchCandles(cleanSymbol, Timeframe.H1, 45)

                if (h1Candles.size >= 20) {
                    val globalCtx = GlobalContextManager.context.value

                    // --- Evaluasi Mode SWING ---
                    val swingResult = SwingEvaluator.evaluate(
                        globalContext = globalCtx,
                        price = tick.price,
                        history = h1Candles,
                        fees = prefs.tradingFees
                    )
                    val trackedSwing = SignalLifecycleManager.process(
                        symbol = cleanSymbol,
                        currentPrice = tick.price,
                        rawSignal = swingResult.signal,
                        mode = StrategyMode.SWING
                    )
                    if (trackedSwing.transition?.hasTriggeringTransition == true && prefs.isNotificationsEnabled) {
                        if (!positionStore.get(cleanSymbol).isHolding) {
                            AlertNotificationHelper.sendCandidateFoundNotification(
                                context = applicationContext,
                                symbol = cleanSymbol,
                                strategyMode = StrategyMode.SWING,
                                signal = trackedSwing.activeSignalState ?: swingResult.signal
                            )
                        }
                    }

                    // --- Evaluasi Mode OFFICE_DAILY ---
                    val officeResult = OfficeDailyEvaluator.evaluate(
                        globalContext = globalCtx,
                        price = tick.price,
                        history = h1Candles,
                        fees = prefs.tradingFees
                    )
                    val trackedOffice = SignalLifecycleManager.process(
                        symbol = cleanSymbol,
                        currentPrice = tick.price,
                        rawSignal = officeResult.signal,
                        mode = StrategyMode.OFFICE_DAILY
                    )
                    if (trackedOffice.transition?.hasTriggeringTransition == true && prefs.isNotificationsEnabled) {
                        if (!positionStore.get(cleanSymbol).isHolding) {
                            AlertNotificationHelper.sendCandidateFoundNotification(
                                context = applicationContext,
                                symbol = cleanSymbol,
                                strategyMode = StrategyMode.OFFICE_DAILY,
                                signal = trackedOffice.activeSignalState ?: officeResult.signal
                            )
                        }
                    }
                }

                // --- Evaluasi Mode SECOND_WAVE ---
                // Fast screening berbasis summary tick untuk hemat kuota dan CPU
                val fastSecondWave = SecondWaveEvaluator.evaluateFast(tick, tick.high24h, tick.low24h)
                if (fastSecondWave.score >= 5) {
                    val h4Candles = IndodaxMarketService.fetchCandles(cleanSymbol, Timeframe.H4, 25)
                    val m15Candles = IndodaxMarketService.fetchCandles(cleanSymbol, Timeframe.M15, 25)

                    if (h4Candles.size >= 20 && m15Candles.size >= 20 && h1Candles.size >= 20) {
                        val secondWaveResult = SecondWaveEvaluator.evaluate(
                            globalContext = GlobalContextManager.context.value,
                            price = tick.price,
                            macroCandles = h4Candles,
                            h1Candles = h1Candles,
                            m15Candles = m15Candles,
                            fees = prefs.tradingFees
                        )
                        val trackedSecond = SignalLifecycleManager.process(
                            symbol = cleanSymbol,
                            currentPrice = tick.price,
                            rawSignal = secondWaveResult.signal,
                            mode = StrategyMode.SECOND_WAVE
                        )
                        if (trackedSecond.transition?.hasTriggeringTransition == true && prefs.isNotificationsEnabled) {
                            if (!positionStore.get(cleanSymbol).isHolding) {
                                AlertNotificationHelper.sendCandidateFoundNotification(
                                    context = applicationContext,
                                    symbol = cleanSymbol,
                                    strategyMode = StrategyMode.SECOND_WAVE,
                                    signal = trackedSecond.activeSignalState ?: secondWaveResult.signal
                                )
                            }
                        }
                    }
                }
            }

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
