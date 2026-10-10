package agu.analys.engine.scalping

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Runtime configuration for scalping engine sensitivity (P1).
 *
 * Seeded from [ScalpingConfig] defaults.
 * Allows interactive adjustments via UI sliders, presets (Strict / Default / Loose),
 * and JSON import/export compatible with `data/config/scalp_config.json` and `tools/scalp_replay.py`.
 */
data class ScalpingRuntimeConfig(
    val minScoreLong: Int = ScalpingConfig.MIN_SCORE_LONG,
    val minScoreStrong: Int = ScalpingConfig.MIN_SCORE_STRONG,
    val minScoreBreakoutRetest: Int = ScalpingConfig.MIN_SCORE_BREAKOUT_RETEST,
    val minNetRr: Double = ScalpingConfig.MIN_NET_RR,
    val targetNetRr: Double = ScalpingConfig.TARGET_NET_RR,
    val minRiskPct: Double = ScalpingConfig.MIN_RISK_PCT,
    val maxTp2R: Double = ScalpingConfig.MAX_TP2_R,
    val slippagePct: Double = ScalpingConfig.DEFAULT_SLIPPAGE_PCT,
    val rvolBreakoutMin: Double = ScalpingConfig.RVOL_BREAKOUT_MIN,
    val rvolSetupMin: Double = ScalpingConfig.RVOL_SETUP_MIN,
    val rvolRetestMin: Double = ScalpingConfig.RVOL_RETEST_MIN,
    val buyPressureBreakout: Double = ScalpingConfig.BUY_PRESSURE_BREAKOUT,
    val buyPressureRetest: Double = ScalpingConfig.BUY_PRESSURE_RETEST,
    val chopHardRanging: Double = ScalpingConfig.CHOP_HARD_RANGING,
    val retestMaxDistPct: Double = ScalpingConfig.RETEST_MAX_DIST_PCT,
    val breakoutAboveFactor: Double = ScalpingConfig.BREAKOUT_ABOVE_FACTOR,
    val retestAboveFactor: Double = ScalpingConfig.RETEST_ABOVE_FACTOR,
    val retestMinStructureStrength: Int = ScalpingConfig.RETEST_MIN_STRUCTURE_STRENGTH,
    val pullbackSupportMaxDistPct: Double = ScalpingConfig.PULLBACK_SUPPORT_MAX_DIST_PCT
) {

    fun minScoreForSetup(setupName: String): Int = when (setupName) {
        "BREAKOUT_RETEST" -> minScoreBreakoutRetest
        else -> minScoreLong
    }

    /**
     * Serializes to JSON matching the schema of `data/config/scalp_config.json`.
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("_comment", "Exported ScalpingRuntimeConfig from analisa-pasar Android")
        json.put("min_score_long", minScoreLong)
        json.put("min_score_strong", minScoreStrong)
        json.put("min_score_breakout_retest", minScoreBreakoutRetest)
        json.put("min_net_rr", minNetRr)
        json.put("target_net_rr", targetNetRr)
        json.put("min_risk_pct", minRiskPct)
        json.put("max_tp2_r", maxTp2R)
        json.put("slippage_pct", slippagePct)
        json.put("rvol_breakout_min", rvolBreakoutMin)
        json.put("rvol_setup_min", rvolSetupMin)
        json.put("rvol_retest_min", rvolRetestMin)
        json.put("buy_pressure_breakout", buyPressureBreakout)
        json.put("buy_pressure_retest", buyPressureRetest)
        json.put("chop_hard_ranging", chopHardRanging)
        json.put("retest_max_dist_pct", retestMaxDistPct)
        json.put("breakout_above_factor", breakoutAboveFactor)
        json.put("retest_above_factor", retestAboveFactor)
        json.put("retest_min_structure_strength", retestMinStructureStrength)
        json.put("pullback_support_max_dist_pct", pullbackSupportMaxDistPct)

        // Include Fee Truth snapshot block for offline replay parity
        val feeObj = JSONObject()
        feeObj.put("_comment", "Tokocrypto ALL-IN regular: USDT 0.4044% per sisi. IDR: buy taker 0.2222 / sell taker 0.4322")
        feeObj.put("quote", "USDT")
        feeObj.put("buy_taker_pct", 0.4044)
        feeObj.put("sell_taker_pct", 0.4044)
        feeObj.put("buy_maker_pct", 0.4044)
        feeObj.put("sell_maker_pct", 0.4044)
        json.put("fee", feeObj)

        val replayObj = JSONObject()
        replayObj.put("lookahead_bars", 120)
        replayObj.put("m1_window", 250)
        replayObj.put("m15_min_bars", 20)
        replayObj.put("h1_min_bars", 20)
        replayObj.put("htf_source", "synthesize")
        json.put("replay", replayObj)

        return json
    }

    fun toJsonString(indent: Int = 2): String = toJson().toString(indent)

    /** Short human-readable summary of primary gates */
    fun toSummaryString(): String {
        return "MinScore: $minScoreLong (Retest: $minScoreBreakoutRetest, Strong: $minScoreStrong) | " +
            "MinNetRR: ${minNetRr}x | RVOL Breakout: ${rvolBreakoutMin}x | CHOP: $chopHardRanging"
    }

    companion object {
        val DEFAULT = ScalpingRuntimeConfig()

        val STRICT = ScalpingRuntimeConfig(
            minScoreLong = 70,
            minScoreStrong = 80,
            minScoreBreakoutRetest = 80,
            minNetRr = 1.30,
            targetNetRr = 1.50,
            minRiskPct = 0.5,
            maxTp2R = 4.0,
            slippagePct = 0.08,
            rvolBreakoutMin = 1.8,
            rvolSetupMin = 1.4,
            rvolRetestMin = 1.8,
            buyPressureBreakout = 1.25,
            buyPressureRetest = 1.25,
            chopHardRanging = 60.0,
            retestMaxDistPct = 1.0,
            breakoutAboveFactor = 0.999,
            retestAboveFactor = 0.997,
            retestMinStructureStrength = 70,
            pullbackSupportMaxDistPct = 1.0
        )

        val LOOSE = ScalpingRuntimeConfig(
            minScoreLong = 50,
            minScoreStrong = 65,
            minScoreBreakoutRetest = 65,
            minNetRr = 1.00,
            targetNetRr = 1.15,
            minRiskPct = 0.4,
            maxTp2R = 5.0,
            slippagePct = 0.08,
            rvolBreakoutMin = 1.2,
            rvolSetupMin = 1.0,
            rvolRetestMin = 1.2,
            buyPressureBreakout = 1.05,
            buyPressureRetest = 1.05,
            chopHardRanging = 70.0,
            retestMaxDistPct = 1.5,
            breakoutAboveFactor = 0.995,
            retestAboveFactor = 0.990,
            retestMinStructureStrength = 50,
            pullbackSupportMaxDistPct = 1.5
        )

        fun fromJson(json: JSONObject): ScalpingRuntimeConfig {
            return ScalpingRuntimeConfig(
                minScoreLong = json.optInt("min_score_long", ScalpingConfig.MIN_SCORE_LONG),
                minScoreStrong = json.optInt("min_score_strong", ScalpingConfig.MIN_SCORE_STRONG),
                minScoreBreakoutRetest = json.optInt("min_score_breakout_retest", ScalpingConfig.MIN_SCORE_BREAKOUT_RETEST),
                minNetRr = json.optDouble("min_net_rr", ScalpingConfig.MIN_NET_RR),
                targetNetRr = json.optDouble("target_net_rr", ScalpingConfig.TARGET_NET_RR),
                minRiskPct = json.optDouble("min_risk_pct", ScalpingConfig.MIN_RISK_PCT),
                maxTp2R = json.optDouble("max_tp2_r", ScalpingConfig.MAX_TP2_R),
                slippagePct = json.optDouble("slippage_pct", ScalpingConfig.DEFAULT_SLIPPAGE_PCT),
                rvolBreakoutMin = json.optDouble("rvol_breakout_min", ScalpingConfig.RVOL_BREAKOUT_MIN),
                rvolSetupMin = json.optDouble("rvol_setup_min", ScalpingConfig.RVOL_SETUP_MIN),
                rvolRetestMin = json.optDouble("rvol_retest_min", ScalpingConfig.RVOL_RETEST_MIN),
                buyPressureBreakout = json.optDouble("buy_pressure_breakout", ScalpingConfig.BUY_PRESSURE_BREAKOUT),
                buyPressureRetest = json.optDouble("buy_pressure_retest", ScalpingConfig.BUY_PRESSURE_RETEST),
                chopHardRanging = json.optDouble("chop_hard_ranging", ScalpingConfig.CHOP_HARD_RANGING),
                retestMaxDistPct = json.optDouble("retest_max_dist_pct", ScalpingConfig.RETEST_MAX_DIST_PCT),
                breakoutAboveFactor = json.optDouble("breakout_above_factor", ScalpingConfig.BREAKOUT_ABOVE_FACTOR),
                retestAboveFactor = json.optDouble("retest_above_factor", ScalpingConfig.RETEST_ABOVE_FACTOR),
                retestMinStructureStrength = json.optInt("retest_min_structure_strength", ScalpingConfig.RETEST_MIN_STRUCTURE_STRENGTH),
                pullbackSupportMaxDistPct = json.optDouble("pullback_support_max_dist_pct", ScalpingConfig.PULLBACK_SUPPORT_MAX_DIST_PCT)
            )
        }

        fun fromJsonString(jsonStr: String): ScalpingRuntimeConfig? {
            return try {
                fromJson(JSONObject(jsonStr))
            } catch (_: Exception) {
                null
            }
        }

        private val _currentFlow = MutableStateFlow(DEFAULT)
        val currentFlow: StateFlow<ScalpingRuntimeConfig> = _currentFlow.asStateFlow()

        var current: ScalpingRuntimeConfig
            get() = _currentFlow.value
            set(value) {
                _currentFlow.value = value
            }
    }
}

enum class SensitivityPreset(val label: String, val description: String) {
    DEFAULT("Standar", "Threshold default ScalpingConfig (Balanced)"),
    STRICT("Ketat", "Threshold selektif — frekuensi rendah, sinyal terkuat"),
    LOOSE("Longgar", "Threshold longgar — frekuensi tinggi untuk eksperimen"),
    CUSTOM("Kustom", "Parameter disesuaikan lewat slider manual")
}
