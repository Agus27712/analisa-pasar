package agu.analys.model

enum class SellLifecycleState(val displayName: String) {
    NOT_HOLDING("TIDAK PUNYA POSISI"),
    MONITORING("MEMANTAU"),
    APPROACHING_TARGET("MENDEKATI TARGET"),
    READY_TO_SELL("SIAP JUAL"),
    STOP_LOSS_HIT("STOP LOSS TERSENTUH"),
    TRAILING_TRIGGERED("TRAILING STOP TERPICU")
}

data class SellSignalState(
    val state: SellLifecycleState = SellLifecycleState.NOT_HOLDING,
    val reason: String = "",
    val netProfitPct: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis()
)
