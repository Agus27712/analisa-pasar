package agu.analys.ui.components.detail.scalp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.scalping.PaperAutoTradeConfig
import agu.analys.engine.scalping.PaperExitSnapshot
import agu.analys.engine.scalping.ScalpExitParams
import agu.analys.model.ScalpingStage
import agu.analys.ui.components.detail.AnalysisCard
import agu.analys.ui.components.detail.AnalysisDivider
import agu.analys.ui.components.detail.SectionTitle
import agu.analys.ui.components.simulation.PaperExitFsmCard
import agu.analys.ui.theme.*
import java.util.Locale

/**
 * **PAPER TRADE DESK** — panel auto buy/sell simulasi untuk halaman detail mode scalping.
 *
 * Panel ini **hanya menyentuh akun simulasi**. Tidak ada tombol di sini yang bisa mengirim
 * order uang riil; larangan itu masih berlaku selama gerbang di `SCALP_EXIT.md` belum lolos.
 *
 * Semua angka posisi berasal dari [PaperExitSnapshot] — sumber yang sama dengan
 * `PaperExitFsmCard` dan `PaperExitStateMachine`, sehingga tidak mungkin menampilkan nilai
 * yang berbeda dari yang dipakai engine untuk memutuskan exit.
 */
@Composable
fun PaperTradeDeskCard(
    snapshot: PaperExitSnapshot?,
    config: PaperAutoTradeConfig,
    exitParams: ScalpExitParams,
    quoteAsset: String,
    canAutoBuy: Boolean,
    onConfigChange: (PaperAutoTradeConfig) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AnalysisCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("PAPER TRADE DESK", Icons.Default.SmartToy)
                Spacer(Modifier.weight(1f))
                MiniChip(
                    text = "SIMULASI",
                    color = TvCyan
                )
            }

            Spacer(Modifier.height(8.dp))

            if (snapshot == null) {
                Text(
                    "Belum ada posisi simulasi untuk pair ini.",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            } else {
                Text(
                    "Posisi aktif · ${snapshot.phase.label} · R ${fmt2(snapshot.currentR)} · " +
                        "net ${fmt2(snapshot.netProfitPct)}% · ${snapshot.barsHeld} bar M1",
                    color = TvTextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 16.sp
                )
            }

            if (config.autoBuyEnabled) {
                Spacer(Modifier.height(5.dp))
                Text(
                    if (canAutoBuy) {
                        "Sinyal saat ini lolos ambang stage — auto-buy akan membuka posisi simulasi."
                    } else {
                        "Auto-buy menunggu sinyal yang lolos ambang stage minimum."
                    },
                    color = if (canAutoBuy) TvGreen else TvTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }

            Spacer(Modifier.height(10.dp))
            AnalysisDivider()
            Spacer(Modifier.height(8.dp))

            // ── Kontrol ─────────────────────────────────────────────────────
            ToggleRow(
                label = "Auto-buy paper",
                description = "Buka posisi simulasi otomatis saat sinyal scalp lolos ambang.",
                checked = config.autoBuyEnabled,
                onCheckedChange = { onConfigChange(config.copy(autoBuyEnabled = it)) }
            )
            ToggleRow(
                label = "Auto-exit paper",
                description = "Engine P2 yang memegang exit (SL / TP / trailing / time-stop).",
                checked = config.autoExitEnabled,
                onCheckedChange = { onConfigChange(config.copy(autoExitEnabled = it)) }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Stage minimum auto-buy",
                color = TvTextSecondary,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ScalpingStage.entries.forEach { stage ->
                    StageChip(
                        label = stage.displayName,
                        selected = config.minStage == stage,
                        onClick = { onConfigChange(config.copy(minStage = stage)) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Alokasi: ${config.allocation.label} · " +
                    if (config.allocation.key == "risk_based") {
                        "risiko ${fmt2(config.riskPctPerTrade)}% per trade"
                    } else {
                        "nominal tetap per posisi"
                    },
                color = TvTextMuted,
                fontSize = 10.sp
            )

            Spacer(Modifier.height(10.dp))
            AnalysisDivider()
            Spacer(Modifier.height(8.dp))

            // ── Peringatan keselamatan ──────────────────────────────────────
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = TvAmber,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Hanya simulasi. Gerbang uang riil masih TIDAK LOLOS — auto-execute dana nyata " +
                        "tetap dilarang sampai hasil uji out-of-sample memenuhi syarat SCALP_EXIT.md.",
                    color = TvAmber,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }

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
                    Icons.Default.Tune,
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

        if (snapshot != null) {
            Spacer(Modifier.height(10.dp))
            // Rincian FSM lengkap (stepper fase, tangga level, metrik) — komponen yang sama
            // dengan layar Simulasi, jadi tidak ada presentasi yang terduplikasi.
            PaperExitFsmCard(
                snapshot = snapshot,
                autoTradeConfig = config,
                exitParams = exitParams,
                quoteAsset = quoteAsset,
                onOpenSettings = onOpenSettings
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TvTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(description, color = TvTextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TvGreen,
                checkedTrackColor = TvGreen.copy(alpha = 0.35f)
            )
        )
    }
}

@Composable
private fun StageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) TvGreen.copy(alpha = 0.18f) else TvSurface)
            .border(
                1.dp,
                if (selected) TvGreen else TvBorder,
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (selected) TvGreen else TvTextSecondary,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** Chip kecil berlabel. */
@Composable
private fun MiniChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun fmt2(v: Double): String =
    String.format(Locale.US, "%.2f", if (v.isFinite()) v else 0.0)
