package agu.analys.viewmodel

import android.content.Context
import agu.analys.model.BatchExecutionState
import agu.analys.model.BatchResultSummary
import agu.analys.model.BatchSellItemResult
import agu.analys.model.ReadySellCoinSummary
import agu.analys.service.IndodaxTradeApiV2
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SpotPositionStore
import agu.analys.util.AlertNotificationHelper
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Koordinator eksekusi batch sell ('Sell All Ready Assets').
 * Memisahkan dan mengisolasi secara ketat eksekusi antara mode Simulasi dan Real Order (Indodax V2 API)
 * berdasarkan preferensi dan mode trading yang sedang aktif.
 */
class BatchSellCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: AppPreferences,
    private val simCoordinator: SimulationCoordinator,
    private val realCoordinator: RealTradeCoordinator,
    private val positionStore: SpotPositionStore,
    private val positionCoordinator: PositionCoordinator
) {
    private val _executionState = MutableStateFlow<BatchExecutionState>(BatchExecutionState.Idle)
    val executionState: StateFlow<BatchExecutionState> = _executionState.asStateFlow()

    fun resetState() {
        _executionState.value = BatchExecutionState.Idle
    }

    fun executeBatchSell(
        items: List<ReadySellCoinSummary>,
        isRealMode: Boolean,
        pin: String? = null,
        onCompleted: ((BatchResultSummary) -> Unit)? = null
    ) {
        if (items.isEmpty()) {
            _executionState.value = BatchExecutionState.Error("Tidak ada aset yang siap dijual.")
            return
        }

        if (_executionState.value is BatchExecutionState.InProgress) {
            return
        }

        scope.launch {
            if (isRealMode) {
                executeRealBatchSell(items, pin, onCompleted)
            } else {
                executeSimulationBatchSell(items, onCompleted)
            }
        }
    }

    private suspend fun executeSimulationBatchSell(
        items: List<ReadySellCoinSummary>,
        onCompleted: ((BatchResultSummary) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        val totalItems = items.size
        var successCount = 0
        var failedCount = 0
        val results = mutableListOf<BatchSellItemResult>()

        items.forEachIndexed { index, item ->
            val symbol = item.pair.symbol
            _executionState.value = BatchExecutionState.InProgress(
                totalItems = totalItems,
                currentIndex = index + 1,
                currentSymbol = item.pair.baseAsset,
                successCount = successCount,
                failedCount = failedCount,
                message = "Menjual ${item.pair.baseAsset} [Simulasi] (${index + 1}/$totalItems)..."
            )

            try {
                val availableCoin = simCoordinator.wallet.value.getAvailableCoin(item.pair.baseAsset)
                val finalQty = if (availableCoin > 0.0) availableCoin else item.quantity

                if (finalQty <= 0.00000001) {
                    failedCount++
                    results.add(
                        BatchSellItemResult(
                            symbol = symbol,
                            baseAsset = item.pair.baseAsset,
                            quantity = 0.0,
                            price = item.currentPrice,
                            success = false,
                            message = "Saldo koin di simulasi tidak mencukupi",
                            profitIdr = 0.0
                        )
                    )
                    positionCoordinator.setOwnership(symbol, false, item.currentPrice)
                } else {
                    val orderResult = simCoordinator.submitOrder(
                        pair = item.pair,
                        currentPrice = item.currentPrice,
                        side = SimulationOrderSide.SELL,
                        type = SimulationOrderType.MARKET,
                        price = item.currentPrice,
                        stopPrice = 0.0,
                        quantity = finalQty
                    )

                    val isSuccess = orderResult is agu.analys.trading.SimulationOrderResult.Success
                    val msg = when (orderResult) {
                        is agu.analys.trading.SimulationOrderResult.Success -> orderResult.message
                        is agu.analys.trading.SimulationOrderResult.Error -> orderResult.message
                    }

                    if (isSuccess) {
                        successCount++
                        positionCoordinator.setOwnership(symbol, false, item.currentPrice)
                        results.add(
                            BatchSellItemResult(
                                symbol = symbol,
                                baseAsset = item.pair.baseAsset,
                                quantity = finalQty,
                                price = item.currentPrice,
                                success = true,
                                message = msg,
                                profitIdr = item.profitIdr
                            )
                        )
                    } else {
                        failedCount++
                        results.add(
                            BatchSellItemResult(
                                symbol = symbol,
                                baseAsset = item.pair.baseAsset,
                                quantity = finalQty,
                                price = item.currentPrice,
                                success = false,
                                message = msg,
                                profitIdr = 0.0
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Gagal batch sell simulasi untuk ${item.pair.baseAsset}")
                failedCount++
                results.add(
                    BatchSellItemResult(
                        symbol = symbol,
                        baseAsset = item.pair.baseAsset,
                        quantity = item.quantity,
                        price = item.currentPrice,
                        success = false,
                        message = e.localizedMessage ?: "Unknown error",
                        profitIdr = 0.0
                    )
                )
            }

            delay(150L) // UI visual spacing
        }

        val totalProfit = results.filter { it.success }.sumOf { it.profitIdr }
        val totalCashOut = items.filter { item -> results.any { it.symbol == item.pair.symbol && it.success } }
            .sumOf { it.cashOutValueIdr }

        val summary = BatchResultSummary(
            isRealMode = false,
            totalItems = totalItems,
            successCount = successCount,
            failedCount = failedCount,
            totalEstimatedProfitIdr = totalProfit,
            totalCashOutIdr = totalCashOut,
            itemResults = results
        )

        _executionState.value = BatchExecutionState.Completed(summary)

        // Notification
        AlertNotificationHelper.sendPriceAlertNotification(
            context = context,
            notificationId = 9801,
            title = "✅ BATCH SELL SIMULASI SELESAI",
            message = "$successCount dari $totalItems koin berhasil dijual (Simulasi). Kas: ${PriceFormatter.formatPrice(totalCashOut, showSymbol = true, quoteAsset = "IDR")}."
        )

        onCompleted?.invoke(summary)
    }

    private suspend fun executeRealBatchSell(
        items: List<ReadySellCoinSummary>,
        pin: String?,
        onCompleted: ((BatchResultSummary) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        val apiKey = prefs.indodaxApiKey
        val secretKey = prefs.indodaxSecretKey

        if (apiKey.isBlank() || secretKey.isBlank()) {
            _executionState.value = BatchExecutionState.Error("Kredensial API Key & Secret Key INDODAX belum diisi.")
            return@withContext
        }

        if (prefs.hasSecurityPin()) {
            if (pin.isNullOrBlank() || !prefs.verifySecurityPin(pin)) {
                _executionState.value = BatchExecutionState.Error("PIN Keamanan salah atau belum dimasukkan.")
                return@withContext
            }
        }

        val totalItems = items.size
        var successCount = 0
        var failedCount = 0
        val results = mutableListOf<BatchSellItemResult>()

        items.forEachIndexed { index, item ->
            val symbol = item.pair.symbol
            _executionState.value = BatchExecutionState.InProgress(
                totalItems = totalItems,
                currentIndex = index + 1,
                currentSymbol = item.pair.baseAsset,
                successCount = successCount,
                failedCount = failedCount,
                message = "Mengirim order jual riil ${item.pair.baseAsset} ke INDODAX (${index + 1}/$totalItems)..."
            )

            try {
                // Gunakan harga diskon market sell 4-5% di bawah harga live agar 100% matched instan pada order book
                val isUsdt = item.pair.quoteAsset.equals("USDT", true) || item.pair.quoteAsset.equals("USD", true)
                val marketSellPrice = if (isUsdt) {
                    item.currentPrice * 0.95
                } else {
                    (item.currentPrice * 0.95).toLong().coerceAtLeast(1L).toDouble()
                }
                val clientOrderId = "agu-batch-${item.pair.baseAsset.lowercase()}-${System.currentTimeMillis()}"

                val orderRes = IndodaxTradeApiV2.createLimitOrderDetailed(
                    apiKey = apiKey,
                    secretKey = secretKey,
                    symbol = symbol,
                    side = "sell",
                    price = marketSellPrice,
                    quantity = item.quantity,
                    clientOrderId = clientOrderId
                )

                if (orderRes.success) {
                    successCount++
                    positionCoordinator.setOwnership(symbol, false, item.currentPrice)
                    results.add(
                        BatchSellItemResult(
                            symbol = symbol,
                            baseAsset = item.pair.baseAsset,
                            quantity = item.quantity,
                            price = item.currentPrice,
                            success = true,
                            message = orderRes.message.ifBlank { "Order jual terkirim (ID: ${orderRes.orderId})" },
                            profitIdr = item.profitIdr
                        )
                    )
                } else {
                    failedCount++
                    results.add(
                        BatchSellItemResult(
                            symbol = symbol,
                            baseAsset = item.pair.baseAsset,
                            quantity = item.quantity,
                            price = item.currentPrice,
                            success = false,
                            message = orderRes.message,
                            profitIdr = 0.0
                        )
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Gagal batch sell real INDODAX untuk ${item.pair.baseAsset}")
                failedCount++
                results.add(
                    BatchSellItemResult(
                        symbol = symbol,
                        baseAsset = item.pair.baseAsset,
                        quantity = item.quantity,
                        price = item.currentPrice,
                        success = false,
                        message = e.localizedMessage ?: "Network Error",
                        profitIdr = 0.0
                    )
                )
            }

            // Jeda rate-limit INDODAX V2 API
            if (index < totalItems - 1) {
                delay(800L)
            }
        }

        // Refresh balance real setelah batch selesai
        realCoordinator.fetchRealBalance()

        val totalProfit = results.filter { it.success }.sumOf { it.profitIdr }
        val totalCashOut = items.filter { item -> results.any { it.symbol == item.pair.symbol && it.success } }
            .sumOf { it.cashOutValueIdr }

        val summary = BatchResultSummary(
            isRealMode = true,
            totalItems = totalItems,
            successCount = successCount,
            failedCount = failedCount,
            totalEstimatedProfitIdr = totalProfit,
            totalCashOutIdr = totalCashOut,
            itemResults = results
        )

        _executionState.value = BatchExecutionState.Completed(summary)

        // Notification
        AlertNotificationHelper.sendPriceAlertNotification(
            context = context,
            notificationId = 9802,
            title = "✅ BATCH SELL INDODAX SELESAI",
            message = "$successCount dari $totalItems order berhasil dieksekusi di Indodax. Estimasi Kas: ${PriceFormatter.formatPrice(totalCashOut, showSymbol = true, quoteAsset = "IDR")}."
        )

        onCompleted?.invoke(summary)
    }
}
