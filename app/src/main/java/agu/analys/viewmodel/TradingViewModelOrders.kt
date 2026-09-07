package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.TradingPair
import agu.analys.service.IndodaxMarketService
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import kotlinx.coroutines.launch

fun TradingViewModel.executeCancelRealOrder(symbol: String, orderId: String, onResult: (Boolean, String) -> Unit) =
    realCoordinator.executeCancelRealOrder(symbol, orderId, onResult)

fun TradingViewModel.checkPublicIp() = realCoordinator.checkPublicIp()
fun TradingViewModel.clearSecurityAlert() { /* handle locally if needed */ }
fun TradingViewModel.hasSecurityPin(): Boolean = prefs.hasSecurityPin()
fun TradingViewModel.hasRealCredentialsConfigured(): Boolean = prefs.hasIndodaxCredentials()
fun TradingViewModel.createSecurityPin(pin: String) { prefs.setSecurityPin(pin) }
fun TradingViewModel.saveRealCredentialsAndPin(pin: String, apiKey: String, secretKey: String) {
    prefs.setSecurityPin(pin)
    prefs.indodaxApiKey = apiKey
    prefs.indodaxSecretKey = secretKey
}
fun TradingViewModel.wipeSecurityCredentials() { prefs.wipeAllRealSecurityData() }
fun TradingViewModel.verifyPin(pin: String): Boolean = realCoordinator.verifyPin(pin)
fun TradingViewModel.lockPin() = realCoordinator.lockPin()
fun TradingViewModel.setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean = realCoordinator.setRealBuyMode(enabled, pin)
fun TradingViewModel.fetchRealBalance() = realCoordinator.fetchRealBalance()
fun TradingViewModel.refreshRealBalance() {
    viewModelScope.launch {
        val allTicks = IndodaxMarketService.fetchAllMarketTicks()
        if (allTicks.isNotEmpty()) marketDataCoordinator.updateDashboardTicks(allTicks)
    }
    fetchRealBalance()
}
fun TradingViewModel.executeRealTrade(pair: String, type: String, price: Long, amountIdr: Double, tp1: Double = 0.0, tp2: Double = 0.0, onResult: (Boolean, String) -> Unit) =
    realCoordinator.executeRealTrade(pair, type, price, amountIdr, tp1, tp2, onResult)

fun TradingViewModel.refreshSimulationState() = simCoordinator.refresh()
fun TradingViewModel.refreshSpotPosition() {
    val sym = try { _selectedPair.value.symbol } catch (_: Throwable) { null }
    if (sym != null) {
        positionCoordinator.refreshPosition(sym)
    }
}
fun TradingViewModel.refreshPriceAlerts() {
    val sym = try { _selectedPair.value.symbol } catch (_: Throwable) { null }
    if (sym != null) {
        positionCoordinator.refreshAlerts(sym)
    }
}
fun TradingViewModel.setOwnership(owned: Boolean, price: Double = 0.0, quantity: Double = 0.0, invested: Double = 0.0, isReal: Boolean = isRealBuyMode.value) {
    val symbol = _selectedPair.value.symbol
    positionCoordinator.setOwnership(symbol, owned, price, quantity, invested, isReal)
}

fun TradingViewModel.submitSimulationOrder(
    side: SimulationOrderSide,
    type: SimulationOrderType,
    price: Double,
    stopPrice: Double = 0.0,
    quantity: Double
): SimulationOrderResult = simCoordinator.submitOrder(
    pair = _selectedPair.value,
    currentPrice = marketDataCoordinator.currentTick.value?.price ?: price,
    side = side, type = type, price = price, stopPrice = stopPrice, quantity = quantity
)

fun TradingViewModel.cancelSimulationOrder(orderId: String): Boolean = simCoordinator.cancelOrder(orderId)
fun TradingViewModel.cancelAllSimulationOrders(symbol: String? = null): Int = simCoordinator.cancelAllOrders(symbol)
fun TradingViewModel.topUpSimulationBalance(amount: Double) = simCoordinator.topUpIdr(amount)
fun TradingViewModel.resetSimulationAccount() = simCoordinator.resetAccount()

fun TradingViewModel.getHoldingStatus(pair: TradingPair, forceIsReal: Boolean? = null): CoinHoldingStatus {
    val targetIsReal = forceIsReal ?: isRealBuyMode.value
    val baseLower = pair.baseAsset.lowercase()
    val baseUpper = pair.baseAsset.uppercase()
    val symbolNorm = pair.symbol.replace("_", "").uppercase()

    if (targetIsReal) {
        // STRICTLY REAL MODE: Hanya evaluasi posisi Real / saldo akun Real Indodax
        val spotPos = positionStore.get(pair.symbol)
        if (spotPos.isHolding && spotPos.isReal && spotPos.quantity > 0.00000001) {
            val entry = if (spotPos.entryPrice > 0.0) {
                spotPos.entryPrice
            } else {
                realAvgBuyPrices.value[symbolNorm]
                    ?: realAvgBuyPrices.value[pair.symbol.uppercase()]
                    ?: realAvgBuyPrices.value[baseUpper]
                    ?: 0.0
            }
            val sl = if (spotPos.stopLossPrice > 0.0) spotPos.stopLossPrice else if (entry > 0.0) entry * 0.99 else 0.0
            return CoinHoldingStatus(
                isHolding = true,
                quantity = spotPos.quantity,
                entryPrice = entry,
                isReal = true,
                tp1Price = spotPos.tp1Price,
                tp2Price = spotPos.tp2Price,
                stopLossPrice = sl,
                isTrailingTriggered = spotPos.isTrailingTriggered
            )
        }

        val realBalances = realIndodaxBalance.value
        val realQty = realBalances[baseLower] ?: realBalances[baseUpper] ?: 0.0
        if (realQty > 0.00000001 && baseUpper != "IDR" && prefs.hasIndodaxCredentials()) {
            val realAvg = realAvgBuyPrices.value[symbolNorm]
                ?: realAvgBuyPrices.value[pair.symbol.uppercase()]
                ?: realAvgBuyPrices.value[baseUpper]
                ?: if (spotPos.isReal) spotPos.entryPrice else 0.0
            val sl = if (spotPos.isReal && spotPos.stopLossPrice > 0.0) spotPos.stopLossPrice else if (realAvg > 0.0) realAvg * 0.99 else 0.0
            return CoinHoldingStatus(
                isHolding = true,
                quantity = realQty,
                entryPrice = realAvg,
                isReal = true,
                tp1Price = if (spotPos.isReal) spotPos.tp1Price else 0.0,
                tp2Price = if (spotPos.isReal) spotPos.tp2Price else 0.0,
                stopLossPrice = sl,
                isTrailingTriggered = spotPos.isReal && spotPos.isTrailingTriggered
            )
        }

        return CoinHoldingStatus(isHolding = false, isReal = true)
    } else {
        // STRICTLY SIMULATION MODE: Hanya evaluasi posisi Simulasi / saldo akun Simulasi
        val spotPos = positionStore.get(pair.symbol)
        if (spotPos.isHolding && !spotPos.isReal && spotPos.quantity > 0.00000001) {
            val sl = if (spotPos.stopLossPrice > 0.0) spotPos.stopLossPrice else if (spotPos.entryPrice > 0.0) spotPos.entryPrice * 0.99 else 0.0
            return CoinHoldingStatus(
                isHolding = true,
                quantity = spotPos.quantity,
                entryPrice = spotPos.entryPrice,
                isReal = false,
                tp1Price = spotPos.tp1Price,
                tp2Price = spotPos.tp2Price,
                stopLossPrice = sl,
                isTrailingTriggered = spotPos.isTrailingTriggered
            )
        }

        val simWallet = simulationWallet.value
        val simQty = simWallet.coinBalances[baseLower] ?: simWallet.coinBalances[baseUpper] ?: 0.0
        if (simQty > 0.00000001 && baseUpper != "IDR") {
            val simAvg = simWallet.avgBuyPrices[baseLower] ?: simWallet.avgBuyPrices[baseUpper] ?: 0.0
            val sl = if (!spotPos.isReal && spotPos.stopLossPrice > 0.0) spotPos.stopLossPrice else if (simAvg > 0.0) simAvg * 0.99 else 0.0
            return CoinHoldingStatus(
                isHolding = true,
                quantity = simQty,
                entryPrice = simAvg,
                isReal = false,
                tp1Price = if (!spotPos.isReal) spotPos.tp1Price else 0.0,
                tp2Price = if (!spotPos.isReal) spotPos.tp2Price else 0.0,
                stopLossPrice = sl,
                isTrailingTriggered = !spotPos.isReal && spotPos.isTrailingTriggered
            )
        }

        return CoinHoldingStatus(isHolding = false, isReal = false)
    }
}

fun TradingViewModel.setManualPositionPrice(symbol: String, entryPrice: Double, investedAmount: Double = 0.0, isReal: Boolean = isRealBuyMode.value) {
    positionCoordinator.setManualEntry(symbol, entryPrice, investedAmount, isReal)
}

fun TradingViewModel.setTrailingStop(enabled: Boolean, trailingPercent: Double) {
    val symbol = _selectedPair.value.symbol
    val currentP = currentTick.value?.price ?: spotPosition.value.entryPrice
    positionCoordinator.setTrailing(symbol, enabled, trailingPercent, currentP)
}

fun TradingViewModel.setAutoSellParams(
    enabled: Boolean,
    tp1Price: Double,
    tp1Percent: Double,
    tp2Price: Double,
    tp2Percent: Double,
    onResult: (Boolean, String) -> Unit = { _, _ -> }
) {
    val symbol = _selectedPair.value.symbol
    positionCoordinator.setAutoSell(symbol, enabled, tp1Price, tp1Percent, tp2Price, tp2Percent)
    onResult(true, if (enabled) "Target TP1 & TP2 tersimpan ke evaluator sinyal." else "Target TP dinonaktifkan.")
}

fun TradingViewModel.executeSellOrders(
    pair: agu.analys.model.TradingPair,
    sellQty: Double,
    marketPrice: Double,
    isAutoTpEnabled: Boolean,
    tp1Price: Double,
    tp1Percent: Double,
    tp2Price: Double,
    tp2Percent: Double,
    isRealMode: Boolean,
    onResult: (Boolean, String) -> Unit
) {
    if (isRealMode) {
        realCoordinator.executeRealSellOrders(
            pair = pair.symbol,
            totalQuantity = sellQty,
            marketPrice = marketPrice,
            isAutoTpEnabled = isAutoTpEnabled,
            tp1Price = tp1Price,
            tp1Percent = tp1Percent,
            tp2Price = tp2Price,
            tp2Percent = tp2Percent,
            onResult = { success, msg ->
                if (success) {
                    positionCoordinator.setOwnership(pair.symbol, false)
                }
                onResult(success, msg)
            }
        )
    } else {
        simCoordinator.executeSimulationSellOrders(
            pair = pair,
            totalQuantity = sellQty,
            marketPrice = marketPrice,
            isAutoTpEnabled = isAutoTpEnabled,
            tp1Price = tp1Price,
            tp1Percent = tp1Percent,
            tp2Price = tp2Price,
            tp2Percent = tp2Percent,
            onResult = { success, msg ->
                if (success) {
                    positionCoordinator.setOwnership(pair.symbol, false)
                }
                onResult(success, msg)
            }
        )
    }
}

fun TradingViewModel.resetTrailingTrigger() {
    positionCoordinator.resetTrailing(_selectedPair.value.symbol)
}

fun TradingViewModel.addPriceAlert(alert: agu.analys.model.PriceAlert) { positionCoordinator.addAlert(alert, _selectedPair.value.symbol) }
fun TradingViewModel.removePriceAlert(alertId: String) { positionCoordinator.removeAlert(alertId, _selectedPair.value.symbol) }
fun TradingViewModel.togglePriceAlert(alertId: String) { positionCoordinator.toggleAlert(alertId, _selectedPair.value.symbol) }
