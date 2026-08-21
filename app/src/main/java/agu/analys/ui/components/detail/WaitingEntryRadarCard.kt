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
import agu.analys.config.StrategyMode
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
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    scalping: Boolean = strategyMode == StrategyMode.SCALPING,
    fees: TradingFeeConfig = TradingFeeConfig(),
    currentPrice: Double = 0.0,
    baseAsset: String = "BTC",
    quoteAsset: String = "IDR",
    availableIdr: Double = 0.0,
    availableCoin: Double = 0.0,
    avgBuyPrice: Double = 0.0,
    isRealBuyMode: Boolean = false,
    onExecuteBuy: ((Double) -> Unit)? = null,
    onExecuteSell: ((Double) -> Unit)? = null,
    onSetManualBuyPrice: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val mtf = signal.mtf
    val completed = listOf(mtf.biasStatus, mtf.setupStatus, mtf.triggerStatus, mtf.entryPriceStatus)
        .count { it.name == "OK" || it.name == "CONFIRMED" }
    
    val effectivePrice = if (currentPrice > 0.0) currentPrice else if (signal.entryPrice > 0.0) signal.entryPrice else 0.0

    // Hoisted states for live transaction details
    var selectedNominal by remember { mutableDoubleStateOf(50000.0) }
    var selectedSellQty by remember { mutableDoubleStateOf(availableCoin) }
    var isMakerOrder by remember { mutableStateOf(true) }
    var isBuyMode by remember { mutableStateOf(true) }

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

    // Deteksi Tipe Entry Badge (Reclaim vs Base-Dip vs Swing)
    val isReclaim = mtf.path == ScalpingPath.MOMENTUM_CONTINUATION ||
                    mtf.triggerDetail.contains("Reclaim", ignoreCase = true) ||
                    signal.reasoning.any { it.contains("Reclaim", ignoreCase = true) || it.contains("Breakout", ignoreCase = true) }
    
    val entryTypeBadgeTitle = when (strategyMode) {
        StrategyMode.SWING -> if (isReclaim) "🚀 SWING BREAKOUT / RECLAIM" else "🛡️ SWING PULLBACK / DIP ENTRY"
        StrategyMode.SECOND_WAVE -> if (isReclaim) "🚀 RECLAIM ENTRY" else "🛡️ BASE-DIP ENTRY"
        StrategyMode.SCALPING -> if (isReclaim) "🚀 RECLAIM BREAKOUT" else "🛡️ PULLBACK DIP ENTRY"
    }

    val entryTypeBadgeDesc = when (strategyMode) {
        StrategyMode.SWING -> if (isReclaim) {
            "Struktur Swing Bullish terkonfirmasi. Menembus resistance dengan inflow momentum multi-timeframe."
        } else {
            "Harga menguji demand zone / support EMA swing. Risiko rendah dengan Stop Loss terukur."
        }
        StrategyMode.SECOND_WAVE -> if (isReclaim) {
            "Reclaim terkonfirmasi! Volume beli meledak menembus Resistance. Momentum Second-Wave aktif."
        } else {
            "Harga menyentuh lantai akumulasi support. Risiko sangat rendah (SL ketat)."
        }
        StrategyMode.SCALPING -> if (isReclaim) {
            "Reclaim momentum 1M terkonfirmasi aktif! Volume beli meledak menembus level 15M."
        } else {
            "Pullback ke support EMA 15M/1M terkonfirmasi. Risiko terkendali."
        }
    }

    val titleHeader = when (strategyMode) {
        StrategyMode.SCALPING -> "⚡ SCALPING (${signal.confidence}%)"
        StrategyMode.SECOND_WAVE -> "🌊 SECOND-WAVE (${signal.confidence}%)"
        StrategyMode.SWING -> "🎯 SWING (${signal.confidence}%)"
    }

    // Micro Tips
    val tips = remember(strategyMode) {
        when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "Swing trading mengutamakan tren makro (1H/4H/1D) dengan target profit lebih lebar.",
                "Pasang Stop Loss di bawah Swing Low atau support EMA untuk membatasi risiko kerugian.",
                "Biarkan posisi berjalan menuju TP1/TP2 selama struktur higher-low tetap bertahan.",
                "Gunakan trailing stop saat harga sudah menembus TP1 untuk mengunci profit."
            )
            StrategyMode.SECOND_WAVE -> listOf(
                "Second Wave mengincar momentum lanjutan setelah koreksi sehat pertama selesai.",
                "Konfirmasi inflow volume di timeframe 15M sebelum masuk saat reclaim terjadi.",
                "Jangan all-in. Bagi modal menjadi 2-3 peluru untuk mengamankan average harga terbaik.",
                "Disiplin menunggu konfirmasi 4/4 lebih menguntungkan daripada FOMO di tengah jalan."
            )
            StrategyMode.SCALPING -> listOf(
                "Disiplin menunggu konfirmasi 4/4 lebih menguntungkan daripada FOMO di tengah candle.",
                "Indodax menerapkan taker/maker fee. Membeli di area pullback meminimalkan risiko terjebak puncak.",
                "Jangan all-in. Gunakan eksekusi cepat di timeframe 1M dengan target profit realistis.",
                "Kondisi sideways sering memicu false breakout. Tunggu volume spike di timeframe 1M."
            )
        }
    }
    var currentTipIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(strategyMode) {
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
                titleHeader,
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
                    text = if (completed == 4) "Eksekusi" else "Wait!",
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

            val (step1Text, step2Text, step3Text, step4Text) = when (strategyMode) {
                StrategyMode.SWING -> listOf(
                    "1. Tren Makro & Alignment EMA (1D/4H/1H)",
                    "2. Struktur Market & Support Lantai (Higher Low)",
                    "3. Momentum & Volume Inflow (RSI/MACD)",
                    "4. Risk/Reward Optimal & Toleransi Entry"
                )
                StrategyMode.SECOND_WAVE -> listOf(
                    "1. Prior Run & Drawdown Reset (Valid 4H/1H)",
                    "2. Accumulation Base & Drawdown Dry (Valid 1H)",
                    "3. Smart Inflow & Higher Low Terbentuk (15M)",
                    "4. Trigger Reclaim Resistance & Zona Entry Ideal"
                )
                StrategyMode.SCALPING -> listOf(
                    "1. Trend & Bias 1H Valid (Bullish Alignment)",
                    "2. Base Compression & Volume Kering (Valid 15M)",
                    "3. Smart Inflow & Higher Low Terbentuk (1M)",
                    "4. Trigger Reclaim Resistance 15M (Volume Masuk!)"
                )
            }

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
            pulseScale = pulseScale,
            strategyMode = strategyMode
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

            val refPrice = if (signal.entryPrice > 0.0) signal.entryPrice else effectivePrice
            val targetPrice1 = if (signal.targetPrice1 > 0.0) signal.targetPrice1 else if (refPrice > 0.0) refPrice * 1.08 else 0.0
            val targetPrice2 = if (signal.targetPrice2 > 0.0) signal.targetPrice2 else if (refPrice > 0.0) refPrice * 1.18 else 0.0
            val stopLoss = if (signal.stopLoss > 0.0) signal.stopLoss else if (refPrice > 0.0) refPrice * 0.95 else 0.0

            val tp1Gain = if (refPrice > 0.0 && targetPrice1 > 0.0) ((targetPrice1 - refPrice) / refPrice) * 100 else 0.0
            val tp2Gain = if (refPrice > 0.0 && targetPrice2 > 0.0) ((targetPrice2 - refPrice) / refPrice) * 100 else 0.0
            val slLoss = if (refPrice > 0.0 && stopLoss > 0.0) ((stopLoss - refPrice) / refPrice) * 100 else 0.0

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("• Entry Area", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    PriceFormatter.formatPrice(refPrice, quoteAsset = quoteAsset),
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

        Spacer(Modifier.height(10.dp))

        // Estimasi Biaya Transaksi & Kalkulasi Net Profit / Loss Jual
        RadarTransactionFeeSection(
            fees = fees,
            currentPrice = effectivePrice,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            availableIdr = availableIdr,
            availableCoin = availableCoin,
            avgBuyPrice = avgBuyPrice,
            selectedNominalIdr = selectedNominal,
            onNominalIdrChanged = { selectedNominal = it },
            selectedSellQuantity = selectedSellQty,
            onSellQuantityChanged = { selectedSellQty = it },
            isMakerOrder = isMakerOrder,
            onOrderTypeChanged = { isMakerOrder = it },
            isBuyMode = isBuyMode,
            onBuyModeChanged = { isBuyMode = it },
            isRealMode = isRealBuyMode,
            onExecuteBuy = onExecuteBuy,
            onExecuteSell = onExecuteSell,
            onSetManualBuyPrice = onSetManualBuyPrice
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
