package agu.analys.engine.trenching

object TrenchingPositionSizer {
    /**
     * Menghitung faktor alokasi posisi berdasarkan kualitas trench dan flow state
     * Sesuai Master Prompt Bagian 13:
     * - WEAK -> 25% (0.25)
     * - VALID -> 50% (0.50)
     * - STRONG -> 75% (0.75)
     * - EXPANSION -> 100% (1.00)
     */
    fun calculateAllocation(
        trenchQuality: TrenchQuality,
        flowState: FlowState
    ): Double {
        var baseAllocation = when (trenchQuality) {
            TrenchQuality.INVALID -> 0.0
            TrenchQuality.WEAK -> 0.25
            TrenchQuality.VALID -> 0.50
            TrenchQuality.STRONG -> 0.75
            TrenchQuality.EXPANSION -> 1.00
        }

        // Penyesuaian dinamis berdasarkan kesiapan flow
        when (flowState) {
            FlowState.EXPANDING -> baseAllocation *= 1.0
            FlowState.ACCUMULATING -> baseAllocation *= 0.9
            FlowState.ABSORBING -> baseAllocation *= 0.75 // Sedang kompresi, batasi eksposur awal
            FlowState.WEAKENING, FlowState.EXHAUSTING -> baseAllocation *= 0.35
            FlowState.REVERSING, FlowState.NEUTRAL -> baseAllocation = 0.0
        }

        return baseAllocation.coerceIn(0.0, 1.0)
    }

    /**
     * Menghitung nilai rupiah posisi berbasis risiko akun & jarak stop loss
     * Sesuai Master Prompt Bagian 14:
     * riskAmount = accountBalance * maxRiskPerTrade
     * rawSize = riskAmount / stopDistancePct
     * positionSize = rawSize * trenchAllocationFactor
     */
    fun calculateRiskBasedPositionSize(
        accountBalance: Double,
        maxRiskPerTradePct: Double = 0.02, // 2% resiko modal default
        entryPrice: Double,
        stopLossPrice: Double,
        trenchQuality: TrenchQuality,
        flowState: FlowState
    ): Double {
        if (accountBalance <= 0 || entryPrice <= 0 || stopLossPrice >= entryPrice || stopLossPrice <= 0) {
            return 0.0
        }

        val stopDistancePct = (entryPrice - stopLossPrice) / entryPrice
        if (stopDistancePct <= 0.001) return 0.0

        val riskBudgetAmount = accountBalance * maxRiskPerTradePct.coerceIn(0.005, 0.05)
        val rawPositionSize = riskBudgetAmount / stopDistancePct
        val allocationFactor = calculateAllocation(trenchQuality, flowState)

        // Batasi ukuran posisi maksimal 25% dari total saldo per koin tunggal untuk money management yang sehat
        val maxSingleAssetAllocation = accountBalance * 0.25
        val finalPositionSize = (rawPositionSize * allocationFactor).coerceAtMost(maxSingleAssetAllocation)

        return finalPositionSize.coerceAtLeast(0.0)
    }
}

