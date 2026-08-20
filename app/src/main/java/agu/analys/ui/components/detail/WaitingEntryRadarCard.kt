package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.TradingFeeConfig
import agu.analys.model.AISignalState
import agu.analys.model.ScalpingPath
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.delay

/**
 * Radar & Progres Entry Interaktif dengan 4 Konfirmasi Bertahap (Progress Bar 1/4 -> 4/4)
 */
@Composable
fun WaitingEntryRadarCard(
    signal: AISignalState,
    scalping: Boolean,
    fees: TradingFeeConfig = TradingFeeConfig(),
    currentPrice: Double = 0.0,
    baseAsset: String = "BTC",
    quoteAsset: String = "IDR",
    isRealBuyMode: Boolean = false,
    onExecuteBuy: (() -> Unit)? = null,
    onExecuteSell: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val mtf = signal.mtf
    val completed = listOf(mtf.biasStatus, mtf.setupStatus, mtf.triggerStatus, mtf.entryPriceStatus)
        .count { it.name == "OK" }
    
    val effectivePrice = if (currentPrice > 0.0) currentPrice else if (signal.entryPrice > 0.0) signal.entryPrice else 0.0

    // Animasi Pulse Radar
    val transition = rememberInfiniteTransition(label = "waiting_radar_pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radar_scale"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radar_glow"
    )

    // Deteksi Tipe Entry Badge (Reclaim vs Base-Dip)
    val isReclaim = mtf.path == ScalpingPath.MOMENTUM_CONTINUATION ||
                    mtf.triggerDetail.contains("Reclaim", ignoreCase = true) ||
                    signal.reasoning.any { it.contains("Reclaim", ignoreCase = true) }
    
    val entryTypeBadgeTitle = if (isReclaim) "🚀 RECLAIM ENTRY" else "🛡️ BASE-DIP ENTRY"
    val entryTypeBadgeDesc = if (isReclaim) {
        "Reclaim terkonfirmasi! Volume beli meledak menembus Resistance. Momentum Scalping / Second-Wave aktif."
    } else {
        "Harga menyentuh lantai akumulasi support. Risiko sangat rendah (SL ketat)."
    }

    // Micro Tips
    val tips = remember {
        listOf(
            "Disiplin menunggu konfirmasi 4/4 lebih menguntungkan daripada FOMO di tengah candle.",
            "Indodax menerapkan taker/maker fee. Membeli di area pullback meminimalkan risiko terjebak puncak.",
            "Jangan all-in. Bagi modal menjadi 3-4 peluru untuk mengamankan average harga terbaik.",
            "Kondisi sideways sering memicu false breakout. Tunggu volume spike di timeframe 15M/1M."
        )
    }
    var currentTipIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(7000L)
            currentTipIndex = (currentTipIndex + 1) % tips.size
        }
    }

    AnalysisCard {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle(
                if (scalping) "⚡ RADAR SCALPING HUNTER" else "🌊 RADAR SECOND-WAVE HUNTER",
                Icons.Default.Timeline
            )

            // Scanning Live Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xFF142436), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF26527C), RoundedCornerShape(20.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .scale(pulseScale)
                        .background(Color(0xFF00E5FF).copy(alpha = glowAlpha), CircleShape)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (completed == 4) "SIAP EKSEKUSI!" else "SCANNING..!!!",
                    color = if (completed == 4) TvGreen else Color(0xFF00E5FF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Badge Tipe Setup Terdeteksi
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isReclaim) Color(0xFF0F3040) else Color(0xFF0F3822),
                    RoundedCornerShape(8.dp)
                )
                .border(
                    1.dp,
                    if (isReclaim) Color(0xFF00E5FF).copy(alpha = 0.6f) else TvGreen.copy(alpha = 0.6f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Setup Terdeteksi: $entryTypeBadgeTitle (Konfirmasi $completed/4)",
                    color = if (isReclaim) Color(0xFF00E5FF) else TvGreen,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = entryTypeBadgeDesc,
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 13.5.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 4 Checklist Konfirmasi
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B141F), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF1B2E42), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val isStep1Ok = mtf.biasStatus.name == "OK" || mtf.biasOk
            val isStep2Ok = mtf.setupStatus.name == "OK" || mtf.setupOk
            val isStep3Ok = mtf.triggerStatus.name == "OK" || mtf.triggerOk
            val isStep4Ok = mtf.entryPriceStatus.name == "OK" || mtf.entryPriceOk

            val step1Text = if (scalping) "1. Trend & Bias 1H Valid (Bullish Alignment)" else "1. Prior Run & Drawdown Reset (Valid 4H/1H)"
            val step2Text = if (scalping) "2. Base Compression & Volume Kering (Valid 15M)" else "2. Accumulation Base & Drawdown Dry (Valid 1H)"
            val step3Text = if (scalping) "3. Smart Inflow & Higher Low Terbentuk (1M)" else "3. Smart Inflow & Higher Low Terbentuk (15M)"
            val step4Text = if (scalping) "4. Trigger Reclaim Resistance 15M (Volume Masuk!)" else "4. Trigger Reclaim Resistance & Zona Entry Ideal"

            RadarChecklistItem(1, step1Text, isStep1Ok, mtf.biasDetail)
            RadarChecklistItem(2, step2Text, isStep2Ok, mtf.setupDetail)
            RadarChecklistItem(3, step3Text, isStep3Ok, mtf.triggerDetail)
            RadarChecklistItem(4, step4Text, isStep4Ok, mtf.entryPriceDetail)
        }

        Spacer(Modifier.height(10.dp))

        // Global Progress Bar
        RadarLinearCheckpointStepper(
            mtf = mtf,
            completed = completed,
            pulseScale = pulseScale
        )

        Spacer(Modifier.height(10.dp))

        // Target Levels Box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101B2B), RoundedCornerShape(10.dp))
                .border(0.5.dp, Color(0xFF213852), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Text(
                text = if (completed == 4) "🔥 STATUS: SIAP EKSEKUSI SEKARANG!" else "⚡ LEVEL PLAN ENTRY & TARGET:",
                color = if (completed == 4) TvGreen else WarningAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(6.dp))

            val targetPrice1 = signal.targetPrice1
            val targetPrice2 = signal.targetPrice2
            val stopLoss = signal.stopLoss

            val tp1Gain = if (signal.entryPrice > 0) ((targetPrice1 - signal.entryPrice) / signal.entryPrice) * 100 else 0.0
            val tp2Gain = if (signal.entryPrice > 0) ((targetPrice2 - signal.entryPrice) / signal.entryPrice) * 100 else 0.0
            val slLoss = if (signal.entryPrice > 0) ((stopLoss - signal.entryPrice) / signal.entryPrice) * 100 else 0.0

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("• Entry Area", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    PriceFormatter.formatPrice(if (signal.entryPrice > 0) signal.entryPrice else effectivePrice, quoteAsset = quoteAsset),
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("• Target TP1", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    "${PriceFormatter.formatPrice(targetPrice1, quoteAsset = quoteAsset)} (${PriceFormatter.formatPercentage(tp1Gain, true)})",
                    color = TvGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("• Target TP2", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    "${PriceFormatter.formatPrice(targetPrice2, quoteAsset = quoteAsset)} (${PriceFormatter.formatPercentage(tp2Gain, true)})",
                    color = TvGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("• Cut Loss (SL)", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    "${PriceFormatter.formatPrice(stopLoss, quoteAsset = quoteAsset)} (${PriceFormatter.formatPercentage(slLoss, true)})",
                    color = TvRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Interactive Direct Trade Execution Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // BUY Button
            Button(
                onClick = { onExecuteBuy?.invoke() },
                enabled = onExecuteBuy != null,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TvGreen,
                    contentColor = Color.Black,
                    disabledContainerColor = TvGreen.copy(alpha = 0.3f)
                )
            ) {
                Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isRealBuyMode) "⚡ REAL BUY NOW" else "⚡ SIMULASI BUY",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black
                )
            }

            // SELL Button (If action callback provided)
            if (onExecuteSell != null) {
                Button(
                    onClick = { onExecuteSell() },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TvRed,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = if (isRealBuyMode) "🔴 REAL SELL" else "🔴 SIMULASI SELL",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Estimasi Biaya Transaksi
        RadarTransactionFeeSection(
            fees = fees,
            currentPrice = effectivePrice,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset
        )

        Spacer(Modifier.height(10.dp))

        // Interaktif Micro-Tip Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101C2B), RoundedCornerShape(10.dp))
                .border(0.5.dp, Color(0xFF233B54), RoundedCornerShape(10.dp))
                .clickable {
                    currentTipIndex = (currentTipIndex + 1) % tips.size
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = "Tips",
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(16.dp).padding(top = 1.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TIPS SAMBIL MENUNGGU ENTRY",
                            color = Color(0xFFFFD54F),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Ganti Tip",
                            tint = TvTextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = tips[currentTipIndex],
                        color = TvTextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarChecklistItem(
    stepNumber: Int,
    label: String,
    isOk: Boolean,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(
                    if (isOk) TvGreen.copy(alpha = 0.2f) else Color(0xFF1A2A3A),
                    CircleShape
                )
                .border(
                    1.dp,
                    if (isOk) TvGreen else Color(0xFF2C3E52),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isOk) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "OK",
                    tint = TvGreen,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.HourglassEmpty,
                    contentDescription = "Pending",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(10.dp)
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (isOk) TvGreen else TvTextPrimary,
                fontSize = 11.sp,
                fontWeight = if (isOk) FontWeight.Bold else FontWeight.Medium
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    color = TvTextSecondary,
                    fontSize = 9.5.sp,
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .background(
                    if (isOk) TvGreen.copy(alpha = 0.15f) else Color(0xFF132232),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (isOk) "[✓] OK" else "[⚡ SCAN]",
                color = if (isOk) TvGreen else Color(0xFF00E5FF),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
