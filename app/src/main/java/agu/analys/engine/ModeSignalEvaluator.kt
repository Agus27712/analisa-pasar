package agu.analys.engine

import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.global.GlobalMarketContext
import agu.analys.engine.intraday.IntradayEvaluator
import agu.analys.engine.regime.MacroAnomalyDetector
import agu.analys.engine.scalping.ScalpingMtfEvaluator
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.engine.swing.SwingEvaluator
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.util.CandleTimeUtil

/**
 * Satu-satunya pintu masuk "hitung skor untuk satu mode".
 *
 * **Kenapa ada (N6 + N17).** Sebelumnya engine foreground (`LearningTradingEngine`) dan
 * `CandidateScanWorker` merakit pemanggilan evaluator masing-masing. Dua perakitan untuk satu
 * pertanyaan yang sama cepat menyimpang — dan memang menyimpang: worker SWING tidak pernah
 * meneruskan `macroAnomalyResult`, sehingga skor yang ia tulis ke key `SYMBOL#SWING` (key yang
 * sama dengan engine) selalu lebih tinggi daripada skor engine.
 *
 * Sekarang penalti anomali makro dihitung **di dalam** seam ini dari candle D1. Pemanggil tidak
 * punya parameter untuk "lupa" meneruskannya. Aturan yang dijaga:
 *
 * > Notifikasi kandidat menampilkan `confidence` dari evaluator mode yang sama dengan yang dipakai
 * > engine. Satu mesin, satu angka.
 *
 * **Batas yang jujur.** Kesegaran data tetap bisa berbeda: worker latar berjalan tiap 15 menit dan
 * tidak punya orderbook live/forming volume seperti engine per-tick. Yang dijamin adalah **metode
 * dan gerbang yang sama** (tanpa jalur diagnostik yang melewati gerbang), bukan snapshot yang sama
 * detik. Skor boleh bergerak mengikuti pasar — memang seharusnya begitu.
 */
data class ModeSignalInput(
    val mode: StrategyMode,
    val symbol: String,
    val price: Double,
    val nowMs: Long,
    val change24h: Double = Double.NaN,
    /** Candle H1 **tanpa bar yang masih berjalan** (pemanggil melakukan `dropLast(1)`). */
    val h1Candles: List<CandleBar> = emptyList(),
    /** Candle H4 **tanpa bar yang masih berjalan**. */
    val h4Candles: List<CandleBar> = emptyList(),
    /** Candle D1 — bahan penalti anomali makro untuk SWING & OFFICE_DAILY. */
    val d1Candles: List<CandleBar> = emptyList(),
    /** M15 boleh memuat bar berjalan; disintesis dengan tick di dalam. */
    val m15Candles: List<CandleBar> = emptyList(),
    /** M5 boleh memuat bar berjalan; disintesis dengan tick di dalam (P0: 4 TF). */
    val m5Candles: List<CandleBar> = emptyList(),
    /** M1 boleh memuat bar berjalan; disintesis dengan tick di dalam. */
    val m1Candles: List<CandleBar> = emptyList(),
    val formingVolume: Double = 0.0,
    val bids: List<OrderBookItem> = emptyList(),
    val asks: List<OrderBookItem> = emptyList(),
    val orderBookAgeMs: Long = 0L,
    val fees: TradingFeeConfig = TradingFeeConfig(),
    val scalpingConfig: ScalpingRuntimeConfig = ScalpingRuntimeConfig.current,
    val globalContext: GlobalMarketContext = GlobalMarketContext()
)

data class ModeSignal(
    val signal: AISignalState,
    val indicators: TechnicalIndicators
)

object ModeSignalEvaluator {

    /** Minimal candle yang dibutuhkan tiap mode sebelum evaluasi masuk akal. */
    const val MIN_CANDLES = 20

    /**
     * @return sinyal + indikator untuk [input.mode], atau `null` bila data candle belum cukup.
     * Tidak pernah mengembalikan skor dari mode lain.
     */
    fun evaluate(input: ModeSignalInput): ModeSignal? = when (input.mode) {
        StrategyMode.SCALPING -> evaluateScalping(input)
        StrategyMode.SWING -> evaluateSwing(input)
        StrategyMode.OFFICE_DAILY -> evaluateIntraday(input)
    }

    private fun evaluateScalping(input: ModeSignalInput): ModeSignal? {
        if (input.h1Candles.size < MIN_CANDLES ||
            input.m15Candles.size < MIN_CANDLES ||
            input.m1Candles.size < MIN_CANDLES
        ) {
            return null
        }

        // Engine menyintesis bar berjalan dari tick terakhir sebelum evaluasi. Worker melakukan
        // hal yang sama dengan tick yang ia punya, supaya bentuk inputnya identik.
        val nowMs = if (input.nowMs > 0L) input.nowMs else System.currentTimeMillis()
        val tick = MarketTick(
            symbol = input.symbol,
            price = input.price,
            high24h = input.price,
            low24h = input.price,
            volume24h = 0.0,
            change24h = input.change24h,
            timestamp = nowMs
        )
        val liveM1 = CandleTimeUtil.synthesizeRealtimeCandles(input.m1Candles, tick, Timeframe.M1, nowMs)
        val liveM15 = CandleTimeUtil.synthesizeRealtimeCandles(input.m15Candles, tick, Timeframe.M15, nowMs)
        val liveM5 = CandleTimeUtil.synthesizeRealtimeCandles(input.m5Candles, tick, Timeframe.M5, nowMs)

        val result = ScalpingMtfEvaluator.evaluate(
            globalContext = input.globalContext,
            price = input.price,
            h1Candles = input.h1Candles,
            m15Candles = liveM15,
            m5Candles = liveM5,
            m1Candles = liveM1,
            formingVolume = input.formingVolume,
            bids = input.bids,
            asks = input.asks,
            fees = input.fees,
            symbol = input.symbol,
            orderBookAgeMs = input.orderBookAgeMs,
            config = input.scalpingConfig,
            nowMs = nowMs,
            change24h = input.change24h
        ) ?: return null

        return ModeSignal(result.signal, result.indicators)
    }

    private fun evaluateSwing(input: ModeSignalInput): ModeSignal? {
        // Sama dengan kontrak lama engine: riwayat kosong = belum bisa dievaluasi. Riwayat 1..19
        // tetap dievaluasi karena evaluator sendiri mengembalikan sinyal "mengumpulkan data" —
        // perilaku UI tidak boleh berubah karena refactor ini.
        if (input.h1Candles.isEmpty()) return null

        // N17 — penalti anomali makro dihitung di sini, bukan diserahkan ke pemanggil.
        val anomaly = MacroAnomalyDetector.evaluate(input.d1Candles, input.price)
        val result = SwingEvaluator.evaluate(
            globalContext = input.globalContext,
            price = input.price,
            history = input.h1Candles,
            fees = input.fees,
            macroAnomalyResult = anomaly,
            symbol = input.symbol
        )
        return ModeSignal(result.signal, result.indicators)
    }

    private fun evaluateIntraday(input: ModeSignalInput): ModeSignal? {
        if (input.h4Candles.isEmpty()) return null

        val anomaly = MacroAnomalyDetector.evaluate(input.d1Candles, input.price)
        val result = IntradayEvaluator.evaluate(
            globalContext = input.globalContext,
            price = input.price,
            history = input.h4Candles,
            fees = input.fees,
            macroAnomalyResult = anomaly,
            symbol = input.symbol
        )
        return ModeSignal(result.signal, result.indicators)
    }
}
