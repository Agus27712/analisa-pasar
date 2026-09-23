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
import agu.analys.util.PriceFormatter
import agu.analys.util.AppPreferences
import agu.analys.util.AlertNotificationHelper
import agu.analys.engine.sell.TickHistoryTracker
import agu.analys.engine.sell.SellSignalEvaluator
import agu.analys.engine.sell.SellSignalLifecycleManager
import agu.analys.model.PositionContext
import agu.analys.model.SellLifecycleState
import agu.analys.config.TradingFeeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TradingForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var monitorJob: Job? = null
    private val lastEmergencyAlertTimes = ConcurrentHashMap<String, Long>()
    private val EMERGENCY_ALERT_COOLDOWN_MS = 60_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startHoldingsMonitor()
    }

    private var lastUpdateTime = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

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
        serviceJob.cancel()
    }

    private fun startHoldingsMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val (realItems, simItems) = getHoldingsData()
                    val allHoldings = (realItems + simItems).distinctBy { it.symbol.uppercase() }

                    if (allHoldings.isNotEmpty()) {
                        // Tarik ticker pasar seluruh koin dari Indodax via summaries API (1 request hemat bandwidth)
                        val marketTicks = IndodaxMarketService.fetchAllMarketTicks()
                        val positionStore = SpotPositionStore(applicationContext)
                        var pricesUpdated = false

                        for (item in allHoldings) {
                            val sym = item.symbol.uppercase()
                            val tick = marketTicks[sym] ?: marketTicks[sym.replace("IDR", "_IDR")]
                            val currentPrice = tick?.price ?: livePrices[sym] ?: 0.0

                            if (currentPrice > 0.0) {
                                if (livePrices[sym] != currentPrice) {
                                    livePrices[sym] = currentPrice
                                    pricesUpdated = true
                                }

                                // 1. Rekam tick ke TickHistoryTracker agar deteksi drop 1m/5m & velocity bekerja
                                TickHistoryTracker.recordTick(sym, currentPrice)

                                // 2. Update trailing stop jika di-enable
                                val pos = positionStore.get(sym, item.isReal)
                                if (pos.isHolding && pos.isTrailingEnabled) {
                                    positionStore.updateTrailingPrice(sym, currentPrice, item.isReal)
                                }

                                // 3. Ambil snapshot risiko
                                val peak = if (pos.isHolding && pos.peakPrice > 0.0) pos.peakPrice else null
                                val riskSnapshot = TickHistoryTracker.getSnapshot(
                                    symbol = sym,
                                    currentPrice = currentPrice,
                                    peakPrice = peak
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

                                // 5. Evaluasi Sinyal Jual (termasuk RAPID_DROP_EXIT dan STOP_LOSS_HIT)
                                val sellState = SellSignalEvaluator.evaluate(
                                    context = posContext,
                                    indicators = null,
                                    tradingFees = TradingFeeConfig(),
                                    riskSnapshot = riskSnapshot
                                )

                                // 6. Proses transisi lifecycle
                                val transition = SellSignalLifecycleManager.process(
                                    symbol = sym,
                                    newState = sellState,
                                    isReal = item.isReal
                                )

                                // 7. Emergency Alert Dispatcher (Rapid Drop & Stop Loss)
                                if (sellState.state == SellLifecycleState.RAPID_DROP_EXIT ||
                                    sellState.state == SellLifecycleState.STOP_LOSS_HIT) {

                                    val lastAlert = lastEmergencyAlertTimes[sym] ?: 0L
                                    val now = System.currentTimeMillis()
                                    if (transition.hasTriggeringTransition || (now - lastAlert > EMERGENCY_ALERT_COOLDOWN_MS)) {
                                        lastEmergencyAlertTimes[sym] = now
                                        AlertNotificationHelper.sendEmergencyExitNotification(
                                            context = applicationContext,
                                            symbol = sym,
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
                                        symbol = sym,
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
                                        symbol = sym,
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
        val pair = TradingPair.fromCustomSymbol(item.symbol)
        val quoteAsset = pair.quoteAsset
        val currPriceStr = PriceFormatter.formatPrice(item.currentPrice, showSymbol = true, quoteAsset = quoteAsset)
        val qtyStr = formatCoinQuantity(item.quantity, item.baseAsset)

        return if (item.entryPrice > 0.0) {
            val entryPriceStr = PriceFormatter.formatPrice(item.entryPrice, showSymbol = true, quoteAsset = quoteAsset)
            val pctFormatted = PriceFormatter.formatPercentage(item.diffPct, includePlusSign = true)
            val statusTag = if (item.isProfit) "▲ $pctFormatted  [SIAP JUAL]" else "▼ $pctFormatted  [HOLD]"
            "• ${item.baseAsset}  $currPriceStr  $statusTag\n  Beli: $entryPriceStr • Saldo: $qtyStr"
        } else {
            "• ${item.baseAsset}  $currPriceStr  [HOLD]\n  Saldo: $qtyStr"
        }
    }

    private fun updateNotification() {
        lastUpdateTime = System.currentTimeMillis()
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
                    val qAsset = TradingPair.fromCustomSymbol(it.symbol).quoteAsset
                    "${it.baseAsset} ${PriceFormatter.formatPrice(it.currentPrice, showSymbol = false, quoteAsset = qAsset)}"
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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText(collapsedText)
            .setSubText("Indodax Spot")
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
        val savedRealBalance = if (prefs.hasIndodaxCredentials()) prefs.getSavedRealBalance() else emptyMap()
        val savedAvgPrices = if (prefs.hasIndodaxCredentials()) prefs.getSavedRealAvgBuyPrices() else emptyMap()
        
        // Scan semua kemungkinan pair: daftar populer + koin yang ada saldo di akun real
        val processedBases = mutableSetOf<String>()
        val realCandidatePairs = mutableListOf<TradingPair>()

        for (pair in TradingPair.POPULAR_INDODAX_PAIRS) {
            val base = pair.baseAsset.uppercase()
            if (base != "IDR" && base != "USDT") {
                processedBases.add(base)
                realCandidatePairs.add(pair)
            }
        }
        for ((baseKey, qty) in savedRealBalance) {
            val base = baseKey.uppercase()
            if (qty > 0.00000001 && base != "IDR" && base != "USDT" && !processedBases.contains(base)) {
                processedBases.add(base)
                realCandidatePairs.add(TradingPair.fromCustomSymbol("${base}IDR"))
            }
        }
        
        for (pair in realCandidatePairs) {
            val baseLower = pair.baseAsset.lowercase()
            val baseUpper = pair.baseAsset.uppercase()
            val symUpper = pair.symbol.uppercase()
            val pos = positionStore.get(pair.symbol, isReal = true)

            val realQty = savedRealBalance[baseLower] ?: savedRealBalance[baseUpper] ?: 0.0
            val isHoldingInStore = pos.isHolding && pos.quantity > 0.0
            val isHoldingInReal = realQty > 0.00000001

            if (isHoldingInStore || isHoldingInReal) {
                val qty = if (isHoldingInStore && pos.quantity > 0.0) pos.quantity else realQty
                val entryPrice = if (isHoldingInStore && pos.entryPrice > 0.0) {
                    pos.entryPrice
                } else {
                    savedAvgPrices[baseUpper] ?: savedAvgPrices[symUpper] ?: savedAvgPrices[baseLower] ?: 0.0
                }

                val currentPrice = livePrices[symUpper] ?: (if (entryPrice > 0.0) entryPrice else 0.0)
                if (currentPrice <= 0.0 && entryPrice <= 0.0) continue

                // Check jika koin di store sudah habis terjual di real
                if (savedRealBalance.isNotEmpty() && isHoldingInStore && realQty <= 0.00000001) {
                    positionStore.markSold(pair.symbol, isReal = true)
                    // Clear sell-signal lifecycle agar tidak tetap muncul di Ready-to-Sell
                    agu.analys.engine.sell.SellSignalLifecycleManager.reset(pair.symbol, isReal = true)
                    continue
                }

                realItems.add(
                    HoldingItem(
                        symbol = pair.symbol,
                        baseAsset = baseUpper,
                        quantity = qty,
                        entryPrice = entryPrice,
                        currentPrice = currentPrice,
                        isReal = true
                    )
                )
            }
        }

        val sortedReal = realItems.sortedWith(
            compareByDescending<HoldingItem> { it.isProfit }
                .thenByDescending { it.diffPct }
                .thenBy { it.baseAsset }
        )

        // 2. Check Simulated positions (Simulation Wallet / SimulationTradeStore)
        val simItems = mutableListOf<HoldingItem>()
        for ((baseAsset, qty) in wallet.coinBalances) {
            val baseAssetUpper = baseAsset.uppercase()
            if (qty > 0.00000001 && baseAssetUpper != "IDR") {
                val symbol = "${baseAssetUpper}IDR"
                val avgPrice = wallet.avgBuyPrices[baseAsset] ?: 0.0
                val currentPrice = livePrices[symbol] ?: avgPrice
                if (currentPrice <= 0.0 && avgPrice <= 0.0) continue

                simItems.add(
                    HoldingItem(
                        symbol = symbol,
                        baseAsset = baseAssetUpper,
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

        fun updatePrice(context: Context, symbol: String, price: Double) {
            val prefs = AppPreferences(context)
            if (!prefs.isNotificationsEnabled) return
            val symUpper = symbol.uppercase()
            val oldPrice = livePrices[symUpper]
            if (oldPrice == price) return // Avoid redundant notification redraw updates if price hasn't changed

            livePrices[symUpper] = price
            val intent = Intent(context, TradingForegroundService::class.java).apply {
                action = ACTION_UPDATE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun updatePrices(context: Context, prices: Map<String, Double>) {
            val prefs = AppPreferences(context)
            if (!prefs.isNotificationsEnabled) return
            var changed = false
            for ((sym, price) in prices) {
                val symUpper = sym.uppercase()
                if (livePrices[symUpper] != price) {
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
