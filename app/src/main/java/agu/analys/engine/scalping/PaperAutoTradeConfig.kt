package agu.analys.engine.scalping

import agu.analys.model.ScalpingStage

/**
 * Cara menentukan ukuran posisi simulasi saat auto-buy paper (P2).
 */
enum class PaperAutoBuyAllocation(val key: String, val label: String, val description: String) {
    FIXED_QUOTE(
        key = "fixed_quote",
        label = "Nominal tetap",
        description = "Setiap posisi memakai jumlah kas kuotasi yang sama"
    ),
    RISK_BASED(
        key = "risk_based",
        label = "Berbasis risiko",
        description = "Ukuran posisi dihitung dari jarak entry ke SL dan % risiko per trade"
    );

    companion object {
        fun fromKey(key: String?): PaperAutoBuyAllocation =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: FIXED_QUOTE
    }
}

/**
 * Pengaturan auto-buy & auto-exit **paper trade** (P2).
 *
 * Ini murni simulasi: tidak pernah memicu order uang riil. Larangan di `workplan2.md`
 * dan `AGENTS.md` tetap berlaku — real auto-execute dari sinyal scalp tidak diaktifkan
 * sebelum gate edge terpenuhi.
 */
data class PaperAutoTradeConfig(
    /** Bila aktif, posisi simulasi dibuka otomatis saat sinyal scalp terkonfirmasi. */
    val autoBuyEnabled: Boolean = false,
    /** Bila aktif, exit otomatis dijalankan memakai [ScalpingRuntimeConfig.exit]. */
    val autoExitEnabled: Boolean = true,
    val allocation: PaperAutoBuyAllocation = PaperAutoBuyAllocation.FIXED_QUOTE,
    /** Nominal kas kuotasi per posisi (mode nominal tetap). */
    val fixedQuoteAmount: Double = 100_000.0,
    /** Risiko per trade dalam % dari saldo kas kuotasi (mode berbasis risiko). */
    val riskPctPerTrade: Double = 1.0,
    /** Stage minimum sinyal yang boleh memicu auto-buy. */
    val minStage: ScalpingStage = ScalpingStage.ENTRY
) {
    /** Apakah stage sinyal memenuhi ambang minimum auto-buy. */
    fun stageQualifies(stage: ScalpingStage): Boolean = stage.ordinal >= minStage.ordinal

    /**
     * Hitung kuantitas posisi simulasi.
     *
     * @param availableQuote saldo kas kuotasi yang tersedia.
     * @param price harga eksekusi.
     * @param stopLoss harga SL (dipakai mode berbasis risiko).
     */
    fun quantityFor(availableQuote: Double, price: Double, stopLoss: Double): Double {
        if (price <= 0.0 || availableQuote <= 0.0) return 0.0
        return when (allocation) {
            PaperAutoBuyAllocation.FIXED_QUOTE -> {
                val budget = fixedQuoteAmount.coerceAtMost(availableQuote)
                budget / price
            }
            PaperAutoBuyAllocation.RISK_BASED -> {
                val riskPerUnit = price - stopLoss
                if (riskPerUnit <= 0.0) {
                    0.0
                } else {
                    val riskBudget = availableQuote * (riskPctPerTrade / 100.0)
                    val qtyByRisk = riskBudget / riskPerUnit
                    // Jangan pernah melebihi daya beli kas yang tersedia
                    qtyByRisk.coerceAtMost(availableQuote / price)
                }
            }
        }
    }

    companion object {
        val DEFAULT = PaperAutoTradeConfig()
    }
}
