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
    }

    private fun getOwnedCoinsSummary(): String {
        val context = applicationContext
        val positionStore = SpotPositionStore(context)
        val simulationStore = SimulationTradeStore(context)
        val wallet = simulationStore.getWallet()

        val sb = StringBuilder()

        // 1. Check Real Spot positions (SpotPositionStore manual/real tracking)
        val realHoldings = mutableListOf<String>()
        var realProfitCount = 0

        val prefs = AppPreferences(context)
        val savedRealBalance = if (prefs.hasIndodaxCredentials()) prefs.getSavedRealBalance() else null
        
        for (pair in TradingPair.POPULAR_INDODAX_PAIRS) {
            val pos = positionStore.get(pair.symbol)
            if (pos.isHolding && pos.quantity > 0.0) {
                val currentPrice = livePrices[pair.symbol.uppercase()] ?: pos.entryPrice
                
                // Cross-reference with real balance if credentials and cache exist
                if (savedRealBalance != null) {
                    val actualQty = savedRealBalance[pair.baseAsset.lowercase()] ?: 0.0
                    val estimatedValueIdr = actualQty * currentPrice
                    if (actualQty <= 0.0001 || estimatedValueIdr < 5000.0) {
                        // Automatically clear the manual position tracker for this coin
                        positionStore.markSold(pair.symbol)
                        continue
                    }
                }

                val isProfit = currentPrice > pos.entryPrice && pos.entryPrice > 0.0
                val diffPct = if (pos.entryPrice > 0.0) ((currentPrice - pos.entryPrice) / pos.entryPrice) * 100.0 else 0.0
                
                val statusStr = if (isProfit) {
                    realProfitCount++
                    "🔥 + (+${String.format(Locale.US, "%.2f", diffPct)}%) [SIAP JUAL!]"
                } else {
                    "❄️ WAIT (${String.format(Locale.US, "%.2f", diffPct)}%)"
                }

                realHoldings.add(
                    "${pair.baseAsset}: ${PriceFormatter.formatPrice(pos.quantity)} @ Rp ${PriceFormatter.formatPrice(pos.entryPrice)} -> Live Rp ${PriceFormatter.formatPrice(currentPrice)} $statusStr"
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
            val intent = Intent(context, TradingForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun updatePrice(context: Context, symbol: String, price: Double) {
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
