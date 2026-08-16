package agu.analys.engine.scalping

import agu.analys.model.CandleBar

/** Snapshot indikator + struktur untuk satu timeframe (1H / 15M / 1M). */
data class FrameSignal(
    val candles: List<CandleBar>,
    val price: Double,
    val rsi: Double,
    val emaFast: Double,
    val emaSlow: Double,
    val macdHist: Double,
    val atr: Double,
    val structureTrend: String,
    val structureEnough: Boolean,
    val volumeRatio: Double,
    val bullishEma: Boolean,
    val bearishEma: Boolean,
    val bullishMomentum: Boolean,
    val bearishMomentum: Boolean,
    val breakoutUp: Boolean,
    val breakoutDown: Boolean,
    val retestUp: Boolean,
    val retestDown: Boolean
)
