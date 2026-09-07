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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TradingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        val formatted = when {
            quantity >= 1000.0 -> {
                if (quantity % 1.0 == 0.0) {
                    String.format(Locale("id", "ID"), "%,d", quantity.toLong())
                } else {
                    String.format(Locale("id", "ID"), "%,.2f", quantity)
                }
            }
            quantity >= 1.0 -> {
                if (quantity % 1.0 == 0.0) {
                    quantity.toLong().toString()
                } else {
                    String.format(Locale.US, "%.4f", quantity).trimEnd('0').trimEnd('.')
                }
            }
            quantity < 0.0001 -> {
                String.format(Locale.US, "%.8f", quantity).trimEnd('0').trimEnd('.')
            }
            else -> {
                String.format(Locale.US, "%.6f", quantity).trimEnd('0').trimEnd('.')
            }
        }
        return "$formatted $baseAsset"
    }

    private fun formatHoldingCard(item: HoldingItem): String {
        val currPriceStr = PriceFormatter.formatPrice(item.currentPrice, showSymbol = true)
        val qtyStr = formatCoinQuantity(item.quantity, item.baseAsset)

        return if (item.entryPrice > 0.0) {
            val entryPriceStr = PriceFormatter.formatPrice(item.entryPrice, showSymbol = true)
            val pctFormatted = if (item.diffPct >= 0.0) {
                "+${String.format(Locale.US, "%.2f", item.diffPct)}%"
            } else {
                "${String.format(Locale.US, "%.2f", item.diffPct)}%"
            }
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
                    "${it.baseAsset} (+${String.format(Locale.US, "%.2f", it.diffPct)}%)"
                }
            }
            totalHoldings > 0 -> {
                val allList = realItems + simItems
                "Pantau: " + allList.take(3).joinToString(", ") {
                    "${it.baseAsset} ${PriceFormatter.formatPrice(it.currentPrice, showSymbol = false)}"
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
            val pos = positionStore.get(pair.symbol)

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
                    positionStore.markSold(pair.symbol)
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
