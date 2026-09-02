package agu.analys.model

import agu.analys.config.StrategyMode

/**
 * Enum tipe badge koin yang didukung oleh sistem analisa-pasar.
 */
enum class BadgeType(
    val label: String,
    val defaultPriority: Int
) {
    OFFICEDAILY("OFFICE DAILY", 1),
    SECONDWAVE("2ND WAVE", 2),
    SWING("SWING", 3),
    SCALPING("SCALPING", 4),
    READY("SIAP ENTRY", 5),
    HOT("HOT", 6),
    PUMP("BREAKOUT", 7),
    VOL24("HIGH VOL", 8),
    DUMP("PULLBACK", 9)
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
