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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
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
                        text = "Konfirmasi 4/4 lengkap: Bias 1H, Setup 15M, Trigger 1M, & Harga Pas. Silakan klik BUY.",
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

    LaunchedEffect(Unit) {
        while (true) {
            delay(7000L)
            currentTipIndex = (currentTipIndex + 1) % tips.size
        }
    }

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
                Text("SCANNING..!!!", color = Color(0xFF00E5FF), fontSize = 9.sp, fontWeight = FontWeight.Black)
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
                    text = if (completed == 4) "Kondisi 4/4 terpenuhi! Segera klik BUY."
                    else if (completed >= 2) "Siapkan input harga sekarang. Saat Step 4 terkonfirmasi HIJAU, langsung klik BUY."
                    else "Siapkan aplikasi web/HP sambil menunggu konfirmasi sinyal.",
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

