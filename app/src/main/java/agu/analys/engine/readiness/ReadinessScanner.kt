package agu.analys.engine.readiness

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.Timeframe

/**
 * Sumber candle untuk pemindaian.
 *
 * Sengaja berbentuk `fun interface` **sinkron** supaya scanner tidak pernah menyentuh jaringan:
 * implementasi produksi membaca dari `MtfCacheManager` (cache memori), dan test cukup memberi
 * lambda biasa. Tidak ada `suspend` — memindai ratusan kombinasi koin × horizon tidak boleh
 * menunggu I/O.
 */
fun interface CandleSource {
    fun candles(symbol: String, timeframe: Timeframe): List<CandleBar>?
}

/**
 * Pemindai kesiapan seluruh daftar pantau (TEAM 1).
 *
 * Memindai **N koin × 3 horizon** dengan satu kali pembacaan candle per (koin, timeframe) —
 * timeframe yang dipakai bersama antar horizon hanya dibaca sekali. Untuk 20 koin itu 100
 * pembacaan cache, bukan 180.
 *
 * Scanner **tidak** mengambil data sendiri. Bila cache kosong, hasilnya
 * [ReadinessState.NO_DATA] — bukan kesimpulan palsu dari data yang tidak ada.
 */
object ReadinessScanner {

    /** Batas aman jumlah baris hasil, mencegah satu daftar pantau raksasa memblokir UI. */
    const val DEFAULT_MAX_ROWS = 600

    fun scan(
        symbols: List<String>,
        source: CandleSource,
        fees: TradingFeeConfig,
        /** Harga live per simbol; `null` ⇒ dipakai close candle bias. */
        priceOf: (String) -> Double? = { null },
        useMaker: Boolean = false,
        now: Long = System.currentTimeMillis(),
        horizons: List<TradeHorizon> = TradeHorizon.entries,
        maxRows: Int = DEFAULT_MAX_ROWS
    ): ReadinessBoard {
        if (symbols.isEmpty() || horizons.isEmpty()) {
            return ReadinessBoard(now, emptyList())
        }

        val neededTimeframes = horizons.flatMap { it.timeframes }.distinct()
        val rows = ArrayList<HorizonReadiness>(minOf(symbols.size * horizons.size, maxRows))

        outer@ for (raw in symbols) {
            val symbol = raw.trim()
            if (symbol.isEmpty()) continue

            val candles = HashMap<Timeframe, List<CandleBar>>(neededTimeframes.size)
            for (tf in neededTimeframes) {
                val list = source.candles(symbol, tf)
                if (!list.isNullOrEmpty()) candles[tf] = list
            }

            val price = priceOf(symbol) ?: 0.0
            for (horizon in horizons) {
                if (rows.size >= maxRows) break@outer
                rows += HorizonReadinessEngine.evaluate(
                    HorizonReadinessEngine.Input(
                        symbol = symbol,
                        horizon = horizon,
                        candles = candles,
                        price = price,
                        fees = fees,
                        useMaker = useMaker,
                        now = now
                    )
                )
            }
        }

        return ReadinessBoard(generatedAt = now, rows = rows)
    }
}
