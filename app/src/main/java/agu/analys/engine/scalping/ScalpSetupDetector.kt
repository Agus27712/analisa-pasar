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
 * 2. BREAKOUT_RETEST (ketat — RVOL/strength/regime; replay Tokocrypto 2026-10)
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

        // Hindari trend-following murni di ranging keras (kecuali sweep)
        val rangingHard =
            input.regime.regime == MarketRegime.RANGING &&
                input.regime.chop >= ScalpingConfig.CHOP_HARD_RANGING

        detectLiquiditySweep(input)?.let { return it }
        // Retest tidak diizinkan di ranging keras (replay: retest di chop = −EV)
        if (!rangingHard) {
            detectBreakoutRetest(input)?.let { return it }
            detectBreakout(input)?.let { return it }
            detectTrendPullback(input)?.let { return it }
        }

        return Result(
            setup = ScalpSetupType.NONE,
            explanation = when {
                rangingHard -> "Regime ranging/choppy — hanya sweep yang diizinkan; retest/breakout/pullback ditahan."
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
    // -------------------------------------------------------------------------
    private fun detectLiquiditySweep(input: Input): Result? {
        val s = input.structure
        if (!s.liquiditySweepDetected && !s.choch) return null

        val hasRejection = hasRejectionCandle(input.m1Candles, bullish = input.preferLong)
        val volumeOk = input.rvol >= ScalpingConfig.RVOL_SETUP_MIN || input.m1Candles.isEmpty()
        val structureReversal = s.choch || s.bos || s.liquiditySweepDetected

        if (!structureReversal) return null
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
                if (input.rvol >= ScalpingConfig.RVOL_SETUP_MIN) append(" + RVOL ${fmt(input.rvol)}x")
                append(".")
            },
            isLongBiased = longBias
        )
    }

    // --------------------------------------------------------------------------
    // BREAKOUT_RETEST — ketat (RVOL, strength, BOS, bias, momentum)
    // -------------------------------------------------------------------------
    private fun detectBreakoutRetest(input: Input): Result? {
        val s = input.structure

        // Jangan long retest saat struktur bearish
        if (s.bias == StructureBias.BEARISH) return null

        val rvolOk = input.rvol >= ScalpingConfig.RVOL_RETEST_MIN
        // Orderbook kosong → buyPressure netral 1.0; jangan wajibkan flow tinggi offline.
        // Jika depth ada (pressure jauh dari 1.0), minta supportive/retest threshold.
        val hasDepthHint = abs(input.buyPressure - 1.0) > 0.02
        val flowOk = !hasDepthHint || input.buyPressure >= ScalpingConfig.BUY_PRESSURE_RETEST

        if (s.isBreakoutRetestValid) {
            if (!rvolOk) return null
            if (!flowOk) return null
            if (s.strength > 0 && s.strength < ScalpingConfig.RETEST_MIN_STRUCTURE_STRENGTH) return null
            return Result(
                setup = ScalpSetupType.BREAKOUT_RETEST,
                explanation = "Breakout & retest valid + RVOL ${fmt(input.rvol)}x" +
                    if (hasDepthHint) " + buy pressure ${fmt(input.buyPressure)}x." else ".",
                isLongBiased = true
            )
        }

        val res = s.resistance ?: return null
        if (res <= 0.0 || input.price <= 0.0) return null

        val distPct = abs(input.price - res) / res * 100.0
        val aboveLevel = input.price >= res * ScalpingConfig.RETEST_ABOVE_FACTOR
        val nearLevel = distPct <= ScalpingConfig.RETEST_MAX_DIST_PCT

        // Wajib konteks break nyata: BOS / pattern BOS (bukan hanya strength)
        val hadBreakContext = s.bos || s.pattern == "BOS" || s.pattern == "HH_HL" && s.bos
        val strengthOk = s.strength >= ScalpingConfig.RETEST_MIN_STRUCTURE_STRENGTH
        val momentumOk = momentumBullish(input.m1Candles, input.price)

        // RVOL wajib; strength + (momentum ATAU flow depth)
        if (!aboveLevel || !nearLevel || !hadBreakContext || !rvolOk || !strengthOk) return null
        if (!momentumOk && !flowOk) return null

        return Result(
            setup = ScalpSetupType.BREAKOUT_RETEST,
            explanation = buildString {
                append("Retest level breakout (jarak ${fmt(distPct)}%)")
                append(" + RVOL ${fmt(input.rvol)}x")
                append(" + strength ${s.strength}")
                if (momentumOk) append(" + momentum")
                if (hasDepthHint && flowOk) append(" + buy pressure ${fmt(input.buyPressure)}x")
                append(".")
            },
            isLongBiased = true
        )
    }

    // --------------------------------------------------------------------------
    // BREAKOUT
    // -------------------------------------------------------------------------
    private fun detectBreakout(input: Input): Result? {
        val s = input.structure
        val res = s.resistance
        val price = input.price

        val broken = when {
            res != null && res > 0.0 -> price >= res * ScalpingConfig.BREAKOUT_ABOVE_FACTOR
            s.bos && s.bias == StructureBias.BULLISH -> true
            input.regime.regime == MarketRegime.BREAKOUT -> true
            else -> false
        }
        if (!broken) return null

        val rvolOk = input.rvol >= ScalpingConfig.RVOL_BREAKOUT_MIN
        val flowOk = input.buyPressure >= ScalpingConfig.BUY_PRESSURE_BREAKOUT
        val momentumOk = momentumBullish(input.m1Candles, price)

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
            abs(price - support) / support * 100.0 <= ScalpingConfig.PULLBACK_SUPPORT_MAX_DIST_PCT &&
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
