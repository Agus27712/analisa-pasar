package agu.analys.engine.trenching

object TrenchingExitEvaluator {
    fun evaluateExit(
        hasPosition: Boolean,
        flowContext: FlowContext,
        entryPrice: Double,
        currentPrice: Double,
        supportLevel: Double = 0.0
    ): TrenchingExitState {
        if (!hasPosition || entryPrice <= 0.0) return TrenchingExitState.HOLD

        val pnlPct = ((currentPrice - entryPrice) / entryPrice) * 100.0

        // 1. HARD STOP / SUPPORT BREAKDOWN: Jika harga tembus support utama trench
        if (supportLevel > 0.0 && currentPrice < supportLevel) {
            return TrenchingExitState.EXIT
        }

        // 2. FLOW FAILURE vs PRICE DIP
        // Kasus Flow Failure: Penurunan harga disertai volume tinggi & reversal flow
        val isDownFromEntry = currentPrice < entryPrice
        if (isDownFromEntry) {
            if (flowContext.flowState == FlowState.REVERSING && flowContext.volumeTrend > 1.15) {
                // FLOW FAILURE SEJATI: Sell volume meledak dan bid runtuh -> CUT LOSS
                return TrenchingExitState.EXIT
            }

            if (flowContext.isHealthyPullback || (flowContext.volumeTrend < 0.8 && flowContext.orderBookScore >= 45.0)) {
                // HEALTHY PULLBACK: Harga terkoreksi tapi volume kering dan flow aman -> TAHAN (HOLD)
                return TrenchingExitState.HOLD
            }

            // Jika drawdown melebihi batas toleransi risiko (misal -3.5%)
            if (pnlPct < -3.5) {
                return TrenchingExitState.EXIT
            }
        }

        // 3. PROFIT PROTECTION: Posisi sedang profit tapi flow mulai kelelahan / melemah
        if (pnlPct >= 1.2) {
            if (flowContext.flowState == FlowState.REVERSING) {
                return TrenchingExitState.EXIT
            }
            if (flowContext.flowState == FlowState.WEAKENING || flowContext.flowState == FlowState.EXHAUSTING) {
                return TrenchingExitState.PROTECT_PROFIT
            }
            if (pnlPct >= 4.0 && flowContext.vwapDistancePct > 4.5) {
                // Extended rally jauh dari VWAP -> kunci sebagian profit
                return TrenchingExitState.PROTECT_PROFIT
            }
        }

        // 4. FLOW WEAKENING TANPA PROFIT: Kurangi eksposur jika flow mulai memburuk tanpa arah jelas
        if (flowContext.flowState == FlowState.WEAKENING && flowContext.flowPersistence == 0 && pnlPct in -1.5..1.0) {
            return TrenchingExitState.REDUCE
        }

        return TrenchingExitState.HOLD
    }
}

