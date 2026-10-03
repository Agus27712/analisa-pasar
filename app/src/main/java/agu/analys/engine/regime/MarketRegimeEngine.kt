package agu.analys.engine.regime

import agu.analys.engine.indicators.IndicatorMath
import agu.analys.model.CandleBar
import agu.analys.model.MarketRegime
import agu.analys.model.RegimeSnapshot
import agu.analys.model.StructureBias
import kotlin.math.abs

/**
 * Formal market regime engine (KriptoYoi P0).
 * Tidak mengganti [MarketRegimeDetector] lama (string legacy) — API baru bertipe [RegimeSnapshot].
 * Exchange-agnostic: threshold berbasis % / ratio (IDR & USDT).
 */
object MarketRegimeEngine {

    data class Input(
        val candles: List<CandleBar>,
        val price: Double = 0.0,
        val structureBias: StructureBias = StructureBias.NEUTRAL,
        val rvol: Double = 1.0,
        val resistanceBroken: Boolean = false,
        val supportBroken: Boolean = false
    )

    fun detect(input: Input): RegimeSnapshot {
        val candles = input.candles
        if (candles.size < 20) {
            return RegimeSnapshot(
                regime = MarketRegime.RANGING,
                explanation = "Data candle kurang untuk regime (min 20)."
            )
        }

        val price = if (input.price > 0) input.price else candles.last().close
        val atr = IndicatorMath.atr(candles, 14)
        val atrPct = if (price > 0) (atr / price) * 100.0 else 0.0
        val adx = IndicatorMath.adx(candles, 14)
        val chop = IndicatorMath.choppiness(candles, 14)

        val closes = candles.map { it.close }
        val ema9 = IndicatorMath.ema(closes, minOf(9, closes.size))
        val ema21 = IndicatorMath.ema(closes, minOf(21, closes.size))
        val ema50 = IndicatorMath.ema(closes, minOf(50, closes.size))

        val emaAlignment = when {
            ema9 > ema21 && ema21 > ema50 -> "bullish"
            ema9 < ema21 && ema21 < ema50 -> "bearish"
            else -> "mixed"
        }

        val regime = classify(
            adx = adx,
            chop = chop,
            atrPct = atrPct,
            emaAlignment = emaAlignment,
            structureBias = input.structureBias,
            rvol = input.rvol,
            resistanceBroken = input.resistanceBroken,
            supportBroken = input.supportBroken
        )

        val explanation = buildExplanation(regime, adx, chop, atrPct, emaAlignment)

        return RegimeSnapshot(
            regime = regime,
            adx = adx,
            chop = chop,
            atrPct = atrPct,
            emaAlignment = emaAlignment,
            explanation = explanation
        )
    }

    /** Convenience overload. */
    fun detect(
        candles: List<CandleBar>,
        price: Double = 0.0,
        structureBias: StructureBias = StructureBias.NEUTRAL,
        rvol: Double = 1.0,
        resistanceBroken: Boolean = false,
        supportBroken: Boolean = false
    ): RegimeSnapshot = detect(
        Input(
            candles = candles,
            price = price,
            structureBias = structureBias,
            rvol = rvol,
            resistanceBroken = resistanceBroken,
            supportBroken = supportBroken
        )
    )

    private fun classify(
        adx: Double,
        chop: Double,
        atrPct: Double,
        emaAlignment: String,
        structureBias: StructureBias,
        rvol: Double,
        resistanceBroken: Boolean,
        supportBroken: Boolean
    ): MarketRegime {
        // Volatility extremes first
        if (atrPct >= 3.5) return MarketRegime.HIGH_VOLATILITY
        if (atrPct > 0.0 && atrPct < 0.8 && adx < 18.0) return MarketRegime.LOW_VOLATILITY

        // Breakout: level broken + participation
        if ((resistanceBroken || supportBroken) && rvol >= 1.5) {
            return MarketRegime.BREAKOUT
        }

        // Ranging / choppy
        if (chop >= 61.8 && adx < 20.0) {
            return MarketRegime.RANGING
        }

        // Trending
        if (adx >= 25.0) {
            when {
                structureBias == StructureBias.BULLISH && emaAlignment == "bullish" ->
                    return MarketRegime.TRENDING_UP
                structureBias == StructureBias.BEARISH && emaAlignment == "bearish" ->
                    return MarketRegime.TRENDING_DOWN
                emaAlignment == "bullish" && structureBias != StructureBias.BEARISH ->
                    return MarketRegime.TRENDING_UP
                emaAlignment == "bearish" && structureBias != StructureBias.BULLISH ->
                    return MarketRegime.TRENDING_DOWN
            }
        }

        // Mild trend without strong ADX
        if (adx >= 20.0 && chop < 50.0) {
            if (emaAlignment == "bullish" || structureBias == StructureBias.BULLISH) {
                return MarketRegime.TRENDING_UP
            }
            if (emaAlignment == "bearish" || structureBias == StructureBias.BEARISH) {
                return MarketRegime.TRENDING_DOWN
            }
        }

        return MarketRegime.RANGING
    }

    private fun buildExplanation(
        regime: MarketRegime,
        adx: Double,
        chop: Double,
        atrPct: Double,
        emaAlignment: String
    ): String {
        val base = "ADX ${fmt(adx)} | CHOP ${fmt(chop)} | ATR ${fmt(atrPct)}% | EMA $emaAlignment"
        return when (regime) {
            MarketRegime.TRENDING_UP -> "TRENDING UP — momentum naik. $base"
            MarketRegime.TRENDING_DOWN -> "TRENDING DOWN — momentum turun. $base"
            MarketRegime.RANGING -> "RANGING / CHOPPY — hindari trend-following murni. $base"
            MarketRegime.BREAKOUT -> "BREAKOUT — level tembus + volume. $base"
            MarketRegime.HIGH_VOLATILITY -> "HIGH VOLATILITY — risiko noise tinggi. $base"
            MarketRegime.LOW_VOLATILITY -> "LOW VOLATILITY — range sempit. $base"
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
