package agu.analys.model

import agu.analys.config.StrategyMode

/**
 * Enum tipe badge koin yang didukung oleh sistem analisa-pasar.
 */
enum class BadgeType(
    val label: String,
    val defaultPriority: Int
) {
    OFFICEDAILY("OFFICEDAILY", 1),
    SECONDWAVE("SECONDWAVE", 2),
    SWING("SWING", 3),
    SCALPING("SCALPING", 4),
    READY("READY", 5),
    HOT("HOT", 6),
    PUMP("PUMP", 7),
    VOL24("VOL24", 8),
    DUMP("DUMP", 9)
}

/**
 * Representasi badge yang terasosiasi dengan sebuah koin / pair.
 */
data class CoinBadge(
    val type: BadgeType,
    val label: String = type.label,
    val priority: Int = type.defaultPriority,
    val description: String = ""
)

typealias StrategyBadge = CoinBadge
