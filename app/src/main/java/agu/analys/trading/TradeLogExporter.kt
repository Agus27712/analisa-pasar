package agu.analys.trading

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import agu.analys.util.PriceFormatter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility exporter & format generator untuk audit log trading.
 * Menghasilkan format JSON dan Markdown terstruktur yang siap diverifikasi oleh
 * LLM (Gemini, Claude, Grok, ChatGPT, DeepSeek).
 */
object TradeLogExporter {

    fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0L) return "Baru Entry (0m)"
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = (durationMs / (1000 * 60 * 60)) % 24
        val days = durationMs / (1000 * 60 * 60 * 24)

        return when {
            days > 0 -> "${days}h ${hours}j ${minutes}m"
            hours > 0 -> "${hours}j ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun buildSingleTradeAuditMarkdown(
        tradeId: String,
        symbol: String,
        baseAsset: String,
        quoteAsset: String,
        side: String,
        orderType: String,
        executionPrice: Double,
        quantity: Double,
        totalIdr: Double,
        feeIdr: Double,
        timestamp: Long,
        isReal: Boolean,
        strategyMode: String,
        holdingDurationMs: Long?,
        entryPrice: Double?,
        entryTimestamp: Long?,
        pnlIdr: Double?,
        pnlPercent: Double?,
        isTrailingUsed: Boolean,
        trailingPercent: Double?,
        trailingPeakPrice: Double?,
        trailingLockPrice: Double?,
        snapshot: TradeSignalSnapshot?
    ): String {
        val dateFormat = SimpleDateFormat("dd MMMM yyyy, HH:mm:ss 'WIB'", Locale("id", "ID"))
        val execTimeStr = dateFormat.format(Date(timestamp))
        val entryTimeStr = entryTimestamp?.let { dateFormat.format(Date(it)) } ?: "-"
        val quote = quoteAsset.ifBlank { "IDR" }

        val outcomeStr = when {
            side.equals("BUY", true) -> "ENTRY POSISI (HOLDING)"
            pnlIdr != null && pnlIdr > 0 -> "PROFIT / UNTUNG (+${PriceFormatter.formatPrice(pnlIdr, quoteAsset = quote)} | +${String.format(Locale.US, "%.2f", pnlPercent ?: 0.0)}%)"
            pnlIdr != null && pnlIdr < 0 -> "LOSS / RUGI (${PriceFormatter.formatPrice(pnlIdr, quoteAsset = quote)} | ${String.format(Locale.US, "%.2f", pnlPercent ?: 0.0)}%)"
            pnlIdr != null -> "BREAK EVEN (0.0%)"
            else -> "SELESAI"
        }

        val trailingInfoStr = if (isTrailingUsed) {
            "AKTIF (Peak: ${trailingPeakPrice?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"} | Lock Stop: ${trailingLockPrice?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"} | Trailing: ${trailingPercent ?: 0.0}%)"
        } else {
            "NONAKTIF"
        }

        val holdStr = formatDuration(holdingDurationMs)

        val sb = StringBuilder()
        sb.append("### INDODAX TRADING LOG & SIGNAL AUDIT REPORT\n")
        sb.append("Generated for LLM Verification (Gemini, Claude, Grok, ChatGPT)\n\n")

        sb.append("#### 📌 1. INFORMASI EKSEKUSI & STRATEGI\n")
        sb.append("- **ID Transaksi**: $tradeId\n")
        sb.append("- **Pasangan Aset**: $baseAsset/$quote ($symbol)\n")
        sb.append("- **Mode Trading**: ${strategyMode.uppercase()}\n")
        sb.append("- **Tipe Akun**: ${if (isReal) "REAL INDODAX" else "SIMULASI VIRTUAL"}\n")
        sb.append("- **Tindakan**: ${side.uppercase()} (${orderType.uppercase()})\n")
        sb.append("- **Waktu Eksekusi**: $execTimeStr\n")
        sb.append("- **Harga Eksekusi**: ${PriceFormatter.formatPrice(executionPrice, quoteAsset = quote)}\n")
        sb.append("- **Jumlah Koin**: ${PriceFormatter.formatRawDecimal(quantity)} $baseAsset\n")
        sb.append("- **Total Nominal**: ${PriceFormatter.formatPrice(totalIdr, quoteAsset = quote)}\n")
        sb.append("- **Estimasi Fee**: ${PriceFormatter.formatPrice(feeIdr, quoteAsset = quote)}\n\n")

        sb.append("#### ⏱ 2. DURASI HOLD & PNL (PROFIT/LOSS)\n")
        sb.append("- **Status Hasil**: $outcomeStr\n")
        if (entryPrice != null && entryPrice > 0.0) {
            sb.append("- **Harga Entry Buy**: ${PriceFormatter.formatPrice(entryPrice, quoteAsset = quote)}\n")
            sb.append("- **Waktu Entry Buy**: $entryTimeStr\n")
        }
        sb.append("- **Durasi Hold**: $holdStr\n")
        if (pnlIdr != null) {
            sb.append("- **PnL Bersih (Net)**: ${PriceFormatter.formatPrice(pnlIdr, quoteAsset = quote)} (${String.format(Locale.US, "%.2f", pnlPercent ?: 0.0)}%)\n")
        }
        sb.append("- **Trailing Profit Lock**: $trailingInfoStr\n\n")

        sb.append("#### 📊 3. METRIK SINYAL TEKNIKAL LENGKAP (PER-KATEGORI)\n")
        if (snapshot != null) {
            // Category A: Order Book & Liquidity
            sb.append("**[A. Order Book & Likuiditas Pasar]**\n")
            sb.append("- Bid vs Ask Ratio: Bid ${snapshot.bidRatioPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"} vs Ask ${snapshot.askRatioPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"}\n")
            sb.append("- Order Book Pressure: ${snapshot.orderBookPressure?.let { if (it > 0) "+$it% (Buyer)" else "$it% (Seller)" } ?: "Netral"}\n")
            sb.append("- Spread Bid/Ask: ${snapshot.spreadPct?.let { String.format(Locale.US, "%.3f%%", it) } ?: "-"}\n")
            sb.append("- Volume 24 Jam: ${snapshot.volume24h?.let { PriceFormatter.formatRawDecimal(it) } ?: "-"} $baseAsset\n")
            sb.append("- Perubahan Harga 24 Jam: ${snapshot.priceChange24h?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "-"}\n\n")

            // Category B: Momentum & Oscillators
            sb.append("**[B. Momentum & Osilator]**\n")
            sb.append("- RSI (14): ${snapshot.rsi14?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}\n")
            sb.append("- MACD Line: ${snapshot.macd?.let { String.format(Locale.US, "%.4f", it) } ?: "-"}\n")
            sb.append("- MACD Signal: ${snapshot.macdSignal?.let { String.format(Locale.US, "%.4f", it) } ?: "-"}\n")
            sb.append("- MACD Histogram: ${snapshot.macdHist?.let { String.format(Locale.US, "%.4f", it) } ?: "-"}\n")
            sb.append("- Momentum Score: ${snapshot.momentum?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}\n\n")

            // Category C: Trend & Moving Averages
            sb.append("**[C. Tren & Exponential Moving Averages]**\n")
            sb.append("- EMA 20: ${snapshot.ema20?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- EMA 50: ${snapshot.ema50?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- EMA 200: ${snapshot.ema200?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- Status Tren: ${snapshot.trendStatus ?: "Konsolidasi"}\n\n")

            // Category D: Volatility & Bollinger Bands
            sb.append("**[D. Volatilitas & Bollinger Bands]**\n")
            sb.append("- Bollinger Upper: ${snapshot.bbUpper?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- Bollinger Middle: ${snapshot.bbMiddle?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- Bollinger Lower: ${snapshot.bbLower?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- Bandwidth %: ${snapshot.bbWidthPct?.let { String.format(Locale.US, "%.2f%%", it) } ?: "-"}\n")
            sb.append("- Average True Range (ATR): ${snapshot.atr?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n\n")

            // Category E: AI Evaluator Decision & Reasons
            sb.append("**[E. Evaluator Signal & Reasoning]**\n")
            sb.append("- Skor Konfidensi: ${snapshot.confidenceScore?.let { "$it / 100" } ?: "-"}\n")
            sb.append("- Market Regime: ${snapshot.marketRegime ?: "-"}\n")
            if (snapshot.patternDetected != null) {
                sb.append("- Pola Terdeteksi: ${snapshot.patternDetected}\n")
            }
            if (snapshot.reasons.isNotEmpty()) {
                sb.append("- Alasan Trigger Sinyal:\n")
                snapshot.reasons.forEach { r ->
                    sb.append("  * $r\n")
                }
            }
        } else {
            sb.append("*Snapshot sinyal teknikal tidak tersedia untuk eksekusi manual ini.*\n")
        }

        sb.append("\n---\n")
        sb.append("#### 🤖 PETUNJUK VERIFIKASI UNTUK LLM (GEMINI / CLAUDE / GROK / CHATGPT):\n")
        sb.append("Mohon audit eksekusi trade di atas berdasarkan logika kuantitatif:\n")
        sb.append("1. Apakah entry dan exit selaras dengan kondisi momentum (RSI/MACD) dan likuiditas order book?\n")
        sb.append("2. Apakah durasi hold dan penggunaan trailing profit lock sudah efektif untuk membatasi risiko (Risk/Reward)?\n")
        sb.append("3. Berikan saran perbaikan rule filter strategi jika ada anomali atau peluang profit yang terlewatkan.\n")

        return sb.toString()
    }

    fun buildSingleTradeAuditJson(
        tradeId: String,
        symbol: String,
        baseAsset: String,
        quoteAsset: String,
        side: String,
        orderType: String,
        executionPrice: Double,
        quantity: Double,
        totalIdr: Double,
        feeIdr: Double,
        timestamp: Long,
        isReal: Boolean,
        strategyMode: String,
        holdingDurationMs: Long?,
        entryPrice: Double?,
        entryTimestamp: Long?,
        pnlIdr: Double?,
        pnlPercent: Double?,
        isTrailingUsed: Boolean,
        trailingPercent: Double?,
        trailingPeakPrice: Double?,
        trailingLockPrice: Double?,
        snapshot: TradeSignalSnapshot?
    ): JSONObject {
        val root = JSONObject()
        root.put("tradeId", tradeId)
        root.put("symbol", symbol)
        root.put("baseAsset", baseAsset)
        root.put("quoteAsset", quoteAsset)
        root.put("side", side)
        root.put("orderType", orderType)
        root.put("executionPrice", executionPrice)
        root.put("quantity", quantity)
        root.put("totalIdr", totalIdr)
        root.put("feeIdr", feeIdr)
        root.put("timestamp", timestamp)
        root.put("isReal", isReal)
        root.put("strategyMode", strategyMode)
        root.put("holdingDurationMs", holdingDurationMs ?: 0L)
        root.put("holdingDurationFormatted", formatDuration(holdingDurationMs))
        entryPrice?.let { root.put("entryPrice", it) }
        entryTimestamp?.let { root.put("entryTimestamp", it) }
        pnlIdr?.let { root.put("pnlIdr", it) }
        pnlPercent?.let { root.put("pnlPercent", it) }
        root.put("isProfit", (pnlIdr ?: 0.0) >= 0.0)
        root.put("isTrailingUsed", isTrailingUsed)
        trailingPercent?.let { root.put("trailingPercent", it) }
        trailingPeakPrice?.let { root.put("trailingPeakPrice", it) }
        trailingLockPrice?.let { root.put("trailingLockPrice", it) }
        if (snapshot != null) {
            root.put("signalSnapshot", snapshot.toJson())
        }
        return root
    }

    fun copyToClipboard(context: Context, text: String, label: String = "Trade Log Audit") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Log berhasil disalin! Siap ditempel ke LLM.", Toast.LENGTH_SHORT).show()
    }

    fun shareText(context: Context, text: String, title: String = "Bagikan Log Audit Trading") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun copyTradeMarkdownToClipboard(
        context: Context,
        tradeId: String,
        symbol: String,
        baseAsset: String,
        quoteAsset: String,
        side: String,
        orderType: String,
        executionPrice: Double,
        quantity: Double,
        totalIdr: Double,
        feeIdr: Double,
        timestamp: Long,
        isReal: Boolean,
        strategyMode: String,
        holdingDurationMs: Long?,
        entryPrice: Double?,
        entryTimestamp: Long?,
        pnlIdr: Double?,
        pnlPercent: Double?,
        isTrailingUsed: Boolean,
        trailingPercent: Double?,
        trailingPeakPrice: Double?,
        trailingLockPrice: Double?,
        snapshot: TradeSignalSnapshot?
    ) {
        val markdown = buildSingleTradeAuditMarkdown(
            tradeId = tradeId,
            symbol = symbol,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            side = side,
            orderType = orderType,
            executionPrice = executionPrice,
            quantity = quantity,
            totalIdr = totalIdr,
            feeIdr = feeIdr,
            timestamp = timestamp,
            isReal = isReal,
            strategyMode = strategyMode,
            holdingDurationMs = holdingDurationMs,
            entryPrice = entryPrice,
            entryTimestamp = entryTimestamp,
            pnlIdr = pnlIdr,
            pnlPercent = pnlPercent,
            isTrailingUsed = isTrailingUsed,
            trailingPercent = trailingPercent,
            trailingPeakPrice = trailingPeakPrice,
            trailingLockPrice = trailingLockPrice,
            snapshot = snapshot
        )
        copyToClipboard(context, markdown, "Trade Audit ($symbol)")
    }

    fun shareTradeLog(
        context: Context,
        tradeId: String,
        symbol: String,
        baseAsset: String,
        quoteAsset: String,
        side: String,
        orderType: String,
        executionPrice: Double,
        quantity: Double,
        totalIdr: Double,
        feeIdr: Double,
        timestamp: Long,
        isReal: Boolean,
        strategyMode: String,
        holdingDurationMs: Long?,
        entryPrice: Double?,
        entryTimestamp: Long?,
        pnlIdr: Double?,
        pnlPercent: Double?,
        isTrailingUsed: Boolean,
        trailingPercent: Double?,
        trailingPeakPrice: Double?,
        trailingLockPrice: Double?,
        snapshot: TradeSignalSnapshot?
    ) {
        val markdown = buildSingleTradeAuditMarkdown(
            tradeId = tradeId,
            symbol = symbol,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            side = side,
            orderType = orderType,
            executionPrice = executionPrice,
            quantity = quantity,
            totalIdr = totalIdr,
            feeIdr = feeIdr,
            timestamp = timestamp,
            isReal = isReal,
            strategyMode = strategyMode,
            holdingDurationMs = holdingDurationMs,
            entryPrice = entryPrice,
            entryTimestamp = entryTimestamp,
            pnlIdr = pnlIdr,
            pnlPercent = pnlPercent,
            isTrailingUsed = isTrailingUsed,
            trailingPercent = trailingPercent,
            trailingPeakPrice = trailingPeakPrice,
            trailingLockPrice = trailingLockPrice,
            snapshot = snapshot
        )
        shareText(context, markdown, "Log Audit Trading $symbol")
    }
}
