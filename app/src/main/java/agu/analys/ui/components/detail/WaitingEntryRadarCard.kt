package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.TradingFeeConfig
import agu.analys.model.AISignalState
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.model.ScalpingPath
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import kotlinx.coroutines.delay

/**
 * Radar & Progres Menunggu Entry yang interaktif dan edukatif.
 * Menghilangkan kejenuhan user saat menunggu sinyal dengan:
 * 1. Visual Status Pulse & Radar Scan Indicator
 * 2. Estimasi Biaya Transaksi (Limit vs Instant) sesuai setting
 * 3. Progres Multi-Timeframe (1H Trend -> 15M Struktur -> 1M Trigger)
 * 4. Micro-tips trading spot & scalping disiplin yang berganti
 */
@Composable
fun WaitingEntryRadarCard(
    signal: AISignalState,
    scalping: Boolean,
    fees: TradingFeeConfig = TradingFeeConfig(),
    currentPrice: Double = 0.0,
    baseAsset: String = "BTC",
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    val buyReady = signal.action == SignalAction.BUY && signal.entryPrice > 0.0
    val stage = signal.scalpingStage
    val mtf = signal.mtf
    val completed = listOf(mtf.biasStatus, mtf.setupStatus, mtf.triggerStatus, mtf.entryPriceStatus).count { it.name == "OK" }
    val effectivePrice = if (currentPrice > 0.0) currentPrice else if (signal.entryPrice > 0.0) signal.entryPrice else 0.0

    // Jika sinyal BUY sudah siap, tampilkan banner konfirmasi hijau ringkas plus rincian biaya
    if (buyReady) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF0F3822), Color(0xFF132B1E))
                        ),
                        RoundedCornerShape(12.dp)
                    )
                    .border(1.dp, TvGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(TvGreen, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SETUP BUY SIAP DIEKSEKUSI (4/4 LENGKAP)",
                        color = TvGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Konfirmasi 4/4 lengkap: Bias 1H, Setup 15M, Trigger 1M, & Harga Pas. Silakan klik BUY di Indodax.",
                        color = TvTextPrimary,
                        fontSize = 11.sp
                    )
                }
            }

            // Tampilkan estimasi biaya transaksi berdasarkan setting
            RadarTransactionFeeSection(
                fees = fees,
                currentPrice = effectivePrice,
                baseAsset = baseAsset,
                quoteAsset = quoteAsset
            )
        }
        return
    }

    // Animasi Pulse Radar saat menunggu entry
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

    // Tips berganti untuk mencegah kebosanan
    val tips = remember {
        listOf(
            "Disiplin menunggu konfirmasi lebih menguntungkan daripada terburu-buru masuk di tengah candle.",
            "Indodax menerapkan taker/maker fee. Membeli di area pullback meminimalkan risiko terjebak puncak.",
            "Jangan pernah all-in. Bagi modal menjadi 3-4 peluru untuk mengamankan average harga terbaik.",
            "Kondisi sideways sering memicu false breakout. Tunggu volume spike di timeframe 15M/1M.",
            "Pasang stop loss segera setelah order beli tereksekusi di aplikasi Indodax."
        )
    }
    var currentTipIndex by remember { mutableIntStateOf(0) }
    var expandedDetails by remember { mutableStateOf(false) }

    val statusTitle = when {
        stage == ScalpingStage.WAIT_PULLBACK -> "MENUNGGU PULLBACK BERSIH"
        stage == ScalpingStage.WATCH -> "MEMBENTUK SETUP (WATCH)"
        completed >= 2 -> "SETUP TERKONFIRMASI · MENUNGGU TRIGGER"
        completed == 1 -> "BIAS 1H VALID · MENUNGGU STRUKTUR 15M"
        else -> "SCANNING LIVE LIQUIDITY & TREND"
    }

    val statusSubtitle = when {
        stage == ScalpingStage.WAIT_PULLBACK -> "Tren naik terbentuk. Sistem menunggu harga menguji support EMA sebelum sinyal BUY dikeluarkan."
        stage == ScalpingStage.WATCH -> "Struktur mulai searah. Menunggu konfirmasi volume dan candle penutupan."
        else -> "Memantau order book dan perubahan candle secara real-time tiap detik."
    }

    AnalysisCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("RADAR STATUS LIVE", Icons.Default.Timeline)
            
            // Badge Live Scanning
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
                Text("SCANNING REAL-TIME", color = Color(0xFF00E5FF), fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Banner Status
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B141F), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF1B2E42), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .scale(pulseScale)
                        .background(WarningAmber, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusTitle,
                    color = WarningAmber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusSubtitle,
                color = TvTextPrimary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(10.dp))

            // Step Progress Linear Indicator & Animated Checkpoint Stepper
            RadarLinearCheckpointStepper(
                mtf = mtf,
                completed = completed,
                pulseScale = pulseScale
            )

            Spacer(Modifier.height(8.dp))

            // Panduan Cepat Eksekusi Indodax
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131F2E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = if (completed >= 3) TvGreen else WarningAmber,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (completed == 4) "Kondisi 4/4 terpenuhi! Segera klik BUY di Indodax."
                    else if (completed >= 2) "Siapkan input harga di Indodax sekarang. Saat Step 4 terkonfirmasi HIJAU, langsung klik BUY."
                    else "Siapkan aplikasi Indodax web/HP sambil menunggu konfirmasi sinyal.",
                    color = if (completed >= 3) TvGreen else TvTextSecondary,
                    fontSize = 9.5.sp,
                    lineHeight = 13.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Estimasi Biaya Transaksi Live berdasarkan Setting Fee Pengguna
        RadarTransactionFeeSection(
            fees = fees,
            currentPrice = effectivePrice,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset
        )

        Spacer(Modifier.height(10.dp))

        // Interaktif Micro-Tip Card (User tidak jenuh saat nunggu)
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
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

data class RadarCheckpointItem(
    val number: Int,
    val tabLabel: String,
    val title: String,
    val isOk: Boolean,
    val detail: String
)

@Composable
private fun RadarLinearCheckpointStepper(
    mtf: ScalpingMtfSnapshot,
    completed: Int,
    pulseScale: Float,
    modifier: Modifier = Modifier
) {
    val checkpoints = remember(mtf) {
        listOf(
            RadarCheckpointItem(
                number = 1,
                tabLabel = "1. Bias 1H",
                title = "1. Bias 1H · Tren Utama",
                isOk = mtf.biasStatus.name == "OK" || mtf.biasOk,
                detail = if (mtf.biasStatus.name == "OK" || mtf.biasOk) {
                    mtf.biasDetail.ifBlank { "Tren 1 Jam Bullish Kuat (EMA 20/50/200 selaras naik)." }
                } else {
                    mtf.biasDetail.ifBlank { "Memantau keselarasan tren pada timeframe 1 Jam..." }
                }
            ),
            RadarCheckpointItem(
                number = 2,
                tabLabel = "2. Setup 15M",
                title = "2. Setup 15M · Struktur Pasar",
                isOk = mtf.setupStatus.name == "OK" || mtf.setupOk,
                detail = if (mtf.setupStatus.name == "OK" || mtf.setupOk) {
                    mtf.setupDetail.ifBlank { "Struktur 15M valid (Pullback ke support EMA / Golden Cross)." }
                } else {
                    mtf.setupDetail.ifBlank { "Menunggu pembentukan konsolidasi atau pantulan support 15M..." }
                }
            ),
            RadarCheckpointItem(
                number = 3,
                tabLabel = "3. Trigger 1M",
                title = "3. Trigger 1M · Momentum Sinyal",
                isOk = mtf.triggerStatus.name == "OK" || mtf.triggerOk,
                detail = if (mtf.triggerStatus.name == "OK" || mtf.triggerOk) {
                    mtf.triggerDetail.ifBlank { "Breakout volume 1M & momentum RSI/MACD terkonfirmasi aktif." }
                } else {
                    mtf.triggerDetail.ifBlank { "Menunggu trigger lonjakan volume beli dan stochastic/MACD 1M..." }
                }
            ),
            RadarCheckpointItem(
                number = 4,
                tabLabel = "4. Area Entry",
                title = "4. Area Entry · Konfirmasi Harga",
                isOk = mtf.entryPriceStatus.name == "OK" || mtf.entryPriceOk,
                detail = if (mtf.entryPriceStatus.name == "OK" || mtf.entryPriceOk) {
                    mtf.entryPriceDetail.ifBlank { "Harga saat ini berada di zona ideal beli dengan risk/reward optimal." }
                } else {
                    mtf.entryPriceDetail.ifBlank { "Menunggu harga bergerak masuk ke dalam toleransi zona beli ideal..." }
                }
            )
        )
    }

    // Checkpoint aktif saat ini (checkpoint pertama yang belum OK, atau ke-4 jika sudah semua)
    val activeCheckpointIndex = remember(checkpoints) {
        val idx = checkpoints.indexOfFirst { !it.isOk }
        if (idx >= 0) idx else 3
    }

    // 1 Linear Progress Bar Global (masing-masing checkpoint = 25%)
    val targetProgress = (completed.coerceIn(0, 4) / 4f)
    val animGlobalProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "global_linear_progress"
    )

    val progressPercent = (completed.coerceIn(0, 4) * 25)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Baris Header Progres Global (0% -> 25% -> 50% -> 75% -> 100%)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Progres Konfirmasi",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "($completed/4 Checkpoint)",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "$progressPercent%",
                color = if (completed == 4) TvGreen else Color(0xFF00E5FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // 1 Single Global Linear Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF142232))
        ) {
            // Fill Bar with smooth gradient animation
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animGlobalProgress)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (completed == 4) Brush.horizontalGradient(listOf(Color(0xFF00C853), TvGreen))
                        else Brush.horizontalGradient(listOf(Color(0xFF0288D1), Color(0xFF00E5FF)))
                    )
            )

            // Divider markers for 25%, 50%, 75%
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF0A121C).copy(alpha = 0.7f))
                    )
                }
            }
        }

        // Animated Card: transisi otomatis saat checkpoint terpenuhi & berpindah ke step selanjutnya
        AnimatedContent(
            targetState = activeCheckpointIndex,
            transitionSpec = {
                (slideInVertically(animationSpec = tween(350, easing = FastOutSlowInEasing)) { height -> height / 3 } + fadeIn(animationSpec = tween(300)))
                    .togetherWith(slideOutVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) { height -> -height / 3 } + fadeOut(animationSpec = tween(250)))
            },
            label = "checkpoint_detail_transition"
        ) { targetIdx ->
            val currentItem = checkpoints[targetIdx]
            val isCurrentScanning = !currentItem.isOk

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0E1A27))
                    .border(
                        1.dp,
                        when {
                            currentItem.isOk -> TvGreen.copy(alpha = 0.4f)
                            isCurrentScanning -> Color(0xFF00E5FF).copy(alpha = 0.4f)
                            else -> Color(0xFF1E334A)
                        },
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when {
                                            currentItem.isOk -> TvGreen.copy(alpha = 0.15f)
                                            isCurrentScanning -> Color(0xFF00E5FF).copy(alpha = 0.15f)
                                            else -> Color(0xFF1A2B3D)
                                        }
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "CHECKPOINT ${currentItem.number}/4",
                                    color = when {
                                        currentItem.isOk -> TvGreen
                                        isCurrentScanning -> Color(0xFF00E5FF)
                                        else -> Color(0xFF94A3B8)
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = currentItem.title.substringAfter("· "),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Status Tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        currentItem.isOk -> Color(0xFF0F3A22)
                                        isCurrentScanning -> Color(0xFF0D324D)
                                        else -> Color(0xFF1A2634)
                                    }
                                )
                                .border(
                                    0.5.dp,
                                    when {
                                        currentItem.isOk -> TvGreen.copy(alpha = 0.6f)
                                        isCurrentScanning -> Color(0xFF00E5FF).copy(alpha = 0.6f)
                                        else -> Color(0xFF2C3E52)
                                    },
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when {
                                    currentItem.isOk -> "✓ TERPENUHI"
                                    isCurrentScanning -> "⚡ SEDANG DIPANTAU"
                                    else -> "⏳ MENUNGGU"
                                },
                                color = when {
                                    currentItem.isOk -> TvGreen
                                    isCurrentScanning -> Color(0xFF00E5FF)
                                    else -> Color(0xFF94A3B8)
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(5.dp))

                    Text(
                        text = currentItem.detail,
                        color = if (currentItem.isOk) Color(0xFFE2E8F0) else TvTextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

