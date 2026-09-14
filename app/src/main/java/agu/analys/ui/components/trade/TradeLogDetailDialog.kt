package agu.analys.ui.components.trade

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import agu.analys.trading.TradeLogExporter
import agu.analys.trading.TradeSignalSnapshot
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TradeLogDetailDialog(
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
    snapshot: TradeSignalSnapshot?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBuy = side.equals("BUY", true)
    val sideColor = if (isBuy) TvGreen else TvRed
    val quote = quoteAsset.ifBlank { "IDR" }
    val timeFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = TvCardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(sideColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                .border(1.dp, sideColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = side.uppercase(),
                                color = sideColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "$baseAsset / $quote",
                                color = TvTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Mode: ${strategyMode.uppercase()} • ${if (isReal) "Real Indodax" else "Simulasi"}",
                                color = TvBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                    }
                }

                HorizontalDivider(color = TvBorder, modifier = Modifier.padding(vertical = 12.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Section 1: Execution & PnL Summary
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "1. HASIL EKSEKUSI & PNL",
                                color = TvAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                InfoItem("Harga Eksekusi", PriceFormatter.formatPrice(executionPrice, quoteAsset = quote))
                                InfoItem("Jumlah", "${PriceFormatter.formatRawDecimal(quantity)} $baseAsset")
                                InfoItem("Total", PriceFormatter.formatPrice(totalIdr, quoteAsset = quote))
                            }

                            HorizontalDivider(color = TvBorder.copy(alpha = 0.5f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                InfoItem(
                                    "Durasi Hold",
                                    TradeLogExporter.formatDuration(holdingDurationMs),
                                    highlightColor = TvBlue
                                )
                                if (entryPrice != null && entryPrice > 0.0) {
                                    InfoItem("Entry Buy", PriceFormatter.formatPrice(entryPrice, quoteAsset = quote))
                                }
                                if (pnlIdr != null) {
                                    val isProfit = pnlIdr >= 0
                                    val pColor = if (isProfit) TvGreen else TvRed
                                    val prefix = if (isProfit) "+" else ""
                                    InfoItem(
                                        "Realized PnL",
                                        "$prefix${PriceFormatter.formatPrice(pnlIdr, quoteAsset = quote)} ($prefix${String.format(Locale.US, "%.2f", pnlPercent ?: 0.0)}%)",
                                        highlightColor = pColor
                                    )
                                }
                            }

                            if (isTrailingUsed) {
                                HorizontalDivider(color = TvBorder.copy(alpha = 0.5f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    InfoItem("Trailing Lock", "AKTIF (${trailingPercent ?: 0.0}%)", highlightColor = TvGreen)
                                    trailingPeakPrice?.let {
                                        InfoItem("Peak Price", PriceFormatter.formatPrice(it, quoteAsset = quote))
                                    }
                                    trailingLockPrice?.let {
                                        InfoItem("Lock Stop Price", PriceFormatter.formatPrice(it, quoteAsset = quote))
                                    }
                                }
                            }
                        }
                    }

                    // Section 2: Technical Indicators Breakdown (Categorized)
                    if (snapshot != null) {
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "2. METRIK SINYAL TEKNIKAL LENGKAP",
                                    color = TvAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // Cat A: Order Book & Liquidity
                                CategoryBox(
                                    title = "A. Order Book & Likuiditas",
                                    items = listOf(
                                        "Bid Ratio" to (snapshot.bidRatioPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"),
                                        "Ask Ratio" to (snapshot.askRatioPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"),
                                        "Pressure" to (snapshot.orderBookPressure?.let { if (it > 0) "+$it% (Buyer)" else "$it% (Seller)" } ?: "Netral"),
                                        "Spread" to (snapshot.spreadPct?.let { String.format(Locale.US, "%.3f%%", it) } ?: "-"),
                                        "24h Vol" to (snapshot.volume24h?.let { PriceFormatter.formatRawDecimal(it) } ?: "-")
                                    )
                                )

                                // Cat B: Momentum & Oscillators
                                CategoryBox(
                                    title = "B. Momentum & Osilator",
                                    items = listOf(
                                        "RSI (14)" to (snapshot.rsi14?.let { String.format(Locale.US, "%.2f", it) } ?: "-"),
                                        "MACD Line" to (snapshot.macd?.let { String.format(Locale.US, "%.4f", it) } ?: "-"),
                                        "MACD Signal" to (snapshot.macdSignal?.let { String.format(Locale.US, "%.4f", it) } ?: "-"),
                                        "MACD Hist" to (snapshot.macdHist?.let { String.format(Locale.US, "%.4f", it) } ?: "-"),
                                        "Momentum" to (snapshot.momentum?.let { String.format(Locale.US, "%.2f", it) } ?: "-")
                                    )
                                )

                                // Cat C: Trend & Moving Averages
                                CategoryBox(
                                    title = "C. Tren & Moving Averages (EMA)",
                                    items = listOf(
                                        "EMA 20" to (snapshot.ema20?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "EMA 50" to (snapshot.ema50?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "EMA 200" to (snapshot.ema200?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "Trend" to (snapshot.trendStatus ?: "Konsolidasi")
                                    )
                                )

                                // Cat D: Volatility & Bollinger Bands
                                CategoryBox(
                                    title = "D. Volatilitas & Bollinger Bands",
                                    items = listOf(
                                        "BB Upper" to (snapshot.bbUpper?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "BB Middle" to (snapshot.bbMiddle?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "BB Lower" to (snapshot.bbLower?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "Bandwidth" to (snapshot.bbWidthPct?.let { String.format(Locale.US, "%.2f%%", it) } ?: "-"),
                                        "ATR" to (snapshot.atr?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-")
                                    )
                                )

                                // Cat E: AI Decision Reasoning
                                CategoryBox(
                                    title = "E. AI Evaluator Decision & Key Factors",
                                    items = listOf(
                                        "Score" to (snapshot.confidenceScore?.let { "$it / 100" } ?: "-"),
                                        "Regime" to (snapshot.marketRegime ?: "-"),
                                        "Pola" to (snapshot.patternDetected ?: "-")
                                    )
                                )

                                if (snapshot.reasons.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(TvCardBackground, RoundedCornerShape(6.dp))
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = "Alasan Trigger Sinyal:",
                                            color = TvTextSecondary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        snapshot.reasons.forEach { reason ->
                                            Text(
                                                text = "• $reason",
                                                color = TvTextPrimary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Snapshot sinyal teknikal dicatat otomatis saat order dieksekusi melalui strategi trading.",
                                color = TvTextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Bottom Action Buttons: Copy for LLM & Share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            TradeLogExporter.copyTradeMarkdownToClipboard(
                                context = context,
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
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TvBlue)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Salin Log (AI Audit)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            TradeLogExporter.shareTradeLog(
                                context = context,
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
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp), tint = TvTextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    highlightColor: Color? = null
) {
    Column {
        Text(text = label, color = TvTextSecondary, fontSize = 10.sp)
        Text(
            text = value,
            color = highlightColor ?: TvTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CategoryBox(
    title: String,
    items: List<Pair<String, String>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvCardBackground, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Text(
            text = title,
            color = TvTextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { (k, v) ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = k, color = TvTextSecondary.copy(alpha = 0.8f), fontSize = 9.sp)
                    Text(
                        text = v,
                        color = TvTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
