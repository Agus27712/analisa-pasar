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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
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

    private fun updateNotification() {
        val title = "Monitor Aktif"
        val contentText = getOwnedCoinsSummary()

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
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(contentText.substringBefore("\n"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Monitor", stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun getOwnedCoinsSummary(): String {
        val context = applicationContext
        val positionStore = SpotPositionStore(context)
        val simulationStore = SimulationTradeStore(context)
        val wallet = simulationStore.getWallet()

        val sb = StringBuilder()

        // 1. Check Real Spot positions (Dual Source: SpotPositionStore + Saved Real Balance fallback)
        val realHoldings = mutableListOf<String>()
        var realProfitCount = 0

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

                val isProfit = entryPrice > 0.0 && currentPrice > entryPrice
                val diffPct = if (entryPrice > 0.0) ((currentPrice - entryPrice) / entryPrice) * 100.0 else 0.0
                
                val statusStr = if (isProfit) {
                    realProfitCount++
                    "🔥 + (+${String.format(Locale.US, "%.2f", diffPct)}%) [SIAP JUAL!]"
                } else if (entryPrice > 0.0) {
                    "❄️ WAIT (${String.format(Locale.US, "%.2f", diffPct)}%)"
                } else {
                    "⏳ HOLD (${PriceFormatter.formatPrice(qty)} ${pair.baseAsset})"
                }

                val entryStr = if (entryPrice > 0.0) " @ Rp ${PriceFormatter.formatPrice(entryPrice)}" else ""
                realHoldings.add(
                    "${pair.baseAsset}: ${PriceFormatter.formatPrice(qty)}$entryStr -> Live Rp ${PriceFormatter.formatPrice(currentPrice)} $statusStr"
                )
            }
        }

        // 2. Check Simulated positions (Simulation Wallet / SimulationTradeStore)
        val simHoldings = mutableListOf<String>()
        var simProfitCount = 0
        for ((baseAsset, qty) in wallet.coinBalances) {
            val baseAssetUpper = baseAsset.uppercase()
            if (qty > 0.00000001 && baseAssetUpper != "IDR") {
                val symbol = "${baseAssetUpper}IDR"
                val avgPrice = wallet.avgBuyPrices[baseAsset] ?: 0.0
                val currentPrice = livePrices[symbol] ?: avgPrice
                val isProfit = currentPrice > avgPrice && avgPrice > 0.0
                val diffPct = if (avgPrice > 0.0) ((currentPrice - avgPrice) / avgPrice) * 100.0 else 0.0
                
                val statusStr = if (isProfit) {
                    simProfitCount++
                    "🔥 + (+${String.format(Locale.US, "%.2f", diffPct)}%) [SIAP JUAL!]"
                } else {
                    "❄️ WAIT (${String.format(Locale.US, "%.2f", diffPct)}%)"
                }

                simHoldings.add(
                    "${baseAssetUpper} (Sim): ${PriceFormatter.formatPrice(qty)} @ Rp ${PriceFormatter.formatPrice(avgPrice)} -> Live Rp ${PriceFormatter.formatPrice(currentPrice)} $statusStr"
                )
            }
        }

        if (realHoldings.isEmpty() && simHoldings.isEmpty()) {
            return "Belum ada pair yang dimiliki saat ini.\nBeli atau tambahkan posisi untuk memantau."
        }

        if (realHoldings.isNotEmpty()) {
            sb.append("ASET REAL (Siap Jual + : $realProfitCount):\n")
            realHoldings.forEach { sb.append("• $it\n") }
        }
        if (simHoldings.isNotEmpty()) {
            if (realHoldings.isNotEmpty()) sb.append("\n")
            sb.append("ASET SIMULASI (Siap Jual + : $simProfitCount):\n")
            simHoldings.forEach { sb.append("• $it\n") }
        }

        return sb.toString().trim()
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
