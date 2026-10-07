package agu.analys.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import agu.analys.MainActivity
import agu.analys.config.StrategyMode
import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.MarketKey
import agu.analys.model.SellSignalState
import agu.analys.model.TradingPair

object AlertNotificationHelper {
    // Channel 1: Candidate / Pair Ready to Buy
    const val CHANNEL_CANDIDATE_ID = "channel_candidate_buy_alerts"
    const val CHANNEL_CANDIDATE_NAME = "Notifikasi Pair Ready & Sinyal Buy"
    const val CHANNEL_CANDIDATE_DESC = "Sinyal koin kandidat strategi yang siap entry / buy"

    // Channel 2: Trailing Stop & Execution
    const val CHANNEL_TRAILING_ID = "channel_trailing_stop_alerts"
    const val CHANNEL_TRAILING_NAME = "Notifikasi Trailing Stop & Eksekusi"
    const val CHANNEL_TRAILING_DESC = "Sinyal kenaikan trailing profit dan eksekusi take profit otomatis"

    // Channel 3: Emergency Exit & Stop Loss (Rapid Drop / Flash Dump)
    const val CHANNEL_EMERGENCY_EXIT_ID = "channel_emergency_exit_alerts"
    const val CHANNEL_EMERGENCY_EXIT_NAME = "Notifikasi Exit Darurat & Stop Loss"
    const val CHANNEL_EMERGENCY_EXIT_DESC = "Peringatan darurat saat Stop Loss tersentuh atau terjadi Flash Dump / Rapid Drop"

    // Channel 4: General Price Alerts
    const val CHANNEL_PRICE_ALERT_ID = "channel_price_alerts"
    const val CHANNEL_PRICE_ALERT_NAME = "Notifikasi Target Harga & Market"
    const val CHANNEL_PRICE_ALERT_DESC = "Notifikasi perubahan harga target dan indikator teknikal"

    // Extra Constants for Intent routing
    const val EXTRA_SYMBOL = "EXTRA_SYMBOL"
    const val EXTRA_EXCHANGE = "EXTRA_EXCHANGE"
    const val EXTRA_QUOTE = "EXTRA_QUOTE"
    const val EXTRA_IS_REAL = "EXTRA_IS_REAL"
    const val EXTRA_LIMIT_PRICE = "EXTRA_LIMIT_PRICE"
    const val EXTRA_QUANTITY = "EXTRA_QUANTITY"
    const val ACTION_EXECUTE_TRAILING_SELL = "agu.analys.ACTION_EXECUTE_TRAILING_SELL"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val audioAttributesNotification = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build()

            val audioAttributesAlarm = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            // Candidate Buy Channel
            val candidateSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val candidateChannel = NotificationChannel(
                CHANNEL_CANDIDATE_ID,
                CHANNEL_CANDIDATE_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_CANDIDATE_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                setSound(candidateSoundUri, audioAttributesNotification)
            }

            // Trailing Stop Channel
            val trailingSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val trailingChannel = NotificationChannel(
                CHANNEL_TRAILING_ID,
                CHANNEL_TRAILING_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_TRAILING_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setSound(trailingSoundUri, audioAttributesNotification)
            }

            // Emergency Exit Channel (High priority / Alarm feel)
            val emergencySoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val emergencyChannel = NotificationChannel(
                CHANNEL_EMERGENCY_EXIT_ID,
                CHANNEL_EMERGENCY_EXIT_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_EMERGENCY_EXIT_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setSound(emergencySoundUri, audioAttributesAlarm)
            }

            // General Price Alert Channel
            val priceAlertSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val priceAlertChannel = NotificationChannel(
                CHANNEL_PRICE_ALERT_ID,
                CHANNEL_PRICE_ALERT_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_PRICE_ALERT_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150)
                setSound(priceAlertSoundUri, audioAttributesNotification)
            }

            notificationManager.createNotificationChannels(
                listOf(candidateChannel, trailingChannel, emergencyChannel, priceAlertChannel)
            )
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    // 1. Candidate Buy Notification
    fun sendCandidateFoundNotification(
        context: Context,
        symbol: String,
        strategyMode: StrategyMode,
        signal: AISignalState,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendCandidateFoundNotification(context, marketKey, strategyMode, signal)
    }

    fun sendCandidateFoundNotification(
        context: Context,
        marketKey: MarketKey,
        strategyMode: StrategyMode,
        signal: AISignalState
    ) {
        if (!hasNotificationPermission(context)) return

        val strategyLabel = when (strategyMode) {
            StrategyMode.SCALPING -> "SCALPING M15/M5"
            StrategyMode.OFFICE_DAILY -> "INTRADAY H1"
            StrategyMode.SWING -> "SWING H4/D1"
        }

        val pairFormatted = marketKey.formattedPair()
        val quoteAsset = marketKey.quote

        val title = "🚀 Sinyal Entry Siap • $pairFormatted ($strategyLabel)"
        val entryFormatted = PriceFormatter.formatPrice(signal.entryPrice, showSymbol = true, quoteAsset = quoteAsset)
        val tp1Formatted = PriceFormatter.formatPrice(signal.targetPrice1, showSymbol = true, quoteAsset = quoteAsset)
        val slFormatted = PriceFormatter.formatPrice(signal.stopLoss, showSymbol = true, quoteAsset = quoteAsset)

        val message = "Kandidat BUY terkonfirmasi [${marketKey.displayExchange}]:\n" +
                "• Entry: $entryFormatted\n" +
                "• Target 1: $tp1Formatted\n" +
                "• Stop Loss: $slFormatted\n" +
                "• Skor Keyakinan: ${(signal.confidence * 100).toInt()}%"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(false, 100),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CANDIDATE_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Kandidat BUY terdeteksi di $entryFormatted")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Lihat Chart", pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(false, 100), notification)
    }

    // 2. Emergency Exit Notification
    fun sendEmergencyExitNotification(
        context: Context,
        symbol: String,
        state: SellSignalState,
        currentPrice: Double,
        entryPrice: Double,
        quantity: Double,
        isReal: Boolean,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendEmergencyExitNotification(context, marketKey, state, currentPrice, entryPrice, quantity, isReal)
    }

    fun sendEmergencyExitNotification(
        context: Context,
        marketKey: MarketKey,
        state: SellSignalState,
        currentPrice: Double,
        entryPrice: Double,
        quantity: Double,
        isReal: Boolean
    ) {
        if (!hasNotificationPermission(context)) return

        val pairFormatted = marketKey.formattedPair()
        val quoteAsset = marketKey.quote
        val modeTag = if (isReal) "REAL" else "SIMULASI"

        val currFormatted = PriceFormatter.formatPrice(currentPrice, showSymbol = true, quoteAsset = quoteAsset)
        val entryFormatted = PriceFormatter.formatPrice(entryPrice, showSymbol = true, quoteAsset = quoteAsset)

        val title = "🚨 EXIT DARURAT [$modeTag] • $pairFormatted"
        val message = "PERINGATAN: ${state.reason}\n" +
                "• Harga Terkini: $currFormatted\n" +
                "• Harga Beli: $entryFormatted\n" +
                "• Estimasi PnL: ${String.format(java.util.Locale.US, "%.2f", state.netProfitPct)}%\n" +
                "Segera periksa posisi Anda di [${marketKey.displayExchange}]."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 200),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sellIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_EXECUTE_TRAILING_SELL
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
            putExtra(EXTRA_LIMIT_PRICE, currentPrice)
            putExtra(EXTRA_QUANTITY, quantity)
            putExtra(EXTRA_IS_REAL, isReal)
        }
        val sellPendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 201),
            sellIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionLabel = if (isReal) "JUAL REAL SEKARANG" else "JUAL SEKARANG"

        val notification = NotificationCompat.Builder(context, CHANNEL_EMERGENCY_EXIT_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Bahaya: ${state.reason}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, actionLabel, sellPendingIntent)
            .addAction(0, "Buka Chart", pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(isReal, 200), notification)
    }

    // 3. Trailing Hit Notification
    fun sendTrailingHitNotification(
        context: Context,
        symbol: String,
        entryPrice: Double,
        peakPrice: Double,
        currentPrice: Double,
        limitSellPrice: Double,
        quantity: Double,
        isReal: Boolean,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendTrailingHitNotification(context, marketKey, entryPrice, peakPrice, currentPrice, limitSellPrice, quantity, isReal)
    }

    fun sendTrailingHitNotification(
        context: Context,
        marketKey: MarketKey,
        entryPrice: Double,
        peakPrice: Double,
        currentPrice: Double,
        limitSellPrice: Double,
        quantity: Double,
        isReal: Boolean
    ) {
        if (!hasNotificationPermission(context)) return

        val pairFormatted = marketKey.formattedPair()
        val quoteAsset = marketKey.quote
        val modeTag = if (isReal) "REAL" else "SIMULASI"

        val profitPct = if (entryPrice > 0.0) ((limitSellPrice - entryPrice) / entryPrice) * 100.0 else 0.0
        val profitStr = String.format(java.util.Locale.US, "%.2f", profitPct)

        val title = "⚡ Trailing Stop Terpicu [$modeTag] • $pairFormatted"
        val limitFormatted = PriceFormatter.formatPrice(limitSellPrice, showSymbol = true, quoteAsset = quoteAsset)
        val peakFormatted = PriceFormatter.formatPrice(peakPrice, showSymbol = true, quoteAsset = quoteAsset)
        val currFormatted = PriceFormatter.formatPrice(currentPrice, showSymbol = true, quoteAsset = quoteAsset)

        val message = "Batas pengaman trailing tercapai [${marketKey.displayExchange}]:\n" +
                "• Harga Eksekusi: $limitFormatted\n" +
                "• Harga Puncak: $peakFormatted\n" +
                "• Harga Terkini: $currFormatted\n" +
                "• Estimasi Profit: +$profitStr%\n" +
                "Auto-sell otomatis diluncurkan untuk mengamankan profit."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 300),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TRAILING_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Trailing stop tersentuh di $limitFormatted (+$profitStr%)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Cek Portofolio", pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(isReal, 300), notification)
    }

    // 4. Trailing Peak Update Notification
    fun sendTrailingPeakUpdateNotification(
        context: Context,
        symbol: String,
        newPeak: Double,
        stopLimitPrice: Double,
        entryPrice: Double,
        profitPct: Double,
        isReal: Boolean,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendTrailingPeakUpdateNotification(context, marketKey, newPeak, stopLimitPrice, entryPrice, profitPct, isReal)
    }

    fun sendTrailingPeakUpdateNotification(
        context: Context,
        marketKey: MarketKey,
        newPeak: Double,
        stopLimitPrice: Double,
        entryPrice: Double,
        profitPct: Double,
        isReal: Boolean
    ) {
        if (!hasNotificationPermission(context)) return

        val pairFormatted = marketKey.formattedPair()
        val quoteAsset = marketKey.quote
        val modeTag = if (isReal) "REAL" else "SIMULASI"

        val profitStr = String.format(java.util.Locale.US, "%.2f", profitPct)
        val peakFormatted = PriceFormatter.formatPrice(newPeak, showSymbol = true, quoteAsset = quoteAsset)
        val stopFormatted = PriceFormatter.formatPrice(stopLimitPrice, showSymbol = true, quoteAsset = quoteAsset)

        val title = "📈 Trailing Peak Naik [$modeTag] • $pairFormatted"
        val message = "Puncak harga naik ke $peakFormatted [${marketKey.displayExchange}] (Profit Puncak: +$profitStr%).\n" +
                "Batas stop limit otomatis dinaikkan ke $stopFormatted untuk mengunci profit."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 400),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TRAILING_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Stop limit dinaikkan ke $stopFormatted (+$profitStr%)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(isReal, 400), notification)
    }

    // 5. Take Profit Reached Notification
    fun sendTakeProfitNotification(
        context: Context,
        symbol: String,
        targetLabel: String,
        entryPrice: Double,
        currentPrice: Double,
        netProfitPct: Double,
        quantity: Double,
        isReal: Boolean,
        exchange: String = "TOKOCRYPTO",
        quote: String? = null
    ) {
        val marketKey = MarketKey.resolve(symbol, exchange, quote)
        sendTakeProfitNotification(context, marketKey, targetLabel, entryPrice, currentPrice, netProfitPct, quantity, isReal)
    }

    fun sendTakeProfitNotification(
        context: Context,
        marketKey: MarketKey,
        targetLabel: String,
        entryPrice: Double,
        currentPrice: Double,
        netProfitPct: Double,
        quantity: Double,
        isReal: Boolean
    ) {
        if (!hasNotificationPermission(context)) return

        val pairFormatted = marketKey.formattedPair()
        val quoteAsset = marketKey.quote
        val modeTag = if (isReal) "REAL" else "SIMULASI"

        val profitStr = String.format(java.util.Locale.US, "%.2f", netProfitPct)
        val currFormatted = PriceFormatter.formatPrice(currentPrice, showSymbol = true, quoteAsset = quoteAsset)
        val entryFormatted = PriceFormatter.formatPrice(entryPrice, showSymbol = true, quoteAsset = quoteAsset)

        val title = "🎯 Target Tercapai ($targetLabel) [$modeTag] • $pairFormatted"
        val message = "Posisi $pairFormatted di [${marketKey.displayExchange}] mencapai target:\n" +
                "• Harga Jual: $currFormatted\n" +
                "• Harga Beli: $entryFormatted\n" +
                "• Net Profit: +$profitStr%\n" +
                "Koin siap untuk direalisasikan."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 500),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sellIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_EXECUTE_TRAILING_SELL
            putExtra(EXTRA_SYMBOL, marketKey.symbol)
            putExtra(EXTRA_EXCHANGE, marketKey.exchange)
            putExtra(EXTRA_QUOTE, marketKey.quote)
            putExtra(EXTRA_LIMIT_PRICE, currentPrice)
            putExtra(EXTRA_QUANTITY, quantity)
            putExtra(EXTRA_IS_REAL, isReal)
        }
        val sellPendingIntent = PendingIntent.getActivity(
            context,
            marketKey.toNotificationId(isReal, 501),
            sellIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionLabel = if (isReal) "JUAL REAL SEKARANG" else "JUAL SEKARANG"

        val notification = NotificationCompat.Builder(context, CHANNEL_TRAILING_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText("Target tercapai di $currFormatted (+$profitStr%)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, actionLabel, sellPendingIntent)
            .addAction(0, "Cek Portofolio", pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(marketKey.toNotificationId(isReal, 500), notification)
    }

    // 6. Generic Price Alert Notification
    fun sendPriceAlertNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = 9999,
        symbol: String = "",
        exchange: String = "TOKOCRYPTO",
        quote: String? = null,
        onlyWhenBackground: Boolean = false
    ) {
        val marketKey = if (symbol.isNotBlank()) MarketKey.resolve(symbol, exchange, quote) else null
        sendPriceAlertNotification(context, title, message, notificationId, marketKey, onlyWhenBackground)
    }

    fun sendPriceAlertNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = 9999,
        marketKey: MarketKey? = null,
        onlyWhenBackground: Boolean = false
    ) {
        if (!hasNotificationPermission(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (marketKey != null) {
                putExtra(EXTRA_SYMBOL, marketKey.symbol)
                putExtra(EXTRA_EXCHANGE, marketKey.exchange)
                putExtra(EXTRA_QUOTE, marketKey.quote)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PRICE_ALERT_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }
}
