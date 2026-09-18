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
import agu.analys.util.PriceFormatter

object AlertNotificationHelper {
    // Channel 1: Candidate / Pair Ready to Buy
    const val CHANNEL_CANDIDATE_ID = "channel_candidate_buy_alerts"
    const val CHANNEL_CANDIDATE_NAME = "Notifikasi Pair Ready & Sinyal Buy"
    const val CHANNEL_CANDIDATE_DESC = "Sinyal koin kandidat strategi yang siap entry / buy"

    // Channel 2: Trailing Stop & Execution
    const val CHANNEL_TRAILING_ID = "channel_trailing_stop_alerts"
    const val CHANNEL_TRAILING_NAME = "Notifikasi Trailing Stop & Eksekusi"
    const val CHANNEL_TRAILING_DESC = "Sinyal penting trailing profit, stop loss, dan eksekusi jual otomatis"

    // Channel 3: General Price Alerts
    const val CHANNEL_PRICE_ALERT_ID = "channel_price_alerts"
    const val CHANNEL_PRICE_ALERT_NAME = "Notifikasi Target Harga & Market"
    const val CHANNEL_PRICE_ALERT_DESC = "Notifikasi perubahan harga target dan indikator teknikal"

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

            // Candidate Buy Channel (Distinct Sound: Notification Sound)
            val candidateSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val candidateChannel = NotificationChannel(
                CHANNEL_CANDIDATE_ID,
                CHANNEL_CANDIDATE_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_CANDIDATE_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150)
                setSound(candidateSoundUri, audioAttributesNotification)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            // Trailing Stop Channel (Distinct Sound: Alarm / High Alert Sound)
            val trailingSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val trailingChannel = NotificationChannel(
                CHANNEL_TRAILING_ID,
                CHANNEL_TRAILING_NAME,
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = CHANNEL_TRAILING_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 100, 300, 100, 300)
                setSound(trailingSoundUri, audioAttributesAlarm)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            // General Price Alert Channel
            val priceChannel = NotificationChannel(
                CHANNEL_PRICE_ALERT_ID,
                CHANNEL_PRICE_ALERT_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_PRICE_ALERT_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            notificationManager.createNotificationChannels(listOf(candidateChannel, trailingChannel, priceChannel))
        }
    }

    fun sendPriceAlertNotification(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        symbol: String = "",
        onlyWhenBackground: Boolean = false
    ) {
        if (onlyWhenBackground && agu.analys.AppContextProvider.isAppInForeground) {
            timber.log.Timber.d("sendPriceAlertNotification: Diabaikan karena aplikasi aktif di foreground: $title")
            return
        }
        val prefs = AppPreferences(context)
        if (!prefs.isNotificationsEnabled || !prefs.isNotifyPriceAlertsEnabled) return

        createNotificationChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_SYMBOL", symbol)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PRICE_ALERT_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setColor(0xFF2563EB.toInt()) // Professional Royal Blue
            .setContentTitle(title)
            .setContentText(message.substringBefore("\n"))
            .setSubText(if (symbol.isNotBlank()) symbol.uppercase() else "Indodax")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {}
    }

    fun sendCandidateFoundNotification(
        context: Context,
        symbol: String,
        strategyMode: StrategyMode,
        signal: AISignalState
    ) {
        if (agu.analys.AppContextProvider.isAppInForeground) {
            timber.log.Timber.d("sendCandidateFoundNotification: Diabaikan karena aplikasi aktif di foreground: $symbol")
            return
        }
        val prefs = AppPreferences(context)
        if (!prefs.isNotificationsEnabled || !prefs.isNotifyCandidateBuyEnabled) return

        createNotificationChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_SYMBOL", symbol)
        }

        val notificationId = (symbol.uppercase().hashCode() xor (strategyMode.name.hashCode() * 31)) and 0x7FFFFFFF

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = when (strategyMode) {
            StrategyMode.SCALPING -> "Scalping"
            StrategyMode.SECOND_WAVE -> "Second-Wave"
            StrategyMode.SWING -> "Swing"
            StrategyMode.OFFICE_DAILY -> "Intraday"
            StrategyMode.TRENCHING -> "Trenching"
        }

        val stateLabel = if (signal.lifecycleState == LifecycleState.READY) "🟢 PAIR SIAP ENTRY (BUY)" else "⚡ KANDIDAT TERDETEKSI"
        val title = "$stateLabel • ${symbol.uppercase()}"

        val priceStr = if (signal.entryPrice > 0.0) "Rp ${PriceFormatter.formatIdrNumber(signal.entryPrice)}" else "-"
        val tp1Str = if (signal.targetPrice1 > 0.0) "Rp ${PriceFormatter.formatIdrNumber(signal.targetPrice1)}" else "-"
        val slStr = if (signal.stopLoss > 0.0) "Rp ${PriceFormatter.formatIdrNumber(signal.stopLoss)}" else "-"

        val reasonStr = signal.reasoning.firstOrNull() ?: signal.sentiment.displayName
        val message = "🎯 Strategi: $modeLabel | Confidence: ${signal.confidence}%\n" +
                "💰 Harga Entry: $priceStr\n" +
                "📈 Target TP1: $tp1Str | 🛡️ Cut Loss: $slStr\n" +
                "💡 Analisa: $reasonStr"

        val builder = NotificationCompat.Builder(context, CHANNEL_CANDIDATE_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setColor(0xFF059669.toInt()) // Professional Emerald Green
            .setContentTitle(title)
            .setContentText("Entry: $priceStr • TP1: $tp1Str • Conf: ${signal.confidence}%")
            .setSubText("${symbol.uppercase()} • $modeLabel")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {}
    }

    fun sendTrailingHitNotification(
        context: Context,
        symbol: String,
        entryPrice: Double,
        peakPrice: Double,
        currentPrice: Double,
        limitSellPrice: Double,
        quantity: Double,
        isReal: Boolean
    ) {
        val prefs = AppPreferences(context)
        if (!prefs.isNotificationsEnabled || !prefs.isNotifyTrailingStopEnabled) return

        createNotificationChannels(context)

        val modeTag = if (isReal) "[REAL]" else "[SIMULASI]"
        val notifBaseId = (symbol.hashCode() and 0x3FFFFFFF) + (if (isReal) 100000 else 200000)

        // Main Intent (when tapped)
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_SYMBOL", symbol)
            putExtra("EXTRA_IS_REAL", isReal)
        }
        val pendingMainIntent = PendingIntent.getActivity(
            context,
            notifBaseId + 1000,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action Intent (JUAL SEKARANG)
        val actionIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "agu.analys.ACTION_EXECUTE_TRAILING_SELL"
            putExtra("EXTRA_SYMBOL", symbol)
            putExtra("EXTRA_LIMIT_PRICE", limitSellPrice)
            putExtra("EXTRA_QUANTITY", quantity)
            putExtra("EXTRA_IS_REAL", isReal)
        }
        val pendingActionIntent = PendingIntent.getActivity(
            context,
            notifBaseId + 1001,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val profitPct = if (entryPrice > 0.0) ((limitSellPrice - entryPrice) / entryPrice) * 100.0 else 0.0
        val formattedProfit = String.format(java.util.Locale.US, "%.2f%%", profitPct)
        
        val title = "🛡️ $modeTag TRAILING PROFIT • ${symbol.uppercase()}"
        val message = "$modeTag 🚨 Keuntungan Terkunci: +$formattedProfit\n" +
                "💵 Harga Stop Limit: Rp ${PriceFormatter.formatIdrNumber(limitSellPrice)}\n" +
                "📊 Harga Running: Rp ${PriceFormatter.formatIdrNumber(currentPrice)} | Modal: Rp ${PriceFormatter.formatIdrNumber(entryPrice)}"

        val action = NotificationCompat.Action.Builder(
            0,
            if (isReal) "⚡ JUAL REAL SEKARANG" else "⚡ JUAL SIMULASI SEKARANG",
            pendingActionIntent
        ).build()

        val builder = NotificationCompat.Builder(context, CHANNEL_TRAILING_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setColor(if (isReal) 0xFFDC2626.toInt() else 0xFF2563EB.toInt())
            .setContentTitle(title)
            .setContentText("$modeTag Terkunci: +$formattedProfit (Rp ${PriceFormatter.formatIdrNumber(limitSellPrice)})")
            .setSubText("$modeTag ${symbol.uppercase()} • Trailing Stop")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingMainIntent)
            .addAction(action)

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(notifBaseId + 1000, builder.build())
        } catch (_: SecurityException) {}
    }

    fun sendTrailingPeakUpdateNotification(
        context: Context,
        symbol: String,
        newPeak: Double,
        stopLimitPrice: Double,
        entryPrice: Double,
        profitPct: Double,
        isReal: Boolean
    ) {
        val prefs = AppPreferences(context)
        if (!prefs.isNotificationsEnabled || !prefs.isNotifyTrailingStopEnabled) return

        createNotificationChannels(context)

        val modeTag = if (isReal) "[REAL]" else "[SIMULASI]"
        val notifId = (symbol.hashCode() and 0x3FFFFFFF) + (if (isReal) 100000 else 200000) + 500

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_SYMBOL", symbol)
            putExtra("EXTRA_IS_REAL", isReal)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "🚀 $modeTag TRAILING NAIK • ${symbol.uppercase()}"
        val message = "$modeTag 📈 Peak baru tercapai: Rp ${PriceFormatter.formatIdrNumber(newPeak)} (+${String.format(java.util.Locale.US, "%.2f", profitPct)}%)\n" +
                "🛡️ Batas Stop Limit dinaikkan ke: Rp ${PriceFormatter.formatIdrNumber(stopLimitPrice)}\n" +
                "💰 Modal Beli: Rp ${PriceFormatter.formatIdrNumber(entryPrice)}"

        val builder = NotificationCompat.Builder(context, CHANNEL_PRICE_ALERT_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setColor(if (isReal) 0xFF059669.toInt() else 0xFF2563EB.toInt())
            .setContentTitle(title)
            .setContentText("$modeTag Stop Limit naik: Rp ${PriceFormatter.formatIdrNumber(stopLimitPrice)} (+${String.format(java.util.Locale.US, "%.2f", profitPct)}%)")
            .setSubText("$modeTag ${symbol.uppercase()} • Trailing Naik")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(notifId, builder.build())
        } catch (_: SecurityException) {}
    }

    fun sendEmergencyExitNotification(
        context: Context,
        symbol: String,
        state: agu.analys.model.SellSignalState,
        currentPrice: Double,
        entryPrice: Double,
        quantity: Double,
        isReal: Boolean
    ) {
        val prefs = AppPreferences(context)
        if (!prefs.isNotificationsEnabled) return

        createNotificationChannels(context)

        val notifId = ((symbol.uppercase().hashCode() xor 0x5E11) and 0x3FFFFFFF) + (if (isReal) 300000 else 400000)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_SYMBOL", symbol)
            putExtra("EXTRA_IS_REAL", isReal)
        }
        val pendingMainIntent = PendingIntent.getActivity(
            context,
            notifId,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action Intent: langsung mengarahkan ke konfirmasi eksekusi jual darurat
        val actionIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "agu.analys.ACTION_EXECUTE_TRAILING_SELL"
            putExtra("EXTRA_SYMBOL", symbol)
            putExtra("EXTRA_LIMIT_PRICE", currentPrice)
            putExtra("EXTRA_QUANTITY", quantity)
            putExtra("EXTRA_IS_REAL", isReal)
        }
        val pendingActionIntent = PendingIntent.getActivity(
            context,
            notifId + 1,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeTag = if (isReal) "[REAL]" else "[SIM]"
        val dropReason = state.reason
        val pnlFormatted = PriceFormatter.formatPercentage(state.netProfitPct, includePlusSign = true)
        val title = "🚨 $modeTag EXIT DARURAT: ${symbol.uppercase()} ($pnlFormatted)"
        val priceStr = PriceFormatter.formatPrice(currentPrice, showSymbol = true)
        val entryStr = if (entryPrice > 0.0) PriceFormatter.formatPrice(entryPrice, showSymbol = true) else "-"
        val message = "$dropReason\n" +
                "💵 Harga Sekarang: $priceStr | Beli: $entryStr\n" +
                "⚠️ Segera periksa posisi dan amankan modal!"

        val action = NotificationCompat.Action.Builder(
            0,
            if (isReal) "⚡ EXIT REAL SEKARANG" else "⚡ EXIT SIMULASI",
            pendingActionIntent
        ).build()

        val builder = NotificationCompat.Builder(context, CHANNEL_TRAILING_ID)
            .setSmallIcon(agu.analys.R.drawable.ic_stat_trading)
            .setColor(0xFFDC2626.toInt()) // Emergency Red
            .setContentTitle(title)
            .setContentText("$dropReason ($pnlFormatted)")
            .setSubText("$modeTag ${symbol.uppercase()} • Exit Darurat")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingMainIntent)
            .addAction(action)

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(notifId, builder.build())
        } catch (_: SecurityException) {}
    }
}

