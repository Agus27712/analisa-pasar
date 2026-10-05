package agu.analys.engine.scalping

/**
 * Threshold terpusat engine scalping KriptoYoi (P2).
 *
 * Semua nilai berbasis **persen / ratio** (exchange-agnostic: IDR Indodax & USDT/IDR Tokocrypto).
 * Engine lain (Setup / Score / Risk / Regime) boleh baca dari sini agar tidak hardcode tersebar.
 *
 * Bobot score & kategori tetap di [SignalScoringEngine] / [ScoreBreakdown]; file ini
 * mengumpulkan **batas keputusan** (RVOL, ADX, CHOP, R:R, spread, dsb).
 */
object ScalpingConfig {

    // --- Score gate (mapping ke LONG) ---
    /** Skor minimum agar step4 / arah LONG dipertimbangkan (setup non-retest). */
    const val MIN_SCORE_LONG = 60
    /** Skor minimum untuk stage STRONG_ENTRY. */
    const val MIN_SCORE_STRONG = 75
    /**
     * Skor minimum khusus [BREAKOUT_RETEST].
     * Replay Tokocrypto: retest ~80% trade dengan avg skor ~67 tapi expectancy jelek.
     * Gate lebih tinggi dari MIN_SCORE_LONG agar hanya retest konfluensi kuat yang lolos.
     */
    const val MIN_SCORE_BREAKOUT_RETEST = 75

    // --- Risk / R:R ---
    const val MIN_NET_RR = 1.15
    const val TARGET_NET_RR = 1.25
    const val MIN_RISK_PCT = 0.5
    const val MAX_TP2_R = 5.0
    const val DEFAULT_SLIPPAGE_PCT = 0.08

    // --- Volume (RVOL) ---
    const val RVOL_LOW = 0.7
    const val RVOL_NORMAL = 1.0
    const val RVOL_HIGH = 1.5
    const val RVOL_EXTREME = 2.5
    /** Minimum RVOL untuk konfirmasi breakout. */
    const val RVOL_BREAKOUT_MIN = 1.5
    /** Minimum RVOL untuk sweep / pullback recovery. */
    const val RVOL_SETUP_MIN = 1.2
    /** Minimum RVOL untuk BREAKOUT_RETEST (ketat — replay: retest longgar = −EV). */
    const val RVOL_RETEST_MIN = 1.5

    // --- Order flow ---
    /** Buy pressure (bid/ask volume ratio) minimum breakout. */
    const val BUY_PRESSURE_BREAKOUT = 1.15
    const val BUY_PRESSURE_SUPPORTIVE = 1.05
    const val BUY_PRESSURE_STRONG = 1.25
    /** Buy pressure minimum saat orderbook ada, untuk retest. */
    const val BUY_PRESSURE_RETEST = 1.15
    /** Order imbalance (bid-ask)/(bid+ask), range -1..+1. */
    const val IMBALANCE_MILD = 0.10
    const val IMBALANCE_STRONG = 0.28

    // --- Regime (CHOP / ADX / ATR%) ---
    const val CHOP_RANGING = 61.8
    /** CHOP di atas ini = ranging keras (hindari trend-follow murni). */
    const val CHOP_HARD_RANGING = 65.0
    const val ADX_TRENDING = 25.0
    const val ADX_WEAK = 20.0
    const val ATR_PCT_HIGH = 3.5
    const val ATR_PCT_LOW = 0.8
    const val ATR_PCT_SCALP_SWEET_LOW = 0.3
    const val ATR_PCT_SCALP_SWEET_HIGH = 1.5

    // --- Structure / setup jarak (%) ---
    /** Jarak maksimum ke level untuk dianggap "retest". */
    const val RETEST_MAX_DIST_PCT = 1.2
    /** Harga masih di atas level breakout (toleransi kecil). */
    const val BREAKOUT_ABOVE_FACTOR = 0.998
    /** Faktor minimum harga di atas level retest (0.995 = -0.5%). */
    const val RETEST_ABOVE_FACTOR = 0.995
    /** Kekuatan struktur minimum untuk retest heuristik. */
    const val RETEST_MIN_STRUCTURE_STRENGTH = 60
    const val PULLBACK_SUPPORT_MAX_DIST_PCT = 1.2

    // --- Order book / spread (selaras OrderBookAnalyzer defaults) ---
    const val SPREAD_TOLERANCE_PCT = 0.40
    const val SPREAD_GUARD_MAX_PCT = 1.20
    const val ORDERBOOK_STALE_MS = 30_000L

    // --- MTF ---
    /** Timeframe yang dipakai confluence scalping (kode internal). */
    val MTF_KEYS_DEFAULT: List<String> = listOf("1D", "4H", "1H", "15M", "5M", "1M")

    // --- Historical edge stub ---
    const val HISTORICAL_EDGE_INSUFFICIENT =
        "Belum cukup data historis untuk Historical Edge. Jangan menampilkan probabilitas palsu."

    /** Skor minimum step4 tergantung setup. */
    fun minScoreForSetup(setupName: String): Int = when (setupName) {
        "BREAKOUT_RETEST" -> MIN_SCORE_BREAKOUT_RETEST
        else -> MIN_SCORE_LONG
    }
}
