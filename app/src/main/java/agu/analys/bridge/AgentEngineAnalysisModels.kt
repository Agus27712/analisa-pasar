package agu.analys.bridge

import kotlinx.serialization.Serializable

/**
 * Model analisis engine multi-mode (scalping / intraday / swing) yang dikirim ke agent
 * lewat Agent Bridge.
 *
 * Diteri port dari repo `agentfeed` (commit `51f9c39` — "feat: add engine analysis support")
 * lalu disesuaikan dengan model lokal:
 * - checkpoint MTF diambil dari `ScalpingMtfSnapshot.resolvedCheckpoints()`;
 * - `ScalpingStage.displayName` dan `TrendSentiment.displayName` dipakai apa adanya;
 * - field `exchange` ditambahkan karena aplikasi lokal melayani **dua** bursa
 *   (Tokocrypto + Indodax), bukan Tokocrypto saja.
 *
 * Berkas ini **murni Kotlin + kotlinx.serialization** supaya bisa ikut di-copy ke project
 * smoke (`tools/bridge-smoke`) tanpa stub Android.
 */

/** Satu checkpoint konfluensi MTF, apa adanya dari mesin — bukan dihitung ulang di bridge. */
@Serializable
data class AgentCheckpoint(
    val code: String,
    val label: String,
    val isOk: Boolean = false,
    val metricValue: String = "",
    val detail: String = ""
)

/**
 * Hasil mode SCALPING.
 *
 * `stage` = `ScalpingStage.displayName` (TAHAN / WATCH / TUNGGU PULLBACK / AWAL ENTRY / ENTRY /
 * ENTRY KUAT). `score` = skor machinery engine (bukan confidence), jadi agent tidak boleh
 * membacanya sebagai keyakinan.
 */
@Serializable
data class AgentScalpingAnalysis(
    val action: String = "HOLD",
    val stage: String = "HOLD",
    val confidence: Int = 0,
    val setup: String = "",
    val score: Int = 0,
    val scoreCategory: String = "",
    val regime: String = "",
    val entryPrice: Double = 0.0,
    val entryZoneLow: Double = 0.0,
    val entryZoneHigh: Double = 0.0,
    val tp1: Double = 0.0,
    val tp2: Double = 0.0,
    val sl: Double = 0.0,
    val riskReward: String = "",
    val checkpoints: List<AgentCheckpoint> = emptyList(),
    val reasoning: List<String> = emptyList()
)

/** Hasil mode INTRADAY (`StrategyMode.OFFICE_DAILY`). */
@Serializable
data class AgentIntradayAnalysis(
    val action: String = "HOLD",
    val confidence: Int = 0,
    val setup: String = "",
    val session: String = "",
    val targetPrice: Double = 0.0,
    val stopLoss: Double = 0.0,
    val riskReward: String = "",
    val reasoning: List<String> = emptyList()
)

/** Hasil mode SWING. */
@Serializable
data class AgentSwingAnalysis(
    val action: String = "HOLD",
    val confidence: Int = 0,
    val trend: String = "",
    val regime: String = "",
    val support: Double = 0.0,
    val resistance: Double = 0.0,
    val targetPrice: Double = 0.0,
    val stopLoss: Double = 0.0,
    val reasoning: List<String> = emptyList()
)

/**
 * Indikator teknikal nyata hasil hitungan mesin.
 *
 * Nilai `null` berarti **tidak tersedia** — bukan nol. Indikator yang tidak punya angka
 * (mis. EMA200 saat riwayat belum cukup) dibiarkan `null` supaya agent tidak salah baca
 * "0" sebagai "indikator mepet nol".
 */
@Serializable
data class AgentTechnicalIndicators(
    val rsi14: Double? = null,
    val macd: Double? = null,
    val macdSignal: Double? = null,
    val macdHist: Double? = null,
    val ema20: Double? = null,
    val ema50: Double? = null,
    val ema200: Double? = null,
    val bbUpper: Double? = null,
    val bbLower: Double? = null,
    val atr: Double? = null,
    val rvol: Double? = null,
    val vwap: Double? = null
)

/**
 * Analisis lengkap satu pair dari tiga engine.
 *
 * `available = false` berarti analisis **tidak** bisa dihitung (simbol tak dikenal, ticker
 * mati, atau bursa tidak melayani pair itu) dan `error` menjelaskan sebabnya. `available = true`
 * dengan `scalping/intraday/swing = null` berarti data candle belum cukup untuk mode itu —
 * dua keadaan berbeda dan tidak boleh dicampur.
 */
@Serializable
data class AgentEngineAnalysis(
    val symbol: String,
    val available: Boolean,
    val source: String = "ENGINE_EVALUATOR",
    val exchange: String = "",
    val price: Double = 0.0,
    val scalping: AgentScalpingAnalysis? = null,
    val intraday: AgentIntradayAnalysis? = null,
    val swing: AgentSwingAnalysis? = null,
    val indicators: AgentTechnicalIndicators? = null,
    val overallBias: String = "NEUTRAL",
    val ts: Long = System.currentTimeMillis(),
    val error: String? = null
)
