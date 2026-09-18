package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.StrategyMode
import agu.analys.model.OrderBookItem
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

data class RadarCheckpointItem(
    val number: Int,
    val tabLabel: String,
    val title: String,
    val isOk: Boolean,
    val detail: String
)

@Composable
fun RadarLinearCheckpointStepper(
    mtf: ScalpingMtfSnapshot,
    completed: Int,
    pulseScale: Float = 1f,
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    confidence: Int = 0,
    orderBookBids: List<OrderBookItem> = emptyList(),
    orderBookAsks: List<OrderBookItem> = emptyList(),
    modifier: Modifier = Modifier
) {
    val isStep1Ok = mtf.biasStatus.name == "OK" || mtf.biasOk
    val isStep2Ok = mtf.setupStatus.name == "OK" || mtf.setupOk
    val isStep3Ok = mtf.triggerStatus.name == "OK" || mtf.triggerOk
    val isStep4Ok = mtf.entryPriceStatus.name == "OK" || mtf.entryPriceOk

    val checkpoints = remember(mtf, strategyMode) {
        val (tab1, title1, def1Ok, def1Wait) = when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "Tren Makro",
                "Tren Makro · Keselarasan EMA (1D/4H/1H)",
                "Tren Makro Bullish Kuat (Harga bergerak di atas EMA 20/50).",
                "Memantau keselarasan tren dan keselarasan EMA makro..."
            )
            StrategyMode.OFFICE_DAILY -> listOf(
                "Tren 1H/4H",
                "Tren 1H/4H · Tren Santai",
                "Tren 1 Jam & 4 Jam Bullish Stabil (Di atas EMA 20/50).",
                "Memantau kestabilan tren 1 Jam / 4 Jam..."
            )
            StrategyMode.TRENCHING -> listOf(
                "Flow M15",
                "Flow M15 · Persistensi Tekanan",
                "Flow konsisten positif dengan volume akumulasi & persistensi aktif.",
                "Memantau persistensi flow volume dan order book inflow..."
            )
            StrategyMode.SECOND_WAVE -> listOf(
                "Prior Run",
                "Prior Run · Drawdown Reset",
                "Prior run terkonfirmasi dan koreksi drawdown reset normal.",
                "Memantau prior run dan siklus reset drawdown 4H/1H..."
            )
            StrategyMode.SCALPING -> listOf(
                "Bias 1H",
                "Bias 1H · Tren Utama",
                "Tren 1 Jam Bullish Kuat (EMA 20/50/200 selaras naik).",
                "Memantau keselarasan tren pada timeframe 1 Jam..."
            )
        }

        val (tab2, title2, def2Ok, def2Wait) = when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "Struktur",
                "Struktur · Support Lantai",
                "Struktur market higher-low & support lantai swing bertahan.",
                "Menunggu pembentukan konsolidasi atau pantulan support swing..."
            )
            StrategyMode.OFFICE_DAILY -> listOf(
                "Base Lantai",
                "Base Lantai · Akumulasi Sehat",
                "Base lantai harga terbentuk rapi tanpa dump liar.",
                "Menunggu konsolidasi base support terbentuk..."
            )
            StrategyMode.TRENCHING -> listOf(
                "Trench Base",
                "Kompresi Trench · Range Ketat",
                "Harga terkompresi rapi di area support trench tanpa volatilitas liar.",
                "Menunggu pembentukan batas kompresi trench yang stabil..."
            )
            StrategyMode.SECOND_WAVE -> listOf(
                "Base Support",
                "Base Support · Akumulasi 1H",
                "Lantai base support terbentuk dan volume koreksi kering.",
                "Menunggu konfirmasi pembentukan base support 1H..."
            )
            StrategyMode.SCALPING -> listOf(
                "Setup 15M",
                "Setup 15M · Struktur Pasar",
                "Struktur 15M valid (Pullback ke support EMA / Golden Cross).",
                "Menunggu pembentukan konsolidasi atau pantulan support 15M..."
            )
        }

        val (tab3, title3, def3Ok, def3Wait) = when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "Momentum",
                "Momentum · RSI & MACD Inflow",
                "Momentum RSI & histogram MACD mendukung arah swing.",
                "Menunggu trigger momentum RSI dan konfirmasi volume swing..."
            )
            StrategyMode.OFFICE_DAILY -> listOf(
                "RSI & Inflow",
                "Inflow · Smart Accumulation",
                "RSI berada di zona aman & volume akumulasi masuk.",
                "Menunggu konfirmasi momentum RSI & akumulasi santai..."
            )
            StrategyMode.TRENCHING -> listOf(
                "Flow Return",
                "Timing · Pullback & Flow Return",
                "Pullback sehat terlewati & flow beralih menguat kembali (Reclaim).",
                "Menunggu timing flow return setelah pullback/absorption..."
            )
            StrategyMode.SECOND_WAVE -> listOf(
                "Inflow 15M",
                "Inflow 15M · Smart Money",
                "Volume beli 15M masuk dan candle konfirmasi terbentuk.",
                "Menunggu smart inflow dan higher-low 15M..."
            )
            StrategyMode.SCALPING -> listOf(
                "Trigger 1M",
                "Trigger 1M · Momentum Sinyal",
                "Breakout volume 1M & momentum RSI/MACD terkonfirmasi aktif.",
                "Menunggu trigger lonjakan volume beli dan stochastic/MACD 1M..."
            )
        }

        val (tab4, title4, def4Ok, def4Wait) = when (strategyMode) {
            StrategyMode.SWING -> listOf(
                "Risk:Reward",
                "Area Entry · Net R:R >= 1:1.5",
                "Harga berada di zona entry dengan Net R:R optimal.",
                "Menunggu harga bergerak masuk ke toleransi zona beli swing..."
            )
            StrategyMode.OFFICE_DAILY -> listOf(
                "Area Entry",
                "Area Entry · TP Santai & Terukur",
                "Harga berada di zona beli aman dengan target TP terukur.",
                "Menunggu harga berada di zona entry yang aman..."
            )
            StrategyMode.TRENCHING -> listOf(
                "Anti-FOMO",
                "Anti-FOMO Guard & Sizing",
                "Anti-FOMO Guard lulus, harga tidak extended, alokasi risiko terhitung aman.",
                "Menunggu validasi Anti-FOMO Guard & toleransi resiko..."
            )
            StrategyMode.SECOND_WAVE -> listOf(
                "Entry Ready",
                "Area Entry · Reclaim / Dip",
                "Harga berada di zona ideal beli dengan risk/reward optimal.",
                "Menunggu harga bergerak masuk ke dalam toleransi zona beli ideal..."
            )
            StrategyMode.SCALPING -> listOf(
                "Area Entry",
                "Area Entry · Konfirmasi Harga",
                "Harga saat ini berada di zona ideal beli dengan risk/reward optimal.",
                "Menunggu harga bergerak masuk ke dalam toleransi zona beli ideal..."
            )
        }

        listOf(
            RadarCheckpointItem(
                number = 1,
                tabLabel = tab1,
                title = title1,
                isOk = isStep1Ok,
                detail = if (isStep1Ok) mtf.biasDetail.ifBlank { def1Ok } else mtf.biasDetail.ifBlank { def1Wait }
            ),
            RadarCheckpointItem(
                number = 2,
                tabLabel = tab2,
                title = title2,
                isOk = isStep2Ok,
                detail = if (isStep2Ok) mtf.setupDetail.ifBlank { def2Ok } else mtf.setupDetail.ifBlank { def2Wait }
            ),
            RadarCheckpointItem(
                number = 3,
                tabLabel = tab3,
                title = title3,
                isOk = isStep3Ok,
                detail = if (isStep3Ok) mtf.triggerDetail.ifBlank { def3Ok } else mtf.triggerDetail.ifBlank { def3Wait }
            ),
            RadarCheckpointItem(
                number = 4,
                tabLabel = tab4,
                title = title4,
                isOk = isStep4Ok,
                detail = if (isStep4Ok) mtf.entryPriceDetail.ifBlank { def4Ok } else mtf.entryPriceDetail.ifBlank { def4Wait }
            )
        )
    }

    // Checkpoint aktif saat ini (checkpoint pertama yang belum OK, atau ke-4 jika sudah semua)
    val activeCheckpointIndex = remember(checkpoints) {
        val idx = checkpoints.indexOfFirst { !it.isOk }
        if (idx >= 0) idx else 3
    }

    // Perhitungan Bid & Ask Flow
    val totalBids = remember(orderBookBids) { orderBookBids.sumOf { it.amount } }
    val totalAsks = remember(orderBookAsks) { orderBookAsks.sumOf { it.amount } }
    val totalVolume = totalBids + totalAsks
    val bidPct = remember(totalBids, totalAsks) {
        if (totalVolume > 0) (totalBids / totalVolume) * 100.0 else 50.0
    }
    val askPct = remember(totalBids, totalAsks) {
        if (totalVolume > 0) (totalAsks / totalVolume) * 100.0 else 50.0
    }
    val ratio = remember(totalBids, totalAsks) {
        if (totalAsks > 0) totalBids / totalAsks else if (totalBids > 0) 9.99 else 1.0
    }
    val ratioSign = if (ratio >= 1.0) ">" else "<"
    val ratioValueStr = String.format(Locale.US, "%.2f", ratio)

    // Penggabungan Stepper (45%) + Kekuatan Sinyal (35%) + Aliran Bid/Ask (20%) menjadi Persentase Dinamis Sampai Entri
    val dynamicEntryProgress = remember(completed, confidence, bidPct) {
        val stepWeight = (completed.coerceIn(0, 4) / 4.0) * 45.0
        val confWeight = (confidence.coerceIn(0, 100) / 100.0) * 35.0
        val bidWeight = (bidPct.coerceIn(0.0, 100.0) / 100.0) * 20.0
        val combined = (stepWeight + confWeight + bidWeight).roundToInt()
        if (completed == 4 && confidence >= 70) {
            combined.coerceAtLeast(85).coerceIn(0, 100)
        } else {
            combined.coerceIn(0, 100)
        }
    }

    val animProgress by animateFloatAsState(
        targetValue = (dynamicEntryProgress / 100f).coerceIn(0f, 1f),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "dynamic_entry_progress"
    )

    val progressColor = when {
        dynamicEntryProgress >= 75 -> TvGreen
        dynamicEntryProgress >= 45 -> TvBlue
        else -> TvAmber
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Header Baris Kesiapan Entri
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Kesiapan Entri",
                color = TvTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "$dynamicEntryProgress%",
                color = progressColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black
            )
        }

        // 2. Loading Bar Dinamis (Penggabungan Stepper + Sinyal + Aliran Bid/Ask)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TvSurfaceVariant)
        ) {
            // Fill Bar dengan Gradient Halus & Dinamis
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animProgress)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            dynamicEntryProgress >= 75 -> Brush.horizontalGradient(
                                listOf(Color(0xFF00B0FF), Color(0xFF00E676), TvGreen)
                            )
                            dynamicEntryProgress >= 45 -> Brush.horizontalGradient(
                                listOf(TvBlue.copy(alpha = 0.8f), Color(0xFF00E5FF))
                            )
                            else -> Brush.horizontalGradient(
                                listOf(TvAmber.copy(alpha = 0.8f), TvBlue.copy(alpha = 0.8f))
                            )
                        }
                    )
            )

            // Garis Pembatas Checkpoint (25%, 50%, 75%)
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
                            .background(TvBackground.copy(alpha = 0.6f))
                    )
                }
            }
        }

        // 3. Persentase Aliran Bid & Ask di Bawah Loading Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Bid ${String.format(Locale.US, "%.1f", bidPct)}%",
                    color = TvGreen,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "vs",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = "Ask ${String.format(Locale.US, "%.1f", askPct)}%",
                    color = TvRed,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Ratio: (> atau < X.X.x)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (ratio >= 1.0) TvGreen.copy(alpha = 0.12f) else TvRed.copy(alpha = 0.12f)
                    )
                    .border(
                        0.5.dp,
                        if (ratio >= 1.0) TvGreen.copy(alpha = 0.35f) else TvRed.copy(alpha = 0.35f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "($ratioSign ${ratioValueStr}x)",
                    color = if (ratio >= 1.0) TvGreen else TvRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 4. Box Keterangan di Bawah Loading Bar
        AnimatedContent(
            targetState = activeCheckpointIndex,
            transitionSpec = {
                (slideInVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { height -> height / 3 } + fadeIn(animationSpec = tween(250)))
                    .togetherWith(slideOutVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) { height -> -height / 3 } + fadeOut(animationSpec = tween(200)))
            },
            label = "checkpoint_detail_transition"
        ) { targetIdx ->
            val currentItem = checkpoints[targetIdx]
            val isCurrentScanning = !currentItem.isOk

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TvSurface)
                    .border(
                        1.dp,
                        when {
                            currentItem.isOk -> TvGreen.copy(alpha = 0.35f)
                            isCurrentScanning -> TvBlue.copy(alpha = 0.35f)
                            else -> TvBorder
                        },
                        RoundedCornerShape(10.dp)
                    )
                    .padding(10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Header Status dalam Box Keterangan
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when {
                                            currentItem.isOk -> TvGreen.copy(alpha = 0.15f)
                                            isCurrentScanning -> TvBlue.copy(alpha = 0.15f)
                                            else -> TvSurfaceVariant
                                        }
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "STEP ${currentItem.number}/4",
                                    color = when {
                                        currentItem.isOk -> TvGreen
                                        isCurrentScanning -> TvBlue
                                        else -> TvTextSecondary
                                    },
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.4.sp
                                )
                            }

                            Text(
                                text = currentItem.title,
                                color = TvTextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Status Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        currentItem.isOk -> TvGreen.copy(alpha = 0.15f)
                                        isCurrentScanning -> TvBlue.copy(alpha = 0.15f)
                                        else -> TvSurfaceVariant
                                    }
                                )
                                .border(
                                    0.5.dp,
                                    when {
                                        currentItem.isOk -> TvGreen.copy(alpha = 0.4f)
                                        isCurrentScanning -> TvBlue.copy(alpha = 0.4f)
                                        else -> TvBorder
                                    },
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (currentItem.isOk) "SIAP" else "MENUNGGU",
                                color = if (currentItem.isOk) TvGreen else TvBlue,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Teks Keterangan Lengkap & Informatif
                    Text(
                        text = currentItem.detail,
                        color = if (currentItem.isOk) TvTextPrimary else TvTextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    // Indikator 4 Checkpoint Mini
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        checkpoints.forEach { cp ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (cp.isOk) TvGreen.copy(alpha = 0.10f) else TvSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .padding(vertical = 3.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(if (cp.isOk) TvGreen else TvTextSecondary.copy(alpha = 0.4f))
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = cp.tabLabel,
                                    color = if (cp.isOk) TvGreen else TvTextSecondary,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
