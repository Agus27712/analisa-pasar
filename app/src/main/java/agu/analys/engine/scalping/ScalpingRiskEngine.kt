package agu.analys.engine.scalping

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.model.EntryZone
import agu.analys.model.RegimeSnapshot
import agu.analys.model.RiskLevels
import agu.analys.model.ScalpSetupType
import agu.analys.model.StructureSnapshot
import java.util.Locale

/**
 * Risk engine scalping KriptoYoi (P1.3) — sisi LONG.
 *
 * Menghasilkan Entry Zone + SL/TP dinamis (berbasis ATR + struktur) + Net R:R
 * lewat [FeeCalculator] (fee exchange + slippage).
 *
 * Exchange-agnostic: semua jarak dihitung dari ATR% / persen, bukan angka absolut IDR/USDT.
 * Net R:R dihitung pada TP1 (target konservatif) dengan entry di sisi atas zona (fill terburuk).
 */
object ScalpingRiskEngine {

    const val DEFAULT_MIN_NET_RR = 1.15
    const val DEFAULT_SLIPPAGE_PCT = 0.08

    /** Dipakai jika ATR belum tersedia (candle kurang). */
    private const val FALLBACK_ATR_PCT = 0.5

    // Pengali ATR
    private const val MIN_RISK_ATR = 0.8
    private const val MAX_RISK_ATR = 2.0
    private const val DEFAULT_SL_ATR = 1.0
    private const val SL_BUFFER_ATR = 0.2
    private const val TP1_R = 2.0
    private const val TP2_R = 3.0

    data class Input(
        val price: Double,
        val setup: ScalpSetupType,
        val structure: StructureSnapshot,
        val regime: RegimeSnapshot,
        val fees: TradingFeeConfig = TradingFeeConfig(),
        val useMaker: Boolean = false,
        val slippagePct: Double = DEFAULT_SLIPPAGE_PCT,
        val minNetRr: Double = DEFAULT_MIN_NET_RR
    )

    data class Result(
        /** null jika input tidak valid (harga <= 0 atau setup NONE). */
        val levels: RiskLevels?,
        /** true jika level masuk akal dan Net R:R >= minNetRr. */
        val valid: Boolean,
        val reasons: List<String>
    )

    fun evaluate(input: Input): Result {
        if (input.price <= 0.0) {
            return Result(null, false, listOf("Harga tidak valid — risk plan tidak dibuat."))
        }
        if (input.setup == ScalpSetupType.NONE) {
            return Result(null, false, listOf("Tidak ada setup — risk plan tidak dibuat."))
        }

        val reasons = mutableListOf<String>()
        val price = input.price
        val s = input.structure

        val atrPct = if (input.regime.atrPct > 0.0) input.regime.atrPct else FALLBACK_ATR_PCT
        if (input.regime.atrPct <= 0.0) {
            reasons += "ATR belum tersedia — pakai ATR cadangan ${fmt(FALLBACK_ATR_PCT)}%."
        }
        val atr = price * atrPct / 100.0

        // --- Entry zone ---
        val (lowK, highK) = when (input.setup) {
            ScalpSetupType.BREAKOUT -> 0.1 to 0.2
            else -> 0.3 to 0.1
        }
        val zoneLow = price - lowK * atr
        val zoneHigh = price + highK * atr
        val entry = zoneHigh // konservatif: fill terburuk

        // --- Stop loss ---
        val structural = when (input.setup) {
            ScalpSetupType.LIQUIDITY_SWEEP -> s.lastSwingLow ?: s.support
            else -> s.support ?: s.lastSwingLow
        }
        val structuralSl = structural
            ?.takeIf { it > 0.0 && it < zoneLow }
            ?.let { it - SL_BUFFER_ATR * atr }

        var sl = structuralSl ?: (entry - DEFAULT_SL_ATR * atr)
        val rawRisk = entry - sl
        when {
            rawRisk > MAX_RISK_ATR * atr -> {
                sl = entry - MAX_RISK_ATR * atr
                reasons += "SL struktur terlalu jauh — dibatasi ${fmt(MAX_RISK_ATR)}x ATR."
            }
            rawRisk < MIN_RISK_ATR * atr -> {
                sl = entry - MIN_RISK_ATR * atr
                reasons += "SL terlalu rapat — dilebarkan ke ${fmt(MIN_RISK_ATR)}x ATR."
            }
            structuralSl != null -> reasons += "SL di bawah struktur (support/swing low) + buffer ATR."
            else -> reasons += "SL berbasis ${fmt(DEFAULT_SL_ATR)}x ATR (struktur tidak tersedia)."
        }

        // --- Take profit ---
        val risk = entry - sl
        var tp1 = entry + TP1_R * risk
        val resistance = s.resistance?.takeIf { it > entry }
        if (resistance != null && resistance * 0.9995 < tp1) {
            tp1 = resistance * 0.9995
            reasons += "Resistance dekat — TP1 dibatasi tepat di bawah resistance."
        }
        val tp2 = maxOf(entry + TP2_R * risk, tp1 + 0.5 * risk)

        // --- Net R:R (fee + slippage) ---
        val fee = FeeCalculator.roundTrip(
            entry = entry,
            stopLoss = sl,
            takeProfit = tp1,
            fees = input.fees,
            useMaker = input.useMaker,
            slippagePct = input.slippagePct
        )

        val riskPct = (entry - sl) / entry * 100.0
        val rewardPct = (tp1 - entry) / entry * 100.0

        val levelsOk = sl > 0.0 && sl < zoneLow && tp1 > entry
        val rrOk = fee.netRr >= input.minNetRr
        val valid = levelsOk && rrOk

        reasons += when {
            !levelsOk -> "Level SL/TP tidak masuk akal — WAIT."
            rrOk -> "Net R:R ${fmt(fee.netRr)} ≥ ${fmt(input.minNetRr)} (setelah fee & slippage)."
            else -> "Net R:R ${fmt(fee.netRr)} < ${fmt(input.minNetRr)} — fee/slippage terlalu besar atau target dekat, WAIT."
        }

        val levels = RiskLevels(
            entryZone = EntryZone(
                low = zoneLow,
                high = zoneHigh,
                confirmationTrigger = triggerText(input.setup),
                invalidation = sl
            ),
            stopLoss = sl,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            riskPct = riskPct,
            rewardPct = rewardPct,
            netRr = fee.netRr,
            feeSlippageNote = "Fee ${fmt(fee.feePct)}% + slippage ${fmt(fee.slippagePct * 2)}% " +
                "(round-trip ${fmt(fee.totalCostPct)}%). Net reward ${fmt(fee.netRewardPct)}%, " +
                "net risk ${fmt(fee.netRiskPct)}%."
        )
        return Result(levels, valid, reasons.toList())
    }

    private fun triggerText(setup: ScalpSetupType): String = when (setup) {
        ScalpSetupType.BREAKOUT -> "Candle M1 close di atas level breakout dengan volume naik."
        ScalpSetupType.BREAKOUT_RETEST -> "Retest level breakout bertahan, lalu candle M1 close hijau."
        ScalpSetupType.LIQUIDITY_SWEEP -> "Harga kembali di atas level sweep, lalu candle M1 close hijau."
        ScalpSetupType.TREND_PULLBACK -> "Pullback tertahan di EMA/support, lalu candle M1 close hijau."
        ScalpSetupType.NONE -> ""
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
