package agu.analys.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import agu.analys.MainActivity
import agu.analys.trading.SpotPositionStore
import agu.analys.trading.SimulationTradeStore
import agu.analys.model.TradingPair
import agu.analys.model.MarketKey
import agu.analys.config.MarketDataSource
import agu.analys.util.PriceFormatter
import agu.analys.util.AppPreferences
import agu.analys.util.AlertNotificationHelper
import agu.analys.engine.sell.TickHistoryTracker
import agu.analys.engine.sell.SellSignalEvaluator
import agu.analys.engine.sell.SellSignalLifecycleManager
import agu.analys.model.PositionContext
import agu.analys.model.SellLifecycleState
import agu.analys.config.TradingFeeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class TradingForegroundService : Service() {

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var monitorJob: Job? = null
    private val lastEmergencyAlertTimes = ConcurrentHashMap<String, Long>()
    private val EMERGENCY_ALERT_COOLDOWN_MS = 60_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureActiveScope()
        createNotificationChannel()
        startHoldingsMonitor()
    }

    private var lastUpdateTime = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            monitorJob?.cancel()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureActiveScope()
        startHoldingsMonitor()

        if (action == ACTION_UPDATE) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime < 1200L) {
                return START_STICKY
            }
            lastUpdateTime = now
        }

        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        serviceJob.cancel()
    }

    private fun ensureActiveScope() {
        if (!serviceJob.isActive) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
        }
    }

    private fun startHoldingsMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val (realItems, simItems) = getHoldingsData()
                    // Pantau koin REAL dan SIMULASI secara terpisah tanpa menimpa salah satunya
                    val allHoldings = realItems + simItems

                    if (allHoldings.isNotEmpty()) {
                        val positionStore = SpotPositionStore(applicationContext)
                        var pricesUpdated = false

                        // Kelompokkan per bursa untuk efisiensi request API
                        val tokoHoldings = allHoldings.filter { it.exchange.equals("TOKOCRYPTO", ignoreCase = true) }
                        val indodaxHoldings = allHoldings.filter { it.exchange.equals("INDODAX", ignoreCase = true) }

                        val marketTicks = mutableMapOf<String, Double>()

                        if (tokoHoldings.isNotEmpty()) {
                            try {
                                val symbolsToFetch = tokoHoldings.map { it.symbol }
                                val fetched = TokocryptoMarketService.fetchTickers(symbolsToFetch)
                                for (t in fetched) {
                                    marketTicks["TOKOCRYPTO_${t.symbol.uppercase()}"] = t.price
                                }
                            } catch (e: Exception) {
                                timber.log.Timber.w(e, "Tokocrypto ticker fetch failed in service")
                            }
                        }

                        if (indodaxHoldings.isNotEmpty()) {
                            try {
                                val allIndodax = IndodaxMarketService.fetchAllMarketTicks()
                                for ((sym, tick) in allIndodax) {
                                    val symUpper = sym.uppercase()
                                    marketTicks["INDODAX_$symUpper"] = tick.price
                                    marketTicks["INDODAX_${symUpper.replace("/", "").replace("_", "")}"] = tick.price
                                }
                            } catch (e: Exception) {
                                timber.log.Timber.w(e, "Indodax ticker fetch failed in service")
                            }
                        }

                        for (item in allHoldings) {
                            val ex = item.exchange.uppercase().trim()
                            val sym = item.symbol.uppercase()
                            val compactSym = if (ex == "TOKOCRYPTO") TokocryptoMarketService.toTokocryptoSymbol(sym) else sym
                            val tickKey = "${ex}_$sym"
                            val compactKey = "${ex}_$compactSym"
                            val globalLiveKey = "${ex}_$sym"

                            val fetchedPrice = marketTicks[tickKey] ?: marketTicks[compactKey]
                            val currentPrice = fetchedPrice ?: livePrices[globalLiveKey] ?: livePrices[sym] ?: 0.0

                            if (currentPrice > 0.0) {
                                if (livePrices[globalLiveKey] != currentPrice) {
                                    livePrices[globalLiveKey] = currentPrice
                                    livePrices[sym] = currentPrice
                                    pricesUpdated = true
                                }

                                // 1. Rekam tick ke TickHistoryTracker dengan exchange key
                                TickHistoryTracker.recordTick(sym, currentPrice, exchange = ex)

                                // 2. Update trailing stop jika di-enable dengan exchange key
                                val pos = positionStore.get(sym, item.isReal, exchange = ex)
                                if (pos.isHolding && pos.isTrailingEnabled) {
                                    positionStore.updateTrailingPrice(sym, currentPrice, item.isReal, exchange = ex)
                                }

                                // 3. Ambil snapshot risiko
                                val peak = if (pos.isHolding && pos.peakPrice > 0.0) pos.peakPrice else null
                                val riskSnapshot = TickHistoryTracker.getSnapshot(
                                    symbol = sym,
                                    currentPrice = currentPrice,
                                    peakPrice = peak,
                                    exchange = ex
                                )

                                // 4. Bangun PositionContext
                                val posContext = PositionContext(
                                    hasPosition = true,
                                    symbol = sym,
                                    entryPrice = if (item.entryPrice > 0.0) item.entryPrice else null,
                                    quantity = if (item.quantity > 0.0) item.quantity else null,
                                    costBasis = if (item.entryPrice > 0.0 && item.quantity > 0.0) item.entryPrice * item.quantity else null,
                                    currentPrice = currentPrice,
                                    stopLoss = if (pos.isHolding && pos.stopLossPrice > 0.0) pos.stopLossPrice else null,
                                    tp1 = if (pos.isHolding && pos.tp1Price > 0.0) pos.tp1Price else null,
                                    tp2 = if (pos.isHolding && pos.tp2Price > 0.0) pos.tp2Price else null,
                                    trailingActive = pos.isHolding && pos.isTrailingEnabled,
                                    isTrailingTriggered = pos.isHolding && pos.isTrailingTriggered,
                                    isReal = item.isReal,
                                    peakPrice = peak,
                                    riskSnapshot = riskSnapshot
                                )

                                // 5. Evaluasi Sinyal Jual
                                val sellState = SellSignalEvaluator.evaluate(
                                    context = posContext,
                                    indicators = null,
                                    tradingFees = TradingFeeConfig(),
                                    riskSnapshot = riskSnapshot
                                )

                                // 6. Proses transisi lifecycle dengan exchange key
                                val transition = SellSignalLifecycleManager.process(
                                    symbol = sym,
                                    newState = sellState,
                                    isReal = item.isReal,
                                    exchange = ex
                                )

                                val marketKey = MarketKey.resolve(sym, ex, item.quoteAsset)

                                // 7. Emergency Alert Dispatcher (Rapid Drop & Stop Loss)
                                if (sellState.state == SellLifecycleState.RAPID_DROP_EXIT ||
                                    sellState.state == SellLifecycleState.STOP_LOSS_HIT) {

                                    val alertKey = "${ex}_${sym}_${item.isReal}"
                                    val lastAlert = lastEmergencyAlertTimes[alertKey] ?: 0L
                                    val now = System.currentTimeMillis()
                                    if (transition.hasTriggeringTransition || (now - lastAlert > EMERGENCY_ALERT_COOLDOWN_MS)) {
                                        lastEmergencyAlertTimes[alertKey] = now
                                        AlertNotificationHelper.sendEmergencyExitNotification(
                                            context = applicationContext,
                                            marketKey = marketKey,
                                            state = sellState,
                                            currentPrice = currentPrice,
                                            entryPrice = item.entryPrice,
                                            quantity = item.quantity,
                                            isReal = item.isReal
                                        )
                                    }
                                } else if (sellState.state == SellLifecycleState.TRAILING_TRIGGERED && transition.hasTriggeringTransition) {
                                    // Trailing Stop Alert
                                    AlertNotificationHelper.sendTrailingHitNotification(
                                        context = applicationContext,
                                        marketKey = marketKey,
                                        entryPrice = item.entryPrice,
                                        peakPrice = peak ?: currentPrice,
                                        currentPrice = currentPrice,
                                        limitSellPrice = if (pos.trailingStopPrice > 0.0) pos.trailingStopPrice else currentPrice,
                                        quantity = item.quantity,
                                        isReal = item.isReal
                                    )
                                } else if (sellState.state == SellLifecycleState.READY_TO_SELL && transition.hasTriggeringTransition) {
                                    // Take Profit 1 / TP2 / Target Reached Alert
                                    AlertNotificationHelper.sendTakeProfitNotification(
                                        context = applicationContext,
                                        marketKey = marketKey,
                                        targetLabel = sellState.reason,
                                        entryPrice = item.entryPrice,
                                        currentPrice = currentPrice,
                                        netProfitPct = sellState.netProfitPct,
                                        quantity = item.quantity,
                                        isReal = item.isReal
                                    )
                                }
                            }
                        }

                        if (pricesUpdated) {
                            updateNotification()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    timber.log.Timber.w(e, "Background holding monitor loop error")
                }
                delay(4000L) // Polling interval 4 detik
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga proses aplikasi tetap hidup dan memantau pair"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private data class HoldingItem(
        val symbol: String,
        val baseAsset: String,
        val quoteAsset: String,
        val exchange: String,
        val quantity: Double,
        val entryPrice: Double,
        val currentPrice: Double,
        val isReal: Boolean
    ) {
        val isProfit: Boolean get() = entryPrice > 0.0 && currentPrice > entryPrice
        val diffPct: Double get() = if (entryPrice > 0.0) ((currentPrice - entryPrice) / entryPrice) * 100.0 else 0.0
    }

    private fun formatCoinQuantity(quantity: Double, baseAsset: String): String {
        if (quantity <= 0.0) return "0 $baseAsset"
        val cache = agu.analys.util.MarketDataCache(this)
        val meta = cache.loadPairsMetadata().find { it.baseCurrency.equals(baseAsset, ignoreCase = true) || it.tradedCurrency.equals(baseAsset, ignoreCase = true) }
        val decimals = meta?.quantityDecimals ?: if (quantity >= 1000.0) 2 else if (quantity >= 1.0) 4 else 8
        return agu.analys.util.PriceFormatter.formatCoinQuantity(quantity, baseAsset, decimals)
    }

    private fun formatHoldingCard(item: HoldingItem): String {
        val currPriceStr = PriceFormatter.formatPrice(item.currentPrice, showSymbol = true, quoteAsset = item.quoteAsset)
        val qtyStr = formatCoinQuantity(item.quantity, item.baseAsset)
        val exBadge = "[${item.exchange}]"

        return if (item.entryPrice > 0.0) {
            val entryPriceStr = PriceFormatter.formatPrice(item.entryPrice, showSymbol = true, quoteAsset = item.quoteAsset)
            val pctFormatted = PriceFormatter.formatPercentage(item.diffPct, includePlusSign = true)
            val statusTag = if (item.isProfit) "▲ $pctFormatted  [SIAP JUAL]" else "▼ $pctFormatted  [HOLD]"
            "• ${item.baseAsset} $exBadge $currPriceStr  $statusTag\n  Beli: $entryPriceStr • Saldo: $qtyStr"
        } else {
            "• ${item.baseAsset} $exBadge $currPriceStr  [HOLD]\n  Saldo: $qtyStr"
        }
    }

    private fun updateNotification() {
        lastUpdateTime = System.currentTimeMillis()
        val prefs = AppPreferences(this)
        val currentDataSource = prefs.marketDataSource
        val (realItems, simItems) = getHoldingsData()
        val totalProfitCount = realItems.count { it.isProfit } + simItems.count { it.isProfit }
        val totalHoldings = realItems.size + simItems.size

        val title = when {
            totalProfitCount > 0 -> "⚡ $totalProfitCount Aset Siap Profit • Spot Monitor"
            totalHoldings > 0 -> "📈 Spot Monitor • $totalHoldings Aset Aktif"
            else -> "📈 Spot Monitor • Menunggu Posisi"
        }

        val collapsedText = when {
            totalProfitCount > 0 -> {
                val profitList = (realItems + simItems).filter { it.isProfit }
                "Siap Jual: " + profitList.joinToString(", ") {
                    "${it.baseAsset} (${PriceFormatter.formatPercentage(it.diffPct, includePlusSign = true)})"
                }
            }
            totalHoldings > 0 -> {
                val allList = realItems + simItems
                "Pantau: " + allList.take(3).joinToString(", ") {
                    "${it.baseAsset} ${PriceFormatter.formatPrice(it.currentPrice, showSymbol = false, quoteAsset = it.quoteAsset)}"
                }
            }
            else -> "Belum ada aset spot yang dipantau"
        }

        val bigText = buildString {
            if (realItems.isEmpty() && simItems.isEmpty()) {
                append("Belum ada koin yang dimiliki saat ini.\nBeli atau tambahkan posisi untuk mulai memantau.")
            } else {
                if (realItems.isNotEmpty()) {
                    val realProfit = realItems.count { it.isProfit }
                    append("💼 PORTOFOLIO REAL")
                    if (realProfit > 0) append(" ($realProfit Siap Jual)")
                    append(":\n")
                    realItems.forEachIndexed { index, item ->
                        append(formatHoldingCard(item))
                        if (index < realItems.size - 1) append("\n\n")
                    }
                }
                if (simItems.isNotEmpty()) {
                    if (realItems.isNotEmpty()) append("\n\n")
                    val simProfit = simItems.count { it.isProfit }
                    append("🧪 PORTOFOLIO SIMULASI")
                    if (simProfit > 0) append(" ($simProfit Siap Jual)")
                    append(":\n")
                    simItems.forEachIndexed { index, item ->
                        append(formatHoldingCard(item))
                        if (index < simItems.size - 1) append("\n\n")
                    }
                }
            }
        }.trim()

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TradingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subTextLabel = "Spot Monitor (${currentDataSource.label})"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText(collapsedText)
            .setSubText(subTextLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .addAction(0, "Buka Portofolio", pendingIntent)
            .addAction(0, "Hentikan", stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun getHoldingsData(): Pair<List<HoldingItem>, List<HoldingItem>> {
        val context = applicationContext
        val positionStore = SpotPositionStore(context)
        val simulationStore = SimulationTradeStore(context)
        val wallet = simulationStore.getWallet()

        val realItems = mutableListOf<HoldingItem>()

        val prefs = AppPreferences(context)
        val activeExchange = prefs.marketDataSource.name

        // Muat saldo real yang tersimpan
        val savedRealBalance = if (prefs.hasTokocryptoCredentials() || prefs.hasIndodaxCredentials()) {
            prefs.getSavedRealBalance(activeExchange)
        } else emptyMap()
        val savedAvgPrices = if (prefs.hasTokocryptoCredentials() || prefs.hasIndodaxCredentials()) {
            prefs.getSavedRealAvgBuyPrices(activeExchange)
        } else emptyMap()
        
        // 1. Ambil semua posisi REAL yang aktif dari SpotPositionStore
        val activeRealPositions = positionStore.getAllActivePositions(exchange = activeExchange, isReal = true)
        val processedSymbols = mutableSetOf<String>()

        for (pos in activeRealPositions) {
            if (pos.isHolding && pos.quantity > 0.0) {
                val mKey = MarketKey.resolve(pos.symbol, pos.exchange)
                processedSymbols.add(mKey.key)
                val currentPrice = livePrices["${pos.exchange}_${pos.symbol}"] ?: livePrices[pos.symbol] ?: pos.entryPrice
                realItems.add(
                    HoldingItem(
                        symbol = pos.symbol,
                        baseAsset = mKey.base.uppercase(),
                        quoteAsset = mKey.quote,
                        exchange = pos.exchange,
                        quantity = pos.quantity,
                        entryPrice = pos.entryPrice,
                        currentPrice = currentPrice,
                        isReal = true
                    )
                )
            }
        }

        // 2. Periksa saldo real tersimpan yang belum terdaftar di SpotPositionStore
        for ((baseKey, qty) in savedRealBalance) {
            val base = baseKey.uppercase()
            if (qty > 0.00000001 && base != "IDR" && base != "BIDR" && base != "USDT" && base != "USD" && base != "BUSD" && base != "USDC") {
                val mKey = MarketKey.resolve(base, activeExchange)
                if (!processedSymbols.contains(mKey.key)) {
                    processedSymbols.add(mKey.key)
                    val baseLower = base.lowercase()
                    val entryPrice = savedAvgPrices[base] ?: savedAvgPrices[baseLower] ?: savedAvgPrices[mKey.symbol] ?: 0.0
                    val currentPrice = livePrices["${activeExchange}_${mKey.symbol}"] ?: livePrices[mKey.symbol] ?: entryPrice

                    realItems.add(
                        HoldingItem(
                            symbol = mKey.symbol,
                            baseAsset = base,
                            quoteAsset = mKey.quote,
                            exchange = activeExchange,
                            quantity = qty,
                            entryPrice = entryPrice,
                            currentPrice = currentPrice,
                            isReal = true
                        )
                    )
                }
            }
        }

        val sortedReal = realItems.sortedWith(
            compareByDescending<HoldingItem> { it.isProfit }
                .thenByDescending { it.diffPct }
                .thenBy { it.baseAsset }
        )

        // 3. Ambil posisi Simulasi
        val simItems = mutableListOf<HoldingItem>()
        for ((baseAsset, qty) in wallet.coinBalances) {
            val baseAssetUpper = baseAsset.uppercase()
            if (qty > 0.00000001 && baseAssetUpper != "IDR" && baseAssetUpper != "USDT" && baseAssetUpper != "BIDR") {
                val mKey = MarketKey.resolve(baseAssetUpper, activeExchange)
                val avgPrice = wallet.avgBuyPrices[baseAsset] ?: wallet.avgBuyPrices[baseAssetUpper] ?: 0.0
                val currentPrice = livePrices["${activeExchange}_${mKey.symbol}"] ?: livePrices[mKey.symbol] ?: avgPrice
                if (currentPrice <= 0.0 && avgPrice <= 0.0) continue

                simItems.add(
                    HoldingItem(
                        symbol = mKey.symbol,
                        baseAsset = baseAssetUpper,
                        quoteAsset = mKey.quote,
                        exchange = activeExchange,
                        quantity = qty,
                        entryPrice = avgPrice,
                        currentPrice = currentPrice,
                        isReal = false
                    )
                )
            }
        }

        val sortedSim = simItems.sortedWith(
            compareByDescending<HoldingItem> { it.isProfit }
                .thenByDescending { it.diffPct }
                .thenBy { it.baseAsset }
        )

        return Pair(sortedReal, sortedSim)
    }

    companion object {
        const val CHANNEL_ID = "trading_foreground_monitor_channel"
        const val NOTIFICATION_ID = 9912
        const val ACTION_UPDATE = "agu.analys.ACTION_UPDATE_NOTIF"
        const val ACTION_FORCE_REFRESH = "agu.analys.ACTION_FORCE_REFRESH"
        const val ACTION_STOP = "agu.analys.ACTION_STOP_SERVICE"

        val livePrices = ConcurrentHashMap<String, Double>()

        fun startService(context: Context) {
            val prefs = AppPreferences(context)
            if (!prefs.isNotificationsEnabled) return
            
            val intent = Intent(context, TradingForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun forceRefresh(context: Context) {
            val prefs = AppPreferences(context)
            if (!prefs.isNotificationsEnabled) return
            val intent = Intent(context, TradingForegroundService::class.java).apply {
                action = ACTION_FORCE_REFRESH
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, TradingForegroundService::class.java)
            intent.action = ACTION_STOP
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun updatePrice(context: Context, symbol: String, price: Double, exchange: String? = null) {
            val prefs = AppPreferences(context)
            if (!prefs.isNotificationsEnabled) return
            val symUpper = symbol.uppercase()
            val ex = exchange ?: prefs.marketDataSource.name
            val key = "${ex.uppercase()}_$symUpper"
            val oldPrice = livePrices[key] ?: livePrices[symUpper]
            if (oldPrice == price) return

            livePrices[key] = price
            livePrices[symUpper] = price
            val intent = Intent(context, TradingForegroundService::class.java).apply {
                action = ACTION_UPDATE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun updatePrices(context: Context, prices: Map<String, Double>, exchange: String? = null) {
            val prefs = AppPreferences(context)
            if (!prefs.isNotificationsEnabled) return
            val ex = (exchange ?: prefs.marketDataSource.name).uppercase()
            var changed = false
            for ((sym, price) in prices) {
                val symUpper = sym.uppercase()
                val key = "${ex}_$symUpper"
                if (livePrices[key] != price || livePrices[symUpper] != price) {
                    livePrices[key] = price
                    livePrices[symUpper] = price
                    changed = true
                }
            }
            if (changed) {
                val intent = Intent(context, TradingForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                }
                try {
                    context.startService(intent)
                } catch (_: Exception) {}
            }
        }
    }
}
