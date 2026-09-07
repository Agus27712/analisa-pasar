package agu.analys.engine.global

enum class GlobalRegime {
    BULLISH,
    BEARISH,
    SIDEWAYS,
    FLASH_CRASH, // The VETO trigger for Buy orders
    FLASH_PUMP   // The VETO trigger for selling too early (optional)
}

data class BinanceCoinTicker(
    val symbol: String = "",
    val baseAsset: String = "",
    val price: Double = 0.0,
    val changePct24h: Double = 0.0,
    val timestamp: Long = 0L,
    val isAvailable: Boolean = true
)

data class GlobalMarketContext(
    val btcPriceUsdt: Double = 0.0,
    val btc24hChangePct: Double = 0.0,
    val regime: GlobalRegime = GlobalRegime.SIDEWAYS,
    val isVetoActive: Boolean = false,
    val vetoReason: String? = null,
    val lastUpdateTime: Long = 0L,
    val isConnected: Boolean = false,
    val dataSource: String = "Global",
    val activeCoinTicker: BinanceCoinTicker? = null
) {
    fun getVetoMessage(): String {
        return if (isVetoActive) "VETO AKTIF: $vetoReason" else "Aman (Tidak Ada Veto)"
    }
}
