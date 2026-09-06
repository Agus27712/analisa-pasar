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
                
                val currentContext = evaluateGlobalContext(ticker, now)
                _context.value = currentContext
            }
        }
        
        scope.launch {
            globalWebSocket.isConnected.collectLatest { connected ->
                _context.value = _context.value.copy(isConnected = connected)
            }
        }
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
