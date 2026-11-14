package agu.analys.bridge

import kotlinx.serialization.Serializable

/**
 * Model data pemindaian seluruh pair pasar dan pemilihan 1 pair oleh agen.
 *
 * Berkas ini **murni Kotlin + kotlinx.serialization** (tanpa dependensi Android) supaya bisa
 * ikut disalin ke proyek smoke (`tools/bridge-smoke`) dan menjamin kontrak JSON yang identik.
 *
 * Seluruh data pemindaian dibatasi ke **satu bursa aktif** (Tokocrypto atau Indodax), tidak
 * pernah mencampur dua bursa dalam satu payload.
 */

@Serializable
data class AgentMarketPairItem(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val price: Double,
    val change24h: Double,
    val high24h: Double,
    val low24h: Double,
    val volume24h: Double,
    val volumeIdr: Double,
    val rangePositionPct: Double,
    val serverScore: Double,
    val momentumTier: String,
    val reasonSummary: String
)

@Serializable
data class AgentMarketScanCandidate(
    val rank: Int,
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val exchange: String,
    val price: Double,
    val change24h: Double,
    val volume24h: Double,
    val volumeIdr: Double,
    val rangePositionPct: Double,
    val serverScore: Double,
    val momentumTier: String,
    val reasonSummary: String,
    val engineAnalysis: AgentEngineAnalysis? = null
)

@Serializable
data class AgentMarketScanOutput(
    val ts: Long,
    val available: Boolean = true,
    val exchange: String,
    val usdtIdrRate: Double = 0.0,
    val totalPairsScanned: Int = 0,
    val bullishCount: Int = 0,
    val marketSummary: String = "",
    val topCandidates: List<AgentMarketScanCandidate> = emptyList(),
    val pairs: List<AgentMarketPairItem> = emptyList(),
    val error: String? = null,
    val type: String = "market_scan"
)

@Serializable
data class AgentMarketPairsResponse(
    val ts: Long,
    val available: Boolean = true,
    val exchange: String,
    val usdtIdrRate: Double = 0.0,
    val totalPairs: Int = 0,
    val pairs: List<AgentMarketPairItem> = emptyList(),
    val error: String? = null
)

@Serializable
data class AgentPairSelectionRequest(
    val symbol: String,
    val reason: String? = null
)

@Serializable
data class AgentPairSelection(
    val symbol: String,
    val exchange: String,
    val reason: String? = null,
    val selectedAt: Long = System.currentTimeMillis(),
    val cleared: Boolean = false,
    val type: String = "agent_selection"
)

@Serializable
data class AgentPairSelectionResponse(
    val success: Boolean,
    val message: String,
    val selection: AgentPairSelection? = null
)
