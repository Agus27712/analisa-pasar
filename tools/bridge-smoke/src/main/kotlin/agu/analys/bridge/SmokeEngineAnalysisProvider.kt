package agu.analys.bridge

import android.content.Context

/**
 * Stub smoke untuk `AgentEngineAnalysisProvider` produksi.
 *
 * Provider asli (`app/src/main/java/agu/analys/bridge/AgentEngineAnalysisProvider.kt`)
 * sengaja **tidak** di-copy ke project smoke karena ia menarik seluruh mesin engine +
 * layanan bursa + preferensi terenkripsi. Yang di-copy hanya model DTO-nya
 * (`AgentEngineAnalysisModels.kt`), supaya kontrak JSON yang diuji smoke sama dengan
 * produksi — persis pola yang dipakai berkas produksi lain di project ini.
 *
 * Stub ini harus **sejalan** dengan provider asli: nama fungsi, parameter, dan bentuk
 * hasil. Kalau provider asli berubah, stub ini harus ikut berubah atau smoke tidak
 * menguji apa pun.
 */
object AgentEngineAnalysisProvider {

    suspend fun engineAnalysis(
        context: Context,
        symbol: String,
        exchange: String? = null
    ): AgentEngineAnalysis = AgentEngineAnalysis(
        symbol = symbol,
        available = true,
        source = "SMOKE_FIXTURE",
        exchange = exchange ?: "TOKOCRYPTO",
        price = 100.0,
        scalping = AgentScalpingAnalysis(
            action = "BUY",
            stage = "ENTRY",
            confidence = 85,
            setup = "Breakout Retest"
        ),
        intraday = AgentIntradayAnalysis(
            action = "BUY",
            confidence = 75,
            setup = "Session High Reclaim"
        ),
        swing = AgentSwingAnalysis(
            action = "HOLD",
            confidence = 60,
            trend = "Bullish Continuation"
        ),
        indicators = AgentTechnicalIndicators(
            rsi14 = 52.5,
            macd = 0.45,
            ema20 = 99.2,
            ema50 = 98.0,
            ema200 = 95.0
        ),
        overallBias = "BULLISH_CONFLUENCE"
    )
}
