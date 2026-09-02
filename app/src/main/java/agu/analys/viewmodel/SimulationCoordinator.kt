package agu.analys.viewmodel

import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.trading.SimulationTradeStore
import agu.analys.trading.SimulationWallet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SimulationCoordinator(private val store: SimulationTradeStore) {
    private val _wallet = MutableStateFlow(store.getWallet())
    val wallet: StateFlow<SimulationWallet> = _wallet.asStateFlow()

    private val _openOrders = MutableStateFlow(store.getOpenOrders())
    val openOrders: StateFlow<List<SimulationOrder>> = _openOrders.asStateFlow()

    private val _history = MutableStateFlow(store.getTradeHistory())
    val history: StateFlow<List<SimulationTradeHistoryItem>> = _history.asStateFlow()

    private val _lastFilledOrder = MutableStateFlow<SimulationOrder?>(null)
    val lastFilledOrder: StateFlow<SimulationOrder?> = _lastFilledOrder.asStateFlow()

    fun refresh() {
        _wallet.value = store.getWallet()
        _openOrders.value = store.getOpenOrders()
        _history.value = store.getTradeHistory()
    }

    fun submitOrder(
        pair: TradingPair,
        currentPrice: Double,
        side: SimulationOrderSide,
        type: SimulationOrderType,
        price: Double,
        stopPrice: Double = 0.0,
        quantity: Double
    ): SimulationOrderResult {
        val execPrice = if (currentPrice > 0.0) currentPrice else price
        val result = store.placeOrder(
            symbol = pair.symbol,
            baseAsset = pair.baseAsset,
            quoteAsset = pair.quoteAsset,
            side = side,
            type = type,
            price = price,
            stopPrice = stopPrice,
            quantity = quantity,
            currentMarketPrice = execPrice
        )
        refresh()
        // P2.2 Lifecycle
        if (result is agu.analys.trading.SimulationOrderResult.Success) {
            agu.analys.engine.scalping.SignalLifecycleManager.markTriggered(pair.symbol)
        }
        return result
    }

    fun cancelOrder(orderId: String): Boolean {
        val ok = store.cancelOrder(orderId)
        if (ok) refresh()
        return ok
    }

    fun cancelAllOrders(symbol: String? = null): Int {
        val count = store.cancelAllOrders(symbol)
        if (count > 0) refresh()
        return count
    }

    fun placeSimulationAutoSellOrders(
        pair: agu.analys.model.TradingPair,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        onResult: (Boolean, String) -> Unit
    ) {
        val wallet = store.getWallet()
        val availableCoin = wallet.getAvailableCoin(pair.baseAsset)
        if (availableCoin <= 0.0) {
            onResult(false, "Saldo koin ${pair.baseAsset} kosong, tidak dapat pasang limit sell TP.")
            return
        }
        val qty1 = availableCoin * (tp1Percent / 100.0)
        val qty2 = availableCoin * (tp2Percent / 100.0)

        var successCount = 0
        var msg = ""

        if (tp1Price > 0.0 && qty1 > 0.0) {
            val res = store.placeOrder(
                symbol = pair.symbol,
                baseAsset = pair.baseAsset,
                quoteAsset = pair.quoteAsset,
                side = agu.analys.trading.SimulationOrderSide.SELL,
                type = agu.analys.trading.SimulationOrderType.LIMIT,
                price = tp1Price,
                stopPrice = 0.0,
                quantity = qty1,
                currentMarketPrice = 0.0
            )
            if (res is agu.analys.trading.SimulationOrderResult.Success) {
                successCount++
                msg += "TP1 OK. "
            } else if (res is agu.analys.trading.SimulationOrderResult.Error) {
                msg += "TP1 Gagal: ${res.message}. "
            }
        }

        if (tp2Price > 0.0 && qty2 > 0.0) {
            val res = store.placeOrder(
                symbol = pair.symbol,
                baseAsset = pair.baseAsset,
                quoteAsset = pair.quoteAsset,
                side = agu.analys.trading.SimulationOrderSide.SELL,
                type = agu.analys.trading.SimulationOrderType.LIMIT,
                price = tp2Price,
                stopPrice = 0.0,
                quantity = qty2,
                currentMarketPrice = 0.0
            )
            if (res is agu.analys.trading.SimulationOrderResult.Success) {
                successCount++
                msg += "TP2 OK."
            } else if (res is agu.analys.trading.SimulationOrderResult.Error) {
                msg += "TP2 Gagal: ${res.message}."
            }
        }

        refresh()
        onResult(successCount > 0, if (successCount > 0) "Order TP Berhasil dipasang! $msg" else "Gagal pasang order TP: $msg")
    }

    fun topUpIdr(amount: Double) {
        store.topUpIdr(amount)
        refresh()
    }

    fun resetAccount() {
        store.resetWallet()
        refresh()
    }

    fun onPriceTick(symbol: String, price: Double, high24h: Double, low24h: Double) {
        val filled = store.processPriceTick(symbol, price, high24h, low24h)
        if (filled.isNotEmpty()) {
            _lastFilledOrder.value = filled.lastOrNull()
            refresh()
        }
    }
}
