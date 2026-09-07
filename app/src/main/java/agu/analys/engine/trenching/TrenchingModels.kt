package agu.analys.engine.trenching

enum class FlowState {
    NEUTRAL,
    ACCUMULATING,
    ABSORBING,
    EXPANDING,
    WEAKENING,
    EXHAUSTING,
    REVERSING
}

enum class TrenchQuality {
    WEAK,
    VALID,
    STRONG,
    EXPANSION,
    INVALID
}

enum class TrenchingState {
    SCANNING,
    FLOW_BUILDING,
    TRENCH_FORMING,
    PULLBACK,
    FLOW_RETURNING,
    ENTRY_READY,
    IN_POSITION,
    PROFIT_PROTECTION,
    FLOW_FAILURE,
    EXIT
}

enum class TrenchingExitState {
    HOLD,
    PROTECT_PROFIT,
    REDUCE,
    EXIT
}

enum class DataQuality {
    LOW,
    MEDIUM,
    HIGH
}

data class TrenchingSignal(
    val state: TrenchingState,
    val flowState: FlowState,
    val trenchQuality: TrenchQuality,
    val flowScore: Double,
    val trenchScore: Double,
    val entryScore: Double,
    val suggestedAllocation: Double,
    val antiFomoBlocked: Boolean,
    val exitState: TrenchingExitState,
    val confidence: Double,
    val reason: String
)
