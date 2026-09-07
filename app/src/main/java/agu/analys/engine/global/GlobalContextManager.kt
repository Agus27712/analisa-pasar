package agu.analys.engine.global

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object GlobalContextManager {
    private val globalWebSocket = GlobalMarketWebSocket()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _context = MutableStateFlow(GlobalMarketContext())
    val context: StateFlow<GlobalMarketContext> = _context.asStateFlow()

    private val priceHistory = mutableListOf<PriceTick>()
    private val HISTORY_WINDOW_MS = 3 * 60 * 1000L // 3 minutes window for crash detection

    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true
        
        globalWebSocket.connect()

        scope.launch {
            globalWebSocket.btcTickerFlow.collectLatest { ticker ->
                if (ticker == null) return@collectLatest
                
                val now = System.currentTimeMillis()
                priceHistory.add(PriceTick(ticker.price, now))
                
                // Cleanup old ticks
                priceHistory.removeAll { now - it.timestamp > HISTORY_WINDOW_MS }
                
                val currentContext = evaluateGlobalContext(ticker, now).copy(
                    isConnected = true,
                    dataSource = "Binance"
                )
                _context.value = currentContext
            }
        }
        
        scope.launch {
            globalWebSocket.isConnected.collectLatest { connected ->
                if (!connected) {
                    if (_context.value.dataSource.startsWith("Binance")) {
                        _context.value = _context.value.copy(isConnected = false)
                    }
                } else {
                    _context.value = _context.value.copy(isConnected = true)
                }
            }
        }
    }

    /**
     * Fallback cerdas ke data live BTC Indodax jika WebSocket global (Binance) diblokir oleh ISP Indonesia atau belum terhubung.
     */
    fun updateFallbackFromIndodax(priceIdr: Double, changePct: Double, usdtRate: Double = 16200.0) {
        val now = System.currentTimeMillis()
        val isBinanceActive = globalWebSocket.isConnected.value && 
                _context.value.dataSource.startsWith("Binance") && 
                (now - _context.value.lastUpdateTime < 20_000L)
        
        // Jika Binance sedang aktif live streaming, prioritaskan Binance sepenuhnya
        if (isBinanceActive) return
        if (priceIdr <= 0) return

        val effectiveUsdtRate = if (usdtRate > 0) usdtRate else 16200.0
        val priceUsdt = priceIdr / effectiveUsdtRate

        priceHistory.add(PriceTick(priceUsdt, now))
        priceHistory.removeAll { now - it.timestamp > HISTORY_WINDOW_MS }

        val indodaxTicker = BtcTickerData(price = priceUsdt, changePct = changePct, source = "Indodax")
        val currentContext = evaluateGlobalContext(indodaxTicker, now).copy(
            isConnected = true,
            dataSource = "Indodax"
        )
        _context.value = currentContext
    }

    private fun evaluateGlobalContext(ticker: BtcTickerData, now: Long): GlobalMarketContext {
        var regime = GlobalRegime.SIDEWAYS
        var isVeto = false
        var vetoReason: String? = null

        if (ticker.changePct > 2.0) {
            regime = GlobalRegime.BULLISH
        } else if (ticker.changePct < -2.0) {
            regime = GlobalRegime.BEARISH
        }

        // Flash Crash Detection: Check if price dropped significantly in the last 3 minutes
        if (priceHistory.isNotEmpty()) {
            val oldestTick = priceHistory.first()
            val dropPct = ((oldestTick.price - ticker.price) / oldestTick.price) * 100.0
            
            // If BTC drops more than 1.5% in 3 minutes, trigger Global Crash Shield (Veto)
            if (dropPct > 1.5 || ticker.changePct < -7.0) {
                regime = GlobalRegime.FLASH_CRASH
                isVeto = true
                vetoReason = "Global Flash Crash (BTC Drop: ${String.format("%.2f", dropPct)}% / 3m)"
            }
        }

        return GlobalMarketContext(
            btcPriceUsdt = ticker.price,
            btc24hChangePct = ticker.changePct,
            regime = regime,
            isVetoActive = isVeto,
            vetoReason = vetoReason,
            lastUpdateTime = now,
            isConnected = _context.value.isConnected,
            dataSource = ticker.source
        )
    }

    data class PriceTick(val price: Double, val timestamp: Long)
}
