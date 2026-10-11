package agu.analys.engine.scalping

import org.json.JSONObject

/**
 * Mode exit paper trade scalping (P2).
 *
 * Kunci `key` **wajib** sama dengan `cfg["exit"]["mode"]` di `tools/scalp_replay.py`
 * agar hasil paper di app bisa dibandingkan langsung dengan replay offline.
 * Lihat `SCALP_EXIT.md`.
 */
enum class ScalpExitMode(
    val key: String,
    val label: String,
    val description: String
) {
    CLASSIC(
        key = "classic",
        label = "Classic",
        description = "SL / TP2 saja (baseline lama)"
    ),
    TP1_BE(
        key = "tp1_be",
        label = "TP1 + BE",
        description = "TP1 partial 50%, lalu SL digeser ke breakeven net"
    ),
    EARLY(
        key = "early",
        label = "Early Profit",
        description = "Jual lebih awal bila profit net cukup dan momentum melemah"
    ),
    TRAIL(
        key = "trail",
        label = "Trailing",
        description = "Trailing % dari peak setelah trigger breakeven"
    ),
    FULL(
        key = "full",
        label = "Full (gabungan)",
        description = "tp1_be + trail + early (prioritas: SL > early > trail > TP2)"
    );

    /** Mode ini memakai partial TP1 50% + geser SL ke BE. */
    val usesTp1Partial: Boolean get() = this == TP1_BE || this == FULL

    /** Mode ini menggeser SL ke BE saat net profit menyentuh trigger. */
    val usesBreakeven: Boolean get() = this == TRAIL || this == EARLY || this == FULL

    /** Mode ini mengaktifkan trailing dari peak. */
    val usesTrailing: Boolean get() = this == TRAIL || this == FULL

    /** Mode ini mengaktifkan early profit exit. */
    val usesEarlyProfit: Boolean get() = this == EARLY || this == FULL

    companion object {
        fun fromKey(key: String?): ScalpExitMode =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: CLASSIC
    }
}

/**
 * Parameter exit paper trade — mirror blok `exit` di `data/config/scalp_config_exit_*.json`.
 *
 * `maxHoldBarsM1 = 0` berarti hanya dibatasi lookahead (tanpa time-stop).
 */
data class ScalpExitParams(
    val mode: ScalpExitMode = ScalpExitMode.CLASSIC,
    /** Profit net % minimum untuk memicu early SELL. */
    val minEarlyProfitNetPct: Double = DEFAULT_MIN_EARLY_PROFIT_NET_PCT,
    /** Net profit % yang meng-arm breakeven / trailing. */
    val breakevenTriggerNetPct: Double = DEFAULT_BREAKEVEN_TRIGGER_NET_PCT,
    /** Jarak trailing dari peak, dalam persen. */
    val trailPct: Double = DEFAULT_TRAIL_PCT,
    /** Time-stop dalam bar M1; 0 = nonaktif. */
    val maxHoldBarsM1: Int = DEFAULT_MAX_HOLD_BARS_M1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.key)
        put("min_early_profit_net_pct", minEarlyProfitNetPct)
        put("breakeven_trigger_net_pct", breakevenTriggerNetPct)
        put("trail_pct", trailPct)
        put("max_hold_bars_m1", maxHoldBarsM1)
    }

    companion object {
        const val DEFAULT_MIN_EARLY_PROFIT_NET_PCT = 0.45
        const val DEFAULT_BREAKEVEN_TRIGGER_NET_PCT = 0.40
        const val DEFAULT_TRAIL_PCT = 0.35
        const val DEFAULT_MAX_HOLD_BARS_M1 = 0

        val DEFAULT = ScalpExitParams()

        fun fromJson(json: JSONObject?): ScalpExitParams {
            if (json == null) return DEFAULT
            return ScalpExitParams(
                mode = ScalpExitMode.fromKey(json.optString("mode", ScalpExitMode.CLASSIC.key)),
                minEarlyProfitNetPct = json.optDouble(
                    "min_early_profit_net_pct", DEFAULT_MIN_EARLY_PROFIT_NET_PCT
                ),
                breakevenTriggerNetPct = json.optDouble(
                    "breakeven_trigger_net_pct", DEFAULT_BREAKEVEN_TRIGGER_NET_PCT
                ),
                trailPct = json.optDouble("trail_pct", DEFAULT_TRAIL_PCT),
                maxHoldBarsM1 = json.optInt("max_hold_bars_m1", DEFAULT_MAX_HOLD_BARS_M1)
            )
        }
    }
}
