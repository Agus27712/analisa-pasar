package agu.analys.engine.scalping

import agu.analys.engine.indicators.IndicatorMath
import agu.analys.model.CandleBar
import agu.analys.model.MarketRegime
import agu.analys.model.RegimeSnapshot
import agu.analys.model.ScalpSetupType
import agu.analys.model.StructureBias
import agu.analys.model.StructureSnapshot
import kotlin.math.abs

/**
 * Deteksi setup scalping KriptoYoi (P1.1).
 * Exchange-agnostic: threshold berbasis % / ratio (IDR & USDT).
 *
 * Prioritas (pertama yang match):
 * 1. LIQUIDITY_SWEEP
 * 2. BREAKOUT_RETEST
 * 3. BREAKOUT
 * 4. TREND_PULLBACK
 * 5. NONE
 */
object ScalpSetupDetector {

    data class Input(
        val price: Double,
        val structure: StructureSnapshot,
        val regime: RegimeSnapshot,
        val rvol: Double = 1.0,
        val buyPressure: Double = 1.0,
        val m1Candles: List<CandleBar> = emptyList(),
        /** Arah bias long/short untuk filter setup. Default long-focused. */
        val preferLong: Boolean = true
    )

    data class Result(
        val setup: ScalpSetupType,
        val explanation: String,
        /** true jika setup mendukung arah long */
        val isLongBiased: Boolean = true
    )

    fun detect(input: Input): Result {
        if (input.price <= 0.0) {
            return Result(ScalpSetupType.NONE, "Harga tidak valid.")
        }

        // Hindari trend-following murni di ranging keras (kecuali sweep / retest valid)
        val rangingHard = input.regime.regime == MarketRegime.RANGING && input.regime.chop >= 65.0

        detectLiquiditySweep(input)?.let { return it }
        detectBreakoutRetest(input)?.let { return it }
        if (!rangingHard) {
            detectBreakout(input)?.let { return it }
            detectTrendPullback(input)?.let { return it }
        }

        return Result(
            setup = ScalpSetupType.NONE,
            explanation = when {
                rangingHard -> "Regime ranging/choppy — tidak ada setup sweep/retest valid."
                else -> "Tidak ada setup scalping yang memenuhi konfluensi."
            }
        )
    }

    fun detect(
        price: Double,
        structure: StructureSnapshot,
        regime: RegimeSnapshot,
        rvol: Double = 1.0,
        buyPressure: Double = 1.0,
        m1Candles: List<CandleBar> = emptyList(),
        preferLong: Boolean = true
    ): Result = detect(
        Input(price, structure, regime, rvol, buyPressure, m1Candles, preferLong)
    )

    // --------------------------------------------------------------------------
    // LIQUIDITY_SWEEP
    // Liquidity taken + rejection + volume + structure reversal (CHoCH/BOS)
    // -------------------------------------------------------------------------
    private fun detectLiquiditySweep(input: Input): Result? {
        val s = input.structure
        if (!s.liquiditySweepDetected && !s.choch) return null

        val hasRejection = hasRejectionCandle(input.m1Candles, bullish = input.preferLong)
        val volumeOk = input.rvol >= 1.2 || input.m1Candles.isEmpty()
        val structureReversal = s.choch || s.bos || s.liquiditySweepDetected

        if (!structureReversal) return null
        // Minimal: sweep flag atau CHoCH; prefer rejection jika candle tersedia
        if (input.m1Candles.isNotEmpty() && !hasRejection && !s.choch) return null
        if (!volumeOk && !s.choch) return null

        val longBias = when {
            s.choch && s.bias == StructureBias.BULLISH -> true
            s.choch && s.bias == StructureBias.BEARISH -> false
            s.liquiditySweepDetected && s.bias != StructureBias.BEARISH -> true
            else -> input.preferLong
        }

        if (input.preferLong && !longBias) return null

        return Result(
            setup = ScalpSetupType.LIQUIDITY_SWEEP,
            explanation = buildString {
                append("Liquidity sweep")
                if (s.choch) append(" + CHoCH")
                if (hasRejection) append(" + rejection candle")
                if (input.rvol >= 1.2) append(" + RVOL ${fmt(input.rvol)}x")
                append(".")
            },
            isLongBiased = longBias
        )
    }

    // --------------------------------------------------------------------------
    // BREAKOUT_RETEST
    // Sudah breakout → pullback ke level → konfirmasi
    // -------------------------------------------------------------------------
    private fun detectBreakoutRetest(input: Input): Result? {
        val s = input.structure
        if (s.isBreakoutRetestValid) {
            return Result(
                setup = ScalpSetupType.BREAKOUT_RETEST,
                explanation = "Breakout & retest valid di level struktur + konfirmasi.",
                isLongBiased = s.bias != StructureBias.BEARISH
            )
        }

        val res = s.resistance ?: return null
        if (res <= 0.0 || input.price <= 0.0) return null

        // Harga di zona retest resistance yang sudah di-break (dekat level, masih di atas)
        val distPct = abs(input.price - res) / res * 100.0
        val aboveLevel = input.price >= res * 0.993
        val nearLevel = distPct <= 1.5
        val flowOk = input.buyPressure >= 1.05 || input.rvol >= 1.2

        // Butuh indikasi bahwa level pernah di-break: BOS / pattern BOS / strength tinggi
        val hadBreakContext = s.bos || s.pattern == "BOS" || s.strength >= 55

        if (aboveLevel && nearLevel && hadBreakContext && flowOk) {
            return Result(
                setup = ScalpSetupType.BREAKOUT_RETEST,
                explanation = "Retest level breakout (jarak ${fmt(distPct)}%) + order flow/volume support.",
                isLongBiased = true
            )
        }
        return null
    }

    // --------------------------------------------------------------------------
    // BREAKOUT
    // Resistance broken + RVOL high + momentum + order flow
    // -------------------------------------------------------------------------
    private fun detectBreakout(input: Input): Result? {
        val s = input.structure
        val res = s.resistance
        val price = input.price

        val broken = when {
            res != null && res > 0.0 -> price >= res * 0.998
            s.bos && s.bias == StructureBias.BULLISH -> true
            input.regime.regime == MarketRegime.BREAKOUT -> true
            else -> false
        }
        if (!broken) return null

        val rvolOk = input.rvol >= 1.5
        val flowOk = input.buyPressure >= 1.15
        val momentumOk = momentumBullish(input.m1Candles, price)

        // Minimal 2 dari 3 konfirmasi (volume / flow / momentum), atau regime BREAKOUT + 1
        val confirms = listOf(rvolOk, flowOk, momentumOk).count { it }
        val regimeBreak = input.regime.regime == MarketRegime.BREAKOUT
        if (confirms < 2 && !(regimeBreak && confirms >= 1)) return null

        return Result(
            setup = ScalpSetupType.BREAKOUT,
            explanation = buildString {
                append("Breakout")
                if (res != null) append(" resistance")
                if (rvolOk) append(" + RVOL ${fmt(input.rvol)}x")
                if (flowOk) append(" + buy pressure ${fmt(input.buyPressure)}x")
                if (momentumOk) append(" + momentum EMA")
                append(".")
            },
            isLongBiased = true
        )
    }

    // --------------------------------------------------------------------------
    // TREND_PULLBACK
    // Trend clear + pullback ke support/EMA + recovery momentum
    // -------------------------------------------------------------------------
    private fun detectTrendPullback(input: Input): Result? {
        val regime = input.regime.regime
        val trending =
            regime == MarketRegime.TRENDING_UP ||
                (regime != MarketRegime.TRENDING_DOWN &&
                    input.structure.bias == StructureBias.BULLISH &&
                    input.regime.emaAlignment == "bullish")

        if (!trending && input.structure.pattern != "HH_HL") return null
        if (input.structure.bias == StructureBias.BEARISH) return null

        val price = input.price
        val support = input.structure.support
        val nearSupport = support != null && support > 0.0 &&
            abs(price - support) / support * 100.0 <= 1.2 &&
            price >= support * 0.995

        val nearEma = nearEmaPullback(input.m1Candles, price)
        if (!nearSupport && !nearEma) return null

        val recovery = momentumBullish(input.m1Candles, price) ||
            input.buyPressure >= 1.1 ||
            input.rvol >= 1.15

        if (!recovery && input.m1Candles.size >= 15) return null

        return Result(
            setup = ScalpSetupType.TREND_PULLBACK,
            explanation = buildString {
                append("Trend pullback")
                when {
                    nearSupport -> append(" ke support")
                    nearEma -> append(" ke EMA")
                }
                if (input.regime.emaAlignment == "bullish") append(" + EMA alignment")
                append(".")
            },
            isLongBiased = true
        )
    }

    // --------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun hasRejectionCandle(candles: List<CandleBar>, bullish: Boolean): Boolean {
        if (candles.isEmpty()) return false
        val c = candles.last()
        val range = (c.high - c.low).coerceAtLeast(1e-12)
        val body = abs(c.close - c.open)
        val lowerWick = minOf(c.open, c.close) - c.low
        val upperWick = c.high - maxOf(c.open, c.close)
        return if (bullish) {
            // Hammer-ish: lower wick dominan, close di setengah atas
            lowerWick >= range * 0.4 && c.close >= c.low + range * 0.5 && body <= range * 0.5
        } else {
            upperWick >= range * 0.4 && c.close <= c.high - range * 0.5 && body <= range * 0.5
        }
    }

    private fun momentumBullish(candles: List<CandleBar>, price: Double): Boolean {
        if (candles.size < 10) return price > 0
        val closes = candles.map { it.close }
        val ema9 = IndicatorMath.ema(closes, minOf(9, closes.size))
        val ema21 = IndicatorMath.ema(closes, minOf(21, closes.size))
        val rsi = IndicatorMath.rsi(candles, minOf(14, candles.size - 1))
        return ema9 >= ema21 * 0.998 && price >= ema9 * 0.995 && rsi in 40.0..78.0
    }

    private fun nearEmaPullback(candles: List<CandleBar>, price: Double): Boolean {
        if (candles.size < 15) return false
        val closes = candles.map { it.close }
        val ema9 = IndicatorMath.ema(closes, 9)
        val ema21 = IndicatorMath.ema(closes, 21)
        val near9 = abs(price - ema9) / ema9 * 100.0 <= 0.8
        val near21 = abs(price - ema21) / ema21 * 100.0 <= 1.0
        return (near9 || near21) && price >= ema21 * 0.992
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
