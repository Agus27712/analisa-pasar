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
    /**
     * Target net R:R default.
     *
     * Dinaikkan 1.25 → **2.0** (2026-10-12) berdasar uji data riil 30 hari, 40 pair Tokocrypto:
     * dengan target 1.25 win-rate minimum agar impas adalah `w* = 1/(1+1.25) ≈ 44%`, sedangkan
     * engine hanya mencapai ~22–34%. Menaikkan target menurunkan `w*` dan, digabung lantai risiko
     * berbasis friksi ([MIN_FRICTION_MULTIPLE]), mengubah avg R dari negatif menjadi sedikit positif.
     */
    const val TARGET_NET_RR = 2.0
    /**
     * Lantai risiko **absolut** (persen harga) — cadangan bila friksi sangat kecil.
     *
     * Tetap 0,5 karena penjaga utama adalah [MIN_FRICTION_MULTIPLE] yang adaptif per bursa.
     * Uji 2026-10-12: lantai absolut 3,0% dikombinasikan dengan penjaga relatif memberi hasil
     * **lebih buruk** di pair IDR berbiaya rendah (penjaga relatif jadi tidak berperan).
     */
    const val MIN_RISK_PCT = 0.5
    const val MAX_TP2_R = 5.0
    const val DEFAULT_SLIPPAGE_PCT = 0.08
    /**
     * **Lantai risiko berbasis friksi** — kelipatan minimum jarak stop loss terhadap biaya
     * round-trip (fee all-in + 2× slippage).
     *
     * Alasan (uji 2026-10-12): dengan `minRiskPct` 0,5% dan friksi Tokocrypto USDT 0,9688%,
     * satu kali kena SL kehilangan `0,5% + 0,97% = 1,47%` ≈ **2,9R** — unit risiko kalah besar
     * dari biaya. Baseline 30 hari × 28 pair: **0 dari 28 pair** punya avg R positif (avg R −1,22).
     *
     * Nilai **6,0** dipilih dari uji sweep 2026-10-12 (30 hari, 40 pair Tokocrypto):
     * terdapat **plateau lebar 5–10×**, dengan 6× memberi hasil terbaik untuk pair USDT
     * (avg R +0,0695, **6 dari 6 pair positif**) dan masih baik untuk IDR maker (+0,0430).
     * Di bawah 5× hasilnya belum konsisten positif; di atas 10× jumlah trade menyusut
     * tanpa perbaikan berarti. Lihat `workplan2.md` §9.
     *
     * Efek lantai: USDT 0,9688% → ≈5,81% · IDR taker 0,8144% → ≈4,89% · IDR maker 0,6144% → ≈3,69%.
     */
    const val MIN_FRICTION_MULTIPLE = 6.0

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
