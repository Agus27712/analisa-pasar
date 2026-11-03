package agu.analys.bridge

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * Central event bus buat push sinyal & portofolio ke WebSocket/SSE (Agent Bridge V2).
 */
object AgentEventBus {
    // Replay 10 agar client baru terkoneksi langsung mendapatkan riwayat sinyal terbaru
    private val _signals = MutableSharedFlow<AgentSignalOutput>(replay = 10, extraBufferCapacity = 100)
    val signals = _signals.asSharedFlow()

    private val _portfolio = MutableSharedFlow<AgentPortfolioOutput>(replay = 1, extraBufferCapacity = 10)
    val portfolio = _portfolio.asSharedFlow()

    suspend fun emitSignal(s: AgentSignalOutput) = _signals.emit(s)
    suspend fun emitPortfolio(p: AgentPortfolioOutput) = _portfolio.emit(p)
}

@Serializable
data class AgentSignalOutput(
    val ts: Long,
    val exchange: String,
    val symbol: String,
    val quote: String,
    val mode: String,
    val action: String,
    val confidence: Float,
    val price: Double,
    val tp1: Double? = null,
    val tp2: Double? = null,
    val sl: Double? = null,
    val reason: String,
    val technical: TechnicalBreakdown? = null,
    val feeTruth: FeeTruthInfo? = null,
    val type: String = "signal" // Memudahkan filter bagi client WebSocket
)

@Serializable
data class TechnicalBreakdown(
    val rsi: Float? = null,
    val macd: String? = null,
    val emaTrend: String? = null,
    val volumeSpike: Boolean = false,
    val orderbookPressure: String? = null
)

@Serializable
data class FeeTruthInfo(
    val feeRate: Double,
    val netPrice: Double,
    val exchange: String
)

@Serializable
data class AgentPortfolioOutput(
    val ts: Long,
    val exchange: String,
    val totalIdrValue: Double,
    val totalUsdtValue: Double,
    val assets: List<AgentAsset>,
    val type: String = "portfolio"
)

@Serializable
data class AgentAsset(
    val symbol: String,
    val free: Double,
    val locked: Double,
    val avgBuyPrice: Double,
    val currentPrice: Double,
    val pnlPercent: Double,
    val pnlValue: Double
)

@Serializable
data class AgentMarketContext(
    val ts: Long,
    val exchange: String,
    val topVolume: List<String>,
    val activeSignals: List<AgentSignalOutput>,
    val type: String = "market"
)

@Serializable
data class AgentEnvelope(
    val event: String, // signal | portfolio | market | ping
    val data: String, // json stringified inner payload
    val ts: Long = System.currentTimeMillis()
)
