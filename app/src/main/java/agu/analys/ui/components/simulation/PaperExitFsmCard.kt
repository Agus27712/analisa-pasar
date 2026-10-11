package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.scalping.PaperAutoTradeConfig
import agu.analys.engine.scalping.PaperExitPhase
import agu.analys.engine.scalping.PaperExitSnapshot
import agu.analys.engine.scalping.ScalpExitMode
import agu.analys.engine.scalping.ScalpExitParams
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale

/**
 * Indikator FSM exit paper trade (P2).
 *
 * Menampilkan posisi simulasi yang sedang berjalan beserta fase state machine-nya, level SL
 * aktif (yang bisa sudah naik ke BE / trailing), serta R net berjalan. Semua angka berasal
 * dari [PaperExitSnapshot] — mesin yang sama dengan yang dipakai engine untuk memutuskan exit,
 * jadi tidak ada dua sumber kebenaran.
 *
 * Kartu ini **hanya untuk paper trade**. Tidak ada tombol apa pun yang menyentuh uang riil.
 */
@Composable
fun PaperExitFsmCard(
    snapshot: PaperExitSnapshot?,
    autoTradeConfig: PaperAutoTradeConfig,
    exitParams: ScalpExitParams,
    quoteAsset: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showInfo by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header ────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = TvCyan,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "FSM EXIT PAPER TRADE",
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                ModeChip(exitParams.mode)
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Penjelasan FSM exit",
                    tint = TvTextSecondary,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { showInfo = !showInfo }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Tahapan auto-exit posisi SIMULASI. Engine ini hanya menutup posisi paper — " +
                    "tidak pernah mengeksekusi order uang riil.",
                color = TvTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )

            if (showInfo) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TvSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Urutan pemeriksaan tiap tick: SL aktif → TP2 → TP1 partial → arm BE → " +
                                "trailing dari peak → early profit. Hitungan bar M1 dan time-stop hanya " +
                                "maju sekali per menit. Satuan R memakai fee all-in + slippage, sama " +
                                "dengan tools/scalp_replay.py.",
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (snapshot == null) {
                EmptyFsmState(autoTradeConfig = autoTradeConfig, onOpenSettings = onOpenSettings)
            } else {
                FsmPhaseStepper(snapshot = snapshot)
                Spacer(Modifier.height(10.dp))
                FsmLevelLadder(snapshot = snapshot, quoteAsset = quoteAsset)
                Spacer(Modifier.height(10.dp))
                FsmPositionBar(snapshot = snapshot)
                Spacer(Modifier.height(10.dp))
                FsmMetricsRow(snapshot = snapshot)
            }
        }
    }
}

@Composable
private fun ModeChip(mode: ScalpExitMode) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = TvCyan.copy(alpha = 0.18f),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvCyan.copy(alpha = 0.5f))
    ) {
        Text(
            mode.label.uppercase(Locale.US),
            color = TvCyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EmptyFsmState(
    autoTradeConfig: PaperAutoTradeConfig,
    onOpenSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (autoTradeConfig.autoBuyEnabled) {
                "Tidak ada posisi paper terbuka. Auto-buy menunggu sinyal scalp yang lolos ambang " +
                    "stage minimal."
            } else {
                "Auto-buy paper masih nonaktif, jadi belum ada posisi yang dilacak FSM ini."
            },
            color = TvTextSecondary,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
        if (!autoTradeConfig.autoBuyEnabled) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvCyan.copy(alpha = 0.12f))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = TvCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Buka Pengaturan → Engine Scalping",
                    color = TvCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Rangkaian fase FSM; fase yang sudah dilalui ditandai. */
@Composable
private fun FsmPhaseStepper(snapshot: PaperExitSnapshot) {
    val phases = buildList {
        add(PaperExitPhase.HOLDING)
        if (snapshot.mode.usesTp1Partial) add(PaperExitPhase.TP1_PARTIAL)
        if (snapshot.mode.usesBreakeven) add(PaperExitPhase.BREAKEVEN)
        if (snapshot.mode.usesTrailing) add(PaperExitPhase.TRAILING)
    }

    val reached = buildSet {
        add(PaperExitPhase.HOLDING)
        if (snapshot.isPartialDone) add(PaperExitPhase.TP1_PARTIAL)
        if (snapshot.phase == PaperExitPhase.BREAKEVEN || snapshot.phase == PaperExitPhase.TRAILING) {
            add(PaperExitPhase.BREAKEVEN)
        }
        if (snapshot.phase == PaperExitPhase.TRAILING) add(PaperExitPhase.TRAILING)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            phases.forEachIndexed { index, phase ->
                val isActive = snapshot.phase == phase
                val isReached = phase in reached
                val tint = when {
                    isActive -> TvCyan
                    isReached -> TvGreen
                    else -> TvTextMuted
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(64.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 12.dp else 9.dp)
                            .clip(CircleShape)
                            .background(tint)
                            .then(
                                if (isActive) {
                                    Modifier.border(2.dp, TvCyan.copy(alpha = 0.35f), CircleShape)
                                } else Modifier
                            )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        phase.shortLabel(),
                        color = tint,
                        fontSize = 8.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        lineHeight = 10.sp
                    )
                }
                if (index != phases.lastIndex) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(if (isReached) TvGreen.copy(alpha = 0.5f) else TvBorder)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            snapshot.phase.label,
            color = TvCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun PaperExitPhase.shortLabel(): String = when (this) {
    PaperExitPhase.HOLDING -> "HOLDING"
    PaperExitPhase.TP1_PARTIAL -> "TP1 50%"
    PaperExitPhase.BREAKEVEN -> "BE"
    PaperExitPhase.TRAILING -> "TRAIL"
    PaperExitPhase.CLOSED -> "CLOSED"
}

/** Tangga level: entry, SL awal, SL aktif, BE net, peak, TP1, TP2. */
@Composable
private fun FsmLevelLadder(snapshot: PaperExitSnapshot, quoteAsset: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FsmLevelRow(
            "Entry", snapshot.entry, quoteAsset, TvTextSecondary,
            "SL awal", snapshot.initialStopLoss, TvRed
        )
        FsmLevelRow(
            "SL aktif", snapshot.activeStopLoss, quoteAsset,
            if (snapshot.stopHasMoved) TvGreen else TvRed,
            "BE net", snapshot.netBreakevenPrice, TvAmber
        )
        FsmLevelRow(
            "Peak", snapshot.peak, quoteAsset, TvTextSecondary,
            "Harga kini", snapshot.currentPrice, TvTextPrimary
        )
        FsmLevelRow(
            "TP1", snapshot.tp1, quoteAsset, TvTextSecondary,
            "TP2", snapshot.tp2, TvGreen
        )
    }
}

@Composable
private fun FsmLevelRow(
    leftLabel: String,
    leftValue: Double,
    quoteAsset: String,
    leftColor: androidx.compose.ui.graphics.Color,
    rightLabel: String,
    rightValue: Double,
    rightColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(leftLabel, color = TvTextMuted, fontSize = 9.sp, modifier = Modifier.width(56.dp))
        Text(
            PriceFormatter.formatPrice(leftValue, quoteAsset = quoteAsset),
            color = leftColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(rightLabel, color = TvTextMuted, fontSize = 9.sp, modifier = Modifier.width(56.dp))
        Text(
            PriceFormatter.formatPrice(rightValue, quoteAsset = quoteAsset),
            color = rightColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Bar posisi: SL awal di kiri, TP2 di kanan, penanda harga sekarang. */
@Composable
private fun FsmPositionBar(snapshot: PaperExitSnapshot) {
    val lo = snapshot.initialStopLoss
    val hi = snapshot.tp2
    val span = hi - lo
    val fraction = if (span > 0.0) {
        ((snapshot.currentPrice - lo) / span).coerceIn(0.0, 1.0).toFloat()
    } else {
        0.5f
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(TvRed.copy(alpha = 0.55f), TvAmber.copy(alpha = 0.55f), TvGreen.copy(alpha = 0.55f))
                    )
                )
        ) {
            // Garis BE net, bila masih di dalam rentang.
            if (span > 0.0 && snapshot.netBreakevenPrice in lo..hi) {
                val beFraction = ((snapshot.netBreakevenPrice - lo) / span).toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxWidth(beFraction)
                        .fillMaxHeight()
                        .padding(end = 1.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(TvTextPrimary.copy(alpha = 0.7f))
                    )
                }
            }
            // Penanda harga sekarang.
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(TvTextPrimary)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("SL awal", color = TvTextMuted, fontSize = 8.sp)
            Text("garis putih = BE net", color = TvTextMuted, fontSize = 8.sp)
            Text("TP2", color = TvTextMuted, fontSize = 8.sp)
        }
    }
}

/** Metrik berjalan: net %, R, jumlah bar, jarak ke SL. */
@Composable
private fun FsmMetricsRow(snapshot: PaperExitSnapshot) {
    val rColor = if (snapshot.currentR >= 0.0) TvGreen else TvRed
    val netColor = if (snapshot.netProfitPct >= 0.0) TvGreen else TvRed

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TvSurface)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FsmMetric("Net", String.format(Locale.US, "%+.2f%%", snapshot.netProfitPct), netColor, Modifier.weight(1f))
        FsmMetric("R berjalan", String.format(Locale.US, "%+.2fR", snapshot.currentR), rColor, Modifier.weight(1f))
        FsmMetric("Bar M1", "${snapshot.barsHeld}", TvTextPrimary, Modifier.weight(0.8f))
        FsmMetric(
            "Jarak SL",
            String.format(Locale.US, "%.2f%%", snapshot.distanceToStopPct),
            if (snapshot.distanceToStopPct >= 0.0) TvTextSecondary else TvRed,
            Modifier.weight(1f)
        )
        if (snapshot.stopHasMoved) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = "SL sudah digeser",
                tint = TvGreen,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun FsmMetric(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = TvTextMuted, fontSize = 8.sp)
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
