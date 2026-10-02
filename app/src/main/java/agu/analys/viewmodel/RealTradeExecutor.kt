package agu.analys.viewmodel

import agu.analys.config.MarketDataSource
import agu.analys.database.AppDatabase
import agu.analys.model.TokocryptoOrderRequest
import agu.analys.model.TokocryptoOrderSide
import agu.analys.model.TokocryptoOrderType
import agu.analys.service.IndodaxTradeApiV2
import agu.analys.service.TokocryptoMarketService
import agu.analys.service.TokocryptoTradeApi
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class RealTradeExecutor(
    private val scope: CoroutineScope,
    private val prefs: AppPreferences,
    private val getLatestTick: (String) -> agu.analys.model.MarketTick?,
    private val onStatusUpdate: (String) -> Unit,
    private val onRateLimit: (String) -> Unit,
    private val isRateLimited: () -> Boolean,
    private val refreshBalance: () -> Unit,
    private val onRealTradeSuccess: ((pair: String, type: String, price: Double, quantity: Double, tp1: Double, tp2: Double) -> Unit)? = null
) {
    private val INTER_REQUEST_DELAY_MS = 1500L
    private val BUY_POLL_INTERVAL_MS = 2500L
    private val BUY_POLL_MAX_ATTEMPTS = 15
    private val MIN_EXECUTED_QTY = 1e-12

    fun executeCancelOrder(
        symbol: String,
        orderId: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
        val apiKey = if (isToko) prefs.tokocryptoApiKey else prefs.indodaxApiKey
        val secretKey = if (isToko) prefs.tokocryptoSecretKey else prefs.indodaxSecretKey
        val sourceLabel = prefs.marketDataSource.label
        if (apiKey.isBlank() || secretKey.isBlank()) {
            onResult(false, "API Key atau Secret Key $sourceLabel belum diisi.")
            return
        }
        if (isRateLimited()) {
            onResult(false, "Rate-limit aktif. Tunggu dulu.")
            return
        }

        scope.launch(Dispatchers.IO) {
            onStatusUpdate("Membatalkan order $orderId di $sourceLabel...")
            val (success, message) = if (isToko) {
                TokocryptoTradeApi.cancelOrder(apiKey, secretKey, symbol, orderId)
            } else {
                IndodaxTradeApiV2.cancelOrder(apiKey, secretKey, symbol, orderId)
            }
            if (!success && looksLikeRateLimit(message)) {
                onRateLimit(message)
                withContext(Dispatchers.Main) {
                    onResult(false, message)
                }
                return@launch
            }
            onStatusUpdate(message)
            agu.analys.util.AppLogManager.trade("RealCancel", "Order $sourceLabel $orderId untuk $symbol: $message (Sukses=$success)")
            if (success) {
                AppDatabase.getInstance().realTradeDao().deleteOpenOrderById(orderId)
                delay(INTER_REQUEST_DELAY_MS)
            }
            withContext(Dispatchers.Main) {
                onResult(success, message)
            }
        }
    }

    private suspend fun waitForBuyFill(
        apiKey: String,
        secretKey: String,
        pair: String,
        orderId: String,
        clientOrderId: String,
        requestedQty: Double
    ): Double {
        var lastExecuted = 0.0
        val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO

        for (attempt in 1..BUY_POLL_MAX_ATTEMPTS) {
            delay(BUY_POLL_INTERVAL_MS)
            onStatusUpdate("Menunggu BUY terisi... ($attempt/$BUY_POLL_MAX_ATTEMPTS)")

            val result = if (isToko) {
                TokocryptoTradeApi.getOrder(
                    apiKey = apiKey,
                    secretKey = secretKey,
                    symbol = pair,
                    orderId = orderId.takeIf { it.isNotBlank() && it != "0" },
                    clientOrderId = clientOrderId.takeIf { it.isNotBlank() }
                )
            } else {
                IndodaxTradeApiV2.getOrder(
                    apiKey = apiKey,
                    secretKey = secretKey,
                    symbol = pair,
                    orderId = orderId.takeIf { it.isNotBlank() && it != "0" },
                    clientOrderId = clientOrderId.takeIf { it.isNotBlank() }
                )
            }

            if (!result.success) {
                if (looksLikeRateLimit(result.message)) {
                    onRateLimit(result.message)
                    break
                }
                Timber.w("Poll BUY gagal: ${result.message}")
                continue
            }

            lastExecuted = result.executedQty

            when (result.status) {
                "FILLED" -> return result.executedQty.coerceAtLeast(0.0)
                "PARTIALLY_FILLED" -> {
                    if (result.executedQty > MIN_EXECUTED_QTY) return result.executedQty
                }
                "CANCELLED", "REJECTED", "EXPIRED" -> return 0.0
            }
        }
        return if (lastExecuted > MIN_EXECUTED_QTY) lastExecuted else 0.0
    }

    fun executeTrade(
        pair: String,
        type: String,
        price: Double,
        amountIdr: Double,
        autoLimitSellPrice1: Double = 0.0,
        autoLimitSellPrice2: Double = 0.0,
        onResult: (Boolean, String) -> Unit
    ) {
        val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
        val apiKey = if (isToko) prefs.tokocryptoApiKey else prefs.indodaxApiKey
        val secretKey = if (isToko) prefs.tokocryptoSecretKey else prefs.indodaxSecretKey
        val sourceLabel = prefs.marketDataSource.label
        if (apiKey.isBlank() || secretKey.isBlank()) {
            onResult(false, "API Key atau Secret Key $sourceLabel belum diisi.")
            return
        }
        var execPrice = price
        val latestTick = getLatestTick(pair)
        if (latestTick != null && latestTick.price > 0.0 && execPrice <= 0.0) {
            execPrice = latestTick.price
        }

        val quantity = if (type.equals("buy", ignoreCase = true)) {
            if (execPrice <= 0.0 || amountIdr <= 0.0) 0.0 else amountIdr / execPrice
        } else {
            amountIdr
        }

        if (quantity <= 0.0) {
            onResult(false, "Quantity order tidak valid.")
            return
        }

        scope.launch(Dispatchers.IO) {
            onStatusUpdate("Mengirim order $type ke $sourceLabel...")
            val clientOrderId = "agu-${type.lowercase()}-${System.currentTimeMillis()}"
            val quote = quoteFromPair(pair)
            val priceFmt = if (quote == "usdt") "$$execPrice" else "Rp ${PriceFormatter.formatIdrNumber(execPrice)}"
            agu.analys.util.AppLogManager.trade(
                "RealOrder",
                "💼 [$sourceLabel REAL] Mengirim order ${type.uppercase()} $pair | Qty: $quantity @ $priceFmt"
            )
            val isBuy = type.equals("buy", ignoreCase = true)
            val buyResult = if (isToko) {
                val req = TokocryptoOrderRequest(
                    symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                    side = if (isBuy) TokocryptoOrderSide.BUY else TokocryptoOrderSide.SELL,
                    type = if (isBuy) TokocryptoOrderType.LIMIT else TokocryptoOrderType.MARKET,
                    quantity = quantity,
                    price = execPrice,
                    clientId = clientOrderId
                )
                val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, execPrice)
                IndodaxTradeApiV2.OrderResult(
                    success = res.success,
                    message = res.errorMessage ?: res.rawMessage,
                    orderId = res.orderId,
                    clientOrderId = res.clientId,
                    executedQty = res.executedQty,
                    origQty = quantity,
                    status = res.status
                )
            } else if (!isBuy) {
                // Untuk SELL di Indodax: coba MARKET order lalu fallback LIMIT
                val marketRes = IndodaxTradeApiV2.createMarketOrderDetailed(
                    apiKey = apiKey, secretKey = secretKey, symbol = pair,
                    side = type, quantity = quantity, clientOrderId = clientOrderId
                )
                if (marketRes.success) {
                    marketRes
                } else if (looksLikeRateLimit(marketRes.message)) {
                    marketRes
                } else {
                    IndodaxTradeApiV2.createLimitOrderDetailed(
                        apiKey = apiKey, secretKey = secretKey, symbol = pair,
                        side = type, price = execPrice, quantity = quantity, clientOrderId = clientOrderId
                    )
                }
            } else {
                IndodaxTradeApiV2.createLimitOrderDetailed(
                    apiKey = apiKey, secretKey = secretKey, symbol = pair,
                    side = type, price = execPrice, quantity = quantity, clientOrderId = clientOrderId
                )
            }

            if (!buyResult.success && looksLikeRateLimit(buyResult.message)) {
                onRateLimit(buyResult.message)
                withContext(Dispatchers.Main) {
                    onResult(false, buyResult.message)
                }
                return@launch
            }

            if (buyResult.success) {
                agu.analys.engine.scalping.SignalLifecycleManager.markTriggered(pair)
                val base = baseFromPair(pair)
                prefs.rememberHistoryBase(base)

                var finalExecutedQty = quantity

                if (isBuy && autoLimitSellPrice1 > price) {
                    val executedQty = if (buyResult.executedQty > MIN_EXECUTED_QTY && buyResult.status == "FILLED") {
                        buyResult.executedQty
                    } else {
                        waitForBuyFill(apiKey, secretKey, pair, buyResult.orderId, buyResult.clientOrderId.ifBlank { clientOrderId }, quantity)
                    }

                    if (executedQty <= MIN_EXECUTED_QTY) {
                        onStatusUpdate("BUY terkirim tapi belum FILLED. TP tidak dipasang.")
                        withContext(Dispatchers.Main) {
                            onResult(true, "BUY berhasil di server $sourceLabel, tapi belum FILLED.")
                        }
                        refreshBalance()
                        return@launch
                    }
                    finalExecutedQty = executedQty
                    val minNotional = if (quote == "usdt") 1.0 else 10_000.0
                    val p2 = if (autoLimitSellPrice2 > price) autoLimitSellPrice2 else autoLimitSellPrice1 * 1.03
                    val halfQty = executedQty / 2.0
                    val canSplit = (halfQty * autoLimitSellPrice1 >= minNotional) && (halfQty * p2 >= minNotional)

                    var finalMsg = if (executedQty < quantity * 0.99) {
                        "BUY terisi sebagian (${"%.8f".format(executedQty)}). "
                    } else {
                        "BUY filled ${"%.8f".format(executedQty)}. "
                    }

                    if (canSplit) {
                        onStatusUpdate("Memasang TP1 di $sourceLabel...")
                        val (s1, m1) = if (isToko) {
                            val req = TokocryptoOrderRequest(
                                symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                                side = TokocryptoOrderSide.SELL,
                                type = TokocryptoOrderType.LIMIT,
                                price = autoLimitSellPrice1,
                                quantity = halfQty,
                                clientId = "agu-tp1-${System.currentTimeMillis()}"
                            )
                            val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, autoLimitSellPrice1)
                            res.success to (res.errorMessage ?: res.rawMessage)
                        } else {
                            IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", autoLimitSellPrice1, halfQty, "agu-tp1-${System.currentTimeMillis()}")
                        }
                        finalMsg += if (s1) "TP1 OK (50%). " else "TP1 Gagal: $m1. "
                        if (!s1 && looksLikeRateLimit(m1)) onRateLimit(m1)

                        onStatusUpdate("Memasang TP2 di $sourceLabel...")
                        val (s2, m2) = if (isToko) {
                            val req = TokocryptoOrderRequest(
                                symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                                side = TokocryptoOrderSide.SELL,
                                type = TokocryptoOrderType.LIMIT,
                                price = p2,
                                quantity = halfQty,
                                clientId = "agu-tp2-${System.currentTimeMillis()}"
                            )
                            val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, p2)
                            res.success to (res.errorMessage ?: res.rawMessage)
                        } else {
                            IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", p2, halfQty, "agu-tp2-${System.currentTimeMillis()}")
                        }
                        finalMsg += if (s2) "TP2 OK (50%)." else "TP2 Gagal: $m2."
                        if (!s2 && looksLikeRateLimit(m2)) onRateLimit(m2)
                    } else {
                        onStatusUpdate("Memasang TP 100% di $sourceLabel...")
                        val (s1, m1) = if (isToko) {
                            val req = TokocryptoOrderRequest(
                                symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                                side = TokocryptoOrderSide.SELL,
                                type = TokocryptoOrderType.LIMIT,
                                price = autoLimitSellPrice1,
                                quantity = executedQty,
                                clientId = "agu-tp-full-${System.currentTimeMillis()}"
                            )
                            val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, autoLimitSellPrice1)
                            res.success to (res.errorMessage ?: res.rawMessage)
                        } else {
                            IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", autoLimitSellPrice1, executedQty, "agu-tp-full-${System.currentTimeMillis()}")
                        }
                        finalMsg += if (s1) "TP Full OK (100%)." else "TP Gagal: $m1."
                        if (!s1 && looksLikeRateLimit(m1)) onRateLimit(m1)
                    }

                    onStatusUpdate("BUY + TP: $finalMsg")
                    agu.analys.util.AppLogManager.trade("RealOrderFilled", "✅ [$sourceLabel REAL FILLED] $pair: Qty $finalExecutedQty @ $priceFmt. Auto Sell: $finalMsg")
                    withContext(Dispatchers.Main) {
                        onResult(true, "BUY berhasil!\nAuto Sell: $finalMsg")
                    }
                } else {
                    if (isBuy && buyResult.executedQty > MIN_EXECUTED_QTY) {
                        finalExecutedQty = buyResult.executedQty
                    }
                    onStatusUpdate(buyResult.message)
                    agu.analys.util.AppLogManager.trade("RealOrderSuccess", "✅ [$sourceLabel REAL] Order $type $pair berhasil dikirim @ $priceFmt: ${buyResult.message}")
                    withContext(Dispatchers.Main) {
                        onResult(true, buyResult.message)
                    }
                }

                // Update saldo lokal
                runCatching {
                    val cachedBalances = prefs.getSavedRealBalance().toMutableMap()
                    val quoteKey = if (quote == "usdt") "usdt" else "idr"
                    val currentQuote = cachedBalances[quoteKey] ?: 0.0
                    val baseLower = base.lowercase()
                    val curCoin = cachedBalances[baseLower] ?: 0.0
                    if (isBuy) {
                        val cost = finalExecutedQty * execPrice
                        val newTotalCoin = curCoin + finalExecutedQty
                        cachedBalances[quoteKey] = (currentQuote - cost).coerceAtLeast(0.0)
                        cachedBalances[baseLower] = newTotalCoin
                        prefs.saveRealBalance(cachedBalances)

                        val cachedAvg = prefs.getSavedRealAvgBuyPrices().toMutableMap()
                        val prevAvg = cachedAvg[baseLower] ?: cachedAvg[base.uppercase()] ?: 0.0
                        val newAvgPrice = if (curCoin > 0.0 && prevAvg > 0.0 && newTotalCoin > 0.0) {
                            ((prevAvg * curCoin) + (execPrice * finalExecutedQty)) / newTotalCoin
                        } else {
                            execPrice
                        }
                        cachedAvg[baseLower] = newAvgPrice
                        cachedAvg[base.uppercase()] = newAvgPrice
                        cachedAvg["${baseLower}idr"] = newAvgPrice
                        cachedAvg["${base.uppercase()}IDR"] = newAvgPrice
                        prefs.saveRealAvgBuyPrices(cachedAvg)
                    } else {
                        val proceeds = finalExecutedQty * execPrice
                        cachedBalances[quoteKey] = currentQuote + proceeds
                        cachedBalances[baseLower] = (curCoin - finalExecutedQty).coerceAtLeast(0.0)
                        prefs.saveRealBalance(cachedBalances)
                    }
                }

                onRealTradeSuccess?.invoke(
                    pair,
                    type,
                    execPrice,
                    finalExecutedQty,
                    if (isBuy) autoLimitSellPrice1 else 0.0,
                    if (isBuy) autoLimitSellPrice2 else 0.0
                )

                refreshBalance()
            } else {
                agu.analys.util.AppLogManager.trade(
                    "RealOrderRejected",
                    "❌ [$sourceLabel REAL ORDER DITOLAK] $pair ${type.uppercase()}: ${buyResult.message}"
                )
                onStatusUpdate(buyResult.message)
                withContext(Dispatchers.Main) {
                    onResult(false, buyResult.message)
                }
            }
        }
    }

    fun executeAutoSellOnServer(
        pair: String,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        onResult: (Boolean, String) -> Unit
    ) {
        val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
        val apiKey = if (isToko) prefs.tokocryptoApiKey else prefs.indodaxApiKey
        val secretKey = if (isToko) prefs.tokocryptoSecretKey else prefs.indodaxSecretKey
        val sourceLabel = prefs.marketDataSource.label
        if (apiKey.isBlank() || secretKey.isBlank()) {
            onResult(false, "API Key/Secret $sourceLabel kosong.")
            return
        }
        if (isRateLimited()) {
            onResult(false, "Rate-limit aktif.")
            return
        }

        scope.launch(Dispatchers.IO) {
            onStatusUpdate("Mengecek saldo $pair di $sourceLabel...")
            val base = baseFromPair(pair)
            val (balances, err) = if (isToko) {
                TokocryptoTradeApi.getAccount(apiKey, secretKey)
            } else {
                IndodaxTradeApiV2.getAccount(apiKey, secretKey)
            }
            if (balances == null) {
                if (looksLikeRateLimit(err)) onRateLimit(err)
                withContext(Dispatchers.Main) {
                    onResult(false, "Gagal saldo: $err")
                }
                return@launch
            }

            val free = balances.free[base] ?: balances.free[base.lowercase()] ?: balances.free[base.uppercase()] ?: 0.0
            if (free <= 0.00000001) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Saldo $base di $sourceLabel 0.")
                }
                return@launch
            }

            val qty1 = if (tp1Percent >= 100.0) free else (free * (tp1Percent / 100.0) * 100_000_000.0).toLong() / 100_000_000.0
            val qty2 = if (tp1Percent >= 100.0) 0.0 else if (tp2Percent >= 100.0) free else free - qty1
            var msg = ""
            var okAll = true

            if (tp1Price > 0.0 && qty1 > 0.0) {
                val (ok, m) = if (isToko) {
                    val req = TokocryptoOrderRequest(
                        symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                        side = TokocryptoOrderSide.SELL,
                        type = TokocryptoOrderType.LIMIT,
                        price = tp1Price,
                        quantity = qty1,
                        clientId = "agu-manualtp1-${System.currentTimeMillis()}"
                    )
                    val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, tp1Price)
                    res.success to (res.errorMessage ?: res.rawMessage)
                } else {
                    IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", tp1Price, qty1, "agu-manualtp1-${System.currentTimeMillis()}")
                }
                msg += if (ok) "TP1 OK. " else "TP1 Gagal: $m. "
                if (!ok) {
                    okAll = false
                    if (looksLikeRateLimit(m)) {
                        onRateLimit(m)
                        withContext(Dispatchers.Main) {
                            onResult(false, msg)
                        }
                        return@launch
                    }
                }
                delay(INTER_REQUEST_DELAY_MS)
            }
            if (tp2Price > 0.0 && qty2 > 0.0) {
                val (ok, m) = if (isToko) {
                    val req = TokocryptoOrderRequest(
                        symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                        side = TokocryptoOrderSide.SELL,
                        type = TokocryptoOrderType.LIMIT,
                        price = tp2Price,
                        quantity = qty2,
                        clientId = "agu-manualtp2-${System.currentTimeMillis()}"
                    )
                    val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, tp2Price)
                    res.success to (res.errorMessage ?: res.rawMessage)
                } else {
                    IndodaxTradeApiV2.createLimitOrder(apiKey, secretKey, pair, "sell", tp2Price, qty2, "agu-manualtp2-${System.currentTimeMillis()}")
                }
                msg += if (ok) "TP2 OK." else "TP2 Gagal: $m."
                if (!ok) {
                    okAll = false
                    if (looksLikeRateLimit(m)) onRateLimit(m)
                }
            }
            if (okAll) prefs.rememberHistoryBase(base)
            onStatusUpdate(if (okAll) "TP Berhasil!" else "Sebagian TP Gagal.")
            withContext(Dispatchers.Main) {
                onResult(okAll, msg)
            }
        }
    }

    fun executeRealSellOrders(
        pair: String,
        totalQuantity: Double,
        marketPrice: Double,
        isAutoTpEnabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        onResult: (Boolean, String) -> Unit
    ) {
        val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
        val apiKey = if (isToko) prefs.tokocryptoApiKey else prefs.indodaxApiKey
        val secretKey = if (isToko) prefs.tokocryptoSecretKey else prefs.indodaxSecretKey
        val sourceLabel = prefs.marketDataSource.label
        if (apiKey.isBlank() || secretKey.isBlank()) {
            onResult(false, "API Key/Secret $sourceLabel belum diisi.")
            return
        }

        scope.launch(Dispatchers.IO) {
            onStatusUpdate("Mengeksekusi order jual $pair di $sourceLabel...")
            val base = baseFromPair(pair)
            val quote = quoteFromPair(pair)
            
            // Periksa saldo lokal terlebih dahulu agar eksekusi order instan tanpa jeda network
            val cachedBalances = prefs.getSavedRealBalance()
            val cachedFree = cachedBalances[base.lowercase()] ?: cachedBalances[base.uppercase()] ?: 0.0
            var sellQty = if (totalQuantity > 0.0) totalQuantity else cachedFree

            if (sellQty <= 0.00000001) {
                onStatusUpdate("Mengecek saldo real $pair di $sourceLabel...")
                val (balances, err) = if (isToko) {
                    TokocryptoTradeApi.getAccount(apiKey, secretKey)
                } else {
                    IndodaxTradeApiV2.getAccount(apiKey, secretKey)
                }
                if (balances != null) {
                    val free = balances.free[base] ?: balances.free[base.lowercase()] ?: balances.free[base.uppercase()] ?: 0.0
                    sellQty = if (totalQuantity > 0.0) totalQuantity.coerceAtMost(free) else free
                } else if (looksLikeRateLimit(err)) {
                    onRateLimit(err)
                }
            }

            if (sellQty <= 0.00000001) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Saldo $base di $sourceLabel tidak mencukupi atau 0.")
                }
                return@launch
            }

            if (isAutoTpEnabled && tp1Price > 0.0 && tp2Price > 0.0) {
                val p1 = (tp1Percent / 100.0).coerceIn(0.01, 0.99)
                val qty1 = ((sellQty * p1) * 100_000_000.0).toLong() / 100_000_000.0
                val qty2 = sellQty - qty1

                var msg = ""
                var okAll = true

                if (qty1 > 0.0) {
                    onStatusUpdate("Memasang order jual TP1 di $sourceLabel...")
                    val (ok1, m1) = if (isToko) {
                        val req = TokocryptoOrderRequest(
                            symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                            side = TokocryptoOrderSide.SELL,
                            type = TokocryptoOrderType.LIMIT,
                            price = tp1Price,
                            quantity = qty1,
                            clientId = "agu-tp1-${System.currentTimeMillis()}"
                        )
                        val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, tp1Price)
                        res.success to (res.errorMessage ?: res.rawMessage)
                    } else {
                        IndodaxTradeApiV2.createLimitOrder(
                            apiKey, secretKey, pair, "sell", tp1Price, qty1, "agu-tp1-${System.currentTimeMillis()}"
                        )
                    }
                    val formattedP1 = if (quote == "usdt") "$$tp1Price" else "Rp ${PriceFormatter.formatIdrNumber(tp1Price)}"
                    msg += if (ok1) "TP1 (${PriceFormatter.formatCryptoExact(qty1, 8)} @ $formattedP1) OK. " else "TP1 Gagal: $m1. "
                    if (!ok1) {
                        okAll = false
                        if (looksLikeRateLimit(m1)) onRateLimit(m1)
                    }
                    delay(INTER_REQUEST_DELAY_MS)
                }

                if (qty2 > 0.0) {
                    onStatusUpdate("Memasang order jual TP2 di $sourceLabel...")
                    val (ok2, m2) = if (isToko) {
                        val req = TokocryptoOrderRequest(
                            symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                            side = TokocryptoOrderSide.SELL,
                            type = TokocryptoOrderType.LIMIT,
                            price = tp2Price,
                            quantity = qty2,
                            clientId = "agu-tp2-${System.currentTimeMillis()}"
                        )
                        val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, tp2Price)
                        res.success to (res.errorMessage ?: res.rawMessage)
                    } else {
                        IndodaxTradeApiV2.createLimitOrder(
                            apiKey, secretKey, pair, "sell", tp2Price, qty2, "agu-tp2-${System.currentTimeMillis()}"
                        )
                    }
                    val formattedP2 = if (quote == "usdt") "$$tp2Price" else "Rp ${PriceFormatter.formatIdrNumber(tp2Price)}"
                    msg += if (ok2) "TP2 (${PriceFormatter.formatCryptoExact(qty2, 8)} @ $formattedP2) OK." else "TP2 Gagal: $m2."
                    if (!ok2) {
                        okAll = false
                        if (looksLikeRateLimit(m2)) onRateLimit(m2)
                    }
                }

                if (okAll) prefs.rememberHistoryBase(base)
                onStatusUpdate(if (okAll) "2 Order TP Real Berhasil!" else "Sebagian Order TP Gagal.")
                scope.launch { refreshBalance() }
                withContext(Dispatchers.Main) {
                    onResult(okAll, if (okAll) "2 Order TP Berhasil (100% tanpa sisa):\n$msg" else msg)
                }
            } else if (isAutoTpEnabled && tp1Price > 0.0) {
                onStatusUpdate("Memasang order jual TP1 di $sourceLabel...")
                val (ok, m) = if (isToko) {
                    val req = TokocryptoOrderRequest(
                        symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                        side = TokocryptoOrderSide.SELL,
                        type = TokocryptoOrderType.LIMIT,
                        price = tp1Price,
                        quantity = sellQty,
                        clientId = "agu-tp1-${System.currentTimeMillis()}"
                    )
                    val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, tp1Price)
                    res.success to (res.errorMessage ?: res.rawMessage)
                } else {
                    IndodaxTradeApiV2.createLimitOrder(
                        apiKey, secretKey, pair, "sell", tp1Price, sellQty, "agu-tp1-${System.currentTimeMillis()}"
                    )
                }
                val formattedP1 = if (quote == "usdt") "$$tp1Price" else "Rp ${PriceFormatter.formatIdrNumber(tp1Price)}"
                if (ok) prefs.rememberHistoryBase(base)
                if (!ok && looksLikeRateLimit(m)) onRateLimit(m)
                scope.launch { refreshBalance() }
                withContext(Dispatchers.Main) {
                    onResult(ok, if (ok) "Order TP1 (${PriceFormatter.formatCryptoExact(sellQty, 8)} @ $formattedP1) berhasil terpasang di $sourceLabel." else "Order TP1 Gagal: $m")
                }
            } else if (isAutoTpEnabled && tp2Price > 0.0) {
                onStatusUpdate("Memasang order jual TP2 di $sourceLabel...")
                val (ok, m) = if (isToko) {
                    val req = TokocryptoOrderRequest(
                        symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                        side = TokocryptoOrderSide.SELL,
                        type = TokocryptoOrderType.LIMIT,
                        price = tp2Price,
                        quantity = sellQty,
                        clientId = "agu-tp2-${System.currentTimeMillis()}"
                    )
                    val res = TokocryptoTradeApi.createOrder(apiKey, secretKey, req, tp2Price)
                    res.success to (res.errorMessage ?: res.rawMessage)
                } else {
                    IndodaxTradeApiV2.createLimitOrder(
                        apiKey, secretKey, pair, "sell", tp2Price, sellQty, "agu-tp2-${System.currentTimeMillis()}"
                    )
                }
                val formattedP2 = if (quote == "usdt") "$$tp2Price" else "Rp ${PriceFormatter.formatIdrNumber(tp2Price)}"
                if (ok) prefs.rememberHistoryBase(base)
                if (!ok && looksLikeRateLimit(m)) onRateLimit(m)
                scope.launch { refreshBalance() }
                withContext(Dispatchers.Main) {
                    onResult(ok, if (ok) "Order TP2 (${PriceFormatter.formatCryptoExact(sellQty, 8)} @ $formattedP2) berhasil terpasang di $sourceLabel." else "Order TP2 Gagal: $m")
                }
            } else {
                // Switch OFF -> Eksekusi Jual Langsung (Market Order Instan)
                onStatusUpdate("Mengeksekusi order jual langsung ke $sourceLabel...")
                val clientOrderId = "agu-sell-${System.currentTimeMillis()}"
                
                val finalRes = if (isToko) {
                    val marketReq = TokocryptoOrderRequest(
                        symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                        side = TokocryptoOrderSide.SELL,
                        type = TokocryptoOrderType.MARKET,
                        quantity = sellQty,
                        clientId = clientOrderId
                    )
                    var res = TokocryptoTradeApi.createOrder(apiKey, secretKey, marketReq, marketPrice)
                    if (!res.success) {
                        val limitReq = TokocryptoOrderRequest(
                            symbol = TokocryptoMarketService.toTokocryptoSymbol(pair),
                            side = TokocryptoOrderSide.SELL,
                            type = TokocryptoOrderType.LIMIT,
                            price = marketPrice,
                            quantity = sellQty,
                            clientId = clientOrderId
                        )
                        res = TokocryptoTradeApi.createOrder(apiKey, secretKey, limitReq, marketPrice)
                    }
                    IndodaxTradeApiV2.OrderResult(
                        success = res.success,
                        message = res.errorMessage ?: res.rawMessage,
                        orderId = res.orderId,
                        clientOrderId = res.clientId,
                        executedQty = res.executedQty,
                        origQty = sellQty,
                        status = res.status
                    )
                } else {
                    val marketRes = IndodaxTradeApiV2.createMarketOrderDetailed(
                        apiKey = apiKey,
                        secretKey = secretKey,
                        symbol = pair,
                        side = "sell",
                        quantity = sellQty,
                        clientOrderId = clientOrderId
                    )
                    if (marketRes.success) {
                        marketRes
                    } else {
                        IndodaxTradeApiV2.createLimitOrderDetailed(
                            apiKey = apiKey,
                            secretKey = secretKey,
                            symbol = pair,
                            side = "sell",
                            price = marketPrice,
                            quantity = sellQty,
                            clientOrderId = clientOrderId
                        )
                    }
                }

                if (finalRes.success) {
                    prefs.rememberHistoryBase(base)

                    // Sinkronkan saldo lokal
                    runCatching {
                        val currentBalances = prefs.getSavedRealBalance().toMutableMap()
                        val quoteKey = if (quote == "usdt") "usdt" else "idr"
                        val curQuote = currentBalances[quoteKey] ?: 0.0
                        val curCoin = currentBalances[base.lowercase()] ?: 0.0
                        currentBalances[base.lowercase()] = (curCoin - sellQty).coerceAtLeast(0.0)
                        val proceeds = sellQty * marketPrice
                        currentBalances[quoteKey] = curQuote + proceeds
                        prefs.saveRealBalance(currentBalances)
                    }

                    onRealTradeSuccess?.invoke(pair, "sell", marketPrice, sellQty, 0.0, 0.0)

                    withContext(Dispatchers.Main) {
                        onResult(true, "Order Jual $sourceLabel (${PriceFormatter.formatCryptoExact(sellQty, 8)} $base) berhasil dieksekusi!")
                    }
                    scope.launch { refreshBalance() }
                } else {
                    agu.analys.util.AppLogManager.trade(
                        "RealSellRejected",
                        "❌ [$sourceLabel REAL SELL DITOLAK] $pair: ${finalRes.message}"
                    )
                    if (looksLikeRateLimit(finalRes.message)) onRateLimit(finalRes.message)
                    withContext(Dispatchers.Main) {
                        onResult(false, "Order Jual $sourceLabel Gagal: ${finalRes.message}")
                    }
                }
            }
        }
    }

    private fun looksLikeRateLimit(msg: String): Boolean {
        return msg.contains("429") || msg.lowercase().contains("rate limit") || msg.lowercase().contains("too many requests")
    }

    private fun baseFromPair(pair: String): String {
        val s = pair.lowercase().replace("_", "")
        return when {
            s.endsWith("idr") -> s.removeSuffix("idr")
            s.endsWith("usdt") -> s.removeSuffix("usdt")
            else -> s
        }
    }

    private fun quoteFromPair(pair: String): String {
        val s = pair.lowercase().replace("_", "")
        return if (s.endsWith("usdt")) "usdt" else "idr"
    }
}
