package agu.analys.bridge

import agu.analys.model.AISignalState
import agu.analys.model.TechnicalIndicators
import agu.analys.service.TokocryptoMarketService

/**
 * Pemetaan hasil mesin → DTO Agent Bridge.
 *
 * Semua fungsi di sini **murni**: tidak menyentuh jaringan, state global, atau Android.
 * Satu-satunya ketergantungan luar adalah `TokocryptoMarketService.toTokocryptoSymbol`,
 * yang juga pemrosesan string murni — dipakai supaya aturan simbol tidak punya definisi kedua.
 * Itu disengaja supaya aturan "jangan asal memetakan angka sendiri" bisa dijaga uji
 * (`AgentEngineAnalysisTest`) tanpa emulator.
 *
 * Angka yang keluar dari sini **apa adanya** dari mesin (`AISignalState` /
 * `TechnicalIndicators`); bridge tidak menghitung ulang skor, R:R, atau indikator.
 */
object AgentEngineAnalysisMapper {

    /** Kuotasi yang dilayani aplikasi: Tokocrypto USDT + IDR, Indodax IDR saja. */
    private val KNOWN_QUOTES = listOf("USDT", "IDR")

    /**
     * Bersihkan simbol kiriman agent menjadi bentuk app (`BTCUSDT`, `BTCIDR`).
     *
     * Menerima `BTCUSDT`, `BTC/USDT`, `BTC_USDT`, `btcusdt`. Normalisasi memakai
     * [TokocryptoMarketService.toTokocryptoSymbol] — **satu definisi simbol** yang sama
     * dipakai layanan bursa, supaya tidak ada aturan kedua yang melenceng diam-diam.
     * Quote `BIDR` dilebur ke `IDR` (quote BIDR sudah dibuang di aplikasi), kecuali koin
     * yang **namanya** berakhiran BIDR (`BNBIDR`, `SHIBIDR`).
     *
     * @return simbol ternormalisasi, atau `null` bila kuotasinya tidak dikenal.
     */
    fun normalizeSymbol(raw: String): String? {
        val s = raw.trim().uppercase().replace("/", "").replace("_", "").replace("-", "")
        if (s.isEmpty()) return null
        val market = TokocryptoMarketService.toTokocryptoSymbol(s)
        return if (quoteSupported(quoteOf(market))) market else null
    }

    /** Kuotasi symbol ternormalisasi (`USDT`/`IDR`); string kosong bila tidak terbaca. */
    fun quoteOf(symbol: String): String {
        val s = symbol.uppercase()
        return when {
            s.endsWith("USDT") -> "USDT"
            s.endsWith("IDR") -> "IDR"
            else -> ""
        }
    }

    /** Kuotasi yang memang dilayani bursa tersebut. */
    fun quoteSupported(quote: String): Boolean = quote.uppercase() in KNOWN_QUOTES

    /**
     * Bias gabungan dari arah aksi tiga mode.
     *
     * `null` (mode tidak dievaluasi karena data kurang) **tidak** dihitung sebagai arah —
     * mode yang tidak ada bukan sinyal netral.
     */
    fun overallBias(actions: List<String>): String {
        val buys = actions.count { it == "BUY" }
        val sells = actions.count { it == "SELL" }
        return when {
            buys >= 2 -> "BULLISH_CONFLUENCE"
            buys == 1 && sells == 0 -> "LEAN_BULLISH"
            sells >= 2 -> "BEARISH_CONFLUENCE"
            sells == 1 && buys == 0 -> "LEAN_BEARISH"
            else -> "NEUTRAL_MIXED"
        }
    }

    /** Indikator mesin → DTO; `NaN`/tak terhingga menjadi `null` ("tidak tersedia"). */
    fun indicatorsOf(ind: TechnicalIndicators): AgentTechnicalIndicators = AgentTechnicalIndicators(
        rsi14 = ind.rsi14.validOrNull(),
        macd = ind.macd.validOrNull(),
        macdSignal = ind.macdSignal.validOrNull(),
        macdHist = ind.macdHist.validOrNull(),
        ema20 = ind.ema20.validOrNull(),
        ema50 = ind.ema50.validOrNull(),
        ema200 = ind.ema200.validOrNull(),
        bbUpper = ind.bbUpper.validOrNull(),
        bbLower = ind.bbLower.validOrNull(),
        atr = ind.atr.validOrNull(),
        rvol = ind.rvol.validOrNull(),
        vwap = ind.vwap.validOrNull()
    )

    /** Hasil mode SCALPING → DTO. `price` dipakai hanya bila sinyal tidak membawa entry. */
    fun scalpingOf(signal: AISignalState, price: Double): AgentScalpingAnalysis = AgentScalpingAnalysis(
        action = signal.action.name,
        stage = signal.scalpingStage.displayName,
        confidence = signal.confidence,
        setup = signal.scalpingSetup.ifBlank { signal.patternDetected.orEmpty() },
        score = signal.scalpingScore,
        scoreCategory = signal.scalpingScoreCategory,
        regime = signal.scalpingRegime.ifBlank { signal.regimeDetected },
        entryPrice = if (signal.entryPrice > 0.0) signal.entryPrice else price,
        entryZoneLow = signal.entryZoneLow,
        entryZoneHigh = signal.entryZoneHigh,
        tp1 = signal.targetPrice1,
        tp2 = signal.targetPrice2,
        sl = signal.stopLoss,
        riskReward = signal.riskRewardRatio,
        checkpoints = signal.mtf.resolvedCheckpoints().map {
            AgentCheckpoint(
                code = it.code,
                label = it.label,
                isOk = it.isOk,
                metricValue = it.metricValue,
                detail = it.detail
            )
        },
        reasoning = signal.reasoning
    )

    /** Hasil mode INTRADAY → DTO. */
    fun intradayOf(signal: AISignalState): AgentIntradayAnalysis = AgentIntradayAnalysis(
        action = signal.action.name,
        confidence = signal.confidence,
        setup = signal.patternDetected ?: "Intraday Momentum",
        session = "H4 / Daily Session",
        targetPrice = signal.targetPrice1,
        stopLoss = signal.stopLoss,
        riskReward = signal.riskRewardRatio,
        reasoning = signal.reasoning
    )

    /** Hasil mode SWING → DTO; support/resistance diambil dari Bollinger Band mesin. */
    fun swingOf(signal: AISignalState, indicators: TechnicalIndicators): AgentSwingAnalysis =
        AgentSwingAnalysis(
            action = signal.action.name,
            confidence = signal.confidence,
            trend = signal.sentiment.displayName,
            regime = signal.regimeDetected,
            support = indicators.bbLower.validOrNull() ?: 0.0,
            resistance = indicators.bbUpper.validOrNull() ?: 0.0,
            targetPrice = signal.targetPrice1,
            stopLoss = signal.stopLoss,
            reasoning = signal.reasoning
        )

    private fun Double.validOrNull(): Double? = if (isNaN() || isInfinite()) null else this
}
