package agu.analys.engine.trenching

object TrenchingAntiFomoGuard {
    fun shouldBlockEntry(
        flowContext: FlowContext,
        currentPrice: Double,
        trenchResistance: Double,
        candleSpreadPct: Double = 0.0,
        netRewardRiskRatio: Double = 2.0
    ): Pair<Boolean, String> {
        // 1. Block if data quality is LOW
        if (flowContext.dataQuality == DataQuality.LOW) {
            return Pair(true, "Data market belum lengkap untuk mengkonfirmasi flow.")
        }

        // 2. Block if flow is exhausted
        if (flowContext.flowState == FlowState.EXHAUSTING) {
            return Pair(true, "Flow mulai exhaustion. Hindari kejar pucuk.")
        }

        // 3. Block if price is too extended from VWAP (> 4.5%)
        if (flowContext.vwapDistancePct > 4.5) {
            return Pair(
                true,
                "Harga terlalu extended dari VWAP (${String.format(java.util.Locale.US, "%.1f", flowContext.vwapDistancePct)}%). Tunggu retest."
            )
        }

        // 4. Block if breakout is too far from trench resistance without healthy retest (> 2.5%)
        if (trenchResistance > 0 && currentPrice > trenchResistance * 1.025) {
            val dist = ((currentPrice - trenchResistance) / trenchResistance) * 100.0
            return Pair(
                true,
                "Breakout terlalu jauh dari trench (+${String.format(java.util.Locale.US, "%.1f", dist)}%). Tunggu retest / kompresi baru."
            )
        }

        // 5. Block single-candle volume spike without flow persistence
        if (flowContext.volumeTrend > 2.2 && flowContext.flowPersistence <= 1) {
            return Pair(true, "Volume spike mendadak tanpa persistensi flow. Berisiko pump sesaat.")
        }

        // 6. Block if current candle is overly impulsive (> 3.5% body in 1 candle)
        if (candleSpreadPct > 3.5) {
            return Pair(true, "Lilin terlalu impulsif (+${String.format(java.util.Locale.US, "%.1f", candleSpreadPct)}%). Tunggu candle tenang.")
        }

        // 7. Block if Net Risk/Reward ratio is unfavorable (< 1.0)
        if (netRewardRiskRatio < 1.0 && netRewardRiskRatio > 0.0) {
            return Pair(true, "Risk/Reward tidak memadai (Net R:R < 1:1.0). Menunggu harga masuk zona ideal.")
        }

        return Pair(false, "Anti-FOMO PASS: Struktur trench, flow & risk terkonfirmasi aman.")
    }
}

